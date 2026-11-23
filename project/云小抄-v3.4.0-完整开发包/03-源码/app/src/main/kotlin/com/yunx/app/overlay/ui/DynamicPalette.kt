/*
 * v3.4.1：从背景图动态提取主色调。
 *
 * 链路：背景图加载完 → DynamicPalette.extract(bitmap, key) →
 * androidx.palette.Palette 提取主色 → StateFlow → FrostedSurface 给卡片叠一层弱染色，
 * 让半透明卡片和背景融合，而不是生硬的纯白/纯黑。
 */

package com.yunx.app.overlay.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 从 Palette 提取到的一组主色；全部 null = 还没提取 / 背景被设成纯壁纸 */
data class ExtractedColors(
    val vibrant: Int? = null,
    val muted: Int? = null,
    val vibrantDark: Int? = null,
    val vibrantLight: Int? = null,
    val dominant: Int? = null
) {
    val isEmpty: Boolean
        get() = vibrant == null && muted == null && vibrantDark == null &&
            vibrantLight == null && dominant == null
}

object DynamicPalette {

    private val _colors = MutableStateFlow(ExtractedColors())
    val colors: StateFlow<ExtractedColors> = _colors.asStateFlow()

    @Volatile
    private var lastKey: String? = null

    @Volatile
    private var token = 0

    /**
     * 触发一次提取。bitmap 为 null 或 key 为空 = 清空。
     *
     * 采样图在调用线程同步生成（几张 ms），Palette 分析放在后台线程，
     * 避免源 Bitmap 在异步期间被回收导致崩溃。
     */
    fun extract(bitmap: Bitmap?, backgroundKey: String?) {
        if (bitmap == null || backgroundKey.isNullOrBlank() || bitmap.isRecycled) {
            token++
            lastKey = backgroundKey
            _colors.value = ExtractedColors()
            return
        }
        if (lastKey == backgroundKey) return
        lastKey = backgroundKey
        val mine = ++token

        val sample = runCatching { downSample(bitmap, maxEdge = 256) }.getOrNull()
            ?: return

        Thread({
            runCatching {
                val palette = Palette.from(sample).generate()
                if (mine != token) return@runCatching
                _colors.value = ExtractedColors(
                    vibrant = palette.getVibrantColor(0).takeIf { it != 0 },
                    muted = palette.getMutedColor(0).takeIf { it != 0 },
                    vibrantDark = palette.getDarkVibrantColor(0).takeIf { it != 0 },
                    vibrantLight = palette.getLightVibrantColor(0).takeIf { it != 0 },
                    dominant = palette.getDominantColor(0).takeIf { it != 0 }
                )
            }
            if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        }, "yunx-palette-extract").start()
    }

    /** 按长边缩到 maxEdge 以内；返回的可能是原图本身。 */
    private fun downSample(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val edge = maxOf(w, h)
        if (edge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / edge
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}

/**
 * Palette 提取到的 ARGB Int → Compose Color，并按亮/暗模式微调明度：
 * 浅色卡片上要深一点才看得见，深色卡片上要浅一点才看得见。
 */
fun lift(colorInt: Int, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(colorInt, hsv)
    if (dark) {
        hsv[2] = (hsv[2] + 0.18f).coerceAtMost(1f)
    } else {
        hsv[2] = (hsv[2] - 0.15f).coerceAtLeast(0.12f)
    }
    return Color(android.graphics.Color.HSVToColor(hsv))
}
