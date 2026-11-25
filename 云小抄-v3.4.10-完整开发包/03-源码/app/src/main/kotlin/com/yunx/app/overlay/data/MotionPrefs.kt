/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件（v2.1.0）。全 App 共用一份「动画开关 + 速度」设置。
 *   动画散落在 Activity（开场 / 切页 / 子页面）、Service（窗口伸缩）、
 *   以及 Service 里的 ComposeView（小窗碎块 / 模块浮起）三处 Composition，
 *   所以用进程内单例 + StateFlow：任何一处改了，其他树重组时立刻读到新值；
 *   非 Compose 的 View 动画则直接读 [duration] 的当前值。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

object MotionPrefs {

    private const val FILE = "motion_prefs"
    private const val KEY_ENABLED = "anim_enabled"
    private const val KEY_SPEED = "anim_speed"
    private const val KEY_ENTRANCE = "anim_entrance"
    private const val KEY_SWITCH = "anim_switch"
    private const val KEY_SLIDE = "slide_effect"

    /** 四档速度。索引即持久化值，插新档只能往后加，不能改旧值含义 */
    val SPEED_LABELS = listOf("慢", "标准", "快", "极快")

    /** 时长除数：越大越快。默认「标准」= 1f，保持 v2.0.11 的手感 */
    private val SPEED_FACTORS = floatArrayOf(0.7f, 1f, 1.5f, 2.3f)
    const val DEFAULT_SPEED = 1

    /**
     * v2.4.0：动画风格，两处各存一份，可以混搭。
     *
     * 用户原话：「把这些画面全部切成细细碎碎的格子，一块一块从左上角慢慢到右下角
     * 慢慢出现」——那是 [TILES]；「你做的这个动画也不错，更简约」指的是 [RISE]
     * （模块逐个浮起）。他还要「这个也不错，可以作为切换界面时的动画」，
     * 所以开屏与切页分开设：碎格开屏 + 浮现切页这种组合能同时存在。
     */
    const val TILES = 0
    const val RISE = 1

    /** 入场（点图标进软件那一下） */
    val ENTRANCE_LABELS = listOf("碎格", "浮现")
    const val DEFAULT_ENTRANCE = TILES

    /**
     * 切页（页签、抽屉、关于 / 使用说明之间）。
     *
     * 默认给「浮现」：用户说碎格是开屏那一下的，切页用模块逐块浮起更耐看，
     * 每次切页都扫一遍大方块反而挡手。
     */
    val SWITCH_LABELS = listOf("碎块", "浮现")
    const val DEFAULT_SWITCH = RISE

    /**
     * v3.0.8：主页模块的「滑动方式」，只留两种。
     *
     * v3.0.3 ~ v3.0.7 试过「层叠淡出」和「转轮」两套分页划法，都要自己接管
     * 手势、自己算吸附，结果是斜着滑不动、点按钮被吞、翻页时整页往下弹。
     * 用户最后给了图 1：不要一屏一张，就要**一叠卡**，后一张压住前一张的下沿，
     * 普通上下滑。所以两套分页实现全部删掉，换成 [SLIDE_STACK] 的重叠卡组。
     *
     * - [SLIDE_PLAIN]：最初那版，整页普通滚动，模块逐个浮起；
     * - [SLIDE_STACK]：卡组 —— v3.1.0 起是「钱包式」：收起时最上面一张完整、
     *   其余只露一条标题边，点一下整摞散成完整列表。没有分页、没有吸附、
     *   不拦任何点击，滚动仍然是系统原生滚动。
     */
    const val SLIDE_PLAIN = 0
    const val SLIDE_STACK = 1

    /** 能选的就这两种 */
    const val SLIDE_SELECTABLE = 2

    val SLIDE_LABELS = listOf("普通", "卡组")
    val SLIDE_FULL_LABELS = listOf("普通滚动", "卡组堆叠")

    /** 旧版本里的「转轮 / 开发中」都落到卡组 —— 别让升级用户回到普通模式 */
    private const val LEGACY_WHEEL = 2
    private const val LEGACY_COMING = 3

    const val DEFAULT_SLIDE = SLIDE_PLAIN

    private var sp: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    private val _speed = MutableStateFlow(DEFAULT_SPEED)
    private val _entrance = MutableStateFlow(DEFAULT_ENTRANCE)
    private val _switchStyle = MutableStateFlow(DEFAULT_SWITCH)
    private val _slide = MutableStateFlow(DEFAULT_SLIDE)

    val enabled: StateFlow<Boolean> = _enabled
    val speed: StateFlow<Int> = _speed
    val entrance: StateFlow<Int> = _entrance
    val switchStyle: StateFlow<Int> = _switchStyle
    val slide: StateFlow<Int> = _slide

    /** 幂等。Activity / Service 起来时各调一次；没调到也只用默认值，不会崩 */
    @Synchronized
    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        _speed.value = p.getInt(KEY_SPEED, DEFAULT_SPEED).coerceIn(0, SPEED_FACTORS.lastIndex)
        _entrance.value = p.getInt(KEY_ENTRANCE, DEFAULT_ENTRANCE).coerceIn(TILES, RISE)
        _switchStyle.value = p.getInt(KEY_SWITCH, DEFAULT_SWITCH).coerceIn(TILES, RISE)
        // v3.0.8：旧值 2（转轮）/ 3（开发中）都映射到卡组；其余夹到 0..1。
        // 用户之前特意选过堆叠效果，升级后不能悄悄退回普通滚动。
        val saved = p.getInt(KEY_SLIDE, DEFAULT_SLIDE)
        _slide.value = when (saved) {
            LEGACY_WHEEL, LEGACY_COMING -> SLIDE_STACK
            else -> saved.coerceIn(SLIDE_PLAIN, SLIDE_STACK)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        _enabled.value = value
        sp?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    fun setSpeed(context: Context, index: Int) {
        init(context)
        val v = index.coerceIn(0, SPEED_FACTORS.lastIndex)
        _speed.value = v
        sp?.edit()?.putInt(KEY_SPEED, v)?.apply()
    }

    fun setEntrance(context: Context, style: Int) {
        init(context)
        val v = style.coerceIn(TILES, RISE)
        _entrance.value = v
        sp?.edit()?.putInt(KEY_ENTRANCE, v)?.apply()
    }

    fun setSwitchStyle(context: Context, style: Int) {
        init(context)
        val v = style.coerceIn(TILES, RISE)
        _switchStyle.value = v
        sp?.edit()?.putInt(KEY_SWITCH, v)?.apply()
    }

    fun setSlide(context: Context, mode: Int) {
        init(context)
        val v = mode.coerceIn(SLIDE_PLAIN, SLIDE_STACK)
        _slide.value = v
        sp?.edit()?.putInt(KEY_SLIDE, v)?.apply()
    }

    /** 传索引是为了让 Compose 侧能显式依赖 `speed` 这个 State，改档时立刻重算 */
    fun factor(index: Int = _speed.value): Float =
        SPEED_FACTORS[index.coerceIn(0, SPEED_FACTORS.lastIndex)]

    /**
     * 把基准时长按当前档位折算。**返回 0 表示动画已关闭**，调用方必须直接
     * `snapTo` 到终态，不能拿 0 去 `tween(0)` —— 那样第一帧仍会被遮罩盖住。
     */
    fun duration(baseMs: Int): Int =
        if (!_enabled.value) 0 else (baseMs / factor()).roundToInt().coerceAtLeast(1)

    fun duration(baseMs: Long): Long = duration(baseMs.toInt()).toLong()
}

/**
 * v2.4.0：区分「冷启动首屏」和「之后的切页」。
 *
 * 开屏和切页可以选不同风格，但承载它们的容器是同一个 StaggerColumn ——
 * 它自己分不出这次是首屏还是切页。TileEntrance 的入场动画跑完就把旗子放倒，
 * 之前都算首屏。Activity 重建时会重新立起来（在 onCreate 里）。
 */
object MotionRuntime {
    @Volatile
    var coldStart: Boolean = true
}
