/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.4.0）。开屏动画：自定义图片或视频，启动时盖在主界面上播。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 开屏动画的偏好。
 *
 * 为什么不并进 OverlayPrefs：开屏是「启动那一下」的事，跟小抄 / 连招 / 准星
 * 那三层窗口没有半点关系，单开一个文件，误删 / 迁移都好处理。
 *
 * 存的只有路径，真正的图 / 视频在导入时已经复制进 `filesDir/splash_*`。
 * 用复制而不是持久化 URI：GetContent 不给持久读权限，重启后 URI 就废了。
 */
object SplashPrefs {

    private const val FILE = "splash_prefs"
    private const val KEY_ENABLED = "splash_enabled"
    private const val KEY_KIND = "splash_kind"
    private const val KEY_PATH = "splash_path"
    private const val KEY_NAME = "splash_name"
    private const val KEY_HOLD = "splash_hold"

    const val KIND_IMAGE = 0
    const val KIND_VIDEO = 1

    /** 图片停留时长。索引即持久化值 */
    val HOLD_LABELS = listOf("1 秒", "2 秒", "3 秒")
    private val HOLD_MS = intArrayOf(1000, 1800, 3000)
    const val DEFAULT_HOLD = 1

    /** 视频最长播这么久，防止选了个长片把用户困在开屏里（可随时点击跳过） */
    const val MAX_VIDEO_MS = 15_000L

    @Volatile
    private var sp: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        sp?.let { return it }
        synchronized(this) {
            sp?.let { return it }
            val p = context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            sp = p
            return p
        }
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun kind(context: Context): Int =
        prefs(context).getInt(KEY_KIND, KIND_IMAGE).coerceIn(KIND_IMAGE, KIND_VIDEO)

    fun setKind(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_KIND, value.coerceIn(KIND_IMAGE, KIND_VIDEO)).apply()
    }

    fun path(context: Context): String? =
        prefs(context).getString(KEY_PATH, null)?.takeIf { it.isNotBlank() }

    fun setPath(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_PATH, value).apply()
    }

    /** 原始文件名，只用于设置页展示 —— 私有目录里那份叫 splash_xxx.img，看不懂 */
    fun displayName(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun setDisplayName(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_NAME, value).apply()
    }

    fun holdIndex(context: Context): Int =
        prefs(context).getInt(KEY_HOLD, DEFAULT_HOLD).coerceIn(0, HOLD_MS.lastIndex)

    fun setHoldIndex(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_HOLD, index.coerceIn(0, HOLD_MS.lastIndex))
            .apply()
    }

    fun holdMs(context: Context): Int = HOLD_MS[holdIndex(context)]

    /**
     * 要不要播。
     *
     * 没自定义文件不等于没有开屏 —— 会走内置那套（图标 + 名字），
     * 所以这里只判断总开关。文件被删 / 读不出来时同样回落内置，
     * 而不是把用户卡在黑屏上。
     */
    fun isPlayable(context: Context): Boolean = enabled(context)

    /** 自定义文件还在不在。不在就当没设过，播内置 */
    fun existingPath(context: Context): String? {
        val p = path(context) ?: return null
        return runCatching { File(p) }
            .getOrNull()
            ?.takeIf { it.isFile && it.length() > 0 }
            ?.absolutePath
    }
}

/**
 * 进程级的开屏运行态。
 *
 * - [played]：一个进程只播一次。转屏 / 分屏都会重建 Activity，靠它避免
 *   「转一下屏又从头播一遍」；
 * - [gate]：入场动画的闸门。首屏模块浮现要等开屏退场才开始，否则用户在开屏
 *   底下把整场动画「看完」了，一进去就是静态画面。
 */
object SplashRuntime {
    const val GATE_OPEN = true

    val gate = MutableStateFlow(GATE_OPEN)
    var played = false
}
