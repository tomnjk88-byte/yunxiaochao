/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.0 底部「协议」按钮对应的页面：
 *   - 用户协议 / 隐私说明 / 权限用途 / 开源协议 / 合规提示；
 *   - 「关于」页（AGPL §5 修改说明、源代码地址）从本页进入。
 */

package com.yunx.app.overlay.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val UPSTREAM_URL = "https://github.com/CYQawa/YunX"

@Composable
fun ProtocolPage(
    onOpenAbout: () -> Unit,
    onOpenAuth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // 滚动由主页统一提供，这里只负责内容，避免嵌套两个 verticalScroll 抢手势。
    // v2.3.0：换成 StaggerColumn，逐项浮现才不会退化成整页淡入
    StaggerColumn(modifier = modifier.fillMaxWidth()) {
        GlassSectionTitle("用户协议")
        ProtocolBlock(
            "1. 本应用是个人自用的工具类软件，不提供任何账号注册、云同步、付费或客服服务。\n" +
                "2. 底部「我的」里的登录只是本机个人中心占位：账号与密码随便填即可，不校验、不上传、不保存密码。\n" +
                "3. 请只用它查看你本人合法拥有或已获授权的内容；网盘账号凭证只保存在本机。\n" +
                "4. 自动连招属实验室功能，可能违反部分游戏的用户协议或触发反作弊，风险由使用者自行判断并承担。\n" +
                "5. 本程序按「现状」提供，不含任何明示或默示担保；因使用产生的后果由使用者承担。"
        )

        GlassSectionTitle("隐私说明")
        ProtocolBlock(
            "· 不收集、不上传任何个人信息，没有第三方统计 / 广告 / 推送 SDK。\n" +
                "· 个人中心昵称、登录时间、悬浮窗位置、连招配置全部只写在本机 SharedPreferences 或应用私有目录。\n" +
                "· 密码只存在于当前输入框的内存里，退出页面即消失，从不写入磁盘。\n" +
                "· 自动连招不读取屏幕内容、不检索窗口、不联网；只回放你自己录制或设置的坐标。\n" +
                "· 「本地文件夹」攻略源由系统文件选择器授权，应用只能读你选中的那棵目录树，不申请通用存储权限。\n" +
                "· 「链接小抄」只读你粘贴的那条分享：不转存、不写你的网盘、不在本地落盘，关掉即失效；\n" +
                "  它和「个人小抄」用的是同一套浏览界面，但缓存分开存放，互不串图。\n" +
                "· 网盘请求只发往对应网盘官方接口，用于列出你自己网盘里的文件。"
        )

        GlassSectionTitle("权限用途")
        ProtocolBlock(
            "· 显示在其他应用上层（悬浮窗）：挂出小抄 / 连招窗口，必需。\n" +
                "· 通知：Android 13+ 前台服务常驻提示，以及通知栏「重置 / 收起」动作。\n" +
                "· 无障碍服务：连招的兜底方案，把点击交给系统 dispatchGesture 回放；" +
                "每发一次会取消你正按着的触摸，只在没有 Root / Shizuku 时使用。\n" +
                "· Shizuku（可选，需你自行安装并授权）：以 shell 身份注入点击，独立指针，" +
                "按住摇杆也能放技能。\n" +
                "· Root（可选，需你主动点按钮申请）：用 su + input 注入，同样是独立指针。\n" +
                "· 文件夹访问：读取你指定的本地攻略目录，授权可随时解除。\n\n" +
                "所有权限都可以不给，只是对应功能不可用；不影响其它功能。"
        )
        Spacer(Modifier.height(6.dp))
        GlassPrimaryButton(
            text = "去检查 / 授予权限",
            subtitle = "",
            icon = Icons.Rounded.Info,
            onClick = onOpenAuth
        )

        GlassSectionTitle("开源协议")
        ProtocolBlock(
            "本应用是「云析 YunX」的二次开发版本，按 GNU Affero General Public License v3.0 授权。\n" +
                "原项目：$UPSTREAM_URL\n" +
                "Copyright (C) 2026 CYQawa\n\n" +
                "个人自用不分发；若再分发，须保留原作者版权声明、以 AGPL-3.0 公开修改后的完整源码，" +
                "并向通过网络交互的用户提供获取源码的方式。"
        )
        Spacer(Modifier.height(8.dp))
        GlassPrimaryButton(
            text = "查看修改说明与源代码",
            subtitle = "",
            icon = Icons.Rounded.Description,
            onClick = onOpenAbout
        )
        Spacer(Modifier.height(8.dp))
        GlassPillRow(
            first = "阅读 AGPL-3.0 全文" to { open(LICENSE_URL) },
            second = "上游云析项目" to { open(UPSTREAM_URL) }
        )

        GlassSectionTitle("合规提示")

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProtocolBlock(text: String) {
    GlassCard {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = GlassColors.TextSub,
            modifier = Modifier.padding(14.dp)
        )
    }
}

/** 两个并排的次级胶囊（协议页底部的外链） */
@Composable
private fun GlassPillRow(
    first: Pair<String, () -> Unit>,
    second: Pair<String, () -> Unit>
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp)
    ) {
        GlassPill(first.first, onClick = first.second, primary = false)
        GlassPill(second.first, onClick = second.second, primary = false)
    }
}

