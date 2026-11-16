package com.suanran.lan

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * 智能下载器：玻璃卡片弹窗，盖在网页上面。
 * 只列本页抓到的视频直链，每张玻璃卡片一个视频：文件名、大小、来源，
 * 点「下载」存到手机，点「播放」进智能播放器。
 */
class SmartDownloaderSheet(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val getVideos: () -> List<SniffedVideo>,
    private val onDownload: (url: String) -> Unit,
    private val onPlay: (video: SniffedVideo) -> Unit
) {

    private var dimView: View? = null
    private var sheetView: LinearLayout? = null
    private var listHeader: TextView? = null
    private var listContainer: LinearLayout? = null
    private var sortTimeBtn: Button? = null
    private var sortSizeBtn: Button? = null

    private var sortMode: String = "time" // time 呈现时间 / size 内容大小

    private val handler = Handler(Looper.getMainLooper())

    var isShowing: Boolean = false
        private set

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun show() {
        if (isShowing) return
        isShowing = true

        val dm = activity.resources.displayMetrics
        val sheetHeight = (dm.heightPixels * 0.66).toInt()

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
            setBackgroundResource(R.drawable.glass_sheet)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                sheetHeight
            ).apply { gravity = Gravity.BOTTOM }
        }
        sheetView = sheet

        val handle = View(activity).apply {
            setBackgroundColor(0xFFCBD5E1.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(6)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        }
        sheet.addView(handle)

        // 标题栏
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(12), dp(2))
        }
        val title = TextView(activity).apply {
            text = "智能下载器"
            textSize = 20f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = Button(activity).apply {
            text = "×"
            textSize = 24f
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { dismiss() }
        }
        header.addView(title)
        header.addView(closeBtn)
        sheet.addView(header)

        val sub = TextView(activity).apply {
            text = "只抓本页正在播的视频 · 点下载存到手机"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(dp(20), 0, dp(20), dp(6))
        }
        sheet.addView(sub)

        // 列表头 + 排序
        val listHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        listHeader = TextView(activity).apply {
            text = "本页视频"
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

        // 玻璃卡片列表（可滚动）
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scroll.addView(listContainer)
        sheet.addView(scroll)

        container.addView(sheet)
        sheet.translationY = sheetHeight.toFloat()
        sheet.animate().translationY(0f).setDuration(250).start()

        refreshList()
        fetchSizes()
    }

    fun refreshList() {
        val list = sortedVideos()
        listHeader?.text = "本页视频（${list.size}）"
        val box = listContainer ?: return
        box.removeAllViews()
        if (list.isEmpty()) {
            val empty = TextView(activity).apply {
                text = "这页没抓到可下载的视频"
                textSize = 14f
                setTextColor(0xFF9CA3AF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            }
            box.addView(empty)
            return
        }
        for (v in list) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.glass_card)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            val name = TextView(activity).apply {
                text = v.url.substringAfterLast("/").substringBefore("?").ifBlank { v.url }
                textSize = 15f
                setTextColor(0xFF111111.toInt())
                maxLines = 2
            }
            val host = try {
                android.net.Uri.parse(v.url).host ?: ""
            } catch (e: Exception) {
                ""
            }
            val meta = TextView(activity).apply {
                text = "${v.via} · ${formatSize(v.sizeBytes)} · $host"
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
                setPadding(0, dp(4), 0, dp(10))
            }
            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dlBtn = Button(activity).apply {
                text = "下载"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF2563EB.toInt())
                setPadding(dp(28), dp(8), dp(28), dp(8))
                setOnClickListener { onDownload(v.url) }
            }
            val playBtn = Button(activity).apply {
                text = "播放"
                textSize = 14f
                setTextColor(0xFF2563EB.toInt())
                setBackgroundColor(0x00000000)
                setPadding(dp(20), dp(8), dp(20), dp(8))
                setOnClickListener { onPlay(v) }
            }
            btnRow.addView(dlBtn)
            btnRow.addView(playBtn)
            card.addView(name)
            card.addView(meta)
            card.addView(btnRow)
            box.addView(card)
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

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
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
}
