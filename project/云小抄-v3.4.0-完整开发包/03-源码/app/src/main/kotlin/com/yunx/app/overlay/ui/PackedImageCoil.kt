/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.0）。让 Coil 认识 `.yxz`：取流时才解压，解压在
 * Coil 的 IO 线程上做，不会卡住列表滚动。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.map.Mapper
import coil3.request.Options
import com.yunx.app.overlay.data.PackedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import java.io.File

/** `.yxz` 的「取流请求」标记对象，避免和普通字符串地址混淆 */
internal data class PackedImageRequest(val uri: String)

/**
 * 把 `...jpg.yxz` 这样的地址映射成 [PackedImageRequest]，交给下面的 Fetcher。
 *
 * 只做字符串判断，不读文件 —— Mapper 是在解析阶段同步调用的，任何 IO 都会
 * 直接压在调用线程上。
 */
internal class PackedImageMapper : Mapper<String, PackedImageRequest> {

    override fun map(data: String, options: Options): PackedImageRequest? {
        val path = data.substringBefore('?')
        return if (PackedImage.isPackedName(path)) PackedImageRequest(data) else null
    }
}

/**
 * 取流时解压。
 *
 * `fetch()` 是挂起函数，Coil 在它自己的 fetcher 线程池里调用，
 * 所以这里的解压不会阻塞 UI。解压后直接包成 [SourceFetchResult]，
 * 后面的采样、解码、内存 / 磁盘缓存全部走 Coil 原有逻辑。
 */
internal class PackedImageFetcher(
    private val request: PackedImageRequest,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val pulled = withContext(Dispatchers.IO) { read(options.context, request.uri) } ?: return null
        val (raw, ext) = pulled
        return SourceFetchResult(
            source = ImageSource(Buffer().write(raw), FileSystem.SYSTEM),
            mimeType = mimeOf(ext),
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<PackedImageRequest> {
        override fun create(
            data: PackedImageRequest,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = PackedImageFetcher(data, options)
    }

    /** @return 原始字节 + 原始扩展名 */
    private fun read(context: Context, uri: String): Pair<ByteArray, String>? = runCatching {
        val packed = if (uri.startsWith("file://")) {
            val path = Uri.parse(uri).path ?: return null
            File(path).readBytes()
        } else {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        } ?: return null
        val head = PackedImage.head(packed) ?: return null
        val raw = PackedImage.decode(packed) ?: return null
        raw to head.ext
    }.getOrNull()

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }
}
