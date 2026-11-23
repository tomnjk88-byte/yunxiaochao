/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.5.9 本地小抄改成「入口」结构的两块界面：
 *   · [ShortcutEntrySection] —— 小抄首页的入口按钮网格（增删改排序都在这里）；
 *   · [ShortcutDetailScreen] —— 点进某个入口后的详情，只列这个目录里的内容。
 *
 * 为什么不在首页平铺文件：用户原话是「所有小抄混在一起，按钮特别乱，
 * 打开龙宫结果别的地图也混进来」。所以首页只放入口，内容一律进详情页看。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.yunx.app.data.db.ShortcutEntryEntity
import com.yunx.app.overlay.data.FolderContent
import com.yunx.app.overlay.data.LocalGuideRepository
import com.yunx.app.overlay.data.PanFolder
import com.yunx.app.overlay.data.PanImage
import com.yunx.app.overlay.data.ShortcutEntryRepository
import kotlinx.coroutines.launch

/** 入口圆标的备选颜色（存 ARGB；0 = 跟随主题） */
private val SHORTCUT_COLORS = listOf(
    0xFF3B82F6.toInt(),
    0xFF10B981.toInt(),
    0xFFF59E0B.toInt(),
    0xFFEF4444.toInt(),
    0xFF8B5CF6.toInt(),
    0xFF06B6D4.toInt()
)

/** 入口能不能读：有持久读授权 + 目录还在 */
private fun entryReadable(context: Context, entry: ShortcutEntryEntity): Boolean = runCatching {
    val uri = Uri.parse(entry.treeUri)
    val granted = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }
    granted
}.getOrDefault(false)

// ======================================================================
// 首页入口网格
// ======================================================================

/**
 * 本地小抄的「入口」区块。
 *
 * 自包含：进来自己起数据层、自己管目录选择器与编辑弹窗，
 * 宿主（MainActivity）只需要接一个「点开某个入口」的回调。
 */
@Composable
internal fun ShortcutEntrySection(onOpen: (ShortcutEntryEntity) -> Unit): DeckCard {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { ShortcutEntryRepository.start(context) }
    val entries by ShortcutEntryRepository.entries.collectAsState()
    // 当前高亮哪个入口。点下去就立刻改，不等 Room 再发一次列表 ——
    // 否则从详情页退回来还停在旧的蓝框上，看着像没切成功。
    var activeId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(entries) {
        activeId = ShortcutEntryRepository.activeEntry(context)?.id
    }

    // null = 新增；非空 = 给这一条换目录
    var pickFor by remember { mutableStateOf<Long?>(null) }
    var naming by remember { mutableStateOf<PendingName?>(null) }
    var managing by remember { mutableStateOf<ShortcutEntryEntity?>(null) }
    // 点开的那颗入口：详情页做成全屏 Dialog，宿主不用为它改导航结构
    var detail by remember { mutableStateOf<ShortcutEntryEntity?>(null) }
    var renaming by remember { mutableStateOf<ShortcutEntryEntity?>(null) }
    var deleting by remember { mutableStateOf<ShortcutEntryEntity?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pickFor = null
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val target = pickFor
        pickFor = null
        if (target != null) {
            // 换目录：名字保持用户起的，不跟着目录名变
            scope.launch { ShortcutEntryRepository.rebind(context, target, uri.toString()) }
        } else {
            naming = PendingName(uri.toString(), ShortcutEntryRepository.folderNameOf(uri.toString()))
        }
    }

    // v3.1.0：这张卡要参与「一摞」—— 收起时整张卡不组合，只靠标题画那一条边，
    // 所以标题从 StackModule 挪到 DeckCard 上
    val card = DeckCard(title = "小抄入口") {
        Column(Modifier.padding(14.dp)) {
            Text(
                "每个入口绑定一个文件夹，点进去只看这个目录里的小抄",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (entries.isEmpty()) GlassColors.TextMuted else GlassColors.TextMuted
            )
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                // 空态：说清下一步该做什么，而不是给一片空白
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassColors.Accent.copy(alpha = 0.06f))
                        .padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = GlassColors.TextMuted,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "还没有小抄入口",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassColors.Text
                        )
                        Text(
                            "点下面的「添加小抄入口」选一个文件夹",
                            fontSize = 11.sp,
                            color = GlassColors.TextMuted
                        )
                    }
                }
            } else {
                entries.chunked(2).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { entry ->
                            EntryTile(
                                entry = entry,
                                active = entry.id == activeId,
                                readable = remember(entry.treeUri, entries) {
                                    entryReadable(context, entry)
                                },
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    // 点入口 = 把全局本地小抄切到它这一层，再展开详情。
                                    // 顺序不能反：先切再开，详情页读到的才是这个目录。
                                    runCatching { ShortcutEntryRepository.activate(context, entry) }
                                    activeId = entry.id
                                    detail = entry
                                    onOpen(entry)
                                },
                                onManage = { managing = entry }
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 添加：先选目录，再确认名字
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassColors.Accent.copy(alpha = 0.10f))
                    .clickable { pickFor = null; picker.launch(null) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = GlassColors.Accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "添加小抄入口",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Accent
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "长按入口可重命名、换目录、排序或删除；排序即上移/下移",
                fontSize = 10.sp,
                color = GlassColors.TextMuted
            )
        }
    }

    // 新增：确认名字
    naming?.let { pending ->
        NameDialog(
            title = "给小抄入口起个名字",
            initial = pending.suggested,
            onDismiss = { naming = null },
            onConfirm = { name ->
                naming = null
                scope.launch {
                    ShortcutEntryRepository.add(context, name, pending.treeUri)
                }
            }
        )
    }

    // 重命名
    renaming?.let { entry ->
        NameDialog(
            title = "重命名入口",
            initial = entry.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch { ShortcutEntryRepository.rename(context, entry.id, name) }
            }
        )
    }

    // 长按菜单：重命名 / 换目录 / 排序 / 停用 / 删除
    managing?.let { entry ->
        val current = entries.firstOrNull { it.id == entry.id } ?: entry
        AlertDialog(
            onDismissRequest = { managing = null },
            title = { Text(current.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        ShortcutEntryRepository.folderNameOf(current.treeUri),
                        fontSize = 12.sp,
                        color = GlassColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    ManageRow("重命名") { managing = null; renaming = current }
                    ManageRow("重新选择目录") {
                        managing = null
                        pickFor = current.id
                        picker.launch(null)
                    }
                    ManageRow("上移") {
                        scope.launch { ShortcutEntryRepository.move(context, current.id, -1) }
                        managing = null
                    }
                    ManageRow("下移") {
                        scope.launch { ShortcutEntryRepository.move(context, current.id, 1) }
                        managing = null
                    }
                    ManageRow(if (current.enabled) "停用（首页置灰）" else "启用") {
                        scope.launch {
                            ShortcutEntryRepository.setEnabled(context, current.id, !current.enabled)
                        }
                        managing = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managing = null }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = { managing = null; deleting = current }) {
                    Text("删除", color = GlassColors.Danger)
                }
            }
        )
    }

    // 删除二次确认
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${entry.name}」？") },
            text = {
                Text(
                    "只会删掉这个入口，手机里的文件一个都不会动。下次要用可以重新添加。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { ShortcutEntryRepository.remove(context, entry.id) }
                }) { Text("删除", color = GlassColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }

    detail?.let { entry -> ShortcutDetailDialog(entry) { detail = null } }

    return card
}

private data class PendingName(val treeUri: String, val suggested: String)

/** 入口详情：全屏 Dialog，盖住首页即可，不用改宿主的页面栈 */
@Composable
private fun ShortcutDetailDialog(entry: ShortcutEntryEntity, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(GlassColors.Bg)
        ) {
            ShortcutDetailScreen(entry = entry, onBack = onClose)
        }
    }
}

/** 一颗入口按钮 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryTile(
    entry: ShortcutEntryEntity,
    active: Boolean,
    readable: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onManage: () -> Unit
) {
    val accent = if (entry.color != 0) Color(entry.color) else GlassColors.Accent
    val dim = !readable || !entry.enabled
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) accent.copy(alpha = 0.14f) else GlassColors.Text.copy(alpha = 0.04f)
            )
            .border(
                width = if (active) 1.dp else 0.5.dp,
                color = if (active) accent.copy(alpha = 0.55f) else GlassColors.Divider,
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = { if (entry.enabled) onClick() else Unit },
                onLongClick = onManage
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (dim) 0.18f else 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.name.take(1).ifBlank { "抄" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "管理",
                tint = GlassColors.TextMuted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onManage)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (dim) GlassColors.TextMuted else GlassColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                !readable -> "权限失效，点管理重新选择"
                !entry.enabled -> "已停用"
                else -> ShortcutEntryRepository.folderNameOf(entry.treeUri)
            },
            fontSize = 10.sp,
            color = when {
                !readable -> GlassColors.Danger
                else -> GlassColors.TextMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ManageRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 14.sp,
        color = if (danger) GlassColors.Danger else GlassColors.Text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24) },
                    singleLine = true,
                    placeholder = { Text("例如：龙宫小抄") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "默认取目录名，可以改成你认得出的说法",
                    fontSize = 11.sp,
                    color = GlassColors.TextMuted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim().ifBlank { initial }) },
                enabled = value.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ======================================================================
// 入口详情页
// ======================================================================

/**
 * 某个入口的详情：只列它绑定目录里的内容。
 *
 * 数据源是**这个入口自己的** [LocalGuideRepository] 实例（不是全局那一个），
 * 所以就算用户同时开着悬浮窗看别的入口，也不会互相把目录串掉。
 */
@Composable
fun ShortcutDetailScreen(entry: ShortcutEntryEntity, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(entry.id, entry.treeUri, entry.rootDocId) {
        LocalGuideRepository(context).also { it.setTree(entry.treeUri, entry.rootDocId) }
    }

    var categories by remember(entry.id) { mutableStateOf<List<PanFolder>>(emptyList()) }
    var selected by remember(entry.id) { mutableStateOf<PanFolder?>(null) }
    var content by remember(entry.id) { mutableStateOf<FolderContent?>(null) }
    var stack by remember(entry.id) { mutableStateOf<List<PanFolder>>(emptyList()) }
    var loading by remember(entry.id) { mutableStateOf(true) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<PanImage>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(0) }

    // 首次进入：读一级分类，默认落在第一个
    LaunchedEffect(entry.id, entry.treeUri, entry.rootDocId) {
        loading = true
        error = null
        runCatching { repo.categories() }
            .onSuccess { cats ->
                categories = cats
                val first = cats.firstOrNull()
                selected = first
                // 没有任何子文件夹时 categories() 会给一个指向根的虚拟分类；
                // 连它都没有，就是真的一张图都没有
                if (first == null) content = FolderContent(emptyList(), emptyList())
            }
            .onFailure { error = it.message ?: "读不到这个目录" }
        loading = false
    }

    fun openFolder(folder: PanFolder) {
        selected = folder
        loading = true
        error = null
    }

    // selected 变化后加载内容（点分类 / 进子目录 / 首次进入都走这条，避免几处各写一遍）
    LaunchedEffect(selected?.id) {
        val folder = selected ?: return@LaunchedEffect
        runCatching { repo.browse(folder.id) }
            .onSuccess { content = it; error = null }
            .onFailure { error = it.message ?: "读不到这个目录" }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = GlassColors.Text,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(8.dp)
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(ShortcutEntryRepository.folderNameOf(entry.treeUri))
                        stack.lastOrNull()?.let { append(" / ").append(it.name) }
                    },
                    fontSize = 10.sp,
                    color = GlassColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (stack.isNotEmpty()) {
                GlassPill("上一级", onClick = {
                    val next = stack.dropLast(1)
                    stack = next
                    selected = next.lastOrNull() ?: categories.firstOrNull()
                }, primary = false)
            }
        }

        // ---------- 分类条 ----------
        if (categories.isNotEmpty() && stack.isEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(categories, key = { it.id }) { folder ->
                    val on = folder.id == selected?.id
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (on) GlassColors.Accent else GlassColors.Text.copy(alpha = 0.06f)
                            )
                            .clickable {
                                stack = emptyList()
                                selected = folder
                                loading = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            folder.name,
                            fontSize = 11.sp,
                            color = if (on) Color.White else GlassColors.TextSub,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---------- 内容 ----------
        when {
            loading && content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GlassColors.Accent)
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("这个入口打不开", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GlassColors.Text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error.orEmpty(),
                        fontSize = 12.sp,
                        color = GlassColors.Danger,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "回到首页长按这个入口 → 重新选择目录",
                        fontSize = 11.sp,
                        color = GlassColors.TextMuted
                    )
                }
            }

            content == null || content!!.isEmpty -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "这个目录里还没有图片",
                    fontSize = 13.sp,
                    color = GlassColors.TextMuted
                )
            }

            else -> {
                val data = content!!
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(data.folders, key = { "f_" + it.id }) { folder ->
                        FolderCell(folder.name) {
                            stack = stack + folder
                            openFolder(folder)
                        }
                    }
                    gridItems(data.images, key = { "i_" + it.id }) { image ->
                        ImageCell(
                            image = image,
                            source = repo.thumbSource(image)
                        ) {
                            preview = data.images
                            previewIndex = data.images.indexOf(image).coerceAtLeast(0)
                        }
                    }
                }
            }
        }
    }

    // ---------- 全屏预览 ----------
    if (preview.isNotEmpty()) {
        val current = preview.getOrNull(previewIndex)
        if (current != null) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { preview = emptyList() }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.94f))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(repo.imageSource(current).url)
                            .build(),
                        contentDescription = current.name,
                        imageLoader = GuideImageLoader.of(context),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable { preview = emptyList() }
                                .padding(8.dp)
                        )
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (previewIndex > 0) {
                            GlassPill("上一张", onClick = { previewIndex-- }, primary = false)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${previewIndex + 1} / ${preview.size}",
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        Spacer(Modifier.width(10.dp))
                        if (previewIndex < preview.lastIndex) {
                            GlassPill("下一张", onClick = { previewIndex++ }, primary = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCell(name: String, onClick: () -> Unit) {
    Column(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassColors.Text.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = GlassColors.Accent,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            fontSize = 10.sp,
            color = GlassColors.TextSub,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ImageCell(
    image: PanImage,
    source: com.yunx.app.overlay.data.ImageSource,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(source.url) {
        ImageRequest.Builder(context)
            .data(source.url)
            .httpHeaders(source.headers.toNetworkHeaders())
            .build()
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassColors.Text.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = request,
            contentDescription = image.name,
            imageLoader = GuideImageLoader.of(context),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
