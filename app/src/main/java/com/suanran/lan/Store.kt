package com.suanran.lan

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

/**
 * 收藏 / 历史 / 设置 / 会话标签，全部存在 SharedPreferences，v0.1 不上数据库。
 */
object Store {

    private const val PREFS = "lan"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_HISTORY = "history"
    private const val KEY_ADBLOCK = "adblock"
    private const val KEY_DESKTOP_UA = "desktop_ua"
    private const val KEY_TABS = "tabs"
    private const val KEY_ACTIVE_TAB = "active_tab"
    private const val KEY_HOME_STYLE = "home_style"
    private const val KEY_CUSTOM_HOME = "custom_home"
    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_SHOW_RECENT = "show_recent"
    private const val KEY_SHOW_TILES = "show_tiles"
    private const val KEY_SMART_PLAYER = "smart_player"
    private const val KEY_PLAY_MODE = "play_mode"
    private const val KEY_LONGPRESS_SPEED = "longpress_speed"
    private const val KEY_SKIP_SECONDS = "skip_seconds"
    private const val KEY_CHECK_PLAYABLE = "check_playable"
    private const val KEY_MEDIA_ASSISTANT = "media_assistant"
    private const val KEY_SHOW_TOOLBOX = "show_toolbox"
    private const val KEY_FLOAT_GESTURE = "float_gesture"
    private const val KEY_FLOAT_AUTOHIDE = "float_autohide"
    private const val KEY_SMART_AUTOPLAY = "smart_autoplay"
    private const val KEY_AUTO_OPEN_SITE = "auto_open_site_"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    private const val KEY_DEV_MODE = "ext_dev_mode"
    private const val KEY_AUTO_UPDATE_EXT = "ext_auto_update"

    /** 开发者模式（默认开）：开才能从链接/粘贴安装用户脚本 */
    fun devMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEV_MODE, true)

    fun setDevMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEV_MODE, enabled).apply()
    }

    /** 自动更新扩展（默认关）：开则启动时重拉从链接装的脚本 */
    fun autoUpdateExt(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_EXT, false)

    fun setAutoUpdateExt(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_EXT, enabled).apply()
    }

    /** 上次自动检查更新的时间戳（毫秒），每天最多自动查一次 */
    fun lastUpdateCheck(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0)

    fun setLastUpdateCheck(context: Context, time: Long) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, time).apply()
    }

    /**
     * 主页风格（览原创 7 套）：wulan 雾蓝 / moye 墨夜 / xingye 星野 / qingci 青瓷 /
     * mushan 暮山 / zhiyuan 纸鸢 / subai 素白。
     * 0.3.x 的旧风格名（照参考起的）升级时自动映射到新名。
     */
    fun homeStyle(context: Context): String {
        val raw = prefs(context).getString(KEY_HOME_STYLE, "wulan") ?: "wulan"
        return when (raw) {
            "yuanzi" -> "wulan"
            "code" -> "moye"
            "jiuyu" -> "zhiyuan"
            "xinni" -> "qingci"
            "cuxian" -> "mushan"
            "guge", "jianyue" -> "subai"
            "wulan", "moye", "xingye", "qingci", "mushan", "zhiyuan", "subai" -> raw
            else -> "wulan"
        }
    }

    fun setHomeStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_HOME_STYLE, style).apply()
    }

    /** 自定义主页网址，空 = 内置起始页 */
    fun customHome(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_HOME, "") ?: ""

    fun setCustomHome(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_HOME, url.trim()).apply()
    }

    /** 搜索引擎：bing / baidu / google */
    fun searchEngine(context: Context): String =
        prefs(context).getString(KEY_SEARCH_ENGINE, "bing") ?: "bing"

    fun setSearchEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_SEARCH_ENGINE, engine).apply()
    }

    fun showRecent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_RECENT, true)

    fun setShowRecent(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_RECENT, show).apply()
    }

    fun showTiles(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_TILES, true)

    fun setShowTiles(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_TILES, show).apply()
    }

    // ---- 视频播放 ----
    /** 智能播放器：嗅探到的视频推给原生播放器（只播不加密的） */
    fun smartPlayerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_PLAYER, true)

    fun setSmartPlayerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_PLAYER, enabled).apply()
    }

    /** 播放显示模式：sheet 智能播放器弹窗 / fullscreen 全屏 / floating 悬浮小窗 */
    fun playMode(context: Context): String =
        prefs(context).getString(KEY_PLAY_MODE, "sheet") ?: "sheet"

    fun setPlayMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_PLAY_MODE, mode).apply()
    }

    /** 长按倍速 */
    fun longPressSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_LONGPRESS_SPEED, 2.0f)

    fun setLongPressSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_LONGPRESS_SPEED, speed).apply()
    }

    /** 双击快进/快退秒数 */
    fun skipSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SKIP_SECONDS, 10)

    fun setSkipSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_SKIP_SECONDS, seconds).apply()
    }

    /** 弹出前先校验能不能播（约 2 秒） */
    fun checkPlayableEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_PLAYABLE, true)

    fun setCheckPlayableEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_PLAYABLE, enabled).apply()
    }

    /** 媒体助手：全屏手势（双击快进退、长按倍速、滑动调亮度音量、横滑进度） */
    fun mediaAssistantEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEDIA_ASSISTANT, true)

    fun setMediaAssistantEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEDIA_ASSISTANT, enabled).apply()
    }

    /** 默认展示工具箱（倍速档位 + 下载） */
    fun showToolboxEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_TOOLBOX, true)

    fun setShowToolboxEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_TOOLBOX, enabled).apply()
    }

    /** 悬浮小窗：滑动调节亮度与声音 */
    fun floatGestureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOAT_GESTURE, false)

    fun setFloatGestureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOAT_GESTURE, enabled).apply()
    }

    /** 悬浮小窗：自动隐藏控制器 */
    fun floatAutohideEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOAT_AUTOHIDE, true)

    fun setFloatAutohideEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOAT_AUTOHIDE, enabled).apply()
    }

    /** 智能播放器弹窗：打开就自动播放 */
    fun smartAutoplayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_AUTOPLAY, true)

    fun setSmartAutoplayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_AUTOPLAY, enabled).apply()
    }

    /** 此网站自动打开智能播放器（按 host 记） */
    fun autoOpenSiteEnabled(context: Context, host: String): Boolean =
        prefs(context).getBoolean(KEY_AUTO_OPEN_SITE + host, false)

    fun setAutoOpenSiteEnabled(context: Context, host: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_OPEN_SITE + host, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 收藏 ----
    fun loadBookmarks(context: Context): MutableList<Bookmark> {
        val out = mutableListOf<Bookmark>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY_BOOKMARKS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Bookmark(o.optString("title"), o.optString("url")))
            }
        } catch (e: Exception) {
            // 损坏就清空重来，不崩
        }
        return out
    }

    fun saveBookmarks(context: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().put("title", b.title).put("url", b.url))
        }
        prefs(context).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    fun isBookmarked(context: Context, url: String): Boolean =
        loadBookmarks(context).any { it.url == url }

    // ---- 历史（最多 200 条） ----
    fun loadHistory(context: Context): MutableList<Bookmark> {
        val out = mutableListOf<Bookmark>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY_HISTORY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Bookmark(o.optString("title"), o.optString("url")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun addHistory(context: Context, title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        val list = loadHistory(context)
        if (list.firstOrNull()?.url == url) return
        list.removeAll { it.url == url }
        list.add(0, Bookmark(if (title.isBlank()) url else title, url))
        while (list.size > 200) list.removeAt(list.size - 1)
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("title", b.title).put("url", b.url))
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().putString(KEY_HISTORY, "[]").apply()
    }

    // ---- 设置 ----
    fun adBlockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADBLOCK, true)

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADBLOCK, enabled).apply()
    }

    fun desktopUaEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESKTOP_UA, false)

    fun setDesktopUaEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESKTOP_UA, enabled).apply()
    }

    // ---- 会话标签 ----
    fun saveTabs(context: Context, urls: List<String>, active: Int) {
        val arr = JSONArray()
        for (u in urls) arr.put(u)
        prefs(context).edit()
            .putString(KEY_TABS, arr.toString())
            .putInt(KEY_ACTIVE_TAB, active)
            .apply()
    }

    fun loadTabs(context: Context): Pair<List<String>, Int> {
        return try {
            val arr = JSONArray(prefs(context).getString(KEY_TABS, "[]") ?: "[]")
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list.add(arr.optString(i))
            list to prefs(context).getInt(KEY_ACTIVE_TAB, 0)
        } catch (e: Exception) {
            emptyList<String>() to 0
        }
    }
}
