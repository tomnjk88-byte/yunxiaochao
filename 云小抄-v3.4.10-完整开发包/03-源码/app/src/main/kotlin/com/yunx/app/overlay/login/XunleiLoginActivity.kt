/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。登录时序完全沿用云析 XunleiAccountRepository：
 *   v3/login → (review_panel) sendsms → v3/smslogin → captcha/init → signin/token。
 */

package com.yunx.app.overlay.login

import androidx.activity.compose.LocalActivity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.network.XunleiLoginStep
import com.yunx.app.overlay.data.CredentialStore
import com.yunx.app.overlay.service.FloatingService
import com.yunx.app.overlay.ui.OverlayColors
import com.yunx.app.overlay.ui.OverlayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 迅雷登录：账号密码 / 短信验证码两条路。
 *
 * 注意：迅雷对「新设备首次登录」几乎必然回 `review_panel(1007)`，也就是密码正确也要再走一次短信。
 * 这不是错误分支，而是正常流程 —— 所以密码登录一旦返回 needSms，就直接切到短信模式并自动发码。
 */
class XunleiLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设备指纹必须先初始化：迅雷 pan 接口的 X-Device-Id 与 devicesign 都来自这里
        XunleiDeviceFingerprint.init(applicationContext)
        setContent {
            OverlayTheme {
                XunleiLoginScreen(onClose = { finish() })
            }
        }
    }
}

private enum class LoginMode { PASSWORD, SMS }

@Composable
private fun XunleiLoginScreen(onClose: () -> Unit) {
    // v2.11.1：校验类型转换改成 LocalActivity —— LocalContext 在预览/嵌套主题里
    // 不一定是 Activity，硬转会 ClassCastException；LocalActivity 只在这个 Activity 的
    // Composition 里非空，语义也对。
    val activity = LocalActivity.current as ComponentActivity
    val store = remember { CredentialStore.get(activity) }
    val api = remember { XunleiApi() }
    val deviceId = remember { XunleiApi.newDeviceId() }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(LoginMode.PASSWORD) }
    var mobile by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var creditKey by remember { mutableStateOf("") }
    var smsToken by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    BackHandler { onClose() }

    /** 拿到 sessionID 后换 token 并落库（captcha/init 必须先于 signin/token） */
    suspend fun finishWithStep(step: XunleiLoginStep) {
        val captcha = runCatching {
            api.initCaptcha(deviceId, mobile.trim(), userId = step.userID)
        }.getOrNull().orEmpty()

        val tokens = runCatching {
            api.exchangeToken(step.sessionId, deviceId, captcha)
        }.getOrNull()

        if (tokens == null) {
            message = "换取登录态失败，请重试"
            return
        }
        store.saveXunlei(
            accessToken = tokens.first,
            refreshToken = tokens.second,
            deviceId = deviceId,
            captchaToken = captcha,
            nickname = step.nickname.ifBlank { "迅雷用户" }
        )
        Toast.makeText(activity, "迅雷登录成功", Toast.LENGTH_SHORT).show()
        FloatingService.refresh(activity)
        activity.finish()
    }

    fun doPasswordLogin() {
        if (mobile.isBlank() || password.isBlank()) {
            message = "请填写账号和密码"
            return
        }
        scope.launch {
            loading = true
            message = ""
            try {
                val step = api.loginWithPassword(mobile.trim(), password, deviceId)
                when {
                    step.sessionId.isNotBlank() -> finishWithStep(step)

                    step.needSms -> {
                        // 触发风控：切短信验证，并主动发一条验证码
                        val sms = api.sendSms(mobile.trim(), deviceId)
                        creditKey = sms.smsCreditKey
                        smsToken = sms.smsToken
                        mode = LoginMode.SMS
                        countdown = 60
                        message = "账号验证通过，请输入短信验证码"
                    }

                    else -> message = step.message.ifBlank { "登录失败，请检查账号密码" }
                }
            } catch (e: Exception) {
                message = e.message ?: "登录失败"
            } finally {
                loading = false
            }
        }
    }

    fun sendSmsCode() {
        if (mobile.isBlank()) {
            message = "请先填写手机号"
            return
        }
        scope.launch {
            loading = true
            try {
                val step = api.sendSms(mobile.trim(), deviceId)
                creditKey = step.smsCreditKey
                smsToken = step.smsToken
                countdown = 60
                message = step.message.ifBlank { "验证码已发送" }
            } catch (e: Exception) {
                message = e.message ?: "验证码发送失败"
            } finally {
                loading = false
            }
        }
    }

    fun doSmsLogin() {
        if (mobile.isBlank() || code.isBlank()) {
            message = "请填写手机号和验证码"
            return
        }
        if (creditKey.isBlank() || smsToken.isBlank()) {
            message = "请先获取验证码"
            return
        }
        scope.launch {
            loading = true
            message = ""
            try {
                val step = api.smsLogin(
                    mobile = mobile.trim(),
                    smsCode = code.trim(),
                    creditKey = creditKey,
                    smsToken = smsToken,
                    deviceId = deviceId
                )
                if (step.sessionId.isBlank()) {
                    message = step.message.ifBlank { "验证码错误或已过期" }
                } else {
                    finishWithStep(step)
                }
            } catch (e: Exception) {
                message = e.message ?: "登录失败"
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(OverlayColors.PanelDark)
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
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
            Spacer(Modifier.width(8.dp))
            Column {
                Text("登录迅雷网盘", fontSize = 15.sp, color = OverlayColors.TextPrimary)
                Text("账号仅保存在本机，不上传", fontSize = 9.sp, color = OverlayColors.TextMuted)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeTab("账号密码", mode == LoginMode.PASSWORD) {
                mode = LoginMode.PASSWORD
                message = ""
            }
            ModeTab("短信验证码", mode == LoginMode.SMS) {
                mode = LoginMode.SMS
                message = ""
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = mobile,
            onValueChange = { mobile = it },
            label = { Text("手机号 / 账号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        if (mode == LoginMode.PASSWORD) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (countdown > 0 || loading) {
                                OverlayColors.PanelDarkElevated
                            } else {
                                OverlayColors.Accent
                            }
                        )
                        .clickable(enabled = countdown == 0 && !loading) { sendSmsCode() }
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = if (countdown > 0) "${countdown}s" else "获取验证码",
                        fontSize = 10.sp,
                        color = if (countdown > 0 || loading) OverlayColors.TextMuted else Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { if (mode == LoginMode.PASSWORD) doPasswordLogin() else doSmsLogin() },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OverlayColors.Accent)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White,
                    strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = if (mode == LoginMode.PASSWORD) "登录" else "验证并登录",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = OverlayColors.TextSecondary
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "提示：迅雷新设备首次登录通常需要短信二次验证；若收不到短信，可先用手机号 + 验证码直接登录。",
            fontSize = 9.sp,
            lineHeight = 13.sp,
            color = OverlayColors.TextMuted
        )
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) OverlayColors.Accent else OverlayColors.PanelDarkElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = if (selected) Color.White else OverlayColors.TextSecondary
        )
    }
}
