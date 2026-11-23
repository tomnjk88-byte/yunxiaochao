/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.5.0）。本地文件夹小抄的缩略图缓存。
 *
 * 为什么需要它：
 *   本地小抄走 SAF（content://），一张截图往往 3000×4000、4~8 MB。以前网格里
 *   每个格子都把这个文件整份读出来再解码，一屏十几格就是几十 MB 的 IO ——
 *   表现就是「点开小抄要转好几秒、往下滚还在白格」。
 *   这里在后台用 inSampleSize 先做一次快速采样解码，再缩到 512px 存成 JPEG；
 *   下次打开同一个目录，格子读的是几十 KB 的缩略图，不再是原图。
 *
 * 为什么放 cacheDir 而不是 filesDir：
 *   缩略图是纯派生数据，删了能重建。系统清缓存只会让它下次慢一次，不会丢用户的图。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.max
import kotlin.math.roundToInt

class LocalThumbCache private constructor(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val dir = File(app.cacheDir, DIR_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLELISM)

    /** 已经排进队列的键，避免来回切目录时对同一张图反复解码 */
    private val queued: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Boolean>?
                ): Boolean = size > 4096
            }
        )
    )

    @Volatile
    private var job: Job? = null

    /**
     * 已经生成好的缩略图；没有返回 null。
     *
     * @param version 原图字节数。同名文件被替换时字节数基本一定变，拿它当版本号
     *                比 mtime 便宜（SAF 查询里本来就带着 COLUMN_SIZE）。
     */
    fun existing(docId: String, version: Long, box: Int = BOX): String? {
        val file = fileOf(docId, version, box)
        return if (file.isFile && file.length() > 0L) Uri.fromFile(file).toString() else null
    }

    /** 同步生成（后台线程调用）。失败返回 null，调用方退回原图。 */
    fun ensure(uri: String, docId: String, version: Long, box: Int = BOX): String? {
        val out = fileOf(docId, version, box)
        if (out.isFile && out.length() > 0L) return Uri.fromFile(out).toString()
        val bitmap = runCatching { decodeSampled(Uri.parse(uri), box) }.getOrNull() ?: return null
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            val tmp = File(dir, "${out.name}.part")
            tmp.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            }
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return null
            }
            Uri.fromFile(out).toString()
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 一个目录的缩略图预热。
     *
     * 只做「还没有的」；并发 3 路 —— 再高会和正在滚动的首屏抢 IO，
     * 在低端机上反而是「越滚越卡」。
     */
    fun prefetch(images: List<PanImage>, box: Int = BOX) {
        val todo = images.asSequence()
            .filter { !it.thumbUrl.isNullOrBlank() }
            .filter { img -> !fileOf(img.id, img.size, box).isFile }
            .filter { img -> queued.add(key(img.id, img.size, box)) }
            .take(MAX_PREFETCH)
            .toList()
        if (todo.isEmpty()) return

        job?.cancel()
        job = scope.launch {
            todo.forEach { image ->
                gate.withPermit {
                    runCatching {
                        ensure(
                            uri = image.thumbUrl.orEmpty(),
                            docId = image.id,
                            version = image.size,
                            box = box
                        )
                    }
                }
            }
            runCatching { trim(MAX_BYTES) }
        }
    }

    /** 换本地目录 / 换来源时清掉排队状态（磁盘文件保留，下次命中直接复用） */
    fun resetQueue() {
        job?.cancel()
        queued.clear()
    }

    fun clear() {
        job?.cancel()
        queued.clear()
        runCatching { dir.deleteRecursively() }
    }

    fun sizeBytes(): Long = runCatching {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------------

    private fun key(docId: String, version: Long, box: Int): String = "$docId:$version:$box"

    private fun fileOf(docId: String, version: Long, box: Int): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(key(docId, version, box).toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.jpg")
    }

    /**
     * 先读尺寸、再按 2 的幂采样解码，最后缩到 [box]。
     *
     * 采样这一步是关键：4K 图直接 decodeStream 要分配 48 MB 的位图，
     * inSampleSize=8 之后只有 0.75 MB，解码时间也从几百毫秒降到几十毫秒。
     */
    private fun decodeSampled(uri: Uri, box: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > box * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val longest = max(decoded.width, decoded.height)
        if (longest <= box) return decoded
        val ratio = box.toFloat() / longest
        val width = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(decoded, width, height, true)
        }.getOrNull()
        if (scaled == null) return decoded
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** 按最后修改时间从旧到新删，压回上限以内 */
    private fun trim(maxBytes: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }

    companion object {
        /** 网格格子大约 160dp，512px 在 3x 屏上也够清晰 */
        const val BOX = 512

        /** 一次最多预热这么多张，避免「打开一个 2000 张的目录」时后台跑十分钟 */
        private const val MAX_PREFETCH = 400

        private const val PARALLELISM = 3
        private const val QUALITY = 82
        private const val MAX_BYTES = 160L * 1024 * 1024
        private const val DIR_NAME = "local_guide_thumbs"

        @Volatile
        private var instance: LocalThumbCache? = null

        fun of(context: Context): LocalThumbCache = instance ?: synchronized(this) {
            instance ?: LocalThumbCache(context.applicationContext).also { instance = it }
        }
    }
}
