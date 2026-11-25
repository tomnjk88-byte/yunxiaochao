/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.7.0「获取资源链接」：
 *   · [ResourceLinkCard]  —— 本地小抄页里的一张虚线卡（入口）；
 *   · [ResourceLinkSheet] —— 点开后的底部弹窗，展示总链接 + 说明 + 复制。
 *
 * 边界很清楚：这里只负责「把一段文本给用户复制走」。
 * 不解析网盘目录、不列子地图、不自动下载、不创建任何小抄入口 ——
 * 下载和绑定全部由用户自己在网盘和「添加小抄入口」里完成。
 */

package com.yunx.app.overlay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.overlay.data.ResourceLinkStore

/**
 * 本地小抄页的「获取资源链接」虚线卡。
 *
 * 为什么做成虚线卡而不是实心卡：它不是「功能开关」，是一个**离开 App 的动作**
 * （复制链接 → 自己去网盘下载）。虚线在视觉上天然表示「这里还没有东西、要你去补」，
 * 而且不与上面的入口网格抢注意力。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResourceLinkCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // 长按卡片 = 本机替换链接。远程配置没部署时，这是唯一「不发版也能换链接」的路；
    // 不做成可见按钮，是不想让普通玩家看到第二个「设置」入口。
    var editing by remember { mutableStateOf(false) }
    // 先拿缓存值渲染，网络回来再换 —— 弹窗永远不用等
    var url by remember { mutableStateOf(ResourceLinkStore.current(context)) }

    // 进「本地」页顺带静默拉一次远程配置：管理员换了链接，用户下次进这页就生效。
    // 失败静默，界面保持原值，不弹任何错误。
    LaunchedEffect(Unit) {
        if (ResourceLinkStore.refresh(context)) {
            url = ResourceLinkStore.current(context)
        }
    }

    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                // 卡组模式下它是叠在最上面的一张，半透明底会把下面那张卡的字透出来
                .background(
                    if (LocalStackCards.current) {
                        LocalUiTokens.current.bg
                    } else {
                        GlassColors.Accent.copy(alpha = 0.05f)
                    }
                )
                .dashedBorder(
                    color = GlassColors.Accent.copy(alpha = 0.34f),
                    radius = 16.dp
                )
                .combinedClickable(
                    onClick = { open = true },
                    onLongClick = { editing = true }
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(GlassColors.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Link,
                    contentDescription = null,
                    tint = GlassColors.Accent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "获取资源链接",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Text
                )
                Text(
                    "展十小抄源",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = GlassColors.TextSub
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = GlassColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (open) {
        ResourceLinkSheet(url = url, onClose = { open = false })
    }

    if (editing) {
        ReplaceLinkDialog(
            initial = url,
            onDismiss = { editing = false },
            onSaved = { newUrl ->
                editing = false
                if (newUrl == null) {
                    // null = 恢复默认
                    ResourceLinkStore.resetLocal(context)
                } else {
                    ResourceLinkStore.saveLocal(context, newUrl)
                }
                url = ResourceLinkStore.current(context)
            }
        )
    }
}

/**
 * 替换链接的小对话框。
 *
 * 刻意写清「只改这一台」：管理员容易以为改完所有人都变了，
 * 实际全员生效要改远程配置文件（[ResourceLinkStore.CONFIG_URL]）。
 */
@Composable
private fun ReplaceLinkDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSaved: (String?) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("替换资源链接", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "只改这一台设备，别的用户不受影响。要让所有人拿新链接，改远程配置文件的地址即可。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = GlassColors.TextMuted
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = false,
                    minLines = 2,
                    placeholder = { Text("https://pan.quark.cn/s/…", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSaved(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSaved(null) }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 弹窗里那五条说明。文案是用户逐字定稿的，别改措辞 */
private val LINK_TIPS = listOf(
    "链接内所有展十小抄源资源需要你自行选择、下载；",
    "下载完成后，使用「本地小抄 - 添加小抄入口」选中存放文件夹即可查看；",
    "如果文件夹内没有攻略图片，打开之后就是空白；",
    "本资源免费，不提供分类挑选、自动下载。"
)

private const val LINK_TIP_LAST = "链接打不开或提示已失效？请前往社群获取最新链接。"

/**
 * 底部弹窗。
 *
 * 用 Dialog + 自绘底部卡片，不用 ModalBottomSheet：后者自带 Material 的 surface 与
 * 拖拽手势，在这套深色玻璃主题里会突兀，而且弹出的高度动画和首页的入场动画会打架。
 */
@Composable
private fun ResourceLinkSheet(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val solid = LocalUiTokens.current.solid
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            // 遮罩：点它也算关闭，和系统弹窗的手感一致
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
            )

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(solid)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(top = 9.dp, bottom = 18.dp)
            ) {
                // 顶部小横条：暗示「这是一块从底部上来的面板」
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(34.dp)
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(GlassColors.Text.copy(alpha = 0.18f))
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "获取资源链接",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Text
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(GlassColors.Text.copy(alpha = 0.06f))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = GlassColors.TextSub,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(Modifier.height(9.dp))
                Text(
                    "下面是展十小抄源总链接，请自行复制，按需下载资源",
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = GlassColors.TextSub
                )

                Spacer(Modifier.height(12.dp))

                // ---- 链接框 ----
                val boxShape = RoundedCornerShape(13.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(boxShape)
                        .background(GlassColors.Text.copy(alpha = 0.05f))
                        .border(
                            width = 1.dp,
                            color = if (copied) GlassColors.Ok.copy(alpha = 0.55f)
                            else GlassColors.Divider,
                            shape = boxShape
                        )
                        .padding(start = 12.dp, end = 7.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        url,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (copied) GlassColors.Ok else GlassColors.TextSub,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    // 框内的小复制键：和底部大按钮是两个入口，习惯点哪都行
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(
                                if (copied) GlassColors.Ok.copy(alpha = 0.16f)
                                else GlassColors.Accent.copy(alpha = 0.16f)
                            )
                            .clickable {
                                copyToClipboard(context, url)
                                copied = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = if (copied) GlassColors.Ok else GlassColors.Accent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (copied) "已复制" else "复制",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (copied) GlassColors.Ok else GlassColors.Accent
                        )
                    }
                }

                // ---- 复制成功提示条 ----
                if (copied) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(GlassColors.Ok.copy(alpha = 0.13f))
                            .border(
                                1.dp,
                                GlassColors.Ok.copy(alpha = 0.38f),
                                RoundedCornerShape(11.dp)
                            )
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = GlassColors.Ok,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "链接已复制到剪贴板，前往网盘打开",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = GlassColors.Ok
                        )
                    }
                }

                // ---- 说明区 ----
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "△",
                        fontSize = 11.sp,
                        color = GlassColors.Warn
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "说明",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.TextMuted
                    )
                }
                Spacer(Modifier.height(7.dp))
                LINK_TIPS.forEach { tip ->
                    TipLine(tip, GlassColors.TextSub)
                    Spacer(Modifier.height(5.dp))
                }
                TipLine(LINK_TIP_LAST, GlassColors.TextMuted)

                // ---- 底部按钮 ----
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    SheetButton(
                        text = if (copied) "已复制到剪贴板" else "复制链接",
                        primary = true,
                        success = copied,
                        modifier = Modifier.weight(1.35f),
                        onClick = {
                            copyToClipboard(context, url)
                            copied = true
                        }
                    )
                    SheetButton(
                        text = "关闭",
                        primary = false,
                        success = false,
                        modifier = Modifier.weight(1f),
                        onClick = onClose
                    )
                }
            }
        }
    }
}

/** 一条说明：圆点 + 自动换行的正文 */
@Composable
private fun TipLine(text: String, color: Color) {
    Row {
        Text(
            "·",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = color.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 底部两颗按钮。主按钮在复制成功后会整体变绿，是这次交互里唯一的「状态记忆」 */
@Composable
private fun SheetButton(
    text: String,
    primary: Boolean,
    success: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val bg = when {
        success -> GlassColors.Ok.copy(alpha = 0.9f)
        primary -> GlassColors.Accent
        else -> Color.Transparent
    }
    val fg = when {
        success -> Color(0xFF06240F)
        primary -> Color.White
        else -> GlassColors.TextSub
    }
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (primary || success) Modifier
                else Modifier.border(1.dp, GlassColors.Divider, shape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * 写剪贴板。
 *
 * 只写不读：Android 12 起读剪贴板会被系统拦（并弹「已粘贴」提示），
 * 而这里根本不需要读回内容，只把链接放进去就行。
 * 系统 copy label 用固定串，用户在剪贴板历史里能认出这是哪来的。
 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching {
        cm.setPrimaryClip(ClipData.newPlainText("云小抄 · 资源链接", text))
    }
}

/** 虚线圆角边框。Compose 没有内置虚线 border，这里用 stroke + dashPathEffect 画 */
private fun Modifier.dashedBorder(
    color: Color,
    radius: Dp,
    width: Dp = 1.dp,
    dash: Dp = 6.dp,
    gap: Dp = 4.dp
): Modifier = drawBehind {
    val w = width.toPx()
    // 线宽的一半要往内收，否则描边有一半落在边界外被裁掉，看着像断线
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2f),
        style = Stroke(
            width = w,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()), 0f)
        )
    )
}
