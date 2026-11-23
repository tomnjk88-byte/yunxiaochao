/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v1.8.4）。连招「专属按键」自定义图片的本地存储。
 *
 * 为什么不直接存 content:// Uri：SAF 授权需要 takePersistableUriPermission，
 * 用户换图 / 卸载重装 / 部分 ROM 清理后容易失效，按钮就变成空白。这里在选图的
 * 当下把图片字节复制到 app 私有 filesDir，之后只依赖一个普通文件路径 —— 零权限、
 * 跨重启有效、和「小抄球自定义图标」是同一套做法。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import android.net.Uri
import java.io.File

object MacroIconStorage {

    /**
     * 把用户选的图片固化成「这个连招专属」的私有文件。
     * 文件名按 macroId 固定，换图直接覆盖，不会在私有目录里越堆越多。
     *
     * @return 绝对路径；失败返回 null（调用方会提示用户重试）
     */
    fun save(context: Context, macroId: String, uri: Uri): String? = runCatching {
        val dest = File(context.filesDir, fileName(macroId))
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选图片")
        input.use { src ->
            dest.outputStream().use { out -> src.copyTo(out) }
        }
        if (dest.length() == 0L) {
            dest.delete()
            error("所选图片为空")
        }
        dest.absolutePath
    }.getOrNull()

    /** 换图 / 恢复默认 / 删除连招时清掉旧文件 */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    private fun fileName(macroId: String): String =
        "macro_icon_${macroId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.img"
}
