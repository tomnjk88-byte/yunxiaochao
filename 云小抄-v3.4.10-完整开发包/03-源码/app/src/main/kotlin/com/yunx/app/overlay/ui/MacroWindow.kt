/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.6.0 起自动连招从攻略悬浮窗里独立出来，单独占一个
 * WindowManager 窗口。用户可从主页开关、攻略窗顶栏闪电按钮、通知栏自由打开/收起，
 * 不影响攻略窗；本窗口的位置与尺寸单独持久化。
 */

package com.yunx.app.overlay.ui

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunx.app.overlay.data.MotionPrefs

/**
 * 独立连招悬浮窗的内容。
 *
 * 与攻略窗共用「拖动按钮 + 标题热区 + 右下角拉伸手柄」这套交互，
 * 但不带双指捏合（避免两个窗口的缩放状态互相干扰），也不需要预览/分类/面包屑。
 */
@Composable
fun MacroWindowContent(
    dragTouchListener: View.OnTouchListener,
    resizeTouchListener: View.OnTouchListener,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = currentScheme()
    // v2.0.8：连招窗也走碎块掀开，和小抄、主界面同一套手感。
    // 球展开成面板同样会播一次 —— 那本来就是一次大的形态变化，碎一下不算打扰。
    // v2.1.0：连招窗也跟着动画开关 / 速度走。关掉时直接 snap 到 1f，
    // tileCurtain 见到进度 >= 0.999 就整层不画。
    val enabled by MotionPrefs.enabled.collectAsState()
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        withFrameNanos { }
        withFrameNanos { }
        // 620ms：比攻略窗短。球↔面板可能来回切得勤，900ms 会显得拖
        val ms = MotionPrefs.duration(620)
        if (ms <= 0) entrance.snapTo(1f)
        else entrance.animateTo(1f, tween(ms, easing = LinearEasing))
    }
    Box(
        modifier
            .fillMaxSize()
            .tileCurtain(
                progress = { entrance.value },
                color = OverlayColors.Page,
                // 小窗用 46dp 那一档，和攻略窗里完全一致
                spec = TileSpec.WINDOW
            )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = OverlayColors.Card,
            border = BorderStroke(1.dp, OverlayColors.Divider)
        ) {
            Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Brush.verticalGradient(listOf(scheme.heroStart, scheme.heroEnd)))
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MoveHandle(dragTouchListener)
                Spacer(Modifier.width(4.dp))
                // 标题区整片可拖，和攻略窗手感一致
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text(
                            text = "自动连招",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        // v1.8.2：整窗长按也能拖，这里给出明示，避免用户只盯着顶栏那条热区
                        // v2.11.2：补上「边缘」—— 三边 14dp 内按住就跟着手指走
                        Text(
                            text = "长按 / 拖边缘 · 可滑动",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 8.sp,
                            maxLines = 1
                        )
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            View(ctx).apply { setOnTouchListener(dragTouchListener) }
                        }
                    )
                }
                MacroHeaderIcon(Icons.Rounded.Minimize, "把连招缩小成球") { onCollapse() }
                MacroHeaderIcon(Icons.Rounded.Close, "收起连招悬浮窗") { onClose() }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MacroPanel(Modifier.fillMaxSize())
                ResizeGrip(
                    listener = resizeTouchListener,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                )
            }
        }
        }
    }
}

@Composable
private fun MacroHeaderIcon(
    image: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            image,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}
