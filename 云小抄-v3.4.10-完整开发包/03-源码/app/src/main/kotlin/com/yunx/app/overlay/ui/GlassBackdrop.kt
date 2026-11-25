/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.13「真玻璃」图层的地基。
 *
 * 以前几套风格的卡片是「半透明 + 渐变描边」—— 靠颜色装玻璃，背后其实什么都没透过来。
 * 要做到鸿蒙 / iOS 26 那种「离谱」的玻璃，必须让卡片真的采样它背后的那几团光斑，
 * 再模糊、再折射。所以这里把背景从「一堆 Box 叠背景色」改成一份**可重复绘制的场景描述**
 * （[BackdropSpec]）：
 *
 *   - 全屏背景：[SceneBackdrop] 画一次；
 *   - 每一张玻璃卡片：拿同一份 spec、按自己在屏幕里的位置再画一次（[paintBackdrop]），
 *     然后交给 [FrostedSurface] 去模糊 / 折射。
 *
 * 两边用同一套 dp 坐标，所以卡片里的「透出来的光斑」和背景永远对得上 —— 这是「映射感」
 * 成立的前提。坐标全部用绝对 dp（不用比例），是为了让卡片在任意位置重绘时不需要知道
 * 根布局尺寸，滚动时也不会因为尺寸变化而跳一下。
 *
 * v1.9.14：每套风格各出「深 / 浅」两张壁纸（[backdropFor] 带 dark 参数）。浅色不是把深色
 * 简单调亮 —— 底色换成奶白 / 淡蓝，光斑换成低透明度的粉彩，暗角改成偏冷的灰蓝，
 * 否则玻璃卡里的像会发灰发脏，跟白卡片拼在一起又是一道缝。
 */

package com.yunx.app.overlay.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** 一团光斑：中心（屏幕 dp 坐标）+ 半径 + 颜色 */
@Immutable
internal data class BlobSpec(
    val cx: Dp,
    val cy: Dp,
    val radius: Dp,
    val color: Color
)

/**
 * 一张壁纸的完整描述。可以在任意 [DrawScope] 里按「屏幕坐标」重绘，
 * 所以玻璃卡片能采到和背景一模一样的像素。
 */
@Immutable
internal data class BackdropSpec(
    /** 垂直底色渐变（2~3 段） */
    val base: List<Color>,
    val blobs: List<BlobSpec>,
    /** 顶部大气光的高度 */
    val veilHeight: Dp = 320.dp,
    val veil: Color = Color.Transparent,
    /** 斜向折射光带的强度（0 = 不画） */
    val sheen: Float = 0f,
    /** 光带颜色：深色底用白，浅色底用极淡的冷白，否则整块会糊成一片 */
    val sheenColor: Color = Color.White,
    /** 暗角强度：压暗四周，玻璃的亮边才立得住 */
    val vignette: Float = 0.16f,
    /**
     * 暗角颜色。浅色模式不能压黑 —— 压黑会在白底四周留一圈脏灰，
     * 换成偏冷的灰蓝只降一点亮度，看起来像纸的边上有一点阴影。
     */
    val vignetteColor: Color = Color.Black
)

// ======================================================================
// 两套「离谱玻璃」的壁纸
// ======================================================================

/**
 * 风格四（深）：偏青蓝的大面积光晕，整体亮、柔、饱和度低一点。
 * 光斑刻意做得很大（半径 300~420dp），这样玻璃只截到其中一小块时，
 * 卡片里的明暗过渡是连续的 —— 小块渐变看起来才像玻璃，而不是一块色纸。
 */
private val HarmonyDarkBackdrop = BackdropSpec(
    base = listOf(Color(0xFF060C1A), Color(0xFF0E1E3C), Color(0xFF040812)),
    blobs = listOf(
        BlobSpec(30.dp, 10.dp, 420.dp, Color(0xBF3E6AFF)),
        BlobSpec(420.dp, 300.dp, 450.dp, Color(0xA610E0D6)),
        BlobSpec(40.dp, 690.dp, 440.dp, Color(0xB88B5CF6)),
        BlobSpec(430.dp, 1070.dp, 400.dp, Color(0x992D84FF))
    ),
    veilHeight = 340.dp,
    veil = Color(0x1F7BE0FF),
    sheen = 0.10f,
    vignette = 0.14f
)

/** 风格四（浅）：雾蓝底 + 粉彩光晕。光斑比深色版更淡，否则白卡片上会透出「脏色」 */
private val HarmonyLightBackdrop = BackdropSpec(
    base = listOf(Color(0xFFEAF3FF), Color(0xFFD9E9FF), Color(0xFFF3F8FF)),
    blobs = listOf(
        BlobSpec(30.dp, 10.dp, 420.dp, Color(0x8A7FA6FF)),
        BlobSpec(420.dp, 300.dp, 450.dp, Color(0x6E6FE6DD)),
        BlobSpec(40.dp, 690.dp, 440.dp, Color(0x7AAE9BFF)),
        BlobSpec(430.dp, 1070.dp, 400.dp, Color(0x6687BEFF))
    ),
    veilHeight = 340.dp,
    veil = Color(0x335FC8FF),
    sheen = 0.22f,
    sheenColor = Color(0xCCFFFFFF),
    vignette = 0.05f,
    vignetteColor = Color(0xFF7C9BC4)
)

/**
 * 风格五（深）：近黑底 + 高饱和品红 / 青 / 橙。光斑边界更硬（少一层过渡），
 * 这样边缘折射环放大之后能看出「一道被掰弯的色带」，映射感最强。
 */
private val AppleDarkBackdrop = BackdropSpec(
    base = listOf(Color(0xFF04020A), Color(0xFF0D081C), Color(0xFF030208)),
    blobs = listOf(
        BlobSpec(20.dp, 20.dp, 400.dp, Color(0xF2FF28CD)),
        BlobSpec(440.dp, 340.dp, 420.dp, Color(0xD924CDFF)),
        BlobSpec(130.dp, 740.dp, 450.dp, Color(0xF2804AFF)),
        BlobSpec(430.dp, 1100.dp, 380.dp, Color(0xB3FF7634))
    ),
    veilHeight = 300.dp,
    veil = Color(0x1FFF6BD6),
    sheen = 0.08f,
    vignette = 0.22f
)

/**
 * 风格五（浅）：奶白底 + 高饱和粉彩。浅色下折射要靠「色差」而不是亮度差，
 * 所以品红 / 青色保留得很足（alpha 0.55~0.65），只是把底色换亮。
 */
private val AppleLightBackdrop = BackdropSpec(
    base = listOf(Color(0xFFFFF6FB), Color(0xFFF1EBFF), Color(0xFFFFF4F9)),
    blobs = listOf(
        BlobSpec(20.dp, 20.dp, 400.dp, Color(0xA6FF6FD8)),
        BlobSpec(440.dp, 340.dp, 420.dp, Color(0x8A5FD8FF)),
        BlobSpec(130.dp, 740.dp, 450.dp, Color(0x807A63FF)),
        BlobSpec(430.dp, 1100.dp, 380.dp, Color(0x66FFA36B))
    ),
    veilHeight = 300.dp,
    veil = Color(0x33FF8ADC),
    sheen = 0.26f,
    sheenColor = Color(0xCCFFFFFF),
    vignette = 0.04f,
    vignetteColor = Color(0xFF9B86B0)
)

/**
 * v1.9.18：液态玻璃改成「真透明」—— 卡片不再重绘一层壁纸，而是直接让**真实背景**
 * 透过来（LiquidBackdrop 的光斑、下面的列表、图片都在原地），所以这里返回 null。
 * 保留函数是为了不动 FrostedSurface 的调用点：spec == null 时它只画压色 + 亮边。
 */
internal fun backdropFor(style: UiStyle, dark: Boolean): BackdropSpec? = null

// ======================================================================
// 绘制
// ======================================================================

/**
 * 按**屏幕坐标**重绘整张壁纸。
 *
 * @param origin 当前画布左上角在屏幕里的位置（卡的 `positionInRoot`）。
 * @param zoom 折射放大倍数：1 = 原样（普通透视），>1 = 玻璃把背后的东西放大了一点，
 *             这就是 iOS 液态玻璃边缘那种「透镜」感。
 */
internal fun DrawScope.paintBackdrop(
    spec: BackdropSpec,
    origin: Offset,
    zoom: Float = 1f
) {
    translate(-origin.x, -origin.y) {
        // 放大以「卡片中心」为轴：玻璃中心不变形，越靠边偏移越大 —— 和真玻璃一样
        val pivot = origin + Offset(size.width / 2f, size.height / 2f)
        scale(scaleX = zoom, scaleY = zoom, pivot = pivot) {
            spec.blobs.forEach { blob ->
                val radius = blob.radius.toPx()
                val center = Offset(blob.cx.toPx(), blob.cy.toPx())
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to blob.color,
                        0.58f to blob.color.copy(alpha = blob.color.alpha * 0.52f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
            if (spec.sheen > 0f) {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            spec.sheenColor.copy(alpha = spec.sheen),
                            spec.sheenColor.copy(alpha = spec.sheen * 0.30f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(760.dp.toPx(), 1180.dp.toPx())
                    ),
                    topLeft = Offset(-1400.dp.toPx(), -1400.dp.toPx()),
                    size = Size(3400.dp.toPx(), 3800.dp.toPx())
                )
            }
        }
    }
}

/**
 * 全屏壁纸。鸿蒙 / 苹果两套风格共用，只是 spec 不同。
 *
 * 刻意**不做动画**：卡片要按屏幕坐标采样同一份像素，背景一动、卡片里的成像就得跟着重算，
 * 滚动时等于每帧额外算一遍动画 —— 中低端机扛不住。静态背景靠光斑本身的层次撑观感。
 */
/**
 * 壁纸的光栅化缓存。
 *
 * 为什么要烘焙成位图：玻璃卡片每次滚动都要「把背后的壁纸重画一遍 + 模糊」。如果每张卡
 * 都现画 4 个径向渐变，10 张卡就是一帧 40 个 shader，中低端机直接掉帧。
 * 烘焙成一张半分辨率位图之后，每张卡只剩「一次 drawImage + 一次 blur」，成本降一个量级。
 *
 * 背景是静态的（不做光斑游动），所以这张图可以一直复用；换风格 / 转屏才重建。
 */
internal class BackdropRaster(
    val spec: BackdropSpec,
    val screenW: Int,
    val screenH: Int,
    val image: ImageBitmap
)

internal object BackdropCache {

    private var cached: BackdropRaster? = null

    fun peek(): BackdropRaster? = cached

    fun obtain(spec: BackdropSpec, screenW: Int, screenH: Int, density: Float): BackdropRaster? {
        val hit = cached
        if (hit != null && hit.spec === spec && hit.screenW == screenW && hit.screenH == screenH) {
            return hit
        }
        if (screenW <= 0 || screenH <= 0) return null
        val built = runCatching { build(spec, screenW, screenH, density) }.getOrNull() ?: return null
        cached = built
        return built
    }

    /** 0.5 倍分辨率就够：反正要糊 14~24dp，再清楚也看不出来 */
    private const val SCALE = 0.5f

    private fun build(
        spec: BackdropSpec,
        screenW: Int,
        screenH: Int,
        density: Float
    ): BackdropRaster {
        val w = max(1, (screenW * SCALE).toInt())
        val h = max(1, (screenH * SCALE).toInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(w.toFloat() / screenW, h.toFloat() / screenH)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 底色
        val baseColors = spec.base.map { it.toArgb() }.toIntArray()
        val baseStops = FloatArray(baseColors.size) { i ->
            if (baseColors.size == 1) 0f else i / (baseColors.size - 1f)
        }
        paint.shader = LinearGradient(
            0f, 0f, 0f, screenH.toFloat(),
            baseColors, baseStops, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)

        // 光斑
        spec.blobs.forEach { blob ->
            val radius = blob.radius.value * density
            val cx = blob.cx.value * density
            val cy = blob.cy.value * density
            if (radius <= 0f) return@forEach
            val mid = blob.color.copy(alpha = blob.color.alpha * 0.52f)
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(blob.color.toArgb(), mid.toArgb(), 0x00000000),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }

        // 斜向光带
        if (spec.sheen > 0f) {
            val sheen = intArrayOf(
                Color.Transparent.toArgb(),
                spec.sheenColor.copy(alpha = spec.sheen).toArgb(),
                spec.sheenColor.copy(alpha = spec.sheen * 0.30f).toArgb(),
                Color.Transparent.toArgb()
            )
            paint.shader = LinearGradient(
                0f, 0f, 760f * density, 1180f * density,
                sheen, floatArrayOf(0f, 0.26f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                RectF(
                    -1400f * density,
                    -1400f * density,
                    screenW + 1400f * density,
                    screenH + 1400f * density
                ),
                paint
            )
        }

        return BackdropRaster(spec, screenW, screenH, bitmap.asImageBitmap())
    }
}

/**
 * 全屏壁纸。鸿蒙 / 苹果两套风格共用，只是 spec 不同。
 *
 * 刻意**不做动画**：卡片要按屏幕坐标采样同一份像素，背景一动、卡片里的成像就得跟着重算，
 * 滚动时等于每帧额外算一遍动画 —— 中低端机扛不住。静态背景靠光斑本身的层次撑观感。
 */
@Composable
internal fun SceneBackdrop(spec: BackdropSpec, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(spec.base))
    ) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val raster = remember(spec, wPx, hPx, density) {
            BackdropCache.obtain(spec, wPx, hPx, density)
        }
        if (raster != null) {
            Image(
                bitmap = raster.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } else {
            Canvas(Modifier.fillMaxSize()) { paintBackdrop(spec, Offset.Zero) }
        }

        // 顶部大气光
        if (spec.veil.alpha > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(spec.veilHeight)
                    .background(
                        Brush.verticalGradient(listOf(spec.veil, Color.Transparent))
                    )
            )
        }

        // 暗角：四周压暗，玻璃卡的亮边才有对比
        if (spec.vignette > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                spec.vignetteColor.copy(alpha = spec.vignette),
                                Color.Transparent,
                                Color.Transparent,
                                spec.vignetteColor.copy(alpha = spec.vignette * 0.8f)
                            )
                        )
                    )
            )
        }
    }
}
