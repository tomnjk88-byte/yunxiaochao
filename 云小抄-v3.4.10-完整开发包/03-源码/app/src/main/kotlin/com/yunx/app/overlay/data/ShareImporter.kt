/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.9「读取链接」的下载执行层：
 * 把分享链接里选中的图片取直链 → 下载 → 压缩 → 写进本地图库（ImageVault），
 * 并对外暴露任务级进度，供「下载」页展示。
 */

package com.yunx.app.overlay.data

import android.content.Context
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.SharePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 分享链接里的图片 → 本地小抄。
 *
 * 和 [GuideDownloader] 的区别：那个面向「自己网盘里已经分好类的目录」，
 * 这个面向「别人分享的一条链接」——文件 id 来自分享列表，直链要按分享凭证现取。
 * 两者最终都写进同一个 [ImageVault]，所以网格里看到的效果是一样的。
 */
class ShareImporter private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = OverlayPrefs(app)
    private val vault = ImageVault.of(app)
    private val links = ShareLinkService.of(app)

    enum class State { WAITING, RUNNING, DONE, FAILED }

    data class Task(
        /** `平台:文件id`，同一张图重复导入时用它去重 */
        val id: String,
        val name: String,
        val size: Long,
        val state: State = State.WAITING,
        val done: Long = 0L,
        val error: String? = null
    )

    data class Stats(
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val running: Boolean = false,
        /** 最近 1 秒的下载速度（字节/秒） */
        val speedBps: Long = 0L,
        val lastMessage: String? = null
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else (done + failed).toFloat() / total
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lanes = Semaphore(LANES)
    private val job = SupervisorJob()

    @Volatile
    private var lastSampleAt = 0L

    @Volatile
    private var lastSampleBytes = 0L

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 把分享里选中的文件排进下载队列。
     *
     * 只处理图片（小抄就是图）；文件夹、压缩包会被忽略。
     * 已经在本地的图不重复下 —— 除非用户显式清空过本地图库。
     */
    fun import(
        opened: ShareLinkService.Opened,
        files: List<ShareFile>,
        /** v2.0.3：分享里的面包屑（从根往下），下载时按它复刻目录 */
        relDir: List<String> = emptyList(),
        /**
         * ★ v3.0.0：每张图各自的相对目录。
         *
         * 整夹下载会一次把好几层子目录里的图混在一个批次里，它们不该全落进同一层。
         * 这里按 fid 查各自的目录，没命中的（比如当前层勾选的图）退回 [relDir]。
         */
        relDirByFid: Map<String, List<String>> = emptyMap()
    ) {
        if (files.isEmpty()) return
        if (!vault.enabled()) {
            _stats.update { it.copy(lastMessage = "先去「我的」打开图片本地化") }
            return
        }
        val platform = panPlatformOf(opened.platform)
        val images = files.filter { !it.isdir && PanFiles.isImage(it.fname) }
        if (images.isEmpty()) {
            _stats.update { it.copy(lastMessage = "这个目录里没有图片") }
            return
        }
        val folderId = "share:${opened.session.shareId}"
        // v1.9.10：分享链接重新保存一次，fid 会全变；先按文件名 + 大小认领本地已有文件
        vault.reconcile(platform, folderId, images.map { toPanImage(it, opened.platform) })
        val fresh = images.filter { file -> !vault.has(platform, toPanImage(file, opened.platform)) }
        if (fresh.isEmpty()) {
            _stats.update { it.copy(lastMessage = "选中的图都已经在本地了") }
            return
        }

        val added = fresh.map { file ->
            Task(
                id = taskId(platform, file),
                name = file.fname,
                size = file.fsize
            )
        }
        // 新批次直接覆盖旧记录：下载页的进度条按「当前这一批」算，
        // 混进历史任务会让 total 越滚越大、进度永远到不了 100%。
        // v3.0.0：不再 takeLast 截断 —— 整夹下载一次可能有上千张，截掉尾巴会让
        // 进度条停在 30% 不动（任务在列表里找不到，update 全部落空）。
        _tasks.value = added
        _stats.update { it.copy(running = true, lastMessage = "开始下载 ${added.size} 张") }
        resetSpeedSample()
        publish()

        fresh.forEach { file ->
            scope.launch(job) {
                lanes.withPermit {
                    if (!isActive) return@withPermit
                    runOne(
                        opened = opened,
                        platform = platform,
                        folderId = folderId,
                        file = file,
                        relDir = relDirByFid[file.fid] ?: relDir
                    )
                }
            }
        }
    }

    fun cancel() {
        job.cancelChildren()
        _stats.update { it.copy(running = false, speedBps = 0L, lastMessage = "已暂停") }
        _tasks.update { list -> list.map { if (it.state == State.WAITING || it.state == State.RUNNING) it.copy(state = State.FAILED, error = "已暂停") else it } }
    }

    /** 清空任务列表（不动已经下好的文件） */
    fun clearTasks() {
        _tasks.value = emptyList()
        _stats.value = Stats()
    }

    private suspend fun runOne(
        opened: ShareLinkService.Opened,
        platform: PanPlatform,
        folderId: String,
        file: ShareFile,
        relDir: List<String>
    ) {
        val id = taskId(platform, file)
        update(id) { it.copy(state = State.RUNNING) }
        val image = toPanImage(file, opened.platform)

        val link = links.downloadLink(opened, file).getOrElse { e ->
            fail(id, e.message ?: "取链失败")
            return
        }
        val headers = links.headers(opened, link)
        val bytes = fetch(link.downloadUrl, headers, id) ?: run {
            fail(id, "下载失败")
            return
        }

        val compressed = if (prefs.imageCompress) {
            ImageCodec.compress(bytes, prefs.imageMaxEdge, prefs.imageQuality)
        } else {
            null
        }
        val extension = if (compressed == null) ImageVault.extensionOf(file.fname) else "jpg"
        val saved = vault.save(
            platform = platform,
            image = image,
            folderId = folderId,
            bytes = compressed ?: bytes,
            compressed = compressed != null,
            extension = extension,
            originSize = if (image.size > 0L) image.size else bytes.size.toLong(),
            relDir = relDir
        )
        if (saved == null) {
            fail(id, "写入失败")
            return
        }
        update(id) { it.copy(state = State.DONE, done = it.size) }
        vault.flush()
        publish()
    }

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    private fun fetch(url: String, headers: Map<String, String>, taskId: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode !in 200..299) return null
            val declared = conn.contentLengthLong
            if (declared > MAX_BYTES) return null
            val buffer = ByteArrayOutputStream(if (declared in 1..MAX_BYTES) declared.toInt() else 64 * 1024)
            conn.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    buffer.write(chunk, 0, read)
                    onChunk(taskId, total)
                }
            }
            buffer.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun onChunk(taskId: String, total: Long) {
        updateThrottled(taskId, total)
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    @Volatile
    private var lastUiAt = 0L

    private fun updateThrottled(id: String, done: Long) {
        val now = System.currentTimeMillis()
        if (now - lastUiAt < UI_INTERVAL_MS) return
        lastUiAt = now
        update(id) { it.copy(done = done) }
        publish()
    }

    private fun update(id: String, block: (Task) -> Task) {
        _tasks.update { list ->
            list.map { if (it.id == id) block(it) else it }
        }
    }

    private fun fail(id: String, message: String) {
        update(id) { it.copy(state = State.FAILED, error = message) }
        publish()
    }

    /** 完成任务数 / 速度 / 运行态一起刷新；全部结束就落一次统计文案 */
    private fun publish() {
        val now = System.currentTimeMillis()
        val bytes = _tasks.value.sumOf { it.done }
        val elapsed = now - lastSampleAt
        val speed = if (lastSampleAt == 0L || elapsed <= 0) 0L else {
            ((bytes - lastSampleBytes).coerceAtLeast(0)) * 1000 / elapsed
        }
        if (elapsed >= SPEED_WINDOW_MS) {
            lastSampleAt = now
            lastSampleBytes = bytes
        }
        val list = _tasks.value
        val done = list.count { it.state == State.DONE }
        val failed = list.count { it.state == State.FAILED }
        val running = list.any { it.state == State.RUNNING || it.state == State.WAITING }
        _stats.update {
            it.copy(
                total = list.size,
                done = done,
                failed = failed,
                running = running,
                speedBps = if (running) speed else 0L,
                lastMessage = if (!running) {
                    when {
                        failed == 0 -> "已下载 $done 张到本地"
                        done == 0 -> "下载失败 $failed 张"
                        else -> "完成 $done 张，失败 $failed 张"
                    }
                } else {
                    it.lastMessage
                }
            )
        }
    }

    private fun resetSpeedSample() {
        lastSampleAt = System.currentTimeMillis()
        lastSampleBytes = _tasks.value.sumOf { it.done }
    }

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    private fun panPlatformOf(platform: SharePlatform): PanPlatform = when (platform) {
        SharePlatform.XUNLEI -> PanPlatform.XUNLEI
        else -> PanPlatform.QUARK
    }

    /**
     * 分享文件 → 图库条目。
     *
     * UC 的 fid 与夸克各成一套，加前缀避免索引撞车；文件名、大小原样带过去，
     * 索引里的「原名 + id 哈希」文件名因此和网盘里看到的一致。
     */
    private fun toPanImage(file: ShareFile, platform: SharePlatform): PanImage {
        val id = if (platform == SharePlatform.UC) "uc_${file.fid}" else file.fid
        return PanImage(
            id = id,
            name = file.fname,
            size = file.fsize,
            thumbUrl = file.thumbnail?.url,
            thumbHeaders = file.thumbnail?.headers.orEmpty()
        )
    }

    private fun taskId(platform: PanPlatform, file: ShareFile): String =
        "${platform.name}:${file.fid}"

    companion object {
        /** 同时下 3 张：再多会和正在看图的那几张抢带宽 */
        private const val LANES = 3

        /** 单张上限：攻略图远小于这个数，超了多半是下到了 HTML 错误页 */
        private const val MAX_BYTES = 40L * 1024 * 1024

        private const val UI_INTERVAL_MS = 250L
        private const val SPEED_WINDOW_MS = 1000L

        @Volatile
        private var instance: ShareImporter? = null

        fun of(context: Context): ShareImporter = instance ?: synchronized(this) {
            instance ?: ShareImporter(context.applicationContext).also { instance = it }
        }
    }
}
