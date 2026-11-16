package com.suanran.lan

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志：写到内部存储 lan-log.txt，日志页查看/清空/分享。
 * 记关键事件（打开页面、抓到视频、下载、更新检查、异常），不记密码与完整 Cookie。
 */
object AppLog {

    private const val FILE_NAME = "lan-log.txt"
    private const val MAX_BYTES = 200 * 1024L

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun write(context: Context, level: String, tag: String, msg: String) {
        try {
            val file = logFile(context)
            if (file.exists() && file.length() > MAX_BYTES) {
                // 超 200KB 就扔掉前一半，只留近期的
                val lines = file.readLines()
                val keep = lines.drop(lines.size / 2)
                file.writeText(keep.joinToString("\n") + "\n")
            }
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.appendText("$time [$level/$tag] $msg\n")
        } catch (e: Exception) {
        }
    }

    fun i(context: Context, tag: String, msg: String) = write(context, "I", tag, msg)

    fun e(context: Context, tag: String, msg: String, t: Throwable? = null) =
        write(context, "E", tag, if (t != null) "$msg：${t.message}" else msg)

    fun readAll(context: Context): String {
        return try {
            val file = logFile(context)
            if (file.exists()) file.readText() else "暂无日志"
        } catch (e: Exception) {
            "读取日志失败：${e.message}"
        }
    }

    fun clear(context: Context) {
        try {
            logFile(context).delete()
        } catch (e: Exception) {
        }
    }
}
