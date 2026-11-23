/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.8.0）。
 *
 * 进程级下载管理器单例。
 *
 * 云析主界面和云小抄的「下载」Tab 现在渲染的是同一套下载界面（见 DownloadScreen），
 * 两边必须共用同一个 DownloadManager：
 *   · 各建一个 = 两条任务队列、两份「启动后恢复未完成任务」逻辑，同一个任务会被两个实例
 *     同时推进，分片互相覆盖；
 *   · 设置（并发数、限速、重试、锁屏保持）也只在实例构造时读一次，两份会出现口径不一致。
 *
 * 所以实例化收敛到这里，任何界面都通过 [manager] 取。
 */

package com.yunx.app.data.download

import android.content.Context
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository

object DownloadRuntime {

    @Volatile
    private var instance: DownloadManager? = null

    fun manager(context: Context): DownloadManager =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(ctx: Context): DownloadManager {
        val settings = SettingsRepository(ctx)
        return DownloadManager(
            context = ctx,
            dao = AppDatabase.get(ctx).downloadTaskDao(),
            downloader = ChunkDownloader({ HttpClients.downloadClient() }),
            threadProvider = { platform -> settings.downloadThreadsFor(platform) },
            // 自定义保存目录（SAF tree Uri），设置页改完即时生效
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.maxConcurrentDownloads },
            speedLimitProvider = { settings.downloadSpeedLimit },
            retryCountProvider = { settings.downloadRetryCount },
            keepWhenLockedProvider = { settings.keepDownloadWhenLocked },
            showSpeedProvider = { settings.notificationShowSpeed }
        )
    }
}
