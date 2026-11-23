/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.7.0 起连招窗也能缩成一颗「独立小球」：
 * 它和攻略的「小抄球」是两层互不相关的窗口，点各自只展开各自的窗，
 * 不会再出现「点开小抄，连招窗跟着一起冒出来」的联动。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 连招球：渐变圆 + 闪电图标，可拖到任意位置，点一下单独展开连招窗。
 *
 * 用与小抄球相反方向的渐变（heroEnd → primary）做区分，游戏里一眼能认出
 * 哪颗是攻略、哪颗是连招。
 */
@Composable
fun MacroBubbleContent(
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val scheme = currentScheme()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                ) { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(listOf(scheme.heroEnd, scheme.primary)),
                    CircleShape
                )
                .border(2.dp, Color.White.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = "展开自动连招",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
