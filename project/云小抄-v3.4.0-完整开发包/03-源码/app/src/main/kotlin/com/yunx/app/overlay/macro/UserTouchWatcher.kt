/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.16「连招时也能手动操作」的触摸监听。
 *
 * 做法：在屏幕角落放一个 1×1 像素、透明、可触摸的小窗口，并带上
 * FLAG_WATCH_OUTSIDE_TOUCH —— 用户只要在窗口外按下，系统就会给它一个
 * MotionEvent.ACTION_OUTSIDE，但**事件不会因此被它吃掉**，下面该收到的照样收到。
 * 这是 Android 上唯一「不挡手」的全局按下探测方式，悬浮窗 App 基本都这么做。
 *
 * 几个必须注意的点：
 *   - 只监听 ACTION_OUTSIDE / ACTION_DOWN，不关心后续 MOVE/UP（系统不给）；
 *   - 连招自己注入的点击也会走到这里，用时间窗挡掉（isSelfInjection），否则会自己让路给自己；
 *   - 只在连招执行期间挂上去，停止就摘掉，不常驻一个 1px 的死角；
 *   - 加窗口失败（权限被撤、ROM 限制）就静默降级成「不监听」，连招照常跑，只是不会让路。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

object UserTouchWatcher {

    /** 最后一次「用户」按下屏幕的时间（uptimeMillis），0 = 没按过 */
    @Volatile
    private var lastTouchAt = 0L

    /** 最后一次连招注入的时间，用来把自身事件挡掉 */
    @Volatile
    private var lastInjectAt = 0L

    private var view: View? = null
    private var manager: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    /** 连招开始执行时调用；重复调用无副作用 */
    fun start(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context) }
            return
        }
        if (view != null) return
        lastTouchAt = 0L
        lastInjectAt = 0L
        val wm = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val probe = object : View(context.applicationContext) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                note(event)
                // 返回 false：这一像素我们不消费，避免在极少数 ROM 上表现成「点了没反应」
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1,
            1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 刻意放在「左侧边缘、垂直居中」，而不是 (0,0)：
            // (0,0) 在状态栏底下，窗口会被判成被遮挡，系统就不再给它 ACTION_OUTSIDE 了。
            // 屏幕中间偏左这一条不会压到状态栏 / 手势条，也几乎不可能挡住游戏内容（1 像素）。
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
        }
        val added = runCatching { wm.addView(probe, lp) }.isSuccess
        if (!added) return
        manager = wm
        view = probe
    }

    /** 连招结束 / 停止时调用 */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        view?.let { runCatching { manager?.removeViewImmediate(it) } }
        view = null
        manager = null
        lastTouchAt = 0L
        lastInjectAt = 0L
    }

    /**
     * 连招自己的手势被系统取消了 —— 说明屏幕上有别人（通常是你的手指，也可能是别的
     * 无障碍服务 / 息屏）。探针有时会漏掉那一下（正好撞在注入后的保护窗口里），
     * 这里补一个「刚刚有人摸过」的标记，让连招退一步再试。
     */
    fun noteInterrupted() {
        lastInjectAt = 0L
        lastTouchAt = SystemClock.uptimeMillis()
    }

    /** 连招每次注入手势前调用，把自己制造的事件标出来 */
    fun markInjected() {
        lastInjectAt = SystemClock.uptimeMillis()
    }

    /** 用户此刻是否还在操作屏幕（含「松手后 holdMs 的缓冲」） */
    fun isBusy(holdMs: Long): Boolean =
        userHolding(SystemClock.uptimeMillis(), lastTouchAt, holdMs)

    /** 最近一次用户按下的时间戳，调试 / 测试用 */
    fun lastTouch(): Long = lastTouchAt

    private fun note(event: MotionEvent) {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_OUTSIDE) return
        val now = SystemClock.uptimeMillis()
        if (isSelfInjection(now, lastInjectAt)) return
        lastTouchAt = now
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
