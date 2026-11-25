/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.6 引入；v1.9.7 改为**整个分区全量预取**：
 * 进哪个分区的瞬间就把这个分区所有缩略图排队下完，滚到哪里都不再等。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yunx.app.overlay.data.ImageVault
import com.yunx.app.overlay.data.PanImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * 缩略图预取器（高性能模式专用）。
 *
 * v1.9.7 起的行为：
 *  - **全量**：一个分区有多少张就排多少张，不再只取前 24 张、也不再跳过首屏；
 *  - **并发 10**：配合 [GuideImageLoader] 的 12 路取流，百来张图基本一两秒铺完；
 *  - **切分区就换队**：点新分区时取消上一批未开始的下载，把带宽全给当前分区；
 *  - 与网格共用同一套缓存键（按文件 id），预取的结果格子直接复用，不会重复解码。
 */
internal object ThumbPrefetcher {

    private const val PARALLELISM = 10

    /** 首屏大概 6~8 格：这几张交给网格自己加载，预取从它们后面开始 */
    private const val VISIBLE_SKIP = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLELISM)

    /** 已经排过队的地址，避免来回切分区时把同一批图反复压进队列 */
    private val seen: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Boolean>?
                ): Boolean = size > 4096
            }
        )
    )

    private var job: Job? = null

    /**
     * 预取整个分区。
     *
     * @param images 当前分区的全部图片（顺序即网格顺序，先排前面的，首屏最快好）
     */
    fun prefetchAll(context: Context, images: List<PanImage>) {
        if (images.size <= VISIBLE_SKIP) return
        val app = context.applicationContext
        val vault = ImageVault.of(app)
        // 首屏那几格由网格自己在加载，预取从第 7 张开始；
        // 其余的**一张不落**全排进去，滚动时不再有空白格。
        val targets = images.asSequence()
            .drop(VISIBLE_SKIP)
            .filter { image ->
                val url = image.thumbUrl
                // v1.9.8：已经下载到本地的图不用再走网络
                !url.isNullOrBlank() && !vault.hasAny(image) && seen.add(url)
            }
            .toList()
        if (targets.isEmpty()) return

        // 切分区 = 换队：旧队列还没开始的直接丢掉，别和当前分区抢带宽
        job?.cancel()
        job = scope.launch {
            val loader = GuideImageLoader.of(app)
            var done = 0
            try {
                for (image in targets) {
                    gate.withPermit {
                        try {
                            loader.execute(image.toRequest(app))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // 单张失败（链接过期 / 网络抖动）不影响整批
                        }
                    }
                    done++
                }
            } finally {
                // 被切分区打断的，把没来得及下的地址从 seen 里放回去，下次还能补
                if (!isActive) {
                    targets.drop(done).forEach { image -> image.thumbUrl?.let(seen::remove) }
                }
            }
        }
    }

    private fun PanImage.toRequest(app: Context): ImageRequest =
        ImageRequest.Builder(app)
            .data(thumbUrl)
            .httpHeaders(thumbHeaders.toNetworkHeaders())
            .size(THUMB_BOX, THUMB_BOX)
            .memoryCacheKey(memoryKey(this))
            .diskCacheKey(diskKey(this))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()

    /** 只是把「已经排过队」清掉，用于换账号 / 换来源后重新预取 */
    fun reset() {
        job?.cancel()
        seen.clear()
    }

    /** 与网格、大图共用同一套缓存键：只认文件 id，直链换签名也不影响命中 */
    fun memoryKey(image: PanImage): String = "yunx_thumb_${image.id}"

    fun diskKey(image: PanImage): String = "yunx_thumb_${image.id}"

    /** 缩略图统一解码边长；与 [FloatingContent] 里网格解码尺寸保持一致 */
    const val THUMB_BOX = 512
}
