/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.15 连招导入的「接住」Activity。
 *
 * 只有一个职责：别的 App（微信 / QQ / 文件管理器）把 .yxmacro 或一行口令丢过来时，
 * 解析、导入、Toast、退出。全程没有界面（透明主题），所以用户感觉是「点一下文件就导好了」。
 *
 * 为什么单独开一个 Activity 而不是塞进主界面：
 *   - 主界面是 singleTask 的入口页，从聊天软件点文件进来会把它整页重建，正在看的页面会跳；
 *   - 这个 Activity 不参与任务栈（noHistory + excludeFromRecents），导完就消失，
 *     不影响主界面状态，也不要求用户先打开 App。
 */

package com.yunx.app.overlay.macro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

class MacroImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val inlineText = if (action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
        val stream = streamOf(intent)

        // 分享文字通常就在 EXTRA_TEXT 里，直接解析；文件才需要读 IO
        if (inlineText != null && MacroShare.looksLikeShare(inlineText)) {
            importText(inlineText)
            return
        }
        if (stream == null) {
            importText(inlineText)
            return
        }

        thread(name = "macro-import") {
            val text = MacroShareIo.readText(this, stream) ?: inlineText
            runOnUiThread { importText(text) }
        }
    }

    /** 分享进来的文本 / 文件都走这里；解析失败给一句人话，不要静默退出 */
    private fun importText(text: String?) {
        if (text.isNullOrBlank()) {
            toast("没有读到连招内容")
            finish()
            return
        }
        val result = MacroController.importSharedText(this, text)
        if (result == null) {
            toast("这不是云小抄的连招（.${MacroShare.EXT}）或口令")
        } else {
            toast(MacroShare.describe(result))
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
