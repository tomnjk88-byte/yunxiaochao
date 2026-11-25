/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.0 底部「自动连招」按钮对应的独立界面。
 *
 * 关键交互（用户明确要求）：
 *   - 本页顶部有一颗**单独的、醒目的按钮**，那是开启 / 收起连招悬浮窗的入口；
 *   - v1.9.2：底部「自动连招」按钮点一下也会保证连招窗已经挂出来，避免出现
 *     「点了按钮却没有悬浮窗」；它依旧只切本页 + 只动连招层；
 *   - 小抄与连招依旧完全独立：这个按钮只碰连招窗，不碰小抄窗。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yunx.app.overlay.MacroIconPickerActivity
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.macro.MacroAccessibilityService
import com.yunx.app.overlay.macro.MacroController
import com.yunx.app.overlay.macro.MacroShare
import com.yunx.app.overlay.macro.MacroShareIo
import com.yunx.app.overlay.service.FloatingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MacroHomePage(
    macroWindowOn: Boolean,
    overlayGranted: Boolean,
    a11yEnabled: Boolean,
    onToggleMacroWindow: (Boolean) -> Unit,
    onOpenAuth: () -> Unit,
    onReset: () -> Unit,
    /** v2.9.1：抽屉二级页。0 开关与显示 / 1 录制台 / 2 按键外观 / 3 分享导入 */
    pane: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { OverlayPrefs(context) }
    var showTap by remember { mutableStateOf(prefs.showTapIndicator) }
    var volumeHide by remember { mutableStateOf(prefs.volumeKeyHide) }
    // v1.9.5：显隐状态改成订阅 StateFlow，值不变不会重组，也不用每秒醒一次
    val hidden by FloatingService.windowsHiddenFlow.collectAsState()

    // 滚动由主页统一提供，这里只负责内容，避免嵌套两个 verticalScroll 抢手势。
    // v2.3.0：换成 StaggerColumn —— 顶层若是普通 Column，主页那层只能看到一个子项，
    // 整页一起淡入，看着像没有逐项动画。
    // v2.9.1：pane 决定这一屏只出现哪一组；playKey = pane 让换页时整页重新浮一次。
    StaggerColumn(modifier = modifier.fillMaxWidth(), playKey = pane) {
        when (pane) {
            // ① 开关与显示
            0 -> {
                GlassSectionTitle("独立开关")
                // ★ 这颗才是「连招面板」的开关：底部 Tab 只切页面，永远不会自己弹窗。
                GlassPrimaryButton(
                    text = if (macroWindowOn) "收起自动连招面板" else "打开自动连招面板",
                    subtitle = if (macroWindowOn) {
                        "只关连招自己，小抄窗一个字节都不会动"
                    } else {
                        "只是打开设置 / 录制面板；录完的连招会单独变成屏幕上的小按钮"
                    },
                    icon = Icons.Rounded.Bolt,
                    onClick = { onToggleMacroWindow(!macroWindowOn) }
                )
                Spacer(Modifier.height(10.dp))
                GlassCard {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "当前状态",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassColors.Text
                        )
                        Spacer(Modifier.height(8.dp))
                        GlassInfoRow("连招悬浮窗", if (macroWindowOn) "已开启" else "未开启")
                        GlassDivider()
                        GlassInfoRow(
                            "悬浮窗权限",
                            if (overlayGranted) "已授予" else "未授予 · 去「权限」页"
                        )
                        GlassDivider()
                        GlassInfoRow(
                            "无障碍服务",
                            if (a11yEnabled) "已开启" else "未开启（也可用 Root）"
                        )
                    }
                }

                // v2.5.6：帧率悬浮窗（v2.6.0 起只留背景 / 不透明度 / 帧耗时，图标入口已移除）
                FpsWindowSection()

                GlassSectionTitle("显示与快捷键")
                GlassCard {
                    GlassRow(
                        title = "点击显示",
                        subtitle = "",
                        icon = Icons.Rounded.Info,
                        onClick = {
                            showTap = !showTap
                            prefs.showTapIndicator = showTap
                        }
                    ) {
                        GlassSwitch(
                            checked = showTap,
                            onCheckedChange = {
                                showTap = it
                                prefs.showTapIndicator = it
                            }
                        )
                    }
                    GlassDivider()
                    GlassRow(
                        title = "音量键隐藏悬浮窗",
                        // v1.9.19：以前只写「双击就行」，可系统没授权按键过滤时按了没反应也没处说理。
                        // 现在把真实状态写出来，用户一眼知道该修无障碍还是改用下面那颗按钮。
                        subtitle = when {
                            !MacroAccessibilityService.connected ->
                                "无障碍服务没连上，双击不会生效 —— 先在上面的「无障碍」里开启"
                            !MacroAccessibilityService.keyFilterActive ->
                                "系统没把音量键事件给到本应用（部分 ROM 会拦），双击无反应时用下面「立即隐藏」"
                            else -> "双击音量上 / 下键隐藏或显示所有悬浮窗；不吞按键，照常能调音量"
                        },
                        icon = Icons.Rounded.Shield,
                        onClick = {
                            volumeHide = !volumeHide
                            prefs.volumeKeyHide = volumeHide
                        }
                    ) {
                        GlassSwitch(
                            checked = volumeHide,
                            onCheckedChange = {
                                volumeHide = it
                                prefs.volumeKeyHide = it
                            }
                        )
                    }
                    GlassDivider()
                    // 兜底入口：部分 ROM 会抢先消费音量键，这里仍可一键显隐
                    GlassRow(
                        title = if (hidden) "立即显示悬浮窗" else "立即隐藏悬浮窗",
                        subtitle = if (hidden) {
                            "当前已隐藏 · 点这里恢复，也可用音量键或通知栏"
                        } else {
                            "不想用音量键时，点这里直接隐藏所有悬浮窗"
                        },
                        icon = Icons.Rounded.Bolt,
                        onClick = { FloatingService.setWindowsVisible(context, !hidden) }
                    ) {
                        GlassPill(
                            if (hidden) "显示" else "隐藏",
                            onClick = { FloatingService.setWindowsVisible(context, !hidden) },
                            primary = false
                        )
                    }
                }

                GlassSectionTitle("怎么用")
                GlassCard {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "1. 点上面的「打开自动连招面板」，在里面点「● 录制动作」；\n" +
                                "2. 面板会自己缩成小球，按平时的顺序点 / 长按 / 滑动，点「保存这条连招」；\n" +
                                "3. 录完面板不会弹回来 —— 屏幕上直接多一颗「连招 1」小按钮，点它一下就开始执行；\n" +
                                "4. 小按钮是独立悬浮窗，可以随手拖到顺手的边角；再点一下停止；\n" +
                                "5. 想换图标 / 图片 / 颜色，或者把某颗小按钮收起来，去「按键外观」页设。",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = GlassColors.TextSub
                        )
                    }
                }

                GlassSectionTitle("快捷")
                GlassCard {
                    GlassRow(
                        title = "去授权",
                        subtitle = "",
                        icon = Icons.Rounded.Shield,
                        onClick = onOpenAuth
                    ) {
                        Text("前往 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                    GlassDivider()
                    GlassRow(
                        title = "一键重置悬浮窗",
                        subtitle = "",
                        icon = Icons.Rounded.Refresh,
                        onClick = onReset
                    ) {
                        Text("重置 ›", fontSize = 10.sp, color = GlassColors.Danger)
                    }
                }
            }

            // ② 录制台：自己摆键、自己按节奏录；多指并行也归这一页
            1 -> {
                MacroStudioSection(onEnsureMacroWindow = { onToggleMacroWindow(true) })
                MacroRunModeSection()
            }

            // ③ 按键外观：每颗连招按钮单独换图标 / 图片 / 颜色
            2 -> MacroAppearanceSection()

            // ④ 分享导入
            else -> MacroShareSection()
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 连招按钮外观分组（v1.8.4 从主界面搬到这里，和自动连招归到同一页）。
 *
 * 与连招悬浮面板共用 MacroAppearanceEditor；这里从 Activity 发起选图，
 * 不需要 FLAG_ACTIVITY_NEW_TASK。列表直接读 MacroController.macros（StateFlow），
 * 在悬浮面板里改完回到这里也会自动同步。
 */
@Composable
fun MacroAppearanceSection() {
    val context = LocalContext.current
    val macros by MacroController.macros.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }

    GlassSectionTitle("连招按钮外观")
    GlassCard {
        if (macros.isEmpty()) {
            // 说明文字删掉之后不能留一张空卡片
            Text(
                "还没有连招",
                fontSize = 12.sp,
                color = GlassColors.TextMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
            )
        } else {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                macros.forEachIndexed { index, macro ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedId = if (expandedId == macro.id) null else macro.id
                            }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MacroIconBadge(macro, size = 36.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                macro.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassColors.Text,
                                maxLines = 1
                            )
                            Text(
                                (if (macro.enabled) "已开启" else "已关闭") + " · " +
                                    if (!macro.iconImagePath.isNullOrBlank()) {
                                        "自定义图片 · 可单独更换"
                                    } else {
                                        "内置图标 ${macro.iconPreset} · 可单独更换"
                                    },
                                fontSize = 10.sp,
                                color = GlassColors.TextSub,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassPill(
                            if (macro.enabled) "开" else "关",
                            onClick = { MacroController.toggleEnabled(macro) },
                            primary = macro.enabled
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (expandedId == macro.id) "收起 ›" else "设置 ›",
                            fontSize = 10.sp,
                            color = GlassColors.TextSub
                        )
                    }
                    if (expandedId == macro.id) {
                        // v1.9.3：这颗连招要不要占屏幕，由这里决定；
                        // 关掉只是收起小按钮，连招本身还在列表里，随时能再放出来。
                        GlassRow(
                            title = "显示为屏幕上的小按钮",
                            subtitle = if (macro.pinned) {
                                "已显示 · 点小按钮执行，可拖到任意位置"
                            } else {
                                "已收起 · 不占屏幕，连招仍保留在列表里"
                            },
                            icon = Icons.Rounded.Bolt,
                            onClick = { MacroController.togglePin(macro) }
                        ) {
                            GlassSwitch(
                                checked = macro.pinned,
                                onCheckedChange = { MacroController.togglePin(macro) }
                            )
                        }
                        GlassDivider()
                        MacroAppearanceEditor(
                            macro = macro,
                            dark = true,
                            onPickImage = {
                                MacroIconPickerActivity.start(context, macro.id, newTask = false)
                            },
                            onChange = { MacroController.applyAppearance(context, it) },
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    if (index != macros.lastIndex) GlassDivider()
                }
            }
        }
    }
}

/**
 * v1.9.15 分享 / 导入。
 *
 * 两条路，按「对方有多小白」排序：
 *   1. 文件：生成 .yxmacro 走系统分享面板，对方点开选「云小抄」就导好了（零操作）；
 *   2. 口令：一段文字，发微信最方便；对方复制后回来点一下「从剪贴板导入」。
 * 两条路的数据完全一样，口令只是把同一份 JSON 压了再 Base64。
 */
@Composable
private fun MacroShareSection() {
    val context = LocalContext.current
    val macros by MacroController.macros.collectAsState()
    val scope = rememberCoroutineScope()

    fun tip(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val shareAll: () -> Unit = {
        when {
            macros.isEmpty() -> tip("还没有连招可分享，先录一条")
            !MacroShareIo.share(context, macros) -> tip("没有找到能接收文件的 App")
            else -> Unit
        }
    }
    val copyAll: () -> Unit = {
        when {
            macros.isEmpty() -> tip("还没有连招可分享，先录一条")
            MacroShareIo.copyCode(context, macros) -> tip("口令已复制，去聊天里粘贴给对方即可")
            else -> tip("复制失败，换「分享我的连招」试试")
        }
    }

    // 选文件后要读 content://，放到 IO 线程；解析与落盘回主线程（要刷新悬浮球）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { MacroShareIo.readText(context, uri) }
            val result = if (text == null) null else MacroController.importSharedText(context, text)
            tip(
                when {
                    text == null -> "这个文件读不出来，换一个试试"
                    result == null -> "这不是云小抄的连招文件（.${MacroShare.EXT}）"
                    else -> MacroShare.describe(result)
                }
            )
        }
    }

    GlassSectionTitle("分享 / 导入")
    GlassCard {
        GlassRow(
            title = "分享我的连招",
            subtitle = if (macros.isEmpty()) {
                "还没有连招，先在上面录一条"
            } else {
                "把 ${macros.size} 条连招打成一个文件，微信 / QQ / 蓝牙都能发"
            },
            icon = Icons.Rounded.Share,
            onClick = shareAll
        ) {
            GlassPill(
                text = "分享",
                onClick = shareAll,
                primary = true,
                enabled = macros.isNotEmpty()
            )
        }
        GlassDivider()
        GlassRow(
            title = "复制分享口令",
            subtitle = "",
            icon = Icons.Rounded.ContentCopy,
            onClick = copyAll
        ) {
            GlassPill(
                text = "复制",
                onClick = copyAll,
                primary = false,
                enabled = macros.isNotEmpty()
            )
        }
        GlassDivider()
        GlassRow(
            title = "从剪贴板导入",
            subtitle = "",
            icon = Icons.Rounded.ContentPaste,
            onClick = {
                val text = MacroShareIo.clipboardText(context)
                if (text.isNullOrBlank()) {
                    tip("剪贴板是空的，先复制对方发来的口令")
                } else {
                    val result = MacroController.importSharedText(context, text)
                    tip(
                        if (result == null) {
                            "剪贴板里不是连招口令（应该以 ${MacroShare.CODE_PREFIX} 开头）"
                        } else {
                            MacroShare.describe(result)
                        }
                    )
                }
            }
        ) {
            Text("导入 ›", fontSize = 10.sp, color = GlassColors.Accent)
        }
        GlassDivider()
        GlassRow(
            title = "从文件导入",
            subtitle = "",
            icon = Icons.Rounded.FolderOpen,
            onClick = { picker.launch(arrayOf("*/*")) }
        ) {
            Text("选择 ›", fontSize = 10.sp, color = GlassColors.Accent)
        }
    }
}
