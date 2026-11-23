/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.8 把「网盘仓库 / 本地图库 / 下载器」做成进程级单例，
 * 让悬浮窗 Service 与主页共用同一份缓存与同一个下载队列。
 */

package com.yunx.app.overlay.data

import android.content.Context

/**
 * 进程级共享的攻略数据层。
 *
 * 以前 [MediaRepository] 是 [com.yunx.app.overlay.service.FloatingService] 的私有字段：
 * 主页「我的 → 小抄本地化」想下载图，就得先起悬浮窗才有仓库；而且主页和 Service
 * 各建一份会让内存缓存翻倍。做成单例后两边拿到的是同一个对象、同一份队列。
 */
object GuideRepo {

    @Volatile
    private var repository: MediaRepository? = null

    fun repository(context: Context): MediaRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val app = context.applicationContext
            val prefs = OverlayPrefs(app)
            val built = MediaRepository(
                store = CredentialStore.get(app),
                prefs = prefs,
                // 高性能模式把快照有效期 / 目录条数一起放宽，少一次「重开就联网」
                snapshot = SnapshotStore(
                    app,
                    maxAgeMs = {
                        if (prefs.highPerfMode) {
                            SnapshotStore.HIGH_PERF_MAX_AGE_MS
                        } else {
                            SnapshotStore.DEFAULT_MAX_AGE_MS
                        }
                    },
                    maxFolders = {
                        if (prefs.highPerfMode) {
                            SnapshotStore.HIGH_PERF_MAX_FOLDERS
                        } else {
                            SnapshotStore.DEFAULT_MAX_FOLDERS
                        }
                    }
                ),
                // v2.5.9：冷启动就落在「上次激活的小抄入口」那一层（含子目录起点），
                // 否则绑了 /小抄/龙宫 的入口重开应用会退回 /小抄 根
                local = LocalGuideRepository(app).also {
                    it.setTree(prefs.localTreeUri, prefs.localRootDocId)
                },
                vault = ImageVault.of(app),
                // v2.0.2 链接小抄：分享会话只能从 Context 建
                appContext = app
            )
            repository = built
            return built
        }
    }

    fun vault(context: Context): ImageVault = ImageVault.of(context)

    fun downloader(context: Context): GuideDownloader = GuideDownloader.of(context)
}
