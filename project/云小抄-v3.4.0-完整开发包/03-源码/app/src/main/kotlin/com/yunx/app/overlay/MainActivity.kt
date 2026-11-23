/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。主页负责：权限、启停两扇互不关联的悬浮窗、两家网盘登录入口、
 * 攻略来源（网盘 / 本地文件夹）、连招与外观设置、协议与关于页、个人中心。
 *
 * v1.8.0：按参考设置页重做「透明液态玻璃」UI，沉浸式无黑边、功能列表化。
 * v1.8.1~v1.8.4：补回自动连招按钮、整窗长按拖动、两按钮彻底解耦、连招专属按键外观。
 *
 * v1.9.0：按用户要求重排底部导航为 5 个玻璃按钮：
 *   1. 底部 = 「小抄 / 自动连招 / 权限 / 协议 / 我的」五个并列按钮；
 *   2. 「我的」= 登录 / 个人中心，账号密码随便输入即可，纯本机占位，不联网不校验不存密码；
 *   3. 「协议」= 用户协议、隐私说明、权限用途、AGPL-3.0 与关于页入口；
 *   4. 底部「自动连招」按钮**只负责切换到自动连招界面**，绝不直接挂出连招悬浮窗；
 *      该界面顶部另有一个独立的「打开自动连招面板」按钮，那才是面板的开关；
 *      （v1.9.3 撤销了 v1.9.2 里"切页顺手开窗"的临时改动，也不再用假状态自愈偷偷补窗）
 *   5. 小抄与自动连招依旧完全独立，各自的开关只碰自己的窗口。
 */

package com.yunx.app.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.yunx.app.overlay.data.CredentialStore
import com.yunx.app.overlay.data.BuiltinCrosshairs
import com.yunx.app.overlay.data.CrosshairProfile
import com.yunx.app.overlay.data.CrosshairProfiles
import com.yunx.app.overlay.data.InstalledCrosshair
import com.yunx.app.overlay.data.GuideDownloader
import com.yunx.app.overlay.data.GuideSource
import com.yunx.app.overlay.data.ImageVault
import com.yunx.app.overlay.data.PanFiles
import com.yunx.app.overlay.data.ShareLinkService
import com.yunx.app.overlay.data.GuideRepo
import com.yunx.app.overlay.data.MotionPrefs
import com.yunx.app.overlay.data.MotionRuntime
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.data.PanCredential
import com.yunx.app.overlay.data.PanPlatform
import com.yunx.app.overlay.data.PanSession
import com.yunx.app.overlay.data.SplashPrefs
import com.yunx.app.overlay.data.SplashRuntime
import com.yunx.app.overlay.data.ThemePack
import com.yunx.app.overlay.data.ThemePacks
import com.yunx.app.overlay.login.QuarkLoginActivity
import com.yunx.app.overlay.login.XunleiLoginActivity
import com.yunx.app.overlay.macro.MacroAccessibilityService
import com.yunx.app.overlay.macro.MacroController
import com.yunx.app.overlay.macro.RootShell
import com.yunx.app.overlay.macro.ShizukuShell
import com.yunx.app.overlay.service.FloatingService
import com.yunx.app.overlay.ui.AboutScreen
import com.yunx.app.overlay.ui.UpdateNotesScreen
import com.yunx.app.overlay.ui.AccountPage
import com.yunx.app.overlay.ui.CrosshairPage
import com.yunx.app.overlay.ui.ColorPickerDialog
import com.yunx.app.overlay.ui.DownloadPage
import com.yunx.app.overlay.ui.DrawerChild
import com.yunx.app.overlay.ui.GlassBackground
import com.yunx.app.overlay.ui.GlassCard
import com.yunx.app.overlay.ui.GlassColors
import com.yunx.app.overlay.ui.GlassDivider
import com.yunx.app.overlay.ui.GlassPill
import com.yunx.app.overlay.ui.GlassPrimaryButton
import com.yunx.app.overlay.ui.GlassRow
import com.yunx.app.overlay.ui.GlassSectionTitle
import com.yunx.app.overlay.ui.GlassSegmented
import com.yunx.app.overlay.ui.LocalThemeTuning
import com.yunx.app.overlay.ui.DeckCard
import com.yunx.app.overlay.ui.GuideStyleStore
import com.yunx.app.overlay.ui.StackDeck
import com.yunx.app.overlay.ui.text
import com.yunx.app.overlay.ui.StackModule
import com.yunx.app.overlay.ui.ThemeSkin
import com.yunx.app.overlay.ui.ThemeWipeLayer
import com.yunx.app.overlay.ui.ThemeWipeRequest
import com.yunx.app.overlay.ui.ThemeStorePage
import com.yunx.app.overlay.ui.DockHeader
import com.yunx.app.overlay.ui.DockNav
import com.yunx.app.overlay.ui.NavStyle
import com.yunx.app.overlay.ui.tuningFromJson
import com.yunx.app.overlay.ui.UiMode
import com.yunx.app.overlay.ui.readableShadow
import com.yunx.app.overlay.ui.DrawerItem
import com.yunx.app.overlay.ui.DrawerSide
import com.yunx.app.overlay.ui.GlassTab
import com.yunx.app.overlay.ui.ShortcutEntrySection
import com.yunx.app.overlay.ui.ResourceLinkCard
import com.yunx.app.overlay.ui.GuideDrawerHost
import com.yunx.app.overlay.ui.GuideTheme
import com.yunx.app.overlay.ui.LocalUiStyle
import com.yunx.app.overlay.ui.LocalUiTokens
import com.yunx.app.overlay.ui.StaggerColumn
import com.yunx.app.overlay.ui.StylePickerDialog
import com.yunx.app.overlay.ui.SplashOverlay
import com.yunx.app.overlay.ui.UsageScreen
import com.yunx.app.overlay.ui.ENTRANCE_MS
import com.yunx.app.overlay.ui.SWITCH_MS
import com.yunx.app.overlay.ui.TileEntrance
import com.yunx.app.overlay.ui.TileSpec
import com.yunx.app.overlay.ui.tileCurtain
import com.yunx.app.overlay.ui.MacroHomePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yunx.app.overlay.ui.OverlayPalette
import com.yunx.app.overlay.ui.OverlayScheme
import com.yunx.app.overlay.ui.OverlayTheme
import com.yunx.app.overlay.ui.ProtocolPage
import com.yunx.app.overlay.ui.ResolveDownloadPane
import com.yunx.app.overlay.ui.ResolvePage
import com.yunx.app.overlay.ui.rememberResolveState
import com.yunx.app.overlay.ui.currentScheme
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** v2.4.0：开屏要播什么，只在 onCreate 里解析一次，往下只传值。path = null 表示播内置那套 */
private data class SplashConfig(val path: String?, val kind: Int, val holdMs: Int)

open class MainActivity : ComponentActivity() {

    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.4.0：动画偏好在进程启动时读回用户的选择（Service 也会各调一次，幂等）
        MotionPrefs.init(this)
        // v2.4.0：Activity 每次重建都算「首屏」—— 首屏走入场风格，之后切页走切页风格。
        // 旗子会在 TileEntrance 播完时放倒。
        MotionRuntime.coldStart = true

        // 沉浸式：让 Compose 内容画到状态栏/导航栏后面，深色背景一直铺到屏幕顶端。
        // 旧的浅色主题会在状态栏留一条黑边，看起来像「上面有一圈黑色」；这里彻底铺满。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // v2.1.2：让内容画进刘海 / 前摄缺口。
        // 不设这一项时，系统把窗口从缺口那条让开，让出来的区域用主题的
        // windowBackground（@android:color/black）填 —— 竖屏是顶部一小块，
        // 横屏缺口跑到左边，就是一条贯穿全高的黑边，浅色皮肤下特别扎眼。
        // 更实际的问题是从那条边起手滑抽屉：手指落在窗口外，事件根本到不了 App，
        // 只会被系统当成返回手势。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false       // 深色底 → 状态栏图标用浅色
            isAppearanceLightNavigationBars = false
        }

        // v1.9.8：每次启动软件时检测一次小抄有没有更新（后台跑，不挡界面）
        runCatching { GuideRepo.downloader(this).maybeAutoCheck() }
        // v1.9.10：把重复文件合并掉 + 按上限淘汰，一天一次
        runCatching { GuideRepo.downloader(this).maybeMaintain() }

        // v2.4.0：自定义开屏动画。一个进程只播一次（转屏 / 分屏重建 Activity 不重播）。
        // 必须在 setContent 之前就把动画闸门关上 —— 否则首页那批模块会在开屏底下
        // 先浮完，等开屏退场时用户只看到一片静态。
        val splashConfig = if (!SplashRuntime.played && SplashPrefs.isPlayable(this)) {
            SplashRuntime.played = true
            SplashRuntime.gate.value = false
            SplashConfig(
                path = SplashPrefs.existingPath(this),
                kind = SplashPrefs.kind(this),
                holdMs = SplashPrefs.holdMs(this)
            )
        } else {
            // 这次不播：必须把闸门强行顶开。上一次要是在开屏播到一半时被系统
            // 连 Activity 一起回收，闸门会留在「关」，首页模块就永远停在透明状态。
            SplashRuntime.gate.value = SplashRuntime.GATE_OPEN
            null
        }

        setContent {
            OverlayTheme {
                // v1.9.11：三套界面风格的换肤入口，必须包在最外层，
                // 这样切风格时连根背景和子页面一起重组
                GuideTheme {
                    // 风格 3 是浅色底，状态栏图标必须切成深色，否则「白字白底」直接看不见
                    val uiDark = LocalUiTokens.current.dark
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !uiDark
                            isAppearanceLightNavigationBars = !uiDark
                        }
                    }
                    var showAbout by remember { mutableStateOf(false) }
                    // v2.0.10：使用说明是第三个页面。从「关于」页进来时 showAbout 仍为 true，
                    // 关掉使用说明就自然退回关于页；从抽屉直接进来则退回主页。
                    var showUsage by remember { mutableStateOf(false) }
                    // v2.6.0：更新说明从「关于」页里的一块内容拆成独立页，四级路由。
                    var showNotes by remember { mutableStateOf(false) }
                    // v2.4.0：开屏还在播时，入场动画全部等它退场（闸门）再起跑
                    var splashOn by remember { mutableStateOf(splashConfig != null) }
                    // 开屏开始淡出的那一刻就放行入场动画，两段叠着更顺
                    var splashLeaving by remember { mutableStateOf(false) }

                    // v2.1.0：主页 ↔ 关于 ↔ 使用说明 的切页动画。
                    // 之前这两个子页面是「硬切」—— 开场细格只在冷启动播一次，
                    // 底部页签那层碎块又挂在 HomeScreen 内部，路由一换就整棵被替换掉。
                    // 现在把路由单独编成 key，罩一层大格碎块，进出子页面都会一块块跳出来。
                    val routeKey = when {
                        showNotes -> 3
                        showUsage -> 2
                        showAbout -> 1
                        else -> 0
                    }
                    val routeCurtain = remember { Animatable(1f) }
                    var routeArmed by remember { mutableStateOf(false) }
                    // v2.5.1：**不能**把动画开关 / 切页风格当 key。
                    // 之前在「我的」点一下「碎块 / 浮现」或「界面动画」，整屏大格遮罩
                    // 就重新扫一遍，画面被盖住的几百毫秒里点什么都没反应。
                    // 这两个值只在起跑时读，改设置从下一次切页开始生效。
                    LaunchedEffect(routeKey) {
                        if (!routeArmed) {
                            // 冷启动这一下交给 TileEntrance 的细格，避免两层动画叠着
                            routeArmed = true
                            routeCurtain.snapTo(1f)
                            return@LaunchedEffect
                        }
                        val ms = if (MotionPrefs.switchStyle.value == MotionPrefs.RISE) {
                            0
                        } else {
                            MotionPrefs.duration(SWITCH_MS)
                        }
                        if (ms <= 0) {
                            routeCurtain.snapTo(1f)
                            return@LaunchedEffect
                        }
                        routeCurtain.snapTo(0f)
                        withFrameNanos { }
                        routeCurtain.animateTo(1f, tween(ms, easing = LinearEasing))
                    }

                    // v2.5.3 闸门看门狗 —— 这条是「进软件后点什么都没反应」的总兜底。
                    //
                    // 整条链路是：开屏在播 → 闸门关 → TileEntrance 里所有模块 alpha=0
                    // （屏幕一片空白）→ 而开屏那一层是 fillMaxSize 吃触摸的。
                    // 只要 onExitStart 没被调用，空白和「点不动」会同时出现，只能杀进程。
                    //
                    // 会让它不回调的情况不止一种：视频的 Surface 被系统销毁后不再走
                    // onCompletion、图片解码在这台机器上失败、播到一半被切后台再回来
                    // 导致组合被重建。与其逐个堵，不如拿墙钟压住：
                    // 到点不管发生什么都开门、撤遮罩。
                    LaunchedEffect(splashOn) {
                        val sc = splashConfig ?: return@LaunchedEffect
                        if (!splashOn) return@LaunchedEffect
                        // 视频本来就允许播到 15 秒，给它留够；图片按停留时长 + 2.5 秒余量
                        val cap = if (sc.kind == SplashPrefs.KIND_VIDEO) {
                            SplashPrefs.MAX_VIDEO_MS + 2_500L
                        } else {
                            sc.holdMs.toLong() + 2_500L
                        }
                        delay(cap)
                        // 还没撤掉 = 一定是卡住了。强行收场。
                        if (splashOn) {
                            splashLeaving = true
                            SplashRuntime.gate.value = SplashRuntime.GATE_OPEN
                            splashOn = false
                        }
                    }

                    // 全屏玻璃底由 Activity 统一提供，子页面透明叠上去，切换时不会闪一下
                    Box(Modifier.fillMaxSize().background(GlassColors.Bg)) {
                        GlassBackground()
                        // v2.11.0：「主题设置 → 背景遮罩」。只压在背景这一层上，
                        // 卡片和文字不受影响 —— 调暗背景是为了让前景更跳出来，
                        // 连字一起压暗就没意义了。
                        BackdropScrimLayer()
                        // v2.0.9：从桌面点图标进来的那一下 —— 整屏铺满 22dp 的细碎小格，
                        // 从左上角开始一块块散开，界面同时从 94% 长大到 100%。
                        // 遮罩取底色往白里提 6%：同色的话看不出格子，提亮后是一层灰麻点，
                        // 散开的过程才像「画面碎开」而不是「蒙板淡出」。
                        TileEntrance(
                            color = curtainColor(),
                            durationMs = ENTRANCE_MS,
                            spec = TileSpec.FINE,
                            zoomFrom = 0.94f,
                            armed = !splashOn || splashLeaving
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .tileCurtain(
                                        progress = { routeCurtain.value },
                                        color = curtainColor(),
                                        spec = TileSpec.CHUNKY
                                    )
                            ) {
                                when {
                                    showNotes -> UpdateNotesScreen(onBack = { showNotes = false })
                                    showUsage -> UsageScreen(onBack = { showUsage = false })
                                    showAbout -> AboutScreen(
                                        onBack = { showAbout = false },
                                        onOpenUsage = { showUsage = true },
                                        onOpenNotes = { showNotes = true }
                                    )
                                    else -> HomeScreen(
                                        resumeTick.intValue,
                                        onOpenAbout = { showAbout = true },
                                        onOpenUsage = { showUsage = true },
                                        onOpenNotes = { showNotes = true }
                                    )
                                }
                            }
                        }

                        // v2.4.0：自定义开屏。盖在最上层、吃掉所有触摸，底下主页照常
                        // 完成首帧；退场后立刻能操作，不会闪白。
                        val sc = splashConfig
                        if (splashOn && sc != null) {
                            SplashOverlay(
                                path = sc.path,
                                kind = sc.kind,
                                holdMs = sc.holdMs,
                                onExitStart = {
                                    splashLeaving = true
                                    SplashRuntime.gate.value = SplashRuntime.GATE_OPEN
                                },
                                onFinish = { splashOn = false }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到主页 = 用户已经离开「取点/录制」场景，无条件收掉那层全屏透明膜。
        FloatingService.dismissCapture(this)
        // 从「悬浮窗权限」/「无障碍」/「登录」/「目录选择」页回来时重新读一遍真实状态
        resumeTick.intValue++
    }
}

/**
 * 碎块遮罩色。
 *
 * 不能直接用底色：遮罩和背景同色时，格子根本看不见，整屏只像一次淡出。
 * 深色底提亮 6%、浅色底压暗 5% —— 都是「一眼能看出有格子，但不抢内容」的程度。
 */
@Composable
private fun curtainColor(): Color {
    val t = LocalUiTokens.current
    return if (t.dark) lerp(t.bg, Color.White, 0.06f) else lerp(t.bg, Color.Black, 0.05f)
}

/** 底部按钮对应的页面。v1.9.9 在「小抄 / 连招」后面补上云析原版的「解析 / 下载」 */
private enum class HomeTab(val title: String, val subtitle: String) {
    GUIDE("小抄", "攻略悬浮窗、图片来源与小抄外观"),
    MACRO("连招", "独立开关 · 录制 · 专属按键外观"),
    CROSSHAIR("准星", "自由导入图片 · 自定义瞄准点与位置"),
    RESOLVE("解析", "粘贴分享链接 · 浏览目录 · 下载到本地"),
    DOWNLOAD("下载", "分享导入进度 · 分区本地化 · 下载管理"),
    AUTH("权限", "把该给的权限一次给齐，别等卡住才想起来"),
    PROTOCOL("协议", "用户协议 · 隐私 · 开源许可"),
    ACCOUNT("我的", "登录 / 个人中心 / 支持作者"),
    // v3.3.0：主题商店。排在最后，tab 下标正好是 8 —— 前面 0~7 的既有跳转一个都不动
    STORE("主题商店", "主题包 · 从文件安装 · 一键换肤")
}

/**
 * 抽屉里的页面列表。
 *
 * v3.0.2：跳转目标改成每项自己写死 [DrawerItem.target]，不再靠列表下标 ——
 * 这一版把「解析」「下载」并成了一项，下标跟着少一个；要是还靠下标对齐，
 * 点「权限」会跳到「下载」、点「我的」会跳到「协议」。目标写死后，以后
 * 增删任何一项都不会再串页。
 */
/** 连招四页的顶栏标题 */
private val MACRO_PANE_TITLES = listOf("连招", "录制台", "按键外观", "分享导入")

/** 「我的」三页的顶栏标题（v3.0.1：「外观与动画」并入主题设置，只剩三页） */
private val ACCOUNT_PANE_TITLES = listOf("账号与同步", "主题设置", "协议与关于")

/** 「解析下载」两页的顶栏标题 */
private val RESOLVE_PANE_TITLES = listOf("解析", "下载")

private val DRAWER_ITEMS = listOf(
    // v2.8.1：「小抄」拆成四个互不相干的独立页面。
    // 以前三个来源挤在同一页里、下半屏还堆着入口 / 下载 / 账号 / 外观，
    // 切来源只是把中间一块换掉 —— 看着就是一坨。现在一页只干一件事：
    // 个人小抄（网盘）· 本地小抄（文件夹）· 链接小抄（分享链接）· 悬浮窗（开关 / 外观）。
    DrawerItem(
        label = "小抄",
        icon = Icons.Rounded.Description,
        section = "内容",
        target = 0,
        children = listOf(
            DrawerChild("个人小抄", "cloud"),
            DrawerChild("本地小抄", "local"),
            DrawerChild("链接小抄", "link"),
            DrawerChild("悬浮窗", "overlay")
        )
    ),
    // v2.9.1：其它页也按「主按钮 + 次按钮」分好 —— 一页只干一件事，
    // 跟小抄那四页同一套结构、同一套选中箭头。
    DrawerItem(
        "连招", Icons.Rounded.Bolt, "内容",
        target = 1,
        children = listOf(
            DrawerChild("连招开关", "switch"),
            DrawerChild("录制台", "studio"),
            DrawerChild("按键外观", "look"),
            DrawerChild("分享导入", "share")
        )
    ),
    DrawerItem(
        "准星", Icons.Rounded.CenterFocusStrong, "内容",
        target = 2,
        children = listOf(
            DrawerChild("我的准星", "list"),
            DrawerChild("准星调整", "tune")
        )
    ),
    // v3.0.2：解析和下载并成一页 —— 粘链接、选目录、看进度本就是一串动作，
    // 拆成两个入口只会让人在「解析完去哪看」上多问一次。
    DrawerItem(
        "解析下载", Icons.Rounded.Link, "内容",
        target = 3,
        jumpOnTap = true,
        children = listOf(
            DrawerChild("解析", "resolve"),
            DrawerChild("下载", "transfers")
        )
    ),
    DrawerItem(
        "权限", Icons.Rounded.Shield, "设置",
        target = 5,
        children = listOf(
            DrawerChild("权限", "perm"),
            DrawerChild("自动连招", "auto")
        )
    ),
    // v3.4.6：「协议」「使用说明」「关于」三处入口合并成一个 ——
    // 抽屉里只留「我的 → 关于与使用说明」，避免底部再挤两行、还跟 Dock 打架。
    // v3.3.0：主题商店。Dock 模式里它是第三格；抽屉模式从这里进
    DrawerItem("主题商店", Icons.Rounded.Style, "设置", target = 8),
    DrawerItem(
        "我的", Icons.Rounded.Person, "设置",
        target = 7,
        children = listOf(
            DrawerChild("账号与同步", "account"),
            DrawerChild("主题设置", "theme"),
            DrawerChild("关于与使用说明", "about")
        )
    )
)

/**
 * v2.11.0：背景遮罩层。
 *
 * 负值往黑里压（画面沉下去），正值往白里提（画面浮起来）。只盖在壁纸那一层，
 * 卡片用的是自己的底色，所以调遮罩不会把卡片和文字一起糊掉。
 */
@Composable
private fun BackdropScrimLayer() {
    val bias = LocalThemeTuning.current.scrimBias
    if (kotlin.math.abs(bias) < 0.005f) return
    val color = if (bias > 0f) {
        Color.Black.copy(alpha = bias.coerceAtMost(0.35f))
    } else {
        Color.White.copy(alpha = (-bias).coerceAtMost(0.35f))
    }
    Box(Modifier.fillMaxSize().background(color))
}

// v2.11.1：MacroController.init 是幂等的，remember 只为「只跑一次」而非缓存返回值，
// 与 GuideTheme 里那处同理。lint 按惯例报 RememberReturnType，显式忽略。
@android.annotation.SuppressLint("RememberReturnType")
@Composable
private fun HomeScreen(
    resumeTick: Int,
    onOpenAbout: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenNotes: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { CredentialStore.get(context) }
    val prefs = remember { OverlayPrefs(context) }
    val session by store.session.collectAsState()
    val scheme = currentScheme()

    var tab by remember { mutableIntStateOf(0) }
    // v1.9.11：换肤弹窗（顶栏调色盘按钮 / 「我的」页的「切换 ›」共用）
    var showStyle by remember { mutableStateOf(false) }
    // v3.3.0：导航方式 —— 抽屉（老样子）/ 底部 Dock（商店那种）。
    // 跟风格一样走 StateFlow，主题设置里一改，这里立刻跟着变。
    val navStyle by GuideStyleStore.nav.collectAsState()
    val dockNav = navStyle == NavStyle.DOCK
    // v3.3.0：主题商店 —— 导入 / 删除后 +1，用来让商店重读磁盘上的包
    var themeTick by remember { mutableIntStateOf(0) }
    // 正在用的那个包（只用来在卡片上打勾）
    var appliedPackId by remember { mutableStateOf(prefs.themePackId) }
    // v3.4.0：冷启动把「上次那套」的图重新挂上去。
    // 只挂在首帧后跑一次 —— 参数是同步读的，图要解码，晚几十毫秒没关系；
    // 不恢复的话，带卡面的包重启之后就只剩渐变，像被降级了。
    LaunchedEffect(Unit) {
        val saved = prefs.themePackId
        if (saved.isNotBlank()) {
            ThemePacks.byId(context, saved)?.let { ThemeSkin.loadMerged(context, it.assets) }
        }
    }
    // v3.2.0：日夜切换 = 《春秋》那种圆形扩散。
    // null = 没在扫；非空 = 旧界面截图 + 圆心，正在把新界面「挖」出来。
    var wipe by remember { mutableStateOf<ThemeWipeRequest?>(null) }
    val uiDark = LocalUiTokens.current.dark
    val rootView = LocalView.current
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)

    // 点按钮的那一下做三件事：截图、挂遮罩、立刻换主题。
    // 三者同一次组合生效 —— 底下已经是新界面，上面压着旧截图，画面上不会闪。
    val toggleTheme: () -> Unit = {
        if (wipe == null) {
            val toDark = !uiDark
            val shot = runCatching { rootView.drawToBitmap() }.getOrNull()
            if (shot != null) {
                wipe = ThemeWipeRequest(
                    snapshot = shot,
                    // 圆心 = 顶栏那颗日夜按钮。右侧留 72dp（按钮 + 调色盘 + 边距），
                    // 纵向放在状态栏下 30dp —— 和录屏里量出来的 (0.80w, 0.084h) 一致。
                    center = Offset(
                        x = rootView.width - with(density) { 72.dp.toPx() },
                        y = statusTop + with(density) { 30.dp.toPx() }
                    )
                )
            }
            GuideStyleStore.selectMode(context, if (toDark) UiMode.DARK else UiMode.LIGHT)
        }
    }
    // v1.9.12：侧滑抽屉（替代底部那排挤在一起的 Tab）
    var drawerOpen by remember { mutableStateOf(false) }
    // v3.4.5：切到 Dock 模式时把抽屉收起来 —— 两个导航方式不共存
    LaunchedEffect(dockNav) {
        if (dockNav) drawerOpen = false
    }
    // v1.9.18：这次从左还是从右弹出（跟着滑动方向走）
    var drawerSide by remember { mutableStateOf(DrawerSide.LEFT) }
    val versionLabel = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "云析二改"
    }
    // 解析页的状态挂在这一层：切到别的 Tab 再回来，链接与目录不会被重置
    val resolveState = rememberResolveState()
    // v3.0.2：「解析下载」一页两栏 —— 0 解析 / 1 下载进度。
    // 标题、抽屉子项高亮、滚动容器都跟这个值走。
    var resolvePane by remember { mutableIntStateOf(0) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifyGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    // v1.8.3：只表示「攻略悬浮窗是否显示」，不再等同于服务是否在跑
    // v2.1.3：初值直接跟「真实挂着的窗口」走，服务没跑就不可能有窗，避免首帧误显「关闭」
    var running by remember {
        mutableStateOf(FloatingService.isRunning && FloatingService.guideWindowAttached)
    }
    var rootName by remember { mutableStateOf(prefs.rootFolderName) }
    var bubbleIcon by remember { mutableStateOf(prefs.bubbleIconPath) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var macroWindowOn by remember { mutableStateOf(prefs.macroWindowShown) }
    var source by remember { mutableStateOf(prefs.guideSource) }
    // v2.8.1：小抄拆页后当前停在哪一页 —— 0 个人 / 1 本地 / 2 链接 / 3 悬浮窗。
    // 初值跟持久化的来源走：上次用本地小抄，重进还是本地小抄。
    var guidePane by remember {
        mutableIntStateOf(
            when (prefs.guideSource) {
                GuideSource.LOCAL -> 1
                GuideSource.LINK -> 2
                else -> 0
            }
        )
    }
    // v2.9.1：连招 / 准星 / 权限 / 我的 也各自拆了子页，这里记当前停在第几页
    var macroPane by remember { mutableIntStateOf(0) }
    var crosshairPane by remember { mutableIntStateOf(0) }
    var authPane by remember { mutableIntStateOf(0) }
    var accountPane by remember { mutableIntStateOf(0) }
    var localTreeUri by remember { mutableStateOf(prefs.localTreeUri) }
    // v2.0.2 链接小抄：存的是原始分享文本（可能带口令），不是规整后的 URL
    var linkShare by remember { mutableStateOf(prefs.linkShare) }
    var linkTitle by remember { mutableStateOf(prefs.linkShareTitle) }
    var linkBusy by remember { mutableStateOf(false) }
    // v2.2.0 准星：独立于小抄 / 连招的第三层
    // v2.3.0：多套准星。列表是唯一真源；下面那组 crosshair* 是「当前生效」的镜像，
    // Service 只认镜像 —— 切套就是把选中那套的值写进镜像，服务侧一行都不用改。
    val crosshairList = remember {
        mutableStateListOf<CrosshairProfile>().apply { addAll(CrosshairProfiles.load(prefs)) }
    }
    // v2.5.0：随包内置的准星。assets 复制到私有目录是 IO，进页面后异步铺出来
    val builtinCrosshairs = remember { mutableStateListOf<InstalledCrosshair>() }
    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) { BuiltinCrosshairs.install(context) }
        builtinCrosshairs.clear()
        builtinCrosshairs.addAll(installed)
    }
    var crosshairActiveId by remember {
        val first = CrosshairProfiles.active(prefs, crosshairList)
        // 老档没有 activeId：把第一套写进快照，Service 才有东西可读
        CrosshairProfiles.applyToPrefs(prefs, first)
        mutableStateOf(first.id)
    }
    var crosshairOn by remember {
        mutableStateOf(FloatingService.isRunning && FloatingService.crosshairAttached)
    }
    var crosshairImage by remember { mutableStateOf(prefs.crosshairImage) }
    var crosshairAnchorX by remember { mutableStateOf(prefs.crosshairAnchorX) }
    var crosshairAnchorY by remember { mutableStateOf(prefs.crosshairAnchorY) }
    var crosshairWidth by remember { mutableStateOf(prefs.crosshairWidth) }
    var crosshairAlpha by remember { mutableStateOf(prefs.crosshairAlpha) }
    var crosshairLines by remember { mutableStateOf(prefs.crosshairLines) }
    var crosshairLocked by remember { mutableStateOf(prefs.crosshairLocked) }
    // v2.5.0：一键下载。目录、进度都在这一层，切页签回来不丢
    val downloader = remember { GuideDownloader.of(context) }
    val imageVault = remember { ImageVault.of(context) }
    val downloadStats by downloader.stats.collectAsState()
    var downloadDirLabel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        downloadDirLabel = withContext(Dispatchers.IO) { imageVault.dirPathLabel() }
    }
    val uiScope = rememberCoroutineScope()
    remember { MacroController.init(context) }

    val rootState by MacroController.rootState.collectAsState()
    val shizukuState by MacroController.shizukuState.collectAsState()
    val rootBusy by MacroController.rootBusy.collectAsState()

    /**
     * v2.3.0：把界面上的准星参数写回选中的那套并落盘。
     *
     * 放在图片选择器之前定义 —— 导入回调里要用到它，局部函数不能前向引用。
     * [look] = true 时顺带让 Service 按新值重算悬浮层。
     */
    fun persistCrosshair(look: Boolean = false) {
        val idx = crosshairList.indexOfFirst { it.id == crosshairActiveId }
        if (idx < 0) return
        val p = crosshairList[idx]
        // data class 字段是 var，改完不等于换对象；显式 copy 回写，列表观察者才收到通知
        crosshairList[idx] = p.copy(
            imagePath = crosshairImage,
            anchorX = crosshairAnchorX,
            anchorY = crosshairAnchorY,
            widthPx = crosshairWidth,
            alpha = crosshairAlpha,
            showLines = crosshairLines
        )
        CrosshairProfiles.save(prefs, crosshairList)
        if (look) FloatingService.applyCrosshair(context)
    }

    // ------------------------------------------------------------------
    // 系统回调
    // ------------------------------------------------------------------
    val pickBubbleIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dest = File(context.filesDir, "bubble_icon_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            bubbleIcon?.let { old -> runCatching { File(old).delete() } }
            prefs.bubbleIconPath = dest.absolutePath
            bubbleIcon = dest.absolutePath
            FloatingService.refresh(context)
        }.onFailure {
            Toast.makeText(context, "设置图标失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v2.2.0：准星图片用 GetContent 而不是相册选择器 ——
     * 用户说「自由导入」，那就连文件管理器、网盘下载目录里的图都能直接挑。
     * 图片原样复制到私有目录，不压缩、不转码，保留透明通道。
     */
    val pickCrosshair = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dest = File(context.filesDir, "crosshair_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            crosshairImage?.let { old -> runCatching { File(old).delete() } }
            prefs.crosshairImage = dest.absolutePath
            crosshairImage = dest.absolutePath
            // v2.3.0：图要落到「当前这套」里，否则切走再切回来图就丢了
            persistCrosshair(look = false)
            // 新图比例可能完全不同，交给 Service 按新图重算尺寸并保持瞄准点
            FloatingService.applyCrosshair(context)
            Toast.makeText(context, "已导入准星图片", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val pickTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 目录树的读权限要显式「拿持久授权」，否则杀进程/重启后就失效
        // v2.5.0：顺带把写权限也留下 —— 「下载目录跟本地小抄同目录」要往这里写文件，
        // 只拿读权限的话，用户切过去下载会一张都写不进去
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val value = uri.toString()
        prefs.localTreeUri = value
        FloatingService.setLocalTreeUri(context, value)
        localTreeUri = value
        if (source != GuideSource.LOCAL) {
            FloatingService.setGuideSource(context, GuideSource.LOCAL)
            source = GuideSource.LOCAL
        }
        Toast.makeText(context, "已选择本地文件夹", Toast.LENGTH_SHORT).show()
    }

    /**
     * v2.5.0：下载目录选择器 —— 和「本地小抄」用同一个 OpenDocumentTree，
     * 但这里要的是**读写**权限（要往这个目录里写图）。
     */
    val pickDownloadDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        imageVault.setTreeUri(uri.toString())
        // dirPathLabel 只解析 documentId 字符串，不查 ContentResolver，可以直接在回调里算
        downloadDirLabel = imageVault.dirPathLabel()
        Toast.makeText(context, "下载目录已改：$downloadDirLabel", Toast.LENGTH_LONG).show()
    }

    val overlaySettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { overlayGranted = Settings.canDrawOverlays(context) }

    // ------------------------------------------------------------------
    // v3.3.0：主题包
    //
    // 应用 = 把包里那段 tuning 解析成 ThemeTuning 写进偏好。壁纸不跟着走 ——
    // 那是用户自己的照片，主题包没资格覆盖。
    // ------------------------------------------------------------------
    val applyThemePack: (ThemePack) -> Unit = { pack ->
        val parsed = tuningFromJson(pack.tuningJson)
        if (parsed == null) {
            Toast.makeText(context, "「${pack.name}」的参数读不出来", Toast.LENGTH_SHORT).show()
        } else {
            // v3.4.1 FIX: 背景图来源必须精确区分三种情况 —— 避免从带图包切到纯参数包时
            // 上一个主题包的背景路径被意外保留（用户报告：二次元包 → 纯色包后背景仍残留二次元人物）
            val prevBg = GuideStyleStore.tuning.value.backgroundKey
            val prevWasThemePack = prefs.themePackId.isNotBlank() && prevBg.isNotBlank()
            // 主题包自己带的背景图
            val packBg = pack.assets?.background
            val newBg = when {
                // 新包自带背景图：用新包的
                !packBg.isNullOrBlank() -> packBg
                // 新包没有背景图 + 上一套是主题包带的：清空（回到程序风格壁纸）
                prevWasThemePack -> ""
                // 新包没有背景图 + 上一套是用户自己选的自定义背景：保留
                else -> prevBg
            }
            GuideStyleStore.setTuning(
                context,
                parsed.copy(backgroundKey = newBg)
            )
            // 卡面 / 底栏图标：清掉上一套再挂这一套，别让两套图叠在一起
            ThemeSkin.loadMerged(context, pack.assets)
            prefs.themePackId = pack.id
            appliedPackId = pack.id
            val extra = if (pack.assets?.isEmpty == false) "（含卡面 / 图标）" else ""
            Toast.makeText(context, "已应用「${pack.name}」$extra", Toast.LENGTH_SHORT).show()
        }
    }
    // 「从文件安装」：json / zip 都收。有些文件管理器把 .json 报成 octet-stream，
    // 所以 MIME 直接给 */*，真正的格式判断在 ThemePacks.import 里按扩展名 + 内容做
    val importScope = rememberCoroutineScope()
    val importThemePack = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // v3.4.0：.sukisu 要先解密再解压再解图，2MB 的包在主线程做会顿一下。
        // 挪到 IO 上跑，界面照常响应；回来再切回主线程弹 Toast。
        importScope.launch {
            val result = withContext(Dispatchers.IO) { ThemePacks.import(context, uri) }
            val pack = result.pack
            if (pack == null) {
                Toast.makeText(context, result.error ?: "导入失败", Toast.LENGTH_LONG).show()
            } else {
                themeTick++
                applyThemePack(pack)
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifyGranted = hasNotificationPermission(context) }

    LaunchedEffect(resumeTick) {
        overlayGranted = Settings.canDrawOverlays(context)
        notifyGranted = hasNotificationPermission(context)
        // v2.1.3：开关文字以「真实挂着的窗口」为准，服务没跑就一定没有窗——
        // 顺手把遗留的偏好写回 false，避免「明明没开却显示关闭」，也避免下次冷启动
        // 又自动把窗弹出来（服务被系统回收 / 上次点 X 后残留的脏状态都走这条）。
        if (FloatingService.isRunning) {
            running = FloatingService.guideWindowAttached
            macroWindowOn = FloatingService.macroWindowAttached
            crosshairOn = FloatingService.crosshairAttached
            // 偏好说开了、实际没挂：把脏偏好改回 false（例如被系统回收过窗口）
            if (prefs.guideWindowShown && !FloatingService.guideWindowAttached) {
                prefs.guideWindowShown = false
            }
            if (prefs.macroWindowShown && !FloatingService.macroWindowAttached) {
                prefs.macroWindowShown = false
            }
            if (prefs.crosshairShown && !FloatingService.crosshairAttached) {
                prefs.crosshairShown = false
            }
        } else {
            // 服务都没在跑，三扇窗一定都没挂；把残留偏好清掉
            if (prefs.guideWindowShown) prefs.guideWindowShown = false
            if (prefs.macroWindowShown) prefs.macroWindowShown = false
            if (prefs.crosshairShown) prefs.crosshairShown = false
            running = false
            macroWindowOn = false
            crosshairOn = false
        }
        crosshairImage = prefs.crosshairImage
        crosshairAnchorX = prefs.crosshairAnchorX
        crosshairAnchorY = prefs.crosshairAnchorY
        crosshairWidth = prefs.crosshairWidth
        crosshairAlpha = prefs.crosshairAlpha
        crosshairLines = prefs.crosshairLines
        crosshairLocked = prefs.crosshairLocked
        // v2.3.0：回到前台时把镜像diff写回当前这套（Service 的重置 / 悬浮窗拖动都走这条）
        persistCrosshair(look = false)
        a11yEnabled = MacroAccessibilityService.isEnabled(context)
        source = prefs.guideSource
        localTreeUri = prefs.localTreeUri
        rootName = prefs.rootFolderName
        bubbleIcon = prefs.bubbleIconPath
        MacroController.refreshEnabled()
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------
    fun requestOverlay() {
        overlaySettings.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(context, "当前系统无需单独授权通知", Toast.LENGTH_SHORT).show()
        }
    }

    /** 两个按钮共用的前置检查；不再由它启动服务，服务由各按钮的 action 自行拉起 */
    fun ensureOverlayReady(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            requestOverlay()
            Toast.makeText(context, "先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return true
    }

    /**
     * 「小抄」开关：只开关攻略悬浮窗，连招窗一个字节都不碰。
     *
     * v3.0.2：[wantSource] 是「点这一下时人正站在哪一页」。以前这里完全不看来源，
     * 在本地小抄页点开启，悬浮窗仍按上次存的 CLOUD 去读网盘 —— 没登录就弹
     * 「请先登录夸克网盘」，看着像点本地小抄蹦出了个人小抄。现在开启前先把
     * 来源切到当前页，窗里窗外同一份图。
     */
    fun toggleOverlay(wantSource: GuideSource? = null) {
        val next = !running
        if (next && wantSource != null && prefs.guideSource != wantSource) {
            // 和 switchSource 同一套动作，但那个函数定义在下面：Kotlin 的局部函数
            // 必须先声明后使用，这里只能就地写一遍。改动时两处要一起对。
            FloatingService.setGuideSource(context, wantSource)
            source = wantSource
            guidePane = when (wantSource) {
                GuideSource.LOCAL -> 1
                GuideSource.LINK -> 2
                GuideSource.CLOUD -> 0
            }
        }
        if (next && !ensureOverlayReady()) return
        FloatingService.showGuide(context, next)
        running = next
    }

    /** 「自动连招」界面里那个独立按钮：只开关连招悬浮窗，攻略窗一个字节都不碰 */
    fun setMacroWindow(show: Boolean) {
        if (show && !ensureOverlayReady()) return
        FloatingService.showMacro(context, show)
        macroWindowOn = show
    }

    /**
     * v2.2.0「准星」开关：只开关准星层。
     * 没图片时直接开 = 屏幕上什么也没有，先拦一下并给下一步。
     */
    fun toggleCrosshair() {
        val next = !crosshairOn
        if (next && crosshairImage?.let { File(it).exists() } != true) {
            Toast.makeText(context, "先导入一张准星图片", Toast.LENGTH_SHORT).show()
            return
        }
        if (next && !ensureOverlayReady()) return
        FloatingService.showCrosshair(context, next)
        crosshairOn = next
    }

    /** 准星外观改了：状态先跟手，再让 Service 保持瞄准点不动地重算 */
    fun applyCrosshairLook() {
        FloatingService.applyCrosshair(context)
    }

    // ------------------------------------------------------------------
    // v2.3.0 多套准星：列表 ↔ 单值快照
    // ------------------------------------------------------------------

    fun selectCrosshair(id: String) {
        if (id == crosshairActiveId) return
        val target = crosshairList.firstOrNull { it.id == id } ?: return
        // 先把上一套还没落盘的改动存回去
        persistCrosshair(look = false)
        CrosshairProfiles.applyToPrefs(prefs, target)
        crosshairActiveId = target.id
        crosshairImage = target.imagePath
        crosshairAnchorX = target.anchorX
        crosshairAnchorY = target.anchorY
        crosshairWidth = target.widthPx
        crosshairAlpha = target.alpha
        crosshairLines = target.showLines
        // 准星开着就立刻换图；没开只更新偏好，等用户按开关
        if (crosshairOn) applyCrosshairLook()
        Toast.makeText(context, "已切换到「${target.name}」", Toast.LENGTH_SHORT).show()
    }

    fun newCrosshair() {
        if (crosshairList.size >= CrosshairProfiles.MAX) {
            Toast.makeText(context, "最多存 ${CrosshairProfiles.MAX} 套准星", Toast.LENGTH_SHORT).show()
            return
        }
        persistCrosshair(look = false)
        val p = CrosshairProfiles.newProfile(crosshairList)
        crosshairList += p
        // 先把 activeId 指到新套，再走统一的偏移逻辑（select 会先存旧套，旧套已是干净值）
        crosshairActiveId = ""
        selectCrosshair(p.id)
        // 新建的空套没有图，直接拉起选择器，少一步点击
        pickCrosshair.launch("image/*")
    }

    /**
     * v2.5.0：套用一套内置准星。
     *
     * 已经加过（同一个图片路径）就直接切过去，不重复占列表；没加过就新建一套并启用。
     * 内置图放在 filesDir/builtin_crosshairs，删这套准星时不能删源文件（见 [deleteCrosshair]）。
     */
    fun useBuiltin(item: InstalledCrosshair) {
        val existing = crosshairList.firstOrNull { it.imagePath == item.path }
        if (existing != null) {
            selectCrosshair(existing.id)
        } else {
            if (crosshairList.size >= CrosshairProfiles.MAX) {
                Toast.makeText(context, "最多存 ${CrosshairProfiles.MAX} 套准星", Toast.LENGTH_SHORT).show()
                return
            }
            persistCrosshair(look = false)
            val px = (item.meta.widthDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
            val profile = CrosshairProfile(
                id = UUID.randomUUID().toString(),
                name = item.meta.name,
                imagePath = item.path,
                anchorX = 0.5f,
                anchorY = 0.5f,
                widthPx = px,
                alpha = 1f,
                // 内置图自己就是准星，再叠一层参考线只剩乱
                showLines = false
            )
            crosshairList += profile
            CrosshairProfiles.save(prefs, crosshairList)
            crosshairActiveId = ""
            selectCrosshair(profile.id)
        }
        // 点内置准星 = 想马上看到效果，所以顺带把准星层打开
        if (!crosshairOn) toggleCrosshair()
    }

    fun renameCrosshair(id: String, name: String) {
        val idx = crosshairList.indexOfFirst { it.id == id }
        if (idx < 0) return
        crosshairList[idx] = crosshairList[idx].copy(name = name.trim().ifBlank { "准星" })
        CrosshairProfiles.save(prefs, crosshairList)
    }

    fun deleteCrosshair(id: String) {
        if (crosshairList.size <= 1) {
            Toast.makeText(context, "至少保留一套准星", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = crosshairList.indexOfFirst { it.id == id }
        if (idx < 0) return
        val removed = crosshairList.removeAt(idx)
        // 每套导入时都是独立复制的文件，删这套不会影响别的套；
        // v2.5.0：内置准星的图是所有套共用的，不能跟着删
        removed.imagePath?.let { path ->
            if (!BuiltinCrosshairs.isBuiltinPath(path)) runCatching { File(path).delete() }
        }
        if (crosshairActiveId == id) {
            crosshairActiveId = ""
            val next = crosshairList[idx.coerceAtMost(crosshairList.lastIndex)]
            CrosshairProfiles.applyToPrefs(prefs, next)
            crosshairActiveId = next.id
            crosshairImage = next.imagePath
            crosshairAnchorX = next.anchorX
            crosshairAnchorY = next.anchorY
            crosshairWidth = next.widthPx
            crosshairAlpha = next.alpha
            crosshairLines = next.showLines
        }
        CrosshairProfiles.save(prefs, crosshairList)
        // 图片没了 / 换了一套：让 Service 按新快照重挂
        applyCrosshairLook()
        Toast.makeText(context, "已删除「${removed.name}」", Toast.LENGTH_SHORT).show()
    }

    fun clearCrosshairImage() {
        crosshairImage?.let { old -> runCatching { File(old).delete() } }
        prefs.crosshairImage = null
        prefs.crosshairShown = false
        crosshairImage = null
        crosshairOn = false
        persistCrosshair(look = false)
        // 没有图就没法显示：Service 收到通知后把准星层摘掉
        FloatingService.applyCrosshair(context)
        Toast.makeText(context, "已清除准星图片", Toast.LENGTH_SHORT).show()
    }

    fun clearLocalTree() {
        runCatching {
            val old = localTreeUri
            if (!old.isNullOrBlank()) {
                val target = Uri.parse(old)
                context.contentResolver.persistedUriPermissions
                    .filter { it.uri == target && it.isReadPermission }
                    .forEach {
                        context.contentResolver.releasePersistableUriPermission(
                            it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
            }
        }
        prefs.localTreeUri = null
        FloatingService.setLocalTreeUri(context, null)
        localTreeUri = null
        Toast.makeText(context, "已清除本地文件夹", Toast.LENGTH_SHORT).show()
    }

    fun switchSource(next: GuideSource) {
        FloatingService.setGuideSource(context, next)
        source = next
        // v2.8.1：来源是「悬浮窗读哪份图」，页面是「人现在看哪一屏」，两者要一起走。
        // 下载完成后自动切回个人小抄、链接校验成功后自动切链接，都靠这一行把页面带过去。
        guidePane = when (next) {
            GuideSource.LOCAL -> 1
            GuideSource.LINK -> 2
            GuideSource.CLOUD -> 0
        }
        when {
            next == GuideSource.LOCAL && localTreeUri.isNullOrBlank() ->
                Toast.makeText(context, "往下点「添加小抄入口」，给每张地图各挑一个文件夹", Toast.LENGTH_SHORT).show()

            // 切到链接源但还没设过链接：直接说清下一步，别让用户切到悬浮窗看一片空白
            next == GuideSource.LINK && linkShare.isNullOrBlank() ->
                Toast.makeText(context, "在下面粘贴分享链接，点「打开」就能看", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v2.5.0：一键下载全部。
     *
     * 下载读的是「个人小抄」的目录树；用户当前停在本地小抄也没关系 —— 先切过去再开始，
     * 免得多一步「你得先去切一下」的来回。
     */
    fun startDownloadAll() {
        if (prefs.imageVaultEnabled == false) {
            // 没开本地化时下载器会直接返回，等于点了没反应，这里先说清
            prefs.imageVaultEnabled = true
        }
        if (source != GuideSource.CLOUD) {
            switchSource(GuideSource.CLOUD)
            Toast.makeText(context, "已切到个人小抄，正在准备下载…", Toast.LENGTH_SHORT).show()
        }
        downloader.downloadAll()
    }

    fun useLocalDirForDownload() {
        if (imageVault.useLocalGuideDir()) {
            downloadDirLabel = imageVault.dirPathLabel()
            Toast.makeText(context, "下载目录已跟本地小抄合并：$downloadDirLabel", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "先在「本地小抄」页选一个文件夹", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 链接小抄：先真打开一次，成功再落库（v2.0.2）。
     *
     * 为什么不在输入时就校验：`sharepage/token` 是网络请求，每敲一个字发一次既慢又
     * 会被服务端当异常流量。放在「打开」这一次点击上，用户预期明确，也只需要一次。
     *
     * 校验顺带把标题和分类数读出来 —— 失败的话用户立刻看到原因（失效 / 要提取码 /
     * 不是夸克），而不是切到小抄才发现一片空白。
     */
    fun applyLinkShare(text: String) {
        val value = text.trim()
        if (value.isEmpty() || linkBusy) return
        linkBusy = true
        uiScope.launch {
            val service = ShareLinkService.of(context)
            val check = runCatching {
                val opened = service.open(value, null).getOrThrow()
                val files = service.list(opened, service.rootFid(opened.platform)).getOrThrow()
                opened.session.title to files
            }
            linkBusy = false
            check.fold(
                onSuccess = { (title, files) ->
                    prefs.linkShare = value
                    prefs.linkShareTitle = title
                    FloatingService.setLinkShare(context, value, title)
                    linkShare = value
                    linkTitle = title
                    if (prefs.guideSource != GuideSource.LINK) switchSource(GuideSource.LINK)
                    val folders = files.count { it.isdir }
                    val images = files.count { !it.isdir && PanFiles.isImage(it.fname) }
                    Toast.makeText(
                        context,
                        "已连接：$title · $folders 个分类 / $images 张图",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        context,
                        "打不开这个链接：" + (e.message ?: "未知原因"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    fun clearLinkShare() {
        prefs.linkShare = null
        prefs.linkShareTitle = ""
        FloatingService.setLinkShare(context, null)
        linkShare = null
        linkTitle = ""
        Toast.makeText(context, "已断开链接小抄", Toast.LENGTH_SHORT).show()
    }

    fun openPanLogin(platform: PanPlatform) {
        val target = when (platform) {
            PanPlatform.QUARK -> QuarkLoginActivity::class.java
            PanPlatform.XUNLEI -> XunleiLoginActivity::class.java
        }
        context.startActivity(Intent(context, target))
    }

    fun resetOverlay() {
        FloatingService.reset(context)
        Toast.makeText(context, "正在重置悬浮窗…", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // 页面骨架
    // ------------------------------------------------------------------
    val scroll = rememberScrollState()
    // v3.0.3：滑动特效。普通模式下滚动还是外层 verticalScroll；
    // 换成「层叠 / 逐张」后滚动交给页内的 StaggerColumn（SlideDeck），
    // 这一层必须让开，否则两个滚动容器互相抢手势，滑一下就卡住。
    val slideMode by MotionPrefs.slide.collectAsState()
    // v3.4.2：顶栏标题可以被「图标文字 → 界面标题文字」覆盖
    val styleTuning by GuideStyleStore.tuning.collectAsState()

    // v3.0.9：卡组到底生不生效，按「这一页改造成卡组了没有」算，而不是只看设置。
    //
    // 只有「个人小抄 / 本地小抄」两页把模块包成了 StackModule，能堆成卡组；
    // 链接小抄 / 悬浮窗 / 连招 / 准星 / 解析 / 权限 / 我的 仍是「小标题 + 玻璃卡」，
    // 它们的滚动必须留给外层 verticalScroll。以前这里只看 slideMode，卡组模式下
    // 一刀切把外层滚动撤了，再在页内补一层 —— 页里本来就有 LazyColumn、
    // 样式选择器的 verticalScroll，被包进无限高度后直接抛
    //   "Vertically scrollable component was measured with an infinity max height"
    // 一进这些页就闪退。现在让开滚动只发生在真正卡组的那两页。
    val stackOn = slideMode == MotionPrefs.SLIDE_STACK && tab == 0 &&
        (guidePane == 0 || guidePane == 1)

    // v3.0.2：页面和悬浮窗的来源必须对齐 —— 站在本地小抄，窗里就该是本地文件；
    // 站在个人小抄，窗里就该是网盘。以前两者各记各的：点开本地小抄，窗还按上次
    // 存的 CLOUD 去读网盘，没登录就先弹「请先登录夸克网盘」，跟串台一样。
    // 只在窗已经开着时跟手；关着的时候不动来源，免得在设置页翻两下就把窗改了。
    LaunchedEffect(guidePane, running) {
        if (!running || guidePane == 3) return@LaunchedEffect
        val want = when (guidePane) {
            1 -> GuideSource.LOCAL
            2 -> GuideSource.LINK
            else -> GuideSource.CLOUD
        }
        if (prefs.guideSource != want) switchSource(want)
    }
    // v2.9.1：主项或子项任一变化都回到顶部 —— 切到「按键外观」还停在上页的半截位置很怪
    LaunchedEffect(tab, guidePane, macroPane, crosshairPane, authPane, accountPane) {
        runCatching { scroll.scrollTo(0) }
    }

    // v2.0.9：切页（小抄 ↔ 连招 ↔ 解析 …）= 整块内容区切成 92dp 的大方块，
    // 从左上角一块块「跳」出去 —— 块的消失途中会先鼓一下，所以看着是弹开，
    // 不是淡出。首次进页面不播：那一下交给 App 开场的细格，不然两层动画叠着。
    val pageCurtain = remember { Animatable(1f) }
    var pageCurtainArmed by remember { mutableStateOf(false) }
    // v2.5.1：开关 / 风格只在起跑时读一次，不当重播键 ——
    // 否则在「我的」调一下动画设置，底部切页的整屏大格遮罩会立刻重扫一遍。
    LaunchedEffect(tab) {
        if (!pageCurtainArmed) {
            pageCurtainArmed = true
            return@LaunchedEffect
        }
        val ms = if (MotionPrefs.switchStyle.value == MotionPrefs.RISE) {
            0
        } else {
            MotionPrefs.duration(SWITCH_MS)
        }
        if (ms <= 0) {
            pageCurtain.snapTo(1f)
            return@LaunchedEffect
        }
        pageCurtain.snapTo(0f)
        withFrameNanos { }
        pageCurtain.animateTo(1f, tween(ms, easing = LinearEasing))
    }

    // v1.9.5：窗口状态改为订阅 Service 的 StateFlow，值一变才组合一次。
    // 原来每秒轮询一次静态字段，低端机上白白唤醒 CPU，还把整页拖着重组的风险。
    LaunchedEffect(Unit) {
        FloatingService.guideAttachedFlow.collect { on ->
            if (FloatingService.isRunning) running = on
        }
    }
    LaunchedEffect(Unit) {
        FloatingService.macroAttachedFlow.collect { on ->
            if (FloatingService.isRunning) macroWindowOn = on
        }
    }
    LaunchedEffect(Unit) {
        FloatingService.crosshairAttachedFlow.collect { on ->
            if (FloatingService.isRunning) crosshairOn = on
        }
    }

    // v3.1.0：最外层包一个 Box —— 日夜扫屏那块遮罩要盖住顶栏、内容、抽屉，
    // 所以它必须是这棵树的最后一个子项
    Box(Modifier.fillMaxSize()) {
    GuideDrawerHost(
        // v3.4.6：双保险 —— Dock 模式下抽屉层根本不参与开合，
        // 哪怕状态被别处置了 true，也不会两套导航同时出现在屏幕上。
        open = drawerOpen && !dockNav,
        openSide = drawerSide,
        onOpenChange = { drawerOpen = it },
        items = DRAWER_ITEMS,
        selected = tab,
        onSelect = { next ->
            // 抽屉只切页面，绝不自己把悬浮窗弹出来（v1.9.3 的约定不变）
            tab = next
            drawerOpen = false
        },
        // v2.9.1：每个主项都有一组次按钮，高亮只看「当前主项 + 当前子页」。
        // 不同主项之间 key 可以重名（都有 "look"），靠 index == selected 区分。
        childSelected = when (tab) {
            0 -> when (guidePane) {
                1 -> "local"
                2 -> "link"
                3 -> "overlay"
                else -> "cloud"
            }
            1 -> when (macroPane) {
                1 -> "studio"
                2 -> "look"
                3 -> "share"
                else -> "switch"
            }
            2 -> if (crosshairPane == 1) "tune" else "list"
            3 -> if (resolvePane == 1) "transfers" else "resolve"
            5 -> if (authPane == 1) "auto" else "perm"
            7 -> when (accountPane) {
                1 -> "theme"
                2 -> "about"
                else -> "account"
            }
            else -> null
        },
        onSelectChild = { index, key ->
            // 点次按钮一定先落到它所属的主项
            if (index != tab) tab = index
            when (index) {
                0 -> when (key) {
                    "cloud" -> switchSource(GuideSource.CLOUD)
                    "local" -> switchSource(GuideSource.LOCAL)
                    // 链接小抄：配过就直接用它；没配过只把页面翻过去，别把悬浮窗的来源
                    // 切成一条空链接 —— 那会让正在看的图直接变白。
                    "link" -> if (!linkShare.isNullOrBlank()) {
                        switchSource(GuideSource.LINK)
                    } else {
                        guidePane = 2
                    }
                    // 悬浮窗页只改页面，不动来源：它管的是开关和外观，跟图从哪来无关
                    "overlay" -> guidePane = 3
                }
                1 -> macroPane = when (key) {
                    "studio" -> 1
                    "look" -> 2
                    "share" -> 3
                    else -> 0
                }
                2 -> crosshairPane = if (key == "tune") 1 else 0
                // v3.0.2：解析 / 下载合并成一页两栏，切子项就是翻同一页的两面
                3 -> resolvePane = if (key == "transfers") 1 else 0
                5 -> authPane = if (key == "auto") 1 else 0
                // v3.0.1：「外观与动画」整项撤掉 —— 那页的日夜 / 界面风格 / 玻璃质感 /
                // 动画 / 开屏，主题设置里一模一样都有，留着就是两个入口指同一堆开关。
                7 -> accountPane = when (key) {
                    "theme" -> 1
                    "about" -> 2
                    else -> 0
                }
            }
            drawerOpen = false
        },
        onOpenAbout = onOpenAbout,
        onOpenUsage = onOpenUsage,
        versionLabel = versionLabel,
        // 抽屉里那颗日夜按钮也走同一套扫屏，不再各切各的
        onToggleTheme = toggleTheme
    ) {
    // v2.8.1：小抄拆页后，标题就是当前那一页的名字 —— 打开本地小抄，
    // 顶栏写「本地小抄」，不再是笼统的「小抄」。v2.9.1 起其它页也一样。
    val headerTitle = when (tab) {
        0 -> GUIDE_PANE_TITLES.getOrElse(guidePane) { "小抄" }
        1 -> MACRO_PANE_TITLES.getOrElse(macroPane) { "连招" }
        2 -> if (crosshairPane == 1) "准星调整" else "我的准星"
        3 -> RESOLVE_PANE_TITLES.getOrElse(resolvePane) { "解析" }
        5 -> if (authPane == 1) "自动连招" else "权限"
        7 -> ACCOUNT_PANE_TITLES.getOrElse(accountPane) { "我的" }
        8 -> "主题商店"
        else -> null
    }
    // Dock 模式的顶栏是「大标题 + 一行小字」，小字写这一页能干什么
    val headerSubtitle = when (tab) {
        0 -> HomeTab.GUIDE.subtitle
        1 -> HomeTab.MACRO.subtitle
        2 -> HomeTab.CROSSHAIR.subtitle
        3 -> HomeTab.RESOLVE.subtitle
        5 -> "悬浮窗 · 通知 · 无障碍 · Root / Shizuku"
        6 -> HomeTab.PROTOCOL.subtitle
        7 -> "账号 · 主题设置 · 关于与使用说明"
        8 -> HomeTab.STORE.subtitle
        else -> null
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        val openMenu = {
            drawerSide = DrawerSide.LEFT
            drawerOpen = true
        }
        if (dockNav) {
            // v3.3.0：商店式导航的顶栏 —— 参考界面那种「一行圆钮 + 一个大标题」
            DockHeader(
                title = styleTuning.text("app.title", headerTitle ?: "云小抄"),
                subtitle = headerSubtitle,
                dark = uiDark,
                onOpenMenu = openMenu,
                onOpenStyle = { showStyle = true },
                onToggleTheme = toggleTheme,
                modifier = Modifier.statusBarsPadding()
            )
        } else {
            GlassHeader(
                tab = HomeTab.entries.getOrNull(tab) ?: HomeTab.GUIDE,
                title = headerTitle,
                running = running,
                macroWindowOn = macroWindowOn,
                crosshairOn = crosshairOn,
                overlayGranted = overlayGranted,
                onOpenDrawer = openMenu,
                showDrawerButton = !dockNav,
                onOpenStyle = { showStyle = true },
                dark = uiDark,
                onToggleTheme = toggleTheme,
                modifier = Modifier.statusBarsPadding()
            )
        }

        // v1.9.18：内容区左右滑 = 弹出侧栏（不再是翻页）。
        // 往右滑 → 左边那扇；往左滑 → 右边那扇；同一个面板，从哪边滑就从哪边进。
        // 手势挂在 Initial pass 上、自己不消费事件，所以上下滚动、按钮点击都不受影响。
        Box(
            Modifier
                .weight(1f)
                .pointerInput(drawerOpen, dockNav) {
                    // v3.4.5：Dock 模式没有抽屉可开，横滑手势一并停用
                    if (drawerOpen || dockNav) return@pointerInput
                    val threshold = 56.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        var dx = 0f
                        var dy = 0f
                        var taken = false
                        while (true) {
                            // 在 Final 阶段读：此时子控件（横向列表 / 滑杆）已经消费过事件，
                            // 它们自己的横滑不会被我们误判成「弹抽屉」
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.isConsumed) { taken = true; break }
                            dx += change.positionChange().x
                            dy += change.positionChange().y
                        }
                        if (!taken && abs(dx) > threshold && abs(dx) > abs(dy) * 1.2f) {
                            drawerSide = if (dx < 0f) DrawerSide.RIGHT else DrawerSide.LEFT
                            drawerOpen = true
                        }
                    }
                }
                // 切页过场：只重绘这一层，页面里的列表 / 输入框一个节点都不重组
                .tileCurtain(
                    progress = { pageCurtain.value },
                    color = curtainColor(),
                    spec = TileSpec.CHUNKY
                )
        ) {
            // v2.0.9：页面里的模块（分组标题 / 卡片 / 按钮）逐个从下方浮上来。
            // v2.3.0：这层容器改成普通 Column —— 逐项动画下放到每个页面里。
            // 原因：StaggerColumn 只能数「直接子项」，而连招 / 解析 / 下载 / 协议 / 我的
            // 这几页顶层都包了一个 Column，等于只有一个子项，整页一起淡入，看着像没动画；
            // 小抄 / 准星 / 权限是平铺的，所以一直有。现在各页自己挂 StaggerColumn，
            // 平铺的那几页在调用处包一层，八页观感统一。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        when {
                            // v2.8.0：下载页就是云析原版界面，内部是 LazyColumn，
                            // 必须拿到有界高度 —— 套整页 verticalScroll 会直接崩。
                            // v3.0.2：解析下载合并成 tab 3 一页两栏，滚动改由
                            // ResolveDownloadPane 自己管，外层同样不能套 scroll。
                            tab == 3 || tab == 4 -> Modifier.padding(horizontal = 18.dp)
                            // v3.3.0：主题商店自己就是 LazyVerticalGrid —— 同样不能套
                            // 整页 verticalScroll（会拿到无限高度直接崩），左右留白它自己管
                            tab == 8 -> Modifier
                            // v3.0.9：只有真正走卡组的两页让开外层滚动（由
                            // StackedColumn 自己滚），其余页一律保留外层
                            // verticalScroll —— 和普通模式完全一致。
                            stackOn ->
                                Modifier.padding(horizontal = 18.dp)
                            else -> Modifier
                                .verticalScroll(scroll)
                                .padding(horizontal = 18.dp)
                                // v3.3.0：Dock 常驻在底下 64dp 高，内容要留出位置，
                                // 否则最后一张卡永远被那颗胶囊压着
                                .padding(bottom = if (dockNav) 104.dp else 40.dp)
                        }
                    )
            ) {
                when (tab) {
                    // v2.3.0：平铺页在调用处包一层 StaggerColumn，逐项动画才数得到子项
                    // v2.8.1：小抄拆成四个独立页 —— 一页只出现自己的功能，
                    // 个人小抄里翻不到本地入口，本地小抄里也翻不到网盘账号。
                    // playKey = guidePane：换页时模块重新浮一次，让「换了一整页」看得出来。
                    // v3.0.8：卡组只作用于已改成 StackModule 的两页
                    // v3.1.0：卡组不再是一个「容器」，而是完整的一页 ——
                    // 一摞卡，最上面一张完整、其余只露标题边，点开才散。
                    // 只有已经改造成 DeckCard 列表的两页能用；链接小抄 / 悬浮窗
                    // 还是散件，照旧一条条铺开、逐项浮起。
                    0 -> when (guidePane) {
                        0 -> GuideDeckPage(
                            playKey = 0,
                            stackOn = stackOn,
                            bottomInset = if (dockNav) 108.dp else 40.dp
                        ) {
                            CloudGuidePane(
                                running = running,
                                session = session,
                                rootName = rootName,
                                downloadStats = downloadStats,
                                downloadDirLabel = downloadDirLabel,
                                onToggleOverlay = { toggleOverlay(GuideSource.CLOUD) },
                                onLogin = ::openPanLogin,
                                onUse = { platform -> store.setActivePlatform(platform) },
                                onLogout = { platform ->
                                    store.clear(platform)
                                    FloatingService.refresh(context)
                                },
                                onRootNameChange = { rootName = it },
                                onSaveRootName = {
                                    prefs.rootFolderName = rootName
                                    rootName = prefs.rootFolderName
                                    FloatingService.refresh(context)
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                },
                                onResetRootName = {
                                    rootName = OverlayPrefs.DEFAULT_ROOT_FOLDER
                                    prefs.rootFolderName = rootName
                                    FloatingService.refresh(context)
                                },
                                onStartDownloadAll = ::startDownloadAll,
                                onCancelDownload = { downloader.cancel() },
                                onPickDownloadDir = { pickDownloadDir.launch(null) },
                                onUseLocalDirForDownload = ::useLocalDirForDownload,
                                onResetDownloadDir = {
                                    imageVault.setTreeUri(null)
                                    downloadDirLabel = imageVault.dirPathLabel()
                                    Toast.makeText(context, "已恢复默认下载目录", Toast.LENGTH_SHORT).show()
                                }
                            )

                        }

                        1 -> GuideDeckPage(
                            playKey = 1,
                            stackOn = stackOn,
                            bottomInset = if (dockNav) 108.dp else 40.dp
                        ) {
                            LocalGuidePane(
                                running = running,
                                localTreeUri = localTreeUri,
                                onToggleOverlay = { toggleOverlay(GuideSource.LOCAL) },
                                onPickTree = { pickTree.launch(null) },
                                onClearTree = ::clearLocalTree
                            )
                        }

                        2 -> StaggerColumn(playKey = guidePane) {
                            LinkGuidePane(
                                running = running,
                                linkShare = linkShare,
                                linkTitle = linkTitle,
                                linkBusy = linkBusy,
                                onToggleOverlay = { toggleOverlay(GuideSource.LINK) },
                                onApplyLink = ::applyLinkShare,
                                onClearLink = ::clearLinkShare
                            )
                        }

                        else -> StaggerColumn(playKey = guidePane) {
                            OverlayPane(
                                running = running,
                                overlayGranted = overlayGranted,
                                scheme = scheme,
                                bubbleIcon = bubbleIcon,
                                onToggleOverlay = { toggleOverlay() },
                                onRequestOverlay = ::requestOverlay,
                                onReset = ::resetOverlay,
                                onSelectTheme = { key -> OverlayPalette.select(context, key) },
                                onPickBubbleIcon = {
                                    pickBubbleIcon.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onClearBubbleIcon = {
                                    bubbleIcon?.let { old -> runCatching { File(old).delete() } }
                                    prefs.bubbleIconPath = null
                                    bubbleIcon = null
                                    FloatingService.refresh(context)
                                }
                            )
                        }
                    }

                    // ★ 底部「自动连招」只切到这一页；开窗只认本页顶部那个单独按钮
                    1 -> MacroHomePage(
                        macroWindowOn = macroWindowOn,
                        overlayGranted = overlayGranted,
                        a11yEnabled = a11yEnabled,
                        onToggleMacroWindow = ::setMacroWindow,
                        onOpenAuth = { tab = 5 },
                        onReset = ::resetOverlay,
                        pane = macroPane
                    )

                    // v2.2.0 准星：独立一层，不影响小抄 / 连招
                    // v2.3.0：多套配置；逐项动画挂在页面内部，这里不要再包一层
                    2 -> CrosshairPage(
                        pane = crosshairPane,
                        running = crosshairOn,
                        profiles = crosshairList,
                        activeId = crosshairActiveId,
                        imagePath = crosshairImage,
                        anchorX = crosshairAnchorX,
                        anchorY = crosshairAnchorY,
                        widthPx = crosshairWidth,
                        alpha = crosshairAlpha,
                        showLines = crosshairLines,
                        locked = crosshairLocked,
                        onToggle = ::toggleCrosshair,
                        onSelectProfile = ::selectCrosshair,
                        onNewProfile = ::newCrosshair,
                        onRenameProfile = ::renameCrosshair,
                        onDeleteProfile = ::deleteCrosshair,
                        onPickImage = { pickCrosshair.launch("image/*") },
                        onClearImage = ::clearCrosshairImage,
                        onAnchorChange = { x, y ->
                            crosshairAnchorX = x
                            crosshairAnchorY = y
                            prefs.crosshairAnchorX = x
                            prefs.crosshairAnchorY = y
                            persistCrosshair(look = true)
                        },
                        onWidthChange = { px ->
                            crosshairWidth = px
                            prefs.crosshairWidth = px
                        },
                        onAlphaChange = { value ->
                            crosshairAlpha = value
                            prefs.crosshairAlpha = value
                        },
                        onLookCommit = { persistCrosshair(look = true) },
                        onToggleLines = { on ->
                            crosshairLines = on
                            prefs.crosshairLines = on
                            persistCrosshair(look = true)
                        },
                        onToggleLock = { on ->
                            crosshairLocked = on
                            prefs.crosshairLocked = on
                            // v2.7.1：锁定只是窗口触摸标志的开关，不走「重读外观」——
                            // 那条路会顺着锚点把几何重算一遍，锁定瞬间准星会自己挪一段。
                            FloatingService.setCrosshairLock(context, on)
                        },
                        onCenter = { FloatingService.centerCrosshair(context) },
                        // v2.7.3：长按「居中显示」= 校准屏幕方向（横竖识别反了时用）
                        onCenterSwap = { FloatingService.centerCrosshair(context, swap = true) },
                        onReset = {
                            crosshairAnchorX = 0.5f
                            crosshairAnchorY = 0.5f
                            crosshairWidth = 0
                            crosshairAlpha = 1f
                            crosshairLines = true
                            crosshairLocked = false
                            prefs.crosshairAnchorX = 0.5f
                            prefs.crosshairAnchorY = 0.5f
                            prefs.crosshairWidth = 0
                            prefs.crosshairAlpha = 1f
                            prefs.crosshairLines = true
                            prefs.crosshairLocked = false
                            persistCrosshair(look = false)
                            FloatingService.resetCrosshair(context)
                        },
                        builtins = builtinCrosshairs,
                        onUseBuiltin = ::useBuiltin
                    )

                    // v3.0.2：解析和下载合并成一页两栏，顶部切换条在页内
                    3 -> ResolveDownloadPane(
                        state = resolveState,
                        pane = resolvePane,
                        onPaneChange = { resolvePane = it }
                    )

                    4 -> DownloadPage()

                    5 -> StaggerColumn(playKey = authPane) { AuthPage(
                        pane = authPane,
                        overlayGranted = overlayGranted,
                        notifyGranted = notifyGranted,
                        a11yEnabled = a11yEnabled,
                        rootState = rootState,
                        rootBusy = rootBusy,
                        shizukuState = shizukuState,
                        onRequestShizuku = {
                            MacroController.refreshShizuku()
                            MacroController.requestShizuku()
                        },
                        localTreeUri = localTreeUri,
                        onRequestOverlay = ::requestOverlay,
                        onRequestNotification = ::requestNotification,
                        onOpenA11y = { MacroController.openAccessibilitySettings() },
                        onRequestRoot = { if (!rootBusy) MacroController.requestRoot() },
                        onPickTree = { pickTree.launch(null) },
                        onClearTree = ::clearLocalTree
                    ) }

                    6 -> ProtocolPage(
                        onOpenAbout = onOpenAbout,
                        onOpenAuth = { tab = 5 }
                    )

                    // v3.3.0：主题商店。不套外层滚动 —— 里面是 LazyVerticalGrid
                    8 -> ThemeStorePage(
                        tick = themeTick,
                        appliedId = appliedPackId,
                        onApply = applyThemePack,
                        onImport = { importThemePack.launch(arrayOf("*/*")) },
                        bottomInset = if (dockNav) 78.dp else 0.dp,
                        onOpenStudio = {
                            tab = 7
                            accountPane = 1
                        }
                    )

                    else -> AccountPage(
                        onOpenProtocol = { tab = 6 },
                        onOpenAbout = onOpenAbout,
                        onOpenNotes = onOpenNotes,
                        pane = accountPane
                    )
                }
            }
        }

        // v1.9.12：底部导航整排移除，页面切换收进左侧抽屉（从左边缘往右滑打开）。
        // 底部留白交给内容自己的 padding，主页看着就干净了。
    }
    }

        // v3.3.0：底部 Dock。浮在内容之上、日夜遮罩之下 ——
        // 放在遮罩前面，切日夜的时候胶囊也会被圆形扫过去，观感是一整块换的。
        if (dockNav) {
            val installedPacks = remember(themeTick) {
                runCatching { ThemePacks.installed(context).size }.getOrDefault(0)
            }
            val dockBadges = buildMap {
                if (installedPacks > 0) put(8, installedPacks)
            }
            val dockDots = buildSet {
                if (running) add(0)
                if (macroWindowOn) add(1)
            }
            DockNav(
                selected = tab,
                badges = dockBadges,
                dots = dockDots,
                onSelect = { next ->
                    if (next != tab) tab = next
                    drawerOpen = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp)
            )
        }

        ThemeWipeLayer(
            request = wipe,
            onEnd = { wipe = null }
        )
    }

    if (showStyle) {
        StylePickerDialog(onDismiss = { showStyle = false })
    }
}

// ======================================================================
// 顶部标题
// ======================================================================

@Composable
private fun GlassHeader(
    tab: HomeTab,
    /** 非空时覆盖 [tab] 的标题：小抄拆成四页后，顶栏要写具体那一页的名字 */
    title: String? = null,
    running: Boolean,
    macroWindowOn: Boolean,
    crosshairOn: Boolean,
    overlayGranted: Boolean,
    onOpenDrawer: () -> Unit,
    /** v3.4.5：Dock 模式下不再给抽屉入口 —— 两种导航方式互斥，选一种就只用一种 */
    showDrawerButton: Boolean = true,
    onOpenStyle: () -> Unit,
    /** v3.1.0：当前是不是深色 —— 决定那颗按钮画太阳还是月亮 */
    dark: Boolean,
    /** v3.1.0：点一下斜着扫过去换主题（由 HomeScreen 挂在最上层的遮罩负责） */
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = 22.dp)
            .padding(top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // v1.9.12：左侧菜单按钮 —— 抽屉的另一个入口（主入口是靠左边缘右滑）
        // v3.4.5：Dock 模式下整颗按钮不出现，两个导航体系彻底分开
        if (showDrawerButton) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(GlassColors.TextSub.copy(alpha = 0.10f))
                    .border(1.dp, GlassColors.Stroke, RoundedCornerShape(13.dp))
                    .clickable(onClick = onOpenDrawer)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Rounded.Menu,
                    contentDescription = "打开页面列表",
                    modifier = Modifier.size(19.dp),
                    tint = GlassColors.Text
                )
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title ?: tab.title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Text,
                    // v2.12.1：自定义背景（尤其亮色壁纸）下，标题加一层光晕
                    style = TextStyle(shadow = readableShadow())
                )
                Spacer(Modifier.width(9.dp))
                // 状态压成一个 7dp 小圆点：绿=在跑，橙=缺权限，灰=没开。
                // 原来那个渐变文字徽章直接把顶栏塞满了，字还小得看不清
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !overlayGranted -> GlassColors.Warn
                                running || macroWindowOn || crosshairOn -> GlassColors.Ok
                                else -> GlassColors.TextMuted.copy(alpha = 0.45f)
                            }
                        )
                )
            }
        }
        // v3.1.0：白天 / 黑夜专属按钮。点下去不是硬切，是从右上角斜着扫下来
        // 盖住整屏、扫满那一帧换主题 —— 就是《春秋》里那种过场
        Box(
            Modifier
                .clip(CircleShape)
                .background(GlassColors.TextSub.copy(alpha = 0.10f))
                .border(1.dp, GlassColors.Stroke, CircleShape)
                .clickable(onClick = onToggleTheme)
                .padding(8.dp)
        ) {
            Icon(
                if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                contentDescription = if (dark) "切换到白天模式" else "切换到黑夜模式",
                modifier = Modifier.size(16.dp),
                tint = GlassColors.TextSub
            )
        }
        Spacer(Modifier.width(8.dp))
        // v1.9.11：换肤按钮 —— 任何时候一点就能切三套风格，不用先翻到「我的」
        Box(
            Modifier
                .clip(CircleShape)
                .background(GlassColors.TextSub.copy(alpha = 0.10f))
                .border(1.dp, GlassColors.Stroke, CircleShape)
                .clickable(onClick = onOpenStyle)
                .padding(8.dp)
        ) {
            Icon(
                Icons.Rounded.Palette,
                contentDescription = "切换界面风格",
                modifier = Modifier.size(16.dp),
                tint = GlassColors.TextSub
            )
        }
    }
}

// ======================================================================
// 小抄（v2.8.1 拆成四个独立页面）
//
// 以前是一页里放「个人 / 本地 / 链接」三个来源开关，下面再堆入口、下载、
// 账号、外观 —— 切来源只是换掉中间一块，整页永远是一坨。
//
// 现在一页只干一件事，各页之间没有任何交叉：
//   · 个人小抄 —— 网盘账号、网盘目录、整棵树下载到本地
//   · 本地小抄 —— 根目录授权、小抄入口、资源链接
//   · 链接小抄 —— 一条分享链接的编辑与校验
//   · 悬浮窗   —— 开关、授权、重置、外观
// 每页顶部都带同一颗「开启 / 关闭小抄悬浮窗」按钮：配到哪一步都能就地开窗。
// ======================================================================

/** 四个小抄子页的名字：抽屉二级项与顶栏标题共用这一套说法 */
private val GUIDE_PANE_TITLES = listOf("个人小抄", "本地小抄", "链接小抄", "悬浮窗")

/**
 * 每页顶部的悬浮窗开关。
 *
 * v2.8.1：四页共用同一颗按钮 —— 用户不需要先记住「开关在哪一页」，
 * 打开任何一个小抄页，最上面就是它。
 */
@Composable
private fun GuideOverlaySwitch(running: Boolean, onToggle: () -> Unit): DeckCard {
    // v3.0.8：卡组模式下这就是一叠卡里的第一张
    // v3.1.0：卡组改成「一摞，点开才散」之后，它得把标题交出去 —— 收起时
    // 其余卡片不参与组合，只有标题能画成那条边
    return DeckCard(
        title = "小抄悬浮窗",
        badge = if (running) "运行中" else "已关闭",
        // 普通模式下这本来就是一颗大按钮，不用再套一层卡
        wrapPlain = false
    ) {
        GlassPrimaryButton(
            text = if (running) "关闭小抄悬浮窗" else "开启小抄悬浮窗",
            icon = Icons.Rounded.Description,
            onClick = onToggle,
            // 卡组模式下按钮直接躺在卡里，不留边会贴到卡沿上
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

/**
 * 一页卡组的内容：卡 + 卡下面那条「不是卡」的东西。
 *
 * 单独包一层，是因为本地小抄页尾部还挂着一条虚线链接 —— 它不是卡、不能进卡组，
 * 但收起态也得跟着一起排，所以当成 footer 一并传下去。
 */
private class DeckPageContent(
    val cards: List<DeckCard>,
    val footer: @Composable () -> Unit = {}
)

/**
 * 一页卡片的两种排法：
 *   · 卡组 —— 一摞：最上面一张完整，其余只露标题边，点开才散（v3.1.0）；
 *   · 普通 —— 还是一条条铺开、逐项浮起，和以前一模一样。
 */
@Composable
private fun GuideDeckPage(
    playKey: Any?,
    stackOn: Boolean,
    /** v3.3.0：Dock 模式底下压着一条 64dp 的胶囊，卡组要自己让开 */
    bottomInset: Dp = 40.dp,
    content: @Composable () -> DeckPageContent
) {
    val page = content()
    if (stackOn) {
        StackDeck(
            cards = page.cards,
            playKey = playKey,
            bottomInset = bottomInset,
            footer = page.footer
        )
    } else {
        StaggerColumn(playKey = playKey) {
            page.cards.forEach { StackModule(it) }
            page.footer()
        }
    }
}

// ----------------------------------------------------------------------
// ① 个人小抄：图从网盘来
// ----------------------------------------------------------------------

@Composable
private fun CloudGuidePane(
    running: Boolean,
    session: PanSession,
    rootName: String,
    downloadStats: GuideDownloader.Stats,
    downloadDirLabel: String,
    onToggleOverlay: () -> Unit,
    onLogin: (PanPlatform) -> Unit,
    onUse: (PanPlatform) -> Unit,
    onLogout: (PanPlatform) -> Unit,
    onRootNameChange: (String) -> Unit,
    onSaveRootName: () -> Unit,
    onResetRootName: () -> Unit,
    onStartDownloadAll: () -> Unit,
    onCancelDownload: () -> Unit,
    onPickDownloadDir: () -> Unit,
    onUseLocalDirForDownload: () -> Unit,
    onResetDownloadDir: () -> Unit
): DeckPageContent = DeckPageContent(
    cards = listOf(
        GuideOverlaySwitch(running, onToggleOverlay),

        DeckCard(
            title = "网盘账号",
            badge = if (session.credentials.isEmpty()) "未登录" else "已登录"
        ) {
            AccountGlassRow(
                platform = PanPlatform.QUARK,
                nickname = (session.credentials[PanPlatform.QUARK] as? PanCredential.Quark)?.nickname,
                active = session.platform == PanPlatform.QUARK,
                onUse = { onUse(PanPlatform.QUARK) },
                onLogin = { onLogin(PanPlatform.QUARK) },
                onLogout = { onLogout(PanPlatform.QUARK) }
            )
            GlassDivider()
            AccountGlassRow(
                platform = PanPlatform.XUNLEI,
                nickname = (session.credentials[PanPlatform.XUNLEI] as? PanCredential.Xunlei)?.nickname,
                active = session.platform == PanPlatform.XUNLEI,
                onUse = { onUse(PanPlatform.XUNLEI) },
                onLogin = { onLogin(PanPlatform.XUNLEI) },
                onLogout = { onLogout(PanPlatform.XUNLEI) }
            )
        },

        DeckCard(
            title = "网盘目录",
            note = "悬浮窗只显示这个目录里的图。换目录名后点「保存」，悬浮窗会立刻刷新。"
        ) {
            GlassCardEditor(
                value = rootName,
                onValueChange = onRootNameChange,
                onSave = onSaveRootName,
                onReset = onResetRootName
            )
        },

        OneClickDownloadCard(
            stats = downloadStats,
            dirLabel = downloadDirLabel,
            onStart = onStartDownloadAll,
            onCancel = onCancelDownload,
            onPickDir = onPickDownloadDir,
            onUseLocalDir = onUseLocalDirForDownload,
            onResetDir = onResetDownloadDir
        )
    )
)

// ----------------------------------------------------------------------
// ② 本地小抄：图在手机文件夹里
// ----------------------------------------------------------------------

@Composable
private fun LocalGuidePane(
    running: Boolean,
    localTreeUri: String?,
    onToggleOverlay: () -> Unit,
    onPickTree: () -> Unit,
    onClearTree: () -> Unit
): DeckPageContent = DeckPageContent(
    cards = listOf(
        GuideOverlaySwitch(running, onToggleOverlay),

        DeckCard(
            title = "小抄根目录",
            badge = if (localTreeUri.isNullOrBlank()) "未选择" else "已授权"
        ) {
            GlassRow(
                title = "小抄根目录",
                subtitle = if (localTreeUri.isNullOrBlank()) {
                    "未选择 · 也可以直接在下面「添加小抄入口」"
                } else {
                    prettyTreeUri(localTreeUri) + " · 没有入口时读这里"
                },
                icon = Icons.Rounded.Folder,
                onClick = onPickTree
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    GlassPill("选择", onClick = onPickTree)
                    if (!localTreeUri.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "清除",
                            fontSize = 10.sp,
                            color = GlassColors.Danger,
                            modifier = Modifier.clickable(onClick = onClearTree)
                        )
                    }
                }
            }
        },

        // 小抄入口：一个入口绑一个文件夹，点龙宫就只看到龙宫
        ShortcutEntrySection(onOpen = {})
    ),
    // 资源总链接：只复制，不解析、不下载、不建入口。它不是卡，挂在卡组下面
    footer = { ResourceLinkCard() }
)

// ----------------------------------------------------------------------
// ③ 链接小抄：图从一条分享链接来
// ----------------------------------------------------------------------

@Composable
private fun LinkGuidePane(
    running: Boolean,
    linkShare: String?,
    linkTitle: String,
    linkBusy: Boolean,
    onToggleOverlay: () -> Unit,
    onApplyLink: (String) -> Unit,
    onClearLink: () -> Unit
) {
    GuideOverlaySwitch(running, onToggleOverlay)

    GlassSectionTitle("分享链接")
    GlassCard {
        LinkShareEditor(
            link = linkShare,
            title = linkTitle,
            busy = linkBusy,
            onApply = onApplyLink,
            onClear = onClearLink
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "粘贴夸克分享链接，点「打开」会真连一次网盘 —— 能打开才算生效，" +
            "悬浮窗随后就跟着这条链接走。链接失效或需要提取码时，这里会直接报出来。",
        fontSize = 10.sp,
        lineHeight = 15.sp,
        color = GlassColors.TextMuted
    )
}

// ----------------------------------------------------------------------
// ④ 悬浮窗：开关、权限、外观
// ----------------------------------------------------------------------

@Composable
private fun OverlayPane(
    running: Boolean,
    overlayGranted: Boolean,
    scheme: OverlayScheme,
    bubbleIcon: String?,
    onToggleOverlay: () -> Unit,
    onRequestOverlay: () -> Unit,
    onReset: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onPickBubbleIcon: () -> Unit,
    onClearBubbleIcon: () -> Unit
) {
    val context = LocalContext.current
    // v3.0.2：调色盘。customColor 只用于「自选那颗圆点显示什么颜色」，
    // 真正的生效值走 OverlayPalette。0 表示还没调过，圆点显示彩虹。
    val prefs = remember(context) { OverlayPrefs(context) }
    var customColor by remember { mutableIntStateOf(prefs.overlayCustomColor) }
    var showPicker by remember { mutableStateOf(false) }
    val customActive = scheme.key == "custom"

    GuideOverlaySwitch(running, onToggleOverlay)

    GlassSectionTitle("授权与重置")
    GlassCard {
        GlassRow(
            title = "悬浮窗权限",
            subtitle = if (overlayGranted) "已授权" else "未授权 · 不开这个窗出不来",
            icon = Icons.Rounded.Lock,
            onClick = onRequestOverlay
        ) {
            GlassPill(if (overlayGranted) "查看" else "去授权", onClick = onRequestOverlay)
        }
        GlassDivider()
        GlassRow(
            title = "一键重置悬浮窗",
            subtitle = "位置跑偏 / 卡住了，点这里回到初始状态",
            icon = Icons.Rounded.Refresh,
            onClick = onReset
        ) {
            GlassPill("重置", onClick = onReset, primary = false)
        }
    }

    GlassSectionTitle("小抄外观")
    GlassCard {
        Column(Modifier.padding(14.dp)) {
            Text("界面主题", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GlassColors.Text)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OverlayPalette.schemes.forEach { item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(listOf(item.heroStart, item.heroEnd))
                                )
                                .border(
                                    width = if (scheme.key == item.key) 2.dp else 1.dp,
                                    color = if (scheme.key == item.key) {
                                        Color.White
                                    } else {
                                        GlassColors.Stroke
                                    },
                                    shape = CircleShape
                                )
                                .clickable { onSelectTheme(item.key) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (scheme.key == item.key) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(item.label, fontSize = 9.sp, color = GlassColors.TextSub)
                    }
                }
                // v3.0.2：第 6 颗是「自选」—— 调过色就显示那颗色，没调过是一圈彩虹。
                // 点开是色相条 + 饱和度/明度面板，存下来后整窗顶栏、按钮、滑杆一起换。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (customColor != 0) {
                                    Brush.linearGradient(
                                        listOf(
                                            Color(customColor),
                                            Color(customColor).copy(alpha = 0.62f)
                                        )
                                    )
                                } else {
                                    Brush.sweepGradient(
                                        listOf(
                                            Color(0xFFFF5A5A), Color(0xFFFFC94D),
                                            Color(0xFF5AFF8A), Color(0xFF4DD2FF),
                                            Color(0xFF9B6BFF), Color(0xFFFF5AC8),
                                            Color(0xFFFF5A5A)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = if (customActive) 2.dp else 1.dp,
                                color = if (customActive) Color.White else GlassColors.Stroke,
                                shape = CircleShape
                            )
                            .clickable { showPicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (customActive && customColor != 0) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = Color.White
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Palette,
                                contentDescription = "自定义颜色",
                                modifier = Modifier.size(15.dp),
                                tint = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("自选", fontSize = 9.sp, color = GlassColors.TextSub)
                }
            }
        }
        GlassDivider()
        GlassRow(
            title = "小抄球图标",
            subtitle = "",
            icon = Icons.Rounded.Image
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(GlassColors.Accent, GlassColors.Accent2))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val iconFile = bubbleIcon?.let { File(it) }
                    if (iconFile != null && iconFile.exists()) {
                        AsyncImage(
                            model = iconFile,
                            contentDescription = "小抄图标预览",
                            modifier = Modifier.size(40.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("抄", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                GlassPill("更换", onClick = onPickBubbleIcon, primary = false)
            }
        }
        if (!bubbleIcon.isNullOrBlank()) {
            GlassDivider()
            GlassRow(
                title = "恢复默认图标",
                subtitle = "",
                icon = Icons.Rounded.Refresh,
                onClick = onClearBubbleIcon
            ) {
                Text("清除 ›", fontSize = 10.sp, color = GlassColors.Danger)
            }
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = customColor,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                customColor = picked
                // 落盘 + 立刻切过去：用户点「用这个色」就是要看见窗变，
                // 不该再让他多点一次那颗圆点。
                OverlayPalette.selectCustom(context, picked)
                showPicker = false
            }
        )
    }
}

// ======================================================================
// 授权页：所有权限集中成一条条按钮
// ======================================================================

@Composable
private fun AuthPage(
    overlayGranted: Boolean,
    notifyGranted: Boolean,
    a11yEnabled: Boolean,
    rootState: RootShell.State,
    rootBusy: Boolean,
    shizukuState: ShizukuShell.State,
    onRequestShizuku: () -> Unit,
    localTreeUri: String?,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenA11y: () -> Unit,
    onRequestRoot: () -> Unit,
    onPickTree: () -> Unit,
    onClearTree: () -> Unit,
    /** v2.9.1：抽屉二级页。0 权限与目录 / 1 自动连招 */
    pane: Int = 0
) {
    if (pane == 0) {
    GlassSectionTitle("权限与目录")
    GlassCard {
        GlassRow(
            title = "悬浮窗权限",
            subtitle = if (overlayGranted) "已授权" else "未授权",
            icon = Icons.Rounded.Lock,
            onClick = onRequestOverlay
        ) {
            GlassPill(if (overlayGranted) "查看" else "去授权", onClick = onRequestOverlay)
        }
        GlassDivider()
        GlassRow(
            title = "通知权限",
            subtitle = when {
                Build.VERSION.SDK_INT < 33 -> "无需授权"
                notifyGranted -> "已授权"
                else -> "未授权"
            },
            icon = Icons.Rounded.Notifications,
            onClick = onRequestNotification
        ) {
            GlassPill(
                when {
                    Build.VERSION.SDK_INT < 33 -> "无需"
                    notifyGranted -> "查看"
                    else -> "去授权"
                },
                onClick = onRequestNotification,
                primary = Build.VERSION.SDK_INT >= 33 && !notifyGranted
            )
        }
        // v2.1.0：原先「本地文件」单独占一个分组标题 + 一张卡，其实它也是「授权」——
        // 只是授权对象从系统能力换成了一块目录。并进同一张卡，设置页从三组变两组。
        GlassDivider()
        GlassRow(
            title = "文件夹访问授权",
            subtitle = prettyTreeUri(localTreeUri),
            icon = Icons.Rounded.Folder,
            onClick = onPickTree
        ) {
            GlassPill(if (localTreeUri.isNullOrBlank()) "选择" else "更换", onClick = onPickTree)
        }
        if (!localTreeUri.isNullOrBlank()) {
            GlassDivider()
            GlassRow(
                title = "解除目录授权",
                subtitle = "",
                icon = Icons.Rounded.Refresh,
                onClick = onClearTree
            ) {
                Text("清除 ›", fontSize = 10.sp, color = GlassColors.Danger)
            }
        }
    }

    } else {
    GlassSectionTitle("自动连招")
    GlassCard {
        GlassRow(
            title = "无障碍服务",
            subtitle = if (a11yEnabled) "已开启" else "未开启",
            icon = Icons.Rounded.Accessibility,
            onClick = onOpenA11y
        ) {
            GlassPill(if (a11yEnabled) "查看" else "去开启", onClick = onOpenA11y)
        }
        GlassDivider()
        GlassRow(
            title = "Root 权限（可选）",
            subtitle = when {
                rootBusy -> "申请中…"
                rootState == RootShell.State.GRANTED -> "已授权"
                rootState == RootShell.State.NO_SU -> "设备无 su"
                rootState == RootShell.State.DENIED -> "已拒绝"
                else -> "未申请"
            },
            icon = Icons.Rounded.Shield,
            onClick = onRequestRoot
        ) {
            GlassPill(
                when {
                    rootBusy -> "申请中"
                    rootState == RootShell.State.GRANTED -> "重新申请"
                    else -> "获取 Root"
                },
                onClick = onRequestRoot,
                enabled = !rootBusy
            )
        }
        GlassDivider()
        // v3.2.0：Shizuku 是免 root 的「独立指针」路径。放在 Activity 里授权，
        // 比在悬浮面板里更容易拉起授权页（后台启动 Activity 的 ROM 限制少一些）。
        GlassRow(
            title = "Shizuku（可选 · 连招不打断走位）",
            subtitle = when (shizukuState) {
                ShizukuShell.State.GRANTED -> "已连接"
                ShizukuShell.State.NOT_GRANTED -> "Shizuku 在运行，尚未授权"
                ShizukuShell.State.DENIED -> "授权被拒绝，可重试"
                ShizukuShell.State.NO_SHIZUKU -> "未检测到 Shizuku"
                ShizukuShell.State.UNKNOWN -> "未检测"
            },
            icon = Icons.Rounded.Bolt,
            onClick = onRequestShizuku
        ) {
            GlassPill(
                if (shizukuState == ShizukuShell.State.GRANTED) "重新连接" else "去授权",
                onClick = onRequestShizuku
            )
        }
    }
    }
}

// ======================================================================
// 复用小部件
// ======================================================================

@Composable
private fun GlassCardEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Column(Modifier.padding(14.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = GlassColors.Text, fontSize = 12.sp),
            cursorBrush = SolidColor(GlassColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                // 用文字色的 6% 当输入框底：深色下是淡白、浅色下是淡黑，日夜都对
                .background(GlassColors.Text.copy(alpha = 0.06f))
                .border(1.dp, GlassColors.Stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GlassPill("保存", onClick = onSave)
            GlassPill("恢复默认", onClick = onReset, primary = false)
        }
    }
}

/**
 * 链接小抄的编辑区（v2.0.2）。
 *
 * 和 [GlassCardEditor] 的关键区别：那个存的是任意文本、存完立刻生效；这里「打开」
 * 是一次真实网络校验（open + list），所以按钮不叫「保存」—— 叫保存会让人以为
 * 点一下只是记下来，实际会转圈等网络。
 */
@Composable
private fun LinkShareEditor(
    link: String?,
    title: String,
    busy: Boolean,
    onApply: (String) -> Unit,
    onClear: () -> Unit
) {
    // key 用 link：断开 / 换成另一条之后，输入框要跟着刷新，
    // 否则会一直显示上一次粘贴的内容，用户以为没生效
    var draft by remember(link) { mutableStateOf(link.orEmpty()) }
    Column(Modifier.padding(14.dp)) {
        // v2.0.9：没连上时不再写「粘贴分享链接…」那行提示，输入框自己会说话
        if (title.isNotBlank()) {
            Text("已连接：$title", fontSize = 11.sp, color = GlassColors.Ok)
            Spacer(Modifier.height(9.dp))
        }
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            maxLines = 3,
            textStyle = TextStyle(color = GlassColors.Text, fontSize = 12.sp),
            cursorBrush = SolidColor(GlassColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassColors.Text.copy(alpha = 0.06f))
                .border(1.dp, GlassColors.Stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GlassPill(
                if (busy) "正在打开…" else if (link.isNullOrBlank()) "打开" else "重新打开",
                onClick = { onApply(draft) }
            )
            if (!link.isNullOrBlank()) {
                GlassPill("断开", onClick = onClear, primary = false)
            }
        }
    }
}

@Composable
private fun AccountGlassRow(
    platform: PanPlatform,
    nickname: String?,
    active: Boolean,
    onUse: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    GlassRow(
        title = "${platform.label}网盘",
        subtitle = nickname?.let { "已登录：$it" } ?: "未登录",
        icon = Icons.Rounded.Person,
        onClick = if (nickname == null) onLogin else onUse
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Text("使用中", fontSize = 9.sp, color = GlassColors.Ok)
                Spacer(Modifier.width(7.dp))
            }
            if (nickname == null) {
                GlassPill("登录", onClick = onLogin)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    if (!active) {
                        Text(
                            "设为使用中",
                            fontSize = 10.sp,
                            color = GlassColors.Accent,
                            modifier = Modifier.clickable(onClick = onUse)
                        )
                        Spacer(Modifier.height(5.dp))
                    }
                    Text(
                        "退出",
                        fontSize = 10.sp,
                        color = GlassColors.Danger,
                        modifier = Modifier.clickable(onClick = onLogout)
                    )
                }
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/** 把 content://.../tree/primary%3Axxx 变成人能看懂的路径 */
/**
 * 一键下载卡片（v2.5.0）。
 *
 * 用户原话：「每次打开小抄读取都太慢，增加一键下载，下载的目录告诉我在哪里，
 * 或者我自定义下载目录，然后下载的目录可以跟本地小抄读取目录同样在一起」。
 *
 * 所以这张卡必须同时回答三件事：
 *   1. 现在存到哪（完整路径，不是只有目录名）；
 *   2. 怎么改（自己选 / 直接跟本地小抄合并）；
 *   3. 现在在干什么（扫描 / 下载 / 等 WiFi / 完成）。
 */
@Composable
private fun OneClickDownloadCard(
    stats: GuideDownloader.Stats,
    dirLabel: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPickDir: () -> Unit,
    onUseLocalDir: () -> Unit,
    onResetDir: () -> Unit = {}
): DeckCard = DeckCard(title = "一键下载") {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(GlassColors.Accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = GlassColors.Accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "下载全部小抄到手机",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Text
                    )
                    Text(
                        "把「个人小抄」整棵树存成本地图片，之后切「本地小抄」秒开，不再等网盘",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = GlassColors.TextSub
                    )
                }
            }

            // 进度只在「真的跑过」之后出现，平时不占地方
            if (stats.running || stats.total > 0 || stats.waitingWifi) {
                Spacer(Modifier.height(12.dp))
                if (!stats.scanning) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(GlassColors.Divider)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(stats.progress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (stats.waitingWifi) GlassColors.Warn else GlassColors.Accent
                                )
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    when {
                        stats.scanning -> "正在扫描分类…"
                        stats.waitingWifi -> "已排队 ${stats.total} 张，连上 WiFi 自动开始"
                        stats.running -> "正在下载 ${stats.done + stats.failed} / ${stats.total} 张"
                        else -> "上次完成 ${stats.done} 张" +
                            if (stats.failed > 0) "，失败 ${stats.failed} 张" else ""
                    },
                    fontSize = 10.sp,
                    color = if (stats.waitingWifi) GlassColors.Warn else GlassColors.TextSub
                )
                stats.lastMessage?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, fontSize = 9.sp, color = GlassColors.TextMuted, maxLines = 2)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("保存到", fontSize = 9.sp, color = GlassColors.TextMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                dirLabel.ifBlank { "应用私有目录（Android/data/com.yunx.app.guide/files/guide_images）" },
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = GlassColors.TextSub
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = if (stats.running || stats.scanning) "暂停" else "开始下载",
                    primary = true,
                    onClick = { if (stats.running || stats.scanning) onCancel() else onStart() }
                )
                // ★ v2.5.5：下载目录三件套 —— 自己选 / 跟本地小抄合并 / 恢复默认。
                // 用户原话「下载文件要自定义，可以自己选」，所以「改目录」是主按钮，
                // 恢复默认放边上，选错了能一键回去。
                GlassPill(text = "改目录", primary = false, onClick = onPickDir)
                GlassPill(text = "恢复默认", primary = false, onClick = onResetDir)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(text = "跟本地小抄同目录", primary = false, onClick = onUseLocalDir)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "「同目录」= 下载直接写进本地小抄那个文件夹，下完切「本地小抄」就是刚下好的图。" +
                    "已经下过的会跳过，只补新增和作者改过的。",
                fontSize = 9.sp,
                lineHeight = 13.sp,
                color = GlassColors.TextMuted
            )
        }
    }

private fun prettyTreeUri(raw: String?): String {
    if (raw.isNullOrBlank()) return "未选择文件夹"
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(raw))
        val decoded = Uri.decode(docId)
        when {
            decoded.startsWith("primary:") ->
                "内部存储/" + decoded.removePrefix("primary:").trimStart('/')
            else -> decoded.replace(':', '/')
        }
    }.getOrDefault("已选择文件夹")
}
