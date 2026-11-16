package com.suanran.lan

import android.net.Uri
import java.io.ByteArrayInputStream

/**
 * 极简去广告：按请求 host 命中已知广告/追踪域名即返回空响应。
 * 只拦第三方广告与统计域名，不动页面自身资源；v0.1 默认开启，可在菜单里关。
 */
object AdBlock {

    private val blockedSuffixes = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ads.google.com",
        "googletagservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "amazon-adsystem.com",
        "ads.yahoo.com",
        "moatads.com",
        "scorecardresearch.com",
        "adsrvr.org",
        "demdex.net",
        "rubiconproject.com",
        "pubmatic.com",
        "casalemedia.com",
        "openx.net",
        "doubleverify.com",
        "adsafeprotected.com",
        "advertising.com",
        "smartadserver.com",
        "adform.net",
        "mathtag.com",
        "bluekai.com",
        "krxd.net",
        "quantserve.com",
        "pos.baidu.com",
        "cpro.baidu.com",
        "union.baidu.com",
        "hm.baidu.com",
        "ad.baidu.com",
        "tanx.alicdn.com",
        "atanx.alicdn.com",
        "adservice.sigmob.cn",
        "pangolin-sdk-toutiao.com",
        "byteoversea.com",
        "log.sina.com.cn",
        "sax.sina.com.cn"
    )

    fun isAd(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return blockedSuffixes.any { host == it || host.endsWith(".$it") }
    }

    fun emptyResponse(): android.webkit.WebResourceResponse {
        return android.webkit.WebResourceResponse(
            "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
        )
    }
}
