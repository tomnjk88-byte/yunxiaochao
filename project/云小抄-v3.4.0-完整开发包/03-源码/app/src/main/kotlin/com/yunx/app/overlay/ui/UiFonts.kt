/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.0.2 新增。界面字体：跟随系统 / 选一个 .ttf .otf 装进来。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * 界面字体。
 *
 * **跟随系统**（[SYSTEM]）：不给任何 fontFamily，Compose 用的是系统默认字体。
 * 在系统主题商店换过字体的手机，绝大多数 ROM 会把默认字体一起换掉，
 * 本 App 不用额外适配 —— 只是要重开一次 App 才刷新（系统只通知新启动的进程）。
 *
 * **自定义字体**：从文件选择器挑 .ttf / .otf，复制进私有目录再按文件加载。
 * 只存文件名不存绝对路径：应用数据目录在系统升级 / 迁移后可能换位置，
 * 存绝对路径会变成一条指向虚空的死路径。
 */
object UiFonts {

    /** 跟系统走 */
    const val SYSTEM = ""

    private const val DIR = "fonts"

    /** 固定文件名：换字体就是覆盖它，不留一堆旧文件 */
    private const val FILE_NAME = "ui_font.ttf"

    private fun dirOf(context: Context): File = File(context.filesDir, DIR)

    /**
     * 导入一个字体文件。
     *
     * @return 存下来的文件名；读不了 / 不是字体返回 null。
     */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val dir = dirOf(context).apply { mkdirs() }
        val dest = File(dir, FILE_NAME)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("读不到所选文件")
        // 先真解一次：选错文件（图片 / apk）时 createFromFile 会抛，
        // 让它在这里失败，别等到整页文字都变成方框才发现。
        Typeface.createFromFile(dest)
        dest.name
    }.getOrNull()

    /** 加载；key 为空或文件不在就返回 null（调用方退回系统字体） */
    fun load(context: Context, key: String): FontFamily? {
        if (key.isBlank()) return null
        val file = File(dirOf(context), key)
        if (!file.exists()) return null
        return runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrNull()
    }

    fun clear(context: Context, key: String) {
        if (key.isBlank()) return
        runCatching { File(dirOf(context), key).delete() }
    }
}
