/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.0 主界面「登录 / 个人中心」的本地占位账号。
 *
 * 重要：这是一个**纯本地的个人中心**，不是云账号体系。
 *   - 账号 / 密码随便输入即可登录，不做任何格式校验、不联网、不请求任何服务器；
 *   - 密码只在当前界面的内存里存在，绝不落盘（连 SharedPreferences 都不写）；
 *   - 只把「昵称 + 登录时间 + 是否已登录」存在本机，用来让个人中心有内容可显示；
 *   - 退出登录只清本机标志，不影响小抄、连招、网盘凭证等任何其它数据。
 */

package com.yunx.app.overlay.data

import android.content.Context

class UserAccount(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("user_account", Context.MODE_PRIVATE)

    /** 是否已登录（本机标志，默认未登录） */
    val loggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED, false)

    /** 昵称：随便输，空则给个默认值「玩家」 */
    val username: String
        get() = prefs.getString(KEY_NAME, null).orEmpty().ifBlank { DEFAULT_NAME }

    /** 登录时间（毫秒）；0 表示还没登录过 */
    val loginAt: Long
        get() = prefs.getLong(KEY_LOGIN_AT, 0L)

    /** 是否已勾选同意协议（登录页用，协议页可随时再看） */
    var agreedProtocol: Boolean
        get() = prefs.getBoolean(KEY_AGREED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGREED, value).apply()
        }

    /** 由昵称算出的稳定短代号，只用于个人中心展示，不是真实账号 ID */
    val userId: String
        get() {
            val hex = Integer.toHexString(username.hashCode()).uppercase()
            return "YX-" + hex.takeLast(6).padStart(6, '0')
        }

    /**
     * 登录：账号随便输，密码完全不参与判断、也不保存。
     * 这是自用 App 的「个人中心」占位，不代表任何真实身份认证。
     */
    fun login(name: String) {
        prefs.edit()
            .putBoolean(KEY_LOGGED, true)
            .putString(KEY_NAME, name.trim().ifBlank { DEFAULT_NAME })
            .putLong(KEY_LOGIN_AT, System.currentTimeMillis())
            .apply()
    }

    /** 退出登录：只翻本机标志，昵称留着方便下次一键登回来 */
    fun logout() {
        prefs.edit()
            .putBoolean(KEY_LOGGED, false)
            .putLong(KEY_LOGIN_AT, 0L)
            .apply()
    }

    companion object {
        const val DEFAULT_NAME = "玩家"

        private const val KEY_LOGGED = "logged_in"
        private const val KEY_NAME = "username"
        private const val KEY_LOGIN_AT = "login_at"
        private const val KEY_AGREED = "agreed_protocol"
    }
}
