/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v2.5.9 本地小抄「入口」的业务层。
 *
 * 这一层只做三件事：
 *   1. 把 Room 里的入口列表变成 UI 能直接 collect 的 StateFlow；
 *   2. 增删改排序（每次改完都重新编号，保证 orderIndex 连续、不会越排越乱）；
 *   3. 激活某个入口 = 把全局「本地小抄」绑定到它那一层，悬浮窗立刻只显示这个目录。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.net.Uri
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.ShortcutEntryDao
import com.yunx.app.data.db.ShortcutEntryEntity
import com.yunx.app.overlay.service.FloatingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ShortcutEntryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var dao: ShortcutEntryDao? = null

    private val _entries = MutableStateFlow<List<ShortcutEntryEntity>>(emptyList())
    val entries: StateFlow<List<ShortcutEntryEntity>> = _entries.asStateFlow()

    /** App 启动 / 进入小抄页时调一次即可，重复调用不会重复起协程 */
    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        val d = AppDatabase.get(app).shortcutEntryDao()
        dao = d
        job = scope.launch {
            ensureMigrated(app, d)
            ensureActive(app, d)
            d.observeAll().collect { list -> _entries.value = list }
        }
    }

    /**
     * 老版本迁移：v2.5.8 及以前，本地小抄只有「一个目录」这一份配置（`prefs.localTreeUri`）。
     *
     * 升级后如果入口表还是空的、而这个旧配置存在，就自动把它变成第一条入口，
     * 名字取目录名。**不移动、不复制任何用户文件**，只是把原来的绑定关系接过来 ——
     * 用户升级完打开小抄，看到的还是原来那个目录，不会「全部空了」。
     */
    private suspend fun ensureMigrated(context: Context, d: ShortcutEntryDao) {
        if (d.count() > 0) return
        val prefs = OverlayPrefs(context)
        val uri = prefs.localTreeUri?.takeIf { it.isNotBlank() } ?: return
        d.insert(
            ShortcutEntryEntity(
                name = folderNameOf(uri),
                treeUri = uri,
                rootDocId = prefs.localRootDocId,
                orderIndex = 0
            )
        )
    }

    /**
     * 入口都配好了、全局本地小抄却还没指向任何目录时，自动接上第一条可用入口。
     *
     * 什么时候会遇到：用户在「个人小抄」模式下加了几个入口，之后才切「本地小抄」；
     * 或者清过根目录。这时候如果不管，悬浮窗读到的是 null、页面一片空白，
     * 看起来就像「入口点了没用」。
     *
     * 只在**完全没绑定**时补一刀；已经绑了别的目录就不动，免得抢走用户当前的选择。
     */
    private suspend fun ensureActive(app: Context, d: ShortcutEntryDao) {
        val prefs = OverlayPrefs(app)
        if (!prefs.localTreeUri.isNullOrBlank()) return
        val list = runCatching { d.all() }.getOrNull().orEmpty()
        val first = list.firstOrNull { it.enabled } ?: return
        runCatching { activate(app, first) }
            .onFailure { /* 权限失效的入口不该阻断启动，等用户点开再提示 */ }
    }

    /** 新增一个入口，默认排在最后；名字留空就用目录名 */
    suspend fun add(context: Context, name: String, treeUri: String, rootDocId: String = "") {
        val d = dao ?: AppDatabase.get(context).shortcutEntryDao().also { dao = it }
        val order = d.maxOrder() + 1
        d.insert(
            ShortcutEntryEntity(
                name = name.ifBlank { folderNameOf(treeUri) },
                treeUri = treeUri,
                rootDocId = rootDocId,
                orderIndex = order
            )
        )
    }

    suspend fun rename(context: Context, id: Long, name: String) {
        val d = dao ?: return
        val target = d.all().firstOrNull { it.id == id } ?: return
        d.update(target.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    /** 重新绑定到另一个目录（保留名称与排序） */
    suspend fun rebind(context: Context, id: Long, treeUri: String, rootDocId: String = "") {
        val d = dao ?: return
        d.rebind(id, treeUri, rootDocId)
    }

    suspend fun setEnabled(context: Context, id: Long, enabled: Boolean) {
        dao?.setEnabled(id, enabled)
    }

    suspend fun setColor(context: Context, id: Long, color: Int) {
        val d = dao ?: return
        val target = d.all().firstOrNull { it.id == id } ?: return
        d.update(target.copy(color = color, updatedAt = System.currentTimeMillis()))
    }

    /** 删除并在同一事务语义下把剩下的一起重排（避免 orderIndex 留空洞） */
    suspend fun remove(context: Context, id: Long) {
        val d = dao ?: return
        val list = d.all()
        list.firstOrNull { it.id == id }?.let { d.delete(it) }
        renumber(d, list.filterNot { it.id == id })
    }

    /** 上移 / 下移一位；到边界就什么都不做 */
    suspend fun move(context: Context, id: Long, delta: Int) {
        val d = dao ?: return
        val list = d.all().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = index + delta
        if (target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        renumber(d, list)
    }

    private suspend fun renumber(d: ShortcutEntryDao, list: List<ShortcutEntryEntity>) {
        list.forEachIndexed { index, entry ->
            if (entry.orderIndex != index) d.updateOrder(entry.id, index)
        }
    }

    /**
     * 激活入口：把全局「本地小抄」切到它绑定的那一层。
     *
     * 三处必须一起改，缺一个都会「点了没反应」：
     *   ① 偏好（进程重启后还能恢复）；
     *   ② 共享仓库（主页/悬浮窗读的是它，含子目录起点）；
     *   ③ 正在跑的悬浮窗（发一个 Intent 让它强制刷新，否则窗口里还是上一个入口的图）。
     */
    fun activate(context: Context, entry: ShortcutEntryEntity) {
        val prefs = OverlayPrefs(context)
        prefs.localTreeUri = entry.treeUri
        prefs.localRootDocId = entry.rootDocId
        prefs.guideSource = GuideSource.LOCAL
        GuideRepo.repository(context).setLocalBinding(entry.treeUri, entry.rootDocId)
        FloatingService.setLocalTreeUri(context, entry.treeUri, entry.rootDocId)
        FloatingService.setGuideSource(context, GuideSource.LOCAL)
    }

    /** 当前激活的是哪个入口（按 tree + 子目录匹配）；没有匹配返回 null */
    fun activeEntry(context: Context): ShortcutEntryEntity? {
        val prefs = OverlayPrefs(context)
        val uri = prefs.localTreeUri ?: return null
        val doc = prefs.localRootDocId
        return _entries.value.firstOrNull { it.treeUri == uri && it.rootDocId == doc }
            ?: _entries.value.firstOrNull { it.treeUri == uri }
    }

    /**
     * 从 SAF tree uri 猜一个能看的名字。
     *
     * `primary:小抄/龙宫` → 龙宫；退不出来就退回「本地小抄」，不显示 content://。
     */
    fun folderNameOf(treeUri: String): String {
        val decoded = runCatching { Uri.decode(treeUri.substringAfter("/tree/", treeUri)) }
            .getOrDefault(treeUri)
        val path = decoded.substringAfter(':', decoded)
        return path.substringAfterLast('/').ifBlank { "本地小抄" }
    }
}
