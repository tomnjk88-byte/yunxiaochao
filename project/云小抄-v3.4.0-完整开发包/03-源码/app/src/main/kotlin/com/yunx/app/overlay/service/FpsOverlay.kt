/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.5.6）。帧率悬浮窗：可透明 / 不透明、可自定义图标与文字色、可拖动。
 *
 * 帧率来源：Choreographer.postFrameCallback 每秒统计显示 VSYNC 回调数（含帧间隔），
 * 得到的是「当前屏幕的合成帧率 / 刷新节奏」。第三方悬浮窗拿不到另一个游戏进程
 * 引擎内部的 FPS（那要 SurfaceFlinger --latency / gfxinfo，需系统或 root 权限），
 * 所以界面上一律如实标成「显示帧率」，不冒充游戏 FPS。
 *
 * 设计要点：
 *  - 一扇独立窗口，FLAG_NOT_TOUCH_MODAL，可拖动但不吞掉周围的游戏触摸；
 *  - 背景三态：完全透明（纯字带描边）/ 半透明磨砂底 / 不透明实色底；
 *  - 整体不透明度 fpsAlpha 作为 WindowManager alpha，与背景样式叠加；
 *  - 图标：内置 emoji 一组可换，也支持选一张自定义图片（圆形裁切）；
 *  - 图标/文字/背景/显隐都在 applyPrefs 里即时重读，设置改完不用重启服务。
 */

package com.yunx.app.overlay.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.yunx.app.overlay.data.OverlayPrefs
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class FpsOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int
) {

    private val prefs = OverlayPrefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    private var root: FpsView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var hidden = false

    private var frameCount = 0
    private var windowStartNanos = 0L
    private var lastFrameNanos = 0L
    private var lastFrameMs = 16.7f
    private var fps = 0

    @Volatile
    private var sampling = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!sampling) return
            frameCount++
            if (lastFrameNanos > 0L) {
                val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                if (dt in 1f..500f) lastFrameMs = lastFrameMs * 0.8f + dt * 0.2f
            }
            lastFrameNanos = frameTimeNanos
            if (windowStartNanos == 0L) windowStartNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - windowStartNanos) / 1_000_000f
            if (elapsedMs >= 1000f) {
                fps = (frameCount * 1000f / elapsedMs).roundToInt()
                frameCount = 0
                windowStartNanos = frameTimeNanos
                root?.postInvalidateOnAnimation()
            }
            choreographer.postFrameCallback(this)
        }
    }

    /** 挂出帧率窗；已挂则只更新外观 */
    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show() }
            return
        }
        if (attached) {
            applyPrefs()
            return
        }
        val view = FpsView(context)
        val density = context.resources.displayMetrics.density
        val w = (VIEW_W_DP * density).toInt()
        val h = (VIEW_H_DP * density).toInt()
        val metrics = context.resources.displayMetrics

        val savedX = prefs.fpsX
        val savedY = prefs.fpsY
        val startX = if (savedX != Int.MIN_VALUE) savedX
        else (metrics.widthPixels - w - (12 * density).toInt())
        val startY = if (savedY != Int.MIN_VALUE) savedY
        else (metrics.heightPixels * 0.12f).toInt()

        val lp = WindowManager.LayoutParams(
            w,
            h,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
            alpha = prefs.fpsAlpha
        }

        val ok = runCatching {
            windowManager.addView(view, lp)
            true
        }.getOrDefault(false)
        if (!ok) return

        root = view
        params = lp
        attached = true
        applyPrefs()
        startSampling()
    }

    /** 收掉窗口（服务销毁 / 关闭开关） */
    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { dismiss() }
            return
        }
        stopSampling()
        params?.let { persistPosition(it.x, it.y) }
        root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        root = null
        params = null
        attached = false
        hidden = false
    }

    /** 音量键隐藏：alpha=0 + 不可触摸，不摘窗口，位置/外观都保留 */
    fun setHidden(value: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setHidden(value) }
            return
        }
        val lp = params ?: return
        if (hidden == value) return
        hidden = value
        if (value) {
            lp.alpha = 0f
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.alpha = prefs.fpsAlpha
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        root?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
    }

    /** 改过透明度 / 背景 / 颜色 / 图标后即时重读刷新 */
    fun applyPrefs() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyPrefs() }
            return
        }
        val lp = params ?: return
        if (!hidden) lp.alpha = prefs.fpsAlpha
        root?.let {
            it.configure(prefs)
            runCatching { windowManager.updateViewLayout(it, lp) }
            it.invalidate()
        }
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        frameCount = 0
        windowStartNanos = 0L
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopSampling() {
        sampling = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /** 拖动结束落盘位置（拖动中只 updateViewLayout，不写盘） */
    private fun persistPosition(x: Int, y: Int) {
        prefs.fpsX = x
        prefs.fpsY = y
    }

    internal fun currentFps(): Int = fps
    internal fun currentFrameMs(): Float = lastFrameMs

    /** 拖动：由 FpsView 的 onTouch 回调 */
    internal fun onDrag(dx: Float, dy: Float) {
        val lp = params ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        root?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
    }

    internal fun onDragEnd() {
        params?.let { persistPosition(it.x, it.y) }
    }

    /**
     * 自绘内容：圆角底（透明/半透/实色三态）+ 图标 + 「60 FPS」+ 可选帧耗时。
     * 用 Canvas 而非 Compose，和准星层 / 点击圆点层一致，独立于宿主 Compose 树。
     */
    private inner class FpsView(ctx: Context) : View(ctx) {
        init {
            // v2.6.0：显式透明。自定义 View 默认无背景，但个别 ROM 会把窗口底色
            // 当成不透明黑填进来，透明区域就显示成一个黑点 —— 这里钉死。
            setBackgroundColor(Color.TRANSPARENT)
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var iconBitmap: Bitmap? = null
        private var iconPathLoaded: String? = null

        private var bgStyle = 1
        private var textColor = 0
        private var showMs = false
        private var iconPreset = ""
        private var iconPath: String? = null

        private var downX = 0f
        private var downY = 0f
        private var dragged = false

        fun configure(p: OverlayPrefs) {
            bgStyle = p.fpsBgStyle
            textColor = p.fpsTextColor
            showMs = p.fpsShowMs
            iconPreset = p.fpsIconPreset
            iconPath = p.fpsIconPath
            reloadIcon()
        }

        private fun reloadIcon() {
            val path = iconPath
            if (path.isNullOrBlank()) {
                iconBitmap = null
                iconPathLoaded = null
                return
            }
            if (path == iconPathLoaded && iconBitmap != null) return
            iconBitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val target = (32 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val sample = (minOf(bounds.outWidth, bounds.outHeight) / target).coerceAtLeast(1)
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            iconPathLoaded = path
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val d = resources.displayMetrics.density
            val pad = 8 * d
            rect.set(1.5f * d, 1.5f * d, width - 1.5f * d, height - 1.5f * d)
            val radius = 12 * d

            // 背景三态
            when (bgStyle) {
                0 -> bgPaint.color = Color.TRANSPARENT                 // 完全透明
                1 -> bgPaint.color = Color.argb(110, 18, 20, 26)       // 半透明磨砂
                else -> bgPaint.color = Color.argb(240, 16, 18, 24)   // 不透明实色
            }
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            if (bgStyle != 0) {
                borderPaint.color = Color.argb(90, 255, 255, 255)
                borderPaint.strokeWidth = 1 * d
                canvas.drawRoundRect(rect, radius, radius, borderPaint)
            }

            // 文字色：自定义色优先；否则半透/实色底用白，透明底用白 + 黑描边保证可读
            val custom = textColor != 0
            val baseColor = if (custom) textColor else Color.WHITE
            textPaint.color = baseColor
            strokePaint.color = if (custom) Color.argb(160, 0, 0, 0) else Color.argb(190, 0, 0, 0)
            val textSizePx = 15 * d
            textPaint.textSize = textSizePx
            strokePaint.textSize = textSizePx
            strokePaint.strokeWidth = 2.4f * d

            var cursorX = rect.left + pad
            val centerY = height / 2f

            // 图标（自定义图片优先，否则 emoji 字符）
            val bmp = iconBitmap
            val iconSize = 20 * d
            if (bmp != null) {
                val size = minOf(bmp.width, bmp.height)
                val left = (bmp.width - size) / 2
                val top = (bmp.height - size) / 2
                val square = Rect(left, top, left + size, top + size)
                val dst = RectF(cursorX, centerY - iconSize / 2f, cursorX + iconSize, centerY + iconSize)
                canvas.drawBitmap(bmp, square, dst, iconPaint)
                cursorX += iconSize + 6 * d
            } else if (iconPreset.isNotBlank()) {
                iconPaint.color = baseColor
                iconPaint.textSize = iconSize
                iconPaint.textAlign = Paint.Align.LEFT
                // v2.6.0：不再用 BOLD 画 emoji —— 缺彩色字体的机型会退化成黑色粗剪影。
                iconPaint.typeface = Typeface.DEFAULT
                canvas.drawText(iconPreset, cursorX, centerY + iconSize * 0.35f, iconPaint)
                cursorX += iconSize + 5 * d
            }

            val fpsLabel = "$fps FPS"
            val msLabel = if (showMs) "  ${lastFrameMs.roundToInt()} ms" else ""
            val baseline = centerY + textSizePx * 0.36f

            // 透明底 / 亮字一律描边，压在亮色游戏画面上也看得清
            if (bgStyle == 0 || !custom) {
                canvas.drawText(fpsLabel + msLabel, cursorX, baseline, strokePaint)
            }
            canvas.drawText(fpsLabel, cursorX, baseline, textPaint)
            if (showMs) {
                val labelW = textPaint.measureText(fpsLabel)
                val subPaint = Paint(textPaint).apply {
                    textSize = 10 * d
                    color = if (custom) textColor else Color.argb(220, 255, 255, 255)
                }
                canvas.drawText(msLabel, cursorX + labelW, baseline, subPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragged && (abs(dx) > 6f || abs(dy) > 6f)) dragged = true
                    if (dragged) {
                        onDrag(dx, dy)
                        downX = event.rawX
                        downY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) onDragEnd()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private companion object {
        const val VIEW_W_DP = 112
        const val VIEW_H_DP = 32
    }
}
