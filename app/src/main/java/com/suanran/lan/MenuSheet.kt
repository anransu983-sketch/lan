package com.suanran.lan

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 菜单底部弹窗：头像行 + 拓展/书签/下载/历史 + 常用功能九宫格。
 * 只放览里真正能用的功能，不放假入口。
 */
class MenuSheet(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val version: String,
    private val desktopUaOn: Boolean,
    private val adBlockOn: Boolean,
    private val onAction: (key: String) -> Unit
) {

    private var dimView: View? = null
    private var sheetView: LinearLayout? = null

    var isShowing: Boolean = false
        private set

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun show() {
        if (isShowing) return
        isShowing = true

        val dm = activity.resources.displayMetrics
        val sheetHeight = (dm.heightPixels * 0.78).toInt()

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

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        scroll.addView(body)
        sheet.addView(scroll)

        val handle = View(activity).apply {
            setBackgroundColor(0xFFCBD5E1.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(6)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }
        body.addView(handle)

        // 头像行：览头像 + 名字版本，右边分享与设置
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(activity).apply {
            text = "览"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.avatar_circle)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val nameBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = TextView(activity).apply {
            text = "览"
            textSize = 18f
            setTextColor(0xFF111111.toInt())
        }
        val sub = TextView(activity).apply {
            text = "Chrome 内核 · $version"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
        }
        nameBox.addView(name)
        nameBox.addView(sub)
        val btnShare = Button(activity).apply {
            text = "↗"
            textSize = 20f
            setTextColor(0xFF374151.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { fire("share") }
        }
        val btnSettings = Button(activity).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(0xFF374151.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { fire("home_settings") }
        }
        header.addView(avatar)
        header.addView(nameBox)
        header.addView(btnShare)
        header.addView(btnSettings)
        body.addView(header)

        // 顶部四个大入口
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(6))
        }
        topRow.addView(bigTile("🧩", "拓展", "extensions", dark = true, last = false))
        topRow.addView(bigTile("☆", "书签", "bookmarks", dark = false, last = false))
        topRow.addView(bigTile("⭳", "下载", "downloads", dark = false, last = false))
        topRow.addView(bigTile("🕘", "历史", "history", dark = false, last = true))
        body.addView(topRow)

        // 常用功能
        val funcTitle = TextView(activity).apply {
            text = "常用功能"
            textSize = 15f
            setTextColor(0xFF111111.toInt())
            setPadding(0, dp(14), 0, dp(10))
        }
        body.addView(funcTitle)
        body.addView(funcRow(listOf(
            Triple("☆", "添加书签", "add_bookmark"),
            Triple("↻", "刷新网页", "reload"),
            Triple("💻", if (desktopUaOn) "桌面模式·开" else "桌面模式", "desktop_ua"),
            Triple("🚫", if (adBlockOn) "去广告·开" else "去广告", "adblock")
        )))
        body.addView(funcRow(listOf(
            Triple("🎬", "视频嗅探", "video_sniff"),
            Triple("▶", "视频播放", "video_settings"),
            Triple("⟳", "检查更新", "check_update"),
            Triple("📋", "日志", "log")
        )))
        body.addView(funcRow(listOf(
            Triple("🧹", "清除浏览数据", "clear_data"),
            Triple("🌐", "用系统浏览器打开", "open_system"),
            Triple("ℹ", "关于", "about"),
            Triple("✕", "退出", "exit")
        )))

        container.addView(sheet)
        sheet.translationY = sheetHeight.toFloat()
        sheet.animate().translationY(0f).setDuration(250).start()
    }

    private fun fire(key: String) {
        dismiss()
        onAction(key)
    }

    private fun bigTile(icon: String, label: String, key: String, dark: Boolean, last: Boolean): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(if (dark) R.drawable.menu_dark_tile else R.drawable.glass_card)
            setPadding(0, dp(16), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (!last) marginEnd = dp(10) }
            val ic = TextView(activity).apply {
                text = icon
                textSize = 26f
                gravity = Gravity.CENTER
            }
            val lb = TextView(activity).apply {
                text = label
                textSize = 13f
                setTextColor(if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt())
                setPadding(0, dp(6), 0, 0)
            }
            addView(ic)
            addView(lb)
            setOnClickListener { fire(key) }
        }
    }

    private fun funcRow(items: List<Triple<String, String, String>>): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        for ((index, item) in items.withIndex()) {
            val (icon, label, key) = item
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.glass_card)
                setPadding(0, dp(12), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index < items.size - 1) marginEnd = dp(10) }
                val ic = TextView(activity).apply {
                    text = icon
                    textSize = 22f
                    gravity = Gravity.CENTER
                }
                val lb = TextView(activity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(0xFF374151.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(6), dp(2), 0)
                }
                addView(ic)
                addView(lb)
                setOnClickListener { fire(key) }
            }
            row.addView(cell)
        }
        return row
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
