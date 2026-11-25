/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.3.0）。多套准星配置。
 *
 * 为什么这么存：
 *   · 每套准星都是「一张图 + 一个锚点 + 一组外观参数」，用户换游戏 / 换枪时
 *     只想切一下，不想每次重新导入、重新点锚点；
 *   · 悬浮层（FloatingService）读的仍是 OverlayPrefs 里那组 crosshair_* 单值快照，
 *     切套时由界面把选中的那套写回快照 —— Service 完全不用知道「多套」这回事，
 *     少一层改动就少一类「切了没生效」的坑；
 *   · 列表本身存成 JSON 字符串，Android 自带的 org.json 足够，不引额外依赖。
 */

package com.yunx.app.overlay.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一套准星配置。字段都是可变的：界面改一下就直接改对象，再整体落盘 */
data class CrosshairProfile(
    val id: String,
    var name: String,
    var imagePath: String? = null,
    var anchorX: Float = 0.5f,
    var anchorY: Float = 0.5f,
    /** 显示宽度 px；0 = 按图片比例自动 */
    var widthPx: Int = 0,
    var alpha: Float = 1f,
    var showLines: Boolean = true
) {
    val hasImage: Boolean get() = !imagePath.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("image", imagePath ?: "")
        put("ax", anchorX.toDouble())
        put("ay", anchorY.toDouble())
        put("w", widthPx)
        put("alpha", alpha.toDouble())
        put("lines", showLines)
    }

    companion object {
        fun fromJson(o: JSONObject): CrosshairProfile = CrosshairProfile(
            id = o.optString("id").takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString(),
            name = o.optString("name").takeIf { it.isNotBlank() } ?: "准星",
            imagePath = o.optString("image").takeIf { it.isNotBlank() },
            anchorX = safeUnit(o.optDouble("ax", 0.5).toFloat(), 0.5f),
            anchorY = safeUnit(o.optDouble("ay", 0.5).toFloat(), 0.5f),
            widthPx = o.optInt("w", 0).coerceAtLeast(0),
            alpha = o.optDouble("alpha", 1.0).toFloat()
                .let { if (it.isNaN() || it.isInfinite()) 1f else it.coerceIn(0.15f, 1f) },
            showLines = o.optBoolean("lines", true)
        )

        private fun safeUnit(v: Float, fallback: Float): Float =
            if (v.isNaN() || v.isInfinite()) fallback else v.coerceIn(0f, 1f)
    }
}

object CrosshairProfiles {

    /** 最多存这么多套。再多列表也翻不过来，还白占内存 */
    const val MAX = 24

    /**
     * 读列表。老版本只有一套，升级上来时把它迁成第一套，用户不会「打开就空了」。
     * 永远返回至少一项。
     */
    fun load(prefs: OverlayPrefs): MutableList<CrosshairProfile> {
        val list = mutableListOf<CrosshairProfile>()
        val raw = prefs.crosshairProfiles
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list += CrosshairProfile.fromJson(it) }
                }
            }
        }
        list.removeAll { it.id.isBlank() }
        // 去重：JSON 被手改过 / 并发写过时可能出现同 id，保留第一个
        val seen = mutableSetOf<String>()
        val dedup = list.filter { seen.add(it.id) }.take(MAX).toMutableList()

        if (dedup.isEmpty()) {
            dedup += CrosshairProfile(
                id = UUID.randomUUID().toString(),
                name = "默认准星",
                imagePath = prefs.crosshairImage,
                anchorX = prefs.crosshairAnchorX,
                anchorY = prefs.crosshairAnchorY,
                widthPx = prefs.crosshairWidth,
                alpha = prefs.crosshairAlpha,
                showLines = prefs.crosshairLines
            )
            save(prefs, dedup)
        }
        return dedup
    }

    fun save(prefs: OverlayPrefs, list: List<CrosshairProfile>) {
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it.toJson()) }
        prefs.crosshairProfiles = arr.toString()
    }

    /** 当前选中的那套；activeId 失效（被删 / 老档）时落回第一套 */
    fun active(prefs: OverlayPrefs, list: List<CrosshairProfile>): CrosshairProfile {
        val id = prefs.crosshairActiveId
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    /** 把选中的那套写进 Service 读的单值快照 */
    fun applyToPrefs(prefs: OverlayPrefs, p: CrosshairProfile) {
        prefs.crosshairImage = p.imagePath
        prefs.crosshairAnchorX = p.anchorX
        prefs.crosshairAnchorY = p.anchorY
        prefs.crosshairWidth = p.widthPx
        prefs.crosshairAlpha = p.alpha
        prefs.crosshairLines = p.showLines
        prefs.crosshairActiveId = p.id
    }

    /** 反向：把用户刚改的单值快照写回这套配置 */
    fun syncFromPrefs(prefs: OverlayPrefs, p: CrosshairProfile) {
        p.imagePath = prefs.crosshairImage
        p.anchorX = prefs.crosshairAnchorX
        p.anchorY = prefs.crosshairAnchorY
        p.widthPx = prefs.crosshairWidth
        p.alpha = prefs.crosshairAlpha
        p.showLines = prefs.crosshairLines
    }

    /** 新套的默认名：「准星 2 / 3 / …」，跳过已占用的编号 */
    fun nextName(list: List<CrosshairProfile>): String {
        val used = list.map { it.name }.toSet()
        var n = list.size + 1
        while (used.contains("准星 $n")) n++
        return "准星 $n"
    }

    fun newProfile(list: List<CrosshairProfile>): CrosshairProfile = CrosshairProfile(
        id = UUID.randomUUID().toString(),
        name = nextName(list),
        imagePath = null,
        anchorX = 0.5f,
        anchorY = 0.5f,
        widthPx = 0,
        alpha = 1f,
        showLines = true
    )
}
