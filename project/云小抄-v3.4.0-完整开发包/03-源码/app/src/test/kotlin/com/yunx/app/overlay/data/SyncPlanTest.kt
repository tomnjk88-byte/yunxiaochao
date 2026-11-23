/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.1）。同步判定规则测试。
 */

package com.yunx.app.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {

    private fun remote(size: Long, md5: String? = null) =
        RemoteStamp(name = "第1页.jpg", size = size, md5 = md5)

    private fun local(origin: Long, md5: String? = null, bytes: Long = origin) =
        LocalStamp(originSize = origin, md5 = md5, bytes = bytes)

    // ------------------------------------------------------------------
    // 基本三态
    // ------------------------------------------------------------------

    @Test
    fun `本地没有就是新增`() {
        assertEquals(SyncVerdict.NEW, SyncPlan.decide(null, remote(120_000)))
    }

    @Test
    fun `大小一致就跳过`() {
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(120_000), remote(120_000)))
    }

    /** 这条是整个功能的地基：作者改图重新导出，字节数几乎不可能一模一样 */
    @Test
    fun `大小变了必须覆盖`() {
        assertEquals(SyncVerdict.UPDATE, SyncPlan.decide(local(120_000), remote(131_072)))
        // 只差 1 个字节也算变了
        assertEquals(SyncVerdict.UPDATE, SyncPlan.decide(local(120_000), remote(120_001)))
    }

    @Test
    fun `覆盖态一定需要下载`() {
        assertTrue(SyncVerdict.UPDATE.needsDownload)
        assertTrue(SyncVerdict.NEW.needsDownload)
        assertEquals(false, SyncVerdict.KEEP.needsDownload)
    }

    // ------------------------------------------------------------------
    // 接口抖动：宁可不更新，也不能整批重下
    // ------------------------------------------------------------------

    @Test
    fun `远端没给大小就不要判成更新`() {
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(120_000), remote(0)))
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(120_000), remote(-1)))
    }

    @Test
    fun `旧索引没记原始大小且本地更小时保守跳过`() {
        // v1.9.9 及以前：只有磁盘大小。压缩过的旧数据一定比远端小
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(0, bytes = 48_000), remote(120_000)))
    }

    @Test
    fun `旧索引没记原始大小但磁盘大小正好等于远端时认定没变`() {
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(0, bytes = 120_000), remote(120_000)))
    }

    @Test
    fun `旧索引本地比远端还大时不猜`() {
        // 可能是当年存了原图、网盘那头换成了压缩版；没有 md5 无法确认
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(0, bytes = 200_000), remote(120_000)))
    }

    // ------------------------------------------------------------------
    // md5
    // ------------------------------------------------------------------

    @Test
    fun `两边都有 md5 且相同就是同一份`() {
        val md5 = "900150983cd24fb0d6963f7d28e17f72"
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(120_000, md5), remote(120_000, md5)))
    }

    @Test
    fun `md5 不同即使大小相同也要覆盖`() {
        val a = "900150983cd24fb0d6963f7d28e17f72"
        val b = "d41d8cd98f00b204e9800998ecf8427e"
        assertEquals(SyncVerdict.UPDATE, SyncPlan.decide(local(120_000, a), remote(120_000, b)))
    }

    /** md5 是字节级证据，比「接口报的大小」更可信：大小对不上但 md5 一样，说明大小字段不准 */
    @Test
    fun `md5 相同就压过大小差异`() {
        val md5 = "900150983cd24fb0d6963f7d28e17f72"
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(120_000, md5), remote(119_000, md5)))
    }

    @Test
    fun `只有一边有 md5 时退化成大小比对`() {
        val md5 = "900150983cd24fb0d6963f7d28e17f72"
        assertEquals(SyncVerdict.KEEP, SyncPlan.decide(local(120_000, md5), remote(120_000)))
        assertEquals(SyncVerdict.UPDATE, SyncPlan.decide(local(120_000), remote(120_001, md5)))
    }

    @Test
    fun `非法的 md5 当作没有`() {
        // 迅雷给的是 40 位 sha1，不能拿来当 md5 比
        assertNull(SyncPlan.normalizeMd5("da39a3ee5e6b4b0d3255bfef95601890afd80709"))
        assertNull(SyncPlan.normalizeMd5(""))
        assertNull(SyncPlan.normalizeMd5(null))
        assertNull(SyncPlan.normalizeMd5("zz0150983cd24fb0d6963f7d28e17f72"))
    }

    @Test
    fun `md5 大小写与空白要归一`() {
        val expected = "900150983cd24fb0d6963f7d28e17f72"
        assertEquals(expected, SyncPlan.normalizeMd5("  900150983CD24FB0D6963F7D28E17F72 "))
    }

    // ------------------------------------------------------------------
    // 批量拆分
    // ------------------------------------------------------------------

    @Test
    fun `批量拆分只留下需要动的`() {
        val split = SyncPlan.split(
            listOf(
                "a" to SyncVerdict.NEW,
                "b" to SyncVerdict.KEEP,
                "c" to SyncVerdict.UPDATE,
                "d" to SyncVerdict.KEEP,
                "e" to SyncVerdict.NEW
            )
        )
        assertEquals(2, split.fresh)
        assertEquals(1, split.updated)
        assertEquals(3, split.total)
        assertEquals(listOf("a", "c", "e"), split.ids)
    }

    @Test
    fun `全都跳过时列表为空`() {
        val split = SyncPlan.split(listOf("a" to SyncVerdict.KEEP, "b" to SyncVerdict.KEEP))
        assertEquals(0, split.total)
        assertTrue(split.ids.isEmpty())
    }

    // ------------------------------------------------------------------
    // 指纹
    // ------------------------------------------------------------------

    @Test
    fun `md5 与标准值一致`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", SyncPlan.md5Of(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", SyncPlan.md5Of("abc".toByteArray()))
    }

    @Test
    fun `md5 对单字节差异敏感`() {
        val a = SyncPlan.md5Of(ByteArray(4096) { 0x41 })
        val b = SyncPlan.md5Of(ByteArray(4096) { if (it == 2048) 0x42 else 0x41 })
        assertEquals(32, a!!.length)
        assertTrue("一个字节不同必须算不同的指纹", a != b)
    }
}
