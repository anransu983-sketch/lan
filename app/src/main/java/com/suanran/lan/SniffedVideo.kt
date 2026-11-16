package com.suanran.lan

/** 嗅探到的视频直链；sizeBytes 由 HEAD 测 Content-Length 得到，-1 = 未知。 */
data class SniffedVideo(val url: String, val via: String, var sizeBytes: Long = -1)
