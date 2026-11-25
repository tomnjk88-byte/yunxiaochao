/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。连招模块对 UI 的唯一门面：Compose 面板、悬浮球、Service 都只跟它打交道，
 * 不直接碰无障碍服务或 WindowManager。WindowManager 相关能力由 [MacroHost] 注入。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import com.yunx.app.overlay.data.OverlayPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 由 FloatingService 实现：只有它能挂全屏覆盖层、加悬浮按钮、弹 Toast */
interface MacroHost {
    fun showCapture(
        mode: CaptureMode,
        onResult: (List<MacroStep>) -> Unit,
        onCancel: () -> Unit
    )

    /**
     * v2.5.7 按键录制台：一个按键一扇独立小窗，屏幕其它地方照常给游戏。
     * 和 [showCapture] 的 RECORD（全屏膜）并存，用户自己选哪种录法。
     */
    fun showKeypadRecorder(
        seedKeys: List<MacroKey>,
        onResult: (List<MacroKey>, List<MacroStep>) -> Unit,
        onCancel: () -> Unit
    )

    /** 单独收起按键录制台（它和透明膜是两套窗口，要能分别收） */
    fun dismissKeypad()

    /** 强制收掉录制透明膜（用户回到主页 / 点重置时调用） */
    fun dismissCapture()

    /**
     * v1.9.1 录制开始：把连招悬浮窗临时缩成球，给全屏透明膜让出位置。
     * [onCaptureEnd] 必须成对调用，否则窗口会一直停在球态。
     */
    fun onCaptureStart()

    /**
     * v1.9.1 录制完成 / 取消：把连招窗恢复成录制前的形态。
     * v1.9.3：[restore] = false 表示「录完故意不弹回面板」——用户要的是录完
     * 只在屏幕上留一颗连招自己的小按钮，而不是又冒出录制面板。
     */
    fun onCaptureEnd(restore: Boolean = true)

    /** v1.9.1「点击显示」：执行到某一步时，在屏幕上画出这个点击点 */
    fun showTap(x: Float, y: Float)

    fun syncBalls()
    fun toast(message: String)
}

object MacroController {

    private lateinit var appContext: Context
    private lateinit var store: MacroStore
    private lateinit var runner: MacroRunner

    /** root 的 su 进程调用必须离开主线程；独立于 Runner，Service 关掉后仍存活 */
    private val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var host: MacroHost? = null

    private val _runningId = MutableStateFlow<String?>(null)
    val runningId: StateFlow<String?> = _runningId.asStateFlow()

    /**
     * v1.9.16：正在为了让你操作而暂停（你的手指还按在屏幕上）。
     * 悬浮球据此把描边换成琥珀色，面板上也会显示「正在等你操作」。
     */
    private val _pausedByUser = MutableStateFlow(false)
    val pausedByUser: StateFlow<Boolean> = _pausedByUser.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _rootState = MutableStateFlow(RootShell.State.UNKNOWN)
    val rootState: StateFlow<RootShell.State> = _rootState.asStateFlow()

    /** v3.2.0：Shizuku 授权状态（连招的独立指针后端） */
    val shizukuState: StateFlow<ShizukuShell.State> get() = ShizukuShell.state

    /** 授权弹窗期间显示「正在申请…」，避免用户连点重复拉起 su */
    private val _rootBusy = MutableStateFlow(false)
    val rootBusy: StateFlow<Boolean> = _rootBusy.asStateFlow()

    val engine: StateFlow<EngineMode> get() = MacroEngine.mode

    private val _pending = MutableStateFlow<PendingMacro?>(null)
    val pending: StateFlow<PendingMacro?> = _pending.asStateFlow()

    /**
     * v1.9.1：录制完成即自动保存，这里给出刚保存的那一条，供面板显示
     * 「已自动保存」卡片（改名 / 换图标 / 换图片 / 删除），不用再手动点保存。
     */
    private val _justSaved = MutableStateFlow<MacroConfig?>(null)
    val justSaved: StateFlow<MacroConfig?> = _justSaved.asStateFlow()

    private val _captureMode = MutableStateFlow<CaptureMode?>(null)
    val captureMode: StateFlow<CaptureMode?> = _captureMode.asStateFlow()

    val macros: StateFlow<List<MacroConfig>> get() = store.macros

    fun init(context: Context) {
        if (::store.isInitialized) return
        synchronized(this) {
            if (::store.isInitialized) return
            appContext = context.applicationContext
            store = MacroStore.get(appContext)
            runner = MacroRunner(
                context = appContext,
                onRunningChange = { id ->
                    _runningId.value = id
                    if (id == null) _pausedByUser.value = false
                },
                onTap = { x, y -> host?.showTap(x, y) },
                onYieldChange = { yielding -> _pausedByUser.value = yielding }
            )
            MacroEngine.init(appContext)
            // 只注册监听，不会弹窗；用户点「连接 Shizuku」才去申请
            ShizukuShell.init(appContext)
            refreshEnabled()
            // 只有以前授权成功过才静默重连 su，否则不碰 root，避免莫名弹授权框
            if (MacroEngine.everRootGranted(appContext)) {
                rootScope.launch {
                    val s = withContext(Dispatchers.IO) { RootShell.restore() }
                    _rootState.value = s
                }
            }
        }
    }

    fun refreshEnabled() {
        if (::appContext.isInitialized) {
            _enabled.value = MacroAccessibilityService.isEnabled(appContext)
        }
    }

    // ------------------------------------------------------------------
    // 执行方式：无障碍 / Root
    // ------------------------------------------------------------------

    /** 用户点「获取 Root 权限」：触发 su 授权弹窗，结果写回状态 */
    fun requestRoot() {
        if (!::appContext.isInitialized || _rootBusy.value) return
        _rootBusy.value = true
        rootScope.launch {
            val s = withContext(Dispatchers.IO) { RootShell.request() }
            if (s == RootShell.State.GRANTED) MacroEngine.markRootGranted(appContext)
            _rootState.value = s
            _rootBusy.value = false
            host?.toast(
                when (s) {
                    RootShell.State.GRANTED -> "已取得 Root 权限，可把执行方式切到「Root」"
                    RootShell.State.NO_SU -> "这台设备没有 su（未 root 或 su 被隐藏）"
                    RootShell.State.DENIED -> "Root 授权被拒绝，或授权弹窗超时，可再试一次"
                    RootShell.State.UNKNOWN -> "未知状态"
                }
            )
        }
    }

    fun setEngine(mode: EngineMode) {
        if (::appContext.isInitialized) MacroEngine.select(appContext, mode)
    }

    /**
     * v3.2.0：拉起 Shizuku 授权。面板是悬浮窗（Service 上下文），
     * Shizuku 的授权页由它自己的进程弹，不依赖我们的 Activity，所以这里能直接调。
     */
    fun requestShizuku() {
        if (::appContext.isInitialized) {
            val ok = ShizukuShell.requestPermission()
            if (!ok) {
                host?.toast("没找到 Shizuku：请先安装并启动 Shizuku，再回来连接")
            }
        }
    }

    fun refreshShizuku() {
        if (::appContext.isInitialized) ShizukuShell.refresh()
    }

    /** 当前实际能用的后端；每次执行前实时判定，不缓存 */
    private fun resolveBackend(): Backend {
        refreshEnabled()
        return MacroEngine.resolve(
            rootGranted = RootShell.granted,
            shizukuGranted = ShizukuShell.granted,
            a11yConnected = MacroAccessibilityService.connected
        )
    }

    fun openAccessibilitySettings() {
        if (::appContext.isInitialized) MacroAccessibilityService.openSettings(appContext)
    }

    // ------------------------------------------------------------------
    // 取点 / 录制
    // ------------------------------------------------------------------

    fun startAim() = capture(CaptureMode.AIM)

    fun startRecord() = capture(CaptureMode.RECORD)

    /**
     * v2.5.7：游戏助手式「按键录制」。
     *
     * @param macro 非空 = 重录这一条（复用它的按键位置，录完覆盖它的步骤）；
     *              为空 = 新建一条连招。
     */
    fun startKeypadRecord(context: Context, macro: MacroConfig? = null) {
        init(context.applicationContext)
        val current = host ?: return
        _justSaved.value = null
        _captureMode.value = CaptureMode.RECORD
        // 录制台要摆按键，连招面板先缩成球让出屏幕
        current.onCaptureStart()
        current.showKeypadRecorder(
            seedKeys = macro?.keys ?: emptyList(),
            onResult = { keys, steps ->
                _captureMode.value = null
                current.onCaptureEnd(restore = false)
                val saved = if (macro != null) {
                    val next = macro.copy(steps = steps, keys = keys)
                    store.update(next)
                    _justSaved.value = next
                    next
                } else {
                    saveRecorded(autoName(), steps, keys)
                }
                current.syncBalls()
                current.toast("已存成「${saved.name}」· ${steps.size} 步，点屏幕上的小按钮就能用")
            },
            onCancel = {
                _captureMode.value = null
                current.onCaptureEnd()
            }
        )
    }

    /** 中途切走 / 点重置时，把「录制中」状态、全屏膜与临时缩小的窗口一起收干净 */
    fun cancelCapture() {
        _captureMode.value = null
        host?.dismissCapture()
        host?.onCaptureEnd()
    }

    private fun capture(mode: CaptureMode) {
        val current = host ?: return
        _justSaved.value = null       // 新一轮录制开始，收掉上一条「已自动保存」提示
        _captureMode.value = mode
        // 录制膜是全屏的，连招窗先临时缩成球，免得挡住要比划的位置
        current.onCaptureStart()
        current.showCapture(
            mode,
            onResult = { steps ->
                _captureMode.value = null
                if (steps.isEmpty()) {
                    // 什么都没录到：老老实实把面板还回去
                    current.onCaptureEnd()
                } else {
                    // v1.9.3：录完不再弹回录制面板，连招窗保持缩成球，
                    // 屏幕上只多出这颗连招自己的小按钮 —— 点一下就能用。
                    current.onCaptureEnd(restore = false)
                    val saved = saveRecorded(autoName(), steps)
                    current.syncBalls()
                    current.toast("已存成「${saved.name}」，点屏幕上的小按钮就能用")
                }
            },
            onCancel = {
                _captureMode.value = null
                current.onCaptureEnd()
            }
        )
        // 说清楚「录制时按键不会真的放技能」，否则用户会以为按键失灵（上一版就栽在这）
        current.toast("录制中：按键只记录顺序和间隔，不会真的放技能；屏幕其它地方照常操作游戏")
    }

    /** 自动编号：连招 1 / 连招 2 …，之后随时可以改名 */
    private fun autoName(): String = "连招 ${store.macros.value.size + 1}"

    private fun saveRecorded(
        name: String,
        steps: List<MacroStep>,
        keys: List<MacroKey> = emptyList()
    ): MacroConfig {
        val macro = MacroConfig(
            name = name.ifBlank { autoName() },
            steps = steps,
            keys = keys,
            // 单点连击默认 200ms 一次（约 5 次/秒），多步录制默认每轮隔 400ms
            intervalMs = if (steps.size == 1) 200L else 400L,
            repeat = 0,
            // v1.9.3：保存即成为一颗独立小按钮，不用再手动点「置顶」
            pinned = true,
            // -1 = 让 MacroBall 自己贴右边竖排，几颗球不会全叠在左上角
            ballX = -1,
            ballY = -1,
            // v1.8.4：保存即分配一枚专属图标，按钮一出生就和别的长得不一样
            iconPreset = MacroIcons.autoPreset(store.macros.value.size)
        )
        store.add(macro)
        _pending.value = null
        _justSaved.value = macro
        return macro
    }

    /** 兼容旧的手动保存入口；录制现在走 [saveRecorded]，这个只留给外部兜底 */
    fun savePending(name: String): MacroConfig? {
        val p = _pending.value ?: return null
        return saveRecorded(name.ifBlank { p.suggestedName }, p.steps)
    }

    fun discardPending() {
        _pending.value = null
    }

    /** 关掉面板上「已自动保存」的提示卡片 */
    fun clearJustSaved() {
        _justSaved.value = null
    }

    /** v1.9.1：每个连招独立的启用 / 停用，关掉后不执行、也不显示它的悬浮球 */
    fun toggleEnabled(macro: MacroConfig) {
        val next = !macro.enabled
        if (!next && _runningId.value == macro.id) runner.stop()
        store.update(macro.copy(enabled = next))
        host?.syncBalls()
    }

    /** 重新给已保存的宏取一个点击点（只有单点宏可用） */
    fun reAim(macro: MacroConfig) {
        val current = host ?: return
        _captureMode.value = CaptureMode.AIM
        current.onCaptureStart()
        current.showCapture(
            CaptureMode.AIM,
            onResult = { steps ->
                _captureMode.value = null
                current.onCaptureEnd()
                val point = steps.firstOrNull()?.start ?: return@showCapture
                val duration = macro.steps.firstOrNull()?.durationMs ?: 50L
                update(macro.copy(steps = listOf(MacroStep.tap(point.x, point.y, duration))))
            },
            onCancel = {
                _captureMode.value = null
                current.onCaptureEnd()
            }
        )
    }

    // ------------------------------------------------------------------
    // 执行 / 编辑
    // ------------------------------------------------------------------

    fun toggleRun(macro: MacroConfig) {
        if (!macro.enabled) {
            host?.toast("「${macro.name}」已关闭，先打开它自己的开关")
            return
        }
        if (_runningId.value == macro.id) {
            runner.stop()
            return
        }
        val backend = resolveBackend()
        if (backend == Backend.NONE) {
            host?.toast(
                MacroEngine.missingHint(
                    MacroEngine.mode.value,
                    RootShell.granted,
                    ShizukuShell.granted,
                    MacroAccessibilityService.connected
                )
            )
            return
        }
        runner.start(macro, backend, currentPolicy())
    }

    /** 只跑一轮，用来试手感 */
    fun previewOnce(macro: MacroConfig) {
        if (!macro.enabled) {
            host?.toast("「${macro.name}」已关闭，先打开它自己的开关")
            return
        }
        val backend = resolveBackend()
        if (backend == Backend.NONE) {
            host?.toast(
                MacroEngine.missingHint(
                    MacroEngine.mode.value,
                    RootShell.granted,
                    ShizukuShell.granted,
                    MacroAccessibilityService.connected
                )
            )
            return
        }
        runner.start(macro.copy(repeat = 1), backend, currentPolicy())
    }

    /**
     * v2.5.6 运行策略由「运行姿态」决定（默认多指并行）：
     *  - PARALLEL：不等待用户松手，短脉冲注入 —— 边走位边放连招（用户核心诉求）；
     *  - YIELD   ：旧「让路」行为，手一碰屏幕就等松手；
     *  - PRIORITY：旧「连招优先」，打包一路点到底。
     * 三者都从 OverlayPrefs.macroRunMode 读取，key 化避免顺序耦合。
     */
    private fun currentPolicy(): MacroRunPolicy {
        if (!::appContext.isInitialized) return MacroRunPolicy(parallel = true)
        val prefs = OverlayPrefs(appContext)
        val resume = prefs.macroResumeDelayMs.toLong().coerceIn(200L, 4000L)
        return when (MacroRunMode.fromKey(prefs.macroRunMode)) {
            MacroRunMode.PARALLEL -> MacroRunPolicy(
                manualFirst = false,
                parallel = true,
                pulseMs = prefs.macroPulseMs.toLong(),
                resumeDelayMs = resume
            )
            MacroRunMode.YIELD -> MacroRunPolicy(
                manualFirst = true,
                parallel = false,
                resumeDelayMs = resume
            )
            MacroRunMode.PRIORITY -> MacroRunPolicy(
                manualFirst = false,
                parallel = false
            )
        }
    }

    fun stopAll() {
        if (::runner.isInitialized) runner.stop()
        _pausedByUser.value = false
    }

    fun update(macro: MacroConfig) {
        if (_runningId.value == macro.id) runner.stop()
        store.update(macro)
        host?.syncBalls()
    }

    fun remove(macro: MacroConfig) {
        if (_runningId.value == macro.id) runner.stop()
        if (_justSaved.value?.id == macro.id) _justSaved.value = null
        MacroIconStorage.delete(macro.iconImagePath)   // 顺手删掉它的专属图片
        store.remove(macro.id)
        host?.syncBalls()
    }

    // ------------------------------------------------------------------
    // 专属按键外观（v1.8.4）
    //
    // 和 update() 的区别：只改图标 / 图片 / 颜色，不动动作参数，也不会打断
    // 正在执行的连招。可从主页、透明选图 Activity 等任意 Context 调用（init 幂等）。
    // ------------------------------------------------------------------

    /** 把一份改好外观的 MacroConfig 落盘，并让已挂出的悬浮按钮即时刷新 */
    fun applyAppearance(context: Context, macro: MacroConfig) {
        init(context.applicationContext)
        val current = store.get(macro.id) ?: return
        val next = current.copy(
            iconPreset = macro.iconPreset,
            iconImagePath = macro.iconImagePath,
            iconColor = macro.iconColor
        )
        // 换图 / 清图 / 恢复默认时统一在这里删掉旧文件，避免私有目录堆积孤儿图
        val oldPath = current.iconImagePath
        if (oldPath != null && oldPath != next.iconImagePath) MacroIconStorage.delete(oldPath)
        store.update(next)
        host?.syncBalls()
    }

    fun setIconPreset(context: Context, macroId: String, preset: String) {
        init(context.applicationContext)
        val m = store.get(macroId) ?: return
        applyAppearance(context, m.copy(iconPreset = preset))
    }

    fun setIconImage(context: Context, macroId: String, path: String?) {
        init(context.applicationContext)
        val m = store.get(macroId) ?: return
        // 旧图由 applyAppearance 统一清理
        applyAppearance(context, m.copy(iconImagePath = path))
    }

    fun setIconColor(context: Context, macroId: String, color: Int) {
        init(context.applicationContext)
        val m = store.get(macroId) ?: return
        applyAppearance(context, m.copy(iconColor = color))
    }

    fun resetIcon(context: Context, macroId: String) {
        init(context.applicationContext)
        val m = store.get(macroId) ?: return
        MacroIconStorage.delete(m.iconImagePath)
        applyAppearance(
            context,
            m.copy(iconPreset = MacroIcons.DEFAULT, iconImagePath = null, iconColor = 0)
        )
    }

    fun togglePin(macro: MacroConfig) {
        store.update(macro.copy(pinned = !macro.pinned))
        host?.syncBalls()
    }

    // ------------------------------------------------------------------
    // 分享 / 导入（v1.9.15）
    //
    // 坐标来自对方设备，必须先按本机屏幕折算再落盘，否则小屏机上有一半动作点在屏幕外，
    // 系统会直接丢掉那些触摸 —— 表现就是「导入的连招只动了一半」。
    // ------------------------------------------------------------------

    fun importShared(context: Context, payload: MacroSharePayload): MacroMergeResult {
        init(context.applicationContext)
        val metrics = context.resources.displayMetrics
        val result = MacroShare.merge(
            existing = store.macros.value,
            payload = payload,
            dstW = metrics.widthPixels,
            dstH = metrics.heightPixels
        )
        if (result.add.isNotEmpty()) {
            store.addAll(result.add)
            host?.syncBalls()
        }
        return result
    }

    /** 解析失败返回 null（调用方负责提示「这不是连招口令」） */
    fun importSharedText(context: Context, text: String?): MacroMergeResult? {
        val payload = MacroShare.decode(text) ?: return null
        return importShared(context, payload)
    }

    fun updateBallPosition(macro: MacroConfig, x: Int, y: Int) {
        store.updateBallPosition(macro.id, x, y)
    }

    /**
     * Service 销毁时调用。这里**只停当前动作、解绑宿主**，不能 cancel Runner 的 CoroutineScope：
     * 悬浮窗关掉之后用户还可能从主页再开一次，Scope 一旦被 cancel 就再也跑不动了。
     */
    fun shutdown() {
        stopAll()
        host = null
    }
}
