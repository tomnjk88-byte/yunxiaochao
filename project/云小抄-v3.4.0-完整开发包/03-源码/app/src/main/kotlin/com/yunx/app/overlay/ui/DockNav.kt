/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.3.0 新增。第二种导航方式 —— 底部液态玻璃胶囊 Dock。
 *
 * 跟原来那套（顶栏菜单键 + 左右边缘滑出抽屉）不是替换关系，是并存：
 *   抽屉   —— 一屏只有内容，页面列表收起来，适合「一天开一次」的人；
 *   Dock   —— 四个大入口常驻在底下，随时一下切过去，适合来回翻的人。
 * 选哪套存在偏好里，主题设置 → 底栏导航 里随时换，换完立刻生效。
 *
 * 做成「浮在内容上的一颗胶囊」而不是「贴底的横条」，是照着参考界面来的：
 * 底下那条要能看见后面的壁纸，中间还得透出一点点颜色 —— 贴死就成了一块板。
 */

package com.yunx.app.overlay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 导航方式。存 key 不存序数 —— 以后再加第三套（比如侧边竖排）不会把老用户错位。
 */
enum class NavStyle(val key: String, val label: String, val hint: String) {
    DRAWER("drawer", "抽屉导航", "顶栏菜单键 · 从屏幕边缘滑入 · 一屏只有内容"),
    DOCK("dock", "底部 Dock", "四个大入口常驻底部 · 一下切页 · 主题商店就在里面");

    companion object {
        fun of(key: String?): NavStyle = entries.firstOrNull { it.key == key } ?: DRAWER
    }
}

/** Dock 上的一个入口。target = HomeScreen 里的 tab 下标 */
private data class DockEntry(
    val target: Int,
    val label: String,
    val icon: ImageVector,
    /**
     * SukiSU 主题包里的图标名（bottom_bar_icon_xxx 的后半段）。
     * 装了带图的主题就画图，没装就画 [icon] —— 这条分支在 DockItem 里。
     */
    val iconKey: String
)

private val DOCK_ENTRIES = listOf(
    // 顺序跟 SukiSU 包里的四个图标对齐：home / superuser / module / setting。
    // 这四个名字是它的固定枚举，不跟着我们改，所以照抄。
    DockEntry(0, "小抄", Icons.Rounded.Description, "home"),
    DockEntry(1, "连招", Icons.Rounded.Bolt, "superuser"),
    // v3.4.4：调色盘图标的孔洞在 22dp 下看着像一颗多余的点，换成画笔
    DockEntry(8, "主题", Icons.Rounded.Brush, "module"),
    DockEntry(7, "我的", Icons.Rounded.Person, "setting")
)

/**
 * 底部玻璃胶囊。
 *
 * @param selected 当前 tab 下标（0/1/8/7 之外的页面，比如解析、准星、权限，
 *   会落回「小抄」那一格不亮 —— 这些页在 Dock 模式里靠顶栏菜单进，属于二级页）
 * @param badges 数字角标：tab 下标 → 数字。0 不画
 * @param dots 绿点：tab 下标集合。表示「这个入口背后有东西在跑」
 */
@Composable
fun DockNav(
    selected: Int,
    badges: Map<Int, Int>,
    dots: Set<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    val shape = RoundedCornerShape(999.dp)
    val fillTop = if (t.dark) Color(0x3DFFFFFF) else Color(0xD9FFFFFF)
    val fillMid = if (t.dark) Color(0x1FFFFFFF) else Color(0xB8FFFFFF)
    val fillBottom = if (t.dark) Color(0x0FFFFFFF) else Color(0x8CFFFFFF)
    val borderTop = if (t.dark) Color(0x66FFFFFF) else Color(0xFFFFFFFF)
    val borderBottom = if (t.dark) Color(0x14FFFFFF) else Color(0x4DFFFFFF)

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 22.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x33000000),
                    spotColor = Color(0x40000000)
                )
                .clip(shape)
                .background(
                    Brush.verticalGradient(listOf(fillTop, fillMid, fillBottom))
                )
                .border(1.dp, Brush.verticalGradient(listOf(borderTop, borderBottom)), shape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DOCK_ENTRIES.forEach { entry ->
                DockItem(
                    entry = entry,
                    active = selected == entry.target,
                    badge = badges[entry.target] ?: 0,
                    dot = dots.contains(entry.target),
                    onClick = { onSelect(entry.target) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 顶部那道镜面高光：只有上半段，宽度 56% —— 玻璃的「厚度」全靠它。
        // 放在 Row 外面，否则它会作为第 5 个子项去挤那四个入口的位置。
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = 13.dp)
                .fillMaxWidth(0.56f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = if (t.dark) 0.5f else 0.9f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
private fun DockItem(
    entry: DockEntry,
    active: Boolean,
    badge: Int,
    dot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    val tint by animateColorAsState(
        targetValue = if (active) t.accent else t.textSub,
        animationSpec = tween(220),
        label = "dockTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1.06f else 1f,
        animationSpec = tween(220),
        label = "dockScale"
    )
    // 主题包自带的图标。有图就用图 —— 那四个格子本来就是这个主题最好看的地方，
    // 拿 tint 去染一张彩色插画只会得到一块脏方块，所以图不做任何 tint。
    val skin by ThemeSkin.state.collectAsState()
    val art = skin.dockIcons[entry.iconKey]
    Column(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) {
                    Brush.verticalGradient(
                        listOf(
                            t.accent.copy(alpha = if (t.dark) 0.26f else 0.18f),
                            t.accent.copy(alpha = if (t.dark) 0.12f else 0.08f)
                        )
                    )
                } else {
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.scale(scale)) {
            if (art != null) {
                val artShape = RoundedCornerShape(7.dp)
                Image(
                    bitmap = art,
                    contentDescription = entry.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(artShape)
                        .then(
                            if (active) {
                                Modifier.border(1.5.dp, t.accent.copy(alpha = 0.9f), artShape)
                            } else {
                                Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), artShape)
                            }
                        )
                )
            } else {
                Icon(
                    entry.icon,
                    contentDescription = entry.label,
                    modifier = Modifier.size(20.dp),
                    tint = tint
                )
            }
            // 数字角标：跟参考界面一样压在图标右上角
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 10.dp)
                        .height(14.dp)
                        .width(if (badge > 9) 20.dp else 14.dp)
                        .clip(CircleShape)
                        .background(if (active) t.accent else t.textSub.copy(alpha = 0.85f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            } else if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(GlassColors.Ok)
                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // v3.4.2：底栏文字可自定义（主题设置 → 图标文字 → 界面文本自定义）
        Text(
            LocalThemeTuning.current.text("dock." + entry.iconKey, entry.label),
            fontSize = 9.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = TextStyle(shadow = readableShadow())
        )
    }
}

/**
 * Dock 模式的顶栏。
 *
 * 跟抽屉模式那颗「菜单 + 标题 + 三颗圆钮挤一行」不同，这里照参考界面分成两行：
 * 上面一行圆形玻璃钮，下面一个大标题。好处是标题能到 30sp，一眼知道自己在哪一页，
 * 而按钮又不会把标题挤成两行。
 */
@Composable
fun DockHeader(
    title: String,
    subtitle: String?,
    dark: Boolean,
    onOpenMenu: () -> Unit,
    onOpenStyle: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalUiTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 14.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左边那颗 = 页面列表。Dock 只放四个大入口，解析 / 准星 / 权限这些
            // 二级页还得有个地方进 —— 与其再塞一格，不如留一颗菜单键。
            DockRoundButton(
                icon = Icons.Rounded.Menu,
                description = "打开页面列表",
                onClick = onOpenMenu
            )
            Spacer(Modifier.weight(1f))
            DockRoundButton(
                icon = if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                description = if (dark) "切换到白天模式" else "切换到黑夜模式",
                onClick = onToggleTheme
            )
            Spacer(Modifier.width(8.dp))
            DockRoundButton(
                icon = Icons.Rounded.Brush,
                description = "切换界面风格",
                onClick = onOpenStyle
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = t.text,
            style = TextStyle(shadow = readableShadow()),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank() && !LocalThemeTuning.current.hideSubtitle) {
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = t.textMuted,
                style = TextStyle(shadow = readableShadow()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DockRoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val t = LocalUiTokens.current
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    if (t.dark) {
                        listOf(Color(0x33FFFFFF), Color(0x12FFFFFF))
                    } else {
                        listOf(Color(0xCFFFFFFF), Color(0x8CFFFFFF))
                    }
                )
            )
            .border(1.dp, if (t.dark) t.stroke else Color(0x66FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp), tint = t.textSub)
    }
}
