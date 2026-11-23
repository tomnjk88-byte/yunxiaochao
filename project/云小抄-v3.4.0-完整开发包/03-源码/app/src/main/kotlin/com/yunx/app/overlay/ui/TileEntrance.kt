/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.0.8 把「碎块掀开」从攻略窗里抽出来，
 * 给主界面冷启动、连招窗展开共用；v2.0.9 改成两种规格 ——
 * 启动用细格、切页用大格，并让每块走的时候先鼓一下再弹走。
 * v2.1.0：时长统一过 [MotionPrefs]，用户可关掉动画或调四档速度。
 */

package com.yunx.app.overlay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunx.app.overlay.data.MotionPrefs
import com.yunx.app.overlay.data.MotionRuntime
import com.yunx.app.overlay.data.SplashRuntime
import kotlin.math.ceil
import kotlin.math.sin

// ---------------------------------------------------------------------------
// v2.0.9：一套「碎块过场」，两种规格
// ---------------------------------------------------------------------------
//
// 用户要的是两件事：
//   · 从桌面点图标进来 —— 细细碎碎的小格子，一层麻点铺满屏，然后从左上往右下
//     一块块散开，界面跟着浮出来；
//   · 小抄 ↔ 连招这类整页切换 —— 大方块，一块一块「跳」出去。
//
// 两份代码只差参数，所以留一个函数、一个 Composable，参数分开给：
//   TileSpec.FINE  细格（启动）
//   TileSpec.CHUNKY 大格（切页）
//
// 进度用 lambda 传、不用 Float 参数：参数方式每帧都要重组整棵内容树
// （主界面里有列表、输入框），draw 阶段读 State 只触发重绘 ——
// 一帧多画几百个圆角矩形，别的一个节点都不动。

/** 一块遮罩的规格 */
internal data class TileSpec(
    val size: Dp,
    /** 单块从开始缩到消失占多少个「对角延迟单位」 */
    val span: Float,
    /** 上飘多少格 */
    val lift: Float,
    /** 块与块之间故意留的缝（px）。0 = 无缝，会像一块布在缩 */
    val gap: Float,
    /** 消失途中鼓起来多少。0 = 纯缩，0.08 左右能看出「跳」 */
    val pop: Float,
    /** 圆角系数（相对短边） */
    val round: Float,
    /** 一屏最多画多少块，超了就自动放大格子，保低端机 */
    val maxTiles: Int = 620
) {
    companion object {
        /** 启动：细碎的小格。22dp 在 1080p 上约 16 列 × 32 行 */
        val FINE = TileSpec(
            size = 22.dp,
            span = 4.0f,
            lift = 0.16f,
            gap = 0.5f,
            pop = 0.04f,
            round = 0.18f
        )

        /** 切页：大方块。92dp 大约一屏 4 列 × 8 行，一块块数得清 */
        val CHUNKY = TileSpec(
            size = 92.dp,
            span = 3.4f,
            lift = 0.34f,
            gap = 1.2f,
            pop = 0.10f,
            round = 0.26f
        )

        /**
         * 悬浮小窗：46dp，和攻略窗里那套完全一致。
         *
         * 小窗只有 300 多 dp 宽，用 92dp 的大格只剩三列，看不出「碎」；
         * 用 22dp 的细格又要画两百多块，小窗本来就小，没必要。
         */
        val WINDOW = TileSpec(
            size = 46.dp,
            span = 4.6f,
            lift = 0.28f,
            gap = 0.8f,
            pop = 0.06f,
            round = 0.26f
        )
    }
}

/** 启动整场时长（细格多，走得久一点才看得清） */
internal const val ENTRANCE_MS = 900

/** 切页整场时长（块大、数量少，快一点不挡操作） */
internal const val SWITCH_MS = 560

/**
 * 把一屏切成方格、按对角线一块块让开。
 *
 * @param progress 0 = 全遮，1 = 全露
 * @param color 遮罩色，用页面底色，块才像「画面本身被切开」而不是盖了一张纸
 */
internal fun DrawScope.drawTileCurtain(
    progress: Float,
    color: Color,
    spec: TileSpec,
    skew: Float = 1.12f
) {
    if (progress >= 0.999f) return
    // v2.1.1：全遮这一帧直接铺满。块与块之间留了 gap，逐块画到 progress=0
    // 仍会漏出一层网格缝 —— 换肤正好在这一帧发生，缝隙里就是新旧两套皮肤
    // 各半的画面，会闪一下花。这里用一整块矩形收口，保证「盖住」是真的盖住。
    if (progress <= 0.001f) {
        drawRect(color)
        return
    }
    var cell = spec.size.toPx()
    if (cell <= 1f) return

    // 块太密就自动放大：细格在平板上会算到两三千块，那样每帧都要重建
    // 一条几千段的 Path，低端机直接掉到个位数帧率。
    var cols = ceil(size.width / cell).toInt().coerceAtLeast(1)
    var rows = ceil(size.height / cell).toInt().coerceAtLeast(1)
    while (cols * rows > spec.maxTiles) {
        cell *= 1.12f
        cols = ceil(size.width / cell).toInt().coerceAtLeast(1)
        rows = ceil(size.height / cell).toInt().coerceAtLeast(1)
    }
    cols = cols.coerceIn(1, 48)
    rows = rows.coerceIn(1, 96)

    val w = size.width / cols
    val h = size.height / rows
    val units = (cols - 1) * skew + (rows - 1) + spec.span

    // 所有块合成一条路径、一次填充：逐块画时相邻两块的公共边会被抗锯齿
    // 各画一次，整屏会浮出一层淡网格线。
    val path = Path()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val delay = c * skew + r
            val local = ((progress * units - delay) / spec.span).coerceIn(0f, 1f)
            if (local >= 1f) continue
            // 先慢后快：块先「憋」一下再缩掉，收尾干脆；纯线性会显得拖泥带水
            val eased = local * local
            // 鼓一下：走到一半时略大于 1，看起来是「跳」出去而不是「缩」没了。
            // 起点和终点都乘在 1-eased 上，所以第一帧仍是严丝合缝的整屏。
            val bounce = 1f + spec.pop * sin(local * Math.PI.toFloat())
            val shrink = (1f - eased) * bounce
            val hw = w * 0.5f * shrink
            val hh = h * 0.5f * shrink
            if (hw < 0.6f || hh < 0.6f) continue
            val cx = (c + 0.5f) * w
            val cy = (r + 0.5f) * h - h * spec.lift * eased
            path.addRoundRect(
                RoundRect(
                    left = cx - hw + spec.gap,
                    top = cy - hh + spec.gap,
                    right = cx + hw - spec.gap,
                    bottom = cy + hh - spec.gap,
                    // 圆角跟着缩小量长：进度 0 时是直角，块才铺得满整屏，
                    // 否则四角会漏出下面的内容，初始画面变成一层点阵
                    cornerRadius = CornerRadius(minOf(hw, hh) * spec.round * eased)
                )
            )
        }
    }
    drawPath(path, color)
}

/**
 * 给任意一屏挂上碎块过场。
 *
 * @param progress 进度读取器，在 draw 阶段调用
 */
internal fun Modifier.tileCurtain(
    progress: () -> Float,
    color: Color,
    spec: TileSpec = TileSpec.FINE
): Modifier = drawWithContent {
    drawContent()
    drawTileCurtain(progress(), color, spec)
}

/**
 * 开场：整屏从 [zoomFrom] 长大到 100%，同时碎块一块块让开。
 *
 * 为什么要等两帧：`setContent` 之后第一帧还在合成，动画抢跑会在用户看到画面
 * 之前先走掉一段。等两帧再起跑，保证第一眼就是「满屏碎块」。
 *
 * v2.4.0：
 * - [armed] 给开屏动画用 —— 开屏还盖在上面时保持全遮不起跑，开屏退场后才开始
 *   碎块散开，否则用户在自定义开屏底下把整场入场动画「看完」了，一进来就是静态；
 * - 入场风格选「浮现」时这里直接落终态，把舞台让给 [StaggerColumn]。
 */
// v2.11.1：同 GuideTheme —— remember 只为「只跑一次」，不缓存返回值，见那里的说明
@android.annotation.SuppressLint("RememberReturnType")
@Composable
private fun ensureMotionPrefs() {
    // init 幂等，重复调用只是多一次判空。放在这里是为了「谁先起来谁负责读盘」：
    // 主 App 的 Activity 会显式 init，小窗的 Service 也会，但这条链路万一
    // 从别的 Composition（预览、测试宿主）先跑，也能读到用户存的档位。
    val context = LocalContext.current
    remember { MotionPrefs.init(context) }
}

@Composable
internal fun TileEntrance(
    color: Color,
    durationMs: Int = ENTRANCE_MS,
    spec: TileSpec = TileSpec.FINE,
    zoomFrom: Float = 0.96f,
    armed: Boolean = true,
    content: @Composable () -> Unit
) {
    ensureMotionPrefs()
    val progress: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
    val zoom: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
    // v2.5.1：**不能**把 entrance 当 LaunchedEffect 的 key。
    //
    // 之前这里订阅了 MotionPrefs.entrance，用户在「我的 → 开屏」点一下「碎格 / 浮现」，
    // 整屏遮罩就重新从 0 播一遍：画面被底色盖死几百毫秒，这期间点什么都没反应，
    // 表现就是「点按钮没反应」。风格只在起跑那一刻读一次，之后改设置只影响下次冷启动。
    // 同理关掉动画时不能重播 —— 单独一个 effect 把进度直接顶到终点。
    // drawTileCurtain 见到 progress >= 0.999 就整层不画，等于没有动画。
    LaunchedEffect(Unit) {
        snapshotFlow { MotionPrefs.enabled.value }.collect { on ->
            if (!on) {
                progress.snapTo(1f)
                zoom.snapTo(1f)
            }
        }
    }
    LaunchedEffect(armed) {
        // 开屏还盖着：保持全遮，等它退场
        if (!armed) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        // 起跑时读一次：之后用户再改「开屏」风格不重播
        val style = MotionPrefs.entrance.value
        // 「浮现」风格不铺碎格，直接让内容就位
        val ms = if (style == MotionPrefs.RISE) 0 else MotionPrefs.duration(durationMs)
        // 缩放这条比碎片短，两条一起收尾，观感才是「展开的同时碎开」
        if (ms <= 0) zoom.snapTo(1f)
        else zoom.animateTo(1f, tween(ms * 2 / 3, easing = LinearEasing))
    }
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val style = MotionPrefs.entrance.value
        val ms = if (style == MotionPrefs.RISE) 0 else MotionPrefs.duration(durationMs)
        if (ms <= 0) progress.snapTo(1f)
        else progress.animateTo(1f, tween(ms, easing = LinearEasing))
        // 首屏入场到此结束：之后所有 StaggerColumn 都该按「切页风格」走了
        MotionRuntime.coldStart = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .tileCurtain({ progress.value }, color, spec)
            .graphicsLayer {
                // graphicsLayer 的 block 在绘制阶段跑，在这里读动画值不会引起重组
                val z = zoomFrom + (1f - zoomFrom) * zoom.value
                scaleX = z
                scaleY = z
            }
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// v2.0.9：模块逐个跳出来
// ---------------------------------------------------------------------------
//
// 开场的细格只解决「画面碎开」，用户还要「里面的模块一个一个跳出来」。
// 做法是把页面的纵向容器换成一个自定义 Layout：
//
//   · 每个直接子项（分组标题 / 卡片 / 按钮）都是一个 measurable；
//   · 放置时用 `placeWithLayer` 给每项单独设 alpha / scale / 位移，
//     迟到的项按索引错开 55ms；
//   · 这些值写在 layerBlock 里 —— 它在绘制阶段跑，读时钟只重绘不重组，
//     页面里的列表、输入框一个节点都不会动。
//
// 为什么不用 animateFloatAsState + 每项包一层 Box：那要给页面里十几个模块
// 逐个套 Composable，还得记住索引；这里换掉容器就够了，模块是自动数的。

/** 相邻两个模块的间隔 */
private const val MODULE_STAGGER_MS = 55f

/**
 * 当前嵌在第几层 StaggerColumn 里。
 *
 * 内层再套一层「逐项浮起」没有意义 —— 外层已经按模块数错了，内层只是把同一块
 * 内容再浮一次。更麻烦的是 3.0.8 的崩溃就出在这里：两层各自补滚动，里层拿到
 * 无限高度。所以嵌套到第二层就直接退化成普通 Column。
 */
private val LocalStaggerDepth = compositionLocalOf { 0 }

/** 单项从开始到落定 */
private const val MODULE_ITEM_MS = 380f

/** 整场最长跑到这么久（够最后一项落定） */
private const val MODULE_TOTAL_MS = 2000f

/**
 * 入场时钟的帧数硬上界。60fps 下 240 帧 = 4 秒，比 MODULE_TOTAL_MS 最慢档
 * （0.7 倍速 → 约 2.9 秒）还长一截，正常永远走不到；只在帧时间源异常时兜底。
 */
private const val MAX_ENTRANCE_FRAMES = 240

/**
 * 纵向容器：子项依次从下方浮起、淡入，落定时轻轻过冲一下。
 *
 * [playKey] 变化就重播（切页、重进）。首帧就是「全都还没出来」，
 * 所以打开 App 时看到的是模块一块块冒上来。
 */
@Composable
internal fun StaggerColumn(
    modifier: Modifier = Modifier,
    playKey: Any? = Unit,
    rise: Dp = 22.dp,
    bottomInset: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val depth = LocalStaggerDepth.current

    // v3.0.9：嵌套直接退化。
    //
    // 「我的 → 主题设置」这条路是 StaggerColumn 套 StaggerColumn：
    //   AccountScreen(pane=1) 的 StaggerColumn → ThemeStudioScreen 的 StaggerColumn
    // 两层各搭一套自定义 Layout，等于同一块内容被逐项入场两次（看着发抖），
    // 还白白多一层测量。3.0.8 之所以「在设置里点一下『卡组』就闪退」，就是
    // 两层各自又套了一层 verticalScroll，里面那层拿到无限高。
    //
    // 现在：外层负责动画，内层只排内容。
    if (depth > 0) {
        // 宽度补成填满：原来 PlainStaggerColumn 的 Layout 在约束有界时也是铺满的，
        // 退化之后不能突然变成「按最宽子项收窄」。
        Column(modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalStaggerDepth provides depth + 1) {
                content()
            }
        }
        return
    }

    val inner: @Composable () -> Unit = {
        CompositionLocalProvider(LocalStaggerDepth provides depth + 1) {
            content()
        }
    }

    // v3.0.9：这里以前给「还没改成 StackModule 的页」自己补了一层
    // `Column(verticalScroll)`，想解决「卡组模式下外层不滚、这些页滚不动」。
    // 结果是把页面里本来就有的滚动容器（样式选择、更新说明的 LazyColumn、
    // 连招面板的 verticalScroll）包进了无限高度约束，一进这些页就崩：
    //   IllegalStateException: Vertically scrollable component was measured
    //   with an infinity maximum height constraints
    // 现在滚动权统一交回 MainActivity：它只在「真正走卡组的那两页」让开
    // 外层滚动，其余页仍然由外层 verticalScroll 负责，和普通模式完全一致。
    //
    // v3.1.0：卡组不再由这一个容器接管 —— 它自己就是完整的一页（[StackDeck]），
    // 在 MainActivity 里直接替换掉本函数，所以这里不再有 stackable 分支。
    PlainStaggerColumn(
        modifier = modifier,
        playKey = playKey,
        rise = rise,
        content = inner
    )
}

@Composable
internal fun PlainStaggerColumn(
    modifier: Modifier = Modifier,
    playKey: Any? = Unit,
    rise: Dp = 22.dp,
    content: @Composable () -> Unit
) {
    ensureMotionPrefs()
    // 用 Float 状态而不是 Animatable：这里要的是「从起跑线开始的毫秒数」，
    // 每帧写一次、只让 layerBlock 失效，不参与动画系统的调度。
    // v2.1.0：订阅开关 / 速度，改设置当场生效；关掉时把时钟直接推到终点，
    // 所有模块 e = 1（alpha / scale / 位移全到位），等于静态显示。
    // v2.4.0：首屏听「入场风格」、之后听「切页风格」——碎格模式这层不抢戏，
    // 整屏交给碎块，模块直接落位；开屏动画没退场前也停在起跑线上等着。
    val enabled by MotionPrefs.enabled.collectAsState()
    val gateOpen by SplashRuntime.gate.collectAsState()
    val clock = remember(playKey) { mutableFloatStateOf(0f) }
    // v2.5.1：关掉「界面动画」立刻落到终态，但**不重播**。
    // 之前 enabled 是下面这个 effect 的 key，用户在「我的」点一下开关，整页模块
    // 重新从下方浮起来，那 1 秒里点什么都被这层动画抢了注意力 —— 用户反馈的
    // 「点按钮没反应」有一半是这个。
    LaunchedEffect(enabled) {
        if (!enabled) clock.floatValue = MODULE_TOTAL_MS
    }
    LaunchedEffect(playKey, gateOpen) {
        if (!enabled) {
            clock.floatValue = MODULE_TOTAL_MS
            return@LaunchedEffect
        }
        if (!gateOpen) {
            clock.floatValue = 0f
            return@LaunchedEffect
        }
        // 风格只在起跑时读一次：中途改设置不该把正在浮的模块重播一遍
        val style = if (MotionRuntime.coldStart) {
            MotionPrefs.entrance.value
        } else {
            MotionPrefs.switchStyle.value
        }
        if (style == MotionPrefs.TILES) {
            clock.floatValue = MODULE_TOTAL_MS
            return@LaunchedEffect
        }
        // 速度同理，只在起跑时读一次。v2.5.3：下限兜一下 —— factor 是 0.7~2.3，
        // 但万一以后加了「自定义速度」让用户能拖到 0，下面的循环就再也退不出来了。
        val factor = MotionPrefs.factor().coerceAtLeast(0.05f)
        clock.floatValue = 0f
        val start = withFrameNanos { it }
        var frames = 0
        while (true) {
            val now = withFrameNanos { it }
            val ms = (now - start) / 1_000_000f * factor
            // 只认 0~总长，负数和超长的写入都夹掉：placeWithLayer 里读到脏值
            // 会把模块算成「永远没落定」，看起来就是页面一片空白
            clock.floatValue = ms.coerceIn(0f, MODULE_TOTAL_MS)
            frames++
            if (ms > MODULE_TOTAL_MS) break
            // v2.5.3 硬上界：正常 60fps 下这段最多跑 120 帧左右。
            // 万一时间源给出非单调值（个别定制 ROM 的 withFrameNanos 会），
            // ms 恒 ≤ 0，上面那个条件永远不成立，循环会一直跑、每帧写一次 clock ——
            // 主线程被占死，表现就是「打开 App 就卡住」。这条兜住它。
            if (frames > MAX_ENTRANCE_FRAMES) break
        }
    }
    Layout(
        content = content,
        // v2.5.1：手指一碰就立刻落定。入场那 0.4 秒里模块还在往上浮，
        // 用户这时候点按钮，看到的是「东西在动、界面上什么都没变」，
        // 很容易当成点击失灵。这里在 Initial 阶段（子控件之前）把时钟推到终点，
        // 只写状态、不消费事件 —— 这一下点击照样会落到真正的按钮上。
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    if (clock.floatValue < MODULE_TOTAL_MS) {
                        clock.floatValue = MODULE_TOTAL_MS
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = if (constraints.maxWidth == Constraints.Infinity) {
            placeables.maxOfOrNull { it.width } ?: 0
        } else {
            constraints.maxWidth
        }
        val height = placeables.sumOf { it.height }
        val risePx = rise.toPx()
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                placeable.placeWithLayer(0, y) {
                    val elapsed = clock.floatValue - index * MODULE_STAGGER_MS
                    val x = (elapsed / MODULE_ITEM_MS).coerceIn(0f, 1f)
                    // 先快后慢；末尾再补一点点过冲，像轻轻落到桌面上
                    val e = 1f - (1f - x) * (1f - x)
                    val bump = 1f + 0.05f * sin(x * Math.PI.toFloat())
                    alpha = e
                    val s = 0.965f + 0.035f * e * bump
                    scaleX = s
                    scaleY = s
                    translationY = (1f - e) * risePx
                }
                y += placeable.height
            }
        }
    }
}
