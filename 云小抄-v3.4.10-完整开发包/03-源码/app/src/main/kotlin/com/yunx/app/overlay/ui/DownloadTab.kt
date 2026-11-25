/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v2.8.0 起，「下载」Tab 直接渲染云析原版下载界面，不再自建汇总页、也不再跳转。
 *
 * 旧版这里是另一套卡片（已本地化用量、分享导入队列、分区本地化、两个按钮），
 * 与云析自己的下载管理并列存在：同一个任务在两处各显示一半，用户得来回跳。
 * 现在只留一个入口 —— 云析的 [DownloadScreen] 原样内嵌，任务列表、暂停/重试/删除、
 * 添加任务、保存目录、调速全在它里面，和云析主界面看到的是同一份数据。
 */

package com.yunx.app.overlay.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.data.download.DownloadRuntime
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.viewmodel.DownloadViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「下载」页 —— 云析原版下载界面。
 *
 * 容器必须给有限高度：[DownloadScreen] 内部是 `Box > Column > LazyColumn(weight(1f))`，
 * 放进外层的 `verticalScroll` 会拿到无限高度约束，直接抛
 * 「Vertically scrollable component was measured with an infinity maximum height」。
 * 所以调用处（MainActivity 的 when(tab)）对下载页单独走有界 Box，不套整页滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { DownloadRuntime.manager(context) }
    val viewModel: DownloadViewModel = viewModel(factory = DownloadViewModel.Factory(manager))
    // 下载页自己不吃顶栏：没有 TopAppBar，只用它的 nestedScroll 连接来处理列表手势
    // enterAlwaysScrollBehavior 本身就是 @Composable（内部已 remember），不能再包 remember
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Android 9- 保存到公共 Download 需要运行时存储权限。云析主界面会装这个钩子，
    // 云小抄这边同样是 Activity 上下文，必须也装一份 —— 否则在下载 Tab 里加的任务
    // 保存阶段直接判失败，用户只看到「保存失败」却不知道该给什么权限。
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pending?.complete(granted)
        pending = null
    }
    DisposableEffect(manager) {
        manager.storagePermissionProvider = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                true // Android 10+ 走 MediaStore，无需存储权限
            } else if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                pending = deferred
                withContext(Dispatchers.Main) {
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                deferred.await()
            }
        }
        onDispose { }
    }

    Box(modifier.fillMaxSize()) {
        DownloadScreen(
            scrollBehavior = scrollBehavior,
            viewModel = viewModel,
            // 下载调试页只在云析主界面的隐藏菜单里开，这里保持关闭
            downloadDebug = false,
            modifier = Modifier.fillMaxSize()
        )
        // 云析下载页用 SnackbarController 报错（权限、文件不存在、安装包等）。
        // 云小抄界面原来没有宿主，这些提示会静默丢掉 —— 补一个，位置和云析主界面一致。
        GlobalSnackbarHost()
    }
}
