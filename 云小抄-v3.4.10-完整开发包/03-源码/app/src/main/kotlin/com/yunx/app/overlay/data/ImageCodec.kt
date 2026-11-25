/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。把「下载后压缩」从 GuideDownloader 里抽出来，
 * 让「分区本地化」和「分享链接导入」两条链路共用同一套压缩参数与透明底处理。
 */

package com.yunx.app.overlay.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 图片压缩（有损，但只作用于本地副本）。
 *
 * 规则和 v1.9.8 完全一致：
 *  1. 先 `inSampleSize` 降采样，避免把 4K 原图整张读进内存；
 *  2. 长边缩到 [maxEdge]，JPEG 重编码；
 *  3. 压完反而更大就返回 null（调用方存原图，绝不让画质和体积双输）；
 *  4. PNG 透明区直接压 JPEG 会变黑，先垫一层白底。
 */
object ImageCodec {

    fun compress(bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray? = runCatching {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        val longest = max(decoded.width, decoded.height)
        val scale = min(1f, maxEdge.toFloat() / longest)
        val scaled = if (scale < 0.995f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }

        // 有透明通道的图（PNG 截图）直接压 JPEG 会变黑底，先垫白
        val flat = if (scaled.hasAlpha()) flattenOnWhite(scaled) else scaled
        val out = ByteArrayOutputStream(bytes.size / 2 + 8 * 1024)
        flat.compress(Bitmap.CompressFormat.JPEG, quality, out)

        if (flat !== scaled) flat.recycle()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        val result = out.toByteArray()
        if (result.size >= bytes.size) null else result
    }.getOrNull()

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
