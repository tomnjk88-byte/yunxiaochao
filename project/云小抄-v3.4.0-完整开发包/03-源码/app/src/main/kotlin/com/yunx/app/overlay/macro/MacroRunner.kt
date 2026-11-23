/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。把 MacroStep 序列翻译成手势并循环回放。
 * 支持两种后端：无障碍 dispatchGesture（默认，频率高）与 root 的 input 注入（可绕开
 * Android 13+ 受限设置 / 屏蔽无障碍的游戏）。
 *
 * v1.9.16 两条重要修正（都是「运行时手没法操作」的根因）：
 *   1. 系统会取消正在执行的手势（官方文档明写：用户、本服务、其他服务的手势都会被取消）。
 *      旧版把 onCancelled 当成致命错误，你一碰屏幕整条连招就停了；现在改成「重试这一步，
 *      几次还不行就跳过」，连招不会因为你的手指而中断。
 *   2. 新增「让路」：执行前先看 UserTouchWatcher 有没有人在按屏幕，有就等，松手后再继续。
 *      同时把「10 步打成一包」改成「一步一发」—— 一包手势会连续占住触摸管线几百毫秒，
 *      那段时间里你的任何操作都会被系统取消；拆成单步，中间就有缝可以插进来。
 */

package com.yunx.app.overlay.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MacroRunner(
    private val context: Context,
    private val onRunningChange: (String?) -> Unit,
    /** v1.9.1「点击显示」：每执行一步，把它的起点交给宿主画一个圆点 */
    private val onTap: (Float, Float) -> Unit = { _, _ -> },
    /** v1.9.16：是否正为了让你操作而暂停（悬浮球会变琥珀色） */
    private val onYieldChange: (Boolean) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * 每次执行发一个令牌。旧任务被 cancel 后它的 finally 可能比新任务的
     * `onRunningChange(id)` 更晚到达主线程，若不加判断就会把新任务的「运行中」清掉，
     * 悬浮球会显示成没在跑。
     */
    private var currentToken: Any? = null

    /**
     * v1.9.16 自适应让路：你的手指一直按着时，固定等 0.8 秒会变成「每 0.8 秒插一次」，
     * 每次都把你的长按打断。所以每被取消一次，等待时间翻倍（上限 4 秒），
     * 成功点出去一次再归零。表现就是：你只是轻点，连招很快接上；你一直按着，它越退越远。
     */
    private var adaptiveHoldMs = 0L

    private enum class DispatchResult { DONE, CANCELLED, UNAVAILABLE }

    fun start(
        macro: MacroConfig,
        backend: Backend = Backend.ACCESSIBILITY,
        policy: MacroRunPolicy = MacroRunPolicy()
    ) {
        stop()
        if (macro.steps.isEmpty() || backend == Backend.NONE) return
        adaptiveHoldMs = 0L
        val token = Any()
        currentToken = token
        job = scope.launch {
            onRunningChange(macro.id)
            // v2.5.6 多指并行：探针只在「让路」模式挂。并行模式根本不看用户手指，
            // 挂一个 1px 的 FLAG_WATCH_OUTSIDE_TOUCH 窗口既没用，还可能在某些 ROM 上
            // 多引入一层触摸竞争 —— 干脆不挂，让屏幕触摸管线只有「你的手指 + 注入点击」。
            if (policy.manualFirst && !policy.parallel) UserTouchWatcher.start(context)
            try {
                var round = 0
                while (currentCoroutineContext().isActive) {
                    if (!runOnce(macro.steps, backend, policy)) break
                    round++
                    if (macro.repeat in 1..round) break
                    delay(macro.intervalMs.coerceAtLeast(1L))
                }
            } finally {
                // 只有「还是这一次执行」才收尾：旧任务被 cancel 后它的 finally 可能
                // 晚于新任务到达，这时收尾会把新任务刚挂上的探针和运行状态一起清掉
                if (currentToken === token) {
                    UserTouchWatcher.stop()
                    onYieldChange(false)
                    currentToken = null
                    onRunningChange(null)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        currentToken = null
        // 被 cancel 的那个 job 的 finally 会因为令牌对不上而不做收尾，这里得自己把探针摘掉
        UserTouchWatcher.stop()
        onRunningChange(null)
        onYieldChange(false)
    }

    fun shutdown() {
        stop()
        UserTouchWatcher.stop()
        scope.cancel()
    }

    private suspend fun runOnce(
        steps: List<MacroStep>,
        backend: Backend,
        policy: MacroRunPolicy
    ): Boolean = when (backend) {
        Backend.ROOT -> runRootSteps(steps, policy)
        // v3.2.0：Shizuku 与 Root 同一套「逐步下发」逻辑 —— 都是 shell 注入，
        // 都是独立 pointer，不会取消用户按着的摇杆。
        Backend.SHIZUKU -> runShizukuSteps(steps, policy)
        Backend.ACCESSIBILITY -> when {
            // v2.5.6 多指并行：优先级最高，不等待用户、短脉冲注入
            policy.parallel -> dispatchParallel(steps, policy)
            policy.manualFirst -> dispatchStepwise(steps, policy)
            else -> dispatchBatched(steps)
        }
        Backend.NONE -> false
    }

    /**
     * v2.5.6 多指并行的核心：连招是「辅助手指」，跟你正在滑动的摇杆手指并存。
     *
     * 与让路模式（dispatchStepwise）的三点关键差异：
     *   1. 完全不调用 awaitUserIdle —— 你按住摇杆走位时连招照跑，不会被饿死；
     *   2. 每一步都用 [MacroRunPolicy.pulseMs] 的极短脉冲点击注入，按下即抬，
     *      注入事件尽可能快地离开触摸管线，减少系统「取消你手指手势」的窗口；
     *   3. 被系统取消时用固定的 30ms 重试，不指数退让（退让到 4 秒正是旧版
     *      「连招触发却没反应」的根因），也不看 UserTouchWatcher。
     *
     * 一步一发而不是打成一包：一包手势会连续占住触摸管线几百毫秒，那段时间你的
     * 任何走位都会被系统取消；单步之间留出缝隙，你的手指才插得进来。
     */
    private suspend fun dispatchParallel(steps: List<MacroStep>, policy: MacroRunPolicy): Boolean {
        for (step in steps) {
            if (!currentCoroutineContext().isActive) return false
            if (step.gapBeforeMs > 0) delay(step.gapBeforeMs)
            onTap(step.start.x, step.start.y)
            if (!dispatchOneParallel(step, policy)) return false
        }
        return true
    }

    /** 单步短脉冲下发 + 被打断快速重试。返回 false 只在「无障碍服务没了」时发生。 */
    private suspend fun dispatchOneParallel(step: MacroStep, policy: MacroRunPolicy): Boolean {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val gesture = buildGesture(listOf(step), pulseMs = policy.pulseMs) ?: return true
            UserTouchWatcher.markInjected()
            when (awaitDispatch(gesture)) {
                DispatchResult.DONE -> return true
                DispatchResult.UNAVAILABLE -> return false
                DispatchResult.CANCELLED -> {
                    // 屏幕上有别人（你的手指 / 别的注入）。并行模式下不退让、不等松手，
                    // 只隔一个很短的固定间隔重试这一步；几次还不行就跳过继续下一轮。
                    attempt++
                    if (!shouldRetryAfterCancel(attempt)) return true
                    delay(MacroRunPolicy.PARALLEL_RETRY_MS)
                }
            }
        }
        return false
    }

    /**
     * 让路模式：一步一发。
     * 每一步都是独立的一次 dispatchGesture，注入完立刻空出管线；
     * 发之前先等用户松手，所以你的点击不会被连招"截胡"。
     */
    private suspend fun dispatchStepwise(steps: List<MacroStep>, policy: MacroRunPolicy): Boolean {
        for (step in steps) {
            if (!currentCoroutineContext().isActive) return false
            if (step.gapBeforeMs > 0) delay(step.gapBeforeMs)
            if (!awaitUserIdle(policy)) return false
            onTap(step.start.x, step.start.y)
            if (!dispatchOne(step, policy)) return false
        }
        return true
    }

    /** 连招优先（老行为）：10 步打成一包，速度最快，但那几百毫秒里屏幕是连招的 */
    private suspend fun dispatchBatched(steps: List<MacroStep>): Boolean {
        for (batch in steps.chunked(MAX_STROKES)) {
            if (!currentCoroutineContext().isActive) return false
            batch.forEach { onTap(it.start.x, it.start.y) }
            val gesture = buildGesture(batch) ?: return false
            UserTouchWatcher.markInjected()
            when (awaitDispatch(gesture)) {
                DispatchResult.DONE, DispatchResult.CANCELLED -> Unit
                DispatchResult.UNAVAILABLE -> return false
            }
        }
        return true
    }

    /**
     * 单步下发 + 被打断重试。
     * 返回 false 只在「无障碍服务没了」时发生；用户抢手只会让这一步重试或跳过，
     * 不会再把整条连招判死。
     */
    private suspend fun dispatchOne(step: MacroStep, policy: MacroRunPolicy): Boolean {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val gesture = buildGesture(listOf(step)) ?: return true
            UserTouchWatcher.markInjected()
            when (awaitDispatch(gesture)) {
                DispatchResult.DONE -> {
                    adaptiveHoldMs = 0L
                    return true
                }
                DispatchResult.UNAVAILABLE -> return false
                DispatchResult.CANCELLED -> {
                    // 自己的手势被取消 = 屏幕上有别人，退一步再来
                    UserTouchWatcher.noteInterrupted()
                    adaptiveHoldMs = (if (adaptiveHoldMs <= 0L) policy.resumeDelayMs else adaptiveHoldMs * 2)
                        .coerceAtMost(MAX_ADAPTIVE_HOLD_MS)
                    attempt++
                    if (!shouldRetryAfterCancel(attempt)) return true
                    delay(RETRY_DELAY_MS)
                    if (!awaitUserIdle(policy)) return false
                }
            }
        }
        return false
    }

    /**
     * root 路径：逐步下发，每条命令都等 shell 执行完（RootShell.execAndWait 里用哨兵同步）。
     * 不等待会积压命令，点「停止」后还会继续点，这点必须保证。
     */
    private suspend fun runRootSteps(steps: List<MacroStep>, policy: MacroRunPolicy): Boolean {
        for (step in steps) {
            if (!currentCoroutineContext().isActive) return false
            if (step.gapBeforeMs > 0) delay(step.gapBeforeMs)
            if (!awaitUserIdle(policy)) return false
            onTap(step.start.x, step.start.y)
            UserTouchWatcher.markInjected()
            // v2.5.6 并行模式：root 注入本来就是独立 pointer（最接近真多指），
            // 再把点击压成短脉冲，让它更快离开输入管线，进一步减少与你手指的竞争。
            val ok = withContext(Dispatchers.IO) {
                injectRootStep(step, if (policy.parallel) policy.pulseMs else 0L)
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * Shizuku 路径：与 root 完全同构，只是命令走 [ShizukuShell]。
     * 每次注入都是一次 `input tap`，在系统里是独立 pointer —— 你的摇杆手指不受影响。
     */
    private suspend fun runShizukuSteps(steps: List<MacroStep>, policy: MacroRunPolicy): Boolean {
        for (step in steps) {
            if (!currentCoroutineContext().isActive) return false
            if (step.gapBeforeMs > 0) delay(step.gapBeforeMs)
            if (!awaitUserIdle(policy)) return false
            onTap(step.start.x, step.start.y)
            UserTouchWatcher.markInjected()
            val ok = withContext(Dispatchers.IO) {
                injectShizukuStep(step, if (policy.parallel) policy.pulseMs else 0L)
            }
            if (!ok) return false
        }
        return true
    }

    private fun injectShizukuStep(step: MacroStep, pulseMs: Long = 0L): Boolean {
        if (!ShizukuShell.granted) return false
        if (step.isTap) {
            val dur = if (pulseMs > 0L && step.durationMs < HOLD_KEEP_MS) {
                pulseMs.coerceAtMost(step.durationMs)
            } else {
                step.durationMs
            }
            return ShizukuShell.tap(step.start.x, step.start.y, dur)
        }
        if (ShizukuShell.motionSupported && step.points.size > 1) {
            val pts = step.points
            if (!ShizukuShell.motion("DOWN", pts.first().x, pts.first().y)) return false
            for (i in 1 until pts.size - 1) {
                if (!ShizukuShell.motion("MOVE", pts[i].x, pts[i].y)) return false
            }
            val last = pts.last()
            return ShizukuShell.motion("UP", last.x, last.y)
        }
        return ShizukuShell.swipe(
            step.start.x, step.start.y,
            step.end.x, step.end.y,
            step.durationMs
        )
    }

    /**
     * 等你松手。只要探针判定「还在操作」，就每 30ms 看一次；
     * 期间把 onYieldChange 置真，悬浮球变琥珀色，面板上也会显示「正在等你操作」。
     */
    private suspend fun awaitUserIdle(policy: MacroRunPolicy): Boolean {
        // v2.5.6：并行模式绝不让路 —— 双重保险（Controller 也会把 manualFirst 置 false）
        if (policy.parallel || !policy.manualFirst) return currentCoroutineContext().isActive
        val hold = maxOf(policy.resumeDelayMs, adaptiveHoldMs)
        var yielded = false
        while (currentCoroutineContext().isActive && UserTouchWatcher.isBusy(hold)) {
            if (!yielded) {
                yielded = true
                onYieldChange(true)
            }
            delay(YIELD_POLL_MS)
        }
        if (yielded) onYieldChange(false)
        return currentCoroutineContext().isActive
    }

    private fun injectRootStep(step: MacroStep, pulseMs: Long = 0L): Boolean {
        if (!RootShell.granted) return false
        if (step.isTap) {
            // 并行模式把**短点**压到脉冲值；滑动与长按都保持原时长
            // （压短短按是为了不和用户手指抢指针；压长按会让蓄力 / 技能读条直接失效）
            val dur = if (pulseMs > 0L && step.durationMs < HOLD_KEEP_MS) {
                pulseMs.coerceAtMost(step.durationMs)
            } else {
                step.durationMs
            }
            return RootShell.tap(step.start.x, step.start.y, dur)
        }
        // 多点轨迹：Android 12+ 用 motionevent 逐点回放，更贴近录制时的真实手势
        if (RootShell.motionSupported && step.points.size > 1) {
            val pts = step.points
            if (!RootShell.motion("DOWN", pts.first().x, pts.first().y)) return false
            for (i in 1 until pts.size - 1) {
                if (!RootShell.motion("MOVE", pts[i].x, pts[i].y)) return false
            }
            val last = pts.last()
            return RootShell.motion("UP", last.x, last.y)
        }
        // 老系统退化成首尾两点直线
        return RootShell.swipe(
            step.start.x, step.start.y,
            step.end.x, step.end.y,
            step.durationMs
        )
    }

    private fun buildGesture(steps: List<MacroStep>, pulseMs: Long = 0L): GestureDescription? {
        val builder = GestureDescription.Builder()
        var cursor = 0L
        var count = 0
        for (step in steps) {
            val first = step.start
            val path = Path().apply {
                moveTo(first.x, first.y)
                if (step.points.size <= 1) {
                    // 只有一个点时必须补一段极短线段，否则系统认为 Path 是空的
                    lineTo(first.x + 0.5f, first.y + 0.5f)
                } else {
                    step.points.drop(1).forEach { lineTo(it.x, it.y) }
                }
            }
            // v2.5.6 并行模式：单点**短按**压成脉冲；多点轨迹（滑动）与长按保持原时长。
            // v2.5.7：录制台能把「按住 300ms 蓄力」原样录下来，这里不能再压成 20ms。
            val duration = if (pulseMs > 0L && step.points.size <= 1 && step.durationMs < HOLD_KEEP_MS) {
                pulseMs.coerceIn(1L, MAX_STEP_DURATION)
            } else {
                step.durationMs.coerceIn(1L, MAX_STEP_DURATION)
            }
            val startAt = cursor + step.gapBeforeMs
            builder.addStroke(
                GestureDescription.StrokeDescription(path, startAt, duration)
            )
            cursor = startAt + duration
            count++
        }
        if (count == 0) return null
        return runCatching { builder.build() }.getOrNull()
    }

    private suspend fun awaitDispatch(gesture: GestureDescription): DispatchResult =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(DispatchResult.DONE)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(DispatchResult.CANCELLED)
                }
            }
            val sent = MacroAccessibilityService.dispatch(gesture, callback)
            if (!sent && cont.isActive) cont.resume(DispatchResult.UNAVAILABLE)
        }

    private companion object {
        /** Android 对一个 GestureDescription 里 Stroke 数量的硬上限 */
        const val MAX_STROKES = 10
        const val MAX_STEP_DURATION = 10_000L

        /** 超过这个时长就算「长按」，并行模式也不再压缩，否则蓄力类技能全废 */
        const val HOLD_KEEP_MS = 180L

        /** 被取消后隔多久重试：太短会一直撞在用户手指上 */
        const val RETRY_DELAY_MS = 70L

        /** 等用户松手时的轮询间隔 */
        const val YIELD_POLL_MS = 30L

        /** 自适应让路的上限：再久也要试着接上，否则连招看起来像停了 */
        const val MAX_ADAPTIVE_HOLD_MS = 4_000L
    }
}
