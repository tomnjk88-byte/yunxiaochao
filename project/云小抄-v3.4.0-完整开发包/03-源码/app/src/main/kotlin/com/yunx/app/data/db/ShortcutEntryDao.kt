/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.5.9 小抄入口的 Room DAO。
 */

package com.yunx.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutEntryDao {

    @Query("SELECT * FROM guide_shortcut ORDER BY orderIndex ASC, id ASC")
    fun observeAll(): Flow<List<ShortcutEntryEntity>>

    @Query("SELECT * FROM guide_shortcut ORDER BY orderIndex ASC, id ASC")
    suspend fun all(): List<ShortcutEntryEntity>

    @Query("SELECT COUNT(*) FROM guide_shortcut")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM guide_shortcut")
    suspend fun maxOrder(): Int

    @Insert
    suspend fun insert(entry: ShortcutEntryEntity): Long

    @Update
    suspend fun update(entry: ShortcutEntryEntity)

    @Delete
    suspend fun delete(entry: ShortcutEntryEntity)

    @Query("UPDATE guide_shortcut SET orderIndex = :order, updatedAt = :now WHERE id = :id")
    suspend fun updateOrder(id: Long, order: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE guide_shortcut SET treeUri = :treeUri, rootDocId = :docId, updatedAt = :now WHERE id = :id")
    suspend fun rebind(id: Long, treeUri: String, docId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE guide_shortcut SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())
}
