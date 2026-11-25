/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.5.0）。内置准星。
 *
 * 用户原话：「加几个，去网上搜索几个经常使用的准星加进去，免得到时候还要自己导入。
 * 导入功能不错，但是再多加几个自带的准星。」
 *
 * 选型依据是社区里最常被抄的那几套（CS2 / 无畏契约 / Apex / PUBG）：
 *   · 细十字 + 小间隙、无中心点     —— CS2 当前主流（NiKo / ZywOo 那一类）
 *   · 十字 + 中心点                —— TenZ 的 1-4-2-2 青十字
 *   · 纯中心点                     —— 点射 / 微冲玩家，最不挡视野
 *   · T 形 / 只有下半             —— PUBG 玩家，下半视野完全打开
 *   · 四角括号                     —— 无畏契约的方框式瞄准参考
 *   · 圆环 / 双环 / 环 + 点         —— Apex 追踪型，用环框住目标
 *   · V 形箭头                     —— PUBG 的 chevron
 * 全部带 1px 黑描边：亮墙、暗门、烟雾边缘都能看清（社区经验里比颜色更重要的一条）。
 *
 * 图片是 512×512 透明 PNG，打包在 assets/crosshairs 下；第一次用到时复制到
 * filesDir/builtin_crosshairs。用独立目录是为了让「删除这套准星」不会把内置图删掉，
 * 下一位用户（或者重新添加）还能直接复用。
 */

package com.yunx.app.overlay.data

import android.content.Context
import java.io.File

/** 一套内置准星：只描述「是什么」，路径在安装后才有 */
data class BuiltinCrosshair(
    val key: String,
    val name: String,
    /** 一句话说明它适合什么，列表里直接显示，省得用户一个个点开试 */
    val hint: String,
    /** 默认显示宽度（dp）。用户套用后还能在滑杆上改 */
    val widthDp: Int
)

/** 已经落到磁盘上的一套内置准星 */
data class InstalledCrosshair(
    val meta: BuiltinCrosshair,
    val path: String
)

object BuiltinCrosshairs {

    val all: List<BuiltinCrosshair> = listOf(
        BuiltinCrosshair("cross_cyan", "经典青十字", "CS2 主流 · 细线小间隙", 54),
        BuiltinCrosshair("cross_green", "绿色细十字", "s1mple 风 · 不挡视野", 52),
        BuiltinCrosshair("cross_white", "白色细十字", "Demon1 1-1-0-1 · 极简", 48),
        BuiltinCrosshair("cross_dot_cyan", "青十字 + 点", "TenZ 1-4-2-2 · 点射稳", 56),
        BuiltinCrosshair("cross_closed_green", "无缝绿十字", "NiKo 风 · 中心不留空", 50),
        BuiltinCrosshair("cross_thick_yellow", "粗黄十字", "高对比 · 远距离也看得清", 58),
        BuiltinCrosshair("t_shape_green", "T 形绿准星", "PUBG 常用 · 下方视野全开", 52),
        BuiltinCrosshair("hbar_cyan", "横向双线", "只留左右 · 不压爆头线", 60),
        BuiltinCrosshair("ring_dot_green", "圆环 + 点", "Apex 追踪 · 环住目标", 62),
        BuiltinCrosshair("double_ring_cyan", "双环", "腰射 / 霰弹 · 范围参考", 64),
        BuiltinCrosshair("ring_white", "白色圆环", "纯环 · 不挡中心", 66),
        BuiltinCrosshair("brackets_white", "四角括号", "无畏契约 · 方框瞄准", 68),
        BuiltinCrosshair("brackets_cyan", "四角括号 · 青", "无畏契约 · 高对比", 68),
        BuiltinCrosshair("chevron_green", "V 形箭头", "PUBG chevron · 快速锁头", 58),
        BuiltinCrosshair("x_pink", "斜十字 · 粉", "喷子 / 近战 · 辨识度高", 52),
        BuiltinCrosshair("triangle_red", "红色三角", "极简指示 · 不遮中心", 34),
        BuiltinCrosshair("ring_dot_purple", "紫环 + 点", "暗图专用 · 紫不撞色", 56)
    )

    private const val DIR = "builtin_crosshairs"

    /**
     * 把 assets 里的准星图铺到私有目录（已存在就跳过），返回可用的列表。
     *
     * 读 assets 是 IO，调用方放后台线程。
     */
    fun install(context: Context): List<InstalledCrosshair> {
        val app = context.applicationContext
        val dir = File(app.filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return emptyList()

        val out = ArrayList<InstalledCrosshair>(all.size)
        all.forEach { meta ->
            val dest = File(dir, "${meta.key}.png")
            if (!dest.isFile || dest.length() == 0L) {
                runCatching {
                    app.assets.open("crosshairs/${meta.key}.png").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            if (dest.isFile) out += InstalledCrosshair(meta, dest.absolutePath)
        }
        return out
    }

    /** 这个路径是不是内置准星（删配置时不要删源文件） */
    fun isBuiltinPath(path: String?): Boolean =
        !path.isNullOrBlank() && path.contains("/$DIR/")
}
