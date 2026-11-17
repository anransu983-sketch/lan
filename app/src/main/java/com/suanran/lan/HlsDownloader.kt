package com.suanran.lan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS 合并下载器（0.7.1 新增）。
 *
 * 针对只给 m3u8 分片流的站（例如视频走 POST /api/v2/vod/playurl 取 play_url，
 * 播放器再拉 m3u8 的站）：DownloadManager 只能下一个文件，下分片 index-1.mp4
 * 得到的是 128KB 坏文件，所以这里自己拉播放列表、按顺序下全部分片再拼接。
 *
 * 支持：
 * - 主播放列表（#EXT-X-STREAM-INF）：选 BANDWIDTH 最高的清晰度子列表；
 * - fmp4（有 #EXT-X-MAP init 分片）：init + 分片顺序拼接成可播 .mp4；
 * - TS：分片顺序拼接成 .ts（可播即可）；
 * - EXT-X-KEY AES-128：取 key + IV 解密（IV 缺省用分片序号）；METHOD 不是
 *   AES-128/NONE 时明确报错，不出坏文件；
 * - play_url 直接就是 mp4 文件时：直接整文件下载（不走拼接）。
 *
 * 不支持：SAMPLE-AES 等其它加密方式、直播（无 #EXT-X-ENDLIST 时只下列表里已有的分片）。
 * 所有请求都带上当前页的 Referer / UA / Cookie；分片失败重试一次。
 */
object HlsDownloader {

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000
    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    private const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    /**
     * 后台线程下载。fileNameHint 一般传页面标题；为空或太通用时用时间戳命名，
     * 绝不用 index-1.mp4 这类分片名。
     */
    fun download(
        context: Context,
        playlistUrl: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
        fileNameHint: String
    ) {
        if (running) {
            toast(context, "已有 HLS 下载在进行，稍等它完成")
            return
        }
        running = true
        val appContext = context.applicationContext
        Thread {
            try {
                runDownload(appContext, playlistUrl, referer, userAgent, cookie, fileNameHint)
            } catch (e: Exception) {
                AppLog.e(appContext, "HLS下载", "下载失败", e)
                toast(appContext, "下载失败：${e.message ?: "未知错误"}")
            } finally {
                running = false
            }
        }.start()
    }

    // ---------- 入口流程 ----------

    private fun runDownload(
        context: Context,
        playlistUrl: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
        fileNameHint: String
    ) {
        toast(context, "开始合并下载…")
        AppLog.i(context, "HLS下载", "开始：$playlistUrl")
        val headers = Headers(referer, userAgent, cookie)

        // 先看首包：是 m3u8 文本就走拼接；是视频文件就直接整文件下。
        val first = openConn(playlistUrl, headers)
        val code = first.responseCode
        if (code !in 200..299) {
            first.disconnect()
            throw IllegalStateException("播放列表请求失败 HTTP $code")
        }
        val contentType = first.contentType ?: ""
        val input = BufferedInputStream(first.inputStream, 8192)
        input.mark(16384)
        val peek = ByteArray(16384)
        var peekLen = 0
        while (peekLen < peek.size) {
            val n = input.read(peek, peekLen, peek.size - peekLen)
            if (n < 0) break
            peekLen += n
        }
        val peekText = String(peek, 0, peekLen, Charsets.UTF_8).trimStart()
        if (!peekText.startsWith("#EXTM3U")) {
            // 不是播放列表：当成直链文件整包下（play_url 直接给 mp4 的情况）。
            val ext = when {
                contentType.contains("mp4") -> "mp4"
                playlistUrl.lowercase().substringBefore("?").endsWith(".mp4") -> "mp4"
                contentType.contains("mp2t") || contentType.contains("mpeg") -> "ts"
                else -> "mp4"
            }
            val name = uniqueName(context, sanitizeFileName(fileNameHint), ext)
            val tmp = File(context.cacheDir, "hls_direct_${System.currentTimeMillis()}.tmp")
            try {
                // reset() 后流回到开头，直接整流写出；peek 只是用来判断类型，不要再单独写一遍。
                input.reset()
                tmp.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    if (total <= 0) throw IllegalStateException("文件是空的")
                }
                saveToDownloads(context, tmp, name, if (ext == "ts") "video/mp2t" else "video/mp4")
                toast(context, "下载完成：$name（${formatSize(tmp.length())}）")
                AppLog.i(context, "HLS下载", "直链文件完成：$name")
            } finally {
                try { input.close() } catch (e: Exception) { }
                first.disconnect()
                tmp.delete()
            }
            return
        }
        // 是播放列表：把首包 + 剩余读成文本。
        input.reset()
        val playlistText = readTextCapped(input, MAX_PLAYLIST_BYTES)
        try { input.close() } catch (e: Exception) { }
        first.disconnect()
        if (!playlistText.trimStart().startsWith("#EXTM3U")) {
            throw IllegalStateException("返回的不是 m3u8 播放列表，可能是验证页或链接已过期")
        }

        val mediaText = resolveMasterPlaylist(playlistText, playlistUrl, headers)
        val parsed = parseMediaPlaylist(mediaText, playlistUrl)
        if (parsed.segments.isEmpty()) {
            throw IllegalStateException("播放列表里没有分片")
        }
        val isFmp4 = parsed.initUri != null ||
            parsed.segments.first().url.lowercase().substringBefore("?")
                .endsWith(".mp4") ||
            parsed.segments.first().url.lowercase().substringBefore("?")
                .endsWith(".m4s")
        val ext = if (isFmp4) "mp4" else "ts"
        val name = uniqueName(context, sanitizeFileName(fileNameHint), ext)
        val tmp = File(context.cacheDir, "hls_${System.currentTimeMillis()}.tmp")
        try {
            var done = 0
            var downloadedBytes = 0L
            var lastMilestone = 0
            tmp.outputStream().use { out ->
                if (parsed.initUri != null) {
                    val initBytes = getBytes(parsed.initUri, headers)
                        ?: throw IllegalStateException("init 分片下载失败")
                    out.write(initBytes)
                    downloadedBytes += initBytes.size
                }
                for ((index, seg) in parsed.segments.withIndex()) {
                    val raw = getBytes(seg.url, headers)
                        ?: throw IllegalStateException("第 ${index + 1}/${parsed.segments.size} 个分片下载失败")
                    val bytes = if (seg.key != null) decryptSegment(raw, seg.key, index) else raw
                    out.write(bytes)
                    downloadedBytes += bytes.size
                    done++
                    val pct = done * 100 / parsed.segments.size
                    if (pct >= lastMilestone + 25) {
                        lastMilestone = pct - pct % 25
                        toast(context, "合并下载中…$pct%（$done/${parsed.segments.size}）")
                    }
                }
                out.flush()
            }
            if (tmp.length() <= 0) throw IllegalStateException("拼接后文件是空的")
            saveToDownloads(context, tmp, name, if (ext == "ts") "video/mp2t" else "video/mp4")
            toast(context, "下载完成：$name（${formatSize(tmp.length())}，${parsed.segments.size} 个分片）")
            AppLog.i(
                context, "HLS下载",
                "完成：$name ${formatSize(tmp.length())} ${parsed.segments.size} 分片"
            )
        } finally {
            tmp.delete()
        }
    }

    // ---------- 播放列表解析 ----------

    private data class Headers(val referer: String?, val userAgent: String?, val cookie: String?)

    private data class KeyInfo(val method: String, val uri: String?, val ivHex: String?)

    private data class Segment(val url: String, val key: KeyInfo?)

    private data class MediaPlaylist(val initUri: String?, val segments: List<Segment>)

    /** 主播放列表：选 BANDWIDTH 最高的变体；已经是媒体列表时原样返回。 */
    private fun resolveMasterPlaylist(text: String, baseUrl: String, headers: Headers): String {
        val lines = text.lines()
        var bestBandwidth = -1L
        var bestUri: String? = null
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)
                ?.toLongOrNull() ?: 0L
            val uriLine = lines.subList(i + 1, lines.size)
                .firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim()
                ?: continue
            if (bw >= bestBandwidth) {
                bestBandwidth = bw
                bestUri = uriLine
            }
        }
        if (bestUri == null) return text
        val variantUrl = resolveUrl(baseUrl, bestUri)
        val variantText = fetchText(variantUrl, headers)
            ?: throw IllegalStateException("清晰度子列表下载失败")
        if (!variantText.trimStart().startsWith("#EXTM3U")) {
            throw IllegalStateException("清晰度子列表不是 m3u8")
        }
        return variantText
    }

    private fun parseMediaPlaylist(text: String, baseUrl: String): MediaPlaylist {
        var initUri: String? = null
        var curKey: KeyInfo? = null
        val segments = mutableListOf<Segment>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
                        ?: Regex("URI=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    if (uri != null) initUri = resolveUrl(baseUrl, uri)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]+)").find(line)?.groupValues
                        ?.getOrNull(1)?.trim() ?: "NONE"
                    if (method == "NONE") {
                        curKey = null
                    } else if (method != "AES-128") {
                        throw IllegalStateException("视频已加密（METHOD=$method），暂不支持解密")
                    } else {
                        val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
                            ?: Regex("URI=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                        val iv = Regex("IV=0x([0-9a-fA-F]+)").find(line)?.groupValues?.getOrNull(1)
                        curKey = KeyInfo(method, uri?.let { resolveUrl(baseUrl, it) }, iv)
                    }
                }
                line.startsWith("#") -> { /* 其它标签忽略 */ }
                else -> segments.add(Segment(resolveUrl(baseUrl, line), curKey))
            }
        }
        return MediaPlaylist(initUri, segments)
    }

    // ---------- 网络 ----------

    private fun openConn(url: String, headers: Headers): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("User-Agent", headers.userAgent ?: DEFAULT_UA)
        if (!headers.referer.isNullOrBlank()) conn.setRequestProperty("Referer", headers.referer)
        if (!headers.cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", headers.cookie)
        return conn
    }

    /** 取文本（播放列表），失败重试一次；超过上限截断并报错。 */
    private fun fetchText(url: String, headers: Headers): String? {
        repeat(2) { attempt ->
            try {
                val conn = openConn(url, headers)
                try {
                    if (conn.responseCode !in 200..299) return null
                    val text = readTextCapped(conn.inputStream, MAX_PLAYLIST_BYTES)
                    if (text.length >= MAX_PLAYLIST_BYTES) return null
                    return text
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                Thread.sleep(1000)
            }
        }
        return null
    }

    /** 取分片/密钥字节，失败重试一次；超过上限返回 null。 */
    private fun getBytes(url: String, headers: Headers): ByteArray? {
        repeat(2) { attempt ->
            try {
                val conn = openConn(url, headers)
                try {
                    if (conn.responseCode !in 200..299) return null
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = conn.inputStream.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SEGMENT_BYTES) return null
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                try { Thread.sleep(1000) } catch (ignored: Exception) { }
            }
        }
        return null
    }

    private fun readTextCapped(stream: java.io.InputStream, cap: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun resolveUrl(base: String, rel: String): String {
        val r = rel.trim()
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        return URL(URL(base), r).toString()
    }

    // ---------- AES-128 解密 ----------

    private fun decryptSegment(data: ByteArray, keyInfo: KeyInfo, segmentIndex: Int): ByteArray {
        val keyUri = keyInfo.uri
            ?: throw IllegalStateException("加密视频没有给 key 地址，无法解密")
        val keyBytes = getBytes(keyUri, Headers(null, null, null))
            ?: throw IllegalStateException("取不到解密 key")
        if (keyBytes.size != 16) {
            throw IllegalStateException("解密 key 长度不对（${keyBytes.size} 字节，应为 16）")
        }
        val iv = keyInfo.ivHex?.let { hexToBytes(it) } ?: sequenceIv(segmentIndex)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(iv)
            )
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw IllegalStateException("分片解密失败（key/IV 可能不对）：${e.message}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X")
        val padded = clean.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i ->
            padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** IV 缺省时用分片序号（HLS 规范的默认做法）：16 字节大端，后 4 字节为序号。 */
    private fun sequenceIv(index: Int): ByteArray {
        val iv = ByteArray(16)
        iv[12] = (index ushr 24).toByte()
        iv[13] = (index ushr 16).toByte()
        iv[14] = (index ushr 8).toByte()
        iv[15] = index.toByte()
        return iv
    }

    // ---------- 落盘 ----------

    private fun saveToDownloads(context: Context, tmp: File, displayName: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("写 Download 目录失败（MediaStore 拒绝）")
            resolver.openOutputStream(uri)?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("写 Download 目录失败（打不开输出流）")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, displayName)
            tmp.copyTo(dest, overwrite = true)
        }
    }

    /** 同名文件已存在时加 _2、_3…，不覆盖旧文件。 */
    private fun uniqueName(context: Context, base: String, ext: String): String {
        var candidate = "$base.$ext"
        var n = 2
        while (existsInDownloads(context, candidate)) {
            candidate = "${base}_$n.$ext"
            n++
        }
        return candidate
    }

    private fun existsInDownloads(context: Context, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(displayName),
                    null
                )?.use { it.count > 0 } ?: false
            } catch (e: Exception) {
                false
            }
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(dir, displayName).exists()
    }

    // ---------- 文件名 ----------

    private val GENERIC_TITLES = setOf(
        "视频", "视频播放", "新标签页", "览 · 首页", "index", "playlist", "master"
    )

    fun sanitizeFileName(hint: String): String {
        var name = hint.trim()
            .removeSuffix(".m3u8").removeSuffix(".mp4")
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('_', ' ', '.')
            .take(60)
        if (name.isBlank() || name in GENERIC_TITLES) {
            name = "视频_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }
        return name
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }

    private fun toast(context: Context, msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
            }
        }
    }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
