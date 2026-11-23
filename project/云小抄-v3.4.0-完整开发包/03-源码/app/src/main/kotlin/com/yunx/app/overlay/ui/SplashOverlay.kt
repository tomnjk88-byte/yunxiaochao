/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.4.0）。自定义开屏动画：图片（缓慢推近）或视频（静音自动播，
 *   播完淡出），轻触任意处可提前跳过。
 */

package com.yunx.app.overlay.ui

import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.yunx.app.R
import com.yunx.app.overlay.data.SplashPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 开屏动画。
 *
 * 直接盖在主界面上（不是单独的 Activity）：主界面在底下照常完成首帧，
 * 开屏退场时下面已经是画好的，不会闪白。触摸由这一层全部吃掉，
 * 开屏期间碰不到下面的按钮。
 *
 * @param path 已经在私有目录里的图 / 视频绝对路径
 * @param holdMs 图片停留时长；视频忽略，播完即走
 */
@Composable
fun SplashOverlay(
    path: String?,
    kind: Int,
    holdMs: Int,
    onExitStart: () -> Unit = {},
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 从 1 开始，不做淡入：首帧要是透明的，用户会先看到主页闪一下再被黑屏盖住。
    // 内容自己的 appear 动画已经够「登场」了，黑场直接压上去最干净。
    val fade = remember { Animatable(1f) }
    // 自定义图 / 视频解码要几十到几百毫秒，直接显示会「啪」一下跳出来；
    // 黑底不动、内容淡进来，等图的时间也就不显眼。内置那套自己有 appear，不再叠。
    val contentFade = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }

    // 用一个普通函数收口：图片定时、视频播完、用户点击、解码失败，四条路都走这里，
    // leaving 挡住重复调用，免得淡出动画被打断两次直接把 onFinish 跑两遍。
    fun leave() {
        if (leaving) return
        leaving = true
        // 先通知外面「准备收场」：入场动画在开屏这 240ms 淡出期间就开始起跑，
        // 两段叠在一起，观感是「开屏化开、下面碎格散开」，而不是先黑一下再开始。
        onExitStart()
        scope.launch {
            // v2.5.1：用 finally 兜住 —— 淡出万一被打断（组合提前回收、系统杀协程），
            // onFinish 不执行的话这一层会一直盖在最上面把点击全吃掉，
            // 表现就是「进软件后点什么都没反应」。
            try {
                fade.animateTo(0f, tween(240, easing = LinearEasing))
            } finally {
                onFinish()
            }
        }
    }

    LaunchedEffect(path) {
        if (path == null) contentFade.snapTo(1f)
        else contentFade.animateTo(1f, tween(260, easing = LinearEasing))
    }

    // 兜底计时：内置开屏 1.3 秒；图片按设置的停留时长；视频给一个上限，
    // 避免选了长片被困住。三条都远长于淡入，所以不会打架。
    LaunchedEffect(path, kind, holdMs) {
        val ms = when {
            path == null -> 1300L
            kind == SplashPrefs.KIND_VIDEO -> SplashPrefs.MAX_VIDEO_MS
            else -> holdMs.toLong()
        }
        delay(ms)
        leave()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { alpha = fade.value }
            .pointerInput(Unit) { detectTapGestures { leave() } }
    ) {
        // 内容单独一层做淡入，黑底不跟着淡 —— 否则又会先露出主页
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentFade.value }) {
            when {
                path == null -> BuiltinSplash()
                kind == SplashPrefs.KIND_VIDEO -> SplashVideo(path = path, onEnd = { leave() })
                else -> SplashImage(path = path, holdMs = holdMs)
            }
        }

        Text(
            "轻触跳过",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * 没设自定义文件时的默认开屏：图标先「亮」起来，名字随后浮上来。
 *
 * 不做成一张内置图片，是为了跟三套皮肤都不打架 —— 黑底 + 应用自己的图标，
 * 深浅皮肤下都成立，也不用额外往 APK 里塞大图。
 */
@Composable
private fun BuiltinSplash() {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(680, easing = LinearEasing))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                // 用前景层那张 PNG，不用 mipmap 的自适应图标：
                // painterResource 碰到 <adaptive-icon> 会抛异常，前景图是透明底的位图，最稳
                painter = painterResource(R.drawable.ic_launcher_new_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        val e = ((appear.value - 0.05f) / 0.55f).coerceIn(0f, 1f)
                        alpha = e
                        val s = 0.84f + 0.16f * e
                        scaleX = s
                        scaleY = s
                    }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "云小抄",
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer {
                    val e = ((appear.value - 0.30f) / 0.5f).coerceIn(0f, 1f)
                    alpha = e
                    translationY = (1f - e) * 14f
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "YUNX GUIDE",
                color = Color.White.copy(alpha = 0.40f),
                fontSize = 9.sp,
                letterSpacing = 3.6.sp,
                modifier = Modifier.graphicsLayer {
                    val e = ((appear.value - 0.50f) / 0.5f).coerceIn(0f, 1f)
                    alpha = e
                }
            )
        }
    }
}

/** 图片：铺满整屏按比例完整显示，同时极慢推近，静帧也有呼吸感 */
@Composable
private fun SplashImage(path: String, holdMs: Int) {
    val ken = remember { Animatable(0f) }
    LaunchedEffect(path, holdMs) {
        // 比停留略长一点，退场时还在动，不会「先停住再淡出」
        ken.animateTo(1f, tween(holdMs + 500, easing = LinearEasing))
    }
    AsyncImage(
        model = File(path),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f + 0.05f * ken.value
                scaleX = s
                scaleY = s
            }
    )
}

/** 视频：静音、不循环、播完就退。解码失败也直接退，绝不把用户卡在黑屏上 */
@Composable
private fun SplashVideo(path: String, onEnd: () -> Unit) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setBackgroundColor(AndroidColor.BLACK)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    // 开屏突然出声会吓人，统一静音
                    runCatching { mp.setVolume(0f, 0f) }
                    start()
                }
                setOnCompletionListener { onEnd() }
                setOnErrorListener { _, _, _ ->
                    onEnd()
                    true
                }
                setVideoPath(path)
            }
        },
        update = { view ->
            // VideoView 自己重写了 onTouchEvent 且返回 false，setOnClickListener 的
            // performClick 走不到；直接用 OnTouchListener 收掉，轻触任意处即跳过。
            // 顺带把事件全吃掉，底下的主页在开屏期间也碰不到。
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) onEnd()
                true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
