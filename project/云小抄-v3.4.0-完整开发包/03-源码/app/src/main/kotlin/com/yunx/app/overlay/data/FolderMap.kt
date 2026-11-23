/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.3）。
 *
 * 下载落盘时复刻网盘目录：分类 → 子目录 → 图片。
 * 以前所有图平铺在 `guide_images/` 一个目录里，几百张之后在文件管理器里
 * 根本认不出哪张属于哪份攻略；这里记住 `fid → (名字, 父 fid)`，
 * 下载时按这条链建同名子目录。
 */

package com.yunx.app.overlay.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 目录名处理与路径推导。
 *
 * 刻意不碰 Android API：单元测试直接跑，不需要 Context / 真机。
 */
object FolderPaths {

    /** 最多复刻这么多层。再深的层级压平 —— 手机上三层目录已经很难点了 */
    const val MAX_DEPTH = 3

    /** 单个目录名的字符上限。SAF 按字节限长，中文 3 字节，40 字约 120 字节，安全 */
    const val MAX_SEGMENT = 40

    /** 文件系统 / SAF 明确不接受的字符；中文、括号、emoji 一律原样保留 */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 网盘目录名 → 本地目录名。
     *
     * 只做「必须做」的清洗：非法字符换下划线、去掉结尾的点和空格（部分 provider 不接受）、
     * 去掉开头的点（避免变成隐藏目录）、超长截断。名字本身保持不变 ——
     * 用户就是要看到跟网盘一样的目录名，多一个哈希后缀都会觉得别扭。
     *
     * @param fallbackId 名字整不出来时的兜底 id，用它的短哈希造一个稳定名字
     */
    fun sanitize(raw: String, fallbackId: String = ""): String {
        val trimmed = raw.trim()
        val cleaned = StringBuilder(trimmed.length)
        trimmed.forEach { ch ->
            cleaned.append(if (ch.isISOControl() || ch in ILLEGAL) '_' else ch)
        }
        var name = cleaned.toString().trimEnd(' ', '.').trimStart('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") {
            name = if (fallbackId.isBlank()) "未命名" else "目录_${shortHash(fallbackId)}"
        }
        if (name.length > MAX_SEGMENT) {
            val points = name.codePointCount(0, name.length).coerceAtMost(MAX_SEGMENT)
            name = name.substring(0, name.offsetByCodePoints(0, points)).trimEnd(' ', '.')
        }
        return name.ifBlank { "未命名" }
    }

    fun shortHash(id: String): String = Integer.toHexString(id.hashCode()).take(6)

    /**
     * 从 [folderId] 一路往上走到表里没有的那个父目录，返回「从根往下」的目录名列表。
     *
     * @param lookup 目录 id → 节点；返回 null 表示「到此为止」（通常是根目录，它本身不是分类）
     */
    fun resolvePath(
        folderId: String?,
        lookup: (String) -> Node?,
        maxDepth: Int = MAX_DEPTH
    ): List<String> {
        val start = folderId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val stack = ArrayList<String>(maxDepth)
        val seen = HashSet<String>(maxDepth * 2)
        var current: String = start
        while (stack.size < maxDepth) {
            // 脏数据 / 网盘把父指成子：走一圈就停，绝不能在这里死循环
            if (!seen.add(current)) break
            val node = lookup(current) ?: break
            node.localName.takeIf { it.isNotBlank() }?.let { stack += it }
            if (node.parentId.isBlank() || node.parentId == current) break
            current = node.parentId
        }
        stack.reverse()
        return stack
    }

    /**
     * 把一批 fid 全展开成完整路径，去重后按字典序排。
     *
     * 与逐个调 [resolvePath] 的区别只有两点：去重、排序。但「稳定顺序」很重要 ——
     * 本地建目录时靠它保证同一棵树每次建出来的次序一致。
     */
    fun allPaths(
        ids: Collection<String>,
        lookup: (String) -> Node?,
        maxDepth: Int = MAX_DEPTH
    ): List<List<String>> {
        if (ids.isEmpty()) return emptyList()
        val seen = HashSet<String>(ids.size * 2)
        val out = ArrayList<List<String>>(ids.size)
        ids.forEach { id ->
            val path = resolvePath(id, lookup, maxDepth)
            // 空路径 = 这个 fid 在表里没有名字（未知 fid / 被上限挤掉的旧条目），
            // 拿来建目录会在根下凭空造一个空名文件夹
            if (path.isEmpty()) return@forEach
            if (seen.add(path.joinToString("/"))) out += path
        }
        out.sortBy { it.joinToString("/") }
        return out
    }

    data class Node(val id: String, val parentId: String, val localName: String)
}

/**
 * 网盘目录树记忆（fid → 名字 / 父 fid），落在 `filesDir/guide_folders.json`。
 *
 * 为什么单独存一份、而不是从索引里反推：图片索引里只有「这张图属于哪个 fid」，
 * 没有 fid 的名字；而列表接口每次都会把 `PanFolder` 给全，顺手记下来最省事。
 * 索引与它各自独立 —— 清空本地图片不该把目录名也忘掉。
 */
class FolderMap private constructor(context: Context) {

    private val app = context.applicationContext
    private val file = File(app.filesDir, FILE_NAME)
    private val nodes = LinkedHashMap<String, Node>()

    @Volatile
    private var loaded = false

    @Volatile
    private var dirty = false

    private data class Node(
        val id: String,
        val parentId: String,
        /** 清洗后的本地目录名（同名兄弟会被加 `_2` 后缀） */
        val localName: String,
        /** 网盘上的原名，用来发现「作者把分类改名了」 */
        val remoteName: String,
        /** 两家网盘的父 fid 可能撞（比如都是 "0"），判同名兄弟时必须分平台 */
        val platform: PanPlatform
    )

    // ------------------------------------------------------------------
    // 登记
    // ------------------------------------------------------------------

    /**
     * 把一批目录记下来。分类列表和每层子目录都在这里登记。
     *
     * 只做「加法」：网盘里删掉的分类不在这里清，因为历史图片还躺在那个目录里，
     * 名字一删，「归位」就会把它们认成无主文件。
     *
     * @return 这次有没有新目录 / 改名 / 换父 —— 调用方据此决定要不要把目录骨架
     *         同步到本地（绝大多数调用都是 `false`，不能每次开列表都全量 mkdir）
     */
    fun remember(platform: PanPlatform, folders: List<PanFolder>): Boolean {
        if (folders.isEmpty()) return false
        ensureLoaded()
        var changed = false
        synchronized(nodes) {
            folders.forEach { folder ->
                if (folder.id.isBlank()) return@forEach
                val key = key(platform, folder.id)
                val base = FolderPaths.sanitize(folder.name, folder.id)
                val local = uniqueName(platform, folder.parentId, folder.id, base)
                val old = nodes[key]
                if (old == null ||
                    old.parentId != folder.parentId ||
                    old.localName != local ||
                    old.remoteName != folder.name
                ) {
                    nodes[key] = Node(folder.id, folder.parentId, local, folder.name, platform)
                    changed = true
                }
            }
            if (changed) {
                while (nodes.size > MAX_NODES) {
                    val oldest = nodes.keys.firstOrNull() ?: break
                    nodes.remove(oldest)
                }
            }
        }
        if (changed) {
            dirty = true
            flush()
        }
        return changed
    }

    /**
     * 同父目录下已经有同名兄弟时加数字后缀。
     *
     * 只在真的撞名时才动名字：`攻略/图文` 和 `攻略:图文` 清洗后都是 `攻略_图文`，
     * 不加后缀会在本地并成一个目录，两份攻略的图混在一起。
     */
    private fun uniqueName(
        platform: PanPlatform,
        parentId: String,
        selfId: String,
        base: String
    ): String {
        var candidate = base
        var n = 2
        while (hasSibling(platform, parentId, selfId, candidate)) {
            candidate = if (n <= 9) "${base}_$n" else "${base}_${FolderPaths.shortHash(selfId)}"
            n++
        }
        return candidate
    }

    private fun hasSibling(
        platform: PanPlatform,
        parentId: String,
        selfId: String,
        name: String
    ): Boolean = nodes.values.any {
        it.platform == platform && it.id != selfId &&
            it.parentId == parentId && it.localName == name
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 这个目录在本地该落在哪（从根往下）。未知 fid 返回空列表 → 存在根目录 */
    fun relPath(platform: PanPlatform, folderId: String?): List<String> {
        if (folderId.isNullOrBlank()) return emptyList()
        ensureLoaded()
        return synchronized(nodes) {
            FolderPaths.resolvePath(folderId, { id ->
                nodes[key(platform, id)]?.let {
                    FolderPaths.Node(it.id, it.parentId, it.localName)
                }
            })
        }
    }

    fun relDir(platform: PanPlatform, folderId: String?): String =
        relPath(platform, folderId).joinToString("/")

    /**
     * v2.0.4：这个平台下**已知的全部目录**，每条都是从根往下的完整相对路径。
     *
     * 用途是把目录骨架提前建到本地 —— 下载一张图才建一次目录的话，
     * 没图的分类在文件管理器里根本不存在，用户看到的「目录结构」永远是残的。
     *
     * 返回顺序按路径字典序，保证同一个目录树每次建出来的顺序一致，
     * 便于「建过的跳过」这条判断稳定命中。
     */
    fun paths(platform: PanPlatform): List<List<String>> {
        ensureLoaded()
        // 先拷一份快照再解析：解析过程里另一次 remember 改了表，也不能算到这一次头上
        val snap = synchronized(nodes) {
            nodes.values.asSequence()
                .filter { it.platform == platform }
                .associateBy { it.id }
        }
        if (snap.isEmpty()) return emptyList()
        return FolderPaths.allPaths(snap.keys, { id ->
            snap[id]?.let { FolderPaths.Node(it.id, it.parentId, it.localName) }
        })
    }

    /** 这个平台记了多少个目录（含子目录） */
    fun count(platform: PanPlatform): Int {
        ensureLoaded()
        return synchronized(nodes) { nodes.values.count { it.platform == platform } }
    }

    /**
     * v2.0.4：从本地记忆还原「根分类列表」。
     *
     * 判定标准：父 fid **不在表里** —— 分类的父是「我的小抄」根目录，
     * 根目录本身不会被登记成节点，所以走到它就到头了。
     *
     * 用途是兜底：断网 + 磁盘快照又丢了的时候，至少还能把分类名字列出来，
     * 而不是给用户一个空白页。点进去仍然会失败，但「有哪几个分类」这件事
     * 本来就该记住。
     */
    fun topFolders(platform: PanPlatform): List<PanFolder> {
        ensureLoaded()
        return synchronized(nodes) {
            val ids = nodes.values.asSequence()
                .filter { it.platform == platform }
                .mapTo(HashSet()) { it.id }
            nodes.values.asSequence()
                .filter { it.platform == platform }
                .filter { it.parentId.isBlank() || it.parentId !in ids }
                .map { PanFolder(id = it.id, name = it.remoteName, parentId = it.parentId) }
                .sortedWith(compareBy(PanFiles.nameComparator) { it.name })
                .toList()
        }
    }

    fun size(): Int {
        ensureLoaded()
        return synchronized(nodes) { nodes.size }
    }

    fun clear() {
        synchronized(nodes) { nodes.clear() }
        dirty = true
        flush()
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    @Synchronized
    fun flush() {
        if (!dirty) return
        dirty = false
        val ok = runCatching {
            val json = JSONObject()
            synchronized(nodes) {
                nodes.forEach { (k, v) ->
                    json.put(
                        k,
                        JSONObject()
                            .put("n", v.remoteName)
                            .put("p", v.parentId)
                            .put("l", v.localName)
                    )
                }
            }
            val tmp = File(app.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.isSuccess
        if (!ok) dirty = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching {
                if (!file.exists() || file.length() > MAX_FILE_BYTES) return
                val json = JSONObject(file.readText())
                synchronized(nodes) {
                    json.keys().forEach { k ->
                        val obj = json.optJSONObject(k) ?: return@forEach
                        val name = obj.optString("n")
                        val local = obj.optString("l")
                        if (name.isBlank() || local.isBlank()) return@forEach
                        val pipe = k.indexOf('|')
                        if (pipe <= 0) return@forEach
                        val platform = PanPlatform.entries
                            .firstOrNull { it.name == k.substring(0, pipe) }
                            ?: return@forEach
                        nodes[k] = Node(
                            id = k.substring(pipe + 1),
                            parentId = obj.optString("p"),
                            localName = local,
                            remoteName = name,
                            platform = platform
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val FILE_NAME = "guide_folders.json"
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024

        /** 目录条目上限：一份攻略几十个分类，1500 条够用很久，换链接也不会无限涨 */
        private const val MAX_NODES = 1500

        private fun key(platform: PanPlatform, id: String): String = "${platform.name}|$id"

        @Volatile
        private var instance: FolderMap? = null

        fun of(context: Context): FolderMap = instance ?: synchronized(this) {
            instance ?: FolderMap(context.applicationContext).also { instance = it }
        }
    }
}
