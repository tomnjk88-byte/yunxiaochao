/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.8.0 主界面「透明液态玻璃」视觉层；
 * v1.9.11 拆成三套可切换风格，全部组件读 [LocalUiTokens]：
 *   - 风格 1 GLASS：深色底 + 半透明渐变卡片 + 双层高光描边（原样）；
 *   - 风格 2 LIQUID：镜面高光、强烈投影、缓慢流动的三团彩色光斑、悬浮胶囊底栏；
 *   - 风格 3 MATERIAL：Material You 浅色（跟随系统深色）白卡片 + 原生开关 + 原生底栏。
 * 底部导航改成可横向滑动的 LazyRow（7 个页面在 360dp 屏上放不下），
 * 选中项自动滚到可视区中间。
 *
 * 说明：minSdk 24 无法使用 RenderEffect 的背景模糊，这里用「半透明 + 渐变高光 +
 * 低饱和彩色光晕」在视觉上还原毛玻璃质感，性能与版本兼容性都更好。
 */

package com.yunx.app.overlay.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 语义色。字段名保持 v1.8 的原样，内部改成「读当前风格的 tokens」，
 * 所以所有旧界面的引用一行都不用改，切风格自动换色。
 */
object GlassColors {
    val Bg: Color
        @Composable get() = LocalUiTokens.current.bg

    val Text: Color
        @Composable get() = LocalUiTokens.current.text
    val TextSub: Color
        @Composable get() = LocalUiTokens.current.textSub
    val TextMuted: Color
        @Composable get() = LocalUiTokens.current.textMuted

    val Stroke: Color
        @Composable get() = LocalUiTokens.current.stroke
    val Divider: Color
        @Composable get() = LocalUiTokens.current.divider

    val Accent: Color
        @Composable get() = LocalUiTokens.current.accent
    val Accent2: Color
        @Composable get() = LocalUiTokens.current.accent2

    val Warn: Color
        @Composable get() = LocalUiTokens.current.warn
    val Danger: Color
        @Composable get() = LocalUiTokens.current.danger
    val Ok: Color
        @Composable get() = LocalUiTokens.current.ok
}

/** 浅色风格下「白色半透明」要换成「黑色半透明」，否则一整套控件全看不见 */
private fun UiTokens.softFill(alpha: Float = 0.13f): Color =
    if (dark) {
        // 真玻璃的卡片本身已经带一层白，控件再叠 8% 白就糊成一片了，这里抬到 1.7 倍
        Color.White.copy(alpha = if (frosted) alpha * 1.7f else alpha)
    } else {
        Color.Black.copy(alpha = alpha * 0.42f)
    }

/**
 * v2.12.1：自定义背景下的「可读性兜底」。
 *
 * 自定义背景 —— 尤其换成亮色壁纸之后 —— 玻璃那层淡到 8% 的底色就撑不住了：
 * 白字压在白衬衫上直接糊掉，浅描边的次要按钮整颗看不见。
 * 这里做两件事：
 *   ① 表面从「往上加白」改成「往下压黑」（深色主题），保证上面的字有底；
 *   ② 关键文字加一层薄光晕，落在花背景上也读得清。
 * 没换自定义背景时这两个函数一律退回原来的行为，一个像素都不动。
 */
@Composable
internal fun hasCustomBackdrop(): Boolean = LocalThemeTuning.current.backgroundKey.isNotEmpty()

/** 表面底色：自定义背景 + 深色主题时改压暗底 */
@Composable
internal fun readableFill(t: UiTokens, alpha: Float): Color =
    if (hasCustomBackdrop() && t.dark) {
        Color.Black.copy(alpha = (alpha + 0.34f).coerceAtMost(0.74f))
    } else {
        t.softFill(alpha)
    }

/** 文字光晕：自定义背景时给关键文字一层描边式阴影 */
@Composable
internal fun readableShadow(): Shadow? {
    if (!hasCustomBackdrop()) return null
    val t = LocalUiTokens.current
    return Shadow(
        color = if (t.dark) Color.Black.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.92f),
        offset = Offset.Zero,
        blurRadius = 6f
    )
}

private fun UiTokens.softStroke(alpha: Float = 0.22f): Color =
    if (dark) {
        Color.White.copy(alpha = if (frosted) (alpha * 1.5f).coerceAtMost(0.55f) else alpha)
    } else {
        Color.Black.copy(alpha = alpha * 0.45f)
    }

// ======================================================================
// 背景
// ======================================================================

/** 全屏底。五套风格各自的「壁纸」，内容可以透出底部的折射色 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val style = LocalUiStyle.current
    val tuning = LocalThemeTuning.current
    // v2.12.0：主题设置里选了自定义背景图 / 系统壁纸时，整个底换成那张图。
    // 没选就走原来的程序壁纸 —— 老用户升级完画面一个像素都不变。
    if (tuning.backgroundKey.isNotEmpty()) {
        CustomBackdrop(
            key = tuning.backgroundKey,
            progressive = tuning.progressiveScrim,
            modifier = modifier
        )
        return
    }
    // v1.9.18：只保留两套背景。液态玻璃用会动的光斑底（卡片真透明，直接透出这层），
    // 原生态用 Material You 的纯色底。
    when (style) {
        UiStyle.LIQUID -> LiquidBackdrop(modifier)
        UiStyle.NATIVE -> MaterialBackdrop(modifier)
    }
}

/**
 * v2.12.0：自定义背景（相册图片 / 系统桌面壁纸）。
 *
 * 解码放在 IO 线程，按目标屏幕宽高做 inSampleSize，避免 4000×3000 的相机原图
 * 直接铺到 1080p 窗口上把内存顶爆。加载失败退回深色渐变，不崩。
 */
@Composable
private fun CustomBackdrop(key: String, progressive: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key) {
        // v3.4.1 FIX: key 变化时先 dispose 上一张 Bitmap，避免频繁切背景时 native 内存堆积
        bitmap?.let { old -> if (!old.isRecycled) old.recycle() }
        bitmap = withContext(Dispatchers.IO) { loadBackdropBitmap(context, key) }
        // v3.4.1: 背景图加载完毕 → Palette API 提取主色调 → 自动注入 ThemeTuning
        // 这样强调色、卡片填充色会跟背景实时联动，杜绝生硬的纯白/纯黑
        DynamicPalette.extract(bitmap, key)
    }
    // v3.4.1 FIX: 离开组合时也 dispose，防止页面切走后 Bitmap 还挂在内存里
    DisposableEffect(key) {
        onDispose {
            bitmap?.let { old -> if (!old.isRecycled) old.recycle() }
            bitmap = null
        }
    }
    val t = LocalUiTokens.current
    val tuning = LocalThemeTuning.current
    // 图还没解码完 / 文件被清理 / 系统壁纸读不到时，用一层深色渐变兜底，
    // 比纯黑好 —— 纯黑会让人以为界面坏了。
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF171922), Color(0xFF0A0B10)))
        )
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        // 可读性薄雾：壁纸再花，界面上的字也得看得清。
        // 深色主题压一层黑（亮色壁纸尤其需要），浅色主题压一层白。
        val veil = if (t.dark) {
            (0.30f + (-tuning.scrimBias).coerceAtLeast(0f) * 0.45f).coerceIn(0f, 0.72f)
        } else {
            0.44f
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (t.dark) Color.Black.copy(alpha = veil)
                    else Color.White.copy(alpha = veil)
                )
        )
        if (progressive) {
            // 「渐进式背景模糊」：上 / 下各压一层渐变，边缘自然沉下去，
            // 顶栏和底栏不会跟壁纸的花纹打架。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                        )
                    )
            )
        }
    }
}

@Composable
private fun GlassBackdrop(modifier: Modifier) {
    val t = LocalUiTokens.current
    val dark = t.dark
    Box(modifier.fillMaxSize().background(GlassColors.Bg)) {
        Box(
            Modifier
                .size(430.dp)
                .offset(x = (-130).dp, y = (-110).dp)
                .background(
                    Brush.radialGradient(
                        listOf(if (dark) Color(0x404F46E5) else Color(0x3D6B5BFF), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .size(380.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 120.dp, y = 90.dp)
                .background(
                    Brush.radialGradient(
                        listOf(if (dark) Color(0x330D9488) else Color(0x2E2BB6A8), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .size(300.dp)
                .align(Alignment.CenterStart)
                .offset(x = (-150).dp, y = 130.dp)
                .background(
                    Brush.radialGradient(
                        listOf(if (dark) Color(0x229335D4) else Color(0x2AA65CD6), Color.Transparent)
                    )
                )
        )
    }
}

/**
 * 风格 2 的底。
 *
 * ★ v2.5.5：纯色底，不再画彩色光斑。
 * 原版是三团彩色光斑 + 渐变底 + 斜向折射高光，用户反馈「太花里胡哨了，
 * 改成纯色的或者无色的」。现在整块背景就是一整块中性色 —— 深色近黑、浅色近白，
 * 不带色相、不带渐变、不带光斑。卡片仍然是半透明玻璃（毛玻璃模糊 + 亮边），
 * 底色纯了，玻璃的通透感反而更清楚，也不会跟内容抢注意力。
 * 顺带把 26 秒一圈的全屏漂移动画也去掉了 —— 纯色底没有需要跟着走的光斑，
 * 少一条无限动画，滚动更省电。
 */
@Composable
private fun LiquidBackdrop(modifier: Modifier) {
    val dark = LocalUiTokens.current.dark
    Box(
        modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF0E0F13) else Color(0xFFF2F2F5))
    )
}

@Composable
private fun MaterialBackdrop(modifier: Modifier) {
    val t = LocalUiTokens.current
    Box(modifier.fillMaxSize().background(t.bg)) {
        // Material You 的 tonal 感：顶部一层主色 6% 的大面积柔光
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(t.accent.copy(alpha = if (t.dark) 0.14f else 0.10f), Color.Transparent)
                    )
                )
        )
    }
}

// ======================================================================
// 卡片 / 基础件
// ======================================================================

/** 玻璃卡片。三套风格的底色、描边、圆角、投影全部来自 tokens */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    /**
     * 是否允许走「真玻璃」。Dialog / Popup 有自己的 window，`positionInRoot` 采不到主界面
     * 那张壁纸的正确位置，硬画会得到一块「图案不对」的玻璃 —— 所以这种场景传 false，
     * 退化成干净半透明卡片。
     */
    allowFrost: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalUiTokens.current
    // v2.11.1：「主题设置 → 卡片不透明度」在这里落地 —— 只把卡片的两个底色乘淡，
    // 卡片里的文字一像素不动。挂在这一层而不是 tokens 里，是为了拖滑杆时
    // 只有卡片重绘，页面骨架不跟着重组。
    // v2.12.0：圆角、描边、投影也一起在这里读，主题设置里那三根滑杆才有落点。
    val tuning = LocalThemeTuning.current
    val cardAlpha = tuning.cardAlpha
    // v2.12.1：自定义背景 + 深色主题时，卡片底色给一个下限 ——
    // 8% 的白在亮色壁纸上等于没有，白字会直接糊进背景里。
    val alphaFloor = if (tuning.backgroundKey.isNotEmpty() && t.dark) 0.22f else 0f
    // v3.4.0：主题包带卡面图时，底色最多压到 0.78 —— 再高就把图完全盖住了，
    // 那几张图才是这个主题最值钱的部分，不能被一层白卡闷死。
    val art by ThemeSkin.state.collectAsState()
    val cardArt = art.card
    val alphaCap = if (cardArt != null) 0.78f else 1f
    val effAlpha = (if (cardAlpha > alphaFloor) cardAlpha else alphaFloor).coerceAtMost(alphaCap)
    val topColor = if (effAlpha >= 0.999f) t.cardTop else t.cardTop.copy(alpha = t.cardTop.alpha * effAlpha)
    val bottomColor = if (effAlpha >= 0.999f) t.cardBottom else t.cardBottom.copy(alpha = t.cardBottom.alpha * effAlpha)
    val radius = t.cardRadius * tuning.cardRadius
    val borderTop = t.cardBorderTop.copy(alpha = (t.cardBorderTop.alpha * tuning.borderScale).coerceIn(0f, 1f))
    val borderBottom = t.cardBorderBottom.copy(alpha = (t.cardBorderBottom.alpha * tuning.borderScale).coerceIn(0f, 1f))
    // 用自定义背景图时不能再采样程序壁纸（那张图对不上），退化成半透明卡片
    // v3.4.0：有卡面图时也不走真玻璃 —— 真玻璃那条路会重画背后的壁纸，
    // 把卡面图直接盖掉。退化成半透明卡，图才铺得上去。
    val frosted = t.frosted && allowFrost && tuning.backgroundKey.isEmpty() && cardArt == null
    // v1.9.13：鸿蒙 / 苹果两套走真玻璃（采样壁纸 + 高斯模糊 + 边缘折射），
    // 其余三套保持半透明叠加 —— 结构不变，所以切换风格不会丢页面状态。
    if (frosted) {
        FrostedSurface(corner = radius, modifier = modifier, content = content)
        return
    }
    val shape = RoundedCornerShape(radius)
    val base = modifier.fillMaxWidth()
    val elevated = t.cardElevation * tuning.shadowScale
    val decorated = when (t.style) {
        // 液态玻璃：压在背后的内容上，只加一层很淡的白 + 亮边。
        // allowFrost=false（弹窗这种独立 window）时也保持同样的透明观感。
        UiStyle.LIQUID -> base
            .then(
                if (elevated > 0.5.dp) {
                    Modifier.shadow(
                        elevation = if (t.dark) elevated * 0.4f else elevated,
                        shape = shape,
                        ambientColor = if (t.dark) Color(0x1A000000) else Color(0x2E000000),
                        // ★ v2.5.5：阴影去色相。原来偏蓝的 spotColor 在纯色底下会晕出一圈蓝，
                        //   用户说「花里胡哨」有一部分就是它。
                        spotColor = if (t.dark) Color(0x2A000000) else Color(0x22000000)
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .themeCardArt(
                image = cardArt,
                scrim = if (t.dark) Color.Black.copy(alpha = 0.36f) else Color.White.copy(alpha = 0.12f)
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        topColor,
                        bottomColor,
                        Color.White.copy(alpha = if (t.dark) 0.06f else 0.34f)
                    )
                )
            )
            .border(1.dp, Brush.verticalGradient(listOf(borderTop, borderBottom)), shape)

        UiStyle.NATIVE -> base
            .then(if (elevated > 0.5.dp) Modifier.shadow(elevated, shape, clip = false) else Modifier)
            .clip(shape)
            .themeCardArt(
                image = cardArt,
                scrim = if (t.dark) Color.Black.copy(alpha = 0.36f) else Color.White.copy(alpha = 0.12f)
            )
            .background(topColor)
            .border(1.dp, borderTop, shape)
    }
    Column(decorated) {
        // 风格 2：卡片顶部压一道镜面高光，宽度只有上半段
        if (t.mirrorHighlight) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = radius)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0x99FFFFFF), Color.Transparent)
                        )
                    )
            )
        }
        content()
    }
}

/** 卡片内行间分隔线（左侧留出图标位置） */
@Composable
fun GlassDivider() {
    // v2.12.0：界面细节 → 分割线强度
    val scale = LocalThemeTuning.current.dividerScale
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 14.dp)
            .height(1.dp)
            .background(
                if (scale >= 0.995f) GlassColors.Divider
                else GlassColors.Divider.copy(alpha = GlassColors.Divider.alpha * scale)
            )
    )
}

/** 分组小标题 */
@Composable
fun GlassSectionTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = GlassColors.TextMuted,
        style = TextStyle(shadow = readableShadow()),
        modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 9.dp)
    )
}

@Composable
fun GlassHint(text: String, color: Color = GlassColors.TextMuted) {
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 15.sp,
        color = color,
        style = TextStyle(shadow = readableShadow()),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp)
    )
}

/**
 * 一行设置项：玻璃图标底座 + 主标题 + 副标题 + 右侧控件。整行也可以直接当按钮点。
 * v2.7.3：新增 [onLongClick]，给「居中显示」这种「点一下是常用功能、长按是校准」的行用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = GlassColors.Accent,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit
) {
    val t = LocalUiTokens.current
    // v2.12.0：图标文字 → 底板形状；界面细节 → 隐藏说明文字
    val tuning = LocalThemeTuning.current
    val iconCorner = when (tuning.iconShape) {
        "circle" -> 17.dp
        "square" -> 4.dp
        else -> if (t.style == UiStyle.NATIVE) 12.dp else 11.dp
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(iconCorner))
                .background(
                    when {
                        t.style == UiStyle.NATIVE ->
                            Brush.verticalGradient(listOf(t.accent.copy(alpha = 0.14f), t.accent.copy(alpha = 0.10f)))
                        // 深色底用白色半透明托住图标；浅色底必须换成主色的淡淡一层，
                        // 否则 26% 白压在白卡片上完全看不见
                        t.dark ->
                            Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x10FFFFFF)))
                        else ->
                            Brush.verticalGradient(
                                listOf(t.accent.copy(alpha = 0.13f), t.accent.copy(alpha = 0.07f))
                            )
                    }
                )
                .border(1.dp, t.softStroke(0.22f), RoundedCornerShape(iconCorner)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = iconTint
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Text,
                style = TextStyle(shadow = readableShadow())
            )
            if (subtitle.isNotBlank() && !tuning.hideSubtitle) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = GlassColors.TextSub,
                    style = TextStyle(shadow = readableShadow())
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** 尾部胶囊按钮 */
@Composable
fun GlassPill(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    !enabled -> Brush.verticalGradient(listOf(t.softFill(0.10f), t.softFill(0.06f)))
                    primary -> Brush.horizontalGradient(listOf(t.accent, t.accent2))
                    // v2.12.1：自定义背景下，次要按钮改成压暗底 ——
                    // 原来那层 20% 的白在亮色壁纸上等于没有，整颗按钮会消失
                    else -> Brush.verticalGradient(
                        listOf(readableFill(t, 0.20f), readableFill(t, 0.12f))
                    )
                }
            )
            .border(1.dp, if (primary) t.softStroke(0.35f) else t.stroke, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> t.textMuted
                primary -> Color.White
                else -> t.text
            },
            style = TextStyle(shadow = readableShadow())
        )
    }
}

/**
 * 开关。自绘，避免 MaterialTheme 的亮色配色串进来（原生风格用 M3 的形状与配色）。
 *
 * v1.9.19：从「只能点」改成能拖 —— 照着 iOS 的手感做：
 * - 按住拖动，滑块跟手走；松手按「过半」或「甩速」吸附到开 / 关，不是拖到哪算哪；
 * - 轻点（位移没过 touchSlop）仍然切换，不用非得拖；
 * - 按下时滑块放大 6%，松手弹回，指头底下有「按住了」的反馈；
 * - 手指在屏幕上时，外部的 checked 变化不驱动动画，免得滑块被抢走。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val t = LocalUiTokens.current
    val material = t.style == UiStyle.NATIVE
    val w = if (material) 52.dp else 46.dp
    val h = if (material) 32.dp else 28.dp
    val thumbSize = if (material) 24.dp else 22.dp
    val edge = if (material) 4.dp else 3.dp

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val travelPx = with(density) { (w - thumbSize - edge * 2).toPx() }
    val edgePx = with(density) { edge.toPx() }
    val topPx = with(density) { ((h - thumbSize) / 2).toPx() }

    // 0 = 全关，1 = 全开。
    // 拖动时不能直接改 Animatable：手势回调跑在 AwaitPointerEventScope 这个「受限协程」里，
    // 它只允许调用自己的挂起成员，snapTo / animateTo 一律编译不过。所以拖动期间写一个
    // 普通 Float 状态，松手后交给 LaunchedEffect 去对齐 + 吸附。
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    var dragPos by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var settleTarget by remember { mutableStateOf<Boolean?>(null) }
    val press by animateFloatAsState(if (dragging) 1f else 0f, tween(110), label = "switchPress")

    // 显示位置：拖动中 / 等待吸附时看手指，其余看动画值，避免松手瞬间跳一帧
    val shown = if (dragging || settleTarget != null) dragPos else pos.value

    LaunchedEffect(checked) {
        if (!dragging && settleTarget == null) {
            pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 520f))
        }
    }
    LaunchedEffect(settleTarget) {
        val target = settleTarget ?: return@LaunchedEffect
        // 先把动画起点对齐到手指松开的位置，再弹到目标，不然会从旧位置跳过去
        pos.snapTo(dragPos)
        pos.animateTo(if (target) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 460f))
        settleTarget = null
    }

    val track = when {
        checked -> Brush.horizontalGradient(listOf(t.accent, t.accent2))
        material -> Brush.horizontalGradient(
            listOf(t.stroke.copy(alpha = 0.55f), t.stroke.copy(alpha = 0.55f))
        )
        // v2.12.1：自定义背景下关着的开关轨道也压暗，否则在亮壁纸上白成一条
        else -> Brush.horizontalGradient(listOf(readableFill(t, 0.20f), readableFill(t, 0.12f)))
    }

    Box(
        Modifier
            .width(w)
            .height(h)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (checked) t.softStroke(0.30f) else t.stroke, CircleShape)
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled, travelPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 自己在按下时就消费掉：整行也可点，不消费会「点一下切两次」
                    down.consume()
                    // 上一次的吸附可能还没播完：先清掉目标，effect 会取消旧动画；
                    // 不清的话，这次松手的目标若与上次相同，LaunchedEffect 的 key 没变，
                    // 新动画根本不会启动，滑块会卡在手指松开的位置。
                    settleTarget = null
                    val start = pos.value
                    dragPos = start
                    var dx = 0f
                    var moved = false
                    var lastX = down.position.x
                    var lastAt = down.uptimeMillis
                    var speed = 0f
                    dragging = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val step = change.position.x - lastX
                        val dt = (change.uptimeMillis - lastAt).coerceAtLeast(1L)
                        speed = step / dt * 1000f
                        lastX = change.position.x
                        lastAt = change.uptimeMillis
                        dx = change.position.x - down.position.x
                        if (!moved && abs(dx) > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            dragPos = (start + dx / travelPx).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }
                    dragging = false
                    // 轻点取反；拖动看甩速，再看过半 —— 规则抽在 SwitchDrag.kt，有单测
                    val next = switchTarget(checked, moved, dragPos, speed)
                    settleTarget = next
                    if (next != checked) {
                        // iOS 的开关在「啪」地落位时有一记轻震；只在真的翻面时给，拖到一半回弹不给
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCheckedChange(next)
                    }
                }
            }
    ) {
        Box(
            Modifier
                .offset {
                    IntOffset(
                        x = (edgePx + travelPx * shown).roundToInt(),
                        y = topPx.roundToInt()
                    )
                }
                .size(thumbSize)
                .graphicsLayer {
                    val s = 1f + 0.06f * press
                    scaleX = s
                    scaleY = s
                }
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x40000000),
                    spotColor = Color(0x40000000)
                )
                .clip(CircleShape)
                .background(
                    if (material && !checked) {
                        Brush.verticalGradient(listOf(t.textMuted, t.textMuted))
                    } else {
                        Brush.verticalGradient(listOf(Color.White, Color(0xFFE9E9F2)))
                    }
                )
        )
    }
}

/** 分段控件（网盘 / 本地、默认图标 / 自定义那种） */
@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(if (t.style == UiStyle.NATIVE) 999.dp else 14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (t.style == UiStyle.NATIVE) t.cardTop else t.softFill(0.08f))
            .border(1.dp, t.stroke, shape)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(if (t.style == UiStyle.NATIVE) 999.dp else 11.dp))
                    .background(
                        when {
                            !active -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            t.style == UiStyle.NATIVE ->
                                Brush.horizontalGradient(
                                    listOf(t.accent.copy(alpha = 0.18f), t.accent2.copy(alpha = 0.18f))
                                )
                            else -> Brush.horizontalGradient(
                                listOf(
                                    t.accent.copy(alpha = if (t.dark) 0.50f else 1f),
                                    t.accent2.copy(alpha = if (t.dark) 0.50f else 1f)
                                )
                            )
                        }
                    )
                    .clickable { onSelect(index) }
                    // v2.5.1：点击区从 ~30dp 提到 40dp。原来只有文字那么高，
                    // 手指稍微偏一点就落到缝里，表现是「点了没反应」
                    .heightIn(min = 40.dp)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active && t.style == UiStyle.NATIVE) t.accent else if (active) Color.White else t.textSub
                )
            }
        }
    }
}

data class GlassTab(val label: String, val icon: ImageVector)

/**
 * 全宽主色大按钮。
 *
 * 典型用途：自动连招界面里那个「单独开启连招悬浮窗」的按钮、登录按钮。
 * 与主界面底部那排 Tab 分开 —— 底部只负责切页面，真正开窗靠这一个按钮。
 */
@Composable
fun GlassPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(if (t.style == UiStyle.NATIVE) 999.dp else 18.dp)
    val bg = when {
        !enabled -> Brush.horizontalGradient(listOf(readableFill(t, 0.08f), readableFill(t, 0.05f)))
        danger -> Brush.horizontalGradient(listOf(t.danger, t.danger.copy(alpha = 0.82f)))
        // 原生风格用纯色（M3 的 Filled Button 不带渐变），另外两套保留渐变高光
        t.style == UiStyle.NATIVE -> Brush.horizontalGradient(listOf(t.accent, t.accent))
        else -> Brush.horizontalGradient(listOf(t.accent, t.accent2))
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, if (enabled) t.softStroke(0.28f) else t.stroke, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (enabled) Color.White else t.textMuted
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.White else t.textMuted
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = if (enabled) Color(0xDDFFFFFF) else t.textMuted,
                    style = TextStyle(shadow = readableShadow())
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) Color(0xCCFFFFFF) else t.textMuted
        )
    }
}

/** 玻璃输入框（登录页账号 / 密码）。密码用系统自带的圆点遮蔽，绝不落盘。 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    password: Boolean = false
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            // v2.12.1：自定义背景下压一层暗底，输入框的边框和文字才浮得出来
            .background(readableFill(t, 0.08f))
            .border(1.dp, t.stroke, shape)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = t.accent
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    fontSize = 12.sp,
                    color = t.textMuted,
                    style = TextStyle(shadow = readableShadow())
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = t.text,
                    fontSize = 13.sp,
                    shadow = readableShadow()
                ),
                cursorBrush = SolidColor(t.accent),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 自绘小勾选框（同意协议） */
@Composable
fun GlassCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    Row(
        modifier.clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier
                .size(16.dp)
                .clip(shape)
                .background(
                    if (checked) {
                        Brush.horizontalGradient(listOf(t.accent, t.accent2))
                    } else {
                        Brush.verticalGradient(listOf(t.softFill(0.10f), t.softFill(0.06f)))
                    }
                )
                .border(1.dp, if (checked) t.softStroke(0.30f) else t.stroke, shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = Color.White
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 10.sp, lineHeight = 14.sp, color = t.textSub)
    }
}

/** 个人中心里的一行「标签 → 值」 */
@Composable
fun GlassInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = GlassColors.TextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = readableShadow()),
            // ★ v3.0.0：标签最多占 96dp，剩下的宽度全留给右边的值。
            //   原来的写法是「标签 weight(1f)、值不设上限」—— 值一长就按自己的
            //   固有宽度铺开，把标签挤到 0，自己顶出卡片右边缘被裁掉。
            //   解析页「支持平台 / 夸克 / UC / 迅雷（前两家可游客读取）」那行
            //   右边看不见结尾，就是被这么裁的。
            modifier = Modifier.widthIn(max = 96.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = GlassColors.Text,
            textAlign = TextAlign.End,
            style = TextStyle(shadow = readableShadow()),
            // 值放不下就换行右对齐，不许溢出卡片
            modifier = Modifier.weight(1f)
        )
    }
}
