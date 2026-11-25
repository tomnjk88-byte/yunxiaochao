/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.19 开关拖动落点的纯函数测试。
 */

package com.yunx.app.overlay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchDragTest {

    @Test
    fun `轻点永远取反`() {
        assertTrue(switchTarget(startChecked = false, moved = false, position = 0f, speedPxPerSec = 0f))
        assertFalse(switchTarget(startChecked = true, moved = false, position = 1f, speedPxPerSec = 0f))
        // 点的时候手指可能落在轨道右侧，但没拖动，仍按「取反」算
        assertTrue(switchTarget(startChecked = false, moved = false, position = 0.9f, speedPxPerSec = 0f))
        assertFalse(switchTarget(startChecked = true, moved = false, position = 0.1f, speedPxPerSec = 0f))
    }

    @Test
    fun `位置不过半按位置算`() {
        assertFalse(switchTarget(false, moved = true, position = 0.49f, speedPxPerSec = 0f))
        assertFalse(switchTarget(true, moved = true, position = 0.49f, speedPxPerSec = 0f))
    }

    @Test
    fun `正好一半算开`() {
        assertTrue(switchTarget(false, moved = true, position = 0.5f, speedPxPerSec = 0f))
        assertTrue(switchTarget(true, moved = true, position = 0.5f, speedPxPerSec = 0f))
    }

    @Test
    fun `往右甩一下 位置不够也开`() {
        assertTrue(
            switchTarget(
                startChecked = false,
                moved = true,
                position = 0.28f,
                speedPxPerSec = SWITCH_FLING_PX_PER_SEC + 1f
            )
        )
    }

    @Test
    fun `往左甩一下 位置过了也关`() {
        assertFalse(
            switchTarget(
                startChecked = true,
                moved = true,
                position = 0.72f,
                speedPxPerSec = -SWITCH_FLING_PX_PER_SEC - 1f
            )
        )
    }

    @Test
    fun `速度刚好到阈值不算甩`() {
        // 阈值是「大于」才认；刚好等于时按位置走，这里位置 0.3 → 关
        assertFalse(
            switchTarget(
                startChecked = false,
                moved = true,
                position = 0.3f,
                speedPxPerSec = SWITCH_FLING_PX_PER_SEC
            )
        )
    }

    @Test
    fun `慢拖以位置为准`() {
        assertTrue(switchTarget(false, true, 0.8f, 120f))
        assertFalse(switchTarget(true, true, 0.2f, -120f))
    }

    @Test
    fun `反向甩速盖过位置`() {
        // 已经拖到 0.9，但最后一刻往左甩 —— 用户改主意了，应该关
        assertFalse(switchTarget(false, true, 0.9f, -900f))
    }
}
