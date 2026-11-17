package com.suanran.lan

/**
 * 嗅探到的视频直链；sizeBytes 由 HEAD 测 Content-Length 得到，-1 = 未知。
 * isPlaylist：m3u8 播放列表（要走 HLS 合并下载，不能当单个文件下）。
 * isSegment：HLS 分片（index-*.mp4 / *.ts / *.m4s 等，单独下就是坏文件，不进列表）。
 */
data class SniffedVideo(
    val url: String,
    val via: String,
    var sizeBytes: Long = -1,
    val isPlaylist: Boolean = false,
    val isSegment: Boolean = false
) {
    /** 列表里显示的名字：播放列表去掉 .m3u8 后缀并标注，分片不显示（本就不进列表）。 */
    fun displayName(): String {
        val raw = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
            .ifBlank { url }
        return if (isPlaylist) raw.removeSuffix(".m3u8") + "（m3u8）" else raw
    }
}
