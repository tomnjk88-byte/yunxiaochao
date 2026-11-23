/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.5.6）。连招页新增的三组设置：
 *   1. 多指并行运行姿态（默认）—— 边走位边放连招的核心开关；
 *   2. iQOO / 小米 / OPPO 三家品牌连招方案 —— 一键生成连招按钮；
 *   3. 帧率悬浮窗 —— 开关、透明/不透明、透明度、图标、文字色、帧耗时。
 * 用独立文件承载，避免把已经很长的 MacroHomeScreen / MainActivity 弄得更乱。
 */

package com.yunx.app.overlay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.overlay.MacroIconPickerActivity
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.macro.MacroController
import com.yunx.app.overlay.macro.MacroRunMode
import com.yunx.app.overlay.macro.MacroRunPolicy
import com.yunx.app.overlay.service.FloatingService
import kotlinx.coroutines.launch
import java.io.File

/**
 * 运行姿态：多指并行（默认）/ 手动让路 / 连招优先。
 * 默认改成多指并行是本次「边走边放」的核心修复。
 */
@Composable
fun MacroRunModeSection() {
    val context = LocalContext.current
    val prefs = remember { OverlayPrefs(context) }
    var mode by remember { mutableStateOf(MacroRunMode.fromKey(prefs.macroRunMode)) }
    val modes = MacroRunMode.values()

    GlassSectionTitle("运行姿态 · 多指并行")
    GlassCard {
        Column(Modifier.padding(14.dp)) {
            GlassSegmented(
                options = modes.map { it.label },
                selected = modes.indexOf(mode).coerceAtLeast(0),
                onSelect = { index ->
                    val next = modes.getOrNull(index) ?: MacroRunMode.PARALLEL
                    mode = next
                    prefs.macroRunMode = next.key
                    // 让路开关是旧字段，保持同步，避免别的旧代码读出互相矛盾的值
                    prefs.macroManualFirst = next == MacroRunMode.YIELD
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                mode.hint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = GlassColors.TextMuted
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "多指并行下：连招不看你手指，按自己的节奏用极短点击注入，像第三根手指。",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "但「能不能真的边走边放」取决于执行方式：无障碍每发一次手势，系统都会取消" +
                    "你正按着的摇杆（这是 Android 的硬规则，改不了）；Shizuku / Root 注入的是" +
                    "独立指针，才会和你的手指并存。按住摇杆时角色一卡一卡，就是还在用无障碍。",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = GlassColors.TextMuted
            )

            if (mode == MacroRunMode.PARALLEL) {
                GlassDivider()
                Spacer(Modifier.height(8.dp))
                var pulse by remember { mutableFloatStateOf(prefs.macroPulseMs.toFloat()) }
                LabeledMiniSlider(
                    label = "点击脉冲时长",
                    valueText = "${pulse.toInt()} ms · 越短越不抢你的手指",
                    value = pulse,
                    range = 16f..80f,
                    onChange = { pulse = it },
                    onCommit = { prefs.macroPulseMs = pulse.toInt() }
                )
            }

            if (mode == MacroRunMode.YIELD) {
                GlassDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    "松手后多久继续",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(6.dp))
                var resumeDelay by remember { mutableStateOf(prefs.macroResumeDelayMs.toLong()) }
                GlassSegmented(
                    options = MacroRunPolicy.RESUME_OPTIONS.map { MacroRunPolicy.label(it) },
                    selected = MacroRunPolicy.RESUME_OPTIONS.indexOf(resumeDelay)
                        .let { if (it < 0) 1 else it },
                    onSelect = { index ->
                        val ms = MacroRunPolicy.RESUME_OPTIONS.getOrNull(index) ?: 800L
                        resumeDelay = ms
                        prefs.macroResumeDelayMs = ms.toInt()
                    }
                )
            }
        }
    }
    GlassHint(
        "红米 / 小米游戏空间的自动连招能边走边放，是因为它们有系统签名，直接调 " +
            "InputManager 注入独立指针。第三方 App 拿不到 SIGNATURE 级的 INJECT_EVENTS，" +
            "等价做法只有两条：装 Shizuku（用 adb 权限，免 root）或在已 root 的设备上走 su。" +
            "两条都没有时只能退回无障碍 —— 它能放技能，但每一下都会打断你的摇杆。"
    )
    GlassHint(
        "Shizuku 怎么开：① 装 Shizuku（应用商店 / GitHub 搜 Shizuku）；" +
            "② 开发者选项里打开「无线调试」，按 Shizuku 里的提示配对并启动服务；" +
            "③ 回到连招面板点「连接 Shizuku」，在弹窗里允许。之后执行方式保持「自动」即可。"
    )
}

/**
 * 三家品牌连招方案：点一下就生成一颗置顶悬浮按钮，之后可改名 / 重新取点 / 换图标。
 */
@Composable
fun FpsWindowSection() {
    val context = LocalContext.current
    val prefs = remember { OverlayPrefs(context) }
    var shown by remember { mutableStateOf(prefs.fpsShown) }
    var bgStyle by remember { mutableStateOf(prefs.fpsBgStyle) }
    var alpha by remember { mutableFloatStateOf(prefs.fpsAlpha) }
    var showMs by remember { mutableStateOf(prefs.fpsShowMs) }
    var textColor by remember { mutableStateOf(prefs.fpsTextColor) }

    fun applyFps() {
        if (shown) FloatingService.applyFps(context)
    }

    GlassSectionTitle("帧率悬浮窗")
    GlassCard {
        // 总开关
        GlassRow(
            title = "显示帧率悬浮窗",
            subtitle = "显示当前屏幕的合成帧率，可拖动到任意位置；透明/不透明都能自定义",
            icon = Icons.Rounded.Speed,
            onClick = {
                shown = !shown
                FloatingService.showFps(context, shown)
            }
        ) {
            GlassSwitch(
                checked = shown,
                onCheckedChange = {
                    shown = it
                    FloatingService.showFps(context, it)
                }
            )
        }
        GlassDivider()

        Column(Modifier.padding(14.dp)) {
            Text("背景", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlassColors.Text)
            Spacer(Modifier.height(8.dp))
            GlassSegmented(
                options = listOf("全透明", "半透明", "不透明"),
                selected = bgStyle,
                onSelect = {
                    bgStyle = it
                    prefs.fpsBgStyle = it
                    applyFps()
                }
            )
            Spacer(Modifier.height(12.dp))
            LabeledMiniSlider(
                label = "整体不透明度",
                valueText = "${(alpha * 100).toInt()}%",
                value = alpha,
                range = 0.2f..1f,
                onChange = { alpha = it },
                onCommit = {
                    prefs.fpsAlpha = alpha
                    applyFps()
                }
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("顺带显示帧耗时 (ms)", fontSize = 12.sp, color = GlassColors.Text, modifier = Modifier.weight(1f))
                GlassSwitch(
                    checked = showMs,
                    onCheckedChange = {
                        showMs = it
                        prefs.fpsShowMs = it
                        applyFps()
                    }
                )
            }
        }
    }

    // 文字颜色
    GlassSectionTitle("帧率窗 · 文字颜色")
    GlassCard {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("颜色", fontSize = 12.sp, color = GlassColors.Text)
            Spacer(Modifier.width(12.dp))
            val colors = listOf(
                0 to "自动",
                0xFFFFFFFF.toInt() to "白",
                0xFF7CFC9B.toInt() to "绿",
                0xFF64D8FF.toInt() to "青",
                0xFFFFD166.toInt() to "黄",
                0xFFFF6B6B.toInt() to "红"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { (argb, _) ->
                    val active = textColor == argb
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (argb == 0) {
                                    Color(0xFF3D6BF5)
                                } else {
                                    Color(argb)
                                }
                            )
                            .border(
                                if (active) 2.dp else 1.dp,
                                if (active) Color.White else GlassColors.TextMuted.copy(alpha = 0.3f),
                                CircleShape
                            )
                            .clickable {
                                textColor = argb
                                prefs.fpsTextColor = argb
                                applyFps()
                            }
                    )
                }
            }
        }
    }

    GlassHint(
        "帧率来自屏幕合成节奏（Choreographer 采样的显示帧率），不是某个游戏引擎内部 FPS —— " +
            "第三方悬浮窗读不到别的进程引擎帧率，需要系统 / Root 权限，这里如实显示可稳定拿到的值。"
    )
}

/** 简化版带标签滑杆，避免依赖 CrosshairScreen 里的私有组件 */
@Composable
private fun LabeledMiniSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlassColors.Text)
            Text(valueText, fontSize = 10.sp, color = GlassColors.TextMuted)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = GlassColors.Accent,
                activeTrackColor = GlassColors.Accent,
                inactiveTrackColor = GlassColors.TextMuted.copy(alpha = 0.22f)
            )
        )
    }
}

/**
 * v2.5.7 连招录制台（游戏助手式）。
 *
 * 上一版这里放的是「iQOO / 小米 / OPPO 品牌预设」—— 那只是三条写死的连点 / 循环，
 * 跟用户要的「游戏空间里自己摆键、自己按节奏录」完全不是一回事，已整块删掉。
 * 现在的入口就一个：进录制台摆按键 → 按节奏点 → 完成，录出来的就是自己的连招。
 */
@Composable
fun MacroStudioSection(onEnsureMacroWindow: () -> Unit = {}) {
    val context = LocalContext.current
    val macros by MacroController.macros.collectAsState()
    val scope = rememberCoroutineScope()

    // 录制台是挂在连招悬浮服务上的：服务没起来时先把它拉起来，等窗口就绪再进录制，
    // 否则用户点了按钮只会「毫无反应」——那正是上一版被骂的地方。
    val startRecord: (com.yunx.app.overlay.macro.MacroConfig?) -> Unit = { target ->
        onEnsureMacroWindow()
        // v2.6.0：原来点了要静默等最多 2.6 秒（服务没起来时更久），期间界面上一点反应都没有，
        // 看着就像「按钮坏了」；小抄窗口还开着时尤其明显。现在点了立刻给一句反馈，
        // 轮询也从 120ms 缩到 80ms，等待上限放到 3.2s，最后没等到就直接说清楚该点哪里。
        Toast.makeText(context, "正在准备录制台…", Toast.LENGTH_SHORT).show()
        scope.launch {
            var waited = 0
            while (MacroController.host == null && waited < 3200) {
                kotlinx.coroutines.delay(80)
                waited += 80
            }
            if (MacroController.host == null) {
                Toast.makeText(context, "连招窗还没起来，先打开上面的「自动连招面板」再试", Toast.LENGTH_SHORT).show()
            } else {
                MacroController.startKeypadRecord(context, target)
            }
        }
    }

    GlassSectionTitle("连招录制台")
    GlassCard {
        Column(Modifier.padding(14.dp)) {
            Text(
                "像游戏空间那样自己录",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Text
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "① 先把按键拖到游戏里的技能键上\n" +
                    "② 按你平时的节奏依次点它们\n" +
                    "③ 点「完成」直接存成一颗连招按钮\n" +
                    "录制时只有按键那一小块吃触摸，屏幕其它地方照常走位、转视角。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = GlassColors.TextMuted
            )
            Spacer(Modifier.height(12.dp))
            GlassPrimaryButton(
                text = "开始按键录制",
                subtitle = "新建一条连招，边看画面边按键",
                icon = Icons.Rounded.Bolt,
                onClick = { startRecord(null) }
            )
            if (macros.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "重录已保存的连招（沿用它的按键位置）",
                    fontSize = 11.sp,
                    color = GlassColors.TextMuted
                )
                macros.take(5).forEach { m ->
                    GlassDivider()
                    GlassRow(
                        title = m.name,
                        subtitle = "${m.steps.size} 步 · ${m.keys.size} 个按键" +
                            if (m.keys.isEmpty()) "（旧录制，需重新摆键）" else "",
                        icon = Icons.Rounded.Refresh,
                        onClick = { startRecord(m) }
                    ) {
                        Text("重录", fontSize = 11.sp, color = GlassColors.Accent)
                    }
                }
            }
        }
    }
}
