/*
 * 云小抄（YunX 衍生） - 本地小抄 · 资源链接配置
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。为「获取资源链接」卡片提供可热更新的链接来源。
 *
 * 设计前提：网盘分享链接会失效，而每换一次链接就重新打包发版是最笨的做法。
 * 所以链接分两层取：
 *   1. 远程配置文件（[CONFIG_URL]）—— 改一行文本即生效，**不用发新版**；
 *   2. 包内兜底常量（[DEFAULT_URL]）—— 首次启动没网、或远程配置还没部署时显示它。
 *
 * 只做「取一段文本并缓存」，不解析网盘、不校验可用性、不碰任何目录结构。
 */

package com.yunx.app.overlay.data

import android.content.Context
import android.util.Log
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ResourceLinkStore {

    private const val TAG = "YunX-ResourceLink"
    private const val SP = "resource_link_cfg"
    private const val KEY_URL = "url"
    private const val KEY_AT = "fetched_at"

    /**
     * ★ 包内兜底链接：远程配置拉不到时显示这个。
     *
     * 换成你的「展十小抄源总链接」即可。注意这只是兜底 —— 真正想做到「换链接不发版」，
     * 要改的是下面的 [CONFIG_URL] 指向的那个文本文件，不是这里。
     */
    const val DEFAULT_URL = "https://pan.quark.cn/s/50c7c208bb2f"

    /**
     * ★ 远程配置地址：一个纯文本文件，文件里只放一行链接。
     *
     * 支持两种写法，随便哪种都行：
     *   ① 纯文本：`https://pan.quark.cn/s/abcdef123456`
     *   ② JSON  ：`{"url":"https://pan.quark.cn/s/abcdef123456","note":"2026-11 更新"}`
     *
     * 部署一次之后，以后换链接 = 改这个文件的内容，客户端下次进「本地」页就自动生效。
     * 常见托管方式任选一个：GitHub Gist / GitHub 仓库 raw / 自己的静态空间 / 对象存储。
     *
     * ⚠️ 必须是 **https** —— 本项目的 network_security_config 全局禁止明文 HTTP
     *    （只有公告服务那一个域名开了例外）。用 http 会直接被系统拦掉，静默失败。
     *
     * 拉不到时的行为：静默失败，继续用上一次成功的缓存，再不行用 [DEFAULT_URL]。
     * 也就是说这个地址填错不会让用户看到报错，只是热更新不生效。
     */
    const val CONFIG_URL = "https://raw.githubusercontent.com/yunx-guide/cfg/main/resource_link.txt"

    /** 两次拉取的间隔下限：进页面就会触发一次，来回切页不该反复打服务器 */
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** 当前该显示的链接：远程缓存 > 包内兜底 */
    fun current(context: Context): String {
        val cached = prefs(context).getString(KEY_URL, null)
        return cached?.takeIf { it.startsWith("http") } ?: DEFAULT_URL
    }

    /** 上次尝试拉取远程配置的时间，0 表示从未试过 */
    fun fetchedAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    /**
     * 本机直接替换链接（长按卡片触发）。
     *
     * 存在意义：远程配置文件还没部署时，这是唯一「不发版也能换链接」的路。
     * 只改这一台设备，别人不受影响；一旦远程配置可用，下次刷新会被远程值覆盖
     * —— 这是刻意的优先级：远程管全员，本机只管自己。
     */
    fun saveLocal(context: Context, url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http")) return false
        prefs(context).edit().putString(KEY_URL, u).apply()
        return true
    }

    /** 抹掉本机覆盖值，回到远程配置 / 包内兜底 */
    fun resetLocal(context: Context) {
        prefs(context).edit().remove(KEY_URL).remove(KEY_AT).apply()
    }

    /**
     * 静默刷新远程配置。
     *
     * @param force true = 忽略间隔限制，强制拉一次（手动刷新用）
     * @return 是否真的更新到了新链接
     *
     * 刻意不抛异常：这条链路是「有更好、没有也能用」，任何失败都只是保持原值。
     */
    suspend fun refresh(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = fetchedAt(context)
        if (!force && last > 0 && now - last < MIN_INTERVAL_MS) return@withContext false

        // 失败也把时间写回去：配置地址没部署时会是 404，不记时间就等于「每次进页面都打一次」，
        // 白耗流量还刷日志。10 分钟后再试一次，这个频率够了。
        val text = runCatching { fetch() }.getOrElse { e ->
            Log.w(TAG, "refresh failed: ${e.message}")
            prefs(context).edit().putLong(KEY_AT, now).apply()
            return@withContext false
        } ?: run {
            prefs(context).edit().putLong(KEY_AT, now).apply()
            return@withContext false
        }

        val url = parse(text) ?: run {
            Log.w(TAG, "remote config has no usable url")
            return@withContext false
        }

        // 拉到了但和现在一样：只记时间，不写值，省掉一次无意义的界面刷新
        val old = prefs(context).getString(KEY_URL, null)
        prefs(context).edit()
            .putLong(KEY_AT, now)
            .apply { if (url != old) putString(KEY_URL, url) }
            .apply()

        if (url != old) Log.i(TAG, "resource link updated")
        url != old
    }

    /** 短超时客户端：只复用一个实例，别为了一次读文本反复 newBuilder */
    @Volatile
    private var client: OkHttpClient? = null

    private fun client(): OkHttpClient =
        client ?: HttpClients.apiClient().newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
            .also { client = it }

    /** 真去拉文件；超时给得很短 —— 这是进页面顺带做的事，不能拖慢界面 */
    private fun fetch(): String? {
        val req = Request.Builder()
            .url(CONFIG_URL)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "http ${resp.code}")
                return null
            }
            // 配置文件很小，整包读进来就够；限制长度避免误指到别的东西上
            val body = resp.body?.string() ?: return null
            return body.take(4096)
        }
    }

    /**
     * 从文件内容里抠出链接。容错做到「不挑格式」：
     * JSON 走正则取 url 字段，纯文本取第一个 http 开头的 token。
     * 这样以后想改成带备注、带更新时间的 JSON 也不用动客户端。
     */
    private fun parse(raw: String): String? {
        val s = raw.trim().removePrefix("\uFEFF")
        if (s.isEmpty()) return null

        if (s.startsWith("{")) {
            Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(s)?.let { m ->
                return m.groupValues[1].trim().takeIf { it.startsWith("http") }
            }
            return null
        }

        // 纯文本：允许带一行注释、允许末尾有换行
        Regex("https?://\\S+").find(s)?.let { m ->
            // 去掉粘贴时常见的尾随标点
            return m.value.trimEnd(',', ';', ')', ']', '。', '，')
        }
        return null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
}
