/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。「把连招做成一个按钮」：一颗独立于小抄窗、可拖到任意位置的圆形按钮。
 * 点一下开始/停止，拖动改位置；这样看图的同时不用切回面板也能触发连招。
 *
 * v1.8.4：按钮外观跟随该连招的专属设置 —— 自定义图片（圆形裁切）/ 内置 emoji / 专属颜色。
 *   v1.8.3 之前所有按钮都是同一颗蓝圆 + 名字前两字，现在每颗都长得不一样。
 */

package com.yunx.app.overlay.macro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import kotlin.math.abs

class MacroBall(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val macroId: String,
    private var config: MacroConfig,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
    /** v1.9.3：还没定过位置时，用它决定贴右边第几格，避免几颗球全叠在左上角 */
    private val defaultSlot: Int = 0
) {

    private var view: FrameLayout? = null
    private var imageView: ImageView? = null
    private var labelView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** v1.9.1 音量键隐藏：记住窗口原本的 alpha / flags，恢复时原样还回去 */
    private var hidden = false
    private var originalAlpha = 1f
    private var originalFlags = 0

    private val sizePx: Int get() = (BALL_DP * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val ball = FrameLayout(context).apply {
            // 圆形裁切：自定义图片铺满后也只露出圆，不用额外做 BitmapShader
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            elevation = 8f
            alpha = 0.94f
        }

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        ball.addView(image)
        ball.addView(label)

        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (config.ballX < 0 || config.ballY < 0) {
                // v1.9.3：刚录完的连招贴右边竖排，按序号往下错开，不会几颗叠成一坨
                val gap = (10 * context.resources.displayMetrics.density).toInt()
                x = screenWidth() - sizePx - gap
                y = (screenHeight() * 0.26f).toInt() + defaultSlot * (sizePx + gap)
            } else {
                x = config.ballX
                y = config.ballY
            }
            clamp(this)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && (abs(dx) > 8f || abs(dy) > 8f)) dragged = true
                    if (dragged) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        clamp(lp)
                        runCatching { windowManager.updateViewLayout(ball, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        MacroController.updateBallPosition(config, lp.x, lp.y)
                    } else {
                        val latest = MacroController.macros.value.firstOrNull { it.id == macroId }
                            ?: config
                        MacroController.toggleRun(latest)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        runCatching { windowManager.addView(ball, lp) }
        view = ball
        imageView = image
        labelView = label
        params = lp
        applyAppearance()
    }

    /** 配置改了（含换图标 / 换图片 / 换颜色）/ 运行状态变了，就地把按钮刷新一下 */
    fun update(next: MacroConfig) {
        config = next
        applyAppearance()
    }

    fun refreshState() {
        view?.background = oval(config.displayColor, isRunning(), isPaused())
    }

    /**
     * v1.9.1：音量键隐藏时把整颗球变成透明且不可触摸，不做 removeView ——
     * 这样恢复时位置、拖动状态、外观全都不用重建。
     */
    fun setHidden(value: Boolean) {
        val lp = params ?: return
        if (hidden == value) return
        hidden = value
        if (value) {
            originalAlpha = lp.alpha
            originalFlags = lp.flags
            lp.alpha = 0f
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.alpha = originalAlpha
            lp.flags = originalFlags
        }
        view?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeViewImmediate(it) } }
        view = null
        imageView = null
        labelView = null
        params = null
    }

    private fun applyAppearance() {
        val root = view ?: return
        root.background = oval(config.displayColor, isRunning(), isPaused())

        val bitmap = decodeIcon(config.iconImagePath)
        imageView?.setImageBitmap(bitmap)
        imageView?.visibility = if (bitmap != null) View.VISIBLE else View.GONE

        labelView?.text = if (bitmap == null) {
            config.iconPreset.ifBlank { config.name.take(2) }
        } else {
            ""
        }
        labelView?.visibility = if (bitmap == null) View.VISIBLE else View.GONE
    }

    /** 按球的尺寸采样解码，避免用户选了一张 4000px 大图时把悬浮窗内存撑爆 */
    private fun decodeIcon(path: String?): Bitmap? {
        if (path.isNullOrBlank() || !File(path).exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val target = sizePx.coerceAtLeast(1)
            val sample = (minOf(bounds.outWidth, bounds.outHeight) / target).coerceAtLeast(1)
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun isRunning(): Boolean = MacroController.runningId.value == macroId

    /** v1.9.16：正在给手动操作让路 —— 描边换成琥珀色，表示「在跑，只是先等你」 */
    private fun isPaused(): Boolean = isRunning() && MacroController.pausedByUser.value

    private fun clamp(lp: WindowManager.LayoutParams) {
        val size = sizePx
        lp.x = lp.x.coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenHeight() - size).coerceAtLeast(0))
    }

    /**
     * 底色用连招的专属色；执行中只把描边加粗变红，不覆盖用户的配色。
     * v1.9.16：正在给你让路时描边转琥珀色 —— 红色=在点，琥珀=在等你，一眼能分。
     */
    private fun oval(color: Int, running: Boolean, paused: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                if (running) 4 else 2,
                when {
                    running && paused -> 0xFFFFB020.toInt()
                    running -> 0xFFFF3B30.toInt()
                    else -> 0x66FFFFFF.toInt()
                }
            )
        }

    private companion object {
        const val BALL_DP = 50
    }
}
