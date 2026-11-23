/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.0.2 新增。悬浮窗「界面主题」下的自主调色盘：
 * 色相条 + 饱和度/明度面板 + 常用色，不引第三方取色库。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/** 常用色：挑的是「一眼能分清、放到深色顶栏上不发灰」的那几颗 */
private val PRESET_COLORS = listOf(
    0xFF2FD6C4.toInt(), // 青碧
    0xFF4F46E5.toInt(), // 靛蓝
    0xFF7C3AED.toInt(), // 暮紫
    0xFFE11D48.toInt(), // 玫红
    0xFF059669.toInt(), // 翡翠
    0xFFF97316.toInt(), // 橙
    0xFF0EA5E9.toInt(), // 湖蓝
    0xFFFF4FA3.toInt()  // 桃粉
)

/**
 * 自主调色盘。
 *
 * 交互就两件事：在方块里点/拖选饱和度与明度，在彩条上点/拖选色相。
 * 面板上用 Canvas 画三层（纯色相 → 白到透明 → 透明到黑）叠出标准 HSV 方块，
 * 不引第三方取色库，也就不必为几十 KB 的依赖改一遍混淆规则。
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    title: String = "自定义颜色"
) {
    val seed = remember(initial) { if (initial == 0) 0xFF7C3AED.toInt() else initial }
    val hsv = remember(seed) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(seed, it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceIn(0.2f, 1f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.35f)) }
    val current = remember(hue, sat, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    }
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(22.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .clip(shape)
                .background(t.cardBottom)
                .border(1.dp, t.stroke, shape)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(current))
                        .border(1.dp, t.stroke, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(hexOf(current), fontSize = 11.sp, color = t.textSub)
            }

            Spacer(Modifier.height(12.dp))
            SvPanel(hue, sat, value) { s, v ->
                sat = s
                value = v
            }

            Spacer(Modifier.height(10.dp))
            HueBar(hue) { hue = it }

            Spacer(Modifier.height(12.dp))
            Text("常用", fontSize = 10.sp, color = t.textMuted)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESET_COLORS.forEach { c ->
                    val picked = (c and 0xFFFFFF) == (current and 0xFFFFFF)
                    Box(
                        Modifier
                            .size(25.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .then(
                                if (picked) Modifier.border(2.dp, t.text, CircleShape) else Modifier
                            )
                            .clickable {
                                val p = FloatArray(3)
                                android.graphics.Color.colorToHSV(c, p)
                                hue = p[0]
                                sat = p[1]
                                value = p[2]
                            }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerButton(
                    text = "取消",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                PickerButton(
                    text = "用这个色",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfirm(current) }
                )
            }
        }
    }
}

/** 饱和度（横）× 明度（纵）面板 */
@Composable
private fun SvPanel(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(164.dp)
            .clip(shape)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun apply(pos: Offset) {
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        onChange(
                            (pos.x / w).coerceIn(0f, 1f),
                            (1f - pos.y / h).coerceIn(0f, 1f)
                        )
                    }
                    val down = awaitFirstDown()
                    apply(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        apply(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRect(pure)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        }
        // 指示圈：白描边 + 黑内圈，落在任何底色上都看得见
        val cx = sat * boxSize.width
        val cy = (1f - value) * boxSize.height
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (cx - 9.dp.toPx()).roundToInt(),
                        (cy - 9.dp.toPx()).roundToInt()
                    )
                }
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, Color.White, CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.45f), CircleShape)
        )
    }
}

/** 色相条：0~360 全彩，指示条直接画在 Canvas 上，不用再量一次宽度 */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val stops = remember {
        (0..6).map { i ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 60f % 360f, 1f, 1f)))
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun apply(pos: Offset) {
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        onChange((pos.x / w).coerceIn(0f, 0.999f) * 360f)
                    }
                    val down = awaitFirstDown()
                    apply(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        apply(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        drawRect(Brush.horizontalGradient(stops))
        val x = (hue / 360f).coerceIn(0f, 1f) * size.width
        val half = 2.dp.toPx()
        drawLine(
            Color.Black.copy(alpha = 0.35f),
            Offset(x - half - 1f, 0f),
            Offset(x - half - 1f, size.height),
            strokeWidth = 1f
        )
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = half * 2)
        drawLine(
            Color.Black.copy(alpha = 0.35f),
            Offset(x + half + 1f, 0f),
            Offset(x + half + 1f, size.height),
            strokeWidth = 1f
        )
    }
}

@Composable
private fun PickerButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) t.accent else t.panelTop)
            .border(1.dp, if (primary) Color.Transparent else t.stroke, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else t.textSub
        )
    }
}

private fun hexOf(color: Int): String =
    String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)
