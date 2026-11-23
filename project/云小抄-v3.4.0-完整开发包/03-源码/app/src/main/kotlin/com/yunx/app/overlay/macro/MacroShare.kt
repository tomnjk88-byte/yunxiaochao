/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.15 自动连招的分享 / 导入。
 *
 * 为什么要有「格式版本 + 来源屏幕尺寸」两样东西：
 *   - 连招存的是一串**绝对像素坐标**，直接发给别人，对方屏幕小一点就点到屏幕外，
 *     所以包里必须记下导出时的屏幕宽高，导入时按比例折过去（见 fitMacro）；
 *   - 手机横竖屏换着用时坐标含义完全不同，这时按短边等比缩放并居中，
 *     宁可位置偏一点，也不能让点击落到屏幕外 —— 落屏外的触摸会被系统直接丢掉。
 *
 * 分享有两条路：
 *   1. 文件（.yxmacro）：走系统分享面板，微信 / QQ / 蓝牙都行，接收方点开即可导入；
 *   2. 口令（YXM1: 开头的一行文本）：发聊天消息最方便，对方复制后在 App 里粘贴导入。
 * 两条路内容完全一致，口令 = gzip(JSON) 再 Base64，所以口令长但不会丢字段。
 */

package com.yunx.app.overlay.macro

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.min

/** 导出时的屏幕参数（px）。density 只作为说明字段，不参与坐标换算 */
data class MacroShareDevice(
    val width: Int,
    val height: Int,
    val density: Float = 1f
) {
    val valid: Boolean get() = width > 0 && height > 0
}

/** 一个分享包 = 一条或多条连招 + 导出设备信息 */
data class MacroSharePayload(
    val macros: List<MacroConfig>,
    val device: MacroShareDevice,
    val exportedAt: Long = System.currentTimeMillis()
)

/** 适配结果：实际缩放比例 + 是否发生了横竖屏翻转 */
data class MacroFit(
    val macro: MacroConfig,
    val scaleX: Float,
    val scaleY: Float,
    val rotated: Boolean
) {
    val scaled: Boolean get() = abs(scaleX - 1f) > 0.02f || abs(scaleY - 1f) > 0.02f
}

/** 合并结果：真正新增的连招、跳过的重复条数、是否需要提示「坐标已缩放」 */
data class MacroMergeResult(
    val add: List<MacroConfig>,
    val skipped: Int,
    val scaled: Boolean,
    val rotated: Boolean
) {
    val added: Int get() = add.size
}

object MacroShare {

    /** 文件里写的标识，用来认「这是不是我们的包」 */
    const val FORMAT = "yunx-macro"
    const val VERSION = 1

    /** 自定义 MIME：系统分享面板、文件选择器都认它 */
    const val MIME = "application/vnd.yunx.macro+json"

    /** 文件后缀：故意不用 .json，避免被聊天软件当文本预览掉 */
    const val EXT = "yxmacro"

    /** 口令前缀。带版本号，以后改格式还能认出老口令 */
    const val CODE_PREFIX = "YXM1:"

    /** 单个包上限 2MB：正常连招只有几 KB，超过基本是选错文件了 */
    const val MAX_PAYLOAD = 2 * 1024 * 1024

    // ------------------------------------------------------------------
    // 编码：文件用带缩进的 JSON（人能看懂），口令用 gzip + Base64
    // ------------------------------------------------------------------

    fun encode(payload: MacroSharePayload): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", payload.exportedAt)
        root.put(
            "device",
            JSONObject().apply {
                put("w", payload.device.width)
                put("h", payload.device.height)
                put("density", payload.device.density.toDouble())
            }
        )
        root.put(
            "macros",
            JSONArray().also { arr -> payload.macros.forEach { arr.put(it.toShareJson()) } }
        )
        return root.toString(2)
    }

    fun decode(raw: String?): MacroSharePayload? {
        val text = clean(raw ?: return null)
        if (text.isEmpty()) return null

        // 口令优先：它可能被夹在一段聊天话术里
        if (text.contains(CODE_PREFIX)) {
            return decodeCode(text)
        }

        val json = extractJsonObject(text) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return fromObject(obj)
    }

    /** 把 payload 压成一行口令，直接发微信也可以 */
    fun encodeCode(payload: MacroSharePayload): String {
        val packed = gzip(encode(payload).toByteArray(Charsets.UTF_8))
        val body = Base64.encodeToString(
            packed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return CODE_PREFIX + body
    }

    fun decodeCode(raw: String): MacroSharePayload? {
        val body = extractCodeBody(raw) ?: return null
        val bytes = runCatching {
            Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        val json = runCatching { String(gunzip(bytes), Charsets.UTF_8) }.getOrNull() ?: return null
        val obj = runCatching { JSONObject(extractJsonObject(json) ?: json) }.getOrNull() ?: return null
        return fromObject(obj)
    }

    /**
     * 是不是一段能导入的内容。
     * 用于「粘贴前先判断」，避免把普通聊天文字当连招解析。
     */
    fun looksLikeShare(raw: String?): Boolean {
        val text = clean(raw ?: return false)
        if (text.contains(CODE_PREFIX) && text.length > CODE_PREFIX.length + 24) return true
        if (!text.contains(FORMAT) && !text.contains("\"steps\"")) return false
        return text.contains('{') && text.contains('}')
    }

    // ------------------------------------------------------------------
    // 坐标适配
    // ------------------------------------------------------------------

    /**
     * 把一条连招从「导出设备」折到「当前设备」。
     *
     * - 方向一致：x / y 各自按屏幕比例缩放（游戏 UI 通常也按宽高比例排版，分开缩最准）；
     * - 方向翻转：按短边等比 + 居中，保证所有点都落在屏幕内（位置会有偏差，UI 会提示重新取点）；
     * - 最后统一 clamp 到 [0, 宽-1] / [0, 高-1]，绝不把触摸送出屏幕。
     */
    fun fitMacro(macro: MacroConfig, src: MacroShareDevice, dstW: Int, dstH: Int): MacroFit {
        if (!src.valid || dstW <= 0 || dstH <= 0) return MacroFit(macro, 1f, 1f, false)

        val srcLandscape = src.width >= src.height
        val dstLandscape = dstW >= dstH
        if (srcLandscape == dstLandscape) {
            val sx = dstW.toFloat() / src.width
            val sy = dstH.toFloat() / src.height
            return MacroFit(
                macro.copy(steps = macro.steps.map { it.scaled(sx, sy, dstW, dstH) }),
                sx, sy, false
            )
        }

        val s = min(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val ox = (dstW - src.width * s) / 2f
        val oy = (dstH - src.height * s) / 2f
        return MacroFit(
            macro.copy(steps = macro.steps.map { it.scaled(s, s, dstW, dstH, ox, oy) }),
            s, s, true
        )
    }

    /**
     * 合并到现有列表：
     *   - 动作完全一样的跳过（重复导入不会堆一屏「连招 1」）；
     *   - 重名但动作不同 → 自动加 (2) / (3)；
     *   - 导入的一律重新生成 id、置顶成小按钮、位置交给 MacroBall 自动排。
     */
    fun merge(
        existing: List<MacroConfig>,
        payload: MacroSharePayload,
        dstW: Int,
        dstH: Int
    ): MacroMergeResult {
        val taken = existing.map { it.name }.toMutableSet()
        val accepted = mutableListOf<MacroConfig>()
        var skipped = 0
        var scaled = false
        var rotated = false

        payload.macros.forEach { source ->
            val fit = fitMacro(source, payload.device, dstW, dstH)
            val duplicated = existing.any { sameActions(it, fit.macro) } ||
                accepted.any { sameActions(it, fit.macro) }
            if (duplicated) {
                skipped++
                return@forEach
            }
            if (fit.scaled) scaled = true
            if (fit.rotated) rotated = true

            val name = uniqueName(fit.macro.name, taken)
            taken += name
            accepted += fit.macro.copy(
                id = UUID.randomUUID().toString(),
                name = name,
                pinned = true,
                enabled = true,
                // -1 = 让悬浮球自己贴右边竖排，不沿用对方的屏幕位置
                ballX = -1,
                ballY = -1,
                // 分享包不带图片文件，导入后先用内置图标，避免指向一个不存在的路径
                iconImagePath = null
            )
        }
        return MacroMergeResult(accepted, skipped, scaled, rotated)
    }

    /** 给用户看的一句话结果 */
    fun describe(result: MacroMergeResult): String {
        if (result.added == 0 && result.skipped == 0) return "没有可导入的连招"
        if (result.added == 0) return "这几条连招已经导入过了，没有重复添加"
        val base = buildString {
            append("已导入 ")
            append(result.added)
            append(" 条连招")
            if (result.skipped > 0) append("，跳过 ${result.skipped} 条重复")
            if (result.scaled) append("，坐标已按本机屏幕缩放")
            if (result.rotated) append("（横竖屏和来源不同，建议进设置重新取点）")
        }
        return base
    }

    // ------------------------------------------------------------------
    // 内部：序列化
    // ------------------------------------------------------------------

    /**
     * 导出用的精简 JSON。
     * 刻意不写 id / pinned / ballX / iconImg：前三个是「本机状态」，最后一个会泄露
     * 用户私有目录的绝对路径，而且对方也没有那张图。
     */
    private fun MacroConfig.toShareJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("interval", intervalMs)
        put("repeat", repeat)
        put("icon", iconPreset)
        put("iconColor", iconColor)
        put(
            "steps",
            JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } }
        )
    }

    private fun fromObject(obj: JSONObject): MacroSharePayload? {
        val deviceObj = obj.optJSONObject("device")
        val device = MacroShareDevice(
            width = deviceObj?.optInt("w", 0) ?: 0,
            height = deviceObj?.optInt("h", 0) ?: 0,
            density = (deviceObj?.optDouble("density", 1.0) ?: 1.0).toFloat()
        )

        val arr = obj.optJSONArray("macros")
        val list = if (arr != null) {
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { MacroConfig.fromJson(it) }
            }
        } else if (obj.has("steps")) {
            // 兼容「只发一条、顶层就是 MacroConfig」的手工 JSON
            listOf(MacroConfig.fromJson(obj))
        } else {
            emptyList()
        }

        val usable = list.filter { it.steps.isNotEmpty() }
        if (usable.isEmpty()) return null
        return MacroSharePayload(usable, device, obj.optLong("exportedAt", 0L))
    }

    // ------------------------------------------------------------------
    // 内部：文本清理与提取
    // ------------------------------------------------------------------

    private fun clean(raw: String): String = raw
        .replace("\uFEFF", "")
        .replace("\u200B", "")
        .trim()

    /** 从一段「前面有寒暄、后面有签名」的文字里抠出第一个完整的 JSON 对象 */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /** 从任意位置找口令，只保留 Base64 字符集，避免把后面的中文说明拼进去 */
    private fun extractCodeBody(raw: String): String? {
        val index = raw.indexOf(CODE_PREFIX)
        if (index < 0) return null
        val tail = raw.substring(index + CODE_PREFIX.length)
        val body = buildString {
            tail.forEach { c ->
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_') append(c)
            }
        }
        if (body.length < 24) return null
        // 去掉可能的末尾截断（正常 Base64 长度是 4 的倍数，缺一个字符也能解，但缺多了就会失败）
        return body
    }

    // ------------------------------------------------------------------
    // 内部：纯函数小工具（单测直接打这些）
    // ------------------------------------------------------------------

    /** 两条连招的「动作」是否完全一致（名字、图标不参与比较） */
    internal fun sameActions(a: MacroConfig, b: MacroConfig): Boolean {
        if (a.steps.size != b.steps.size) return false
        return a.steps.zip(b.steps).all { (x, y) ->
            x.durationMs == y.durationMs &&
                x.gapBeforeMs == y.gapBeforeMs &&
                x.points.size == y.points.size &&
                x.points.zip(y.points).all { (p, q) ->
                    abs(p.x - q.x) < 1f && abs(p.y - q.y) < 1f
                }
        }
    }

    /** 重名加序号：连招 1 → 连招 1 (2) → 连招 1 (3) */
    internal fun uniqueName(base: String, taken: Set<String>): String {
        val name = base.ifBlank { "导入的连招" }
        if (name !in taken) return name
        var index = 2
        while ("$name ($index)" in taken) index++
        return "$name ($index)"
    }

    private fun MacroStep.scaled(
        sx: Float,
        sy: Float,
        width: Int,
        height: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): MacroStep {
        val maxX = (width - 1).coerceAtLeast(0).toFloat()
        val maxY = (height - 1).coerceAtLeast(0).toFloat()
        return copy(
            points = points.map { p ->
                MacroPoint(
                    (p.x * sx + offsetX).coerceIn(0f, maxX),
                    (p.y * sy + offsetY).coerceIn(0f, maxY)
                )
            }
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                // 防 zip 炸弹：超过上限就不再读了
                if (out.size() > MAX_PAYLOAD) break
            }
        }
        return out.toByteArray()
    }
}
