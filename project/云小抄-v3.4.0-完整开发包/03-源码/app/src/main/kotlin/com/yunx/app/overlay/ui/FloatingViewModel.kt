/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。状态从「分类 / 图片」升级为「分类 / 目录栈 / 图片」，
 * 支持任意层级下钻；启动时先用磁盘快照把内容铺出来，再刷新网络。
 */

package com.yunx.app.overlay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunx.app.overlay.data.FolderContent
import com.yunx.app.overlay.data.GuideSource
import com.yunx.app.overlay.data.ImageSource
import com.yunx.app.overlay.data.MediaRepository
import com.yunx.app.overlay.data.PanFolder
import com.yunx.app.overlay.data.PanImage
import com.yunx.app.overlay.data.PanPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverlayUiState(
    val platform: PanPlatform = PanPlatform.QUARK,
    val nickname: String = "",
    /** 数据源是否就绪（网盘=已登录；本地=已选目录），不再单指「网盘登录」 */
    val loggedIn: Boolean = false,
    /** 当前攻略来源，UI 用它区分「去登录」与「去选择文件夹」 */
    val source: GuideSource = GuideSource.CLOUD,
    val sourceLabel: String = "",
    val loadingCategories: Boolean = false,
    val loadingContent: Boolean = false,
    val categories: List<PanFolder> = emptyList(),
    /** 从分类到当前目录的路径，最后一个是当前目录 */
    val path: List<PanFolder> = emptyList(),
    val folders: List<PanFolder> = emptyList(),
    val images: List<PanImage> = emptyList(),
    /** 非 null = 状态 B（大图预览），值即当前页索引 */
    val previewIndex: Int? = null,
    val error: String? = null,
    /**
     * v2.0.7：入场动画的重播信号。
     *
     * 每次自增都会让 `FloatingContent` 里的 navKey 变一次 → 碎片重新盖一遍。
     * 存在的理由：用户说「装了两遍啥也没动」，得给一个不依赖切目录的、
     * 随手能点的验证入口 —— 点一下立刻看到整屏碎开，就不用猜是不是包错了。
     */
    val revealTick: Int = 0
) {
    val currentFolder: PanFolder? get() = path.lastOrNull()
    val selectedFolderId: String? get() = currentFolder?.id
    val selectedFolderName: String get() = currentFolder?.name.orEmpty()
    val previewVisible: Boolean get() = previewIndex != null
    val canGoUp: Boolean get() = path.size > 1
    val isEmpty: Boolean get() = folders.isEmpty() && images.isEmpty()
}

/**
 * 悬浮窗的唯一状态源。
 *
 * 为什么不用 Activity 那套 `collectAsStateWithLifecycle`：悬浮窗没有 Activity 生命周期，
 * Service 自己建 LifecycleOwner（见 FloatingService），Compose 里直接 `collectAsState()` 即可。
 */
class FloatingViewModel(private val repo: MediaRepository) : ViewModel() {

    private val _state = MutableStateFlow(OverlayUiState())
    val state: StateFlow<OverlayUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * v2.0.2：上一次看到的分享链接。
     *
     * 为什么要单独记：换链接时 `source` 仍然是 LINK，只比较来源会漏刷新 ——
     * 表现是「在主页换了条链接，小抄里还是上一份的图」。
     */
    private var lastLink: String? = repo.currentLink()

    init {
        viewModelScope.launch {
            // 登录态变化、切来源、换本地目录，三者都能让「是否就绪 / 显示什么」发生变化，
            // 所以合并成一个流统一处理；sourceTick 只是个自增信号，值本身不参与判断。
            combine(repo.session, repo.sourceTick) { session, _ -> session }
                .collect { session ->
                    val source = repo.currentSource()
                    val ready = repo.isReady(session)
                    val snapshot = _state.value
                    val sourceChanged = snapshot.source != source
                    val platformChanged = snapshot.platform != session.platform
                    val linkNow = repo.currentLink()
                    val linkChanged = linkNow != lastLink
                    lastLink = linkNow
                    _state.update {
                        it.copy(
                            platform = session.platform,
                            nickname = session.active?.nickname.orEmpty(),
                            loggedIn = ready,
                            source = source,
                            sourceLabel = repo.sourceLabel()
                        )
                    }
                    when {
                        !ready -> {
                            job?.cancel()
                            _state.update {
                                it.copy(
                                    categories = emptyList(),
                                    path = emptyList(),
                                    folders = emptyList(),
                                    images = emptyList(),
                                    previewIndex = null,
                                    loadingCategories = false,
                                    loadingContent = false,
                                    error = null
                                )
                            }
                        }

                        // 切平台 / 切来源 / 换分享链接：先清空上一批目录，
                        // 避免「标题换了、网格还是旧图」
                        sourceChanged || platformChanged || linkChanged -> {
                            _state.update {
                                it.copy(
                                    categories = emptyList(),
                                    path = emptyList(),
                                    folders = emptyList(),
                                    images = emptyList(),
                                    previewIndex = null,
                                    error = null
                                )
                            }
                            // 换链接必须走网络：新链接不可能有缓存
                            refresh(force = linkChanged)
                        }

                        // 首次进入 / 重新就绪：先吃缓存，再决定要不要打网络
                        _state.value.categories.isEmpty() -> refresh(force = false)
                    }
                }
        }
    }

    /**
     * 刷新分类并重新载入当前分类。
     *
     * @param force false = 允许用内存/磁盘快照（悬浮窗刚挂上去时先秒出内容）；
     *              true  = 强制走网络（用户点刷新 / 重试）。
     */
    fun refresh(force: Boolean = true) {
        job?.cancel()
        val snapshot = _state.value
        if (!snapshot.loggedIn) return

        job = viewModelScope.launch {
            _state.update { it.copy(loadingCategories = true, error = null) }
            try {
                val folders = repo.categories(force)
                val keepId = snapshot.currentFolder?.id ?: repo.lastCategoryId()
                val target = folders.firstOrNull { it.id == keepId } ?: folders.firstOrNull()

                val sameCategory = target != null && target.id == snapshot.currentFolder?.id
                _state.update {
                    it.copy(
                        loadingCategories = false,
                        categories = folders,
                        path = if (target == null) emptyList() else listOf(target),
                        folders = if (sameCategory) it.folders else emptyList(),
                        images = if (sameCategory) it.images else emptyList(),
                        previewIndex = null,
                        error = if (folders.isEmpty()) {
                            "「${repo.rootFolderName()}」下还没有子文件夹"
                        } else {
                            null
                        }
                    )
                }
                if (target != null) {
                    repo.rememberCategory(target.id)
                    loadContent(target, force)
                    warmOtherCategories(folders, target.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingCategories = false,
                        loadingContent = false,
                        error = e.message ?: "加载失败"
                    )
                }
            }
        }
    }

    /** 点左侧分类：回到该分类的第一层 */
    fun selectCategory(folder: PanFolder) {
        val snapshot = _state.value
        if (snapshot.path.size == 1 &&
            snapshot.currentFolder?.id == folder.id &&
            !snapshot.isEmpty
        ) {
            return
        }
        job?.cancel()
        _state.update {
            it.copy(
                path = listOf(folder),
                folders = emptyList(),
                images = emptyList(),
                previewIndex = null,
                error = null
            )
        }
        repo.rememberCategory(folder.id)
        job = viewModelScope.launch { loadContent(folder, force = false) }
    }

    /**
     * v2.0.7：手动重播一次入场动画。
     *
     * 只拨一个自增信号，不碰数据 —— 动画判定在 FloatingContent 根上算，
     * navKey 里带上这个数就会让整屏重新盖一遍再掀开。
     */
    fun playReveal() {
        _state.update { it.copy(revealTick = it.revealTick + 1) }
    }

    /** 点网格里的文件夹：继续下钻一层 */
    fun openFolder(folder: PanFolder) {
        job?.cancel()
        val nextPath = _state.value.path + folder
        _state.update {
            it.copy(
                path = nextPath,
                folders = emptyList(),
                images = emptyList(),
                previewIndex = null,
                error = null
            )
        }
        job = viewModelScope.launch { loadContent(folder, force = false) }
    }

    /** 返回上一级目录 */
    fun goUp() {
        val snapshot = _state.value
        if (snapshot.path.size <= 1) return
        job?.cancel()
        val parent = snapshot.path[snapshot.path.size - 2]
        _state.update {
            it.copy(
                path = snapshot.path.dropLast(1),
                folders = emptyList(),
                images = emptyList(),
                previewIndex = null,
                error = null
            )
        }
        job = viewModelScope.launch { loadContent(parent, force = false) }
    }

    private suspend fun loadContent(folder: PanFolder, force: Boolean) {
        // v1.9.6：内存里已有这一层就先铺出来（同一帧出图），再决定要不要联网刷新。
        // 以前是先清空网格 → 转圈 → 等 IO 返回，视觉上就是「点一下卡两秒」。
        if (!force) {
            repo.peekContent(folder.id)?.let { cached ->
                _state.update {
                    it.copy(
                        folders = cached.folders,
                        images = cached.images,
                        loadingContent = false,
                        error = null
                    )
                }
                warmPrefetch(cached.images)
            }
        }
        _state.update { it.copy(loadingContent = true, error = null) }
        try {
            val content = repo.browse(folder.id, force)
            _state.update {
                it.copy(
                    loadingContent = false,
                    folders = content.folders,
                    images = content.images,
                    error = if (content.isEmpty) "「${folder.name}」里没有内容" else null
                )
            }
            warmPrefetch(content.images)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loadingContent = false,
                    folders = emptyList(),
                    images = emptyList(),
                    error = e.message ?: "加载图片失败"
                )
            }
        }
    }

    /**
     * v1.9.6 高性能模式：列表一拿到就把前几张的**原图直链**先换好。
     *
     * 网格用的是缩略图，点开大图才需要直链 —— 而夸克 / 迅雷取一次直链要走一次接口。
     * 提前换好开头几张，点开第一张时不再空转一个转圈。
     */
    /**
     * v1.9.7：顺手把**其它分类的文件列表**也取回来（只取元数据，不下图片）。
     *
     * 点分类之所以要等一下，主要是那一次「列目录」请求；提前取好，切过去就是本地列表，
     * 网格立刻铺满，图片再由预取器补。延迟 1.2 秒再开始，避免和当前分类的缩略图抢带宽。
     */
    private fun warmOtherCategories(folders: List<PanFolder>, currentId: String) {
        if (!repo.highPerfEnabled || folders.size <= 1) return
        viewModelScope.launch {
            delay(1_200)
            folders.asSequence()
                .filter { it.id != currentId }
                .take(WARM_CATEGORY_LIMIT)
                .forEach { folder -> runCatching { repo.browse(folder.id, force = false) } }
        }
    }

    private fun warmPrefetch(images: List<PanImage>) {
        if (!repo.highPerfEnabled || images.isEmpty()) return
        images.take(PREWARM_ORIGINALS).forEach { image ->
            viewModelScope.launch { repo.prefetch(image) }
        }
    }

    fun openPreview(index: Int) {
        val total = _state.value.images.size
        if (index !in 0 until total) return
        _state.update { it.copy(previewIndex = index) }
        prefetchAround(index)
    }

    fun closePreview() {
        _state.update { it.copy(previewIndex = null) }
    }

    /** 供 UI 在翻页时调用：预取某一张原图 */
    fun prefetch(image: PanImage) {
        viewModelScope.launch { repo.prefetch(image) }
    }

    /** 重试当前目录（不动分类，避免整棵树重刷） */
    fun retry() {
        val folder = _state.value.currentFolder ?: run {
            refresh(force = true)
            return
        }
        job?.cancel()
        job = viewModelScope.launch { loadContent(folder, force = true) }
    }

    /** 预取前后各一张原图，滑过去时不用等取链 */
    private fun prefetchAround(index: Int) {
        val images = _state.value.images
        if (images.isEmpty()) return
        listOf(index - 1, index + 1, index + 2)
            .filter { it in images.indices }
            .distinct()
            .forEach { i ->
                viewModelScope.launch { repo.prefetch(images[i]) }
            }
    }

    /**
     * 逐张解析图片地址（网格用缩略图，预览用原图）。
     * 单张失败返回 null，让那一格显示占位，不把整个网格打挂。
     */
    suspend fun resolveSource(image: PanImage, thumbnail: Boolean): ImageSource? = try {
        repo.imageSource(image, thumbnail)
    } catch (e: Exception) {
        null
    }

    fun switchPlatform(platform: PanPlatform) {
        repo.setPlatform(platform)
    }

    fun logout() {
        repo.logout(_state.value.platform)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        // v1.9.8：仓库是进程级单例了，窗口关掉≠这次浏览结束。
        // 只回收内存里的目录/直链，磁盘快照留着，下次开窗照样秒出内容。
        repo.trimRuntimeCache()
    }

    private companion object {
        /** 预热几张原图直链：够点开第一张不转圈，又不会一次打太多接口 */
        const val PREWARM_ORIGINALS = 3

        /** 最多顺手预热几个「其它分类」的文件列表 */
        const val WARM_CATEGORY_LIMIT = 4
    }
}
