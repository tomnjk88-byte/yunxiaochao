/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。AGPL-3.0 第 5 条要求修改版保留显著声明，第 13 条要求
 * 网络交互用户能够获得源代码 —— 本页同时承担这两个义务。
 * v1.8.0：跟随主界面改为深色液态玻璃配色。
 */

package com.yunx.app.overlay.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * 修改后应用的源代码地址。
 *
 * AGPL-3.0 要求「通过网络与本应用交互的用户」能拿到对应版本的完整源码，
 * 所以这里指向本应用自己的发布仓库，而不是上游 YunX。
 */
private const val SOURCE_CODE_URL = "https://github.com/tomnjk88-byte/yunxiaochao"

private const val UPSTREAM_URL = "https://github.com/CYQawa/YunX"
private const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

/**
 * SukiSU Nova（原名 SukiSU-Ultra）。
 *
 * 我们复用了它的两样「约定」，不是代码：
 *  ① .sukisu 主题包格式（zip + 加密的 theme.json）；
 *  ② 主题商店接口与底栏图标命名（bottom_bar_icon_home / superuser / module / setting）。
 * 它应用核心是 GPL-3.0-or-later，内核目录 GPL-2.0-only。
 */
private const val SUKISU_REPO_URL = "https://github.com/SukiSU-Ultra/SukiSU-Ultra"
private const val SUKISU_SITE_URL = "https://sukisu.org"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenNotes: () -> Unit
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "1.0.0" }
    }

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

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
                "关于",
                fontSize = 18.sp,
                color = GlassColors.Text,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "云小抄 v$version",
            fontSize = 22.sp,
            color = GlassColors.Text,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "悬浮在游戏上，随时翻攻略 · 基于云析 YunX 二次开发",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = GlassColors.TextSub
        )

        // v2.0.10：点左下角「关于 / 修改说明」进来，第一眼就是这颗使用说明入口
        Spacer(Modifier.height(18.dp))
        GlassPrimaryButton(text = "使用说明", onClick = onOpenUsage)

        Spacer(Modifier.height(20.dp))
        AboutSection("开源来源") {
            Text(
                "本应用是「云析 YunX」的二次开发版本。\n" +
                    "原项目：$UPSTREAM_URL\n" +
                    "Copyright (C) 2026 CYQawa\n" +
                    "协议：GNU Affero General Public License v3.0",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(8.dp))
            LinkRow("查看云析原项目", UPSTREAM_URL, ::open)
        }

        Spacer(Modifier.height(14.dp))
        AboutSection("更新说明") {
            Text(
                "更新说明内置在安装包里，不联网。按版本号倒序，每版拆成「新增功能 / 优化体验 / 修复 Bug」三段。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(8.dp))
            GlassPrimaryButton(
                text = "查看全部 ${UPDATE_NOTES.size} 个版本",
                onClick = onOpenNotes
            )
        }

        Spacer(Modifier.height(12.dp))
        AboutSection("复用的云析模块") {
            Text(
                "· QuarkApi / QuarkConstants（含个人网盘列表 listCloudFiles、取链 getDownloadLink）\n" +
                    "· XunleiApi / XunleiConstants（含 getFiles、getFileDetail、登录与 token 刷新）\n" +
                    "· HttpClients（OkHttp 客户端）\n" +
                    "· ShareModels（ShareFile / FileThumbnail / DownloadLink）\n" +
                    "· XunleiDeviceFingerprint（迅雷设备指纹）",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextSub
            )
        }

        Spacer(Modifier.height(12.dp))
        AboutSection("主题格式与 SukiSU Nova") {
            Text(
                "本应用的 .sukisu 主题包解析、在线主题商店接口（含 X-Sukisu-Theme-Token 请求头、" +
                    "SHA-256 校验）以及底栏图标命名约定（bottom_bar_icon_home / superuser / " +
                    "module / setting），均参照 SukiSU Nova（原名 SukiSU-Ultra）的公开格式实现。\n\n" +
                    "我们只实现兼容的读取/写入，不包含、不修改也不分发 SukiSU 的源代码。\n" +
                    "SukiSU 的授权：应用核心 GPL-3.0-or-later；kernel 目录 GPL-2.0-only。\n\n" +
                    "主题包内的图片与配色版权归各主题作者所有。本应用只在本机解析、展示，" +
                    "不上传、不再分发；在线下载的包与官方源保持一致，不做二次打包。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(8.dp))
            LinkRow("SukiSU 项目源代码", SUKISU_REPO_URL, ::open)
            Spacer(Modifier.height(6.dp))
            LinkRow("SukiSU 官网 · 主题格式文档", SUKISU_SITE_URL, ::open)
        }

        Spacer(Modifier.height(12.dp))
        AboutSection("源代码与协议") {
            Text(
                "修改声明（AGPL-3.0 §5）：本应用系「云析 YunX」的二次开发版本，" +
                    "主要修改为新增悬浮窗、本地 / 链接攻略源、自动连招、准星与下载管理；" +
                    "逐版改动明细见上方「更新说明」。\n\n" +
                    "本应用以 AGPL-3.0 授权。个人自用不分发；若再分发，必须：\n" +
                    "· 保留原作者版权声明与本协议；\n" +
                    "· 修改后的版本同样以 AGPL-3.0 公开完整源代码；\n" +
                    "· 通过网络提供交互时，向用户提供获取源码的方式（即本页）。\n\n" +
                    "本程序不含任何担保，详见协议第 15、16 条。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(8.dp))
            LinkRow("本应用源代码（含全部修改）", SOURCE_CODE_URL, ::open)
            Spacer(Modifier.height(6.dp))
            LinkRow("上游项目源代码（云析）", UPSTREAM_URL, ::open)
            Spacer(Modifier.height(6.dp))
            LinkRow("阅读 AGPL-3.0 全文", LICENSE_URL, ::open)
        }

        Spacer(Modifier.height(12.dp))
        AboutSection("自动连招与合规") {
            Text(
                "自动连招支持三种执行方式，「自动」按 Root → Shizuku → 无障碍 的顺序挑。" +
                    "Root 走 su + input，Shizuku 走它的 shell 权限，两者注入的都是独立指针 —— " +
                    "你按住摇杆走位时，技能照常放，角色不会被拽停。两个都没有时才退回 Android " +
                    "的无障碍 dispatchGesture：它最快，但每发一次都会取消你正按着的触摸。\n\n" +
                    "三种方式都只回放你自己录制/设置的坐标，不读取屏幕内容、不检索窗口、" +
                    "不联网，也不收集或上传任何数据；所有宏只存在本机。\n\n" +
                    "Root 授权完全由你主动点按钮触发，应用不会在后台偷偷申请；Shizuku 也需要你" +
                    "自行安装并启动。两种 shell 注入的频率低于无障碍（每次 input 约 80~150ms）。\n\n" +
                    "请注意：root 与自动化操作可能触发部分游戏的反作弊或违反其用户协议，" +
                    "使用前请自行确认规则并自行承担账号风险。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextSub
            )
        }

        Spacer(Modifier.height(12.dp))
        AboutSection("免责声明") {
            Text(
                "本应用与夸克、迅雷官方无任何关联，使用的是公开客户端接口，可能随服务端调整而失效。\n" +
                    "请仅访问你本人合法拥有或已获授权的内容；因使用本应用产生的账号风险与法律责任由使用者自行承担。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextMuted
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    GlassCard {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                fontSize = 12.sp,
                color = GlassColors.Text,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

@Composable
private fun LinkRow(text: String, url: String, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.horizontalGradient(listOf(Color(0x335E9BFF), Color(0x229B7CFF)))
            )
            .border(1.dp, GlassColors.Stroke, RoundedCornerShape(8.dp))
            .clickable { onOpen(url) }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(text, fontSize = 11.sp, color = GlassColors.Text)
    }
}
