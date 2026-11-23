/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.15 连招分享的 Android 侧实现：写文件、拉起系统分享、
 * 读写剪贴板、从 content:// 读文本。纯编解码逻辑在 MacroShare.kt，这里只碰系统 API。
 */

package com.yunx.app.overlay.macro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MacroShareIo {

    private const val CACHE_DIR = "macro_share"

    /** 只清一天以前的，避免用户刚分享完又点一次时文件没了 */
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** 当前设备参数：坐标由 MotionEvent.rawX / rawY 得到，和 displayMetrics 同一坐标系 */
    fun device(context: Context): MacroShareDevice {
        val metrics = context.resources.displayMetrics
        return MacroShareDevice(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.density
        )
    }

    fun payload(context: Context, macros: List<MacroConfig>): MacroSharePayload =
        MacroSharePayload(macros, device(context))

    /**
     * 写一个 .yxmacro 到缓存目录，然后拉起系统分享面板。
     * 不申请任何存储权限：FileProvider 只把这一个文件临时授权给接收方。
     */
    fun share(context: Context, macros: List<MacroConfig>): Boolean = runCatching {
        if (macros.isEmpty()) return false
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        prune(dir)

        val file = File(dir, fileName(macros))
        file.writeText(MacroShare.encode(payload(context, macros)), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MacroShare.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, shareMessage(macros))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享连招").apply {
            // 部分接收方会用自己的 Intent 再读一次 uri，外层也要带授权
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 悬浮面板传进来的是 Service context，不加这个 flag 会直接崩
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)

    /** 复制成一行口令，发给对方后他复制、进 App 点「从剪贴板导入」即可 */
    fun copyCode(context: Context, macros: List<MacroConfig>): Boolean = runCatching {
        if (macros.isEmpty()) return false
        val code = MacroShare.encodeCode(payload(context, macros))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("云小抄连招", code))
        true
    }.getOrDefault(false)

    fun clipboardText(context: Context): String? = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()
    }.getOrNull()

    /** 从系统文件选择器 / 分享进来的 content:// 读文本；超过 2MB 直接判失败 */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = readLimited(input, MacroShare.MAX_PAYLOAD + 1)
            if (bytes.size > MacroShare.MAX_PAYLOAD) null else String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    fun shareMessage(macros: List<MacroConfig>): String {
        val names = macros.take(5).joinToString("、") { it.name }
        val more = if (macros.size > 5) " 等 ${macros.size} 条" else ""
        return buildString {
            append("云小抄 · 自动连招（")
            append(macros.size)
            append(" 条）\n")
            if (names.isNotBlank()) {
                append(names)
                append(more)
                append("\n")
            }
            append("收到后：点开文件 → 选「云小抄」打开即可自动导入；")
            append("也可以打开云小抄 → 自动连招 →「从文件导入」选它。")
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun fileName(macros: List<MacroConfig>): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        val label = if (macros.size == 1) safeName(macros.first().name) else "连招x${macros.size}"
        return "云小抄-$label-$stamp.${MacroShare.EXT}"
    }

    private fun safeName(raw: String): String {
        val cleaned = raw.map { c ->
            if (c in "\\/:*?\"<>|" || c == '\n' || c == '\r' || c == '\t') '_' else c
        }.joinToString("").trim().trim('.')
        return cleaned.take(18).ifBlank { "连招" }
    }

    private fun prune(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > KEEP_MS) runCatching { file.delete() }
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
            if (out.size() >= limit) break
        }
        return out.toByteArray()
    }
}
