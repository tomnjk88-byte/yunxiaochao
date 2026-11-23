/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件（v1.9.18）。按压光圈。
 *
 * 华为那些宣传片里，手指按到玻璃上时不是一块灰色方块扩散，而是**沿着控件边缘亮起一圈**，
 * 松手后光圈往外散掉。这里就做这件事：
 *   - 通过 [LocalIndication] 全局替换默认水波纹，所有 `Modifier.clickable` 自动生效；
 *   - 按下时 140ms 亮起：1.2dp 主色描边 + 内侧 6dp 柔光，亮度 55%；
 *   - 松手后 360ms 淡出，同时光圈微微外扩（1 → 1.04），像被弹开；
 *   - 只画在控件内部，不改变布局、不挡点击、不遮文字。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 按压光圈。用 [androidx.compose.foundation.LocalIndication] 提供，全局生效。
 *
 * @param color 光圈颜色，通常取当前风格的主色
 */
class GlowIndication(private val color: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlowNode(interactionSource, color)

    override fun equals(other: Any?): Boolean =
        other is GlowIndication && other.color == color

    override fun hashCode(): Int = color.hashCode()
}

private class GlowNode(
    private val source: InteractionSource,
    private val color: Color
) : Modifier.Node(), DrawModifierNode {

    /** 0 = 完全熄灭，1 = 最亮。用 State 而不是普通字段：draw 阶段读它会自动触发重绘 */
    private val glow = mutableFloatStateOf(0f)
    private var job: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> animateTo(1f, 140)
                    is PressInteraction.Release, is PressInteraction.Cancel -> animateTo(0f, 360)
                }
            }
        }
    }

    override fun onDetach() {
        job?.cancel()
        job = null
    }

    /** 逐帧推进，不依赖 Animatable：Node 里拿不到 composition，用 withFrameNanos 最稳 */
    private fun animateTo(target: Float, durationMs: Int) {
        job?.cancel()
        job = coroutineScope.launch {
            val start = glow.floatValue
            val begin = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val t = ((now - begin) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                // 亮起用 ease-out，熄灭用 ease-in-out，手感更接近真实玻璃
                val eased = if (target > start) 1f - (1f - t) * (1f - t)
                else t * t * (3f - 2f * t)
                glow.floatValue = start + (target - start) * eased
                if (t >= 1f) break
            }
            glow.floatValue = target
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val p = glow.floatValue
        if (p <= 0.004f) return

        // 松手后光圈外扩一点点：0 → 1 的过程中半径从 1.0 走到 1.04
        val spread = 3.dp.toPx() * (1f - p)
        val left = -spread
        val top = -spread
        val w = size.width + spread * 2f
        val h = size.height + spread * 2f
        val radius = min(w, h) * 0.22f

        // ① 内侧柔光：贴边 6dp 的一圈渐变，越靠边越亮
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(
                    color.copy(alpha = 0.30f * p),
                    Color.Transparent,
                    Color.Transparent,
                    color.copy(alpha = 0.18f * p)
                )
            ),
            topLeft = Offset(left + 1.dp.toPx(), top + 1.dp.toPx()),
            size = Size(w - 2.dp.toPx(), h - 2.dp.toPx()),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 6.dp.toPx())
        )

        // ② 亮边：这一圈就是「亮起来」的主角
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.85f * p),
                    color.copy(alpha = 0.65f * p),
                    Color.White.copy(alpha = 0.45f * p)
                )
            ),
            topLeft = Offset(left + 0.6.dp.toPx(), top + 0.6.dp.toPx()),
            size = Size(w - 1.2.dp.toPx(), h - 1.2.dp.toPx()),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 1.2.dp.toPx())
        )
    }
}
