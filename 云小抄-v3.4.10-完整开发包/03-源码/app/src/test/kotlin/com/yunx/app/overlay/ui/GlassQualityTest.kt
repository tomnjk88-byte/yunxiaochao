/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.13 玻璃质感档位的纯逻辑单测（JVM，不依赖 Android）。
 */

package com.yunx.app.overlay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassQualityTest {

    @Test
    fun `未知 key 回落到标准档`() {
        assertEquals(GlassQuality.BALANCED, GlassQuality.of(null))
        assertEquals(GlassQuality.BALANCED, GlassQuality.of(""))
        assertEquals(GlassQuality.BALANCED, GlassQuality.of("ultra2"))
    }

    @Test
    fun `已知 key 精确命中`() {
        GlassQuality.entries.forEach { quality ->
            assertEquals(quality, GlassQuality.of(quality.key))
        }
    }

    @Test
    fun `key 大小写敏感`() {
        assertEquals(GlassQuality.BALANCED, GlassQuality.of("ULTRA"))
    }

    @Test
    fun `模糊可用时保留用户选择`() {
        GlassQuality.entries.forEach { quality ->
            assertEquals(quality, resolveQuality(quality, blurSupported = true))
        }
    }

    @Test
    fun `模糊不可用时一律降到流畅`() {
        GlassQuality.entries.forEach { quality ->
            assertEquals(GlassQuality.LITE, resolveQuality(quality, blurSupported = false))
        }
    }

    @Test
    fun `三档的顺序是从轻到重`() {
        assertEquals(
            listOf("lite", "balanced", "ultra"),
            GlassQuality.entries.map { it.key }
        )
    }
}
