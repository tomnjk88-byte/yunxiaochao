/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。可选的 root 执行后端：申请 su 授权，并复用一条持久 su shell
 * 下发 input 命令注入触摸。不读屏、不联网、不采集任何数据。
 *
 * 合规提示：root 只在用户主动点「获取 Root 权限」并同意授权后才使用；部分游戏的
 * 反作弊会检测 root / 自动化操作，是否允许使用取决于该游戏用户协议，风险由使用者自负。
 */

package com.yunx.app.overlay.macro

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 一条长期存活的 `su` 会话。
 *
 * 为什么不每次 `su -c "input tap ..."`：那样每点一次都要重新 fork 一个
 * app_process，开销更大。复用同一条 shell 后，写命令只有一次 flush。
 *
 * 关键约束：**每条命令都要等它真正执行完**（用 echo 哨兵同步），否则在
 * 20ms 间隔下 shell 会积压几十条 input 命令，用户点「停止」之后还会继续点。
 * 代价是 root 后端的点击频率受 `input` 进程启动速度限制（约 8~12 次/秒），
 * 明显低于无障碍 dispatchGesture，这是取舍，见 README。
 */
object RootShell {

    enum class State {
        UNKNOWN,
        /** 设备没有 su（未 root，或 su 被隐藏） */
        NO_SU,
        /** 有 su，但用户拒绝授权 / 授权弹窗超时 */
        DENIED,
        /** 已拿到 uid 0，且持久 shell 可用 */
        GRANTED
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errDrain: Thread? = null
    private var seq = 0

    /**
     * `input motionevent DOWN/MOVE/UP` 是 Android 12（API 31）才加入的子命令。
     * 有它才能比较精确地回放多点轨迹；没有就退化成「首尾两点直线 swipe」。
     */
    val motionSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val granted: Boolean
        get() = _state.value == State.GRANTED && synchronized(lock) {
            val p = process
            p != null && writer != null && reader != null &&
                runCatching { p.exitValue() }.isFailure
        }

    /**
     * 用户点「获取 Root 权限」时调用。第一次执行 su 会触发 Magisk / KernelSU /
     * APatch 的授权弹窗，所以整段必须带超时，不能把线程永久卡在等用户点按钮上。
     */
    fun request(): State {
        _state.value = probe()
        if (_state.value == State.GRANTED && !openSession()) _state.value = State.DENIED
        return _state.value
    }

    /** 静默恢复上一轮授权状态（冷启动时不弹窗；已授权过的 su 不会再问） */
    fun restore(): State {
        _state.value = probe()
        if (_state.value == State.GRANTED && !openSession()) _state.value = State.DENIED
        return _state.value
    }

    private fun probe(): State {
        val pool = Executors.newSingleThreadExecutor()
        val handle = AtomicReference<Process?>(null)
        val future = pool.submit(
            Callable {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
                    handle.set(p)
                    // 有的 su 实现会先打 banner，取最后一个纯数字行当 uid
                    val lines = p.inputStream.bufferedReader().use { it.readLines() }
                    val uid = lines.map { it.trim() }.lastOrNull { it.matches(Regex("\\d+")) }.orEmpty()
                    val err = p.errorStream.bufferedReader().use { it.readText() }
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    if (uid == "0" && code == 0) State.GRANTED else State.DENIED
                } catch (e: Exception) {
                    State.NO_SU
                } finally {
                    runCatching { handle.get()?.destroy() }
                }
            }
        )
        return try {
            future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // 授权弹窗还挂着没点：先按拒绝处理，用户点完可以再点一次按钮
            future.cancel(true)
            runCatching { handle.get()?.destroy() }
            State.DENIED
        } catch (e: Exception) {
            State.NO_SU
        } finally {
            pool.shutdownNow()
        }
    }

    private fun openSession(): Boolean = synchronized(lock) {
        closeInternal()
        try {
            val p = Runtime.getRuntime().exec("su")
            // stdout 由 execAndWait 同步读取（找哨兵）；stderr 单独排空，避免管道写满卡死 shell
            val t = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(p.errorStream)).use {
                        while (it.readLine() != null) Unit
                    }
                }
            }.apply {
                isDaemon = true
                name = "yunx-root-err-drain"
                start()
            }
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            reader = BufferedReader(InputStreamReader(p.inputStream))
            errDrain = t
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    /**
     * 写一条命令并等它执行完。命令后追加 `echo <哨兵>`，从 stdout 读到该行才返回。
     * 必须在 IO 线程调用；整段加 lock 保证同一时刻只有一条命令在飞。
     */
    fun execAndWait(command: String, timeoutMs: Long = CMD_TIMEOUT_MS): Boolean {
        if (!granted) return false
        return synchronized(lock) {
            val w = writer ?: return@synchronized false
            val r = reader ?: return@synchronized false
            val token = "__YUNX_DONE_${seq++}__"
            val pool = Executors.newSingleThreadExecutor()
            val future = pool.submit(
                Callable<Boolean> {
                    try {
                        w.write(command); w.newLine()
                        w.write("echo $token"); w.newLine()
                        w.flush()
                        // input 命令一般往 stdout 不输出东西，忽略其它行只找哨兵
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
            } catch (e: Exception) {
                future.cancel(true)
                // 读超时后 reader 状态已经不可信，直接废弃会话，下次会提示重新授权
                closeInternal()
                _state.value = State.DENIED
                false
            } finally {
                pool.shutdownNow()
            }
        }
    }

    /** 点击；长按用「同点 swipe」近似按住 */
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

    /** 首尾两点直线滑动（多点轨迹在 root 模式下的降级实现） */
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean =
        execAndWait(
            "input swipe ${fromX.toInt()} ${fromY.toInt()} " +
                "${toX.toInt()} ${toY.toInt()} ${durationMs.coerceIn(1L, 10_000L)}",
            durationMs + CMD_TIMEOUT_MS
        )

    /** Android 12+：按轨迹逐点下发 DOWN/MOVE/UP，比 swipe 更接近真实录制 */
    fun motion(action: String, x: Float, y: Float): Boolean =
        motionSupported && execAndWait("input motionevent $action ${x.toInt()} ${y.toInt()}")

    fun close() {
        synchronized(lock) { closeInternal() }
        _state.value = State.UNKNOWN
    }

    private fun closeInternal() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.outputStream?.close() }
        runCatching { process?.destroy() }
        writer = null
        reader = null
        process = null
        errDrain = null
    }

    private const val PROBE_TIMEOUT_MS = 20_000L
    private const val CMD_TIMEOUT_MS = 4_000L
    private const val LONG_PRESS_MS = 250L
}
