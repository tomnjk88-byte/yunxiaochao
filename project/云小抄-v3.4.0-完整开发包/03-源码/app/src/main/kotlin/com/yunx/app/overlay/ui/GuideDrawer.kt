/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件。v1.9.12 用「侧滑抽屉」替掉底部 7 个挤在一起的 Tab：
 *   - 页面切换收进左侧抽屉，主界面只留标题 + 两个小按钮，底部彻底空出来；
 *   - 从屏幕左边缘往右滑，面板跟手拉出（不是点一下才弹），松手按位移 / 速度吸附；
 *   - 抽屉里是图标 + 文字，选中项高亮，底部固定「关于」；
 *   - 面板打开时点遮罩关闭、向左甩也关闭。
 * v1.9.14 修「割裂感」：
 *   - 以前面板底色写死成近黑（#0B0B14），亮色页面一拉开就是一黑一白两块硬拼；
 *     现在面板颜色来自 [UiTokens.panelTop] / [panelBottom]，跟当前风格、当前日夜同一族；
 *   - 风格四 / 五 的面板直接是「真玻璃」：和背景同一张壁纸、同一套坐标，模糊后压在
 *     面板里，所以拉开时像是同一块玻璃被推开，而不是贴上去一块黑板；
 *   - 遮罩也跟着日夜走（浅色 28%、深色 46%），并给面板加了柔和投影和右缘高光。
 * v1.9.17 左右两边都能拉：
 *   - 原先只有左边缘 22dp 能起手，右边完全没有入口；现在左右各留一条 28dp 热区，
 *     左边缘往右拖、右边缘往左拖都能把同一块面板拉出来，面板从哪边进就从哪边出
 *     （圆角、投影、内边距全部镜像），不用先想「我该从哪边滑」；
 *   - 顺手治了「滑不动」的真凶：Android 10 起，屏幕左右边缘是系统返回手势的地盘，
 *     应用在边缘收到的触摸会被系统直接吃掉。这里用 setSystemGestureExclusionRects
 *     把两侧中间 200dp 高的一条带子从返回手势里豁免出来（系统对豁免高度有上限，
 *     所以只申请中间一段），于是「从边缘往里滑」才真的能滑到抽屉，而不是每次退回上一页。
 */

package com.yunx.app.overlay.ui

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 抽屉里的一项：只有图标和文字，不加副标题 —— 副标题正是「乱」的来源 */
data class DrawerItem(
    val label: String,
    val icon: ImageVector,
    /** 分组名；相邻项分组不同时插一个小标题。只影响显示，不影响 index */
    val section: String = "",
    /**
     * v2.8.0：二级项。非空时，点主项是展开 / 收起，不直接切页 ——
     * 避免「想展开看看有哪些子页」的那一下反而把页面切走。
     */
    val children: List<DrawerChild> = emptyList(),
    /**
     * v3.0.2：点它切到哪一页（[com.yunx.app.overlay.HomeTab] 的序号）。
     *
     * 以前选中和跳页都靠「列表下标」，一合并项就整体前移一位：删掉「下载」后
     * 点「权限」跳的是「下载」，点「我的」跳的是「协议」—— 看着就是点了这个、
     * 蹦出那个。现在目标页写死在数据里，增删项只影响显示顺序，绝不影响跳转。
     */
    val target: Int = -1,
    /**
     * v3.0.2：点主项时除了展开，要不要顺手跳进去。
     *
     * 其它项保持「只展开」，是因为「小抄」下面四页差别很大，展开看一眼再选更稳；
     * 「解析下载」只有一个入口含义，点一下就该到解析页，不该让人再点第二次。
     */
    val jumpOnTap: Boolean = false
) {
    /** 目标页；没显式写就退回列表下标（老调用点行为不变） */
    fun tabIndex(fallback: Int): Int = if (target >= 0) target else fallback
}

/** 抽屉二级项。[key] 由调用方解释，这里只负责原样上报 */
data class DrawerChild(val label: String, val key: String)

private val PANEL_WIDTH = 300.dp

/**
 * 边缘起手热区宽度。
 *
 * v2.5.1：28dp → 12dp。**不能大于页面左右留白（18dp）** —— 这层热区是透明 Box，
 * 盖在内容上面、参与命中测试：它一旦命中，底下同位置的卡片 / 按钮就再也收不到
 * 点击了。28dp 时页面左侧 18~28dp 那条竖边被它整条吃掉，用户点卡片最左边的
 * 图标经常「没反应」，往右挪一点又好了，很难自己发现规律。
 *
 * 12dp 落在 18dp 留白之内，一点都不碰内容；系统手势豁免仍是 32dp，从边上起手
 * 照样能拉出抽屉。
 */
/**
 * 二级项前面的指示点 / 箭头。
 *
 * v2.10.1：箭头原来给到 16dp，比 13sp 的字还高一头，压在圆点位置上显得又笨又偏，
 * 用户原话「你自己看这个小箭头丑不丑」。现在缩到 13dp —— 跟字号同高，
 * 并且严格以圆点圆心为中点，垂直方向再往上让 0.5dp 补掉汉字的视觉重心偏差。
 */
private val CHILD_DOT_SIZE = 4.dp
private val CHILD_DOT_START = 43.dp
private val ARROW_SIZE = 14.dp
private val ARROW_CENTER_X = CHILD_DOT_START + (CHILD_DOT_SIZE - ARROW_SIZE) / 2
private val ARROW_STROKE = 1.5.dp

private val EDGE_HIT_WIDTH = 12.dp

/** 面板圆角只加在「朝里」的那条边上：左边拉出圆右边，右边拉出圆左边 */
private fun panelShape(side: DrawerSide) = if (side == DrawerSide.LEFT) {
    RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
} else {
    RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
}

/**
 * 侧滑抽屉宿主：把整页包进来即可。
 *
 * 手势分工（v1.9.17 起两边对称）：
 *  - 左边缘 28dp 内向右拖 / 右边缘 28dp 内向左拖 → 拉出面板（跟手，从哪边拉就从哪边进）；
 *  - 面板上朝外甩 → 收起；点遮罩 → 收起；
 *  - 其它位置的水平拖动交给页面自己的翻页手势，互不干扰。
 */
@Composable
fun GuideDrawerHost(
    open: Boolean,
    openSide: DrawerSide = DrawerSide.LEFT,
    onOpenChange: (Boolean) -> Unit,
    items: List<DrawerItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUsage: () -> Unit,
    versionLabel: String,
    /** v2.8.0：当前选中的二级项 key（与 [selected] 共同决定高亮） */
    childSelected: String? = null,
    onSelectChild: (Int, String) -> Unit = { _, _ -> },
    /** v3.2.0：传了就走「圆形扩散」换主题；不传就退回原来的硬切 */
    onToggleTheme: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val panelPx = with(density) { PANEL_WIDTH.toPx() }
    val offset = remember { Animatable(0f) }
    // 面板现在停在哪一边；完全收起时从另一边起手会立刻换边（此时 offset 还是 0，看不出跳变）
    var side by remember { mutableStateOf(DrawerSide.LEFT) }

    // 外部（顶栏菜单按钮 / 内容区左右滑）要开要关，都从这里走
    LaunchedEffect(open, openSide) {
        if (open) side = openSide
        offset.animateTo(if (open) panelPx else 0f, tween(280))
    }

    /** 把原始横向拖动量换算成「拉出距离」的增量：左抽屉往右是拉开，右抽屉往左才是拉开 */
    fun dragBy(rawDelta: Float, from: DrawerSide) {
        if (offset.value < 1f) side = from
        val delta = drawerPullDelta(from, rawDelta)
        scope.launch {
            offset.snapTo((offset.value + delta).coerceIn(0f, panelPx))
        }
    }

    // 松手吸附：先看甩的速度（统一成「拉开为正」），再看拉出的比例
    fun settle(rawVelocity: Float) {
        val pull = rawVelocity * side.pullSign
        val target = drawerSettleTarget(pull, offset.value, panelPx)
        onOpenChange(target > panelPx * 0.5f)
        scope.launch { offset.animateTo(target, tween(280)) }
    }

    /** 直接收起（点遮罩用）：不管停在哪一边，都朝外滑回去 */
    fun closeNow() {
        onOpenChange(false)
        scope.launch { offset.animateTo(0f, tween(280)) }
    }

    // 面板自身的拖动：方向随当前所在边翻转
    val panelDrag = rememberDraggableState { d -> dragBy(d, side) }
    val leftEdgeDrag = rememberDraggableState { d -> dragBy(d, DrawerSide.LEFT) }
    val rightEdgeDrag = rememberDraggableState { d -> dragBy(d, DrawerSide.RIGHT) }

    val progress = (offset.value / panelPx).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        content()

        // 把两侧中间一段从系统返回手势里豁免出来，否则边缘滑动会被系统当成「返回」
        DrawerEdgeExclusion()

        // 遮罩：颜色跟着日夜走，跟手渐变；点一下收起
        val scrim = LocalUiTokens.current.scrim
        if (progress > 0.002f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim.copy(alpha = scrim.alpha * progress))
                    .pointerInput(Unit) {
                        detectTapGestures { closeNow() }
                    }
            )
        }

        DrawerPanel(
            side = side,
            items = items,
            selected = selected,
            onSelect = onSelect,
            onOpenAbout = onOpenAbout,
            onOpenUsage = onOpenUsage,
            versionLabel = versionLabel,
            childSelected = childSelected,
            onSelectChild = onSelectChild,
            onToggleTheme = onToggleTheme,
            modifier = Modifier
                .align(if (side == DrawerSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .width(PANEL_WIDTH)
                .fillMaxHeight()
                .offset {
                    val x = if (side == DrawerSide.LEFT) {
                        -panelPx + offset.value
                    } else {
                        panelPx - offset.value
                    }
                    IntOffset(x.roundToInt(), 0)
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = panelDrag,
                    onDragStopped = { v -> settle(v) }
                )
        )

        // 边缘热区：只有面板完全收起时才生效；顶部让开 96dp，免得挡住顶栏左右两颗按钮
        if (progress < 0.01f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(EDGE_HIT_WIDTH)
                    .fillMaxHeight()
                    .padding(top = 96.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = leftEdgeDrag,
                        onDragStopped = { v -> settle(v) }
                    )
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(EDGE_HIT_WIDTH)
                    .fillMaxHeight()
                    .padding(top = 96.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rightEdgeDrag,
                        onDragStopped = { v -> settle(v) }
                    )
            )
        }
    }
}

/**
 * 两侧边缘手势豁免。
 *
 * Android 10 起，屏幕左右边缘属于系统返回手势；应用在边缘收到的 ACTION_DOWN 会被系统
 * 直接截走，抽屉看着有热区、实际滑不出来。`systemGestureExclusionRects` 允许应用申请
 * 一小块区域不被当成返回手势，但系统对高度有上限（约 200dp / 边），所以这里只申请
 * 垂直居中的一段 —— 正好是人最常滑的位置。
 */
@Composable
private fun DrawerEdgeExclusion() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val view = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(view, density) {
        val apply = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                view.width > 0 && view.height > 0
            ) {
                val w = with(density) { 32.dp.toPx() }.toInt()
                val band = with(density) { 200.dp.toPx() }.toInt()
                // v2.1.2：系统对每条屏幕边最多只接受 200dp 的手势豁免，必须挑一段。
                // 原来放「垂直居中」，用户从顶栏下方起手（最顺手的位置）不在带内，
                // move 事件被系统返回手势抢走 —— 表现就是「按钮能开，边上滑不开」。
                // 挪到状态栏以下：覆盖顶栏和首屏内容区，正是手指最常起手的高度。
                val top = with(density) { 48.dp.toPx() }.toInt()
                val bottom = (top + band).coerceAtMost(view.height)
                view.systemGestureExclusionRects = listOf(
                    Rect(0, top, w, bottom),
                    Rect(view.width - w, top, view.width, bottom)
                )
            }
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        view.addOnLayoutChangeListener(listener)
        view.post(apply)
        onDispose {
            view.removeOnLayoutChangeListener(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.systemGestureExclusionRects = emptyList()
            }
        }
    }
}

/**
 * 抽屉面板。
 *
 * 结构：玻璃底（只有风格四 / 五，非「流畅」档才跑）→ 同色系压色 → 顶部受光 →
 * 内容。压色不是纯色块，而是「panelTop → panelBottom」的水平渐变，从左到右略变深，
 * 面板自己就有方向感；朝外的边缘再压一条 1dp 高光，边界干净但不生硬。
 */
@Composable
private fun DrawerPanel(
    side: DrawerSide,
    items: List<DrawerItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUsage: () -> Unit,
    versionLabel: String,
    childSelected: String?,
    onSelectChild: (Int, String) -> Unit,
    onToggleTheme: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    val context = LocalContext.current
    val shape = panelShape(side)
    // 默认展开「小抄」那组（第 0 项）；其余组点开才展
    var expandedIndex by remember { mutableIntStateOf(0) }

    Box(
        modifier
            .shadow(
                elevation = 26.dp,
                shape = shape,
                clip = false,
                // v1.9.19：深色下 60% 黑会把面板背后也压灰，降到 30%
                ambientColor = if (t.dark) Color(0x4D000000) else Color(0x40000000),
                spotColor = if (t.dark) Color(0x4D2F7BFF) else Color(0x332F7BFF)
            )
            .clip(shape)
    ) {
        // ① 压色：只压一层半透明的同族颜色 —— 拉开时背后的页面还看得见，
        //    这是「透明」的关键；以前在这里重绘一张不透明壁纸，等于贴了块黑板。
        //    渐变方向跟着边翻转：从哪边拉开，外侧就深一点。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        if (side == DrawerSide.LEFT) listOf(t.panelTop, t.panelBottom)
                        else listOf(t.panelBottom, t.panelTop)
                    )
                )
        )

        // ③ 顶部受光：一条很淡的白，深色 12% / 浅色 55%，让面板上沿「有厚度」
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (t.dark) 0.12f else 0.55f),
                        0.18f to Color.Transparent,
                        0.9f to Color.Transparent,
                        1f to Color.White.copy(alpha = if (t.dark) 0.03f else 0.22f)
                    )
                )
        )

        // ④ 朝外那条边的 1dp 高光
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (t.dark) 0.34f else 0.9f),
                            if (t.dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
                        )
                    ),
                    shape = shape
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    start = if (side == DrawerSide.LEFT) 16.dp else 14.dp,
                    end = if (side == DrawerSide.LEFT) 14.dp else 16.dp,
                    top = 18.dp,
                    bottom = 14.dp
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(t.accent, t.accent2))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "抄",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    // v3.4.6：应用名称可改（主题设置 → 图标文字 → 应用名称）
                    Text(
                        com.yunx.app.overlay.data.OverlayPrefs(context).appName(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(versionLabel, fontSize = 9.sp, color = t.textMuted)
                }
                Spacer(Modifier.weight(1f))
                // 一键白天 / 黑夜：图标画的是「点下去会变成什么」，深色时给太阳
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (t.dark) Color.White.copy(alpha = 0.09f)
                            else Color.Black.copy(alpha = 0.05f)
                        )
                        .clickable {
                            if (onToggleTheme != null) {
                                // 首页那颗按钮和这颗走同一套扫屏，两个入口一个观感
                                onToggleTheme()
                            } else {
                                GuideStyleStore.selectMode(
                                    context,
                                    if (t.dark) UiMode.LIGHT else UiMode.DARK
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (t.dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = if (t.dark) "切到白天模式" else "切到黑夜模式",
                        modifier = Modifier.size(18.dp),
                        tint = t.textSub
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 条目区可滚：字号调大 / 小屏时不至于把「关于」挤出去
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEachIndexed { index, item ->
                    // v2.0.9：分组小标题（「内容」「设置」）不再画。它跟页面里的
                    // 分组标题重名，抽屉一拉开就是「内容 / 内容」两行，反而更乱。
                    // v3.0.2：高亮按「目标页」比，不按列表下标 —— 合并「解析 / 下载」
                    // 之后「权限」的下标从 5 变成 4，用下标比会把「解析下载」点亮。
                    val active = item.tabIndex(index) == selected
                    val expandable = item.children.isNotEmpty()
                    val expanded = expandable && expandedIndex == index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (active) t.accent.copy(alpha = if (t.dark) 0.18f else 0.13f)
                                else Color.Transparent
                            )
                            .clickable {
                                // 有子项的主按钮负责展开 / 收起；标了 jumpOnTap 的一并跳页
                                if (expandable) {
                                    expandedIndex = if (expanded) -1 else index
                                    if (item.jumpOnTap) onSelect(item.tabIndex(index))
                                } else {
                                    onSelect(item.tabIndex(index))
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = if (active) t.accent else t.textSub
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            item.label,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) t.text else t.textSub,
                            modifier = Modifier.weight(1f)
                        )
                        if (expandable) {
                            Icon(
                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (expanded) "收起${item.label}" else "展开${item.label}",
                                modifier = Modifier.size(17.dp),
                                tint = if (active) t.accent else t.textMuted
                            )
                        } else if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = t.accent
                            )
                        }
                    }

                    // 二级按钮：缩进 + 前置指示点。
                    //
                    // v2.9.1：不再给子项铺整条高亮 —— 父项本来就有一条圆角高亮，
                    // 子项再来一条，两条紫条上下叠着，用户原话「像两条大便」。
                    // 现在子项一律透明，只保留前面的小圆点；选中的那一项，圆点换成
                    // 一个朝右的箭头，并且在换项时从上一行滑到这一行。
                    if (expanded) {
                        val density = LocalDensity.current
                        val activeChild = if (index == selected) {
                            item.children.indexOfFirst { it.key == childSelected }
                        } else {
                            -1
                        }
                        // 每行高度一致，量一次就够；用它把「第几行」换算成像素位置
                        var rowHeightPx by remember(item.label) { mutableFloatStateOf(0f) }
                        val rowHeightDp = with(density) { rowHeightPx.toDp() }
                        val arrowTarget = if (activeChild >= 0 && rowHeightPx > 0f) {
                            // 行高一半 = 这一行的中轴；箭头自己再回半个身位才居中。
                            // 再往上让 0.5dp：汉字方块在 Text 盒里本身偏下，不让这一点
                            // 箭头永远比字低半根头发，看着就是没对齐。
                            rowHeightDp * activeChild + (rowHeightDp - ARROW_SIZE) / 2 - 0.5.dp
                        } else {
                            0.dp
                        }
                        val arrowTop by animateDpAsState(
                            targetValue = arrowTarget,
                            animationSpec = tween(durationMillis = if (activeChild >= 0) 180 else 0),
                            label = "drawerChildArrow"
                        )
                        Box(Modifier.fillMaxWidth()) {
                            Column {
                                item.children.forEachIndexed { childIndex, child ->
                                    val childActive = item.tabIndex(index) == selected && childSelected == child.key
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onSelectChild(item.tabIndex(index), child.key) }
                                            // ★ v3.0.1：测量必须在 padding **外面**。
                                            //   原来 onGloballyPositioned 挂在 padding 里层，
                                            //   量到的是文字内容高度（约 19dp），不含上下各 9dp；
                                            //   箭头按「内容高 × 行号」算位置，越往下飘得越狠，
                                            //   看着就是「小箭头没对准文字」。挪到外层才是整行行高。
                                            .then(
                                                // 只量第一行：所有子项同一套 padding / 字号
                                                if (childIndex == 0) {
                                                    Modifier.onGloballyPositioned {
                                                        rowHeightPx = it.size.height.toFloat()
                                                    }
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .padding(start = CHILD_DOT_START, end = 12.dp, top = 9.dp, bottom = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 选中项的点让位给箭头（箭头是同层浮动的那个）
                                        Box(
                                            Modifier
                                                .size(CHILD_DOT_SIZE)
                                                .clip(CircleShape)
                                                .background(
                                                    if (childActive) Color.Transparent else t.textMuted
                                                )
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            child.label,
                                            fontSize = 13.sp,
                                            fontWeight = if (childActive) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (childActive) t.text else t.textSub,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (childActive) {
                                            Icon(
                                                Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = t.accent
                                            )
                                        }
                                    }
                                }
                            }
                            // 箭头：跟着选中行上下滑。x 由 ARROW_CENTER_X 算出来，
                            // 保证它和 4dp 圆点共用同一个圆心 —— 不是靠眼睛估的 37dp。
                            if (activeChild >= 0 && rowHeightPx > 0f) {
                                DrawerArrow(
                                    color = t.accent,
                                    modifier = Modifier
                                        .offset(x = ARROW_CENTER_X, y = arrowTop)
                                        .size(ARROW_SIZE)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            // v3.4.6：原来这里单独占两行「使用说明」「关于 / 开源协议」，
            // 和上面的「我的 → 协议与关于」完全重复，还会跟底部 Dock 叠在一起。
            // 现在合并成抽屉里的一个入口（设置分组里的「关于与说明」），这里只留底部留白。
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * v2.11.0：抽屉二级项前面的指示箭头。
 *
 * 不用 Material 的 ArrowForward：那是个实心粗箭头，缩到跟文字同高以后
 * 笔画反而糊成一坨，用户连着两次说它丑。这里按线画 —— 一根圆头箭杆加两条
 * 同样的圆头斜线，粗细固定 1.5dp，缩到多小都是干净的。
 */
@Composable
private fun DrawerArrow(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = ARROW_STROKE.toPx()
        drawLine(
            color = color,
            start = Offset(w * 0.14f, h * 0.5f),
            end = Offset(w * 0.84f, h * 0.5f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(w * 0.56f, h * 0.22f)
            lineTo(w * 0.86f, h * 0.5f)
            lineTo(w * 0.56f, h * 0.78f)
        }
        drawPath(
            path = head,
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
