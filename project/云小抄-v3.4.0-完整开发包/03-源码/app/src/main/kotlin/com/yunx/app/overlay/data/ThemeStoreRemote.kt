/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.4.2 新增。SukiSU Nova 在线主题商店（sukisunova 主题镜像）。
 *
 * 为什么单独一个文件：
 *   这套商店不是「一个 json 列表 + 链接」那么简单 —— 它要求先申请一次性设备
 *   凭证（POST client/access），之后每个请求都带 X-Sukisu-Theme-Token，
 *   凭证过期还要用 refreshToken 换新的。把这些鉴权细节塞进 ThemePacks
 *   会让那个文件从「包格式」变成「网络客户端」，所以拆出来。
 *
 * 接口（对 themes.oddnexus.top 实测）：
 *   POST {base}/client/access   {"name":"..."}  → accessToken / refreshToken / expiresAt
 *   POST {base}/client/refresh   {"refreshToken":"..."} → 新凭证
 *   GET  {base}/catalog.json    header X-Sukisu-Theme-Token → 主题目录
 *   GET  {base}/{path}          header X-Sukisu-Theme-Token → .sukisu 包
 *
 * R18 过滤：目录里每条都带 r18 布尔值和 tags（如 ["r18"]）。两者任一为真、
 * 或名字以「18+」开头，一律不进列表 —— 用户明确要求过滤掉。
 */

package com.yunx.app.overlay.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 在线商店里的一条主题。字段与 catalog.json 对齐，只留界面要用的。 */
data class RemoteTheme(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val version: Int,
    val size: Long,
    val sha256: String,
    val path: String,
    val previewPath: String,
    val screenshotPaths: List<String>,
    val downloads: Long,
    val heat: Long,
    val adaptive: Boolean,
    val r18: Boolean
)

/** 一次拉取的结果：主题列表 + 被 R18 过滤掉的数量（界面上说一声，别让人以为包少了） */
data class RemoteCatalog(
    val baseUrl: String,
    val themes: List<RemoteTheme>,
    val filteredR18: Int
)

object ThemeStoreRemote {

    /**
     * 官方内置镜像。顺序与 SukiSU Nova 管理器一致：国际站优先，
     * 大陆三个节点按 主 → 备 → 三 顺序试。全部失败才报错。
     */
    private val BASE_URLS = listOf(
        "https://themes.oddnexus.top/",
        "https://themes.neko.shirkneko.top:8443/",
        "https://themes.zako.shirkneko.top:20005/"
    )

    private const val TOKEN_HEADER = "X-Sukisu-Theme-Token"
    private const val PREFS = "yunx_theme_store"
    private const val MAX_CATALOG = 8L * 1024 * 1024
    private const val MAX_PACKAGE = 64L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    /**
     * 拉在线目录。会按镜像顺序自动重试，返回第一个成功的。
     * 阻塞 IO，调用方负责放到 Dispatchers.IO。
     */
    fun load(context: Context): RemoteCatalog {
        var last: Exception? = null
        for (base in BASE_URLS) {
            try {
                val token = ensureToken(context, base)
                val raw = getCatalog(base, token)
                    ?: run {
                        // token 被服务端撤销 / 过期：清掉重领一次
                        clearToken(context, base)
                        getCatalog(base, ensureToken(context, base))
                    }
                    ?: throw IOException("无权访问主题目录")
                return parseCatalog(base, raw)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("主题商店连不上")
    }

    private fun getCatalog(base: String, token: String): String? {
        val request = Request.Builder()
            .url(base + "catalog.json")
            .header(TOKEN_HEADER, token)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            if (body.contentLength() > MAX_CATALOG) throw IOException("目录过大")
            val text = body.string()
            if (text.length > MAX_CATALOG) throw IOException("目录过大")
            return text
        }
    }

    private fun parseCatalog(base: String, raw: String): RemoteCatalog {
        val root = JSONObject(raw)
        if (root.optBoolean("maintenance", false)) throw IOException("主题商店维护中")
        val arr = root.optJSONArray("themes") ?: JSONArray()
        val all = ArrayList<RemoteTheme>(arr.length())
        var filtered = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = RemoteTheme(
                id = o.optString("id"),
                name = o.optString("name").ifBlank { "未命名主题" },
                author = o.optString("author").ifBlank { "佚名" },
                description = o.optString("description"),
                tags = o.optJSONArray("tags")?.let { tags ->
                    buildList { for (j in 0 until tags.length()) tags.optString(j).takeIf { it.isNotBlank() }?.let { add(it) } }
                } ?: emptyList(),
                version = o.optInt("version", 1),
                size = o.optLong("size", 0),
                sha256 = o.optString("sha256"),
                path = o.optString("path"),
                previewPath = o.optString("previewPath"),
                screenshotPaths = o.optJSONArray("screenshotPaths")?.let { a ->
                    buildList { for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let { add(it) } }
                } ?: emptyList(),
                downloads = o.optLong("downloads", 0),
                heat = o.optLong("heat", 0),
                adaptive = o.optBoolean("adaptive", false),
                r18 = o.optBoolean("r18", false)
            )
            if (isR18(item)) {
                filtered++
            } else {
                all += item
            }
        }
        // 热门的排前面：下载量 + 热度，跟商店主页的默认排序一致
        all.sortWith(compareByDescending<RemoteTheme> { it.heat }.thenByDescending { it.downloads })
        return RemoteCatalog(baseUrl = base, themes = all, filteredR18 = filtered)
    }

    /**
     * R18 判定。catalog 里的 r18 字段是最权威的；tags 里带 r18 / nsfw /
     * 18+ 的也算；少数投稿没打标但名字直接写「18+」，一并挡掉。
     */
    fun isR18(theme: RemoteTheme): Boolean {
        if (theme.r18) return true
        if (theme.tags.any { it.equals("r18", true) || it.equals("nsfw", true) || it.contains("18+") }) {
            return true
        }
        val name = theme.name.trim()
        return name.startsWith("18+") || name.contains("R18", true) || name.contains("NSFW", true)
    }

    /** 封面 / 截图的完整 URL；目录里的 path 是相对路径 */
    fun assetUrl(baseUrl: String, path: String): String? {
        if (path.isBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 下载一个 .sukisu 包并校验 SHA-256。返回文件字节。
     * 校验不过直接抛异常 —— 宁可报错，也不要把半个包喂给导入器。
     */
    fun download(context: Context, theme: RemoteTheme): ByteArray {
        var last: Exception? = null
        for (b in BASE_URLS) {
            try {
                val token = ensureToken(context, b)
                val bytes = getPackage(b, token, theme.path)
                    ?: run {
                        clearToken(context, b)
                        getPackage(b, ensureToken(context, b), theme.path) ?: throw IOException("无权访问")
                    }
                val expected = theme.sha256.trim().lowercase()
                if (expected.length == 64) {
                    val actual = sha256(bytes)
                    if (actual != expected) throw IOException("文件校验失败")
                }
                return bytes
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("下载失败")
    }

    private fun getPackage(base: String, token: String, path: String): ByteArray? {
        if (path.contains("..") || path.startsWith("http")) throw IOException("包路径不合法")
        val request = Request.Builder()
            .url(base + path.trimStart('/'))
            .header(TOKEN_HEADER, token)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            if (body.contentLength() > MAX_PACKAGE) throw IOException("包过大")
            val stream = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_PACKAGE) throw IOException("包过大")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------------
    // 设备凭证
    // ------------------------------------------------------------------

    /**
     * 拿到可用 token：缓存有效就用缓存，过期用 refreshToken 换，
     * 都没有（或刷新失败）就重新申请一个只读设备凭证。
     */
    private fun ensureToken(context: Context, base: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "k_" + Integer.toHexString(base.hashCode())
        val now = System.currentTimeMillis() / 1000
        val token = prefs.getString("${key}_token", null)
        val exp = prefs.getLong("${key}_exp", 0)
        val refresh = prefs.getString("${key}_refresh", null)
        val refreshExp = prefs.getLong("${key}_refresh_exp", 0)

        if (!token.isNullOrBlank() && exp > now + 60) return token
        if (!refresh.isNullOrBlank() && refreshExp > now + 60) {
            runCatching { requestToken(base, "{\"refreshToken\":\"$refresh\"}", "client/refresh") }
                .getOrNull()?.let { save(prefs, key, it); return it.accessToken }
        }
        val fresh = requestToken(base, "{\"name\":\"YunX Android\"}", "client/access")
        save(prefs, key, fresh)
        return fresh.accessToken
    }

    private data class Access(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val refreshExpiresAt: Long
    )

    private fun requestToken(base: String, json: String, path: String): Access {
        val request = Request.Builder()
            .url(base + path)
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "SukiSUThemeAdmin")
            .header("Cache-Control", "no-store")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("申请凭证失败：HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            val o = JSONObject(text)
            val access = o.optString("accessToken")
            if (access.isBlank()) throw IOException("凭证为空")
            return Access(
                accessToken = access,
                refreshToken = o.optString("refreshToken"),
                expiresAt = o.optLong("expiresAt", 0),
                refreshExpiresAt = o.optLong("refreshExpiresAt", 0)
            )
        }
    }

    private fun save(prefs: android.content.SharedPreferences, key: String, a: Access) {
        prefs.edit()
            .putString("${key}_token", a.accessToken)
            .putLong("${key}_exp", a.expiresAt)
            .putString("${key}_refresh", a.refreshToken)
            .putLong("${key}_refresh_exp", a.refreshExpiresAt)
            .apply()
    }

    private fun clearToken(context: Context, base: String) {
        val key = "k_" + Integer.toHexString(base.hashCode())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("${key}_token")
            .remove("${key}_exp")
            .apply()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
