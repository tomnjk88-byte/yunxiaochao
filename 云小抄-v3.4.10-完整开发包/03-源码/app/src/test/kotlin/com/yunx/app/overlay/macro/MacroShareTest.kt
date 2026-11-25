/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.15 连招分享的坐标适配 / 去重 / 改名逻辑单测。
 *
 * 这里只测纯函数，不碰 org.json 与 Android API（JVM 单测里它们是 stub）：
 *   - fitMacro：同向按比例、反向等比居中、越界点必须收进屏幕；
 *   - merge：动作相同跳过、重名加序号、导入后重新置顶；
 *   - uniqueName / sameActions / describe。
 * 这几条一旦回归，表现是「导入的连招点到屏幕外」或「重复导入堆一屏」，都很难靠肉眼发现。
 */

package com.yunx.app.overlay.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroShareTest {

    private fun tapMacro(name: String, x: Float, y: Float): MacroConfig = MacroConfig(
        name = name,
        steps = listOf(MacroStep.tap(x, y)),
        intervalMs = 200L,
        repeat = 0
    )

    private fun payload(
        macros: List<MacroConfig>,
        width: Int = 1080,
        height: Int = 2400
    ) = MacroSharePayload(macros, MacroShareDevice(width, height, 2.75f), exportedAt = 1L)

    // ------------------------------------------------------------------
    // 坐标适配
    // ------------------------------------------------------------------

    @Test
    fun `same_screen_keeps_points`() {
        val src = tapMacro("连招 1", 540f, 1200f)
        val fit = MacroShare.fitMacro(src, MacroShareDevice(1080, 2400), 1080, 2400)
        val point = fit.macro.steps.first().start

        assertEquals(540f, point.x, 0.01f)
        assertEquals(1200f, point.y, 0.01f)
        assertFalse(fit.scaled)
        assertFalse(fit.rotated)
    }

    @Test
    fun `smaller_screen_scales_x_and_y_separately`() {
        val src = tapMacro("连招 1", 1080f, 2400f)
        val fit = MacroShare.fitMacro(src, MacroShareDevice(1080, 2400), 720, 1600)
        val point = fit.macro.steps.first().start

        // 右下角仍然落在右下角；像素坐标最大是 宽-1 / 高-1（和 MotionEvent 的取值范围一致）
        assertEquals(719f, point.x, 0.01f)
        assertEquals(1599f, point.y, 0.01f)
        assertEquals(2f / 3f, fit.scaleX, 0.001f)
        assertEquals(2f / 3f, fit.scaleY, 0.001f)
        assertTrue(fit.scaled)
    }

    @Test
    fun `points_outside_screen_are_clamped`() {
        val src = tapMacro("越界", 5000f, 9000f)
        val fit = MacroShare.fitMacro(src, MacroShareDevice(1080, 2400), 1080, 2400)
        val point = fit.macro.steps.first().start

        assertEquals(1079f, point.x, 0.01f)
        assertEquals(2399f, point.y, 0.01f)
    }

    @Test
    fun `rotated_screen_keeps_every_point_inside`() {
        // 对方横屏录的，我是竖屏：这时不能按比例硬折，否则 y 会飞出屏幕
        val src = tapMacro("横屏连招", 2000f, 900f)
        val fit = MacroShare.fitMacro(src, MacroShareDevice(2400, 1080), 1080, 2400)

        assertTrue(fit.rotated)
        val point = fit.macro.steps.first().start
        assertTrue("x=${point.x}", point.x in 0f..1079f)
        assertTrue("y=${point.y}", point.y in 0f..2399f)
        // 等比缩放 + 居中：短边刚好铺满宽度
        assertEquals(1080f / 2400f, fit.scaleX, 0.001f)
    }

    @Test
    fun `invalid_source_size_does_not_rewrite_points`() {
        val src = tapMacro("未知来源", 300f, 400f)
        val fit = MacroShare.fitMacro(src, MacroShareDevice(0, 0), 1080, 2400)
        val point = fit.macro.steps.first().start

        assertEquals(300f, point.x, 0.01f)
        assertEquals(400f, point.y, 0.01f)
        assertFalse(fit.scaled)
    }

    // ------------------------------------------------------------------
    // 合并 / 去重 / 改名
    // ------------------------------------------------------------------

    @Test
    fun `same_actions_are_deduplicated`() {
        val existing = listOf(tapMacro("连招 1", 540f, 1200f))
        val result = MacroShare.merge(existing, payload(existing), 1080, 2400)

        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertTrue(result.add.isEmpty())
    }

    @Test
    fun `different_actions_are_imported_with_new_id`() {
        val existing = listOf(tapMacro("连招 1", 540f, 1200f))
        val incoming = listOf(tapMacro("连招 1", 200f, 300f))
        val result = MacroShare.merge(existing, payload(incoming), 1080, 2400)

        assertEquals(1, result.added)
        val added = result.add.first()
        assertNotEquals(existing.first().id, added.id)
        // 重名自动加序号，不覆盖原来的
        assertEquals("连招 1 (2)", added.name)
        // 导入后直接成为屏幕上的小按钮，位置交给 MacroBall 自动排
        assertTrue(added.pinned)
        assertTrue(added.enabled)
        assertEquals(-1, added.ballX)
        assertEquals(-1, added.ballY)
        assertNull(added.iconImagePath)
    }

    @Test
    fun `duplicate_inside_one_package_is_skipped`() {
        val a = tapMacro("同一条", 100f, 200f)
        val b = tapMacro("同一条 副本", 100f, 200f)
        val result = MacroShare.merge(emptyList(), payload(listOf(a, b)), 1080, 2400)

        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `duplicate_check_ignores_name_and_tolerance_under_one_pixel`() {
        val a = tapMacro("A", 100f, 200f)
        val b = tapMacro("B", 100.4f, 200.4f)
        assertTrue(MacroShare.sameActions(a, b))

        val c = tapMacro("C", 104f, 200f)
        assertFalse(MacroShare.sameActions(a, c))
    }

    @Test
    fun `unique_name_adds_incrementing_suffix`() {
        assertEquals("连招 1", MacroShare.uniqueName("连招 1", emptySet()))
        assertEquals("连招 1 (2)", MacroShare.uniqueName("连招 1", setOf("连招 1")))
        assertEquals(
            "连招 1 (3)",
            MacroShare.uniqueName("连招 1", setOf("连招 1", "连招 1 (2)"))
        )
        assertEquals("导入的连招", MacroShare.uniqueName("   ", emptySet()))
    }

    @Test
    fun `describe_mentions_skip_and_scale`() {
        val text = MacroShare.describe(
            MacroMergeResult(
                add = listOf(tapMacro("a", 1f, 2f)),
                skipped = 2,
                scaled = true,
                rotated = false
            )
        )
        assertTrue(text.contains("已导入 1 条"))
        assertTrue(text.contains("跳过 2 条"))
        assertTrue(text.contains("缩放"))
    }

    @Test
    fun `describe_handles_nothing_new`() {
        val text = MacroShare.describe(MacroMergeResult(emptyList(), 3, false, false))
        assertTrue(text.contains("已经导入过"))
    }

    // ------------------------------------------------------------------
    // 口令识别
    // ------------------------------------------------------------------

    @Test
    fun `looks_like_share_rejects_plain_chat`() {
        assertFalse(MacroShare.looksLikeShare("今晚打本吗"))
        assertFalse(MacroShare.looksLikeShare(""))
        assertFalse(MacroShare.looksLikeShare(null))
    }

    @Test
    fun `looks_like_share_accepts_code_prefix`() {
        assertTrue(MacroShare.looksLikeShare("YXM1:" + "A".repeat(64)))
    }
}
