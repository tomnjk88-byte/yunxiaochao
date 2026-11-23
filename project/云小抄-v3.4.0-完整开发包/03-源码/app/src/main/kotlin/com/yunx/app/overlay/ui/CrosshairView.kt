/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.2.0 准星）。
 *
 * 准星本体是一个纯粹的自绘 View：
 *   1. 把用户自由导入的图片按 View 的最终尺寸铺满（窗口宽高已按图片比例算好）；
 *   2. 在归一化锚点 (anchorX, anchorY) 处画十字线 + 中心点 —— 这一点就是「瞄准点」；
 *   3. 悬浮层和应用内预览共用同一个 View，所见即所得，不会出现「预览偏了、上屏也偏」。
 *
 * 十字线默认开启：很多准星图是纯色小块 / 半透明图案，没有线根本看不出中心在哪。
 * 图片自带准星线时可以在页面里关掉，只留图片本身。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.io.File

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var imagePath: String? = null
    private var anchorX = 0.5f
    private var anchorY = 0.5f
    private var showLines = true

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 120
    }
    // 放大绘制时开双线性，不然小图标铺大之后边缘全是锯齿
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 原图宽高（用于算窗口比例 / 限制尺寸），没加载成功时为 0 */
    val sourceWidth: Int get() = bitmap?.width ?: 0
    val sourceHeight: Int get() = bitmap?.height ?: 0

    /** 当前图片路径；页面用来判断要不要重新解码 */
    val loadedPath: String? get() = imagePath

    /** 同一路径只解码一次，避免每次重组都读盘 */
    fun load(path: String?): Boolean {
        if (path == imagePath && bitmap != null) return true
        imagePath = path
        bitmap = CrosshairImages.decode(path)
        invalidate()
        return bitmap != null
    }

    fun setAnchor(x: Float, y: Float) {
        anchorX = x.coerceIn(0f, 1f)
        anchorY = y.coerceIn(0f, 1f)
        invalidate()
    }

    fun setShowLines(show: Boolean) {
        if (showLines == show) return
        showLines = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        if (!showLines) return

        val cx = anchorX * width
        val cy = anchorY * height
        // 按窗口短边自适应：小准星上的十字不能比图还大，大图上也不能细成一根丝
        val base = minOf(width, height).toFloat()
        val gap = (base * 0.09f).coerceIn(2f * density, 8f * density)
        val arm = (base * 0.28f).coerceIn(6f * density, 22f * density)
        val dot = (base * 0.045f).coerceIn(1.4f * density, 3.2f * density)
        linePaint.strokeWidth = (base * 0.006f).coerceIn(1f * density, 2.4f * density)

        // 四段短线留出中心空隙，叠在复杂图片上也看得清
        canvas.drawLine(cx - gap - arm, cy, cx - gap, cy, linePaint)
        canvas.drawLine(cx + gap, cy, cx + gap + arm, cy, linePaint)
        canvas.drawLine(cx, cy - gap - arm, cx, cy - gap, linePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + arm, linePaint)
        // 白底衬一圈，深色 / 花图里也不糊
        canvas.drawCircle(cx, cy, dot + 1.2f * density, haloPaint)
        canvas.drawCircle(cx, cy, dot, dotPaint)
    }
}

/**
 * 准星图片的解码工具。
 *
 * 只按长边 1600px 降采样 —— 准星窗口最大也就半屏宽，再高的分辨率纯属浪费内存；
 * 但也不能太小，用户可能把一张截图当准星用，缩太狠会糊。
 */
object CrosshairImages {

    const val MAX_EDGE = 1600

    fun decode(path: String?, maxEdge: Int = MAX_EDGE): Bitmap? {
        if (path.isNullOrBlank()) return null
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return runCatching {
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.getOrNull()
    }

    /** 高 / 宽；解码尺寸即可，不读整张图。失败返回 null（调用方按 1:1 处理） */
    fun ratio(path: String?): Float? {
        if (path.isNullOrBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outHeight.toFloat() / bounds.outWidth.toFloat()
    }
}
