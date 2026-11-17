package com.suanran.lan

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * 览 v0.1.0 —— 系统 WebView（Chrome 内核 Blink + V8）壳浏览器。
 * 功能：地址栏/搜索、多标签、收藏、历史、桌面版 UA、基础去广告、下载、全屏视频、文件上传。
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val HOME_URL = "file:///android_asset/home.html"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private class Tab(val webView: WebView) {
        var title: String = "新标签页"
        var url: String = HOME_URL
    }

    private lateinit var container: FrameLayout
    private lateinit var etAddress: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnTabs: Button
    private lateinit var btnBookmark: Button

    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null
    private var defaultUa: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** 视频嗅探：每标签页抓到的直链（m3u8/mp4 等） */
    private val sniffedByTab = mutableMapOf<Tab, MutableList<SniffedVideo>>()

    /** 每标签页是否只抓到过 HLS 分片（分片不进列表，见 addSniffed） */
    private val segmentSeenByTab = HashMap<Tab, Boolean>()
    private lateinit var sniffBtn: Button

    /** 智能播放器弹窗 + 分站自动打开去重 */
    private var smartSheet: SmartPlayerSheet? = null
    private var downloaderSheet: SmartDownloaderSheet? = null

    /** 每个标签页当前有没有视频在播：桥按 WebView 回报，只在播的时候才露下载器入口 */
    private val videoPlayingByTab = HashMap<Tab, Boolean>()
    private var lastAutoOpenedPage: String = ""

    /** 应用内悬浮小窗播放器 */
    private var floatOverlay: FrameLayout? = null
    private var floatBall: TextView? = null
    private var floatPlayer: MediaPlayer? = null
    private var floatTexture: TextureView? = null
    private var floatUrl: String = ""
    private var floatTitle: String = ""
    private var floatControls: LinearLayout? = null
    private val floatHandler = Handler(Looper.getMainLooper())
    private val floatHideRunnable = Runnable {
        floatControls?.visibility = View.GONE
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileChooserCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.webContainer)
        etAddress = findViewById(R.id.etAddress)
        progressBar = findViewById(R.id.progressBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnTabs = findViewById(R.id.btnTabs)
        btnBookmark = findViewById(R.id.btnBookmark)

        btnBack.setOnClickListener { activeTab?.webView?.let { if (it.canGoBack()) it.goBack() } }
        btnForward.setOnClickListener { activeTab?.webView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<Button>(R.id.btnGo).setOnClickListener { activeTab?.webView?.reload() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.btnHome).setOnClickListener { activeTab?.webView?.loadUrl(homeUrl()) }
        findViewById<Button>(R.id.btnNewTab).setOnClickListener { newTab(homeUrl(), true) }

        // 智能下载器悬浮入口：玻璃质感，可拖动，松手磁吸到最近的边；
        // 只在页面视频正在播、且抓到直链时出现，不固定在页面上
        sniffBtn = Button(this).apply {
            text = "下载"
            visibility = View.GONE
            setBackgroundResource(R.drawable.glass_btn)
            setTextColor(0xFF0E7490.toInt())
            elevation = 12f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply { setMargins(0, 0, 24, 24) }
            var downRawX = 0f
            var downRawY = 0f
            var startRight = 0
            var startBottom = 0
            var moved = false
            setOnTouchListener { v, event ->
                val lp = v.layoutParams as FrameLayout.LayoutParams
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startRight = lp.rightMargin
                        startBottom = lp.bottomMargin
                        moved = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                        if (moved) {
                            val maxRight = (container.width - v.width - 12).coerceAtLeast(12)
                            val maxBottom = (container.height - v.height - 12).coerceAtLeast(12)
                            lp.rightMargin = (startRight - dx).toInt().coerceIn(12, maxRight)
                            lp.bottomMargin = (startBottom - dy).toInt().coerceIn(12, maxBottom)
                            v.layoutParams = lp
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            performClick()
                            openDownloader()
                        } else {
                            // 磁吸：贴到最近的左右边
                            val left = container.width - lp.rightMargin - v.width
                            lp.rightMargin = if (left + v.width / 2 < container.width / 2) {
                                container.width - v.width - 12
                            } else {
                                12
                            }
                            v.layoutParams = lp
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        container.addView(sniffBtn)
        btnTabs.setOnClickListener { showTabsDialog() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnBookmark.setOnLongClickListener { showBookmarksDialog(); true }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { showHistoryDialog() }

        etAddress.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (go) {
                navigate(etAddress.text.toString())
                etAddress.clearFocus()
                true
            } else false
        }
        etAddress.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etAddress.selectAll() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = activeTab?.webView
                when {
                    customView != null -> hideCustomView()
                    wv != null && wv.canGoBack() -> wv.goBack()
                    tabs.size > 1 -> closeCurrentTab()
                    else -> finish()
                }
            }
        })

        // 恢复上次会话，没有就开首页
        val (savedUrls, savedActive) = Store.loadTabs(this)
        if (savedUrls.isNotEmpty()) {
            savedUrls.forEachIndexed { index, url -> newTab(url, index == savedActive) }
        } else {
            intent?.data?.toString()?.let { newTab(it, true) } ?: newTab(homeUrl(), true)
        }
        if (activeTab == null && tabs.isNotEmpty()) showTab(tabs[0])

        AppLog.i(this, "启动", "览启动")
        // 每天自动检查一次更新（静默，有更新才弹框）
        val now = System.currentTimeMillis()
        if (now - Store.lastUpdateCheck(this) > 24 * 60 * 60 * 1000L) {
            Store.setLastUpdateCheck(this, now)
            UpdateChecker.check(this, silent = true)
        }
        // 扩展自动更新开了：启动时重拉从链接装的脚本
        if (Store.autoUpdateExt(this)) {
            Thread {
                val n = ExtensionManager.updateFromUrls(this)
                if (n > 0) AppLog.i(this, "扩展", "启动时更新了 $n 个脚本")
            }.start()
        }
    }

    /** 首页地址：自定义主页优先，否则内置起始页 */
    private fun homeUrl(): String {
        val custom = Store.customHome(this)
        return if (custom.isNotBlank()) custom else HOME_URL
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        activeTab?.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        activeTab?.webView?.onResume()
    }

    override fun onDestroy() {
        smartSheet?.dismiss()
        downloaderSheet?.dismiss()
        menuSheet?.dismiss()
        releaseFloatPlayer()
        saveSession()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    private fun saveSession() {
        if (tabs.isEmpty()) return
        Store.saveTabs(
            this,
            tabs.map { it.webView.url ?: it.url },
            tabs.indexOf(activeTab).coerceAtLeast(0)
        )
    }

    // ---------- 标签 ----------

    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        if (defaultUa == null) defaultUa = settings.userAgentString
        applyUa(wv)
        wv.addJavascriptInterface(LanBridge(), "Lan")
        wv.addJavascriptInterface(VideoStateBridge(wv), "LanVideo")
        // 0.7.1：嗅探桥。hook 脚本把 fetch/XHR 里出现的视频地址报回来，
        // 专门抓 playurl 这类 JSON 接口里回的 play_url（拦截层看不到响应体）。
        wv.addJavascriptInterface(SniffBridge(wv), "LanSniff")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                // 首页内部动作：lan://extensions 打开扩展页，lan://home-settings 打开主页设置，
                // 其余内部动作（视频播放/下载/书签/历史/日志/检查更新）也走这里，不经 JS 桥
                if (uri.scheme == "lan") {
                    when (uri.host) {
                        "extensions" -> startActivity(
                            Intent(this@MainActivity, ExtensionsActivity::class.java)
                        )
                        "home-settings" -> startActivity(
                            Intent(this@MainActivity, HomeSettingsActivity::class.java)
                        )
                        "video-settings" -> startActivity(
                            Intent(this@MainActivity, VideoSettingsActivity::class.java)
                        )
                        "downloads" -> {
                            try {
                                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "打不开系统下载列表", Toast.LENGTH_SHORT).show()
                            }
                        }
                        "bookmarks" -> showBookmarksDialog()
                        "history" -> showHistoryDialog()
                        "log" -> startActivity(
                            Intent(this@MainActivity, LogActivity::class.java)
                        )
                        "check-update" -> UpdateChecker.check(this@MainActivity, silent = false)
                    }
                    return true
                }
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val tab = tabs.find { it.webView == view }
                maybeSniff(tab, request.url.toString())
                if (Store.adBlockEnabled(this@MainActivity) && AdBlock.isAd(request.url.toString())) {
                    return AdBlock.emptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                val tab = tabs.find { it.webView == view }
                if (tab != null) {
                    // 跳到新页面：上页抓到的视频清空，「当前页面视频」只算本页的
                    if (url != tab.url) {
                        sniffedByTab.remove(tab)
                        segmentSeenByTab.remove(tab)
                        videoPlayingByTab[tab] = false
                        if (tab == activeTab) {
                            smartSheet?.dismiss()
                            downloaderSheet?.dismiss()
                            lastAutoOpenedPage = ""
                            updateSniffBtn()
                        }
                    }
                    if (tab == activeTab && !etAddress.hasFocus()) {
                        etAddress.setText(url)
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                val tab = tabs.find { it.webView == view } ?: return
                tab.url = url
                tab.title = view.title ?: url
                AppLog.i(this@MainActivity, "页面", "打开 $url")
                // 首页是本地起始页，不记历史；扩展也不往本地页里注
                if (!url.startsWith("file://")) {
                    Store.addHistory(this@MainActivity, tab.title, url)
                }
                ExtensionManager.inject(view, url)
                scanVideoTags(view)
                if (!url.startsWith("file://")) hookVideoState(view)
                // 0.7.1：每次页面加载完都注入嗅探钩子（SPA 同文档内路由跳转不重载，
                // 钩子挂在 fetch/XHR 原型上，一次注入后续路由也生效；跨域 iframe 靠拦截层兜底）
                if (!url.startsWith("file://")) injectSniffHook(view)
                if (tab == activeTab) updateUi()
                saveSession()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabs.find { it.webView == view } == activeTab) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val tab = tabs.find { it.webView == view } ?: return
                tab.title = title ?: tab.url
                if (tab == activeTab) updateUi()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tab = newTab("about:blank", true)
                val transport = resultMsg.obj as? WebView.WebViewTransport
                transport?.webView = tab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                pickFile.launch("*/*")
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                window.decorView.findViewById<FrameLayout>(android.R.id.content).addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() = hideCustomView()
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        return wv
    }

    private fun newTab(url: String, switchTo: Boolean): Tab {
        val tab = Tab(createWebView())
        tabs.add(tab)
        if (switchTo) showTab(tab)
        // about:blank 留给 onCreateWindow 的 transport 填目标地址，不先加载首页
        if (url != "about:blank") tab.webView.loadUrl(url)
        updateUi()
        return tab
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        container.removeAllViews()
        (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
        container.addView(tab.webView)
        container.addView(sniffBtn)
        updateSniffBtn()
        updateUi()
    }

    private fun closeCurrentTab() {
        val tab = activeTab ?: return
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        sniffedByTab.remove(tab)
        segmentSeenByTab.remove(tab)
        videoPlayingByTab.remove(tab)
        container.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            newTab(homeUrl(), true)
        } else {
            showTab(tabs[(index - 1).coerceAtLeast(0)])
        }
        updateUi()
    }

    // ---------- 导航 ----------

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/\\S*)?$").matches(text) -> "https://$text"
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/\\S*)?$").matches(text) -> "http://$text"
            else -> {
                val q = URLEncoder.encode(text, "UTF-8")
                when (Store.searchEngine(this)) {
                    "baidu" -> "https://www.baidu.com/s?wd=$q"
                    "google" -> "https://www.google.com/search?q=$q"
                    else -> "https://www.bing.com/search?q=$q"
                }
            }
        }
        activeTab?.webView?.loadUrl(url)
    }

    private fun applyUa(wv: WebView) {
        wv.settings.userAgentString =
            if (Store.desktopUaEnabled(this)) DESKTOP_UA else (defaultUa ?: wv.settings.userAgentString)
    }

    private fun updateUi() {
        val tab = activeTab
        val wv = tab?.webView
        if (tab != null && !etAddress.hasFocus()) {
            val raw = wv?.url ?: tab.url
            // 本地起始页地址栏不显示 file:// 路径，显示“览 · 首页”
            val shown = if (raw.startsWith("file://")) "览 · 首页" else raw
            if (etAddress.text.toString() != shown) etAddress.setText(shown)
        }
        btnBack.alpha = if (wv?.canGoBack() == true) 1f else 0.4f
        btnForward.alpha = if (wv?.canGoForward() == true) 1f else 0.4f
        btnTabs.text = "▣ 标签(${tabs.size})"
        val url = wv?.url ?: tab?.url ?: ""
        btnBookmark.text = if (url.isNotEmpty() && Store.isBookmarked(this, url)) "★ 收藏" else "☆ 收藏"
    }

    // ---------- 菜单 / 对话框 ----------

    /** 菜单底部弹窗：头像行 + 拓展/书签/下载/历史 + 常用功能九宫格 */
    private var menuSheet: MenuSheet? = null

    private fun showMenu() {
        menuSheet?.dismiss()
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.7.1"
        } catch (e: Exception) {
            "0.7.1"
        }
        menuSheet = MenuSheet(
            this,
            container,
            version = ver,
            desktopUaOn = Store.desktopUaEnabled(this),
            adBlockOn = Store.adBlockEnabled(this),
            onAction = { key -> handleMenuAction(key) }
        ).also { it.show() }
    }

    private fun handleMenuAction(key: String) {
        when (key) {
            "extensions" -> startActivity(Intent(this, ExtensionsActivity::class.java))
            "bookmarks" -> showBookmarksDialog()
            "history" -> showHistoryDialog()
            "downloads" -> {
                try {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (e: Exception) {
                    Toast.makeText(this, "打不开系统下载列表", Toast.LENGTH_SHORT).show()
                }
            }
            "add_bookmark" -> toggleBookmark()
            "reload" -> activeTab?.webView?.reload()
            "desktop_ua" -> {
                val next = !Store.desktopUaEnabled(this)
                Store.setDesktopUaEnabled(this, next)
                tabs.forEach { applyUa(it.webView) }
                activeTab?.webView?.reload()
                Toast.makeText(this, if (next) "已切换桌面版 UA" else "已切换手机版 UA", Toast.LENGTH_SHORT).show()
            }
            "adblock" -> {
                val next = !Store.adBlockEnabled(this)
                Store.setAdBlockEnabled(this, next)
                Toast.makeText(this, if (next) "去广告已开启" else "去广告已关闭", Toast.LENGTH_SHORT).show()
            }
            "clear_data" -> {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
                activeTab?.webView?.clearCache(true)
                Store.clearHistory(this)
                Toast.makeText(this, "浏览数据已清除", Toast.LENGTH_SHORT).show()
            }
            "share" -> {
                val url = activeTab?.webView?.url ?: return
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                startActivity(Intent.createChooser(send, "分享链接"))
            }
            "open_system" -> {
                val url = activeTab?.webView?.url ?: return
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            "home_settings" -> startActivity(Intent(this, HomeSettingsActivity::class.java))
            "video_sniff" -> showSniffDialog()
            "video_settings" -> startActivity(Intent(this, VideoSettingsActivity::class.java))
            "check_update" -> UpdateChecker.check(this, silent = false)
            "log" -> startActivity(Intent(this, LogActivity::class.java))
            "about" -> showAbout()
            "exit" -> finish()
        }
    }

    private fun showTabsDialog() {
        val names = tabs.mapIndexed { i, t ->
            "${if (t == activeTab) "● " else "○ "}${t.title.ifBlank { t.url }}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("标签页（${tabs.size}）")
            .setItems(names) { _, which -> showTab(tabs[which]) }
            .setPositiveButton("新标签") { _, _ -> newTab(homeUrl(), true) }
            .setNegativeButton("关闭当前") { _, _ -> closeCurrentTab() }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun showBookmarksDialog() {
        val list = Store.loadBookmarks(this)
        if (list.isEmpty()) {
            Toast.makeText(this, "还没有收藏，点底部 ☆ 收藏当前页", Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.map { "${it.title.ifBlank { it.url }}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("收藏")
            .setItems(names) { _, which ->
                activeTab?.webView?.loadUrl(list[which].url)
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun showHistoryDialog() {
        val list = Store.loadHistory(this)
        if (list.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.map { "${it.title.ifBlank { it.url }}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setItems(names) { _, which ->
                activeTab?.webView?.loadUrl(list[which].url)
            }
            .setPositiveButton("清空历史") { _, _ ->
                Store.clearHistory(this)
                Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun toggleBookmark() {
        val wv = activeTab?.webView ?: return
        val url = wv.url ?: return
        if (url == "about:blank" || url.startsWith("file://") || url == homeUrl()) {
            Toast.makeText(this, "首页不用收藏", Toast.LENGTH_SHORT).show()
            return
        }
        val list = Store.loadBookmarks(this)
        val existing = list.find { it.url == url }
        if (existing != null) {
            list.remove(existing)
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
        } else {
            list.add(0, Bookmark(wv.title ?: url, url))
            Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show()
        }
        Store.saveBookmarks(this, list)
        updateUi()
    }

    private fun showAbout() {
        val pkg = WebView.getCurrentWebViewPackage()
        val info = "览 0.7.1 (11)\n" +
            "内核：系统 WebView（Chrome 内核 Blink + V8）\n" +
            "WebView：${pkg?.packageName ?: "未知"} ${pkg?.versionName ?: ""}\n" +
            "去广告：${if (Store.adBlockEnabled(this)) "开" else "关"}，" +
            "UA：${if (Store.desktopUaEnabled(this)) "桌面版" else "手机版"}"
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(info)
            .setPositiveButton("确定", null)
            .show()
    }

    // ---------- 视频嗅探 ----------

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".m3u8")) return true
        val path = lower.substringBefore("?").substringBefore("#")
        // .ts/.m4s 一律是 HLS 分片：也要认出来，才能记一笔“只抓到分片”而不是当没抓到
        return listOf(".mp4", ".webm", ".mov", ".m4v", ".flv", ".mkv", ".avi", ".ts", ".m4s")
            .any { path.endsWith(it) }
    }

    /** m3u8 播放列表：下载要走 HLS 合并，不能当单个文件下。 */
    private fun isPlaylistUrl(url: String): Boolean = url.lowercase().contains(".m3u8")

    private val segmentNameRe =
        Regex("^(index|seg|chunk|segment|part|media)[-_]?\\d+\\.(mp4|ts|m4s)$", RegexOption.IGNORE_CASE)

    /**
     * 是不是 HLS 分片（单独下就是坏文件，例如 index-1.mp4 128KB 播不了）。
     * 注意：B 站那种 43625612837-1-16.mp4 是完整直链，不算分片，别误杀。
     */
    private fun isSegmentUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")
        val name = path.substringAfterLast("/")
        if (path.endsWith(".ts") || path.endsWith(".m4s")) return true
        if (segmentNameRe.matches(name)) return true
        if (name.contains("-seg") || name.contains("_seg")) return true
        // hls 路径下的纯数字分片，例如 /hls/0.mp4、/hls/12.ts
        if (path.contains("/hls/") && Regex("^\\d+\\.(mp4|ts|m4s)$").matches(name)) return true
        return false
    }

    /**
     * 0.7.1：分片不进可下载列表，只记一笔 segmentSeenByTab，下面的下载器入口
     * 会据此提示“只抓到分片，没抓到 m3u8”，而不是把分片当整片下出坏文件。
     * isPlayUrl：JS 钩子从播放接口 JSON 里抠出的 play_url——该站 play_url 就是
     * m3u8，即使地址里没写 .m3u8 也按播放列表收；万一实际是直链 mp4，
     * HlsDownloader 首包判断会改走整文件下载，不会拼坏。
     */
    private fun addSniffed(tab: Tab, url: String, via: String, isPlayUrl: Boolean = false) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val playlist = isPlaylistUrl(url) || isPlayUrl
        if (!playlist && isSegmentUrl(url)) {
            segmentSeenByTab[tab] = true
            AppLog.i(this, "嗅探", "HLS 分片（不列出）：$via $url")
            runOnUiThread { if (tab == activeTab) updateSniffBtn() }
            return
        }
        val list = sniffedByTab.getOrPut(tab) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.url == url }) {
                list.add(SniffedVideo(url, via, -1, playlist, false))
                AppLog.i(this, "嗅探", "抓到视频：$via $url")
                if (list.size > 50) list.removeAt(0)
            } else return
        }
        runOnUiThread {
            updateSniffBtn()
            smartSheet?.refreshList()
            downloaderSheet?.refreshList()
            maybeAutoOpenSmartPlayer()
        }
    }

    /** 本页视频：m3u8 播放列表永远排最前面，其余保持抓到顺序。 */
    private fun sortedSniffed(tab: Tab): List<SniffedVideo> {
        val list = sniffedByTab[tab].orEmpty()
        return list.sortedByDescending { it.isPlaylist }
    }

    /** 此网站开了自动打开：本页第一次抓到视频就自动弹出智能播放器 */
    private fun maybeAutoOpenSmartPlayer() {
        val tab = activeTab ?: return
        val pageUrl = tab.webView.url ?: tab.url
        if (pageUrl == lastAutoOpenedPage) return
        val host = try {
            Uri.parse(pageUrl).host ?: return
        } catch (e: Exception) {
            return
        }
        if (smartSheet?.isShowing == true) return
        if (!Store.smartPlayerEnabled(this)) return
        if (!Store.autoOpenSiteEnabled(this, host)) return
        val list = sortedSniffed(tab)
        if (list.isEmpty()) return
        lastAutoOpenedPage = pageUrl
        openSmartPlayer(list.first(), Store.smartAutoplayEnabled(this))
    }

    private fun maybeSniff(tab: Tab?, url: String) {
        if (tab == null || !isVideoUrl(url)) return
        addSniffed(tab, url, "网络请求")
    }

    /** 扫页面里的 <video> 标签，补抓网络层没经过的直链。 */
    private fun scanVideoTags(view: WebView) {
        view.evaluateJavascript(
            "(function(){var out=[];" +
                "document.querySelectorAll('video').forEach(function(v){" +
                "if(v.currentSrc)out.push(v.currentSrc);" +
                "v.querySelectorAll('source').forEach(function(s){if(s.src)out.push(s.src);});});" +
                "return JSON.stringify(out);})();"
        ) { result ->
            try {
                val raw = org.json.JSONTokener(result ?: return@evaluateJavascript)
                    .nextValue() as? String ?: return@evaluateJavascript
                val arr = org.json.JSONArray(raw)
                val tab = tabs.find { it.webView == view } ?: return@evaluateJavascript
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (isVideoUrl(u)) addSniffed(tab, u, "video 标签")
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun updateSniffBtn() {
        val tab = activeTab ?: return
        val count = sniffedByTab[tab]?.size ?: 0
        val onlySegments = count == 0 && segmentSeenByTab[tab] == true
        val playing = videoPlayingByTab[tab] == true
        // 只有分片时也露入口，点开会提示“没抓到 m3u8”，而不是悄悄消失
        if ((count > 0 || onlySegments) && playing) {
            sniffBtn.text = if (count > 0) "下载($count)" else "下载"
            sniffBtn.visibility = View.VISIBLE
        } else {
            sniffBtn.visibility = View.GONE
        }
    }

    private fun showSniffDialog() {
        val tab = activeTab
        val list = tab?.let { sortedSniffed(it) } ?: emptyList()
        if (list.isEmpty()) {
            if (tab != null && segmentSeenByTab[tab] == true) {
                Toast.makeText(
                    this,
                    "只抓到 HLS 分片，没抓到 m3u8 播放列表：等视频开始播、再点一次试试，分片不能单独下载",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "这页没抓到直接视频链接：部分网站用 blob 或加密流，抓不到",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // 智能播放器开着：直接进播放器（弹窗/全屏/悬浮按模式走），选视频在播放器列表里，不再经过这个旧弹窗
        if (Store.smartPlayerEnabled(this)) {
            openVideoPlayer(list.first())
            return
        }
        val names = list.map { v ->
            val file = v.displayName()
            val host = try {
                Uri.parse(v.url).host ?: ""
            } catch (e: Exception) {
                ""
            }
            "$file\n${v.via} · $host"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("视频嗅探（${list.size}）")
            .setItems(names) { _, which -> showSniffActions(list[which]) }
            .setNeutralButton("关闭", null)
            .show()
    }

    /**
     * 把嗅探到的视频推给原生播放器。
     * 校验开着：先 HEAD 探一下能不能播（约 2 秒），明确播不了（4xx/5xx）就提示不弹；
     * 网络异常探不出时放行，让播放器自己报错。
     * 播放模式是悬浮：进应用内悬浮小窗；否则进全屏原生播放器。
     */
    private fun openVideoPlayer(video: SniffedVideo) {
        val title = video.url.substringAfterLast("/").substringBefore("?")
            .removeSuffix(".m3u8").ifBlank { "视频" }
        when (Store.playMode(this)) {
            "sheet" -> {
                openSmartPlayer(video, true)
                return
            }
            "floating" -> {
                showFloatingPlayer(video.url, title)
                return
            }
        }
        if (!Store.checkPlayableEnabled(this)) {
            startPlayerActivity(video.url, title)
            return
        }
        Thread {
            var ok = true
            try {
                val conn = URL(video.url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.connect()
                val code = conn.responseCode
                if (code in 400..599) ok = false
                conn.disconnect()
            } catch (e: Exception) {
                ok = true
            }
            runOnUiThread {
                if (ok) startPlayerActivity(video.url, title)
                else Toast.makeText(this, "视频无法播放（可能已加密）", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun startPlayerActivity(url: String, title: String, startMs: Int = 0) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("url", url)
            putExtra("title", title)
            putExtra("startMs", startMs)
        }
        startActivity(intent)
    }

    /** 打开智能播放器弹窗（盖在当前网页上面） */
    private fun openSmartPlayer(initial: SniffedVideo?, autoplay: Boolean) {
        val tab = activeTab ?: return
        val host = try {
            Uri.parse(tab.webView.url ?: tab.url).host ?: ""
        } catch (e: Exception) {
            ""
        }
        if (smartSheet?.isShowing == true) {
            smartSheet?.show(initial, autoplay)
            return
        }
        val sheet = SmartPlayerSheet(
            activity = this,
            container = container,
            getVideos = { activeTab?.let { sortedSniffed(it) } ?: emptyList() },
            host = host,
            onFullscreen = { url, title, pos -> startPlayerActivity(url, title, pos) },
            onFloat = { url, title, pos -> showFloatingPlayer(url, title, pos) },
            onDownload = { url -> downloadVideo(url) },
            onCopyLink = { url -> copyVideoLink(url) },
            onOpenInNewTab = { url -> newTab(url, true) }
        )
        smartSheet = sheet
        sheet.show(initial, autoplay)
    }

    /**
     * 0.7.1 下载分流：m3u8 播放列表走 HLS 合并下载（拉全部分片拼接成完整文件），
     * 普通直链仍走 DownloadManager。文件名取页面标题，不再用 index-1.mp4 这类分片名。
     * 注意：不做 UA 伪装——该站 App 也是同一套 HLS，改 UA 拿不到整片 mp4。
     */
    private fun downloadVideo(url: String) {
        val tab = activeTab
        val video = tab?.let { sniffedByTab[it]?.find { v -> v.url == url } }
        if (video?.isPlaylist == true || isPlaylistUrl(url)) {
            val wv = tab?.webView
            val referer = wv?.url ?: tab?.url
            val ua = try {
                wv?.settings?.userAgentString
            } catch (e: Exception) {
                null
            }
            val cookie = try {
                CookieManager.getInstance().getCookie(url)
            } catch (e: Exception) {
                null
            }
            val titleHint = try {
                wv?.title ?: tab?.title ?: ""
            } catch (e: Exception) {
                ""
            }
            HlsDownloader.download(this, url, referer, ua, cookie, titleHint)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, null, "video/mp4")
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            AppLog.i(this, "下载", "开始下载 $fileName")
            Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.e(this, "下载", "下载失败", e)
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyVideoLink(url: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("视频链接", url))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    // ---------- 应用内悬浮小窗播放器 ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showFloatingPlayer(url: String, title: String, startMs: Int = 0) {
        closeFloatingPlayer()
        floatUrl = url
        floatTitle = title

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(320), dp(200)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        val texture = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    floatPlayer?.setSurface(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        overlay.addView(texture)
        floatTexture = texture

        // 控制条：标题 + 播放/暂停 + ×
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(8), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pauseBtn = Button(this).apply {
            text = "❚❚"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                val p = floatPlayer ?: return@setOnClickListener
                if (p.isPlaying) {
                    p.pause()
                    text = "▶"
                } else {
                    p.start()
                    text = "❚❚"
                }
                scheduleFloatHide()
            }
        }
        val closeBtn = Button(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { shrinkFloatingToBall() }
            setOnLongClickListener { closeFloatingPlayer(); true }
        }
        controls.addView(titleView)
        controls.addView(pauseBtn)
        controls.addView(closeBtn)
        overlay.addView(controls)
        floatControls = controls

        // 拖动 + 点按显隐控制条 + 竖滑调亮度/音量（悬浮小窗开关开时）
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false
        var swipeMode = 0
        var startBrightness = 0.5f
        var startVolume = 0
        overlay.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    val lp = overlay.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    moved = false
                    swipeMode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > 16 || abs(dy) > 16)) {
                        moved = true
                        swipeMode = if (abs(dx) > abs(dy)) 1 else 2
                        startBrightness = window.attributes.screenBrightness.let {
                            if (it < 0) 0.5f else it
                        }
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        startVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    if (moved && swipeMode == 1) {
                        val lp = overlay.layoutParams as FrameLayout.LayoutParams
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = (startLeft + dx).toInt().coerceIn(0, container.width - overlay.width)
                        lp.topMargin = (startTop + dy).toInt().coerceIn(0, container.height - overlay.height)
                        overlay.layoutParams = lp
                    } else if (moved && swipeMode == 2 && Store.floatGestureEnabled(this@MainActivity)) {
                        if (event.x < overlay.width / 2f) {
                            val b = (startBrightness - dy / overlay.height).coerceIn(0.05f, 1f)
                            val wlp = window.attributes
                            wlp.screenBrightness = b
                            window.attributes = wlp
                            Toast.makeText(this@MainActivity, "亮度 ${(b * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                        } else {
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / overlay.height * maxV).toInt()
                            val vol = (startVolume + delta).coerceIn(0, maxV)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                            Toast.makeText(this@MainActivity, "音量 ${(vol * 100 / maxV)}%", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        controls.visibility =
                            if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        scheduleFloatHide()
                    }
                }
            }
            true
        }

        container.addView(overlay)
        floatOverlay = overlay
        startFloatPlayer(startMs)
        scheduleFloatHide()
    }

    private fun scheduleFloatHide() {
        floatHandler.removeCallbacks(floatHideRunnable)
        if (Store.floatAutohideEnabled(this)) {
            floatHandler.postDelayed(floatHideRunnable, 3000)
        }
    }

    private fun startFloatPlayer(startMs: Int = 0) {
        releaseFloatPlayer()
        try {
            floatPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, Uri.parse(floatUrl))
                setOnPreparedListener {
                    if (startMs > 0) it.seekTo(startMs)
                    it.start()
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@MainActivity, "视频无法播放（可能已加密）", Toast.LENGTH_LONG).show()
                    closeFloatingPlayer()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "视频无法播放：${e.message}", Toast.LENGTH_LONG).show()
            closeFloatingPlayer()
        }
    }

    /** 单击 ×：缩小为悬浮球，点球恢复小窗 */
    private fun shrinkFloatingToBall() {
        floatOverlay?.let { container.removeView(it) }
        floatOverlay = null
        floatControls = null
        val ball = TextView(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0E7490.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        var downX = 0f
        var downY = 0f
        var moved = false
        ball.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > 16 || abs(dy) > 16)) moved = true
                    if (moved) {
                        val lp = ball.layoutParams as FrameLayout.LayoutParams
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = (lp.leftMargin + dx).toInt().coerceIn(0, container.width - ball.width)
                        lp.topMargin = (lp.topMargin + dy).toInt().coerceIn(0, container.height - ball.height)
                        ball.layoutParams = lp
                        downX = event.rawX
                        downY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        floatBall?.let { container.removeView(it) }
                        floatBall = null
                        showFloatingPlayer(floatUrl, floatTitle)
                    }
                }
            }
            true
        }
        container.addView(ball)
        floatBall = ball
    }

    /** 长按 ×：彻底关闭悬浮播放 */
    private fun closeFloatingPlayer() {
        floatOverlay?.let { container.removeView(it) }
        floatOverlay = null
        floatBall?.let { container.removeView(it) }
        floatBall = null
        floatControls = null
        floatHandler.removeCallbacks(floatHideRunnable)
        releaseFloatPlayer()
    }

    private fun releaseFloatPlayer() {
        try {
            floatPlayer?.release()
        } catch (e: Exception) {
        }
        floatPlayer = null
    }

    private fun showSniffActions(video: SniffedVideo) {
        val actions = arrayOf("下载", "复制链接", "用播放器打开", "新标签页打开")
        AlertDialog.Builder(this)
            .setTitle(video.url.substringAfterLast("/").substringBefore("?"))
            .setItems(actions) { _, which ->
                when (which) {
                    // 0.7.1：收敛到 downloadVideo，m3u8 自动走合并下载，不再直接下单个文件
                    0 -> downloadVideo(video.url)
                    1 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("视频链接", video.url))
                        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
                    }
                    2 -> openVideoPlayer(video)
                    3 -> newTab(video.url, true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    /**
     * 首页专用桥：只在本地起始页生效，其他页面调用直接忽略，
     * 防止任意网页借桥跳转。
     */
    inner class LanBridge {
        private fun onHomePage(): Boolean =
            activeTab?.webView?.url?.startsWith("file:///android_asset/home.html") == true

        @android.webkit.JavascriptInterface
        fun search(query: String) {
            if (!onHomePage()) return
            runOnUiThread { navigate(query) }
        }

        @android.webkit.JavascriptInterface
        fun open(url: String) {
            if (!onHomePage()) return
            runOnUiThread { activeTab?.webView?.loadUrl(url) }
        }

        @android.webkit.JavascriptInterface
        fun quickLinks(): String {
            if (!onHomePage()) return "[]"
            val arr = org.json.JSONArray()
            val seen = mutableSetOf<String>()
            for (b in Store.loadBookmarks(this@MainActivity)) {
                if (seen.add(b.url)) {
                    arr.put(
                        org.json.JSONObject()
                            .put("title", b.title.ifBlank { b.url })
                            .put("url", b.url)
                    )
                }
            }
            for (h in Store.loadHistory(this@MainActivity)) {
                if (arr.length() >= 8) break
                if (seen.add(h.url)) {
                    arr.put(
                        org.json.JSONObject()
                            .put("title", h.title.ifBlank { h.url })
                            .put("url", h.url)
                    )
                }
            }
            return arr.toString()
        }

        @android.webkit.JavascriptInterface
        fun recent(): String {
            if (!onHomePage()) return "[]"
            val arr = org.json.JSONArray()
            for (h in Store.loadHistory(this@MainActivity).take(8)) {
                arr.put(
                    org.json.JSONObject()
                        .put("title", h.title.ifBlank { h.url })
                        .put("url", h.url)
                )
            }
            return arr.toString()
        }

        @android.webkit.JavascriptInterface
        fun version(): String = "0.7.1 (11)"

        @android.webkit.JavascriptInterface
        fun searchEngine(): String = Store.searchEngine(this@MainActivity)

        @android.webkit.JavascriptInterface
        fun homeStyle(): String = Store.homeStyle(this@MainActivity)

        @android.webkit.JavascriptInterface
        fun showTiles(): String = Store.showTiles(this@MainActivity).toString()

        @android.webkit.JavascriptInterface
        fun showRecent(): String = Store.showRecent(this@MainActivity).toString()

        @android.webkit.JavascriptInterface
        fun openExtensions() {
            if (!onHomePage()) return
            runOnUiThread {
                startActivity(Intent(this@MainActivity, ExtensionsActivity::class.java))
            }
        }
    }

    /**
     * 0.7.1 嗅探桥：JS 钩子把 fetch/XHR 里出现的视频地址报回来。
     * 只收 http(s) 视频地址，其它一律丢掉；onPlayUrl 专收播放接口 JSON 里的 play_url。
     */
    inner class SniffBridge(private val wv: WebView) {
        @android.webkit.JavascriptInterface
        fun onUrl(url: String) {
            reportSniffed(wv, url, "fetch/XHR", false)
        }

        @android.webkit.JavascriptInterface
        fun onPlayUrl(url: String) {
            reportSniffed(wv, url, "播放接口", true)
        }
    }

    private fun reportSniffed(wv: WebView, rawUrl: String, via: String, isPlayUrl: Boolean) {
        val tab = tabs.find { it.webView == wv } ?: return
        val resolved = resolveSniffedUrl(wv.url, rawUrl) ?: return
        if (!isVideoUrl(resolved) && !isPlayUrl) return
        // addSniffed 自己会做分片过滤与去重；这里只做线程无关的转发
        addSniffed(tab, resolved, via, isPlayUrl)
    }

    /** 相对地址按当前页解析；blob:/data: 不是可下载地址，直接丢掉。 */
    private fun resolveSniffedUrl(base: String?, raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        if (u.startsWith("blob:") || u.startsWith("data:")) return null
        if (base.isNullOrBlank()) return null
        return try {
            URL(URL(base), u).toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 0.7.1 注入 fetch/XHR 钩子：拦截层（shouldInterceptRequest）能看到资源请求，
     * 但看不到响应体——该站 play_url 是 POST /api/v2/vod/playurl 的 JSON 响应体
     * 里回的，不钩 XHR 就抓不到 m3u8；钩子只扫 JSON/文本响应，不碰二进制分片。
     * 跨域 iframe 里钩不到（主 frame 的 JS 够不着 iframe），那部分靠拦截层兜底。
     */
    private fun injectSniffHook(view: WebView) {
        view.evaluateJavascript(
            """(function(){if(window.__lanSniffHook)return;window.__lanSniffHook=true;
function report(u,isPlay){try{if(!u||typeof u!=='string')return;if(window.LanSniff){if(isPlay){window.LanSniff.onPlayUrl(u);}else{window.LanSniff.onUrl(u);}}}catch(e){}}
function isVid(u){return /\.m3u8(\?|#|$)/i.test(u)||/\.ts(\?|#|$)/i.test(u)||/\.m4s(\?|#|$)/i.test(u)||/index-\d+\.mp4/i.test(u)||/\.mp4(\?|#|$)/i.test(u);}
function check(u){try{if(!u)return;var s=typeof u==='string'?u:(u.url||'');if(s&&isVid(s))report(s,false);}catch(e){}}
function scanText(t){if(!t||typeof t!=='string')return;var re=/(https?:\/\/[^\s"'<>\\]+?\.m3u8(?:[?#][^\s"'<>\\]*)?)/g,m;while((m=re.exec(t))!==null){report(m[1],false);}var p=/"play_url"\s*:\s*"([^"\\]+)"/g;while((m=p.exec(t))!==null){report(m[1].replace(/\\/g,''),true);}}
function maybeScan(url,text){try{if(!text)return;var u=String(url||'');if(u.indexOf('playurl')>=0||u.indexOf('.m3u8')>=0||text.indexOf('#EXTM3U')>=0||text.indexOf('.m3u8')>=0||text.indexOf('play_url')>=0){scanText(text);}}catch(e){}}
try{if(window.fetch){var of=window.fetch;window.fetch=function(){var a=arguments;try{check(a[0]);}catch(e){}return of.apply(this,a).then(function(res){try{var ct='';try{ct=res.headers.get('content-type')||'';}catch(e){}var u=res.url||'';if(/json|text|mpegurl/i.test(ct)||u.indexOf('playurl')>=0||u.indexOf('.m3u8')>=0){var c=res.clone();c.text().then(function(t){maybeScan(u,t);}).catch(function(){});}}catch(e){}return res;});}}}catch(e){}
try{var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{this.__lanUrl=u;check(u);}catch(e){}return oo.apply(this,arguments);};var os=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){var x=this;try{x.addEventListener('load',function(){try{var t=x.responseText;if(typeof t==='string')maybeScan(x.responseURL||x.__lanUrl,t);}catch(e){}});}catch(e){}return os.apply(this,arguments);};}catch(e){}
function scanVideos(){try{var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var s=vs[i].currentSrc||vs[i].src;if(s)check(s);}}catch(e){}}
scanVideos();
try{if(window.MutationObserver){new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(!n)return;if(n.tagName==='VIDEO'){var s=n.currentSrc||n.src;if(s)check(s);}if(n.querySelectorAll){var vs=n.querySelectorAll('video');for(var i=0;i<vs.length;i++){var s2=vs[i].currentSrc||vs[i].src;if(s2)check(s2);}}});});}).observe(document.documentElement,{childList:true,subtree:true});}}catch(e){}
})();""",
            null
        )
    }

    /** 视频播放状态桥：页面里 <video> 一播/一停就上报，桥实例绑在自己的 WebView 上 */
    inner class VideoStateBridge(private val wv: WebView) {
        @android.webkit.JavascriptInterface
        fun onState(playing: Boolean) {
            runOnUiThread {
                val tab = tabs.find { it.webView == wv } ?: return@runOnUiThread
                videoPlayingByTab[tab] = playing
                if (tab == activeTab) updateSniffBtn()
            }
        }
    }

    /** 给页面挂视频播放监听：播/停/播完都上报，动态插进来的 <video> 也盯着 */
    private fun hookVideoState(view: WebView) {
        view.evaluateJavascript(
            "(function(){if(window.__lanVideoHook)return;window.__lanVideoHook=true;" +
            "function anyPlaying(){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){if(!vs[i].paused&&!vs[i].ended)return true;}return false;}" +
            "function report(){try{if(window.LanVideo)window.LanVideo.onState(anyPlaying());}catch(e){}}" +
            "function hook(v){v.addEventListener('play',report);v.addEventListener('pause',report);v.addEventListener('ended',report);}" +
            "document.querySelectorAll('video').forEach(hook);" +
            "new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.tagName==='VIDEO')hook(n);if(n.querySelectorAll)n.querySelectorAll('video').forEach(hook);});});}).observe(document.documentElement,{childList:true,subtree:true});" +
            "report();})();",
            null
        )
    }

    /** 智能下载器入口：玻璃卡片弹窗，列本页视频 */
    private fun openDownloader() {
        val tab = activeTab ?: return
        val list = sortedSniffed(tab)
        if (list.isEmpty()) {
            if (segmentSeenByTab[tab] == true) {
                Toast.makeText(
                    this,
                    "只抓到 HLS 分片，没抓到 m3u8 播放列表：等视频开始播、再点一次试试，分片不能单独下载",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "这页没抓到可下载的视频", Toast.LENGTH_SHORT).show()
            }
            return
        }
        downloaderSheet?.dismiss()
        downloaderSheet = SmartDownloaderSheet(
            this,
            container,
            getVideos = { activeTab?.let { sortedSniffed(it) } ?: emptyList() },
            onDownload = { url -> downloadVideo(url) },
            onPlay = { video ->
                downloaderSheet?.dismiss()
                openVideoPlayer(video)
            }
        ).also { it.show() }
    }
}
