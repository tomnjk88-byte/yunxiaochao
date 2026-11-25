/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.1「点击显示」：在屏幕上画出连招每一次点击的位置。
 *
 * 设计要点：
 *   - 全屏窗口带 FLAG_NOT_TOUCHABLE，只画不摸，绝不会抢走游戏的触摸；
 *   - 圆点带序号、约 0.9 秒淡出，最后一个点消失后自动把窗口摘掉，不常驻；
 *   - 录制膜里另有一套「持久标记」（见 MacroCaptureOverlay），这套用于回放时；
 *   - 是否显示由 OverlayPrefs.showTapIndicator 控制，用户可在自动连招界面关掉。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.yunx.app.overlay.data.OverlayPrefs

class TapIndicatorOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int
) {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = OverlayPrefs(context)
    private val points = mutableListOf<TapPoint>()
    private var sequence = 0
    private var root: TapView? = null

    /** 执行到某一步时由 MacroRunner 回调；坐标是屏幕绝对坐标 rawX / rawY */
    fun showTap(x: Float, y: Float) {
        if (!prefs.showTapIndicator) return
        handler.post {
            if (!prefs.showTapIndicator) return@post
            ensureView()
            sequence++
            points.add(TapPoint(x, y, sequence, SystemClock.uptimeMillis() + LIFETIME_MS))
            if (points.size > MAX_POINTS) points.removeAt(0)
            root?.invalidate()
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    /** 窗口隐藏 / Service 销毁时立刻收掉，别留一层透明膜 */
    fun dismiss() {
        handler.removeCallbacks(tick)
        root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        root = null
        points.clear()
    }

    private fun ensureView() {
        if (root != null) return
        val view = TapView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { root = null; return }
        root = view
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            points.removeAll { now > it.expireAt }
            val view = root
            if (view == null || points.isEmpty()) {
                dismiss()
                return
            }
            view.invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    private inner class TapView(context: Context) : View(context) {

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TAP_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TAP_COLOR
            style = Paint.Style.FILL
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 19f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            points.forEach { p ->
                val left = p.expireAt - now
                if (left <= 0L) return@forEach
                val alpha = (left.coerceAtMost(LIFETIME_MS).toFloat() / LIFETIME_MS * 255f)
                    .toInt().coerceIn(0, 255)
                fill.alpha = alpha / 3
                ring.alpha = alpha
                label.alpha = alpha
                canvas.drawCircle(p.x, p.y, 22f, fill)
                canvas.drawCircle(p.x, p.y, 22f, ring)
                canvas.drawText(p.seq.toString(), p.x, p.y + 7f, label)
            }
        }
    }

    private data class TapPoint(
        val x: Float,
        val y: Float,
        val seq: Int,
        val expireAt: Long
    )

    private companion object {
        const val LIFETIME_MS = 900L
        const val FRAME_MS = 80L
        const val MAX_POINTS = 12
        const val TAP_COLOR = 0xFFFF4D4F.toInt()
    }
}
