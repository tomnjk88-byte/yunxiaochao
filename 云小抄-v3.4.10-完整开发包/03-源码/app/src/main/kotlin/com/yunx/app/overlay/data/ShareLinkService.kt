/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.9「读取链接」的数据层：直接复用云析原版的
 * ShareLinkParser / ShareResolveRepository（夸克、UC、迅雷），
 * 把「打开分享 → 列目录 → 取直链」收敛成三个方法，UI 不用关心平台差异。
 */

package com.yunx.app.overlay.data

import android.content.Context
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分享链接读取服务（进程内单例）。
 *
 * 只支持夸克 / UC / 迅雷三家：它们的分享列表接口都允许**游客**访问，
 * 用户没登录也能先看清楚链接里有什么；其余平台请走「云析完整解析器」。
 *
 * 下载直链优先用登录态（不受游客 50MB 限制），没有登录态就退回游客取链。
 */
class ShareLinkService private constructor(context: Context) {

    private val app = context.applicationContext
    private val store = CredentialStore.get(app)
    private val quarkApi = QuarkApi()
    private val ucApi = UCApi()
    private val xunleiApi = XunleiApi()

    /** 已打开的分享：repo 与 session 绑定在一起，后续列目录 / 取链都复用它 */
    data class Opened(
        val platform: SharePlatform,
        val repo: ShareResolveRepository,
        val session: ShareSession,
        /** 夸克是 Cookie，迅雷是 access_token，游客为空串 */
        val cookie: String,
        val guest: Boolean,
        val link: String
    )

    suspend fun open(link: String, pwd: String?): Result<Opened> = withContext(Dispatchers.IO) {
        val parsed = ShareLinkParser.parse(link)
            ?: return@withContext Result.failure(IllegalArgumentException("没认出这个链接，粘贴夸克 / 迅雷的分享链接或口令试试"))
        val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
        val platform = parsed.platform
        when (platform) {
            SharePlatform.QUARK, SharePlatform.UC -> {
                val cookie = if (platform == SharePlatform.QUARK) {
                    store.quark()?.cookie.orEmpty()
                } else {
                    // 云小抄没有 UC 登录入口：固定走游客，能看能下（小文件）就够用
                    ""
                }
                val repo: ShareResolveRepository =
                    if (platform == SharePlatform.QUARK) QuarkResolveRepository(quarkApi)
                    else UCResolveRepository(ucApi)
                repo.createSession(link, effectivePwd, cookie).fold(
                    onSuccess = {
                        Result.success(Opened(platform, repo, it, cookie, cookie.isBlank(), link))
                    },
                    onFailure = { e ->
                        Result.failure(
                            IllegalStateException(e.message ?: "${platform.displayName}分享打开失败")
                        )
                    }
                )
            }

            SharePlatform.XUNLEI -> {
                val cred = store.xunlei()
                val token = cred?.accessToken.orEmpty()
                val repo = XunleiResolveRepository(
                    api = xunleiApi,
                    accountProvider = { cred?.accessToken },
                    deviceIdProvider = { cred?.deviceId },
                    captchaProvider = { cred?.captchaToken }
                )
                repo.createSession(link, effectivePwd, token).fold(
                    onSuccess = {
                        Result.success(Opened(platform, repo, it, token, token.isBlank(), link))
                    },
                    onFailure = { e ->
                        Result.failure(
                            IllegalStateException(e.message ?: "迅雷分享打开失败")
                        )
                    }
                )
            }

            else -> Result.failure(
                IllegalStateException(
                    "「${platform.displayName}」暂不支持在这里读取，点下方「用云析完整解析器打开」处理"
                )
            )
        }
    }

    suspend fun list(opened: Opened, dirFid: String): Result<List<ShareFile>> =
        withContext(Dispatchers.IO) {
            opened.repo.listFiles(opened.session, dirFid, opened.cookie).fold(
                onSuccess = { files ->
                    // 文件夹在前、图片和文件按自然序在后，和网盘里看到的一致
                    Result.success(
                        files.sortedWith(
                            compareByDescending<ShareFile> { it.isdir }
                                .thenComparator { a, b -> PanFiles.naturalCompare(a.fname, b.fname) }
                        )
                    )
                },
                onFailure = { e ->
                    Result.failure(IllegalStateException(e.message ?: "读取目录失败"))
                }
            )
        }

    /** 取下载直链：登录态优先（无 50MB 限制），失败再退回游客取链 */
    suspend fun downloadLink(opened: Opened, file: ShareFile): Result<DownloadLink> =
        withContext(Dispatchers.IO) {
            val login = if (opened.guest) {
                null
            } else {
                opened.repo.getShareDownloadLinkWithoutSave(opened.session, file, opened.cookie)
                    .getOrNull()
            }
            if (login != null) return@withContext Result.success(login)

            val guest = opened.repo.getGuestShareDownloadLink(opened.session, file).getOrNull()
            if (guest != null) return@withContext Result.success(guest)

            // 最后一种：迅雷这类「必须先转存」的平台
            opened.repo.getShareDownloadLink(opened.session, file, opened.cookie).fold(
                onSuccess = { Result.success(it) },
                onFailure = { e ->
                    Result.failure(
                        IllegalStateException(
                            when {
                                opened.guest -> "未登录下载失败：${e.message ?: "先登录网盘再试"}"
                                else -> e.message ?: "获取下载链接失败"
                            }
                        )
                    )
                }
            )
        }

    /**
     * 下载请求头。各平台的防盗链要求不同，这里只保留「不带就一定失败」的那几个。
     */
    fun headers(opened: Opened, link: DownloadLink): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        when (opened.platform) {
            SharePlatform.QUARK -> {
                val cookie = link.guestCookie.ifBlank { opened.cookie }
                if (cookie.isNotBlank()) map["Cookie"] = cookie
                map["User-Agent"] = QuarkConstants.API_USER_AGENT
                map["Referer"] = QuarkConstants.DOWNLOAD_REFERER
            }

            SharePlatform.UC -> {
                val cookie = link.guestCookie.ifBlank { opened.cookie }
                if (cookie.isNotBlank()) map["Cookie"] = cookie
                // UC 的网页端 UA 与游客 UA 一致（区别在 Cookie），直接统一即可
                map["User-Agent"] = UCConstants.GUEST_UA
                map["Referer"] = UCConstants.DOWNLOAD_REFERER
                map["Origin"] = UCConstants.WEB_ORIGIN
            }

            SharePlatform.XUNLEI -> {
                // 迅雷直链自带签名，只认官方 App UA（浏览器 UA 会被 CDN 降级）
                map["User-Agent"] = XunleiConstants.APP_UA
            }

            else -> Unit
        }
        return map
    }

    fun rootFid(platform: SharePlatform): String = when (platform) {
        SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
        SharePlatform.XUNLEI -> "0"
        else -> QuarkConstants.DEFAULT_PDIR_FID
    }

    companion object {
        @Volatile
        private var instance: ShareLinkService? = null

        fun of(context: Context): ShareLinkService = instance ?: synchronized(this) {
            instance ?: ShareLinkService(context.applicationContext).also { instance = it }
        }
    }
}
