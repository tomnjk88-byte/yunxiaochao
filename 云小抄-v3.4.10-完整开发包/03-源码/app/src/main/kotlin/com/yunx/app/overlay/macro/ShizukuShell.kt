/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。v3.2.0 的第三个连招后端 —— 通过 Shizuku 以 shell 身份注入。
 *
 * 为什么需要它（这是「连招时走不动」的根因）：
 *   无障碍的 dispatchGesture 每发一次手势，系统会先取消屏幕上所有进行中的触摸
 *   （官方文档原话：user / this service / other services 的手势都会被取消）。
 *   你按住摇杆走路时，连招每放一个技能就把摇杆那根手指「掐断」一次，
 *   游戏收到 ACTION_CANCEL，角色立刻停住 —— 表现就是一卡一卡、根本走不动。
 *
 *   厂商游戏空间（红米 / 小米的自动连招）是系统签名应用，直接走
 *   InputManager.injectInputEvent，注入的是一根**独立 pointer**，
 *   与用户的摇杆手指在系统里并存，所以能边走边放。
 *
 *   第三方 App 拿不到 INJECT_EVENTS 签名权限，但 Shizuku 可以：
 *   它用 adb（无线调试）或 root 起一个 shell 身份的服务，本应用经它执行
 *   `input tap`，事件由 shell uid 注入，同样是独立 pointer，不会取消你的触摸。
 *
 * 代价：`input` 每次都要起一个进程，单次约 80~150ms，连招节奏会比无障碍慢。
 * 这是「能走位」换来的，值。要更快只能用 root 下的原生注入，见 RootShell。
 */

package com.yunx.app.overlay.macro

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 一条经 Shizuku 拉起的持久 `sh` 会话（uid = shell 或 root，取决用户怎么启动 Shizuku）。
 *
 * 和 [RootShell] 的结构故意保持一致：写命令 → 写一条 echo 哨兵 → 读到哨兵才算执行完。
 * 不这么做的话，20ms 间隔下 shell 会积压几十条 input，点「停止」后还在点。
 */
object ShizukuShell {

    enum class State {
        UNKNOWN,
        /** 没装 Shizuku / 服务没启动 */
        NO_SHIZUKU,
        /** Shizuku 在跑，但本应用还没被授权 */
        NOT_GRANTED,
        /** 已授权，可以起 shell */
        GRANTED,
        /** 用户拒绝了授权 */
        DENIED
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 授权结果回调里用，任意常数即可 */
    const val REQUEST_CODE = 4213

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var seq = 0
    private var inited = false

    /** 与 RootShell 一致：Android 12 起才有 input motionevent */
    val motionSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val granted: Boolean
        get() = _state.value == State.GRANTED && Shizuku.pingBinder()

    /**
     * 只注册一次监听。放在 MacroController.init 里调用。
     *
     * 注意：ShizukuProvider 必须在 AndroidManifest 里声明，否则这里的
     * pingBinder() 永远是 false —— 用户明明开着 Shizuku 也会显示「未安装」。
     */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            inited = true
        }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                closeSession()
                _state.value = State.NO_SHIZUKU
            }
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                _state.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    State.GRANTED
                } else {
                    State.DENIED
                }
            }
        }
        refresh()
    }

    fun refresh(): State {
        val next = runCatching {
            when {
                !Shizuku.pingBinder() -> State.NO_SHIZUKU
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    State.NOT_GRANTED
                else -> State.GRANTED
            }
        }.getOrDefault(State.NO_SHIZUKU)
        _state.value = next
        if (next != State.GRANTED) closeSession()
        return next
    }

    /** 拉起 Shizuku 的授权页。必须在主线程调用（它会 startActivity） */
    fun requestPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) {
            _state.value = State.NO_SHIZUKU
            false
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 命令通道
    // ------------------------------------------------------------------

    fun execAndWait(command: String, timeoutMs: Long = CMD_TIMEOUT_MS): Boolean {
        if (!granted) return false
        return synchronized(lock) {
            if (!ensureSession()) return@synchronized false
            val w = writer ?: return@synchronized false
            val r = reader ?: return@synchronized false
            val token = "__YUNX_SHZ_${seq++}__"
            val pool = Executors.newSingleThreadExecutor()
            val future = pool.submit(
                Callable<Boolean> {
                    try {
                        w.write(command); w.newLine()
                        w.write("echo $token"); w.newLine()
                        w.flush()
                        var found = false
                        while (!found) {
                            val line = r.readLine() ?: break
                            if (line.contains(token)) found = true
                        }
                        found
                    } catch (e: Exception) {
                        false
                    }
                }
            )
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // 卡住了就整条会话作废，下次重建，避免后面每一步都等同一个死进程
                future.cancel(true)
                closeSessionInternal()
                false
            } catch (e: Exception) {
                closeSessionInternal()
                false
            } finally {
                pool.shutdownNow()
            }
        }
    }

    fun tap(x: Float, y: Float, durationMs: Long): Boolean {
        val px = x.toInt()
        val py = y.toInt()
        val cmd = if (durationMs >= LONG_PRESS_MS) {
            "input swipe $px $py $px $py $durationMs"
        } else {
            "input tap $px $py"
        }
        return execAndWait(cmd, durationMs + CMD_TIMEOUT_MS)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean =
        execAndWait(
            "input swipe ${fromX.toInt()} ${fromY.toInt()} " +
                "${toX.toInt()} ${toY.toInt()} ${durationMs.coerceIn(1L, 10_000L)}",
            durationMs + CMD_TIMEOUT_MS
        )

    fun motion(action: String, x: Float, y: Float): Boolean =
        motionSupported && execAndWait("input motionevent $action ${x.toInt()} ${y.toInt()}")

    fun close() {
        synchronized(lock) { closeSessionInternal() }
        _state.value = State.UNKNOWN
    }

    private fun ensureSession(): Boolean {
        val p = process
        if (p != null && writer != null && reader != null) {
            val alive = runCatching { p.exitValue() }.isFailure
            if (alive) return true
        }
        closeSessionInternal()
        return runCatching {
            val started = newProcess()
            val w = BufferedWriter(OutputStreamWriter(started.outputStream))
            val r = BufferedReader(InputStreamReader(started.inputStream))
            process = started
            writer = w
            reader = r
            true
        }.getOrDefault(false)
    }

    /**
     * Shizuku 13 把 newProcess 改成了 private（不希望普通应用随便起进程），
     * 但服务端仍然实现了它。这里用反射调用，失败就退化成「本后端不可用」。
     * 反射的是 Shizuku 自己的类，不涉及 Android 隐藏 API 限制。
     */
    private fun newProcess(): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh"), null, null) as Process
    }

    private fun closeSession() {
        synchronized(lock) { closeSessionInternal() }
    }

    private fun closeSessionInternal() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        writer = null
        reader = null
        process = null
    }

    private const val CMD_TIMEOUT_MS = 4_000L
    private const val LONG_PRESS_MS = 250L
}
