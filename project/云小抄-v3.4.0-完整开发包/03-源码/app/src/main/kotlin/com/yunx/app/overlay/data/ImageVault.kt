/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.9.8 小抄本地化：把攻略图从「系统缓存」改成
 * 「用户目录里的普通文件」，可选 SAF 自选目录，卸载不清、清缓存不动。
 * v1.9.10：索引不再只认「文件 id」，改成 id + 内容指纹两层。
 * 换分享链接 / 把文件夹挪个位置之后，网盘给的 id 会全变，但图还是那张图 ——
 * 这时候按「文件名 + 字节数」认领本地已有文件，不重下、不重复占空间。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地图片仓库。
 *
 * 与 `cacheDir/guide_image_cache`（v1.9.7 Coil 磁盘缓存）的区别：
 *  - 那是**缓存**，系统「清理缓存」/ 空间不足时会被整目录删掉，卸载 App 也没了；
 *  - 这里是**文件**，写在用户选定的目录（SAF）或应用私有目录，系统不会碰。
 *
 * 索引落在 `filesDir/local_images.json`：`平台:文件id → 路径 / 大小 / 原始文件名 / 原始字节数`。
 * 索引常驻内存（几万条也只是几百 KB），所以「这张图本地有没有」是纯内存判断，不进 IO。
 *
 * **为什么要记「原始文件名 + 原始字节数」**：
 * 网盘的 id 是「这一个文件在这一次转存里的编号」，用户把文件夹挪进另一个目录、
 * 或者重新保存一次分享链接，同一张图就会拿到一个全新的 id。只按 id 查索引 →
 * 全部 miss → 整区重下一遍，本地立刻多出一份同样大小的副本。
 * 记下文件名和网盘声明的字节数之后，新 id 可以「认领」旧文件：只加一条索引，
 * 不写第二个字节。这就是 [adopt] / [reconcile] 在做的事。
 */
class ImageVault private constructor(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = OverlayPrefs(app)

    private val indexFile = File(app.filesDir, INDEX_FILE)
    private val entries = ConcurrentHashMap<String, Entry>()

    /** SAF 写入用的锁：3 条下载线程可能同时需要建同一个分类目录 */
    private val safLock = Any()

    @Volatile
    private var safRootUri: Uri? = null

    @Volatile
    private var safRootTree: String? = null

    /** `分类/子目录` → 目录 document uri（v2.0.3） */
    private val safDirs = HashMap<String, Uri>()

    /** `分类/子目录` → (文件名 → document uri)。按目录分开，同名文件在不同分类互不影响 */
    private val safDocsByDir = HashMap<String, MutableMap<String, Uri>>()

    /**
     * v2.0.4：已经确认建好的目录链（`根|分类/子目录`）。
     *
     * 目录骨架是「知道就要建」的，而 `categories()` / `browse()` 每次刷新都会把
     * 整批目录再喂一遍 —— 没有这张表就是每开一次列表全量 mkdir 一遍
     * （SAF 下等于几十次 IPC 往返）。换存储根时整表作废。
     */
    private val ensuredDirs = HashSet<String>(64)

    @Volatile
    private var loaded = false

    @Volatile
    private var dirty = false

    // 指纹池：规范化文件名 → 本地已有文件。索引命中不到时用它「认领」。
    private val fpLock = Any()
    private val fpByName = HashMap<String, MutableList<Fingerprint>>()

    @Volatile
    private var fpBuilt = false

    @Volatile
    private var fpBuiltAt = 0L

    /**
     * 已经确认打不开的 uri（本地文件被删 / 目录被移走）。
     *
     * 只放内存：进程重启后重新给一次机会。作用是**打断自愈死循环** ——
     * 否则「认领到一个打不开的旧文件 → 加载失败 → 作废索引 → 又认领同一个文件」会无限转。
     */
    private val deadUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class Entry(
        val uri: String,
        val bytes: Long,
        val savedAt: Long,
        val folderId: String,
        val compressed: Boolean,
        /** v2.0.0：磁盘上是不是 `.yxz`（无损打包，用时解压） */
        val packed: Boolean = false,
        /** 网盘声明的原始字节数；0 = 旧版本索引，没记过 */
        val originSize: Long = 0L,
        /** 网盘上的原始文件名；空 = 旧版本索引，没记过 */
        val originName: String = "",
        /** 最后一次被看到的时间，空间不够时按它从旧到新淘汰 */
        val lastSeen: Long = 0L,
        /**
         * v2.0.1：**下载时拿到的原始字节**的 md5。
         *
         * 注意它跟磁盘上那份文件不一定对得上 —— 开了「压缩存储」是有损重编码、
         * 开了「无损打包」是 deflate 过。这里记的一直是网盘上那份的指纹，
         * 所以拿它和列表接口给的 md5 比是有意义的；拿磁盘文件现算则毫无意义。
         * 空 = 接口当年没给，或旧版本索引没记过。
         */
        val md5: String? = null,
        /**
         * v2.0.3：相对存储根目录的子路径（`分类/子目录`，根目录为空串）。
         *
         * 只是**记录**，真正的路径在 [uri] 里。留着它是为了判断「这张图下过，
         * 但还躺在旧版本的平铺目录里」—— 那种情况不用重下，搬一下位置就行。
         * 空串在旧索引里同时表示「在根目录」和「没记过」，两种都按「需要归位」处理。
         */
        val relDir: String = ""
    )

    /**
     * @param count 图片张数（索引条数，同一份文件被两个分区引用会算两张）
     * @param files 实际文件数
     * @param bytes 实际占用（**去重后**，不是把引用重复相加）
     * @param savedBytes 因为复用而少占的空间
     */
    data class Usage(
        val count: Int,
        val files: Int,
        val bytes: Long,
        val savedBytes: Long
    )

    /** 指纹池里的一项：一份**已经存在**的本地文件 */
    private data class Fingerprint(
        val uri: String,
        val bytes: Long,
        val name: String,
        val originSize: Long,
        val lastSeen: Long
    )

    data class DedupeResult(val removedFiles: Int, val freedBytes: Long)

    // ------------------------------------------------------------------
    // 开关与目录
    // ------------------------------------------------------------------

    fun enabled(): Boolean = prefs.imageVaultEnabled

    fun setEnabled(value: Boolean) {
        prefs.imageVaultEnabled = value
    }

    fun treeUri(): String? = prefs.imageTreeUri

    /**
     * 换存储目录。
     *
     * 只改「以后往哪写」，已经下到旧目录的图仍按索引可用 —— 用户换目录不是为了删图，
     * 顺手清掉几十兆反而会被当成 bug。要清有单独的「清空本地图片」。
     */
    fun setTreeUri(uri: String?) {
        prefs.imageTreeUri = uri
        invalidateSaf()
        invalidateFingerprints()
    }

    fun dirLabel(): String {
        val tree = prefs.imageTreeUri ?: return "应用私有目录（Android/data/${app.packageName}/files/guide_images）"
        val name = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                Uri.parse(tree),
                DocumentsContract.getTreeDocumentId(Uri.parse(tree))
            )
            resolver.query(
                doc,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: "自选目录"
    }

    /**
     * v2.5.0：**完整**路径，直接告诉用户「在文件管理器里去哪找」。
     *
     * [dirLabel] 只给了目录名（「云小抄」），用户在文件管理器里翻半天也找不到 ——
     * 他们真正需要的是「内部存储/Download/云小抄」这一整条。
     * SAF 的 documentId 本身就带卷和相对路径（`primary:Download/云小抄`），解码即可。
     */
    fun dirPathLabel(): String {
        val tree = prefs.imageTreeUri ?: return "Android/data/${app.packageName}/files/guide_images"
        return runCatching {
            val docId = Uri.decode(DocumentsContract.getTreeDocumentId(Uri.parse(tree)))
            when {
                docId.startsWith("primary:") ->
                    "内部存储/" + docId.removePrefix("primary:").trim('/')
                docId.contains(":") ->
                    "外置存储/" + docId.substringAfter(":").trim('/')
                else -> docId
            }
        }.getOrDefault("自选目录")
    }

    /** 当前是不是写在应用私有目录（Android 11+ 文件管理器看不进去） */
    fun isPrivateDir(): Boolean = prefs.imageTreeUri.isNullOrBlank()

    /**
     * v2.5.0：下载目录一键跟随「本地小抄」目录。
     *
     * 用户的真实用法就是「下载完切到本地小抄」，两个目录分开只会让人多找一次。
     * 没选过本地目录返回 false，调用方提示先选。
     */
    fun useLocalGuideDir(): Boolean {
        val uri = prefs.localTreeUri?.takeIf { it.isNotBlank() } ?: return false
        setTreeUri(uri)
        return true
    }

    // ------------------------------------------------------------------
    // 查询（同步、纯内存，网格每格都会调）
    // ------------------------------------------------------------------

    fun has(platform: PanPlatform, image: PanImage): Boolean =
        find(platform, image) != null

    /** 本地文件的 Coil 地址；没有返回 null */
    fun uri(platform: PanPlatform, image: PanImage): String? = find(platform, image)?.uri

    /** 不关心是哪个平台，只要有本地文件就算命中（预取器跳过用） */
    fun hasAny(image: PanImage): Boolean =
        find(PanPlatform.QUARK, image) != null || find(PanPlatform.XUNLEI, image) != null

    private fun find(platform: PanPlatform, image: PanImage): Entry? {
        ensureLoaded()
        val k = key(platform, image)
        val entry = entries[k] ?: return null
        // 私有目录的图用 file://，用户可以手动删：删了就当作没有，别让 Coil 一直报错占位
        if (entry.uri.startsWith("file://")) {
            val path = entry.uri.removePrefix("file://")
            if (!File(path).exists()) {
                entries.remove(k)
                dirty = true
                return null
            }
        }
        touch(k, entry)
        return entry
    }

    /**
     * 记一次「这张图刚被看到」。
     *
     * 12 小时内不重复写：网格每一帧都会查一次索引，真按帧写会把 ConcurrentHashMap
     * 和一个不可变对象刷成 GC 热点，而 LRU 淘汰的精度根本不需要到小时级。
     */
    private fun touch(k: String, entry: Entry) {
        val now = System.currentTimeMillis()
        if (now - entry.lastSeen < TOUCH_INTERVAL_MS) return
        entries[k] = entry.copy(lastSeen = now)
    }

    // ------------------------------------------------------------------
    // 内容复用：换链接 / 移动文件夹之后，新 id 认领旧文件
    // ------------------------------------------------------------------

    /**
     * 批量认领。
     *
     * 放在「目录内容刚拿到」的地方调用一次即可：网盘返回的 `images` 里每一张，
     * 只要本地有同名同大小的文件，就直接登记成「已下载」，后面下载器会整批跳过。
     *
     * @return 认出多少张
     */
    fun reconcile(platform: PanPlatform, folderId: String, images: List<PanImage>): Int {
        if (images.isEmpty()) return 0
        if (!prefs.imageDedupEnabled) return 0
        ensureLoaded()
        var reused = 0
        images.forEach { image ->
            if (find(platform, image) != null) return@forEach
            if (adopt(platform, image, folderId) != null) reused++
        }
        if (reused > 0) dirty = true
        return reused
    }

    /**
     * 给一张图找本地已有的同一份文件并登记；找不到返回 null。
     *
     * 判断顺序（从可信到宽松）：
     *  1. 网盘原始字节数完全一致 —— 基本可以认定是同一张；
     *  2. 旧索引没记原始字节数时，退化成「本地文件大小 == 网盘大小」（未压缩下载的旧数据）；
     *  3. 本地明显比网盘小（压缩过的旧数据）且同名候选只有一个 —— 才敢认。
     */
    fun adopt(platform: PanPlatform, image: PanImage, folderId: String): String? {
        ensureLoaded()
        find(platform, image)?.let { return it.uri }
        val pool = fingerprints()[normalizeName(image.name)] ?: return null
        if (pool.isEmpty()) return null

        // 网盘接口偶尔不给 size（0）。这时候任何按大小的比较都没有意义，
        // 一旦放过就会把「所有大小未知的图」和「所有大小读不出来的文件」互相认成一堆。
        if (image.size <= 0L) return null

        val exact = pool.firstOrNull { it.originSize > 0L && it.originSize == image.size }
        val sameBytes = pool.firstOrNull { it.originSize <= 0L && it.bytes == image.size }
        val shrunk = if (pool.size == 1) {
            pool[0].takeIf {
                it.originSize <= 0L &&
                    it.bytes < image.size &&
                    it.bytes >= image.size / 10L
            }
        } else {
            null
        }
        val pick = exact ?: sameBytes ?: shrunk ?: return null
        bind(platform, image, folderId, pick)
        return pick.uri
    }

    private fun bind(platform: PanPlatform, image: PanImage, folderId: String, fp: Fingerprint) {
        val now = System.currentTimeMillis()
        entries[key(platform, image)] = Entry(
            uri = fp.uri,
            bytes = fp.bytes,
            savedAt = now,
            folderId = folderId,
            compressed = fp.bytes < image.size,
            packed = PackedImage.isPackedName(fp.uri),
            originSize = image.size,
            originName = image.name,
            lastSeen = now,
            md5 = SyncPlan.normalizeMd5(image.md5)
        )
        dirty = true
    }

    /**
     * 指纹池。
     *
     * 除了索引里已有的条目，还会扫一遍**当前目录里真实存在的文件**：
     * 索引文件被清掉（重装、清应用数据）但图片目录还在时，靠这一步就能全部认回来，
     * 不用因为「索引没了」把几百张图重下一遍。
     */
    private fun fingerprints(): Map<String, MutableList<Fingerprint>> {
        val now = System.currentTimeMillis()
        if (fpBuilt && now - fpBuiltAt < FP_TTL_MS) return fpByName
        synchronized(fpLock) {
            if (fpBuilt && now - fpBuiltAt < FP_TTL_MS) return fpByName
            fpByName.clear()
            val known = HashSet<String>(entries.size)
            entries.values.forEach { e ->
                if (e.uri in deadUris) return@forEach
                if (known.add(e.uri)) {
                    addFingerprint(
                        Fingerprint(
                            uri = e.uri,
                            bytes = e.bytes,
                            name = normalizeName(e.originName.ifBlank { guessNameFromUri(e.uri) }),
                            originSize = e.originSize,
                            lastSeen = e.lastSeen
                        )
                    )
                }
            }
            // 目录里有、索引里没有的（索引丢失 / 用户手动拷回来）
            scanDirectory(known).forEach { addFingerprint(it) }
            fpBuilt = true
            fpBuiltAt = now
        }
        return fpByName
    }

    private fun addFingerprint(fp: Fingerprint) {
        if (fp.name.isBlank()) return
        // 大小读不出来（0）的文件不参与匹配：按 0 比 0 会把一堆无关文件互相认领
        if (fp.bytes <= 0L) return
        val list = fpByName.getOrPut(fp.name) { ArrayList(2) }
        // 同一个 uri 不重复入池（索引与目录扫描会撞上）
        if (list.any { it.uri == fp.uri }) return
        list += fp
        // 候选太多的名字（比如整个目录都叫 1.jpg）没有区分度，留最新的三个就够
        if (list.size > 4) {
            list.sortByDescending { it.lastSeen }
            while (list.size > 4) list.removeAt(list.size - 1)
        }
    }

    private fun scanDirectory(known: Set<String>): List<Fingerprint> {
        val out = ArrayList<Fingerprint>(256)
        val tree = prefs.imageTreeUri
        if (!tree.isNullOrBlank()) {
            runCatching { scanSafTree(Uri.parse(tree), known, out) }
        } else {
            runCatching { scanPrivate(privateDir(), 0, known, out) }
        }
        return out
    }

    /**
     * 递归扫私有目录（v2.0.3）。
     *
     * 只扫一层的话，按分类建了子目录之后，索引一丢就再也认不回任何一张图 ——
     * 用户看到的是「明明在手机里，却整区重新下载」。
     */
    private fun scanPrivate(
        dir: File,
        depth: Int,
        known: Set<String>,
        out: MutableList<Fingerprint>
    ) {
        if (depth > FolderPaths.MAX_DEPTH || out.size >= MAX_SCAN_FILES) return
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        files.forEach { f ->
            if (out.size >= MAX_SCAN_FILES) return
            if (f.isDirectory) {
                scanPrivate(f, depth + 1, known, out)
                return@forEach
            }
            if (!f.isFile || !looksLikeOurs(f.name)) return@forEach
            val docUri = Uri.fromFile(f).toString()
            if (docUri in known || docUri in deadUris) return@forEach
            out += Fingerprint(
                uri = docUri,
                bytes = f.length(),
                name = normalizeName(originalNameOf(f.name)),
                originSize = if (PackedImage.isPackedName(f.name)) {
                    PackedImage.rawLengthOf(f).toLong()
                } else {
                    0L
                },
                lastSeen = f.lastModified()
            )
        }
    }

    /**
     * 广度优先扫 SAF 目录树。
     *
     * 每个子目录一次 query，成本比单层高，所以卡死上限：深度 [FolderPaths.MAX_DEPTH]、
     * 最多 [MAX_SCAN_DIRS] 个目录 / [MAX_SCAN_FILES] 个文件。指纹池本身有 60 秒缓存，
     * 正常翻页不会反复触发。
     */
    private fun scanSafTree(tree: Uri, known: Set<String>, out: MutableList<Fingerprint>) {
        val root = safRoot(tree) ?: return
        val queue = ArrayDeque<Pair<Uri, Int>>()
        queue += root to 0
        var dirs = 0
        while (queue.isNotEmpty() && dirs < MAX_SCAN_DIRS && out.size < MAX_SCAN_FILES) {
            val (dir, depth) = queue.removeFirst()
            dirs++
            runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getDocumentId(dir)
                )
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < FolderPaths.MAX_DEPTH) {
                                queue += docUri to (depth + 1)
                            }
                            continue
                        }
                        if (!looksLikeOurs(name)) continue
                        if (docUri.toString() in known || docUri.toString() in deadUris) continue
                        out += Fingerprint(
                            uri = docUri.toString(),
                            bytes = c.getLong(2).coerceAtLeast(0L),
                            name = normalizeName(originalNameOf(name)),
                            // v2.0.0：`.yxz` 的磁盘大小不是原图大小，读一下头里的原始长度，
                            // 否则「换链接不重下」会认不出来，整批重新下载
                            originSize = if (PackedImage.isPackedName(name)) {
                                packedRawLengthOf(docUri.toString())
                            } else {
                                0L
                            },
                            lastSeen = 0L
                        )
                        if (out.size >= MAX_SCAN_FILES) return
                    }
                }
            }
        }
    }

    fun invalidateFingerprints() {
        synchronized(fpLock) {
            fpBuilt = false
            fpByName.clear()
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 保存一张图。
     *
     * @param folderId   所属目录 id，用于「小抄更新后清掉已删除的旧图」
     * @param originSize 网盘声明的原始大小（不是压缩后的大小），下次换链接时靠它认领
     * @param relDir     v2.0.3：相对存储根的目录链（`["分类","子目录"]`），按网盘结构建目录
     * @return 成功时返回可直接交给 Coil 的地址
     */
    fun save(
        platform: PanPlatform,
        image: PanImage,
        folderId: String,
        bytes: ByteArray,
        compressed: Boolean,
        extension: String,
        originSize: Long = 0L,
        /** v2.0.1：下载到的原始字节 md5；和 [originSize] 一起构成「作者有没有改过这张图」的证据 */
        originMd5: String? = null,
        relDir: List<String> = emptyList()
    ): String? {
        if (bytes.isEmpty()) return null
        ensureLoaded()
        val segments = cleanSegments(relDir)
        // v2.0.0：先试无损打包。压不动（JPEG 这类已经是熵编码的）就存原图，
        // 绝不为「开了压缩」而把文件压得更大、还多付一次解压。
        val packedBytes = if (prefs.imagePack && !prefs.lowPerfMode) {
            runCatching { PackedImage.encode(bytes, extension) }.getOrNull()
        } else {
            null
        }
        val usePack = packedBytes != null && ImagePackPolicy.shouldPack(
            enabled = prefs.imagePack,
            lowPerf = prefs.lowPerfMode,
            rawSize = bytes.size,
            packedSize = packedBytes.size
        )
        val stored = if (usePack) packedBytes!! else bytes
        val name = fileName(image, extension, usePack)
        val mime = if (usePack) "application/octet-stream" else mimeOf(extension)
        val written = writeFile(segments, name, stored, mime) ?: return null
        // 同名文件刚被重写，之前拉黑的地址现在有效了
        deadUris -= written
        // v2.0.1：新文件已经落盘成功，再清理「同目录、同原名、大小不同」的旧版本。
        // 顺序不能反 —— 先删后写的话，一旦写失败，用户就既没有新图也没有旧图了。
        runCatching { dropSuperseded(platform, image, folderId, keepUri = written) }

        val k = key(platform, image)
        val old = entries[k]
        if (old != null && old.uri != written) releaseUri(old.uri, exceptKey = k)

        val now = System.currentTimeMillis()
        entries[k] = Entry(
            uri = written,
            bytes = stored.size.toLong(),
            savedAt = now,
            folderId = folderId,
            compressed = compressed,
            packed = usePack,
            originSize = if (originSize > 0L) originSize else image.size,
            originName = image.name,
            lastSeen = now,
            md5 = SyncPlan.normalizeMd5(originMd5) ?: SyncPlan.normalizeMd5(image.md5),
            relDir = segments.joinToString("/")
        )
        dirty = true
        invalidateFingerprints()
        return written
    }

    /**
     * 覆盖更新：同一目录里、同一张原图、但字节数对不上的旧版本，删掉。
     *
     * 为什么需要它：文件名是「原名 + id 哈希」。作者改了图（比如修正地图错别字）重新上传，
     * 如果用户是重新保存了一次分享链接，网盘会给出**全新的 fid** → 哈希变了 → 文件名也变了 →
     * 不动手就是两份共存，一份是对的、一份是错的，用户翻到哪份全看运气。
     *
     * 判定只敢用「同目录 + 同归一化文件名 + 双方原始大小都已知且不同」三条同时成立：
     * 少一条就可能删掉一张同名但确实是另一张的图（攻略图里 `1.jpg`、`2.jpg` 太常见）。
     */
    private fun dropSuperseded(
        platform: PanPlatform,
        image: PanImage,
        folderId: String,
        keepUri: String
    ): Int {
        if (image.size <= 0L) return 0
        val norm = normalizeName(image.name)
        if (norm.isBlank()) return 0
        val selfKey = key(platform, image)
        val victims = entries.entries.filter { (k, e) ->
            k != selfKey &&
                e.uri != keepUri &&
                e.folderId == folderId &&
                e.originName.isNotBlank() &&
                normalizeName(e.originName) == norm &&
                e.originSize > 0L &&
                e.originSize != image.size
        }
        if (victims.isEmpty()) return 0

        val refs = refCounts()
        var removed = 0
        victims.forEach { (k, e) ->
            entries.remove(k)
            dirty = true
            removed++
            val left = (refs[e.uri] ?: 1) - 1
            refs[e.uri] = left
            if (left <= 0) deletePhysical(e.uri)
        }
        // 目录里 `文件名 → uri` 的内存表刚被删掉了一条，整表作废重扫：
        // 留着的话，下次写同名文件会往一个已经不存在的 document 上写，直接抛异常
        invalidateSafDocs()
        invalidateFingerprints()
        return removed
    }

    // ------------------------------------------------------------------
    // 目录归位（v2.0.3）
    // ------------------------------------------------------------------

    /**
     * 这张图下过、但没躺在它该在的分类目录里。
     *
     * 只在「这条索引独占这份文件」时才回答 true：同一份文件被两条索引引用时，
     * 挪走会把另一条指向一个不存在的 document。
     */
    fun needsPlacement(platform: PanPlatform, image: PanImage, relDir: List<String>): Boolean {
        ensureLoaded()
        val entry = entries[key(platform, image)] ?: return false
        val target = cleanSegments(relDir).joinToString("/")
        if (entry.relDir == target) return false
        if (!sameRoot(entry.uri)) return false
        return (refCounts()[entry.uri] ?: 1) <= 1
    }

    /**
     * 把已下载的文件挪进目标目录，返回是否真的动过。
     *
     * 不重新下载：文件本来就在本地，只是位置不对。私有目录用 rename（同一分区，瞬间完成）；
     * SAF 拿不到源父目录的 document uri（跨目录 move 需要它），退化成「复制 + 删旧」——
     * 本地读写几百 KB，比重新走一遍网络快得多，也不费流量。
     */
    fun place(platform: PanPlatform, image: PanImage, relDir: List<String>): Boolean {
        ensureLoaded()
        val k = key(platform, image)
        val entry = entries[k] ?: return false
        val segments = cleanSegments(relDir)
        val target = segments.joinToString("/")
        if (entry.relDir == target) return false
        if (!sameRoot(entry.uri)) return false
        if ((refCounts()[entry.uri] ?: 1) > 1) return false

        val diskName = runCatching { Uri.decode(entry.uri).substringAfterLast('/') }
            .getOrDefault("")
        if (diskName.isBlank() || !looksLikeOurs(diskName)) return false

        val moved = if (entry.uri.startsWith("file://")) {
            movePrivate(entry.uri, segments, diskName)
        } else {
            val mime = if (entry.packed) {
                "application/octet-stream"
            } else {
                mimeOf(extensionOf(diskName))
            }
            copySaf(entry.uri, segments, diskName, mime)
        } ?: return false

        entries[k] = entry.copy(uri = moved, relDir = target)
        dirty = true
        invalidateFingerprints()
        return true
    }

    /**
     * 这条索引指向的文件是不是还在**当前**存储根之下。
     *
     * 用户换过存储目录之后，旧目录里的图不能被「归位」搬进新目录再删掉 ——
     * 那是他自己选的文件夹，删他一个文件比多占几十兆严重得多。
     * 换目录只影响「以后往哪写」，这条规则跟 [setTreeUri] 的注释保持一致。
     */
    private fun sameRoot(uri: String): Boolean {
        if (uri.startsWith("file://")) {
            val path = runCatching { Uri.parse(uri).path }.getOrNull() ?: return false
            return File(path).absolutePath.startsWith("${privateDir().absolutePath}${File.separator}")
        }
        val tree = prefs.imageTreeUri?.takeIf { it.isNotBlank() } ?: return false
        return uri.startsWith(tree)
    }

    private fun movePrivate(oldUri: String, segments: List<String>, name: String): String? {
        val path = runCatching { Uri.parse(oldUri).path }.getOrNull() ?: return null
        val src = File(path)
        if (!src.exists()) return null
        val dir = privateDirOf(segments)
        if (!dir.exists() && !dir.mkdirs()) return null
        val dst = File(dir, name)
        if (dst.absolutePath == src.absolutePath) return oldUri
        val ok = runCatching {
            if (dst.exists()) dst.delete()
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
            dst.exists()
        }.getOrDefault(false)
        return if (ok) Uri.fromFile(dst).toString() else null
    }

    private fun copySaf(oldUri: String, segments: List<String>, name: String, mime: String): String? {
        val tree = prefs.imageTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        synchronized(safLock) {
            val dir = ensureSafDir(tree, segments) ?: return null
            val docs = safDocs(tree, dir, segments.joinToString("/"))
            // 目标位置已有同名文件：多半是上次搬到一半退出（写完了还没来得及删旧的），直接用现成的
            docs[name]?.let { existing ->
                if (existing.toString() != oldUri) {
                    runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(oldUri)) }
                }
                return existing.toString()
            }
            val doc = DocumentsContract.createDocument(resolver, dir, mime, name) ?: return null
            val copied = runCatching {
                resolver.openInputStream(Uri.parse(oldUri))?.use { input ->
                    resolver.openOutputStream(doc, "w")?.use { out ->
                        input.copyTo(out, 64 * 1024)
                    }
                } != null
            }.getOrDefault(false)
            if (!copied) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                return null
            }
            // 新的写成功了才删旧的：中途断电最多多一份，不会两头都空
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(oldUri)) }
            docs[name] = doc
            return doc.toString()
        }
    }

    /**
     * 去掉路径里的空段、`.`、`..` 和分隔符，并限制层数。
     *
     * [FolderMap] 已经清洗过一遍，这里是最后一道：即便上层给了脏数据，
     * 也不能让 `../../` 把文件写到存储根外面去。
     */
    private fun cleanSegments(relDir: List<String>): List<String> =
        relDir.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
            .take(FolderPaths.MAX_DEPTH)
            .toList()

    // ------------------------------------------------------------------
    // 同步判定（v2.0.1）
    // ------------------------------------------------------------------

    /**
     * 这张图本地这一份的指纹；本地没有返回 null。
     *
     * 与 [has] 的区别：`has` 只回答「有没有」，这里还要回答「是不是同一份」。
     */
    fun stampOf(platform: PanPlatform, image: PanImage): LocalStamp? {
        val e = find(platform, image) ?: return null
        return LocalStamp(originSize = e.originSize, md5 = e.md5, bytes = e.bytes)
    }

    /**
     * 判定这张图该「跳过 / 新下 / 覆盖」。
     *
     * 比 [has] 多做了两件事：
     *  1. 同 id 命中时，再比大小与 md5 —— 作者原地换图（id 不变）也能发现；
     *  2. 同 id 没命中时，先尝试按指纹认领（换链接 / 挪目录），认领到的再比一次，
     *     避免「换个链接就整区重下」。
     */
    fun verdict(platform: PanPlatform, image: PanImage, folderId: String): SyncVerdict {
        ensureLoaded()
        val remote = RemoteStamp(name = image.name, size = image.size, md5 = image.md5)

        entries[key(platform, image)]?.let { entry ->
            return SyncPlan.decide(
                LocalStamp(originSize = entry.originSize, md5 = entry.md5, bytes = entry.bytes),
                remote
            )
        }

        // 没有同 id 的条目：试一次认领（内部会写一条新索引）
        if (adopt(platform, image, folderId) != null) {
            entries[key(platform, image)]?.let { entry ->
                return SyncPlan.decide(
                    LocalStamp(originSize = entry.originSize, md5 = entry.md5, bytes = entry.bytes),
                    remote
                )
            }
        }
        return SyncVerdict.NEW
    }

    /**
     * 只读 `.yxz` 的头，拿回原始字节数。不是打包文件 / 读失败一律 0。
     *
     * 只读 32 字节：目录里几百个文件也不会因为「认领」把整张图读进来。
     */
    private fun packedRawLengthOf(uri: String): Long = runCatching {
        if (!PackedImage.isPackedName(uri)) return 0
        if (uri.startsWith("file://")) {
            val path = Uri.parse(uri).path ?: return 0
            PackedImage.rawLengthOf(File(path)).toLong()
        } else {
            resolver.openInputStream(Uri.parse(uri))?.use { input ->
                val buf = ByteArray(32)
                val n = input.read(buf)
                if (n <= 0) 0L else (PackedImage.headOf(buf, n)?.rawLength ?: 0).toLong()
            } ?: 0L
        }
    }.getOrDefault(0L)

    /**
     * v2.0.4：在本地把 [relDir] 这条目录链建出来 —— 只建目录，不写文件。
     *
     * 只在「下载某张图时顺手建目录」的话，本地永远只有下过图的那些分类，
     * 用户在文件管理器里看到的是一棵残树。目录结构应当独立于图片存在：
     * 网盘有什么分类，本地就有什么文件夹，哪怕里面一张图都还没有。
     *
     * 幂等：同一条链只真正 mkdir 一次，之后命中 [ensuredDirs] 直接返回。
     *
     * @return 目录现在存在（或本次建成功）
     */
    fun ensureDir(relDir: List<String>): Boolean {
        val segments = cleanSegments(relDir)
        val tree = prefs.imageTreeUri?.takeIf { it.isNotBlank() }
        val key = "${tree ?: PRIVATE_ROOT}|${segments.joinToString("/")}"
        if (synchronized(safLock) { key in ensuredDirs }) return true
        val ok = if (tree != null) {
            runCatching {
                synchronized(safLock) { ensureSafDir(Uri.parse(tree), segments) != null }
            }.getOrDefault(false)
        } else {
            runCatching {
                val dir = privateDirOf(segments)
                dir.isDirectory || dir.mkdirs()
            }.getOrDefault(false)
        }
        if (ok) synchronized(safLock) { ensuredDirs += key }
        return ok
    }

    private fun writeFile(
        segments: List<String>,
        name: String,
        bytes: ByteArray,
        mime: String
    ): String? {
        val tree = prefs.imageTreeUri
        if (!tree.isNullOrBlank()) {
            val uri = Uri.parse(tree)
            // SAF 目录可能被用户在文件管理器里删掉 / 改名，缓存里的 document 就失效了。
            // 第一次失败时把目录缓存整体作废再试一次 —— 用户明明选了外置目录，
            // 结果图悄悄落进 Android/data，事后几乎发现不了。
            runCatching { writeToTree(uri, segments, name, bytes, mime) }.getOrNull()
                ?.let { return it }
            invalidateSaf()
            runCatching { writeToTree(uri, segments, name, bytes, mime) }.getOrNull()
                ?.let { return it }
            return runCatching { writeToPrivate(segments, name, bytes) }.getOrNull()
        }
        return runCatching { writeToPrivate(segments, name, bytes) }.getOrNull()
    }

    private fun writeToPrivate(segments: List<String>, name: String, bytes: ByteArray): String? {
        val dir = privateDirOf(segments)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private fun privateDir(): File =
        app.getExternalFilesDir(DIR_NAME) ?: File(app.filesDir, DIR_NAME)

    private fun privateDirOf(segments: List<String>): File =
        if (segments.isEmpty()) privateDir()
        else File(privateDir(), segments.joinToString(File.separator))

    /**
     * 写进用户选的 SAF 目录（v2.0.3 起支持子目录）。
     *
     * 文件名是「原名 + id 哈希」，同一个文件重下必然同名。这里按目录维护
     * `文件名 → document uri` 的内存表：命中就直接覆盖写（不会生成 `xxx (1).jpg` 副本），
     * 没命中才 create。目录链也缓存，逐级 `createDocument(MIME_TYPE_DIR)` 只发生一次。
     */
    private fun writeToTree(
        tree: Uri,
        segments: List<String>,
        name: String,
        bytes: ByteArray,
        mime: String
    ): String {
        synchronized(safLock) {
            val dir = ensureSafDir(tree, segments) ?: throw IllegalStateException("目录授权已失效")
            val docs = safDocs(tree, dir, segments.joinToString("/"))
            val doc = docs[name]
                ?: DocumentsContract.createDocument(resolver, dir, mime, name)
                    ?.also { docs[name] = it }
                ?: throw IllegalStateException("无法创建文件")
            resolver.openOutputStream(doc, "w")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IllegalStateException("无法写入文件")
            return doc.toString()
        }
    }

    /** 逐级找到 / 建出 `segments` 这条目录链；空链就是存储根 */
    private fun ensureSafDir(tree: Uri, segments: List<String>): Uri? {
        var current = safRoot(tree) ?: return null
        if (segments.isEmpty()) return current
        val key = StringBuilder()
        segments.forEach { seg ->
            if (key.isNotEmpty()) key.append('/')
            key.append(seg)
            val path = key.toString()
            current = safDirs[path] ?: run {
                val dir = findChildDir(tree, current, seg)
                    ?: DocumentsContract.createDocument(
                        resolver,
                        current,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        seg
                    )
                    ?: throw IllegalStateException("无法创建目录：$seg")
                safDirs[path] = dir
                dir
            }
        }
        return current
    }

    private fun safRoot(tree: Uri): Uri? {
        if (safRootTree == tree.toString()) safRootUri?.let { return it }
        val root = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
        }.getOrNull() ?: return null
        safRootUri = root
        safRootTree = tree.toString()
        return root
    }

    /** 父目录下有没有同名的子目录（用户手动建过、或者上次建到一半就退出了） */
    private fun findChildDir(tree: Uri, parent: Uri, name: String): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getDocumentId(parent)
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (c.getString(1) != name) continue
                return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        null
    }.getOrNull()

    /** 某个目录里 `文件名 → uri`；首次使用时查一次，之后走内存 */
    private fun safDocs(tree: Uri, dir: Uri, dirKey: String): MutableMap<String, Uri> {
        safDocsByDir[dirKey]?.let { return it }
        val map = HashMap<String, Uri>(64)
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(dir)
            )
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    // 子目录不能进这张表：同名时会被当成「已存在的文件」打开写流，直接失败
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val id = c.getString(0) ?: continue
                    val display = c.getString(1) ?: continue
                    map[display] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        safDocsByDir[dirKey] = map
        return map
    }

    /** 目录/文件缓存整体作废（外部改动、删除、换目录之后） */
    private fun invalidateSaf() {
        synchronized(safLock) {
            safRootUri = null
            safRootTree = null
            safDirs.clear()
            safDocsByDir.clear()
            // v2.0.4：目录可能被用户在文件管理器里删了，已建标记跟着作废，下次重新建
            ensuredDirs.clear()
        }
    }

    /** 只作废「文件名 → uri」表，目录链还可用 */
    private fun invalidateSafDocs() {
        synchronized(safLock) { safDocsByDir.clear() }
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /** 小抄更新后：这个目录下已经不在网盘里的旧图，本地也删掉，别越攒越多 */
    fun pruneFolder(platform: PanPlatform, folderId: String, keepIds: Set<String>): Int {
        ensureLoaded()
        val prefix = "${platform.name}:"
        val stale = entries.entries.filter { (k, v) ->
            k.startsWith(prefix) && v.folderId == folderId && k.removePrefix(prefix) !in keepIds
        }
        if (stale.isEmpty()) return 0
        val refs = refCounts()
        var removed = 0
        stale.forEach { (k, v) ->
            entries.remove(k)
            dirty = true
            removed++
            val left = (refs[v.uri] ?: 1) - 1
            refs[v.uri] = left
            if (left <= 0) deletePhysical(v.uri)
        }
        invalidateFingerprints()
        flush()
        return removed
    }

    /**
     * 严格去重：只合并「同名 + 字节数完全相同」的重复文件。
     *
     * 为什么不按内容哈希：小抄动辄几百张，全量读盘算哈希要几十秒；而同一张攻略图
     * 在不同链接里即使被重新压缩过，字节数也极难撞在一起。这个条件足够保守，
     * 又刚好覆盖「换了链接 / 挪了文件夹 → 同一批图又下一遍」这种最常见的情况。
     *
     * 被合并的只是**多出来的副本**，保留哪一份都行，所以挑最近看过的那份。
     */
    fun dedupeStrict(): DedupeResult {
        ensureLoaded()
        backfillNames()
        val groups = HashMap<String, MutableList<Pair<String, Entry>>>(entries.size)
        entries.forEach { (k, e) ->
            // 旧索引没记原始文件名，无法安全判断「同名」，不参与；
            // 大小未知（0）的也不参与：两个 0 字节条目会被当成同一份，删错文件
            if (e.originName.isBlank() || e.bytes <= 0L) return@forEach
            val fp = "${normalizeName(e.originName)}|${e.bytes}"
            groups.getOrPut(fp) { ArrayList(2) } += k to e
        }

        var files = 0
        var freed = 0L
        groups.values.forEach { list ->
            if (list.size < 2) return@forEach
            val byUri = list.groupBy { it.second.uri }
            // 只合并两份都还在的：万一用户手动删了其中一份，保留现有的、下次访问时自愈即可，
            // 不能把还活着的引用重指向一个已经不存在的文件。
            val alive = byUri.filterKeys { uriAlive(it) }
            if (alive.size < 2) return@forEach
            val keep = alive.maxByOrNull { (_, refs) -> refs.maxOf { it.second.lastSeen } }?.key
                ?: return@forEach
            for ((uri, refs) in alive) {
                if (uri == keep) continue
                refs.forEach { (k, e) -> entries[k] = e.copy(uri = keep) }
                deletePhysical(uri)
                files++
                freed += refs.first().second.bytes
                dirty = true
            }
        }
        if (files > 0) {
            invalidateFingerprints()
            flush()
        }
        return DedupeResult(files, freed)
    }

    /**
     * 按时间淘汰，把总占用压到 [maxBytes] 以内。
     *
     * 保护规则：当前正在看的分区（[keepFolders]）不动，最近 [PROTECT_MS] 内看过的也不动 ——
     * 用户刚翻过的图第二天就没了，比占点空间更难受。
     */
    fun trimToLimit(maxBytes: Long, keepFolders: Set<String> = emptySet()): DedupeResult {
        if (maxBytes <= 0L) return DedupeResult(0, 0)
        ensureLoaded()
        val usage = usage()
        if (usage.bytes <= maxBytes) return DedupeResult(0, 0)

        val cutoff = System.currentTimeMillis() - PROTECT_MS
        val candidates = entries.entries
            .filter { (_, e) -> e.folderId !in keepFolders && e.lastSeen < cutoff }
            .sortedBy { it.value.lastSeen }

        var bytes = usage.bytes
        var files = 0
        var freed = 0L
        val refs = refCounts()
        for ((k, e) in candidates) {
            if (bytes <= maxBytes) break
            entries.remove(k)
            dirty = true
            val left = (refs[e.uri] ?: 1) - 1
            refs[e.uri] = left
            if (left <= 0) {
                deletePhysical(e.uri)
                bytes -= e.bytes
                freed += e.bytes
                files++
            }
        }
        if (files > 0) {
            invalidateFingerprints()
            flush()
        }
        return DedupeResult(files, freed)
    }

    /**
     * 给 v1.9.9 及以前写下的旧条目补原始文件名。
     *
     * 旧索引只存了 `uri`，没有 `originName`。文件名就藏在 uri 里（`file://…/第_1_页_3fa8c1.jpg`
     * 或 SAF 的 docId 末段），反推一次就能让它们参与去重 —— 否则升级之后
     * 「历史上重复下载的那一份」永远被当成「无法判断同名」而留着。
     */
    private fun backfillNames() {
        var changed = 0
        entries.entries.forEach { (k, e) ->
            if (e.originName.isNotBlank()) return@forEach
            val guessed = guessNameFromUri(e.uri)
            if (guessed.isBlank()) return@forEach
            entries[k] = e.copy(originName = guessed)
            changed++
        }
        if (changed > 0) dirty = true
    }

    /**
     * 清掉指向已不存在文件的索引（用户手动删了私有目录里的图）。
     *
     * SAF 的 `content://` 不做校验：为了一条索引去 query 一次系统文档太贵，
     * 而且授权失效时 query 也会失败，分不清「文件没了」和「没权限」。
     */
    fun purgeMissing(): Int {
        ensureLoaded()
        var removed = 0
        entries.entries.forEach { (k, e) ->
            if (!e.uri.startsWith("file://")) return@forEach
            if (File(e.uri.removePrefix("file://")).exists()) return@forEach
            entries.remove(k)
            removed++
            dirty = true
        }
        if (removed > 0) {
            invalidateFingerprints()
            flush()
        }
        return removed
    }

    private fun uriAlive(uri: String): Boolean =
        if (uri.startsWith("file://")) File(uri.removePrefix("file://")).exists() else true

    /**
     * 本地文件打不开了（被手动删掉 / 目录被移走 / SAF 授权失效）。
     *
     * 只作废索引，**不删文件** —— 读失败可能只是这一下的事，物理删掉就真没了。
     * 下次进分区重新认领，认不回来再走网络。
     */
    fun invalidate(image: PanImage): Int {
        ensureLoaded()
        var removed = 0
        PanPlatform.entries.forEach { platform ->
            val entry = entries.remove(key(platform, image)) ?: return@forEach
            // 记进黑名单：指纹池不再把它当成「本地已有」，否则会一直认领一个打不开的文件
            if (deadUris.size < MAX_DEAD_URIS) deadUris += entry.uri
            removed++
            dirty = true
        }
        if (removed > 0) {
            invalidateFingerprints()
            flush()
        }
        return removed
    }

    fun clearAll(): Int {
        ensureLoaded()
        val count = entries.size
        entries.values.map { it.uri }.toHashSet().forEach { deletePhysical(it) }
        entries.clear()
        invalidateSaf()
        // v2.0.4：只删图，目录骨架留着。
        // 用户要的是「本地跟网盘一样的目录结构」—— 清一次图就把分类文件夹全清掉，
        // 下次还得等他再进一遍分类才慢慢长回来，看起来就像功能坏了。
        // 空目录不占空间，留着更符合直觉。
        invalidateFingerprints()
        dirty = true
        flush()
        return count
    }

    private fun deletePhysical(uri: String) {
        runCatching {
            if (uri.startsWith("file://")) {
                File(uri.removePrefix("file://")).delete()
            } else {
                DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
            }
        }
    }

    /** 只删文件，不动索引 —— 索引里其它还引用这个 uri 的条目仍然有效时用 */
    private fun releaseUri(uri: String, exceptKey: String) {
        val stillUsed = entries.any { (k, v) -> k != exceptKey && v.uri == uri }
        if (!stillUsed) deletePhysical(uri)
    }

    private fun refCounts(): MutableMap<String, Int> {
        val map = HashMap<String, Int>(entries.size)
        entries.values.forEach { map[it.uri] = (map[it.uri] ?: 0) + 1 }
        return map
    }

    fun usage(): Usage {
        ensureLoaded()
        var bytes = 0L
        var saved = 0L
        val sizes = HashMap<String, Long>(entries.size)
        entries.values.forEach { sizes[it.uri] = it.bytes }
        sizes.values.forEach { bytes += it }
        // 同一个文件被 N 个 id 引用时，少占的就是 (N-1) 份
        val refs = refCounts()
        refs.forEach { (uri, n) ->
            if (n > 1) saved += (sizes[uri] ?: 0L) * (n - 1)
        }
        return Usage(
            count = entries.size,
            files = sizes.size,
            bytes = bytes,
            savedBytes = saved
        )
    }

    // ------------------------------------------------------------------
    // 索引落盘
    // ------------------------------------------------------------------

    @Synchronized
    fun flush() {
        if (!dirty) return
        dirty = false
        val ok = runCatching {
            val json = JSONObject()
            entries.forEach { (k, v) ->
                json.put(
                    k,
                    JSONObject()
                        .put("u", v.uri)
                        .put("s", v.bytes)
                        .put("t", v.savedAt)
                        .put("f", v.folderId)
                        .put("c", v.compressed)
                        .put("pk", v.packed)
                        .put("o", v.originSize)
                        .put("n", v.originName)
                        .put("v", v.lastSeen)
                        .put("m", v.md5 ?: "")
                        .put("d", v.relDir)
                )
            }
            val tmp = File(app.filesDir, "$INDEX_FILE.tmp")
            tmp.writeText(json.toString())
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        }.isSuccess
        // 写失败（空间满 / 权限异常）要把脏标记放回去，不然这一批索引就永远不落盘了
        if (!ok) dirty = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching {
                if (!indexFile.exists()) return
                if (indexFile.length() > MAX_INDEX_BYTES) {
                    indexFile.delete()
                    return
                }
                val json = JSONObject(indexFile.readText())
                json.keys().forEach { k ->
                    val obj = json.optJSONObject(k) ?: return@forEach
                    val uri = obj.optString("u").takeIf { it.isNotBlank() } ?: return@forEach
                    val savedAt = obj.optLong("t", 0L)
                    entries[k] = Entry(
                        uri = uri,
                        bytes = obj.optLong("s", 0L),
                        savedAt = savedAt,
                        folderId = obj.optString("f"),
                        compressed = obj.optBoolean("c", false),
                        packed = obj.optBoolean("pk", false),
                        originSize = obj.optLong("o", 0L),
                        originName = obj.optString("n"),
                        lastSeen = obj.optLong("v", savedAt),
                        md5 = SyncPlan.normalizeMd5(obj.optString("m")),
                        relDir = obj.optString("d")
                    )
                }
            }
        }
    }

    companion object {
        private const val INDEX_FILE = "local_images.json"
        private const val DIR_NAME = "guide_images"

        /** [ensuredDirs] 里代表「没选 SAF 目录、写在应用私有目录」的那个前缀 */
        private const val PRIVATE_ROOT = "private"
        private const val MAX_INDEX_BYTES = 8L * 1024 * 1024

        /** 12 小时才更新一次「最近看过」，别让网格滚动变成写热点 */
        private const val TOUCH_INTERVAL_MS = 12L * 60 * 60 * 1000

        /** 指纹池缓存：目录扫描是 IO，同一分钟内不必反复扫 */
        private const val FP_TTL_MS = 60_000L

        /** 空间清理的保护期：3 天内看过的图不淘汰 */
        private const val PROTECT_MS = 3L * 24 * 60 * 60 * 1000

        /** 失效地址黑名单上限，防止极端情况下内存只涨不落 */
        private const val MAX_DEAD_URIS = 2000

        /** v2.0.3：重建指纹时最多扫这么多个目录 / 文件，防止目录树异常时把手机卡住 */
        private const val MAX_SCAN_DIRS = 400
        private const val MAX_SCAN_FILES = 6000

        private val IMAGE_EXTS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
        )

        @Volatile
        private var instance: ImageVault? = null

        fun of(context: Context): ImageVault = instance ?: synchronized(this) {
            instance ?: ImageVault(context).also { instance = it }
        }

        private fun key(platform: PanPlatform, image: PanImage): String =
            "${platform.name}:${image.id}"

        private fun mimeOf(ext: String): String = when (ext.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

        /**
         * 文件名 = 原名（清洗后，最多 40 字）+ id 短哈希 + 扩展名。
         *
         * 直接拿网盘 fid 当文件名在夸克上还行、迅雷是长串数字，SAF 目录里看着难受；
         * 只留原名又可能同名覆盖，所以两者拼起来，哈希保证唯一。
         */
        fun fileName(image: PanImage, extension: String, packed: Boolean = false): String {
            val base = image.name.substringBeforeLast('.', image.name)
            val safe = base.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
            }.joinToString("").take(40).ifBlank { "img" }
            val hash = Integer.toHexString(image.id.hashCode())
            val ext = extension.ifBlank { "jpg" }
            // 双后缀：`第_1_页_3fa8c1.jpg.yxz`。保留原扩展名，认领 / 排错时一眼看出是什么图
            return if (packed) "${safe}_$hash.$ext.${PackedImage.EXT}" else "${safe}_$hash.$ext"
        }

        /**
         * 比对的归一化名字：和 [fileName] 用同一套清洗规则，去掉扩展名和哈希。
         *
         * `第 1 页.jpg` → `第_1_页`；目录里的 `第_1_页_3fa8c1.jpg` 反推回来也是 `第_1_页`。
         */
        fun normalizeName(name: String): String {
            if (name.isBlank()) return ""
            // `.jpg.yxz` 要先剥掉打包后缀，否则归一化出来是 `第_1_页_jpg`，认领永远 miss
            val plain = if (PackedImage.isPackedName(name)) name.dropLast(PackedImage.EXT.length + 1) else name
            val base = plain.substringBeforeLast('.', plain)
            return base.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
            }.joinToString("").take(40).ifBlank { "img" }
        }

        /** 从 `第_1_页_3fa8c1.jpg` 反推 `第_1_页` */
        private fun originalNameOf(fileName: String): String {
            val plain = if (PackedImage.isPackedName(fileName)) {
                fileName.dropLast(PackedImage.EXT.length + 1)
            } else {
                fileName
            }
            val dot = plain.lastIndexOf('.')
            val stem = if (dot > 0) plain.substring(0, dot) else plain
            val under = stem.lastIndexOf('_')
            return if (under > 0) stem.substring(0, under) else stem
        }

        /**
         * 从 uri 里猜原始文件名。
         *
         * 私有目录的 `file://...` 直接就是文件名；SAF 的 `content://` 里 docId 的最后一段
         * 通常也是文件名（`primary:YunX/第_1_页_3fa8c1.jpg`，`Uri.decode` 之后）。
         * 在 v1.9.9 的旧索引上，这一步能把「没有 originName」的条目救回来，
         * 升级后第一次进分区就能参与认领，不用等重新下载一遍。
         */
        private fun guessNameFromUri(uri: String): String {
            val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
            val last = decoded.substringBefore('?').substringAfterLast('/')
            if (!looksLikeOurs(last)) return ""
            return originalNameOf(last)
        }

        /**
         * 只认本 App 写出来的文件名（`xxx_1a2b3c.jpg`）。
         *
         * 用户选的目录里可能还放着别的图片，乱认会把无关文件当成小抄，
         * 既占索引又可能在清理时被误删。
         */
        private fun looksLikeOurs(fileName: String): Boolean {
            // v2.0.0：`xxx_1a2b3c.jpg.yxz` 也认 —— 先剥打包后缀，再按原来的规则校验
            val plain = if (PackedImage.isPackedName(fileName)) {
                fileName.dropLast(PackedImage.EXT.length + 1)
            } else {
                fileName
            }
            val dot = plain.lastIndexOf('.')
            if (dot <= 0 || dot == plain.lastIndex) return false
            if (plain.substring(dot + 1).lowercase() !in IMAGE_EXTS) return false
            val stem = plain.substring(0, dot)
            val under = stem.lastIndexOf('_')
            if (under <= 0 || under == stem.lastIndex) return false
            val hash = stem.substring(under + 1)
            if (hash.length !in 1..8) return false
            return hash.all { it.isDigit() || it in 'a'..'f' }
        }

        fun extensionOf(name: String): String =
            name.substringAfterLast('.', "").lowercase().takeIf { it in IMAGE_EXTS }
                ?: "jpg"
    }
}
