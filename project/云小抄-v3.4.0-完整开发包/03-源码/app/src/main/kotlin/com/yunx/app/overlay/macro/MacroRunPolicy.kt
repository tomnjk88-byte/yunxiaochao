/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.16「连招时也能手动操作」的运行策略。
 *
 * v2.5.6 多指并行（用户核心诉求）：
 *   「我要边滑动屏幕操控角色行走，边触发连招；连招是辅助，触发时我还能移动。」
 *   旧版只有两态 —— 让路（手一碰屏幕连招就停，等你松手）和 连招优先（一路点到底）。
 *   让路在「按住摇杆走位」这种持续触摸场景下会永久饿死连招（还会指数退让到 4 秒），
 *   表现就是「触发了连招却没反应 / 我这边也别扭」。这不是错觉，是策略选错了。
 *
 *   现在有三态：
 *     PARALLEL 多指并行（默认）：连招不看你手指，按自己的节奏用「极短脉冲点击」注入，
 *       每次注入是一根独立的手指，按下即抬起（约 24ms），不长期占用触摸管线；
 *       被系统取消时用很短的固定间隔重试，不指数退让、不等你松手。你的摇杆手指
 *       与注入的技能点击在系统里是两根手指 —— 这就是多指触控能做到的形态。
 *     YIELD   手动让路（旧默认）：手一碰屏幕连招停，松手后接着跑。适合挂机打桩。
 *     PRIORITY 连招优先：10 步打成一包，最快，期间你的点击可能被系统取消。
 *
 * 系统层限制（必须如实说明）：厂商游戏空间的「自动连招」是系统签名级注入，
 * 第三方 App 拿不到。这里用 Android 标准的无障碍 dispatchGesture / root input 注入，
 * 已经是非系统应用能做到的最接近多指的方式；个别 ROM 对注入手势仍会互相取消，
 * 有 root 时改用 input 注入（天然独立 pointer）效果更好，见 MacroEngine/RootShell。
 *
 * 逻辑里跟触摸判定相关的部分保持纯函数，方便 JVM 单测（见 MacroRunPolicyTest）。
 */

package com.yunx.app.overlay.macro

/**
 * 一次执行使用的「运行姿态」。只认 key 不认序数，以后插新档不会让老用户设置错位。
 */
enum class MacroRunMode(val key: String, val label: String, val hint: String) {
    PARALLEL(
        "parallel",
        "多指并行",
        "边走边放：连招像第三根手指，短促点击、不等你松手；你滑动摇杆时它照常放技能"
    ),
    YIELD(
        "yield",
        "手动让路",
        "手指一碰屏幕连招先停，松手后从当前这步接着跑（挂机打桩最稳）"
    ),
    PRIORITY(
        "priority",
        "连招优先",
        "10 步打成一包一路点到底，速度最快，期间你的点击可能被打断"
    );

    companion object {
        fun fromKey(raw: String?): MacroRunMode =
            values().firstOrNull { it.key == raw } ?: PARALLEL
    }
}

/**
 * 一次执行使用的策略。
 *
 * @param manualFirst   true = 让路（YIELD）。保留字段以兼容旧代码与 JVM 单测。
 * @param parallel      true = 多指并行（PARALLEL）。优先级高于 manualFirst。
 * @param resumeDelayMs 让路模式：手指离开后等多久继续。
 * @param pulseMs       并行模式：单点点击注入的脉冲时长。越短越不容易与你的手指抢管线；
 *                      太短（<16ms）会被部分 ROM 直接丢事件，24ms 是实测较稳的下限。
 */
data class MacroRunPolicy(
    val manualFirst: Boolean = true,
    val resumeDelayMs: Long = DEFAULT_RESUME_DELAY,
    val parallel: Boolean = false,
    val pulseMs: Long = PARALLEL_PULSE_MS
) {
    companion object {
        /** 默认 0.8 秒：轻点一下不会把连招卡太久，普通拖拽也基本能盖住 */
        const val DEFAULT_RESUME_DELAY = 800L

        /** 「松手后多久继续」三档，UI 与偏好里都用它 */
        val RESUME_OPTIONS = listOf(400L, 800L, 1600L)

        /** 并行模式的点击脉冲：24ms，够系统识别为一次完整 down/up，又足够短 */
        const val PARALLEL_PULSE_MS = 24L

        /** 并行模式被取消后的固定重试间隔，绝不指数退让 */
        const val PARALLEL_RETRY_MS = 30L

        fun label(ms: Long): String = when (ms) {
            in 0..500 -> "0.4 秒"
            in 501..1100 -> "0.8 秒"
            else -> "1.6 秒"
        }
    }
}

/**
 * 用户手指是否还「占着」屏幕。
 *
 * 我们没有 UP 事件（FLAG_WATCH_OUTSIDE_TOUCH 只在按下时给一次 ACTION_OUTSIDE），
 * 所以用「最后一次按下发生在 holdMs 之内」近似判断。holdMs 就是 [MacroRunPolicy.resumeDelayMs]。
 * 注意：仅在「让路」模式会用到它；并行模式根本不看用户手指。
 */
internal fun userHolding(now: Long, lastTouchAt: Long, holdMs: Long): Boolean =
    lastTouchAt > 0L && now >= lastTouchAt && now - lastTouchAt < holdMs

/**
 * 连招自己注入的点击同样会冒出一个 ACTION_OUTSIDE（注入事件走的是同一条触摸管线），
 * 不挡掉的话连招会把自己当成用户，一路给自己让路，永远点不下去。
 */
internal fun isSelfInjection(now: Long, lastInjectAt: Long, guardMs: Long = SELF_GUARD_MS): Boolean =
    lastInjectAt > 0L && now >= lastInjectAt && now - lastInjectAt < guardMs

/** 注入后多久之内的触摸算「自己人」 */
internal const val SELF_GUARD_MS = 180L

/**
 * 手势被取消后要不要重试同一步。并行模式下也用它，只是重试间隔更短、且不等待用户。
 */
internal fun shouldRetryAfterCancel(attempt: Int, maxAttempts: Int = MAX_DISPATCH_ATTEMPTS): Boolean =
    attempt in 1 until maxAttempts

/** 单步最多尝试几次 */
internal const val MAX_DISPATCH_ATTEMPTS = 3
