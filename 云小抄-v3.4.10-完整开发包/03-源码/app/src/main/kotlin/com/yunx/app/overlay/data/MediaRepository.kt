/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：
 *   1. 把云析的「分享链接解析」改成「个人网盘浏览」。
 *   2. 复用 QuarkApi.listCloudFiles / getDownloadLink、XunleiApi.getFiles / getFileDetail，
 *      未改动云析源码；个人盘与分享链接接口的差异见 docs/02。
 *   3. browse() 同时返回子文件夹与图片，支持无限层级；分类/目录快照落盘，二次打开秒出内容。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.util.LruCache
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkApiException
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 网盘媒体仓库：分类 / 目录内容 → 图片直链。
 *
 * 与云析 `*ResolveRepository` 的区别：
 *  - 云析的入口是「分享链接」，先换 stoken，再拿分享内的文件，最后转存/取链；
 *  - 这里的入口是「已登录账号的个人网盘」，目录 id 直接来自个人盘列表，**没有 stoken、
 *    没有转存、没有清理临时目录**这一整套副作用。
 *
 * 缓存分三层：
 *  1. 内存（ConcurrentHashMap，本次进程内秒回）；
 *  2. 磁盘快照（[SnapshotStore]，重启悬浮窗/杀进程后仍能先出内容）；
 *  3. 网络。
 * 取直链额外按 fid 缓存，避免翻页时反复请求同一个文件。
 */
class MediaRepository(
    private val store: CredentialStore,
    private val prefs: OverlayPrefs,
    private val snapshot: SnapshotStore? = null,
    private val local: LocalGuideRepository? = null,
    /** v1.9.8 本地图库：命中就完全不走网络 */
    private val vault: ImageVault? = null,
    private val quarkApi: QuarkApi = QuarkApi(),
    private val xunleiApi: XunleiApi = XunleiApi(),
    /**
     * v2.0.2：链接小抄要建 [ShareLinkService]。
     *
     * 传 null 不影响个人 / 本地两个来源，只是链接源会报「未初始化」——
     * 这样单元测试构造仓库时不用塞一个假的 Android Context。
     */
    private val appContext: Context? = null
) {

    private data class Cached<T>(val value: T, val expireAt: Long)

    /** v1.9.6 高性能读取模式：开关在「我的 → 性能」里，改了立刻生效（缓存按需扩容在重建时完成） */
    val highPerfEnabled: Boolean get() = prefs.highPerfMode

    // v2.0.2：键从 PanPlatform 改成作用域字符串 —— 链接源的 fid 与个人网盘是两套空间，
    // 共用一层缓存必须分桶（见 SourceScope）
    private val categoryCache = ConcurrentHashMap<String, Cached<List<PanFolder>>>()
    // v1.9.5：目录内容与图片直链改成「有上限的 LRU」。
    // 以前只按 TTL 过期、不设条数，翻几十个目录 / 上千张图后内存只涨不落，
    // 低内存设备很容易被系统判成内存大户。LRU 在超限时自动淘汰最久没用的那条。
    // v1.9.6：高性能模式把上限放大，来回翻页不再反复走网络。
    private val contentCache = LruCache<String, Cached<FolderContent>>(
        if (prefs.highPerfMode) HIGH_PERF_CONTENT_ENTRIES else MAX_CONTENT_ENTRIES
    )
    private val sourceCache = LruCache<String, Cached<ImageSource>>(
        if (prefs.highPerfMode) HIGH_PERF_SOURCE_ENTRIES else MAX_SOURCE_ENTRIES
    )

    /** 根目录（「我的小抄」）的 fid：夸克每次进来都要先从 0 找一次，记住能省一个往返 */
    private val rootIdCache = ConcurrentHashMap<PanPlatform, String>()

    // ------------------------------------------------------------------
    // 链接小抄（v2.0.2）
    // ------------------------------------------------------------------

    /** 已打开的分享会话；换链接 / 切回个人小抄都不主动丢，切回来能省一次握手 */
    @Volatile
    private var linkOpened: ShareLinkService.Opened? = null

    /** [linkOpened] 对应的是哪条链接，用来判断要不要重开 */
    @Volatile
    private var linkOpenedFrom: String? = null

    /** 打开分享是网络请求，并发进来只允许一次（分类 + 目录 + 直链可能同时触发） */
    private val linkGuard = Mutex()

    /**
     * fid → 分享里的文件条目。
     *
     * 为什么不在 [PanImage] 上多存一个 `share_fid_token`：取直链需要的是整个 `ShareFile`
     * （token、大小、甚至平台差异字段），把它们塞进 UI 模型会让「一张图」这个概念的
     * 字段随平台膨胀。分享里的文件总数通常几百条，一个 map 足够；容量上限在
     * [rememberLinkFiles] 里兜。
     */
    private val linkFiles = ConcurrentHashMap<String, ShareFile>()

    /**
     * v2.0.3：网盘目录树记忆。
     *
     * 下载落盘要按「分类 / 子目录」建目录，而下载队列手里只有一个 fid ——
     * 名字从这里查。appContext 为空（单元测试）时直接跳过，各调用点都已 runCatching。
     */
    private val folderMap: FolderMap? get() = appContext?.let { FolderMap.of(it) }

    /** 当前来源的缓存作用域；本地源不走这几层缓存，但返回 `local` 便于统一调用 */
    private fun scope(): String = SourceScope.of(currentSource(), store.session.value.platform)

    /**
     * 来源切换信号。ViewModel 把 `session` 和它 combine 后一起监听：
     * 网盘登录 / 退出会变 session，切来源 / 换本地目录会变 tick，
     * 两种情况都要重新判断「是否就绪」并重刷内容。
     */
    private val _sourceTick = MutableStateFlow(0)
    val sourceTick: StateFlow<Int> = _sourceTick.asStateFlow()

    init {
        local?.setTreeUri(prefs.localTreeUri)
        // 夸克每次响应都可能下发新的 __puus，回写保持会话新鲜（否则取链 412）
        quarkApi.cookieSink = { merged, requestCookie ->
            store.updateQuarkCookie(requestCookie, merged)
        }
        // 迅雷 access_token 过期时由 XunleiApi 内部触发；这里负责换新并落库
        xunleiApi.refreshTokenProvider = { deviceId ->
            val cred = store.xunlei()
            if (cred == null || cred.refreshToken.isBlank()) {
                null
            } else {
                xunleiApi.refreshToken(cred.refreshToken, deviceId, cred.authType)?.also { (access, refresh) ->
                    store.updateXunleiTokens(access, refresh)
                }
            }
        }
    }

    val session get() = store.session

    // ------------------------------------------------------------------
    // 攻略来源（v1.7.0）：网盘 / 本地文件夹
    // ------------------------------------------------------------------

    fun currentSource(): GuideSource = prefs.guideSource

    fun localTreeUri(): String? = prefs.localTreeUri

    fun sourceLabel(): String = when (currentSource()) {
        GuideSource.LOCAL -> "本地小抄"
        // 分享有标题就显示标题：用户加了好几条链接时，光看「链接小抄」分不出是哪份
        GuideSource.LINK -> prefs.linkShareTitle.ifBlank { "链接小抄" }
        GuideSource.CLOUD -> "${store.session.value.platform.label}网盘"
    }

    /**
     * 是否需要网盘登录。
     *
     * 只有个人网盘需要：本地源靠 SAF 授权，链接源靠游客态读分享页 ——
     * 两样都不要求用户登录，这正是「链接小抄」的意义：临时看别人的攻略，
     * 不用先把自己账号交出去。
     */
    fun needsLogin(): Boolean = currentSource() == GuideSource.CLOUD

    fun isReady(session: PanSession): Boolean = when (currentSource()) {
        GuideSource.LOCAL -> !prefs.localTreeUri.isNullOrBlank() && local?.isConfigured() == true
        GuideSource.LINK -> !prefs.linkShare.isNullOrBlank()
        GuideSource.CLOUD -> session.active != null
    }

    fun setSource(source: GuideSource) {
        if (prefs.guideSource == source) return
        prefs.guideSource = source
        clearRuntimeCache()
        _sourceTick.value++
    }

    fun setLocalTreeUri(uri: String?) = setLocalBinding(uri, null)

    /**
     * v2.5.9：切到某个「小抄入口」。
     *
     * 一个入口 = 一棵已授权的树 + 可选的子目录起点。换入口时缓存必须整份清掉，
     * 否则会拿着上一个入口的目录结构去渲染，表现是「点了龙宫，出来还是主城」。
     */
    fun setLocalBinding(uri: String?, rootDocId: String?) {
        prefs.localTreeUri = uri
        prefs.localRootDocId = rootDocId.orEmpty()
        local?.setTree(uri, rootDocId)
        clearRuntimeCache()
        _sourceTick.value++
    }

    /** 当前绑定的子目录起点（空 = 树根） */
    fun currentLocalDocId(): String = prefs.localRootDocId

    /**
     * v2.5.0：一键下载可能正好写进「本地小抄」那个目录。
     *
     * 写完之后本地仓库的内存缓存 / 磁盘快照还是旧的，用户切过去会看不到刚下的图，
     * 表现就是「明明说下载了 200 张，本地还是那几张」。这里让它下次重新扫。
     */
    fun refreshLocalGuide() {
        local?.invalidate()
    }

    /**
     * 换一条分享链接（v2.0.2）。
     *
     * 必须把 [linkOpened] 一起丢掉：链接变了但会话还留着的话，列目录会拿着**上一条**
     * 分享的 stoken 去请求，表现为「链接明明换了，看到的还是旧的那份」。
     *
     * 不在这里做网络校验 —— 校验放在调用方（粘贴后点「打开」时先 open 一次成功再保存），
     * 这样保存动作是纯本地、不会因为地铁里没信号而把用户已经输入的链接清掉。
     */
    fun setLinkShare(link: String?, title: String = "") {
        prefs.linkShare = link
        prefs.linkShareTitle = title
        linkOpened = null
        linkOpenedFrom = null
        linkFiles.clear()
        clearRuntimeCache()
        _sourceTick.value++
    }

    /** 当前链接小抄的分享文本；没设置返回 null */
    fun currentLink(): String? = prefs.linkShare

    /** 链接源是否已经能读（有链接且服务可用） */
    fun linkReady(): Boolean =
        currentSource() == GuideSource.LINK &&
            !prefs.linkShare.isNullOrBlank() &&
            appContext != null

    fun rootFolderName(): String = when (currentSource()) {
        GuideSource.LOCAL -> "本地小抄"
        GuideSource.LINK -> prefs.linkShareTitle.ifBlank { "分享链接" }
        GuideSource.CLOUD -> prefs.rootFolderName
    }

    /** 只清内存与本地目录缓存，不动网盘磁盘快照（切回网盘还能秒出内容） */
    private fun clearRuntimeCache() {
        categoryCache.clear()
        contentCache.evictAll()
        sourceCache.evictAll()
        rootIdCache.clear()
        local?.clearCache()
    }

    /**
     * 「我的小抄」这个根目录在网盘里的 fid（v2.0.1）。
     *
     * 镜像同步要把分享里的文件夹转存到这儿，必须拿到真实 fid。夸克根目录是 "0"，
     * 但真正的「我的小抄」是它下面的一个子文件夹，得先列一次根目录找出来。
     * 解析结果缓存在 [rootIdCache]，正常情况一个进程只问一次。
     */
    suspend fun rootFolderId(): String? = withContext(Dispatchers.IO) {
        // 链接源没有「我的小抄」这个概念：镜像同步只服务于个人网盘
        if (currentSource() != GuideSource.CLOUD) return@withContext null
        val platform = store.session.value.platform
        rootIdCache[platform]?.let { return@withContext it }
        val resolved = runCatching {
            when (platform) {
                PanPlatform.QUARK -> {
                    val cred = store.quark() ?: throw NotLoggedInException(PanPlatform.QUARK)
                    resolveQuarkRoot(cred.cookie)
                }

                PanPlatform.XUNLEI -> {
                    val cred = store.xunlei() ?: throw NotLoggedInException(PanPlatform.XUNLEI)
                    findRootFolder(xunleiList(XUNLEI_ROOT_ID, cred)).fid
                }
            }
        }.getOrNull()
        if (resolved != null) rootIdCache[platform] = resolved
        resolved
    }

    fun lastCategoryId(): String? = prefs.lastCategoryId

    fun rememberCategory(id: String?) {
        prefs.lastCategoryId = id
    }

    fun setPlatform(platform: PanPlatform) {
        if (store.session.value.platform == platform) return
        clearCache()
        store.setActivePlatform(platform)
    }

    fun logout(platform: PanPlatform) {
        store.clear(platform)
        clearCache()
    }

    // ------------------------------------------------------------------
    // 分类：根目录（默认「我的小抄」）下的子文件夹
    // ------------------------------------------------------------------

    suspend fun categories(force: Boolean = false): List<PanFolder> = withContext(Dispatchers.IO) {
        val list = categoriesInner(force)
        // v2.0.3：分类名记下来。下载时只拿得到 fid，本地目录叫什么全靠这张表
        // v2.0.4：真有新目录才建本地骨架 —— 每次都全量 mkdir 的话，
        // 在 SAF 目录下等于每开一次列表几十次 IPC
        if (currentSource() == GuideSource.CLOUD) {
            val platform = store.session.value.platform
            val changed = runCatching { folderMap?.remember(platform, list) }.getOrNull() ?: false
            if (changed) mirrorFolderTree(platform)
        }
        list
    }

    /**
     * v2.0.4：把网盘目录骨架建到本地（只建目录，不下载图）。
     *
     * 一个分类下可能几百张图、也可能一张都没下过。等下载时才建目录的话，
     * 文件管理器里永远只有「下过图的那几个分类」—— 用户要的是打开本地就看到
     * 跟网盘一样的树。所以这里在「第一次见到这个目录」时就把文件夹建出来。
     *
     * 只在 [FolderMap.remember] 返回 true（有新目录 / 改名 / 换父）时调用，
     * 所以正常刷新一次列表不会触发任何 mkdir。
     */
    private fun mirrorFolderTree(platform: PanPlatform) {
        val map = folderMap ?: return
        val vaultRef = vault ?: return
        if (!vaultRef.enabled()) return
        runCatching {
            map.paths(platform).forEach { vaultRef.ensureDir(it) }
        }
    }

    /**
     * 手动同步一次目录骨架。
     *
     * 给设置页那个「立即同步」用 —— 用户刚在网盘里加了一批分类，
     * 不想等下次刷新分类列表。
     *
     * @return 本地确认存在的目录数
     */
    suspend fun syncFolderTree(): Int = withContext(Dispatchers.IO) {
        if (currentSource() != GuideSource.CLOUD) return@withContext 0
        val map = folderMap ?: return@withContext 0
        val vaultRef = vault ?: return@withContext 0
        if (!vaultRef.enabled()) return@withContext 0
        val platform = store.session.value.platform
        val paths = runCatching { map.paths(platform) }.getOrNull().orEmpty()
        paths.count { runCatching { vaultRef.ensureDir(it) }.getOrDefault(false) }
    }

    /** 本地记了多少个网盘目录（含子目录）；设置页拿来跟图片数并列显示 */
    fun knownFolderCount(): Int {
        if (currentSource() != GuideSource.CLOUD) return 0
        return runCatching { folderMap?.count(store.session.value.platform) }.getOrNull() ?: 0
    }

    private suspend fun categoriesInner(force: Boolean): List<PanFolder> = withContext(Dispatchers.IO) {
        if (currentSource() == GuideSource.LOCAL) {
            return@withContext requireLocal().categories()
        }
        val platform = store.session.value.platform
        val now = System.currentTimeMillis()

        // 链接源：不读写磁盘快照。快照的键是 PanPlatform，分享的分类一旦写进去，
        // 切回个人网盘会被当成「我的小抄」的内容先铺出来 —— 那是最难查的一类错位。
        if (currentSource() == GuideSource.LINK) {
            val key = scope()
            if (!force) {
                categoryCache[key]?.takeIf { it.expireAt > now }?.let { return@withContext it.value }
            }
            val list = guard { linkCategories() }
            categoryCache[key] = Cached(list, now + categoryTtl)
            return@withContext list
        }

        val key = scope()
        if (!force) {
            categoryCache[key]?.takeIf { it.expireAt > now }?.let { return@withContext it.value }
            snapshot?.categories(platform)?.let {
                categoryCache[key] = Cached(it, now + categoryTtl)
                return@withContext it
            }
        }

        val list = try {
            guard {
                when (platform) {
                    PanPlatform.QUARK -> quarkCategories()
                    PanPlatform.XUNLEI -> xunleiCategories()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // v2.0.4：断网 + 快照也丢了的时候，用本地记住的目录树兜底。
            // 分类名本来就该存在本地 —— 什么都列不出来，用户会以为小抄被清空了。
            val local = runCatching { folderMap?.topFolders(platform) }.getOrNull()
            if (local.isNullOrEmpty()) throw e
            local
        }
        categoryCache[key] = Cached(list, now + categoryTtl)
        snapshot?.saveCategories(platform, list)
        list
    }

    /**
     * 夸克：`/1/clouddrive/file/sort`（个人网盘）。
     *
     * 和分享接口 `/share/sharepage/detail` 最大的不同：这里返回的 `fid` 就是**账号内的真实文件 id**，
     * 不需要 `share_fid_token`，也不存在「分享已失效」；但每个请求都必须带登录 Cookie。
     */
    private suspend fun quarkCategories(): List<PanFolder> {
        val cred = store.quark() ?: throw NotLoggedInException(PanPlatform.QUARK)
        val cachedRoot = rootIdCache[PanPlatform.QUARK]
        val rootFid: String
        val children: List<ShareFile>

        if (cachedRoot == null) {
            rootFid = resolveQuarkRoot(cred.cookie).also { rootIdCache[PanPlatform.QUARK] = it }
            children = quarkList(rootFid, cred.cookie)
        } else {
            rootFid = cachedRoot
            children = try {
                quarkList(rootFid, cred.cookie)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 记住的 fid 失效（目录被改名/移动）时重新解析一次
                rootIdCache.remove(PanPlatform.QUARK)
                val fresh = resolveQuarkRoot(cred.cookie)
                rootIdCache[PanPlatform.QUARK] = fresh
                quarkList(fresh, cred.cookie)
            }
        }

        return children.asSequence()
            .filter { it.isdir }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.fname })
            .map { PanFolder(id = it.fid, name = it.fname, parentId = rootFid) }
            .toList()
    }

    private suspend fun resolveQuarkRoot(cookie: String): String {
        val rootFiles = quarkList(QUARK_ROOT_FID, cookie)
        return findRootFolder(rootFiles).fid
    }

    /**
     * 迅雷：`/drive/v1/files?parent_id=`（个人网盘）。
     *
     * 分享接口是 `/drive/v1/share` + `pass_code_token`，个人盘换成 Bearer + `X-Device-Id`
     * + `X-Captcha-Token`；根目录的 parent_id 是**空串**而不是 "0"。
     */
    private suspend fun xunleiCategories(): List<PanFolder> {
        val cred = store.xunlei() ?: throw NotLoggedInException(PanPlatform.XUNLEI)
        val rootFid = rootIdCache[PanPlatform.XUNLEI] ?: run {
            val rootFiles = xunleiList(XUNLEI_ROOT_ID, cred)
            val root = findRootFolder(rootFiles)
            rootIdCache[PanPlatform.XUNLEI] = root.fid
            root.fid
        }
        val children = xunleiList(rootFid, cred)
        return children.asSequence()
            .filter { it.isdir }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.fname })
            .map { PanFolder(id = it.fid, name = it.fname, parentId = rootFid) }
            .toList()
    }

    /**
     * 在根目录里找配置的攻略目录。
     *
     * 找不到时**不静默退回根目录**：那会让用户看到一堆无关文件夹，还以为功能坏了。
     * 直接把根目录现有的文件夹名列出来，用户一眼就知道是名字填错了还是目录没建。
     */
    private fun findRootFolder(files: List<ShareFile>): ShareFile {
        val wanted = prefs.rootFolderName
        val folders = files.filter { it.isdir }
        return folders.firstOrNull { it.fname == wanted }
            ?: folders.firstOrNull { it.fname.contains(wanted, ignoreCase = true) }
            ?: run {
                val names = folders.take(8).joinToString("、") { it.fname }.ifBlank { "（没有子文件夹）" }
                throw PanException("未找到「$wanted」。根目录现有：$names\n可在主页修改根目录名称。")
            }
    }

    // ------------------------------------------------------------------
    // 目录内容（子文件夹 + 图片）
    // ------------------------------------------------------------------

    /**
     * 读取某个目录的全部内容。
     *
     * 网络失败时若磁盘里有过这一层，就退回磁盘内容 —— 地铁/电梯里翻攻略不至于一片空白。
     */
    suspend fun browse(folderId: String, force: Boolean = false): FolderContent = withContext(Dispatchers.IO) {
        val content = browseRaw(folderId, force)
        // v1.9.10：目录内容一到手就认领本地已有文件。
        // 换分享链接 / 移动文件夹之后新 id 谁也不认识，但图还是那些图 ——
        // 在这里一次性按「文件名 + 字节数」登记，后面 downloadOne 会整批跳过。
        val vaultRef = vault
        // 链接源不认领：分享里的 fid 不是自己账号的，认领进来的条目在个人网盘里永远对不上，
        // 只会在索引里堆一批「看得见但再也用不到」的孤儿
        if (currentSource() == GuideSource.CLOUD) {
            // v2.0.3：这层里的子目录名也要记 —— 下钻两层之后下载，目录链是「分类/子目录」
            // v2.0.4：见到新子目录就把本地同名文件夹建出来
            val platform = store.session.value.platform
            val changed = runCatching { folderMap?.remember(platform, content.folders) }
                .getOrNull() ?: false
            if (changed) mirrorFolderTree(platform)
        }
        if (currentSource() == GuideSource.CLOUD && vaultRef != null && vaultRef.enabled()) {
            runCatching {
                val reused = vaultRef.reconcile(store.session.value.platform, folderId, content.images)
                // 认领结果要落盘：不然杀进程之后就白认了，下次还得再扫一遍
                if (reused > 0) vaultRef.flush()
            }
        }
        content
    }

    /**
     * v2.5.0：把「个人小抄」整棵目录树走一遍，按目录收集图片。
     *
     * 一键下载用的。以前只有「进哪个分区下哪个分区」，用户得把每个分类都点开一次；
     * 现在一次列完，交给 [GuideDownloader] 合并成一条队列。
     *
     * 三道上限不是防呆，是防「目录里放了几万个文件」时把手机拖死：
     * 分类 300 个、层深 6 层、图片 2 万张 —— 正常的攻略库远达不到。
     *
     * @param onProgress 每列完一个目录回调一次（已扫描目录数、已发现图片数、当前目录名）
     */
    suspend fun walkAll(
        maxFolders: Int = 300,
        maxDepth: Int = 6,
        onProgress: (folders: Int, images: Int, current: String) -> Unit = { _, _, _ -> }
    ): List<Pair<String, List<PanImage>>> = withContext(Dispatchers.IO) {
        if (currentSource() != GuideSource.CLOUD) {
            throw PanException("一键下载读的是「个人小抄」，先切到个人小抄再试")
        }
        if (store.session.value.active == null) {
            throw NotLoggedInException(store.session.value.platform)
        }

        val roots = categories(force = false)
        val out = ArrayList<Pair<String, List<PanImage>>>(roots.size * 2)
        val seen = HashSet<String>(roots.size * 2)
        var imageCount = 0
        var folderCount = 0

        suspend fun visit(folder: PanFolder, depth: Int) {
            if (imageCount >= MAX_WALK_IMAGES || seen.size >= maxFolders) return
            if (!seen.add(folder.id)) return
            val content = runCatching { browse(folder.id, force = false) }.getOrNull() ?: return
            folderCount++
            if (content.images.isNotEmpty()) {
                out += folder.id to content.images
                imageCount += content.images.size
            }
            onProgress(folderCount, imageCount, folder.name)
            if (depth >= maxDepth) return
            content.folders.forEach { child -> visit(child, depth + 1) }
        }
        roots.forEach { root -> visit(root, 0) }
        out
    }

    private suspend fun browseRaw(folderId: String, force: Boolean): FolderContent = withContext(Dispatchers.IO) {
        if (currentSource() == GuideSource.LOCAL) {
            return@withContext requireLocal().browse(folderId)
        }
        val link = currentSource() == GuideSource.LINK
        val platform = store.session.value.platform
        val key = SourceScope.contentKey(currentSource(), platform, folderId)
        val now = System.currentTimeMillis()

        if (!force) {
            contentCache[key]?.takeIf { it.expireAt > now }?.let { return@withContext it.value }
            if (!link) snapshot?.content(platform, folderId)?.let {
                contentCache.put(key, Cached(it, now + contentTtl))
                return@withContext it
            }
        }

        val fallback = if (link) null else snapshot?.content(platform, folderId)
        val content = try {
            guard {
                if (link) linkBrowse(folderId) else when (platform) {
                    PanPlatform.QUARK -> quarkBrowse(folderId)
                    PanPlatform.XUNLEI -> xunleiBrowse(folderId)
                }
            }
        } catch (e: Throwable) {
            if (fallback != null) return@withContext fallback
            throw e
        }

        contentCache.put(key, Cached(content, now + contentTtl))
        if (!link) snapshot?.saveContent(platform, folderId, content)
        content
    }

    /**
     * v1.9.6：**同步**取内存里已有的目录内容，不碰磁盘、不碰网络。
     *
     * 切分类 / 进子目录时先用它把上一帧的内容铺出来（几毫秒内出图），
     * 再在后台决定要不要向网盘刷新。没有缓存返回 null，调用方照常走 [browse]。
     */
    fun peekContent(folderId: String): FolderContent? {
        if (currentSource() == GuideSource.LOCAL) return local?.peekContent(folderId)
        val platform = store.session.value.platform
        return contentCache.get(SourceScope.contentKey(currentSource(), platform, folderId))?.value
    }

    /**
     * v1.9.6：**同步**取网格缩略图的加载地址。
     *
     * 列表接口里已经带好了缩略图 url 和请求头，以前每个格子还要起一个协程去问仓库，
     * 一百格就是一百次主线程↔IO 往返，首帧自然出不来。现在直接返回，Compose 第一帧就能发请求。
     */
    fun thumbSource(image: PanImage): ImageSource? {
        if (currentSource() == GuideSource.LOCAL) {
            // 本地源在授权失效时会抛异常；这里只负责「先拿一个地址」，异常交给异步路径处理
            // v2.5.0：优先给缩略图（512px 缓存），没有才退回原图
            return runCatching { local?.thumbSource(image) }.getOrNull()
        }
        // v1.9.8：本地化过的图直接读文件，连缩略图接口都不用碰
        localCopy(image)?.let { return ImageSource(it) }
        image.thumbUrl?.takeIf { it.isNotBlank() }?.let {
            return ImageSource(it, image.thumbHeaders)
        }
        val platform = store.session.value.platform
        return sourceCache.get(SourceScope.imageKey(currentSource(), platform, image.id, true))?.value
    }

    /**
     * v1.9.8：这张图本地有没有（同步、纯内存）。
     *
     * 有就返回可直接交给 Coil 的 `file://` / `content://`，没有返回 null。
     */
    fun localCopy(image: PanImage): String? {
        // 只有个人网盘才有「本地副本」这回事：链接源从不落盘，本地源本身就是文件
        if (currentSource() != GuideSource.CLOUD) return null
        return runCatching { vault?.uri(store.session.value.platform, image) }.getOrNull()
    }

    /**
     * v1.9.6：预热。
     *
     * Service 一起来就在后台跑：把磁盘快照读进内存 → 拉一次分类 → 把「上次看的分类」
     * 的第一层内容也拉进来。这样用户过几秒点「小抄」时，ViewModel 走的是内存命中，
     * 不再出现「点开先白屏转圈、再一张张刷出来」。
     */
    suspend fun warmUp(): FolderContent? = withContext(Dispatchers.IO) {
        if (!isReady(store.session.value)) return@withContext null
        runCatching { snapshot?.warmUp() }
        val folders = runCatching { categories(force = false) }.getOrNull().orEmpty()
        if (folders.isEmpty()) return@withContext null
        val target = prefs.lastCategoryId?.let { id -> folders.firstOrNull { it.id == id } }
            ?: folders.first()
        runCatching { browse(target.id, force = false) }.getOrNull()
    }

    private val categoryTtl: Long
        get() = if (highPerfEnabled) HIGH_PERF_CATEGORY_TTL_MS else CATEGORY_TTL_MS

    private val contentTtl: Long
        get() = if (highPerfEnabled) HIGH_PERF_CONTENT_TTL_MS else CONTENT_TTL_MS

    private val thumbTtl: Long
        get() = if (highPerfEnabled) HIGH_PERF_THUMB_TTL_MS else THUMB_TTL_MS

    private val sourceTtl: Long
        get() = if (highPerfEnabled) HIGH_PERF_SOURCE_TTL_MS else SOURCE_TTL_MS

    private suspend fun quarkBrowse(folderId: String): FolderContent {
        val cred = store.quark() ?: throw NotLoggedInException(PanPlatform.QUARK)
        return buildContent(folderId, quarkList(folderId, cred.cookie))
    }

    private suspend fun xunleiBrowse(folderId: String): FolderContent {
        val cred = store.xunlei() ?: throw NotLoggedInException(PanPlatform.XUNLEI)
        return buildContent(folderId, xunleiList(folderId, cred))
    }

    private fun buildContent(folderId: String, files: List<ShareFile>): FolderContent {
        val folders = files.asSequence()
            .filter { it.isdir }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.fname })
            .map { PanFolder(id = it.fid, name = it.fname, parentId = folderId) }
            .toList()

        val images = files.asSequence()
            .filter { !it.isdir && PanFiles.isImage(it.fname) }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.fname })
            .map { file ->
                PanImage(
                    id = file.fid,
                    name = file.fname,
                    size = file.fsize,
                    // 列表接口已经按 host 分流好缩略图要带的头，这里原样带走
                    thumbUrl = file.thumbnail?.url,
                    thumbHeaders = file.thumbnail?.headers.orEmpty(),
                    // v2.0.1：接口给了内容指纹就一路带到同步判定，不给则留空
                    md5 = file.md5
                )
            }
            .toList()

        return FolderContent(folders = folders, images = images)
    }

    // ------------------------------------------------------------------
    // 链接小抄（v2.0.2）：别人的分享，只读、不转存、不落盘
    // ------------------------------------------------------------------

    private val linkService: ShareLinkService?
        get() = appContext?.let { ShareLinkService.of(it) }

    /**
     * 打开当前链接（幂等）。
     *
     * 分类、目录、取直链三处都可能同时触发，但 `sharepage/token` 是一次真实网络请求，
     * 并发重复打开既浪费也会让服务端多下发几个 stoken。用 Mutex 收口，第二次进来
     * 直接拿第一次的结果。
     */
    private suspend fun openLink(): ShareLinkService.Opened {
        val link = prefs.linkShare?.trim().orEmpty()
        if (link.isEmpty()) {
            throw PanException("还没设置分享链接：去「链接小抄」页粘贴一条")
        }
        linkOpened?.let { if (linkOpenedFrom == link) return it }
        return linkGuard.withLock {
            linkOpened?.let { if (linkOpenedFrom == link) return@withLock it }
            val service = linkService ?: throw PanException("链接源没初始化，重启一下 App")
            val opened = service.open(link, null).getOrElse {
                throw PanException(it.message ?: "打不开这个链接")
            }
            // 换了链接：上一份的文件表整体作废，否则 fid 空间会混在一起
            if (linkOpenedFrom != link) linkFiles.clear()
            linkOpened = opened
            linkOpenedFrom = link
            opened
        }
    }

    /**
     * 分享根目录下的子文件夹 = 分类。
     *
     * 作者常常把攻略图**直接平铺在分享根目录**（没有子文件夹）。这时要补一个虚拟分类，
     * 否则 `categories()` 为空 → 界面报「还没有子文件夹」，用户会以为链接解析失败。
     */
    private suspend fun linkCategories(): List<PanFolder> {
        val opened = openLink()
        val service = linkService ?: throw PanException("链接源没初始化，重启一下 App")
        val root = service.rootFid(opened.platform)
        val files = service.list(opened, root).getOrElse {
            throw PanException(it.message ?: "读取分享目录失败")
        }
        rememberLinkFiles(files)

        val folders = files.asSequence()
            .filter { it.isdir }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.fname })
            .map { PanFolder(id = it.fid, name = it.fname, parentId = root) }
            .toMutableList()

        if (files.any { !it.isdir && PanFiles.isImage(it.fname) }) {
            folders += PanFolder(id = root, name = "根目录图片", parentId = root)
        }
        return folders
    }

    private suspend fun linkBrowse(folderId: String): FolderContent {
        val opened = openLink()
        val service = linkService ?: throw PanException("链接源没初始化，重启一下 App")
        val files = service.list(opened, folderId).getOrElse {
            throw PanException(it.message ?: "读取分享目录失败")
        }
        rememberLinkFiles(files)
        return buildContent(folderId, files)
    }

    /**
     * 分享里的原图直链。
     *
     * 顺序在 [ShareLinkService.downloadLink] 里：登录态优先 → 游客态 → 转存后取链。
     * 链接小抄刻意允许「登录了就用自己的会话」：夸克游客取链对单文件有大小限制，
     * 而攻略长图偶尔会超过那个阈值；登录只是让取链更稳，不代表要转存 ——
     * 它仍然不往用户网盘写任何东西（`getShareDownloadLinkWithoutSave`）。
     */
    private suspend fun linkOriginalSource(image: PanImage): ImageSource {
        val opened = openLink()
        val service = linkService ?: throw PanException("链接源没初始化，重启一下 App")
        val file = linkFiles[image.id]
            ?: throw PanException("这条分享的内容变了，回主页重新打开一次链接")
        val link = service.downloadLink(opened, file).getOrElse {
            throw PanException(it.message ?: "无法获取图片直链（${image.name}）")
        }
        if (link.downloadUrl.isBlank()) throw PanException("直链为空（${image.name}）")
        return ImageSource(link.downloadUrl, service.headers(opened, link))
    }

    /**
     * 记住 fid → 文件条目，供取直链时还原 `ShareFile`。
     *
     * 上限存在的意义：极端分享（几万张图）会把这张表撑到几十 MB。到顶就整体清空重建，
     * 代价只是下一次取链要重新列一次目录，不会 OOM。
     */
    private fun rememberLinkFiles(files: List<ShareFile>) {
        if (files.isEmpty()) return
        if (linkFiles.size > MAX_LINK_FILES) linkFiles.clear()
        files.forEach { file -> if (file.fid.isNotBlank()) linkFiles[file.fid] = file }
    }

    // ------------------------------------------------------------------
    // 图片地址
    // ------------------------------------------------------------------

    /**
     * 取一张图的加载地址。
     *
     * @param thumbnail true = 网格用的小图（优先用列表里自带的缩略图，省一次取链）；
     *                  false = 大图预览用的原图直链。
     * @param preferLocal v2.0.1：本地已有副本时是否直接返回它。
     *        **覆盖更新时必须传 false** —— 否则拿到的还是本地那张旧图，下完写回去等于没更新。
     */
    suspend fun imageSource(
        image: PanImage,
        thumbnail: Boolean,
        preferLocal: Boolean = true
    ): ImageSource {
        if (currentSource() == GuideSource.LOCAL) {
            return requireLocal().imageSource(image)
        }
        val source = currentSource()
        // v1.9.8：本地已下载的图优先（压缩与否都直接可用，预览也够看）。
        // 链接源没有本地副本，localCopy 内部直接返回 null。
        if (preferLocal) localCopy(image)?.let { return ImageSource(it) }

        val platform = store.session.value.platform
        val key = SourceScope.imageKey(source, platform, image.id, thumbnail)
        val now = System.currentTimeMillis()
        sourceCache[key]?.takeIf { it.expireAt > now }?.let { return it.value }

        val thumb = image.thumbUrl
        if (thumbnail && !thumb.isNullOrBlank()) {
            return ImageSource(thumb, image.thumbHeaders)
                .also { sourceCache.put(key, Cached(it, now + thumbTtl)) }
        }

        val resolved = guard {
            if (source == GuideSource.LINK) {
                linkOriginalSource(image)
            } else {
                when (platform) {
                    PanPlatform.QUARK -> quarkOriginalSource(image)
                    PanPlatform.XUNLEI -> xunleiOriginalSource(image)
                }
            }
        }
        sourceCache.put(key, Cached(resolved, now + sourceTtl))
        return resolved
    }

    /** 预取原图直链（翻页前先把隔壁两张的链接换好，滑过去时只剩下载图片本身） */
    suspend fun prefetch(image: PanImage) {
        // 本地图片不需要「取链」，thumbUrl 已经是可直接解码的 content Uri，预取没有意义
        if (currentSource() == GuideSource.LOCAL) return
        // 已经下载到本地的图不用再换直链
        if (localCopy(image) != null) return
        runCatching { imageSource(image, thumbnail = false) }
    }

    private fun requireLocal(): LocalGuideRepository =
        local ?: throw PanException("本地文件源未初始化，请重启悬浮窗")

    /**
     * 夸克原图：`POST /1/clouddrive/file/download`，body `{"fids":["<fid>"]}`。
     *
     * 取链与下载必须用**同一份 Cookie**：直链签名绑定取链那一刻的会话。
     * 这里取完链再从 store 读一次 Cookie（刷新过的会话已由 cookieSink 回写），
     * 避免把刷新前的旧 Cookie 配给新直链。
     */
    private suspend fun quarkOriginalSource(image: PanImage): ImageSource {
        val cred = store.quark() ?: throw NotLoggedInException(PanPlatform.QUARK)
        val link = quarkWithRetry(cred.cookie) { cookie ->
            quarkApi.getDownloadLink(image.id, cookie)
        } ?: throw PanException("无法获取图片直链（${image.name}）")
        if (link.downloadUrl.isBlank()) throw PanException("直链为空（${image.name}）")

        val cookie = store.quark()?.cookie ?: cred.cookie
        return ImageSource(
            url = link.downloadUrl,
            headers = mapOf(
                "Cookie" to cookie,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        )
    }

    /** 迅雷原图：`GET /drive/v1/files/{id}` → `links["application/octet-stream"].url`，CDN 只认客户端 UA */
    private suspend fun xunleiOriginalSource(image: PanImage): ImageSource {
        val cred = store.xunlei() ?: throw NotLoggedInException(PanPlatform.XUNLEI)
        xunleiApi.cacheUserId(cred.accessToken)
        val link = xunleiApi.getFileDetail(
            fileId = image.id,
            accessToken = cred.accessToken,
            deviceId = cred.deviceId,
            captchaToken = cred.captchaToken
        ) ?: throw PanException("无法获取图片直链（${image.name}）")
        if (link.downloadUrl.isBlank()) throw PanException("直链为空（${image.name}）")
        return ImageSource(
            url = link.downloadUrl,
            headers = mapOf("User-Agent" to XunleiConstants.APP_UA)
        )
    }

    // ------------------------------------------------------------------
    // 底层请求
    // ------------------------------------------------------------------

    private suspend fun quarkList(fid: String, cookie: String): List<ShareFile> =
        quarkWithRetry(cookie) { c -> quarkApi.listCloudFiles(fid, c).orEmpty() }

    /**
     * 夸克请求带一次鉴权重试：`__puus` 过期时列表接口常回 401 / 「未登录」类消息，
     * 先 `/config` 重新下发 `__puus`，成功就重试一次；失败则把原异常抛给 UI。
     */
    private suspend fun <T> quarkWithRetry(cookie: String, block: suspend (String) -> T): T {
        return try {
            block(cookie)
        } catch (e: QuarkApiException) {
            if (!isQuarkAuthError(e)) throw e
            val refreshed = quarkApi.refreshSession(cookie) ?: throw e
            if (refreshed == cookie) throw e
            store.updateQuarkCookie(cookie, refreshed)
            block(refreshed)
        }
    }

    private suspend fun xunleiList(parentId: String, cred: PanCredential.Xunlei): List<ShareFile> {
        // 进程重启后 currentUserId 为空，captcha/init 会拿到降级 token → 列表 401。
        // 先从 JWT 里把 user_id 补回去（XunleiApi.cacheUserId 本身是幂等的）。
        xunleiApi.cacheUserId(cred.accessToken)
        return xunleiApi.getFiles(
            parentId = parentId,
            accessToken = cred.accessToken,
            deviceId = cred.deviceId,
            captchaToken = cred.captchaToken
        ).orEmpty()
    }

    private fun isQuarkAuthError(e: QuarkApiException): Boolean {
        val message = e.message.orEmpty()
        return e.code == 31001 ||
            message.contains("登录") ||
            message.contains("401") ||
            message.contains("unauthenticated", ignoreCase = true) ||
            message.contains("未授权")
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 取消不是业务错误：包装成 PanException 会让 ViewModel 的取消逻辑失效
        throw e
    } catch (e: Throwable) {
        throw translate(e)
    }

    /** 把各层异常收敛成一句人话；保留 cause 便于 Logcat 排查 */
    private fun translate(e: Throwable): PanException = when (e) {
        is PanException -> e
        is QuarkApiException -> {
            val raw = e.message.orEmpty()
            val msg = when {
                raw.isBlank() -> "网盘请求失败，请稍后重试"
                raw.contains("unauthenticated", ignoreCase = true) -> "登录已过期，请重新登录"
                raw.contains("captcha_invalid", ignoreCase = true) -> "验证已过期，请重新登录"
                raw.contains("401") -> "登录已过期，请重新登录"
                else -> raw
            }
            PanException(msg, e)
        }

        else -> PanException(
            e.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试",
            e
        )
    }

    /**
     * 转存完立刻让某个目录的缓存作废（v2.0.1）。
     *
     * 镜像同步刚往这个目录里塞了新图，10 分钟的 TTL 缓存会让下载器看到的还是旧列表，
     * 表现成「同步说补了 5 张，本地一张没多」。
     */
    fun invalidateContent(folderId: String) {
        val platform = store.session.value.platform
        contentCache.remove(SourceScope.contentKey(currentSource(), platform, folderId))
    }

    /** 同上，分类列表（转存了新的整个文件夹时用） */
    fun invalidateCategories() {
        categoryCache.remove(scope())
        // 镜像同步只作用在个人网盘；顺手把链接源的分类也标脏没有意义，
        // 但换链接时 setLinkShare 已经整体清过，这里不需要额外动作
    }

    /**
     * v1.9.8：只清内存缓存，不动磁盘快照。
     *
     * 仓库变成进程级单例后，悬浮窗关闭不再等于「这次浏览结束」——
     * 把快照留在磁盘上，下次开窗还是秒出内容。
     */
    fun trimRuntimeCache() {
        categoryCache.clear()
        contentCache.evictAll()
        sourceCache.evictAll()
        rootIdCache.clear()
    }

    fun clearCache() {
        categoryCache.clear()
        contentCache.evictAll()
        sourceCache.evictAll()
        rootIdCache.clear()
        local?.clearCache()
        snapshot?.clear()
    }

    private companion object {

        /** 链接源文件表上限：超过就整体清空重建，防极端分享把内存顶爆 */
        private const val MAX_LINK_FILES = 4000
        const val QUARK_ROOT_FID = "0"

        /** 迅雷个人网盘根目录：parent_id 传空串 */
        const val XUNLEI_ROOT_ID = ""

        const val CATEGORY_TTL_MS = 10 * 60_000L
        const val CONTENT_TTL_MS = 10 * 60_000L
        const val THUMB_TTL_MS = 30 * 60_000L

        /** 原图直链有效期通常 1~3 小时，保守缓存 10 分钟 */
        const val SOURCE_TTL_MS = 10 * 60_000L

        /** v1.9.6 高性能模式：内存里留久一点，来回翻页 / 切分类完全不出网 */
        const val HIGH_PERF_CATEGORY_TTL_MS = 2 * 60 * 60_000L
        const val HIGH_PERF_CONTENT_TTL_MS = 2 * 60 * 60_000L
        const val HIGH_PERF_THUMB_TTL_MS = 4 * 60 * 60_000L
        const val HIGH_PERF_SOURCE_TTL_MS = 60 * 60_000L

        /** v1.9.5 缓存条数上限：24 个目录内容、480 条图片直链，足够覆盖来回翻页 */
        const val MAX_CONTENT_ENTRIES = 24
        const val MAX_SOURCE_ENTRIES = 480

        /** v1.9.6 高性能模式：128 个目录、2000 条直链 */
        const val HIGH_PERF_CONTENT_ENTRIES = 128
        const val HIGH_PERF_SOURCE_ENTRIES = 2000

        /** v2.5.0 一键下载：整棵树最多收这么多张，超出的等下一轮「检测更新」补 */
        const val MAX_WALK_IMAGES = 20_000
    }
}
