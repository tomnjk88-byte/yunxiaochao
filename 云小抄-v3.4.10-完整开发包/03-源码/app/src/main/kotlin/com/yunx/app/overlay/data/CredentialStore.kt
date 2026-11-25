/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者）
 * Copyright (C) 2026 <你的名字>（悬浮窗 / 个人网盘浏览部分）
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。云析用 Room 存多账号，本应用只需要「当前平台 + 一份凭证」，
 * 因此改为轻量 SharedPreferences 存储，避免为一个字段引入 Room + KSP。
 */

package com.yunx.app.overlay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 凭证仓库（进程内单例）。
 *
 * 为什么用单例而不是把实例一层层传：悬浮窗 Service、登录 Activity、主页 Activity 分属三个
 * 组件，登录完成后要立刻让悬浮窗感知到状态变化 —— 一个共享的 [StateFlow] 是最短的路径。
 *
 * 安全说明：凭证落在 `MODE_PRIVATE` 的 SharedPreferences 里（仅本应用可读）。
 * 如果需要更强的静态加密，把 [read]/[write] 换成云析的 `AuthCrypto`（Android Keystore），
 * 接口不用动。**不要**把 Cookie / token 写进日志。
 */
class CredentialStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<PanSession> = _session.asStateFlow()

    // ---------------- 读取 ----------------

    fun quark(): PanCredential.Quark? = _session.value.credentials[PanPlatform.QUARK] as? PanCredential.Quark

    fun xunlei(): PanCredential.Xunlei? = _session.value.credentials[PanPlatform.XUNLEI] as? PanCredential.Xunlei

    // ---------------- 写入 ----------------

    @Synchronized
    fun setActivePlatform(platform: PanPlatform) {
        prefs.edit().putString(KEY_PLATFORM, platform.name).apply()
        _session.value = _session.value.copy(platform = platform)
    }

    @Synchronized
    fun saveQuark(cookie: String, nickname: String) {
        prefs.edit()
            .putString(KEY_QUARK_COOKIE, cookie)
            .putString(KEY_QUARK_NICK, nickname)
            // 刚登录完的平台通常就是用户想看的平台，直接切过去，省一次手动切换
            .putString(KEY_PLATFORM, PanPlatform.QUARK.name)
            .apply()
        publish()
    }

    @Synchronized
    fun saveXunlei(
        accessToken: String,
        refreshToken: String,
        deviceId: String,
        captchaToken: String,
        nickname: String,
        authType: String = ""
    ) {
        prefs.edit()
            .putString(KEY_XL_ACCESS, accessToken)
            .putString(KEY_XL_REFRESH, refreshToken)
            .putString(KEY_XL_DEVICE, deviceId)
            .putString(KEY_XL_CAPTCHA, captchaToken)
            .putString(KEY_XL_NICK, nickname)
            .putString(KEY_XL_AUTH_TYPE, authType)
            .putString(KEY_PLATFORM, PanPlatform.XUNLEI.name)
            .apply()
        publish()
    }

    /**
     * 夸克响应 Set-Cookie 回写（`__puus` 约 3 小时过期）。
     *
     * 只在「本次请求用的正是当前这份 Cookie」时才写：多账号/切换账号时旧请求的回包
     * 可能晚到，把它的 `__puus` 混进新账号会让签名与 uid 对不上（取链 412）。
     */
    @Synchronized
    fun updateQuarkCookie(requestCookie: String, merged: String) {
        val current = quark() ?: return
        if (current.cookie != requestCookie || current.cookie == merged) return
        prefs.edit()
            .putString(KEY_QUARK_COOKIE, merged)
            .putString(KEY_QUARK_NICK, current.nickname)
            .apply()
        publish()
    }

    @Synchronized
    fun updateXunleiTokens(accessToken: String, refreshToken: String) {
        val current = xunlei() ?: return
        prefs.edit()
            .putString(KEY_XL_ACCESS, accessToken)
            .putString(KEY_XL_REFRESH, refreshToken)
            .apply()
        publish()
    }

    @Synchronized
    fun clear(platform: PanPlatform) {
        when (platform) {
            PanPlatform.QUARK -> prefs.edit()
                .remove(KEY_QUARK_COOKIE)
                .remove(KEY_QUARK_NICK)
                .apply()

            PanPlatform.XUNLEI -> prefs.edit()
                .remove(KEY_XL_ACCESS)
                .remove(KEY_XL_REFRESH)
                .remove(KEY_XL_DEVICE)
                .remove(KEY_XL_CAPTCHA)
                .remove(KEY_XL_NICK)
                .remove(KEY_XL_AUTH_TYPE)
                .apply()
        }
        publish()
    }

    // ---------------- 内部 ----------------

    private fun publish() {
        _session.value = readSession()
    }

    private fun readSession(): PanSession {
        val map = buildMap<PanPlatform, PanCredential> {
            val cookie = prefs.getString(KEY_QUARK_COOKIE, null).orEmpty()
            if (cookie.isNotBlank()) {
                put(
                    PanPlatform.QUARK,
                    PanCredential.Quark(
                        cookie = cookie,
                        nickname = prefs.getString(KEY_QUARK_NICK, null).orEmpty().ifBlank { "夸克用户" }
                    )
                )
            }
            val access = prefs.getString(KEY_XL_ACCESS, null).orEmpty()
            if (access.isNotBlank()) {
                put(
                    PanPlatform.XUNLEI,
                    PanCredential.Xunlei(
                        accessToken = access,
                        refreshToken = prefs.getString(KEY_XL_REFRESH, null).orEmpty(),
                        deviceId = prefs.getString(KEY_XL_DEVICE, null).orEmpty(),
                        captchaToken = prefs.getString(KEY_XL_CAPTCHA, null).orEmpty(),
                        authType = prefs.getString(KEY_XL_AUTH_TYPE, null).orEmpty(),
                        nickname = prefs.getString(KEY_XL_NICK, null).orEmpty().ifBlank { "迅雷用户" }
                    )
                )
            }
        }
        return PanSession(
            platform = PanPlatform.fromKey(prefs.getString(KEY_PLATFORM, null)),
            credentials = map
        )
    }

    companion object {
        private const val PREF_NAME = "pan_credentials"

        private const val KEY_PLATFORM = "active_platform"
        private const val KEY_QUARK_COOKIE = "quark_cookie"
        private const val KEY_QUARK_NICK = "quark_nickname"
        private const val KEY_XL_ACCESS = "xunlei_access"
        private const val KEY_XL_REFRESH = "xunlei_refresh"
        private const val KEY_XL_DEVICE = "xunlei_device"
        private const val KEY_XL_CAPTCHA = "xunlei_captcha"
        private const val KEY_XL_NICK = "xunlei_nickname"
        private const val KEY_XL_AUTH_TYPE = "xunlei_auth_type"

        @Volatile
        private var instance: CredentialStore? = null

        fun get(context: Context): CredentialStore =
            instance ?: synchronized(this) {
                instance ?: CredentialStore(context).also { instance = it }
            }
    }
}
