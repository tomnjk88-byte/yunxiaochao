/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.7.0 起支持把「本地文件夹」当作攻略来源：用户用系统文件
 * 选择器（SAF）授权一个目录树，这里直接遍历它的子文件夹与图片，产出与网盘完全
 * 相同的 PanFolder / PanImage / FolderContent / ImageSource，UI 一行都不用改。
 *
 * v2.5.0 读取提速（用户反馈「每次打开小抄读取都太慢」）：
 *   1. 缩略图磁盘缓存（[LocalThumbCache]）：网格不再直接解码 4K 原图；
 *   2. 目录快照落盘：进程被杀 / 隔天再开，第一帧就有上次的分类和图片列表，
 *      不用等 SAF 把整棵目录树重新查一遍；
 *   3. 内存缓存的目录结果带 TTL，文件管理器里新加的图不会永远不出现。
 *
 * 为什么用 SAF 而不是直接读 /sdcard 路径：
 *  - Android 11+ 分区存储下，第三方 App 无法任意读公共目录，申请「所有文件访问」
 *    又要跳特殊设置页；SAF 由用户主动选目录并授予持久化读权限，零权限、跨重启有效。
 *  - 只依赖 framework 的 DocumentsContract + ContentResolver，不需要 androidx.documentfile，
 *    离线编译不会因缺依赖失败。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LocalGuideRepository(context: Context) {

    private val appContext = context.applicationContext
    private val thumbs = LocalThumbCache.of(appContext)

    @Volatile
    private var treeUri: Uri? = null

    /**
     * v2.5.9：绑定到 tree 下的某个子目录（小抄入口可以只指向 `/小抄/龙宫`）。
     *
     * 空 = 用 tree 根。SAF 的持久授权只覆盖用户选的那棵树，所以这里不另建 tree uri，
     * 而是在同一棵树上换一个起点 —— 权限仍然有效。
     */
    @Volatile
    private var rootDocOverride: String? = null

    private val contentCache = ConcurrentHashMap<String, Timed<FolderContent>>()

    @Volatile
    private var categoryCache: Timed<List<PanFolder>>? = null

    @Volatile
    private var rootNameCache: String? = null

    private data class Timed<T>(val value: T, val at: Long)

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )

    // ------------------------------------------------------------------
    // 磁盘快照：只存目录结构，不存图片内容
    // ------------------------------------------------------------------

    private val snapshotFile = File(appContext.filesDir, SNAPSHOT_FILE)

    @Volatile
    private var snapshotLoaded = false

    private var snapshotRoot: JSONObject? = null

    private fun snapshot(): JSONObject? {
        if (snapshotLoaded) return snapshotRoot
        synchronized(this) {
            if (snapshotLoaded) return snapshotRoot
            snapshotRoot = runCatching {
                if (!snapshotFile.isFile) null
                else JSONObject(snapshotFile.readText()).takeIf { obj ->
                    // 换了目录就整份作废：旧的 docId 在新目录里指向别的东西
                    obj.optString("tree") == snapshotKey()
                }
            }.getOrNull()
            snapshotLoaded = true
            return snapshotRoot
        }
    }

    private fun snapshotCategories(): List<PanFolder>? {
        val obj = snapshot() ?: return null
        if (System.currentTimeMillis() - obj.optLong("ts") > SNAPSHOT_MAX_AGE_MS) return null
        return obj.optJSONArray("categories")?.toFolders().orEmpty().takeIf { it.isNotEmpty() }
    }

    private fun snapshotContent(folderId: String): FolderContent? {
        val obj = snapshot()?.optJSONObject("dirs")?.optJSONObject(folderId) ?: return null
        if (System.currentTimeMillis() - obj.optLong("ts") > SNAPSHOT_MAX_AGE_MS) return null
        val folders = obj.optJSONArray("folders")?.toFolders().orEmpty()
        val images = obj.optJSONArray("images")?.toImages().orEmpty()
        return if (folders.isEmpty() && images.isEmpty()) null
        else FolderContent(folders = folders, images = images)
    }

    /**
     * 把分类 / 某个目录的列表落盘。
     *
     * 调用点都在 IO 线程（[categories] / [browse] 的 withContext 块里），
     * 这里直接同步写：文件几十 KB，耗时比一次 SAF 查询低一个量级，
     * 但换来的是下次冷启动第一帧就有内容。
     */
    private fun persistSnapshot(categories: List<PanFolder>?, content: Pair<String, FolderContent>?) {
        val now = System.currentTimeMillis()
        val tree = treeUri?.toString() ?: return
        runCatching {
            val root = snapshot()?.let { JSONObject(it.toString()) } ?: JSONObject()
            root.put("tree", snapshotKey())
            if (categories != null) {
                root.put("categories", categories.foldersJson())
                root.put("ts", now)
            }
            val dirs = root.optJSONObject("dirs") ?: JSONObject().also { root.put("dirs", it) }
            if (content != null) {
                val (folderId, value) = content
                dirs.put(
                    folderId,
                    JSONObject()
                        .put("folders", value.folders.foldersJson())
                        .put("images", value.images.imagesJson())
                        .put("ts", now)
                )
            }
            trimDirs(dirs)
            val tmp = File(snapshotFile.parentFile, "$SNAPSHOT_FILE.tmp")
            tmp.writeText(root.toString())
            if (snapshotFile.exists()) snapshotFile.delete()
            tmp.renameTo(snapshotFile)
            snapshotRoot = root
            snapshotLoaded = true
        }
    }

    /** 只留最近的 30 个目录：再多也不会有人翻，还会把启动时的解析拖慢 */
    private fun trimDirs(dirs: JSONObject) {
        if (dirs.length() <= MAX_SNAPSHOT_DIRS) return
        val keys = dirs.keys().asSequence().toList()
            .sortedBy { dirs.optJSONObject(it)?.optLong("ts") ?: 0L }
        keys.take(keys.size - MAX_SNAPSHOT_DIRS).forEach { dirs.remove(it) }
    }

    fun setTreeUri(uriString: String?) = setTree(uriString, null)

    /** v2.5.9：换目录的同时指定从哪一层开始浏览（entry 绑定子目录时用） */
    fun setTree(uriString: String?, rootDocId: String?) {
        treeUri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        rootDocOverride = rootDocId?.takeIf { it.isNotBlank() }
        clearCache()
        synchronized(this) {
            snapshotRoot = null
            snapshotLoaded = false
        }
        thumbs.resetQueue()
    }

    fun currentUri(): String? = treeUri?.toString()

    fun isConfigured(): Boolean = treeUri != null

    fun clearCache() {
        contentCache.clear()
        categoryCache = null
        rootNameCache = null
    }

    /** 换来源 / 下载完回写之后让界面下一次能读到新文件 */
    fun invalidate() {
        clearCache()
    }

    private fun requireTree(): Uri =
        treeUri ?: throw PanException("还没有选择本地文件夹")

    private fun rootDocId(tree: Uri): String =
        rootDocOverride?.takeIf { it.isNotBlank() } ?: runCatching {
            DocumentsContract.getTreeDocumentId(tree)
        }.getOrElse { throw PanException("本地文件夹授权已失效，请重新选择一次") }

    /** 快照的命名空间：同一棵树下绑不同子目录时不能互相串味 */
    private fun snapshotKey(): String = "${treeUri?.toString().orEmpty()}#${rootDocOverride.orEmpty()}"

    private data class Entry(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val isDir: Boolean
    )

    private fun listChildren(parentDocId: String): List<Entry> {
        val tree = requireTree()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val resolver = appContext.contentResolver
        val out = ArrayList<Entry>()
        try {
            val cursor = resolver.query(uri, projection, null, null, null)
                ?: throw PanException("读不到这个文件夹，请重新选择一次")
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val id = c.getString(idIdx) ?: continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else id
                    if (name.startsWith(".")) continue // 隐藏文件/缓存目录
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(Entry(id, name, mime, size, isDir))
                }
            }
        } catch (e: SecurityException) {
            throw PanException("本地文件夹授权已失效，请重新选择一次")
        } catch (e: PanException) {
            throw e
        } catch (e: Exception) {
            throw PanException("读取本地文件夹失败：${e.message ?: "未知错误"}")
        }
        return out
    }

    /** 单个文件的 content:// Uri，Coil 可以直接解码（权限来自整棵目录树的持久化授权） */
    private fun documentUri(docId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(requireTree(), docId).toString()

    private fun isImage(e: Entry): Boolean = !e.isDir &&
        (e.mime.startsWith("image/", ignoreCase = true) || PanFiles.isImage(e.name))

    /** 根目录名，用于分类条与空态提示 */
    fun rootName(): String {
        rootNameCache?.let { return it }
        val name = runCatching {
            val tree = requireTree()
            val root = rootDocId(tree)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, root)
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val result = name?.takeIf { it.isNotBlank() } ?: "本地图片"
        rootNameCache = result
        return result
    }

    /**
     * 一级分类 = 所选目录下的子文件夹。
     *
     * 若用户选的目录里直接堆着图片、一个子文件夹都没有，就补一个指向根目录的虚拟分类，
     * 否则「分类条」空了会让人以为功能坏了。行为与网盘根目录保持一致。
     */
    suspend fun categories(): List<PanFolder> = withContext(Dispatchers.IO) {
        categoryCache?.let { cached ->
            if (System.currentTimeMillis() - cached.at < CACHE_TTL_MS) return@withContext cached.value
        }
        // 冷启动第一帧：先用上次落盘的分类，别让用户盯着空分类条
        snapshotCategories()?.let { cached ->
            categoryCache = Timed(cached, System.currentTimeMillis())
            return@withContext cached
        }
        val tree = requireTree()
        val root = rootDocId(tree)
        val entries = listChildren(root)
        val folders = entries.asSequence()
            .filter { it.isDir }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.name })
            .map { PanFolder(id = it.id, name = it.name, parentId = root) }
            .toList()
        val result = if (folders.isEmpty() && entries.any { isImage(it) }) {
            listOf(PanFolder(id = root, name = rootName(), parentId = ""))
        } else {
            folders
        }
        categoryCache = Timed(result, System.currentTimeMillis())
        persistSnapshot(categories = result, content = null)
        result
    }

    /** 下钻任意一层：子文件夹 + 图片，与网盘 browse() 同构，支持无限层级 */
    suspend fun browse(folderId: String): FolderContent = withContext(Dispatchers.IO) {
        contentCache[folderId]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < CACHE_TTL_MS) return@withContext cached.value
        }
        snapshotContent(folderId)?.let { cached ->
            contentCache[folderId] = Timed(cached, System.currentTimeMillis())
            // 快照里没有缩略图地址（换进程后 content:// 仍然有效，但先让后台补缩略图）
            thumbs.prefetch(cached.images)
            return@withContext cached
        }
        val entries = listChildren(folderId)
        val folders = entries.asSequence()
            .filter { it.isDir }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.name })
            .map { PanFolder(id = it.id, name = it.name, parentId = folderId) }
            .toList()
        val images = entries.asSequence()
            .filter { isImage(it) }
            .sortedWith(compareBy(PanFiles.nameComparator) { it.name })
            .map {
                PanImage(
                    id = it.id,
                    name = it.name,
                    size = it.size,
                    thumbUrl = documentUri(it.id)
                )
            }
            .toList()
        val content = FolderContent(folders = folders, images = images)
        contentCache[folderId] = Timed(content, System.currentTimeMillis())
        // 后台把这一层的缩略图做出来：用户往下滚时大多已经就绪
        thumbs.prefetch(images)
        persistSnapshot(categories = null, content = folderId to content)
        content
    }

    /**
     * 网格缩略图地址：有缓存就用缓存，没有先给原图并让后台去生成。
     *
     * 大图预览走 [imageSource]，永远给原图 —— 缩略图放大看会糊。
     */
    fun thumbSource(image: PanImage): ImageSource {
        thumbs.existing(image.id, image.size)?.let { return ImageSource(it) }
        return ImageSource(image.thumbUrl ?: documentUri(image.id))
    }

    /** 本地图不需要取直链：content:// 本身就指向原文件 */
    fun imageSource(image: PanImage): ImageSource =
        ImageSource(image.thumbUrl ?: documentUri(image.id))

    /** v1.9.6：同步取已经遍历过的目录，供切换时先出画面 */
    fun peekContent(folderId: String): FolderContent? = contentCache[folderId]?.value

    companion object {
        /** 给 SAF 查询结果排序用的自然序比较器（复用网盘那套，编号图不会乱序） */
        private val nameComparator: Comparator<String> get() = PanFiles.nameComparator

        /** 内存目录缓存的寿命：30 秒内反复进出目录不重新扫描，超过就重查 */
        private const val CACHE_TTL_MS = 30_000L

        /** 磁盘快照的寿命：10 分钟内打开就有内容，再久就重新扫描一次 */
        private const val SNAPSHOT_MAX_AGE_MS = 10L * 60 * 1000

        private const val SNAPSHOT_FILE = "local_guide_snapshot.json"
        private const val MAX_SNAPSHOT_DIRS = 30
        private const val PERSIST_INTERVAL_MS = 2_000L

        // 快照读写的 JSON 小工具：只在本文件用得到，不往外暴露
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
                )
            }
        }

        private fun JSONArray.toFolders(): List<PanFolder> = buildList {
            for (i in 0 until length()) {
                val o = optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    PanFolder(
                        id = id,
                        name = o.optString("name").takeIf { it.isNotBlank() } ?: id,
                        parentId = o.optString("parentId")
                    )
                )
            }
        }

        private fun JSONArray.toImages(): List<PanImage> = buildList {
            for (i in 0 until length()) {
                val o = optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: id
                // 快照里不存 content://：重建一次很便宜，存了反而可能被旧授权串味
                add(PanImage(id = id, name = name, size = o.optLong("size")))
            }
        }
    }
}
