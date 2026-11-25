/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件（v2.0.10）。抽屉左下角「使用说明」和「关于」页顶部入口都跳到这里。
 *   每条一张卡片、一句话，只写「点哪里 → 发生什么」，不写原理、不写备注。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一条「点哪里 → 发生什么」。刻意只有标题 + 一句话 */
private data class UsageStep(val title: String, val body: String)

/**
 * v2.6.0：本地小抄的完整放置教程。
 * 用户最常卡在「图放哪、App 怎么找到」这一步，所以单独放在最前面，四步讲完：
 * 建总目录 → 按地图分子目录 → 用什么方式把图放进去 → 回悬浮球切「本地」。
 */
private val LOCAL_STEPS = listOf(
    UsageStep(
        "建一个总目录",
        "打开手机自带的「文件管理」，在内部存储根目录新建一个文件夹，名字随意，比如「云小抄」。\n" +
            "别放在 Download、Android/data 或各种 cache 目录里 —— 那些地方系统会定时清空，图会莫名消失。"
    ),
    UsageStep(
        "按地图分子目录",
        "在这个总目录里，一个地图建一个子文件夹：「王者」「原神」「和平」…… 图直接丢进对应子文件夹。\n" +
            "文件名不用改，App 不认名字、只按目录分组；想再细分成「英雄」「装备」也行，最多支持三层。"
    ),
    UsageStep(
        "把图放进去（三种方式任选）",
        "① 用 App 内的下载器：解析页粘贴链接 → 勾要下的资源 → 选保存目录，直接落到这个总目录；\n" +
            "② 用网盘客户端或云析下载，保存位置选同一个总目录；\n" +
            "③ 数据线拷、微信「保存到手机」后再用文件管理挪进来。\n" +
            "放完不用在 App 里做任何导入，回到小抄就能看到。"
    ),
    UsageStep(
        "回悬浮球切「本地」",
        "打开小抄悬浮窗 → 抽屉里点「本地小抄」→ 选刚才那个总目录（只需选一次，之后会记住）。\n" +
            "窗里会列出所有子目录，点「王者」就只看王者的小抄。想把常用目录固定成首页按钮，" +
            "就在「本地小抄」页加「小抄入口」，一个入口绑一个目录，点进去只显示那一层。"
    )
)

private val STEPS = listOf(
    UsageStep("开启小抄悬浮窗", "「权限」页授权显示在其他应用上层 → 小抄任意一页顶部的大按钮都能开窗。"),
    UsageStep("四个独立页面", "抽屉里「小抄」下面直接分成个人小抄 / 本地小抄 / 链接小抄 / 悬浮窗，点哪个进哪个，每页只放自己的功能。"),
    UsageStep("浏览小抄", "窗里点文件夹往下翻，点图片看大图；顶栏可拖动，右下角可拉伸，右上角缩成小球。"),
    UsageStep("保存到本地", "打开「图片本地化」后，图片会按网盘的分类自动建目录存到手机。"),
    UsageStep("自动连招", "「权限」页开启无障碍 →「连招」页点顶部按钮开窗，录制并设置连点。"),
    UsageStep("准星", "「准星」页导入任意图片 → 点图片上任意位置设瞄准点 → 开窗；长按可拖位，锁定后点击穿透。顶部「我的准星」能存多套，长按某张可重命名 / 删除，换游戏点一下就切。"),
    UsageStep("外观", "顶栏调色盘切白天 / 黑夜与界面风格；换风格时画面会碎块合上再散开，「我的」页里也能改。"),
    UsageStep("动画", "「我的 → 动画」里开屏和切页各选风格：碎格是整屏细碎小格从左上碎到右下，浮现是模块逐块升起；也能直接关掉或调四档速度。"),
    UsageStep("开屏", "「我的 → 开屏动画」可导入自己的图片或视频：图片可选停留 1 / 2 / 3 秒，视频静音播放、播完自动进主界面；轻触任意处随时跳过。")
)

@Composable
fun UsageScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                "使用说明",
                fontSize = 18.sp,
                color = GlassColors.Text,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(18.dp))
        UsageGroupTitle("本地小抄 · 图放哪、怎么让它读出来")
        Spacer(Modifier.height(8.dp))
        LOCAL_STEPS.forEachIndexed { index, step ->
            UsageCard(index + 1, step)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(10.dp))
        UsageGroupTitle("其它功能")
        Spacer(Modifier.height(8.dp))
        STEPS.forEachIndexed { index, step ->
            UsageCard(index + 1, step)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun UsageGroupTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = GlassColors.TextMuted,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun UsageCard(no: Int, step: UsageStep) {
    GlassCard {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(GlassColors.Accent, GlassColors.Accent2)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    no.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    step.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    step.body,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = GlassColors.TextSub
                )
            }
        }
    }
}
