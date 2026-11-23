/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.17 左右两侧抽屉的换算 / 吸附规则单测（JVM，不依赖 Android）。
 * 这两条规则最容易写反 —— 右抽屉的 dx 是反的，甩速也是反的，所以逐条钉死。
 */

package com.yunx.app.overlay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerSideTest {

    private val panel = 300f

    @Test
    fun `左边往右拖是拉开 右边往左拖才是拉开`() {
        assertEquals(60f, drawerPullDelta(DrawerSide.LEFT, 60f), 0.001f)
        assertEquals(60f, drawerPullDelta(DrawerSide.RIGHT, -60f), 0.001f)
    }

    @Test
    fun `反方向的拖动得到负增量 会被 clamp 掉`() {
        assertEquals(-60f, drawerPullDelta(DrawerSide.LEFT, -60f), 0.001f)
        assertEquals(-60f, drawerPullDelta(DrawerSide.RIGHT, 60f), 0.001f)
    }

    @Test
    fun `两边的符号正好相反`() {
        assertEquals(1f, DrawerSide.LEFT.pullSign, 0.001f)
        assertEquals(-1f, DrawerSide.RIGHT.pullSign, 0.001f)
    }

    @Test
    fun `拉过三分之一就吸附展开`() {
        assertEquals(panel, drawerSettleTarget(0f, panel * 0.36f, panel), 0.001f)
        assertEquals(0f, drawerSettleTarget(0f, panel * 0.34f, panel), 0.001f)
    }

    @Test
    fun `往开的方向甩 没过三分之一也展开`() {
        assertEquals(panel, drawerSettleTarget(1200f, panel * 0.1f, panel), 0.001f)
    }

    @Test
    fun `往关的方向甩 哪怕拉出很多也收起`() {
        assertEquals(0f, drawerSettleTarget(-1200f, panel * 0.9f, panel), 0.001f)
    }

    @Test
    fun `慢速拖动只看比例`() {
        assertEquals(panel, drawerSettleTarget(899f, panel * 0.9f, panel), 0.001f)
        assertEquals(0f, drawerSettleTarget(-899f, panel * 0.1f, panel), 0.001f)
    }

    @Test
    fun `关到底和开到顶都不会越界`() {
        assertEquals(0f, drawerSettleTarget(0f, 0f, panel), 0.001f)
        assertEquals(panel, drawerSettleTarget(0f, panel, panel), 0.001f)
    }

    @Test
    fun `右边甩开的速度换成拉开为正 才能命中展开`() {
        // 右边缘往左甩，原始速度是负的；乘 pullSign 之后变成正，才会吸附到展开
        val raw = -1500f
        val pull = raw * DrawerSide.RIGHT.pullSign
        assertEquals(panel, drawerSettleTarget(pull, 0f, panel), 0.001f)
    }

    @Test
    fun `key 稳定 不会因为改文案错位`() {
        assertEquals("left", DrawerSide.LEFT.key)
        assertEquals("right", DrawerSide.RIGHT.key)
    }
}
