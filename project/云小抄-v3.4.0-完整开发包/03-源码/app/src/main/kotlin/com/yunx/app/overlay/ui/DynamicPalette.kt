/*
 * v3.4.1：从背景图动态提取主色调的状态。
 *
 * 作用链路：用户换背景图 → CustomBackdrop LaunchedEffect 加载 Bitmap →
 * 这里用 androidx.palette.Palette 提取 Vibrant/Muted/VibrantDark 等 →
 * 暴露为 StateFlow → UiStyle.applyPalette 和 ThemeStudioScreen 的强调色选择器
 * 直接 collect。
 *
 * 为什么单独抽成一个 object 而不是塞回 CustomBackdrop：
 *   · Palette 提取是一次性操作（不涉及 Compose state），但消费方有两个
 *     （卡片填充色 + 强调色自动跟随），独立 object 方便多订阅。
 *   · Palette 在后台线程跑，不阻塞 Compose 主线程。
 *
 * minSdk 24: androidx.palette:palette:1.0.0 支持 API 24+，但它底层仍然用的
 * android.graphics.Bitmap + Palette，所以不需要额外兼容层。
 */

package com.yunx.app.overlay.ui

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import com.yunx.app.overlay.data.GuideStyleStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** 从 Palette 提取到的一组主色；全部 null = 还没提取 / 背景被设成纯壁纸 */
data class ExtractedColors(
    val vibrant: Int? = null,
    val muted: Int? = null,
    val vibrantDark: Int? = null,
    val vibrantLight: Int? = null,
    val dominant: Int? = null
) {
    /** 有没有有效颜色 —— 至少要有一颗才能自动跟随 */
    val isEmpty: Boolean
        get() = vibrant == null && muted == null && vibrantDark == null &&
            vibrantLight == null && dominant == null

    /** 挑一颗最适合当「强调色」的：优先 vibrant，其次 muted，都没就 dominant */
    val accentBest: Int?
        get() = vibrant ?: vibrantLight ?: muted ?: dominant ?: vibrantDark
}

object DynamicPalette {

    private val _colors = MutableStateFlow(ExtractedColors())
    val colors: StateFlow<ExtractedColors> = _colors.asStateFlow()

    private val _lastBackgroundKey = MutableStateFlow<String?>(null)
    val lastBackgroundKey: StateFlow<String?> = _lastBackgroundKey.asStateFlow()

    /**
     * 触发一次提取。传 null / "" = 清空（用户把背景设成系统壁纸或纯色）。
     *
     * @param bitmap  背景图的 Bitmap；内部会缩到 <= 256px 再喂给 Palette ——
     *                Palette 分辨率越高越慢，1080p 图提取一次要 40ms+，
     *                缩到 256 以内基本 5ms 就能出结果，肉眼看不出区别。
     * @param backgroundKey  用来去重：同一张图连续调用时直接返回上一次结果，
     *                       不重复跑 Palette（用户频繁点同一张背景图的场景）。
     */
    fun extract(bitmap: Bitmap?, backgroundKey: String?) {
        if (bitmap == null || backgroundKey.isNullOrBlank()) {
            _lastBackgroundKey.value = backgroundKey
            _colors.value = ExtractedColors()
            return
        }
        // 去重：同 key 不重复提取
        if (_lastBackgroundKey.value == backgroundKey) return

        _lastBackgroundKey.value = backgroundKey
        Thread({
            runCatching {
                val sample = downSample(bitmap, maxEdge = 256)
                val palette = Palette.from(sample).generate()
                _colors.value = ExtractedColors(
                    vibrant = palette.getVibrantColor(null),
                    muted = palette.getMutedColor(null),
                    vibrantDark = palette.getDarkVibrantColor(null),
                    vibrantLight = palette.getLightVibrantColor(null),
                    dominant = palette.getDominantColor(null)
                )
                // 采样图是我们自己 new 的，用完就丢，别等 GC
                if (sample !== bitmap && !sample.isRecycled) sample.recycle()
            }
        }, "yunx-palette-extract").start()
    }

    /**
     * 把一张图按长边缩到 maxEdge 以内。返回的可能是原图（已经够小），
     * 所以调用方要判断 sample !== bitmap 才 recycle。
     */
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

    /**
     * 把 Palette 提取到的颜色自动注入 ThemeTuning.customAccent，
     * 让强调色自动跟随背景图变化。用户切主题包 / 换背景后，
     * 卡片填充色、按钮强调色、进度条颜色会一起变。
     *
     * 只在 accentKey == "auto" 或 "palette" 时生效（尊重用户手动选的强调色）。
     */
    fun autoInjectAccentIfNeeded() {
        val extracted = _colors.value
        if (extracted.isEmpty) return
        val accent = extracted.accentBest ?: return
        val tuning = GuideStyleStore.tuning.value
        // 用户手动选了 accentKey 不是 auto/palette → 不动
        if (tuning.accentKey != "auto" && tuning.accentKey != "palette") return
        // 差值 > 0x202020 才写，避免抖动时反复覆盖
        if (tuning.customAccent != 0 && colorSimilar(tuning.customAccent, accent)) return
        GuideStyleStore.setTuning(
            context = null, // 不需要 context
            tuning = tuning.copy(customAccent = accent)
        )
    }

    private fun colorSimilar(a: Int, b: Int): Boolean {
        val ra = (a shr 16) and 0xFF; val ga = (a shr 8) and 0xFF; val ba = a and 0xFF
        val rb = (b shr 16) and 0xFF; val gb = (b shr 8) and 0xFF; val bb = b and 0xFF
        val diff = abs(ra - rb) + abs(ga - gb) + abs(ba - bb)
        return diff < 48
    }
}

/**
 * Palette 提取到的颜色 (ARGB Int) → Compose Color，并根据亮/暗模式推一下明度。
 * 浅色卡片上的 tint 需要更深一点才看得见（不然叠在白卡片上等于没叠），
 * 深色卡片上的 tint 需要更浅一点（不然叠在黑卡片上就看不见了）。
 *
 * 放在这里而不是 DynamicPalette object 里，是因为它是「纯函数」、不依赖状态，
 * FrostedGlass.kt 和 ThemeStudioScreen 的强调色选择器都能用（同一个 package）。
 */
fun lift(colorInt: Int, dark: Boolean): androidx.compose.ui.graphics.Color {
    val base = androidx.compose.ui.graphics.Color(colorInt)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(colorInt, hsv)
    if (dark) {
        // 深色：往白方向推一点（明度 +18%），叠在黑卡片上能看见
        hsv[2] = (hsv[2] + 0.18f).coerceAtMost(1f)
    } else {
        // 浅色：往黑方向推一点（明度 -15%），叠在白卡片上有对比
        hsv[2] = (hsv[2] - 0.15f).coerceAtLeast(0.12f)
    }
    return androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = base.alpha)
}

