package com.suanran.lan

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 主页设置：览原创 7 套主页风格切换 + 自定义主页网址 + 通用选项。
 * 风格名和配色都是览自己的（雾蓝 / 墨夜 / 星野 / 青瓷 / 暮山 / 纸鸢 / 素白），
 * 不照搬别的浏览器。
 */
class HomeSettingsActivity : ComponentActivity() {

    data class HomeStyle(val id: String, val name: String, val bg: Int, val accent: Int, val motif: String)

    private val styles = listOf(
        HomeStyle("wulan", "雾蓝", 0xFFEAF2FB.toInt(), 0xFF3B82C4.toInt(), "雾"),
        HomeStyle("moye", "墨夜", 0xFF14161A.toInt(), 0xFFE8A33D.toInt(), "墨"),
        HomeStyle("xingye", "星野", 0xFF101736.toInt(), 0xFF8B9CF9.toInt(), "星"),
        HomeStyle("qingci", "青瓷", 0xFFEAF3EE.toInt(), 0xFF4E8A6E.toInt(), "瓷"),
        HomeStyle("mushan", "暮山", 0xFFF7E8E0.toInt(), 0xFFC65D3B.toInt(), "山"),
        HomeStyle("zhiyuan", "纸鸢", 0xFFFBF3E4.toInt(), 0xFFC0392B.toInt(), "鸢"),
        HomeStyle("subai", "素白", 0xFFFFFFFF.toInt(), 0xFF6B7280.toInt(), "白")
    )

    private lateinit var cardsRow: LinearLayout
    private lateinit var customHomeInput: EditText
    private lateinit var engineValue: TextView

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
            text = "主页设置"
            textSize = 24f
            setTextColor(0xFF111111.toInt())
            setPadding(16, 0, 0, 0)
        }
        header.addView(back)
        header.addView(title)
        root.addView(header)

        root.addView(sectionTitle("主页模式"))
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hScroll.addView(cardsRow)
        root.addView(hScroll)
        refreshCards()

        root.addView(sectionTitle("自定义主页"))
        customHomeInput = EditText(this).apply {
            hint = "设置网址为主页"
            setText(Store.customHome(this@HomeSettingsActivity))
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(28, 28, 28, 28)
            textSize = 16f
        }
        root.addView(customHomeInput)
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val saveCustom = Button(this).apply {
            text = "保存"
            setOnClickListener {
                val url = customHomeInput.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this@HomeSettingsActivity, "网址要带 http:// 或 https://", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Store.setCustomHome(this@HomeSettingsActivity, url)
                Toast.makeText(
                    this@HomeSettingsActivity,
                    if (url.isEmpty()) "已恢复内置起始页" else "主页已设为 $url",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val clearCustom = Button(this).apply {
            text = "恢复内置"
            setOnClickListener {
                Store.setCustomHome(this@HomeSettingsActivity, "")
                customHomeInput.setText("")
                Toast.makeText(this@HomeSettingsActivity, "已恢复内置起始页", Toast.LENGTH_SHORT).show()
            }
        }
        customRow.addView(saveCustom)
        customRow.addView(clearCustom)
        root.addView(customRow)

        root.addView(sectionTitle("通用选项"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        // 搜索引擎
        val engineRow = optionRow("搜索引擎") {
            showEnginePicker()
        }
        engineValue = TextView(this).apply {
            text = engineName(Store.searchEngine(this@HomeSettingsActivity))
            textSize = 16f
            setTextColor(0xFF6B7280.toInt())
        }
        (engineRow.tag as LinearLayout).addView(engineValue, 0)
        card.addView(engineRow)
        card.addView(divider())
        // 显示最近访问
        card.addView(switchRow("显示最近访问", Store.showRecent(this)) { checked ->
            Store.setShowRecent(this, checked)
        })
        card.addView(divider())
        // 显示快捷方式
        card.addView(switchRow("显示快捷方式", Store.showTiles(this)) { checked ->
            Store.setShowTiles(this, checked)
        })
        root.addView(card)

        setContentView(scroll)
    }

    private fun engineName(engine: String): String = when (engine) {
        "baidu" -> "百度"
        "google" -> "Google"
        else -> "必应"
    }

    private fun showEnginePicker() {
        val engines = arrayOf("必应", "百度", "Google")
        val values = arrayOf("bing", "baidu", "google")
        val current = values.indexOf(Store.searchEngine(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("搜索引擎")
            .setSingleChoiceItems(engines, current) { dialog, which ->
                Store.setSearchEngine(this, values[which])
                engineValue.text = engines[which]
                Toast.makeText(this, "搜索已切换为 ${engines[which]}", Toast.LENGTH_SHORT).show()
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

    /** 一行：左标题，右一个可点的容器（放值或箭头），点整行触发 onClick。 */
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
        // 把 right 容器暴露给调用方往里加值：用 tag 存
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

    private fun refreshCards() {
        cardsRow.removeAllViews()
        val current = Store.homeStyle(this)
        for (style in styles) {
            val selected = style.id == current
            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(6, 6, 6, 6)
                setBackgroundColor(if (selected) style.accent else Color.TRANSPARENT)
            }
            val preview = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(style.bg)
                layoutParams = LinearLayout.LayoutParams(300, 420)
                setPadding(16, 16, 16, 16)
            }
            val logo = TextView(this).apply {
                text = style.motif
                textSize = 44f
                setTextColor(style.accent)
                gravity = Gravity.CENTER
            }
            preview.addView(logo)
            val bar = View(this).apply {
                setBackgroundColor(if (style.id == "moye" || style.id == "xingye") 0x33FFFFFF else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(200, 30).apply {
                    topMargin = 28
                }
            }
            preview.addView(bar)
            val plus = TextView(this).apply {
                text = "···"
                textSize = 22f
                setTextColor(if (style.id == "moye" || style.id == "xingye") 0xFF9CA3AF.toInt() else 0xFF9CA3AF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }
            preview.addView(plus)
            outer.addView(preview)
            val name = TextView(this).apply {
                text = style.name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (selected) style.accent else 0xFF111111.toInt())
                setPadding(0, 12, 0, 8)
            }
            outer.addView(name)
            outer.setOnClickListener {
                Store.setHomeStyle(this, style.id)
                Toast.makeText(this, "主页已切换为${style.name}", Toast.LENGTH_SHORT).show()
                refreshCards()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 28 }
            cardsRow.addView(outer, params)
        }
    }
}
