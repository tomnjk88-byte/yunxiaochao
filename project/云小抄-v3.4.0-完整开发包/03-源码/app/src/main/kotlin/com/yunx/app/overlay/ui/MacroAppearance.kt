/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v1.8.4）。连招「专属按键」的外观组件，连招悬浮面板与
 * 主界面「连招按钮外观」分组共用同一套 UI。
 *
 * 为什么能两处共用：编辑器只接收 MacroConfig 与回调，不假定自己在 Activity 还是
 * Service 的悬浮窗里；[dark] 只切换文字明度（主界面是深色玻璃、面板是浅色卡片）。
 * 图片选择由调用方注入，因为悬浮窗没有 ActivityResultRegistryOwner。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yunx.app.overlay.macro.MacroConfig
import com.yunx.app.overlay.macro.MacroIcons
import java.io.File

/** 只画图标本体（自定义图片或内置 emoji），不带底色 —— 供「专属按键」嵌在自己的底色里 */
@Composable
fun MacroIconGlyph(macro: MacroConfig, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (size >= 34.dp) 12.dp else 9.dp)
    val file = macro.iconImagePath?.let { File(it) }?.takeIf { it.exists() }
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = "${macro.name} 的按钮图标",
            modifier = modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop
        )
    } else {
        Text(
            text = macro.iconPreset,
            fontSize = (size.value * 0.5f).sp,
            color = Color.White,
            maxLines = 1,
            modifier = modifier
        )
    }
}

/**
 * 一个连招按钮的图标徽章：优先显示用户自定义图片，没有则显示内置 emoji，
 * 底色用该连招的专属颜色。悬浮按钮（传统 View）另有等价实现，见 MacroBall。
 */
@Composable
fun MacroIconBadge(
    macro: MacroConfig,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val shape = RoundedCornerShape(if (size >= 34.dp) 12.dp else 9.dp)
    val base = Color(macro.displayColor)

    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.96f), base.copy(alpha = 0.72f))
                )
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape),
        contentAlignment = Alignment.Center
    ) {
        MacroIconGlyph(macro, size = size * 0.86f)
    }
}

/**
 * 专属按键外观编辑器：内置图标、专属颜色、相册换图、恢复默认。
 *
 * @param dark      true = 深色玻璃背景（主界面），false = 浅色卡片（连招悬浮面板）
 * @param onPickImage 拉起图片选择器；悬浮窗里走透明 Activity，主界面可直接 startActivity
 * @param onChange  返回改好外观的 MacroConfig，由调用方交给 MacroController.applyAppearance
 * @param allowImage 还没保存（录制结果待命名）时传 false：隐藏「换图片」，只让挑图标/颜色
 */
@Composable
fun MacroAppearanceEditor(
    macro: MacroConfig,
    onPickImage: () -> Unit,
    onChange: (MacroConfig) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    allowImage: Boolean = true
) {
    val titleColor = if (dark) Color.White else OverlayColors.TextPrimary
    val muted = if (dark) Color.White.copy(alpha = 0.68f) else OverlayColors.TextMuted
    val cellBg = if (dark) Color(0x1CFFFFFF) else Color(0x0A000000)
    val selectedColor = Color(macro.displayColor)

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MacroIconBadge(macro, size = 34.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "按钮外观",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                Text(
                    if (!macro.iconImagePath.isNullOrBlank()) {
                        "已用自定义图片 · 可随时更换"
                    } else {
                        "内置图标 + 专属颜色，每个连招各设各的"
                    },
                    fontSize = 8.sp,
                    color = muted,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("换图标", fontSize = 8.sp, color = muted)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(MacroIcons.PRESETS) { emoji ->
                val selected = macro.iconImagePath == null && macro.iconPreset == emoji
                val shape = RoundedCornerShape(7.dp)
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(shape)
                        .background(if (selected) selectedColor.copy(alpha = 0.3f) else cellBg)
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) selectedColor else Color.Transparent,
                            shape = shape
                        )
                        // 选内置图标时清掉自定义图片，否则图片会一直盖着 emoji
                        .clickable {
                            onChange(macro.copy(iconPreset = emoji, iconImagePath = null))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("换颜色", fontSize = 8.sp, color = muted)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(MacroIcons.COLORS) { c ->
                val swatch = if (c == 0) MacroIcons.DEFAULT_COLOR else c
                val selected = macro.iconColor == c
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Color.White else Color(0x33FFFFFF),
                            shape = CircleShape
                        )
                        .clickable { onChange(macro.copy(iconColor = c)) },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        c == 0 -> Text("默认", fontSize = 7.sp, color = Color.White)
                        selected -> Text("✓", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (allowImage) {
                AppearanceChip("🖼 换图片", highlighted = true, dark = dark, onClick = onPickImage)
                if (!macro.iconImagePath.isNullOrBlank()) {
                    AppearanceChip("清除图片", dark = dark) {
                        onChange(macro.copy(iconImagePath = null))
                    }
                }
            }
            AppearanceChip("恢复默认", dark = dark) {
                onChange(
                    macro.copy(
                        iconPreset = MacroIcons.DEFAULT,
                        iconImagePath = null,
                        iconColor = 0
                    )
                )
            }
            if (!allowImage) {
                Text(
                    "保存后可换成自己的图片",
                    fontSize = 8.sp,
                    color = muted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AppearanceChip(
    text: String,
    dark: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    highlighted -> overlayAccent()
                    dark -> Color(0x1CFFFFFF)
                    else -> Color(0x12000000)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 9.sp,
            color = when {
                highlighted -> Color.White
                dark -> Color.White
                else -> OverlayColors.TextSecondary
            },
            maxLines = 1
        )
    }
}
