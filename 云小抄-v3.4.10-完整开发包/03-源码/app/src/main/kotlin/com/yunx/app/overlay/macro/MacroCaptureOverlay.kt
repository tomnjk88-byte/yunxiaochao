/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。取点 / 录制用的全屏透明覆盖层。
 *
 * 为什么用 Android View 而不是 Compose：这里必须拿到 MotionEvent.rawX/rawY 的屏幕绝对坐标，
 * 而 dispatchGesture 用的正是同一套坐标系；Compose 的 pointerInput 给的是相对坐标，
 * 隔着状态栏 / 手势导航栏容易偏几个像素，连点位置就打歪了。
 *
 * 录制时这层会吃掉所有触摸——用户是在「比划一遍」给 App 看，游戏本身收不到，
 * 保存并收起覆盖层之后才会真正回放。
 */

package com.yunx.app.overlay.macro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class MacroCaptureOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val mode: CaptureMode,
    private val onResult: (List<MacroStep>) -> Unit,
    private val onCancel: () -> Unit,
    /** v1.9.1「点击显示」：录制时把每一次点击画成带序号的圆点 */
    private val showTapIndicator: Boolean = true
) {

    private var root: CaptureRoot? = null
    private var hintView: TextView? = null
    private var dismissed = false

    /**
     * 兜底：录制/取点时若用户直接按 Home 切走、或系统把 App 杀到后台，
     * 这层 MATCH_PARENT 的透明膜会一直盖在屏幕上吞掉所有触摸，看起来像「整机卡死」。
     * 无人操作超过 3 分钟自动收起，并且回到主页时 Service 会主动 dismiss。
     */
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (!dismissed) {
            dismiss()
            onCancel()
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)
    }

    private val recorded = mutableListOf<MacroStep>()
    private var pickedPoint: MacroPoint? = null
    private var aimPoint: MacroPoint? = null
    private val liveTrace = mutableListOf<MacroPoint>()
    /** 手指还按着时先画一个高亮点，抬手后由 recorded 里的持久点接管 */
    private var activeTap: MacroPoint? = null

    private var downTime = 0L
    private var lastUpTime = 0L
    private var lastSampleTime = 0L

    fun show() {
        val density = context.resources.displayMetrics.density
        val px = { value: Int -> (value * density).toInt() }

        val hint = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            text = initialHint()
        }
        hintView = hint

        val cancel = actionButton("取消") {
            dismiss()
            onCancel()
        }
        val confirm = actionButton(if (mode == CaptureMode.AIM) "确定并保存" else "保存这条连招") {
            val result: List<MacroStep> = if (mode == CaptureMode.AIM) {
                val point = pickedPoint
                if (point == null) {
                    hint.text = "先在屏幕上点一下选位置"
                    return@actionButton
                }
                listOf(MacroStep.tap(point.x, point.y, DEFAULT_TAP_DURATION))
            } else {
                recorded.toList()
            }
            if (result.isEmpty()) {
                hint.text = "还没有录到动作，先点/划几下"
                return@actionButton
            }
            dismiss()
            onResult(result)
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(8), px(8), px(8))
            background = roundedRect(0xF01B2030.toInt(), px(10).toFloat())
            elevation = px(6).toFloat()
            addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(30)))
            addView(confirm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(30)))
        }

        val captureRoot = CaptureRoot(context, bar).also { it.setWillNotDraw(false) }
        captureRoot.setBackgroundColor(0x1A000000)
        captureRoot.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(px(12), px(36), px(12), 0) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        runCatching { windowManager.addView(captureRoot, params) }
            .onFailure {
                dismissed = true
                onCancel()
                return
            }
        root = captureRoot
        dismissed = false
        scheduleTimeout()
    }

    fun dismiss() {
        if (dismissed && root == null) return
        dismissed = true
        handler.removeCallbacks(timeoutRunnable)
        root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        root = null
        hintView = null
    }

    private fun initialHint(): String = when (mode) {
        CaptureMode.AIM -> "取点：点一下要连点的位置，再按「确定并保存」"
        CaptureMode.RECORD -> "连招窗已缩成球 · 按平时节奏点 / 长按 / 滑动"
    }

    private fun updateHint() {
        val hint = hintView ?: return
        hint.text = when (mode) {
            CaptureMode.AIM -> pickedPoint?.let { "已选 (${it.x.toInt()}, ${it.y.toInt()})，按「确定并保存」" }
                ?: initialHint()
            CaptureMode.RECORD -> "已录 ${recorded.size} 步${if (showTapIndicator) " · 点击位置已显示" else ""}${if (liveTrace.isNotEmpty()) " · 正在画…" else ""}"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleMotion(event: MotionEvent) {
        if (event.pointerCount != 1) return   // 只录单指，多指直接忽略
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                lastSampleTime = downTime
                liveTrace.clear()
                liveTrace.add(MacroPoint(x, y))
                if (mode == CaptureMode.AIM) aimPoint = MacroPoint(x, y)
                else if (showTapIndicator) activeTap = MacroPoint(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                val last = liveTrace.lastOrNull()
                if (last != null &&
                    (abs(x - last.x) > 6f || abs(y - last.y) > 6f ||
                        event.eventTime - lastSampleTime > 60L) &&
                    liveTrace.size < 80
                ) {
                    liveTrace.add(MacroPoint(x, y))
                    lastSampleTime = event.eventTime
                }
                if (mode == CaptureMode.AIM) aimPoint = MacroPoint(x, y)
            }

            MotionEvent.ACTION_UP -> {
                val duration = (event.eventTime - downTime).coerceIn(20L, 10_000L)
                if (mode == CaptureMode.AIM) {
                    pickedPoint = MacroPoint(x, y)
                    aimPoint = pickedPoint
                } else {
                    val gap = if (lastUpTime > 0L) {
                        (downTime - lastUpTime).coerceIn(0L, 30_000L)
                    } else {
                        0L
                    }
                    if (liveTrace.isEmpty()) liveTrace.add(MacroPoint(x, y))
                    recorded.add(MacroStep(liveTrace.toList(), duration, gap))
                    lastUpTime = event.eventTime
                }
                liveTrace.clear()
                activeTap = null
            }

            MotionEvent.ACTION_CANCEL -> {
                liveTrace.clear()
                activeTap = null
            }
        }
        updateHint()
        root?.invalidate()
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.WHITE)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(14, 0, 14, 0)
            background = roundedRect(0xFF3D6BF5.toInt(), 14f)
            setOnClickListener { onClick() }
        }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    /** 全屏捕获层：控制条区域交给按钮，其余区域自己吃掉并记录坐标 */
    private inner class CaptureRoot(
        context: Context,
        private val controlBar: View
    ) : FrameLayout(context) {

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF4D4F.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD166.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        // v1.9.1「点击显示」：把录到的每一次点击标出来，带序号，录制期间一直留着
        private val tapFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FF4D4F
            style = Paint.Style.FILL
        }
        private val tapRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF4D4F.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val tapLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 17f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            scheduleTimeout()
            val loc = IntArray(2)
            controlBar.getLocationOnScreen(loc)
            val inBar = ev.rawX >= loc[0] && ev.rawX <= loc[0] + controlBar.width &&
                ev.rawY >= loc[1] && ev.rawY <= loc[1] + controlBar.height
            if (inBar) return super.dispatchTouchEvent(ev)
            handleMotion(ev)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val point = if (mode == CaptureMode.AIM) (aimPoint ?: pickedPoint) else null
            if (point != null) {
                canvas.drawCircle(point.x, point.y, 18f, ringPaint)
                canvas.drawLine(point.x - 30f, point.y, point.x + 30f, point.y, crossPaint)
                canvas.drawLine(point.x, point.y - 30f, point.x, point.y + 30f, crossPaint)
            }
            if (mode == CaptureMode.RECORD && liveTrace.size > 1) {
                for (i in 1 until liveTrace.size) {
                    val a = liveTrace[i - 1]
                    val b = liveTrace[i]
                    canvas.drawLine(a.x, a.y, b.x, b.y, tracePaint)
                }
            }
            if (showTapIndicator && mode == CaptureMode.RECORD) {
                recorded.forEachIndexed { i, step ->
                    val p = step.start
                    canvas.drawCircle(p.x, p.y, 16f, tapFill)
                    canvas.drawCircle(p.x, p.y, 16f, tapRing)
                    canvas.drawText((i + 1).toString(), p.x, p.y + 6f, tapLabel)
                }
                activeTap?.let { p ->
                    canvas.drawCircle(p.x, p.y, 24f, tapFill)
                    canvas.drawCircle(p.x, p.y, 24f, tapRing)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TAP_DURATION = 50L
        /** 无人操作 3 分钟自动收起，避免透明膜永久残留 */
        const val CAPTURE_TIMEOUT_MS = 3 * 60_000L
    }
}
