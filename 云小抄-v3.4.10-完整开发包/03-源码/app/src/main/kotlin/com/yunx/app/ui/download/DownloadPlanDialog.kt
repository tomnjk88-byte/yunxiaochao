/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.yunx.app.ui.download

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.5.9「下载清单」的一项。
 *
 * 下载页粘多条链接、分享解析页多选文件，最终都归一成这个模型，
 * 共用同一套「勾选 + 逐项指定目录」的界面 —— 两处各写一份迟早会长歪。
 */
data class DownloadPlanItem(
    /** 稳定标识：分享文件用 fid，手输链接用链接本身 */
    val key: String,
    val title: String,
    val subtitle: String,
    /** 右侧小标记，例如「文件夹」 */
    val badge: String = ""
)

/**
 * 下载清单弹窗。
 *
 * 设计要点（对应用户明确提的几条）：
 *  1. **默认一个都不勾** —— 不给「默认全下」，用户必须自己选；
 *  2. 每项都能单独挑目录，也可以「统一给所有已选项挑一个目录」；
 *  3. 目录一律走 ACTION_OPEN_DOCUMENT_TREE 并 takePersistableUriPermission，
 *     重启后仍然可写；
 *  4. 点「开始下载」前逐项校验：没有任何一项 → 禁用；有项没有有效目录 →
 *     弹窗点名是哪几个文件，并允许直接补选；
 *  5. 上一次用过的目录会被记住：系统选择器直接落在那儿，顶部还给一颗快捷按钮。
 */
@Composable
fun DownloadPlanDialog(
    items: List<DownloadPlanItem>,
    /** 全局「设置 → 下载目录」；非空时作为每一项的初始值，并明确显示出来（不是静默落盘） */
    defaultDirUri: String?,
    /**
     * 进来时就已经勾上的项。
     *
     * 从「多选文件 → 下载」进来时，把用户刚手选的那几项带上 —— 那是他刚表达过的意图，
     * 再让他从零勾一遍纯属折腾。粘链接进来则传空集合，一个都不预勾。
     * 无论哪种，用户都能在清单里改。
     */
    initialSelected: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    /** picked = 最终要下的 key；dirs = 每个 key 的**有效**目录（已把默认值算进去） */
    onConfirm: (List<String>, Map<String, String>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // 默认全不勾：用户必须逐项确认
    val checked = remember(items) {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.key, false) }
            items.forEach { if (it.key in initialSelected) put(it.key, true) }
        }
    }
    val dirs = remember(items) { mutableStateMapOf<String, String>() }
    var pickTarget by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf<List<DownloadPlanItem>>(emptyList()) }
    var conflict by remember { mutableStateOf(settings.conflictPolicy) }
    var busy by remember { mutableStateOf(false) }

    val lastDir = remember { settings.lastPlanDirUri }

    val picker = rememberLauncherForActivityResult(OpenTreeAtInitial()) { uri ->
        val target = pickTarget
        pickTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        settings.lastPlanDirUri = uri.toString()
        val value = uri.toString()
        if (target == UNIFIED_TARGET) {
            // 统一目录：套给所有已勾选项
            items.filter { checked[it.key] == true }.forEach { dirs[it.key] = value }
        } else if (target != null) {
            dirs[target] = value
            // 顺手勾上：用户既然给它选了目录，大概率就是要下它
            checked[target] = true
        }
    }

    fun launchPick(target: String) {
        pickTarget = target
        runCatching { picker.launch(settings.lastPlanDirUri) }
            .onFailure { picker.launch(null) }
    }

    val picked = items.filter { checked[it.key] == true }
    val effectiveDir: (String) -> String =
        { key -> dirs[key]?.takeIf { it.isNotBlank() } ?: defaultDirUri.orEmpty() }
    val lackingCount = picked.count { effectiveDir(it.key).isBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要下载的内容") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 全选 / 反选 / 清空
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已选 ${picked.size}/${items.size}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.weight(1f))
                    SmallAction("全选") { items.forEach { checked[it.key] = true } }
                    SmallAction("反选") { items.forEach { checked[it.key] = !(checked[it.key] ?: false) } }
                    SmallAction("清空") { items.forEach { checked[it.key] = false } }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { launchPick(UNIFIED_TARGET) },
                        enabled = picked.isNotEmpty()
                    ) {
                        Text(
                            "统一给已选 ${picked.size} 项选目录",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    lastDir?.takeIf { it.isNotBlank() }?.let { prev ->
                        TextButton(onClick = {
                            items.filter { checked[it.key] == true }.forEach { dirs[it.key] = prev }
                        }) { Text("上次：${dirLabel(prev)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    items.forEach { item ->
                        val dir = effectiveDir(item.key)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { checked[item.key] = !(checked[item.key] ?: false) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked[item.key] == true,
                                onCheckedChange = { checked[item.key] = it }
                            )
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.title.ifBlank { item.key },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (item.badge.isNotBlank()) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            item.badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    text = if (dir.isBlank()) "未选择保存位置" else dirLabel(dir),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (dir.isBlank()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.subtitle.isNotBlank()) {
                                    Text(
                                        item.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(onClick = { launchPick(item.key) }) {
                                Text(if (dir.isBlank()) "选目录" else "更改")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 重名策略：三选一，全局生效（写进设置，下次打开还是这个）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("重名时", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    listOf(
                        SettingsRepository.CONFLICT_RENAME,
                        SettingsRepository.CONFLICT_OVERWRITE,
                        SettingsRepository.CONFLICT_SKIP
                    ).forEach { policy ->
                        PolicyChip(
                            text = SettingsRepository.conflictLabel(policy),
                            selected = conflict == policy
                        ) {
                            conflict = policy
                            settings.conflictPolicy = policy
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = picked.isNotEmpty() && !busy,
                onClick = {
                    val bad = picked.filter { effectiveDir(it.key).isBlank() }
                    if (bad.isNotEmpty()) {
                        missing = bad
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val finalDirs = picked.associate { it.key to effectiveDir(it.key) }
                        val skipped = if (conflict == SettingsRepository.CONFLICT_SKIP) {
                            withContext(Dispatchers.IO) {
                                picked.filter {
                                    DownloadSaver.existsInDir(
                                        context, it.title, finalDirs[it.key]
                                    ) != null
                                }.map { it.key }
                            }
                        } else {
                            emptyList()
                        }
                        val real = picked.map { it.key }.filterNot { it in skipped }
                        busy = false
                        if (real.isEmpty() && skipped.isNotEmpty()) {
                            missing = emptyList()
                            onDismiss()
                            android.widget.Toast.makeText(
                                context,
                                "所选文件在目标目录里都已存在，已按「同名跳过」跳过",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            if (skipped.isNotEmpty()) {
                                android.widget.Toast.makeText(
                                    context,
                                    "已跳过 ${skipped.size} 个同名文件",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            onConfirm(real, finalDirs.filterKeys { it in real })
                        }
                    }
                }
            ) { Text(if (picked.isEmpty()) "先勾选文件" else "开始下载 ${picked.size} 项") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    // 未选目录的拦截提示：点名是哪几个文件，并给一键补选
    if (missing.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { missing = emptyList() },
            title = { Text("还有 ${missing.size} 个文件没选保存位置") },
            text = {
                Column {
                    Text(
                        "为了不把文件悄悄塞进默认目录，请先给下面这些文件选一个文件夹：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    missing.take(6).forEach {
                        Text("· ${it.title.ifBlank { it.key }}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (missing.size > 6) {
                        Text("… 等 ${missing.size} 个", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val first = missing.first()
                    missing = emptyList()
                    launchPick(first.key)
                }) { Text("给它选目录") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // 有全局目录时允许一次性套用（那本身就是用户配置过的位置）
                        val fallback = defaultDirUri
                        if (!fallback.isNullOrBlank()) {
                            missing.forEach { dirs[it.key] = fallback }
                            missing = emptyList()
                        }
                    }) { Text(if (defaultDirUri.isNullOrBlank()) "取消" else "都用默认目录") }
                }
            }
        )
    }
}

private const val UNIFIED_TARGET = "__all__"

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun PolicyChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 打开系统目录选择器，并直接落到上次用过的目录。
 *
 * 系统自带的 `OpenDocumentTree()` 不接受初始位置参数，所以这里自定义一个 Contract；
 * `EXTRA_INITIAL_URI` 需要 API 26，低版本直接忽略（不影响选择，只是从头开始翻）。
 */
private class OpenTreeAtInitial : ActivityResultContract<String?, Uri?>() {
    override fun createIntent(context: Context, input: String?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !input.isNullOrBlank()) {
            runCatching {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(input))
            }
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * SAF tree uri → 人能看懂的路径。
 *
 * `content://com.android.externalstorage.documents/tree/primary%3ADownload%2F游戏`
 * 解析成 `Download/游戏`；解析不出来就退回末段，不显示一长串 content://。
 */
fun dirLabel(uri: String): String {
    val decoded = runCatching {
        Uri.decode(uri.substringAfter("/tree/", uri))
    }.getOrDefault(uri)
    val path = decoded.substringAfter(':', decoded)
    return path.substringAfterLast('/').ifBlank { path.ifBlank { "已选目录" } }
}
