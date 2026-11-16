package com.suanran.lan

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 扩展商店：览能装的用户脚本卡片（图标 + 名字 + 简介 + 安装按钮），
 * 底部是从链接 / 粘贴代码安装（要开开发者模式才显示）。
 *
 * 诚实说明：这里装的是用户脚本，不是 Chrome 应用商店扩展——
 * WebView 跑不了 Chrome 扩展，翻译 / 阅读 / 去广告都按这个路子做了等价脚本。
 */
class ExtensionStoreActivity : ComponentActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var installBox: LinearLayout

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

        val title = TextView(this).apply {
            text = "扩展商店"
            textSize = 22f
            setTextColor(0xFF1F2937.toInt())
        }
        body.addView(title)
        body.addView(TextView(this).apply {
            text = "览能用的用户脚本，点安装就能用。Chrome 应用商店的扩展装不了，那是 Chromium 内核的活。"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(6), 0, dp(16))
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(listContainer)

        // 从其他地方安装
        body.addView(TextView(this).apply {
            text = "从其他地方安装"
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            setPadding(0, dp(18), 0, dp(10))
        })
        installBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(installBox)

        setContentView(root)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val installed = ExtensionManager.list(this).map { it.id }.toSet()
        for (b in ExtensionManager.catalogItems()) {
            val isInstalled = installed.contains(b.id)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_card)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            val icon = TextView(this).apply {
                text = b.name.firstOrNull()?.toString() ?: "扩"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(b.color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = b.name
                textSize = 16f
                setTextColor(0xFF1F2937.toInt())
            }
            nameRow.addView(name)
            if (b.badge.isNotBlank()) {
                val badge = TextView(this).apply {
                    text = b.badge
                    textSize = 11f
                    setTextColor(0xFFFFFFFF.toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(if (b.badge == "推荐") 0xFFF97316.toInt() else 0xFF2563EB.toInt())
                    }
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(8) }
                }
                nameRow.addView(badge)
            }
            mid.addView(nameRow)
            mid.addView(TextView(this).apply {
                text = b.description
                textSize = 13f
                setTextColor(0xFF6B7280.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            val installBtn = Button(this).apply {
                text = if (isInstalled) "已安装" else "安装"
                textSize = 14f
                isEnabled = !isInstalled
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(if (isInstalled) 0xFF9CA3AF.toInt() else 0xFF2563EB.toInt())
                setPadding(dp(20), dp(8), dp(20), dp(8))
                setOnClickListener {
                    val ext = ExtensionManager.installBuiltin(this@ExtensionStoreActivity, b.id)
                    if (ext != null) {
                        Toast.makeText(this@ExtensionStoreActivity, "已安装 ${ext.name}", Toast.LENGTH_SHORT).show()
                        AppLog.i(this@ExtensionStoreActivity, "扩展", "从商店安装 ${ext.name}")
                        refreshList()
                    }
                }
            }
            card.addView(icon)
            card.addView(mid)
            card.addView(installBtn)
            listContainer.addView(card)
        }

        // 从链接 / 粘贴安装：开发者模式开才显示
        installBox.removeAllViews()
        if (Store.devMode(this)) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val btnUrl = Button(this).apply { text = "从链接安装" }
            val btnPaste = Button(this).apply { text = "粘贴代码安装" }
            btnUrl.setOnClickListener { showInstallFromUrlDialog() }
            btnPaste.setOnClickListener { showPasteDialog() }
            btnRow.addView(btnUrl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(btnPaste, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            installBox.addView(btnRow)
            installBox.addView(TextView(this).apply {
                text = "从链接装的脚本会记下地址，开了「自动更新扩展」后每次启动都会重拉最新版。"
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, dp(8), 0, 0)
            })
        } else {
            installBox.addView(TextView(this).apply {
                text = "开发者模式已关闭：去「扩展程序」页打开后，才能从链接或粘贴代码安装。"
                textSize = 13f
                setTextColor(0xFF6B7280.toInt())
            })
        }
    }

    private fun showInstallFromUrlDialog() {
        val input = EditText(this).apply {
            hint = "输入 .user.js 脚本链接，如 https://…/xxx.user.js"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("从链接安装")
            .setView(input)
            .setPositiveButton("安装") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                installFromUrl(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun installFromUrl(url: String) {
        Toast.makeText(this, "正在下载脚本…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Lan/0.7.0")
                val code = conn.inputStream.bufferedReader().readText()
                runOnUiThread {
                    if (code.trimStart().startsWith("<")) {
                        Toast.makeText(
                            this,
                            "下到的是网页不是脚本：请用 raw 链接（如 greasyfork.org/scripts/…/code.user.js 或 github raw 链接）",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    val ext = ExtensionManager.installFromCode(
                        this, code, url.substringAfterLast("/"), sourceUrl = url
                    )
                    if (ext != null) {
                        Toast.makeText(this, "已安装 ${ext.name}", Toast.LENGTH_SHORT).show()
                        AppLog.i(this, "扩展", "从链接安装 ${ext.name}")
                        refreshList()
                    } else {
                        Toast.makeText(this, "脚本为空，安装失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPasteDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val nameInput = EditText(this).apply { hint = "扩展名字（可选，脚本里有 @name 就用它的）" }
        val codeInput = EditText(this).apply {
            hint = "粘贴脚本代码"
            minLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(nameInput)
        container.addView(codeInput)
        AlertDialog.Builder(this)
            .setTitle("粘贴代码安装")
            .setView(container)
            .setPositiveButton("安装") { _, _ ->
                val code = codeInput.text.toString()
                val fallback = nameInput.text.toString().trim().ifBlank { "未命名扩展" }
                val ext = ExtensionManager.installFromCode(this, code, fallback)
                if (ext != null) {
                    Toast.makeText(this, "已安装 ${ext.name}", Toast.LENGTH_SHORT).show()
                    AppLog.i(this, "扩展", "粘贴安装 ${ext.name}")
                    refreshList()
                } else {
                    Toast.makeText(this, "脚本为空，安装失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
