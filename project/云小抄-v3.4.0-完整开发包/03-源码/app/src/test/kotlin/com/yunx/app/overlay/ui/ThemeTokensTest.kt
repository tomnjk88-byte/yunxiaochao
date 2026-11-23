/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.14 日夜双主题的纯逻辑单测（JVM，不依赖 Android）：
 *   1. 五套风格必须都能给出亮 / 暗两套 tokens，且两套真的不一样；
 *   2. 「跟随系统 / 白天 / 黑夜」的解析结果；
 *   3. 正文与背景、副文字与背景的对比度下限（WCAG）；
 *   4. 抽屉底色必须够不透明，否则拉开抽屉文字会跟背后的内容糊在一起。
 */

package com.yunx.app.overlay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeTokensTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    @Test
    fun `两套风格都有亮暗两套`() {
        UiStyle.entries.forEach { style ->
            val light = tokensFor(style, dark = false)
            val dark = tokensFor(style, dark = true)
            assertFalse("$style 浅色标记错了", light.dark)
            assertTrue("$style 深色标记错了", dark.dark)
            assertNotEquals("$style 亮暗底色一样", light.bg, dark.bg)
            assertEquals(style, light.style)
            assertEquals(style, dark.style)
        }
    }

    @Test
    fun `日夜解析`() {
        assertEquals(false, resolveDark(UiMode.SYSTEM, systemDark = false))
        assertEquals(true, resolveDark(UiMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDark(UiMode.LIGHT, systemDark = true))
        assertEquals(true, resolveDark(UiMode.DARK, systemDark = false))
    }

    @Test
    fun `未知 key 回落到跟随系统与风格一`() {
        assertEquals(UiMode.SYSTEM, UiMode.of(null))
        assertEquals(UiMode.SYSTEM, UiMode.of("night"))
        UiMode.entries.forEach { assertEquals(it, UiMode.of(it.key)) }
        UiStyle.entries.forEach { assertEquals(it, UiStyle.of(it.key)) }
    }

    @Test
    fun `正文对比度至少 7`() {
        UiStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val t = tokensFor(style, dark)
                val ratio = contrast(t.text, t.bg)
                assertTrue(
                    "$style dark=$dark 正文对比度只有 $ratio",
                    ratio >= 7f
                )
            }
        }
    }

    @Test
    fun `副文字对比度至少 4_5`() {
        UiStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val t = tokensFor(style, dark)
                val ratio = contrast(t.textSub, t.bg)
                assertTrue(
                    "$style dark=$dark 副文字对比度只有 $ratio",
                    ratio >= 4.5f
                )
            }
        }
    }

    @Test
    fun `抽屉半透明但压得住`() {
        UiStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val t = tokensFor(style, dark)
                // v1.9.18：抽屉改成真半透明（要能看见背后的页面），但不能透到读不清字。
                // 下限 0.55 是实测可读的临界，上限 1.0 给原生风格的实心面板。
                assertTrue("$style dark=$dark 抽屉顶部太透", t.panelTop.alpha in 0.55f..1f)
                assertTrue("$style dark=$dark 抽屉底部太透", t.panelBottom.alpha in 0.55f..1f)
                assertTrue("$style dark=$dark 遮罩没带透明度", t.scrim.alpha in 0.1f..0.9f)
            }
        }
    }

    @Test
    fun `只保留两套风格`() {
        assertEquals(2, UiStyle.entries.size)
        assertEquals(UiStyle.LIQUID, UiStyle.entries[0])
        assertEquals(UiStyle.NATIVE, UiStyle.entries[1])
    }

    @Test
    fun `旧风格 key 迁移不丢用户选择`() {
        // 删掉的三套统一落到液态玻璃，material 仍是原生
        listOf("glass", "harmony", "apple", "liquid").forEach {
            assertEquals("$it 应迁移到液态玻璃", UiStyle.LIQUID, UiStyle.of(it))
        }
        assertEquals(UiStyle.NATIVE, UiStyle.of("material"))
        assertEquals(UiStyle.NATIVE, UiStyle.of("native"))
        // 新装 / 未知 key 默认液态玻璃
        assertEquals(UiStyle.LIQUID, UiStyle.of(null))
        assertEquals(UiStyle.LIQUID, UiStyle.of("whatever"))
    }

    @Test
    fun `风格名不再使用品牌`() {
        val banned = listOf("鸿蒙", "苹果", "iOS", "Harmony", "Apple", "华为")
        UiStyle.entries.forEach { style ->
            banned.forEach { word ->
                assertFalse("${style.label} 仍含「$word」", style.label.contains(word))
                assertFalse("${style.short} 仍含「$word」", style.short.contains(word))
            }
        }
        assertEquals("液态玻璃", UiStyle.LIQUID.label)
        assertEquals("安卓原生", UiStyle.NATIVE.label)
    }
}
