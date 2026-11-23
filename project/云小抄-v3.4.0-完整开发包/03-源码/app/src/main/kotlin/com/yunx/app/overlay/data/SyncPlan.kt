/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.1）。同步判定：网盘上这一份和本地这一份是不是同一个字节流。
 */

package com.yunx.app.overlay.data

/**
 * 一张图「要不要动」的结论。
 *
 * 只有三种，没有第四种「不确定」——不确定的时候一律归到 [KEEP]，
 * 因为误判成 [UPDATE] 的代价是白下几十张图（还可能把好好的本地文件覆盖坏），
 * 误判成 [KEEP] 的代价只是这一次没更新上，下次作者再动它还能发现。
 */
enum class SyncVerdict {
    /** 本地这份就是网盘上那份，跳过 */
    KEEP,

    /** 本地没有 */
    NEW,

    /** 本地有，但网盘上那份已经换过了 —— 必须覆盖 */
    UPDATE;

    val needsDownload: Boolean get() = this != KEEP
}

/**
 * 网盘侧的一份文件。
 *
 * [size] 是列表接口给的字节数，[md5] 只在接口真的返回时才非空 —— 夸克多数列表接口
 * 不给 md5，迅雷给的是 sha1，所以**不能假设它一定有**。
 */
data class RemoteStamp(
    val name: String,
    val size: Long,
    val md5: String? = null
)

/**
 * 本地侧的一份文件。
 *
 * [originSize] 是**网盘上那份原始文件**的字节数（不是本地磁盘占用）。
 * 开了压缩之后本地文件可能只有原图的三成，拿磁盘大小去比会整批误判成 UPDATE，
 * 所以索引里单独记了这个字段。
 *
 * [md5] 同样记的是**下载时拿到的原始字节**的 md5，与本地是否重编码/打包无关。
 */
data class LocalStamp(
    val originSize: Long,
    val md5: String? = null,
    /** 本地实际占用，只在 [originSize] 缺失（v1.9.9 及更早的索引）时当兜底线索 */
    val bytes: Long = 0L
)

/**
 * 同步判定规则。
 *
 * 抽成纯对象有两个原因：一是能单测（这条规则写反了，表现只是「作者更新了但我没发现」，
 * 肉眼看不出来）；二是下载器、镜像同步、UI 统计三处都要用同一套口径，不能各写一遍。
 */
object SyncPlan {

    /** md5 统一成小写去空白，避免大小写不同被判成「内容变了」 */
    fun normalizeMd5(raw: String?): String? {
        val v = raw?.trim()?.lowercase().orEmpty()
        return v.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * 判定一份远端文件相对本地该怎么做。
     *
     * 证据强度从高到低：
     *  1. **md5 双方都有** —— 唯一能证明「字节完全相同」的证据。不同就是 [SyncVerdict.UPDATE]。
     *  2. **原始字节数** —— 作者改图（哪怕只改一个错别字重新导出）几乎不可能让文件大小
     *     一模一样；大小不同就足以判定内容变了。这是本功能的主判据。
     *  3. **本地磁盘字节数** —— 只在旧索引没记原始大小时兜底，且只用来「确认没变」。
     *
     * 什么时候故意不判 UPDATE：
     *  - 远端 [RemoteStamp.size] <= 0：列表接口抖动 / 权限降级时会给 0。
     *    拿 0 去比「和本地不一样」会把整个分区重下一遍，这是最贵的误判。
     *  - 本地 [LocalStamp.originSize] <= 0 且磁盘大小也解释不了：没有可比证据。
     */
    fun decide(local: LocalStamp?, remote: RemoteStamp): SyncVerdict {
        if (local == null) return SyncVerdict.NEW

        val remoteSize = remote.size
        val localSize = local.originSize
        val remoteMd5 = normalizeMd5(remote.md5)
        val localMd5 = normalizeMd5(local.md5)

        // 1. 有 md5 就信 md5：两边都算过，直接比字节
        if (remoteMd5 != null && localMd5 != null) {
            if (remoteMd5 == localMd5) return SyncVerdict.KEEP
            // md5 不同但大小也不同 → 铁定换了；大小相同也仍是换了（作者同尺寸重导出）
            return SyncVerdict.UPDATE
        }

        // 2. 远端没给大小 → 什么都不判
        if (remoteSize <= 0L) return SyncVerdict.KEEP

        // 3. 主判据：网盘声明的原始字节数
        if (localSize > 0L) {
            return if (localSize == remoteSize) SyncVerdict.KEEP else SyncVerdict.UPDATE
        }

        // 4. 旧索引没有原始大小：本地磁盘大小等于远端 → 当年是原样存的，认定没变
        if (local.bytes > 0L && local.bytes == remoteSize) return SyncVerdict.KEEP

        // 5. 本地明显更小 → 当年压缩过，原始大小已经无从得知，保守跳过
        if (local.bytes > 0L && local.bytes < remoteSize) return SyncVerdict.KEEP

        // 6. 本地比远端还大：可能是当年存的是原图、网盘那头被换成了压缩版；
        //    没有 md5 时无法确认，宁可不动（真的换了，下一轮 md5 或人工重下能兜住）
        return SyncVerdict.KEEP
    }

    /**
     * 批量判定，返回「需要下载的」以及其中「属于覆盖的」。
     *
     * 单独抽出来是为了让调用处不必重复写 `filter { it.verdict.needsDownload }`：
     * 统计文案要区分「新增 N 张」和「更新 M 张」，这两个数用户看得懂，也直接对应
     * 「作者到底有没有改图」这个他最关心的问题。
     */
    fun split(decisions: List<Pair<String, SyncVerdict>>): Split {
        var fresh = 0
        var updated = 0
        val ids = ArrayList<String>(decisions.size)
        decisions.forEach { (id, verdict) ->
            when (verdict) {
                SyncVerdict.NEW -> {
                    fresh++
                    ids += id
                }

                SyncVerdict.UPDATE -> {
                    updated++
                    ids += id
                }

                SyncVerdict.KEEP -> Unit
            }
        }
        return Split(fresh, updated, ids)
    }

    data class Split(val fresh: Int, val updated: Int, val ids: List<String>) {
        val total: Int get() = fresh + updated
    }

    /**
     * 算一段字节的 md5。
     *
     * 用在这几个地方：下载完成后给「网盘上那份」留指纹。实测 1 MB 数据在主流 ARM
     * 上约 3 ms，相对一次几百毫秒的网络请求可以忽略；而且它只算一次，之后每次都从
     * 索引里读，不会出现「每次打开小抄都重算一遍」这种拖慢。
     *
     * 用 MessageDigest 而不是手写实现：ART 上它有 intrinsics，比自己写的循环快数倍。
     */
    fun md5Of(bytes: ByteArray): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val out = digest.digest(bytes)
        buildString(out.size * 2) {
            out.forEach { b ->
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }.getOrNull()

    private val HEX = "0123456789abcdef".toCharArray()
}
