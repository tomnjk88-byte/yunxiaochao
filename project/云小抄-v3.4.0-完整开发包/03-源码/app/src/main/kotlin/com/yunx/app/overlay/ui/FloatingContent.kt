/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：整窗版式按参考样式重做 —— 圆角浅色卡片、渐变顶栏、路径面包屑、
 * 两列缩略图网格、可收起的「透 / 窗」双滑杆底栏；保留多级目录、大图预览、
 * 双击铺满、单击隐藏控件、缩成小球与自动连招页签。
 */

package com.yunx.app.overlay.ui

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Size
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.overlay.data.GuideSource
import com.yunx.app.overlay.data.ImageSource
import com.yunx.app.overlay.data.GuideRepo
import com.yunx.app.overlay.data.MotionPrefs
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.data.PanFolder
import com.yunx.app.overlay.data.PanImage
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** 把 ViewModel / Service 的回调打成一包，避免 FloatingContent 出现十几个 lambda 参数 */
data class OverlayActions(
    val onClose: () -> Unit,
    val onRefresh: () -> Unit,
    val onSelectFolder: (PanFolder) -> Unit,
    val onOpenFolder: (PanFolder) -> Unit,
    val onGoUp: () -> Unit,
    val onOpenPreview: (Int) -> Unit,
    val onClosePreview: () -> Unit,
    val onLogin: () -> Unit,
    val onRetry: () -> Unit,
    val onCollapse: () -> Unit,
    val onMaximize: (Boolean) -> Unit,
    /** 卡住时一键重建窗口（通知栏 / 主页同款入口） */
    val onReset: () -> Unit,
    /** v2.0.7：重播一次入场动画（顶栏版本号就是按钮） */
    val onReplayReveal: () -> Unit,
    val onPrefetch: (PanImage) -> Unit,
    val resolveSource: suspend (PanImage, Boolean) -> ImageSource?,
    /** v1.9.6：同步拿缩略图地址，首帧就能发图片请求，不用每个格子起一个协程 */
    val thumbSource: (PanImage) -> ImageSource?
)

/**
 * 悬浮窗根布局：渐变顶栏 + 分类条 + 面包屑 + 内容区 + 双滑杆底栏。
 *
 * @param dragTouchListener 挂在顶栏「拖动」按钮与标题区上的 OnTouchListener（Service 提供）
 * @param resizeTouchListener 挂在右下角拉伸手柄上的 OnTouchListener（Service 提供）
 * @param sizeScale 相对初始宽度（屏宽 0.8）的缩放倍率
 */
@Composable
fun FloatingContent(
    state: OverlayUiState,
    actions: OverlayActions,
    dragTouchListener: View.OnTouchListener,
    resizeTouchListener: View.OnTouchListener,
    alpha: Float,
    onAlphaChange: (Float) -> Unit,
    sizeScale: Float,
    onSizeChange: (Float) -> Unit
) {
    // 预览态：chrome 控制控件显隐，focus 控制「铺满 + 窗口最大化」
    var chrome by remember(state.previewVisible) { mutableStateOf(true) }
    var focus by remember(state.previewVisible) { mutableStateOf(false) }
    var footerExpanded by remember { mutableStateOf(true) }

    // ------------------------------------------------------------------
    // v2.0.7：入场动画的状态全部放在这一层。
    //
    // v2.0.6 把它们写在内容区里面，结果一次都没生效：点开目录时 ViewModel 先把
    // folders / images 清空，内容区被 LoadingBox 顶掉，remember 连同 lastNavKey
    // 一起销毁；数据回来时内容区重建，lastNavKey 又是 null，于是「换来源」判定
    // 永远成立，碎片掀开那条分支的开关永远是 false —— 整整一套代码是死的。
    //
    // 挪到 FloatingContent 根上：它活着的时间比 LoadingBox 长，判定才成立。
    // ------------------------------------------------------------------
    val navKey = "${state.source.name}|${state.currentFolder?.id.orEmpty()}|${state.revealTick}"
    // v2.0.9：只留一种入场 —— 碎片掀开。换来源、进退目录、刷新、手动重播，
    // 全部走这一套。之前按「换界面 / 换画面」分两种，实际用起来两种都像
    // 「东西在动」，用户分不出差别，反而多一套状态、多一处会坏的地方。
    // 内容真的出现才开始掀。否则加载圈那两秒就把动画跑完了，
    // 用户看到的还是「转圈 → 唰一下全在」
    val tiles = rememberTileReveal(navKey = navKey, contentReady = !state.isEmpty)

    val hideChrome = state.previewVisible && (!chrome || focus)

    // 预览被别处关掉（切分类 / 刷新）时，确保窗口从「双击全屏」退回原尺寸
    LaunchedEffect(state.previewVisible) {
        if (!state.previewVisible) actions.onMaximize(false)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = if (focus) RectangleShape else RoundedCornerShape(12.dp),
        color = if (focus) Color.Black else OverlayColors.Card,
        border = if (focus) null else BorderStroke(1.dp, OverlayColors.Divider)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
            if (!hideChrome) {
                HeaderBar(
                    state = state,
                    actions = actions,
                    dragTouchListener = dragTouchListener
                )
                if (!state.previewVisible) {
                    CategoryRow(state, actions)
                    BreadcrumbRow(state)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    state.previewVisible -> ImagePager(
                        state = state,
                        actions = actions,
                        focus = focus,
                        showOverlay = !hideChrome,
                        onTap = {
                            if (focus) {
                                focus = false
                                chrome = true
                                actions.onMaximize(false)
                            } else {
                                chrome = !chrome
                            }
                        },
                        onDoubleTap = {
                            val next = !focus
                            focus = next
                            chrome = true
                            actions.onMaximize(next)
                        }
                    )

                    else -> GuideContent(
                        state = state,
                        actions = actions,
                        tiles = tiles
                    )
                }

                // 右下角拉伸手柄：按住拖动，像电脑窗口拖角一样改宽高
                if (!hideChrome) {
                    ResizeGrip(
                        listener = resizeTouchListener,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                    )
                }
            }

            if (!hideChrome && !state.previewVisible) {
                FooterControls(
                    alpha = alpha,
                    onAlphaChange = onAlphaChange,
                    sizeScale = sizeScale,
                    onSizeChange = onSizeChange,
                    expanded = footerExpanded,
                    onToggleExpanded = { footerExpanded = !footerExpanded },
                    actions = actions
                )
            }

            }

            // 双击铺满后浮在右上角的极小退出叉（单击图片也可退出）
            if (state.previewVisible && hideChrome && chrome) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(OverlayColors.ImageScrim)
                        .clickable {
                            focus = false
                            chrome = true
                            actions.onMaximize(false)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "退出铺满",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏：渐变 hero、拖动把手、标题、图标按钮
// ---------------------------------------------------------------------------

@Composable
private fun HeaderBar(
    state: OverlayUiState,
    actions: OverlayActions,
    dragTouchListener: View.OnTouchListener
) {
    val scheme = currentScheme()
    val ctx = LocalContext.current
    // 顶栏水印用真实安装包的 versionName，不是写死的常量 —— 装错包时才看得出来
    val overlayVersion = remember(ctx) {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty().substringBefore('-').ifBlank { "?" }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Brush.verticalGradient(listOf(scheme.heroStart, scheme.heroEnd)))
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoveHandle(dragTouchListener)
        Spacer(Modifier.width(3.dp))
        // 标题区也能拖：顶栏除图标外的整片区域都是移动热区，不必精准戳左边的「拖动」按钮
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "云小抄",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // v2.0.7：把版本号印在顶栏上，顺手当「重播动画」按钮。
                // 上一版最大的沟通成本是「你到底装没装上新包」—— 现在一眼就能看见；
                // 点一下立刻重播整屏碎片，不用切目录去碰运气。
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "v$overlayVersion",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { actions.onReplayReveal() }
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> View(ctx).apply { setOnTouchListener(dragTouchListener) } }
            )
        }
        if (state.previewVisible) {
            HeaderIcon(Icons.Rounded.ArrowBack, "返回网格") { actions.onClosePreview() }
        } else {
            if (state.canGoUp) {
                HeaderIcon(Icons.Rounded.ArrowBack, "返回上级") { actions.onGoUp() }
            }
            HeaderIcon(Icons.Rounded.Refresh, "刷新") { actions.onRefresh() }
            // v1.8.3：攻略窗内不再放连招入口。两个按钮在主页各自独立，互不关联。
        }
        HeaderIcon(Icons.Rounded.Minimize, "缩成小抄球") { actions.onCollapse() }
        HeaderIcon(Icons.Rounded.Close, "关闭悬浮窗") { actions.onClose() }
    }
}

@Composable
private fun HeaderIcon(image: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(29.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = image,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.94f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 明确的「拖动」按钮：底层画图标 + 文字，顶层透明 View 接 OnTouchListener 改 params.x/y */
@Composable
internal fun MoveHandle(dragTouchListener: View.OnTouchListener) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .width(54.dp)
            .height(28.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.26f))
            .border(1.dp, Color.White.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.DragIndicator,
                contentDescription = "拖动悬浮窗",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text("拖动", color = Color.White, fontSize = 9.sp, maxLines = 1)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> View(ctx).apply { setOnTouchListener(dragTouchListener) } }
        )
    }
}

/**
 * 右下角拉伸手柄：三条斜线的电脑窗口角标，按住拖动改窗口宽高。
 * 放在内容区右下而不是窗口最角落，是为了不挡住底栏的重置 / 收起 / 缩球按钮。
 */
@Composable
internal fun ResizeGrip(
    listener: View.OnTouchListener,
    modifier: Modifier = Modifier
) {
    val tint = currentScheme().primary
    val shape = RoundedCornerShape(topStart = 10.dp, bottomEnd = 9.dp)
    Box(
        modifier
            .size(26.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.92f))
            .border(1.dp, OverlayColors.Divider, shape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(15.dp)) {
            val lineColor = tint.copy(alpha = 0.8f)
            val step = 4.6.dp.toPx()
            val stroke = 1.5.dp.toPx()
            for (i in 1..3) {
                val d = i * step
                drawLine(
                    color = lineColor,
                    start = Offset(size.width - d, size.height),
                    end = Offset(size.width, size.height - d),
                    strokeWidth = stroke
                )
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> View(ctx).apply { setOnTouchListener(listener) } }
        )
    }
}

// ---------------------------------------------------------------------------
// 分类条 / 面包屑
// ---------------------------------------------------------------------------

@Composable
private fun CategoryRow(state: OverlayUiState, actions: OverlayActions) {
    val scheme = currentScheme()
    if (state.categories.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(OverlayColors.Card)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        lazyRowItems(state.categories, key = { it.id }) { folder ->
            val selected = state.path.firstOrNull()?.id == folder.id
            Text(
                text = folder.name,
                fontSize = 9.sp,
                maxLines = 1,
                color = if (selected) Color.White else OverlayColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) scheme.primary else OverlayColors.Page)
                    .clickable { actions.onSelectFolder(folder) }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun BreadcrumbRow(state: OverlayUiState) {
    val scheme = currentScheme()
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(OverlayColors.Page)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.path.forEachIndexed { index, folder ->
            if (index > 0) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = OverlayColors.TextMuted,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = folder.name,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (index == state.path.lastIndex) scheme.primary else OverlayColors.TextMuted,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "${state.sourceLabel} · ${state.folders.size} 夹 · ${state.images.size} 图",
            fontSize = 9.sp,
            color = OverlayColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// 内容区：两列网格 / 空态 / 登录 / 错误
// ---------------------------------------------------------------------------

@Composable
private fun GuideContent(
    state: OverlayUiState,
    actions: OverlayActions,
    tiles: Animatable<Float, AnimationVector1D>
) {
    val context = LocalContext.current
    // v1.9.8：开了「小抄本地化」就真下载到目录；没开才退回 v1.9.7 的缓存预取
    val folderId = state.currentFolder?.id
    LaunchedEffect(folderId, state.images, state.source) {
        if (state.images.isEmpty()) return@LaunchedEffect
        // v2.0.2：链接小抄只在线看，不下载。它连的是别人的分享，
        // 把图存进自己的本地库会让索引里出现一批永远对不上号的条目；
        // 真要存下来，用户应该切到个人小抄（转存后再下）。
        if (state.source == GuideSource.LINK) return@LaunchedEffect
        val prefs = OverlayPrefs(context)
        when {
            prefs.imageVaultEnabled -> GuideRepo.downloader(context).enqueue(folderId, state.images)
            prefs.highPerfMode -> ThumbPrefetcher.prefetchAll(context, state.images)
        }
    }
    when {
        !state.loggedIn -> LoginPrompt(state, actions)
        (state.loadingCategories || state.loadingContent) && state.isEmpty -> LoadingBox()
        state.error != null && state.isEmpty -> ErrorBox(state.error.orEmpty(), actions.onRetry)
        state.isEmpty -> EmptyBox()
        else -> {
            // v2.0.7：入场动画的判定与进度都在 FloatingContent 根上算好了，
            // 这里只管画。以前判定写在这一层，进目录时被 LoadingBox 连锅端掉，
            // 碎片那条路永远走不到（lastNavKey 重建后恒为 null）。
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .background(OverlayColors.Page)
                    .drawWithContent {
                        drawContent()
                        // 动画值在 draw 阶段读：只触发重绘，不重组整张网格
                        drawTileMask(tiles.value)
                    },
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(state.folders, key = { _, f -> "f_${f.id}" }) { _, folder ->
                    // v2.0.9：格子本身不再做浮起 —— 小窗里只留「碎片掀开」这一种。
                    // 两套同时播时，碎片还没扫完格子已经在浮，看着像两拨东西在抢。
                    FolderCell(folder) { actions.onOpenFolder(folder) }
                }
                itemsIndexed(state.images, key = { _, i -> "i_${i.id}" }) { index, image ->
                    ThumbCell(image, actions) { actions.onOpenPreview(index) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// v2.0.7：入场动画
// ---------------------------------------------------------------------------
//
// 两套，按「换的是什么」分工：
//
//   碎片掀开（tileReveal）—— 第一次打开、换来源、进 / 退目录、刷新、手动重播，
//   全部走这一套。整块内容区被切成约 46dp 的格子，从左上角开始按对角线一块块
//   缩小让开，露出的就是下面的画面 —— 看起来像画面碎成小块、再逐格浮现。
//
// v2.0.9 删掉了原来的「格子逐个浮起」：两套同时存在时，碎片还没扫完格子已经
// 在浮，像两拨东西在抢。用户也明确说了「只做一种」。
//
// 碎片为什么不做成「每格独立动画」：那得把内容画 N 遍（每遍裁一块），
// 一屏一百多块就等于一帧画一百多遍网格。这里反过来 —— 内容只画一遍，
// 上面盖一层接近底色的方块，方块按顺序缩小把自己「让」出去。
// 一次内容绘制 + 一百来次圆角矩形，成本几乎为零。

/** 碎片边长。46dp 在 340dp 宽的小窗上是 7 列 × 11 行，块够大，数得清 */
private val TILE_SIZE = 46.dp

/**
 * 碎片整场时长。
 *
 * v2.0.7 从 1.2s 拉到 1.5s，是为了让人眼跟得上「一块一块」的顺序；
 * v2.0.11 按反馈收回到 1.1s —— 每次点进分类都要等 1.5 秒确实拖，
 * 但再短（900ms 以下）单块只剩 200ms 出头，又数不清先后了。
 */
private const val TILE_TOTAL_MS = 1100

/**
 * 单块从开始缩到消失，占多少个「对角延迟单位」。
 *
 * 5.2 表示一块要缩 5.2 格的时间（约占整场四分之一）。这个值同时决定
 * 「同时有几块在动」：太小（2.6 试过）单块一闪就没了；太大就糊成一片淡出，
 * 数不出块。5.2 时约十来块同时在缩，看得清一块一块，又不至于卡顿。
 * 用相对单位而不是毫秒，整场时长怎么调都不用跟着改这个数。
 */
private const val TILE_SPAN = 5.2f

/** 列方向每格多算 0.14 的延迟：同一对角线上几块同时消失会像整条斜线在闪 */
private const val TILE_SKEW = 1.14f

/**
 * 碎片遮罩色。
 *
 * 不直接用页面底色：一样是 #F2F2F7 时，块和块之间没有边界，整屏看着像
 * 「一块灰布在缩」，看不出被切碎。压深 4% 之后每块都有轮廓，
 * 掀开的瞬间能看见一格一格的方块在动。
 */
private val TILE_COLOR = Color(0xFFE8E8F0)

/**
 * 碎片掀开的进度（0 = 全遮，1 = 全露）。
 *
 * 用 [Animatable] 而不是 `animateFloatAsState`：换目录时这个 composable 会带着新的
 * navKey 重建，后者会把进度从当前值「倒着」动画回 0 —— 屏幕上就是遮罩先退回去、
 * 再重新盖上来。新建的 Animatable 初始值就是 0，直接盖住，没有回退那一帧。
 *
 * [contentReady] = false（还在转圈）时不许开跑：加载那两秒会把 1.1 秒动画吃干净，
 * 用户看到的还是「转圈 → 唰一下全在」。
 */
@Composable
private fun rememberTileReveal(
    navKey: String,
    contentReady: Boolean
): Animatable<Float, AnimationVector1D> {
    // v2.1.0：开关订阅（关掉时正在盖的遮罩立刻全露），速度在起跑时读一次。
    val enabled by MotionPrefs.enabled.collectAsState()
    val anim = remember(navKey) { Animatable(0f) }
    LaunchedEffect(navKey, contentReady, enabled) {
        if (!contentReady) {
            // 内容还没到：死死盖住，等数据
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        val ms = MotionPrefs.duration(TILE_TOTAL_MS)
        if (ms <= 0) {
            // 动画关掉：直接全露，遮罩一层都不画
            anim.snapTo(1f)
            return@LaunchedEffect
        }
        anim.snapTo(0f)
        // 等两帧再起跑。窗口刚 addView 时第一帧还在路上，动画抢跑就会
        // 在用户看到画面之前先走掉一段 —— 这就是上一版「装了但没动」的元凶之一。
        withFrameNanos { }
        withFrameNanos { }
        anim.animateTo(1f, tween(ms, easing = LinearEasing))
    }
    return anim
}

/**
 * 把内容区盖成一片细格，再按进度逐块揭掉。
 *
 * 每块的本地进度按「列 + 行」的对角距离错开，所以波是从左上角斜着推向
 * 右下角的。块缩小时同时向上飘四分之一格 —— 看着像碎片被掀走，
 * 而不是原地不动地缩小。
 */
private fun DrawScope.drawTileMask(progress: Float) {
    if (progress >= 0.999f) return
    val cell = TILE_SIZE.toPx()
    if (cell <= 1f) return
    val cols = ceil(size.width / cell).toInt().coerceIn(1, 40)
    val rows = ceil(size.height / cell).toInt().coerceIn(1, 80)
    val w = size.width / cols
    val h = size.height / rows
    val units = (cols - 1) * TILE_SKEW + (rows - 1) + TILE_SPAN
    // 所有块合成一条路径、一次填充：
    // 逐块 drawRoundRect 时，相邻两块共享的那条边会被抗锯齿各画一次，
    // 叠出来的 alpha 比周围低一点点 —— 整屏就浮出一层淡网格。
    // 一条 path 里相邻矩形是内部边，填充时直接合并，一条缝都不会有。
    val path = Path()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val delay = c * TILE_SKEW + r
            val local = ((progress * units - delay) / TILE_SPAN).coerceIn(0f, 1f)
            if (local >= 1f) continue
            // 先慢后快：块先「憋」一下再缩掉，收尾干脆；纯线性会显得拖泥带水
            val eased = local * local
            val shrink = 1f - eased
            val hw = w * 0.5f * shrink
            val hh = h * 0.5f * shrink
            if (hw < 0.6f || hh < 0.6f) continue
            val cx = (c + 0.5f) * w
            val cy = (r + 0.5f) * h - h * 0.3f * eased
            path.addRoundRect(
                RoundRect(
                    // v2.0.7：不再外扩，改成内缩 0.7px 故意留一道缝。
                    // 上一版无缝铺满时，整屏看着像「一块灰布在缩」，看不出被切碎；
                    // 留缝之后每一格都有轮廓，才像用户要的「细细碎碎的格子」。
                    left = cx - hw + 0.7f,
                    top = cy - hh + 0.7f,
                    right = cx + hw - 0.7f,
                    bottom = cy + hh - 0.7f,
                    // 圆角跟着缩小量长：进度 0 时是直角，块才铺得满
                    cornerRadius = CornerRadius(minOf(hw, hh) * 0.32f * eased)
                )
            )
        }
    }
    drawPath(path, TILE_COLOR)
}

@Composable
private fun FolderCell(
    folder: PanFolder,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = currentScheme()
    Column(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.15f)
            .clip(RoundedCornerShape(8.dp))
            .background(OverlayColors.Card)
            .border(1.dp, OverlayColors.Divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = folder.name,
            fontSize = 10.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            color = OverlayColors.TextPrimary
        )
    }
}

@Composable
private fun ThumbCell(
    image: PanImage,
    actions: OverlayActions,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // v1.9.5 低性能模式 / v1.9.6 高性能模式：缩略图都按 512px 解码，
    // 别把网盘原图整张读进内存；两种模式共用同一份缓存，来回切也不重复解码。
    val prefs = remember { OverlayPrefs(context) }
    val compactDecode = remember { prefs.lowPerfMode || prefs.highPerfMode }
    // 同步拿地址：列表接口已经带好缩略图 url，绝大多数格子第一帧就有图可发请求
    var source by remember(image.id) { mutableStateOf(actions.thumbSource(image)) }
    LaunchedEffect(image.id) {
        if (source == null) source = actions.resolveSource(image, true)
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.15f)
            .clip(RoundedCornerShape(8.dp))
            .background(OverlayColors.Card)
            .border(1.dp, OverlayColors.Divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        val current = source
        if (current != null) {
            val request = remember(current.url, current.headers, compactDecode) {
                ImageRequest.Builder(context)
                    .data(current.url)
                    .httpHeaders(current.headers.toNetworkHeaders())
                    .apply {
                        if (compactDecode) size(Size(THUMB_MAX_PX, THUMB_MAX_PX))
                    }
                    // 按文件 id 定缓存键：和预取共用同一份内存 / 磁盘缓存
                    .memoryCacheKey(ThumbPrefetcher.memoryKey(image))
                    .diskCacheKey(ThumbPrefetcher.diskKey(image))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = image.name,
                imageLoader = GuideImageLoader.of(context),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    if (current.url.startsWith("file://") || current.url.startsWith("content://")) {
                        runCatching { GuideRepo.vault(context).invalidate(image) }
                    }
                }
            )
        } else {
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                tint = OverlayColors.TextMuted,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center)
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(OverlayColors.ImageScrim)
                .padding(horizontal = 5.dp, vertical = 3.dp)
        ) {
            Text(
                text = image.name,
                color = Color.White,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 大图预览
// ---------------------------------------------------------------------------

@Composable
private fun ImagePager(
    state: OverlayUiState,
    actions: OverlayActions,
    focus: Boolean,
    showOverlay: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    val images = state.images
    if (images.isEmpty()) return
    val initial = (state.previewIndex ?: 0).coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = initial) { images.size }

    LaunchedEffect(state.previewIndex) {
        val target = state.previewIndex ?: return@LaunchedEffect
        if (target in images.indices && target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        images.getOrNull(pagerState.currentPage)?.let(actions.onPrefetch)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = if (focus) 0.dp else 4.dp
        ) { page ->
            PageImage(
                image = images[page],
                focus = focus,
                resolveSource = actions.resolveSource,
                onTap = onTap,
                onDoubleTap = onDoubleTap
            )
        }

        if (showOverlay) {
            Text(
                text = images.getOrNull(pagerState.currentPage)?.name.orEmpty(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OverlayColors.ImageScrim)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${pagerState.currentPage + 1} / ${images.size}",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OverlayColors.ImageScrim)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 9.sp
            )
        }
    }
}

/**
 * 单页大图：双指放大 / 放大后单指平移；单击切换控件、双击铺满。
 * 手势循环加了迭代上限，避免个别 ROM 丢事件后 pointerInput 协程永久挂起、
 * 一直消费事件导致「翻不动页」。
 */
@Composable
private fun PageImage(
    image: PanImage,
    focus: Boolean,
    resolveSource: suspend (PanImage, Boolean) -> ImageSource?,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    val context = LocalContext.current
    val scheme = currentScheme()
    // v1.9.5 低性能 / v1.9.6 高性能：大图按屏幕尺寸解码，别把 4K 原图整张读进来
    val prefs = remember { OverlayPrefs(context) }
    val compactDecode = remember { prefs.lowPerfMode || prefs.highPerfMode }
    val screenSize = remember {
        val m = context.resources.displayMetrics
        Size(m.widthPixels, m.heightPixels)
    }
    var source by remember(image.id) { mutableStateOf<ImageSource?>(null) }
    var failed by remember(image.id) { mutableStateOf(false) }
    var scale by remember(image.id) { mutableStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(image.id) {
        val resolved = resolveSource(image, false)
        if (resolved == null) failed = true else source = resolved
    }
    LaunchedEffect(focus) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(image.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(image.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var guard = 0
                    while (guard++ < 600) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed < 2 && scale <= 1f) continue

                        val zoom = if (pressed > 1) event.calculateZoom() else 1f
                        val pan = event.calculatePan()
                        val next = (scale * zoom).coerceIn(1f, 4f)
                        if (next <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val maxX = (viewport.width * (next - 1f)) / 2f
                            val maxY = (viewport.height * (next - 1f)) / 2f
                            scale = next
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (source == null) {
            Icon(
                imageVector = if (failed) Icons.Rounded.BrokenImage else Icons.Rounded.Image,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color(0x66FFFFFF)
            )
        }

        val current = source
        if (current != null) {
            val request = remember(current.url, current.headers, compactDecode) {
                ImageRequest.Builder(context)
                    .data(current.url)
                    .httpHeaders(current.headers.toNetworkHeaders())
                    .apply {
                        if (compactDecode) size(screenSize)
                    }
                    // 原图也按文件 id 缓存：同一张图第二次点开（换签名直链）直接命中
                    .memoryCacheKey("yunx_full_${image.id}")
                    .diskCacheKey("yunx_full_${image.id}")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = image.name,
                imageLoader = GuideImageLoader.of(context),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = if (focus) ContentScale.Crop else ContentScale.Fit,
                onError = {
                    // v1.9.10：本地文件读不出来（手动删了 / 存储目录被移走）时作废索引，
                    // 下次进分区会重新认领，认不回来再走网络，不会一直卡在破图上
                    if (current.url.startsWith("file://") || current.url.startsWith("content://")) {
                        runCatching { GuideRepo.vault(context).invalidate(image) }
                    }
                }
            )
        } else if (!failed) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = scheme.primary,
                strokeWidth = 2.dp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 底栏：可收起的「透 / 窗」双滑杆 + 重置 / 收起 / 缩球
// ---------------------------------------------------------------------------

@Composable
private fun FooterControls(
    alpha: Float,
    onAlphaChange: (Float) -> Unit,
    sizeScale: Float,
    onSizeChange: (Float) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actions: OverlayActions
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OverlayColors.Card)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OverlayColors.Divider)
        )
        if (expanded) {
            ControlRow("透", alpha, 0.2f..1f, onAlphaChange) {
                FooterIcon(Icons.Rounded.RestartAlt, "重置悬浮窗") { actions.onReset() }
                FooterIcon(Icons.Rounded.ExpandLess, "收起底栏") { onToggleExpanded() }
            }
            // v2.5.0：下限从 0.35 放到 0.05 —— 用户可以一路缩到 28dp，
            // 缩到界面没法点也没关系，长按窗口就能恢复默认大小
            ControlRow("窗", sizeScale, 0.05f..1.25f, onSizeChange) {
                FooterIcon(Icons.Rounded.Minimize, "缩成小抄球") { actions.onCollapse() }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "展开底栏",
                    tint = OverlayColors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("透 / 窗 调节", fontSize = 9.sp, color = OverlayColors.TextMuted)
            }
        }
    }
}

@Composable
private fun ControlRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    trailing: @Composable () -> Unit
) {
    // v2.12.0：底栏导航 → 密度 / 显示数值
    val tuning = LocalThemeTuning.current
    val rowHeight = when (tuning.bottomDensity) {
        "compact" -> 23.dp
        "relaxed" -> 34.dp
        else -> 28.dp
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = OverlayColors.TextMuted,
            modifier = Modifier.width(16.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = overlaySliderColors(),
            modifier = Modifier
                .weight(1f)
                .height((rowHeight - 4.dp).coerceAtLeast(16.dp))
        )
        if (tuning.showSliderValue) {
            Text(
                text = "${(value * 100).toInt()}%",
                fontSize = 8.sp,
                color = OverlayColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.width(26.dp)
            )
        }
        trailing()
    }
}

@Composable
private fun FooterIcon(image: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = image,
            contentDescription = description,
            tint = OverlayColors.TextSecondary,
            modifier = Modifier.size(15.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 空态 / 登录 / 加载 / 错误
// ---------------------------------------------------------------------------

@Composable
private fun LoginPrompt(state: OverlayUiState, actions: OverlayActions) {
    val local = state.source == GuideSource.LOCAL
    val link = state.source == GuideSource.LINK
    CenteredBox {
        Icon(
            when {
                local -> Icons.Rounded.Folder
                link -> Icons.Rounded.Link
                else -> Icons.Rounded.Person
            },
            contentDescription = null,
            tint = OverlayColors.TextMuted,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                local -> "还没有选择本地文件夹"
                link -> "还没有设置分享链接"
                else -> "请先登录${state.platform.label}网盘"
            },
            fontSize = 11.sp,
            color = OverlayColors.TextPrimary
        )
        Text(
            text = when {
                local -> "选一个装攻略图的文件夹，子文件夹当分类、图片当攻略，\n和网盘里的结构一样"
                // 链接必须在主页那边打开过一次才能拿到分享会话：在悬浮窗里放输入框
                // 会让「粘贴 / 键盘 / 验证」全都挤进一个小窗，体验远比在 App 里差
                link -> "回 App「链接小抄」页粘贴一条分享链接，\n这里就能直接看，不用登录、不占空间"
                else -> "登录后会读取「我的小抄」下的分类与图片"
            },
            fontSize = 9.sp,
            color = OverlayColors.TextMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            when {
                local -> "去选择文件夹"
                link -> "刷新"
                else -> "去登录"
            }
        ) {
            // 链接源没有「登录」这回事，点刷新即可：用户可能刚在主页设好回来
            if (link) actions.onRefresh() else actions.onLogin()
        }
    }
}

@Composable
private fun LoadingBox() {
    val scheme = currentScheme()
    CenteredBox {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = scheme.primary,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.height(8.dp))
        Text("加载中…", fontSize = 10.sp, color = OverlayColors.TextMuted)
    }
}

@Composable
private fun EmptyBox() {
    CenteredBox {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = OverlayColors.TextMuted,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("该文件夹为空", fontSize = 10.sp, color = OverlayColors.TextMuted)
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    CenteredBox {
        Text(
            text = message,
            fontSize = 10.sp,
            color = OverlayColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton("重试") { onRetry() }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val scheme = currentScheme()
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/** Coil 3 的逐请求头（预取器也复用这份实现） */
internal fun Map<String, String>.toNetworkHeaders(): NetworkHeaders {
    val builder = NetworkHeaders.Builder()
    var hasUserAgent = false
    forEach { (name, value) ->
        if (value.isNotBlank()) {
            builder.set(name, value)
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
        }
    }
    if (!hasUserAgent) builder.set("User-Agent", QuarkConstants.API_USER_AGENT)
    return builder.build()
}

/**
 * v1.9.5：低性能模式下缩略图的最大解码边长。
 * 悬浮窗里两列网格，单格在 1080p 屏上约 430px，512 足够清晰又不会把原图整张解码。
 */
private const val THUMB_MAX_PX = 512
