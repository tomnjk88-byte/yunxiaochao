/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：v1.9.18 风格只留两套，其余全部删除。
 *   液态玻璃 LIQUID —— 真透明：卡片只压 3~8% 的白，背后的壁纸 / 列表 / 图片直接透过来，
 *                      靠 1dp 亮边、顶部镜面高光、边缘内反射撑出「玻璃」而不是「色块」；
 *                      点击时四周泛一圈光（GlowIndication），对应华为宣传片里那种按压反馈。
 *   安卓原生 NATIVE —— v1.9.11 的风格三，Material You：实心卡片、原生开关、紫调主色。
 *
 * 删除的是 v1.9.11 ~ v1.9.14 的 glass / harmony / apple 三套。老用户的偏好不会失效：
 * [UiStyle.of] 会把旧 key 映射到液态玻璃（material → 原生），不会出现「选过的风格消失、
 * 界面回到默认」的情况。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunx.app.overlay.data.OverlayPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 界面风格。存的是 [key]，不存序数，以后插新风格不会错位 */
enum class UiStyle(
    val key: String,
    val label: String,
    val short: String
) {
    // v2.0.10：说明文字从界面上删掉，也不再留在数据里
    LIQUID("liquid", "液态玻璃", "液态玻璃"),
    NATIVE("material", "安卓原生", "原生");

    /** 原生风格：实心表面、不使用玻璃绘制链路 */
    val isNative: Boolean get() = this == NATIVE

    companion object {
        /**
         * 旧 key 迁移：glass / harmony / apple 三套已删除，统一落到液态玻璃；
         * material 保持原生。null（新装）默认液态玻璃。
         */
        fun of(key: String?): UiStyle = when (key) {
            "material", "native" -> NATIVE
            "liquid", "glass", "harmony", "apple" -> LIQUID
            else -> LIQUID
        }
    }
}

/**
 * 白天 / 黑夜。默认跟随系统 —— 只给「白天 / 黑夜」两个硬开关的话，
 * 系统夜里自动切深色时用户还得再进来点一次。
 */
enum class UiMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "白天"),
    DARK("dark", "黑夜");

    companion object {
        fun of(key: String?): UiMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** 纯函数：把「偏好 + 系统当前是否深色」算成最终用不用深色。抽出来方便单测 */
internal fun resolveDark(mode: UiMode, systemDark: Boolean): Boolean = when (mode) {
    UiMode.SYSTEM -> systemDark
    UiMode.LIGHT -> false
    UiMode.DARK -> true
}

/**
 * 一套风格 + 一个日夜模式的全部可视参数。
 *
 * 只放「会随风格 / 日夜变」的东西；组件结构完全一致，所以切换不会丢页面状态。
 */
@Immutable
data class UiTokens(
    val style: UiStyle,
    val dark: Boolean,
    val bg: Color,
    val text: Color,
    val textSub: Color,
    val textMuted: Color,
    val stroke: Color,
    val divider: Color,
    val accent: Color,
    val accent2: Color,
    val warn: Color,
    val danger: Color,
    val ok: Color,
    /** 卡片渐变的上/下两端 */
    val cardTop: Color,
    val cardBottom: Color,
    /** 卡片描边的上/下两端（模拟顶部受光） */
    val cardBorderTop: Color,
    val cardBorderBottom: Color,
    val cardRadius: Dp,
    /** 卡片投影高度；0 = 不画阴影 */
    val cardElevation: Dp,
    /** 是否需要「镜面高光带」（液态玻璃专用） */
    val mirrorHighlight: Boolean,
    /**
     * 侧滑抽屉的底色。液态玻璃下是**半透明**的：调出抽屉时背后的页面还能看见，
     * 不是拉出一块实心黑板。
     */
    val panelTop: Color,
    val panelBottom: Color,
    /** 弹窗 / 对话框这种独立窗口用的不透明表面色（半透明会漏出窗口后面的黑） */
    val solid: Color,
    /** 抽屉遮罩，已经带 alpha；跟手时再乘进度 */
    val scrim: Color,
    /**
     * 是否走玻璃绘制链路（卡片按位置重绘壁纸 + 模糊 + 折射）。
     * 原生风格为 false：纯色卡片，最省。
     */
    val frosted: Boolean = false
)

// ----------------------------------------------------------------------
// 液态玻璃：深色
// ----------------------------------------------------------------------
private val LiquidDark = UiTokens(
    style = UiStyle.LIQUID,
    dark = true,
    bg = Color(0xFF05060E),
    text = Color(0xFFFFFFFF),
    textSub = Color(0xFFC6CBDD),
    textMuted = Color(0xFF858BA2),
    stroke = Color(0x33FFFFFF),
    divider = Color(0x1AFFFFFF),
    // v3.2.0：默认色从「蓝 → 紫」改成纯蓝渐变。原来的 accent2 是紫罗兰，
    // 两色叠在胶囊按钮上会显出紫粉中间色，就是用户说的「太难看了」的那套。
    accent = Color(0xFF6AA8FF),
    accent2 = Color(0xFF9BC6FF),
    warn = Color(0xFFFFD07A),
    danger = Color(0xFFFF7A85),
    ok = Color(0xFF5EEBA0),
    // v2.5.3：往 iOS Liquid Glass 收。
    // 填充从 10%→8%、2.7%→2%：玻璃本身几乎不上色，画面全靠背后那层模糊透上来；
    // 描边反过来把上沿提到 92%、下沿压到 6% —— 苹果那种「一圈亮线上沿最亮、
    // 越往下越沉」的厚度感，靠的是描边落差，不是靠填充变白。
    cardTop = Color(0x14FFFFFF),
    cardBottom = Color(0x05FFFFFF),
    cardBorderTop = Color(0xEBFFFFFF),
    cardBorderBottom = Color(0x0FFFFFFF),
    cardRadius = 26.dp,
    cardElevation = 18.dp,
    mirrorHighlight = true,
    // 抽屉：62% 左右，拉开时背后的页面还读得出来
    panelTop = Color(0x9E0D0F17),
    panelBottom = Color(0xB8070810),
    solid = Color(0xFF111220),
    scrim = Color(0x52000000),
    frosted = true
)

// ----------------------------------------------------------------------
// 液态玻璃：浅色
// ----------------------------------------------------------------------
private val LiquidLight = UiTokens(
    style = UiStyle.LIQUID,
    dark = false,
    bg = Color(0xFFEFF3FF),
    text = Color(0xFF10131C),
    textSub = Color(0xFF454B5C),
    textMuted = Color(0xFF6D7385),
    stroke = Color(0x1A10131C),
    divider = Color(0x1210131C),
    // v3.2.0：浅色同样去掉品红端，蓝 → 浅蓝，不再出现紫粉渐变
    accent = Color(0xFF1F6BFF),
    accent2 = Color(0xFF4C9BFF),
    warn = Color(0xFFA86200),
    danger = Color(0xFFC0271F),
    ok = Color(0xFF14743A),
    // v2.5.3：浅色同样往「清透」收。40%→32%、18%→12%。
    // 浅色玻璃最怕糊成一块灰白板 —— 上沿给到 98% 近乎纯白，下沿只留 14%，
    // 一眼就能看出这是「有边的一层」而不是「一块不透明的卡片」。
    cardTop = Color(0x52FFFFFF),
    cardBottom = Color(0x1FFFFFFF),
    cardBorderTop = Color(0xFAFFFFFF),
    cardBorderBottom = Color(0x24FFFFFF),
    cardRadius = 26.dp,
    cardElevation = 14.dp,
    mirrorHighlight = true,
    // 浅色抽屉同样半透明：拉开能看见背后的列表
    panelTop = Color(0xD9F4F7FF),
    panelBottom = Color(0xC2E9EFFF),
    solid = Color(0xFFFDFBFF),
    scrim = Color(0x3D000000),
    frosted = true
)

// ----------------------------------------------------------------------
// 安卓原生（Material You）
// ----------------------------------------------------------------------
private val NativeLight = UiTokens(
    style = UiStyle.NATIVE,
    dark = false,
    bg = Color(0xFFF7F2FA),
    text = Color(0xFF1D1B20),
    textSub = Color(0xFF49454F),
    textMuted = Color(0xFF79747E),
    stroke = Color(0xFFCAC4D0),
    divider = Color(0xFFE7E0EC),
    accent = Color(0xFF6750A4),
    accent2 = Color(0xFF7F67BE),
    warn = Color(0xFFB26A00),
    danger = Color(0xFFB3261E),
    ok = Color(0xFF2E7D32),
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFFFFFFF),
    cardBorderTop = Color(0x14000000),
    cardBorderBottom = Color(0x0A000000),
    cardRadius = 24.dp,
    cardElevation = 1.dp,
    mirrorHighlight = false,
    panelTop = Color(0xFFFFFBFF),
    panelBottom = Color(0xFFFFFBFF),
    solid = Color(0xFFFFFBFF),
    scrim = Color(0x40000000)
)

private val NativeDark = UiTokens(
    style = UiStyle.NATIVE,
    dark = true,
    bg = Color(0xFF141218),
    text = Color(0xFFE6E0E9),
    textSub = Color(0xFFCAC4D0),
    textMuted = Color(0xFF938F99),
    stroke = Color(0xFF49454F),
    divider = Color(0xFF2B2930),
    accent = Color(0xFFD0BCFF),
    accent2 = Color(0xFFCCC2DC),
    warn = Color(0xFFFFB77C),
    danger = Color(0xFFF2B8B5),
    ok = Color(0xFF7BD88F),
    cardTop = Color(0xFF1D1B20),
    cardBottom = Color(0xFF1D1B20),
    cardBorderTop = Color(0x1FFFFFFF),
    cardBorderBottom = Color(0x0FFFFFFF),
    cardRadius = 24.dp,
    cardElevation = 1.dp,
    mirrorHighlight = false,
    panelTop = Color(0xF21D1B20),
    panelBottom = Color(0xF21D1B20),
    solid = Color(0xFF211F26),
    scrim = Color(0x66000000)
)

fun tokensFor(style: UiStyle, dark: Boolean): UiTokens = when (style) {
    UiStyle.LIQUID -> if (dark) LiquidDark else LiquidLight
    UiStyle.NATIVE -> if (dark) NativeDark else NativeLight
}

val LocalUiTokens = staticCompositionLocalOf { LiquidDark }
val LocalUiStyle = staticCompositionLocalOf { UiStyle.LIQUID }

/**
 * v2.11.0：「主题设置 → 背景」三项的全局微调值。
 *
 * 单独拎出来、不塞进 [UiTokens] 的原因：tokens 是「风格 × 日夜」组合出来的常量，
 * 一共就那四套；这三项是同一个人在同一套风格上随时拖的滑杆，跟风格无关。
 * 混在一起就得为每个滑杆位置缓存一套 tokens，没必要。
 */
@Immutable
data class ThemeTuning(
    /** 卡片不透明度：1 原样，0.35 很透 */
    val cardAlpha: Float = 1f,
    /** 背景模糊倍数：0 不糊，1 默认，1.6 更糊 */
    val blurScale: Float = 1f,
    /** 背景遮罩：负变暗，正变亮 */
    val scrimBias: Float = 0f,
    /** 界面强调色。auto = 跟当前风格自带的那套 */
    val accentKey: String = "auto",
    /** 界面文字缩放：1 = 原样，0.85 更小，1.15 更大 */
    val fontScale: Float = 1f,
    /**
     * v3.0.2：界面字体。"" = 跟系统（[UiFonts.SYSTEM]）；
     * 其他 = 私有目录 fonts/ 下的文件名（挑 .ttf / .otf 装进来的那个）。
     */
    val fontKey: String = "",

    // ------------------------------------------------------------------
    // v2.12.0：对齐 SukiSU Nova 的主题分类（颜色 / 背景 / 图标文字 /
    // 首页卡片 / 底栏导航 / 交互手势 / 界面细节 / 主题包）。
    // ------------------------------------------------------------------

    /** 色彩风格：tonal 原色 / vibrant 鲜艳 / neutral 柔和 / expressive 活泼 / mono 单色 */
    val paletteStyle: String = "tonal",
    /** 色彩标准：2021 沉一点、2025 鲜一点 */
    val colorSpec: String = "2025",
    /** 「从背景图取色」得到的自定义强调色（ARGB）。0 = 没取过 */
    val customAccent: Int = 0,
    /** 卡片圆角倍数：0.6 更方，1.6 更圆 */
    val cardRadius: Float = 1f,
    /** 卡片描边强度倍数：0 不画，1.6 最亮 */
    val borderScale: Float = 1f,
    /** 卡片投影强度倍数 */
    val shadowScale: Float = 1f,
    /** 分割线强度倍数 */
    val dividerScale: Float = 1f,
    /** 设置行图标底板：rounded 圆角 / circle 圆形 / square 方形 */
    val iconShape: String = "rounded",
    /** 隐藏设置行的说明文字，只留标题 */
    val hideSubtitle: Boolean = false,
    /** 底栏密度：compact 紧凑 / normal 标准 / relaxed 宽松 */
    val bottomDensity: String = "normal",
    /** 底栏滑杆右侧的数值（默认关，保持旧观感） */
    val showSliderValue: Boolean = false,
    /** 背景上下边缘渐隐（截图里的「渐进式背景模糊」） */
    val progressiveScrim: Boolean = false,
    /**
     * v3.4.2：自定义界面标题。"" = 用页面自带的标题 / 默认「云小抄」。
     * 对齐 SukiSU Nova「界面文本自定义」里的「标题抬头」。
     */
    val customTitle: String = "",
    /**
     * 自定义背景："" = 跟风格走的程序壁纸；"system" = 系统桌面壁纸；
     * 其他 = 私有目录里的图片绝对路径
     */
    val backgroundKey: String = "",
    /**
     * v3.4.2：界面文本自定义。JSON 对象（键见 [THEME_TEXT_KEYS]），空串 = 全部默认。
     * 存 JSON 而不是拆字段：键还会继续加（首页标题、副标题、各页抬头……），
     * 拆成一个个 prefs 字段会让 readTuning/writeTuning 变成几十行的流水账。
     */
    val customTextsJson: String = "",
    /**
     * v3.4.2：用户手动指定的底栏图标。JSON 对象，key ∈ home/superuser/module/setting，
     * value = 私有目录图片路径。优先级高于主题包自带的底栏图标。
     */
    val dockIconsJson: String = ""
)

/** 「界面文本自定义」可编辑的条目：key → 界面上的叫法。顺序即设置页顺序 */
val THEME_TEXT_KEYS: List<Pair<String, String>> = listOf(
    "app.title" to "顶栏标题",
    "dock.home" to "底栏 · 小抄",
    "dock.macro" to "底栏 · 连招",
    "dock.theme" to "底栏 · 主题",
    "dock.mine" to "底栏 · 我的"
)

/** 把上面那些 key 的默认值写死在一处，清空输入框就回落到这里 */
fun defaultTextFor(key: String): String = when (key) {
    "app.title" -> "云小抄"
    "dock.home" -> "小抄"
    "dock.macro" -> "连招"
    "dock.theme" -> "主题"
    "dock.mine" -> "我的"
    else -> ""
}

/** JSON → 键值表。解析失败一律当空表，绝不因为一条脏数据让界面崩 */
fun parseTextMap(json: String): Map<String, String> {
    if (json.isBlank()) return emptyMap()
    return runCatching {
        val o = org.json.JSONObject(json)
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k ->
            val v = o.optString(k).trim()
            if (v.isNotEmpty()) out[k] = v
        }
        out
    }.getOrDefault(emptyMap())
}

/** 键值表 → JSON。空表直接返回空串，保持「没设置过」的语义 */
fun textMapToJson(map: Map<String, String>): String {
    val clean = map.filterValues { it.isNotBlank() }
    if (clean.isEmpty()) return ""
    return runCatching { org.json.JSONObject(clean as Map<*, *>).toString() }.getOrDefault("")
}

/** 取一条自定义文案；没设置就返回 [fallback] */
fun ThemeTuning.text(key: String, fallback: String): String {
    // 旧版只有一个 customTitle 字段，迁移时当成 app.title 用，别让老用户的自定义丢掉
    if (key == "app.title" && customTitle.isNotBlank() && customTextsJson.isBlank()) {
        return customTitle
    }
    return parseTextMap(customTextsJson)[key] ?: fallback
}

/** 色彩风格选项（顺序即界面顺序） */
val THEME_PALETTE_STYLES = listOf(
    "tonal" to "原色",
    "vibrant" to "鲜艳",
    "neutral" to "柔和",
    "expressive" to "活泼",
    "mono" to "单色"
)

/** 色彩标准 */
val THEME_COLOR_SPECS = listOf(
    "2025" to "2025 · 鲜明",
    "2021" to "2021 · 淡雅"
)


/** 主题设置里可选的一套强调色。深浅两版都备好，日夜切换不会撞成一片糊 */
data class ThemeAccent(
    val key: String,
    val label: String,
    val onDark: Color,
    val onLight: Color
)

/**
 * 强调色板。第一项 auto 表示「不覆盖，用风格自带色」——
 * 默认必须是它，否则老用户升级完会莫名其妙换色。
 */
val THEME_ACCENTS = listOf(
    ThemeAccent("auto", "默认", Color.Unspecified, Color.Unspecified),
    ThemeAccent("ice", "冰蓝", Color(0xFF6AA8FF), Color(0xFF2D6BFF)),
    ThemeAccent("teal", "青碧", Color(0xFF2FD6C4), Color(0xFF0E9384)),
    ThemeAccent("violet", "紫罗兰", Color(0xFFB58CFF), Color(0xFF7C3AED)),
    ThemeAccent("rose", "玫瑰", Color(0xFFFF7DA0), Color(0xFFD6336C)),
    ThemeAccent("amber", "琥珀", Color(0xFFFFC46B), Color(0xFFB47608)),
    ThemeAccent("jade", "翠绿", Color(0xFF5EEBA0), Color(0xFF0F9D58))
)

val LocalThemeTuning = staticCompositionLocalOf { ThemeTuning() }

/**
 * v2.12.0：把「色彩风格 + 色彩标准」作用到基础强调色上。
 *
 * 不引入 materialkolor 之类的调色库 —— 我们只需要「同一颗主色，五种气质」，
 * HSV 上动饱和度和色相就够了，几行代码，还不用加依赖。
 */
private fun applyPalette(color: Color, paletteStyle: String, colorSpec: String): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    when (paletteStyle) {
        "vibrant" -> {
            hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1f)
            hsv[2] = (hsv[2] + 0.04f).coerceAtMost(1f)
        }
        "neutral" -> hsv[1] *= 0.45f
        "expressive" -> {
            hsv[0] = (hsv[0] + 28f) % 360f
            hsv[1] = (hsv[1] * 1.12f).coerceAtMost(1f)
        }
        "mono" -> hsv[1] *= 0.06f
    }
    if (colorSpec == "2021") hsv[2] = (hsv[2] - 0.07f).coerceAtLeast(0.12f)
    else hsv[2] = (hsv[2] + 0.03f).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 覆盖强调色；auto 原样返回，一个像素都不动 */
private fun UiTokens.tintedAccent(tuning: ThemeTuning): UiTokens {
    val base = when (tuning.accentKey) {
        "auto" -> return this
        "custom" -> tuning.customAccent.takeIf { it != 0 }?.let { Color(it) } ?: return this
        else -> THEME_ACCENTS.firstOrNull { it.key == tuning.accentKey }
            ?.let { if (dark) it.onDark else it.onLight }
            ?: return this
    }
    val primary = applyPalette(base, tuning.paletteStyle, tuning.colorSpec)
    // accent2 取同色往白里提 22%，保留原来的「两色渐变」质感，
    // 不然强调色变成单色，按钮和进度条会显得扁。
    return copy(accent = primary, accent2 = lerp(primary, Color.White, 0.22f))
}

/**
 * 主题微调值 ⇄ 偏好文件。
 *
 * 放在 ui 包（而不是 OverlayPrefs 里）是为了不让 data 层反过来依赖 ui 层的
 * [ThemeTuning] —— 数据层只认 key 和类型，怎么组合归界面管。
 */
internal fun OverlayPrefs.readTuning(): ThemeTuning = ThemeTuning(
    cardAlpha = themeCardAlpha,
    blurScale = themeBackdropBlur,
    scrimBias = themeBackdropScrim,
    accentKey = themeAccentKey,
    fontScale = themeFontScale,
    fontKey = themeFontKey,
    paletteStyle = themePaletteStyle,
    colorSpec = themeColorSpec,
    customAccent = themeCustomAccent,
    cardRadius = themeCardRadius,
    borderScale = themeBorderScale,
    shadowScale = themeShadowScale,
    dividerScale = themeDividerScale,
    iconShape = themeIconShape,
    hideSubtitle = themeHideSubtitle,
    bottomDensity = themeBottomDensity,
    showSliderValue = themeShowSliderValue,
    progressiveScrim = themeProgressiveScrim,
    backgroundKey = themeBackgroundKey,
    customTextsJson = themeCustomTexts,
    dockIconsJson = themeDockIcons
)

internal fun OverlayPrefs.writeTuning(t: ThemeTuning) {
    themeCardAlpha = t.cardAlpha
    themeBackdropBlur = t.blurScale
    themeBackdropScrim = t.scrimBias
    themeAccentKey = t.accentKey
    themeFontScale = t.fontScale
    themeFontKey = t.fontKey
    themePaletteStyle = t.paletteStyle
    themeColorSpec = t.colorSpec
    themeCustomAccent = t.customAccent
    themeCardRadius = t.cardRadius
    themeBorderScale = t.borderScale
    themeShadowScale = t.shadowScale
    themeDividerScale = t.dividerScale
    themeIconShape = t.iconShape
    themeHideSubtitle = t.hideSubtitle
    themeBottomDensity = t.bottomDensity
    themeShowSliderValue = t.showSliderValue
    themeProgressiveScrim = t.progressiveScrim
    themeBackgroundKey = t.backgroundKey
    themeCustomTexts = t.customTextsJson
    themeDockIcons = t.dockIconsJson
}

/**
 * 风格 / 日夜源：Activity 与 Service 各有一个 Compose 树，共用这个 StateFlow，
 * 切换后两棵树同时换肤。
 */
object GuideStyleStore {

    private val _style = MutableStateFlow(UiStyle.LIQUID)
    val style: StateFlow<UiStyle> = _style.asStateFlow()

    /** 白天 / 黑夜 / 跟随系统 */
    private val _mode = MutableStateFlow(UiMode.SYSTEM)
    val mode: StateFlow<UiMode> = _mode.asStateFlow()

    /** 玻璃质感：跟着风格一起走 StateFlow，主界面与悬浮窗两棵树同时生效 */
    private val _quality = MutableStateFlow(GlassQuality.BALANCED)
    val quality: StateFlow<GlassQuality> = _quality.asStateFlow()

    /** v2.11.0：主题设置里那三根滑杆 */
    private val _tuning = MutableStateFlow(ThemeTuning())
    val tuning: StateFlow<ThemeTuning> = _tuning.asStateFlow()

    /**
     * v3.3.0：导航方式（抽屉 / 底部 Dock）。
     *
     * 跟风格、日夜一样走 StateFlow —— 主题设置里点一下，主界面立刻换，
     * 不用退出去重进。
     */
    private val _nav = MutableStateFlow(NavStyle.DRAWER)
    val nav: StateFlow<NavStyle> = _nav.asStateFlow()

    fun init(context: Context) {
        val prefs = OverlayPrefs(context)
        _style.value = UiStyle.of(prefs.uiStyleKey)
        _mode.value = UiMode.of(prefs.uiModeKey)
        _quality.value = GlassQuality.of(prefs.glassQualityKey)
        _tuning.value = prefs.readTuning()
        _nav.value = NavStyle.of(prefs.navStyleKey)
    }

    fun select(context: Context, style: UiStyle) {
        OverlayPrefs(context).uiStyleKey = style.key
        _style.value = style
    }

    fun selectNav(context: Context, nav: NavStyle) {
        OverlayPrefs(context).navStyleKey = nav.key
        _nav.value = nav
    }

    fun selectMode(context: Context, mode: UiMode) {
        OverlayPrefs(context).uiModeKey = mode.key
        _mode.value = mode
    }

    fun selectQuality(context: Context, quality: GlassQuality) {
        OverlayPrefs(context).glassQualityKey = quality.key
        _quality.value = quality
    }

    /**
     * v2.11.0：拖动「背景」那三根滑杆时走这里。
     *
     * 拖动过程中会连着调很多次，所以写盘也一起做 —— SharedPreferences 的 apply()
     * 是内存里改完就返回、异步落盘，不会卡住手指。不写盘的话转个屏就全丢了。
     */
    fun setTuning(context: Context, tuning: ThemeTuning) {
        OverlayPrefs(context).writeTuning(tuning)
        _tuning.value = tuning
    }

    /**
     * 拖滑杆时只改内存值，让预览实时跟手。
     *
     * 不用 [setTuning] 是因为拖动一秒能触发几十次，每次都 edit() + apply() 建一个
     * Editor 对象，虽然不阻塞但纯属浪费；松手时调一次 [commitTuning] 落盘就够了。
     */
    fun previewTuning(tuning: ThemeTuning) {
        _tuning.value = tuning
    }

    /** 松手落盘：把当前内存里的那一份写进偏好 */
    fun commitTuning(context: Context) {
        OverlayPrefs(context).writeTuning(_tuning.value)
    }
}

/**
 * 换肤入口。放在 [OverlayTheme] 里面用，MaterialTheme 仍然管 M3 组件（Switch 等）的默认色。
 *
 * 首帧就在 [remember] 里同步读一次偏好，避免「先渲染默认风格、再闪一下换成保存的风格」。
 *
 * 日夜：偏好是「跟随系统」时读 [isSystemInDarkTheme]；选白天 / 黑夜时直接用偏好，
 * 所以系统深色开着也能强制浅色（反过来同样成立）。
 */
// v2.11.1：remember 的 block 返回 Unit 是有意的 —— 这里要的不是缓存一个值，
// 而是「首帧渲染之前，这段初始化只跑一次」。换成 LaunchedEffect 会晚一帧，
// 皮肤会先按默认值画一遍再换成用户存的，看得见闪。lint 按惯例报 Error，显式忽略。
@android.annotation.SuppressLint("RememberReturnType")
@Composable
fun GuideTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context) { GuideStyleStore.init(context) }
    val style by GuideStyleStore.style.collectAsState()
    val mode by GuideStyleStore.mode.collectAsState()
    val quality by GuideStyleStore.quality.collectAsState()
    val tuning by GuideStyleStore.tuning.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = resolveDark(mode, systemDark)
    // v2.11.1：卡片不透明度**不**参与这里的 tokens 构建。
    // 之前把 cardAlpha 乘进 tokens，拖滑杆时每帧都生成一套新 tokens，
    // CompositionLocalProvider 一变，整棵界面（顶栏、卡片、开关……）全部重组。
    // 现在交给 GlassCard / FrostedSurface 自己去读 LocalThemeTuning，
    // 拖动时只有卡片那一层重绘，页面骨架不动。
    val tokens = remember(
        style,
        dark,
        tuning.accentKey,
        tuning.paletteStyle,
        tuning.colorSpec,
        tuning.customAccent
    ) {
        tokensFor(style, dark).tintedAccent(tuning)
    }
    // v2.11.0：「界面文字」滑杆。只改 fontScale，不动 density ——
    // 动 density 会把卡片圆角、间距一起放大，那叫整体缩放，不叫改字号。
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, tuning.fontScale) {
        if (tuning.fontScale >= 0.995f && tuning.fontScale <= 1.005f) {
            baseDensity
        } else {
            Density(baseDensity.density, baseDensity.fontScale * tuning.fontScale)
        }
    }
    // v3.0.2：界面字体。
    // key 为空 = 不给 fontFamily，走 Compose 的默认字体 —— 那本来就是系统字体，
    // 系统主题商店换过字体的机器会跟着换（只是要重开 App 才刷新生效）。
    // key 非空 = 私有目录里的那个 .ttf/.otf，加载失败就当没设，绝不让界面变成方框。
    val chosenFont = remember(context, tuning.fontKey) {
        UiFonts.load(context, tuning.fontKey)
    }
    val baseTextStyle = LocalTextStyle.current
    val textStyle = remember(baseTextStyle, chosenFont) {
        if (chosenFont == null) baseTextStyle else baseTextStyle.copy(fontFamily = chosenFont)
    }
    CompositionLocalProvider(
        LocalUiTokens provides tokens,
        LocalUiStyle provides style,
        LocalGlassQuality provides quality,
        LocalThemeTuning provides tuning,
        LocalDensity provides scaledDensity,
        LocalTextStyle provides textStyle,
        // 点击反馈：所有 clickable 默认都走这圈光
        LocalIndication provides GlowIndication(tokens.accent),
        // v2.1.1：换风格不再硬切。遮罩挂在这层里面，读得到当前 tokens。
        // 用大格（92dp）—— 换的是整张皮，细格看着像加载，大格才像「掀开」。
        content = { StyleCurtainLayer { content() } }
    )
}
