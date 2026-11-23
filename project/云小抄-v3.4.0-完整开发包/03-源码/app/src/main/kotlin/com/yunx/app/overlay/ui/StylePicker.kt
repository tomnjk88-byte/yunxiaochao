/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.11 风格选择器（弹窗 + 内嵌两种用法）。
 *   - 每个选项左边是一张真正的「缩略预览」：底色、卡片、底栏都按对应风格画，
 *     不是三张静态图片，所以风格微调后预览会跟着变；
 *   - 选中立即生效并落盘（OverlayPrefs.uiStyleKey），下次启动还是这套。
 * v1.9.14：弹窗顶部增加「白天 / 黑夜 / 跟随系统」三档；缩略图也按当前日夜渲染，
 * 否则在浅色界面里点开弹窗，五张预览全是黑的 —— 选完才发现完全不一样。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 白天 / 黑夜三档。放在「我的 → 界面风格」和换肤弹窗里，和风格是两件事：
 * 风格决定材质，日夜只决定底色 —— 两套风格都能各配白天或黑夜。
 */
@Composable
fun ThemeModePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by GuideStyleStore.mode.collectAsState()
    GlassSegmented(
        options = UiMode.entries.map { it.label },
        selected = mode.ordinal.coerceIn(0, UiMode.entries.lastIndex),
        onSelect = { index ->
            UiMode.entries.getOrNull(index)?.let { GuideStyleStore.selectMode(context, it) }
        },
        modifier = modifier
    )
}

/**
 * 风格列表。 [compact] = true 时用在「我的」页面里（无标题、无底部按钮）。
 */
@Composable
fun StylePicker(
    selected: UiStyle,
    onSelect: (UiStyle) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val t = LocalUiTokens.current
    Column(modifier.fillMaxWidth()) {
        UiStyle.entries.forEach { style ->
            val active = style == selected
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (active) t.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .border(1.dp, if (active) t.accent.copy(alpha = 0.45f) else t.stroke, shape)
                    .clickable { onSelect(style) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StyleThumb(style, Modifier.width(62.dp).height(46.dp))
                Spacer(Modifier.width(12.dp))
                // v2.0.10：风格说明那两行小字删掉，只留名字 + 缩略图 + 勾
                Column(Modifier.weight(1f)) {
                    Text(
                        style.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.text
                    )
                }
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = t.accent
                    )
                }
            }
            if (style != UiStyle.entries.last()) Spacer(Modifier.height(if (compact) 6.dp else 7.dp))
        }
    }
}

/** 换肤弹窗：顶栏那颗调色盘按钮、以及「我的」页的「切换 ›」都走这里 */
@Composable
fun StylePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = LocalUiStyle.current
    val t = LocalUiTokens.current
    val panel = RoundedCornerShape(26.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .shadow(30.dp, panel, ambientColor = Color(0x66000000), spotColor = Color(0x665E7CFF))
                .clip(panel)
                // 弹窗是独立窗口，半透明会漏出窗口后面的黑，所以用 tokens 里的不透明表面色
                .background(t.solid)
                // 两套风格 + 三档质感 + 日夜在小屏上也可能超一屏：整块可滚，别把「完成」顶出去
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "外观",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = t.text
            )

            Spacer(Modifier.height(14.dp))
            Text("白天 / 黑夜", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = t.textSub)
            Spacer(Modifier.height(7.dp))
            ThemeModePicker()

            Spacer(Modifier.height(16.dp))
            Text("界面风格", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = t.textSub)
            Spacer(Modifier.height(7.dp))
            StylePicker(
                selected = current,
                onSelect = { style ->
                    if (style != current) {
                        StyleCurtain.switch(context, style, t.bg)
                        // 弹窗是独立 window，主窗口那层遮罩盖不住它：留着的话
                        // 用户只能看到弹窗里的字变了，外面整场碎块全被挡住。
                        // 选完就关，正好抬头看新皮肤一块块散开。
                        onDismiss()
                    }
                }
            )
            // 液态玻璃才有玻璃质感档位，和「我的」页里那组是同一个开关
            if (current == UiStyle.LIQUID) {
                Spacer(Modifier.height(13.dp))
                GlassQualityPicker()
            }
            Spacer(Modifier.height(14.dp))
            GlassPrimaryButton(text = "完成", onClick = onDismiss)
        }
    }
}

/**
 * 缩略预览：用真实 tokens 画一张迷你界面（两行卡片 + 一条底栏）。
 * 风格 2 额外画两团彩色光斑，风格 3 用浅色底。
 */
@Composable
private fun StyleThumb(style: UiStyle, modifier: Modifier = Modifier) {
    val dark = LocalUiTokens.current.dark
    val t = remember(style, dark) { tokensFor(style, dark) }
    val frosted = style == UiStyle.LIQUID
    val cardShape = RoundedCornerShape(7.dp)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(t.bg)) {
        // 各风格的代表性壁纸
        val fit: (Color) -> Color = { c -> if (dark) c else c.copy(alpha = c.alpha * 0.55f) }
        when (style) {
            // 液态玻璃：三团高饱和光斑（真实背景也是这一套）
            UiStyle.LIQUID -> {
                ThumbBlob(52.dp, fit(Color(0xBB4C6FFF)), Alignment.TopStart)
                ThumbBlob(46.dp, fit(Color(0x9912D9D0)), Alignment.CenterEnd)
                ThumbBlob(44.dp, fit(Color(0x998B5CF6)), Alignment.BottomStart)
            }
            // 原生：不加光斑，纯色底
            UiStyle.NATIVE -> Unit
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 顶栏：一道短标题 + 右上角一颗小圆点
            Row(
                Modifier.fillMaxWidth().height(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(24.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(t.text.copy(alpha = 0.85f))
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(t.accent.copy(alpha = 0.9f))
                )
            }
            // 两张卡片
            repeat(2) { i ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(13.dp)
                        .clip(cardShape)
                        .background(
                            if (style == UiStyle.NATIVE) {
                                Brush.verticalGradient(listOf(t.cardTop, t.cardTop))
                            } else {
                                Brush.verticalGradient(listOf(t.cardTop, t.cardBottom))
                            }
                        )
                        .border(
                            if (frosted && i == 0) 1.dp else 1.dp,
                            when {
                                style == UiStyle.NATIVE -> t.cardBorderTop
                                frosted && i == 0 -> Color(0xB3FFFFFF)
                                t.style == UiStyle.LIQUID && i == 0 -> Color(0x99FFFFFF)
                                else -> t.cardBorderTop
                            },
                            cardShape
                        )
                )
            }
        }
    }
}

/** 缩略图里的一团光斑，位置靠 align */
@Composable
private fun BoxScope.ThumbBlob(size: androidx.compose.ui.unit.Dp, color: Color, align: Alignment) {
    Box(
        Modifier
            .align(align)
            .size(size)
            .background(Brush.radialGradient(listOf(color, Color.Transparent)))
    )
}
