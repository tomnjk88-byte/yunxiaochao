/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者）
 * Copyright (C) 2026 <你的名字>（悬浮窗 / 个人网盘浏览部分）
 *
 * 本文件是 AGPL-3.0 衍生作品：可以再分发/修改，但必须以 AGPL-3.0 公开源代码，
 * 并保留本声明。完整协议见项目根目录 LICENSE。
 */

package com.yunx.app.overlay.data

/**
 * 支持的网盘平台。
 *
 * 只做夸克 / 迅雷：这两家在云析里都同时具备「登录」「个人网盘列表」「直链」三条链路，
 * 其它平台要么没有个人盘列表、要么登录依赖扫码，改造成本远高于收益。
 */
enum class PanPlatform(val label: String) {
    QUARK("夸克"),
    XUNLEI("迅雷");

    companion object {
        fun fromKey(key: String?): PanPlatform =
            PanPlatform.entries.firstOrNull { it.name == key } ?: QUARK
    }
}

/**
 * 登录凭证。
 *
 * 夸克是 Cookie 会话（`__pus` + `__puus`），迅雷是 OAuth Bearer（access/refresh + 设备号 + 验证码 token）。
 * 两者结构差异太大，不做「统一成 token」的抽象 —— 那只会让调用处到处 if。
 */
sealed interface PanCredential {
    val nickname: String

    data class Quark(
        val cookie: String,
        override val nickname: String
    ) : PanCredential

    data class Xunlei(
        val accessToken: String,
        val refreshToken: String,
        val deviceId: String,
        /** captcha/init 换来的 token，官方只有 300s；过期由 XunleiApi 内部自动重签 */
        val captchaToken: String,
        /** 空串 = App 通道（账密/短信登录）；[com.yunx.app.data.network.XunleiWebCredential.AUTH_TYPE] = 网页通道 */
        val authType: String,
        override val nickname: String
    ) : PanCredential
}

/** 当前生效的平台 + 各平台已保存的凭证（可能同时登录两家） */
data class PanSession(
    val platform: PanPlatform,
    val credentials: Map<PanPlatform, PanCredential>
) {
    val active: PanCredential? get() = credentials[platform]
}

/**
 * 分类 = 根目录（默认「我的小抄」）下的子文件夹。
 *
 * [parentId] 是它在网盘里的父目录 id：夸克为根目录 fid（通常 "0"），迅雷为根目录的
 * 空串 parent_id。留着它，后续做「分类内再进子目录」时不用改模型。
 */
data class PanFolder(
    val id: String,
    val name: String,
    val parentId: String
)

/**
 * 一张攻略图。
 *
 * [thumbUrl] 是列表接口顺带返回的缩略图（夸克 / 迅雷都给），[thumbHeaders] 是取图必须带的
 * 请求头 —— 夸克的缩略图域名 `drive-pc.quark.cn` 不带账号 Cookie 直接 403，迅雷的 CDN
 * 只认客户端 UA。列表接口拿到凭证的那一次顺手拼好，UI 层就不用回头要账号。
 */
data class PanImage(
    val id: String,
    val name: String,
    val size: Long,
    val thumbUrl: String? = null,
    val thumbHeaders: Map<String, String> = emptyMap(),
    /** v2.0.1：网盘声明的内容指纹；接口不给就是 null，判定时自动退化成「大小比对」 */
    val md5: String? = null
) {
    val sizeLabel: String
        get() = when {
            size <= 0L -> ""
            size < 1024L -> "${size}B"
            size < 1024L * 1024L -> "${size / 1024}KB"
            else -> String.format(java.util.Locale.US, "%.1fMB", size / 1024f / 1024f)
        }
}

/**
 * 一个目录的内容：子文件夹 + 图片。
 *
 * 之前只取图片、把子文件夹过滤掉，所以「分类里再套一层」的网盘结构会显示成「没有图片」。
 * 现在两样都返回，UI 先铺文件夹再铺图片，点文件夹继续下钻。
 */
data class FolderContent(
    val folders: List<PanFolder> = emptyList(),
    val images: List<PanImage> = emptyList()
) {
    val isEmpty: Boolean get() = folders.isEmpty() && images.isEmpty()
}

/** 可直接交给 Coil 的图片地址 + 逐请求头 */
data class ImageSource(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

object PanFiles {

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
    )

    fun isImage(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return false
        return name.substring(dot + 1).lowercase() in IMAGE_EXTENSIONS
    }

    /**
     * 自然排序：`第2页.jpg` 排在 `第10页.jpg` 前面。
     *
     * 攻略图的文件名几乎都是「1、2、10」这种编号，字典序会把 10 排到 2 前面，
     * 网格顺序一乱，用户按顺序翻页就对不上正文了。
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                // 去掉前导 0 后先比长度（位数多的一定更大），再比字典序，避免 Long 溢出
                val na = a.substring(startA, i).trimStart('0')
                val nb = b.substring(startB, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    val nameComparator: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }
}

/** 业务异常：消息直接展示给用户，不再二次翻译 */
open class PanException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotLoggedInException(platform: PanPlatform) :
    PanException("请先登录${platform.label}网盘")
