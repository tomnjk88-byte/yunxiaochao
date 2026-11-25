/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。自动连招的「执行方式」选择与可用性判定。
 * 无障碍与 root 两种后端并存，默认 AUTO：无障碍可用就用无障碍（频率更高），
 * 否则自动改用 root；用户也可以强制指定某一种。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用户在面板里选的执行方式 */
enum class EngineMode(val label: String, val hint: String) {
    AUTO("自动", "按 Root → Shizuku → 无障碍 的顺序挑，前两个不会打断你的手指"),
    SHIZUKU("Shizuku", "经 Shizuku 以 shell 注入，独立指针，按住摇杆也能放技能"),
    ACCESSIBILITY("无障碍", "dispatchGesture，最快，但每一下都会取消你正按着的触摸"),
    ROOT("Root", "su + input 注入，独立指针；已 root 时最稳")
}

/** 一次执行真正落到哪个后端 */
enum class Backend { NONE, ACCESSIBILITY, SHIZUKU, ROOT }

object MacroEngine {

    private const val PREFS = "macro_engine"
    private const val KEY_MODE = "mode"
    private const val KEY_EVER_ROOT = "ever_root_granted"

    private val _mode = MutableStateFlow(EngineMode.AUTO)
    val mode: StateFlow<EngineMode> = _mode.asStateFlow()

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MODE, EngineMode.AUTO.name)
            }.getOrDefault(EngineMode.AUTO.name)
            _mode.value = runCatching { EngineMode.valueOf(raw.orEmpty()) }
                .getOrDefault(EngineMode.AUTO)
            loaded = true
        }
    }

    fun select(context: Context, mode: EngineMode) {
        _mode.value = mode
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode.name).apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 只有用户曾经授权成功过，冷启动才敢静默去连 su（否则会莫名弹授权框） */
    fun everRootGranted(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_EVER_ROOT, false) }.getOrDefault(false)

    fun markRootGranted(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_EVER_ROOT, true).apply() }
    }

    /**
     * 决定这次用哪个后端。root 授权状态与无障碍连接状态都是实时传入的，
     * 避免把「曾经授权过」缓存成永久可用。
     */
    fun resolve(
        rootGranted: Boolean,
        shizukuGranted: Boolean,
        a11yConnected: Boolean
    ): Backend = when (_mode.value) {
        // v3.2.0：顺序反过来了。以前「有 accessibility 就先用 accessibility」，
        // 结果只要开过无障碍，Root/Shizuku 永远轮不到 —— 而 accessibility 每发一次
        // 手势都会取消用户正按着的摇杆，正是「连招时走不动」的根因。
        // 现在能用独立指针的先用，实在没有才退回无障碍。
        EngineMode.AUTO -> when {
            rootGranted -> Backend.ROOT
            shizukuGranted -> Backend.SHIZUKU
            a11yConnected -> Backend.ACCESSIBILITY
            else -> Backend.NONE
        }
        EngineMode.SHIZUKU -> if (shizukuGranted) Backend.SHIZUKU else Backend.NONE
        EngineMode.ACCESSIBILITY -> if (a11yConnected) Backend.ACCESSIBILITY else Backend.NONE
        EngineMode.ROOT -> if (rootGranted) Backend.ROOT else Backend.NONE
    }

    /** 面板上给的引导文案 */
    fun missingHint(
        mode: EngineMode,
        rootGranted: Boolean,
        shizukuGranted: Boolean,
        a11yConnected: Boolean
    ): String = when (mode) {
        EngineMode.AUTO ->
            if (a11yConnected) {
                "正在用无障碍兜底：能放技能，但每一下都会打断你按着的摇杆。" +
                    "想边走边放，请装 Shizuku 并授权，或获取 Root。"
            } else {
                "无可用的执行方式：装 Shizuku 并授权（推荐），或点「获取 Root 权限」，" +
                    "或开启无障碍服务"
            }
        EngineMode.SHIZUKU ->
            if (shizukuGranted) "Shizuku 会话已断开，请重新点「连接 Shizuku」"
            else "尚未连接 Shizuku：先安装并启动 Shizuku，再回来点「连接 Shizuku」"
        EngineMode.ACCESSIBILITY ->
            if (a11yConnected) {
                "无障碍已连接，但它每发一次点击都会取消你正按着的摇杆 —— 走位会一卡一卡"
            } else {
                "无障碍服务未连接，请到系统设置里开启云小抄的连招服务"
            }
        EngineMode.ROOT ->
            if (rootGranted) "Root 会话已断开，请重新点「获取 Root 权限」"
            else "尚未取得 Root 权限，请先点「获取 Root 权限」并允许授权"
    }
}
