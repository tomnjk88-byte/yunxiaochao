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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.data.RemoteCatalog
import com.yunx.app.overlay.data.RemoteTheme
import com.yunx.app.overlay.data.ThemePack
import com.yunx.app.overlay.data.ThemePacks
import com.yunx.app.overlay.data.ThemeStoreRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // v3.4.2：本机 / 在线（SukiSU Nova 镜像）两个来源
    var source by remember { mutableIntStateOf(0) }
    var remoteCatalog by remember { mutableStateOf<RemoteCatalog?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteReload by remember { mutableIntStateOf(0) }
    // 正在下载的那条 (id)，用来在卡片上转圈并挡住重复点击
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 导出 .sukisu：先记下要导的包，等系统文件选择器回 Uri 再写
    var exportPack by remember { mutableStateOf<ThemePack?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pack = exportPack
        exportPack = null
        if (uri != null && pack != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ThemePacks.exportSukisu(pack)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        true
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (ok) "已导出「${pack.name}」" else "导出失败，换个目录再试",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val packList = remember(tick, localTick) { ThemePacks.all(context) }
    val remoteThemes = remoteCatalog?.themes.orEmpty()
    val tags = remember(packList, remoteThemes) {
        (listOf("全部") + (packList.flatMap { it.tags } + remoteThemes.flatMap { it.tags }))
            .distinct().take(9)
    }

    // 在线目录只在切到「在线」时拉一次；点重试 +1 重新拉
    LaunchedEffect(source, remoteReload) {
        if (source != 1) return@LaunchedEffect
        remoteLoading = true
        remoteError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { ThemeStoreRemote.load(context) }
        }
        result.onSuccess { remoteCatalog = it }
            .onFailure { remoteError = it.message ?: "连不上主题商店" }
        remoteLoading = false
    }

    // v3.4.5：封面图也要带 token，否则服务端 401，卡片只剩一片透明
    val remoteToken = remember(remoteCatalog?.baseUrl) {
        remoteCatalog?.baseUrl?.let { ThemeStoreRemote.cachedToken(context, it) }
    }

    // v3.4.8：老包补封面。之前从商店 / 文件导进来的包都没存预览图，
    // 目录一到就按名字去在线列表里配一张，配上了就落盘，之后不再重复下。
    LaunchedEffect(remoteCatalog) {
        val catalog = remoteCatalog ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bare = ThemePacks.installed(context).filter {
                it.coverPath == null && it.tags.contains("SukiSU")
            }
            var fixed = 0
            for (pack in bare) {
                val match = catalog.themes.firstOrNull { it.name == pack.name }
                    ?: catalog.themes.firstOrNull {
                        it.name.contains(pack.name, true) ||
                            pack.name.contains(it.name, true)
                    } ?: continue
                val bytes = runCatching {
                    ThemeStoreRemote.downloadPreview(context, match)
                }.getOrNull() ?: continue
                if (ThemePacks.attachCover(context, pack.id, bytes) != null) fixed++
            }
            if (fixed > 0) {
                withContext(Dispatchers.Main) { localTick++ }
            }
        }
    }

    // 在线列表的搜索过滤（标签筛选由上面的 tag 统一管）
    val remoteShown = remember(remoteThemes, query, tag) {
        val q = query.trim()
        remoteThemes.filter { theme ->
            (tag == "全部" || theme.tags.contains(tag)) &&
                (q.isEmpty() ||
                    theme.name.contains(q, true) ||
                    theme.author.contains(q, true) ||
                    theme.description.contains(q, true) ||
                    theme.tags.any { it.contains(q, true) })
        }
    }

    fun installRemote(theme: RemoteTheme) {
        if (downloadingId != null) return
        downloadingId = theme.id
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ThemeStoreRemote.download(context, theme)
                    val imported = ThemePacks.importBytes(context, bytes, "${theme.name}.sukisu")
                    // v3.4.8：预览图不在 .sukisu 里，单独下一张存成封面，
                    // 卡片才有真截图。失败了就退回按主题配色画的缩略图。
                    imported.pack?.let { pack ->
                        val preview = runCatching {
                            ThemeStoreRemote.downloadPreview(context, theme)
                        }.getOrNull()
                        if (preview != null && preview.isNotEmpty()) {
                            ThemePacks.attachCover(context, pack.id, preview)
                        }
                    }
                    imported
                }
            }
            downloadingId = null
            val imported = result.getOrNull()
            if (imported == null) {
                Toast.makeText(
                    context,
                    "下载失败：${result.exceptionOrNull()?.message ?: "网络异常"}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val pack = imported.pack
            if (pack == null) {
                Toast.makeText(
                    context,
                    imported.error ?: "这个包导不进来",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                localTick++
                onApply(pack)
                Toast.makeText(context, "已下载并应用「${pack.name}」", Toast.LENGTH_SHORT).show()
            }
        }
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
            item(key = "source", span = { GridItemSpan(maxLineSpan) }) {
                SourceTabs(selected = source, onSelect = { source = it })
            }
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
                    if (source == 0) {
                        Spacer(Modifier.width(7.dp))
                        SortChip(label = SORT_LABELS[sort]) { sort = (sort + 1) % SORT_LABELS.size }
                    }
                }
            }
            if (source == 0) {
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
            } else {
                val catalog = remoteCatalog
                when {
                    catalog == null && remoteLoading -> {
                        item(key = "remote.loading", span = { GridItemSpan(maxLineSpan) }) {
                            RemoteStatusRow(text = "正在连接 SukiSU 主题商店…", loading = true)
                        }
                    }
                    catalog == null -> {
                        item(key = "remote.error", span = { GridItemSpan(maxLineSpan) }) {
                            RemoteStatusRow(
                                text = remoteError ?: "连不上主题商店",
                                loading = false,
                                onRetry = { if (!remoteLoading) remoteReload++ }
                            )
                        }
                    }
                    else -> {
                        item(key = "remote.count", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "SukiSU 在线商店",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = t.text,
                                    style = TextStyle(shadow = readableShadow())
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("${remoteShown.size} 套", fontSize = 9.5.sp, color = t.textMuted)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "已过滤 ${catalog.filteredR18} 个 R18",
                                    fontSize = 9.sp,
                                    color = t.textMuted.copy(alpha = 0.8f)
                                )
                            }
                        }
                        items(remoteShown, key = { "remote." + it.id }) { theme ->
                            RemoteThemeCard(
                                theme = theme,
                                coverUrl = ThemeStoreRemote.assetUrl(
                                    catalog.baseUrl,
                                    theme.previewPath.ifBlank {
                                        theme.screenshotPaths.firstOrNull().orEmpty()
                                    }
                                ),
                                downloading = downloadingId == theme.id,
                                token = remoteToken,
                                onClick = { installRemote(theme) }
                            )
                        }
                        if (remoteShown.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                GlassHint("没有匹配的在线主题。换个关键词，或者切回「本机」。")
                            }
                        }
                        if (remoteLoading) {
                            item(key = "remote.refreshing", span = { GridItemSpan(maxLineSpan) }) {
                                RemoteStatusRow(text = "正在刷新…", loading = true)
                            }
                        }
                    }
                }
            }
            item(key = "tail", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 6.dp)) {
                    GlassHint(
                        if (source == 1) {
                            "在线商店来自 SukiSU Nova 官方镜像，R18 内容已自动过滤。" +
                                "点一下就会下载、导入并当场换上，装好后也会出现在「本机」里。"
                        } else {
                            "认得三种包：云小抄的 .json、带封面的 .zip，以及 SukiSU 的 .sukisu" +
                                "（外面又套了一层 zip 的也认）。长按卡片可以导出 .sukisu 发给别人。"
                        }
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
                        if (pack.id == appliedId) {
                            ThemeSkin.clear()
                            OverlayPrefs(context).themePackId = ""
                        }
                        localTick++
                        Toast.makeText(context, "已删除「${pack.name}」", Toast.LENGTH_SHORT).show()
                    }
                },
                onExport = {
                    actionPack = null
                    exportPack = pack
                    exportLauncher.launch(ThemePacks.exportFileName(pack))
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
    onExport: () -> Unit,
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
                Spacer(Modifier.height(8.dp))
                SheetAction(
                    text = if (pack.assets?.isEmpty == false) {
                        "导出主题包（.sukisu · 含图）"
                    } else {
                        "导出主题包（.sukisu）"
                    },
                    primary = false,
                    onClick = onExport
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

/** 来源切换：本机 / SukiSU 在线商店。做成两颗等宽胶囊，选了哪边一眼看得出。 */
@Composable
private fun SourceTabs(selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalUiTokens.current
    val labels = listOf("本机主题", "SukiSU 在线")
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val on = selected == index
            val shape = RoundedCornerShape(13.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(
                        if (on) Brush.horizontalGradient(listOf(t.accent, t.accent2))
                        else Brush.verticalGradient(
                            listOf(t.cardTop.copy(alpha = 0.7f), t.cardBottom.copy(alpha = 0.6f))
                        )
                    )
                    .border(1.dp, if (on) Color.Transparent else t.divider, shape)
                    .combinedClickableCompat(onClick = { onSelect(index) })
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) Color.White else t.textSub
                )
            }
        }
    }
}

/** 在线商店的加载 / 出错行。出错时给一个「重试」。 */
@Composable
private fun RemoteStatusRow(text: String, loading: Boolean, onRetry: (() -> Unit)? = null) {
    val t = LocalUiTokens.current
    GlassCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = t.accent
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = t.textSub,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                Text(
                    "重试",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(t.accent.copy(alpha = 0.12f))
                        .combinedClickableCompat(onRetry)
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 在线主题卡。封面直接走 URL，Coil 自己缓存；下载中转一圈，不给重复点。 */
@Composable
private fun RemoteThemeCard(
    theme: RemoteTheme,
    coverUrl: String?,
    downloading: Boolean,
    /** 商店的图片同样要带访问令牌，不然一律 401 */
    token: String?,
    onClick: () -> Unit
) {
    val t = LocalUiTokens.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(156.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(t.cardTop, t.cardBottom)))
            .border(1.dp, t.divider, shape)
            .combinedClickableCompat(onClick)
    ) {
        if (coverUrl != null) {
            // v3.4.5：封面对 401 很敏感，必须在 ImageRequest 上挂同一个令牌
            val model = remember(coverUrl, token) {
                ImageRequest.Builder(context)
                    .data(coverUrl)
                    .apply {
                        if (!token.isNullOrBlank()) {
                            httpHeaders(
                                NetworkHeaders.Builder()
                                    .set("X-Sukisu-Theme-Token", token)
                                    .build()
                            )
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = theme.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f))
                    )
                )
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(9.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SourceChip(if (theme.adaptive) "自适应" else "SUKISU")
            if (theme.downloads > 0) StatChip(Icons.Rounded.Download, compact(theme.downloads.toInt()))
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 11.dp, end = 44.dp, bottom = 10.dp)
        ) {
            Text(
                theme.name,
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                theme.author,
                fontSize = 8.5.sp,
                color = Color.White.copy(alpha = 0.76f),
                maxLines = 1
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 10.dp)
                .size(30.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = Color(0x33000000))
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.8.dp,
                    color = Color(0xFF141A2A)
                )
            } else {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "下载并应用",
                    modifier = Modifier.size(15.dp),
                    tint = Color(0xFF141A2A)
                )
            }
        }
    }
}

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
        // v3.4.7：没有封面图时，画一张「这个主题下的界面缩略图」——
        // 标题条 + 四张卡 + 底部胶囊，比单纯一圈细环更像预览
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (big) 0.64f else 0.60f)
                .padding(
                    start = if (big) 22.dp else 13.dp,
                    end = if (big) 22.dp else 13.dp,
                    top = if (big) 26.dp else 15.dp
                )
        ) {
            Box(
                Modifier
                    .width(if (big) 96.dp else 58.dp)
                    .height(if (big) 9.dp else 7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.74f))
            )
            Spacer(Modifier.height(if (big) 5.dp else 4.dp))
            Box(
                Modifier
                    .width(if (big) 58.dp else 36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.34f))
            )
            Spacer(Modifier.height(if (big) 12.dp else 8.dp))
            repeat(2) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(if (big) 7.dp else 5.dp)) {
                    repeat(2) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(if (big) 38.dp else 26.dp)
                                .clip(RoundedCornerShape(if (big) 9.dp else 7.dp))
                                .background(Color.White.copy(alpha = 0.13f))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.16f),
                                    RoundedCornerShape(if (big) 9.dp else 7.dp)
                                )
                        )
                    }
                }
                if (row == 0) Spacer(Modifier.height(if (big) 7.dp else 5.dp))
            }
            Spacer(Modifier.weight(1f))
            // 底栏胶囊：缩略图里也能看出「底部导航长什么样」
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (big) 14.dp else 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.17f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.20f),
                        RoundedCornerShape(999.dp)
                    )
            )
        }
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
