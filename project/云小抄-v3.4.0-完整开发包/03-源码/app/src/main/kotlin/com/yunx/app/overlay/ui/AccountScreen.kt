/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.0 底部「我的」按钮对应的页面（登录 / 个人中心）。
 *
 * 设计约束（用户明确要求「账号跟密码随便输入」）：
 *   - 不做格式校验、不判断密码、不联网、不请求任何服务器；
 *   - 密码只留在 Compose 的 remember 内存里，退出页面即消失，绝不落盘；
 *   - 登录后只在本机保存昵称 / 时间 / 标志，用来让个人中心有内容可显示。
 */

package com.yunx.app.overlay.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import com.yunx.app.R
import com.yunx.app.overlay.data.GuideRepo
import com.yunx.app.overlay.data.ImageVault
import com.yunx.app.overlay.data.MirrorSource
import com.yunx.app.overlay.data.MirrorStore
import com.yunx.app.overlay.data.MirrorSync
import com.yunx.app.overlay.data.ShareLinkService
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.overlay.data.UserAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AccountPage(
    onOpenProtocol: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenNotes: () -> Unit,
    /** v3.0.1：抽屉二级页。0 账号与同步 / 1 主题设置 / 2 协议与关于 */
    pane: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val account = remember { UserAccount(context) }
    val prefs = remember { OverlayPrefs(context) }

    var logged by remember { mutableStateOf(account.loggedIn) }
    var lowPerf by remember { mutableStateOf(prefs.lowPerfMode) }
    var highPerf by remember { mutableStateOf(prefs.highPerfMode) }

    // v1.9.8 小抄本地化
    val vault = remember { GuideRepo.vault(context) }
    val downloader = remember { GuideRepo.downloader(context) }
    val repo = remember { GuideRepo.repository(context) }
    val downloadStats by downloader.stats.collectAsState()
    var vaultOn by remember { mutableStateOf(prefs.imageVaultEnabled) }
    // v2.0.4：本地已复刻多少个网盘目录。读的是落盘的 guide_folders.json，走 IO 线程
    var folderCount by remember { mutableStateOf(0) }
    var compress by remember { mutableStateOf(prefs.imageCompress) }
    // v2.0.0：无损打包（.yxz）。低性能模式强制关，开关同时置灰
    var pack by remember { mutableStateOf(prefs.imagePack) }
    var preset by remember { mutableStateOf(prefs.imageQuality to prefs.imageMaxEdge) }
    var wifiOnly by remember { mutableStateOf(prefs.imageWifiOnly) }
    var autoCheck by remember { mutableStateOf(prefs.imageAutoCheck) }
    // v1.9.10 内容复用 + 空间上限
    var dedup by remember { mutableStateOf(prefs.imageDedupEnabled) }
    var maxBytes by remember { mutableStateOf(prefs.imageMaxBytes) }
    var showSpace by remember { mutableStateOf(false) }
    var spaceMsg by remember { mutableStateOf<String?>(null) }
    val uiScope = rememberCoroutineScope()
    // v2.1.0：这两项原来在 composition 里同步读盘（索引 JSON + SAF 目录名），
    // 一进「我的」页会卡一下 —— 改成先给空值、后台读完再填。
    var dirLabel by remember { mutableStateOf("") }
    var showDonate by remember { mutableStateOf(false) }
    // v2.1.0：本地化 / 性能 / 云端同步十来个开关默认折起来。
    // 「我的」页一进来应该是外观 + 动画 + 账号，而不是一屏开关海。
    var expandStorage by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(ImageVault.Usage(0, 0, 0L, 0L)) }
    LaunchedEffect(Unit) {
        dirLabel = withContext(Dispatchers.IO) { vault.dirLabel() }
        usage = withContext(Dispatchers.IO) { vault.usage() }
    }
    LaunchedEffect(downloadStats.done, downloadStats.failed) {
        usage = withContext(Dispatchers.IO) { vault.usage() }
    }
    LaunchedEffect(vaultOn) {
        folderCount = withContext(Dispatchers.IO) { repo.knownFolderCount() }
    }

    // v2.0.1 镜像同步：作者分享 → 我的小抄 → 本地
    val mirrorStore = remember { MirrorStore.of(context) }
    val mirrorSync = remember { MirrorSync.of(context) }
    var mirrorSources by remember { mutableStateOf(mirrorStore.all()) }
    var showMirror by remember { mutableStateOf(false) }
    var mirrorBusy by remember { mutableStateOf(false) }
    var mirrorMsg by remember { mutableStateOf<String?>(null) }
    var mirrorReplace by remember { mutableStateOf(prefs.mirrorReplace) }
    var mirrorAuto by remember { mutableStateOf(prefs.mirrorAuto) }
    var mirrorInterval by remember { mutableStateOf(prefs.mirrorIntervalHours) }
    val mirrorProgress by mirrorSync.progress.collectAsState()

    // 自选存储目录：SAF 授权后拿「持久读写」，重启 / 杀进程都不会失效
    val pickImageDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        vault.setTreeUri(uri.toString())
        // v2.1.0：SAF 取显示名要走一次跨进程查询，放 IO 线程，别卡在点击回调里
        uiScope.launch {
            val label = withContext(Dispatchers.IO) { vault.dirLabel() }
            dirLabel = label
            Toast.makeText(context, "图片将保存到：$label", Toast.LENGTH_SHORT).show()
        }
    }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(account.agreedProtocol) }

    // 滚动由主页统一提供，这里只负责内容，避免嵌套两个 verticalScroll 抢手势。
    // v2.3.0：换成 StaggerColumn —— 沿用小抄页那种「一块块浮上来」，
    // 顶层还是普通 Column 的话主页只数得到一个子项，整页一起动。
    StaggerColumn(modifier = modifier.fillMaxWidth(), playKey = pane) {
        // v3.0.1：「外观与动画」整页撤掉 —— 日夜 / 界面风格 / 玻璃质感在主题设置
        // 的「颜色」分类里，动效在「交互手势」分类里，自定义开屏也搬过去了。
        // 两个入口指同一堆开关，只会让人怀疑「改哪个才算数」。
        // v2.11.0：主题设置单独一页 —— 颜色、背景三根滑杆、图标与文字。
        // 用户在截图里看到的那套「主题设置」，能落地的部分都收在这。
        if (pane == 1) {
            ThemeStudioPage()
        }

        if (!logged && pane == 0) {
            // --------------------------------------------------------------
            // 未登录：登录表单
            // --------------------------------------------------------------
            GlassSectionTitle("登录 / 个人中心")
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(GlassColors.Accent, GlassColors.Accent2)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "抄",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "欢迎使用云小抄",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlassColors.Text
                            )
                            // v2.1.0：「账号密码随便输入即可…」那行删掉 ——
                            // 下面两个输入框的占位符已经写了「随便填，留空也行」
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    GlassTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "账号 / 昵称（随便填，留空也行）",
                        leading = Icons.Rounded.Person
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "密码（随便填，不会保存）",
                        leading = Icons.Rounded.Lock,
                        password = true
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassCheck(
                            checked = agreed,
                            onCheckedChange = {
                                agreed = it
                                account.agreedProtocol = it
                            },
                            text = "我已阅读并同意用户协议与隐私说明",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "查看 ›",
                            fontSize = 10.sp,
                            color = GlassColors.Accent,
                            modifier = Modifier.clickable(onClick = onOpenProtocol)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    GlassPrimaryButton(
                        text = "登录",
                        subtitle = "",
                        icon = Icons.Rounded.Person,
                        onClick = {
                            account.login(name)
                            logged = true
                            password = ""
                            Toast.makeText(
                                context,
                                "欢迎回来，${account.username}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        } else if (pane == 0) {
            // --------------------------------------------------------------
            // 已登录：个人中心
            // --------------------------------------------------------------
            GlassSectionTitle("个人中心")
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(GlassColors.Accent, GlassColors.Accent2)
                                    )
                                )
                                .border(1.dp, Color(0x55FFFFFF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                account.username.take(1).uppercase(),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                account.username,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlassColors.Text
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                account.userId,
                                fontSize = 10.sp,
                                color = GlassColors.TextSub
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "登录时间 ${formatTime(account.loginAt)}",
                                fontSize = 10.sp,
                                color = GlassColors.TextMuted
                            )
                        }
                        GlassPill(
                            "已登录",
                            onClick = { },
                            primary = false
                        )
                    }
                    // v2.1.0：退出登录从独立分组并进来 —— 它只属于「个人中心」，
                    // 单开一个标题 + 一个通栏按钮太占地方
                    GlassDivider()
                    GlassRow(
                        title = "退出登录",
                        subtitle = "",
                        icon = Icons.Rounded.Refresh,
                        iconTint = GlassColors.Danger,
                        onClick = {
                            account.logout()
                            logged = false
                            name = ""
                            password = ""
                            Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("退出 ›", fontSize = 10.sp, color = GlassColors.Danger)
                    }
                }
            }

            // v2.1.0：删掉「本机概览」。六行状态（连招数 / 来源 / 两个窗口 / 两个网盘）
            // 全是别处已经能看到的信息，放在设置页只增加滚动距离。

            // v2.1.0：删掉「快捷入口」。小抄 / 连招 / 协议在抽屉里都有一级入口，
            // 在设置页再列一遍是重复导航。

            // v2.1.0：「性能」并进「存储与下载」，并且整块默认折叠。
            // 这里只留一行入口，点开才展开十几项开关 —— 默认视图不再被撑长。
            GlassSectionTitle("存储与同步")
            GlassCard {
                GlassRow(
                    title = "本地化 · 性能 · 云端",
                    // 折叠起来也要有一句「里面有什么」—— 动态数字不算说明文字
                    subtitle = if (usage.count > 0) {
                        "已本地化 ${usage.count} 张 · ${formatSize(usage.bytes)}"
                    } else {
                        ""
                    },
                    icon = Icons.Rounded.Folder,
                    onClick = { expandStorage = !expandStorage }
                ) {
                    Icon(
                        imageVector = if (expandStorage) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Rounded.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = GlassColors.TextSub
                    )
                }
            }
            if (expandStorage) {
            // 展开后第一张卡紧贴入口卡，中间留一点缝，看起来才是「打开了一层」
            Spacer(Modifier.height(10.dp))
            GlassCard {
                GlassRow(
                    title = "高性能读取",
                    subtitle = "",
                    icon = Icons.Rounded.Bolt,
                    onClick = {
                        val next = !highPerf
                        highPerf = next
                        prefs.highPerfMode = next
                    }
                ) {
                    GlassSwitch(
                        checked = highPerf,
                        onCheckedChange = {
                            highPerf = it
                            prefs.highPerfMode = it
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "低性能模式",
                    subtitle = "",
                    icon = Icons.Rounded.Bolt,
                    onClick = {
                        val next = !lowPerf
                        lowPerf = next
                        prefs.lowPerfMode = next
                    }
                ) {
                    GlassSwitch(
                        checked = lowPerf,
                        onCheckedChange = {
                            lowPerf = it
                            prefs.lowPerfMode = it
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "图片本地化",
                    subtitle = "",
                    icon = Icons.Rounded.Download,
                    onClick = {
                        val next = !vaultOn
                        vaultOn = next
                        prefs.imageVaultEnabled = next
                        if (next) Toast.makeText(context, "已开启，进入分区即开始下载", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    GlassSwitch(
                        checked = vaultOn,
                        onCheckedChange = {
                            vaultOn = it
                            prefs.imageVaultEnabled = it
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "存储目录",
                    subtitle = dirLabel,
                    icon = Icons.Rounded.Folder,
                    onClick = { pickImageDir.launch(null) }
                ) {
                    Text("选择 ›", fontSize = 10.sp, color = GlassColors.Accent)
                }
                GlassDivider()
                GlassRow(
                    title = "压缩存储",
                    subtitle = "",
                    icon = Icons.Rounded.Bolt,
                    onClick = {
                        val next = !compress
                        compress = next
                        prefs.imageCompress = next
                    }
                ) {
                    GlassSwitch(
                        checked = compress,
                        onCheckedChange = {
                            compress = it
                            prefs.imageCompress = it
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "压缩强度",
                    subtitle = compressLabel(preset.first, preset.second),
                    icon = Icons.Rounded.Bolt,
                    onClick = {
                        val next = nextPreset(prefs.imageQuality, prefs.imageMaxEdge)
                        prefs.imageQuality = next.first
                        prefs.imageMaxEdge = next.second
                        // 触发重组，让副标题立刻变
                        preset = next
                    }
                ) {
                    Text("切换 ›", fontSize = 10.sp, color = GlassColors.Accent)
                }
                GlassDivider()
                GlassRow(
                    title = "无损打包压缩",
                    subtitle = "",
                    icon = Icons.Rounded.Bolt,
                    onClick = {
                        if (!lowPerf) {
                            pack = !pack
                            prefs.imagePack = pack
                        }
                    }
                ) {
                    GlassSwitch(
                        checked = pack && !lowPerf,
                        enabled = !lowPerf,
                        onCheckedChange = {
                            if (!lowPerf) {
                                pack = it
                                prefs.imagePack = it
                            }
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "仅 WiFi 自动下载",
                    subtitle = "",
                    icon = Icons.Rounded.Bolt,
                    onClick = {
                        val next = !wifiOnly
                        wifiOnly = next
                        prefs.imageWifiOnly = next
                    }
                ) {
                    GlassSwitch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            prefs.imageWifiOnly = it
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "自动检测更新",
                    subtitle = "",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        val next = !autoCheck
                        autoCheck = next
                        prefs.imageAutoCheck = next
                    }
                ) {
                    GlassSwitch(
                        checked = autoCheck,
                        onCheckedChange = {
                            autoCheck = it
                            prefs.imageAutoCheck = it
                        }
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "换链接不重下",
                    subtitle = "",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        val next = !dedup
                        dedup = next
                        prefs.imageDedupEnabled = next
                        usage = vault.usage()
                    }
                ) {
                    GlassSwitch(
                        checked = dedup,
                        onCheckedChange = {
                            dedup = it
                            prefs.imageDedupEnabled = it
                            usage = vault.usage()
                        }
                    )
                }
            }

            // v2.1.0：六行压到三行。占用与复用省下的空间并进「已本地化」，
            // 「上次检测」交给自动检测那一栏表达 —— 设置页不该变成仪表盘。
            GlassCard {
                GlassInfoRow(
                    "已本地化",
                    "${usage.count} 张 · ${formatSize(usage.bytes)}" +
                        if (usage.savedBytes > 0L) " · 复用省 ${formatSize(usage.savedBytes)}" else ""
                )
                GlassDivider()
                GlassInfoRow(
                    "本地目录",
                    if (folderCount > 0) "$folderCount 个 · 与网盘分类对应" else "还没读到网盘目录"
                )
                GlassDivider()
                GlassInfoRow(
                    "下载队列",
                    when {
                        downloadStats.waitingWifi -> "等 WiFi · 排队 ${downloadStats.total} 张"
                        downloadStats.running -> "${downloadStats.done + downloadStats.failed}/${downloadStats.total} · 失败 ${downloadStats.failed}"
                        downloadStats.total > 0 -> "空闲 · 上次 ${downloadStats.done} 张成功 / ${downloadStats.failed} 张失败"
                        else -> "空闲"
                    }
                )
            }

            GlassPrimaryButton(
                text = "下载当前分区",
                subtitle = "",
                icon = Icons.Rounded.Download,
                onClick = {
                    val folderId = prefs.lastCategoryId
                    if (folderId.isNullOrBlank()) {
                        Toast.makeText(context, "先打开一次小抄，选一个分区", Toast.LENGTH_SHORT).show()
                    } else if (!vaultOn) {
                        Toast.makeText(context, "先打开「图片本地化」", Toast.LENGTH_SHORT).show()
                    } else {
                        downloader.downloadCategory(folderId)
                        Toast.makeText(context, "已开始下载，进度看上面", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            GlassPrimaryButton(
                text = "立即检测更新",
                subtitle = "",
                icon = Icons.Rounded.Refresh,
                onClick = {
                    if (!vaultOn) {
                        Toast.makeText(context, "先打开「图片本地化」", Toast.LENGTH_SHORT).show()
                    } else {
                        downloader.checkNow()
                        Toast.makeText(context, "正在检测…", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            GlassPrimaryButton(
                text = "同步目录结构",
                subtitle = "",
                icon = Icons.Rounded.CloudSync,
                onClick = {
                    if (!vaultOn) {
                        Toast.makeText(context, "先打开「图片本地化」", Toast.LENGTH_SHORT).show()
                    } else {
                        uiScope.launch {
                            val n = withContext(Dispatchers.IO) { repo.syncFolderTree() }
                            folderCount = withContext(Dispatchers.IO) { repo.knownFolderCount() }
                            val msg = if (n > 0) {
                                "本地已有 $n 个分类文件夹"
                            } else {
                                "还没有读到网盘目录，先在「小抄」里刷新一次"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            GlassPrimaryButton(
                text = "空间管理",
                subtitle = "",
                icon = Icons.Rounded.Folder,
                onClick = { showSpace = true }
            )
            // v2.1.0：「清空本地图片」从首页按钮挪进「空间管理」——危险动作不占主列表，
            // 但也没藏起来，只是回到它该在的地方。

            // ------------------------------------------------------------------
            // v2.0.1 云端同步：作者分享 → 我的小抄 → 本地
            // ------------------------------------------------------------------
            GlassSectionTitle("云端同步")
            Spacer(Modifier.height(2.dp))
            GlassCard {
                if (mirrorProgress.running) {
                    GlassInfoRow("正在同步", mirrorProgress.step.ifBlank { "…" })
                    GlassDivider()
                }
                if (mirrorSources.isEmpty()) {
                    GlassRow(
                        title = "还没有同步源",
                        subtitle = "",
                        icon = Icons.Rounded.CloudSync,
                        onClick = { showMirror = true }
                    ) {
                        Text("添加 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                } else {
                    mirrorSources.forEach { source ->
                        GlassRow(
                            title = source.shortLink,
                            subtitle = buildString {
                                append(source.scopeLabel)
                                append(" · ")
                                append(
                                    if (source.lastSyncAt > 0L) {
                                        "上次 " + formatTime(source.lastSyncAt)
                                    } else {
                                        "还没同步过"
                                    }
                                )
                                if (source.lastMessage.isNotBlank()) {
                                    append(" · ")
                                    append(source.lastMessage)
                                }
                            },
                            icon = Icons.Rounded.CloudSync,
                            onClick = {
                                if (mirrorBusy || mirrorProgress.running) {
                                    Toast.makeText(
                                        context,
                                        "正在同步，等这轮结束",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    mirrorBusy = true
                                    mirrorMsg = null
                                    uiScope.launch {
                                        val result = mirrorSync.sync(source, mirrorReplace)
                                        mirrorSources = mirrorStore.all()
                                        mirrorBusy = false
                                        mirrorMsg = result.fold(
                                            { it.message },
                                            { "同步失败：" + (it.message ?: "未知原因") }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("同步 ›", fontSize = 10.sp, color = GlassColors.Accent)
                        }
                        GlassDivider()
                    }
                    GlassRow(
                        title = "添加 / 管理同步源",
                        subtitle = "",
                        icon = Icons.Rounded.Add,
                        onClick = { showMirror = true }
                    ) {
                        Text("打开 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                }
                GlassDivider()
                GlassRow(
                    title = "每次启动自动检查",
                    subtitle = "",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        mirrorAuto = !mirrorAuto
                        prefs.mirrorAuto = mirrorAuto
                    }
                ) {
                    GlassSwitch(
                        checked = mirrorAuto,
                        onCheckedChange = {
                            mirrorAuto = it
                            prefs.mirrorAuto = it
                        }
                    )
                }
                if (mirrorAuto) {
                    GlassDivider()
                    GlassRow(
                        title = "检查频率",
                        subtitle = intervalLabel(mirrorInterval),
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            mirrorInterval = nextInterval(mirrorInterval)
                            prefs.mirrorIntervalHours = mirrorInterval
                        }
                    ) {
                        Text("切换 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                }
                GlassDivider()
                GlassRow(
                    title = "作者改图时替换",
                    subtitle = "",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        mirrorReplace = !mirrorReplace
                        prefs.mirrorReplace = mirrorReplace
                    }
                ) {
                    GlassSwitch(
                        checked = mirrorReplace,
                        onCheckedChange = {
                            mirrorReplace = it
                            prefs.mirrorReplace = it
                        }
                    )
                }
            }
            if (mirrorSources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                GlassPrimaryButton(
                    text = "同步全部源",
                    subtitle = "",
                    icon = Icons.Rounded.CloudSync,
                    enabled = !mirrorBusy && !mirrorProgress.running,
                    onClick = {
                        mirrorBusy = true
                        mirrorMsg = null
                        uiScope.launch {
                            val results = ArrayList<String>(mirrorSources.size)
                            mirrorSources.forEach { source ->
                                val r = mirrorSync.sync(source, mirrorReplace)
                                results += r.fold(
                                    { it.message },
                                    { "失败：" + (it.message ?: "?") }
                                )
                            }
                            mirrorSources = mirrorStore.all()
                            mirrorBusy = false
                            mirrorMsg = results.joinToString("；")
                        }
                    }
                )
            }
            mirrorMsg?.let { GlassHint(it) }
            }   // end if (expandStorage)

        }

        // v2.9.1：协议、更新说明、关于、支持作者单独成一页 ——
        // 以前它们一半挤在「未登录」分支里、一半挤在「已登录」分支里，
        // 登录状态一变，入口就换地方。v2.11.0 起让位给「主题设置」，自己退到第 3 页。
        if (pane == 2) {
            GlassSectionTitle("协议与关于")
            GlassCard {
                GlassRow(
                    title = "用户协议 / 隐私说明",
                    subtitle = "",
                    icon = Icons.Rounded.Shield,
                    onClick = onOpenProtocol
                ) {
                    Text("查看 ›", fontSize = 10.sp, color = GlassColors.Accent)
                }
                GlassDivider()
                GlassRow(
                    title = "更新说明",
                    subtitle = "",
                    icon = Icons.Rounded.Description,
                    onClick = onOpenNotes
                ) {
                    Text("查看 ›", fontSize = 10.sp, color = GlassColors.Accent)
                }
                GlassDivider()
                GlassRow(
                    title = "关于 / 开源协议",
                    subtitle = "",
                    icon = Icons.Rounded.Info,
                    onClick = onOpenAbout
                ) {
                    Text("查看 ›", fontSize = 10.sp, color = GlassColors.Accent)
                }
            }

            GlassSectionTitle("支持作者")
            Spacer(Modifier.height(2.dp))
            GlassCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.donate_qr),
                        contentDescription = "作者赞赏码",
                        modifier = Modifier
                            .width(210.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showDonate = true }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("微信扫码 · 请作者喝杯奶茶", fontSize = 12.sp, color = GlassColors.Text)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // v1.9.10 空间管理：去重 / 限额淘汰 / 清缩略图缓存，三个动作都只动本地副本
    if (showSpace) {
        Dialog(onDismissRequest = { showSpace = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(GlassColors.Bg)
                    .border(1.dp, GlassColors.Stroke, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text(
                    "存储空间",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "共 ${usage.count} 张 · ${usage.files} 个文件 · ${formatSize(usage.bytes)}" +
                        if (usage.savedBytes > 0L) " · 复用已省 ${formatSize(usage.savedBytes)}" else "",
                    fontSize = 11.sp,
                    color = GlassColors.TextSub
                )
                Spacer(Modifier.height(12.dp))

                GlassCard(allowFrost = false) {
                    GlassRow(
                        title = "空间上限",
                        subtitle = limitLabel(maxBytes),
                        icon = Icons.Rounded.Folder,
                        onClick = {
                            maxBytes = nextLimit(maxBytes)
                            prefs.imageMaxBytes = maxBytes
                        }
                    ) {
                        Text("切换 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                    GlassDivider()
                    GlassRow(
                        title = "立即去重",
                        subtitle = "",
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            spaceMsg = "正在去重…"
                            downloader.dedupeNow { r ->
                                uiScope.launch {
                                    usage = vault.usage()
                                    spaceMsg = if (r.removedFiles > 0) {
                                        "已合并 ${r.removedFiles} 个重复文件，省下 ${formatSize(r.freedBytes)}"
                                    } else {
                                        "没有发现重复文件"
                                    }
                                }
                            }
                        }
                    ) {
                        Text("清理 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                    GlassDivider()
                    GlassRow(
                        title = "清理旧图",
                        subtitle = "",
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            if (maxBytes <= 0L) {
                                spaceMsg = "先把「空间上限」设一个值，再清理"
                            } else {
                                spaceMsg = "正在按上限清理…"
                                downloader.trimNow { r ->
                                    uiScope.launch {
                                        usage = vault.usage()
                                        spaceMsg = if (r.removedFiles > 0) {
                                            "已清理 ${r.removedFiles} 个文件，释放 ${formatSize(r.freedBytes)}"
                                        } else {
                                            "已经在上限以内，不用清理"
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Text("清理 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                    GlassDivider()
                    GlassRow(
                        title = "清缩略图缓存",
                        subtitle = "",
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            uiScope.launch {
                                withContext(Dispatchers.IO) { GuideImageLoader.clearCache(context) }
                                usage = vault.usage()
                                spaceMsg = "缩略图缓存已清空"
                            }
                        }
                    ) {
                        Text("清理 ›", fontSize = 10.sp, color = GlassColors.Accent)
                    }
                    // v2.1.0：从首页挪进来。放最后一行，红字，误点概率最低
                    GlassDivider()
                    GlassRow(
                        title = "清空本地图片",
                        subtitle = "",
                        icon = Icons.Rounded.Refresh,
                        iconTint = GlassColors.Danger,
                        onClick = {
                            uiScope.launch {
                                val removed = withContext(Dispatchers.IO) { vault.clearAll() }
                                usage = vault.usage()
                                spaceMsg = "已删除 $removed 张"
                            }
                        }
                    ) {
                        Text("清空 ›", fontSize = 10.sp, color = GlassColors.Danger)
                    }
                }

                spaceMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.sp, color = GlassColors.Warn)
                }

                Spacer(Modifier.height(12.dp))
                GlassPrimaryButton(text = "完成", onClick = { showSpace = false })
            }
        }
    }

    // v2.0.1 同步源管理：加链接 → 读分类 → 勾选 → 保存；已添加的在这里移除
    if (showMirror) {
        MirrorSourceDialog(
            onDismiss = { showMirror = false },
            onChanged = { mirrorSources = mirrorStore.all() }
        )
    }

    if (showDonate) {
        Dialog(onDismissRequest = { showDonate = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.donate_qr),
                    contentDescription = "作者赞赏码",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "微信扫码 · 感谢支持",
                    fontSize = 13.sp,
                    color = Color(0xFF1A1A1A)
                )
            }
        }
    }
}

/** 三档压缩预设：均衡 / 省空间 / 极小 */
private fun compressLabel(quality: Int, maxEdge: Int): String = when {
    quality >= 80 -> "均衡 · JPEG $quality% / 长边 ${maxEdge}px，约省一半"
    quality >= 65 -> "省空间 · JPEG $quality% / 长边 ${maxEdge}px，手机上看足够"
    else -> "极小 · JPEG $quality% / 长边 ${maxEdge}px，只求认得字"
}

private fun nextPreset(quality: Int, maxEdge: Int): Pair<Int, Int> = when {
    quality >= 80 -> 70 to 1280
    quality >= 65 -> 60 to 1080
    else -> 85 to 1600
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
}

/** 空间上限档位：0 = 不限。小抄图压缩后一张 200~400 KB，2000 张大约 600 MB */
private val IMAGE_LIMITS = longArrayOf(
    0L,
    300L * 1024 * 1024,
    800L * 1024 * 1024,
    2L * 1024 * 1024 * 1024,
    5L * 1024 * 1024 * 1024
)

private fun nextLimit(current: Long): Long =
    IMAGE_LIMITS[(IMAGE_LIMITS.indexOf(current) + 1).mod(IMAGE_LIMITS.size)]

private fun limitLabel(bytes: Long): String =
    if (bytes <= 0L) "不限 · 只靠去重和手动清理" else "超过 ${formatSize(bytes)} 时自动淘汰最久没看的"

/** 检查间隔的下一档：12h → 24h → 72h（3 天）→ 168h（7 天）→ 回到 12h */
private fun nextInterval(current: Int): Int {
    val all = OverlayPrefs.MIRROR_INTERVALS
    val i = all.indexOf(current)
    return if (i < 0) all[1] else all[(i + 1) % all.size]
}

private fun intervalLabel(hours: Int): String = when {
    hours < 24 -> "每 $hours 小时查一次 · 适合日更的活动图"
    hours == 24 -> "每天查一次 · 默认，绝大多数攻略够用"
    hours < 168 -> "每 ${hours / 24} 天查一次 · 写完之后基本不动的攻略"
    else -> "每周查一次 · 最省流量"
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) {
        "—"
    } else {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrDefault("—")
    }

/**
 * 同步源管理弹窗（v2.0.1）。
 *
 * 为什么「读取分类」要单独一步：`sharepage/detail` 是一次网络请求，不能在输入框
 * 每敲一个字就发一次；而且用户很可能粘的是带口令的整段分享文本，得先让
 * `ShareLinkParser` 解析出来才谈得上选分类。
 */
@Composable
private fun MirrorSourceDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { MirrorStore.of(context) }
    var existing by remember { mutableStateOf(store.all()) }
    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var shareTitle by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun readFolders() {
        val text = link.trim()
        if (text.isEmpty()) return
        loading = true
        error = null
        folders = emptyList()
        picked = emptySet()
        shareTitle = ""
        scope.launch {
            val service = ShareLinkService.of(context)
            val opened = service.open(text, null).getOrElse {
                loading = false
                error = it.message ?: "打不开这个链接"
                return@launch
            }
            if (opened.platform != SharePlatform.QUARK) {
                loading = false
                error = "自动转存目前只支持夸克分享"
                return@launch
            }
            val files = service.list(opened, service.rootFid(opened.platform)).getOrElse {
                loading = false
                error = it.message ?: "读不到分享目录"
                return@launch
            }
            val names = files.filter { it.isdir }.map { it.fname }
            folders = names
            picked = names.toSet()
            shareTitle = opened.session.title
            loading = false
            if (names.isEmpty()) {
                error = "这个分享根目录没有子文件夹，会整目录同步"
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(GlassColors.Bg)
                .border(1.dp, GlassColors.Stroke, RoundedCornerShape(20.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "同步源",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GlassColors.Text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "粘贴作者的夸克分享链接（可以直接粘带口令的整段文字）",
                fontSize = 11.sp,
                color = GlassColors.TextSub
            )
            Spacer(Modifier.height(10.dp))
            GlassTextField(
                value = link,
                onValueChange = { link = it },
                placeholder = "https://pan.quark.cn/s/xxxxxx",
                leading = Icons.Rounded.CloudSync
            )
            Spacer(Modifier.height(8.dp))

            if (folders.isEmpty()) {
                GlassPrimaryButton(
                    text = if (loading) "正在读取…" else "读取分类",
                    subtitle = "",
                    icon = Icons.Rounded.Refresh,
                    enabled = !loading && link.isNotBlank(),
                    onClick = { readFolders() }
                )
            } else {
                Text(
                    if (shareTitle.isBlank()) "选择要同步的分类" else "「$shareTitle」· 选要同步的分类",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "全勾 = 全部；只勾常用的那几个，就不会把几百兆大图全搬过来",
                    fontSize = 10.sp,
                    color = GlassColors.TextMuted
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    folders.forEach { name ->
                        val on = name in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (on) picked - name else picked + name
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (on) "✓" else "○",
                                fontSize = 13.sp,
                                color = if (on) GlassColors.Accent else GlassColors.TextMuted
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(name, fontSize = 12.sp, color = GlassColors.Text)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "全选",
                        fontSize = 11.sp,
                        color = GlassColors.Accent,
                        modifier = Modifier
                            .clickable { picked = folders.toSet() }
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "全不选",
                        fontSize = 11.sp,
                        color = GlassColors.TextMuted,
                        modifier = Modifier
                            .clickable { picked = emptySet() }
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, lineHeight = 15.sp, color = GlassColors.Danger)
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "取消",
                    fontSize = 12.sp,
                    color = GlassColors.TextSub,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(vertical = 10.dp, horizontal = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (loading) "读取中…" else "保存",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (link.isNotBlank() && !loading) {
                        GlassColors.Accent
                    } else {
                        GlassColors.TextMuted
                    },
                    modifier = Modifier
                        .clickable(enabled = link.isNotBlank() && !loading) {
                            val text = link.trim()
                            val source = MirrorSource(
                                id = MirrorSource.idOf(text),
                                link = text,
                                // 全选时存空列表：以后作者新增了分类也能自动跟上，
                                // 存死名单的话新分类永远同步不到
                                folderNames = if (folders.isNotEmpty() && picked.size < folders.size) {
                                    picked.toList()
                                } else {
                                    emptyList()
                                }
                            )
                            store.put(source)
                            existing = store.all()
                            onChanged()
                            link = ""
                            folders = emptyList()
                            picked = emptySet()
                            error = null
                            Toast.makeText(context, "已保存，点这一条就能开始同步", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 10.dp, horizontal = 6.dp)
                )
            }

            if (existing.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                GlassDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "已添加 ${existing.size} 条",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.TextSub
                )
                Spacer(Modifier.height(2.dp))
                existing.forEach { source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.shortLink,
                                fontSize = 11.sp,
                                color = GlassColors.Text
                            )
                            Text(
                                source.scopeLabel,
                                fontSize = 9.sp,
                                color = GlassColors.TextMuted
                            )
                        }
                        Text(
                            "移除",
                            fontSize = 11.sp,
                            color = GlassColors.Danger,
                            modifier = Modifier
                                .clickable {
                                    store.remove(source.id)
                                    existing = store.all()
                                    onChanged()
                                }
                                .padding(vertical = 6.dp, horizontal = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
