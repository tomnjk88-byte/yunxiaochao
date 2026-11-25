/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.0.2 引入「链接小抄」后，三种来源共用同一套
 * 分类 / 目录缓存，必须给每条缓存一个互不相撞的命名空间。
 */

package com.yunx.app.overlay.data

/**
 * 缓存作用域（纯函数，便于单测）。
 *
 * 为什么需要它：`MediaRepository` 的三层缓存（分类 / 目录内容 / 直链）原来只按
 * `PanPlatform` 分桶。个人网盘与分享链接是**两套完全不同的 fid 空间**：
 * 夸克分享里的 fid 与账号内 fid 长度、前缀都不同，但语义上没有任何约束保证不撞。
 * 一旦撞上，表现是「切到链接小抄，看到的是自己网盘那个同名目录的旧图」——
 * 界面上完全看不出是缓存错位，只会觉得「链接里的图不对」。
 *
 * 所以作用域必须是三元而不是二元：
 *
 * | 来源 | 作用域 |
 * |---|---|
 * | 本地文件夹 | `local` |
 * | 个人网盘 | 平台名（`QUARK` / `XUNLEI`） |
 * | 链接小抄 | `link`（不区分平台：同一时刻只开一个链接） |
 *
 * 链接源不按平台分桶是刻意的：用户换了链接（哪怕换成迅雷的）也应该整体作废旧缓存，
 * 而 `setLinkShare` 会连作用域一起清，不需要靠名字区分。
 */
object SourceScope {

    /** 链接源的固定作用域名，与平台名不可能重复（枚举名全大写，这里全小写） */
    const val LINK = "link"

    /** 本地文件夹的作用域名 */
    const val LOCAL = "local"

    /**
     * 给某个来源 + 平台算缓存前缀。
     *
     * 注意 [GuideSource.LOCAL] 的目录内容不走这几层缓存（`LocalGuideRepository` 自己管），
     * 这里仍返回 `local` 只是为了调用处不用再写一次 if，避免以后有人漏判。
     */
    fun of(source: GuideSource, platform: PanPlatform): String = when (source) {
        GuideSource.LOCAL -> LOCAL
        GuideSource.LINK -> LINK
        GuideSource.CLOUD -> platform.name
    }

    /**
     * 目录内容 / 图片直链的完整键。
     *
     * 缩略图与原图分两个命名空间（`t` / `f`），因为同一张图两种尺寸的 URL 不同，
     * 共用一个键会导致「先看了缩略图，再点开大图拿到的还是缩略图地址」。
     */
    fun contentKey(source: GuideSource, platform: PanPlatform, folderId: String): String =
        "${of(source, platform)}:$folderId"

    fun imageKey(
        source: GuideSource,
        platform: PanPlatform,
        imageId: String,
        thumbnail: Boolean
    ): String = "${of(source, platform)}:${if (thumbnail) "t" else "f"}:$imageId"
}
