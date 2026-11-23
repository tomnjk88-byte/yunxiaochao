/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：
 *   v3.0.8 新增「重叠卡组」—— 每张卡按固定量压住上一张，一直排下去。
 *   v3.1.0 整篇推翻，改成「钱包式卡组」（wallet-style stack）：
 *     · 收起：最上面一张完整，其余每张只露一条 48dp 的标题边，逐条变窄；
 *     · 点一下整摞散成完整列表，顶部有「收起」；
 *     · 卡片多的时候不再把整页拉成一条长带子。
 *
 * 为什么不再用「重叠 + 一直排」：
 *   那是把列表压扁，卡越多页越长，永远像一坨挤在一起的东西；而且每张卡都要
 *   从下一张的上沿开始读，信息密度反而更低。图 1 那种形态是「一摞，点开才散」，
 *   收起时一屏只占一张卡的高度。
 *
 * 为什么卡底必须画在每张卡自己身上：
 *   Compose 的绘制顺序是「容器的 drawBehind → 子项 1 → 子项 2 → …」。
 *   在容器上画卡底，永远画在所有内容下面，被压住的那张卡的文字照样浮在
 *   下一张的卡面上 —— 这正是用户说的「一堆字糊在一起」。所以每张卡自己
 *   带背景（[StackModule]），不存在谁透谁的问题。
 */

package com.yunx.app.overlay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** 当前是不是「卡组」模式。模块自己决定要不要长成一张大卡 */
internal val LocalStackCards = compositionLocalOf { false }

/** 收起时每条边的高度。标题一行 + 上下留白，正好是「一条卡边」的厚度 */
private val PEEK_HEIGHT = 48.dp

/** 收起时最多露出几条边（不算最上面那张完整卡）。多的折成 +N */
private const val MAX_PEEK = 3

/**
 * 卡组里的一张卡。
 *
 * 标题 / 徽章 / 说明必须单独给，不能只给一个组合好的卡体 —— 收起时整张卡不会
 * 参与组合，只靠这几个字段画那一条边。这是「钱包式堆叠」唯一的结构要求。
 */
internal data class DeckCard(
    val title: String,
    val badge: String? = null,
    val note: String? = null,
    /** 普通模式下要不要再套一层 GlassCard。按钮这种本身就成块的传 false */
    val wrapPlain: Boolean = true,
    val body: @Composable () -> Unit
)

/**
 * 一摞卡。收起 / 展开两态，点一下切换。
 *
 * 滚动只在展开态存在：收起时整摞通常不到一屏，直接跟着系统滚动走，
 * 不接管任何手势 —— v3.0.7 那种「自己算吸附点」的写法不会再出现。
 */
@Composable
internal fun StackDeck(
    cards: List<DeckCard>,
    modifier: Modifier = Modifier,
    playKey: Any? = Unit,
    bottomInset: Dp = 40.dp,
    /** 卡组下面还要挂一条「不是卡」的东西时用它（本地小抄那条虚线链接） */
    footer: @Composable () -> Unit = {}
) {
    if (cards.isEmpty()) return

    var open by remember(playKey) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // 换页：合上、回到顶部，别把上一页摊开的状态带过来
    LaunchedEffect(playKey) {
        open = false
        scroll.scrollTo(0)
    }
    // 摊开时从顶上看起：上次停在半截、这次一打开就少了「收起」按钮，很懵
    LaunchedEffect(open) {
        if (open) scroll.scrollTo(0)
    }

    // 换页时整组淡进来：只动这一层的 alpha，卡片里的输入框 / 列表不重组
    val enter = remember(playKey) { Animatable(0f) }
    LaunchedEffect(playKey) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }

    AnimatedContent(
        targetState = open,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value },
        transitionSpec = {
            // 摊开 / 合上都只做淡入淡出：容器高度由 SizeTransform 自己补间，
            // 卡片内容是实打实的布局，不会被拉变形
            fadeIn(tween(220, delayMillis = 30)) togetherWith fadeOut(tween(130))
        },
        contentAlignment = Alignment.TopStart,
        label = "stack-deck"
    ) { expanded ->
        if (expanded) {
            ExpandedDeck(
                cards = cards,
                scroll = scroll,
                bottomInset = bottomInset,
                footer = footer,
                onCollapse = { open = false }
            )
        } else {
            CollapsedDeck(cards = cards, footer = footer, onOpen = { open = true })
        }
    }
}

/** 收起态：最上面一张完整 + 几条只露标题的边 */
@Composable
private fun CollapsedDeck(
    cards: List<DeckCard>,
    footer: @Composable () -> Unit,
    onOpen: () -> Unit
) {
    val scroll = rememberScrollState()
    val interaction = remember { MutableInteractionSource() }
    val visible = min(cards.size, MAX_PEEK + 1)

    Column(
        Modifier
            .fillMaxWidth()
            // 整摞都能点：点在按钮上时按钮先吃掉事件，不会误展开
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen
            )
            .verticalScroll(scroll)
            .padding(bottom = 26.dp)
    ) {
        CompositionLocalProvider(LocalStackCards provides true) {
            StackModule(cards.first())
            if (visible > 1) {
                Spacer(Modifier.height(9.dp))
                for (i in 1 until visible) {
                    DeckStrip(
                        title = cards[i].title,
                        badge = cards[i].badge,
                        depth = i,
                        // 折在这条边下面的还有几张
                        more = if (i == visible - 1) cards.size - visible else 0
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (cards.size > 1) "点这一摞展开 · 共 ${cards.size} 项" else "共 1 项",
            fontSize = 10.sp,
            color = GlassColors.TextMuted,
            modifier = Modifier.padding(start = 6.dp)
        )
        Spacer(Modifier.height(14.dp))
        footer()
    }
}

/** 展开态：完整列表 + 顶部「收起」 */
@Composable
private fun ExpandedDeck(
    cards: List<DeckCard>,
    scroll: androidx.compose.foundation.ScrollState,
    bottomInset: Dp,
    footer: @Composable () -> Unit,
    onCollapse: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 ${cards.size} 项",
                fontSize = 11.sp,
                color = GlassColors.TextMuted
            )
            Spacer(Modifier.weight(1f))
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .clip(shape)
                    .background(GlassColors.TextSub.copy(alpha = 0.10f))
                    .border(1.dp, GlassColors.Stroke, shape)
                    .clickable(onClick = onCollapse)
                    .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "收起",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlassColors.TextSub
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = GlassColors.TextSub
                )
            }
        }
        CompositionLocalProvider(LocalStackCards provides true) {
            cards.forEachIndexed { index, card ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                StackModule(card)
            }
        }
        Spacer(Modifier.height(16.dp))
        footer()
        Spacer(Modifier.height(bottomInset))
    }
}

/**
 * 收起态里的一条边：只有标题 + 徽章，下面被折住的卡不参与组合。
 *
 * 越往下越窄（每层各缩 8dp），一眼看出是一摞而不是一列。
 */
@Composable
private fun DeckStrip(
    title: String,
    badge: String?,
    depth: Int,
    more: Int
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(18.dp)
    // 卡面要实色：半透明卡在叠着的时候会把下面那张的字透出来
    val top = t.cardTop.compositeOver(t.bg)
    val bottom = t.cardBottom.compositeOver(t.bg)
    // v3.4.0：主题包带卡面图时，露出的那几条边也铺同一张图 ——
    // 否则一摞里最上面是插画、下面三条是纯色，看着像两套东西
    val art by ThemeSkin.state.collectAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = (depth * 8).dp)
            .padding(vertical = 3.dp)
            .shadow(
                elevation = 7.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.24f)
            )
            .clip(shape)
            .themeCardArt(art.card, Color.Black.copy(alpha = 0.36f))
            .background(
                if (art.card != null) {
                    Brush.verticalGradient(listOf(top.copy(alpha = 0.66f), bottom.copy(alpha = 0.82f)))
                } else {
                    Brush.verticalGradient(listOf(top, bottom))
                }
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(t.cardBorderTop, t.cardBorderBottom)),
                shape = shape
            )
            .heightIn(min = PEEK_HEIGHT)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = GlassColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            more > 0 -> MorePill("+$more")
            !badge.isNullOrBlank() -> StackBadge(badge)
        }
    }
}

/** 「还有 N 张折在下面」 */
@Composable
private fun MorePill(text: String) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(GlassColors.TextSub.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = GlassColors.TextSub
        )
    }
}

/**
 * 一个模块 —— 在卡组模式下就是「一张卡」，普通模式下保持原来的
 * 「小标题 + 玻璃卡」。
 *
 * [badge] 是标题右边那枚小胶囊（「运行中」「已授权」），只在卡组模式显示；
 * 普通模式的标题是 11sp 小字，塞不下也不需要。
 *
 * [note] 是卡底那行说明。普通模式下它跟在卡片外面，卡组模式下放进卡里 ——
 * 不然说明会浮在下一张卡上。
 */
@Composable
internal fun StackModule(
    title: String,
    badge: String? = null,
    note: String? = null,
    /** 普通模式下要不要再套一层 GlassCard。按钮这种本身就成块的传 false */
    wrapPlain: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!LocalStackCards.current) {
        GlassSectionTitle(title)
        if (wrapPlain) GlassCard { content() } else content()
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                note,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = GlassColors.TextMuted,
                style = androidx.compose.ui.text.TextStyle(shadow = readableShadow())
            )
        }
        return
    }

    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(26.dp)
    // 卡面必须是不透明的：把半透明的主题卡色压到页面底色上算一遍，
    // 得到实色。半透明卡在叠着的时候会透出下面那张的字。
    val top = t.cardTop.compositeOver(t.bg)
    val bottom = t.cardBottom.compositeOver(t.bg)
    val edgeTop = t.cardBorderTop
    val edgeBottom = t.cardBorderBottom
    // v3.4.0：卡面图。渐变退成半透明盖在图上，卡本身仍然是不透明的
    // （图铺满 + 半透明底色），叠着的时候不会透出下一张的字。
    val art by ThemeSkin.state.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            // 矮卡（标题 + 一颗按钮）撑到 168dp：不然它只露几十 dp，
            // 看着不像「一张卡」。
            .heightIn(min = 168.dp)
            // 阴影落在下面那张上 —— 这一圈就是两层之间的分界
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.32f),
                spotColor = Color.Black.copy(alpha = 0.38f)
            )
            .clip(shape)
            .themeCardArt(art.card, Color.Black.copy(alpha = 0.36f))
            .background(
                if (art.card != null) {
                    Brush.verticalGradient(listOf(top.copy(alpha = 0.62f), bottom.copy(alpha = 0.80f)))
                } else {
                    Brush.verticalGradient(listOf(top, bottom))
                }
            )
            // 顶部压一层很淡的白：卡面比卡外亮一档，边界才立得起来
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (t.dark) 0.050f else 0.30f),
                        0.46f to Color.Transparent,
                        startY = 0f,
                        endY = size.height
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height)
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(edgeTop, edgeBottom)),
                shape = shape
            )
            .padding(top = 20.dp, bottom = 22.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                color = GlassColors.Text,
                style = androidx.compose.ui.text.TextStyle(shadow = readableShadow()),
                modifier = Modifier.weight(1f)
            )
            if (!badge.isNullOrBlank()) {
                StackBadge(badge)
            }
        }
        Spacer(Modifier.height(13.dp))
        content()
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                note,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = GlassColors.TextMuted,
                style = androidx.compose.ui.text.TextStyle(shadow = readableShadow()),
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
    }
}

/** [DeckCard] 版本：标题 / 徽章 / 说明都在卡上，直接摊开渲染 */
@Composable
internal fun StackModule(card: DeckCard) {
    StackModule(
        title = card.title,
        badge = card.badge,
        note = card.note,
        wrapPlain = card.wrapPlain,
        content = card.body
    )
}

/** 卡标题右边的小胶囊 */
@Composable
private fun StackBadge(text: String) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(t.accent.copy(alpha = 0.14f))
            .border(1.dp, t.accent.copy(alpha = 0.32f), shape)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            color = if (t.dark) Color(0xFFD7DEFF) else t.accent
        )
    }
}

/** 卡组里的一张「裸」行 —— 卡内不需要再套一层 GlassCard，用一条淡淡的底分隔 */
@Composable
internal fun StackRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (t.dark) 0.040f else 0.34f))
            .border(1.dp, t.divider.copy(alpha = 0.55f), shape)
    ) {
        content()
    }
}
