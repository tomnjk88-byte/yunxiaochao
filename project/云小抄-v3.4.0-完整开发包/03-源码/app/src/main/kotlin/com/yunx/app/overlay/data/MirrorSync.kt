/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.1）。镜像同步：读作者分享 → 只把缺的转存进「我的小抄」
 * → 再从自己的账号拉直链下到本地。三步都走自己的账号，不碰作者的流量，也不依赖
 * 游客接口的 50MB 限制。
 */

package com.yunx.app.overlay.data

import android.content.Context
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.SharePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 镜像同步引擎。
 *
 * ## 为什么要有「转存」这一步
 *
 * 夸克的直链只能从**自己账号里的 fid** 取。作者的分享里那张图虽然有 fid，但那是
 * 「分享会话里的 fid」，拿它去 `file/download` 会 404 —— 必须先 `sharepage/save`
 * 把它复制一份进自己的网盘，拿返回的新 fid 才能取链。
 *
 * 所以「作者更新了 → 我手机上也有新的」这条链路一定是三段：
 *
 * ```
 * 嗅探（列分享目录） → 转存（把缺的复制进「我的小抄」） → 本地更新（列自己目录 + 下载）
 * ```
 *
 * 前两段在这里，第三段交给 [GuideDownloader]（它本来就在做「缺哪张补哪张」）。
 *
 * ## 三条安全边界
 *
 * 1. **不碰作者的分享**：只读，所有写操作都落在自己账号。
 * 2. **能不动就不动**：目标目录里已经有同名的，默认跳过；只有用户明确打开
 *    「作者改图时替换」才会先删自己那份再转存。
 * 3. **一轮最多替换 [MAX_REPLACE_PER_RUN] 张**：万一比对逻辑出错（比如接口把
 *    size 返回成 0），也不会一口气把几百张图从网盘里删掉。
 */
class MirrorSync private constructor(
    context: Context,
    private val repo: MediaRepository,
    private val downloader: GuideDownloader,
    private val store: MirrorStore
) {

    private val app = context.applicationContext
    private val shareService = ShareLinkService.of(app)
    private val quarkApi = QuarkApi()
    private val prefs = OverlayPrefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一轮同步的结果，直接拿来拼文案 */
    data class Report(
        /** 参与同步的分类数 */
        val folders: Int = 0,
        /** 整夹新转存的分类数 */
        val foldersAdded: Int = 0,
        /** 补转存的单张图 */
        val filesAdded: Int = 0,
        /** 作者改过、这次替换掉的 */
        val filesReplaced: Int = 0,
        /** 作者改过、但用户没开替换，只在结果里报出来 */
        val filesChanged: Int = 0,
        /** 已经一致、跳过 */
        val skipped: Int = 0,
        /** 改过的文件名（最多列 6 个，给用户核对） */
        val changedNames: List<String> = emptyList(),
        /** 本轮排进本地下载队列的张数 */
        val queued: Int = 0,
        val seconds: Int = 0,
        val failed: Int = 0
    ) {
        val message: String
            get() {
                if (folders == 0) return "分享里没有可同步的分类"
                val parts = ArrayList<String>(4)
                if (foldersAdded > 0) parts += "新增 $foldersAdded 个分类"
                if (filesAdded > 0) parts += "补转存 $filesAdded 张"
                if (filesReplaced > 0) parts += "替换 $filesReplaced 张"
                if (filesChanged > 0 && filesReplaced == 0) parts += "$filesChanged 张作者改过（未替换）"
                if (failed > 0) parts += "$failed 个失败"
                if (parts.isEmpty()) return "已是最新（$folders 个分类，$skipped 张）"
                val head = parts.joinToString("，")
                return if (queued > 0) "$head；已排队下载 $queued 张" else head
            }
    }

    /** 同步进度：悬浮窗里只显示一行，所以只留一句话 */
    data class Progress(
        val running: Boolean = false,
        val step: String = "",
        val sourceId: String = ""
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * 同步一个源。
     *
     * @param replaceChanged 作者改过图时，是否先删掉自己网盘里的旧版本再转存新版本。
     *        默认开（用户要的就是「大小变了就覆盖」），但每轮有上限。
     */
    suspend fun sync(source: MirrorSource, replaceChanged: Boolean = true): Result<Report> {
        if (_progress.value.running) {
            return Result.failure(PanException("上一轮同步还没结束"))
        }
        _progress.value = Progress(running = true, step = "准备中…", sourceId = source.id)
        return try {
            val result = withContext(Dispatchers.IO) {
                withTimeoutOrNull(SYNC_TIMEOUT_MS) { runSync(source, replaceChanged) }
                    ?: Result.failure(PanException("同步超时，检查网络后重试"))
            }
            result.fold(
                onSuccess = { store.markSynced(source.id, it.message) },
                onFailure = { store.markFailed(source.id, it.message ?: "同步失败") }
            )
            result
        } finally {
            _progress.value = Progress()
        }
    }

    /**
     * 启动时的自动检查（24 小时节流）。
     *
     * 为什么不每次启动都跑：一次同步会转存、删旧文件、重排下载队列，
     * 是「有副作用」的操作，不该因为用户开关了几次悬浮窗就反复触发。
     */
    fun maybeAutoSync() {
        if (!prefs.mirrorAuto) return
        val sources = store.all()
        if (sources.isEmpty()) return
        val now = System.currentTimeMillis()
        val interval = prefs.mirrorIntervalHours * 60L * 60_000L
        if (now - prefs.mirrorLastRunAt < interval) return
        prefs.mirrorLastRunAt = now
        scope.launch {
            sources.forEach { source ->
                // 单源失败不影响后面的源；结果已经由 sync 自己写回 MirrorStore
                runCatching { sync(source, prefs.mirrorReplace) }
            }
        }
    }

    private suspend fun runSync(source: MirrorSource, replaceChanged: Boolean): Result<Report> {
        val started = System.currentTimeMillis()
        step("正在打开分享…")
        val opened = shareService.open(source.link, source.pwd.takeIf { it.isNotBlank() })
            .getOrElse { return Result.failure(PanException(it.message ?: "分享打不开")) }

        if (opened.platform != SharePlatform.QUARK) {
            return Result.failure(PanException("自动转存目前只支持夸克分享"))
        }
        if (opened.guest || opened.cookie.isBlank()) {
            return Result.failure(PanException("自动转存要登录夸克账号（游客只能看，不能存）"))
        }

        step("正在读「我的小抄」…")
        val myRoot = repo.rootFolderId()
            ?: return Result.failure(
                PanException("找不到「${repo.rootFolderName()}」，先在主页设置好根目录名")
            )
        val myFolders = runCatching { repo.categories(force = true) }.getOrElse {
            return Result.failure(PanException(it.message ?: "读取自己网盘失败"))
        }

        // ---- 分享这一侧 ------------------------------------------------------
        val shareRoot = shareService.rootFid(opened.platform)
        step("正在读分享目录…")
        val rootFiles = shareService.list(opened, shareRoot)
            .getOrElse { return Result.failure(PanException(it.message ?: "读取分享目录失败")) }

        val subFolders = rootFiles.filter { it.isdir }
        // 分享直接指向一堆图（没有子文件夹）时，把它当成分页目录整体同步；
        // 标题取分享标题，落到「我的小抄」下就是同名文件夹。
        val groups: List<Group> = if (subFolders.isEmpty()) {
            val images = rootFiles.filter { !it.isdir && PanFiles.isImage(it.fname) }
            if (images.isEmpty()) return Result.success(Report())
            listOf(
                Group(
                    name = opened.session.title.ifBlank { "分享图片" },
                    fid = shareRoot,
                    token = "",
                    files = images
                )
            )
        } else {
            subFolders.map { Group(name = it.fname, fid = it.fid, token = it.fidToken, files = null) }
        }

        val selected = if (source.folderNames.isEmpty()) {
            groups
        } else {
            groups.filter { g -> source.folderNames.any { it == g.name } }
        }
        if (selected.isEmpty()) {
            return Result.failure(
                PanException("分享里没有「${source.folderNames.joinToString("、")}」这些分类")
            )
        }

        // ---- 逐分类比对 ------------------------------------------------------
        var foldersAdded = 0
        var filesAdded = 0
        var filesReplaced = 0
        var filesChanged = 0
        var skipped = 0
        var failed = 0
        val changedNames = ArrayList<String>(8)
        val touchedFolderIds = LinkedHashSet<String>()

        val lanes = Semaphore(TRANSFER_LANES)

        for (group in selected) {
            step("同步「${group.name}」…")
            val mine = myFolders.firstOrNull { it.name == group.name }

            if (mine == null) {
                // 整个文件夹还没转存过：一次请求把夹子连同里面所有图搬过来
                val ok = transfer(
                    opened = opened,
                    fid = group.fid,
                    pdirFid = shareRoot,
                    fidToken = group.token,
                    toDir = myRoot
                )
                if (ok) {
                    foldersAdded++
                    repo.invalidateCategories()
                } else {
                    failed++
                }
                continue
            }

            val shareFiles = group.files ?: runCatching { shareService.list(opened, group.fid) }
                .getOrNull()?.getOrNull()
                ?.filter { !it.isdir && PanFiles.isImage(it.fname) }
                .orEmpty()
            if (shareFiles.isEmpty()) continue

            // 自己网盘这一侧要拿最新列表：browse(force) 会跳过内存与快照缓存
            val mineImages = runCatching { repo.browse(mine.id, force = true) }
                .getOrNull()?.images.orEmpty()
            // 同名可能不止一份（历史上转存过两次），取字节数最大的那份当基准：
            // 它是「没被二次转存成副本、最接近原图」的那个
            val byName = HashMap<String, PanImage>(mineImages.size)
            mineImages.forEach { img ->
                val old = byName[img.name]
                if (old == null || img.size > old.size) byName[img.name] = img
            }

            val toTransfer = ArrayList<ShareFile>(8)
            shareFiles.forEach { file ->
                val existing = byName[file.fname]
                when {
                    existing == null -> toTransfer += file
                    // 作者改过：文件名没变，字节数变了。这是本功能最核心的一条判据
                    existing.size > 0L && file.fsize > 0L && existing.size != file.fsize -> {
                        filesChanged++
                        if (changedNames.size < 6) changedNames += file.fname
                        if (replaceChanged && filesReplaced < MAX_REPLACE_PER_RUN) {
                            // 先删自己那份、再转存新的：夸克对「同名文件转存到同一目录」
                            // 的行为不稳定（有时返回已删除的旧 fid），先删最干净。
                            // 删的是自己账号里转存来的副本，作者的原分享不受影响。
                            val deleted = runCatching {
                                quarkApi.deleteFile(existing.id, opened.cookie)
                            }.isSuccess
                            if (deleted) {
                                toTransfer += file
                                filesReplaced++
                            } else {
                                failed++
                            }
                        }
                    }

                    else -> skipped++
                }
            }

            if (toTransfer.isEmpty()) continue

            // 转存本身是「一个文件一次请求 + 轮询任务」，并发 2 条已经能压到
            // 「50 张 ≈ 半分钟」，再高容易触发风控
            val results = java.util.Collections.synchronizedList(ArrayList<Boolean>())
            coroutineScope {
                toTransfer.forEach { file ->
                    launch {
                        lanes.withPermit {
                            val ok = transfer(
                                opened = opened,
                                fid = file.fid,
                                pdirFid = file.pdirFid.ifBlank { group.fid },
                                fidToken = file.fidToken,
                                toDir = mine.id
                            )
                            results += ok
                            if (!ok) failed++
                        }
                    }
                }
            }
            val added = results.count { it }
            filesAdded += added
            if (added > 0) {
                touchedFolderIds += mine.id
                repo.invalidateContent(mine.id)
            }
        }

        // ---- 第三步：本地补下 ------------------------------------------------
        step("正在排下载队列…")
        var queued = 0
        if (foldersAdded > 0) repo.invalidateCategories()
        val fresh = runCatching { repo.categories(force = true) }.getOrDefault(myFolders)
        val targets = ArrayList<Pair<String, List<PanImage>>>()
        fresh.filter { it.name in selected.map { g -> g.name } }.forEach { folder ->
            val content = runCatching { repo.browse(folder.id, force = true) }.getOrNull()
                ?: return@forEach
            val platform = repo.session.value.platform
            val missing = content.images.filter {
                vault().verdict(platform, it, folder.id).needsDownload
            }
            if (missing.isNotEmpty()) {
                queued += missing.size
                targets += folder.id to missing
            }
        }
        if (targets.isNotEmpty()) downloader.enqueueMany(targets)

        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
        return Result.success(
            Report(
                folders = selected.size,
                foldersAdded = foldersAdded,
                filesAdded = filesAdded,
                filesReplaced = filesReplaced,
                filesChanged = filesChanged,
                skipped = skipped,
                changedNames = changedNames,
                queued = queued,
                seconds = seconds,
                failed = failed
            )
        )
    }

    /**
     * 转存一个文件/文件夹到自己网盘，等异步任务完成。
     *
     * `pdirFid` 必须是**分享里的父目录 fid**，`toDir` 是**自己网盘的目标目录 fid** ——
     * 这两个参数在夸克接口里都叫 `pdir`，写反了会返回 400，且错误信息看不出来。
     */
    private suspend fun transfer(
        opened: ShareLinkService.Opened,
        fid: String,
        pdirFid: String,
        fidToken: String,
        toDir: String
    ): Boolean = runCatching {
        val taskId = quarkApi.saveShareFile(
            shareId = opened.session.shareId,
            stoken = opened.session.stoken,
            pdirFid = pdirFid,
            fid = fid,
            fidToken = fidToken,
            toPdirFid = toDir,
            cookie = opened.cookie
        ) ?: return@runCatching false
        quarkApi.pollTask(taskId, opened.cookie) != null
    }.getOrDefault(false)

    private fun step(text: String) {
        _progress.value = _progress.value.copy(step = text)
    }

    private fun vault(): ImageVault = ImageVault.of(app)

    /** 分享里的一个分类 */
    private data class Group(
        val name: String,
        val fid: String,
        val token: String,
        /** 已经列过的文件；null = 还没列（整夹转存时不需要列） */
        val files: List<ShareFile>?
    )

    companion object {
        /** 整轮上限 6 分钟：几十张图的转存 + 轮询，再久就是网络卡死了 */
        private const val SYNC_TIMEOUT_MS = 6 * 60_000L

        /** 转存并发：接口调用不是流量，2 条足够，再多容易被判定成脚本 */
        private const val TRANSFER_LANES = 2

        /** 一轮最多替换多少张：防止判定逻辑出错时把整个网盘清空 */
        private const val MAX_REPLACE_PER_RUN = 20

        @Volatile
        private var instance: MirrorSync? = null

        fun of(context: Context): MirrorSync = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                MirrorSync(
                    context = app,
                    repo = GuideRepo.repository(app),
                    downloader = GuideDownloader.of(app),
                    store = MirrorStore.of(app)
                ).also { instance = it }
            }
        }
    }
}
