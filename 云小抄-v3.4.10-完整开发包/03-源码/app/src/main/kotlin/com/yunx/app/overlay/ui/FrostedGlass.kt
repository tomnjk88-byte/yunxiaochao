/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.13 真 · 毛玻璃卡片。
 *
 * 之前所有「玻璃」都是假的：半透明白 + 渐变描边，背后其实没有内容透过来。
 * 这里的 [FrostedSurface] 做三件真事：
 *   1. **真透视**：按自己在屏幕里的位置，把 [BackdropSpec] 那一层壁纸重绘一遍（和背景同源，
 *      所以光斑位置完全对得上，滚动时「玻璃里的像」跟着背景走）；
 *   2. **真模糊**：那一层交给 `Modifier.blur()`，Android 12+ 走 RenderEffect 的高斯模糊；
 *   3. **真厚度**：边缘再叠一圈「放大 1.14 倍的壁纸」（只保留 9dp 环带），
 *      模拟玻璃边缘把画面掰弯 —— iOS 液态玻璃那种映射感的来源。
 *
 * 低配兜底见 [GlassQuality]：LITE 档完全不跑模糊、不重绘壁纸，开销和风格 1 基本一样。
 * Android 11 及以下没有 RenderEffect，自动降级到 LITE（UI 上会说明）。
 */

package com.yunx.app.overlay.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 玻璃质感三档。**这不是配色，是开销档位** —— 低配手机切「流畅」就能回到风格 1 的成本。
 */
enum class GlassQuality(
    val key: String,
    val label: String
) {
    // v2.0.10：档位说明不再显示，也不再留在数据里
    LITE("lite", "流畅"),
    BALANCED("balanced", "标准"),
    ULTRA("ultra", "极致");

    companion object {
        fun of(key: String?): GlassQuality =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/** Android 12 以下没有 RenderEffect，`Modifier.blur()` 是空操作，只能退化 */
internal fun blurAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 纯函数：模糊不可用时强制降到 LITE。单独抽出来是为了能写 JVM 单测（不碰 Build） */
internal fun resolveQuality(preferred: GlassQuality, blurSupported: Boolean): GlassQuality =
    if (blurSupported) preferred else GlassQuality.LITE

internal fun effectiveQuality(preferred: GlassQuality): GlassQuality =
    resolveQuality(preferred, blurAvailable())

val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.BALANCED }

/**
 * 磨砂颗粒。96×96 的随机灰点平铺，alpha 很淡（整体再乘 1），
 * 只在「极致」档出现 —— 它负责让大片模糊看起来是「玻璃」而不是「糊了的照片」。
 */
private val NoiseBrush: ShaderBrush by lazy {
    val n = 96
    val pixels = IntArray(n * n)
    val random = java.util.Random(0x5EEDL)
    for (i in pixels.indices) {
        val v = 110 + random.nextInt(146)
        val a = 14 + random.nextInt(40)
        pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * 真 · 毛玻璃容器。只在「鸿蒙 / 苹果」两套风格下被 [GlassCard] 调用。
 *
 * @param corner 圆角（跟着 tokens 走，两种风格不一样大）
 */
@Composable
internal fun FrostedSurface(
    corner: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalUiTokens.current
    val style = tokens.style
    val spec = remember(style, tokens.dark) { backdropFor(style, tokens.dark) }
    val quality = effectiveQuality(LocalGlassQuality.current)
    // v2.11.0：「主题设置 → 背景模糊」那根滑杆。1 = 原来的默认半径，
    // 0 = 完全不糊（只剩一层干净的壁纸，等于纯色板），1.6 = 更糊。
    // 只乘半径、不跳过绘制层 —— 跳过的话卡片就只剩几个百分点的底色，会整片消失。
    val tuning = LocalThemeTuning.current
    val blurScale = tuning.blurScale
    // v3.4.1: Palette 提取到的主色调 —— 如果有，就在卡片填充色上叠一层弱染色，
    // 让卡片微微染成背景图的主色调（alpha 很低，主要是和背景融合，不是覆盖）
    val paletteColors by DynamicPalette.colors.collectAsState()
    val paletteTint = paletteColors.vibrant?.let { lift(it, tokens.dark) }
        ?: paletteColors.muted?.let { lift(it, tokens.dark) }
    val tintStrength = if (paletteTint != null) 0.12f else 0f
    val baseTop = tokens.cardTop
    val baseBottom = tokens.cardBottom
    val rawTop = if (tuning.cardAlpha >= 0.999f) baseTop
        else baseTop.copy(alpha = baseTop.alpha * tuning.cardAlpha)
    val rawBottom = if (tuning.cardAlpha >= 0.999f) baseBottom
        else baseBottom.copy(alpha = baseBottom.alpha * tuning.cardAlpha)
    // 把 palette 主色按 tintStrength 叠上去 —— 比如白色卡面叠一层 12% 的蓝色 → 淡蓝卡片
    val cardTop = paletteTint?.let { lerp(it, rawTop, tintStrength) } ?: rawTop
    val cardBottom = paletteTint?.let { lerp(it, rawBottom, tintStrength * 0.5f) } ?: rawBottom
    val baseBlur = if (quality == GlassQuality.ULTRA) 24.dp else 14.dp
    val blurRadius = baseBlur * blurScale
    val shape = RoundedCornerShape(corner)

    // 卡片在屏幕里的位置。滚动时会变，但只写进一个 State、只在绘制阶段读，
    // 所以只是「重画」而不是「重组」—— 滚动时不会因为采样位置变化把整页重组一遍。
    var origin by remember { mutableStateOf(Offset.Zero) }
    val viewportHeight = LocalWindowInfo.current.containerSize.height

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // v1.9.19：深色下原来投的是 70% 黑 + 18dp 环境阴影。卡片几乎通屏宽、几张又挨着，
            // 外扩的阴影在卡片之间连成一片，看起来就是「中间糊了一大块灰」——白天环境光白，
            // 看不出来，只有黑夜明显。现在深色几乎不投黑，只用一点冷光把边界托住。
            // ★ v2.5.5：冷光改成中性灰。原来 spotColor 是 0x332F7BFF（偏蓝），
            //   纯色底下这圈蓝晕特别扎眼，就是「花里胡哨」的一部分。改成无色相的灰，
            //   阴影只负责托边界，不负责染色。
            .shadow(
                elevation = if (tokens.dark) tokens.cardElevation * 0.4f else tokens.cardElevation,
                shape = shape,
                ambientColor = if (tokens.dark) Color(0x1A000000) else Color(0x2E000000),
                spotColor = if (tokens.dark) Color(0x2A000000) else Color(0x22000000)
            )
            .clip(shape)
    ) {
        // ① 玻璃底：背后的壁纸按同一坐标重画一遍，整体高斯模糊
        if (spec != null && quality != GlassQuality.LITE) {
            Box(
                Modifier
                    .matchParentSize()
                    .blur(
                        radius = blurRadius,
                        edgeTreatment = BlurredEdgeTreatment.Unbounded
                    )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 滚出屏幕的卡片不画：模糊是整条链路里最贵的一步
                    if (origin.y > viewportHeight + 320f || origin.y + size.height < -320f) return@Canvas
                    drawWallpaper(
                        spec,
                        origin,
                        zoom = if (quality == GlassQuality.ULTRA) 1.06f else 1f
                    )
                }
            }
        }

        // ② 折射环：只留边缘 9dp，用放大 1.14 倍的壁纸。放大越靠边偏移越大 → 像被掰弯
        if (spec != null && quality == GlassQuality.ULTRA && style == UiStyle.LIQUID) {
            Canvas(Modifier.matchParentSize()) {
                if (origin.y > viewportHeight + 320f || origin.y + size.height < -320f) return@Canvas
                val ring = 9.dp.toPx()
                val cornerPx = corner.toPx()
                val outer = Path().apply {
                    addRoundRect(
                        RoundRect(0f, 0f, size.width, size.height, CornerRadius(cornerPx))
                    )
                }
                val inner = Path().apply {
                    addRoundRect(
                        RoundRect(
                            ring,
                            ring,
                            max(ring, size.width - ring),
                            max(ring, size.height - ring),
                            CornerRadius(max(0f, cornerPx - ring))
                        )
                    )
                }
                val band = Path().apply { op(outer, inner, PathOperation.Difference) }
                clipPath(band) { drawWallpaper(spec, origin, zoom = 1.14f) }
            }
        }

        // ③ 玻璃本体：鸿蒙是「白雾」，苹果是「清透」
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(cardTop, cardBottom)))
        )

        // ④ 顶部受光：苹果的玻璃顶边是「一道硬光」，往下五分之一就吃掉 ——
        // 不是整片往下蒙白。v2.5.3 把衰减点从 30% 提到 18%、峰值从 14% 压到 10%：
        // 之前那层 14% 的白一直铺到卡片三成高，卡片看着就像起了一层雾。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (tokens.dark) 0.10f else 0.38f),
                        0.18f to Color.Transparent,
                        0.84f to Color.Transparent,
                        1f to Color.White.copy(alpha = if (tokens.dark) 0.03f else 0.07f)
                    )
                )
        )

        // ④.2 斜向镜面：左上到右下一道高光。液态玻璃的「流动感」八成来自这道光，
        // 但它也是雾感的第二个来源 —— v2.5.3 从 10%/34% 降到 4.5%/14%，
        // 现在是「隐隐有一道光扫过去」，而不是「卡片左上角糊了一块白」。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = if (tokens.dark) 0.045f else 0.14f),
                        0.34f to Color.Transparent,
                        0.66f to Color.Transparent,
                        1f to Color.White.copy(alpha = if (tokens.dark) 0.02f else 0.05f)
                    )
                )
        )

        // ④.5 内侧暗边：玻璃有厚度才会「压」得住下面的颜色，不然看起来像一张贴纸。
        // v2.5.3：7dp 收到 3dp、深色 16% 压到 10%。原来那圈 7dp 的黑边在圆角处
        // 会堆成一小块脏灰，正是「不像苹果」最明显的地方 —— 苹果的内缘只有很窄一条。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = if (tokens.dark) 0.10f else 0.05f)
                            )
                        ),
                        cornerRadius = CornerRadius(corner.toPx()),
                        style = Stroke(3.dp.toPx())
                    )
                }
        )

        // ⑤ 磨砂颗粒：深色底上是「玻璃的沙」，浅色底上会变成一层灰，索性不画。
        // ★ v2.5.5：从「只在极致档」放宽到「标准档也画」—— 纯色底下卡片只剩半透明色块，
        //   颗粒是唯一能让人看出「这是毛玻璃不是塑料片」的东西。极致档颗粒保持原样。
        if (quality != GlassQuality.LITE && tokens.dark) {
            Box(Modifier.matchParentSize().background(NoiseBrush))
        }

        // ⑥ 内容 + 边缘（描边画在内容之上，只有 1~2dp，压不到文字）
        Column(
            Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    drawGlassEdges(tokens, corner.toPx(), quality)
                }
        ) {
            content()
        }
    }
}

/**
 * 画「背后那张壁纸」：优先用烘焙好的位图（一次 drawImage，便宜），
 * 缓存还没建好时退回现画渐变（首帧可能走这条）。
 */
internal fun DrawScope.drawWallpaper(spec: BackdropSpec, origin: Offset, zoom: Float) {
    val raster = BackdropCache.peek()
    if (raster == null || raster.spec !== spec) {
        paintBackdrop(spec, origin, zoom)
        return
    }
    val offset = IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt())
    val dstSize = IntSize(raster.screenW, raster.screenH)
    if (zoom == 1f) {
        drawImage(
            image = raster.image,
            dstOffset = offset,
            dstSize = dstSize,
            filterQuality = FilterQuality.Low
        )
    } else {
        // 折射：以卡片中心为轴放大，越靠边偏移越大
        scale(zoom, zoom, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawImage(
                image = raster.image,
                dstOffset = offset,
                dstSize = dstSize,
                filterQuality = FilterQuality.Low
            )
        }
    }
}

/** 外描边 / 内反射 / 色散 / 顶部镜面亮线 */
private fun DrawScope.drawGlassEdges(tokens: UiTokens, cornerPx: Float, quality: GlassQuality) {
    val w = size.width
    val h = size.height
    if (w <= 4f || h <= 4f) return
    val hair = 1.dp.toPx()
    val radius = CornerRadius(max(0f, cornerPx - hair / 2f))

    // v2.5.3：外描边从「上到下线性」改成三段。线性的话中段还有一半亮度，
    // 整圈看着一样亮，就成了塑料片；苹果那圈光只堆在上沿，到卡片腰部已经快没了。
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to tokens.cardBorderTop,
            0.42f to tokens.cardBorderTop.copy(alpha = tokens.cardBorderTop.alpha * 0.26f),
            1f to tokens.cardBorderBottom
        ),
        topLeft = Offset(hair / 2f, hair / 2f),
        size = Size(w - hair, h - hair),
        cornerRadius = radius,
        style = Stroke(hair)
    )

    if (quality != GlassQuality.LITE) {
        val inset = hair * 2.2f
        // v2.5.3：这圈「内反射」原来是「深色给白、浅色给黑」——深色下那圈白 8.5%
        // 正好压在刚提亮的上沿里面，叠出一道发灰的箍，是雾感的第三个来源。
        // 真实的玻璃内缘是**暗**的（厚度把光挡在里面），两套皮肤统一给黑。
        drawRoundRect(
            color = Color.Black.copy(alpha = if (tokens.dark) 0.06f else 0.045f),
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = CornerRadius(max(0f, cornerPx - inset)),
            style = Stroke(hair * 0.8f)
        )
    }

    // 苹果的色散：沿四边走一圈极淡的彩虹（sweep 渐变，角度决定颜色）。
    // v2.5.3：30% 降到 9%，并且只在「极致」档出现 —— 原来每一档都画，1dp 的边
    // 上一圈 30% 的彩边在深色底上像贴了层糖纸，是「廉价感」最重的一处。
    // 苹果在静态玻璃上几乎看不到彩色，只有边缘极窄的一条冷色偏移。
    if (quality == GlassQuality.ULTRA) {
        drawRoundRect(
            brush = Brush.sweepGradient(
                listOf(
                    Color(0x00FFFFFF),
                    Color(0x1AFF7AD9),
                    Color(0x00FFFFFF),
                    Color(0x1A56E1FF),
                    Color(0x00FFFFFF),
                    Color(0x1AFFE066),
                    Color(0x00FFFFFF)
                ),
                center = Offset(w / 2f, h / 2f)
            ),
            topLeft = Offset(hair / 2f, hair / 2f),
            size = Size(w - hair, h - hair),
            cornerRadius = radius,
            style = Stroke(hair * 1.2f)
        )
    }

    // 顶部镜面亮线：只占中间 76%，两头淡出 —— 玻璃最亮的地方永远在上沿。
    // v2.5.3：线宽 1.5dp → 1.1dp。苹果那条高光是一条**细**线，粗了就变成「卡片描了个白边」。
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                if (tokens.dark) {
                    Color.White.copy(alpha = if (tokens.style == UiStyle.LIQUID) 0.92f else 0.62f)
                } else {
                    // 浅色卡片本身就是白的，亮线改成极淡的冷蓝，靠色差而不是亮度撑轮廓
                    Color(0xFF9FC4FF).copy(alpha = 0.45f)
                },
                Color.Transparent
            )
        ),
        start = Offset(w * 0.12f, 0.9.dp.toPx()),
        end = Offset(w * 0.88f, 0.9.dp.toPx()),
        strokeWidth = 1.1.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * 「玻璃质感」三档选择器。只有选中鸿蒙 / 苹果风格时才有意义，
 * 放在「我的 → 界面风格」下面。
 */
@Composable
fun GlassQualityPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = LocalGlassQuality.current
    // v2.0.10：档位下面的两行小字（质感说明 / 低版本提示）都删掉，只留三档按钮
    Column(modifier.fillMaxWidth()) {
        GlassSegmented(
            options = GlassQuality.entries.map { it.label },
            selected = current.ordinal.coerceIn(0, GlassQuality.entries.lastIndex),
            onSelect = { index ->
                GlassQuality.entries.getOrNull(index)?.let { GuideStyleStore.selectQuality(context, it) }
            }
        )
    }
}
