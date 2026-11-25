/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.4.7 新增。菜单键连点三下弹出的「导航方式」切换。
 *
 * 两张示意图：上面保持完整（不削），只在底部把 Dock 的位置留出来 ——
 * 抽屉那张下面直接结束，Dock 那张下面留一条胶囊位，一眼能看出区别。
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 示意手机里的底色与墨色。固定中性色 —— 不跟主题走，两张图对比才清楚 */
private val PREVIEW_BG = Color(0xFFEDF0F5)
private val PREVIEW_INK = Color(0xFF2B3550)
private val PREVIEW_CARD = Color(0xFFFBFCFE)

/**
 * 导航方式切换。
 *
 * 入口两处：菜单键连点三下、主题设置 → 底栏与导航。
 */
@Composable
fun NavSwitchDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val nav by GuideStyleStore.nav.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "导航方式",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassColors.Text
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "点一下立刻切换，另一种同时停用。以后连点顶栏菜单键三下也能调出这里。",
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp,
                        color = GlassColors.TextMuted
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NavOption(
                            style = NavStyle.DRAWER,
                            selected = nav == NavStyle.DRAWER,
                            modifier = Modifier.weight(1f)
                        ) {
                            GuideStyleStore.selectNav(context, NavStyle.DRAWER)
                            onDismiss()
                        }
                        NavOption(
                            style = NavStyle.DOCK,
                            selected = nav == NavStyle.DOCK,
                            modifier = Modifier.weight(1f)
                        ) {
                            GuideStyleStore.selectNav(context, NavStyle.DOCK)
                            onDismiss()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "也可以去「主题设置 → 底栏与导航」里改。",
                        fontSize = 9.sp,
                        color = GlassColors.TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun NavOption(
    style: NavStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(
                if (selected) GlassColors.Accent.copy(alpha = 0.13f)
                else Color.Black.copy(alpha = 0.04f)
            )
            .border(
                width = if (selected) 1.6.dp else 1.dp,
                color = if (selected) GlassColors.Accent else GlassColors.Divider,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // 上面完整保留，只在底部为 Dock 留位置 —— 这就是「上面不削、下面削」
        Box(
            Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PREVIEW_BG)
        ) {
            if (style == NavStyle.DRAWER) {
                // 左侧抽屉条：从顶到底，压住内容左半边
                Box(
                    Modifier
                        .width(42.dp)
                        .fillMaxHeight()
                        .background(PREVIEW_INK.copy(alpha = 0.10f))
                ) {
                    Column(Modifier.padding(7.dp)) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(GlassColors.Accent.copy(alpha = 0.55f))
                        )
                        Spacer(Modifier.height(9.dp))
                        repeat(3) {
                            Box(
                                Modifier
                                    .width(24.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(PREVIEW_INK.copy(alpha = 0.34f))
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            // 内容区：标题 + 两张卡；Dock 模式底部留出胶囊位
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (style == NavStyle.DRAWER) 52.dp else 10.dp,
                        end = 10.dp,
                        top = 10.dp,
                        bottom = if (style == NavStyle.DOCK) 30.dp else 8.dp
                    )
            ) {
                Box(
                    Modifier
                        .width(48.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(PREVIEW_INK.copy(alpha = 0.62f))
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(30.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(PREVIEW_INK.copy(alpha = 0.26f))
                )
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(2) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(PREVIEW_CARD)
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(2) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(PREVIEW_CARD)
                        )
                    }
                }
            }
            if (style == NavStyle.DOCK) {
                // 底部胶囊 = Dock 的位置，下面那块留白就是从内容里让出来的
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp)
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(GlassColors.Accent.copy(alpha = 0.42f))
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                style.label,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Text
            )
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "已选",
                    modifier = Modifier.size(14.dp),
                    tint = GlassColors.Accent
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            if (style == NavStyle.DOCK) "底部四格常驻，一下切页" else "一屏只有内容，边缘滑出",
            fontSize = 8.5.sp,
            lineHeight = 12.sp,
            color = GlassColors.TextMuted
        )
    }
}
