/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。自动连招的执行端：通过 AccessibilityService.dispatchGesture
 * 把录制好的手势注入系统。这是 Android 7.0+ 的标准无障碍能力，不需要 root。
 *
 * v1.9.1：额外监听「双击音量键」来隐藏 / 显示所有悬浮窗。
 *   - 用动态 setServiceInfo 申请 FLAG_REQUEST_FILTER_KEY_EVENTS，不用 XML 属性，
 *     兼容 minSdk 24；没开音量键隐藏开关时完全不处理按键；
 *   - onKeyEvent 一律 return false，不吞按键，音量键照常能调音量；
 *   - 只用「同一方向 600ms 内连按两次」触发，避免单次调音量误触。
 *
 * 合规提示：本服务只执行「用户自己录制/设置」的点击，不读取屏幕内容、不截取其他应用信息、
 * 不联网上传任何数据。是否允许在游戏中使用自动化操作，取决于该游戏的用户协议，
 * 请仅在被允许的场景下使用。
 */

package com.yunx.app.overlay.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.yunx.app.overlay.data.OverlayPrefs
import com.yunx.app.overlay.service.FloatingService

class MacroAccessibilityService : AccessibilityService() {

    private var lastVolumeCode = 0
    private var lastVolumeAt = 0L

    override fun onServiceConnected() {
        instance = this
        // 动态申请按键过滤（XML 里已声明 canRequestFilterKeyEvents，这里再 or 一次，
        // 兜住「老系统 / 被别的东西改写过的 serviceInfo」）。
        runCatching {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }
        // v1.9.19：读回来确认真拿到了。有些 ROM / 系统策略会静默丢掉这个 flag，
        // 表现就是「双击音量键一点反应都没有」，不读回来根本不知道。
        keyFilterActive = runCatching {
            (serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        }.getOrDefault(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只借用 dispatchGesture，不订阅任何窗口事件
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!OverlayPrefs(this).volumeKeyHide) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        // 服务没跑时没有窗口可隐藏，也不该从后台偷偷拉起前台服务
        if (!FloatingService.isRunning) return false

        val now = SystemClock.uptimeMillis()
        val doubleTap = lastVolumeCode == code && now - lastVolumeAt <= DOUBLE_TAP_MS
        lastVolumeCode = code
        lastVolumeAt = now
        if (doubleTap) {
            lastVolumeCode = 0
            lastVolumeAt = 0L
            // 切换与提示都由 Service 自己完成（它才知道切换后的真实状态）
            runCatching {
                startService(
                    Intent(this, FloatingService::class.java)
                        .setAction(FloatingService.ACTION_TOGGLE_VISIBILITY)
                )
            }
        }
        // 不消费按键：音量键仍可正常调音量
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        keyFilterActive = false
        lastVolumeCode = 0
        lastVolumeAt = 0L
        return super.onUnbind(intent)
    }

    companion object {
        private const val DOUBLE_TAP_MS = 600L

        @Volatile
        private var instance: MacroAccessibilityService? = null

        /** 服务是否已连接（已连接才可以 dispatchGesture） */
        val connected: Boolean get() = instance != null

        /**
         * v1.9.19：系统是否真的把「按键事件过滤」给了本服务。
         * false 时 onKeyEvent 不会被调用 —— 双击音量键隐身不可用，只能走面板 / 通知栏。
         */
        @Volatile
        var keyFilterActive: Boolean = false
            private set

        fun dispatch(
            gesture: android.accessibilityservice.GestureDescription,
            callback: AccessibilityService.GestureResultCallback?
        ): Boolean = runCatching {
            instance?.dispatchGesture(gesture, callback, null) ?: false
        }.getOrDefault(false)

        /**
         * 读系统「已启用的无障碍服务」列表。
         * 设置里存的是 flattenToString 或短格式，两种都匹配一遍，避免不同 ROM 写法差异。
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, MacroAccessibilityService::class.java)
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            if (enabled.isBlank()) return false
            return enabled.split(':').any { entry ->
                entry.isNotBlank() &&
                    (entry.equals(expected.flattenToString(), ignoreCase = true) ||
                        entry.equals(expected.flattenToShortString(), ignoreCase = true))
            }
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
