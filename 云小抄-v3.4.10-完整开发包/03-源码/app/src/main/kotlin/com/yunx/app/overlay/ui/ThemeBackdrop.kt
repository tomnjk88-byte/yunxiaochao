/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.12.0 主题设置支持「自定义背景 / 系统壁纸」后，
 * 解码与取色这两件事两个界面都要用，从 GlassTheme 里抽出来单独放。
 */

package com.yunx.app.overlay.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import java.io.File

/**
 * 把 [key] 指向的背景读成位图。
 *
 * - `"system"`：系统桌面壁纸，画到一张 ARGB 位图上（部分 ROM 会抛 SecurityException，
 *   外面统一 runCatching 兜底）；
 * - 其他：私有目录里的图片文件，按目标宽度做 2 的幂次降采样 —— 相机原图动辄
 *   4000×3000，直接铺到 1080p 窗口上会把内存顶爆。
 */
internal fun loadBackdropBitmap(context: Context, key: String): Bitmap? {
    val metrics = context.resources.displayMetrics
    val targetW = metrics.widthPixels.coerceIn(360, 1440)
    return runCatching {
        if (key == "system") {
            val drawable = WallpaperManager.getInstance(context).drawable ?: return null
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: targetW,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: targetW,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        } else {
            val file = File(key)
            if (!file.exists() || file.length() <= 0L) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(key, opts)
            var sample = 1
            while (opts.outWidth / sample > targetW * 2) sample *= 2
            BitmapFactory.decodeFile(key, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
}

/**
 * 从背景里挑一个「能当强调色」的颜色。
 *
 * 不是简单求平均 —— 壁纸大面积是天空 / 墙壁这种低饱和色，平均下来会得到一坨灰。
 * 这里按 30° 色相分成 12 个桶，用「饱和度 × 明度」当权重找主色桶，再取桶内平均；
 * 最后把饱和度和明度拉到可用区间，保证按钮上的白字看得清。
 */
internal fun dominantAccentColor(bitmap: Bitmap): Int {
    val size = 36
    val small = Bitmap.createScaledBitmap(bitmap, size, size, false)
    val hsv = FloatArray(3)
    val weight = FloatArray(12)
    val sumR = IntArray(12)
    val sumG = IntArray(12)
    val sumB = IntArray(12)
    val count = IntArray(12)

    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            if ((p ushr 24) and 0xFF < 40) continue
            AndroidColor.colorToHSV(p, hsv)
            // 灰、黑、过白都不适合当强调色
            if (hsv[1] < 0.18f || hsv[2] < 0.16f) continue
            val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            weight[bucket] += hsv[1] * hsv[2]
            sumR[bucket] += (p shr 16) and 0xFF
            sumG[bucket] += (p shr 8) and 0xFF
            sumB[bucket] += p and 0xFF
            count[bucket]++
        }
    }
    if (small !== bitmap) small.recycle()

    val best = weight.indices.maxByOrNull { weight[it] } ?: 0
    if (count[best] == 0) return 0
    val r = sumR[best] / count[best]
    val g = sumG[best] / count[best]
    val b = sumB[best] / count[best]

    AndroidColor.colorToHSV(AndroidColor.rgb(r, g, b), hsv)
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.55f, 0.88f)
    return AndroidColor.HSVToColor(hsv)
}
