/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v1.7.0 起攻略图片可以来自「网盘」或「本地文件夹」两个来源。
 */

package com.yunx.app.overlay.data

/**
 * 攻略图片来源。
 *
 * - [CLOUD]：原有链路，登录夸克 / 迅雷后读「我的小抄」目录（个人网盘）。
 * - [LOCAL]：用户用系统文件选择器（SAF）授权一个本地文件夹，直接读里面的子文件夹
 *   与图片。走的是同一套 `PanFolder / PanImage / FolderContent / ImageSource` 模型，
 *   所以分类 chips、面包屑、两列网格、大图预览全部复用，不需要第二套 UI。
 * - [LINK]（v2.0.2）：用户粘贴一条**别人的分享链接**，直接读那份分享里的目录与图片。
 *   不转存、不落本地、不写索引 —— 看图就是「打开别人的网盘那页」，关掉小抄什么都不留。
 *   和个人网盘完全隔开（参见 [SourceScope]：两套 fid 空间共用一层缓存必须分桶）。
 *
 * 三种来源的定位：
 *
 * | 来源 | 要不要登录 | 要不要占空间 | 适合 |
 * |---|---|---|---|
 * | 个人小抄 | 要 | 可选下载到本地 | 自己收藏、要离线看 |
 * | 本地小抄 | 不要 | 已经在你手机里 | 网盘限速时预先存一份 |
 * | 链接小抄 | 不要（游客可读） | 不占 | 临时看别人的攻略 |
 */
enum class GuideSource(val label: String) {
    CLOUD("个人小抄"),
    LOCAL("本地小抄"),
    LINK("链接小抄");

    companion object {
        /**
         * 从持久化字符串还原。
         *
         * 默认回 [CLOUD]：旧版本只存过 `CLOUD` / `LOCAL`，读到 `LINK` 之外的值（或者
         * 以后降级安装回旧版）都应该落到「个人小抄」，而不是抛异常或给出空来源。
         */
        fun fromKey(key: String?): GuideSource =
            entries.firstOrNull { it.name == key } ?: CLOUD
    }
}
