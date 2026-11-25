/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.1）。镜像同步源的配置与持久化。
 */

package com.yunx.app.overlay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个「作者分享链接」的同步配置。
 *
 * 为什么不直接复用 `OverlayPrefs` 里的几个字段：作者可能开好几个分享（一份主线攻略、
 * 一份活动图），每个都可以只同步其中几个分类，塞进 SharedPreferences 会变成
 * `mirror_1_link` / `mirror_2_link` 这种没法维护的东西。
 *
 * @param id           链接的短哈希，删改时用来定位
 * @param link         用户粘贴的原始分享文本（可能带口令，解析交给 ShareLinkParser）
 * @param pwd          提取码；链接里自带的会先被解析出来，这里只存用户额外填的
 * @param folderNames  只同步这几个分类；空 = 全部。分类名是分享里的文件夹名
 * @param lastSyncAt   上次成功同步的时间
 * @param lastMessage  上次同步的结果，直接展示给用户，不再二次翻译
 */
data class MirrorSource(
    val id: String,
    val link: String,
    val pwd: String = "",
    val folderNames: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastMessage: String = ""
) {
    /** 展示用：把链接缩成 `https://pan.quark.cn/s/abcd…`，太长的整串会把行撑爆 */
    val shortLink: String
        get() {
            val trimmed = link.trim()
            val cut = trimmed.indexOfFirst { it == ' ' || it == '\n' }
            val head = if (cut > 0) trimmed.substring(0, cut) else trimmed
            return if (head.length <= 42) head else head.take(38) + "…"
        }

    val scopeLabel: String
        get() = if (folderNames.isEmpty()) "全部分类" else folderNames.joinToString("、")

    companion object {
        /** 用链接算短 id：同一个链接重复添加会落在同一条配置上，不会出现两份一样的源 */
        fun idOf(link: String): String =
            Integer.toHexString(link.trim().hashCode())

        fun fromJson(obj: JSONObject): MirrorSource? {
            val link = obj.optString("link").takeIf { it.isNotBlank() } ?: return null
            val names = ArrayList<String>()
            obj.optJSONArray("folders")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
            return MirrorSource(
                id = obj.optString("id").ifBlank { idOf(link) },
                link = link,
                pwd = obj.optString("pwd"),
                folderNames = names,
                createdAt = obj.optLong("created"),
                lastSyncAt = obj.optLong("synced"),
                lastMessage = obj.optString("msg")
            )
        }

        fun toJson(source: MirrorSource): JSONObject = JSONObject()
            .put("id", source.id)
            .put("link", source.link)
            .put("pwd", source.pwd)
            .put("folders", JSONArray().apply { source.folderNames.forEach { put(it) } })
            .put("created", source.createdAt)
            .put("synced", source.lastSyncAt)
            .put("msg", source.lastMessage)
    }
}

/**
 * 镜像源存储（进程内单例）。
 *
 * 和 `GuideDownloader.OptInStore` 同一套写法：小 JSON 文件、内存常驻、写时先写临时文件
 * 再 rename —— 同步过程中被杀进程也不会留下半个 JSON 让下次启动读崩。
 */
class MirrorStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val items = LinkedHashMap<String, MirrorSource>()

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                MirrorSource.fromJson(obj)?.let { items[it.id] = it }
            }
        }
    }

    fun all(): List<MirrorSource> = items.values.toList()

    fun find(id: String): MirrorSource? = items[id]

    /**
     * 新增或覆盖。同一链接重复添加时保留原来的「上次同步时间」，
     * 只更新分类范围与提取码 —— 用户改一下范围不该让同步记录清零。
     */
    fun put(source: MirrorSource): MirrorSource {
        val old = items[source.id]
        val merged = source.copy(
            createdAt = old?.createdAt ?: source.createdAt.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            lastSyncAt = old?.lastSyncAt ?: 0L,
            lastMessage = old?.lastMessage.orEmpty()
        )
        items[merged.id] = merged
        flush()
        return merged
    }

    fun remove(id: String) {
        if (items.remove(id) != null) flush()
    }

    fun markSynced(id: String, message: String) {
        val old = items[id] ?: return
        items[id] = old.copy(lastSyncAt = System.currentTimeMillis(), lastMessage = message)
        flush()
    }

    fun markFailed(id: String, message: String) {
        val old = items[id] ?: return
        items[id] = old.copy(lastMessage = message)
        flush()
    }

    private fun flush() {
        runCatching {
            val arr = JSONArray()
            items.values.forEach { arr.put(MirrorSource.toJson(it)) }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val FILE_NAME = "mirror_sources.json"

        @Volatile
        private var instance: MirrorStore? = null

        fun of(context: Context): MirrorStore = instance ?: synchronized(this) {
            instance ?: MirrorStore(context.applicationContext).also { instance = it }
        }
    }
}
