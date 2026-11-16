package com.suanran.lan

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/**
 * 扩展程序页（参考 Chrome 扩展页样式）：
 * 开发者模式 / 自动更新扩展 两个开关 + 我的扩展程序列表 + 去扩展商店。
 * 内置脚本从商店安装；老用户已装的 3 个不受影响。
 * 开发者模式关掉后，商店里从链接/粘贴安装的入口会藏起来。
 */
class ExtensionsActivity : ComponentActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var devSwitch: Switch
    private lateinit var autoUpdateSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        scroll.addView(body)
        root.addView(scroll)

        // 标题行
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "扩展程序"
            textSize = 22f
            setTextColor(0xFF1F2937.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        body.addView(header)

        // 开关卡片：开发者模式 + 自动更新
        val switchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_card)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }.also { body.addView(it) }
        switchCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }

        devSwitch = Switch(this).apply {
            text = "开发者模式"
            isChecked = Store.devMode(this@ExtensionsActivity)
            setOnCheckedChangeListener { _, checked ->
                Store.setDevMode(this@ExtensionsActivity, checked)
                Toast.makeText(
                    this@ExtensionsActivity,
                    if (checked) "开发者模式已开：商店里可从链接/粘贴装脚本" else "开发者模式已关：商店里只能装内置脚本",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        switchCard.addView(devSwitch)
        switchCard.addView(TextView(this).apply {
            text = "打开后才能从链接或粘贴代码安装用户脚本"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(dp(4), 0, 0, dp(8))
        })
        autoUpdateSwitch = Switch(this).apply {
            text = "自动更新扩展"
            isChecked = Store.autoUpdateExt(this@ExtensionsActivity)
            setOnCheckedChangeListener { _, checked ->
                Store.setAutoUpdateExt(this@ExtensionsActivity, checked)
                Toast.makeText(
                    this@ExtensionsActivity,
                    if (checked) "已开：每次启动会重拉从链接装的脚本" else "自动更新已关",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        switchCard.addView(autoUpdateSwitch)
        val btnCheckScripts = Button(this).apply {
            text = "立即检查脚本更新"
            setOnClickListener {
                Toast.makeText(this@ExtensionsActivity, "正在检查…", Toast.LENGTH_SHORT).show()
                thread {
                    val n = ExtensionManager.updateFromUrls(this@ExtensionsActivity)
                    runOnUiThread {
                        Toast.makeText(
                            this@ExtensionsActivity,
                            if (n > 0) "已更新 $n 个脚本" else "没有可更新的脚本",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        switchCard.addView(btnCheckScripts)

        // 我的扩展程序
        body.addView(TextView(this).apply {
            text = "我的扩展程序"
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            setPadding(0, dp(20), 0, dp(10))
        })
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(listContainer)

        // 去商店
        val btnStore = Button(this).apply {
            text = "去扩展商店"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2563EB.toInt())
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
            setOnClickListener {
                startActivity(Intent(this@ExtensionsActivity, ExtensionStoreActivity::class.java))
            }
        }
        body.addView(btnStore)

        body.addView(TextView(this).apply {
            text = "览的扩展是用户脚本：按网址匹配，页面加载后注入 JS。WebView 跑不了 Chrome 应用商店扩展，需要的功能基本都能用脚本实现。"
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(root)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val list = ExtensionManager.list(this)
        if (list.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "还没有扩展，去商店装一个吧"
                textSize = 14f
                setTextColor(0xFF6B7280.toInt())
            })
            return
        }
        for (ext in list) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_card)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val icon = TextView(this).apply {
                text = ext.name.firstOrNull()?.toString() ?: "扩"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(if (ext.builtin) 0xFF2563EB.toInt() else 0xFF0E7490.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(this).apply {
                text = "${ext.name}  v${ext.version}" + if (ext.builtin) "（内置）" else ""
                textSize = 16f
                setTextColor(0xFF1F2937.toInt())
            }
            mid.addView(name)
            if (ext.description.isNotBlank()) {
                mid.addView(TextView(this).apply {
                    text = ext.description
                    textSize = 13f
                    setTextColor(0xFF6B7280.toInt())
                    setPadding(0, dp(2), 0, 0)
                })
            }
            mid.addView(TextView(this).apply {
                text = "匹配：" + ext.matches.joinToString("，")
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val toggle = Switch(this).apply {
                isChecked = ext.enabled
                setOnCheckedChangeListener { _, checked ->
                    ExtensionManager.setEnabled(this@ExtensionsActivity, ext.id, checked)
                }
            }
            right.addView(toggle)
            if (!ext.builtin) {
                val del = Button(this).apply {
                    text = "删除"
                    textSize = 12f
                    setOnClickListener {
                        ExtensionManager.delete(this@ExtensionsActivity, ext)
                        Toast.makeText(this@ExtensionsActivity, "已删除 ${ext.name}", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                }
                right.addView(del)
            }
            card.addView(icon)
            card.addView(mid)
            card.addView(right)
            listContainer.addView(card)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
