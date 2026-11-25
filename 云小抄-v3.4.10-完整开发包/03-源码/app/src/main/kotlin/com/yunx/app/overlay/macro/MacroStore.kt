/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。连招宏的本地持久化（SharedPreferences + JSON）。
 * 只存用户自己录的坐标和参数，不上传、不联网。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class MacroStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _macros = MutableStateFlow(load())
    val macros: StateFlow<List<MacroConfig>> = _macros.asStateFlow()

    fun get(id: String): MacroConfig? = _macros.value.firstOrNull { it.id == id }

    fun add(macro: MacroConfig) {
        publish(_macros.value + macro)
    }

    /** v1.9.15：导入是一次加多条，合并成一次写盘，避免 N 次 SharedPreferences 提交 */
    fun addAll(list: List<MacroConfig>) {
        if (list.isEmpty()) return
        publish(_macros.value + list)
    }

    fun update(macro: MacroConfig) {
        val list = _macros.value.toMutableList()
        val index = list.indexOfFirst { it.id == macro.id }
        if (index < 0) {
            list.add(macro)
        } else {
            list[index] = macro
        }
        publish(list)
    }

    /** 拖动悬浮球时避免每帧写盘：只在手指抬起时调用 */
    fun updateBallPosition(id: String, x: Int, y: Int) {
        val current = get(id) ?: return
        update(current.copy(ballX = x, ballY = y))
    }

    fun remove(id: String) {
        publish(_macros.value.filterNot { it.id == id })
    }

    fun pinned(): List<MacroConfig> = _macros.value.filter { it.pinned }

    private fun publish(list: List<MacroConfig>) {
        prefs.edit()
            .putString(
                KEY_MACROS,
                JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }.toString()
            )
            .apply()
        _macros.value = list
    }

    private fun load(): List<MacroConfig> {
        val raw = prefs.getString(KEY_MACROS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { MacroConfig.fromJson(it) }
            }.filter { it.steps.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "yunx_macro"
        private const val KEY_MACROS = "macros"

        @Volatile
        private var instance: MacroStore? = null

        fun get(context: Context): MacroStore =
            instance ?: synchronized(this) {
                instance ?: MacroStore(context.applicationContext).also { instance = it }
            }
    }
}
