/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。自动连招模块的数据模型与 JSON 序列化（仅用 Android 内置 org.json，
 * 不引入新依赖）。一个「宏」= 一串手势步骤 + 重复策略；步骤里的 points 是一条触摸轨迹，
 * 单点轨迹就是「点击」，多点轨迹就是「滑动/长按拖动」。
 *
 * v1.8.4：MacroConfig 增加「专属按键外观」三件套（内置图标 / 自定义图片 / 专属颜色），
 *   让每个保存后的连招按钮都能单独设置长什么样。字段带默认值且 fromJson 兼容旧存档。
 * v1.9.1：增加 enabled —— 每个连招都能独立「开启 / 关闭」，关闭后不执行也不显示悬浮球。
 */

package com.yunx.app.overlay.macro

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** 屏幕绝对坐标（来自 MotionEvent.rawX / rawY，与 dispatchGesture 的坐标系一致） */
data class MacroPoint(val x: Float, val y: Float)

/**
 * v2.5.7「连招录制台」的按键：屏幕上的一个可视按键点。
 *
 * 和旧「自由录制」的区别：旧录制是一层全屏透明膜，录制期间整块屏都被 App 吃掉，
 * 游戏画面还在但点哪都没反应，用户只能「空划」；录制台的按键是**一个按键一扇独立小窗**，
 * 只有按键那几十像素吃触摸，屏幕其它地方照常交给游戏。
 * 所以可以一边走位 / 放技能，一边按这些键来编排连招 —— 也就是用户要的
 * 「游戏空间里自动连招的那种设置方式」。
 *
 * @param x,y    按键中心的屏幕绝对坐标（px），回放时直接当点击坐标用
 * @param sizeDp 按键直径，录制时可调
 */
data class MacroKey(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val x: Int,
    val y: Int,
    val sizeDp: Int = 54
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("x", x)
        put("y", y)
        put("size", sizeDp)
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroKey = MacroKey(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            label = obj.optString("label"),
            x = obj.optInt("x", 0),
            y = obj.optInt("y", 0),
            sizeDp = obj.optInt("size", 54).coerceIn(36, 96)
        )
    }
}

/**
 * 一次「按下 → 移动 → 抬起」。
 *
 * @param points       轨迹采样点，至少 1 个
 * @param durationMs   按下到抬起的时长（点击约 30~60ms，长按可到 600ms+）
 * @param gapBeforeMs  上一次动作结束后，等到本次动作开始的间隔
 */
data class MacroStep(
    val points: List<MacroPoint>,
    val durationMs: Long,
    val gapBeforeMs: Long
) {
    val start: MacroPoint get() = points.firstOrNull() ?: MacroPoint(0f, 0f)
    val end: MacroPoint get() = points.lastOrNull() ?: MacroPoint(0f, 0f)
    val isTap: Boolean
        get() = points.size <= 2 &&
            abs(end.x - start.x) < 12f &&
            abs(end.y - start.y) < 12f

    fun toJson(): JSONObject = JSONObject().apply {
        put("duration", durationMs)
        put("gap", gapBeforeMs)
        put(
            "points",
            JSONArray().also { arr ->
                points.forEach { p -> arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
            }
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroStep {
            val arr = obj.optJSONArray("points") ?: JSONArray()
            val points = (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                MacroPoint(pair.optDouble(0).toFloat(), pair.optDouble(1).toFloat())
            }
            return MacroStep(
                points = points.ifEmpty { listOf(MacroPoint(0f, 0f)) },
                durationMs = obj.optLong("duration", 50L).coerceIn(1L, 10_000L),
                gapBeforeMs = obj.optLong("gap", 0L).coerceIn(0L, 30_000L)
            )
        }

        /** 生成一个「点一下」步骤 */
        fun tap(x: Float, y: Float, durationMs: Long = 50L, gapBeforeMs: Long = 0L) =
            MacroStep(listOf(MacroPoint(x, y)), durationMs, gapBeforeMs)
    }
}

/**
 * 一个可保存、可点按钮执行的连招。
 *
 * @param intervalMs 每轮结束后再等多久开下一轮（单点连击时它就是「点击间隔」）
 * @param repeat     重复轮数，0 = 一直点到手动停止
 * @param pinned     是否置顶成一颗独立的悬浮按钮
 * @param enabled    v1.9.1 独立开关：false = 停用，不执行、不显示它的置顶悬浮球
 * @param iconPreset 专属按键的内置图标（一个 emoji / 符号字符）；自定义图片优先于它
 * @param iconImagePath 用户从相册选的图片，已复制到 app 私有目录的绝对路径；null = 用内置图标
 * @param iconColor  专属按键背景 ARGB，0 = 用默认主题蓝
 */
data class MacroConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<MacroStep>,
    val intervalMs: Long = 200L,
    val repeat: Int = 0,
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val ballX: Int = 0,
    val ballY: Int = 240,
    val iconPreset: String = MacroIcons.DEFAULT,
    val iconImagePath: String? = null,
    val iconColor: Int = 0,
    /**
     * v2.5.7：这条连招自己的录制按键板。非空 = 可以用「按键录制」重录 / 续录，
     * 录制时这些键会原样摆回屏幕上。旧存档没有这个键，读出来是空列表，互不影响。
     */
    val keys: List<MacroKey> = emptyList()
) {
    val isSingleTap: Boolean get() = steps.size == 1 && steps[0].isTap

    /** 这个按钮当前实际用的底色（默认主题蓝 / 用户专属色） */
    val displayColor: Int
        get() = if (iconColor != 0) iconColor else MacroIcons.DEFAULT_COLOR

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("interval", intervalMs)
        put("repeat", repeat)
        put("pinned", pinned)
        put("enabled", enabled)
        put("ballX", ballX)
        put("ballY", ballY)
        // v1.8.4 外观三件套（旧存档没有这几个键，fromJson 会回落到默认值）
        put("icon", iconPreset)
        put("iconImg", iconImagePath.orEmpty())
        put("iconColor", iconColor)
        put("steps",
            JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } }
        )
        put("keys", JSONArray().also { arr -> keys.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroConfig {
            val arr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { MacroStep.fromJson(it) }
            }
            return MacroConfig(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name").ifBlank { "未命名连招" },
                steps = steps,
                intervalMs = obj.optLong("interval", 200L).coerceIn(0L, 30_000L),
                repeat = obj.optInt("repeat", 0).coerceIn(0, 9999),
                pinned = obj.optBoolean("pinned", false),
                enabled = obj.optBoolean("enabled", true),
                ballX = obj.optInt("ballX", 0),
                ballY = obj.optInt("ballY", 240),
                iconPreset = obj.optString("icon").ifBlank { MacroIcons.DEFAULT },
                iconImagePath = obj.optString("iconImg").takeIf { it.isNotBlank() },
                iconColor = obj.optInt("iconColor", 0),
                keys = (obj.optJSONArray("keys") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { MacroKey.fromJson(it) }
                    }
                }
            )
        }
    }
}

/**
 * 专属按键的可选外观素材。
 *
 * 用 emoji 字符当内置图标：Compose `Text` 和传统 `TextView` 都能直接显示，
 * 不引入任何图片资源 / 依赖，AGPL 衍生包体积也不变。
 */
object MacroIcons {
    const val DEFAULT = "⚡"

    /** 保存新连招时按顺序自动分配，让每个按钮一出生就和别的长得不一样 */
    val PRESETS = listOf(
        "⚡", "🔥", "❄️", "⚔️", "🎯", "💥", "🛡️", "🌟",
        "👊", "🌀", "❤️", "🍀", "🐉", "🎲", "🔮", "🏹"
    )

    /** 0 = 跟随默认主题蓝，其余为 ARGB 专属色 */
    val COLORS = listOf(
        0,
        0xFF3D6BF5.toInt(), 0xFFE5484D.toInt(), 0xFFFF9500.toInt(),
        0xFF34C759.toInt(), 0xFF8B7CF6.toInt(), 0xFF0D9488.toInt(),
        0xFFFF2D55.toInt(), 0xFF00C7BE.toInt(), 0xFF5856D6.toInt()
    )

    /** 悬浮按钮 / 卡片默认底色 */
    val DEFAULT_COLOR = 0xE63D6BF5.toInt()

    fun autoPreset(index: Int): String = PRESETS[index % PRESETS.size]
}

/** 录制/取点完成后、还没命名保存的临时结果 */
data class PendingMacro(val steps: List<MacroStep>, val suggestedName: String)

/** 覆盖层工作模式 */
enum class CaptureMode { AIM, RECORD }
