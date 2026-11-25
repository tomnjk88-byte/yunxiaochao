/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。把「分类 + 最近浏览过的目录」落盘缓存，
 * 悬浮窗重建（切游戏、被杀进程）时先出内容再补网络请求，解决白屏等待。
 */

package com.yunx.app.overlay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 极简 JSON 快照缓存。
 *
 * 只缓存**非敏感**的目录结构（文件名、缩略图地址），不碰 Cookie / token：
 * 凭证由 [CredentialStore] 单独保存。缩略图地址本身带时效，所以存的时候记时间戳，
 * 超过 [MAX_AGE_MS] 就当没有，避免拿过期地址一直 403。
 */
class SnapshotStore(
    context: Context,
    /** 快照有效期；高性能模式会传一个更大的值，避免「半小时没开就重新联网」 */
    private val maxAgeMs: () -> Long = { DEFAULT_MAX_AGE_MS },
    /** 最多留几个目录；高性能模式放宽到 48 个 */
    private val maxFolders: () -> Int = { DEFAULT_MAX_FOLDERS }
) {

    private val file = File(context.applicationContext.filesDir, CACHE_FILE)
    private val lock = Any()
    private var root: JSONObject? = null

    /**
     * v1.9.6：把 JSON 提前读进内存。
     *
     * 以前第一次 `categories()` 才顺手 `readText()` + 解析，正好卡在「点开小抄」那一帧；
     * 现在 Service 一启动就丢到 IO 线程预热，真正打开窗口时这里只是一个内存命中。
     */
    fun warmUp() = synchronized(lock) { root() }

    fun categories(platform: PanPlatform): List<PanFolder>? = synchronized(lock) {
        val obj = platformObj(platform) ?: return null
        if (isStale(obj)) return null
        obj.optJSONArray("categories")?.toFolders()
    }

    fun saveCategories(platform: PanPlatform, folders: List<PanFolder>) = synchronized(lock) {
        val obj = platformObj(platform) ?: JSONObject().also { root().put(platform.name, it) }
        obj.put("categories", folders.foldersJson())
        obj.put("ts", System.currentTimeMillis())
        persist()
    }

    fun content(platform: PanPlatform, folderId: String): FolderContent? = synchronized(lock) {
        val foldersObj = platformObj(platform)?.optJSONObject("folders") ?: return null
        val obj = foldersObj.optJSONObject(folderId) ?: return null
        if (isStale(obj)) return null
        FolderContent(
            folders = obj.optJSONArray("folders")?.toFolders().orEmpty(),
            images = obj.optJSONArray("images")?.toImages().orEmpty()
        )
    }

    fun saveContent(platform: PanPlatform, folderId: String, content: FolderContent) = synchronized(lock) {
        val platformObj = platformObj(platform) ?: JSONObject().also { root().put(platform.name, it) }
        val folders = platformObj.optJSONObject("folders")
            ?: JSONObject().also { platformObj.put("folders", it) }
        folders.put(
            folderId,
            JSONObject()
                .put("folders", content.folders.foldersJson())
                .put("images", content.images.imagesJson())
                .put("ts", System.currentTimeMillis())
        )
        trim(folders)
        persist()
    }

    fun clear() = synchronized(lock) {
        root = JSONObject()
        runCatching { file.delete() }
    }

    // ------------------------------------------------------------------

    private fun platformObj(platform: PanPlatform): JSONObject? =
        root().optJSONObject(platform.name)

    private fun isStale(obj: JSONObject): Boolean {
        val ts = obj.optLong("ts", 0L)
        return ts <= 0L || System.currentTimeMillis() - ts > maxAgeMs()
    }

    private fun root(): JSONObject = root ?: run {
        val loaded = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
        root = loaded
        loaded
    }

    /** 目录缓存只留最近 N 个，防止逛得多了文件无限长 */
    private fun trim(folders: JSONObject) {
        val limit = maxFolders()
        if (folders.length() <= limit) return
        val keys = folders.keys().asSequence()
            .sortedBy { folders.optJSONObject(it)?.optLong("ts", 0L) ?: 0L }
            .toList()
        keys.take(folders.length() - limit).forEach { folders.remove(it) }
    }

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(root().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root().toString())
                tmp.delete()
            }
        }
    }

    private fun List<PanFolder>.foldersJson(): JSONArray = JSONArray().also { arr ->
        forEach { folder ->
            arr.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("parentId", folder.parentId)
            )
        }
    }

    private fun List<PanImage>.imagesJson(): JSONArray = JSONArray().also { arr ->
        forEach { image ->
            arr.put(
                JSONObject()
                    .put("id", image.id)
                    .put("name", image.name)
                    .put("size", image.size)
                    .put("thumbUrl", image.thumbUrl.orEmpty())
                    .put(
                        "thumbHeaders",
                        JSONObject().also { h ->
                            image.thumbHeaders.forEach { (k, v) -> h.put(k, v) }
                        }
                    )
            )
        }
    }

    private fun JSONArray.toFolders(): List<PanFolder> = buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            add(
                PanFolder(
                    id = id,
                    name = obj.optString("name"),
                    parentId = obj.optString("parentId")
                )
            )
        }
    }

    private fun JSONArray.toImages(): List<PanImage> = buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val headers = mutableMapOf<String, String>()
            obj.optJSONObject("thumbHeaders")?.let { h ->
                h.keys().forEach { key -> headers[key] = h.optString(key) }
            }
            add(
                PanImage(
                    id = id,
                    name = obj.optString("name"),
                    size = obj.optLong("size"),
                    thumbUrl = obj.optString("thumbUrl").takeIf { it.isNotBlank() },
                    thumbHeaders = headers
                )
            )
        }
    }

    companion object {
        const val CACHE_FILE = "overlay_cache.json"
        const val DEFAULT_MAX_AGE_MS = 30 * 60_000L
        const val DEFAULT_MAX_FOLDERS = 24

        /** v1.9.6 高性能模式：快照留 12 小时、48 个目录 */
        const val HIGH_PERF_MAX_AGE_MS = 12 * 60 * 60_000L
        const val HIGH_PERF_MAX_FOLDERS = 48
    }
}
