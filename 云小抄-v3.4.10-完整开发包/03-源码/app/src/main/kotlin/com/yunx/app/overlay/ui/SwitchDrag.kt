/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.19 开关支持拖动后，「松手落到开还是关」的判定。
 *
 * 为什么要单独抽出来：
 *   1. 三种意图要分开 —— 轻点（原地按一下）、拖过半、甩一下。写成 if 嵌套很容易
 *      把「甩」的优先级写反，表现是「明明往右甩了却弹回去」，肉眼很难定位；
 *   2. 纯函数才能在 JVM 单测里跑，不用起模拟器。
 */

package com.yunx.app.overlay.ui

/** 甩动判定阈值（像素 / 秒）。约等于手指快速划一下的速度。 */
internal const val SWITCH_FLING_PX_PER_SEC = 480f

/**
 * 开关松手后应该落在哪个状态。
 *
 * @param startChecked 按下前的状态
 * @param moved 这次手势是否超过了 touchSlop（false = 轻点）
 * @param position 松手时滑块的位置，0 = 关到底，1 = 开到底
 * @param speedPxPerSec 松手瞬间的横向速度，正 = 往开的方向
 * @param flingThresholdPxPerSec 超过这个速度就按「甩」处理，不看位置
 * @param half 位置过半就翻面
 */
internal fun switchTarget(
    startChecked: Boolean,
    moved: Boolean,
    position: Float,
    speedPxPerSec: Float,
    flingThresholdPxPerSec: Float = SWITCH_FLING_PX_PER_SEC,
    half: Float = 0.5f
): Boolean {
    // 没拖动 = 点了一下，永远是「取反」，跟手指落在轨道哪一侧无关
    if (!moved) return !startChecked
    // 甩的优先级高于位置：快速往右划一下，哪怕只走了 1/4，也应该开
    return when {
        speedPxPerSec > flingThresholdPxPerSec -> true
        speedPxPerSec < -flingThresholdPxPerSec -> false
        else -> position >= half
    }
}
