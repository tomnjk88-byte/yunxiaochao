/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.0）。无损打包的编码 / 解码 / 收益判定测试。
 */

package com.yunx.app.overlay.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PackedImageTest {

    /** 能压得动的「图」：大量重复字节，接近 PNG 里大块同色的情形 */
    private fun compressible(size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in out.indices) out[i] = ((i / 97) % 7 + 32).toByte()
        return out
    }

    @Test
    fun `编码后能原样解回来`() {
        val raw = compressible(200_000)
        val packed = PackedImage.encode(raw, "png")
        assertNotNull("应该能压动", packed)
        assertTrue("压缩后应该更小", packed!!.size < raw.size)
        assertArrayEquals(raw, PackedImage.decode(packed))
    }

    @Test
    fun `扩展名与原始长度写进头里`() {
        val raw = compressible(100_000)
        val packed = PackedImage.encode(raw, "webp")!!
        val head = PackedImage.head(packed)
        assertNotNull(head)
        assertEquals("webp", head!!.ext)
        assertEquals(raw.size, head.rawLength)
        assertEquals(PackedImage.isPackedName("a.webp.yxz"), true)
    }

    @Test
    fun `压不动的数据返回 null`() {
        // 随机字节几乎没有冗余，deflate 只会变大
        val raw = Random(7).nextBytes(300_000)
        assertNull(PackedImage.encode(raw, "jpg"))
    }

    @Test
    fun `太小的文件不压`() {
        assertNull(PackedImage.encode(ByteArray(1024) { 1 }, "png"))
    }

    @Test
    fun `magic 不对直接拒绝`() {
        val raw = compressible(50_000)
        val packed = PackedImage.encode(raw, "png")!!
        packed[0] = 'X'.code.toByte()
        assertNull(PackedImage.head(packed))
        assertNull(PackedImage.decode(packed))
    }

    @Test
    fun `截断的数据解不出来`() {
        val raw = compressible(50_000)
        val packed = PackedImage.encode(raw, "png")!!
        val cut = packed.copyOf(packed.size / 2)
        assertNull("只给一半数据不应返回半张图", PackedImage.decode(cut))
    }

    @Test
    fun `头里的长度离谱时拒绝`() {
        val raw = compressible(50_000)
        val packed = PackedImage.encode(raw, "png")!!
        val head = PackedImage.head(packed)!!
        // 把 rawLength 改成 1，解压出来的字节数对不上
        val offset = head.dataOffset - 4
        packed[offset] = 0
        packed[offset + 1] = 0
        packed[offset + 2] = 0
        packed[offset + 3] = 1
        assertNull(PackedImage.decode(packed))
    }

    @Test
    fun `只读头也能拿到原始长度`() {
        val raw = compressible(64_000)
        val packed = PackedImage.encode(raw, "jpg")!!
        val headBytes = packed.copyOf(32)
        val head = PackedImage.headOf(headBytes, headBytes.size)
        assertEquals(raw.size, head?.rawLength)
    }

    @Test
    fun `文件名判断大小写都认`() {
        assertTrue(PackedImage.isPackedName("a.jpg.yxz"))
        assertTrue(PackedImage.isPackedName("A.JPG.YXZ"))
        assertFalse(PackedImage.isPackedName("a.jpg"))
    }
}

class ImagePackPolicyTest {

    @Test
    fun `关掉就永远不压`() {
        assertFalse(ImagePackPolicy.shouldPack(false, false, 1_000_000, 500_000))
    }

    @Test
    fun `低性能模式强制不压`() {
        assertFalse(ImagePackPolicy.shouldPack(true, true, 1_000_000, 500_000))
    }

    @Test
    fun `小图不压`() {
        assertFalse(
            ImagePackPolicy.shouldPack(
                true, false,
                ImagePackPolicy.MIN_RAW_BYTES - 1,
                ImagePackPolicy.MIN_RAW_BYTES - 100
            )
        )
    }

    @Test
    fun `省不到百分之二就不压`() {
        // 1000 KB → 990 KB，只省 1%
        assertFalse(ImagePackPolicy.shouldPack(true, false, 1_000_000, 990_000))
        // 刚好 98% 也算不划算（要求严格小于）
        assertFalse(ImagePackPolicy.shouldPack(true, false, 1_000_000, 980_000))
        assertTrue(ImagePackPolicy.shouldPack(true, false, 1_000_000, 979_000))
    }

    @Test
    fun `正常的 PNG 能通过`() {
        assertTrue(ImagePackPolicy.shouldPack(true, false, 800_000, 600_000))
    }

    @Test
    fun `空结果不采用`() {
        assertFalse(ImagePackPolicy.shouldPack(true, false, 1_000_000, 0))
    }

    @Test
    fun `超大图乘法不溢出`() {
        // 用一个接近上限的尺寸，确认不会因为 Int 溢出得出「不划算」
        assertTrue(ImagePackPolicy.shouldPack(true, false, 400 * 1024 * 1024, 200 * 1024 * 1024))
    }
}
