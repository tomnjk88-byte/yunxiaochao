/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.16「连招时也能手动操作」的策略单测。
 *
 * 这几个判断看起来简单，但一旦写反，表现分别是：
 *   - userHolding 写反 → 连招永远在让路（点了不动）或永远不让路（你的触摸还是被吃掉）；
 *   - isSelfInjection 写反 → 连招把自己的点击当成用户，自己等自己，永远点不下去；
 *   - shouldRetryAfterCancel 写反 → 你碰一下屏幕，整条连招直接停（v1.9.15 的老毛病）。
 * 都是很难靠肉眼复现的回归，所以钉死在这里。
 */

package com.yunx.app.overlay.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRunPolicyTest {

    // ------------------------------------------------------------------
    // 让路判定
    // ------------------------------------------------------------------

    @Test
    fun `没按过屏幕时不让路`() {
        assertFalse(userHolding(now = 10_000L, lastTouchAt = 0L, holdMs = 800L))
    }

    @Test
    fun `刚按下的 800 毫秒内算还在操作`() {
        assertTrue(userHolding(now = 10_100L, lastTouchAt = 10_000L, holdMs = 800L))
        assertTrue(userHolding(now = 10_799L, lastTouchAt = 10_000L, holdMs = 800L))
    }

    @Test
    fun `超过等待时间就恢复执行`() {
        // 边界取「不小于」：正好 800ms 时认为已经松手，避免多等一帧
        assertFalse(userHolding(now = 10_800L, lastTouchAt = 10_000L, holdMs = 800L))
        assertFalse(userHolding(now = 12_000L, lastTouchAt = 10_000L, holdMs = 800L))
    }

    @Test
    fun `时钟回拨不会把连招永久卡住`() {
        assertFalse(userHolding(now = 9_000L, lastTouchAt = 10_000L, holdMs = 800L))
    }

    // ------------------------------------------------------------------
    // 自己的注入事件要挡掉
    // ------------------------------------------------------------------

    @Test
    fun `注入后 180 毫秒内的事件算自己的`() {
        assertTrue(isSelfInjection(now = 10_050L, lastInjectAt = 10_000L))
        assertTrue(isSelfInjection(now = 10_179L, lastInjectAt = 10_000L))
    }

    @Test
    fun `过了保护窗口就是用户`() {
        assertFalse(isSelfInjection(now = 10_180L, lastInjectAt = 10_000L))
        assertFalse(isSelfInjection(now = 11_000L, lastInjectAt = 0L))
    }

    // ------------------------------------------------------------------
    // 被取消后的重试
    // ------------------------------------------------------------------

    @Test
    fun `前两次被取消还会再试一次`() {
        assertTrue(shouldRetryAfterCancel(1))
        assertTrue(shouldRetryAfterCancel(2))
    }

    @Test
    fun `第三次仍被取消就跳过这一步而不是停掉整条连招`() {
        assertFalse(shouldRetryAfterCancel(3))
        assertFalse(shouldRetryAfterCancel(4))
        assertFalse(shouldRetryAfterCancel(0))
    }

    @Test
    fun `单步最多尝试三次`() {
        assertEquals(3, MAX_DISPATCH_ATTEMPTS)
    }

    // ------------------------------------------------------------------
    // 策略本身
    // ------------------------------------------------------------------

    @Test
    fun `默认就是手动优先 松手 0_8 秒继续`() {
        val policy = MacroRunPolicy()
        assertTrue(policy.manualFirst)
        assertEquals(800L, policy.resumeDelayMs)
    }

    @Test
    fun `三档延迟按顺序排列且都能显示成文案`() {
        val options = MacroRunPolicy.RESUME_OPTIONS
        assertEquals(3, options.size)
        assertEquals(options.sorted(), options)
        assertEquals("0.4 秒", MacroRunPolicy.label(400L))
        assertEquals("0.8 秒", MacroRunPolicy.label(800L))
        assertEquals("1.6 秒", MacroRunPolicy.label(1600L))
    }

    @Test
    fun `关掉手动优先时策略明确表示不让路`() {
        val policy = MacroRunPolicy(manualFirst = false, resumeDelayMs = 0L)
        assertFalse(policy.manualFirst)
        // 关掉之后 resumeDelay 不参与判定，这里只是确认它不会被强行改回默认值
        assertEquals(0L, policy.resumeDelayMs)
    }
}
