/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.8.2「长按整窗拖动」根布局。
 *
 * 背景：此前两个悬浮窗只有顶栏那条 38dp 的「拖动」热区能移动，内容区（分类网格、
 * 大图预览、连招面板的按钮 / 滑杆 / Tab）整片都拖不动；窗口贴到屏幕顶被挖孔挡住时，
 * 那条热区几乎按不到。用户要求「整个都能拖，比如长按拖动」。
 *
 * 为什么必须是长按而不是按下即拖：内容区全是 clickable / Slider / Pager / 双指缩放，
 * 直接在 DOWN 就抢事件会让所有按钮失效。这里用 onInterceptTouchEvent 做三态判定：
 *   1. 短按抬手            → 完全不拦截，交给子控件正常点击；
 *   2. 按下后立刻滑动      → 位移超过 touchSlop 就取消长按，翻页 / 滑杆 / 拉伸照常；
 *   3. 按住不动 ~350ms     → 进入拖动，高亮描边 + 轻震动，之后 MOVE 改 LayoutParams.x/y。
 * 第二根手指落下也取消长按，把多指让给攻略窗的双指缩放状态机。
 *
 * 自愈（避免复现 v1.4.0 修过的「用一阵后滑不动」）：
 *   - 新的 ACTION_DOWN 若发现上一轮拖动未结束，先强制放手；
 *   - 拖动超过 15s 强制放手；
 *   - resetTouchState() 供「一键重置」/ 窗口销毁时清状态。
 * 重要：ViewGroup 一旦拦截成功，后续 MOVE 就不再回调 onInterceptTouchEvent，
 * 全部改走 onTouchEvent。v1.8.2 只在前者里更新位置，结果「每次只能移动一点」；
 * v1.8.3 两处都调用 updatePosition()（绝对坐标计算，幂等，不会累加抖动）。
 * 自愈避免厂商 ROM 丢 ACTION_UP 后状态卡死。
 */

package com.yunx.app.overlay.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

class LongPressDragRootLayout(
    context: Context,
    /** 当前这一层窗口的 LayoutParams；窗口还没建好时返回 null */
    private val paramsProvider: () -> WindowManager.LayoutParams?,
    /** 球态 / 双击铺满等临时几何期间禁用，交给各自原有的手势 */
    private val enabled: () -> Boolean,
    /** 位置已写入 params，回调负责 clamp + WindowManager.updateViewLayout */
    private val onMove: (WindowManager.LayoutParams) -> Unit,
    /** 抬手，回调负责持久化几何 */
    private val onEnd: () -> Unit,
    /**
     * v2.5.0：窗口被缩到极小时，长按不再进入拖动，而是交给这个回调「放大回来」。
     *
     * 为什么需要：窗口最小已经放开到 28dp，此时界面里一个按钮都点不到，
     * 只剩长按还能用。返回 true = 已处理，本次手势不再拖动。
     */
    private val onTinyLongPress: (() -> Boolean)? = null,
    /**
     * v2.5.4：按下就能拖，不走 350ms 长按。
     *
     * 只给准星这种「窗口里没有任何可点内容」的层用。准星窗口最小 24dp，
     * 长按 350ms 再拖的实际体验是：手指一按就想挪，稍微一动就被判成「滑」而
     * 取消长按，结果怎么拖都不动 —— 用户原话「准心移动不到小白点那里」。
     */
    private val directDrag: Boolean = false,
    /**
     * v2.7.2：每一轮手势按下时先通知宿主刷新屏幕尺寸。
     *
     * 横屏游戏里进程的 Configuration 可能还是竖屏（App 在后台），屏幕范围会算错，
     * 表现是「拖到屏幕一半就顶住」。拖动开始前刷一次，这一整轮都对。
     */
    private val onGestureStart: (() -> Unit)? = null,
    /**
     * v2.11.2：窗口边缘的「按下即拖」热区宽度（dp），<= 0 关闭。
     *
     * 群友反馈两件事：① 连招弹窗只有顶栏那一条能拖，想挪得先精准戳中；
     * ② 小抄球放在屏幕最右边、点开成窗后，热区跑到屏幕外，整窗拖不动。
     * 加一圈边缘热区后，按住窗口任一条边就能像拖电脑窗口那样挪位置。
     */
    private val edgeDragDp: Float = 0f,
    /** v2.11.2：参与边缘拖动的边，SIDE_LEFT / SIDE_TOP / SIDE_RIGHT / SIDE_BOTTOM 的位或 */
    private val edgeDragSides: Int = SIDE_LEFT or SIDE_RIGHT or SIDE_BOTTOM,
    /**
     * v2.11.2：顶栏高度（dp）。顶栏本身就是拖动热区、右侧还有缩小 / 关闭按钮，
     * 这一段不参与边缘判定，否则顶栏图标会被「边缘拖动」抢走点不到。
     */
    private val edgeDragTopBarDp: Float = 44f
) : FrameLayout(context) {

    companion object {
        const val SIDE_LEFT = 1
        const val SIDE_TOP = 2
        const val SIDE_RIGHT = 4
        const val SIDE_BOTTOM = 8

        private const val LONG_PRESS_MS = 350L
        private const val MAX_DRAG_MS = 15_000L
        private val HIGHLIGHT = 0xFF8B7CF6.toInt()
    }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hintWidth = (2f * density).toInt().coerceAtLeast(2)
    private val hintRadius = 12f * density
    /** 边缘热区宽度，0 = 这一层不开边缘拖动 */
    private val edgeDragPx = if (edgeDragDp > 0f) (edgeDragDp * density).toInt().coerceAtLeast(1) else 0
    private val topBarPx = (edgeDragTopBarDp * density).toInt()
    /** 右下角让给拉伸手柄：34dp 见方内不判边缘 */
    private val resizeCornerPx = (34f * density).toInt()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var pending = false
    /** v2.5.4：长按时间已到、但手指还没动 —— 只「上膛」，不动手 */
    private var armed = false
    private var dragging = false
    private var dragStartedAt = 0L

    private val trigger = Runnable {
        if (!pending || dragging) return@Runnable
        if (!enabled() || paramsProvider() == null) {
            cancelPending()
            return@Runnable
        }
        // 极小窗优先「放大回来」：这时拖动没有意义，用户更可能是想恢复
        if (onTinyLongPress?.invoke() == true) {
            cancelPending()
            return@Runnable
        }
        // v2.5.4：这里只把状态推到「长按已成立」，真正的拖动等 MOVE 越过 slop 再开始。
        // 旧版在定时器里就直接 dragging=true，于是：
        //   · 手指没动、慢慢抬手 → ACTION_UP 被当拖拽结束吞掉，按钮一下都点不响；
        //   · 想点按钮的人按得稍久一点（>350ms）就永远点不到。
        // 现在长按只是「允许拖」，没位移就仍然是一次正常点击。
        armed = true
    }

    private fun beginDrag() {
        if (dragging) return
        pending = false
        armed = false
        removeCallbacks(trigger)
        dragging = true
        dragStartedAt = SystemClock.uptimeMillis()
        showDragHint()
    }

    /** 拖动成立时的视觉 + 触感反馈：描边高亮 + 轻震动 */
    private fun showDragHint() {
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(hintWidth, HIGHLIGHT)
            cornerRadius = hintRadius
        }
        runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }

    /**
     * v2.11.2：命中窗口边缘热区？
     *
     * 规则：
     *   - 顶栏整条排除（那里本来就能拖，且右侧有缩小 / 关闭按钮）；
     *     窗口矮到不足两倍顶栏时不再排除，否则整窗都会落在「顶栏」里、边缘彻底失效。
     *   - 右下角 34dp 让给 ResizeGrip —— 那里按住应该是改尺寸，不是挪窗。
     */
    private fun hitEdge(x: Float, y: Float): Boolean {
        if (edgeDragPx <= 0) return false
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        if (h > topBarPx * 2 && y <= topBarPx) return false
        val band = edgeDragPx.toFloat()
        val onEdge =
            ((edgeDragSides and SIDE_LEFT) != 0 && x <= band) ||
                ((edgeDragSides and SIDE_RIGHT) != 0 && x >= w - band) ||
                ((edgeDragSides and SIDE_TOP) != 0 && y <= band) ||
                ((edgeDragSides and SIDE_BOTTOM) != 0 && y >= h - band)
        if (!onEdge) return false
        if (x >= w - resizeCornerPx && y >= h - resizeCornerPx) return false
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked

        // 自愈 1：新的单指按下，说明上一轮手势早该结束（丢 UP 的 ROM 最常见）
        if (action == MotionEvent.ACTION_DOWN && (dragging || pending || armed)) endDrag()
        // 自愈 2：不让拖动状态无限期吞掉触摸
        if (dragging && SystemClock.uptimeMillis() - dragStartedAt > MAX_DRAG_MS) endDrag()

        if (!enabled()) {
            if (dragging) endDrag()
            return false
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                onGestureStart?.invoke()
                val p = paramsProvider() ?: return false
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = p.x
                startY = p.y
                if (directDrag) {
                    // 准星层：按下即拖，整段手势自己接
                    dragging = true
                    dragStartedAt = SystemClock.uptimeMillis()
                    return true
                }
                // v2.11.2：边缘按下即拖。不进 350ms 长按，也不和内容区的点击 / 滑动抢
                if (hitEdge(ev.x, ev.y)) {
                    cancelPending()
                    dragging = true
                    dragStartedAt = SystemClock.uptimeMillis()
                    showDragHint()
                    return true
                }
                pending = true
                postDelayed(trigger, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    // 拦截发生的这一帧：子 View 还持有 touch target，父的 onTouchEvent
                    // 收不到，所以在这里也更新一次
                    updatePosition(ev)
                    return true
                }
                val moved = abs(ev.rawX - downRawX) > slop || abs(ev.rawY - downRawY) > slop
                if (moved) {
                    if (armed) {
                        // 长按已成立，这一动才算真正开始拖：从这一帧起父布局接管
                        beginDrag()
                        updatePosition(ev)
                        return true
                    }
                    // 用户在点滑杆 / 翻页 / 拖右下角拉伸，不抢事件
                    cancelPending()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指：把多指让给双指缩放 / 图片手势
                if (!dragging) cancelPending()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    endDrag()
                    return true
                }
                // 长按成立但没位移 = 一次按得比较久的点击，原样交给子控件
                cancelPending()
            }
        }
        return false
    }

    /**
     * 绝对坐标计算：startX + (rawX - downRawX)。重复调用结果一致，不会累加抖动。
     * 拦截帧走 onInterceptTouchEvent，之后的每一帧都走这里，两处都要调用。
     */
    private fun updatePosition(ev: MotionEvent) {
        val p = paramsProvider() ?: return
        // rawX/rawY 是屏幕坐标，窗口跟着手指移动时不会像 x/y 那样逐帧漂移
        // v2.7.1：四舍五入而不是截断。截断在负半轴上会持续偏 -1px，
        // 手指慢慢挪时看起来就像「挪了没反应」，越拖越偏。
        p.x = (startX + (ev.rawX - downRawX)).roundToInt()
        p.y = (startY + (ev.rawY - downRawY)).roundToInt()
        onMove(p)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        when (ev.actionMasked) {
            // ViewGroup 拦截成功后不再回调 onInterceptTouchEvent，后续 MOVE 全在这里
            MotionEvent.ACTION_MOVE -> updatePosition(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun cancelPending() {
        pending = false
        armed = false
        removeCallbacks(trigger)
    }

    private fun endDrag() {
        val wasDragging = dragging
        cancelPending()
        dragging = false
        foreground = null
        if (wasDragging) runCatching { onEnd() }
    }

    /** 一键重置 / 窗口销毁时调用，保证不会带着拖动状态重建或残留高亮描边 */
    fun resetTouchState() = endDrag()
}
