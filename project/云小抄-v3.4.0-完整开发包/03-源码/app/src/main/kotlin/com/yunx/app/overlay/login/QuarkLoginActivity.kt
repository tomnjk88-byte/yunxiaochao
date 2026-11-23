/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。登录流程复用云析 QuarkLoginScreen 的思路（PC UA + CookieManager），
 * 但把「登录成功后回调分享解析」改成「落库 + 通知悬浮窗刷新」。
 */

package com.yunx.app.overlay.login

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.overlay.data.CredentialStore
import com.yunx.app.overlay.service.FloatingService
import com.yunx.app.overlay.ui.OverlayColors
import com.yunx.app.overlay.ui.OverlayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 夸克登录：WebView 走 PC 登录页（扫码 / 账号密码都由夸克自己处理），
 * 登录成功的唯一判据是 Cookie 里出现 `__pus` + `__puus`（云析 QuarkConstants.isValidCookie）。
 */
class QuarkLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OverlayTheme {
                QuarkLoginScreen(onClose = { finish() })
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun QuarkLoginScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CredentialStore.get(context) }
    val api = remember { QuarkApi() }
    val scope = rememberCoroutineScope()
    // 进页面时已有的 Cookie：如果它本来就有效（上次登录没退出），不要立刻自动关页，
    // 否则用户点「重新登录」会看到页面一闪而过
    val initialCookie = remember {
        CookieManager.getInstance().getCookie(QuarkConstants.COOKIE_DOMAIN).orEmpty()
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("登录完成后会自动保存并关闭") }

    fun readCookie(): String =
        CookieManager.getInstance().getCookie(QuarkConstants.COOKIE_DOMAIN).orEmpty()

    fun submit(cookie: String) {
        if (saving || !QuarkConstants.isValidCookie(cookie)) return
        saving = true
        status = "正在校验登录态…"
        scope.launch {
            // 昵称只是展示用，取不到不影响登录
            val nickname = runCatching { api.fetchNickname(cookie) }.getOrNull()
            store.saveQuark(cookie, nickname?.takeIf { it.isNotBlank() } ?: "夸克用户")
            CookieManager.getInstance().flush()
            Toast.makeText(context, "夸克登录成功", Toast.LENGTH_SHORT).show()
            FloatingService.refresh(context)
            (context as? Activity)?.finish()
        }
    }

    // Cookie 是异步落盘的：轮询而不是只依赖 onPageFinished，扫码确认后页面通常不会重新加载
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            val cookie = readCookie()
            if (cookie != initialCookie && QuarkConstants.isValidCookie(cookie)) {
                submit(cookie)
                break
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    BackHandler { onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(OverlayColors.PanelDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = OverlayColors.TextPrimary
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("登录夸克网盘", fontSize = 14.sp, color = OverlayColors.TextPrimary)
                Text("登录态仅保存在本机", fontSize = 9.sp, color = OverlayColors.TextMuted)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(OverlayColors.Accent)
                    .clickable { submit(readCookie()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("完成", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }

        if (saving) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = OverlayColors.Accent,
                    strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(6.dp))
                Text(status, fontSize = 10.sp, color = OverlayColors.TextSecondary)
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // 必须用 PC UA：移动页登录后拿到的 Cookie 与 drive-pc 接口不通用
                    settings.userAgentString = QuarkConstants.USER_AGENT
                    val web = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(web, true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            CookieManager.getInstance().flush()
                        }
                    }
                    loadUrl(QuarkConstants.LOGIN_URL)
                    webView = this
                }
            }
        )

        Text(
            text = status,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 9.sp,
            color = OverlayColors.TextMuted
        )
    }
}
