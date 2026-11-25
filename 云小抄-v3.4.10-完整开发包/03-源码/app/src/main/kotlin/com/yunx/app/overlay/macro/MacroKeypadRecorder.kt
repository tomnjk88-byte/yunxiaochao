/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.5.7「连招录制台」。
 *
 * 为什么重做录制：
 *   旧录制（MacroCaptureOverlay 的 RECORD 模式）是一层 MATCH_PARENT 的透明膜，
 *   录制期间整块屏幕的触摸都被它吃掉 —— 游戏画面还在，但点哪都没反应，
 *   用户只能对着空气比划，录完还得自己想象节奏对不对。这跟「游戏空间里
 *   自动连招的设置方式」完全是两回事。
 *
 * 这一版换成游戏助手那套「按键板」：
 *   1) 布置：把一个个按键拖到游戏真实的技能键 / 普攻键上；
 *   2) 录制：每个按键是**一扇独立的小悬浮窗**，只有按键那几十像素吃触摸，
 *      屏幕其它地方照常交给游戏。所以可以边走路、边看画面、边按顺序点键，
 *      点出来的顺序和时间间隔被原样记成连招步骤。
 *   3) 完成：按键位置 + 步骤一起存下来，回放时按同一组坐标注入点击。
 *
 * 一句话：录制的是「你按了哪个键、隔多久按下一个」，不是「你在黑屏上划了什么」。
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

class MacroKeypadRecorder(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    /** 重新录制时把上一次的按键摆回来；首次录制传空列表 */
    seedKeys: List<MacroKey>,
    private val onResult: (List<MacroKey>, List<MacroStep>) -> Unit,
    private val onCancel: () -> Unit
) {

    private enum class Stage { PLACE, RECORD }

    private val density = context.resources.displayMetrics.density
    private val screenW = context.resources.displayMetrics.widthPixels
    private val screenH = context.resources.displayMetrics.heightPixels
    private val handler = Handler(Looper.getMainLooper())

    private val keys = mutableListOf<MacroKey>().apply { addAll(seedKeys) }
    private val steps = mutableListOf<MacroStep>()

    private var stage = Stage.PLACE
    private var dead = false

    // PLACE 阶段的整屏布置层
    private var placeRoot: PlaceBoard? = null
    private var placeBar: LinearLayout? = null

    // RECORD 阶段：一个键一扇窗 + 一条控制条
    private val keyWindows = mutableMapOf<String, View>()
    private val keyParams = mutableMapOf<String, WindowManager.LayoutParams>()
    private var controlBar: View? = null
    private var statusText: TextView? = null
    private var pauseChip: TextView? = null

    private var paused = false
    private var lastUpAt = 0L
    private var startedAt = 0L
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (dead) return
            updateStatus()
            handler.postDelayed(this, 100L)
        }
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    fun show() {
        openPlace()
    }

    fun dismiss() {
        if (dead) return
        dead = true
        handler.removeCallbacksAndMessages(null)
        removePlace()
        removeRecord()
    }

    // ------------------------------------------------------------------
    // 阶段一：布置按键
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun openPlace() {
        stage = Stage.PLACE
        removeRecord()

        val board = PlaceBoard(context).also { it.setWillNotDraw(false) }
        board.setBackgroundColor(0x26000000)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(8), dp(7))
            background = roundRect(0xF01B2030.toInt(), dp(10).toFloat())
            addView(
                label("点屏幕放按键 · 拖到技能键上", 10f, 0xFFB9C2D6.toInt()),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(chip("↩ 撤一个", 0xFF2C3448.toInt()) { undoKey() })
            addView(chip("▶ 开始录", 0xFF3D6BF5.toInt()) { startRecord() })
            addView(chip("✕", 0xFF3A2130.toInt()) { cancelAll() })
        }
        placeBar = bar
        board.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(dp(10), dp(52), dp(10), 0) }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(board, lp) }
            .onFailure {
                dead = true
                onCancel()
                return
            }
        placeRoot = board
        updatePlaceHint()
    }

    private fun undoKey() {
        if (keys.isEmpty()) return
        keys.removeAt(keys.lastIndex)
        placeRoot?.invalidate()
        updatePlaceHint()
    }

    private fun updatePlaceHint() {
        val bar = placeBar ?: return
        val tip = bar.getChildAt(0) as? TextView ?: return
        tip.text = if (keys.isEmpty()) {
            "点屏幕放按键 · 建议放在技能键上"
        } else {
            "已放 ${keys.size} 个 · 拖可挪位 · 再点空白可继续加"
        }
    }

    /** 布置层里的触摸：按在已有键上就拖它，按在空白处就新建并跟随手指 */
    private fun onPlaceTouch(ev: MotionEvent): Boolean {
        val x = ev.rawX
        val y = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = keys.lastOrNull { key ->
                    hypot(x - key.x.toFloat(), y - key.y.toFloat()) <= key.sizeDp * density / 2f + dp(8)
                }
                if (hit != null) {
                    draggingId = hit.id
                } else {
                    val created = MacroKey(
                        label = (keys.size + 1).toString(),
                        x = x.toInt(),
                        y = y.toInt()
                    )
                    keys.add(created)
                    draggingId = created.id
                    updatePlaceHint()
                }
                placeRoot?.invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val id = draggingId ?: return true
                val i = keys.indexOfFirst { it.id == id }
                if (i >= 0) {
                    keys[i] = keys[i].copy(
                        x = x.toInt().coerceIn(dp(8), screenW - dp(8)),
                        y = y.toInt().coerceIn(dp(8), screenH - dp(8))
                    )
                    placeRoot?.invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingId = null
                updatePlaceHint()
            }
        }
        return true
    }

    private var draggingId: String? = null

    // ------------------------------------------------------------------
    // 阶段二：按键录制
    // ------------------------------------------------------------------

    private fun startRecord() {
        if (keys.isEmpty()) {
            updatePlaceHint()
            placeBar?.let { bar ->
                (bar.getChildAt(0) as? TextView)?.text = "先点屏幕放至少一个按键"
            }
            return
        }
        removePlace()
        stage = Stage.RECORD
        steps.clear()
        lastUpAt = 0L
        startedAt = 0L
        paused = false

        keys.forEach { addKeyWindow(it) }
        addControlBar()
        ticking = true
        handler.post(ticker)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addKeyWindow(key: MacroKey) {
        val size = (key.sizeDp * density).toInt()
        val view = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC3D6BF5.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            addView(
                TextView(context).apply {
                    text = key.label.ifBlank { "${keys.indexOf(key) + 1}" }
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            )
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = key.x - size / 2
            y = key.y - size / 2
        }

        var downAt = 0L
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = ev.eventTime
                    v.alpha = 1f
                    v.scaleX = 1.1f
                    v.scaleY = 1.1f
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.alpha = 0.86f
                    v.scaleX = 1f
                    v.scaleY = 1f
                    recordPress(key, downAt, ev.eventTime)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.86f
                    v.scaleX = 1f
                    v.scaleY = 1f
                    true
                }

                else -> true
            }
        }
        view.alpha = 0.86f

        runCatching { windowManager.addView(view, lp) }.onSuccess {
            keyWindows[key.id] = view
            keyParams[key.id] = lp
        }
    }

    private fun recordPress(key: MacroKey, downAt: Long, upAt: Long) {
        if (paused || dead) return
        if (startedAt == 0L) startedAt = downAt
        val gap = if (lastUpAt == 0L) 0L else (downAt - lastUpAt).coerceIn(0L, 30_000L)
        val hold = (upAt - downAt).coerceIn(16L, 10_000L)
        steps.add(
            MacroStep(
                points = listOf(MacroPoint(key.x.toFloat(), key.y.toFloat())),
                durationMs = hold,
                gapBeforeMs = gap
            )
        )
        lastUpAt = upAt
        updateStatus()
    }

    private fun addControlBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            background = roundRect(0xF01B2030.toInt(), dp(11).toFloat())
        }
        val status = label("0 步 · 0.0s", 10f, Color.WHITE)
        statusText = status
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val pause = chip("⏸", 0xFF2C3448.toInt()) {
            paused = !paused
            pauseChip?.text = if (paused) "▶" else "⏸"
            pauseChip?.background = roundRect(
                if (paused) 0xFFB26A00.toInt() else 0xFF2C3448.toInt(),
                dp(9).toFloat()
            )
            updateStatus()
        }
        pauseChip = pause
        bar.addView(pause)
        bar.addView(chip("↩", 0xFF2C3448.toInt()) {
            steps.removeLastOrNull()
            lastUpAt = 0L
            updateStatus()
        })
        bar.addView(chip("✎ 挪键", 0xFF2C3448.toInt()) {
            // 回到布置阶段：按键位置要重摆，已录的步骤作废
            openPlace()
        })
        bar.addView(chip("✓ 完成", 0xFF34C759.toInt()) { finishRecord() })
        bar.addView(chip("✕", 0xFF3A2130.toInt()) { cancelAll() })

        val lp = WindowManager.LayoutParams(
            // 控制条自己占一条固定宽度，只吃这一条上的触摸；别的区域照常给游戏
            (screenW - dp(20)).coerceAtLeast(dp(240)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        runCatching { windowManager.addView(bar, lp) }.onSuccess { controlBar = bar }
        updateStatus()
    }

    private fun updateStatus() {
        val t = statusText ?: return
        val span = if (startedAt == 0L) 0L else (android.os.SystemClock.uptimeMillis() - startedAt)
        val secs = span / 1000f
        t.text = when {
            paused -> "已暂停 · ${steps.size} 步"
            steps.isEmpty() -> "按顺序点键"
            else -> "${steps.size} 步 · ${String.format("%.1f", secs)}s"
        }
        t.setTextColor(if (paused) 0xFFFFB020.toInt() else Color.WHITE)
    }

    private fun finishRecord() {
        if (steps.isEmpty()) {
            statusText?.text = "还没按任何键"
            statusText?.setTextColor(0xFFFF6B6B.toInt())
            return
        }
        val resultKeys = keys.toList()
        val resultSteps = steps.toList()
        dismiss()
        onResult(resultKeys, resultSteps)
    }

    private fun cancelAll() {
        dismiss()
        onCancel()
    }

    // ------------------------------------------------------------------
    // 窗口清理
    // ------------------------------------------------------------------

    private fun removePlace() {
        placeRoot?.let { runCatching { windowManager.removeViewImmediate(it) } }
        placeRoot = null
        placeBar = null
    }

    private fun removeRecord() {
        ticking = false
        handler.removeCallbacks(ticker)
        keyWindows.values.forEach { v -> runCatching { windowManager.removeViewImmediate(v) } }
        keyWindows.clear()
        keyParams.clear()
        controlBar?.let { runCatching { windowManager.removeViewImmediate(it) } }
        controlBar = null
        statusText = null
        pauseChip = null
    }

    // ------------------------------------------------------------------
    // 小组件
    // ------------------------------------------------------------------

    private fun label(text: String, size: Float, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            maxLines = 2
        }

    private fun chip(text: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundRect(bg, dp(9).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun roundRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(v: Int): Int = (v * density).toInt()

    /** 布置阶段的整屏层：画半透明圈 + 序号，空白处点一下就加键 */
    private inner class PlaceBoard(context: Context) : FrameLayout(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x553D6BF5
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(15).toFloat()
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val bar = placeBar
            if (bar != null) {
                val loc = IntArray(2)
                bar.getLocationOnScreen(loc)
                if (ev.rawX >= loc[0] && ev.rawX <= loc[0] + bar.width &&
                    ev.rawY >= loc[1] && ev.rawY <= loc[1] + bar.height
                ) {
                    return super.dispatchTouchEvent(ev)
                }
            }
            onPlaceTouch(ev)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            keys.forEachIndexed { i, k ->
                val r = k.sizeDp * density / 2f
                canvas.drawCircle(k.x.toFloat(), k.y.toFloat(), r, fill)
                canvas.drawCircle(k.x.toFloat(), k.y.toFloat(), r, ring)
                canvas.drawText(
                    k.label.ifBlank { "${i + 1}" },
                    k.x.toFloat(),
                    k.y.toFloat() + dp(5),
                    text
                )
            }
        }
    }
}
