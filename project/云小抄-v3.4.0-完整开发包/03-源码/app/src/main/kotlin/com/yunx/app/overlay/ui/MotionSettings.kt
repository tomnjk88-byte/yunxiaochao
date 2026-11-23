/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件（v2.1.0）。动画开关与速度档位，只有两行控件、零说明文字。
 *   同一张卡片复用在「我的」页和顶栏调色盘弹窗，两处改的都是同一份 MotionPrefs。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.overlay.data.MotionPrefs

/**
 * 动画设置卡片。
 *
 * - 开关关掉 = 全 App 动画直接到终态（等于把动画砍掉）；
 * - 速度四档同时作用于小窗碎块、进软件开场、切页、连招窗、窗口伸缩；
 * - 关掉时把速度行收起来，避免留一排点不动的灰控件。
 */
@Composable
fun MotionSettingsCard() {
    val context = LocalContext.current
    val enabled by MotionPrefs.enabled.collectAsState()
    val speed by MotionPrefs.speed.collectAsState()
    val entrance by MotionPrefs.entrance.collectAsState()
    val switchStyle by MotionPrefs.switchStyle.collectAsState()

    GlassSectionTitle("动画")
    GlassCard {
        GlassRow(
            title = "界面动画",
            subtitle = "",
            icon = Icons.Rounded.Bolt,
            onClick = { MotionPrefs.setEnabled(context, !enabled) }
        ) {
            GlassSwitch(
                checked = enabled,
                onCheckedChange = { MotionPrefs.setEnabled(context, it) }
            )
        }
        if (enabled) {
            GlassDivider()
            Column(Modifier.padding(14.dp)) {
                Text(
                    "开屏",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(10.dp))
                // 0 = 碎格（细碎小格从左上往右下逐块出现）
                // 1 = 浮现（模块从下方一个个浮上来，更简约）
                GlassSegmented(
                    options = MotionPrefs.ENTRANCE_LABELS,
                    selected = entrance,
                    onSelect = { MotionPrefs.setEntrance(context, it) }
                )
                Spacer(Modifier.height(6.dp))
                GlassHint(
                    if (entrance == MotionPrefs.TILES) {
                        "整屏切成细格，从左上角一路碎到右下角"
                    } else {
                        "不铺碎格，标题和卡片逐块浮上来"
                    }
                )
            }
            GlassDivider()
            Column(Modifier.padding(14.dp)) {
                Text(
                    "切页",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(10.dp))
                GlassSegmented(
                    options = MotionPrefs.SWITCH_LABELS,
                    selected = switchStyle,
                    onSelect = { MotionPrefs.setSwitchStyle(context, it) }
                )
                Spacer(Modifier.height(6.dp))
                GlassHint(
                    if (switchStyle == MotionPrefs.TILES) {
                        "整页切成大方块，一块块弹开再换内容"
                    } else {
                        "不铺碎块，新页面的模块依次浮起"
                    }
                )
            }
            GlassDivider()
            Column(Modifier.padding(14.dp)) {
                Text(
                    "速度",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(10.dp))
                GlassSegmented(
                    options = MotionPrefs.SPEED_LABELS,
                    selected = speed,
                    onSelect = { MotionPrefs.setSpeed(context, it) }
                )
            }
        }
    }
}
