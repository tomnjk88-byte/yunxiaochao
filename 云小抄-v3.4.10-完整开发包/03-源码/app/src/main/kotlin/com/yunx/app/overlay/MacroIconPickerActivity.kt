/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v1.8.4）。给「连招专属按键」挑自定义图片的透明中继 Activity。
 *
 * 为什么需要它：连招面板挂在 Service 的悬浮窗里，Compose 树只有
 * LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner，**没有
 * ActivityResultRegistryOwner**，`rememberLauncherForActivityResult` 会直接抛异常，
 * 而且 SAF / 照片选择器必须由一个真实 Activity 用 startActivityForResult 才能拿到结果。
 * 所以面板点「换图片」时拉起这个全透明、无界面的 Activity，由它发起
 * PickVisualMedia，把结果图复制到私有目录写回对应连招，随后立即 finish ——
 * 用户看到的就是「点一下 → 系统选图 → 按钮图标变了」，没有额外页面。
 */

package com.yunx.app.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.macro.MacroController
import com.yunx.app.overlay.macro.MacroIconStorage
import com.yunx.app.overlay.service.FloatingService
import java.io.File

class MacroIconPickerActivity : ComponentActivity() {

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        // v2.5.4：同一个中继 Activity 兼管「小抄球图标」—— 悬浮窗里长按小球就能换图，
        // 不用先收起悬浮窗回主页设置页。
        // v2.5.6：再兼管「帧率悬浮窗图标」。
        when {
            bubbleTarget -> saveBubbleIcon(uri)
            fpsTarget -> saveFpsIcon(uri)
            else -> saveMacroIcon(uri)
        }
        finish()
    }

    private fun saveMacroIcon(uri: Uri) {
        val id = macroId ?: return
        val path = MacroIconStorage.save(this, id, uri)
        if (path != null) {
            MacroController.init(applicationContext)   // 幂等：Service 没跑时也能落盘
            val macro = MacroController.macros.value.firstOrNull { it.id == id }
            if (macro != null) {
                MacroController.setIconImage(this, id, path)
                Toast.makeText(this, "「${macro.name}」的按钮图片已更新", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "读取图片失败，请换一张试试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBubbleIcon(uri: Uri) {
        val dest = File(filesDir, "bubble_icon_${System.currentTimeMillis()}.img")
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
        }.isSuccess
        if (!ok) {
            Toast.makeText(this, "读取图片失败，请换一张试试", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = OverlayPrefs(this)
        prefs.bubbleIconPath?.let { old -> runCatching { File(old).delete() } }
        prefs.bubbleIconPath = dest.absolutePath
        // 正在桌上的悬浮球立刻换图，不用重启服务
        FloatingService.refresh(this)
        Toast.makeText(this, "小抄图标已更新", Toast.LENGTH_SHORT).show()
    }

    /** v2.5.6：帧率悬浮窗图标 */
    private fun saveFpsIcon(uri: Uri) {
        val dest = File(filesDir, "fps_icon_${System.currentTimeMillis()}.img")
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
        }.isSuccess
        if (!ok) {
            Toast.makeText(this, "读取图片失败，请换一张试试", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = OverlayPrefs(this)
        prefs.fpsIconPath?.let { old -> runCatching { File(old).delete() } }
        prefs.fpsIconPath = dest.absolutePath
        FloatingService.applyFps(this)
        Toast.makeText(this, "帧率窗图标已更新", Toast.LENGTH_SHORT).show()
    }

    private val bubbleTarget: Boolean
        get() = intent?.getStringExtra(EXTRA_TARGET) == TARGET_BUBBLE

    private val fpsTarget: Boolean
        get() = intent?.getStringExtra(EXTRA_TARGET) == TARGET_FPS

    private val macroId: String?
        get() = intent?.getStringExtra(EXTRA_MACRO_ID)?.takeIf { it.isNotBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!bubbleTarget && !fpsTarget && macroId == null) {
            finish()
            return
        }
        // 旋转等重建时别重复拉起选择器；回调仍会由 registerForActivityResult 送回
        if (savedInstanceState == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    companion object {
        const val EXTRA_MACRO_ID = "macro_icon_target_id"
        const val EXTRA_TARGET = "icon_target"
        const val TARGET_BUBBLE = "bubble"
        const val TARGET_FPS = "fps"

        /**
         * @param newTask 从悬浮窗（Service）发起时必须 true；从主页 Activity 发起用 false，
         *                避免在主页之上另开一个任务栈。
         */
        fun start(context: Context, macroId: String, newTask: Boolean = false) {
            val intent = Intent(context, MacroIconPickerActivity::class.java)
                .putExtra(EXTRA_MACRO_ID, macroId)
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "无法打开图片选择器，请到主界面「连招按钮外观」里设置", Toast.LENGTH_LONG).show()
            }
        }

        /** v2.5.4：长按悬浮小抄球换图标 */
        fun startForBubble(context: Context, newTask: Boolean = true) {
            val intent = Intent(context, MacroIconPickerActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_BUBBLE)
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "无法打开图片选择器，请到主界面换图标", Toast.LENGTH_LONG).show()
            }
        }

        /** v2.5.6：帧率悬浮窗换图标 */
        fun startForFps(context: Context, newTask: Boolean = false) {
            val intent = Intent(context, MacroIconPickerActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_FPS)
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "无法打开图片选择器", Toast.LENGTH_LONG).show()
            }
        }
    }
}
