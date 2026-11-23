/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v2.11.0 新增。v2.12.0 按 SukiSU Nova 的主题设置重排：
 *   顶部预览卡 → 八格分类（颜色 / 背景 / 图标文字 / 首页卡片 /
 *   底栏导航 / 交互手势 / 界面细节 / 主题包）→ 当前分类的内容。
 *   原先所有设置堆在一页里，找一项要滚很久；分类之后一次只看一组。
 */

package com.yunx.app.overlay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import com.yunx.app.overlay.data.MotionPrefs
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.service.FloatingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** 主题设置的九个分类，顺序即宫格顺序 */
private enum class ThemeCategory(val label: String, val icon: ImageVector) {
    COLORS("颜色", Icons.Rounded.Palette),
    BACKGROUND("背景", Icons.Rounded.Wallpaper),
    ICON_TEXT("图标文字", Icons.Rounded.TextFields),
    CARD("首页卡片", Icons.Rounded.Dashboard),
    BOTTOM_BAR("底栏导航", Icons.Rounded.ViewAgenda),
    GESTURE("交互手势", Icons.Rounded.Swipe),
    SLIDE("滑动特效", Icons.Rounded.Layers),
    DETAILS("界面细节", Icons.Rounded.Tune),
    PACK("主题包", Icons.Rounded.Style)
}

@Composable
fun ThemeStudioPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { OverlayPrefs(context) }
    val scope = rememberCoroutineScope()

    // 这一页可能从悬浮窗里打开，MotionPrefs 未必被别处 init 过
    remember(context) { MotionPrefs.init(context) }

    val tuning by GuideStyleStore.tuning.collectAsState()
    val style by GuideStyleStore.style.collectAsState()
    val mode by GuideStyleStore.mode.collectAsState()
    val quality by GuideStyleStore.quality.collectAsState()

    var category by remember { mutableIntStateOf(0) }
    var bubbleIcon by remember { mutableStateOf(prefs.bubbleIconPath) }

    // v2.11.0：字号是唯一一根「松手才生效」的滑杆。
    // 它改的是 LocalDensity.fontScale，一变整棵树都要重新排版 ——
    // 跟着手指每帧重排，手指还没到右边画面就先卡住了。其余几根只改颜色/半径，
    // 不触发排版，可以实时跟手。
    var fontDraft by remember { mutableFloatStateOf(tuning.fontScale) }
    LaunchedEffect(tuning.fontScale) { fontDraft = tuning.fontScale }

    // 拖到一半直接退出这一页时，preview 值还在内存里没落盘 —— 补一次。
    DisposableEffect(Unit) {
        onDispose { GuideStyleStore.commitTuning(context) }
    }

    /** 一次性改若干项（开关 / 分段 / 预设都走这里，立刻落盘） */
    fun update(block: (ThemeTuning) -> ThemeTuning) {
        GuideStyleStore.setTuning(context, block(GuideStyleStore.tuning.value))
    }

    // 换小抄球图标：复制到私有目录，原图删不了也不去动它
    val pickBubbleIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dest = File(context.filesDir, "bubble_icon_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            bubbleIcon?.let { old -> runCatching { File(old).delete() } }
            prefs.bubbleIconPath = dest.absolutePath
            bubbleIcon = dest.absolutePath
            FloatingService.refresh(context)
        }.onFailure {
            Toast.makeText(context, "设置图标失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 自定义背景：同样复制进私有目录，避免相册里的原图被用户删掉后背景变黑
    val pickBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dest = File(context.filesDir, "theme_bg_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            val old = GuideStyleStore.tuning.value.backgroundKey
            if (old.isNotEmpty() && old != "system") runCatching { File(old).delete() }
            update { it.copy(backgroundKey = dest.absolutePath) }
            FloatingService.refresh(context)
        }.onFailure {
            Toast.makeText(context, "设置背景失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // v3.0.2：界面字体。只收字体文件，选完复制进私有目录 —— 原文件被删 / 移走也不影响。
    // 文件名固定为 ui_font.ttf，换一次就是覆盖一次，不攒垃圾。
    val pickFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = UiFonts.import(context, uri)
        if (name == null) {
            Toast.makeText(context, "这个文件不是字体，只认 .ttf / .otf", Toast.LENGTH_SHORT)
                .show()
        } else {
            update { it.copy(fontKey = name) }
            Toast.makeText(context, "字体已换，界面会重新排一遍字", Toast.LENGTH_SHORT).show()
        }
    }

    StaggerColumn(modifier = modifier.fillMaxWidth()) {

        // --------------------------------------------------------------
        // 预览：跟截图一样先给一张「现在长什么样」，改设置时这块实时变
        // --------------------------------------------------------------
        PreviewCard(style = style, mode = mode, tuning = tuning)

        // --------------------------------------------------------------
        // 八格分类：一次只显示一组设置，不再一条长列表堆到底
        // --------------------------------------------------------------
        CategoryGrid(selected = category, onSelect = { category = it })

        when (ThemeCategory.entries.getOrElse(category) { ThemeCategory.COLORS }) {
            ThemeCategory.COLORS -> ColorsSection(
                model = ColorModel(
                    mode = mode,
                    tuning = tuning,
                    quality = quality,
                    style = style
                ),
                update = ::update,
                onExtract = {
                    val key = GuideStyleStore.tuning.value.backgroundKey
                    if (key.isEmpty()) {
                        Toast.makeText(context, "先选一张背景图，再从图里取色", Toast.LENGTH_SHORT).show()
                    } else {
                        scope.launch {
                            val color = withContext(Dispatchers.IO) {
                                loadBackdropBitmap(context, key)?.let { dominantAccentColor(it) } ?: 0
                            }
                            if (color == 0) {
                                Toast.makeText(context, "这张图里没找到合适的颜色", Toast.LENGTH_SHORT).show()
                            } else {
                                update { it.copy(accentKey = "custom", customAccent = color) }
                                Toast.makeText(context, "已从背景图取色", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )

            ThemeCategory.BACKGROUND -> BackgroundSection(
                tuning = tuning,
                update = ::update,
                onPickBackground = {
                    pickBackground.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )

            ThemeCategory.ICON_TEXT -> IconTextSection(
                tuning = tuning,
                fontDraft = fontDraft,
                onFontDraft = { fontDraft = it },
                onCommitFont = {
                    GuideStyleStore.setTuning(
                        context,
                        GuideStyleStore.tuning.value.copy(fontScale = fontDraft)
                    )
                },
                bubbleIcon = bubbleIcon,
                onPickBubbleIcon = {
                    pickBubbleIcon.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onResetBubbleIcon = {
                    bubbleIcon?.let { old -> runCatching { File(old).delete() } }
                    prefs.bubbleIconPath = null
                    bubbleIcon = null
                    FloatingService.refresh(context)
                },
                onPickFont = { pickFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "*/*")) },
                onResetFont = {
                    update { it.copy(fontKey = UiFonts.SYSTEM) }
                    Toast.makeText(context, "已换回系统字体", Toast.LENGTH_SHORT).show()
                },
                update = ::update
            )

            ThemeCategory.CARD -> CardSection(tuning = tuning, update = ::update)

            ThemeCategory.BOTTOM_BAR -> BottomBarSection(tuning = tuning, update = ::update)

            ThemeCategory.GESTURE -> GestureSection()

            ThemeCategory.SLIDE -> SlideSection()

            ThemeCategory.DETAILS -> DetailsSection(tuning = tuning, update = ::update)

            ThemeCategory.PACK -> ThemePackageSection(
                tuning = tuning,
                update = ::update,
                onApply = { preset ->
                    GuideStyleStore.setTuning(context, preset.apply(GuideStyleStore.tuning.value))
                    Toast.makeText(context, "已应用「${preset.name}」", Toast.LENGTH_SHORT).show()
                }
            )
        }

        GlassHint(
            "桌面启动图标暂不支持换样式：那要用多个 activity-alias 反复启用禁用，切错一次" +
                "桌面上就没有图标了，风险不值得。「小抄球图标」换的是悬浮球，改完立刻生效。"
        )
    }
}

// ======================================================================
// 预览卡
// ======================================================================

@Composable
private fun PreviewCard(style: UiStyle, mode: UiMode, tuning: ThemeTuning) {
    val t = LocalUiTokens.current
    val accentLabel = when (tuning.accentKey) {
        "auto" -> "默认色"
        "custom" -> "自定义取色"
        else -> THEME_ACCENTS.firstOrNull { it.key == tuning.accentKey }?.label ?: "默认色"
    }
    val paletteLabel = THEME_PALETTE_STYLES.firstOrNull { it.first == tuning.paletteStyle }?.second ?: "原色"
    val shadow = readableShadow()

    // v3.0.6：这一块原来叫「预览」，外面还挂着一个 11sp 的 GlassSectionTitle("预览")。
    // 于是屏幕最上面紧挨着并排出现三层标题 —— 顶栏「主题设置」(26sp)、
    // 小标题「预览」(11sp)、卡内「预览 · 液态玻璃」(15sp)，字号一路递减、
    // 语义还重复，全压在壁纸的花纹上，就是用户说的「很杂乱」。
    //
    // 现在去掉外面那层小标题，卡里只留一个主信息（用的哪套风格），
    // 其余压成一行副标题 + 色点；长说明移到九宫格上方当脚注。
    // 卡片矮了约 40dp，九宫格整块上移，顶部不再有四行字挤在一起。
    GlassCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        style.label,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassColors.Text,
                        style = TextStyle(shadow = shadow)
                    )
                    Spacer(Modifier.width(7.dp))
                    // 「当前」小徽章：说明这是正在生效的那套，不是另一个设置入口
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(t.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text("当前", fontSize = 8.5.sp, color = t.accent)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${mode.label} · $paletteLabel · $accentLabel",
                    fontSize = 10.5.sp,
                    color = GlassColors.TextSub,
                    style = TextStyle(shadow = shadow)
                )
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(t.accent, t.accent2, t.ok).forEach { c ->
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(c)
                        )
                    }
                }
            }
            // 一块「窗口样张」：调不透明度 / 模糊 / 圆角时，这块最先变
            Box(
                Modifier
                    .width(64.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape((14f * tuning.cardRadius).dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                t.cardTop.copy(alpha = t.cardTop.alpha * tuning.cardAlpha),
                                t.cardBottom.copy(alpha = t.cardBottom.alpha * tuning.cardAlpha)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                t.cardBorderTop.copy(alpha = (t.cardBorderTop.alpha * tuning.borderScale).coerceIn(0f, 1f)),
                                t.cardBorderBottom.copy(alpha = (t.cardBorderBottom.alpha * tuning.borderScale).coerceIn(0f, 1f))
                            )
                        ),
                        RoundedCornerShape((14f * tuning.cardRadius).dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(t.accent)
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 3.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(t.textMuted.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

// ======================================================================
// 分类宫格
// ======================================================================

@Composable
private fun CategoryGrid(selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalUiTokens.current
    // v3.0.3：多了一格「滑动特效」，八格改九格 —— 四列会剩一个孤零零的尾格，
    // 改成三列正好 3×3，格子也宽一点，图标和字更松快。
    val rows = ThemeCategory.entries.chunked(3)
    Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
        // v3.0.6：这句原来挤在预览卡最底下，字号 9.5sp，压在壁纸上看不清。
        // 挪到九宫格正上方当脚注 —— 它本来就是解释宫格怎么用的。
        Text(
            "点一格只看那一组 · 滑杆松手即保存",
            fontSize = 9.5.sp,
            color = GlassColors.TextMuted,
            style = TextStyle(shadow = readableShadow()),
            modifier = Modifier.padding(start = 3.dp, bottom = 8.dp)
        )
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { cat ->
                    val index = ThemeCategory.entries.indexOf(cat)
                    val active = index == selected
                    val shape = RoundedCornerShape(15.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(
                                if (active) {
                                    Brush.verticalGradient(
                                        listOf(
                                            t.accent.copy(alpha = if (t.dark) 0.26f else 0.20f),
                                            t.accent2.copy(alpha = if (t.dark) 0.16f else 0.12f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(t.cardTop.copy(alpha = 0.55f), t.cardBottom.copy(alpha = 0.45f))
                                    )
                                }
                            )
                            .border(
                                1.dp,
                                if (active) t.accent.copy(alpha = 0.55f) else t.divider,
                                shape
                            )
                            .clickable { onSelect(index) }
                            .padding(vertical = 11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            cat.icon,
                            contentDescription = null,
                            tint = if (active) GlassColors.Accent else GlassColors.TextSub,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            cat.label,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            color = if (active) GlassColors.Text else GlassColors.TextSub
                        )
                    }
                }
                // 最后一行不足三格时补空位，保证每格宽度一致
                repeat(3 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (rowIndex < rows.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

// ======================================================================
// 1. 颜色
// ======================================================================

private data class ColorModel(
    val mode: UiMode,
    val tuning: ThemeTuning,
    val quality: GlassQuality,
    val style: UiStyle
)

@Composable
private fun ColorsSection(
    model: ColorModel,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit,
    onExtract: () -> Unit
) {
    val context = LocalContext.current
    val tuning = model.tuning
    val t = LocalUiTokens.current

    GlassSectionTitle("颜色")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            PaneLabel("明暗")
            GlassSegmented(
                options = UiMode.entries.map { it.label },
                selected = UiMode.entries.indexOf(model.mode),
                onSelect = { GuideStyleStore.selectMode(context, UiMode.entries[it]) }
            )
            Spacer(Modifier.height(14.dp))
            PaneLabel("色彩风格")
            GlassSegmented(
                options = THEME_PALETTE_STYLES.map { it.second },
                selected = THEME_PALETTE_STYLES.indexOfFirst { it.first == tuning.paletteStyle }
                    .coerceAtLeast(0),
                onSelect = {
                    update { cur -> cur.copy(paletteStyle = THEME_PALETTE_STYLES[it].first) }
                }
            )
            Spacer(Modifier.height(14.dp))
            PaneLabel("色彩标准")
            GlassSegmented(
                options = THEME_COLOR_SPECS.map { it.second },
                selected = THEME_COLOR_SPECS.indexOfFirst { it.first == tuning.colorSpec }
                    .coerceAtLeast(0),
                onSelect = {
                    update { cur -> cur.copy(colorSpec = THEME_COLOR_SPECS[it].first) }
                }
            )
            Spacer(Modifier.height(14.dp))
            PaneLabel("界面风格")
            GlassSegmented(
                options = UiStyle.entries.map { it.short },
                selected = UiStyle.entries.indexOf(model.style),
                onSelect = { GuideStyleStore.select(context, UiStyle.entries[it]) }
            )
            Spacer(Modifier.height(14.dp))
            PaneLabel("玻璃质感")
            GlassSegmented(
                options = GlassQuality.entries.map { it.label },
                selected = GlassQuality.entries.indexOf(model.quality),
                onSelect = { GuideStyleStore.selectQuality(context, GlassQuality.entries[it]) }
            )
        }
    }

    GlassSectionTitle("强调色")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(
                "按钮、开关、选中项、进度条都用这个色。",
                fontSize = 10.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                THEME_ACCENTS.forEach { accent ->
                    val active = accent.key == tuning.accentKey
                    val fill = when {
                        accent.key == "auto" -> t.textMuted.copy(alpha = 0.45f)
                        t.dark -> accent.onDark
                        else -> accent.onLight
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .then(
                                if (active) Modifier.border(2.dp, t.text, CircleShape) else Modifier
                            )
                            .clickable {
                                update { it.copy(accentKey = accent.key) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (accent.key == "auto" || !isBright(fill, t.dark)) {
                                    Color.White
                                } else {
                                    Color(0xFF10131C)
                                }
                            )
                        }
                    }
                }
                if (tuning.customAccent != 0) {
                    val active = tuning.accentKey == "custom"
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(tuning.customAccent))
                            .then(
                                if (active) Modifier.border(2.dp, t.text, CircleShape) else Modifier
                            )
                            .clickable { update { it.copy(accentKey = "custom") } },
                        contentAlignment = Alignment.Center
                    ) {
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
        GlassDivider()
        GlassRow(
            title = "从背景图取色",
            subtitle = if (tuning.backgroundKey.isEmpty()) {
                "先在「背景」里选一张图，再把图里的主色拿来当强调色"
            } else {
                "把当前背景的主色作为强调色"
            },
            icon = Icons.Rounded.Palette,
            onClick = onExtract
        ) {
            Text("取色 ›", fontSize = 10.sp, color = GlassColors.Accent)
        }
        if (tuning.accentKey == "custom") {
            GlassDivider()
            GlassRow(
                title = "改回默认色",
                subtitle = "不再覆盖风格自带的强调色",
                icon = Icons.Rounded.RestartAlt,
                iconTint = GlassColors.TextSub,
                onClick = { update { it.copy(accentKey = "auto") } }
            ) {
                Text("恢复 ›", fontSize = 10.sp, color = GlassColors.TextSub)
            }
        }
    }
}

// ======================================================================
// 2. 背景
// ======================================================================

@Composable
private fun BackgroundSection(
    tuning: ThemeTuning,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit,
    onPickBackground: () -> Unit
) {
    val context = LocalContext.current
    val bgLabel = when {
        tuning.backgroundKey.isEmpty() -> "跟界面风格走的程序壁纸"
        tuning.backgroundKey == "system" -> "正在用系统桌面壁纸"
        else -> "正在用自定义图片"
    }

    GlassSectionTitle("背景外观")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            TuningSlider(
                title = "卡片不透明度",
                hint = "调低以后卡片底更透，背后的壁纸能透上来；字不会跟着变淡。",
                value = tuning.cardAlpha,
                range = 0.35f..1f,
                display = "${(tuning.cardAlpha * 100).toInt()}%",
                onChange = { GuideStyleStore.previewTuning(tuning.copy(cardAlpha = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
            TuningSlider(
                title = "背景模糊",
                hint = "0% 完全不糊，只剩一层干净的壁纸；160% 更糊。只对液态玻璃有效。",
                value = tuning.blurScale,
                range = 0f..1.6f,
                display = "${(tuning.blurScale * 100).toInt()}%",
                onChange = { GuideStyleStore.previewTuning(tuning.copy(blurScale = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
            TuningSlider(
                title = "背景遮罩",
                hint = "往左变暗、往右变亮。只压背景那一层，卡片和文字不受影响。",
                value = tuning.scrimBias,
                range = -0.35f..0.35f,
                display = if (tuning.scrimBias > 0.005f) {
                    "变亮 ${(tuning.scrimBias * 100).toInt()}%"
                } else if (tuning.scrimBias < -0.005f) {
                    "变暗 ${(-tuning.scrimBias * 100).toInt()}%"
                } else {
                    "关"
                },
                onChange = { GuideStyleStore.previewTuning(tuning.copy(scrimBias = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
        }
    }

    GlassSectionTitle("壁纸")
    GlassCard {
        GlassRow(
            title = "自定义背景",
            subtitle = bgLabel,
            icon = Icons.Rounded.Image,
            onClick = onPickBackground
        ) {
            Text(
                if (tuning.backgroundKey.isEmpty()) "选择 ›" else "更换 ›",
                fontSize = 10.sp,
                color = GlassColors.Accent
            )
        }
        GlassDivider()
        GlassRow(
            title = "使用系统桌面壁纸",
            subtitle = "把当前桌面壁纸作为界面背景",
            icon = Icons.Rounded.Wallpaper
        ) {
            GlassSwitch(
                checked = tuning.backgroundKey == "system",
                onChange = { on ->
                    if (on) {
                        update { it.copy(backgroundKey = "system") }
                        FloatingService.refresh(context)
                    } else {
                        update { it.copy(backgroundKey = "") }
                        FloatingService.refresh(context)
                    }
                }
            )
        }
        if (tuning.backgroundKey.isNotEmpty()) {
            GlassDivider()
            GlassRow(
                title = "清除自定义背景",
                subtitle = "换回跟界面风格走的程序壁纸",
                icon = Icons.Rounded.RestartAlt,
                iconTint = GlassColors.TextSub,
                onClick = {
                    val old = tuning.backgroundKey
                    if (old.isNotEmpty() && old != "system") runCatching { File(old).delete() }
                    update { it.copy(backgroundKey = "") }
                    FloatingService.refresh(context)
                }
            ) {
                Text("清除 ›", fontSize = 10.sp, color = GlassColors.TextSub)
            }
        }
        GlassDivider()
        GlassRow(
            title = "渐进式背景模糊",
            subtitle = "上下边缘压一层渐变，顶栏和底栏不跟壁纸花纹打架",
            icon = Icons.Rounded.Tune
        ) {
            GlassSwitch(
                checked = tuning.progressiveScrim,
                onChange = { on -> update { it.copy(progressiveScrim = on) } }
            )
        }
    }
}

// ======================================================================
// 3. 图标文字
// ======================================================================

@Composable
private fun IconTextSection(
    tuning: ThemeTuning,
    fontDraft: Float,
    onFontDraft: (Float) -> Unit,
    onCommitFont: () -> Unit,
    bubbleIcon: String?,
    onPickBubbleIcon: () -> Unit,
    onResetBubbleIcon: () -> Unit,
    onPickFont: () -> Unit,
    onResetFont: () -> Unit,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit
) {
    val context = LocalContext.current
    val shapes = listOf("圆角", "圆形", "方形")
    val shapeKeys = listOf("rounded", "circle", "square")

    GlassSectionTitle("文字")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            TuningSlider(
                title = "界面文字大小",
                hint = "只改字号，卡片圆角和间距不动。松手才生效，拖动时不会卡。",
                value = fontDraft,
                range = 0.85f..1.15f,
                display = "${(fontDraft * 100).toInt()}%",
                onChange = onFontDraft,
                onCommit = onCommitFont
            )
        }
        GlassDivider()
        // v3.0.2：字体。系统那档不设 fontFamily，主题商店换过字体就跟着换；
        // 自定义那档只认 .ttf/.otf，装进私有目录后整页文字一起换。
        GlassRow(
            title = "界面字体",
            subtitle = if (tuning.fontKey.isBlank()) {
                "跟随系统 · 系统主题里换过字体的机器会自动跟着换"
            } else {
                "已装自定义字体 · 想换回系统点下面"
            },
            icon = Icons.Rounded.TextFields,
            onClick = onPickFont
        ) {
            Text(
                if (tuning.fontKey.isBlank()) "选择字体 ›" else "更换 ›",
                fontSize = 10.sp,
                color = GlassColors.Accent
            )
        }
        if (tuning.fontKey.isNotBlank()) {
            GlassDivider()
            GlassRow(
                title = "恢复系统字体",
                subtitle = "把自定义字体撤掉，回到手机自己的字体",
                icon = Icons.Rounded.RestartAlt,
                iconTint = GlassColors.TextSub,
                onClick = onResetFont
            ) {
                Text("恢复 ›", fontSize = 10.sp, color = GlassColors.TextSub)
            }
        }
    }

    GlassSectionTitle("图标")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            PaneLabel("设置行图标底板")
            GlassSegmented(
                options = shapes,
                selected = shapeKeys.indexOf(tuning.iconShape).coerceAtLeast(0),
                onSelect = { update { cur -> cur.copy(iconShape = shapeKeys[it]) } }
            )
        }
        GlassDivider()
        GlassRow(
            title = "小抄球图标",
            subtitle = if (bubbleIcon == null) "默认图标 · 点一下换成自己的图" else "已自定义 · 点一下更换",
            icon = Icons.Rounded.Image,
            onClick = onPickBubbleIcon
        ) {
            Text(
                if (bubbleIcon == null) "更换 ›" else "已设置 ›",
                fontSize = 10.sp,
                color = GlassColors.Accent
            )
        }
        if (bubbleIcon != null) {
            GlassDivider()
            GlassRow(
                title = "恢复默认图标",
                subtitle = "换回随包内置的那个小抄球",
                icon = Icons.Rounded.RestartAlt,
                iconTint = GlassColors.TextSub,
                onClick = onResetBubbleIcon
            ) {
                Text("恢复 ›", fontSize = 10.sp, color = GlassColors.TextSub)
            }
        }
        GlassDivider()
        GlassRow(
            title = "隐藏说明文字",
            subtitle = "设置行只留标题，副标题整行收起",
            icon = Icons.Rounded.TextFields
        ) {
            GlassSwitch(
                checked = tuning.hideSubtitle,
                onChange = { on -> update { it.copy(hideSubtitle = on) } }
            )
        }
    }
}

// ======================================================================
// 4. 首页卡片
// ======================================================================

@Composable
private fun CardSection(
    tuning: ThemeTuning,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit
) {
    val context = LocalContext.current
    GlassSectionTitle("卡片")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            TuningSlider(
                title = "卡片圆角",
                hint = "从接近直角到更圆润，所有玻璃卡片一起变。",
                value = tuning.cardRadius,
                range = 0.6f..1.6f,
                display = "${(tuning.cardRadius * 100).toInt()}%",
                onChange = { GuideStyleStore.previewTuning(tuning.copy(cardRadius = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
            TuningSlider(
                title = "卡片描边",
                hint = "上沿那圈亮线。调到 0 卡片就没有边界，只靠底色分层。",
                value = tuning.borderScale,
                range = 0f..1.6f,
                display = "${(tuning.borderScale * 100).toInt()}%",
                onChange = { GuideStyleStore.previewTuning(tuning.copy(borderScale = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
            TuningSlider(
                title = "卡片投影",
                hint = "0% 完全平面，160% 浮得更高。深色下投影本来就淡。",
                value = tuning.shadowScale,
                range = 0f..1.6f,
                display = "${(tuning.shadowScale * 100).toInt()}%",
                onChange = { GuideStyleStore.previewTuning(tuning.copy(shadowScale = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
        }
    }
    GlassSectionTitle("内容")
    GlassCard {
        GlassRow(
            title = "卡片不透明度",
            subtitle = "和「背景」里那根是同一个值，这里放一份方便调卡片时对照",
            icon = Icons.Rounded.Dashboard
        ) {
            Text(
                "${(tuning.cardAlpha * 100).toInt()}%",
                fontSize = 11.sp,
                color = GlassColors.Accent
            )
        }
    }
}

// ======================================================================
// 5. 底栏导航
// ======================================================================

@Composable
private fun BottomBarSection(
    tuning: ThemeTuning,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit
) {
    val context = LocalContext.current
    val nav by GuideStyleStore.nav.collectAsState()
    val densityKeys = listOf("compact", "normal", "relaxed")
    val densityLabels = listOf("紧凑", "标准", "宽松")

    GlassSectionTitle("导航")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            PaneLabel("导航方式")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                NavStyle.entries.forEach { style ->
                    val active = nav == style
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(
                                if (active) {
                                    Brush.verticalGradient(
                                        listOf(
                                            GlassColors.Accent.copy(alpha = 0.22f),
                                            GlassColors.Accent.copy(alpha = 0.10f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            GlassColors.TextSub.copy(alpha = 0.07f),
                                            GlassColors.TextSub.copy(alpha = 0.03f)
                                        )
                                    )
                                }
                            )
                            .border(
                                1.dp,
                                if (active) GlassColors.Accent.copy(alpha = 0.5f) else GlassColors.Divider,
                                shape
                            )
                            .clickable { GuideStyleStore.selectNav(context, style) }
                            .padding(horizontal = 11.dp, vertical = 11.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (style == NavStyle.DOCK) Icons.Rounded.ViewAgenda else Icons.Rounded.Menu,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (active) GlassColors.Accent else GlassColors.TextSub
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                style.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassColors.Text
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            style.hint,
                            fontSize = 8.5.sp,
                            lineHeight = 12.sp,
                            color = GlassColors.TextMuted
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "抽屉：一屏只有内容，页面列表从左边缘滑出来。\n" +
                    "Dock：底部常驻一条玻璃胶囊，小抄 / 连招 / 主题 / 我的 一下切，" +
                    "主题商店也在里面。换完立刻生效，不用重进。",
                fontSize = 9.5.sp,
                lineHeight = 14.sp,
                color = GlassColors.TextMuted
            )
        }
        GlassDivider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            PaneLabel("底栏密度")
            GlassSegmented(
                options = densityLabels,
                selected = densityKeys.indexOf(tuning.bottomDensity).coerceAtLeast(1),
                onSelect = { update { cur -> cur.copy(bottomDensity = densityKeys[it]) } }
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "控制悬浮窗底部那两根滑杆（透明 / 大小）的行高，紧凑更省地方，宽松更好按。",
                fontSize = 9.5.sp,
                lineHeight = 14.sp,
                color = GlassColors.TextMuted
            )
        }
        GlassDivider()
        GlassRow(
            title = "显示滑杆数值",
            subtitle = "在滑杆右侧显示当前百分比",
            icon = Icons.Rounded.ViewAgenda
        ) {
            GlassSwitch(
                checked = tuning.showSliderValue,
                onChange = { on -> update { it.copy(showSliderValue = on) } }
            )
        }
    }
}

// ======================================================================
// 6. 交互手势
// ======================================================================

@Composable
private fun GestureSection() {
    val context = LocalContext.current
    val enabled by MotionPrefs.enabled.collectAsState()
    val speed by MotionPrefs.speed.collectAsState()
    val entrance by MotionPrefs.entrance.collectAsState()
    val switchStyle by MotionPrefs.switchStyle.collectAsState()

    GlassSectionTitle("动效")
    GlassCard {
        GlassRow(
            title = "界面动画",
            subtitle = "关闭后所有入场 / 切页动画直接落到终态",
            icon = Icons.Rounded.Swipe
        ) {
            GlassSwitch(
                checked = enabled,
                onChange = { MotionPrefs.setEnabled(context, it) }
            )
        }
        GlassDivider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            PaneLabel("动画速度")
            GlassSegmented(
                options = MotionPrefs.SPEED_LABELS,
                selected = speed,
                onSelect = { MotionPrefs.setSpeed(context, it) }
            )
            Spacer(Modifier.height(14.dp))
            PaneLabel("开屏动画")
            GlassSegmented(
                options = MotionPrefs.ENTRANCE_LABELS,
                selected = entrance,
                onSelect = { MotionPrefs.setEntrance(context, it) }
            )
            Spacer(Modifier.height(14.dp))
            PaneLabel("切页动画")
            GlassSegmented(
                options = MotionPrefs.SWITCH_LABELS,
                selected = switchStyle,
                onSelect = { MotionPrefs.setSwitchStyle(context, it) }
            )
        }
    }
    GlassHint(
        "改完立刻生效：切到别的页面看一眼，再回来接着调。关掉动画后界面会瞬间切换，" +
            "省电也省心。"
    )

    // ★ v3.0.1：自定义开屏素材（图片 / 视频 + 停留时长）从「我的 → 外观与动画」搬过来。
    //   那一页撤掉之后这是它唯一的入口 —— 光有上面那排「开屏动画」风格，选不了自己的图。
    Spacer(Modifier.height(4.dp))
    SplashSettingsCard()
}

// ======================================================================
// 7. 滑动特效（v3.0.5：四种，第四格占位）
// ======================================================================

@Composable
private fun SlideSection() {
    val context = LocalContext.current
    val slide by MotionPrefs.slide.collectAsState()

    GlassSectionTitle("滑动特效")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            PaneLabel("主页划法")
            Spacer(Modifier.height(9.dp))
            SlideModeGrid(
                selected = slide,
                onSelect = { index ->
                    MotionPrefs.setSlide(context, index)
                }
            )
            Spacer(Modifier.height(12.dp))
            SlidePreview(slide)
            Spacer(Modifier.height(10.dp))
            Text(
                when (slide) {
                    MotionPrefs.SLIDE_STACK ->
                        "一摞卡：最上面一张完整，其余每张只露一条标题边，" +
                            "点一下整摞散成完整列表，再点「收起」合回去。" +
                            "不分页、不吸附、不拦点击。"
                    else ->
                        "和最初那版一样：整页跟着手指滚，滚到哪停哪。"
                },
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                color = GlassColors.TextMuted
            )
        }
    }
    GlassHint(
        "只改「个人小抄 / 本地小抄」这两页的排法；其余页和解析 / 下载仍然按原来的方式滚。" +
            "卡组模式下点一下那摞卡就展开，先看上面的预览再回主页试。"
    )
}

/** 四格选择器：2×2，第四格「开发中」置灰但可点（点了给提示） */
@Composable
private fun SlideModeGrid(selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalUiTokens.current
    val hints = listOf("和以前一样", "一摞卡，点开才散")
    Column(Modifier.fillMaxWidth()) {
        MotionPrefs.SLIDE_LABELS.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { label ->
                    val index = MotionPrefs.SLIDE_LABELS.indexOf(label)
                    val active = index == selected
                    val shape = RoundedCornerShape(13.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(
                                if (active) {
                                    Brush.verticalGradient(
                                        listOf(
                                            t.accent.copy(alpha = if (t.dark) 0.30f else 0.22f),
                                            t.accent2.copy(alpha = if (t.dark) 0.18f else 0.13f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            t.cardTop.copy(alpha = 0.55f),
                                            t.cardBottom.copy(alpha = 0.45f)
                                        )
                                    )
                                }
                            )
                            .border(
                                1.dp,
                                if (active) t.accent.copy(alpha = 0.60f) else t.divider,
                                shape
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) GlassColors.Text else GlassColors.TextSub
                            )
                            if (active) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = GlassColors.Accent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            hints[index],
                            fontSize = 9.sp,
                            maxLines = 1,
                            color = GlassColors.TextMuted
                        )
                    }
                }
                repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            if (rowIndex == 0) Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 可拖动的小窗口 —— 三种可用划法的实时示意。
 *
 * 用户先看的是 HTML 样图，那里能直接拖着看；设置页如果只放一张静态图，
 * 他没法确认「我选的这一种和样图是不是一回事」。这里按住上下拖，
 * 卡片就按当前模式的公式走，松手回弹到起始位置。
 */
@Composable
private fun SlidePreview(mode: Int) {
    val t = LocalUiTokens.current
    val accent = GlassColors.Accent
    val scope = rememberCoroutineScope()
    var progress by remember(mode) { mutableFloatStateOf(0f) }

    fun springBack() {
        scope.launch {
            animate(progress, 0f, animationSpec = tween(420, easing = FastOutSlowInEasing)) { v, _ ->
                progress = v
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(126.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                Brush.verticalGradient(
                    listOf(t.cardTop.copy(alpha = 0.42f), t.cardBottom.copy(alpha = 0.24f))
                )
            )
            .border(1.dp, t.divider, RoundedCornerShape(13.dp))
            .then(
                // 普通模式：上下拖一下，能看见模块跟着手指走。
                // 卡组模式没有「拖」这回事了（点一下才散），所以这里不挂手势。
                if (mode == MotionPrefs.SLIDE_STACK) {
                    Modifier
                } else {
                    Modifier.pointerInput(mode) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                progress = (progress - dy / (size.height * 0.90f)).coerceIn(0f, 1f)
                            },
                            onDragEnd = { springBack() },
                            onDragCancel = { springBack() }
                        )
                    }
                }
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val padX = 16.dp.toPx()
            val w = size.width - padX * 2
            val h = size.height
            val radius = CornerRadius(8.dp.toPx())

            fun card(centerY: Float, scale: Float, alpha: Float) {
                if (alpha <= 0.02f) return
                val cw = w * 0.94f * scale
                val chh = h * 0.30f * scale
                val left = (size.width - cw) / 2f
                val topPx = centerY - chh / 2f
                drawRoundRect(
                    color = accent.copy(alpha = (0.15f * alpha).coerceAtMost(1f)),
                    topLeft = Offset(left, topPx),
                    size = Size(cw, chh),
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = accent.copy(alpha = (0.55f * alpha).coerceAtMost(1f)),
                    topLeft = Offset(left, topPx),
                    size = Size(cw, chh),
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx())
                )
                val lx = left + cw * 0.09f
                val lw = cw * 0.58f
                drawRoundRect(
                    color = accent.copy(alpha = (0.40f * alpha).coerceAtMost(1f)),
                    topLeft = Offset(lx, topPx + chh * 0.28f),
                    size = Size(lw, 2.2.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = accent.copy(alpha = (0.20f * alpha).coerceAtMost(1f)),
                    topLeft = Offset(lx, topPx + chh * 0.54f),
                    size = Size(lw * 0.72f, 2.2.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }

            when (mode) {
            // 卡组（v3.1.0）：一摞 —— 最上面一张完整，下面三条只露标题边。
            // 它不跟手指走、点一下才散，所以这里画的就是「收起」时的样子。
            MotionPrefs.SLIDE_STACK -> {
                val top0 = h * 0.055f
                val cardH = h * 0.52f
                val stripH = h * 0.095f
                val gap = h * 0.022f

                fun face(top: Float, left: Float, width: Float, height: Float, live: Boolean) {
                    drawRoundRect(
                        color = t.bg,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        cornerRadius = radius
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = if (live) 0.55f else 0.26f),
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        cornerRadius = radius,
                        style = Stroke(1.dp.toPx())
                    )
                }

                // 最上面那张：标题 + 一行内容 + 一颗按钮
                face(top0, padX, w, cardH, true)
                drawRoundRect(
                    color = accent.copy(alpha = 0.52f),
                    topLeft = Offset(padX + w * 0.07f, top0 + cardH * 0.16f),
                    size = Size(w * 0.34f, 2.6.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.20f),
                    topLeft = Offset(padX + w * 0.07f, top0 + cardH * 0.34f),
                    size = Size(w * 0.62f, 2.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.30f),
                    topLeft = Offset(padX + w * 0.07f, top0 + cardH * 0.60f),
                    size = Size(w * 0.86f, cardH * 0.24f),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )

                // 下面三条：逐条变窄，每条只画一行标题
                for (i in 1..3) {
                    val inset = i * 6.dp.toPx()
                    val top = top0 + cardH + gap + (i - 1) * (stripH + gap)
                    face(top, padX + inset, w - inset * 2, stripH, false)
                    drawRoundRect(
                        color = accent.copy(alpha = 0.34f),
                        topLeft = Offset(padX + inset + w * 0.06f, top + stripH * 0.40f),
                        size = Size(w * 0.30f, 2.2.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }

                // 普通：一条条排开，整块平移，不缩不淡
                else -> {
                    for (i in 0..2) {
                        card(
                            centerY = h * 0.26f + i * h * 0.34f - progress * h * 0.34f,
                            scale = 1f,
                            alpha = 0.92f - i * 0.16f
                        )
                    }
                }
            }
        }
        Text(
            "按住上下拖",
            fontSize = 9.sp,
            color = t.textMuted.copy(alpha = 0.60f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 6.dp)
        )
    }
}

// ======================================================================
// 8. 界面细节
// ======================================================================

@Composable
private fun DetailsSection(
    tuning: ThemeTuning,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit
) {
    val context = LocalContext.current
    GlassSectionTitle("细节")
    GlassCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            TuningSlider(
                title = "分割线强度",
                hint = "设置行之间那条细线。0% 完全不画，靠留白分组。",
                value = tuning.dividerScale,
                range = 0f..1.6f,
                display = "${(tuning.dividerScale * 100).toInt()}%",
                onChange = { GuideStyleStore.previewTuning(tuning.copy(dividerScale = it)) },
                onCommit = { GuideStyleStore.commitTuning(context) }
            )
        }
    }
    GlassHint(
        "这一组只影响「线」和「边界」这类细节。想整体换气质去「主题包」，" +
            "想换颜色去「颜色」。"
    )
}

// ======================================================================
// 8. 主题包
// ======================================================================

private data class ThemePreset(
    val name: String,
    val hint: String,
    val dot: Color,
    val apply: (ThemeTuning) -> ThemeTuning
)

private val THEME_PRESETS = listOf(
    ThemePreset("默认", "冰蓝 · 标准玻璃", Color(0xFF6AA8FF)) { t ->
        t.copy(
            accentKey = "auto", paletteStyle = "tonal", colorSpec = "2025",
            cardAlpha = 1f, blurScale = 1f, scrimBias = 0f,
            cardRadius = 1f, borderScale = 1f, shadowScale = 1f, dividerScale = 1f
        )
    },
    ThemePreset("极简灰", "单色 · 低模糊 · 细边界", Color(0xFFB8BCC8)) { t ->
        t.copy(
            paletteStyle = "mono", colorSpec = "2021",
            cardAlpha = 0.88f, blurScale = 0.45f, scrimBias = -0.04f,
            cardRadius = 0.85f, borderScale = 0.5f, shadowScale = 0.4f, dividerScale = 0.7f
        )
    },
    ThemePreset("午夜蓝", "冰蓝 · 深透 · 高模糊", Color(0xFF2D6BFF)) { t ->
        t.copy(
            accentKey = "ice", paletteStyle = "tonal", colorSpec = "2025",
            cardAlpha = 0.72f, blurScale = 1.35f, scrimBias = -0.18f,
            cardRadius = 1.1f, borderScale = 1.15f, shadowScale = 1.2f, dividerScale = 1f
        )
    },
    ThemePreset("樱花粉", "玫瑰 · 大圆角 · 柔光", Color(0xFFD6336C)) { t ->
        t.copy(
            accentKey = "rose", paletteStyle = "vibrant", colorSpec = "2025",
            cardAlpha = 0.8f, blurScale = 1.1f, scrimBias = 0.05f,
            cardRadius = 1.3f, borderScale = 1.25f, shadowScale = 1.1f, dividerScale = 0.9f
        )
    },
    ThemePreset("森林绿", "翠绿 · 柔和 · 安稳", Color(0xFF0F9D58)) { t ->
        t.copy(
            accentKey = "jade", paletteStyle = "neutral", colorSpec = "2025",
            cardAlpha = 0.85f, blurScale = 0.9f, scrimBias = -0.06f,
            cardRadius = 1f, borderScale = 0.9f, shadowScale = 0.9f, dividerScale = 1f
        )
    },
    ThemePreset("高对比", "琥珀 · 清晰 · 硬边界", Color(0xFFB47608)) { t ->
        t.copy(
            accentKey = "amber", paletteStyle = "expressive", colorSpec = "2021",
            cardAlpha = 1f, blurScale = 0.25f, scrimBias = -0.28f,
            cardRadius = 0.75f, borderScale = 1.5f, shadowScale = 0.6f, dividerScale = 1.35f
        )
    }
)

@Composable
private fun ThemePackageSection(
    tuning: ThemeTuning,
    update: ((ThemeTuning) -> ThemeTuning) -> Unit,
    onApply: (ThemePreset) -> Unit
) {
    val context = LocalContext.current
    val t = LocalUiTokens.current

    GlassSectionTitle("主题包")
    GlassCard {
        Column(Modifier.padding(12.dp)) {
            THEME_PRESETS.chunked(2).forEachIndexed { rowIndex, rowItems ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { preset ->
                        val shape = RoundedCornerShape(14.dp)
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(t.cardTop.copy(alpha = 0.7f), t.cardBottom.copy(alpha = 0.6f))
                                    )
                                )
                                .border(1.dp, t.divider, shape)
                                .clickable { onApply(preset) }
                                .padding(horizontal = 12.dp, vertical = 11.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(preset.dot)
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    preset.name,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassColors.Text
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                preset.hint,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                color = GlassColors.TextMuted
                            )
                        }
                    }
                    if (rowItems.size < 2) Spacer(Modifier.weight(1f))
                }
                if (rowIndex < THEME_PRESETS.chunked(2).lastIndex) Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "一套主题会同时改强调色、颜色风格、卡片透明度 / 圆角 / 描边和分割线，" +
                    "应用后还能在各自分类里继续微调。",
                fontSize = 9.sp,
                lineHeight = 13.sp,
                color = GlassColors.TextMuted
            )
        }
        GlassDivider()
        GlassRow(
            title = "导出当前主题",
            subtitle = "把这一套设置复制成一段文本，方便存下来或发给别人",
            icon = Icons.Rounded.ContentCopy,
            onClick = {
                val json = tuningToJson(GuideStyleStore.tuning.value)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("云小抄主题", json))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        ) {
            Text("复制 ›", fontSize = 10.sp, color = GlassColors.Accent)
        }
        GlassDivider()
        GlassRow(
            title = "从剪贴板导入",
            subtitle = "粘贴之前导出的那段文本，整套设置一次还原",
            icon = Icons.Rounded.ContentPaste,
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                val parsed = tuningFromJson(text)
                if (parsed == null) {
                    Toast.makeText(context, "剪贴板里没有可用的主题文本", Toast.LENGTH_SHORT).show()
                } else {
                    // 主题文本不带壁纸：保留当前背景，别让导入把用户的图弄没了
                    GuideStyleStore.setTuning(
                        context,
                        parsed.copy(backgroundKey = GuideStyleStore.tuning.value.backgroundKey)
                    )
                    Toast.makeText(context, "已导入主题", Toast.LENGTH_SHORT).show()
                }
            }
        ) {
            Text("导入 ›", fontSize = 10.sp, color = GlassColors.TextSub)
        }
        GlassDivider()
        GlassRow(
            title = "全部恢复默认",
            subtitle = "颜色、背景、卡片、底栏、细节一起回到出厂值（动画设置不动）",
            icon = Icons.Rounded.RestartAlt,
            iconTint = GlassColors.TextSub,
            onClick = {
                // v3.4.1 FIX: 恢复默认必须同步清掉 ThemeSkin 和主题包状态
                // 否则卡面图会残留到默认主题上（从带图包→恢复默认=纯色主题后仍见旧图）
                ThemeSkin.clear()
                OverlayPrefs(context).themePackId = ""
                GuideStyleStore.setTuning(context, ThemeTuning())
                Toast.makeText(context, "已恢复默认主题", Toast.LENGTH_SHORT).show()
            }
        ) {
            Text("恢复 ›", fontSize = 10.sp, color = GlassColors.TextSub)
        }
    }
    GlassHint("主题包不带壁纸：背景图属于你自己的图片，不写进导出文本，换机时不会被带过去。")
}

// ======================================================================
// 小件
// ======================================================================

@Composable
private fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.scale(0.84f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = GlassColors.Accent,
            checkedBorderColor = GlassColors.Accent,
            uncheckedThumbColor = GlassColors.TextMuted,
            uncheckedTrackColor = GlassColors.Divider,
            uncheckedBorderColor = GlassColors.Divider
        )
    )
}

/** 一组设置项上面的小标题 */
@Composable
private fun PaneLabel(text: String) {
    Text(
        text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = GlassColors.Text,
        modifier = Modifier.padding(bottom = 9.dp)
    )
}

/**
 * 一根带说明和实时数值的滑杆。
 *
 * 拖动过程中只更新内存（预览跟手），松手才写盘 —— 见 [GuideStyleStore.previewTuning]。
 */
@Composable
private fun TuningSlider(
    title: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Text,
                modifier = Modifier.weight(1f)
            )
            Text(display, fontSize = 11.sp, color = GlassColors.Accent)
        }
        Text(
            hint,
            fontSize = 9.5.sp,
            lineHeight = 14.sp,
            color = GlassColors.TextMuted,
            modifier = Modifier.padding(top = 3.dp)
        )
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onCommit,
            colors = SliderDefaults.colors(
                thumbColor = GlassColors.Accent,
                activeTrackColor = GlassColors.Accent,
                inactiveTrackColor = GlassColors.TextMuted.copy(
                    alpha = if (LocalUiTokens.current.dark) 0.35f else 0.25f
                ),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

/** 颜色够不够亮 —— 决定选中时打白勾还是黑勾 */
private fun isBright(color: Color, dark: Boolean): Boolean {
    val l = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (dark) l > 0.65f else l > 0.6f
}

// ----------------------------------------------------------------------
// 主题包的导出 / 导入
// ----------------------------------------------------------------------

private fun tuningToJson(t: ThemeTuning): String = JSONObject().apply {
    put("v", 1)
    put("cardAlpha", t.cardAlpha.toDouble())
    put("blurScale", t.blurScale.toDouble())
    put("scrimBias", t.scrimBias.toDouble())
    put("accentKey", t.accentKey)
    put("fontScale", t.fontScale.toDouble())
    put("paletteStyle", t.paletteStyle)
    put("colorSpec", t.colorSpec)
    put("customAccent", t.customAccent)
    put("cardRadius", t.cardRadius.toDouble())
    put("borderScale", t.borderScale.toDouble())
    put("shadowScale", t.shadowScale.toDouble())
    put("dividerScale", t.dividerScale.toDouble())
    put("iconShape", t.iconShape)
    put("hideSubtitle", t.hideSubtitle)
    put("bottomDensity", t.bottomDensity)
    put("showSliderValue", t.showSliderValue)
    put("progressiveScrim", t.progressiveScrim)
}.toString()

internal fun tuningFromJson(text: String): ThemeTuning? = runCatching {
    val o = JSONObject(text)
    ThemeTuning(
        cardAlpha = o.optDouble("cardAlpha", 1.0).toFloat().coerceIn(0.35f, 1f),
        blurScale = o.optDouble("blurScale", 1.0).toFloat().coerceIn(0f, 1.6f),
        scrimBias = o.optDouble("scrimBias", 0.0).toFloat().coerceIn(-0.35f, 0.35f),
        accentKey = o.optString("accentKey", "auto"),
        fontScale = o.optDouble("fontScale", 1.0).toFloat().coerceIn(0.85f, 1.15f),
        paletteStyle = o.optString("paletteStyle", "tonal"),
        colorSpec = o.optString("colorSpec", "2025"),
        customAccent = o.optInt("customAccent", 0),
        cardRadius = o.optDouble("cardRadius", 1.0).toFloat().coerceIn(0.6f, 1.6f),
        borderScale = o.optDouble("borderScale", 1.0).toFloat().coerceIn(0f, 1.6f),
        shadowScale = o.optDouble("shadowScale", 1.0).toFloat().coerceIn(0f, 1.6f),
        dividerScale = o.optDouble("dividerScale", 1.0).toFloat().coerceIn(0f, 1.6f),
        iconShape = o.optString("iconShape", "rounded"),
        hideSubtitle = o.optBoolean("hideSubtitle", false),
        bottomDensity = o.optString("bottomDensity", "normal"),
        showSliderValue = o.optBoolean("showSliderValue", false),
        progressiveScrim = o.optBoolean("progressiveScrim", false),
        // 背景图是本机文件，不随主题文本走
        backgroundKey = ""
    )
}.getOrNull()
