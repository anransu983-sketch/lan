package com.suanran.lan

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * 智能播放器弹窗：从页面底部滑上来的播放器，盖在网页上面。
 * 上面是视频卡（倍速/菜单/进度/下载/循环/全屏），中间是本页抓到的视频列表
 * （按呈现时间 / 按内容大小排，带文件大小），下面是分站设置。
 * 关掉弹窗就停播；点左上角 ▣ 缩成悬浮球继续播；点 ⛶ 进全屏播放器。
 */
class SmartPlayerSheet(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val getVideos: () -> List<SniffedVideo>,
    private val host: String,
    private val onFullscreen: (url: String, title: String, positionMs: Int) -> Unit,
    private val onFloat: (url: String, title: String, positionMs: Int) -> Unit,
    private val onDownload: (url: String) -> Unit,
    private val onCopyLink: (url: String) -> Unit,
    private val onOpenInNewTab: (url: String) -> Unit
) {

    private var dimView: View? = null
    private var sheetView: LinearLayout? = null
    private var textureView: TextureView? = null
    private var titleView: TextView? = null
    private var listHeader: TextView? = null
    private var listContainer: LinearLayout? = null
    private var sortTimeBtn: Button? = null
    private var sortSizeBtn: Button? = null
    private var playBtn: Button? = null
    private var seekBar: SeekBar? = null
    private var timeText: TextView? = null
    private var speedBtn: Button? = null
    private var loopBtn: Button? = null
    private var loading: ProgressBar? = null

    private var player: MediaPlayer? = null
    private var currentVideo: SniffedVideo? = null
    private var currentTitle: String = "视频"
    private var sortMode: String = "time" // time 呈现时间 / size 内容大小
    private var durationMs = 0
    private var seeking = false
    private var baseSpeed = 1.0f
    private var longPressing = false
    private var looping = false
    private var playOnPrepared = true

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    var isShowing: Boolean = false
        private set

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun show(initial: SniffedVideo?, autoplay: Boolean) {
        if (isShowing) {
            if (initial != null && initial.url != currentVideo?.url) playVideo(initial, true)
            return
        }
        isShowing = true
        playOnPrepared = autoplay

        val dm = activity.resources.displayMetrics
        val sheetHeight = (dm.heightPixels * 0.72).toInt()

        dimView = View(activity).apply {
            setBackgroundColor(0x66000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dismiss() }
        }
        container.addView(dimView)

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_top_rounded)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                sheetHeight
            ).apply { gravity = Gravity.BOTTOM }
        }
        sheetView = sheet

        // 拖动手柄
        val handle = View(activity).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(6)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        }
        sheet.addView(handle)

        // 标题栏：智能播放器-xxx + 全屏 + ×
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(6))
        }
        titleView = TextView(activity).apply {
            text = "智能播放器"
            textSize = 17f
            setTextColor(0xFF111111.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fullBtn = Button(activity).apply {
            text = "⛶"
            textSize = 20f
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                val v = currentVideo ?: return@setOnClickListener
                val pos = try {
                    player?.currentPosition ?: 0
                } catch (e: Exception) {
                    0
                }
                dismiss()
                onFullscreen(v.url, currentTitle, pos)
            }
        }
        val closeBtn = Button(activity).apply {
            text = "×"
            textSize = 24f
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { dismiss() }
        }
        header.addView(titleView)
        header.addView(fullBtn)
        header.addView(closeBtn)
        sheet.addView(header)

        // 视频卡
        val cardWidth = dm.widthPixels - dp(32)
        val cardHeight = cardWidth * 9 / 16
        val card = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.card_rounded)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                cardHeight
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        }
        textureView = TextureView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    player?.setSurface(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        card.addView(textureView)

        loading = ProgressBar(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
            visibility = View.GONE
        }
        card.addView(loading)

        // 卡片上层：左 ▣ 悬浮，右 倍速 + ⋮
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val floatBtn = Button(activity).apply {
            text = "▣"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                val v = currentVideo ?: return@setOnClickListener
                val pos = try {
                    player?.currentPosition ?: 0
                } catch (e: Exception) {
                    0
                }
                dismiss()
                onFloat(v.url, currentTitle, pos)
            }
        }
        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        speedBtn = Button(activity).apply {
            text = "1.0x"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x88000000.toInt())
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
        val moreBtn = Button(activity).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { anchor -> showCardMenu(anchor) }
        }
        topRow.addView(floatBtn)
        topRow.addView(spacer)
        topRow.addView(speedBtn)
        topRow.addView(moreBtn)
        card.addView(topRow)

        // 卡片下层：进度条 + 播放/时间/下载/循环/全屏
        val bottomCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        seekBar = SeekBar(activity).apply {
            max = 1000
            setPadding(dp(12), 0, dp(12), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && durationMs > 0) {
                        val pos = durationMs * progress / 1000
                        timeText?.text = "${formatTime(pos)} / ${formatTime(durationMs)}"
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
        bottomCol.addView(seekBar)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        playBtn = Button(activity).apply {
            text = "▶"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { togglePlay() }
        }
        timeText = TextView(activity).apply {
            text = "00:00 / 00:00"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val dlBtn = Button(activity).apply {
            text = "↓"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { currentVideo?.let { onDownload(it.url) } }
        }
        loopBtn = Button(activity).apply {
            text = "↻"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                looping = !looping
                player?.isLooping = looping
                setTextColor(if (looping) 0xFF38BDF8.toInt() else 0xFFFFFFFF.toInt())
                Toast.makeText(activity, if (looping) "循环播放已开" else "循环播放已关", Toast.LENGTH_SHORT).show()
            }
        }
        val fsBtn = Button(activity).apply {
            text = "⛶"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                val v = currentVideo ?: return@setOnClickListener
                val pos = try {
                    player?.currentPosition ?: 0
                } catch (e: Exception) {
                    0
                }
                dismiss()
                onFullscreen(v.url, currentTitle, pos)
            }
        }
        controls.addView(playBtn)
        controls.addView(timeText)
        controls.addView(dlBtn)
        controls.addView(loopBtn)
        controls.addView(fsBtn)
        bottomCol.addView(controls)
        card.addView(bottomCol)

        // 卡片手势：双击快进/快退，长按按住倍速
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val skip = Store.skipSeconds(activity) * 1000
                val p = player ?: return true
                val w = textureView?.width ?: 1
                if (e.x < w / 2f) {
                    p.seekTo(max(0, p.currentPosition - skip))
                    Toast.makeText(activity, "快退 ${Store.skipSeconds(activity)} 秒", Toast.LENGTH_SHORT).show()
                } else {
                    p.seekTo(min(durationMs, p.currentPosition + skip))
                    Toast.makeText(activity, "快进 ${Store.skipSeconds(activity)} 秒", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressing = true
                applySpeed(Store.longPressSpeed(activity))
                Toast.makeText(activity, "长按倍速 ${Store.longPressSpeed(activity)}x", Toast.LENGTH_SHORT).show()
            }
        })
        textureView?.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && longPressing) {
                longPressing = false
                applySpeed(baseSpeed)
            }
            true
        }

        sheet.addView(card)

        // 列表头：当前页面视频 + 排序
        val listHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        listHeader = TextView(activity).apply {
            text = "当前页面视频"
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sortTimeBtn = Button(activity).apply {
            text = "按呈现时间"
            textSize = 13f
            setOnClickListener {
                sortMode = "time"
                paintSortButtons()
                refreshList()
            }
        }
        sortSizeBtn = Button(activity).apply {
            text = "按内容大小"
            textSize = 13f
            setOnClickListener {
                sortMode = "size"
                paintSortButtons()
                refreshList()
            }
        }
        listHead.addView(listHeader)
        listHead.addView(sortTimeBtn)
        listHead.addView(sortSizeBtn)
        sheet.addView(listHead)
        paintSortButtons()

        // 列表（可滚动）
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        scroll.addView(listContainer)
        sheet.addView(scroll)

        // 分站设置
        val settings = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        settings.addView(divider())
        settings.addView(switchRow("此网站自动打开智能播放器", Store.autoOpenSiteEnabled(activity, host)) { checked ->
            Store.setAutoOpenSiteEnabled(activity, host, checked)
            Toast.makeText(
                activity,
                if (checked) "此网站抓到视频会自动弹出播放器" else "已关闭此网站自动打开",
                Toast.LENGTH_SHORT
            ).show()
        })
        settings.addView(divider())
        settings.addView(switchRow("自动播放", Store.smartAutoplayEnabled(activity)) { checked ->
            Store.setSmartAutoplayEnabled(activity, checked)
        })
        sheet.addView(settings)

        container.addView(sheet)
        sheet.translationY = sheetHeight.toFloat()
        sheet.animate().translationY(0f).setDuration(250).start()

        val first = initial ?: getVideos().firstOrNull()
        if (first != null) playVideo(first, autoplay)
        refreshList()
        fetchSizes()
    }

    /** 外面又抓到新视频时刷新列表 */
    fun refreshList() {
        val list = sortedVideos()
        listHeader?.text = "当前页面视频（${list.size}）"
        val box = listContainer ?: return
        box.removeAllViews()
        for (v in list) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { playVideo(v, true) }
            }
            val left = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(activity).apply {
                text = v.url.substringAfterLast("/").substringBefore("?").ifBlank { v.url }
                textSize = 14f
                setTextColor(0xFF111111.toInt())
                maxLines = 2
            }
            val meta = TextView(activity).apply {
                text = "${v.via} · ${formatSize(v.sizeBytes)}"
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, dp(2), 0, 0)
            }
            left.addView(name)
            left.addView(meta)
            val check = TextView(activity).apply {
                text = if (v.url == currentVideo?.url) "✓" else ""
                textSize = 20f
                setTextColor(0xFF2563EB.toInt())
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(left)
            row.addView(check)
            box.addView(row)
            box.addView(divider())
        }
    }

    private fun sortedVideos(): List<SniffedVideo> {
        val list = getVideos()
        return if (sortMode == "size") {
            list.sortedWith(compareByDescending<SniffedVideo> { it.sizeBytes >= 0 }.thenByDescending { it.sizeBytes })
        } else {
            list
        }
    }

    /** 没测过大小的，HEAD 测 Content-Length，回来刷新列表 */
    private fun fetchSizes() {
        val pending = getVideos().filter { it.sizeBytes < 0 }
        if (pending.isEmpty()) return
        Thread {
            for (v in pending) {
                try {
                    val conn = URL(v.url).openConnection() as HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.connect()
                    val len = conn.getHeaderFieldLong("Content-Length", -1)
                    if (len > 0) v.sizeBytes = len
                    conn.disconnect()
                } catch (e: Exception) {
                }
            }
            handler.post { if (isShowing) refreshList() }
        }.start()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "大小未知"
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }

    private fun paintSortButtons() {
        val on = 0xFF2563EB.toInt()
        sortTimeBtn?.apply {
            setTextColor(if (sortMode == "time") 0xFFFFFFFF.toInt() else on)
            setBackgroundColor(if (sortMode == "time") on else 0xFFE5E7EB.toInt())
        }
        sortSizeBtn?.apply {
            setTextColor(if (sortMode == "size") 0xFFFFFFFF.toInt() else on)
            setBackgroundColor(if (sortMode == "size") on else 0xFFE5E7EB.toInt())
        }
    }

    private fun showCardMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "复制链接")
        popup.menu.add(0, 2, 0, "新标签页打开")
        popup.menu.add(0, 3, 0, "下载")
        popup.setOnMenuItemClickListener { item ->
            val v = currentVideo ?: return@setOnMenuItemClickListener true
            when (item.itemId) {
                1 -> onCopyLink(v.url)
                2 -> onOpenInNewTab(v.url)
                3 -> onDownload(v.url)
            }
            true
        }
        popup.show()
    }

    private fun playVideo(video: SniffedVideo, autoplay: Boolean) {
        currentVideo = video
        currentTitle = video.url.substringAfterLast("/").substringBefore("?").ifBlank { "视频" }
        titleView?.text = "智能播放器-$currentTitle"
        playOnPrepared = autoplay
        durationMs = 0
        seekBar?.progress = 0
        timeText?.text = "00:00 / 00:00"
        playBtn?.text = "▶"
        loading?.visibility = View.VISIBLE
        val surface = textureView?.surfaceTexture?.let { Surface(it) }
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(activity, Uri.parse(video.url))
                if (surface != null) setSurface(surface)
                setOnPreparedListener { mp ->
                    loading?.visibility = View.GONE
                    durationMs = mp.duration
                    isLooping = looping
                    applySpeed(baseSpeed)
                    if (playOnPrepared) {
                        mp.start()
                        playBtn?.text = "❚❚"
                    }
                    handler.post(progressRunnable)
                }
                setOnCompletionListener {
                    playBtn?.text = "▶"
                }
                setOnErrorListener { _, _, _ ->
                    loading?.visibility = View.GONE
                    Toast.makeText(activity, "视频无法播放（可能已加密）", Toast.LENGTH_LONG).show()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            loading?.visibility = View.GONE
            Toast.makeText(activity, "视频无法播放：${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshList()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playBtn?.text = "▶"
        } else {
            p.start()
            playBtn?.text = "❚❚"
        }
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

    private fun updateProgress() {
        val p = player ?: return
        if (durationMs > 0 && !seeking) {
            val pos = try {
                p.currentPosition
            } catch (e: Exception) {
                return
            }
            seekBar?.progress = (pos * 1000 / durationMs).coerceIn(0, 1000)
            timeText?.text = "${formatTime(pos)} / ${formatTime(durationMs)}"
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(0xFFE5E7EB.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val left = TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(left)
        row.addView(sw)
        return row
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        handler.removeCallbacks(progressRunnable)
        releasePlayer()
        val sheet = sheetView
        val dim = dimView
        sheetView = null
        dimView = null
        if (sheet != null) {
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(220).withEndAction {
                container.removeView(sheet)
                dim?.let { container.removeView(it) }
            }.start()
        } else {
            dim?.let { container.removeView(it) }
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
}
