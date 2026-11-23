/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.4.0 新增。当前主题的「图」—— 卡面和底栏图标。
 *
 * 为什么参数和图画在两个地方：
 *   参数（颜色、圆角、透明度）是**值**，跟 ThemeTuning 一样走 StateFlow，
 *   换一套只是重组一次，几十毫秒；图是**位图**，解码要几十到上百毫秒，
 *   而且占内存。混在一个流里，拖一下卡片透明度的滑杆都会重新解码一遍图。
 *   所以这里只管图，且只在「换主题」时更新一次。
 *
 * 解码放子线程：卡片底图 1269×542、图标 256×144，主线程解会掉一帧。
 * 解完再切回去，加载期间界面保持上一套的图（或者干脆没有图），不会闪白。
 */

package com.yunx.app.overlay.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Modifier
import com.yunx.app.overlay.data.ThemeAssets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 当前主题的位图资源。
 *
 * @param card 卡片底图；null = 卡片还是画渐变
 * @param dockIcons 底栏图标，key 与 [DockEntry.iconKey] 对应
 */
data class ThemeSkinState(
    val card: ImageBitmap? = null,
    val dockIcons: Map<String, ImageBitmap> = emptyMap()
) {
    val isEmpty: Boolean get() = card == null && dockIcons.isEmpty()
}

object ThemeSkin {

    private val _state = MutableStateFlow(ThemeSkinState())
    val state: StateFlow<ThemeSkinState> = _state.asStateFlow()

    /**
     * 换一套图。传 null / 空资源 = 清空（纯参数包、或者用户把带图的包删了）。
     *
     * 每次换都递增 [token]：连着点两套包时，先解完的那套可能后回来，
     * 编号对不上就丢掉，不会出现「点了 A 显示 B」。
     *
     * v3.4.1 FIX: 替换 state 前先 dispose 旧的 ImageBitmap —— Compose 的 ImageBitmap
     * 底层持有 native Bitmap，Java GC 不会立刻回收 native 内存，频繁切主题会在
     * Java Heap 未满时先把 native 内存顶爆（典型 OOM 路径）。
     */
    @Volatile
    private var token = 0

    /** 安全回收一个 ImageBitmap 底层的 Android Bitmap */
    private fun ImageBitmap?.disposeBitmap() {
        if (this == null) return
        runCatching {
            val bmp = this.asAndroidBitmap()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** 回收一整个 ThemeSkinState 里所有 ImageBitmap 的 native 内存 */
    private fun ThemeSkinState?.disposeAll() {
        if (this == null) return
        card.disposeBitmap()
        dockIcons.values.forEach { it.disposeBitmap() }
    }

    fun load(assets: ThemeAssets?) {
        val mine = ++token
        // v3.4.1 FIX: 先把旧 state 的 native 内存清掉，再挂新值或清空
        val old = _state.value
        if (assets == null || assets.isEmpty) {
            old.disposeAll()
            _state.value = ThemeSkinState()
            return
        }
        val cardPath = assets.cardBackground
        val iconPaths = assets.dockIcons
        Thread({
            val card = cardPath?.let { decode(it, 1800) }
            val icons = LinkedHashMap<String, ImageBitmap>()
            iconPaths.forEach { (k, v) -> decode(v, 320)?.let { icons[k] = it } }
            if (mine == token) {
                // token 对得上 = 这张图就是用户要的；先 dispose 旧图再挂新图
                _state.value.disposeAll()
                _state.value = ThemeSkinState(card = card, dockIcons = icons)
            } else {
                // token 对不上 = 这张图是过期的（用户在解码期间已经点了下一个包），直接清
                card?.disposeBitmap()
                icons.values.forEach { it.disposeBitmap() }
            }
        }, "yunx-theme-skin").start()
    }

    fun clear() {
        token++
        _state.value.disposeAll()
        _state.value = ThemeSkinState()
    }

    /**
     * v3.4.2：主题包自带图标 + 用户手动指定的底栏图标，合并后加载。
     *
     * 用户指定优先 —— 主题包是「整套换」，但用户只改了其中一个格子时，
     * 不该被主题包覆盖回去。没有主题包、只设了图标也要能生效。
     */
    fun loadMerged(context: android.content.Context, assets: ThemeAssets?) {
        val user = parseTextMap(com.yunx.app.overlay.data.OverlayPrefs(context).themeDockIcons)
        if (user.isEmpty()) {
            load(assets)
            return
        }
        load(
            ThemeAssets(
                background = assets?.background,
                cardBackground = assets?.cardBackground,
                dockIcons = assets?.dockIcons.orEmpty() + user,
                texts = assets?.texts.orEmpty()
            )
        )
    }

    /**
     * 解一张本地图，长边压到 [maxEdge] 以内。
     *
     * 卡片底图会铺满整张卡（宽 350dp 上下，3x 屏约 1050px），1800 已经够；
     * 底栏图标在 Dock 里只有 22dp，320 绰绰有余。压得越狠，切页越不容易抖。
     */
    private fun decode(path: String, maxEdge: Int): ImageBitmap? = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sample = 1
        while (maxOf(probe.outWidth, probe.outHeight) / sample > maxEdge * 2) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opt)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 把卡面图铺在卡片底下（cover 裁切 + 居中），再压一层 [scrim]。
 *
 * 为什么用 drawWithCache 而不是 `background(Brush.bitmap(...))`：
 *   · `Brush.bitmap` 是平铺语义，要铺满一张长宽比不同的卡片得自己算 matrix；
 *   · 这里要的只是「铺满、居中、超出的裁掉」，直接用 drawImage 的
 *     src/dst 两个矩形最直白，也不依赖实验 API。
 * 缓存在 drawWithCache 里算一次，滚动时不会每帧重算缩放比。
 */
internal fun Modifier.themeCardArt(image: ImageBitmap?, scrim: Color): Modifier {
    if (image == null) return this
    return this.drawWithCache {
        val cw = size.width
        val ch = size.height
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val ok = cw > 0f && ch > 0f && iw > 0f && ih > 0f
        val k = if (ok) maxOf(cw / iw, ch / ih) else 1f
        val dw = iw * k
        val dh = ih * k
        val dx = (cw - dw) / 2f
        val dy = (ch - dh) / 2f
        onDrawBehind {
            if (!ok) return@onDrawBehind
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                filterQuality = FilterQuality.Medium
            )
            if (scrim.alpha > 0f) drawRect(scrim)
        }
    }
}
