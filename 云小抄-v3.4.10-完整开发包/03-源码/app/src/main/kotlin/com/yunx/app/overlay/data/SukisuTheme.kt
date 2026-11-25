/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.4.0 新增。读 SukiSU / KernelSU 管理器那套 `.sukisu` 主题包。
 *
 * 为什么单独一个文件：
 *   这套包跟云小抄自己的 json 包没有一处相同 —— 外面裹了一层 AES-GCM，
 *   里面是一个 zip，装的不是参数而是**图片 + 一份管理器自己的配置**。
 *   混进 ThemePack.kt 会让那个文件变成两种格式的缝合怪，所以拆出来。
 *
 * 包的实际结构（对 `SukiSU-theme-遐蝶.sukisu` 逐字节验证过）：
 *
 *   SKTH | IV(12B) | AES-GCM 密文（含 16B tag）
 *        └─ 解密出来是 zip：
 *             theme.json                    管理器的外观配置
 *             background                    背景图（webp / jpg / png，无扩展名）
 *             card_background               卡片底图
 *             bottom_bar_icon_home          底栏图标 ×4
 *             bottom_bar_icon_superuser
 *             bottom_bar_icon_module
 *             bottom_bar_icon_setting
 *
 * 密钥是常量：这套格式本来就是「防手改、不防拆」，任何拿到管理器源码的人
 * 都能解开。这里照抄同一把钥匙，只是为了能读别人的包，不做二次分发。
 */

package com.yunx.app.overlay.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** 从 `.sukisu` 里读出来的一套东西，还没落盘 */
data class SukisuSkin(
    val title: String,
    val note: String,
    val tags: List<String>,
    val accent: Int,
    val dots: List<Int>,
    val tuningJson: String,
    val background: ByteArray?,
    val cardBackground: ByteArray?,
    /** key = home / superuser / module / setting */
    val icons: Map<String, ByteArray>,
    /** 包里那份文案表（「遐蝶喵~」这类）。云小抄只挑几条能对上的用 */
    val texts: Map<String, String>,
    val deep: Boolean
)

object SukisuTheme {

    private val MAGIC = byteArrayOf(0x53, 0x4B, 0x54, 0x48) // "SKTH"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** 单个文件的解压上限，防 zip bomb；正常主题包最大也就几 MB */
    private const val MAX_ENTRY = 64L * 1024 * 1024
    private const val MAX_TOTAL = 96L * 1024 * 1024

    /**
     * 包密钥。SukiSU 的实现里它是「主题密钥的 48 字节哈希的前 32 字节」——
     * 也就是说源码里就摆着，这里照抄同样的 32 字节。
     */
    private val KEY = byteArrayOf(
        0x41, 0xdc.toByte(), 0x17, 0x4e, 0x50, 0x96.toByte(), 0xb4.toByte(), 0xee.toByte(),
        0x93.toByte(), 0x31, 0x1c, 0xc8.toByte(), 0x92.toByte(), 0xb1.toByte(), 0x32, 0xe2.toByte(),
        0x19, 0x61, 0x01, 0x2b, 0x14, 0xcf.toByte(), 0x21, 0x79,
        0x54, 0x0f, 0x20, 0x3c, 0xa7.toByte(), 0x2a, 0xb9.toByte(), 0xc1.toByte()
    )

    // ------------------------------------------------------------------
    // 认包 / 解密 / 解压
    // ------------------------------------------------------------------

    fun looksLike(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size + IV_SIZE + 16) return false
        return MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /**
     * 解密。GCM 会校验 tag，密钥不对 / 文件被改过都会直接抛异常 ——
     * 调用方接住当「这包打不开」处理就行，不要吞成空数据。
     */
    fun decrypt(bytes: ByteArray): ByteArray {
        require(looksLike(bytes)) { "不是 .sukisu 包" }
        val iv = bytes.copyOfRange(4, 4 + IV_SIZE)
        val body = bytes.copyOfRange(4 + IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return cipher.doFinal(body)
    }

    /**
     * 加密成 `.sukisu`。与 [decrypt] 严格互逆：SKTH | IV(12B) | AES-GCM 密文。
     *
     * 每次导出都现取一个随机 IV —— GCM 下同一个 key + 同一个 IV 重复用是致命的，
     * 而 SecureRandom 在这里的开销可以忽略。
     */
    fun encrypt(zip: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(KEY, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        val body = cipher.doFinal(zip)
        val out = ByteArrayOutputStream(MAGIC.size + IV_SIZE + body.size)
        out.write(MAGIC)
        out.write(iv)
        out.write(body)
        return out.toByteArray()
    }

    /**
     * 把 zip 读成「文件名 → 字节」。
     *
     * 只取 basename：SukiSU 的包永远是平铺的，但有人重新打包时会多套一层文件夹，
     * 取 basename 能一并兼容。同名只留第一个。
     */
    fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val key = entry.name.substringAfterLast('/').lowercase()
                    if (key.isNotEmpty() && !out.containsKey(key)) {
                        val data = zip.readLimited(MAX_ENTRY)
                        total += data.size
                        if (total > MAX_TOTAL) error("包太大了")
                        out[key] = data
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun ZipInputStream.readLimited(limit: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(32 * 1024)
        var sum = 0L
        while (true) {
            val n = read(chunk)
            if (n <= 0) break
            sum += n
            if (sum > limit) error("压缩包里的文件过大")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** 这段 JSON 是不是 SukiSU 的 theme.json（而不是云小抄自己的） */
    fun looksLikeSukisuJson(text: String): Boolean {
        val o = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return false
        if (o.optString("schema") == ThemePacks.SCHEMA) return false
        // 云小抄的三种载体：完整包 / 带 tuning / 裸参数
        if (o.has("tuning") || o.has("cardAlpha") || o.has("accentKey")) return false
        return o.has("uiMode") ||
            o.has("cardBackgroundAlphaPercent") ||
            o.has("bottomBarIcons") ||
            o.has("customTexts") ||
            o.has("themeMode")
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 把 theme.json + 图片 揉成一套云小抄能用的东西。
     *
     * 字段映射的原则是「能看见的才搬」：
     *   · 背景图 / 卡片底图 / 底栏图标  → 原样搬，这是这个包长什么样的全部；
     *   · 卡片透明度 / 背景模糊 / 遮罩  → 换算成云小抄的 cardAlpha / blurScale / scrimBias；
     *   · 取色方式 / 色彩规格          → 换成强调色 + 调色板气质；
     *   · 文案表（「阁下的内核版本喵~」）→ 只留几条通用的做介绍，不硬塞进界面。
     * 其余字段（模块排序、KPM、Zygisk 开关那一堆）跟云小抄没有对应物，直接忽略。
     */
    fun parse(json: String, files: Map<String, ByteArray>, fileName: String): SukisuSkin? {
        val o = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null

        val background = files["background"]?.takeIf { isImage(it) }
        val card = files["card_background"]?.takeIf { isImage(it) }
        val icons = LinkedHashMap<String, ByteArray>()
        files.forEach { (name, data) ->
            if (name.startsWith("bottom_bar_icon_") && isImage(data)) {
                icons[name.removePrefix("bottom_bar_icon_")] = data
            }
        }

        // 包名：文件名最像人起的名字（SukiSU-theme-遐蝶 → 遐蝶），
        // 文件名没信息量时才退到包里的标题
        val texts = LinkedHashMap<String, String>()
        o.optJSONObject("customTexts")?.let { t ->
            t.keys().forEach { k -> texts[k] = t.optString(k) }
        }
        val title = cleanName(fileName)
            .ifBlank { o.optString("customHomeTitle") }
            .ifBlank { texts["home.title"].orEmpty() }
            .ifBlank { "SukiSU 主题" }

        val keyColor = o.optInt("keyColor", 0)
        val fromBg = background?.let { accentOf(it) }
        val accent = when {
            keyColor != 0 -> keyColor or 0xFF000000.toInt()
            fromBg != null -> fromBg
            else -> 0xFF8B5CF6.toInt()
        }
        val dots = background?.let { dotsOf(it, accent) }
            ?: listOf(accent, lighten(accent, 0.35f), 0xFF0B1020.toInt())

        // 深不深看两张图：立绘大面积是人物皮肤，平均亮度经常骗人（这张 0.58，
        // 看着却是深紫主题）；卡面图才是真正的「界面底色」，优先信它。
        val bgLum = background?.let { luminanceOf(it) }
        val cardLum = card?.let { luminanceOf(it) }
        val darkBg = when {
            bgLum != null && bgLum < 0.48f -> true
            cardLum != null -> cardLum < 0.52f
            bgLum != null -> bgLum < 0.62f
            else -> o.optInt("themeMode", 0) == 2
        }

        val cardAlphaPct = o.optInt("cardBackgroundAlphaPercent", 30).coerceIn(0, 100)
        val blurPct = o.optInt("customBackgroundBlurPercent", 0).coerceIn(0, 100)
        val scrimPct = o.optInt("customBackgroundScrimPercent", 20).coerceIn(0, 100)

        val tuning = JSONObject().apply {
            put("v", 1)
            put("accentKey", "custom")
            put("customAccent", accent)
            put("paletteStyle", paletteOf(o.optString("colorStyle")))
            put("colorSpec", if (o.optString("colorSpec").contains("2021")) "2021" else "2025")
            // 30% → 0.545：云小抄的 cardAlpha 是「卡片底色的倍数」，
            // 直接拿 0.3 会透到看不见，压进 0.35–1.0 这一段才既透又读得清
            put("cardAlpha", (0.35f + 0.65f * cardAlphaPct / 100f).toDouble())
            put("blurScale", (blurPct / 100f * 1.3f).coerceIn(0f, 1.6f).toDouble())
            put("scrimBias", (-scrimPct / 100f * 0.28f).coerceIn(-0.35f, 0.35f).toDouble())
            put("fontScale", 1.0)
            // SukiSU 的卡都是大圆角，1.18 比默认 1.0 更接近原包
            put("cardRadius", 1.18)
            put("borderScale", if (darkBg) 1.1 else 0.9)
            put("shadowScale", 1.05)
            put("dividerScale", 1.0)
            put("iconShape", "rounded")
            put("hideSubtitle", false)
            put("bottomDensity", "normal")
            put("showSliderValue", false)
            // 有背景图时上下渐隐，顶栏和底栏的字才不会被亮图吃掉
            put("progressiveScrim", background != null)
        }

        val bits = buildList {
            if (background != null) add("背景")
            if (card != null) add("卡面")
            if (icons.isNotEmpty()) add("底栏图标")
        }
        val note = if (bits.isEmpty()) {
            "SukiSU 主题 · 只有配色"
        } else {
            "SukiSU 主题 · " + bits.joinToString(" · ")
        }

        val tags = buildList {
            add("SukiSU")
            add(if (darkBg) "深色" else "浅色")
            if (card != null) add("卡面")
        }

        return SukisuSkin(
            title = title,
            note = note,
            tags = tags,
            accent = accent,
            dots = dots,
            tuningJson = tuning.toString(),
            background = background,
            cardBackground = card,
            icons = icons,
            texts = texts,
            deep = darkBg
        )
    }

    // ------------------------------------------------------------------
    // 从图里取色
    // ------------------------------------------------------------------

    private fun isImage(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun decodeSmall(data: ByteArray, target: Int = 64): Bitmap? = runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, probe)
        var sample = 1
        while (probe.outWidth / sample > target * 2 && sample < 16) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, opt)
    }.getOrNull()

    /** 画面里「最像强调色」的那颗像素：饱和度高、亮度居中，蓝紫一类最容易胜出 */
    private fun accentOf(data: ByteArray): Int? {
        val bmp = decodeSmall(data, 48) ?: return null
        val w = bmp.width
        val h = bmp.height
        val hsv = FloatArray(3)
        var best = 0
        var bestScore = -1f
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = bmp.getPixel(x, y)
                Color.colorToHSV(px, hsv)
                // 太黑（阴影）、太白（人物皮肤 / 高光）都不算「主题色」
                if (hsv[2] in 0.22f..0.92f && hsv[1] > 0.18f) {
                    val score = hsv[1] * (1f - abs(hsv[2] - 0.6f) * 1.4f)
                    if (score > bestScore) {
                        bestScore = score
                        best = px
                    }
                }
                x += 2
            }
            y += 2
        }
        bmp.recycle()
        if (bestScore <= 0f) return null
        Color.colorToHSV(best, hsv)
        // 提纯 + 定亮：截图里的紫往往是灰紫，直接用会发闷
        hsv[1] = hsv[1].coerceAtLeast(0.5f)
        hsv[2] = 0.78f
        return Color.HSVToColor(hsv) or 0xFF000000.toInt()
    }

    /** 三颗预览色：主色 / 提亮 / 画面暗部（卡片渐变要有深底才压得住白字） */
    private fun dotsOf(data: ByteArray, accent: Int): List<Int> {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var n = 0L
        decodeSmall(data, 32)?.let { bmp ->
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val px = bmp.getPixel(x, y)
                    sumR += (px shr 16) and 0xFF
                    sumG += (px shr 8) and 0xFF
                    sumB += px and 0xFF
                    n++
                    x += 2
                }
                y += 2
            }
            bmp.recycle()
        }
        val base = if (n > 0) Color.rgb(
            (sumR / n).toInt(), (sumG / n).toInt(), (sumB / n).toInt()
        ) else accent
        val deep = scale(base, 0.18f) or 0xFF000000.toInt()
        return listOf(accent, lighten(accent, 0.38f), deep)
    }

    private fun luminanceOf(data: ByteArray): Float? {
        val bmp = decodeSmall(data, 32) ?: return null
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                sum += (0.2126 * ((px shr 16) and 0xFF) +
                    0.7152 * ((px shr 8) and 0xFF) +
                    0.0722 * (px and 0xFF)) / 255.0
                n++
                x += 2
            }
            y += 2
        }
        bmp.recycle()
        return if (n == 0) null else (sum / n).toFloat()
    }

    private fun scale(color: Int, k: Float): Int = Color.rgb(
        (((color shr 16) and 0xFF) * k).toInt().coerceIn(0, 255),
        (((color shr 8) and 0xFF) * k).toInt().coerceIn(0, 255),
        ((color and 0xFF) * k).toInt().coerceIn(0, 255)
    )

    private fun lighten(color: Int, k: Float): Int = Color.rgb(
        (((color shr 16) and 0xFF) + (255 - ((color shr 16) and 0xFF)) * k).toInt().coerceIn(0, 255),
        (((color shr 8) and 0xFF) + (255 - ((color shr 8) and 0xFF)) * k).toInt().coerceIn(0, 255),
        ((color and 0xFF) + (255 - (color and 0xFF)) * k).toInt().coerceIn(0, 255)
    )

    private fun paletteOf(style: String): String = when (style.lowercase()) {
        "vibrant" -> "vibrant"
        "neutral" -> "neutral"
        "expressive" -> "expressive"
        "monochrome", "mono" -> "mono"
        else -> "tonal"
    }

    /**
     * 文件名 → 包名。
     *
     * 网盘 / 微信下载下来的名字经常是
     * `1795389705012713556-SukiSU-theme-遐蝶.zip`，前面挂一串雪花 id；
     * 直接截前缀会得到「1795389705012713556-SukiSU-theme-遐蝶」这种卡片上放不下的东西。
     * 所以先剥 id、再剥格式前缀，剩下的才是「遐蝶」。
     */
    fun cleanName(fileName: String): String {
        var s = fileName.substringBeforeLast('.')
        // 纯数字 id 前缀（至少 6 位，避免把「2024主题」这种名字误伤）
        s = s.replace(Regex("^\\d{6,}[-_ ]"), "")
        listOf("sukisu-theme-", "sukisu_theme_", "sukisu-", "sukisu_", "theme-", "theme_")
            .forEach { p ->
                if (s.lowercase().startsWith(p)) s = s.substring(p.length)
            }
        // Windows 上复制出来的「xxx (1)」副本标记
        s = s.replace(Regex("\\s*\\(\\d+\\)$"), "")
        val clean = s.trim()
        // 文件名没信息量时交回去，让上层退到包里的标题
        return if (clean.equals("theme", true) || clean.equals("pack", true)) "" else clean
    }
}
