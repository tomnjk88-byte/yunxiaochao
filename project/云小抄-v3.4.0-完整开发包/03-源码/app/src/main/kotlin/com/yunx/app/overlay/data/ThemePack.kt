/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：v3.3.0 新增。主题包 —— 商店里那种「一套皮肤一个文件」的东西。
 *
 * 为什么放在 data 层而不是 ui 层：
 *   主题包本身是**一串参数 + 一张封面**，跟 Compose 没有半点关系。
 *   这里只负责「读得进来、存得下去、长得像不像一个包」；
 *   怎么把这些参数变成颜色和圆角，归 ui 层的 ThemeTuning 管。
 *   所以本文件里出现的是 tuning 的 JSON 原文，不是 ThemeTuning 对象 ——
 *   数据层不反向依赖界面层，这条线从 v2.11 就划下了。
 *
 * 支持的两种文件：
 *   1. 单个 .json —— 商店里点「导出」拿到的那段文本，另存为文件即可；
 *   2. .zip      —— 里面放 theme.json（必需）+ cover.png / cover.jpg（可选封面）。
 *      也认「根目录随便一个 .json」，方便别人把自己那套直接压包发出来。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * 主题包带出来的图片资源（v3.4.0）。
 *
 * 云小抄自己的包只有一段参数，画出来就是渐变；SukiSU 那种包装的是**图**，
 * 参数只是配图用的。所以这里存的都是本机绝对路径 —— 导入时把图片从包里
 * 抠出来搬进私有目录，用户删了原文件也不会突然变成一片空白。
 *
 * 全部可空：老包没有任何图片，字段就是空的，界面照旧走渐变。
 */
data class ThemeAssets(
    /** 整屏背景图 */
    val background: String? = null,
    /** 卡片底图 */
    val cardBackground: String? = null,
    /** 底栏图标：home / superuser / module / setting → 本地图片路径 */
    val dockIcons: Map<String, String> = emptyMap(),
    /** 包里那份文案表，目前只用来在详情里展示，不往界面硬塞 */
    val texts: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean
        get() = background == null && cardBackground == null && dockIcons.isEmpty()
}

/**
 * 一个主题包。
 *
 * [tuningJson] 是「一整套外观参数」的原文，字段与主题设置里导出的那段完全一致 ——
 * 这样商店里的包、剪贴板里的文本、别人发来的文件，是同一种东西的三种载体。
 *
 * v3.4.0：多了一种来源 —— SukiSU 的 `.sukisu` 包。它照样被翻译成一段 tuning，
 * 只是额外带一套图（[assets]）。这样「应用」这条链路不用分叉：
 * 参数进 ThemeTuning，图进 ThemeSkin。
 */
data class ThemePack(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val note: String,
    val tags: List<String>,
    /** 卡片预览用的三颗色：主色 / 次色 / 底色 */
    val dots: List<Int>,
    val downloads: Int,
    val likes: Int,
    val date: String,
    val tuningJson: String,
    /** 本地封面文件绝对路径；null = 没有封面，界面画渐变 */
    val coverPath: String? = null,
    val builtin: Boolean = false,
    /** v3.4.0：包的图片资源。SukiSU 包才有，普通 json 包是 null */
    val assets: ThemeAssets? = null
)

/** 导入结果：成功给包，失败给一句能直接弹 Toast 的话 */
data class ThemeImport(val pack: ThemePack?, val error: String? = null) {
    val ok: Boolean get() = pack != null
}

object ThemePacks {

    const val SCHEMA = "yunx.theme"

    /** 导入上限。正常主题包 2–3MB，给到 48MB 足够装 4K 壁纸，再大就是选错文件了 */
    private const val MAX_IMPORT = 48 * 1024 * 1024

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    fun packsDir(context: Context): File =
        File(context.filesDir, "theme_packs").apply { if (!exists()) mkdirs() }

    fun coversDir(context: Context): File =
        File(packsDir(context), "covers").apply { if (!exists()) mkdirs() }

    /** SukiSU 包抠出来的图：assets/<包 id>/{background,card,dock_home,…} */
    fun assetsDir(context: Context): File =
        File(packsDir(context), "assets").apply { if (!exists()) mkdirs() }

    // ------------------------------------------------------------------
    // 内置包
    // ------------------------------------------------------------------

    /**
     * 出厂自带的一批。刻意做成「商店里的商品」而不是「设置里的预设」——
     * 有作者、有版本、有下载量和点赞，卡片才有东西可画。
     *
     * 参数不是随便填的：每一套都在强调色之外动了透明度 / 模糊 / 圆角 / 描边，
     * 应用之后是整套气质变了，而不是只换一颗按钮的颜色。
     */
    val builtins: List<ThemePack> by lazy {
        listOf(
            ThemePack(
                id = "builtin.deepsea",
                name = "深海夜航",
                author = "云小抄",
                version = "1.0.0",
                note = "冰蓝玻璃 · 标准厚度 · 默认观感",
                tags = listOf("精选", "深色", "玻璃"),
                dots = listOf(0xFF1F6BFF.toInt(), 0xFF6AA8FF.toInt(), 0xFF0B1020.toInt()),
                downloads = 1820, likes = 168, date = "2026/11/22",
                tuningJson = tuningJson(),
                builtin = true
            ),
            ThemePack(
                id = "builtin.midnight",
                name = "午夜蓝调",
                author = "云小抄",
                version = "1.2.0",
                note = "更透的玻璃 · 更重的背景 · 大圆角",
                tags = listOf("精选", "深色", "玻璃"),
                dots = listOf(0xFF2D6BFF.toInt(), 0xFF7FB4FF.toInt(), 0xFF05070F.toInt()),
                downloads = 2310, likes = 212, date = "2026/11/20",
                tuningJson = tuningJson(
                    accentKey = "ice", cardAlpha = 0.74f, blurScale = 1.32f,
                    scrimBias = -0.16f, cardRadius = 1.16f, borderScale = 1.18f,
                    shadowScale = 1.2f, progressiveScrim = true
                ),
                builtin = true
            ),
            ThemePack(
                id = "builtin.minimal",
                name = "极简灰",
                author = "云小抄",
                version = "1.0.3",
                note = "单色 · 细描边 · 不抢内容",
                tags = listOf("浅色", "极简"),
                dots = listOf(0xFFB8BCC8.toInt(), 0xFFE7E9EF.toInt(), 0xFF2A2D36.toInt()),
                downloads = 964, likes = 88, date = "2026/11/18",
                tuningJson = tuningJson(
                    paletteStyle = "mono", colorSpec = "2021", cardAlpha = 0.9f,
                    blurScale = 0.42f, scrimBias = -0.04f, cardRadius = 0.86f,
                    borderScale = 0.5f, shadowScale = 0.42f, dividerScale = 0.7f,
                    iconShape = "square", bottomDensity = "compact"
                ),
                builtin = true
            ),
            ThemePack(
                id = "builtin.sakura",
                name = "樱花季",
                author = "云小抄",
                version = "2.0.0",
                note = "玫瑰调 · 大圆角 · 柔光",
                tags = listOf("浅色", "粉色"),
                dots = listOf(0xFFD6336C.toInt(), 0xFFFFB3C7.toInt(), 0xFFFFF3F6.toInt()),
                downloads = 1502, likes = 196, date = "2026/11/17",
                tuningJson = tuningJson(
                    accentKey = "rose", paletteStyle = "vibrant", cardAlpha = 0.82f,
                    blurScale = 1.12f, scrimBias = 0.05f, cardRadius = 1.32f,
                    borderScale = 1.24f, shadowScale = 1.06f, dividerScale = 0.9f,
                    iconShape = "circle"
                ),
                builtin = true
            ),
            ThemePack(
                id = "builtin.forest",
                name = "森林绿",
                author = "云小抄",
                version = "1.1.0",
                note = "翠绿 · 柔和 · 长久看不累",
                tags = listOf("浅色", "自然"),
                dots = listOf(0xFF0F9D58.toInt(), 0xFF7FE0AE.toInt(), 0xFFF1F7F2.toInt()),
                downloads = 733, likes = 64, date = "2026/11/15",
                tuningJson = tuningJson(
                    accentKey = "jade", paletteStyle = "neutral", cardAlpha = 0.86f,
                    blurScale = 0.9f, scrimBias = -0.06f, cardRadius = 1f,
                    borderScale = 0.9f, shadowScale = 0.9f
                ),
                builtin = true
            ),
            ThemePack(
                id = "builtin.contrast",
                name = "高对比",
                author = "云小抄",
                version = "1.0.1",
                note = "琥珀 · 硬边界 · 户外看得清",
                tags = listOf("深色", "高对比"),
                dots = listOf(0xFFFFB020.toInt(), 0xFFFFD79A.toInt(), 0xFF0A0A0C.toInt()),
                downloads = 421, likes = 39, date = "2026/11/12",
                tuningJson = tuningJson(
                    accentKey = "amber", paletteStyle = "expressive", colorSpec = "2021",
                    cardAlpha = 1f, blurScale = 0.24f, scrimBias = -0.28f,
                    cardRadius = 0.74f, borderScale = 1.5f, shadowScale = 0.6f,
                    dividerScale = 1.36f, iconShape = "square", bottomDensity = "relaxed"
                ),
                builtin = true
            ),
            ThemePack(
                id = "builtin.teal",
                name = "青碧",
                author = "云小抄",
                version = "1.0.0",
                note = "青绿 · 清透 · 电量友好",
                tags = listOf("深色", "玻璃"),
                dots = listOf(0xFF0E9384.toInt(), 0xFF7BE8DA.toInt(), 0xFF04100F.toInt()),
                downloads = 588, likes = 51, date = "2026/11/10",
                tuningJson = tuningJson(
                    accentKey = "teal", paletteStyle = "vibrant", cardAlpha = 0.8f,
                    blurScale = 1.05f, scrimBias = -0.08f, cardRadius = 1.08f,
                    borderScale = 1.05f, shadowScale = 0.95f
                ),
                builtin = true
            ),
            ThemePack(
                id = "builtin.violet",
                name = "紫罗兰",
                author = "云小抄",
                version = "1.3.2",
                note = "紫调 · 大字号 · 松一点",
                tags = listOf("深色", "紫色"),
                dots = listOf(0xFF7C3AED.toInt(), 0xFFC9A8FF.toInt(), 0xFF12081F.toInt()),
                downloads = 1176, likes = 143, date = "2026/11/08",
                tuningJson = tuningJson(
                    accentKey = "violet", paletteStyle = "tonal", cardAlpha = 0.88f,
                    blurScale = 1.15f, scrimBias = -0.05f, cardRadius = 1.24f,
                    borderScale = 1.1f, shadowScale = 1.1f, fontScale = 1.06f,
                    bottomDensity = "relaxed"
                ),
                builtin = true
            )
        )
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /** 已安装的包（不含内置）。按文件修改时间倒序 —— 刚导进来的排最前面 */
    fun installed(context: Context): List<ThemePack> =
        packsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f -> runCatching { parse(f.readText(), builtin = false) }.getOrNull() }
            ?: emptyList()

    /** 商店列表：本机装的在前，出厂的在后面 */
    fun all(context: Context): List<ThemePack> = installed(context) + builtins

    /** 按 id 找一个包。冷启动时用它把「上次应用的那套」的图重新挂上去 */
    fun byId(context: Context, id: String): ThemePack? {
        if (id.isBlank()) return null
        builtins.firstOrNull { it.id == id }?.let { return it }
        val f = File(packsDir(context), "$id.json")
        if (!f.isFile) return null
        return runCatching { parse(f.readText(), builtin = false) }.getOrNull()
    }

    fun save(context: Context, pack: ThemePack): File {
        val file = File(packsDir(context), "${pack.id.ifBlank { "pack" }}.json")
        file.writeText(toJson(pack))
        return file
    }

    fun delete(context: Context, pack: ThemePack): Boolean {
        if (pack.builtin) return false
        val ok = File(packsDir(context), "${pack.id}.json").let { it.exists() && it.delete() }
        pack.coverPath?.let { p -> runCatching { File(p).takeIf { it.exists() }?.delete() } }
        // v3.4.0：SukiSU 包搬进来的那套图一并清掉，不然删了包之后
        // filesDir 里永远搁着几 MB 的壁纸和图标
        runCatching {
            File(assetsDir(context), pack.id).takeIf { it.exists() }?.deleteRecursively()
        }
        return ok
    }

    /** 封面文件；不存在 / 读不出来返回 null，界面退回渐变 */
    fun cover(pack: ThemePack): File? =
        pack.coverPath?.let { p -> File(p).takeIf { it.isFile && it.length() > 0 } }

    // ------------------------------------------------------------------
    // 导出 .sukisu（v3.4.2）
    // ------------------------------------------------------------------

    /**
     * 把一个包导出成 SukiSU 的 `.sukisu` 文件。
     *
     * 流程跟导入完全反过来：
     *   tuning 参数 → theme.json（SukiSU 字段名）
     *   卡面 / 背景 / 底栏图标 → zip 里的 background / card_background / bottom_bar_icon_*
     *   zip → AES-GCM 加密 → SKTH 外壳
     *
     * 导出的是「这条主题」的全部可见内容。纯参数包（没有图）也能导出，
     * 对方装进去就是一套配色；带图的包则连壁纸和图标一起带走。
     * 返回的字节直接写进用户选的文件即可。
     */
    fun exportSukisu(pack: ThemePack): ByteArray {
        val assets = pack.assets
        val files = LinkedHashMap<String, ByteArray>()
        files["theme.json"] = sukisuThemeJson(pack).toByteArray(Charsets.UTF_8)
        assets?.background?.let { p -> readFile(p)?.let { files["background"] = it } }
        assets?.cardBackground?.let { p -> readFile(p)?.let { files["card_background"] = it } }
        assets?.dockIcons?.forEach { (k, p) ->
            if (k.isNotBlank()) readFile(p)?.let { files["bottom_bar_icon_$k"] = it }
        }

        val zip = java.io.ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            files.forEach { (name, data) ->
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return SukisuTheme.encrypt(zip.toByteArray())
    }

    /** 导出文件名：包名去掉非法字符，补上 .sukisu */
    fun exportFileName(pack: ThemePack): String {
        val safe = pack.name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
            .ifBlank { "云小抄主题" }
        return if (safe.endsWith(".sukisu", true)) safe else "$safe.sukisu"
    }

    /** tuning 参数 → SukiSU 的 theme.json。只写对方认得的字段，别塞私货。 */
    private fun sukisuThemeJson(pack: ThemePack): String {
        val t = runCatching { JSONObject(pack.tuningJson) }.getOrDefault(JSONObject())
        val accent = t.optInt("customAccent", 0).takeIf { it != 0 }
            ?: pack.dots.firstOrNull()
            ?: 0
        // 导入时是 0.35 + 0.65 × pct，这里反解回百分比
        val cardAlpha = t.optDouble("cardAlpha", 1.0).toFloat().coerceIn(0.35f, 1f)
        val cardPct = ((cardAlpha - 0.35f) / 0.65f * 100f).roundToInt().coerceIn(0, 100)
        val blurPct = (t.optDouble("blurScale", 1.0).toFloat() / 1.3f * 100f)
            .roundToInt().coerceIn(0, 100)
        val scrimPct = (-t.optDouble("scrimBias", 0.0).toFloat() / 0.28f * 100f)
            .roundToInt().coerceIn(0, 100)

        return JSONObject().apply {
            put("uiMode", 0)
            put("themeMode", 0)
            put("keyColor", accent)
            put("colorStyle", t.optString("paletteStyle", "tonal"))
            put("colorSpec", t.optString("colorSpec", "2025"))
            put("cardBackgroundAlphaPercent", cardPct)
            put("customBackgroundBlurPercent", blurPct)
            put("customBackgroundScrimPercent", scrimPct)
            val texts = pack.assets?.texts.orEmpty()
            if (texts.isNotEmpty()) put("customTexts", JSONObject(texts as Map<*, *>))
            put("exportedBy", "云小抄")
        }.toString(2)
    }

    private fun readFile(path: String): ByteArray? = runCatching {
        File(path).takeIf { it.isFile && it.length() > 0 }?.readBytes()
    }.getOrNull()

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 从系统文件选择器拿到的 Uri 导入。
     *
     * v3.4.0 起认四种东西：
     *   ① `.sukisu`      —— SukiSU / KernelSU 管理器的主题包（外面裹着 AES-GCM）
     *   ② 套了一层的 zip —— 里面躺着一个 `.sukisu`（社区发出来的常见形态）
     *   ③ `.zip`         —— 云小抄自己的包，或者 SukiSU 解出来的「theme.json + 图」
     *   ④ `.json` / 文本 —— 参数包，或者一份裸的 theme.json
     *
     * 一律先读成字节再判断：文件管理器给的扩展名经常是错的（.sukisu 常被报成
     * application/octet-stream），认魔数比认名字靠谱。
     */
    fun import(context: Context, uri: Uri): ThemeImport = runCatching {
        val name = queryName(context, uri) ?: "theme.json"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                total += n
                if (total > MAX_IMPORT) return ThemeImport(null, "文件超过 48MB，不像是主题包")
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } ?: return ThemeImport(null, "文件读不出来")
        importBytes(context, bytes, name)
    }.getOrElse { ThemeImport(null, "读取失败：${it.message ?: "未知错误"}") }

    /** 字节流导入。文件选择器、分享进来、自测都走这一条 */
    fun importBytes(context: Context, bytes: ByteArray, fileName: String): ThemeImport {
        // ① 裸 .sukisu
        if (SukisuTheme.looksLike(bytes)) {
            val zip = runCatching { SukisuTheme.decrypt(bytes) }.getOrElse {
                return ThemeImport(null, "这个 .sukisu 解不开 —— 可能不是 SukiSU 的包，或者格式被改过")
            }
            return importSukisuZip(context, fileName, zip)
        }
        // ② zip
        if (SukisuTheme.isZip(bytes)) {
            val files = runCatching { SukisuTheme.readZip(bytes) }.getOrElse {
                return ThemeImport(null, "压缩包读不出来：${it.message ?: "格式不对"}")
            }
            // 里面套了一个 .sukisu：捞出来解开
            files.entries.firstOrNull { it.key.endsWith(".sukisu") }?.let { (_, inner) ->
                if (SukisuTheme.looksLike(inner)) {
                    val zip = runCatching { SukisuTheme.decrypt(inner) }.getOrNull()
                    if (zip != null) return importSukisuZip(context, fileName, zip)
                }
            }
            return importFiles(context, files, fileName)
        }
        // ③ 文本
        val text = bytes.toString(Charsets.UTF_8)
        if (SukisuTheme.looksLikeSukisuJson(text)) {
            return importFiles(context, mapOf("theme.json" to bytes), fileName)
        }
        return importText(context, text, fallbackName = fileName.substringBeforeLast('.'))
    }

    private fun importSukisuZip(context: Context, fileName: String, zip: ByteArray): ThemeImport {
        val files = runCatching { SukisuTheme.readZip(zip) }.getOrElse {
            return ThemeImport(null, "主题包内容读不出来：${it.message ?: "压缩格式不对"}")
        }
        return importFiles(context, files, fileName)
    }

    /**
     * 「已经摊平的文件表」→ 一个包。
     *
     * 先看 theme.json 是谁家的：SukiSU 的走贴图那条路，云小抄的走参数那条路。
     * 两条路最后都落成同一个 [ThemePack]，所以商店、应用、删除都不用分叉。
     */
    private fun importFiles(
        context: Context,
        files: Map<String, ByteArray>,
        fileName: String
    ): ThemeImport {
        val themeBytes = files["theme.json"]
            ?: files.entries.firstOrNull { it.key.endsWith(".json") }?.value
            ?: return ThemeImport(null, "压缩包里没有 theme.json")
        val text = themeBytes.toString(Charsets.UTF_8)
        if (!SukisuTheme.looksLikeSukisuJson(text)) {
            return importYunxFiles(context, files, text, fileName)
        }
        val skin = SukisuTheme.parse(text, files, fileName)
            ?: return ThemeImport(null, "theme.json 读不出来")

        // 同名的 SukiSU 包视作「同一个包的新一版」：沿用旧 id，把旧的图删掉重写。
        // 不然反复导同一个包，商店里会堆出一串「遐蝶」。
        val existing = installed(context).firstOrNull {
            it.tags.contains("SukiSU") && it.name == skin.title
        }
        existing?.let {
            runCatching { File(assetsDir(context), it.id).takeIf { f -> f.exists() }?.deleteRecursively() }
        }
        val id = existing?.id
            ?: ("sukisu." + Integer.toHexString((skin.title + System.currentTimeMillis()).hashCode()))
        val dir = File(assetsDir(context), id).apply { mkdirs() }
        val bg = skin.background?.let { writeAsset(dir, "background", it) }
        val card = skin.cardBackground?.let { writeAsset(dir, "card", it) }
        val icons = LinkedHashMap<String, String>()
        skin.icons.forEach { (k, v) -> writeAsset(dir, "dock_$k", v)?.let { icons[k] = it } }
        // 封面用背景图缩一份：商店列表一屏六七张卡，直接吃 794×1719 的原图会卡
        val cover = (skin.background ?: skin.cardBackground)?.let { makeCover(context, id, it) }

        val pack = ThemePack(
            id = id,
            name = skin.title,
            author = "SukiSU 主题",
            version = "1.0.0",
            note = skin.note,
            tags = skin.tags,
            dots = skin.dots,
            downloads = 0,
            likes = 0,
            date = today(),
            tuningJson = skin.tuningJson,
            coverPath = cover,
            assets = ThemeAssets(
                background = bg,
                cardBackground = card,
                dockIcons = icons,
                texts = skin.texts
            )
        )
        save(context, pack)
        return ThemeImport(pack)
    }

    /** 云小抄自己的 zip / 摊平文件表 */
    private fun importYunxFiles(
        context: Context,
        files: Map<String, ByteArray>,
        text: String,
        fileName: String
    ): ThemeImport {
        var tempCover: File? = null
        files.entries.firstOrNull { (n, _) ->
            (n.startsWith("cover.") || n == "cover") && !n.endsWith(".json")
        }?.let { (name, data) ->
            val ext = name.substringAfterLast('.', "png").ifBlank { "png" }
            val f = File(context.cacheDir, "pack_cover.$ext")
            runCatching { f.writeBytes(data) }
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, probe)
            tempCover = if (probe.outWidth > 0) f else null
        }
        return importText(
            context,
            text,
            cover = tempCover,
            fallbackName = fileName.substringBeforeLast('.')
        )
    }

    private fun writeAsset(dir: File, name: String, data: ByteArray): String? = runCatching {
        val f = File(dir, name)
        FileOutputStream(f).use { it.write(data) }
        f.absolutePath
    }.getOrNull()

    /**
     * 给列表用的封面：宽降到 900 以内再压 JPEG。
     * 原图动辄 1MB 的 webp，Coil 每次滚动都要解一遍，六张卡就开始掉帧。
     */
    private fun makeCover(context: Context, id: String, data: ByteArray): String? = runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, probe)
        if (probe.outWidth <= 0) return null
        var sample = 1
        while (probe.outWidth / sample > 1800) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val f = File(coversDir(context), "$id.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        f.absolutePath
    }.getOrNull()

    /** 纯文本导入（剪贴板 / 分享进来的一段 JSON 都走这里） */
    fun importText(
        context: Context,
        text: String,
        cover: File? = null,
        fallbackName: String = ""
    ): ThemeImport {
        val pack = runCatching { parse(text, builtin = false) }.getOrNull()
            ?: return ThemeImport(null, "这段内容不是主题包")
        val named = if (pack.name.isBlank() && fallbackName.isNotBlank()) {
            pack.copy(name = fallbackName)
        } else {
            pack
        }
        // 没有 id 的包（老版剪贴板导出的纯参数）按名字 + 时间生成一个，别互相覆盖
        val id = named.id.ifBlank {
            "local." + Integer.toHexString((named.name + System.currentTimeMillis()).hashCode())
        }
        val withId = named.copy(id = id)
        val movedCover = cover?.let { src ->
            runCatching {
                val dest = File(coversDir(context), "$id.${src.extension.ifBlank { "png" }}")
                src.copyTo(dest, overwrite = true)
                dest.absolutePath
            }.getOrNull()
        }
        val final = withId.copy(coverPath = movedCover ?: withId.coverPath)
        save(context, final)
        return ThemeImport(final)
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    fun toJson(pack: ThemePack): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("v", 1)
        put("id", pack.id)
        put("name", pack.name)
        put("author", pack.author)
        put("version", pack.version)
        put("note", pack.note)
        put("tags", JSONArray(pack.tags))
        put("dots", JSONArray(pack.dots.map { String.format("#%06X", it and 0xFFFFFF) }))
        put("downloads", pack.downloads)
        put("likes", pack.likes)
        put("date", pack.date)
        // tuning 原样嵌进去，不再二次序列化 —— 它就是别人导出时那段文本
        put("tuning", JSONObject(pack.tuningJson))
        // v3.4.0：SukiSU 包带出来的图。存的是本机路径 —— 这套东西本来就不跟着
        // 包走（导出时只导出参数），换台机器要重新导一次原包
        pack.assets?.takeIf { !it.isEmpty }?.let { a ->
            put("assets", JSONObject().apply {
                a.background?.let { put("background", it) }
                a.cardBackground?.let { put("cardBackground", it) }
                if (a.dockIcons.isNotEmpty()) put("dockIcons", JSONObject(a.dockIcons as Map<*, *>))
                if (a.texts.isNotEmpty()) put("texts", JSONObject(a.texts as Map<*, *>))
            })
        }
    }.toString(2)

    /**
     * 解析一个主题包。
     *
     * 三种输入都能吃：
     *   ① 完整包（有 schema / tuning）；
     *   ② 只有 tuning 字段的老格式；
     *   ③ 主题设置里「导出当前主题」那段裸参数（连 tuning 都没有）。
     */
    fun parse(text: String, builtin: Boolean): ThemePack {
        val o = JSONObject(text.trim())
        val tuningObj: JSONObject = when {
            o.optJSONObject("tuning") != null -> o.getJSONObject("tuning")
            o.has("cardAlpha") || o.has("paletteStyle") || o.has("accentKey") -> o
            else -> JSONObject()
        }
        if (tuningObj.length() == 0) error("缺少外观参数")
        val dots = mutableListOf<Int>()
        o.optJSONArray("dots")?.let { arr ->
            for (i in 0 until arr.length()) {
                runCatching { android.graphics.Color.parseColor(arr.getString(i)) }
                    .getOrNull()?.let { dots += it }
            }
        }
        val tags = mutableListOf<String>()
        o.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { tags += it }
        }
        val name = o.optString("name").takeIf { it.isNotBlank() }
            ?: tags.firstOrNull()?.let { "导入的主题" }
            ?: "导入的主题"
        val assets = o.optJSONObject("assets")?.let { a ->
            ThemeAssets(
                background = a.optString("background").takeIf { it.isNotBlank() },
                cardBackground = a.optString("cardBackground").takeIf { it.isNotBlank() },
                dockIcons = a.optJSONObject("dockIcons")?.let { d ->
                    val m = LinkedHashMap<String, String>()
                    d.keys().forEach { k ->
                        d.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
                    }
                    m
                } ?: emptyMap(),
                texts = a.optJSONObject("texts")?.let { tx ->
                    val m = LinkedHashMap<String, String>()
                    tx.keys().forEach { k -> m[k] = tx.optString(k) }
                    m
                } ?: emptyMap()
            ).takeIf { !it.isEmpty }
        }
        return ThemePack(
            id = o.optString("id").orEmpty(),
            name = name,
            author = o.optString("author").takeIf { it.isNotBlank() } ?: "本机导入",
            version = o.optString("version").takeIf { it.isNotBlank() } ?: "1.0.0",
            note = o.optString("note").orEmpty(),
            tags = tags,
            dots = dots.ifEmpty {
                listOf(0xFF1F6BFF.toInt(), 0xFF4C9BFF.toInt(), 0xFF0B1020.toInt())
            },
            downloads = o.optInt("downloads", 0),
            likes = o.optInt("likes", 0),
            date = o.optString("date").takeIf { it.isNotBlank() } ?: today(),
            tuningJson = tuningObj.toString(),
            builtin = builtin,
            assets = assets
        )
    }

    // ------------------------------------------------------------------
    // 小件
    // ------------------------------------------------------------------

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun today(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%04d/%02d/%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * 内置包参数。默认值 = 出厂那套（冰蓝玻璃），其余包只覆盖自己改的那几项 ——
     * 跟主题设置里「导出」出来的字段一一对应。
     */
    private fun tuningJson(
        accentKey: String = "auto",
        paletteStyle: String = "tonal",
        colorSpec: String = "2025",
        customAccent: Int = 0,
        cardAlpha: Float = 1f,
        blurScale: Float = 1f,
        scrimBias: Float = 0f,
        fontScale: Float = 1f,
        cardRadius: Float = 1f,
        borderScale: Float = 1f,
        shadowScale: Float = 1f,
        dividerScale: Float = 1f,
        iconShape: String = "rounded",
        hideSubtitle: Boolean = false,
        bottomDensity: String = "normal",
        showSliderValue: Boolean = false,
        progressiveScrim: Boolean = false
    ): String = JSONObject().apply {
        put("v", 1)
        put("accentKey", accentKey)
        put("paletteStyle", paletteStyle)
        put("colorSpec", colorSpec)
        put("customAccent", customAccent)
        put("cardAlpha", cardAlpha.toDouble())
        put("blurScale", blurScale.toDouble())
        put("scrimBias", scrimBias.toDouble())
        put("fontScale", fontScale.toDouble())
        put("cardRadius", cardRadius.toDouble())
        put("borderScale", borderScale.toDouble())
        put("shadowScale", shadowScale.toDouble())
        put("dividerScale", dividerScale.toDouble())
        put("iconShape", iconShape)
        put("hideSubtitle", hideSubtitle)
        put("bottomDensity", bottomDensity)
        put("showSliderValue", showSliderValue)
        put("progressiveScrim", progressiveScrim)
    }.toString()
}
