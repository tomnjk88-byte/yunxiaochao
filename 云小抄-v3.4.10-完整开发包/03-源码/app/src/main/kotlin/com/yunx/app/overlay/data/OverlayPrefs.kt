/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件，保存悬浮窗几何参数与攻略根目录名。
 */

package com.yunx.app.overlay.data

import android.app.ActivityManager
import android.content.Context
import com.yunx.app.overlay.macro.MacroRunPolicy

/** 悬浮窗与业务相关的小配置（非敏感，和凭证分开存） */
class OverlayPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)

    /** 系统自己认为这是低内存设备（Android Go / 2~3GB 机） */
    private val isLowRam: Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .isLowRamDevice
    }.getOrDefault(false)

    /**
     * v1.9.5 低性能模式。
     *
     * 默认跟随系统 `isLowRamDevice`：低内存设备开箱就是省电省内存的姿态，
     * 好一点的机器不受影响。用户可以在「我的」页手动改，改过之后以用户选择为准。
     *
     * 打开后：缩略图按 512px 解码、预览图按屏幕尺寸解码、默认不画点击圆点。
     */
    var lowPerfMode: Boolean
        get() = prefs.getBoolean(KEY_LOW_PERF, isLowRam)
        set(value) {
            prefs.edit().putBoolean(KEY_LOW_PERF, value).apply()
        }

    /**
     * v1.9.6 高性能读取模式（默认开）。
     *
     * 和「低性能模式」不是一回事：低性能关心省内存，高性能关心**读得快**。
     * 打开后：
     *  - 悬浮窗挂起时先在后台把「上次浏览的分类」预热进内存，点开小抄直接出图，不再等网络；
     *  - 目录 / 快照缓存有效期从 10 分钟 / 30 分钟拉长到 2 小时 / 12 小时，
     *    少一次「翻个目录先转两秒圈」；
     *  - 列表拿到后立刻后台预取前几张缩略图，滚到哪一格都是现成的；
     *  - 缩略图统一按 512px 解码并共享同一份内存缓存，同一张图不重复解码。
     *
     * 关掉就退回 v1.9.5 的按需加载，省流量、省内存。
     */
    var highPerfMode: Boolean
        get() = prefs.getBoolean(KEY_HIGH_PERF, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HIGH_PERF, value).apply()
        }

    // ------------------------------------------------------------------
    // v1.9.8 小抄本地化（把图真正下到自己选的目录里，而不是系统缓存）
    // ------------------------------------------------------------------

    /**
     * 图片本地化总开关。
     *
     * 关掉 = v1.9.7 的行为：缩略图进 `cacheDir/guide_image_cache`，系统清缓存时会被删；
     * 打开 = 进「存储目录」，是**普通文件**，卸载 App 也不会被清（系统清缓存也不动它）。
     */
    var imageVaultEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_VAULT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_VAULT, value).apply()
        }

    /** 本地化目录（SAF 授权树 Uri）；空 = 用应用私有目录 `Android/data/<包名>/files/guide_images` */
    var imageTreeUri: String?
        get() = prefs.getString(KEY_IMAGE_TREE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_IMAGE_TREE, value?.takeIf { it.isNotBlank() }).apply()
        }

    /**
     * v2.0.0：下载后是否再做一层**无损打包**（`.yxz`，用时解压）。
     *
     * 与上面的 [imageCompress] 是两回事：那个是重编码（有损、会缩分辨率），
     * 这个只是把原始字节压一层 Deflate，画质一点不动。
     * 默认开；低性能模式下强制不压（见 ImagePackPolicy）。
     */
    var imagePack: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_PACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_PACK, value).apply()
        }

    /** 下载时是否重新压缩（JPEG 重编码 + 长边缩放），默认开：小抄图多为截图，压缩后肉眼几乎无差 */
    var imageCompress: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_COMPRESS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_COMPRESS, value).apply()
        }

    /** JPEG 质量（30~100） */
    var imageQuality: Int
        get() = prefs.getInt(KEY_IMAGE_QUALITY, 85).coerceIn(30, 100)
        set(value) {
            prefs.edit().putInt(KEY_IMAGE_QUALITY, value.coerceIn(30, 100)).apply()
        }

    /** 压缩后长边上限（px） */
    var imageMaxEdge: Int
        get() = prefs.getInt(KEY_IMAGE_MAX_EDGE, 1600).coerceIn(720, 4096)
        set(value) {
            prefs.edit().putInt(KEY_IMAGE_MAX_EDGE, value.coerceIn(720, 4096)).apply()
        }

    /** 只在 WiFi / 以太网下自动下载（默认开），避免把流量跑光 */
    var imageWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_WIFI_ONLY, value).apply()
        }

    /** 自动检测分享链接更新：每 5 天 + 每次启动 */
    var imageAutoCheck: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_AUTO_CHECK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_AUTO_CHECK, value).apply()
        }

    /** 上次检测更新时间（毫秒） */
    var imageLastCheckAt: Long
        get() = prefs.getLong(KEY_IMAGE_LAST_CHECK, 0L)
        set(value) {
            prefs.edit().putLong(KEY_IMAGE_LAST_CHECK, value).apply()
        }

    /**
     * 内容复用（v1.9.10）。
     *
     * 换分享链接、把文件夹挪到别的目录之后，网盘给的文件 id 会变，图还是那些图。
     * 打开时按「文件名 + 字节数」认领本地已有文件，不再重下一遍。
     * 关掉 = 只认 id，换链接就整区重下（一般没人想这样）。
     */
    var imageDedupEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_DEDUP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IMAGE_DEDUP, value).apply()
        }

    /** 本地图片占用上限（字节）；0 = 不限。超了按「最久没看」自动淘汰 */
    var imageMaxBytes: Long
        get() = prefs.getLong(KEY_IMAGE_MAX_BYTES, 0L).coerceAtLeast(0L)
        set(value) {
            prefs.edit().putLong(KEY_IMAGE_MAX_BYTES, value.coerceAtLeast(0L)).apply()
        }

    /** 上次自动去重 / 限额清理的时间（毫秒），一天最多跑一次 */
    var imageLastMaintainAt: Long
        get() = prefs.getLong(KEY_IMAGE_LAST_MAINTAIN, 0L)
        set(value) {
            prefs.edit().putLong(KEY_IMAGE_LAST_MAINTAIN, value).apply()
        }

    // ------------------------------------------------------------------
    // 镜像同步（v2.0.1）
    // ------------------------------------------------------------------

    /** 每次启动检查一次镜像源（24 小时节流）；关掉就只能手动点同步 */
    var mirrorAuto: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_AUTO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MIRROR_AUTO, value).apply()
        }

    /**
     * 作者改过图时，是否先删掉自己网盘里的旧版本再转存新的。
     *
     * 删的是**自己账号里转存来的副本**，原作者那份不受影响。默认开 ——
     * 不开的话「作者更新了但我这边一直不动」这个问题就永远解决不了。
     */
    var mirrorReplace: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_REPLACE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MIRROR_REPLACE, value).apply()
        }

    /**
     * 自动检查的间隔（小时，v2.0.1）。默认 24，可选 12 / 24 / 72 / 168。
     *
     * 允许用户调，是因为「作者多久更新一次」差别很大：日更的活动图 12 小时合适，
     * 一份写完就不动的攻略 7 天一次就够，天天查纯属白耗流量。
     */
    var mirrorIntervalHours: Int
        get() = prefs.getInt(KEY_MIRROR_INTERVAL, 24).let { if (it in MIRROR_INTERVALS) it else 24 }
        set(value) {
            prefs.edit().putInt(KEY_MIRROR_INTERVAL, if (value in MIRROR_INTERVALS) value else 24).apply()
        }

    /** 上次自动跑镜像同步的时间（毫秒），按 [mirrorIntervalHours] 节流 */
    var mirrorLastRunAt: Long
        get() = prefs.getLong(KEY_MIRROR_LAST_RUN, 0L)
        set(value) {
            prefs.edit().putLong(KEY_MIRROR_LAST_RUN, value).apply()
        }

    /** 攻略根目录名：登录后先在这个目录下找子文件夹作为分类 */
    var rootFolderName: String
        get() = prefs.getString(KEY_ROOT_FOLDER, null).orEmpty().ifBlank { DEFAULT_ROOT_FOLDER }
        set(value) {
            val trimmed = value.trim().ifBlank { DEFAULT_ROOT_FOLDER }
            prefs.edit().putString(KEY_ROOT_FOLDER, trimmed).apply()
        }

    /**
     * 恢复上次的窗口几何参数。
     * 宽高 <= 0 表示还没保存过，由 Service 按「屏宽 0.8 倍」初始化。
     */
    fun restoreWindow(): WindowGeometry {
        val rawAlpha = prefs.getFloat(KEY_ALPHA, 1f)
        // SharedPreferences 里若被写入 NaN / Inf，coerceIn 会把 NaN 原样带进
        // WindowManager.LayoutParams.alpha，窗口渲染会异常，看起来像「点不动」。
        val alpha = if (rawAlpha.isNaN() || rawAlpha.isInfinite()) {
            1f
        } else {
            rawAlpha.coerceIn(MIN_ALPHA, 1f)
        }
        return WindowGeometry(
            width = prefs.getInt(KEY_W, 0),
            height = prefs.getInt(KEY_H, 0),
            x = prefs.getInt(KEY_X, Int.MIN_VALUE),
            y = prefs.getInt(KEY_Y, Int.MIN_VALUE),
            alpha = alpha
        )
    }

    fun saveWindow(geometry: WindowGeometry) {
        prefs.edit()
            .putInt(KEY_W, geometry.width)
            .putInt(KEY_H, geometry.height)
            .putInt(KEY_X, geometry.x)
            .putInt(KEY_Y, geometry.y)
            .putFloat(KEY_ALPHA, geometry.alpha)
            .apply()
    }

    /** 上次浏览的分类，重启后直接回到它 */
    var lastCategoryId: String?
        get() = prefs.getString(KEY_LAST_CATEGORY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_LAST_CATEGORY, value).apply()
        }

    /** 上次退出时是不是缩成了小球 */
    var collapsed: Boolean
        get() = prefs.getBoolean(KEY_COLLAPSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COLLAPSED, value).apply()
        }

    /**
     * 「小抄」按钮的独立开关：攻略悬浮窗是否显示。默认 false —— 点连招冷启动时
     * 不会把小抄一起挂出来。与 macroWindowShown 各存各的，互不读写。
     */
    var guideWindowShown: Boolean
        get() = prefs.getBoolean(KEY_GUIDE_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GUIDE_SHOWN, value).apply()
        }

    /** 界面主题 key：teal / indigo / jade / rose / twilight / custom */
    var themeKey: String
        get() = prefs.getString(KEY_THEME, "teal")?.takeIf { it.isNotBlank() } ?: "teal"
        set(value) {
            prefs.edit().putString(KEY_THEME, value).apply()
        }

    /**
     * v3.0.2：悬浮窗调色盘里自己调的那颗色（ARGB）。0 = 没调过。
     *
     * 只给「悬浮窗 → 界面主题」用，跟主题设置里那颗 themeCustomAccent 各存各的 ——
     * 一个是窗框配色，一个是主页强调色，混用会出现「改窗框把主页按钮也改了」。
     */
    var overlayCustomColor: Int
        get() = prefs.getInt(KEY_OVERLAY_CUSTOM_COLOR, 0)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_CUSTOM_COLOR, value).apply()
        }

    /**
     * 界面风格：glass（风格 1 液态玻璃）/ liquid（风格 2 夸张液态玻璃）/ material（风格 3 安卓原生）。
     *
     * 只认 key，不认序数 —— 以后插新风格不会把老用户的设置错位。
     */
    var uiStyleKey: String
        get() = prefs.getString(KEY_UI_STYLE, "glass")?.takeIf { it.isNotBlank() } ?: "glass"
        set(value) {
            prefs.edit().putString(KEY_UI_STYLE, value).apply()
        }

    /**
     * 白天 / 黑夜（v1.9.14）：system / light / dark。
     * 与风格分开存 —— 换风格不该把用户选的「黑夜」改回跟随系统。
     */
    var uiModeKey: String
        get() = prefs.getString(KEY_UI_MODE, "system")?.takeIf { it.isNotBlank() } ?: "system"
        set(value) {
            prefs.edit().putString(KEY_UI_MODE, value).apply()
        }

    /**
     * 玻璃质感档位（v1.9.13）：lite / balanced / ultra。
     * 只影响风格四 / 五 两套真玻璃，风格一~三不读它。
     */
    var glassQualityKey: String
        get() = prefs.getString(KEY_GLASS_QUALITY, "balanced")?.takeIf { it.isNotBlank() } ?: "balanced"
        set(value) {
            prefs.edit().putString(KEY_GLASS_QUALITY, value).apply()
        }

    // ------------------------------------------------------------------
    // v2.11.0：主题设置 —— 背景外观三项
    // ------------------------------------------------------------------

    /**
     * 卡片不透明度。1 = 原样，0.35 = 很透（背后壁纸能穿过来）。
     * 只乘在卡片的底色上，不动卡片里的文字，所以调低不会把字也调没。
     */
    var themeCardAlpha: Float
        get() = prefs.getFloat(KEY_CARD_ALPHA, 1f).coerceIn(0.35f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_CARD_ALPHA, value.coerceIn(0.35f, 1f)).apply()
        }

    /**
     * 背景模糊倍数。0 = 完全不糊（玻璃变纯色板），1 = 默认，1.6 = 更糊。
     * 只对真玻璃那两套风格有效，原生风格本来就不糊。
     */
    var themeBackdropBlur: Float
        get() = prefs.getFloat(KEY_BACKDROP_BLUR, 1f).coerceIn(0f, 1.6f)
        set(value) {
            prefs.edit().putFloat(KEY_BACKDROP_BLUR, value.coerceIn(0f, 1.6f)).apply()
        }

    /**
     * 背景遮罩。负 = 变暗，正 = 变亮，0 = 不动。
     * 只盖在背景那一层上，卡片和文字不受影响 —— 跟主题商店里的「背景遮罩」一个意思。
     */
    var themeBackdropScrim: Float
        get() = prefs.getFloat(KEY_BACKDROP_SCRIM, 0f).coerceIn(-0.35f, 0.35f)
        set(value) {
            prefs.edit().putFloat(KEY_BACKDROP_SCRIM, value.coerceIn(-0.35f, 0.35f)).apply()
        }

    /** 强调色 key。auto = 用当前风格自带色（默认，保证升级后不变色） */
    var themeAccentKey: String
        get() = prefs.getString(KEY_ACCENT, "auto")?.takeIf { it.isNotBlank() } ?: "auto"
        set(value) {
            prefs.edit().putString(KEY_ACCENT, value).apply()
        }

    /** 界面文字缩放。1 = 原样 */
    var themeFontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.85f, 1.15f)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SCALE, value.coerceIn(0.85f, 1.15f)).apply()
        }

    // ------------------------------------------------------------------
    // v2.12.0：主题设置按 SukiSU Nova 的分类展开后的新增项
    // ------------------------------------------------------------------

    var themePaletteStyle: String
        get() = prefs.getString(KEY_PALETTE_STYLE, "tonal") ?: "tonal"
        set(value) {
            prefs.edit().putString(KEY_PALETTE_STYLE, value).apply()
        }

    var themeColorSpec: String
        get() = prefs.getString(KEY_COLOR_SPEC, "2025") ?: "2025"
        set(value) {
            prefs.edit().putString(KEY_COLOR_SPEC, value).apply()
        }

    /** 取色得到的自定义强调色；0 = 没取过 */
    var themeCustomAccent: Int
        get() = prefs.getInt(KEY_CUSTOM_ACCENT, 0)
        set(value) {
            prefs.edit().putInt(KEY_CUSTOM_ACCENT, value).apply()
        }

    /** v3.0.2：界面字体文件名；空 = 跟随系统字体 */
    var themeFontKey: String
        get() = prefs.getString(KEY_THEME_FONT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_THEME_FONT, value).apply()
        }

    var themeCardRadius: Float
        get() = prefs.getFloat(KEY_CARD_RADIUS, 1f).coerceIn(0.6f, 1.6f)
        set(value) {
            prefs.edit().putFloat(KEY_CARD_RADIUS, value.coerceIn(0.6f, 1.6f)).apply()
        }

    var themeBorderScale: Float
        get() = prefs.getFloat(KEY_BORDER_SCALE, 1f).coerceIn(0f, 1.6f)
        set(value) {
            prefs.edit().putFloat(KEY_BORDER_SCALE, value.coerceIn(0f, 1.6f)).apply()
        }

    var themeShadowScale: Float
        get() = prefs.getFloat(KEY_SHADOW_SCALE, 1f).coerceIn(0f, 1.6f)
        set(value) {
            prefs.edit().putFloat(KEY_SHADOW_SCALE, value.coerceIn(0f, 1.6f)).apply()
        }

    var themeDividerScale: Float
        get() = prefs.getFloat(KEY_DIVIDER_SCALE, 1f).coerceIn(0f, 1.6f)
        set(value) {
            prefs.edit().putFloat(KEY_DIVIDER_SCALE, value.coerceIn(0f, 1.6f)).apply()
        }

    var themeIconShape: String
        get() = prefs.getString(KEY_ICON_SHAPE, "rounded") ?: "rounded"
        set(value) {
            prefs.edit().putString(KEY_ICON_SHAPE, value).apply()
        }

    var themeHideSubtitle: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SUBTITLE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_SUBTITLE, value).apply()
        }

    var themeBottomDensity: String
        get() = prefs.getString(KEY_BOTTOM_DENSITY, "normal") ?: "normal"
        set(value) {
            prefs.edit().putString(KEY_BOTTOM_DENSITY, value).apply()
        }

    var themeShowSliderValue: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SLIDER_VALUE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_SLIDER_VALUE, value).apply()
        }

    var themeProgressiveScrim: Boolean
        get() = prefs.getBoolean(KEY_PROGRESSIVE_SCRIM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROGRESSIVE_SCRIM, value).apply()
        }

    /** "" = 程序壁纸；"system" = 系统桌面壁纸；其他 = 图片路径 */
    var themeBackgroundKey: String
        get() = prefs.getString(KEY_BACKGROUND_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_BACKGROUND_KEY, value).apply()
        }

    /**
     * v3.3.0：导航方式。drawer = 现在这套（顶栏菜单 + 侧滑抽屉）；
     * dock = 底部液态玻璃胶囊，四个大入口常驻（就是主题商店那种界面）。
     */
    var navStyleKey: String
        get() = prefs.getString(KEY_NAV_STYLE, "drawer")?.takeIf { it.isNotBlank() } ?: "drawer"
        set(value) {
            prefs.edit().putString(KEY_NAV_STYLE, value).apply()
        }

    /** v3.3.0：当前应用的主题包 id（只用来在商店里标一个「正在用」，不参与外观计算） */
    var themePackId: String
        get() = prefs.getString(KEY_THEME_PACK, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_THEME_PACK, value).apply()
        }

    /** v3.4.2：桌面图标（classic / new / round / photo），对应清单里的四个入口 */
    var appIconKey: String
        get() = prefs.getString(KEY_APP_ICON, "classic")?.takeIf { it.isNotBlank() } ?: "classic"
        set(value) {
            prefs.edit().putString(KEY_APP_ICON, value).apply()
        }

    /**
     * v3.4.2：界面文本自定义。JSON 对象，如 {"app.title":"云小抄","dock.home":"小抄"}。
     * 存字符串而不是拆成一堆布尔/文本字段，是因为键会继续增加，拆开会把 prefs 撑爆。
     */
    var themeCustomTexts: String
        get() = prefs.getString(KEY_CUSTOM_TEXTS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_TEXTS, value).apply()
        }

    /**
     * v3.4.2：用户手动指定的底栏图标。JSON 对象，key ∈ home/superuser/module/setting，
     * value = 私有目录里的图片绝对路径。优先级高于主题包自带的图标。
     */
    var themeDockIcons: String
        get() = prefs.getString(KEY_DOCK_ICONS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DOCK_ICONS, value).apply()
        }

    /**
     * v3.4.9：页面列表（抽屉）每项的图标。JSON：key → 私有目录图片路径。
     * key 与抽屉条目一一对应（home / macro / aim / parse / perm / store / mine）。
     */
    var drawerIcons: String
        get() = prefs.getString(KEY_DRAWER_ICONS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DRAWER_ICONS, value).apply()
        }

    /**
     * v3.4.6：应用显示名称。留空 = 「云小抄」。
     *
     * 只改应用内看得到的名字（抽屉头部、关于页、桌面快捷方式）。
     * Android 不允许已安装应用在运行时改自己的 android:label —— 想换桌面上的名字，
     * 用「应用图标 → 导入图片做桌面图标」生成的快捷方式，那个名字可以随便写。
     */
    var appDisplayName: String
        get() = prefs.getString(KEY_APP_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_APP_NAME, value.trim()).apply()
        }

    /** 应用名（空则默认「云小抄」） */
    fun appName(): String = appDisplayName.takeIf { it.isNotBlank() } ?: "云小抄"

    // ------------------------------------------------------------------
    // 攻略来源（v1.7.0）：网盘 / 本地文件夹
    // ------------------------------------------------------------------

    /** 当前攻略来源：CLOUD（默认，保持旧行为）或 LOCAL */
    var guideSource: GuideSource
        get() = GuideSource.fromKey(prefs.getString(KEY_SOURCE, null))
        set(value) {
            prefs.edit().putString(KEY_SOURCE, value.name).apply()
        }

    /**
     * 链接小抄的分享链接（v2.0.2）。
     *
     * 存原始文本（可能是一整段带口令的分享文案，不是纯 URL）——
     * `ShareLinkParser` 本来就吃这种格式，存规整后的 URL 反而会丢口令。
     */
    var linkShare: String?
        get() = prefs.getString(KEY_LINK_SHARE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_LINK_SHARE, value?.takeIf { it.isNotBlank() }).apply()
        }

    /**
     * 链接打开成功后分享的标题（作者给这份分享起的名字）。
     *
     * 只用来显示，不参与任何判定：拿不到就是空，界面退化成「链接小抄」四个字。
     */
    var linkShareTitle: String
        get() = prefs.getString(KEY_LINK_SHARE_TITLE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LINK_SHARE_TITLE, value).apply()
        }

    /** SAF 授权的目录树 Uri（content://.../tree/...）；空 = 还没选本地文件夹 */
    var localTreeUri: String?
        get() = prefs.getString(KEY_LOCAL_TREE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_LOCAL_TREE, value?.takeIf { it.isNotBlank() }).apply()
        }

    /**
     * v2.5.9：本地小抄从树的哪一层开始浏览。
     *
     * 空 = tree 根（老行为）。小抄入口绑定的是 `/小抄/龙宫` 这种子目录时，
     * [localTreeUri] 仍然是 `/小抄`（权限在这层），这里记子目录的 documentId。
     */
    var localRootDocId: String
        get() = prefs.getString(KEY_LOCAL_ROOT_DOC, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LOCAL_ROOT_DOC, value).apply()
        }

    /** 小球图标（用户自选图片的本地路径）；空 = 用默认的「抄」字 */
    var bubbleIconPath: String?
        get() = prefs.getString(KEY_BUBBLE_ICON, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_BUBBLE_ICON, value).apply()
        }

    /** 缩成小球前的透明度，展开时原样恢复 */
    var expandedAlpha: Float
        get() = prefs.getFloat(KEY_EXPANDED_ALPHA, 1f).coerceIn(MIN_ALPHA, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_EXPANDED_ALPHA, value.coerceIn(MIN_ALPHA, 1f)).apply()
        }

    /** 缩成小球前的窗口尺寸，展开时原样恢复 */
    var expandedWidth: Int
        get() = prefs.getInt(KEY_EXPANDED_W, 0)
        set(value) {
            prefs.edit().putInt(KEY_EXPANDED_W, value).apply()
        }

    var expandedHeight: Int
        get() = prefs.getInt(KEY_EXPANDED_H, 0)
        set(value) {
            prefs.edit().putInt(KEY_EXPANDED_H, value).apply()
        }

    // ------------------------------------------------------------------
    // 独立连招悬浮窗（v1.6.0）：开关与它自己的几何，和攻略窗完全分开
    // ------------------------------------------------------------------

    /**
     * 「自动连招」按钮的独立开关：连招层是否显示（窗或球）。默认 false。
     *
     * v1.8.3：与 guideWindowShown 各存各的，冷启动各自按自己的开关独立恢复；
     * 点开小抄不会动它，点开连招也不会动小抄。
     */
    var macroWindowShown: Boolean
        get() = prefs.getBoolean(KEY_MACRO_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MACRO_SHOWN, value).apply()
        }

    /** 连招层是否处于「缩成球」状态。默认 true —— 冷启动只放一颗不打扰的球 */
    var macroCollapsed: Boolean
        get() = prefs.getBoolean(KEY_MACRO_COLLAPSED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MACRO_COLLAPSED, value).apply()
        }

    /** 缩成球之前的连招窗尺寸，点球展开时原样恢复 */
    var macroExpandedW: Int
        get() = prefs.getInt(KEY_MACRO_EXPANDED_W, 0)
        set(value) {
            prefs.edit().putInt(KEY_MACRO_EXPANDED_W, value).apply()
        }

    var macroExpandedH: Int
        get() = prefs.getInt(KEY_MACRO_EXPANDED_H, 0)
        set(value) {
            prefs.edit().putInt(KEY_MACRO_EXPANDED_H, value).apply()
        }

    /** 连招球自己的位置（与连招窗展开位置分开记，两个形态互不覆盖） */
    var macroBubbleX: Int
        get() = prefs.getInt(KEY_MACRO_BUBBLE_X, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_MACRO_BUBBLE_X, value).apply()
        }

    var macroBubbleY: Int
        get() = prefs.getInt(KEY_MACRO_BUBBLE_Y, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_MACRO_BUBBLE_Y, value).apply()
        }

    // ------------------------------------------------------------------
    // v2.2.0 准星层：自由导入图片，图片上的锚点即瞄准点
    // ------------------------------------------------------------------

    /** 准星悬浮层是否显示。与攻略窗 / 连招窗各存各的，互不联动 */
    var crosshairShown: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CROSSHAIR_SHOWN, value).apply()
        }

    /** 用户导入的准星图片本地路径；空 = 还没导入 */
    var crosshairImage: String?
        get() = prefs.getString(KEY_CROSSHAIR_IMAGE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_CROSSHAIR_IMAGE, value?.takeIf { it.isNotBlank() }).apply()
        }

    /**
     * 锚点：图片内哪一点是「瞄准点」，0..1 归一化。
     * 默认正中 (0.5, 0.5)；用户在预览图上点哪里就落在哪里。
     */
    var crosshairAnchorX: Float
        get() = prefs.getFloat(KEY_CROSSHAIR_AX, 0.5f).coerceIn(0f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_CROSSHAIR_AX, value.coerceIn(0f, 1f)).apply()
        }

    var crosshairAnchorY: Float
        get() = prefs.getFloat(KEY_CROSSHAIR_AY, 0.5f).coerceIn(0f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_CROSSHAIR_AY, value.coerceIn(0f, 1f)).apply()
        }

    /** 准星显示宽度（px）；0 = 按图片原始尺寸自动算（不超过屏宽一半） */
    var crosshairWidth: Int
        get() = prefs.getInt(KEY_CROSSHAIR_W, 0).coerceAtLeast(0)
        set(value) {
            prefs.edit().putInt(KEY_CROSSHAIR_W, value.coerceAtLeast(0)).apply()
        }

    /** 准星不透明度 0.15~1，默认 1 */
    var crosshairAlpha: Float
        get() = prefs.getFloat(KEY_CROSSHAIR_ALPHA, 1f)
            .let { if (it.isNaN() || it.isInfinite()) 1f else it.coerceIn(0.15f, 1f) }
        set(value) {
            prefs.edit().putFloat(KEY_CROSSHAIR_ALPHA, value.coerceIn(0.15f, 1f)).apply()
        }

    /** 是否在锚点位置画十字辅助线（图片本身不带准星线时很有用） */
    var crosshairLines: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_LINES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CROSSHAIR_LINES, value).apply()
        }

    /**
     * 锁定位置（点击穿透）。
     *
     * 打开后窗口带 FLAG_NOT_TOUCHABLE，不再吃触摸，可以放心盖在游戏上；
     * 关掉才能长按拖动移位。默认关 —— 第一次用总得先摆位置。
     */
    var crosshairLocked: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_LOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CROSSHAIR_LOCKED, value).apply()
        }

    /**
     * v2.7.3：屏幕宽高对调。
     *
     * 极少数机器（游戏强制横屏、系统 rotation 没跟着变）连 Display 的物理尺寸都会报反，
     * 这时自动识别无能为力。长按「居中显示」切换这一位，等于告诉程序「你横竖看反了」。
     */
    var crosshairSwapScreen: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_SWAP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CROSSHAIR_SWAP, value).apply()
        }

    /** 准星窗口左上角坐标；Int.MIN_VALUE = 没存过，由 Service 居中放 */
    var crosshairX: Int
        get() = prefs.getInt(KEY_CROSSHAIR_X, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_CROSSHAIR_X, value).apply()
        }

    var crosshairY: Int
        get() = prefs.getInt(KEY_CROSSHAIR_Y, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_CROSSHAIR_Y, value).apply()
        }

    // ------------------------------------------------------------------
    // v2.3.0 多套准星
    // ------------------------------------------------------------------

    /** 所有准星配置的 JSON 数组；由 [CrosshairProfiles] 负责解析与写入 */
    var crosshairProfiles: String?
        get() = prefs.getString(KEY_CROSSHAIR_PROFILES, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_CROSSHAIR_PROFILES, value).apply()
        }

    /** 当前选中的准星 id；空 = 还没选过，按列表第一套算 */
    var crosshairActiveId: String
        get() = prefs.getString(KEY_CROSSHAIR_ACTIVE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CROSSHAIR_ACTIVE, value).apply()
        }

    // ------------------------------------------------------------------
    // v1.9.1：点击可视化 / 音量键隐藏
    // ------------------------------------------------------------------

    /**
     * 「点击显示」：录制与执行连招时，在屏幕上画出点击位置的圆点。
     * 默认开 —— 录制时悬浮窗会缩成球，需要看清自己点在哪。
     */
    var showTapIndicator: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TAP, !lowPerfMode)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_TAP, value).apply()
        }

    /**
     * v1.9.16「连招时也能手动操作」。
     *
     * 开（默认）：手一碰屏幕，连招立刻停止注入，你随便点 / 滑；松手 [macroResumeDelayMs]
     * 之后从当前这一步继续。系统会取消「正在进行的手势」，不让路的话你的操作和连招
     * 会互相打断 —— 旧版更糟，你碰一下屏幕连招就直接停了。
     *
     * 关：连招优先，一路点到底，期间屏幕基本交给连招（打桩、快速连点这种场景更快）。
     */
    var macroManualFirst: Boolean
        get() = prefs.getBoolean(KEY_MACRO_MANUAL_FIRST, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MACRO_MANUAL_FIRST, value).apply()
        }

    /**
     * 松手后多久继续（毫秒）。默认 800。
     * 太短会插进你还没做完的操作里，太长会觉得「点完了连招才反应」。
     */
    var macroResumeDelayMs: Int
        get() = prefs.getInt(KEY_MACRO_RESUME_DELAY, MacroRunPolicy.DEFAULT_RESUME_DELAY.toInt())
        set(value) {
            prefs.edit().putInt(KEY_MACRO_RESUME_DELAY, value).apply()
        }

    // ------------------------------------------------------------------
    // v2.5.6 多指并行运行姿态（iQOO / 小米 / OPPO 三家游戏空间连招方案共用）
    // ------------------------------------------------------------------

    /**
     * 运行姿态：parallel（多指并行，默认）/ yield（手动让路）/ priority（连招优先）。
     * 只认 key 不认序数，见 [com.yunx.app.overlay.macro.MacroRunMode]。
     *
     * 默认值改成 parallel 是本次核心修复：旧默认「让路」在你按住摇杆走位时
     * 会一直判定屏幕忙，连招被饿死、指数退让到 4 秒，表现就是「触发了却动不了」。
     */
    var macroRunMode: String
        get() = prefs.getString(KEY_MACRO_RUN_MODE, "parallel")?.takeIf { it.isNotBlank() } ?: "parallel"
        set(value) {
            prefs.edit().putString(KEY_MACRO_RUN_MODE, value).apply()
        }

    /** 并行模式点击脉冲时长（ms），16~80，默认 24 */
    var macroPulseMs: Int
        get() = prefs.getInt(KEY_MACRO_PULSE_MS, 24).coerceIn(16, 80)
        set(value) {
            prefs.edit().putInt(KEY_MACRO_PULSE_MS, value.coerceIn(16, 80)).apply()
        }

    // ------------------------------------------------------------------
    // v2.5.6 帧率悬浮窗（透明 / 不透明可自定义，可换图标）
    // ------------------------------------------------------------------

    /** 帧率悬浮窗是否显示，默认关 */
    var fpsShown: Boolean
        get() = prefs.getBoolean(KEY_FPS_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FPS_SHOWN, value).apply()
        }

    /** 整体不透明度 0.2~1，默认 1（不透明） */
    var fpsAlpha: Float
        get() = prefs.getFloat(KEY_FPS_ALPHA, 1f)
            .let { if (it.isNaN() || it.isInfinite()) 1f else it.coerceIn(0.2f, 1f) }
        set(value) {
            prefs.edit().putFloat(KEY_FPS_ALPHA, value.coerceIn(0.2f, 1f)).apply()
        }

    /**
     * 背景样式：0=完全透明（无底，纯字）/ 1=半透明磨砂底 / 2=不透明实色底。
     * v2.6.0：默认改成 0。原来默认 1，一开帧率屏幕上就多一块深色圆角底，
     * 在亮色游戏画面上看着就是一个「黑点」；默认纯字 + 描边最干净。
     */
    var fpsBgStyle: Int
        get() = prefs.getInt(KEY_FPS_BG_STYLE, 0).coerceIn(0, 2)
        set(value) {
            prefs.edit().putInt(KEY_FPS_BG_STYLE, value.coerceIn(0, 2)).apply()
        }

    /** 文字颜色 ARGB，0 = 用默认色（跟随背景自动取白/黑），否则用用户选的颜色 */
    var fpsTextColor: Int
        get() = prefs.getInt(KEY_FPS_TEXT_COLOR, 0)
        set(value) {
            prefs.edit().putInt(KEY_FPS_TEXT_COLOR, value).apply()
        }

    /**
     * 帧率窗前缀图标（emoji / 符号）；自定义图片优先于它。
     * v2.6.0：默认空 —— 不再预置 🎮。老版本存过 "🎮" 的也按空处理，
     * 因为部分机型缺彩色 emoji 字体时会把它画成一团黑色剪影（就是那个「黑点」）。
     */
    var fpsIconPreset: String
        get() = (prefs.getString(KEY_FPS_ICON_PRESET, "") ?: "")
            .takeIf { it.isNotBlank() && it != "🎮" }
            .orEmpty()
        set(value) {
            prefs.edit().putString(KEY_FPS_ICON_PRESET, value).apply()
        }

    /** 帧率窗自定义图标图片（app 私有目录绝对路径）；空 = 用内置 emoji */
    var fpsIconPath: String?
        get() = prefs.getString(KEY_FPS_ICON_PATH, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_FPS_ICON_PATH, value?.takeIf { it.isNotBlank() }).apply()
        }

    /** 是否额外显示每帧耗时（ms），默认关 */
    var fpsShowMs: Boolean
        get() = prefs.getBoolean(KEY_FPS_SHOW_MS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FPS_SHOW_MS, value).apply()
        }

    /** 帧率窗左上角坐标；Int.MIN_VALUE = 没存过，由 Service 放右上角 */
    var fpsX: Int
        get() = prefs.getInt(KEY_FPS_X, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_FPS_X, value).apply()
        }

    var fpsY: Int
        get() = prefs.getInt(KEY_FPS_Y, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_FPS_Y, value).apply()
        }

    /**
     * 「音量键隐藏」：双击音量键切换所有悬浮窗的显示 / 隐藏。
     * 默认开；关闭后音量键完全不参与任何逻辑。
     * 隐藏状态本身**不落盘**（见 FloatingService），Service 重启后一律恢复显示，
     * 避免用户重启手机后找不到窗口。
     */
    var volumeKeyHide: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_HIDE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VOLUME_HIDE, value).apply()
        }

    fun restoreMacroWindow(): WindowGeometry = WindowGeometry(
        width = prefs.getInt(KEY_MACRO_W, 0),
        height = prefs.getInt(KEY_MACRO_H, 0),
        x = prefs.getInt(KEY_MACRO_X, Int.MIN_VALUE),
        y = prefs.getInt(KEY_MACRO_Y, Int.MIN_VALUE),
        alpha = 1f
    )

    fun saveMacroWindow(geometry: WindowGeometry) {
        prefs.edit()
            .putInt(KEY_MACRO_W, geometry.width)
            .putInt(KEY_MACRO_H, geometry.height)
            .putInt(KEY_MACRO_X, geometry.x)
            .putInt(KEY_MACRO_Y, geometry.y)
            .apply()
    }

    data class WindowGeometry(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val alpha: Float
    )

    companion object {
        const val DEFAULT_ROOT_FOLDER = "我的小抄"
        const val MIN_ALPHA = 0.2f

        private const val KEY_ROOT_FOLDER = "root_folder"
        private const val KEY_MIRROR_AUTO = "mirror_auto"
        private const val KEY_MIRROR_REPLACE = "mirror_replace"
        private const val KEY_MIRROR_LAST_RUN = "mirror_last_run"
        private const val KEY_MIRROR_INTERVAL = "mirror_interval_hours"

        /** 可选的检查间隔（小时）。放常量里，UI 与读取侧共用同一份，改一处就够 */
        val MIRROR_INTERVALS = intArrayOf(12, 24, 72, 168)
        private const val KEY_W = "window_w"
        private const val KEY_H = "window_h"
        private const val KEY_X = "window_x"
        private const val KEY_Y = "window_y"
        private const val KEY_ALPHA = "window_alpha"
        private const val KEY_LAST_CATEGORY = "last_category"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_GUIDE_SHOWN = "guide_window_shown"
        private const val KEY_BUBBLE_ICON = "bubble_icon"
        private const val KEY_THEME = "ui_theme"
        // v3.0.2：悬浮窗调色盘里的自选色（ARGB，0 = 没选过）
        private const val KEY_OVERLAY_CUSTOM_COLOR = "ui_theme_custom_color"
        private const val KEY_UI_STYLE = "ui_style"
        private const val KEY_UI_MODE = "ui_mode"
        private const val KEY_GLASS_QUALITY = "glass_quality"

        // v2.11.0：主题设置里的「背景」三项
        private const val KEY_CARD_ALPHA = "theme_card_alpha"
        private const val KEY_BACKDROP_BLUR = "theme_backdrop_blur"
        private const val KEY_BACKDROP_SCRIM = "theme_backdrop_scrim"
        private const val KEY_ACCENT = "theme_accent"
        private const val KEY_FONT_SCALE = "theme_font_scale"
        // v3.0.2：界面字体（私有目录里的文件名，空 = 跟系统）
        private const val KEY_THEME_FONT = "theme_font"
        private const val KEY_PALETTE_STYLE = "theme_palette_style"
        private const val KEY_COLOR_SPEC = "theme_color_spec"
        private const val KEY_CUSTOM_ACCENT = "theme_custom_accent"
        private const val KEY_CARD_RADIUS = "theme_card_radius"
        private const val KEY_BORDER_SCALE = "theme_border_scale"
        private const val KEY_SHADOW_SCALE = "theme_shadow_scale"
        private const val KEY_DIVIDER_SCALE = "theme_divider_scale"
        private const val KEY_ICON_SHAPE = "theme_icon_shape"
        private const val KEY_HIDE_SUBTITLE = "theme_hide_subtitle"
        private const val KEY_BOTTOM_DENSITY = "theme_bottom_density"
        private const val KEY_SHOW_SLIDER_VALUE = "theme_show_slider_value"
        private const val KEY_PROGRESSIVE_SCRIM = "theme_progressive_scrim"
        private const val KEY_BACKGROUND_KEY = "theme_background_key"
        // v3.3.0：导航方式（drawer / dock）
        private const val KEY_NAV_STYLE = "nav_style"
        // v3.3.0：正在用的主题包
        private const val KEY_THEME_PACK = "theme_pack_id"
        // v3.4.2：桌面图标 / 界面文本 / 底栏自定义图标
        private const val KEY_APP_ICON = "app_icon_key"
        private const val KEY_CUSTOM_TEXTS = "theme_custom_texts"
        private const val KEY_DOCK_ICONS = "theme_dock_icons"
        private const val KEY_DRAWER_ICONS = "drawer_custom_icons"
        private const val KEY_APP_NAME = "app_display_name"
        private const val KEY_EXPANDED_W = "expanded_w"
        private const val KEY_EXPANDED_H = "expanded_h"
        private const val KEY_EXPANDED_ALPHA = "expanded_alpha"
        private const val KEY_MACRO_SHOWN = "macro_window_shown"
        private const val KEY_MACRO_W = "macro_window_w"
        private const val KEY_MACRO_H = "macro_window_h"
        private const val KEY_MACRO_X = "macro_window_x"
        private const val KEY_MACRO_Y = "macro_window_y"
        private const val KEY_MACRO_COLLAPSED = "macro_collapsed"
        private const val KEY_MACRO_EXPANDED_W = "macro_expanded_w"
        private const val KEY_MACRO_EXPANDED_H = "macro_expanded_h"
        private const val KEY_MACRO_BUBBLE_X = "macro_bubble_x"
        private const val KEY_MACRO_BUBBLE_Y = "macro_bubble_y"
        private const val KEY_SOURCE = "guide_source"
        private const val KEY_LOCAL_TREE = "local_tree_uri"
    private const val KEY_LOCAL_ROOT_DOC = "local_root_doc_id"
        private const val KEY_LINK_SHARE = "link_share_text"
        private const val KEY_LINK_SHARE_TITLE = "link_share_title"
        private const val KEY_SHOW_TAP = "show_tap_indicator"
        private const val KEY_MACRO_MANUAL_FIRST = "macro_manual_first"
        private const val KEY_MACRO_RESUME_DELAY = "macro_resume_delay_ms"
        // v2.5.6 多指并行
        private const val KEY_MACRO_RUN_MODE = "macro_run_mode"
        private const val KEY_MACRO_PULSE_MS = "macro_pulse_ms"
        // v2.5.6 帧率悬浮窗
        private const val KEY_FPS_SHOWN = "fps_shown"
        private const val KEY_FPS_ALPHA = "fps_alpha"
        private const val KEY_FPS_BG_STYLE = "fps_bg_style"
        private const val KEY_FPS_TEXT_COLOR = "fps_text_color"
        private const val KEY_FPS_ICON_PRESET = "fps_icon_preset"
        private const val KEY_FPS_ICON_PATH = "fps_icon_path"
        private const val KEY_FPS_SHOW_MS = "fps_show_ms"
        private const val KEY_FPS_X = "fps_x"
        private const val KEY_FPS_Y = "fps_y"
        private const val KEY_LOW_PERF = "low_perf_mode"
        private const val KEY_HIGH_PERF = "high_perf_mode"
        private const val KEY_IMAGE_VAULT = "image_vault_enabled"
        private const val KEY_IMAGE_TREE = "image_vault_tree"
        private const val KEY_IMAGE_COMPRESS = "image_compress"
        private const val KEY_IMAGE_PACK = "image_pack_enabled"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_IMAGE_MAX_EDGE = "image_max_edge"
        private const val KEY_IMAGE_WIFI_ONLY = "image_wifi_only"
        private const val KEY_IMAGE_AUTO_CHECK = "image_auto_check"
        private const val KEY_IMAGE_LAST_CHECK = "image_last_check"
        private const val KEY_IMAGE_DEDUP = "image_dedup_enabled"
        private const val KEY_IMAGE_MAX_BYTES = "image_max_bytes"
        private const val KEY_IMAGE_LAST_MAINTAIN = "image_last_maintain"
        private const val KEY_VOLUME_HIDE = "volume_key_hide"
        private const val KEY_CROSSHAIR_SHOWN = "crosshair_shown"
        private const val KEY_CROSSHAIR_IMAGE = "crosshair_image"
        private const val KEY_CROSSHAIR_AX = "crosshair_anchor_x"
        private const val KEY_CROSSHAIR_AY = "crosshair_anchor_y"
        private const val KEY_CROSSHAIR_W = "crosshair_width"
        private const val KEY_CROSSHAIR_ALPHA = "crosshair_alpha"
        private const val KEY_CROSSHAIR_LINES = "crosshair_lines"
        private const val KEY_CROSSHAIR_LOCKED = "crosshair_locked"
        private const val KEY_CROSSHAIR_SWAP = "crosshair_swap_screen"
        private const val KEY_CROSSHAIR_X = "crosshair_x"
        private const val KEY_CROSSHAIR_Y = "crosshair_y"
        // v2.3.0：多套准星。列表存 JSON，上面那组 crosshair_* 依然是「当前生效」的快照，
        // Service 只读快照，切套时由界面把选中的那套写回来 —— Service 一行都不用改。
        private const val KEY_CROSSHAIR_PROFILES = "crosshair_profiles"
        private const val KEY_CROSSHAIR_ACTIVE = "crosshair_active_id"
    }
}
