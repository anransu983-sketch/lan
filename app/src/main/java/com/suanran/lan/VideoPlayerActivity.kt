package com.suanran.lan

import android.app.DownloadManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 原生视频播放器：嗅探到的直链（mp4 / m3u8 等不加密的）推到这里播。
 * 手势（媒体助手开时生效）：单击显/隐控制条，双击左/右快退/快进，
 * 长按按住倍速，横滑调进度，竖滑左半屏调亮度、右半屏调音量。
 * 工具箱：倍速档位 + 下载。
 */
class VideoPlayerActivity : ComponentActivity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var toolboxRow: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var speedBtn: Button
    private lateinit var hintText: TextView
    private lateinit var loading: ProgressBar

    private var player: MediaPlayer? = null
    private var url: String = ""
    private var title: String = ""
    private var startMs: Int = 0
    private var baseSpeed = 1.0f
    private var longPressing = false
    private var controlsVisible = true
    private var durationMs = 0
    private var seeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var swipeMode = 0 // 0 无，1 横滑进度，2 竖滑
    private var swipeStartPos = 0
    private var swipeStartBrightness = 0.5f
    private var swipeStartVolume = 0

    private lateinit var detector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        url = intent.getStringExtra("url") ?: ""
        title = intent.getStringExtra("title") ?: "视频"
        startMs = intent.getIntExtra("startMs", 0)
        if (url.isBlank()) {
            Toast.makeText(this, "没有视频地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = this@VideoPlayerActivity
        }
        root.addView(textureView)

        loading = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(120, 120, Gravity.CENTER)
        }
        root.addView(loading)

        hintText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding(36, 20, 36, 20)
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            visibility = View.GONE
        }
        root.addView(hintText)

        // 顶栏：返回 + 标题
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(8, 16, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8, 0, 0, 0)
        }
        topBar.addView(back)
        topBar.addView(titleView)
        root.addView(topBar)

        // 工具箱：倍速档位 + 下载（媒体助手开且默认展示时显示）
        toolboxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { bottomMargin = 150 }
            visibility =
                if (Store.mediaAssistantEnabled(this@VideoPlayerActivity) &&
                    Store.showToolboxEnabled(this@VideoPlayerActivity)
                ) View.VISIBLE else View.GONE
        }
        val speedLabel = TextView(this).apply {
            text = "倍速"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 16, 0)
        }
        toolboxRow.addView(speedLabel)
        for (s in listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)) {
            val chip = Button(this).apply {
                text = if (s == 1.0f) "1.0x" else "${s}x"
                textSize = 14f
                setTextColor(if (s == 1.0f) 0xFF0E7490.toInt() else 0xFFFFFFFF.toInt())
                setBackgroundColor(0x00000000)
                setOnClickListener {
                    baseSpeed = s
                    applySpeed(s)
                    speedBtn.text = "${s}x"
                    for (i in 1 until toolboxRow.childCount) {
                        val c = toolboxRow.getChildAt(i) as? Button ?: continue
                        c.setTextColor(0xFFFFFFFF.toInt())
                    }
                    setTextColor(0xFF0E7490.toInt())
                    Toast.makeText(this@VideoPlayerActivity, "倍速 ${s}x", Toast.LENGTH_SHORT).show()
                }
            }
            toolboxRow.addView(chip)
        }
        val dlBtn = Button(this).apply {
            text = "下载"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { downloadCurrent() }
        }
        toolboxRow.addView(dlBtn)
        root.addView(toolboxRow)

        // 底栏：播放/暂停 + 进度 + 时间 + 倍速 + 工具箱开关
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 8, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && durationMs > 0) {
                        val pos = durationMs * progress / 1000
                        timeText.text = "${formatTime(pos)} / ${formatTime(durationMs)}"
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    val p = player ?: return
                    if (durationMs > 0) p.seekTo(durationMs * progress / 1000)
                }
            })
        }
        bottomBar.addView(seekBar)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playBtn = Button(this).apply {
            text = "❚❚"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { togglePlay() }
        }
        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(12, 0, 12, 0)
        }
        speedBtn = Button(this).apply {
            text = "1.0x"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                val next = when (baseSpeed) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2.0f
                    2.0f -> 0.5f
                    else -> 1.0f
                }
                baseSpeed = next
                applySpeed(next)
                text = "${next}x"
            }
        }
        val toolboxBtn = Button(this).apply {
            text = "工具箱"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                toolboxRow.visibility =
                    if (toolboxRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        row.addView(playBtn)
        row.addView(timeText)
        row.addView(speedBtn)
        row.addView(toolboxBtn)
        bottomBar.addView(row)
        root.addView(bottomBar)

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!Store.mediaAssistantEnabled(this@VideoPlayerActivity)) return true
                val skip = Store.skipSeconds(this@VideoPlayerActivity) * 1000
                val p = player ?: return true
                if (e.x < textureView.width / 2f) {
                    p.seekTo(max(0, p.currentPosition - skip))
                    showHint("快退 ${Store.skipSeconds(this@VideoPlayerActivity)} 秒")
                } else {
                    p.seekTo(min(durationMs, p.currentPosition + skip))
                    showHint("快进 ${Store.skipSeconds(this@VideoPlayerActivity)} 秒")
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!Store.mediaAssistantEnabled(this@VideoPlayerActivity)) return
                longPressing = true
                applySpeed(Store.longPressSpeed(this@VideoPlayerActivity))
                showHint("长按倍速 ${Store.longPressSpeed(this@VideoPlayerActivity)}x")
            }
        })

        textureView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    swipeMode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!Store.mediaAssistantEnabled(this@VideoPlayerActivity)) return@setOnTouchListener true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (swipeMode == 0 && (abs(dx) > 24 || abs(dy) > 24)) {
                        swipeMode = if (abs(dx) > abs(dy)) 1 else 2
                        swipeStartPos = player?.currentPosition ?: 0
                        swipeStartBrightness = window.attributes.screenBrightness.let {
                            if (it < 0) 0.5f else it
                        }
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        swipeStartVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    if (swipeMode == 1 && durationMs > 0) {
                        val delta = (dx / textureView.width * durationMs).toInt()
                        val pos = (swipeStartPos + delta).coerceIn(0, durationMs)
                        player?.seekTo(pos)
                        showHint("${formatTime(pos)} / ${formatTime(durationMs)}")
                    } else if (swipeMode == 2) {
                        if (event.x < textureView.width / 2f) {
                            // 左半屏：亮度
                            val b = (swipeStartBrightness - dy / textureView.height).coerceIn(0.05f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = b
                            window.attributes = lp
                            showHint("亮度 ${(b * 100).toInt()}%")
                        } else {
                            // 右半屏：音量
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / textureView.height * maxV).toInt()
                            val v = (swipeStartVolume + delta).coerceIn(0, maxV)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            showHint("音量 ${(v * 100 / maxV)}%")
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressing) {
                        longPressing = false
                        applySpeed(baseSpeed)
                        hideHintSoon()
                    } else if (swipeMode != 0) {
                        hideHintSoon()
                    }
                    swipeMode = 0
                }
            }
            true
        }

        setContentView(root)
        scheduleHide()
    }

    private fun applySpeed(speed: Float) {
        val p = player ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                p.playbackParams = PlaybackParams().setSpeed(speed)
            } catch (e: Exception) {
            }
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playBtn.text = "▶"
        } else {
            p.start()
            playBtn.text = "❚❚"
        }
        scheduleHide()
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        if (Store.mediaAssistantEnabled(this) && Store.showToolboxEnabled(this)) {
            toolboxRow.visibility = View.VISIBLE
        }
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        toolboxRow.visibility = View.GONE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3000)
    }

    private var hintHide: Runnable? = null

    private fun showHint(text: String) {
        hintText.text = text
        hintText.visibility = View.VISIBLE
        hintHide?.let { handler.removeCallbacks(it) }
    }

    private fun hideHintSoon() {
        val r = Runnable { hintText.visibility = View.GONE }
        hintHide = r
        handler.postDelayed(r, 800)
    }

    private fun updateProgress() {
        val p = player ?: return
        if (!p.isPlaying && playBtn.text == "❚❚") return
        if (durationMs > 0 && !seeking) {
            val pos = p.currentPosition
            seekBar.progress = (pos * 1000 / durationMs).coerceIn(0, 1000)
            timeText.text = "${formatTime(pos)} / ${formatTime(durationMs)}"
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun downloadCurrent() {
        // 0.7.1：当前播的是 m3u8 时走 HLS 合并下载，别把播放列表文本当视频下下来
        if (url.contains(".m3u8", ignoreCase = true)) {
            val cookie = try {
                CookieManager.getInstance().getCookie(url)
            } catch (e: Exception) {
                null
            }
            HlsDownloader.download(this, url, null, null, cookie, title)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, null, "video/mp4")
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                )
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- TextureView ----
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startPlayer(Surface(surface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun startPlayer(surface: Surface) {
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@VideoPlayerActivity, Uri.parse(url))
                setSurface(surface)
                setOnPreparedListener { mp ->
                    loading.visibility = View.GONE
                    durationMs = mp.duration
                    if (startMs > 0) mp.seekTo(startMs)
                    mp.start()
                    playBtn.text = "❚❚"
                    handler.post(progressRunnable)
                    scheduleHide()
                }
                setOnCompletionListener {
                    playBtn.text = "▶"
                }
                setOnErrorListener { _, _, _ ->
                    loading.visibility = View.GONE
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        "视频无法播放（可能已加密）",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "视频无法播放：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressRunnable)
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    override fun onPause() {
        super.onPause()
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }
}
