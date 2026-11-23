/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.4.2 新增。桌面图标切换（对齐 SukiSU Nova 主题设置里的「应用图标喵~」）。
 *
 * 原理：清单里为每套图标注册一个 activity-alias，全部带 LAUNCHER intent-filter，
 * 同一时刻只启用一个 —— 桌面永远只看到一个图标。切换用 setComponentEnabledSetting，
 * 不重启进程（DONT_KILL_APP），但桌面会重新拉一次图标，属正常现象。
 *
 * ★ alias 的类名必须以 MainActivityIcon2 结尾：YunXApp 的防改包校验会拿
 *   getLaunchIntentForPackage() 返回的组件名做 endsWith 比对，对不上直接杀进程。
 */

package com.yunx.app.overlay.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File

object AppIcon {

    /** 一套可选图标 */
    data class Option(
        val key: String,
        val label: String,
        val hint: String,
        val component: String
    )

    /**
     * 顺序即界面顺序。第一项是随包默认的那套（activity 本身，没有开关状态），
     * 其余都是 manifest 里 enabled=false 的 alias。
     */
    val options: List<Option> = listOf(
        Option("classic", "经典", "随包默认的云小抄图标", "com.yunx.app.overlay.MainActivityIcon2"),
        Option("new", "新版", "方形渐变新图标", "com.yunx.app.MainActivityIcon2"),
        Option("round", "圆形", "圆形底板图标", "com.yunx.app.iconround.MainActivityIcon2"),
        Option("photo", "手绘", "手绘风格图标", "com.yunx.app.iconphoto.MainActivityIcon2")
    )

    /** 当前正在用的那套。classic 是 Default 状态，其余看 alias 是否 ENABLED */
    fun current(context: Context): String {
        val pm = context.packageManager
        return options.drop(1).firstOrNull { option ->
            runCatching {
                pm.getComponentEnabledSetting(ComponentName(context, option.component)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }.getOrDefault(false)
        }?.key ?: "classic"
    }

    /**
     * 切到 [key]。返回 false 表示清单里没有这套（理论上不会发生）。
     *
     * 先把目标置 ENABLED 再关掉其余，顺序不能反 —— 反着来会有一瞬间
     * 「一个入口都没有」，桌面图标直接消失到下一次刷新。
     */
    fun apply(context: Context, key: String): Boolean {
        val target = options.firstOrNull { it.key == key } ?: return false
        val pm = context.packageManager
        val targetName = ComponentName(context, target.component)
        runCatching {
            pm.setComponentEnabledSetting(
                targetName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        options.filter { it.key != key }.forEach { other ->
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(context, other.component),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        OverlayPrefs(context).appIconKey = key
        return true
    }

    // ------------------------------------------------------------------
    // v3.4.6：手动导入图标
    // ------------------------------------------------------------------

    /**
     * 用用户自己的图片在桌面钉一个快捷方式。
     *
     * Android 不允许已安装应用在运行时替换自己的 launcher 图标资源
     * （android:icon 编译进 APK，activity-alias 也只能指向已有资源），
     * 所以「导入图片当图标」只能走快捷方式：图标是任意 Bitmap，名字也随便写。
     * 返回 false 表示桌面不支持或请求失败。
     */
    fun pinCustomShortcut(context: Context, label: String, bitmap: Bitmap): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return false
        launch.action = Intent.ACTION_MAIN
        launch.addCategory(Intent.CATEGORY_LAUNCHER)
        val name = label.trim().ifBlank { "云小抄" }
        val shortcut = ShortcutInfoCompat.Builder(
            context,
            "yunx-custom-" + System.currentTimeMillis()
        )
            .setShortLabel(name.take(10))
            .setLongLabel(name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            .setIntent(launch)
            .build()
        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
    }

    /** 导入的图标存私有目录：下次再钉快捷方式直接复用，不用用户重新选图 */
    fun saveCustomIcon(context: Context, bitmap: Bitmap): String? = runCatching {
        val file = File(context.filesDir, "custom_launcher_icon.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrNull()

    fun loadCustomIcon(context: Context): Bitmap? = runCatching {
        val file = File(context.filesDir, "custom_launcher_icon.png")
        if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
    }.getOrNull()
}
