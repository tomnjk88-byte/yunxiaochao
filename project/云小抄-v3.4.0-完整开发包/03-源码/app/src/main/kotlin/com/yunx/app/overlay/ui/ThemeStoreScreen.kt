/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.3.0 新增。主题商店 —— 仿参考界面那套「卡片流 + 从文件安装」。
 *
 * 三件事：
 *   ① 逛：搜索框、标签、排序、两列卡片，内置包和本机导入的包混在一起排；
 *   ② 装：点卡片就应用整套皮肤；右下角「从文件安装」吃 .json / .zip；
 *   ③ 换：应用完去主题设置里还能继续微调 —— 这里只负责「一眼看到效果」。
 *
 * 刻意不做「详情页 + 下载进度」：主题包就是几十 KB 的一段参数，
 * 点下去当场就换好了，再让用户等一个进度条纯属装样子。
 */

package com.yunx.app.overlay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.data.ThemePack
import com.yunx.app.overlay.data.ThemePacks

/** 三个排序项。存下标，界面上循环切 */
private val SORT_LABELS = listOf("默认", "最新", "最热")

/**
 * 主题商店。
 *
 * @param tick 每次导入 / 删除之后 +1，用来重读磁盘上的包列表
 * @param appliedId 正在用的那个包（只在界面上标一下，不影响外观计算）
 */
@Composable
fun ThemeStorePage(
    tick: Int,
    appliedId: String,
    onApply: (ThemePack) -> Unit,
    onImport: () -> Unit,
    onOpenStudio: () -> Unit,
    /** Dock 模式下要给底下那条胶囊让位，否则「从文件安装」正好被它压住 */
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val t = LocalUiTokens.current
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("全部") }
    var sort by remember { mutableIntStateOf(0) }
    // 长按卡片弹的那个小菜单；非空就压一层遮罩
    var actionPack by remember { mutableStateOf<ThemePack?>(null) }
    // 删除只影响这一个页面，自己 +1 重读，不必惊动外面
    var localTick by remember { mutableIntStateOf(0) }

    val packList = remember(tick, localTick) { ThemePacks.all(context) }
    val tags = remember(packList) {
        (listOf("全部") + packList.flatMap { it.tags }).distinct().take(9)
    }
    val shown = remember(packList, query, tag, sort) {
        val q = query.trim()
        packList.asSequence()
            .filter { pack ->
                (tag == "全部" || pack.tags.contains(tag)) &&
                    (q.isEmpty() ||
                        pack.name.contains(q, true) ||
                        pack.author.contains(q, true) ||
                        pack.note.contains(q, true) ||
                        pack.tags.any { it.contains(q, true) })
            }
            .let { seq ->
                when (sort) {
                    1 -> seq.sortedByDescending { it.date }
                    2 -> seq.sortedByDescending { it.likes }
                    else -> seq
                }
            }
            .toList()
    }
    // 大卡只在「没筛没搜、按默认排」时出现 —— 筛选状态下再塞一张精选会让人以为它也是结果
    // v3.4.0：先给「本机导入的那套」—— 用户刚导进来的包，最该被看见的就是它，
    // 而不是永远被出厂那套「深海夜航」压在下面
    val featured = if (tag == "全部" && query.isBlank() && sort == 0) {
        packList.firstOrNull { !it.builtin }
            ?: packList.firstOrNull { it.tags.contains("精选") }
            ?: packList.firstOrNull()
    } else {
        null
    }
    // 大卡已经在上面占了一整行，网格里就不能再来一张一样的 ——
    // 否则「深海夜航」会出现两次，看着像重复上架
    val gridPacks = if (featured != null) shown.filter { it.id != featured.id } else shown

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 118.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                SearchField(
                    query = query,
                    onQuery = { query = it },
                    onDone = { focus.clearFocus() }
                )
            }
            item(key = "tags", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(count = tags.size) { index ->
                            val item = tags[index]
                            TagChip(
                                text = if (item == "全部") "全部主题" else item,
                                selected = tag == item,
                                onClick = { tag = item }
                            )
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    SortChip(label = SORT_LABELS[sort]) { sort = (sort + 1) % SORT_LABELS.size }
                }
            }
            if (featured != null) {
                item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                    FeatureCard(
                        pack = featured,
                        applied = featured.id == appliedId,
                        onApply = { onApply(featured) },
                        onLongPress = { actionPack = featured }
                    )
                }
            }
            item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (tag == "全部" && query.isBlank()) "全部主题" else "筛选结果",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.text,
                        style = TextStyle(shadow = readableShadow())
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${gridPacks.size} 套",
                        fontSize = 9.5.sp,
                        color = t.textMuted
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "长按可删除 / 导出",
                        fontSize = 9.sp,
                        color = t.textMuted.copy(alpha = 0.8f)
                    )
                }
            }
            items(gridPacks, key = { it.id }) { pack ->
                ThemePackCard(
                    pack = pack,
                    applied = pack.id == appliedId,
                    onApply = { onApply(pack) },
                    onLongPress = { actionPack = pack }
                )
            }
            if (gridPacks.isEmpty() && featured == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlassHint("没有匹配的主题。换个词，或者把文件拖进来 —— 右下角那颗按钮就是入口。")
                }
            }
            item(key = "tail", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 6.dp)) {
                    GlassHint(
                        "认得三种包：云小抄的 .json、带封面的 .zip，以及 SukiSU 的 .sukisu" +
                            "（外面又套了一层 zip 的也认）。选进来就会当场换上 —— 背景、卡面、" +
                            "底栏图标都跟着变。"
                    )
                    Text(
                        "去主题设置里继续微调 ›",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = t.accent,
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(t.accent.copy(alpha = 0.10f))
                            .combinedClickableCompat(onOpenStudio)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // 从文件安装：浮在右下角，跟参考界面同位置
        InstallFab(
            onClick = onImport,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 24.dp + bottomInset)
        )

        actionPack?.let { pack ->
            PackActionSheet(
                pack = pack,
                applied = pack.id == appliedId,
                onApply = {
                    actionPack = null
                    onApply(pack)
                },
                onDelete = {
                    actionPack = null
                    if (ThemePacks.delete(context, pack)) {
                        // v3.4.1 FIX: 删除正在用的主题包时，必须同步清掉 ThemeSkin 和 prefs
                        // 否则磁盘上的包删了，但内存里卡面图/底栏图标/包ID残留，
                        // 冷启动时 ThemePacks.byId(prefs.themePackId) 找不到这个包，
                        // ThemeSkin 保持最后一次 decode 的状态 —— 就是那套被删掉的图
                        if (applied) {
                            ThemeSkin.clear()
                            OverlayPrefs(context).themePackId = ""
                        }
                        localTick++
                        Toast.makeText(context, "已删除「${pack.name}」", Toast.LENGTH_SHORT).show()
                    }
                },
                onClose = { actionPack = null }
            )
        }
    }
}

/**
 * 长按卡片弹出来的小面板。
 *
 * 没做「详情页」是故意的：主题包的全部内容就是一段参数，应用一下当场就知道长什么样，
 * 真正需要单独处理的事只有两件 —— 把这一套发给别人，或者把自己导进来的删掉。
 */
@Composable
private fun PackActionSheet(
    pack: ThemePack,
    applied: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val t = LocalUiTokens.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.46f))
            .combinedClickableCompat(onClose),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .padding(horizontal = 34.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(t.solid)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                .combinedClickableCompat(onClick = {})
                .padding(18.dp)
        ) {
            Column {
                Text(
                    pack.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = t.text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pack.author} · v${pack.version} · ${pack.date}",
                    fontSize = 9.5.sp,
                    color = t.textMuted
                )
                if (pack.note.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(pack.note, fontSize = 10.sp, lineHeight = 15.sp, color = t.textSub)
                }
                Spacer(Modifier.height(10.dp))
                Dots(pack = pack, size = 14.dp)
                Spacer(Modifier.height(14.dp))
                SheetAction(
                    text = if (applied) "已经是这套了 · 再应用一次" else "应用这套主题",
                    primary = true,
                    onClick = onApply
                )
                Spacer(Modifier.height(8.dp))
                SheetAction(
                    text = "复制主题文本（发给别人）",
                    primary = false,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        // 复制出去的那份不带本机图片路径 —— 传到别人手机上那些路径
                        // 指向的是自己这台机器的私有目录，拿到手也读不出来。
                        // 对方会得到一套「只有参数」的版本，颜色圆角仍然是对的。
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "云小抄主题",
                                ThemePacks.toJson(pack.copy(assets = null))
                            )
                        )
                        Toast.makeText(context, "已复制，粘贴给别人即可", Toast.LENGTH_SHORT).show()
                        onClose()
                    }
                )
                if (!pack.builtin) {
                    Spacer(Modifier.height(8.dp))
                    SheetAction(text = "删除这套（只删本机）", primary = false, danger = true, onClick = onDelete)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "点空白处关闭",
                    fontSize = 8.5.sp,
                    color = t.textMuted,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                        .combinedClickableCompat(onClose)
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    text: String,
    primary: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    primary -> Brush.horizontalGradient(listOf(t.accent, t.accent2))
                    else -> Brush.verticalGradient(
                        listOf(t.textSub.copy(alpha = 0.10f), t.textSub.copy(alpha = 0.05f))
                    )
                }
            )
            .border(1.dp, if (primary) Color.Transparent else t.divider, shape)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                primary -> Color.White
                danger -> t.danger
                else -> t.text
            }
        )
    }
}

// ----------------------------------------------------------------------
// 搜索 / 标签 / 排序
// ----------------------------------------------------------------------

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (t.dark) listOf(Color(0x24FFFFFF), Color(0x12FFFFFF))
                    else listOf(Color(0xE6FFFFFF), Color(0xBFFFFFFF))
                )
            )
            .border(1.dp, if (t.dark) t.stroke else Color(0x66FFFFFF), shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = t.textMuted
        )
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.5.sp, color = t.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth()
            )
            if (query.isEmpty()) {
                Text("搜索主题、作者或标签", fontSize = 12.5.sp, color = t.textMuted)
            }
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(t.textMuted.copy(alpha = 0.18f))
                    .combinedClickableCompat(onClick = { onQuery("") }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "清空",
                    modifier = Modifier.size(11.dp),
                    tint = t.textSub
                )
            }
        }
    }
}

@Composable
private fun TagChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(t.accent, t.accent2))
                } else {
                    Brush.verticalGradient(
                        if (t.dark) listOf(Color(0x1FFFFFFF), Color(0x0DFFFFFF))
                        else listOf(Color(0xB3FFFFFF), Color(0x80FFFFFF))
                    )
                }
            )
            .border(1.dp, if (selected) Color.Transparent else t.stroke, shape)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(
            if (selected) text else "# $text",
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else t.textSub,
            maxLines = 1
        )
    }
}

@Composable
private fun SortChip(label: String, onClick: () -> Unit) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (t.dark) listOf(Color(0x1FFFFFFF), Color(0x0DFFFFFF))
                    else listOf(Color(0xB3FFFFFF), Color(0x80FFFFFF))
                )
            )
            .border(1.dp, t.stroke, shape)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Sort,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = t.textSub
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.5.sp, color = t.textSub, maxLines = 1)
    }
}

// ----------------------------------------------------------------------
// 卡片
// ----------------------------------------------------------------------

@Composable
private fun FeatureCard(
    pack: ThemePack,
    applied: Boolean,
    onApply: () -> Unit,
    onLongPress: () -> Unit
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(22.dp)
    val d0 = Color(pack.dots.getOrElse(0) { 0xFF1F6BFF.toInt() })
    val d1 = Color(pack.dots.getOrElse(1) { 0xFF6AA8FF.toInt() })
    val d2 = Color(pack.dots.getOrElse(2) { 0xFF0B1020.toInt() })
    Box(
        Modifier
            .fillMaxWidth()
            .height(206.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(d0, d2)))
            .combinedClickableCompat(onClick = onApply, onLongClick = onLongPress)
    ) {
        CoverArt(pack = pack, d0 = d0, d1 = d1, d2 = d2, big = true)
        // 底部压暗，字才站得住
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f))
                    )
                )
        )
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(11.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // v3.4.0：不再显示下载量。内置包只剩「喜欢」这一项，
            // 导入的包干脆不画数字 —— 那本来就不是它的真实数据
            if (pack.tags.contains("SukiSU")) {
                SourceChip("SUKISU")
            } else if (pack.likes > 0) {
                StatChip(Icons.Rounded.Favorite, compact(pack.likes))
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(11.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.92f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(
                when {
                    applied -> "使用中"
                    !pack.builtin -> "本机导入"
                    else -> "编辑推荐"
                },
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1B2030)
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 15.dp, end = 74.dp, bottom = 13.dp)
        ) {
            Text(
                pack.note.ifBlank { "一套配色，一套手感" },
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                pack.name,
                fontSize = 25.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pack.author} · ${pack.date}",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Spacer(Modifier.width(8.dp))
                Dots(pack = pack, size = 7.dp)
            }
        }
        ApplyButton(
            applied = applied,
            onClick = onApply,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 13.dp, bottom = 15.dp)
        )
    }
}

@Composable
private fun ThemePackCard(
    pack: ThemePack,
    applied: Boolean,
    onApply: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(18.dp)
    val d0 = Color(pack.dots.getOrElse(0) { 0xFF1F6BFF.toInt() })
    val d1 = Color(pack.dots.getOrElse(1) { 0xFF6AA8FF.toInt() })
    val d2 = Color(pack.dots.getOrElse(2) { 0xFF0B1020.toInt() })
    Box(
        modifier
            .fillMaxWidth()
            .height(214.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(d0, d2)))
            .border(
                1.dp,
                if (applied) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
                shape
            )
            .combinedClickableCompat(onClick = onApply, onLongClick = onLongPress)
    ) {
        CoverArt(pack = pack, d0 = d0, d1 = d1, d2 = d2, big = false)
        Box(
            Modifier
                .fillMaxWidth()
                .height(116.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.76f))
                    )
                )
        )
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(9.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (pack.tags.contains("SukiSU")) {
                SourceChip("SUKISU")
            } else if (pack.likes > 0) {
                StatChip(Icons.Rounded.Favorite, compact(pack.likes))
            }
        }
        if (applied) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(9.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.94f))
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "使用中",
                    modifier = Modifier.size(11.dp),
                    tint = Color(0xFF1B2030)
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 11.dp, end = 44.dp, bottom = 11.dp)
        ) {
            Text(
                pack.name,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pack.author,
                    fontSize = 8.5.sp,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
                Text("·", fontSize = 8.5.sp, color = Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.width(4.dp))
                Text(
                    pack.date,
                    fontSize = 8.5.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1
                )
            }
            if (pack.version.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "v${pack.version}",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
        ApplyButton(
            applied = applied,
            onClick = onApply,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 10.dp)
        )
    }
}

/**
 * 封面。有图就铺图；没图就用主题自己那三颗色画一层「抽象壁纸」——
 * 外圈一道细环、两个大光斑、一条斜光。比放张纯色块更像一个「主题」。
 */
@Composable
private fun CoverArt(pack: ThemePack, d0: Color, d1: Color, d2: Color, big: Boolean) {
    val cover = remember(pack.coverPath) { ThemePacks.cover(pack) }
    Box(Modifier.fillMaxSize()) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = pack.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            return@Box
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // 三段：主色 → 主色掺次色（提亮） → 深底。
                    // 原来中间直接往 d2（最深的那个）混，整张卡上半截就沉下去了，
                    // 远看只剩一块黑蓝，主题之间的差别全被吃掉。
                    Brush.linearGradient(
                        listOf(d0, lerpColor(d0, d1, 0.5f), lerpColor(d0, d2, 0.88f))
                    )
                )
        )
        // 右上光斑
        Box(
            Modifier
                .size(if (big) 240.dp else 170.dp)
                .align(Alignment.TopEnd)
                .offset(x = 46.dp, y = (-56).dp)
                .background(
                    Brush.radialGradient(
                        listOf(d1.copy(alpha = 0.85f), d1.copy(alpha = 0f))
                    )
                )
        )
        // 左下再压一颗，画面就有纵深了
        Box(
            Modifier
                .size(if (big) 180.dp else 130.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-40).dp, y = 30.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent)
                    )
                )
        )
        // 细环：卡面里唯一一条「线」，让纯渐变不至于太空
        Box(
            Modifier
                .size(if (big) 150.dp else 104.dp)
                .align(Alignment.TopStart)
                .offset(x = if (big) 96.dp else 54.dp, y = if (big) 46.dp else 30.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        )
        // 斜光
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                )
        )
    }
}

/** 来源角标。目前只有一种：SukiSU 导进来的包 */
@Composable
private fun SourceChip(text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 1
        )
    }
}

@Composable
private fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = Color.White.copy(alpha = 0.9f)
        )
        Spacer(Modifier.width(2.dp))
        Text(text, fontSize = 8.5.sp, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun Dots(pack: ThemePack, size: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        pack.dots.take(3).forEach { argb ->
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
            )
        }
    }
}

@Composable
private fun ApplyButton(applied: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .shadow(8.dp, CircleShape, clip = false, ambientColor = Color(0x33000000))
            .clip(CircleShape)
            .background(
                if (applied) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.94f)
            )
            .combinedClickableCompat(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (applied) Icons.Rounded.Check else Icons.Rounded.Download,
            contentDescription = if (applied) "使用中" else "应用这套主题",
            modifier = Modifier.size(15.dp),
            tint = if (applied) Color.White else Color(0xFF141A2A)
        )
    }
}

@Composable
private fun InstallFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .shadow(18.dp, shape, clip = false, ambientColor = Color(0x44000000), spotColor = Color(0x55000000))
            .clip(shape)
            .background(
                if (t.dark) Color.White.copy(alpha = 0.94f) else Color.White
            )
            .border(1.dp, Color.White.copy(alpha = 0.7f), shape)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.FileDownload,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color(0xFF2A3350)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "从文件安装",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1B2030)
        )
    }
}

// ----------------------------------------------------------------------
// 小件
// ----------------------------------------------------------------------

/** 1100 → 1.1k，3200 → 3.2k。商店里的数字只要一眼看得懂 */
private fun compact(n: Int): String = when {
    n >= 10000 -> "%.0fw".format(n / 10000f)
    n >= 1000 -> "%.1fk".format(n / 1000f)
    else -> n.toString()
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

/**
 * 点一下的统一入口。
 *
 * 商店里的卡片既要能点（应用）、又要能长按（删除 / 导出），
 * 但 combinedClickable 是 ExperimentalFoundationApi —— 全文件加一次 OptIn 太散，
 * 这里包一层，调用点就干净了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
