/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.5.9 本地小抄改成「自定义入口」结构：
 * 不再把所选目录下的所有子文件夹平铺成一排分类，而是由用户自己定义几个入口按钮，
 * 每个按钮绑定一个独立目录，点进去只看到那个目录里的内容。
 */

package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 一个「小抄入口」= 首页上的一颗按钮。
 *
 * 两个 uri 字段的关系要说清楚，这是整个模块最容易搞错的地方：
 *
 * - [treeUri] 是 SAF 的 tree uri（`content://.../tree/primary%3A小抄%2F龙宫`），
 *   它同时代表「用户授予的权限范围」和「这颗按钮的绑定位置」。
 * - [rootDocId] 只在**绑定父目录下的子目录**时才非空。例如用户只授权了 `/小抄`，
 *   但想让这颗按钮指向 `/小抄/龙宫`：treeUri 仍是 `/小抄`（权限在这层），
 *   rootDocId 填 `/小抄/龙宫` 的 documentId，浏览时从这一层开始。
 *
 * 之所以不把子目录直接建成 tree uri：SAF 的持久授权是跟着**用户选的那棵树**走的，
 * 自己拼一个子目录 tree uri 在多数文件管理器上拿不到权限，重进应用就失效了。
 */
@Entity(tableName = "guide_shortcut")
data class ShortcutEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 入口名称，默认取目录名，用户可改（例如「龙宫小抄」） */
    val name: String,
    /** SAF tree uri：权限范围 */
    val treeUri: String,
    /** 绑定的子目录 documentId；空串 = 直接用 tree 根 */
    @ColumnInfo(defaultValue = "''")
    val rootDocId: String = "",
    /** 展示排序，小的在前 */
    @ColumnInfo(defaultValue = "0")
    val orderIndex: Int = 0,
    /** 图标 key（见 ShortcutIcons）；空 = 默认文件夹 */
    @ColumnInfo(defaultValue = "''")
    val icon: String = "",
    /** 主题色 ARGB；0 = 跟随全局主题 */
    @ColumnInfo(defaultValue = "0")
    val color: Int = 0,
    /** 关掉的入口在首页置灰、不可进入（用于临时隐藏一堆不看的） */
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 真正用于浏览的那一层：有子目录绑定就用它，否则用树根 */
    val effectiveDocId: String get() = rootDocId
}
