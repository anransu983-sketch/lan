package com.suanran.lan

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内检测更新：读仓库里的 version.json（raw.githubusercontent，和 GKD 订阅同一条通道），
 * version_code 比当前大就弹框提示下载，不走应用商店、不走 GitHub API（免登录、不限流）。
 * version.json 格式：{"version":"0.7.0","version_code":10,"apk":".../releases/lan-0.7.0.apk","notes":"..."}
 */
object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/anransu983-sketch/lan/main/version.json"

    private fun currentCode(activity: ComponentActivity): Int {
        return try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (e: Exception) {
            0
        }
    }

    /** silent=true：自动检查（启动时），有更新才弹框，没更新/失败都静默；silent=false：手动检查，一定给回音 */
    fun check(activity: ComponentActivity, silent: Boolean) {
        Thread {
            try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.connect()
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(text)
                val latestCode = json.optInt("version_code", 0)
                val latestVersion = json.optString("version", "")
                val apkUrl = json.optString("apk", "")
                val notes = json.optString("notes", "")
                val current = currentCode(activity)
                AppLog.i(
                    activity, "更新",
                    "检查完成：当前 vc=$current，最新 vc=$latestCode（$latestVersion）"
                )
                activity.runOnUiThread {
                    if (latestCode > current && apkUrl.isNotBlank()) {
                        AlertDialog.Builder(activity)
                            .setTitle("发现新版本 $latestVersion")
                            .setMessage(if (notes.isBlank()) "览有新版本可以更新。" else notes)
                            .setPositiveButton("下载") { _, _ ->
                                downloadApk(activity, apkUrl, latestVersion)
                            }
                            .setNegativeButton("以后再说", null)
                            .show()
                    } else if (!silent) {
                        Toast.makeText(
                            activity,
                            if (latestCode <= 0) "未找到更新信息" else "已是最新版本",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                AppLog.e(activity, "更新", "检查失败", e)
                if (!silent) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "检查更新失败，请稍后再试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun downloadApk(activity: ComponentActivity, url: String, version: String) {
        try {
            val fileName = "lan-$version.apk"
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("览 $version 更新包")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            AppLog.i(activity, "更新", "开始下载 $fileName")
            Toast.makeText(activity, "开始下载更新包，完成后点通知安装", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.e(activity, "更新", "下载更新包失败", e)
            Toast.makeText(activity, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
