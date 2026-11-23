/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.4.0）。「我的 → 开屏动画」设置卡：开关、图片 / 视频切换、
 *   自由导入、停留时长、就地预览、清除。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunx.app.overlay.data.SplashPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 单张图片上限：开屏不需要 4K 原图，太大只是白占空间、拖慢首帧 */
private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

/** 视频上限：超过这个体积复制一次要好几秒，用户会以为卡死 */
private const val MAX_VIDEO_BYTES = 80L * 1024 * 1024

/**
 * 开屏动画设置。自带分组标题与卡片，直接插进「我的」页即可。
 *
 * 所有状态都在本卡片内部读写（不进 MainActivity 的那一大坨 remember）：
 * 开屏只在下次冷启动生效，改它不需要让主页重组。
 */
@Composable
fun SplashSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(SplashPrefs.enabled(context)) }
    var kind by remember { mutableIntStateOf(SplashPrefs.kind(context)) }
    var path by remember { mutableStateOf(SplashPrefs.path(context)) }
    var name by remember { mutableStateOf(SplashPrefs.displayName(context)) }
    var hold by remember { mutableIntStateOf(SplashPrefs.holdIndex(context)) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    // 默认收起：这一整张卡只在「我的」页占一行，想改才展开，
    // 免得又把这页拉得老长（用户之前专门提过「我的」太杂）
    var expanded by remember { mutableStateOf(false) }

    fun import(uri: Uri?, k: Int) {
        if (uri == null) {
            // 用户在选择器里按了返回：类型跟着回滚，免得「界面写着视频、存的还是图片」
            kind = SplashPrefs.kind(context)
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { copyIn(context, uri, k) } }
            result.onSuccess { picked ->
                // 旧文件换掉，别让「换过五次」白占几十 MB
                SplashPrefs.path(context)?.let { old ->
                    if (old != picked.file.absolutePath) runCatching { File(old).delete() }
                }
                SplashPrefs.setPath(context, picked.file.absolutePath)
                SplashPrefs.setDisplayName(context, picked.name)
                SplashPrefs.setKind(context, k)
                SplashPrefs.setEnabled(context, true)
                path = picked.file.absolutePath
                name = picked.name
                kind = k
                enabled = true
            }.onFailure {
                toast(context, "导入失败：${it.message}")
            }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> import(uri, kind) }

    // v3.0.1：搬到「主题设置 → 交互手势」之后，上面已经有一排「开屏动画」风格选项，
    // 这张卡管的是素材本身，标题叫「开屏素材」才分得清。
    GlassSectionTitle("开屏素材")
    GlassCard {
        GlassRow(
            title = "自定义开屏",
            subtitle = when {
                !enabled -> "已关闭"
                path == null -> "内置 · 图标 + 名字"
                else -> name ?: "已选择文件"
            },
            icon = Icons.Rounded.PlayArrow,
            onClick = { expanded = !expanded }
        ) {
            GlassSwitch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    SplashPrefs.setEnabled(context, it)
                    if (it) expanded = true
                }
            )
        }

        if (enabled && expanded) {
            GlassDivider()
            Column(Modifier.padding(14.dp)) {
                Text(
                    "内容",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(10.dp))
                GlassSegmented(
                    options = listOf("图片", "视频"),
                    selected = kind,
                    onSelect = { k ->
                        if (k != kind) {
                            // 只改界面，等文件真的选进来再落盘 —— 取消选择时
                            // 类型不会跟已存的文件对不上（不然下次开屏会拿图片当视频播）
                            kind = k
                            picker.launch(
                                if (k == SplashPrefs.KIND_VIDEO) "video/*" else "image/*"
                            )
                        }
                    }
                )
            }

            GlassDivider()
            GlassRow(
                title = if (path == null) "选择文件" else "更换文件",
                subtitle = name ?: if (kind == SplashPrefs.KIND_VIDEO) "mp4 / webm，≤ 80 MB"
                else "jpg / png / webp，≤ 20 MB",
                icon = Icons.Rounded.Image,
                onClick = {
                    if (!busy) {
                        picker.launch(
                            if (kind == SplashPrefs.KIND_VIDEO) "video/*" else "image/*"
                        )
                    }
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = GlassColors.Accent
                    )
                }
            }

            if (kind == SplashPrefs.KIND_IMAGE) {
                GlassDivider()
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "停留",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Text
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassSegmented(
                        options = SplashPrefs.HOLD_LABELS,
                        selected = hold,
                        onSelect = {
                            hold = it
                            SplashPrefs.setHoldIndex(context, it)
                        }
                    )
                }
            } else {
                GlassDivider()
                GlassHint("视频静音播放，播完自动进入主界面；最长播 15 秒，随时轻触跳过。")
            }

            if (path != null) {
                GlassDivider()
                GlassRow(
                    title = "预览一次",
                    subtitle = "",
                    icon = Icons.Rounded.PlayCircle,
                    onClick = { preview = true }
                ) {}
                GlassDivider()
                GlassRow(
                    title = "清除",
                    subtitle = "回到内置开屏",
                    icon = Icons.Rounded.Delete,
                    iconTint = GlassColors.TextMuted,
                    onClick = {
                        runCatching { File(path!!).delete() }
                        SplashPrefs.setPath(context, null)
                        SplashPrefs.setDisplayName(context, null)
                        // 不清总开关：没文件就播内置那套，想彻底关就关上面的开关
                        path = null
                        name = null
                    }
                ) {}
            }
        }
    }

    // 就地预览：和真正启动时同一套 Composable，看到什么就是什么
    if (preview) {
        val p = path
        if (p != null) {
            Dialog(
                onDismissRequest = { preview = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                SplashOverlay(
                    path = p,
                    kind = kind,
                    holdMs = SplashPrefs.holdMs(context),
                    onFinish = { preview = false }
                )
            }
        }
    }
}

private class PickedFile(val file: File, val name: String)

/** 复制进私有目录。用流拷贝而不是一次性 readBytes —— 视频可能有几十 MB */
private fun copyIn(context: Context, uri: Uri, kind: Int): PickedFile {
    val limit = if (kind == SplashPrefs.KIND_VIDEO) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
    val suffix = if (kind == SplashPrefs.KIND_VIDEO) ".mp4" else ".img"
    val dest = File(context.filesDir, "splash_${System.currentTimeMillis()}$suffix")
    var copied = 0L
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > limit) {
                        error(
                            if (kind == SplashPrefs.KIND_VIDEO) "视频超过 80 MB，换个小点的"
                            else "图片超过 20 MB，换个小点的"
                        )
                    }
                    output.write(buf, 0, n)
                }
            }
        } ?: error("无法读取所选文件")
        if (copied <= 0L) error("文件是空的")
        val name = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "已选择文件"
        return PickedFile(dest, name)
    } catch (t: Throwable) {
        // 半截文件留在私有目录只会白占空间
        runCatching { dest.delete() }
        throw t
    }
}

private fun toast(context: Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}
