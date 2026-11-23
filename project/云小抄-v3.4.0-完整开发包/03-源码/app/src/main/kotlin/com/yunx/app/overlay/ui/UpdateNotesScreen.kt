/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 说明：新增文件（v2.6.0）。独立的「更新说明」页 —— 按版本号倒序、每版拆
 *   版本号 / 日期 / 新增功能 / 优化体验 / 修复 Bug 五段，数据来自
 *   UpdateNotesData.kt（内置在安装包里，不联网、不读外部文件）。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UpdateNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏固定：只滚列表，标题与返回一直在
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x10FFFFFF)))
                    )
                    .border(1.dp, GlassColors.Stroke, RoundedCornerShape(11.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = GlassColors.Text
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "更新说明",
                    fontSize = 18.sp,
                    color = GlassColors.Text,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "共 ${UPDATE_NOTES.size} 个版本 · 按版本号倒序",
                    fontSize = 10.sp,
                    color = GlassColors.TextMuted
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(UPDATE_NOTES) { note ->
                val isCurrent = note.version.trimStart('v') == currentVersion.trimStart('v')
                UpdateNoteCard(note = note, isCurrent = isCurrent)
            }
        }
    }
}

@Composable
private fun UpdateNoteCard(note: UpdateNote, isCurrent: Boolean) {
    GlassCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.version,
                    fontSize = 15.sp,
                    color = GlassColors.Text,
                    fontWeight = FontWeight.Bold
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "当前版本",
                        fontSize = 9.5.sp,
                        color = GlassColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GlassColors.Accent.copy(alpha = 0.14f))
                            .border(1.dp, GlassColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(note.date, fontSize = 10.sp, color = GlassColors.TextMuted)
            }

            if (!note.hasContent) {
                Spacer(Modifier.height(6.dp))
                Text("本版为细节调整。", fontSize = 11.sp, color = GlassColors.TextSub)
                return@Column
            }

            NoteGroup("新增功能", note.added, GlassColors.Accent)
            NoteGroup("优化体验", note.improved, GlassColors.Ok)
            NoteGroup("修复 Bug", note.fixed, GlassColors.Warn)
        }
    }
}

@Composable
private fun NoteGroup(title: String, items: List<String>, tint: Color) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(9.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            fontSize = 10.5.sp,
            color = tint,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(4.dp))
    items.forEach { line ->
        Row(Modifier.padding(bottom = 3.dp)) {
            Text("·", fontSize = 11.sp, lineHeight = 18.sp, color = GlassColors.TextMuted)
            Spacer(Modifier.width(5.dp))
            Text(
                line,
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = GlassColors.TextSub
            )
        }
    }
}
