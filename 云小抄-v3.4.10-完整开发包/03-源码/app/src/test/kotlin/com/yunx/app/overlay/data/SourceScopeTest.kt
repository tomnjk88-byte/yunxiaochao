/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.2）。三种攻略来源的缓存命名空间测试。
 */

package com.yunx.app.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceScopeTest {

    // ------------------------------------------------------------------
    // 作用域本身
    // ------------------------------------------------------------------

    @Test
    fun `个人网盘用平台名作作用域`() {
        assertEquals("QUARK", SourceScope.of(GuideSource.CLOUD, PanPlatform.QUARK))
        assertEquals("XUNLEI", SourceScope.of(GuideSource.CLOUD, PanPlatform.XUNLEI))
    }

    @Test
    fun `链接源的作用域与平台无关`() {
        // 同一时刻只开一条链接：换平台不该换出一个新桶，否则换链接后会读到上一份的缓存
        assertEquals(
            SourceScope.of(GuideSource.LINK, PanPlatform.QUARK),
            SourceScope.of(GuideSource.LINK, PanPlatform.XUNLEI)
        )
        assertEquals(SourceScope.LINK, SourceScope.of(GuideSource.LINK, PanPlatform.QUARK))
    }

    @Test
    fun `链接源作用域不会撞上任何平台名`() {
        // 这条是防「以后加了平台叫 LINK」的：枚举名全大写，作用域全小写
        PanPlatform.entries.forEach { platform ->
            assertNotEquals(platform.name, SourceScope.of(GuideSource.LINK, platform))
        }
    }

    @Test
    fun `本地源有独立作用域`() {
        assertEquals(SourceScope.LOCAL, SourceScope.of(GuideSource.LOCAL, PanPlatform.QUARK))
        assertNotEquals(
            SourceScope.of(GuideSource.LOCAL, PanPlatform.QUARK),
            SourceScope.of(GuideSource.CLOUD, PanPlatform.QUARK)
        )
    }

    // ------------------------------------------------------------------
    // 目录键：三种来源两两不相撞
    // ------------------------------------------------------------------

    @Test
    fun `同一个目录 id 在三种来源下是三个不同的键`() {
        val folderId = "f1a2b3c4"
        val keys = GuideSource.entries.map {
            SourceScope.contentKey(it, PanPlatform.QUARK, folderId)
        }.toSet()
        assertEquals("三种来源必须各占一个桶，撞了就会串图", 3, keys.size)
    }

    @Test
    fun `换平台时个人网盘的目录键会变`() {
        val a = SourceScope.contentKey(GuideSource.CLOUD, PanPlatform.QUARK, "f1")
        val b = SourceScope.contentKey(GuideSource.CLOUD, PanPlatform.XUNLEI, "f1")
        assertNotEquals(a, b)
    }

    @Test
    fun `目录键里包含原始目录 id`() {
        // 不需要解析格式，只要求「不同目录一定不同键」
        val a = SourceScope.contentKey(GuideSource.CLOUD, PanPlatform.QUARK, "f1")
        val b = SourceScope.contentKey(GuideSource.CLOUD, PanPlatform.QUARK, "f2")
        assertNotEquals(a, b)
        assertTrue(a.contains("f1"))
    }

    // ------------------------------------------------------------------
    // 图片键：缩略图与原图必须分开
    // ------------------------------------------------------------------

    @Test
    fun `同一张图的缩略图与原图不是同一个键`() {
        val thumb = SourceScope.imageKey(GuideSource.CLOUD, PanPlatform.QUARK, "img", true)
        val full = SourceScope.imageKey(GuideSource.CLOUD, PanPlatform.QUARK, "img", false)
        assertNotEquals(
            "共用一个键会出现「先看了缩略图，点开大图拿到的还是缩略图地址」",
            thumb,
            full
        )
    }

    @Test
    fun `同一张图在个人网盘与链接源下的键不同`() {
        val cloud = SourceScope.imageKey(GuideSource.CLOUD, PanPlatform.QUARK, "img", false)
        val link = SourceScope.imageKey(GuideSource.LINK, PanPlatform.QUARK, "img", false)
        assertNotEquals(cloud, link)
    }

    // ------------------------------------------------------------------
    // 枚举兼容
    // ------------------------------------------------------------------

    @Test
    fun `未知的持久化值回落到个人小抄`() {
        assertEquals(GuideSource.CLOUD, GuideSource.fromKey(null))
        assertEquals(GuideSource.CLOUD, GuideSource.fromKey(""))
        assertEquals(GuideSource.CLOUD, GuideSource.fromKey("SOMETHING_ELSE"))
    }

    @Test
    fun `三个来源都能从持久化值还原`() {
        GuideSource.entries.forEach {
            assertEquals(it, GuideSource.fromKey(it.name))
        }
    }
}
