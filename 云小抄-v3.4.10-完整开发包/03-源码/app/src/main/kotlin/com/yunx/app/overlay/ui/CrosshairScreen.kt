/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.2.0 准星页）；v2.3.0 支持多套准星。
 *
 * 用户诉求原话：「再加一个准星。就是可以设置添加图片，设置位置，在图片一个位置
 * 设置准星，可以自由导入图片。」所以这一页就四件事：
 *   1. 开关：独立于小抄 / 连招的一层小窗；
 *   2. 图片：从相册、文件管理器随便导，换 / 清都行；
 *   3. 瞄准点：点预览图上的任意位置，那一点就是准星对准的点；
 *   4. 位置与外观：拖动悬浮窗 / 一键居中、调大小与透明度、画不画十字线、锁不锁穿透。
 *
 * v2.3.0：一个人不可能只用一把枪 / 一个游戏。顶部「我的准星」可以存多套，
 * 每套各带自己的图、锚点、大小和透明度，点一下就切；长按可重命名 / 删除。
 *
 * 预览和悬浮层共用 CrosshairView，点哪儿就显示在哪儿，不会出现「预览一套、上屏一套」。
 */

package com.yunx.app.overlay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.yunx.app.overlay.data.CrosshairProfile
import com.yunx.app.overlay.data.InstalledCrosshair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun CrosshairPage(
    running: Boolean,
    /** 已保存的所有准星 */
    profiles: List<CrosshairProfile>,
    activeId: String,
    imagePath: String?,
    anchorX: Float,
    anchorY: Float,
    widthPx: Int,
    alpha: Float,
    showLines: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onNewProfile: () -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onAnchorChange: (Float, Float) -> Unit,
    onWidthChange: (Int) -> Unit,
    onAlphaChange: (Float) -> Unit,
    /** 滑杆松手才同步到悬浮窗：拖动过程只更新预览与偏好，避免每帧走一次 IPC */
    onLookCommit: () -> Unit,
    onToggleLines: (Boolean) -> Unit,
    onToggleLock: (Boolean) -> Unit,
    onCenter: () -> Unit,
    onCenterSwap: () -> Unit,
    onReset: () -> Unit,
    /** v2.5.0：随包内置的准星（已经复制到私有目录，可直接用） */
    builtins: List<InstalledCrosshair> = emptyList(),
    onUseBuiltin: (InstalledCrosshair) -> Unit = {},
    /** v2.9.1：抽屉二级页。0 我的准星 / 1 准星调整 */
    pane: Int = 0
) {
    val density = LocalDensity.current
    // 上限跟着屏宽走：服务侧会按「96% 屏宽」再夹一次，界面先夹住，避免滑杆和实际尺寸对不上
    val maxWidthDp = minOf(
        420f,
        LocalConfiguration.current.screenWidthDp * 0.96f
    ).coerceAtLeast(120f)
    val hasImage = !imagePath.isNullOrBlank() && File(imagePath).exists()

    // 长按某套准星 → 管理菜单；点重命名 → 输入框
    var menuFor by remember { mutableStateOf<CrosshairProfile?>(null) }
    var renaming by remember { mutableStateOf<CrosshairProfile?>(null) }
    val active = profiles.firstOrNull { it.id == activeId }

    // 逐项浮现：主页外层已换成普通 Column，这一页自己挂 StaggerColumn，
    // 卡片才是一块块浮上来，而不是整页一起淡入（v2.3.0）
    StaggerColumn(playKey = pane) {
        when (pane) {

        // ------------------------------------------------------------------
        // 我的准星
        // ------------------------------------------------------------------
        0 -> {

        // ------------------------------------------------------------------
        // 多套准星
        // ------------------------------------------------------------------
        GlassSectionTitle("我的准星")
        ProfileStrip(
            profiles = profiles,
            activeId = activeId,
            onSelect = onSelectProfile,
            onLongPress = { menuFor = it },
            onNew = onNewProfile
        )
        Spacer(Modifier.height(6.dp))
        GlassHint(
            "每套准星各存各的图、瞄准点和大小，换个游戏点一下就能切。" +
                "长按某一张可以重命名或删除；带「+」的那张是新建。"
        )

        // ------------------------------------------------------------------
        // 内置准星（v2.5.0）
        // ------------------------------------------------------------------
        if (builtins.isNotEmpty()) {
            GlassSectionTitle("内置准星")
            BuiltinStrip(builtins = builtins, onUse = onUseBuiltin)
            Spacer(Modifier.height(6.dp))
            GlassHint(
                "这些都是按 CS2 / 无畏契约 / Apex / PUBG 常见配置画的，点一下就加进「我的准星」并启用，" +
                    "大小和透明度还能继续调。全部带黑描边，亮墙和暗门都能看清。"
            )
        }
        }

        // ------------------------------------------------------------------
        // 准星调整：开关 / 图片 / 外观 / 位置
        // ------------------------------------------------------------------
        else -> {

        // ------------------------------------------------------------------
        // 开关
        // ------------------------------------------------------------------
        GlassSectionTitle("准星开关")
        GlassPrimaryButton(
            text = if (running) "关闭准星悬浮窗" else "开启准星悬浮窗",
            subtitle = if (hasImage) {
                active?.name?.let { "当前：$it" }
            } else {
                "先导入一张图片，才能打开准星"
            },
            icon = Icons.Rounded.CenterFocusStrong,
            onClick = onToggle
        )
        Spacer(Modifier.height(8.dp))
        GlassHint(
            "准星是独立的一层，和「小抄」「连招」互不影响。导入图片后，点图片上任意位置" +
                "把它设成瞄准点；定位好再打开「锁定」，就不会挡游戏操作。"
        )

        // ------------------------------------------------------------------
        // 图片与瞄准点
        // ------------------------------------------------------------------
        GlassSectionTitle("准星图片")
        GlassCard {
            Column(Modifier.padding(14.dp)) {
                if (!hasImage) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(126.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(GlassColors.TextMuted.copy(alpha = 0.06f))
                            .border(1.dp, GlassColors.Stroke, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Image,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = GlassColors.TextMuted
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("还没有准星图片", fontSize = 12.sp, color = GlassColors.TextSub)
                            Text(
                                "支持相册、文件管理器里的任意图片",
                                fontSize = 10.sp,
                                color = GlassColors.TextMuted
                            )
                        }
                    }
                } else {
                    CrosshairPreview(
                        imagePath = imagePath,
                        anchorX = anchorX,
                        anchorY = anchorY,
                        showLines = showLines,
                        alpha = alpha,
                        onAnchorChange = onAnchorChange
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点图片上的任意位置 → 那一点就是瞄准点（当前 " +
                            "${(anchorX * 100).roundToInt()}% · ${(anchorY * 100).roundToInt()}%）",
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        color = GlassColors.TextMuted
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassPill(
                        text = if (hasImage) "更换图片" else "导入图片",
                        onClick = onPickImage,
                        primary = true
                    )
                    if (hasImage) {
                        Spacer(Modifier.width(8.dp))
                        GlassPill(text = "清除", onClick = onClearImage, primary = false)
                    }
                    Spacer(Modifier.weight(1f))
                    if (hasImage) {
                        Text(
                            "自由导入 · 原图不压缩",
                            fontSize = 9.sp,
                            color = GlassColors.TextMuted
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 外观
        // ------------------------------------------------------------------
        GlassSectionTitle("外观")
        GlassCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                val sliderWidth = (widthPx.takeIf { it > 0 }?.let { it / density.density }
                    ?: 160f).coerceIn(24f, maxWidthDp)
                LabeledSlider(
                    label = "大小",
                    valueText = if (widthPx > 0) "${sliderWidth.roundToInt()} dp" else "自动",
                    value = sliderWidth,
                    range = 24f..maxWidthDp,
                    onChange = { onWidthChange((it * density.density).roundToInt()) },
                    onCommit = onLookCommit
                )
                Spacer(Modifier.height(10.dp))
                LabeledSlider(
                    label = "透明度",
                    valueText = "${(alpha * 100).roundToInt()}%",
                    value = (alpha * 100f).coerceIn(15f, 100f),
                    range = 15f..100f,
                    onChange = { onAlphaChange(it / 100f) },
                    onCommit = onLookCommit
                )
            }
            GlassDivider()
            GlassRow(
                title = "十字辅助线",
                subtitle = "在瞄准点画红色十字；图片自带准星线时可关掉",
                icon = Icons.Rounded.Adjust,
                onClick = { onToggleLines(!showLines) }
            ) {
                GlassSwitch(checked = showLines, onCheckedChange = onToggleLines)
            }
            GlassDivider()
            GlassRow(
                title = "锁定位置（点击穿透）",
                subtitle = if (locked) {
                    "已锁定：不挡游戏操作；要挪位置先关掉这里"
                } else {
                    "长按准星可拖动；定位好建议打开"
                },
                icon = Icons.Rounded.Lock,
                onClick = { onToggleLock(!locked) }
            ) {
                GlassSwitch(checked = locked, onCheckedChange = onToggleLock)
            }
        }

        // ------------------------------------------------------------------
        // 位置
        // ------------------------------------------------------------------
        GlassSectionTitle("位置")
        GlassCard {
            GlassRow(
                title = "居中显示",
                subtitle = "把准星挪回屏幕正中；居中偏了就长按这一行校准屏幕方向",
                icon = Icons.Rounded.MyLocation,
                onClick = onCenter,
                onLongClick = onCenterSwap
            ) {
                Text("居中 ›", fontSize = 10.sp, color = GlassColors.Accent)
            }
            GlassDivider()
            GlassRow(
                title = "恢复默认",
                subtitle = "瞄准点回正中、大小自动、不透明、画十字线",
                icon = Icons.Rounded.Refresh,
                onClick = onReset
            ) {
                Text("重置 ›", fontSize = 10.sp, color = GlassColors.Danger)
            }
        }
        GlassHint(
            "位置也可以直接拖：长按准星 0.35 秒后移动。开了「锁定」就拖不动，" +
                "回这一页关掉即可。改动会立刻同步到屏幕上的准星。"
        )
        }
        }
    }

    // ------------------------------------------------------------------
    // 管理弹窗
    // ------------------------------------------------------------------
    menuFor?.let { target ->
        val canDelete = profiles.size > 1
        Dialog(onDismissRequest = { menuFor = null }) {
            GlassCard(
                allowFrost = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        target.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Text
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (canDelete) "可以改名，也可以删掉这一套" else "这是最后一套准星，至少保留一个",
                        fontSize = 11.sp,
                        color = GlassColors.TextMuted
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassPill(
                            text = "重命名",
                            onClick = {
                                renaming = target
                                menuFor = null
                            },
                            primary = true
                        )
                        if (canDelete) {
                            Spacer(Modifier.width(8.dp))
                            GlassPill(
                                text = "删除",
                                onClick = {
                                    menuFor = null
                                    onDeleteProfile(target.id)
                                },
                                primary = false
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        GlassPill(text = "取消", onClick = { menuFor = null }, primary = false)
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        Dialog(onDismissRequest = { renaming = null }) {
            GlassCard(
                allowFrost = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "重命名准星",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Text
                    )
                    Spacer(Modifier.height(12.dp))
                    GlassTextField(
                        value = text,
                        onValueChange = { text = it.take(16) },
                        placeholder = "给它起个名字，比如「步枪」「狙击」"
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassPill(text = "取消", onClick = { renaming = null }, primary = false)
                        Spacer(Modifier.width(8.dp))
                        GlassPill(
                            text = "保存",
                            onClick = {
                                val name = text.trim()
                                if (name.isNotBlank()) onRenameProfile(target.id, name)
                                renaming = null
                            },
                            primary = true
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 横向的准星列表
// ---------------------------------------------------------------------------

/**
 * 内置准星条（v2.5.0）。
 *
 * 深色卡片底：准星图本身是透明 PNG，压在浅色玻璃上白色那几套会看不见。
 */
@Composable
private fun BuiltinStrip(
    builtins: List<InstalledCrosshair>,
    onUse: (InstalledCrosshair) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 2.dp, end = 8.dp)
    ) {
        items(builtins, key = { it.meta.key }) { item ->
            Column(
                Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onUse(item) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF15151C))
                        .border(1.dp, GlassColors.Stroke, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    ProfileThumb(item.path, Modifier.size(46.dp))
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    item.meta.name,
                    fontSize = 9.sp,
                    color = GlassColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileStrip(
    profiles: List<CrosshairProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onLongPress: (CrosshairProfile) -> Unit,
    onNew: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(profiles, key = { it.id }) { profile ->
            ProfileCard(
                profile = profile,
                active = profile.id == activeId,
                onClick = { onSelect(profile.id) },
                onLongClick = { onLongPress(profile) }
            )
        }
        item(key = "__new__") {
            NewProfileCard(onClick = onNew)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCard(
    profile: CrosshairProfile,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(78.dp)
            .clip(shape)
            .background(
                if (active) GlassColors.Accent.copy(alpha = 0.14f)
                else GlassColors.TextMuted.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (active) GlassColors.Accent else GlassColors.Stroke,
                shape
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassColors.TextMuted.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center
        ) {
            ProfileThumb(
                path = profile.imagePath,
                modifier = Modifier.size(46.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            profile.name,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) GlassColors.Text else GlassColors.TextSub
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewProfileCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(78.dp)
            .clip(shape)
            .border(1.dp, GlassColors.Stroke, shape)
            .combinedClickable(onClick = onClick)
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassColors.Accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "新建准星",
                modifier = Modifier.size(22.dp),
                tint = GlassColors.Accent
            )
        }
        Spacer(Modifier.height(5.dp))
        Text("新建", fontSize = 10.sp, color = GlassColors.Accent)
    }
}

/**
 * 缩略图：后台按 1/4 采样解码，列表里几十张图也不会卡主线程。
 * 文件被删 / 读不出来时落回占位图标，不抛。
 */
@Composable
private fun ProfileThumb(path: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val opt = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(path, opt)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = GlassColors.TextMuted
            )
        }
    }
}

/**
 * 预览：按图片真实比例撑开一块画布，点哪里就把锚点写到哪里。
 *
 * 用 BoxWithConstraints 自己算宽高，而不是 fillMaxWidth + aspectRatio：
 * 超长截图当准星时，后者会把整页拉成两三屏高，前者会按最大高度收窄宽度，不撑爆页面。
 */
@Composable
private fun CrosshairPreview(
    imagePath: String,
    anchorX: Float,
    anchorY: Float,
    showLines: Boolean,
    alpha: Float,
    onAnchorChange: (Float, Float) -> Unit
) {
    val hOverW = remember(imagePath) { CrosshairImages.ratio(imagePath) ?: 1f }
    val maxPreview = 300.dp

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val height = (maxWidth * hOverW).coerceAtMost(maxPreview)
        val width = if (hOverW > 0f) height / hOverW else maxWidth

        Box(
            Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.dp, GlassColors.Stroke, RoundedCornerShape(14.dp))
        ) {
            AndroidView(
                factory = { context -> CrosshairView(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .alpha(alpha),
                update = { view ->
                    view.load(imagePath)
                    view.setAnchor(anchorX, anchorY)
                    view.setShowLines(showLines)
                }
            )
            // 透明点击层：单独一条 pointerInput，不干扰 AndroidView 自己的绘制
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .pointerInput(imagePath) {
                        detectTapGestures { offset ->
                            if (size.width > 0 && size.height > 0) {
                                onAnchorChange(
                                    (offset.x / size.width).coerceIn(0f, 1f),
                                    (offset.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = GlassColors.Text
            )
            Text(valueText, fontSize = 10.sp, color = GlassColors.TextMuted)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = GlassColors.Accent,
                activeTrackColor = GlassColors.Accent,
                inactiveTrackColor = GlassColors.TextMuted.copy(alpha = 0.22f)
            )
        )
    }
}
