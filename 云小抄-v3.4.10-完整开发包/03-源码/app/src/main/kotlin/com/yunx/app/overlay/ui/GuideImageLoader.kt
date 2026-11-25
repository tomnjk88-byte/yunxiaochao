/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.7 攻略图专用的 Coil ImageLoader：独立磁盘缓存、
 * 强制缓存策略（不看网盘 CDN 的 Cache-Control）、更高的下载并发。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import com.yunx.app.overlay.data.OverlayPrefs
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import java.io.File

/**
 * 攻略图专用的图片加载器，和主 App 的 Coil 单例完全分开。
 *
 * 为什么要自己建一个：
 *  1. **强制缓存**。网盘 CDN（夸克 `drive-pc.quark.cn`、迅雷 CDN）的缩略图经常带
 *     `Cache-Control: no-store` / `no-cache`，Coil 默认的 [CacheStrategy.DEFAULT] 会因此
 *     **拒绝写入磁盘缓存**，表现就是每次打开小抄都要把几十张图重新下一遍。
 *     这里的策略是「有缓存就直接用、拿到了就写盘」—— 攻略图按文件 id 缓存，内容不会变。
 *  2. **磁盘缓存按模式给**。v1.9.10 起：开了本地化，图本来就有正式文件，Coil 这层
 *     只需要兜住缩略图，128 MB 足够；没开本地化才退回 512 MB 的老行为。
 *     之前不管开没开都是 512 MB，「存储占用很恐怖」有一半是这里贡献的。
 *  3. **更高的下载并发**。默认 fetcher 并发偏低，一屏几十张图排队；这里给 16 路。
 */
internal object GuideImageLoader {

    private const val DISK_LIMIT_BYTES = 512L * 1024 * 1024

    /** 本地化开启时的缓存上限：只放缩略图和临时图，正文图在用户目录里 */
    private const val LOCALIZED_DISK_BYTES = 128L * 1024 * 1024

    /**
     * 取流并发：预取占 10 路，这里给 16 路，留 6 路给网格自己的可见格，
     * 免得「预取把带宽占满、首屏反而更慢」。
     */
    private const val FETCH_PARALLELISM = 16

    @Volatile
    private var instance: ImageLoader? = null

    fun of(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    /** 「空间管理 → 清缩略图缓存」：只清这个独立缓存目录，不动用户目录里的正式文件 */
    suspend fun clearCache(context: Context) {
        val cache = instance?.diskCache
        if (cache != null) {
            runCatching { cache.clear() }
        } else {
            runCatching { File(context.cacheDir, "guide_image_cache").deleteRecursively() }
        }
    }

    private fun build(app: Context): ImageLoader {
        val vaultOn = runCatching { OverlayPrefs(app).imageVaultEnabled }.getOrDefault(true)
        val limit = if (vaultOn) LOCALIZED_DISK_BYTES else DISK_LIMIT_BYTES
        return ImageLoader.Builder(app)
            // 用带「强制缓存」策略的网络取流器替换默认实现
            .components {
                // v2.0.0：本地化图片可能存成 `.yxz`（无损打包），这里负责用时解压
                add(PackedImageMapper())
                add(PackedImageFetcher.Factory())
                add(OkHttpNetworkFetcherFactory(cacheStrategy = { AlwaysCache }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("guide_image_cache").absolutePath.toPath())
                    .maxSizeBytes(limit)
                    .build()
            }
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(FETCH_PARALLELISM))
            .build()
    }

    /**
     * 「有就用、拿到就存」。
     *
     * 攻略图以网盘文件 id 作为缓存键：同一张图的内容不会变，直链签名换了也不影响。
     * 所以这里不需要按 RFC 去判断 `no-store` / `max-age`，直接信任缓存即可。
     */
    private object AlwaysCache : CacheStrategy {

        override suspend fun read(
            response: NetworkResponse,
            request: NetworkRequest,
            options: Options
        ): CacheStrategy.ReadResult = CacheStrategy.ReadResult(response)

        override suspend fun write(
            cacheResponse: NetworkResponse?,
            networkRequest: NetworkRequest,
            networkResponse: NetworkResponse,
            options: Options
        ): CacheStrategy.WriteResult = CacheStrategy.WriteResult(networkResponse)
    }
}
