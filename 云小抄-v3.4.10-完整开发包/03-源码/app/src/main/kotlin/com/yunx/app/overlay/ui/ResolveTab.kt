/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.9 底部「解析」按钮对应的页面：
 * 粘贴分享链接 → 读取目录 → 勾选图片 → 直接下载进本地小抄。
 * 解析本身复用云析原版的 ShareResolveRepository，不重复造轮子。
 */

package com.yunx.app.overlay.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Size
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.data.PanFiles
import com.yunx.app.overlay.data.ShareImporter
import com.yunx.app.overlay.data.ShareLinkService
import kotlinx.coroutines.launch

/**
 * 「读取链接」页。
 *
 * 交互顺序刻意做成「先看清内容，再决定下什么」：
 * 链接解析出来先铺目录（文件夹可继续下钻），图片默认全选，
 * 用户取消掉不需要的再点「下载选中」——不会一点链接就开始偷偷下几百张。
 */
/**
 * 解析页的可变状态。
 *
 * 挂在 [HomeScreen] 上而不是页面内部：切到「下载」看一眼进度再切回来，
 * 链接、目录、勾选都还在，不用重新解析一遍。
 */
@Stable
class ResolveState {
    var link by mutableStateOf("")
    var pwd by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var opened by mutableStateOf<ShareLinkService.Opened?>(null)
    var title by mutableStateOf("")
    var files by mutableStateOf<List<ShareFile>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    var dirFid by mutableStateOf("")
    /** 面包屑：从分享根目录一路走下来的 (fid, 名称) */
    var trail by mutableStateOf<List<Pair<String, String>>>(emptyList())
}

@Composable
fun rememberResolveState(): ResolveState = remember { ResolveState() }

@Composable
fun ResolvePage(
    state: ResolveState = rememberResolveState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { OverlayPrefs(context) }
    val service = remember { ShareLinkService.of(context) }
    val importer = remember { ShareImporter.of(context) }
    val scope = rememberCoroutineScope()

    var link by state::link
    var pwd by state::pwd
    var loading by state::loading
    var error by state::error
    var opened by state::opened
    var title by state::title
    var files by state::files
    var selected by state::selected
    var dirFid by state::dirFid
    var trail by state::trail

    // v3.0.0：整夹下载的展开进度。放在页面内而不是 ResolveState 里 ——
    // 这是一次性动作，切页回来还挂着「正在展开」反而是错的。
    var expanding by remember { mutableStateOf(false) }
    var expandNote by remember { mutableStateOf("") }

    fun load(current: ShareLinkService.Opened, fid: String) {
        loading = true
        error = null
        scope.launch {
            service.list(current, fid).fold(
                onSuccess = { list ->
                    files = list
                    dirFid = fid
                    selected = list.filter { !it.isdir && PanFiles.isImage(it.fname) }
                        .map { it.fid }
                        .toSet()
                    loading = false
                },
                onFailure = { e ->
                    error = e.message ?: "读取目录失败"
                    loading = false
                }
            )
        }
    }

    fun startResolve() {
        val text = link.trim()
        if (text.isEmpty()) {
            Toast.makeText(context, "先粘贴一条分享链接", Toast.LENGTH_SHORT).show()
            return
        }
        loading = true
        error = null
        files = emptyList()
        opened = null
        scope.launch {
            service.open(text, pwd.trim().ifBlank { null }).fold(
                onSuccess = { o ->
                    opened = o
                    title = o.session.title
                    trail = emptyList()
                    load(o, service.rootFid(o.platform))
                },
                onFailure = { e ->
                    error = e.message ?: "解析失败"
                    loading = false
                }
            )
        }
    }

    // v2.3.0：StaggerColumn 让每个卡片自己浮上来；普通 Column 会被当成单个子项
    StaggerColumn(modifier = modifier) {
        GlassSectionTitle("读取链接")
        Spacer(Modifier.height(2.dp))
        GlassCard {
            GlassTextField(
                value = link,
                onValueChange = { link = it },
                placeholder = "粘贴分享链接或口令",
                leading = Icons.Rounded.Link
            )
            Spacer(Modifier.height(8.dp))
            GlassTextField(
                value = pwd,
                onValueChange = { pwd = it },
                placeholder = "提取码（可选，链接里带了就不用填）"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(text = "从剪贴板粘贴", primary = false, onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.isBlank()) {
                        Toast.makeText(context, "剪贴板是空的", Toast.LENGTH_SHORT).show()
                    } else {
                        link = text.trim()
                    }
                })
                GlassPill(
                    text = if (loading) "解析中…" else "开始解析",
                    primary = true,
                    enabled = !loading,
                    onClick = { startResolve() }
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            GlassHint(it, color = GlassColors.Danger)
        }

        val current = opened
        if (current != null) {
            Spacer(Modifier.height(14.dp))
            GlassSectionTitle(if (title.isBlank()) "分享内容" else title)

            // 面包屑 + 返回上级
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回上级",
                    tint = if (trail.isEmpty()) GlassColors.TextMuted else GlassColors.Accent,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(enabled = trail.isNotEmpty()) {
                            val parent = trail.last()
                            trail = trail.dropLast(1)
                            load(current, parent.first)
                        }
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        append("根目录")
                        trail.forEach { append(" / "); append(it.second) }
                    },
                    fontSize = 11.sp,
                    color = GlassColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (current.guest) "游客读取" else current.platform.displayName,
                    fontSize = 10.sp,
                    color = if (current.guest) GlassColors.Warn else GlassColors.Ok
                )
            }

            // v3.0.0：目录里条目多的时候，一个个点太慢。全选本层会把文件夹一起勾上，
            // 配合「整夹下载」等于一次把这一层所有东西都下完。
            val selectable = files.take(MAX_ROWS).filter { it.isdir || PanFiles.isImage(it.fname) }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassPill(
                    text = "全选本层",
                    primary = false,
                    enabled = selectable.isNotEmpty() && selectable.any { it.fid !in selected },
                    onClick = { selected = selected + selectable.map { it.fid } }
                )
                GlassPill(
                    text = "取消本层",
                    primary = false,
                    enabled = selectable.any { it.fid in selected },
                    onClick = { selected = selected - selectable.map { it.fid }.toSet() }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${selectable.size} 项 · 已选 ${selectable.count { it.fid in selected }}",
                    fontSize = 10.sp,
                    color = GlassColors.TextMuted
                )
            }

            GlassCard {
                if (files.isEmpty() && !loading) {
                    Text(
                        "这个目录是空的",
                        fontSize = 12.sp,
                        color = GlassColors.TextSub,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                }
                val shown = files.take(MAX_ROWS)
                shown.forEachIndexed { index, file ->
                    if (index > 0) GlassDivider()
                    ShareFileRow(
                        file = file,
                        selected = file.fid in selected,
                        showThumb = index < THUMB_ROWS,
                        onToggle = {
                            selected = if (file.fid in selected) selected - file.fid else selected + file.fid
                        },
                        onToggleFolder = {
                            selected = if (file.fid in selected) selected - file.fid else selected + file.fid
                        },
                        onOpenFolder = {
                            trail = trail + (dirFid to file.fname)
                            load(current, file.fid)
                        }
                    )
                }
                if (files.size > shown.size) {
                    GlassHint("目录里还有 ${files.size - shown.size} 项没显示，先进子文件夹或分批下载。")
                }
            }

            val picked = files.filter { it.fid in selected && !it.isdir }
            val pickedDirs = files.filter { it.fid in selected && it.isdir }
            val anything = picked.isNotEmpty() || pickedDirs.isNotEmpty()
            if (pickedDirs.isNotEmpty()) {
                // 勾了文件夹但没勾图片时，按钮文案容易让人以为「没选中东西」，这里补一句
                GlassHint(
                    "已勾选 ${pickedDirs.size} 个文件夹：点下面的下载会把它们连同子文件夹里的图片一次下完。",
                    color = GlassColors.Accent2
                )
            }
            Spacer(Modifier.height(10.dp))
            GlassPrimaryButton(
                text = when {
                    expanding -> "正在展开文件夹…"
                    pickedDirs.isEmpty() && picked.isEmpty() -> "下载选中图片到本地"
                    pickedDirs.isEmpty() -> "下载选中 ${picked.size} 张到本地"
                    picked.isEmpty() -> "下载 ${pickedDirs.size} 个文件夹到本地"
                    else -> "下载 ${picked.size} 张 + ${pickedDirs.size} 个文件夹"
                },
                subtitle = when {
                    // 展开时把「已经找到多少张」实时写出来，几百张的目录不至于像卡死
                    expanding -> expandNote.ifBlank { "正在读取文件夹…" }
                    pickedDirs.isNotEmpty() -> "文件夹会整夹递归展开，里面的图片一次下完；登录网盘后不受游客单文件大小限制"
                    prefs.imageVaultEnabled -> "存进「我的 → 小抄本地化」选定的目录，下载完网格直接读本地文件"
                    else -> "下载会自动打开图片本地化，存进默认目录"
                },
                icon = Icons.Rounded.Download,
                enabled = anything && !loading && !expanding,
                onClick = {
                    // ★ v3.0.0：不再拿「图片本地化没开」把人挡在门外。
                    //   登录了网盘账号就该能下 —— 没开本地化就按 startDownloadAll 的老做法
                    //   自动打开，落到默认目录，而不是弹一句「先去我的打开」再让人跑一趟。
                    if (!prefs.imageVaultEnabled) prefs.imageVaultEnabled = true

                    val baseTrail = trail.map { it.second }
                    if (pickedDirs.isEmpty()) {
                        importer.import(current, picked, baseTrail)
                        Toast.makeText(context, "已开始下载 ${picked.size} 张，进度见「下载」", Toast.LENGTH_SHORT).show()
                        return@GlassPrimaryButton
                    }

                    expanding = true
                    expandNote = "正在读取文件夹…"
                    scope.launch {
                        val collected = mutableListOf<TaggedFile>()
                        var hitLimit = false
                        for (dir in pickedDirs) {
                            if (collected.size >= MAX_FOLDER_IMAGES) {
                                hitLimit = true
                                break
                            }
                            collectFolderImages(
                                service = service,
                                opened = current,
                                dir = dir,
                                relDir = baseTrail + dir.fname
                            ) { name, count ->
                                expandNote = "正在展开「$name」· 已找到 $count 张"
                                if (count >= MAX_FOLDER_IMAGES) hitLimit = true
                            }.also { collected += it }
                        }
                        val all = picked.map { TaggedFile(it, baseTrail) } + collected
                        expanding = false
                        expandNote = ""
                        if (all.isEmpty()) {
                            Toast.makeText(context, "选中的文件夹里没有图片", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        // 每张图带着自己的相对目录下去，本地才能复刻出网盘里的层级
                        importer.import(
                            opened = current,
                            files = all.map { it.file },
                            relDir = baseTrail,
                            relDirByFid = all.associate { it.file.fid to it.relDir }
                        )
                        Toast.makeText(
                            context,
                            if (hitLimit) {
                                "文件夹太大，先下前 ${all.size} 张（上限 $MAX_FOLDER_IMAGES）"
                            } else {
                                "已开始下载 ${all.size} 张，进度见「下载」"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        Spacer(Modifier.height(14.dp))
        GlassSectionTitle("说明")
        Spacer(Modifier.height(2.dp))
        GlassCard {
            GlassInfoRow("支持平台", "夸克 / UC / 迅雷（前两家可游客读取）")
            GlassDivider()
            GlassInfoRow("下载位置", "与「小抄本地化」同一目录")
            GlassDivider()
            // v3.0.0：整夹下载的行为边界写在明面上，免得一次勾了相册级的目录没心理准备
            GlassInfoRow("整夹下载", "勾选文件夹即整夹递归展开，一次最多 $MAX_FOLDER_IMAGES 张")
            GlassDivider()
            GlassInfoRow("其它网盘", "百度 / 123 / 115 等请用云析完整解析器")
        }
        Spacer(Modifier.height(10.dp))
        GlassPrimaryButton(
            text = "用云析完整解析器打开",
            subtitle = "",
            icon = Icons.Rounded.OpenInNew,
            onClick = { YunxBridge.openResolve(context, link.trim().ifBlank { null }) }
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShareFileRow(
    file: ShareFile,
    selected: Boolean,
    showThumb: Boolean,
    onToggle: () -> Unit,
    onToggleFolder: () -> Unit,
    onOpenFolder: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (file.isdir) onOpenFolder() else onToggle() }
            // ★ v3.0.0：补上左右内边距。
            //   原来整行只有 vertical padding，缩略图直接压在卡片左边缘上 ——
            //   卡片圆角（18dp 左右）会把 38dp 的缩略图切掉一块，右边勾选框也
            //   被圆角削掉半边，看着就是「有一小部分被遮住了、没显现出来」。
            //   14 + 38 + 8 = 60dp，正好对上 GlassDivider 的 start = 60.dp，分隔线
            //   和文件名的起点能连成一条竖线。
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassColors.Divider)
                .border(1.dp, GlassColors.Stroke, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val thumb = file.thumbnail?.url
            if (showThumb && !file.isdir && !thumb.isNullOrBlank()) {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumb)
                        .httpHeaders(file.thumbnail?.headers.orEmpty().toNetworkHeaders())
                        .size(Size(160, 160))
                        .build(),
                    contentDescription = file.fname,
                    imageLoader = GuideImageLoader.of(context),
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(
                    if (file.isdir) Icons.Rounded.Folder else Icons.Rounded.Image,
                    contentDescription = null,
                    tint = if (file.isdir) GlassColors.Accent2 else GlassColors.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.fname,
                fontSize = 12.sp,
                color = GlassColors.Text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    // v3.0.0：文件夹也能整夹下载了，副标题把这件事写出来
                    file.isdir -> if (selected) "文件夹 · 已选整夹" else "文件夹 · 勾选可整夹下载"
                    file.fsize > 0 -> formatBytes(file.fsize)
                    else -> "图片"
                },
                fontSize = 10.sp,
                color = if (file.isdir && selected) GlassColors.Accent2 else GlassColors.TextMuted
            )
        }
        if (file.isdir) {
            // v3.0.0：文件夹不再只有「点进去」一条路 —— 勾选框选中整夹，
            // 后面点一次下载就能把里面所有图片递归下完。
            // 点击热区靠 padding 撑到 28×30dp，比 16dp 的小方块好点。
            GlassCheck(
                checked = selected,
                onCheckedChange = { onToggleFolder() },
                text = "",
                modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = GlassColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        } else {
            GlassCheck(
                checked = selected,
                onCheckedChange = { onToggle() },
                text = "",
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1024f / 1024f)
}

/** 一张图 + 它在分享里的相对目录（整夹展开时逐张带着走） */
private data class TaggedFile(val file: ShareFile, val relDir: List<String>)

/**
 * v3.0.0：把一个文件夹逐层读下去，收集里面所有图片。
 *
 * 为什么串行：网盘对同一个分享的并发读取有限流，同时炸十个 list 请求大概率被拒，
 * 串行反而更快，而且只有串行才能在展开过程中给出真实的「已找到 N 张」。
 * 单个子目录读失败就跳过它 —— 一个坏目录不该让整次下载报废。
 *
 * 触到 [MAX_FOLDER_IMAGES] 或 [MAX_FOLDER_DEPTH] 就停，避免一条分享把手机拖死。
 */
private suspend fun collectFolderImages(
    service: ShareLinkService,
    opened: ShareLinkService.Opened,
    dir: ShareFile,
    relDir: List<String>,
    depth: Int = 0,
    onProgress: (String, Int) -> Unit
): List<TaggedFile> {
    val out = mutableListOf<TaggedFile>()
    if (depth > MAX_FOLDER_DEPTH) return out
    onProgress(dir.fname, 0)
    val children = service.list(opened, dir.fid).getOrNull() ?: return out
    for (child in children) {
        if (out.size >= MAX_FOLDER_IMAGES) break
        if (child.isdir) {
            if (depth + 1 <= MAX_FOLDER_DEPTH) {
                out += collectFolderImages(service, opened, child, relDir + child.fname, depth + 1, onProgress)
            }
        } else if (PanFiles.isImage(child.fname)) {
            out += TaggedFile(child, relDir)
        }
    }
    onProgress(dir.fname, out.size)
    return out
}

/** 打开云析原版界面（解析 / 下载）：云小抄只做入口，复杂格式交给原版 */
object YunxBridge {

    /** 官方云析（与本包 com.yunx.app.guide 可共存） */
    private const val OFFICIAL_PKG = "com.yunx.app"

    /** 完整解析器入口类名。官方包与二次开发包都叫这个 */
    private const val PARSER_ACT = "com.yunx.app.MainActivity"
    private const val EXTRA_TAB = "com.yunx.app.extra.OPEN_TAB"

    fun openResolve(context: Context, text: String? = null) = open(context, text, "resolve")

    fun openDownload(context: Context) = open(context, null, "download")

    /**
     * v2.5.4：先找官方云析，没装就退回本包内置的同一套解析器。
     *
     * 旧实现写的是 setClassName(context.packageName, "com.yunx.app.MainActivity")，
     * 只在「完整解析器恰好和当前包同名」时成立；一旦装的是精简包 / 改过入口的包，
     * 用户看到的就是那句没头没尾的「打不开云析解析器：android.content.ActivityN…」。
     * 现在逐个 ComponentName 先问 PackageManager 存不存在，再决定跳谁；
     * 都不存在时给人话，不把异常原文弹到屏幕上。
     *
     * 另外补 FLAG_ACTIVITY_NEW_TASK：从 Service / Application 上下文启动 Activity
     * 不带这个 flag 会直接抛 AndroidRuntimeException。
     */
    private fun open(context: Context, text: String?, tab: String) {
        val pm = context.packageManager
        val candidates = buildList {
            if (context.packageName != OFFICIAL_PKG) {
                add(ComponentName(OFFICIAL_PKG, PARSER_ACT))
            }
            add(ComponentName(context.packageName, PARSER_ACT))
        }
        val target = candidates.firstOrNull { runCatching { pm.getActivityInfo(it, 0) }.isSuccess }
        if (target == null) {
            Toast.makeText(
                context,
                "这台设备上找不到完整解析器，重装一次云小抄即可恢复",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent().apply {
            component = target
            if (!text.isNullOrBlank()) {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            } else {
                putExtra(EXTRA_TAB, tab)
            }
        }
        // ★ v2.5.5：无条件 NEW_TASK —— 解析器（云析完整界面）必须待在自己的任务栈里
        //   （manifest 已给它独立 taskAffinity）。带这个 flag 后，从主页 / Service 跳过去
        //   都不会把云析界面压进云小抄的任务，点桌面图标永远回小抄。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "打不开完整解析器，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        // 装的是官方云析时不需要解释；退到内置界面时说明一句，免得以为「怎么跳去云析了」
        if (target.packageName == context.packageName && candidates.size > 1) {
            Toast.makeText(context, "未安装云析原版，已打开内置完整解析器", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 列表里最多铺这么多行：外层是 verticalScroll，一次渲染几百行会卡 */
private const val MAX_ROWS = 200

/** 只有前这么多行去取缩略图：200 张同时发请求会把首屏拖慢 */
private const val THUMB_ROWS = 40

/** 整夹展开最多收这么多张：再多就不是「一份攻略」了，分批下更稳 */
private const val MAX_FOLDER_IMAGES = 1500

/** 分享目录一般就两三层，超过这个深度多半是链接里有环，及时打住 */
private const val MAX_FOLDER_DEPTH = 8

// ======================================================================
// v3.0.2：「解析下载」合并页
// ======================================================================

/**
 * 解析 + 下载合成一页。
 *
 * 上面一排切换，下面两栏之一：
 * - 解析：粘链接 → 选目录 → 下载，内容自己滚；
 * - 下载：云析原版 [DownloadPage]，内部是 LazyColumn，必须拿到**有界高度**，
 *   所以这一层不能套整页 verticalScroll —— 只有解析那一栏自己包滚动。
 *
 * 没有把两栏上下叠着铺：下载栏逐帧跟着任务表刷新，铺在下面时一有任务
 * 整页都在跳，滑到一半会被拽回去。
 */
@Composable
fun ResolveDownloadPane(
    state: ResolveState,
    pane: Int,
    onPaneChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ResolvePaneTab("解析", Icons.Rounded.Link, pane == 0) { onPaneChange(0) }
            ResolvePaneTab("下载", Icons.Rounded.Download, pane == 1) { onPaneChange(1) }
        }
        if (pane == 0) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ResolvePage(state)
                Spacer(Modifier.height(40.dp))
            }
        } else {
            Box(Modifier.weight(1f)) { DownloadPage() }
        }
    }
}

@Composable
private fun RowScope.ResolvePaneTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .weight(1f)
            .clip(shape)
            .background(
                if (active) t.accent.copy(alpha = 0.16f) else t.panelTop.copy(alpha = 0.5f)
            )
            .border(
                1.dp,
                if (active) t.accent.copy(alpha = 0.5f) else t.stroke.copy(alpha = 0.5f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (active) t.accent else t.textSub
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) t.accent else t.textSub
        )
    }
}
