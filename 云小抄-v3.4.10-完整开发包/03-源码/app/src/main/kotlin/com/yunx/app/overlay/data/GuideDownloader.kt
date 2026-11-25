/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.8 本地化下载器：把攻略图真正下载到本机目录，
 * 支持压缩、并发、仅 WiFi、以及「分享链接更新后自动补下新图」。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 攻略图下载器。
 *
 * 和 v1.9.7 的「预取」是两件事：
 *  - 预取只是把图塞进 Coil 的磁盘缓存，系统清缓存就没了，本质仍是「临时」；
 *  - 这里是真的把一个字节一个字节落成文件，写进用户选的目录，并且记在索引里，
 *    下次直接 `file://` / `content://` 读，完全不走网络。
 *
 * 触发点有三处：
 *  1. 进分区时（[enqueue]）；
 *  2. 「我的 → 小抄本地化 → 立即下载」；
 *  3. 每 5 天 / 每次启动的自动检测（[maybeAutoCheck]），发现新图就补下。
 */
class GuideDownloader private constructor(
    context: Context,
    private val repo: MediaRepository,
    private val vault: ImageVault
) {

    private val app = context.applicationContext
    private val prefs = OverlayPrefs(app)
    /** v2.0.3：fid → 分类目录名，下载时按网盘结构建目录 */
    private val folderMap = FolderMap.of(app)

    data class Stats(
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        /** v2.0.1：这批里有多少张是「作者改过、本次覆盖」的，不是新增 */
        val updated: Int = 0,
        val running: Boolean = false,
        /** 因为「仅 WiFi」被挂起：有队列，但当前网络不允许 */
        val waitingWifi: Boolean = false,
        /** v2.5.0：`scan` = 正在列整棵目录树；空 = 普通下载 */
        val stage: String = "",
        val lastMessage: String? = null
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else (done + failed).toFloat() / total

        val scanning: Boolean get() = stage == STAGE_SCAN
    }

    /**
     * v2.0.3：队列里的一项。
     *
     * [placeOnly] = 这张图早就下过了，只是还躺在旧版本的平铺目录里 —— 不重新下载，
     * 后台把它挪进对应的分类目录即可。
     */
    private data class Target(
        val folderId: String,
        val image: PanImage,
        val relDir: List<String>,
        val placeOnly: Boolean = false
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lanes = Semaphore(LANES)

    /** 归位只做本地读写，单独两条通道就行，别占下载名额 */
    private val placeLanes = Semaphore(2)
    private var job: Job? = null

    /** v2.5.0：「一键下载」的扫描阶段单独一条协程，取消时一起停 */
    private var scanJob: Job? = null
    private val gen = AtomicInteger(0)

    @Volatile
    private var checkedThisProcess = false

    // ------------------------------------------------------------------
    // 下载队列
    // ------------------------------------------------------------------

    /**
     * 把一批图排进下载队列。
     *
     * @param folderId 所属目录 id（用于更新检测时清理旧图，可为 null）
     * @param force    true = 已本地化的也重下（「重新下载」按钮用）
     */
    fun enqueue(folderId: String?, images: List<PanImage>, force: Boolean = false) {
        // 用户真正打开过的目录才登记：以后每 5 天只在这些目录里找新图，
        // 没点开过的分区不会因为一次检测就被整区拖下来。
        if (!folderId.isNullOrBlank()) OptInStore(app).add(folderId)
        start(listOf(folderId.orEmpty() to images), force)
    }

    /**
     * 多目录一次排队（v2.0.1，镜像同步用）。
     *
     * 为什么不能循环调 [enqueue]：每次 enqueue 都会 `job?.cancel()`，
     * 后一个目录会把前一个刚排好的队列干掉，最后只有最后一个目录在下载。
     */
    fun enqueueMany(groups: List<Pair<String, List<PanImage>>>) {
        val effective = groups.filter { it.second.isNotEmpty() }
        if (effective.isEmpty()) return
        val optIn = OptInStore(app)
        effective.forEach { (fid, _) -> if (fid.isNotBlank()) optIn.add(fid) }
        start(effective, force = false)
    }

    /**
     * 多目录合并成一条队列。
     *
     * 更新检测可能一次发现好几个分区都有新图，如果逐个 [enqueue]，
     * 后一个会把前一个的队列 cancel 掉 —— 所以合并后再启动。
     */
    private fun start(groups: List<Pair<String, List<PanImage>>>, force: Boolean) {
        val generation = gen.incrementAndGet()
        // 切分区 = 换队：已经下完的都落了盘，取消的只是「还没开始的」，不会白费流量
        job?.cancel()
        scanJob?.cancel()
        launchQueue(groups, force, generation)
    }

    /**
     * v2.5.0：**一键下载全部小抄**。
     *
     * 和「进分区自动下载」的区别只有一点：先把整棵目录树列完，再一次性排队。
     * 列目录本身也要走网盘接口，所以扫描阶段单独给一条状态，让界面能说清
     * 「现在是在找图，不是在下载」——否则用户看到 0/0 会以为卡死了。
     */
    fun downloadAll() {
        if (!vault.enabled()) {
            _stats.value = Stats(lastMessage = "先在「我的 → 小抄本地化」打开图片本地化")
            return
        }
        if (repo.currentSource() != GuideSource.CLOUD) {
            _stats.value = Stats(lastMessage = "一键下载读的是「个人小抄」，先切到个人小抄")
            return
        }
        val generation = gen.incrementAndGet()
        job?.cancel()
        scanJob?.cancel()
        _stats.value = Stats(running = true, stage = STAGE_SCAN, lastMessage = "正在扫描全部分类…")
        scanJob = scope.launch {
            val groups = try {
                repo.walkAll { folders, images, current ->
                    if (gen.get() != generation) return@walkAll
                    _stats.update {
                        it.copy(
                            total = images,
                            lastMessage = "已扫描 $folders 个目录 · 发现 $images 张图" +
                                if (current.isNotBlank()) "（$current）" else ""
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _stats.update {
                    it.copy(running = false, stage = "", lastMessage = e.message ?: "扫描失败")
                }
                return@launch
            }
            if (gen.get() != generation) return@launch
            if (groups.isEmpty()) {
                _stats.update {
                    it.copy(running = false, stage = "", lastMessage = "没找到可下载的图片")
                }
                return@launch
            }
            launchQueue(groups, force = false, generation = generation)
        }
    }

    private fun launchQueue(
        groups: List<Pair<String, List<PanImage>>>,
        force: Boolean,
        generation: Int
    ) {
        if (!vault.enabled() || groups.isEmpty()) return
        // v2.0.2：链接小抄不参与本地化。它连的是别人的分享，fid 不属于自己账号，
        // 存进本地库只会产生「索引里有、个人网盘里永远对不上」的孤儿条目。
        // 界面已经不再对它触发 enqueue，这里再兜一层：后台维护 / 镜像同步误调也拦住。
        if (repo.currentSource() == GuideSource.LINK) {
            _stats.update {
                it.copy(
                    running = false,
                    lastMessage = "链接小抄是在线看图，不下载；要存到手机请切到「个人小抄」"
                )
            }
            return
        }
        val platform = repo.session.value.platform
        val targets = ArrayList<Target>(64)
        var updatedCount = 0
        groups.forEach { (fid, images) ->
            // v1.9.10：先认领一遍本地已有文件（换链接 / 移动文件夹后 id 变了但图没变）
            vault.reconcile(platform, fid, images)
            // v2.0.3：这个目录在本地该叫什么、套几层，全看 FolderMap 里记的网盘树
            val relDir = folderMap.relPath(platform, fid)
            images.forEach { image ->
                // v2.0.1：不再只问「本地有没有」，还要问「本地那份还是不是同一份」。
                // 作者原地换图（文件名不变、字节数变了）在这里被判成 UPDATE，走覆盖下载。
                val verdict = if (force) SyncVerdict.NEW else vault.verdict(platform, image, fid)
                if (verdict.needsDownload) {
                    targets += Target(fid, image, relDir)
                    if (verdict == SyncVerdict.UPDATE) updatedCount++
                } else if (vault.needsPlacement(platform, image, relDir)) {
                    // 图已经在本地，只是目录结构变了（v2.0.3 之前的版本是平铺的）
                    targets += Target(fid, image, relDir, placeOnly = true)
                }
            }
        }
        if (targets.isEmpty()) {
            // 全是认领来的：索引同样要落盘
            vault.flush()
            _stats.update { it.copy(updated = 0, lastMessage = "这些图都已经在本地了") }
            return
        }

        val pending = targets.filter { !it.placeOnly }
        val placeOnly = targets.count { it.placeOnly }

        // 切分区 = 换队：已经下完的都落了盘，取消的只是「还没开始的」，不会白费流量
        job?.cancel()
        // 只剩归位时不需要网络，别让「仅 WiFi」把纯本地整理也拦住
        if (pending.isNotEmpty() && !networkAllowed()) {
            _stats.value = Stats(
                total = pending.size,
                running = false,
                waitingWifi = true,
                lastMessage = "等 WiFi：已排队 ${pending.size} 张" +
                    if (placeOnly > 0) "（另有 $placeOnly 张待归档）" else ""
            )
            return
        }

        // 代数已在 start() / downloadAll() 里递增；旧的 finally 发现代数对不上就不碰状态，
        // 避免「新队列刚开就被旧队列清零」
        _stats.value = Stats(
            total = targets.size,
            running = true,
            updated = updatedCount,
            stage = STAGE_DOWNLOAD
        )
        job = scope.launch {
            // 先让网格把首屏那几张图发出去，再开始抢带宽：
            // 这段延迟是「点开分区立刻看见图」和「整区快速下完」之间的折中。
            delay(START_DELAY_MS)
            val ok = AtomicInteger()
            val bad = AtomicInteger()
            val placed = AtomicInteger()
            try {
                // v2.0.3：先归档。纯本地读写，几百毫秒就能把旧版本平铺的图收进分类目录，
                // 用户在文件管理器里立刻看到正确结构，不用等下载队列跑完。
                if (placeOnly > 0) {
                    coroutineScope {
                        targets.forEach { target ->
                            if (!target.placeOnly || !isActive) return@forEach
                            launch {
                                placeLanes.withPermit {
                                    if (!isActive) return@withPermit
                                    val moved = runCatching {
                                        vault.place(platform, target.image, target.relDir)
                                    }.getOrDefault(false)
                                    if (moved) {
                                        placed.incrementAndGet()
                                        if (gen.get() == generation) {
                                            _stats.update {
                                                it.copy(done = ok.get() + placed.get(), running = true)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    vault.flush()
                }
                coroutineScope {
                    targets.forEach { target ->
                        if (target.placeOnly || !isActive) return@forEach
                        launch {
                            lanes.withPermit {
                                if (!isActive) return@withPermit
                                val success = runCatching {
                                    downloadOne(target.image, target.folderId, target.relDir)
                                }.getOrDefault(false)
                                if (success) ok.incrementAndGet() else bad.incrementAndGet()
                                if (gen.get() == generation) {
                                    _stats.update {
                                        it.copy(
                                            done = ok.get() + placed.get(),
                                            failed = bad.get(),
                                            running = true
                                        )
                                    }
                                }
                                // 每 20 张落一次索引：崩了也不至于全丢
                                if ((ok.get() + bad.get()) % 20 == 0) vault.flush()
                            }
                        }
                    }
                }
            } finally {
                vault.flush()
                // v1.9.10：这一轮如果有「同一张图被不同 id 存了两份」，在这里合并掉；
                // 再把总占用压回用户设的上限以内（没设上限就是空操作）。
                runCatching { vault.dedupeStrict() }
                runCatching { vault.trimToLimit(prefs.imageMaxBytes) }
                // v2.5.0：刚写进去的图可能就在「本地小抄」同一个目录里，
                // 让本地仓库下次重新扫描，否则用户切过去看不到新图
                runCatching { repo.refreshLocalGuide() }
                if (gen.get() == generation) {
                    _stats.update {
                        it.copy(
                            done = ok.get() + placed.get(),
                            failed = bad.get(),
                            running = false,
                            stage = "",
                            lastMessage = summary(ok.get(), bad.get(), placed.get())
                        )
                    }
                }
            }
        }
    }

    /** 下载某个分类（用上次浏览的分类 id） */
    fun downloadCategory(folderId: String) {
        scope.launch {
            val content = runCatching { repo.browse(folderId, force = false) }.getOrNull()
                ?: return@launch
            enqueue(folderId, content.images)
        }
    }

    fun cancel() {
        scanJob?.cancel()
        job?.cancel()
        _stats.update { it.copy(running = false, stage = "", lastMessage = "已暂停") }
    }

    /** 「清空本地图片」：几百个文件的删除不能压在 UI 线程上 */
    fun clearLocal(onDone: (Int) -> Unit = {}) {
        scope.launch {
            scanJob?.cancel()
            job?.cancel()
            val removed = vault.clearAll()
            onDone(removed)
        }
    }

    /** 「立即检测更新」按钮：不等 5 天，直接跑一遍 */
    fun checkNow() {
        if (!vault.enabled()) return
        _stats.update { it.copy(lastMessage = "正在检测更新…") }
        scope.launch { runCatching { checkForUpdates() } }
    }

    /**
     * 启动时的日常维护（v1.9.10）：合并重复文件 + 按上限淘汰。
     *
     * 为什么不在前台等：几百个文件的 stat / 删除是纯 IO，压在启动路径上会让
     * 悬浮窗晚出来半秒。一天最多跑一次，跑完记时间。
     */
    fun maybeMaintain() {
        if (!vault.enabled()) return
        val now = System.currentTimeMillis()
        if (now - prefs.imageLastMaintainAt < MAINTAIN_INTERVAL_MS) return
        prefs.imageLastMaintainAt = now
        scope.launch {
            val keep = runCatching { OptInStore(app).all().toSet() }.getOrDefault(emptySet())
            // 先清掉指向已被删掉文件的索引，否则「实际占用」会一直虚高
            runCatching { vault.purgeMissing() }
            runCatching { vault.dedupeStrict() }
            runCatching { vault.trimToLimit(prefs.imageMaxBytes, keep) }
        }
    }

    /** 一轮结束后的汇总：下载 / 失败 / 归档分开说，别把「归档 20 张」说成「下载 20 张」 */
    private fun summary(done: Int, failed: Int, placed: Int): String {
        val parts = ArrayList<String>(3)
        if (done > 0) parts += "已下载 $done 张到本地"
        if (failed > 0) parts += "失败 $failed 张"
        if (placed > 0) parts += "归档 $placed 张到分类目录"
        return if (parts.isEmpty()) "这些图都已经在本地了" else parts.joinToString("，")
    }

    private suspend fun downloadOne(
        image: PanImage,
        folderId: String,
        relDir: List<String>
    ): Boolean {
        val platform = repo.session.value.platform
        // v2.0.1：KEEP 才算跳过；UPDATE 要继续往下走（覆盖）
        if (vault.verdict(platform, image, folderId) == SyncVerdict.KEEP) return true

        // 覆盖更新必须绕开本地副本，否则取回来的还是那张旧图
        val source = repo.imageSource(image, thumbnail = false, preferLocal = false)
        val origin = fetch(source) ?: return false
        // 给「网盘上那份」留指纹：以后接口一旦给了 md5，就能做到
        // 「文件名没变、大小也没变，但字节真的换了」也发现得了
        val md5 = SyncPlan.md5Of(origin)

        // v2.0.1：开了「无损打包」就不再叠加「有损重编码」。
        // 打包本身已经在省空间，再 decode→缩放→encode 一遍要 50~150 ms/张，
        // 三个下载线程会被这段 CPU 卡住，而且画质还降了一档 —— 与「无损」直接矛盾。
        val packOn = prefs.imagePack && !prefs.lowPerfMode
        val compressed = when {
            packOn -> null
            prefs.imageCompress -> compress(origin)
            else -> null
        }
        val payload = compressed ?: origin
        val extension = if (compressed != null) "jpg" else ImageVault.extensionOf(image.name)
        return vault.save(
            platform = platform,
            image = image,
            folderId = folderId,
            bytes = payload,
            compressed = compressed != null,
            extension = extension,
            // 记网盘声明的原图大小：下次换链接时靠它认领，压缩后也能对上
            originSize = if (image.size > 0L) image.size else origin.size.toLong(),
            originMd5 = md5,
            relDir = relDir
        ) != null
    }

    /** 「空间管理 → 立即去重」：把同名同字节的多余副本合并掉 */
    fun dedupeNow(onDone: (ImageVault.DedupeResult) -> Unit = {}) {
        scope.launch {
            val result = runCatching { vault.dedupeStrict() }
                .getOrDefault(ImageVault.DedupeResult(0, 0))
            onDone(result)
        }
    }

    /** 「空间管理 → 立即清理」：按时间淘汰到上限以内 */
    fun trimNow(onDone: (ImageVault.DedupeResult) -> Unit = {}) {
        scope.launch {
            val limit = prefs.imageMaxBytes
            val keep = runCatching { OptInStore(app).all().toSet() }.getOrDefault(emptySet())
            val result = runCatching { vault.trimToLimit(limit, keep) }
                .getOrDefault(ImageVault.DedupeResult(0, 0))
            onDone(result)
        }
    }

    // ------------------------------------------------------------------
    // 取图与压缩
    // ------------------------------------------------------------------

    private fun fetch(source: ImageSource): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                // 不让服务端 gzip：图片本身已压缩，套一层反而要边下边解，白耗 CPU
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
                source.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            if (conn.responseCode !in 200..299) return null
            val declared = conn.contentLengthLong
            if (declared > MAX_BYTES) return null
            // v2.0.1：不再先写 ByteArrayOutputStream 再 toByteArray()。
            // 那个写法对每张图都要多分配一次、多拷贝一遍（500 KB 的图就是 500 KB 的无谓内存流量，
            // 一百张图叠起来足够在低端机上触发一次 GC）。服务端给了 Content-Length 时直接读满，
            // 没给才退化成按块扩容。
            val exact = declared in 1..MAX_BYTES
            var out = ByteArray(if (exact) declared.toInt() else 64 * 1024)
            var total = 0
            conn.inputStream.use { input ->
                if (exact) {
                    while (total < out.size) {
                        val n = input.read(out, total, out.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    // 少数 CDN 会少给几个字节（连接被提前关掉），按实际读到的截断
                    if (total != out.size) out = out.copyOf(total)
                } else {
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        if (total.toLong() + n > MAX_BYTES) return null
                        if (total + n > out.size) {
                            out = out.copyOf(maxOf(out.size * 2, total + n))
                        }
                        System.arraycopy(chunk, 0, out, total, n)
                        total += n
                    }
                    out = out.copyOf(total)
                }
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 压缩：长边缩到 [OverlayPrefs.imageMaxEdge]，JPEG 重编码。
     *
     * 实现在 [ImageCodec]，和「分享链接导入」共用同一套参数与透明底处理。
     * 返回 null 表示「压了反而更大 / 解不出来」——调用方存原图。
     */
    private fun compress(bytes: ByteArray): ByteArray? =
        ImageCodec.compress(bytes, prefs.imageMaxEdge, prefs.imageQuality)

    // ------------------------------------------------------------------
    // 自动检测分享链接更新
    // ------------------------------------------------------------------

    /**
     * 每 5 天 / 每次启动检测一次。
     *
     * 「每次启动」只在进程内做一次，不会因为反复开关窗口把接口打成刷子。
     */
    fun maybeAutoCheck() {
        if (!vault.enabled() || !prefs.imageAutoCheck) return
        val due = System.currentTimeMillis() - prefs.imageLastCheckAt >= CHECK_INTERVAL_MS
        if (checkedThisProcess && !due) return
        checkedThisProcess = true
        scope.launch { runCatching { checkForUpdates() } }
    }

    /**
     * 立刻检测一遍：重新列一遍分类与目录，和上次记录的文件 id 对比。
     *
     * @return 新发现的图片张数（已自动排进下载队列）
     */
    suspend fun checkForUpdates(): Int = withContext(Dispatchers.IO) {
        if (!vault.enabled()) return@withContext 0
        val session = repo.session.value
        if (!repo.isReady(session)) return@withContext 0
        val platform = session.platform

        // 只检查「用户点开过、并且开过本地化」的目录；这些目录里的图本来就该在本地。
        val watched = OptInStore(app).all()
        if (watched.isEmpty()) {
            _stats.update { it.copy(lastMessage = "还没有开启本地化的分区") }
            return@withContext 0
        }

        var fresh = 0
        var updated = 0
        val groups = ArrayList<Pair<String, List<PanImage>>>()
        watched.forEach { folderId ->
            val content = runCatching { repo.browse(folderId, force = true) }.getOrNull()
                ?: return@forEach
            val images = content.images
            // 「缺哪张补哪张」：不依赖上一次的记录，取消 / 失败 / 中途退出过的都会在这里补回来。
            // v2.0.1：同时判「本地那份还是不是同一份」—— 作者改了图但 id 没变时，
            // 旧逻辑看到 has() 为真就跳过，永远更新不到。
            val split = SyncPlan.split(
                images.map { it.id to vault.verdict(platform, it, folderId) }
            )
            if (split.total > 0) {
                val wanted = images.filter { it.id in split.ids.toHashSet() }
                fresh += split.fresh
                updated += split.updated
                if (wanted.isNotEmpty()) groups += folderId to wanted
            }
            // 网盘里已经删掉的旧图，本地也清掉 —— 不然「更新」只是越堆越多。
            // 列表为空时不清：接口抖动 / 权限过期也可能返回空，不能因为一次空响应把本地图全删了。
            if (images.isNotEmpty()) {
                runCatching { vault.pruneFolder(platform, folderId, images.map { it.id }.toSet()) }
            }
        }
        if (groups.isNotEmpty()) start(groups, force = false)
        prefs.imageLastCheckAt = System.currentTimeMillis()
        _stats.update {
            it.copy(
                    lastMessage = when {
                    fresh > 0 && updated > 0 -> "新增 $fresh 张、更新 $updated 张，正在补下"
                    fresh > 0 -> "检测到 $fresh 张新图，正在补下"
                    updated > 0 -> "作者改了 $updated 张图，正在覆盖"
                    else -> "已是最新（${watched.size} 个分区）"
                }
            )
        }
        fresh
    }

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    private fun networkAllowed(): Boolean {
        if (!prefs.imageWifiOnly) return true
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * 已开启本地化的目录 id 集合。
     *
     * 只记 id、不记内容：判断「有没有新图」直接看本地图库里缺哪张，
     * 比维护一份「上次看到过什么」的名单更不容易出错（取消、失败、中断都能自愈）。
     */
    private class OptInStore(context: Context) {

        private val file = File(context.applicationContext.filesDir, FILE_NAME)
        private val ids: MutableSet<String> = runCatching {
            if (!file.exists()) mutableSetOf()
            else {
                val arr = JSONArray(file.readText())
                val set = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(set::add)
                }
                set
            }
        }.getOrElse { mutableSetOf<String>() }

        fun all(): List<String> = ids.toList()

        fun add(folderId: String) {
            if (ids.add(folderId)) {
                // 换一次链接就换一批 fid，旧目录永远拉不到东西。留最近 40 个就够，
                // 不然用一年之后每次「检测更新」都要挨个请求几十个失效目录。
                while (ids.size > MAX_FOLDERS) {
                    val first = ids.firstOrNull() ?: break
                    ids.remove(first)
                }
                flush()
            }
        }

        fun flush() {
            runCatching {
                val arr = JSONArray()
                ids.forEach { arr.put(it) }
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(arr.toString())
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        }

        private companion object {
            const val FILE_NAME = "local_opted.json"

            /** 最多记这么多个分区；换链接后旧 fid 会被自然挤掉 */
            const val MAX_FOLDERS = 40
        }
    }

    companion object {
        /** v2.5.0：一键下载的两个阶段，UI 用它区分「在找图」和「在下图」 */
        const val STAGE_SCAN = "scan"
        const val STAGE_DOWNLOAD = "download"

        /**
         * 并发下载路数（v2.0.1：4 → 3）。
         *
         * 夸克的直链是「一次签名、限时有效」的，几十张小图同时开线程时，瞬时并发
         * 会让部分请求在 CDN 侧被判定成异常流量，表现为间歇性 403 / 连接重置 ——
         * 重试一遍能过，但整体反而更慢。3 条是「还吃得满带宽」和「不触发风控」之间
         * 的折中：手机上单张图 200~400 KB，3 路已经能把家用 WiFi 跑满。
         */
        private const val LANES = 3

        /** 单张上限：正常的攻略图远小于这个数，超了多半是下到了 HTML 错误页 */
        private const val MAX_BYTES = 40L * 1024 * 1024

        /** 进入分区后先给网格 0.5 秒取首屏，再开始大批下载 */
        private const val START_DELAY_MS = 500L

        /** 5 天 */
        const val CHECK_INTERVAL_MS = 5L * 24 * 60 * 60 * 1000

        /** 自动维护（去重 + 限额）：一天一次就够 */
        private const val MAINTAIN_INTERVAL_MS = 24L * 60 * 60 * 1000

        @Volatile
        private var instance: GuideDownloader? = null

        fun of(context: Context): GuideDownloader = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                GuideDownloader(
                    context = app,
                    repo = GuideRepo.repository(app),
                    vault = ImageVault.of(app)
                ).also { instance = it }
            }
        }
    }
}
