/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v3.1.0），v3.2.0 重写。
 *
 * 日夜切换的过场。v3.1.0 做的是「斜切色块」；对着《春秋》的录屏逐帧量过之后
 * 发现不是斜线，是**圆**：
 *
 *   把每一帧里「暗区/亮区」的分界线取出来，拟合圆心在 (0.80w, 0.084h)、
 *   半径从 0 一路涨到 610px（640×1422 的录屏）—— 正好是右上角那颗月亮按钮，
 *   圆的边缘扫过左下角。也就是说它用的是 Android 经典的 circular reveal：
 *
 *     ViewAnimationUtils.createCircularReveal(view, cx, cy, 0f, maxRadius)
 *
 * Compose 里没有现成的 createCircularReveal，但等价做法很简单：
 *   1. 切换前把当前界面截成一张位图（旧界面）；
 *   2. 立刻切主题 —— 底下已经是新界面；
 *   3. 把旧位图盖在最上层，用 clipPath(Difference) 挖一个越来越大的圆洞，
 *      洞里透出新界面；
 *   4. 圆盖满整屏后把位图撤掉。
 *
 * 这样看到的就是「新界面从一个点长出来，把旧界面推走」，而不是整屏闪一下。
 */

package com.yunx.app.overlay.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一次扫屏所需的全部东西。
 *
 * @param snapshot 切换前那一帧的截图（旧界面）
 * @param center   圆心，单位 px，坐标系与根视图一致（右上角那颗日夜按钮的中心）
 */
internal class ThemeWipeRequest(
    val snapshot: Bitmap,
    val center: Offset
)

/**
 * @param request 非空 = 正在扫；宿主在动画结束（或异常）后把它置空
 * @param onEnd   扫完 / 被取消时回调
 */
@Composable
internal fun ThemeWipeLayer(
    request: ThemeWipeRequest?,
    onEnd: () -> Unit
) {
    val progress = remember(request) { Animatable(0f) }

    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        // 兜底：动画被取消（转屏 / 页面被移出组合 / 系统回收）时也要把宿主状态清掉，
        // 否则这块整屏位图会一直留在最上层，把所有点击都吃掉 —— 看起来就是「卡死」。
        try {
            progress.snapTo(0f)
            withTimeoutOrNull(1500) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(520, easing = FastOutSlowInEasing)
                )
            }
        } finally {
            onEnd()
        }
    }

    if (request == null) return

    val image: ImageBitmap = remember(request) { request.snapshot.asImageBitmap() }
    val center = request.center

    Canvas(
        Modifier
            .fillMaxSize()
            // 这半秒内吃掉所有触摸：否则手指可能点到「新主题里刚好飘到那个位置的按钮」
            .pointerInput(request) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // 圆要能盖住最远的那个角，否则收尾时会剩一条边没换过来
        val maxRadius = max(
            max(hypot(center.x, center.y), hypot(w - center.x, center.y)),
            max(hypot(center.x, h - center.y), hypot(w - center.x, h - center.y))
        ) + 2f

        val radius = maxRadius * progress.value

        // 旧界面盖满整屏，只在圆内挖空 → 圆内是新界面，圆外还是旧界面
        val hole = Path().apply {
            addOval(Rect(center = center, radius = radius))
        }
        clipPath(hole, clipOp = ClipOp.Difference) {
            drawImage(
                image = image,
                dstSize = IntSize(w.roundToInt(), h.roundToInt())
            )
        }
    }
}
