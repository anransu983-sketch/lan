package com.suanran.lan

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** 日志页：显示 lan-log.txt，刷新 / 清空 / 分享（以文字分享出去）。 */
class LogActivity : ComponentActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val title = TextView(this).apply {
            text = "日志"
            textSize = 20f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRefresh = Button(this).apply {
            text = "刷新"
            setOnClickListener { refresh() }
        }
        val btnClear = Button(this).apply {
            text = "清空"
            setOnClickListener {
                AppLog.clear(this@LogActivity)
                refresh()
                Toast.makeText(this@LogActivity, "日志已清空", Toast.LENGTH_SHORT).show()
            }
        }
        val btnShare = Button(this).apply {
            text = "分享"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, AppLog.readAll(this@LogActivity))
                    putExtra(Intent.EXTRA_SUBJECT, "览日志")
                }
                startActivity(Intent.createChooser(send, "分享日志"))
            }
        }
        bar.addView(title)
        bar.addView(btnRefresh)
        bar.addView(btnClear)
        bar.addView(btnShare)
        root.addView(bar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF1F2937.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(logView)
        root.addView(scroll)

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        logView.text = AppLog.readAll(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
