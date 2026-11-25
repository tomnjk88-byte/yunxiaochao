/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.0）。图片的「无损打包」：下载完把原始字节压一层
 * Deflate 存成 `.yxz`，真正用到时才解压回内存。纯字节操作，不动像素，画质
 * 与网盘原图一致；解码速度只取决于 zlib，与压缩级别无关。
 */

package com.yunx.app.overlay.data

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * `.yxz` 文件格式（大端）：
 *
 * ```
 * 0   .. 3   "YXZ1"        magic
 * 4          version = 1
 * 5          extLen       原始扩展名长度（1..8）
 * 6   ..     ext          ascii，例如 "png"
 * ..  .. +3  rawLength    原始字节数（int）
 * 之后       deflate      raw deflate 流（nowrap，没有 zlib 头）
 * ```
 *
 * 为什么用 raw deflate：
 *  - 解压是 zlib 原生实现，实测一张 400 KB 的图 0.7 ms、1 MB 的图 1.1 ms（x86 桌面，
 *    手机 ARM 乘 2~3 倍），比重新 decode 一次 JPEG（8~15 ms）还快一个数量级；
 *  - 压缩级别只影响压得多小，基本不影响解压速度（实测三档差 0.02~0.05 ms）；
 *  - 不套 zlib 头，省 6 字节，也让「压完反而更大」的概率再低一点。
 *
 * 为什么不是把所有图都硬压：
 *  - JPEG / WebP 本身已经熵编码过，收益完全取决于画面内容：v2.0.1 拿项目里 6 张真实
 *    界面截图（191~410 KB）实测省 8.9%~18.1%，一张 257 KB 的攻略长图省 41.1%，
 *    但一张纯照片可能只省 1%~2%；
 *  - 是否采用压缩结果由 [ImagePackPolicy] 判断，压不动就存原图。
 */
object PackedImage {

    const val EXT = "yxz"

    private const val MAGIC = "YXZ1"
    private const val VERSION = 1
    private const val HEAD_FIXED = 4 + 1 + 1 + 4

    /** 小于 8 KB 的图压不出东西，还多一个文件头 */
    private const val MIN_RAW = 8 * 1024

    /**
     * 防御性上限：损坏的头声称有 2 GB，直接拒绝，别把内存打爆。
     * 解压要一次性分配整个原图，64 MB 已经覆盖攻略长图（4K 长图约 10~20 MB）。
     */
    const val MAX_RAW = 64 * 1024 * 1024

    private const val EXT_MAX = 8

    /**
     * 压缩级别（v2.0.1 用实测定的，不是拍脑袋）。
     *
     * 同一批真实截图：level 1 省 12.5% / 4.43 ms，level 3 省 12.6% / 4.27 ms，
     * level 6 省 12.7% / 4.89 ms。6 比 3 只多省 0.1 个百分点，却慢 15%；
     * 1 比 3 快不了多少，收益还少一截。所以 3 是这条曲线上的拐点。
     */
    private const val LEVEL = 3

    data class Head(val ext: String, val rawLength: Int, val dataOffset: Int)

    fun isPackedName(name: String): Boolean = name.endsWith(".$EXT", ignoreCase = true)

    /**
     * 把原始图片字节压成 `.yxz`。
     *
     * @return 压缩后的字节；太小、失败、或压完不比原图小时返回 null（调用方存原图）
     */
    fun encode(raw: ByteArray, ext: String): ByteArray? {
        if (raw.size < MIN_RAW) return null
        val extBytes = ext.lowercase().filter { it.isLetterOrDigit() }
            .take(EXT_MAX).toByteArray(Charsets.US_ASCII)
        if (extBytes.isEmpty()) return null

        val out = ByteArrayOutputStream(raw.size / 2 + 1024)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(VERSION)
        out.write(extBytes.size)
        out.write(extBytes)
        out.write(intBytes(raw.size))

        val deflater = Deflater(LEVEL, true)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val buf = ByteArray(32 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        val packed = out.toByteArray()
        // 头 6 字节 + 扩展名 + 长度 4 字节，这些是纯开销
        return if (packed.size < raw.size) packed else null
    }

    fun head(bytes: ByteArray): Head? = headOf(bytes, bytes.size)

    /**
     * 只解析文件头，允许 [length] 刚好等于头长度（没有数据体）。
     * 目录扫描时只需要原始长度，没必要把一个几 MB 的文件整个读进来。
     */
    fun headOf(bytes: ByteArray, length: Int): Head? {
        if (length < HEAD_FIXED) return null
        if (length > bytes.size) return null
        if (bytes[0] != 'Y'.code.toByte() || bytes[1] != 'X'.code.toByte() ||
            bytes[2] != 'Z'.code.toByte() || bytes[3] != '1'.code.toByte()
        ) {
            return null
        }
        if (bytes[4].toInt() != VERSION) return null
        val extLen = bytes[5].toInt() and 0xFF
        if (extLen !in 1..EXT_MAX) return null
        val offset = 6 + extLen + 4
        if (length < offset) return null
        val ext = String(bytes, 6, extLen, Charsets.US_ASCII)
        val rawLength = ((bytes[offset - 4].toInt() and 0xFF) shl 24) or
            ((bytes[offset - 3].toInt() and 0xFF) shl 16) or
            ((bytes[offset - 2].toInt() and 0xFF) shl 8) or
            (bytes[offset - 1].toInt() and 0xFF)
        if (rawLength < 1 || rawLength > MAX_RAW) return null
        return Head(ext, rawLength, offset)
    }

    /** 还原成原始字节；格式不对、被截断、长度不符都返回 null */
    fun decode(packed: ByteArray): ByteArray? {
        val h = head(packed) ?: return null
        val out = ByteArray(h.rawLength)
        return if (inflateInto(packed, h, out)) out else null
    }

    private fun inflateInto(packed: ByteArray, h: Head, out: ByteArray): Boolean {
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed, h.dataOffset, packed.size - h.dataOffset)
            var written = 0
            while (written < out.size) {
                val n = runCatching { inflater.inflate(out, written, out.size - written) }
                    .getOrDefault(0)
                if (n > 0) {
                    written += n
                    continue
                }
                // 0 且已经结束 / 需要更多输入 —— 数据不完整
                if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
            }
            if (written != out.size) return false
            // 长度也必须对得上：头里写 1 字节、实际流里有 5 MB，说明头被改过或文件损坏，
            // 宁可返回 null 让上层当「读不出来」，也不要交出一张截断的图
            return runCatching { inflater.finished() }.getOrDefault(false)
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { inflater.end() }
        }
    }

    /** 只读文件头里的原始长度（给「换链接认领」用），失败返回 0 */
    fun rawLengthOf(file: java.io.File): Int = runCatching {
        if (!file.isFile || file.length() < HEAD_FIXED.toLong()) return 0
        file.inputStream().use { input ->
            val buf = ByteArray(HEAD_FIXED + EXT_MAX)
            val n = input.read(buf)
            if (n <= 0) return 0
            headOf(buf, n)?.rawLength ?: 0
        }
    }.getOrDefault(0)

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte()
    )
}

/**
 * 是否采用「无损打包」的结果。
 *
 * 抽成纯对象是为了能单测：这条规则一旦写反，表现只是「开了压缩但空间没变」，
 * 肉眼根本看不出来。
 */
object ImagePackPolicy {

    /** 小于这个大小不压：省不下几百字节，还多一个文件头 */
    const val MIN_RAW_BYTES = 8 * 1024

    /** 至少省 2% 才值得多一层解压（980 / 1000） */
    const val MIN_GAIN_PERMILLE = 980

    /**
     * @param enabled 用户是否打开「压缩图片」
     * @param lowPerf 低性能模式：压缩吃 CPU，直接不压
     * @param rawSize 原始字节数
     * @param packedSize 压缩后字节数（含头）
     */
    fun shouldPack(
        enabled: Boolean,
        lowPerf: Boolean,
        rawSize: Int,
        packedSize: Int
    ): Boolean {
        if (!enabled || lowPerf) return false
        if (rawSize < MIN_RAW_BYTES) return false
        if (packedSize <= 0) return false
        // 用 Long 乘，避免大图 ×1000 溢出
        return packedSize.toLong() * 1000L < rawSize.toLong() * MIN_GAIN_PERMILLE
    }
}
