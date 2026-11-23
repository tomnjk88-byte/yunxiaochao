/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。自动连招面板：取点连击 / 录制动作 / 参数调节 / 保存成按钮 /
 * 置顶为独立悬浮球。全部通过 MacroController 单例操作，不直接碰 WindowManager。
 *
 * v1.8.4：录制入口做成醒目的主色按钮并给出「录制中」状态；保存时就能挑图标/颜色，
 *   「保存并换图片」一步拉起系统选图；每个保存后的连招是一枚带专属图标的按键，
 *   展开「设置」里可随时换图标 / 换图片 / 换颜色 / 恢复默认。
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.overlay.MacroIconPickerActivity
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.macro.CaptureMode
import com.yunx.app.overlay.macro.EngineMode
import com.yunx.app.overlay.macro.MacroConfig
import com.yunx.app.overlay.macro.MacroController
import com.yunx.app.overlay.macro.MacroEngine
import com.yunx.app.overlay.macro.MacroShareIo
import com.yunx.app.overlay.macro.RootShell
import com.yunx.app.overlay.macro.ShizukuShell
import android.widget.Toast
import kotlinx.coroutines.delay

@Composable
fun MacroPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val macros by MacroController.macros.collectAsState()
    val enabled by MacroController.enabled.collectAsState()
    val runningId by MacroController.runningId.collectAsState()
    // v1.9.16：连招正在给手动操作让路
    val pausedByUser by MacroController.pausedByUser.collectAsState()
    val rootState by MacroController.rootState.collectAsState()
    val rootBusy by MacroController.rootBusy.collectAsState()
    val shizukuState by MacroController.shizukuState.collectAsState()
    val engineMode by MacroController.engine.collectAsState()
    val captureMode by MacroController.captureMode.collectAsState()

    // v1.9.1：录完即自动保存，这里只显示一条「已自动保存」的提示卡
    val justSaved by MacroController.justSaved.collectAsState()
    val prefs = remember { OverlayPrefs(context) }
    var showTap by remember { mutableStateOf(prefs.showTapIndicator) }
    var volumeHide by remember { mutableStateOf(prefs.volumeKeyHide) }

    var expandedId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var savedNameDraft by remember { mutableStateOf("") }

    // 用户去系统设置里开启无障碍后，回到这个面板要能自动变绿。
    // v1.9.5：间隔从 1.5s 放宽到 3s（低性能模式 5s）—— 这个查询要走 Settings.Secure
    val refreshGap = if (prefs.lowPerfMode) 5000L else 3000L
    LaunchedEffect(Unit) { MacroController.refreshShizuku() }
    LaunchedEffect(refreshGap) {
        while (true) {
            MacroController.refreshEnabled()
            delay(refreshGap)
        }
    }

    // 新自动保存的连招：把命名框预填好，并展开它的设置方便立刻换外观
    LaunchedEffect(justSaved) {
        val saved = justSaved ?: return@LaunchedEffect
        savedNameDraft = saved.name
        expandedId = saved.id
    }

    Column(
        modifier
            .fillMaxSize()
            // v1.9.2：窗口偏矮时，上半部分的引擎设置 / 录制入口 / 参数也要能滑到，
            // 不再只有底部连招列表那一小块可滚。
            .verticalScroll(rememberScrollState())
            .background(OverlayColors.PanelDark)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        // 无障碍服务状态
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(if (enabled) overlayAccentSoft() else Color(0x33E5484D))
                .clickable(enabled = !enabled) { MacroController.openAccessibilitySettings() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (enabled) {
                    "无障碍已开启 · 只有没有 Shizuku/Root 时才用它兜底"
                } else {
                    "点这里开启「无障碍」（可选；有 Shizuku / Root 就不需要）"
                },
                fontSize = 9.sp,
                color = if (enabled) overlayAccent() else OverlayColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (enabled) Text("✓", fontSize = 10.sp, color = overlayAccent())
        }

        Spacer(Modifier.height(5.dp))

        // Root 权限：只有用户主动点按钮才去申请 su，未点过绝不弹授权框
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (rootState == RootShell.State.GRANTED) overlayAccentSoft()
                    else Color(0x14000000)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        rootBusy -> "正在等待 Root 授权弹窗…"
                        rootState == RootShell.State.GRANTED -> "Root 已授权 · 可用 su 注入点击"
                        rootState == RootShell.State.NO_SU -> "设备没有 su（未 root / su 被隐藏）"
                        rootState == RootShell.State.DENIED -> "Root 授权被拒绝或超时，可重试"
                        else -> "Root 未申请（可选）"
                    },
                    fontSize = 9.sp,
                    color = if (rootState == RootShell.State.GRANTED) {
                        overlayAccent()
                    } else {
                        OverlayColors.TextPrimary
                    }
                )
                Text(
                    "可绕开受限设置 / 屏蔽无障碍的游戏；频率低于无障碍",
                    fontSize = 7.sp,
                    color = OverlayColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(5.dp))
            RootPill(
                when {
                    rootBusy -> "申请中…"
                    rootState == RootShell.State.GRANTED -> "重新申请"
                    else -> "获取 Root 权限"
                }
            ) {
                if (!rootBusy) MacroController.requestRoot()
            }
        }

        Spacer(Modifier.height(5.dp))

        // v3.2.0：Shizuku —— 不打断摇杆的关键。和 Root 并排，谁可用就用谁。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (shizukuState == ShizukuShell.State.GRANTED) overlayAccentSoft()
                    else Color(0x14000000)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (shizukuState) {
                        ShizukuShell.State.GRANTED -> "Shizuku 已连接 · 独立指针，边走边放"
                        ShizukuShell.State.NOT_GRANTED -> "Shizuku 在运行 · 还没给本应用授权"
                        ShizukuShell.State.DENIED -> "Shizuku 授权被拒绝，可重试"
                        ShizukuShell.State.NO_SHIZUKU -> "没检测到 Shizuku（推荐装一个，免 root）"
                        ShizukuShell.State.UNKNOWN -> "Shizuku 未检测"
                    },
                    fontSize = 9.sp,
                    color = if (shizukuState == ShizukuShell.State.GRANTED) {
                        overlayAccent()
                    } else {
                        OverlayColors.TextPrimary
                    }
                )
                Text(
                    "用 adb 权限注入点击，不经过无障碍 —— 你按住摇杆时它不会把手指掐断",
                    fontSize = 7.sp,
                    color = OverlayColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(5.dp))
            RootPill(
                if (shizukuState == ShizukuShell.State.GRANTED) "重新连接" else "连接 Shizuku"
            ) {
                MacroController.refreshShizuku()
                MacroController.requestShizuku()
            }
        }

        Spacer(Modifier.height(5.dp))

        // 执行方式：自动 / Shizuku / 无障碍 / Root
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("执行方式", fontSize = 9.sp, color = OverlayColors.TextMuted)
            Spacer(Modifier.width(6.dp))
            val modes = EngineMode.values()
            modes.forEachIndexed { index, mode ->
                EngineTab(
                    label = mode.label,
                    selected = engineMode == mode,
                    modifier = Modifier.weight(1f)
                ) { MacroController.setEngine(mode) }
                if (index != modes.lastIndex) Spacer(Modifier.width(4.dp))
            }
        }
        Text(
            engineMode.hint,
            fontSize = 7.sp,
            color = OverlayColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(7.dp))

        // ================================================================
        // 醒目的「录制」入口：录制是主按钮，取点连击是次要按钮
        // ================================================================
        Text(
            "新建连招",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = OverlayColors.TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // v2.5.7：主入口换成「按键录制」——游戏助手那种摆键 + 按节奏录，
            // 不再是全屏膜上对着黑屏划一遍
            MacroAction(
                "⌨ 按键录制",
                primary = true,
                modifier = Modifier.weight(1f)
            ) { MacroController.startKeypadRecord(context) }
            MacroAction("✍ 手划", modifier = Modifier.weight(1f)) {
                MacroController.startRecord()
            }
            MacroAction("◎ 取点", modifier = Modifier.weight(1f)) {
                MacroController.startAim()
            }
            if (runningId != null) {
                MacroAction("■ 停", modifier = Modifier.width(40.dp)) {
                    MacroController.stopAll()
                }
            }
        }
        if (runningId != null && pausedByUser) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x2AFFB020))
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "正在等你操作：松手后自动从这一步继续，不会从头重来",
                    fontSize = 8.sp,
                    color = Color(0xFFB26A00),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (captureMode != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22E5484D))
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (captureMode == CaptureMode.RECORD) {
                        "录制中：连招窗已缩成球，录完点「完成」"
                    } else {
                        "取点中：连招窗已缩成球，点一下再按「确定并保存」"
                    },
                    fontSize = 8.sp,
                    color = OverlayColors.Danger,
                    modifier = Modifier.weight(1f)
                )
                SmallChip("取消") { MacroController.cancelCapture() }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "「按键录制」会先让你把按键拖到技能键上，然后照平时节奏点它们，顺序和间隔都记下来；只有按键那一小块吃触摸，屏幕其它地方仍可操作游戏。录完自动存成一颗小按钮。",
            fontSize = 8.sp,
            lineHeight = 11.sp,
            color = OverlayColors.TextMuted
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("点击显示", showTap, Modifier.weight(1f)) {
                showTap = !showTap
                prefs.showTapIndicator = showTap
            }
            ToggleChip("音量键隐藏", volumeHide, Modifier.weight(1f)) {
                volumeHide = !volumeHide
                prefs.volumeKeyHide = volumeHide
            }
        }

        // ================================================================
        // v1.9.1：录完即自动保存，这里只给一条提示 + 改名 / 换图快捷入口
        // ================================================================
        val savedMacro = justSaved?.let { js -> macros.firstOrNull { it.id == js.id } }
        if (savedMacro != null) {
            Spacer(Modifier.height(7.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x223D6BF5))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MacroIconBadge(savedMacro, size = 34.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "✓ 已自动保存「${savedMacro.name}」",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = OverlayColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${savedMacro.steps.size} 步 · 屏幕上已多一颗小按钮，点它就能用",
                            fontSize = 8.sp,
                            color = OverlayColors.TextMuted
                        )
                    }
                    SmallChip("✕") { MacroController.clearJustSaved() }
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = savedNameDraft,
                        onValueChange = { savedNameDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 10.sp, color = OverlayColors.TextPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(OverlayColors.PanelDarkElevated)
                            .padding(horizontal = 7.dp, vertical = 6.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    SmallChip("√", primary = true) {
                        if (savedNameDraft.isNotBlank()) {
                            MacroController.update(savedMacro.copy(name = savedNameDraft.trim()))
                        }
                    }
                    Spacer(Modifier.width(5.dp))
                    MacroAction("换图片") {
                        MacroIconPickerActivity.start(context, savedMacro.id, newTask = true)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (macros.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有连招。点上面「● 录制动作」演一遍，点「保存这条连招」就得到一颗屏幕上的小按钮，点一下就能用。",
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    color = OverlayColors.TextMuted
                )
            }
        } else {
            // v1.9.2：整块面板统一由外层 Column 上下滚，这里用普通 Column 平铺连招卡片，
            // 不套 LazyColumn，避免两个滚动容器互相抢手势导致「滑不动」。
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                macros.forEach { macro ->
                    MacroCard(
                        macro = macro,
                        running = runningId == macro.id,
                        expanded = expandedId == macro.id,
                        renaming = renameId == macro.id,
                        renameDraft = renameDraft,
                        onRenameDraft = { renameDraft = it },
                        onToggleExpand = {
                            expandedId = if (expandedId == macro.id) null else macro.id
                        },
                        onStartRename = {
                            renameId = macro.id
                            renameDraft = macro.name
                        },
                        onCommitRename = {
                            if (renameDraft.isNotBlank()) {
                                MacroController.update(macro.copy(name = renameDraft.trim()))
                            }
                            renameId = null
                        },
                        onPickImage = {
                            MacroIconPickerActivity.start(context, macro.id, newTask = true)
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "每个按钮的图标 / 图片 / 颜色都是各设各的；「开 / 关」是它自己的独立开关，关掉后不执行也不显示悬浮球。" +
                        "录制时连招窗自动缩成球、点击位置会画出来，演完自动保存，不用再点保存按钮。" +
                        "打不开无障碍开关时，可点「获取 Root 权限」改用 su 注入。" +
                        "Root 模式每次 input 约 80~150ms，高频连点请优先用无障碍；多点轨迹在 Android 12+ 用 motionevent 回放，旧系统退化为首尾直线。" +
                        "自动化操作可能违反部分游戏的用户协议，请仅在被允许的场景使用。",
                    fontSize = 8.sp,
                    lineHeight = 11.sp,
                    color = OverlayColors.TextMuted
                )
            }
        }
        // 右下角有拉伸手柄，给最后一行留余量，别被压住
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MacroCard(
    macro: MacroConfig,
    running: Boolean,
    expanded: Boolean,
    renaming: Boolean,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    onToggleExpand: () -> Unit,
    onStartRename: () -> Unit,
    onCommitRename: () -> Unit,
    onPickImage: () -> Unit
) {
    val context = LocalContext.current
    val base = Color(macro.displayColor)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OverlayColors.PanelDarkElevated)
            .padding(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 「专属按键」：带这个连招自己的图标 / 图片 / 颜色，点一下执行；
            // 独立开关关掉时整颗置灰、显示「已关闭」，点它会提示先打开开关。
            val dim = !macro.enabled
            Box(
                Modifier
                    .width(56.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (dim) base.copy(alpha = 0.35f) else base,
                                if (dim) base.copy(alpha = 0.22f) else base.copy(alpha = 0.76f)
                            )
                        )
                    )
                    .border(
                        1.5.dp,
                        Color.White.copy(alpha = if (dim) 0.35f else 0.9f),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { MacroController.toggleRun(macro) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MacroIconGlyph(macro, size = 24.dp)
                    Text(
                        when {
                            dim -> "已关闭"
                            running -> "■ 停止"
                            else -> "▶ 执行"
                        },
                        fontSize = 7.sp,
                        color = Color.White.copy(alpha = if (dim) 0.7f else 1f),
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                if (renaming) {
                    BasicTextField(
                        value = renameDraft,
                        onValueChange = onRenameDraft,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 10.sp, color = OverlayColors.TextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(OverlayColors.PanelDark)
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                    )
                } else {
                    Text(
                        macro.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = OverlayColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    summary(macro),
                    fontSize = 8.sp,
                    color = OverlayColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // v1.9.1：每个连招自己的独立开启 / 关闭开关
            SmallChip(
                if (macro.enabled) "开" else "关",
                primary = macro.enabled
            ) { MacroController.toggleEnabled(macro) }
            if (renaming) {
                SmallChip("√", primary = true, onClick = onCommitRename)
            } else {
                SmallChip("✎", onClick = onStartRename)
            }
            SmallChip(if (macro.pinned) "★" else "☆") { MacroController.togglePin(macro) }
            SmallChip(if (expanded) "收起" else "设置", onClick = onToggleExpand)
            SmallChip("✕") { MacroController.remove(macro) }
        }

        if (expanded) {
            Spacer(Modifier.height(7.dp))
            // 外观编辑器：改完立刻落盘 + 刷新悬浮按钮，不会打断正在跑的连招
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x0A000000))
                    .padding(8.dp)
            ) {
                MacroAppearanceEditor(
                    macro = macro,
                    onPickImage = onPickImage,
                    onChange = { MacroController.applyAppearance(context, it) }
                )
            }

            Spacer(Modifier.height(7.dp))
            ParamSlider(
                label = "间隔",
                value = macro.intervalMs.toFloat(),
                range = 20f..2000f,
                display = { "${it.toInt()}ms" }
            ) { value ->
                MacroController.update(macro.copy(intervalMs = value.toLong()))
            }
            ParamSlider(
                label = "轮数",
                value = macro.repeat.toFloat(),
                range = 0f..200f,
                display = { if (it < 1f) "无限" else "${it.toInt()} 轮" }
            ) { value ->
                MacroController.update(macro.copy(repeat = value.toInt()))
            }
            if (macro.isSingleTap) {
                val duration = macro.steps.first().durationMs.toFloat()
                ParamSlider(
                    label = "按住",
                    value = duration,
                    range = 20f..1000f,
                    display = { "${it.toInt()}ms" }
                ) { value ->
                    MacroController.update(
                        macro.copy(
                            steps = listOf(macro.steps.first().copy(durationMs = value.toLong()))
                        )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (macro.isSingleTap) {
                    MacroAction("重新取点", modifier = Modifier.weight(1f)) {
                        MacroController.reAim(macro)
                    }
                }
                MacroAction("试一次", modifier = Modifier.weight(1f)) {
                    MacroController.previewOnce(macro)
                }
                MacroAction(
                    text = if (macro.pinned) "收起小按钮" else "放到屏幕上",
                    modifier = Modifier.weight(1.4f)
                ) { MacroController.togglePin(macro) }
            }
            // v1.9.15：单条分享。面板挂在 Service 上，分享走系统面板；口令走剪贴板，
            // 两条路都不需要存储权限，也不会上传任何东西。
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroAction("⤴ 分享这条", modifier = Modifier.weight(1f)) {
                    if (!MacroShareIo.share(context, listOf(macro))) {
                        Toast.makeText(context, "没有找到能接收文件的 App", Toast.LENGTH_SHORT).show()
                    }
                }
                MacroAction("复制口令", modifier = Modifier.weight(1f)) {
                    val ok = MacroShareIo.copyCode(context, listOf(macro))
                    Toast.makeText(
                        context,
                        if (ok) "已复制，粘贴给对方即可" else "复制失败，换「分享这条」试试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

private fun summary(macro: MacroConfig): String {
    val repeat = if (macro.repeat == 0) "无限" else "${macro.repeat} 轮"
    return if (macro.isSingleTap) {
        val p = macro.steps.first().start
        "(${p.x.toInt()}, ${p.y.toInt()}) · ${macro.intervalMs}ms · 按住 ${macro.steps.first().durationMs}ms · $repeat"
    } else {
        "${macro.steps.size} 步 · 每轮隔 ${macro.intervalMs}ms · $repeat"
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onCommit: (Float) -> Unit
) {
    // 拖动时只改本地值，松手才落盘，避免每帧写 SharedPreferences
    var draft by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 8.sp, color = OverlayColors.TextMuted, modifier = Modifier.width(26.dp))
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
            colors = overlaySliderColors()
        )
        Text(
            display(draft),
            fontSize = 8.sp,
            color = OverlayColors.TextMuted,
            modifier = Modifier.width(42.dp)
        )
    }
}

@Composable
private fun MacroAction(
    text: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (primary) overlayAccent() else OverlayColors.PanelDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            color = if (primary) Color.White else OverlayColors.TextSecondary,
            maxLines = 1
        )
    }
}

/** v1.9.1：面板里的一颗小开关（点击显示 / 音量键隐藏） */
@Composable
private fun ToggleChip(
    text: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (checked) overlayAccentSoft() else OverlayColors.PanelDarkElevated
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (if (checked) "● " else "○ ") + text,
            fontSize = 8.sp,
            color = if (checked) overlayAccent() else OverlayColors.TextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun SmallChip(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (primary) overlayAccent() else Color(0x14FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = if (text.length > 1) 7.sp else 10.sp,
            color = if (primary) Color.White else OverlayColors.TextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun RootPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(overlayAccent())
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 8.sp, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun EngineTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) overlayAccent() else OverlayColors.PanelDarkElevated
            )
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = if (selected) Color.White else OverlayColors.TextSecondary,
            maxLines = 1
        )
    }
}
