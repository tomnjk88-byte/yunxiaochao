/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。用 Service + WindowManager 承载悬浮窗：
 *   - 双指缩放整个窗口（ScaleGestureDetector + LayoutParams.width/height）
 *   - 单指拖动只作用于左下角「移动区域」（OnTouchListener + LayoutParams.x/y）
 *   - 透明度 Slider 直接改 LayoutParams.alpha
 *
 * v1.8.1：主页「自动连招」按钮改为一步展开可操作的连招面板。
 *   （v1.8.3 起该入口与 setMacroWindow 合并进 showMacro()，冷启动联动已移除。）
 *
 * v1.8.2：两个悬浮窗都套上 LongPressDragRootLayout，整窗任意位置长按 ~350ms 即可拖动。
 *   短按点击、快速滑动、双指缩放、滑杆、右下角拉伸角全部照常；顶栏「拖动」按钮仍可即时拖。
 *   OverlayRootLayout 的缩放状态机一行未改，只多了一个父 View。
 *
 * v1.8.3：小抄与自动连招彻底解耦成两个互不关联的按钮，各自只 attach/detach 自己的
 *   WindowManager 窗口；删掉攻略窗顶栏的连招入口、冷启动收球（pendingMacroOnly）与
 *   强制重置连招状态。服务只是共同宿主，关掉一个窗口不影响另一个。
 *   另修 v1.8.2 拖动只走一帧的 bug（拦截后 MOVE 改走 onTouchEvent）。
 */

package com.yunx.app.overlay.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yunx.app.R
import com.yunx.app.overlay.MainActivity
import com.yunx.app.overlay.MacroIconPickerActivity
import com.yunx.app.overlay.data.CredentialStore
import com.yunx.app.overlay.data.GuideRepo
import com.yunx.app.overlay.data.GuideSource
import com.yunx.app.overlay.data.LocalGuideRepository
import com.yunx.app.overlay.data.MediaRepository
import com.yunx.app.overlay.data.MotionPrefs
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.data.PanPlatform
import com.yunx.app.overlay.data.SnapshotStore
import com.yunx.app.overlay.macro.CaptureMode
import com.yunx.app.overlay.macro.MacroAccessibilityService
import com.yunx.app.overlay.macro.MacroBall
import com.yunx.app.overlay.macro.MacroCaptureOverlay
import com.yunx.app.overlay.macro.MacroController
import com.yunx.app.overlay.macro.MacroHost
import com.yunx.app.overlay.macro.MacroKey
import com.yunx.app.overlay.macro.MacroKeypadRecorder
import com.yunx.app.overlay.macro.MacroStep
import com.yunx.app.overlay.macro.TapIndicatorOverlay
import com.yunx.app.overlay.ui.BubbleContent
import com.yunx.app.overlay.ui.CrosshairView
import com.yunx.app.overlay.login.QuarkLoginActivity
import com.yunx.app.overlay.login.XunleiLoginActivity
import com.yunx.app.overlay.ui.FloatingContent
import com.yunx.app.overlay.ui.FloatingViewModel
import com.yunx.app.overlay.ui.MacroBubbleContent
import com.yunx.app.overlay.ui.MacroWindowContent
import com.yunx.app.overlay.ui.OverlayActions
import com.yunx.app.overlay.ui.OverlayTheme
import com.yunx.app.overlay.ui.ThumbPrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: OverlayPrefs
    private lateinit var host: OverlayHost
    private lateinit var params: WindowManager.LayoutParams
    /** 最外层：长按整窗拖动。内层 [pinchRoot] 仍是原来的双指缩放根，两者互不干扰 */
    private lateinit var rootView: LongPressDragRootLayout
    private var pinchRoot: OverlayRootLayout? = null
    /** 攻略层当前是否挂在 WindowManager 上（与连招层 macroRoot 各自独立） */
    private var guideAttached = false
        set(value) {
            field = value
            // v1.9.5：顺带同步静态标志与状态流，主界面不用再每秒轮询
            guideWindowAttached = value
            _guideAttachedFlow.value = value
        }

    private val windowAlpha = MutableStateFlow(1f)
    private val collapsed = MutableStateFlow(false)
    private val sizeScale = MutableStateFlow(1f)
    private val bubbleIcon = MutableStateFlow<String?>(null)

    // ---- 独立连招层（v1.6.0 建 / v1.7.0 加球态）：与攻略窗并列、完全解耦 ----
    private var macroParams: WindowManager.LayoutParams? = null
    private var macroRoot: LongPressDragRootLayout? = null
    /** 连招层是否处于「缩成球」状态。与小抄球的 collapsed 各管各的，互不联动 */
    private val macroCollapsed = MutableStateFlow(true)
    /** 球拖动的累计起点，避免每帧抖动 */
    private var macroBubbleDragX = 0f
    private var macroBubbleDragY = 0f
    private var macroDragStartX = 0
    private var macroDragStartY = 0
    private var macroDragDownRawX = 0f
    private var macroDragDownRawY = 0f
    private var macroResizeStartW = 0
    private var macroResizeStartH = 0
    private var macroResizeDownRawX = 0f
    private var macroResizeDownRawY = 0f

    // ---- v2.2.0 准星层：自由导入图片，图片上的锚点就是瞄准点 ----
    private var crosshairRoot: LongPressDragRootLayout? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var crosshairView: CrosshairView? = null
    /** 服务内存里的旧锚点：改锚点 / 改大小时用它反推，保证瞄准点不跑 */
    private var crosshairAnchorX = 0.5f
    private var crosshairAnchorY = 0.5f

    /** 双击全屏前的窗口几何，退出时还原：w, h, x, y */
    private var beforeMaximize: IntArray? = null

    // ---- 自动连招 ----
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureOverlay: MacroCaptureOverlay? = null
    /** v2.5.7 按键录制台（一按键一窗，屏幕其它区域照常给游戏） */
    private var keypadRecorder: MacroKeypadRecorder? = null
    private val macroBalls = mutableMapOf<String, MacroBall>()

    /** v1.9.1「点击显示」：回放连招时画点击圆点的透明层（不可触摸、用完自动摘掉） */
    private val tapIndicator by lazy {
        TapIndicatorOverlay(this, windowManager, overlayWindowType())
    }

    /** v2.5.6 帧率悬浮窗：独立一层，透明/不透明/图标/文字色都能自定义 */
    private val fpsOverlay by lazy {
        FpsOverlay(this, windowManager, overlayWindowType())
    }

    /** 录制前连招窗是否展开；录完据此恢复，免得把本来就收成球的窗强行摊开 */
    private var captureRestoreExpanded = false

    /** 音量键隐藏时记住每个窗口原本的 alpha / flags，恢复时原样还回去 */
    private val hiddenOriginals = mutableMapOf<WindowManager.LayoutParams, Pair<Float, Int>>()

    /** 正在重建窗口：避免重复 remove/add 造成 WindowManager 状态错乱 */
    private var rebuilding = false

    /**
     * v1.9.4：本次 Service 实例是不是刚冷启动。
     * 冷启动时只认 onStartCommand 里那一条 action 要开的那扇窗，
     * 绝不按旧偏好顺手把另一扇也挂出来（点连招却蹦出小抄就是这么来的）。
     */
    private var coldStart = true

    /** v1.9.5：上一次前台通知的内容指纹，用来跳过无意义的重建 */
    private var notifyKey: String? = null

    /** 把 WindowManager 能力注入给连招模块；Compose 面板只跟 MacroController 打交道 */
    private val macroHost = object : MacroHost {
        override fun showCapture(
            mode: CaptureMode,
            onResult: (List<com.yunx.app.overlay.macro.MacroStep>) -> Unit,
            onCancel: () -> Unit
        ) {
            captureOverlay?.dismiss()
            captureOverlay = MacroCaptureOverlay(
                this@FloatingService,
                windowManager,
                overlayWindowType(),
                mode,
                onResult,
                onCancel,
                prefs.showTapIndicator
            ).also { it.show() }
        }

        override fun showKeypadRecorder(
            seedKeys: List<MacroKey>,
            onResult: (List<MacroKey>, List<MacroStep>) -> Unit,
            onCancel: () -> Unit
        ) {
            dismissCaptureOverlay()
            keypadRecorder = MacroKeypadRecorder(
                this@FloatingService,
                windowManager,
                overlayWindowType(),
                seedKeys,
                onResult,
                onCancel
            ).also { it.show() }
        }

        override fun dismissKeypad() {
            keypadRecorder?.dismiss()
            keypadRecorder = null
        }

        override fun dismissCapture() {
            dismissCaptureOverlay()
        }

        override fun onCaptureStart() = beginCapture()

        override fun onCaptureEnd(restore: Boolean) = endCapture(restore)

        override fun showTap(x: Float, y: Float) = tapIndicator.showTap(x, y)

        override fun syncBalls() = syncMacroBalls()

        override fun toast(message: String) = this@FloatingService.toast(message)
    }

    // v1.9.8：仓库改成进程级单例（主页的「小抄本地化」也要用它），
    // 这样主页与悬浮窗共用同一份内存缓存和同一个下载队列。
    private val repository by lazy { GuideRepo.repository(this) }

    private val viewModel: FloatingViewModel by lazy {
        ViewModelProvider(
            host,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(FloatingViewModel::class.java)) {
                        "未知的 ViewModel: $modelClass"
                    }
                    return FloatingViewModel(repository) as T
                }
            }
        )[FloatingViewModel::class.java]
    }

    private val overlayActions: OverlayActions by lazy {
        OverlayActions(
            // v2.1.3：头顶的 X 只收攻略层，绝不能直接 stopSelf()——
            // 那样会杀掉整个服务却漏掉 prefs.guideWindowShown 回写，
            // 导致「窗口没了、开关却停在已开启」，回主页要多按一下才开得起来。
            onClose = { closeGuideWindow() },
            onRefresh = { viewModel.refresh(force = true) },
            onSelectFolder = viewModel::selectCategory,
            onOpenFolder = viewModel::openFolder,
            onGoUp = viewModel::goUp,
            onOpenPreview = viewModel::openPreview,
            onClosePreview = viewModel::closePreview,
            onLogin = ::openLogin,
            onRetry = ::retry,
            onCollapse = ::collapse,
            onMaximize = ::setMaximized,
            onReset = ::resetOverlay,
            onReplayReveal = viewModel::playReveal,
            onPrefetch = viewModel::prefetch,
            resolveSource = { image, thumbnail -> viewModel.resolveSource(image, thumbnail) },
            thumbSource = repository::thumbSource
        )
    }

    // 拖动起点（窗口坐标）与按下点（屏幕坐标）
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 隐藏状态只在本次 Service 生命周期内有效，重启一律恢复显示，
        // 免得重启手机后用户以为悬浮窗丢了
        windowsHidden = false
        hiddenOriginals.clear()
        prefs = OverlayPrefs(this)
        // v2.1.0：动画偏好是进程单例，Service 也要初始化，否则窗口伸缩读不到用户的选择
        MotionPrefs.init(this)
        collapsed.value = prefs.collapsed
        macroCollapsed.value = prefs.macroCollapsed
        bubbleIcon.value = prefs.bubbleIconPath
        sizeScale.value = initialSizeScale()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // v2.7.2：先把真实屏幕范围拿到手，后面建窗 / 夹位置都按它算
        refreshScreenSize()
        windowAlpha.value = prefs.restoreWindow().alpha
        host = OverlayHost().apply { create() }

        MacroController.init(this)
        MacroController.host = macroHost
        // 运行状态变化时把悬浮按钮换个颜色（执行中=红，让路中=琥珀，空闲=白边）
        serviceScope.launch {
            MacroController.runningId.collect { refreshMacroBalls() }
        }
        // v1.9.16：连招为了让你操作而暂停时，描边要跟着变 —— 否则你会以为它卡住了
        serviceScope.launch {
            MacroController.pausedByUser.collect { refreshMacroBalls() }
        }

        // Android 8+ 必须先 startForeground，再挂悬浮窗，否则后台启动会被系统直接掐掉
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            toast("请先在设置里授予「显示在其他应用上层」权限")
            stopSelf()
            return
        }
        syncMacroBalls()
        // v1.9.6 高性能读取：Service 一起来就在后台把「上次浏览的分类」预热进内存。
        // 用户过几秒点开小抄时，ViewModel 走的是内存命中，不会先白屏转圈再一张张刷图。
        // 只在小抄用户（开过窗 / 看过分类）身上做，纯连招用户不额外走网。
        if (prefs.guideWindowShown || prefs.lastCategoryId != null) {
            serviceScope.launch {
                val content = runCatching { repository.warmUp() }.getOrNull()
                // v1.9.7：连图片一起预热 —— 整个分区全量排队，打开小抄时缩略图已经在缓存里
                if (content == null) return@launch
                if (prefs.imageVaultEnabled) {
                    // v1.9.8：本地化模式下载到目录，下次开窗直接读文件
                    GuideRepo.downloader(this@FloatingService)
                        .enqueue(prefs.lastCategoryId, content.images)
                } else if (prefs.highPerfMode) {
                    ThumbPrefetcher.prefetchAll(this@FloatingService, content.images)
                }
            }
        }
        // v1.9.8：启动时检测一次分享链接有没有新增小抄（每 5 天一次；进程内只跑一次）
        runCatching { GuideRepo.downloader(this).maybeAutoCheck() }
        // v1.9.10：顺手把历史上重复下载的文件合并掉（一天一次，后台跑）
        runCatching { GuideRepo.downloader(this).maybeMaintain() }
        // v2.0.1：镜像同步（作者分享 → 我的小抄 → 本地），24 小时节流，默认开
        runCatching { com.yunx.app.overlay.data.MirrorSync.of(this).maybeAutoSync() }
        // v1.9.4：冷启动这里先不挂任何窗。紧接着的 onStartCommand 会告诉我们
        // 用户到底点的是哪一颗按钮：点连招只挂连招，点小抄只挂小抄，互不捎带。
        // 只有系统粘性重启（intent == null）才按各自偏好恢复。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_DISMISS_CAPTURE -> {
                // 用户回到主页 / 重新打开 App：录制透明膜不应该还盖在屏幕上。
                // 这是「用了一阵子突然全屏点不动」最常见的残留物，先无条件收掉。
                dismissCaptureOverlay()
                MacroController.cancelCapture()
            }

            ACTION_RESET -> {
                // 通知栏 / 主页的「重置悬浮窗」：清膜 + 重建窗口 + 触摸状态归零
                resetOverlay()
                toast("悬浮窗已重置")
            }

            ACTION_TOGGLE_MACRO -> toggleMacroWindow()

            ACTION_TOGGLE_VISIBILITY -> setWindowsHidden(!windowsHidden)

            ACTION_SET_VISIBILITY -> {
                val show = intent?.getBooleanExtra(EXTRA_VISIBILITY_SHOW, true) ?: true
                setWindowsHidden(!show)
            }

            ACTION_SET_GUIDE -> setGuideWindowShown(
                intent?.getBooleanExtra(EXTRA_GUIDE_SHOW, false) ?: false
            )

            ACTION_SET_MACRO -> setMacroWindowShown(
                intent?.getBooleanExtra(EXTRA_MACRO_SHOW, false) ?: false
            )

            ACTION_SET_CROSSHAIR -> setCrosshairWindowShown(
                intent?.getBooleanExtra(EXTRA_CROSSHAIR_SHOW, false) ?: false
            )

            ACTION_APPLY_CROSSHAIR -> refreshCrosshairFromPrefs()

            // v2.7.1：锁定只改 flags，不碰几何
            ACTION_SET_CROSSHAIR_LOCKED -> applyCrosshairLock(
                intent?.getBooleanExtra(EXTRA_CROSSHAIR_LOCKED, false) ?: false
            )

            ACTION_CENTER_CROSSHAIR -> {
                // v2.7.3：长按「居中显示」= 告诉程序「你把屏幕方向看反了」
                if (intent?.getBooleanExtra(EXTRA_SWAP_SCREEN, false) == true) {
                    prefs.crosshairSwapScreen = !prefs.crosshairSwapScreen
                }
                centerCrosshairLayer()
            }

            ACTION_RESET_CROSSHAIR -> resetCrosshairLayer()

            // v2.5.6 帧率悬浮窗：开关 / 重读外观
            ACTION_SET_FPS -> setFpsWindowShown(
                intent?.getBooleanExtra(EXTRA_FPS_SHOW, false) ?: false
            )
            ACTION_APPLY_FPS -> fpsOverlay.applyPrefs()

            ACTION_SET_SOURCE -> {
                repository.setSource(
                    GuideSource.fromKey(intent?.getStringExtra(EXTRA_SOURCE))
                )
                viewModel.refresh(force = true)
            }

            ACTION_SET_LOCAL_URI -> {
                // v2.5.9：带上「从哪一层开始」—— 小抄入口绑子目录时，光换 tree 会停在父目录
                repository.setLocalBinding(
                    intent?.getStringExtra(EXTRA_LOCAL_URI),
                    intent?.getStringExtra(EXTRA_LOCAL_DOC)
                )
                viewModel.refresh(force = true)
            }

            // v2.0.2：链接小抄换了链接 / 清了链接
            ACTION_SET_LINK -> {
                repository.setLinkShare(
                    intent?.getStringExtra(EXTRA_LINK),
                    intent?.getStringExtra(EXTRA_LINK_TITLE).orEmpty()
                )
                viewModel.refresh(force = true)
            }

            ACTION_REFRESH -> {
                // 主页可能刚换了小抄图标 / 切了来源：重新读一次，不用重启悬浮窗
                MacroController.cancelCapture()   // 顺带收膜 + 恢复录制前缩小的连招窗
                tapIndicator.dismiss()
                bubbleIcon.value = prefs.bubbleIconPath
                MacroController.refreshEnabled()
                syncMacroBalls()
                // 注意：这里不再 syncMacroWindow()，连招层不随刷新一起冒出来
                // v2.2.0：换了准星图片 / 改过外观也走这条，顺手重载一次
                refreshCrosshairFromPrefs()
                // v2.5.6：帧率窗换图标 / 改透明度 / 改颜色后即时重读
                if (prefs.fpsShown) fpsOverlay.applyPrefs()
                viewModel.refresh(force = true)
            }
        }
        // v1.9.4：冷启动只认这一条 action。上面 SET_MACRO / SET_GUIDE 已经各挂各的窗，
        // 这里绝不再补另一扇；只有 intent 为空（系统粘性重启）才按偏好恢复。
        if (coldStart) {
            coldStart = false
            if (intent?.action == null) {
                if (prefs.guideWindowShown) attachOverlay()
                if (prefs.macroWindowShown) attachMacroWindow()
                // 准星图被删 / 读不出来：如实把开关落回 false，别留一个永远不显示的「已开启」
                if (prefs.crosshairShown && !attachCrosshair()) prefs.crosshairShown = false
                // v2.5.6：帧率窗按自己的持久化开关独立恢复
                if (prefs.fpsShown) fpsOverlay.show()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // v2.7.2：横竖屏换了，先重取真实范围，再谈夹位置
        refreshScreenSize()
        // v2.7.1：只开准星、没开小抄窗时 rootView 还没初始化，旧代码在这里就 return 了，
        // 结果旋转后准星被留在屏外，怎么拖都回不来。先夹准星，再管小抄窗。
        crosshairParams?.let {
            clampCrosshairPosition()
            safeCrosshairUpdate()
        }
        if (!::rootView.isInitialized) return
        // 双击全屏中旋转：按新屏幕重新铺满；否则按新屏幕收一次旧尺寸
        if (beforeMaximize != null) {
            // 先留住「双击前」的原始几何，重算全屏尺寸后再塞回去，
            // 否则旋转一次就把退出时要还原的尺寸记成了旧屏幕的全屏尺寸
            val original = beforeMaximize
            beforeMaximize = null
            setMaximized(true)
            beforeMaximize = original
        } else {
            applyWidth(params.width)
        }
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        captureOverlay?.dismiss()
        captureOverlay = null
        keypadRecorder?.dismiss()
        keypadRecorder = null
        tapIndicator.dismiss()
        // v2.5.6：帧率窗也必须在进程退出前摘干净，别留一层吃触摸的窗
        fpsOverlay.dismiss()
        hiddenOriginals.clear()
        windowsHidden = false
        detachMacroWindow()
        guideWindowAttached = false
        // v2.2.0：准星层也必须在进程退出前摘干净，不能留一层吃触摸的图在屏幕上
        crosshairRoot?.let { runCatching { windowManager.removeViewImmediate(it) } }
        crosshairRoot = null
        crosshairView = null
        crosshairParams = null
        crosshairAttached = false
        macroBalls.values.forEach { it.dismiss() }
        macroBalls.clear()
        MacroController.shutdown()
        serviceScope.cancel()
        persistGeometry()
        if (::rootView.isInitialized && guideAttached) {
            runCatching { rootView.resetTouchState() }
            // removeViewImmediate 比 removeView 更可靠：不会把「待移除」排到下一帧，
            // 进程退出前一定把这一层从 WindowManager 摘干净，避免残留窗口吃掉触摸。
            runCatching { windowManager.removeViewImmediate(rootView) }
        }
        pinchRoot?.resetTouchState()
        host.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 悬浮窗
    // ------------------------------------------------------------------

    // ==================================================================
    // 两个互不关联的窗口开关（v1.8.3）
    //
    // 「小抄」只 attach/detach 攻略层，「自动连招」只 attach/detach 连招层。
    // 两者共用同一个前台 Service 作为宿主，但没有任何状态、偏好或几何上的联动：
    // 关掉一个绝不影响另一个，冷启动也各自按自己的持久化开关独立恢复。
    // ==================================================================

    /** 「小抄」按钮：只动攻略层 */
    private fun setGuideWindowShown(show: Boolean) {
        prefs.guideWindowShown = show
        // v1.9.2：主动点「开启」就一定要看得见，先解除音量键隐藏
        if (show) revealWindows()
        if (show == guideAttached) {
            startAsForeground()
            return
        }
        if (show) {
            attachOverlay()
            if (!guideAttached) {
                prefs.guideWindowShown = false
                return
            }
            toast("小抄已显示")
        } else {
            detachOverlay()
            toast("小抄已收起")
        }
        startAsForeground()
    }

    /**
     * v2.1.3：悬浮窗头顶 X 的收窗逻辑。
     *
     * 只收攻略层（prefs.guideWindowShown 落回 false + detachOverlay），连招层一个字节都不碰。
     * 关键坑：这里**绝不能**直接 `stopSelf()`——那会杀掉整个服务却漏掉偏好回写，
     * 于是「窗口没了、开关却停在已开启」，回到主页就得多按一下才开得起来（见 bug 反馈）。
     * 只有连招窗也都没挂时，才把空服务停掉，省一个前台通知；否则保留服务给连招窗用。
     * 停服务前先等收起动画走完，免得 onDestroy 把窗口瞬间摘掉、动画看不到。
     */
    private fun closeGuideWindow() {
        // v2.2.0：连招窗或准星窗还在，就必须把服务留着，不能顺手把别人一起杀掉
        val otherStillUp =
            FloatingService.macroWindowAttached || FloatingService.crosshairAttached
        setGuideWindowShown(false)
        if (!otherStillUp) {
            val delayMs = MotionPrefs.duration(WINDOW_EXIT_MS)
            if (delayMs <= 0L) stopSelf()
            else serviceScope.launch { delay(delayMs); stopSelf() }
        }
    }

    /** 摘掉攻略层，连招层一个字节都不碰 */
    private fun detachOverlay() {
        if (!::rootView.isInitialized || !guideAttached) return
        persistGeometry()
        runCatching { rootView.resetTouchState() }
        pinchRoot?.resetTouchState()
        // v2.0.8：收起也给个 150ms 的缩小淡出。直接 removeViewImmediate 是「啪」地消失，
        // 用户点了「小抄」再点「连招」时，中间那下硬切就是他要的「切换没有动画」。
        // 用局部变量接住：这期间如果又被打开，rootView 会被换成新的。
        val closing = rootView
        guideAttached = false
        guideWindowAttached = false
        // v2.1.0：动画关掉就直接摘窗，不留一段看得见的淡出
        val exitMs = MotionPrefs.duration(WINDOW_EXIT_MS)
        if (exitMs <= 0L) {
            runCatching { if (closing.isAttachedToWindow) windowManager.removeViewImmediate(closing) }
        } else {
            closing.animate()
                .alpha(0f)
                .scaleX(WINDOW_EXIT_SCALE)
                .scaleY(WINDOW_EXIT_SCALE)
                .setDuration(exitMs)
                .withEndAction {
                    runCatching { if (closing.isAttachedToWindow) windowManager.removeViewImmediate(closing) }
                }
                .start()
        }
    }

    // ==================================================================
    // v2.2.0 准星层
    //
    // 和攻略层 / 连招层完全解耦的第三扇窗：一扇只画「图片 + 瞄准点」的
    // 小窗，图片由用户自由导入，锚点由用户在预览图上点选。
    // 打开「锁定」后窗口带 FLAG_NOT_TOUCHABLE，纯显示、不挡游戏操作。
    // ==================================================================

    /** 「准星」开关：只动准星层 */
    /** v2.5.6 帧率窗开关：独立一层，与小抄 / 连招 / 准星各存各的开关 */
    private fun setFpsWindowShown(show: Boolean) {
        prefs.fpsShown = show
        if (show) revealWindows()
        if (show) {
            fpsOverlay.show()
            toast("帧率悬浮窗已显示 · 拖动可移位")
        } else {
            fpsOverlay.dismiss()
            toast("帧率悬浮窗已收起")
        }
        startAsForeground()
    }

    private fun setCrosshairWindowShown(show: Boolean) {
        prefs.crosshairShown = show
        if (show) revealWindows()
        if (show == (crosshairRoot != null)) {
            startAsForeground()
            return
        }
        if (show) {
            if (!attachCrosshair()) {
                prefs.crosshairShown = false
                toast("准星打开失败：先导入一张图片")
            } else {
                toast("准星已显示")
            }
        } else {
            detachCrosshair()
            toast("准星已收起")
        }
        startAsForeground()
    }

    /** @return 是否成功挂上窗口 */
    private fun attachCrosshair(): Boolean {
        if (crosshairRoot != null) return true
        if (!::windowManager.isInitialized) return false

        val view = CrosshairView(this)
        if (!view.load(prefs.crosshairImage)) return false
        view.setAnchor(prefs.crosshairAnchorX, prefs.crosshairAnchorY)
        view.setShowLines(prefs.crosshairLines)

        val widthPx = resolveCrosshairWidth(view)
        val heightPx = crosshairHeight(view, widthPx)
        val savedX = prefs.crosshairX
        val savedY = prefs.crosshairY
        // v2.7.2：没存过位置时按「瞄准点落在屏幕正中」摆，而不是把窗口居中。
        // 锚点不是 0.5 时（用户在图上点过自定义瞄准点），窗口居中会让瞄准点偏出去。
        val startX = if (validCoord(savedX)) savedX
        else (screenWidth() / 2f - prefs.crosshairAnchorX * widthPx).roundToInt()
        val startY = if (validCoord(savedY)) savedY
        else (screenHeight() / 2f - prefs.crosshairAnchorY * heightPx).roundToInt()

        val p = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayWindowType(),
            crosshairFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
            alpha = prefs.crosshairAlpha
        }
        crosshairParams = p
        clampCrosshairPosition()

        val root = LongPressDragRootLayout(
            context = this,
            paramsProvider = { crosshairParams },
            // 锁定 = 点击穿透，窗口根本收不到触摸，这里顺手禁用拖动状态机
            enabled = { !prefs.crosshairLocked },
            onMove = {
                clampCrosshairPosition()
                safeCrosshairUpdate()
            },
            onEnd = { persistCrosshairGeometry() },
            // v2.5.4：准星窗里没有任何可点内容，按下就该跟手走，不该再等 350ms 长按
            directDrag = true,
            // v2.7.2：按下先刷新真实屏幕范围，横屏游戏里也能拖到正中
            onGestureStart = { refreshScreenSize() }
        )
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return try {
            windowManager.addView(root, p)
            crosshairRoot = root
            crosshairView = view
            crosshairAnchorX = prefs.crosshairAnchorX
            crosshairAnchorY = prefs.crosshairAnchorY
            crosshairAttached = true
            // 只淡入不缩放：缩放会让瞄准点先偏一下再归位，准星最忌这个
            val ms = MotionPrefs.duration(WINDOW_ENTER_MS)
            if (ms <= 0L) {
                root.alpha = 1f
            } else {
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(ms).start()
            }
            true
        } catch (t: Throwable) {
            crosshairParams = null
            false
        }
    }

    private fun detachCrosshair() {
        val root = crosshairRoot ?: return
        persistCrosshairGeometry()
        runCatching { root.resetTouchState() }
        crosshairRoot = null
        crosshairView = null
        crosshairParams = null
        crosshairAttached = false
        val exitMs = MotionPrefs.duration(WINDOW_EXIT_MS)
        if (exitMs <= 0L) {
            runCatching { if (root.isAttachedToWindow) windowManager.removeViewImmediate(root) }
        } else {
            root.animate()
                .alpha(0f)
                .setDuration(exitMs)
                .withEndAction {
                    runCatching { if (root.isAttachedToWindow) windowManager.removeViewImmediate(root) }
                }
                .start()
        }
    }

    private fun crosshairFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (prefs.crosshairLocked) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    /**
     * 显示宽度。用户调过就用用户的值；没调过按图片原始宽度，但限制在
     * [24dp, 96% 屏宽]，并且默认不超过半屏 —— 一张 4K 截图直接铺满屏没法用。
     */
    private fun resolveCrosshairWidth(view: CrosshairView): Int {
        val srcW = view.sourceWidth.coerceAtLeast(1)
        val srcH = view.sourceHeight.coerceAtLeast(1)
        val minW = dp(24)
        val maxW = (screenWidth() * 0.96f).toInt().coerceAtLeast(minW)
        val wanted = prefs.crosshairWidth.takeIf { it > 0 }
            ?: minOf(srcW, (screenWidth() * 0.45f).toInt()).coerceAtLeast(dp(120))
        var width = wanted.coerceIn(minW, maxW)
        // 超长图（截图当准星）按屏高 90% 反推宽度，别让窗口比屏幕还长
        val maxH = (screenHeight() * 0.9f).toInt()
        if ((width.toFloat() * srcH / srcW) > maxH) {
            width = (maxH.toFloat() * srcW / srcH).toInt().coerceIn(minW, maxW)
        }
        return width
    }

    private fun crosshairHeight(view: CrosshairView, widthPx: Int): Int {
        val srcW = view.sourceWidth.coerceAtLeast(1)
        val srcH = view.sourceHeight.coerceAtLeast(1)
        return (widthPx.toFloat() * srcH / srcW).toInt().coerceAtLeast(1)
    }

    private fun safeCrosshairUpdate() {
        val p = crosshairParams ?: return
        val root = crosshairRoot ?: return
        runCatching { if (root.isAttachedToWindow) windowManager.updateViewLayout(root, p) }
    }

    private fun clampCrosshairPosition() {
        val p = crosshairParams ?: return
        val keep = dp(24)
        // v2.7.3：上界改用「长边」，不再用被识别错的 screenWidth。
        //
        // 屏幕一旦被报成竖屏值（1080×2400）或 App 小窗的宽度，原来的
        // screenWidth() - keep 就会在半路形成一堵墙：手指还在往外推，准星却一步不动。
        // 长边是物理尺寸，横竖屏都不会小于真实宽高 —— 宁可多给一点空间，
        // 也不要把准星钉在半路。真拖到屏外了，点「居中显示」就能拉回来。
        val span = maxOf(screenWidth(), screenHeight())
        val maxX = span - keep
        val maxY = span - keep
        val minX = (-p.width + keep).coerceAtMost(maxX)
        val minY = (-p.height + keep).coerceAtMost(maxY)
        p.x = p.x.coerceIn(minX, maxX)
        p.y = p.y.coerceIn(minY, maxY)
    }

    private fun persistCrosshairGeometry() {
        val p = crosshairParams ?: return
        prefs.crosshairX = p.x
        prefs.crosshairY = p.y
    }

    /**
     * 图片 / 锚点 / 大小 / 透明度 / 十字线 / 锁定 任一变化后走这里。
     *
     * 核心：保持「瞄准点在屏幕上的位置」不动。换锚点、改大小时用户正对着目标，
     * 图不能自己跑偏 —— 先用旧锚点把瞄准点换算成屏幕坐标，再按新锚点回推左上角。
     */
    private fun refreshCrosshairFromPrefs() {
        val root = crosshairRoot ?: return
        val view = crosshairView ?: return
        val p = crosshairParams ?: return
        if (!root.isAttachedToWindow) return

        if (view.loadedPath != prefs.crosshairImage) {
            if (!view.load(prefs.crosshairImage)) {
                // 图片被删了 / 读不出来：与其在屏幕上留一块空白，不如如实收起
                detachCrosshair()
                prefs.crosshairShown = false
                return
            }
        }

        val oldW = p.width
        val oldH = p.height
        val oldAx = crosshairAnchorX
        val oldAy = crosshairAnchorY
        val newW = resolveCrosshairWidth(view)
        val newH = crosshairHeight(view, newW)
        val newAx = prefs.crosshairAnchorX
        val newAy = prefs.crosshairAnchorY

        view.setAnchor(newAx, newAy)
        view.setShowLines(prefs.crosshairLines)

        // v2.7.1：尺寸和锚点都没变时，位置一个像素都不碰。
        //
        // 旧版不管改的是什么（锁定开关、透明度、十字线）都要走一遍
        // 「瞄准点换算 → 左上角回推」。只要浮点取整、或窗口实际宽度与 p.width 有一点对不上，
        // 准星就会自己挪一段 —— 用户看到的就是「点了固定位置，准星突然跳到别处」。
        // 现在只有真换了尺寸 / 换了锚点才重算。
        val geometryChanged = newW != oldW || newH != oldH || newAx != oldAx || newAy != oldAy
        if (geometryChanged) {
            // 原则仍是「瞄准点在屏幕上原地不动」，但改用 p.x/p.y 直接加差值，
            // 少一次放大回推，边界上的取整更稳。
            p.x = (p.x + (oldAx * oldW - newAx * newW)).roundToInt()
            p.y = (p.y + (oldAy * oldH - newAy * newH)).roundToInt()
            p.width = newW
            p.height = newH
            crosshairAnchorX = newAx
            crosshairAnchorY = newAy
            clampCrosshairPosition()
        }

        p.alpha = prefs.crosshairAlpha
        p.flags = crosshairFlags()
        safeCrosshairUpdate()
        if (geometryChanged) persistCrosshairGeometry()
    }

    /**
     * v2.7.1：只切「锁定（点击穿透）」，不重读外观。
     *
     * 锁定 / 解锁本质只改窗口 flags 一位，但旧版走的是 [refreshCrosshairFromPrefs]，
     * 会顺带把几何重算一遍 —— 这是「点固定位置，准星瞬移」的直接原因。
     */
    private fun applyCrosshairLock(locked: Boolean) {
        prefs.crosshairLocked = locked
        val p = crosshairParams ?: return
        p.flags = crosshairFlags()
        safeCrosshairUpdate()
        // 锁上时如果还残留拖动状态，下次解锁第一下会白吞一次手势
        if (locked) crosshairRoot?.resetTouchState()
    }

    /**
     * v2.7.1：把准星层提到所有悬浮窗最前面。
     *
     * 同为 TYPE_APPLICATION_OVERLAY 的窗口，Z 序就是 addView 的先后 —— 后加的压在上面。
     * 小抄窗收起 / 展开、连招窗重建都会重新 addView，于是跑到准星头上：准星还看得见
     * （下层透明图透上来），但那一整块触摸被上层窗吃掉，怎么拖都不动。
     * 用户原话「打开小窗的时候移动不了，没打开小窗的时候就移动得了」就是这个。
     * WindowManager 没有 bringToFront，只能先摘再挂，让它排到队尾。
     */
    private fun bringCrosshairToFront() {
        val root = crosshairRoot ?: return
        val p = crosshairParams ?: return
        if (!root.isAttachedToWindow) return
        runCatching {
            root.resetTouchState()
            windowManager.removeViewImmediate(root)
            windowManager.addView(root, p)
        }.onFailure { Log.w("YunX-Crosshair", "bringToFront: ${it.message}") }
    }

    private fun centerCrosshairLayer() {
        val p = crosshairParams ?: return
        // v2.7.1：锁定时窗口带 FLAG_NOT_TOUCHABLE，居中之后怎么按都拖不动，只会以为坏了。
        // 居中本来就是为了重新摆位置，顺手把锁定解掉，并在提示里说清楚。
        val wasLocked = prefs.crosshairLocked
        if (wasLocked) {
            prefs.crosshairLocked = false
            p.flags = crosshairFlags()
        }
        // v2.7.3：居中前强制重取一次，不吃 700ms 的缓存 —— 刚切横屏 / 刚退出小窗时
        // 缓存里可能还是上一套尺寸，一按居中就摆偏。
        refreshScreenSize()
        // v2.7.2：居中的是「瞄准点」，不是窗口。锚点不在 0.5 时两者能差半个屏幕。
        p.x = (screenWidth() / 2f - crosshairAnchorX * p.width).roundToInt()
        p.y = (screenHeight() / 2f - crosshairAnchorY * p.height).roundToInt()
        safeCrosshairUpdate()
        persistCrosshairGeometry()
        // v2.7.3：把识别到的屏幕尺寸说出来 —— 居中偏了的时候，用户一眼就能看出
        // 是宽高反了（2400×1080 变成 1080×2400），长按这颗按钮即可对调。
        val size = "${screenWidth()}×${screenHeight()}"
        toast(
            if (wasLocked) "准星已居中（识别屏幕 $size），顺手解除了锁定"
            else "准星已居中（识别屏幕 $size）"
        )
    }

    private fun resetCrosshairLayer() {
        prefs.crosshairAnchorX = 0.5f
        prefs.crosshairAnchorY = 0.5f
        prefs.crosshairWidth = 0
        prefs.crosshairAlpha = 1f
        prefs.crosshairLines = true
        prefs.crosshairLocked = false
        if (crosshairRoot != null) {
            crosshairAnchorX = 0.5f
            crosshairAnchorY = 0.5f
            refreshCrosshairFromPrefs()
            centerCrosshairLayer()
        }
    }

    /** 「自动连招」按钮：只动连招层，打开即是可操作的面板 */
    private fun setMacroWindowShown(show: Boolean) {
        prefs.macroWindowShown = show
        if (show) {
            // 用户主动点「开启」：解除隐藏 → 保证有一个展开的面板 → 坐标拉回屏内。
            // 任何一步失败都把偏好写回 false，主界面不会停在「已开启」的假状态。
            revealWindows()
            if (!ensureMacroPanelVisible()) {
                prefs.macroWindowShown = false
                toast("连招悬浮窗创建失败，请检查悬浮窗权限")
                startAsForeground()
                return
            }
            toast("自动连招已打开")
        } else {
            detachMacroWindow()
            toast("自动连招已收起")
        }
        startAsForeground()
    }

    /**
     * v1.9.2：把连招层修正成「展开、可见、在屏幕内」的确定状态。
     * 旧逻辑在 macroRoot != null 且未缩小时直接什么都不做，一旦窗口被系统回收 /
     * detach 过而引用残留，就会「状态已开启、屏幕没窗口」，用户再点一次反而变成收起。
     */
    private fun ensureMacroPanelVisible(): Boolean {
        if (macroRoot == null) {
            macroCollapsed.value = false
            prefs.macroCollapsed = false
            if (!attachMacroWindow()) return false
        } else if (macroCollapsed.value) {
            if (macroParams == null) {
                detachMacroWindow()
                macroCollapsed.value = false
                prefs.macroCollapsed = false
                if (!attachMacroWindow()) return false
            } else {
                expandMacro()
            }
        }
        // 换分辨率 / 分屏 / 旋转后持久化坐标可能落到屏幕外，拉回可见区域
        macroParams?.let {
            clampMacroPosition()
            safeMacroUpdate()
        }
        return macroRoot != null
    }

    /** 通知栏那颗按钮：只切连招层 */
    private fun toggleMacroWindow() = setMacroWindowShown(!prefs.macroWindowShown)

    /** @return 是否成功挂上窗口 */
    private fun attachMacroWindow(): Boolean {
        if (macroRoot != null) return true
        if (!::windowManager.isInitialized) return false
        val sw = screenWidth()
        val sh = screenHeight()
        val isBubble = macroCollapsed.value
        val bubble = dp(BUBBLE_DP)

        val w: Int
        val h: Int
        val startX: Int
        val startY: Int
        if (isBubble) {
            w = bubble
            h = bubble
            val bx = prefs.macroBubbleX
            val by = prefs.macroBubbleY
            startX = if (validCoord(bx)) bx else (sw - bubble - dp(12))
            startY = if (validCoord(by)) by else (sh - bubble) / 2
        } else {
            val saved = prefs.restoreMacroWindow()
            val defaultW = (sw * 0.62f).toInt()
                .coerceIn(macroMinW(), macroMaxW())
            val defaultH = (sh * 0.46f).toInt()
                .coerceIn(macroMinH(), macroMaxH())
            w = if (saved.width > 0) saved.width.coerceIn(macroMinW(), macroMaxW()) else defaultW
            h = if (saved.height > 0) saved.height.coerceIn(macroMinH(), macroMaxH()) else defaultH
            startX = if (validCoord(saved.x)) saved.x else (sw - w - dp(8))
            startY = if (validCoord(saved.y)) saved.y else (sh - h) / 2 + dp(40)
        }

        val p = WindowManager.LayoutParams(
            w,
            h,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
            alpha = 1f
        }
        macroParams = p
        clampMacroPosition()

        // 用 LongPressDragRootLayout 而不是 OverlayRootLayout：连招层不参与双指缩放
        // 状态机；这层只做「长按整窗拖动」拦截，短按 / 滑杆 / 拉伸角全部透传给子控件，
        // 两窗口各有一套手势、互不抢事件，也不会复现 v1.4.0 修过的「卡住」。
        val root = LongPressDragRootLayout(
            context = this,
            paramsProvider = { macroParams },
            enabled = { !macroCollapsed.value },
            onMove = { clampMacroPosition(); safeMacroUpdate() },
            onEnd = { persistMacroGeometry() },
            // v2.11.2：连招弹窗三边（左 / 右 / 下）按住就拖。
            // 群友原话「只有最顶端中间那一部分可以移动」—— 顶栏那条 38dp 太窄，
            // 窗口缩小后更难戳中；给一圈 14dp 的边缘热区，像拖电脑窗口一样。
            edgeDragDp = 14f,
            edgeDragSides = LongPressDragRootLayout.SIDE_LEFT or
                LongPressDragRootLayout.SIDE_RIGHT or
                LongPressDragRootLayout.SIDE_BOTTOM,
            edgeDragTopBarDp = 44f
        ).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
        }
        val compose = ComposeView(this).apply {
            setContent {
                OverlayTheme {
                    val isCollapsed by macroCollapsed.collectAsState()
                    if (isCollapsed) {
                        MacroBubbleContent(
                            onTap = ::expandMacro,
                            onDrag = ::dragMacroBubble,
                            onDragEnd = ::persistMacroBubble
                        )
                    } else {
                        MacroWindowContent(
                            dragTouchListener = macroDragTouchListener,
                            resizeTouchListener = macroResizeTouchListener,
                            onCollapse = ::collapseMacro,
                            onClose = { setMacroWindowShown(false) }
                        )
                    }
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.alpha = 0f
        root.scaleX = WINDOW_ENTER_SCALE
        root.scaleY = WINDOW_ENTER_SCALE
        return try {
            windowManager.addView(root, p)
            macroRoot = root
            macroWindowAttached = true
            playWindowEnter(root)
            bringCrosshairToFront()
            true
        } catch (e: Exception) {
            toast("连招悬浮窗创建失败：${e.message}")
            macroRoot = null
            macroParams = null
            false
        }
    }

    private fun detachMacroWindow() {
        persistMacroGeometry()
        // 和攻略层同一套：先缩小淡出再摘，避免「啪」地消失
        val closing = macroRoot
        macroRoot = null
        macroParams = null
        macroWindowAttached = false
        closing?.let {
            it.resetTouchState()
            // v2.1.0：动画关掉就直接摘窗
            val exitMs = MotionPrefs.duration(WINDOW_EXIT_MS)
            if (exitMs <= 0L) {
                runCatching { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
            } else {
                it.animate()
                    .alpha(0f)
                    .scaleX(WINDOW_EXIT_SCALE)
                    .scaleY(WINDOW_EXIT_SCALE)
                    .setDuration(exitMs)
                    .withEndAction {
                        runCatching { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
                    }
                    .start()
            }
        }
    }

    // ------------------------------------------------------------------
    // 连招球 ⇄ 连招窗：只改这一层的 params 与 Compose 内容，绝不碰攻略窗
    // ------------------------------------------------------------------

    /** 连招窗点「缩小」：记住展开几何，把这一层换成小球 */
    private fun collapseMacro() {
        val p = macroParams ?: return
        if (macroCollapsed.value) return
        prefs.macroExpandedW = p.width
        prefs.macroExpandedH = p.height
        prefs.saveMacroWindow(
            OverlayPrefs.WindowGeometry(p.width, p.height, p.x, p.y, 1f)
        )
        val bubble = dp(BUBBLE_DP)
        val centerX = p.x + p.width / 2
        val centerY = p.y + p.height / 2
        p.width = bubble
        p.height = bubble
        p.x = centerX - bubble / 2
        p.y = centerY - bubble / 2
        clampMacroPosition()
        safeMacroUpdate()
        macroCollapsed.value = true
        prefs.macroCollapsed = true
        prefs.macroBubbleX = p.x
        prefs.macroBubbleY = p.y
    }

    /** 点连招球：恢复成缩小前的连招窗 */
    private fun expandMacro() {
        val p = macroParams ?: return
        if (!macroCollapsed.value) return
        val defaultW = (screenWidth() * 0.62f).toInt().coerceIn(macroMinW(), macroMaxW())
        val defaultH = (screenHeight() * 0.46f).toInt().coerceIn(macroMinH(), macroMaxH())
        val w = prefs.macroExpandedW.takeIf { it > 0 } ?: defaultW
        val h = prefs.macroExpandedH.takeIf { it > 0 } ?: defaultH
        val centerX = p.x + p.width / 2
        val centerY = p.y + p.height / 2
        p.width = w.coerceIn(macroMinW(), macroMaxW())
        p.height = h.coerceIn(macroMinH(), macroMaxH())
        p.x = centerX - p.width / 2
        p.y = centerY - p.height / 2
        // v2.11.2：同攻略窗，球贴屏幕右边时展开不再把窗推到屏幕外
        clampExpandedOnScreen(p)
        safeMacroUpdate()
        macroCollapsed.value = false
        prefs.macroCollapsed = false
        persistMacroGeometry()
        // v2.0.8：球 → 窗也是一次「界面切换」，给它一个长出来的手感
        macroRoot?.let { playWindowPop(it) }
    }

    private fun dragMacroBubble(dx: Float, dy: Float) {
        val p = macroParams ?: return
        p.x += dx.toInt()
        p.y += dy.toInt()
        clampMacroPosition()
        safeMacroUpdate()
    }

    private fun persistMacroBubble() {
        val p = macroParams ?: return
        prefs.macroBubbleX = p.x
        prefs.macroBubbleY = p.y
    }

    private val macroDragTouchListener = View.OnTouchListener { _, event ->
        val p = macroParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                macroDragStartX = p.x
                macroDragStartY = p.y
                macroDragDownRawX = event.rawX
                macroDragDownRawY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                p.x = (macroDragStartX + (event.rawX - macroDragDownRawX)).toInt()
                p.y = (macroDragStartY + (event.rawY - macroDragDownRawY)).toInt()
                clampMacroPosition()
                safeMacroUpdate()
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                persistMacroGeometry()
                true
            }

            else -> false
        }
    }

    private val macroResizeTouchListener = View.OnTouchListener { _, event ->
        val p = macroParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                macroResizeStartW = p.width
                macroResizeStartH = p.height
                macroResizeDownRawX = event.rawX
                macroResizeDownRawY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                applyMacroSize(
                    macroResizeStartW + (event.rawX - macroResizeDownRawX).toInt(),
                    macroResizeStartH + (event.rawY - macroResizeDownRawY).toInt()
                )
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                persistMacroGeometry()
                true
            }

            else -> false
        }
    }

    /** 拖右下角：左上角固定，宽高各自自由变化 */
    private fun applyMacroSize(targetW: Int, targetH: Int) {
        val p = macroParams ?: return
        val upperW = maxOf(macroMinW(), minOf(macroMaxW(), screenWidth() - p.x))
        val upperH = maxOf(macroMinH(), minOf(macroMaxH(), screenHeight() - p.y))
        p.width = targetW.coerceIn(macroMinW(), upperW)
        p.height = targetH.coerceIn(macroMinH(), upperH)
        clampMacroPosition()
        safeMacroUpdate()
    }

    private fun clampMacroPosition() {
        val p = macroParams ?: return
        val minVisible = dp(40)
        p.x = p.x.coerceIn(-p.width + minVisible, screenWidth() - minVisible)
        p.y = p.y.coerceIn(-p.height + minVisible, screenHeight() - minVisible)
    }

    private fun safeMacroUpdate() {
        val root = macroRoot ?: return
        val p = macroParams ?: return
        if (!root.isAttachedToWindow) return
        runCatching { windowManager.updateViewLayout(root, p) }
    }

    private fun persistMacroGeometry() {
        val p = macroParams ?: return
        if (macroCollapsed.value) {
            // 球态只记球位置，别把 58dp 覆写成「展开尺寸」，否则点球展开会变成小球大小
            prefs.macroBubbleX = p.x
            prefs.macroBubbleY = p.y
        } else {
            prefs.saveMacroWindow(
                OverlayPrefs.WindowGeometry(p.width, p.height, p.x, p.y, 1f)
            )
            prefs.macroExpandedW = p.width
            prefs.macroExpandedH = p.height
        }
    }

    private fun macroMinW() = dp(220)
    private fun macroMaxW() = (screenWidth() * 0.92f).toInt()
    private fun macroMinH() = dp(200)
    private fun macroMaxH() = (screenHeight() * 0.82f).toInt()

    private fun attachOverlay() {
        val sw = screenWidth()
        val sh = screenHeight()
        val saved = prefs.restoreWindow()

        val defaultWidth = (sw * WIDTH_RATIO).toInt()
        val bubble = dp(BUBBLE_DP)
        // 磁盘里可能留着上一版 / 异常退出时的脏几何（Int.MIN_VALUE、超大值、负数），
        // 直接拿去建窗口会让窗口跑到屏幕外或尺寸异常，看起来就像「点不动」。
        val width = if (collapsed.value) bubble else sanitizeWidth(saved.width, defaultWidth)
        val height = if (collapsed.value) bubble else sanitizeHeight(saved.height, (width * ASPECT).toInt())

        params = WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (validCoord(saved.x)) saved.x else (sw - width) / 2
            y = if (validCoord(saved.y)) saved.y else (sh - height) / 2
            alpha = if (collapsed.value) 1f else saved.alpha.coerceIn(OverlayPrefs.MIN_ALPHA, 1f)
        }
        windowAlpha.value = params.alpha
        clampPosition()

        buildAndAttachRoot()
    }

    /** 重建窗口时复用：始终保证 WindowManager 里只有一层 rootView */
    private fun buildAndAttachRoot() {
        // 最外层套一层「长按整窗拖动」；内层仍是原来那套双指缩放 + 自愈状态机，
        // 只多了一个父 View，OverlayRootLayout 内部逻辑一行没改。
        val dragRoot = LongPressDragRootLayout(
            context = this,
            paramsProvider = { if (::params.isInitialized) params else null },
            // 球态有 BubbleContent 自己的拖动；双击铺满是临时几何，别写进磁盘
            enabled = { !collapsed.value && beforeMaximize == null },
            onMove = { clampPosition(); safeUpdate() },
            onEnd = { persistGeometry() },
            onGestureStart = { refreshScreenSize() },
            onTinyLongPress = {
                // v2.5.0：缩到 120dp 以下时界面基本没法点，长按改为「恢复默认大小」
                if (::params.isInitialized && params.width <= dp(TINY_WIDTH_DP)) {
                    restoreDefaultSize()
                    true
                } else {
                    false
                }
            },
            // v2.11.2：左右边缘按住就拖。底边不参与 —— 那一排是透明 / 大小双滑杆，
            // 从最底下 9dp 起手滑滑杆的概率远高于拖窗。
            edgeDragDp = 9f,
            edgeDragSides = LongPressDragRootLayout.SIDE_LEFT or
                LongPressDragRootLayout.SIDE_RIGHT,
            edgeDragTopBarDp = 46f
        )
        dragRoot.setViewTreeLifecycleOwner(host)
        dragRoot.setViewTreeViewModelStoreOwner(host)
        dragRoot.setViewTreeSavedStateRegistryOwner(host)

        val pinch = OverlayRootLayout(this)
        pinch.setViewTreeLifecycleOwner(host)
        pinch.setViewTreeViewModelStoreOwner(host)
        pinch.setViewTreeSavedStateRegistryOwner(host)
        pinch.addView(
            buildComposeView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        dragRoot.addView(
            pinch,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootView = dragRoot
        pinchRoot = pinch
        // v2.0.8：先把这一层摆成「小一号 + 全透明」，addView 之后再用 240ms 长回来。
        // 只动 View 的 alpha，不动 params.alpha —— 后者会写进 WindowManager，
        // 动画被打断时窗口可能停在 0，用户看到的是「点了没反应」。
        dragRoot.alpha = 0f
        dragRoot.scaleX = WINDOW_ENTER_SCALE
        dragRoot.scaleY = WINDOW_ENTER_SCALE
        try {
            windowManager.addView(dragRoot, params)
            guideAttached = true
            guideWindowAttached = true
            playWindowEnter(dragRoot)
            // 新挂上来的这层会压在准星头上：把准星提回去，免得「看得见拖不动」
            bringCrosshairToFront()
        } catch (e: Exception) {
            // 权限在检查之后被撤销、或厂商 ROM 额外限制：只放弃这一层，不牵连连招层
            guideAttached = false
            toast("悬浮窗创建失败：${e.message}")
        }
    }

    /**
     * v2.0.8：窗口级入场 —— 从 94% 长到 100%，同时整层淡入。
     *
     * 为什么还要这一层：Compose 的碎块只盖在「内容」上，窗口边缘是硬切的，
     * 小抄和连招互相切的时候看着像贴图换了一张。把整个 root 一起缩放，
     * 感知上才是「这扇窗出来了」。
     *
     * 用 View 的 alpha 而不是 `params.alpha`：后者会写进 WindowManager，
     * 动画中途被打断（销毁 / 切后台）时窗口可能停在 0，用户看到的就是
     * 「点了按钮什么都没发生」。View 的 alpha 只影响绘制，最坏也只是画得淡。
     */
    private fun playWindowEnter(view: View) {
        val ms = MotionPrefs.duration(WINDOW_ENTER_MS)
        if (ms <= 0L) {
            // v2.1.0：动画关掉 —— 调用方已把这一层摆成「小一号 + 全透明」，
            // 这里必须直接落到终态，否则窗口会一直停在透明
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ms)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /**
     * v2.0.8：已经在屏幕上的窗口「重新长一下」。
     *
     * 球展开成面板时窗口并没有重新 attach，只有尺寸变了 —— 如果什么都不做，
     * 就是「啪」地从小圆变成大矩形，很生硬。这里只补缩放、不补 alpha：
     * 窗口本来就可见，alpha 从 0 起会闪一下白。
     */
    private fun playWindowPop(view: View) {
        val ms = MotionPrefs.duration(WINDOW_POP_MS)
        if (ms <= 0L) {
            // v2.1.0：动画关掉就直接维持原尺寸，不做那一下缩放
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = 0.93f
        view.scaleY = 0.93f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ms)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun buildComposeView(): ComposeView = ComposeView(this).apply {        setContent {
            OverlayTheme {
                val state by viewModel.state.collectAsState()
                val alphaValue by windowAlpha.collectAsState()
                val isCollapsed by collapsed.collectAsState()
                val scale by sizeScale.collectAsState()
                val icon by bubbleIcon.collectAsState()
                if (isCollapsed) {
                    BubbleContent(
                        iconPath = icon,
                        onTap = ::expand,
                        onDrag = ::dragBubble,
                        onDragEnd = ::persistGeometry,
                        // v2.5.4：长按小球换图标，选完图立刻应用到桌面上的这颗球
                        onLongPress = { MacroIconPickerActivity.startForBubble(this@FloatingService) }
                    )
                } else {
                    FloatingContent(
                        state = state,
                        actions = overlayActions,
                        dragTouchListener = dragTouchListener,
                        resizeTouchListener = resizeTouchListener,
                        alpha = alphaValue,
                        onAlphaChange = ::setWindowAlpha,
                        sizeScale = scale,
                        onSizeChange = ::setSizeScale
                    )
                }
            }
        }
    }

    /**
     * 单指拖动：只挂在「移动区域」那个透明 View 上。
     *
     * 用 rawX/rawY 而不是 x/y：窗口会跟着手指移动，x/y 是相对窗口的，
     * 每帧都在变，累加会抖；rawX/rawY 是屏幕坐标，Δ 就是真实的位移。
     */
    private val dragTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = params.x
                dragStartY = params.y
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                params.x = (dragStartX + (event.rawX - dragDownRawX)).toInt()
                params.y = (dragStartY + (event.rawY - dragDownRawY)).toInt()
                clampPosition()
                safeUpdate()
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                persistGeometry()
                true
            }

            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 右下角拉伸手柄：像电脑窗口那样拖角改尺寸
    // 与「双指缩放 / 大小滑杆」的区别：拖角是左上角固定、宽高各自自由变化；
    // 捏合和滑杆是以窗口中心为锚、并锁 0.62 高宽比。
    // ------------------------------------------------------------------

    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeDownRawX = 0f
    private var resizeDownRawY = 0f

    private val resizeTouchListener = View.OnTouchListener { _, event ->
        // 缩成球、或双击铺满期间不允许拖角，免得把临时几何写进磁盘
        if (collapsed.value || beforeMaximize != null) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartWidth = params.width
                resizeStartHeight = params.height
                resizeDownRawX = event.rawX
                resizeDownRawY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                applySize(
                    resizeStartWidth + (event.rawX - resizeDownRawX).toInt(),
                    resizeStartHeight + (event.rawY - resizeDownRawY).toInt()
                )
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                persistGeometry()
                true
            }

            else -> false
        }
    }

    private fun applySize(targetWidth: Int, targetHeight: Int) {
        if (collapsed.value || !::params.isInitialized) return
        // 尽量别让右下角拖出屏幕；maxOf 保证上界不小于下界，避免 coerceIn 抛异常
        val upperW = maxOf(minWidthPx(), minOf(maxWidthPx(), screenWidth() - params.x))
        val upperH = maxOf(minHeightPx(), minOf(maxHeightPx(), screenHeight() - params.y))
        params.width = targetWidth.coerceIn(minWidthPx(), upperW)
        params.height = targetHeight.coerceIn(minHeightPx(), upperH)
        // 左上角不动：clampPosition 只会在窗口快整体出屏时才把 x/y 拉回来
        clampPosition()
        safeUpdate()
        // 让底栏「窗」滑杆跟着实际尺寸走，避免拖完角滑杆再一跳
        sizeScale.value =
            (params.width / (screenWidth() * WIDTH_RATIO)).coerceIn(MIN_SIZE_SCALE, 1.25f)
    }

    private fun setWindowAlpha(value: Float) {
        val clamped = value.coerceIn(OverlayPrefs.MIN_ALPHA, 1f)
        windowAlpha.value = clamped
        params.alpha = clamped
        safeUpdate()
    }

    /**
     * 双指缩放整窗。
     *
     * 这里重写窗口根布局的 `dispatchTouchEvent`：单指原样交给 Compose，双指才接管。
     *
     * 曾经有个线上问题：缩放只在 ACTION_UP / ACTION_CANCEL 结束，而部分 ROM 在
     * `updateViewLayout` 之后、或双指中先抬一根手指时只给 ACTION_POINTER_UP，
     * 收尾事件丢失 ⇒ `scaling` 永远为 true ⇒ 之后**所有**单指事件都被这里吞掉，
     * 表现就是「用了一阵子突然滑不动，重开 App 也没用」（前台 Service 还活着，状态没清）。
     * 现在加了三重自愈：新 DOWN 强制复位、抬到只剩一指就结束、超时看门狗。
     */
    private inner class OverlayRootLayout(context: Context) : FrameLayout(context) {

        private var scaling = false
        private var scalingStartedAt = 0L
        private var gestureStartWidth = 0
        private var accumulatedScale = 1f

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (!windowPinchEnabled()) return false
                    gestureStartWidth = params.width
                    accumulatedScale = 1f
                    scaling = true
                    scalingStartedAt = SystemClock.uptimeMillis()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!scaling) return false
                    accumulatedScale = (accumulatedScale * detector.scaleFactor)
                        .coerceIn(MIN_SCALE, MAX_SCALE)
                    applyWidth((gestureStartWidth * accumulatedScale).toInt())
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    persistGeometry()
                }
            }
        ).apply { isQuickScaleEnabled = false }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked
            val now = SystemClock.uptimeMillis()

            // 自愈 1：都按下新的单指手势了，说明上一轮缩放早该结束
            if (action == MotionEvent.ACTION_DOWN && scaling) forceEndScaling()
            // 自愈 2：丢事件 / 厂商 ROM 异常时，不让缩放状态无限期吞掉触摸
            if (scaling && now - scalingStartedAt > MAX_SCALING_MS) forceEndScaling()

            if (scaling) {
                scaleDetector.onTouchEvent(ev)
                val finished = action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL ||
                    // 双指抬成单指：别赌最后那根手指的 ACTION_UP 一定能送到
                    (action == MotionEvent.ACTION_POINTER_UP && ev.pointerCount <= 2) ||
                    (action == MotionEvent.ACTION_MOVE && ev.pointerCount <= 1)
                if (finished) endScaling()
                return true
            }

            // 单指阶段只观察、不拦截
            runCatching { scaleDetector.onTouchEvent(ev) }
            if (scaling) {
                // onScaleBegin 刚返回 true：给 Compose 补 CANCEL，避免它卡在按下态
                cancelChildGesture()
                return true
            }
            return runCatching { super.dispatchTouchEvent(ev) }.getOrDefault(false)
        }

        private fun endScaling() {
            if (!scaling) return
            scaling = false
            resetDetector()
            cancelChildGesture()
            persistGeometry()
        }

        private fun forceEndScaling() = endScaling()

        /** 给 detector 补一个 CANCEL，清掉它内部的 in-progress 状态 */
        private fun resetDetector() {
            val cancel = MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0f, 0f, 0
            )
            runCatching { scaleDetector.onTouchEvent(cancel) }
            cancel.recycle()
        }

        /** Service 重建窗口 / 用户点「重置」时调用 */
        fun resetTouchState() {
            scaling = false
            accumulatedScale = 1f
            resetDetector()
        }

        private fun cancelChildGesture() {
            val cancel = MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0f, 0f, 0
            )
            runCatching { super.dispatchTouchEvent(cancel) }
            cancel.recycle()
        }
    }

    /**
     * 大图预览时把双指让给图片缩放，否则「捏合看细节」会变成「把窗口放大」。
     * 想让双指在任何时候都缩放窗口，把 [PINCH_WINDOW_ALWAYS] 改成 true 即可。
     */
    private fun windowPinchEnabled(): Boolean =
        !collapsed.value && (PINCH_WINDOW_ALWAYS || viewModel.state.value.previewIndex == null)

    /** 收掉取点/录制的全屏透明膜：它若残留会吃掉整屏触摸，是最容易误判成「卡死」的情况 */
    private fun dismissCaptureOverlay() {
        captureOverlay?.dismiss()
        captureOverlay = null
        keypadRecorder?.dismiss()
        keypadRecorder = null
    }

    // ------------------------------------------------------------------
    // v1.9.1 录制时自动缩小 / 录完自动放大
    // ------------------------------------------------------------------

    /**
     * 录制膜是全屏的，连招窗先临时缩成球，别挡住要比划的位置。
     * 只记「录制前是不是展开的」：如果用户本来就把连招窗收成球，录完不该替他摊开。
     */
    private fun beginCapture() {
        dismissCaptureOverlay()
        captureRestoreExpanded = macroRoot != null && !macroCollapsed.value
        if (captureRestoreExpanded) collapseMacro()
    }

    /** 录制完成 / 取消 / 由 onResult 或 cancelCapture 成对调用 */
    private fun endCapture(restore: Boolean) {
        val wasExpanded = captureRestoreExpanded
        captureRestoreExpanded = false
        if (restore) {
            if (wasExpanded && macroRoot != null && macroCollapsed.value) expandMacro()
            return
        }
        // v1.9.4：录完把「录制面板」整扇收掉，屏幕上只留新连招那颗小按钮。
        // 这样以后点小按钮执行时，录制窗绝不会跟着冒出来 —— 一扇窗只管一件事。
        prefs.macroWindowShown = false
        macroCollapsed.value = false
        prefs.macroCollapsed = false
        detachMacroWindow()
        // 通知栏那颗「连招窗」按钮的状态也跟着改回「未开」，别让通知显示假状态
        startAsForeground()
    }

    // ------------------------------------------------------------------
    // v1.9.1 音量键隐藏 / 显示
    // ------------------------------------------------------------------

    /**
     * 双击音量键或通知栏「显示」按钮走这里。
     * 用 alpha=0 + FLAG_NOT_TOUCHABLE，不 removeView：窗口的位置、拖动状态、
     * 用户调过的透明度在恢复时都能原样还原。
     */
    private fun setWindowsHidden(hidden: Boolean) {
        if (hidden && captureOverlay != null) {
            // 录制膜必须继续吃触摸，隐藏会让它失效甚至把触摸漏给游戏；先取消录制
            dismissCaptureOverlay()
            MacroController.cancelCapture()
        }
        windowsHidden = hidden
        applyWindowVisibility(hidden)
        // v2.5.6：帧率窗是独立一层，随音量键 / 通知栏一起隐藏或恢复
        fpsOverlay.setHidden(hidden)
        startAsForeground()
        toast(
            if (hidden) "悬浮窗已隐藏 · 双击音量键或点通知栏「显示」恢复"
            else "悬浮窗已显示"
        )
    }

    /**
     * v1.9.2：用户主动点「开启」时一律解除隐藏。
     *
     * 旧逻辑在开窗后判断 windowsHidden，再对新窗口调 applyWindowVisibility(true)，
     * 于是刚挂上的窗口立刻被设成 alpha=0 + FLAG_NOT_TOUCHABLE —— 用户看到的就是
     * 「点了一下啥也没有」。隐藏是「暂时别挡我」，不该让之后的开窗动作也失效。
     */
    private fun revealWindows() {
        if (!windowsHidden) return
        windowsHidden = false
        applyWindowVisibility(false)   // 从 hiddenOriginals 恢复原 alpha / flags
        // 兜底：隐藏期间新建的窗口没登记过 hiddenOriginals，也要保证可见可摸
        val guideLp = if (::params.isInitialized && guideAttached) params else null
        val macroLp = if (macroRoot != null) macroParams else null
        val crossLp = if (crosshairRoot != null) crosshairParams else null
        listOfNotNull(guideLp, macroLp).forEach { lp ->
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (lp.alpha <= 0f) {
                lp.alpha = if (lp === guideLp) {
                    windowAlpha.value.coerceIn(0.2f, 1f)
                } else {
                    1f
                }
            }
        }
        // 准星有自己的透明度与「锁定穿透」，不能照抄上面的一刀切
        crossLp?.let { lp ->
            lp.flags = crosshairFlags()
            if (lp.alpha <= 0f) lp.alpha = prefs.crosshairAlpha
        }
        if (guideLp != null && ::rootView.isInitialized && rootView.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(rootView, guideLp) }
        }
        if (macroLp != null && macroRoot != null) {
            runCatching { windowManager.updateViewLayout(macroRoot, macroLp) }
        }
        if (crossLp != null && crosshairRoot != null) {
            runCatching { windowManager.updateViewLayout(crosshairRoot, crossLp) }
        }
        // v2.5.6：解除隐藏时帧率窗跟着一起恢复可见可摸
        fpsOverlay.setHidden(false)
    }

    private fun applyWindowVisibility(hidden: Boolean) {
        val guideLp = if (::params.isInitialized && guideAttached) params else null
        val macroLp = if (macroRoot != null) macroParams else null
        val crossLp = if (crosshairRoot != null) crosshairParams else null
        listOfNotNull(guideLp, macroLp, crossLp).forEach { lp ->
            if (hidden) {
                if (!hiddenOriginals.containsKey(lp)) {
                    hiddenOriginals[lp] = lp.alpha to lp.flags
                }
                lp.alpha = 0f
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                hiddenOriginals.remove(lp)?.let { (alpha, flags) ->
                    lp.alpha = alpha
                    lp.flags = flags
                }
            }
        }
        if (guideLp != null && ::rootView.isInitialized && rootView.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(rootView, guideLp) }
        }
        if (macroLp != null && macroRoot != null) {
            runCatching { windowManager.updateViewLayout(macroRoot, macroLp) }
        }
        if (crossLp != null && crosshairRoot != null) {
            runCatching { windowManager.updateViewLayout(crosshairRoot, crossLp) }
        }
        macroBalls.values.forEach { it.setHidden(hidden) }
        // 点击圆点也是一层窗口，隐藏时一并收掉
        if (hidden) tapIndicator.dismiss()
    }

    /**
     * 一键复位。用户从通知栏 / 主页点「重置悬浮窗」时走这里：
     * 清残留膜 → 触摸状态归零 → 几何回默认值 → 硬重建窗口。
     * 前台 Service 不会因「重开 App」而重建，所以必须给一条不杀进程也能恢复的路。
     */
    private fun resetOverlay() {
        if (rebuilding || !::params.isInitialized) return
        // 攻略层没挂时不做重建，免得点「重置」反而把小抄又弹出来
        if (!guideAttached) {
            toast("小抄未开启，无需重置")
            return
        }
        rebuilding = true
        dismissCaptureOverlay()
        MacroController.cancelCapture()
        beforeMaximize = null

        if (::rootView.isInitialized && guideAttached) {
            runCatching { rootView.resetTouchState() }   // 清长按拖动状态
            pinchRoot?.resetTouchState()                 // 清双指缩放状态机
            if (rootView.isAttachedToWindow) {
                runCatching { windowManager.removeViewImmediate(rootView) }
            }
            guideAttached = false
        }

        val sw = screenWidth()
        val sh = screenHeight()
        val w = (sw * WIDTH_RATIO).toInt().coerceIn(minWidthPx(), maxWidthPx())
        val h = (w * ASPECT).toInt().coerceIn(minHeightPx(), maxHeightPx())
        params.width = w
        params.height = h
        params.x = (sw - w) / 2
        params.y = (sh - h) / 2
        params.alpha = 1f
        windowAlpha.value = 1f
        sizeScale.value = 1f
        collapsed.value = false
        prefs.collapsed = false
        prefs.expandedWidth = w
        prefs.expandedHeight = h
        prefs.expandedAlpha = 1f
        clampPosition()
        persistGeometry()

        // 延后一帧重建，让 removeViewImmediate 先真正从 WindowManager 摘掉
        mainHandler.post {
            if (!isRunning) {
                rebuilding = false
                return@post
            }
            runCatching { buildAndAttachRoot() }
            syncMacroBalls()
            rebuilding = false
        }
    }

    /** 磁盘几何可能被异常退出写坏，落窗前统一洗一遍 */
    private fun sanitizeWidth(raw: Int, fallback: Int): Int =
        (if (raw in 1..screenWidth() * 2) raw else fallback).coerceIn(minWidthPx(), maxWidthPx())

    private fun sanitizeHeight(raw: Int, fallback: Int): Int =
        (if (raw in 1..screenHeight() * 2) raw else fallback).coerceIn(minHeightPx(), maxHeightPx())

    private fun validCoord(value: Int): Boolean =
        value != Int.MIN_VALUE && value > -100_000 && value < 100_000

    private fun applyWidth(targetWidth: Int) {
        if (collapsed.value) return
        val width = targetWidth.coerceIn(minWidthPx(), maxWidthPx())
        // v2.5.0：高度只按比例走，不再被 minHeight 顶回去 ——
        // 否则缩到 28dp 时会变成「28 × 180」一条竖线，比例完全失真
        val height = (width * ASPECT).toInt().coerceAtLeast(1)

        // 以窗口中心为锚点缩放，避免每次捏合窗口都往左上角跑
        val centerX = params.x + params.width / 2
        val centerY = params.y + params.height / 2

        params.width = width
        params.height = height
        params.x = centerX - width / 2
        params.y = centerY - height / 2
        clampPosition()
        safeUpdate()
    }

    /**
     * v2.5.0：从「极小窗」恢复成默认大小。
     *
     * 以窗口中心为锚点，不会把用户摆好的位置甩到屏幕另一头。
     */
    private fun restoreDefaultSize() {
        if (!::params.isInitialized || collapsed.value) return
        val width = (screenWidth() * WIDTH_RATIO).toInt().coerceIn(minWidthPx(), maxWidthPx())
        val height = (width * ASPECT).toInt().coerceAtLeast(1)
        val centerX = params.x + params.width / 2
        val centerY = params.y + params.height / 2
        params.width = width
        params.height = height
        params.x = centerX - width / 2
        params.y = centerY - height / 2
        // v2.11.2：和球展开同一套，放大后别把窗留在屏幕外
        clampExpandedOnScreen(params)
        safeUpdate()
        persistGeometry()
        sizeScale.value = 1f
        toast("已恢复默认大小（长按还能再调）")
    }

    private fun clampPosition() {
        // 窗口可能只有 28dp 宽，此时「至少露 40dp」比整个窗口还大，会把 x 夹成非法区间
        val minVisible = minOf(dp(40), params.width.coerceAtLeast(1))
        params.x = params.x.coerceIn(-params.width + minVisible, screenWidth() - minVisible)
        params.y = params.y.coerceIn(-params.height + minVisible, screenHeight() - minVisible)
    }

    /**
     * v2.11.2：球 → 窗的收尾，把展开后的窗口整个拉回屏幕内。
     *
     * 旧版这里只调 clampPosition()（允许窗口大半个在屏幕外）。球贴着屏幕右边时，
     * 展开出来的窗右半截直接落在屏幕外：屏幕外的部分收不到触摸，顶栏按钮和拖动热区
     * 全摸不到，用户以为是「窗卡死了、怎么拖都不动」。
     *
     * 现在展开后强制完整可见；只有窗口本身比屏幕还大（最大 0.96 屏宽 × 0.9 屏高，
     * 正常不会）时才退化为贴边。
     */
    private fun clampExpandedOnScreen(p: WindowManager.LayoutParams) {
        val sw = screenWidth()
        val sh = screenHeight()
        val margin = dp(4)
        p.x = if (p.width >= sw) 0 else p.x.coerceIn(0, (sw - p.width).coerceAtLeast(0))
        p.y =
            if (p.height >= sh - margin * 2) margin
            else p.y.coerceIn(margin, (sh - p.height - margin).coerceAtLeast(margin))
    }

    private fun safeUpdate() {
        if (!::rootView.isInitialized || !rootView.isAttachedToWindow) return
        runCatching { windowManager.updateViewLayout(rootView, params) }
    }

    private fun persistGeometry() {
        if (!::params.isInitialized) return
        prefs.saveWindow(
            OverlayPrefs.WindowGeometry(
                width = params.width,
                height = params.height,
                x = params.x,
                y = params.y,
                alpha = params.alpha
            )
        )
    }

    // ------------------------------------------------------------------
    // 缩小成球 / 大小调节
    // ------------------------------------------------------------------

    /** 缩成一颗小球：记住原尺寸，把窗口换成正方形 */
    private fun collapse() {
        if (collapsed.value) return
        if (beforeMaximize != null) setMaximized(false)
        prefs.expandedAlpha = params.alpha
        prefs.expandedWidth = params.width
        prefs.expandedHeight = params.height

        val size = dp(BUBBLE_DP)
        val centerX = params.x + params.width / 2
        val centerY = params.y + params.height / 2
        params.width = size
        params.height = size
        params.x = centerX - size / 2
        params.y = centerY - size / 2
        params.alpha = 1f
        clampPosition()
        safeUpdate()

        collapsed.value = true
        prefs.collapsed = true
        persistGeometry()
    }

    /** 点小球：恢复到缩之前的尺寸与透明度 */
    private fun expand() {
        if (!collapsed.value) return
        val defaultWidth = (screenWidth() * WIDTH_RATIO).toInt()
        val width = prefs.expandedWidth.takeIf { it > 0 } ?: defaultWidth
        val height = prefs.expandedHeight.takeIf { it > 0 } ?: (width * ASPECT).toInt()

        val centerX = params.x + params.width / 2
        val centerY = params.y + params.height / 2
        params.width = width.coerceIn(minWidthPx(), maxWidthPx())
        params.height = height.coerceIn(minHeightPx(), maxHeightPx())
        params.x = centerX - params.width / 2
        params.y = centerY - params.height / 2
        params.alpha = prefs.expandedAlpha.coerceIn(OverlayPrefs.MIN_ALPHA, 1f)
        windowAlpha.value = params.alpha
        // v2.11.2：展开后必须整窗可见，否则右侧 / 顶部跑到屏幕外就拖不动、点不到
        clampExpandedOnScreen(params)
        safeUpdate()

        collapsed.value = false
        prefs.collapsed = false
        sizeScale.value = (params.width / (screenWidth() * WIDTH_RATIO)).coerceIn(MIN_SIZE_SCALE, 1.25f)
        persistGeometry()
    }

    /**
     * 双击大图：把窗口本身顶到接近全屏（96% × 90%），并保留原来的尺寸/位置。
     * 退出时原样还原，不会把用户精心摆好的小窗弄丢。
     */
    private fun setMaximized(max: Boolean) {
        if (collapsed.value) return
        if (max) {
            if (beforeMaximize != null) return
            beforeMaximize = intArrayOf(params.width, params.height, params.x, params.y)
            val w = (screenWidth() * 0.96f).toInt()
            val h = (screenHeight() * 0.90f).toInt()
            params.width = w
            params.height = h
            params.x = (screenWidth() - w) / 2
            params.y = (screenHeight() - h) / 2
        } else {
            val saved = beforeMaximize ?: return
            beforeMaximize = null
            params.width = saved[0]
            params.height = saved[1]
            params.x = saved[2]
            params.y = saved[3]
            clampPosition()
        }
        safeUpdate()
    }

    /** 底栏「大小」滑杆：按屏宽 0.8 的基准缩放，宽高保持 0.62 的宽扁比 */
    private fun setSizeScale(scale: Float) {
        if (collapsed.value) return
        val base = screenWidth() * WIDTH_RATIO
        val width = (base * scale).toInt().coerceIn(minWidthPx(), maxWidthPx())
        applyWidth(width)
        sizeScale.value = (width / base).coerceIn(MIN_SIZE_SCALE, 1.25f)
        persistGeometry()
    }

    private fun initialSizeScale(): Float {
        val width = prefs.restoreWindow().width.takeIf { it > 0 }
            ?: (screenWidth() * WIDTH_RATIO).toInt()
        return (width / (screenWidth() * WIDTH_RATIO)).coerceIn(MIN_SIZE_SCALE, 1.25f)
    }

    private fun dragBubble(dx: Float, dy: Float) {
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampPosition()
        safeUpdate()
    }

    /** 按当前「已置顶」的宏重建悬浮按钮；多余移除、新增补上、已存在则刷新文案 */
    private fun syncMacroBalls() {
        if (!::windowManager.isInitialized) return
        // v1.9.1：被单独关掉的连招不显示它的悬浮球
        val pinned = MacroController.macros.value.filter { it.pinned && it.enabled }
        val ids = pinned.map { it.id }.toSet()
        macroBalls.keys.filterNot { it in ids }.forEach { id ->
            macroBalls.remove(id)?.dismiss()
        }
        pinned.forEachIndexed { index, macro ->
            val existing = macroBalls[macro.id]
            if (existing == null) {
                MacroBall(
                    this,
                    windowManager,
                    overlayWindowType(),
                    macro.id,
                    macro,
                    ::screenWidth,
                    ::screenHeight,
                    defaultSlot = index
                ).also { ball ->
                    ball.show()
                    if (windowsHidden) ball.setHidden(true)
                    macroBalls[macro.id] = ball
                }
            } else {
                existing.update(macro)
            }
        }
    }

    private fun refreshMacroBalls() {
        macroBalls.values.forEach { it.refreshState() }
    }

    private fun retry() {
        val folder = viewModel.state.value.currentFolder
        if (folder == null) viewModel.refresh(force = true) else viewModel.retry()
    }

    private fun openLogin() {
        val target = when (viewModel.state.value.platform) {
            PanPlatform.QUARK -> QuarkLoginActivity::class.java
            PanPlatform.XUNLEI -> XunleiLoginActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        // v1.9.5：内容没变就直接返回。开窗 / 隐藏 / 录制结束都会走到这里，
        // 低端机上每次都重建 Notification + 5 个 PendingIntent 是白花的。
        val key = "${windowsHidden}/${prefs.macroWindowShown}/${prefs.guideWindowShown}"
        if (key == notifyKey) return
        notifyKey = key

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "悬浮窗",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "云小抄悬浮窗运行状态"
                    setShowBadge(false)
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val close = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reset = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingService::class.java).setAction(ACTION_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val macroToggle = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatingService::class.java).setAction(ACTION_TOGGLE_MACRO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // v1.9.1「点击显示」：隐藏时点通知正文或「显示」按钮，把窗口放回来
        val showWindows = PendingIntent.getService(
            this,
            4,
            Intent(this, FloatingService::class.java).setAction(ACTION_SET_VISIBILITY)
                .putExtra(EXTRA_VISIBILITY_SHOW, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_overlay)
            .setContentTitle("云小抄")
            .setContentText(
                if (windowsHidden) "悬浮窗已隐藏 · 点这里或双击音量键显示"
                else "小抄与自动连招各自独立开关；卡住点「重置」"
            )
            .setContentIntent(if (windowsHidden) showWindows else openApp)
            .addAction(0, "重置", reset)
            .apply {
                if (windowsHidden) {
                    addAction(0, "显示", showWindows)
                } else {
                    addAction(
                        0,
                        if (prefs.macroWindowShown) "收起连招" else "连招窗",
                        macroToggle
                    )
                }
            }
            .addAction(0, "关闭", close)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Android 14 起必须声明 FGS 类型；specialUse 需要在 Manifest 里配 property
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ------------------------------------------------------------------
    // 尺寸
    // ------------------------------------------------------------------

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private var screenW = 0
    private var screenH = 0
    private var screenSizeAt = 0L

    /**
     * v2.7.3：屏幕尺寸改用 display 级物理范围，并且能手动纠正方向。
     *
     * 前两版各踩一个坑，合起来正是用户说的「拖准星像撞上一堵墙」：
     *   · resources.displayMetrics 是 App 进程自己的 Configuration，横屏游戏里常常
     *     还是竖屏的 1080×2400；
     *   · currentWindowMetrics 在自由窗口 / 游戏助手小窗里返回的是 App 那个小窗的
     *     bounds，不是整块屏幕 —— 墙就落在小窗右边缘附近。
     *
     * Display.getRealMetrics 是 display 级物理尺寸，不受 App 窗口大小和进程配置影响，
     * 是这里唯一可信的来源。万一某台机器连它也报反（游戏强制横屏、系统 rotation
     * 没跟着变），长按「居中显示」可把宽高对调一次，结果记在 crosshairSwapScreen。
     */
    private fun refreshScreenSize() {
        screenSizeAt = SystemClock.uptimeMillis()
        var w = 0
        var h = 0

        // 1) display 级物理范围（首选）
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(m)
            if (m.widthPixels > 0 && m.heightPixels > 0) {
                w = m.widthPixels
                h = m.heightPixels
            }
        }

        // 2) 兜底：WindowManager 侧。自由窗口下拿到的可能只是 App 小窗的范围，
        //    但总比 0 强 —— 至少拖动、居中不会崩。
        if (w <= 0 || h <= 0) {
            runCatching {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.maximumWindowMetrics.bounds
                    if (b.width() > 0 && b.height() > 0) {
                        w = b.width()
                        h = b.height()
                    }
                }
                if (w <= 0 || h <= 0) {
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(m)
                    if (m.widthPixels > 0 && m.heightPixels > 0) {
                        w = m.widthPixels
                        h = m.heightPixels
                    }
                }
            }
        }

        if (w <= 0 || h <= 0) return
        // 手动校正：长按「居中显示」切换
        if (prefs.crosshairSwapScreen) {
            val t = w
            w = h
            h = t
        }
        screenW = w
        screenH = h
    }

    /** 惰性 + 700ms 节流：拖动时每帧都会问一次 clamp，不能每帧走一次 IPC */
    private fun screenWidth(): Int {
        if (screenW <= 0 || SystemClock.uptimeMillis() - screenSizeAt > 700L) refreshScreenSize()
        return if (screenW > 0) screenW else resources.displayMetrics.widthPixels
    }

    private fun screenHeight(): Int {
        if (screenH <= 0 || SystemClock.uptimeMillis() - screenSizeAt > 700L) refreshScreenSize()
        return if (screenH > 0) screenH else resources.displayMetrics.heightPixels
    }

    // v2.5.0：最小尺寸从 240×180dp 放开到 28dp —— 用户明确要求「想缩多小就缩多小」，
    // 哪怕缩到界面完全没法操作。极小窗靠长按恢复默认大小（见 buildAndAttachRoot）。
    private fun minWidthPx() = dp(MIN_WIDTH_DP)

    private fun maxWidthPx() = (screenWidth() * 0.96f).toInt()

    private fun minHeightPx() = dp(MIN_WIDTH_DP)

    private fun maxHeightPx() = (screenHeight() * 0.9f).toInt()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CHANNEL_ID = "overlay_guide"
        private const val NOTIFICATION_ID = 0x5A17

        /** 初始宽度 = 屏宽 0.8；高宽比 0.62，整体是「宽扁」的一块 */
        private const val WIDTH_RATIO = 0.8f
        private const val ASPECT = 0.62f

        /** 捏合缩放的总倍率范围（相对初始宽度） */
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 3.5f

        /** v2.5.0：窗口能缩到的最小边长（dp）。28dp 差不多一个图标那么大 */
        private const val MIN_WIDTH_DP = 28

        /** 底栏「窗」滑杆与 sizeScale 的下限，和 MIN_WIDTH_DP 对齐 */
        private const val MIN_SIZE_SCALE = 0.05f

        /** 小于这个宽度就算「极小窗」，长按改为恢复默认大小 */
        private const val TINY_WIDTH_DP = 120

        /** true = 任何状态下双指都缩放窗口（大图预览就不能捏合放大了） */
        private const val PINCH_WINDOW_ALWAYS = false

        /** 小抄球直径（dp） */
        private const val BUBBLE_DP = 58

        /** v2.0.8：窗口入场时长与起始缩放 */
        private const val WINDOW_ENTER_MS = 260L
        private const val WINDOW_POP_MS = 220L
        private const val WINDOW_ENTER_SCALE = 0.94f

        /** v2.0.8：窗口退场 —— 比入场快，收东西不该拖 */
        private const val WINDOW_EXIT_MS = 150L
        private const val WINDOW_EXIT_SCALE = 0.96f

        /** 双指缩放状态最长允许持续多久，超时自动放开，避免丢事件后永久吞触摸 */
        private const val MAX_SCALING_MS = 8_000L

        const val ACTION_STOP = "com.yunx.app.overlay.action.STOP"
        const val ACTION_REFRESH = "com.yunx.app.overlay.action.REFRESH"
        const val ACTION_RESET = "com.yunx.app.overlay.action.RESET"
        const val ACTION_DISMISS_CAPTURE = "com.yunx.app.overlay.action.DISMISS_CAPTURE"
        const val ACTION_TOGGLE_MACRO = "com.yunx.app.overlay.action.TOGGLE_MACRO"
        const val ACTION_SET_MACRO = "com.yunx.app.overlay.action.SET_MACRO"
        const val ACTION_SET_GUIDE = "com.yunx.app.overlay.action.SET_GUIDE"
        const val EXTRA_MACRO_SHOW = "macro_show"
        const val EXTRA_GUIDE_SHOW = "guide_show"

        /** v2.2.0 准星：开关 / 重读外观 / 居中 / 恢复默认 */
        const val ACTION_SET_CROSSHAIR = "com.yunx.app.overlay.action.SET_CROSSHAIR"
        const val ACTION_APPLY_CROSSHAIR = "com.yunx.app.overlay.action.APPLY_CROSSHAIR"
        /** v2.7.1：只切锁定（点击穿透），与「重读外观」分开，避免锁定瞬间重算几何 */
        const val ACTION_SET_CROSSHAIR_LOCKED = "com.yunx.app.overlay.action.SET_CROSSHAIR_LOCKED"
        const val EXTRA_CROSSHAIR_LOCKED = "crosshair_locked"
        const val ACTION_CENTER_CROSSHAIR = "com.yunx.app.overlay.action.CENTER_CROSSHAIR"
        /** v2.7.3：附在居中指令上，表示先对调屏幕宽高（校准横竖识别反了） */
        const val EXTRA_SWAP_SCREEN = "swap_screen"
        const val ACTION_RESET_CROSSHAIR = "com.yunx.app.overlay.action.RESET_CROSSHAIR"
        const val EXTRA_CROSSHAIR_SHOW = "crosshair_show"
        /** v2.5.6 帧率悬浮窗：开关 / 重读外观 */
        const val ACTION_SET_FPS = "com.yunx.app.overlay.action.SET_FPS"
        const val ACTION_APPLY_FPS = "com.yunx.app.overlay.action.APPLY_FPS"
        const val EXTRA_FPS_SHOW = "fps_show"
        const val ACTION_SET_SOURCE = "com.yunx.app.overlay.action.SET_SOURCE"
        const val ACTION_SET_LOCAL_URI = "com.yunx.app.overlay.action.SET_LOCAL_URI"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LOCAL_URI = "local_uri"
        /** v2.5.9：入口绑定的子目录 documentId（空 = tree 根） */
        const val EXTRA_LOCAL_DOC = "local_doc_id"
        const val ACTION_SET_LINK = "com.yunx.app.overlay.action.SET_LINK"
        const val EXTRA_LINK = "link"
        const val EXTRA_LINK_TITLE = "link_title"
        /** v1.9.1 双击音量键 / 通知栏「显示」 */
        const val ACTION_TOGGLE_VISIBILITY = "com.yunx.app.overlay.action.TOGGLE_VISIBILITY"
        const val ACTION_SET_VISIBILITY = "com.yunx.app.overlay.action.SET_VISIBILITY"
        const val EXTRA_VISIBILITY_SHOW = "visibility_show"

        /** 当前是否处于「隐藏」状态；只在 Service 生命周期内有效，不落盘 */
        @Volatile
        var windowsHidden: Boolean = false
            set(value) {
                field = value
                _windowsHiddenFlow.value = value
            }

        /**
         * v1.9.2：两个窗口「真实是否挂在屏幕上」的标志。主界面拿它校准开关文字，
         * 不再出现「状态写着已开启、屏幕上一扇窗都没有」的假状态。
         * 与 guideWindowShown / macroWindowShown 这两个「用户期望」的持久化偏好分开。
         *
         * v1.9.5：改成 StateFlow，主界面订阅即可，删掉每秒一次的轮询。
         */
        @Volatile
        var macroWindowAttached: Boolean = false
            set(value) {
                field = value
                _macroAttachedFlow.value = value
            }

        @Volatile
        var guideWindowAttached: Boolean = false
            set(value) {
                field = value
                _guideAttachedFlow.value = value
            }

        private val _guideAttachedFlow = MutableStateFlow(false)
        val guideAttachedFlow: StateFlow<Boolean> = _guideAttachedFlow.asStateFlow()

        /** v2.2.0：准星层是否真的挂在屏幕上，主页开关靠它校准 */
        @Volatile
        var crosshairAttached: Boolean = false
            set(value) {
                field = value
                _crosshairAttachedFlow.value = value
            }

        private val _crosshairAttachedFlow = MutableStateFlow(false)
        val crosshairAttachedFlow: StateFlow<Boolean> = _crosshairAttachedFlow.asStateFlow()

        private val _macroAttachedFlow = MutableStateFlow(false)
        val macroAttachedFlow: StateFlow<Boolean> = _macroAttachedFlow.asStateFlow()

        private val _windowsHiddenFlow = MutableStateFlow(false)
        val windowsHiddenFlow: StateFlow<Boolean> = _windowsHiddenFlow.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }

        /**
         * v1.9.1：主界面 / 通知栏兜底入口 —— 不依赖音量键也能切换显隐。
         * 某些 ROM 会抢先消费音量键，这时用户仍可从自动连招页或通知栏操作。
         */
        fun setWindowsVisible(context: Context, show: Boolean) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_SET_VISIBILITY)
                        .putExtra(EXTRA_VISIBILITY_SHOW, show)
                )
            }
        }

        /** 主页改了根目录名 / 登录状态变化后，让已运行的悬浮窗重新拉一次 */
        fun refresh(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }

        /** 回到主页时无条件收掉录制透明膜，避免它残留在屏幕上吃掉触摸 */
        fun dismissCapture(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java).setAction(ACTION_DISMISS_CAPTURE)
                )
            }
        }

        /** 一键重建悬浮窗：不杀进程也能从「滑不动」里恢复 */
        fun reset(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java).setAction(ACTION_RESET)
                )
            }
        }

        /**
         * 「小抄」按钮：只切攻略层。先写自己的偏好再拉服务，这样冷启动 onCreate
         * 读到的就是目标状态，不会误挂另一个窗口，也不会出现「点一下闪一下」。
         */
        fun showGuide(context: Context, show: Boolean) {
            OverlayPrefs(context).guideWindowShown = show
            dispatch(
                context,
                Intent(context, FloatingService::class.java)
                    .setAction(ACTION_SET_GUIDE)
                    .putExtra(EXTRA_GUIDE_SHOW, show)
            )
        }

        /** 「自动连招」按钮：只切连招层，与小抄完全无关 */
        fun showMacro(context: Context, show: Boolean) {
            OverlayPrefs(context).macroWindowShown = show
            dispatch(
                context,
                Intent(context, FloatingService::class.java)
                    .setAction(ACTION_SET_MACRO)
                    .putExtra(EXTRA_MACRO_SHOW, show)
            )
        }

        /**
         * v2.2.0「准星」按钮：只切准星层。先写偏好再发 action，
         * 冷启动的 onCreate / onStartCommand 读到的就是目标状态。
         */
        fun showCrosshair(context: Context, show: Boolean) {
            OverlayPrefs(context).crosshairShown = show
            // 服务没跑还要「关」，只是把偏好落回 false，没必要冷启动一个空服务
            if (!show && !isRunning) return
            dispatch(
                context,
                Intent(context, FloatingService::class.java)
                    .setAction(ACTION_SET_CROSSHAIR)
                    .putExtra(EXTRA_CROSSHAIR_SHOW, show)
            )
        }

        /** 准星外观 / 锚点 / 图片变了：重读偏好并保持瞄准点不动。服务没跑就只落盘 */
        fun applyCrosshair(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_APPLY_CROSSHAIR)
                )
            }
        }

        /** v2.7.1：锁定 / 解锁准星层。只改窗口触摸标志，位置不动 */
        fun setCrosshairLock(context: Context, locked: Boolean) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_SET_CROSSHAIR_LOCKED)
                        .putExtra(EXTRA_CROSSHAIR_LOCKED, locked)
                )
            }
        }

        /**
         * v2.5.6 帧率悬浮窗开关：先写偏好再发 action。
         * 服务没跑还要「关」，只把偏好落回 false，没必要冷启动一个空服务。
         */
        fun showFps(context: Context, show: Boolean) {
            OverlayPrefs(context).fpsShown = show
            if (!show && !isRunning) return
            dispatch(
                context,
                Intent(context, FloatingService::class.java)
                    .setAction(ACTION_SET_FPS)
                    .putExtra(EXTRA_FPS_SHOW, show)
            )
        }

        /** 帧率窗透明度 / 背景 / 颜色 / 图标改了：让已挂的窗即时重读 */
        fun applyFps(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_APPLY_FPS)
                )
            }
        }

        /** 把准星挪回屏幕正中 */
        /** v2.7.3：居中；swap = true 时先把屏幕宽高对调（校准横竖屏识别反了） */
        fun centerCrosshair(context: Context, swap: Boolean = false) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_CENTER_CROSSHAIR)
                        .putExtra(EXTRA_SWAP_SCREEN, swap)
                )
            }
        }

        /** 恢复默认：锚点居中、大小自动、不透明、画十字线、不锁定 */
        fun resetCrosshair(context: Context) {
            OverlayPrefs(context).apply {
                crosshairAnchorX = 0.5f
                crosshairAnchorY = 0.5f
                crosshairWidth = 0
                crosshairAlpha = 1f
                crosshairLines = true
                crosshairLocked = false
            }
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_RESET_CROSSHAIR)
                )
            }
        }

        /** 服务在跑就发 action；没跑就冷启动（onCreate 会按各自偏好独立挂窗） */
        private fun dispatch(context: Context, intent: Intent) {
            if (isRunning) {
                runCatching { context.startService(intent) }
            } else {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            }
        }

        /** 主页切攻略来源；Service 在跑时同步给已挂的悬浮窗，不跑则下次启动自动生效 */
        fun setGuideSource(context: Context, source: GuideSource) {
            OverlayPrefs(context).guideSource = source
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_SET_SOURCE)
                        .putExtra(EXTRA_SOURCE, source.name)
                )
            }
        }

        /**
         * 设置 / 清除链接小抄的分享链接（v2.0.2）。
         *
         * 校验不在这里做：调用方（主页）已经成功 open 过一次才调用它。
         * 把网络请求放进这个静态方法，会让「保存」这个动作在没网时变成一句报错。
         */
        fun setLinkShare(context: Context, link: String?, title: String = "") {
            val prefs = OverlayPrefs(context)
            prefs.linkShare = link
            prefs.linkShareTitle = if (link.isNullOrBlank()) "" else title
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_SET_LINK)
                        .putExtra(EXTRA_LINK, link)
                        .putExtra(EXTRA_LINK_TITLE, if (link.isNullOrBlank()) "" else title)
                )
            }
        }

        /** 选好本地目录（或清除）后调用：持久化 Uri 并让悬浮窗重新扫描 */
        fun setLocalTreeUri(context: Context, uri: String?, rootDocId: String? = null) {
            val prefs = OverlayPrefs(context)
            prefs.localTreeUri = uri
            prefs.localRootDocId = rootDocId.orEmpty()
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java)
                        .setAction(ACTION_SET_LOCAL_URI)
                        .putExtra(EXTRA_LOCAL_URI, uri)
                        .putExtra(EXTRA_LOCAL_DOC, rootDocId.orEmpty())
                )
            }
        }
    }
}

/**
 * 给 WindowManager 里的 ComposeView 提供 Lifecycle / ViewModelStore / SavedStateRegistry。
 *
 * 直接 `windowManager.addView(ComposeView)` 而不设置这三个 owner，Compose 会在
 * `LocalLifecycleOwner` / `rememberSaveable` 处直接抛异常 —— 这是把 Compose 放进
 * Service 时最常见的坑。
 */
private class OverlayHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
