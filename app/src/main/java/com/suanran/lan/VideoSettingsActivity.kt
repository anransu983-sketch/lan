package com.suanran.lan

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 视频播放设置：智能播放器（嗅探到的视频推给原生播放器，只播不加密的）、
 * 媒体助手（全屏手势 + 工具箱）、悬浮小窗。
 * 投屏没做：要 Cast/DLNA 发现设备，当前没接这个 SDK，不放假入口。
 */
class VideoSettingsActivity : ComponentActivity() {

    private lateinit var modeValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var skipValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF6F5FB.toInt())
            setPadding(28, 20, 28, 40)
        }
        scroll.addView(root)

        // 标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 26f
            setBackgroundResource(android.R.drawable.btn_default)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "视频播放"
            textSize = 24f
            setTextColor(0xFF111111.toInt())
            setPadding(16, 0, 0, 0)
        }
        header.addView(back)
        header.addView(title)
        root.addView(header)

        // ---- 智能播放器 ----
        val smartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        val smartRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 8)
        }
        val smartText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val smartTitle = TextView(this).apply {
            text = "启用智能播放器"
            textSize = 18f
            setTextColor(0xFF111111.toInt())
        }
        val smartDesc = TextView(this).apply {
            text = "网页视频推到原生播放器播，体验更好。只支持不加密的视频，推流失败就是加密了"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 8, 0, 0)
        }
        smartText.addView(smartTitle)
        smartText.addView(smartDesc)
        val smartSwitch = Switch(this).apply {
            isChecked = Store.smartPlayerEnabled(this@VideoSettingsActivity)
            setOnCheckedChangeListener { _, c ->
                Store.setSmartPlayerEnabled(this@VideoSettingsActivity, c)
                Toast.makeText(
                    this@VideoSettingsActivity,
                    if (c) "智能播放器已开启" else "智能播放器已关闭，嗅探到的视频只出下载/复制选项",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        smartRow.addView(smartText)
        smartRow.addView(smartSwitch)
        smartCard.addView(smartRow)
        smartCard.addView(divider())
        // 播放显示模式
        val modeRow = optionRow("播放显示模式") { showModePicker() }
        modeValue = TextView(this).apply {
            text = when (Store.playMode(this@VideoSettingsActivity)) {
                "fullscreen" -> "全屏播放"
                "floating" -> "悬浮播放"
                else -> "智能播放器弹窗"
            }
            textSize = 16f
            setTextColor(0xFF6B7280.toInt())
        }
        (modeRow.tag as LinearLayout).addView(modeValue, 0)
        smartCard.addView(modeRow)
        smartCard.addView(divider())
        // 长按倍速
        val speedRow = optionRow("长按倍速设置") { showSpeedPicker() }
        speedValue = TextView(this).apply {
            text = "x${Store.longPressSpeed(this@VideoSettingsActivity)}"
            textSize = 16f
            setTextColor(0xFF6B7280.toInt())
        }
        (speedRow.tag as LinearLayout).addView(speedValue, 0)
        smartCard.addView(speedRow)
        smartCard.addView(divider())
        // 双击快进/快退
        val skipRow = optionRow("双击右侧快进 / 左侧快退进度") { showSkipPicker() }
        skipValue = TextView(this).apply {
            text = "${Store.skipSeconds(this@VideoSettingsActivity)}s"
            textSize = 16f
            setTextColor(0xFF6B7280.toInt())
        }
        (skipRow.tag as LinearLayout).addView(skipValue, 0)
        smartCard.addView(skipRow)
        smartCard.addView(divider())
        // 校验可播放性
        smartCard.addView(switchRow("校验可播放性再弹出，耗时约 2 秒", Store.checkPlayableEnabled(this)) { checked ->
            Store.setCheckPlayableEnabled(this, checked)
        })
        root.addView(smartCard)

        // ---- 媒体助手 ----
        root.addView(sectionTitle("媒体助手"))
        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 8)
        }
        val mediaText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mediaTitle = TextView(this).apply {
            text = "开启媒体助手"
            textSize = 18f
            setTextColor(0xFF111111.toInt())
        }
        val mediaDesc = TextView(this).apply {
            text = "全屏播放时：手势快进后退、长按倍速、上下滑动调倍速、横滑调进度、自定义倍速"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 8, 0, 0)
        }
        mediaText.addView(mediaTitle)
        mediaText.addView(mediaDesc)
        val mediaSwitch = Switch(this).apply {
            isChecked = Store.mediaAssistantEnabled(this@VideoSettingsActivity)
            setOnCheckedChangeListener { _, c ->
                Store.setMediaAssistantEnabled(this@VideoSettingsActivity, c)
            }
        }
        mediaRow.addView(mediaText)
        mediaRow.addView(mediaSwitch)
        mediaCard.addView(mediaRow)
        mediaCard.addView(divider())
        mediaCard.addView(switchRow("默认展示工具箱", Store.showToolboxEnabled(this)) { checked ->
            Store.setShowToolboxEnabled(this, checked)
        })
        root.addView(mediaCard)

        // ---- 悬浮小窗 ----
        root.addView(sectionTitle("悬浮小窗"))
        val floatCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        floatCard.addView(switchRow("滑动调节亮度与声音", Store.floatGestureEnabled(this)) { checked ->
            Store.setFloatGestureEnabled(this, checked)
        })
        floatCard.addView(divider())
        floatCard.addView(switchRow("自动隐藏悬浮控制器", Store.floatAutohideEnabled(this)) { checked ->
            Store.setFloatAutohideEnabled(this, checked)
        })
        floatCard.addView(divider())
        floatCard.addView(infoRow("单击 ×", "缩小为悬浮球"))
        floatCard.addView(divider())
        floatCard.addView(infoRow("长按 ×", "彻底关闭"))
        root.addView(floatCard)

        val note = TextView(this).apply {
            text = "悬浮播放是应用内小窗，切到别的 App 会停；投屏还没接，暂时播不了电视。"
            textSize = 13f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(8, 28, 8, 0)
        }
        root.addView(note)

        setContentView(scroll)
    }

    private fun showModePicker() {
        val labels = arrayOf("智能播放器弹窗", "全屏播放", "悬浮播放")
        val values = arrayOf("sheet", "fullscreen", "floating")
        val current = values.indexOf(Store.playMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("播放显示模式")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Store.setPlayMode(this, values[which])
                modeValue.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSpeedPicker() {
        val labels = arrayOf("x1.5", "x2.0", "x2.5", "x3.0")
        val values = arrayOf(1.5f, 2.0f, 2.5f, 3.0f)
        val current = values.indexOf(Store.longPressSpeed(this)).coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("长按倍速设置")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Store.setLongPressSpeed(this, values[which])
                speedValue.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSkipPicker() {
        val labels = arrayOf("5s", "10s", "15s", "30s")
        val values = arrayOf(5, 10, 15, 30)
        val current = values.indexOf(Store.skipSeconds(this)).coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("双击快进 / 快退进度")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Store.setSkipSeconds(this, values[which])
                skipValue.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(0xFF111111.toInt())
        setPadding(8, 36, 0, 16)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFFE5E7EB.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
    }

    private fun optionRow(label: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 28, 24, 28)
            setOnClickListener { onClick() }
        }
        val left = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val arrow = TextView(this).apply {
            text = " ›"
            textSize = 20f
            setTextColor(0xFF9CA3AF.toInt())
        }
        right.addView(arrow)
        row.addView(left)
        row.addView(right)
        row.tag = right
        return row
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
        }
        val left = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(left)
        row.addView(sw)
        return row
    }

    private fun infoRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 28, 24, 28)
        }
        val left = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(0xFF6B7280.toInt())
        }
        row.addView(left)
        row.addView(right)
        return row
    }
}
