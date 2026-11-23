/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 *
 * 修改说明：新增文件（v2.1.1）。换风格不再硬切，改走一次「碎块穿场」：
 *
 *   旧皮肤 --碎块从左上合拢--> 整屏旧底色 --换肤--> 碎块往右下散开 --> 新皮肤
 *
 * 为什么必须由点击方驱动、而不是监听风格变化再补动画：风格是个 StateFlow，
 * 一 `select` 全树当场重组，旧皮肤连一帧都留不住。所以顺序反过来 ——
 * 先把画面盖死，再换，再揭开。
 *
 * 遮罩色取「切换前」那套皮肤的底色，整场不变：合拢时它和旧画面同色，块像
 * 从画面里长出来；散开时它又成了旧画面最后一点残影，块没了才露出新皮肤。
 * 换成新底色的话，合拢那一刻就会先闪一下新色，等于白做。
 */

package com.yunx.app.overlay.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.yunx.app.overlay.data.MotionPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 合拢：旧画面被碎块吃掉。比散开短，观感是「先手起刀落，再慢慢亮出来」 */
private const val COVER_MS = 200

/** 散开：新皮肤一块块露出来。和切页 560ms 同一个呼吸，不拖 */
private const val REVEAL_MS = 380

object StyleCurtain {

    /** 1 = 全露，0 = 全遮。只在 draw 阶段读 —— 每帧写它只重绘，不重组整棵树 */
    private val _progress = mutableFloatStateOf(1f)

    /**
     * 遮罩色。用 State 而不是普通字段：颜色和进度是两次独立写入，
     * 万一进度这一帧没变，普通字段改了也不会触发重绘，第一帧就会用旧色。
     */
    private val _tint: MutableState<Color> = mutableStateOf(Color.Black)

    // 必须用 AndroidUiDispatcher：它自带 MonotonicFrameClock，下面的 withFrameNanos
    // 才拿得到帧。普通 MainScope 只有 Dispatchers.Main，一调 withFrameNanos 就抛
    // 「No MonotonicFrameClock in context」—— 编译期完全看不出来，只在真机换肤时崩。
    // SupervisorJob：某一次过渡抛异常不该把整个单例的 scope 带走，
    // 否则之后再点风格就永远不播了。
    private val scope: CoroutineScope by lazy {
        CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    }
    private var job: Job? = null
    private var watchdog: Job? = null

    /** 给 tileCurtain 的读取器，务必在 draw lambda 里调 */
    fun progressValue(): Float = _progress.floatValue

    fun tintValue(): Color = _tint.value

    /**
     * 切到 [style]。已经在这套风格上时什么都不做。
     *
     * 动画关掉时直接换 —— 不是把两段时长设成 0 再走一遍，
     * 那样仍会有一帧整屏遮罩，看起来像闪黑。
     */
    fun switch(context: Context, style: UiStyle, bg: Color) {
        if (GuideStyleStore.style.value == style) return

        val coverMs = MotionPrefs.duration(COVER_MS)
        val revealMs = MotionPrefs.duration(REVEAL_MS)
        if (coverMs <= 0 || revealMs <= 0) {
            job?.cancel()
            watchdog?.cancel()
            _progress.floatValue = 1f
            GuideStyleStore.select(context, style)
            return
        }

        _tint.value = bg
        // 连点两套风格：从当前进度接着走，不要跳回 1 再合一次 —— 那会闪一下旧画面
        job?.cancel()

        // v2.5.3 看门狗：整场过渡最多 coverMs + revealMs + 700ms。
        // 超时还停在遮罩里就强行揭开 —— 遮罩是 0 的时候整屏什么都点不动，
        // 用户看到的就是「点一下按钮卡死了，只能杀进程」。
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(coverMs + revealMs + 700L)
            if (_progress.floatValue < 0.999f) _progress.floatValue = 1f
        }

        job = scope.launch {
            try {
                val anim = Animatable(_progress.floatValue)
                // 还在遮罩里（上一轮刚合拢、或散到一半）就别再合一遍：
                // 直接换皮接着散。否则连点两套风格会看到画面「合上→退回去→再合上」。
                if (anim.value > 0.55f) {
                    anim.animateTo(0f, tween(coverMs, easing = LinearEasing)) {
                        _progress.floatValue = value
                    }
                }
                GuideStyleStore.select(context, style)
                // 等一帧让新皮肤真的画出来，再散。否则第一块让开时露出的还是旧树。
                // v2.5.3：必须带超时。AndroidUiDispatcher 的 MonotonicFrameClock 在
                // 窗口不可见时（换肤弹窗刚 dismiss、刚切后台）不会回调，裸 withFrameNanos
                // 会永久挂住 —— 而此刻遮罩正好是全合（progress=0），于是整屏永远黑着，
                // 这就是「有时候点完风格就卡死」的真凶。
                withTimeoutOrNull(150L) { withFrameNanos { } }
                anim.animateTo(1f, tween(revealMs, easing = LinearEasing)) {
                    _progress.floatValue = value
                }
                _progress.floatValue = 1f
            } catch (t: Throwable) {
                // 被下一个 switch 取消是正常路径：进度交给新 job 接管，这里不要动它。
                // 只有真异常（select 写偏好失败等）才把遮罩揭开，否则页面会一直黑着。
                if (t is CancellationException) throw t
                _progress.floatValue = 1f
                throw t
            }
        }
    }
}

/**
 * 挂在 [GuideTheme] 最外层的遮罩。放进 CompositionLocalProvider 里面，
 * 这样它读到的就是当前皮肤的 tokens；但颜色只在 [StyleCurtain.switch] 里
 * 写一次，所以整场过渡用的都是切换前那套底色。
 */
@Composable
internal fun StyleCurtainLayer(
    spec: TileSpec = TileSpec.CHUNKY,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .tileCurtain(
                progress = { StyleCurtain.progressValue() },
                color = StyleCurtain.tintValue(),
                spec = spec
            )
    ) {
        content()
    }
}
