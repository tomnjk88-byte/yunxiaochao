/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：界面视觉改为「浅色卡片 + 渐变顶栏」，并内置 5 套配色。
 * 配色思路来自对参考 APK 资源表的解析：#F2F2F7 页面底、白色卡片、
 * 深色文字 #1C1C1E / #8E8E93、#E5E5EA 分隔线、8~12dp 圆角、
 * hero 渐变 + 橙色强调点。均为自行实现，未复制参考 APK 的任何资源文件。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.yunx.app.overlay.data.OverlayPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 静态中性色：不随主题变，保证文字在任何底色上都读得清 */
object OverlayColors {
    val Accent = Color(0xFF0D9488)
    val AccentSoft = Color(0x1A0D9488)

    /** 页面底：iOS 风浅灰。保留旧名字，主页/悬浮窗原有引用可直接复用 */
    val Page = Color(0xFFF2F2F7)
    val Card = Color(0xFFFFFFFF)
    val PanelDark = Color(0xFFF2F2F7)
    val PanelDarkElevated = Color(0xFFFFFFFF)

    val SidebarLight = Color(0xFFFFFFFF)
    val SidebarSelected = Color(0xFFE4F5F1)
    val SidebarText = Color(0xFF1C1C1E)
    val SidebarMuted = Color(0xFF8E8E93)
    val SidebarBorder = Color(0xFFE5E5EA)

    val TextPrimary = Color(0xFF1C1C1E)
    val TextSecondary = Color(0xFF5B616E)
    val TextMuted = Color(0xFF8E8E93)
    val TextInverse = Color(0xFFFFFFFF)

    val Divider = Color(0xFFE5E5EA)
    val Scrim = Color(0x99000000)
    val ImageScrim = Color(0x66000000)

    val Success = Color(0xFF34C759)
    val Warning = Color(0xFFFF9500)
    val Danger = Color(0xFFFF3B30)
}

/** 一套主题：主色 + 顶栏渐变 + 强调点 */
data class OverlayScheme(
    val key: String,
    val label: String,
    val primary: Color,
    val primaryDark: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val accent: Color,
    val soft: Color
)

/**
 * 全局主题源。悬浮窗在 Service 的独立 Compose 树里、主页在 Activity 里，
 * 两边都 collect 同一个 StateFlow，切换后能即时同步。
 */
object OverlayPalette {

    val schemes = listOf(
        OverlayScheme(
            "teal", "青碧",
            Color(0xFF0D9488), Color(0xFF0F766E),
            Color(0xFF0F766E), Color(0xFF14B8A6),
            Color(0xFFF97316), Color(0xFFF0FDFA)
        ),
        OverlayScheme(
            "indigo", "靛蓝",
            Color(0xFF4F46E5), Color(0xFF4338CA),
            Color(0xFF3730A3), Color(0xFF6366F1),
            Color(0xFFF59E0B), Color(0xFFEEF2FF)
        ),
        OverlayScheme(
            "jade", "翡翠",
            Color(0xFF059669), Color(0xFF047857),
            Color(0xFF065F46), Color(0xFF10B981),
            Color(0xFFF97316), Color(0xFFECFDF5)
        ),
        OverlayScheme(
            "rose", "玫红",
            Color(0xFFE11D48), Color(0xFFBE123C),
            Color(0xFF9F1239), Color(0xFFF43F5E),
            Color(0xFF0EA5E9), Color(0xFFFFF1F2)
        ),
        OverlayScheme(
            "twilight", "暮紫",
            Color(0xFF7C3AED), Color(0xFF6D28D9),
            Color(0xFF5B21B6), Color(0xFF8B5CF6),
            Color(0xFFF59E0B), Color(0xFFF5F3FF)
        )
    )

    private val _current = MutableStateFlow(schemes[0])
    val current: StateFlow<OverlayScheme> = _current.asStateFlow()

    fun init(context: Context) {
        val prefs = OverlayPrefs(context)
        _current.value = resolve(prefs, prefs.themeKey)
    }

    fun select(context: Context, key: String) {
        val prefs = OverlayPrefs(context)
        prefs.themeKey = key
        _current.value = resolve(prefs, key)
    }

    /**
     * v3.0.2：调色盘自选色。
     *
     * 只存一颗主色，其余（深色 / 顶栏两端 / 强调点 / 浅底）由它按 HSV 派生 ——
     * 五套预设之所以好看，是因为它们不是单一色块；自选色也要有同一套层次，
     * 否则顶栏和按钮全是一个颜色，看着像没渲染完。
     */
    fun selectCustom(context: Context, color: Int) {
        val prefs = OverlayPrefs(context)
        prefs.overlayCustomColor = color
        prefs.themeKey = "custom"
        _current.value = customScheme(color)
    }

    /** key → 真正生效的那套；custom 没调过色就退回第一套，不留空白顶栏 */
    private fun resolve(prefs: OverlayPrefs, key: String): OverlayScheme = when {
        key == "custom" && prefs.overlayCustomColor != 0 -> customScheme(prefs.overlayCustomColor)
        else -> schemes.firstOrNull { it.key == key } ?: schemes[0]
    }

    /** 一颗色 → 整套配色。明度往下压做顶栏起点，往上提做终点，浅底留给悬浮球回执 */
    fun customScheme(color: Int): OverlayScheme {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        // 主色会被拿去铺按钮、白字压在上面。纯白 / 纯黄压不住白字，
        // 统一把明度收到 0.88 以内 —— 五套预设里最亮的玫红也就这个亮度。
        val safeV = hsv[2].coerceAtMost(0.88f)
        val base = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], safeV)))
        return OverlayScheme(
            key = "custom",
            label = "自选",
            primary = base,
            primaryDark = shiftValue(base, 0.80f),
            heroStart = shiftValue(base, 0.66f),
            heroEnd = shiftValue(base, 1.10f),
            accent = complement(base),
            soft = lerp(base, Color.White, 0.90f)
        )
    }

    /** 只动明度：色相和饱和度保持用户选的那颗，压暗后不会偏色 */
    private fun shiftValue(color: Color, factor: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0.04f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** 强调点（角标 / 小圆点）取互补色：同一色系里挑不出对比，只能往对面找 */
    private fun complement(color: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

/**
 * v3.0.2：连招面板 / 按键外观用的强调色。
 *
 * 以前 OverlayColors.Accent 是写死的青绿 0xFF0D9488：悬浮窗主题换成紫色，
 * 连招面板里的「按键录制」还是绿的、选中项也是绿的 —— 同一块窗两套配色。
 * 现在直接读当前主题主色，换色即时跟手。
 */
@Composable
fun overlayAccent(): Color = currentScheme().primary

@Composable
fun overlayAccentSoft(): Color = currentScheme().primary.copy(alpha = 0.12f)

/** 供各 Composable 取当前主题（会随切换重组） */
@Composable
fun currentScheme(): OverlayScheme {
    val scheme by OverlayPalette.current.collectAsState()
    return scheme
}

/** 两个滑杆共用的配色：强调色轨道 + 浅灰底轨 */
@Composable
fun overlaySliderColors(): SliderColors {
    val scheme = currentScheme()
    return SliderDefaults.colors(
        thumbColor = scheme.primary,
        activeTrackColor = scheme.primary,
        inactiveTrackColor = OverlayColors.Divider,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent
    )
}

@Composable
fun OverlayTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OverlayPalette.init(context) }
    // v3.0.2：悬浮窗里的字也跟着「界面字体」走 —— 这里没有 GuideTheme 那层，
    // 得自己读一次 tuning。Service 是独立进程里的另一棵树，init 是幂等的。
    remember(context) { GuideStyleStore.init(context) }
    val tuning by GuideStyleStore.tuning.collectAsState()
    val chosenFont = remember(context, tuning.fontKey) { UiFonts.load(context, tuning.fontKey) }
    val scheme = currentScheme()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = scheme.primary,
            onPrimary = Color.White,
            primaryContainer = scheme.soft,
            onPrimaryContainer = scheme.primaryDark,
            background = OverlayColors.Page,
            onBackground = OverlayColors.TextPrimary,
            surface = OverlayColors.Card,
            onSurface = OverlayColors.TextPrimary,
            surfaceVariant = OverlayColors.Page,
            onSurfaceVariant = OverlayColors.TextSecondary,
            outline = OverlayColors.Divider
        ),
        content = {
            if (chosenFont == null) {
                content()
            } else {
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = chosenFont),
                    content = content
                )
            }
        }
    )
}
