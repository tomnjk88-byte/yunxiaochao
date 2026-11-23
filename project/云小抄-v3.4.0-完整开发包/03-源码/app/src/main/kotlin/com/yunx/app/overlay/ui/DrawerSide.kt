/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件（v1.9.17）。抽屉现在左右两条边都能拉开，这里放「边」的枚举和
 * 两个纯函数 —— 拖动量怎么换算成拉出距离、松手吸附到开还是关。抽出来的原因很实际：
 * 这两条规则一左一右特别容易写反（右抽屉的 dx 是反的），放成纯函数就能单测。
 */

package com.yunx.app.overlay.ui

/** 抽屉从哪条边拉出来 */
enum class DrawerSide(val key: String) {
    LEFT("left"),
    RIGHT("right");

    /** +1 = 往右拖是拉开；-1 = 往左拖才是拉开 */
    val pullSign: Float get() = if (this == LEFT) 1f else -1f

    val isLeft: Boolean get() = this == LEFT
}

/** 甩过这个速度就直接按方向吸附（px/s） */
const val DRAWER_FLING_VELOCITY = 900f

/** 拉出比例超过这个值就吸附到展开 */
const val DRAWER_OPEN_FRACTION = 0.35f

/**
 * 原始横向位移 → 「拉出距离」增量。
 *
 * 左抽屉：手指往右（dx > 0）= 拉开，符号不变；
 * 右抽屉：手指往左（dx < 0）= 拉开，要取反，否则面板会往反方向跑。
 */
fun drawerPullDelta(side: DrawerSide, dx: Float): Float = dx * side.pullSign

/**
 * 松手后停在哪儿。返回 0f（收起）或 panelPx（展开）。
 *
 * @param pullVelocity 统一成「拉开为正」的速度，右抽屉的原始速度要先乘 pullSign
 * @param offset       当前拉出距离
 */
fun drawerSettleTarget(pullVelocity: Float, offset: Float, panelPx: Float): Float = when {
    pullVelocity > DRAWER_FLING_VELOCITY -> panelPx
    pullVelocity < -DRAWER_FLING_VELOCITY -> 0f
    offset > panelPx * DRAWER_OPEN_FRACTION -> panelPx
    else -> 0f
}
