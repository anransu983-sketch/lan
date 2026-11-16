package com.suanran.lan

import android.content.Context
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LanExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val fileName: String,
    val enabled: Boolean,
    val builtin: Boolean,
    /** 从链接装的脚本记下来源，自动更新时重拉；内置与粘贴装的为空 */
    val sourceUrl: String = ""
)

/**
 * 扩展系统（用户脚本式）。
 *
 * 先说清楚上限：Android System WebView 跑不了 Chrome 应用商店扩展，
 * 那需要自带 Chromium 内核重编，手机壳浏览器做不到。所以览的扩展走
 * Tampermonkey 式用户脚本：按网址匹配，在页面加载完后注入 JS。
 * 去广告增强、深色模式、视频倍速这类需求都能用它实现。
 */
object ExtensionManager {

    private const val PREFS = "lan_ext"
    private const val KEY_INDEX = "index"
    private const val DIR = "extensions"

    // ---------- 内置扩展脚本 ----------

    private val builtinAdblockJs = """
        (function(){
          var css = '.adsbygoogle,.advertisement,[id^="ad-"],[class*="advert"],[id*="google_ads"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"]{display:none!important}';
          function hide(){
            var s = document.getElementById('lan-adblock');
            if(!s){ s = document.createElement('style'); s.id = 'lan-adblock'; s.textContent = css; document.documentElement.appendChild(s); }
            document.querySelectorAll('iframe[src*="doubleclick"],iframe[src*="googlesyndication"]').forEach(function(el){ el.style.display = 'none'; });
          }
          hide();
          new MutationObserver(hide).observe(document.documentElement, {subtree:true, childList:true});
        })();
    """.trimIndent()

    private val builtinDarkJs = """
        (function(){
          if(document.getElementById('lan-dark')) return;
          var s = document.createElement('style');
          s.id = 'lan-dark';
          s.textContent = 'html{filter:invert(1) hue-rotate(180deg)!important;background:#111!important}img,video,canvas,svg,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)!important}';
          document.documentElement.appendChild(s);
        })();
    """.trimIndent()

    private val builtinSpeedJs = """
        (function(){
          function f(){ document.querySelectorAll('video').forEach(function(v){ v.playbackRate = 2; }); }
          f();
          new MutationObserver(f).observe(document.documentElement, {subtree:true, childList:true});
        })();
    """.trimIndent()

    private val builtinTranslateJs = """
        (function(){
          if(document.getElementById('lan-translate-btn')) return;
          var b = document.createElement('button');
          b.id = 'lan-translate-btn';
          b.textContent = '翻译';
          b.style.cssText = 'position:fixed;right:12px;bottom:90px;z-index:2147483647;padding:8px 14px;border:none;border-radius:20px;background:#DC2626;color:#fff;font-size:14px;box-shadow:0 2px 8px rgba(0,0,0,.3);';
          b.onclick = function(){
            window.open('https://translate.google.com/translate?sl=auto&tl=zh-CN&u=' + encodeURIComponent(location.href), '_blank');
          };
          document.documentElement.appendChild(b);
        })();
    """.trimIndent()

    private val builtinReaderJs = """
        (function(){
          if(document.getElementById('lan-reader-btn')) return;
          var b = document.createElement('button');
          b.id = 'lan-reader-btn';
          b.textContent = '阅读';
          b.style.cssText = 'position:fixed;right:12px;bottom:150px;z-index:2147483647;padding:8px 14px;border:none;border-radius:20px;background:#7C3AED;color:#fff;font-size:14px;box-shadow:0 2px 8px rgba(0,0,0,.3);';
          b.onclick = function(){
            var ps = document.querySelectorAll('p');
            var texts = [];
            ps.forEach(function(p){ var t = p.innerText.trim(); if(t.length > 20) texts.push(t); });
            if(texts.length < 3){
              var tip = document.createElement('div');
              tip.textContent = '这页没找到够长的正文';
              tip.style.cssText = 'position:fixed;left:50%;top:40%;transform:translateX(-50%);z-index:2147483647;background:#111;color:#fff;padding:10px 18px;border-radius:20px;font-size:14px;';
              document.body.appendChild(tip);
              setTimeout(function(){ tip.remove(); }, 2500);
              return;
            }
            var ov = document.createElement('div');
            ov.id = 'lan-reader-view';
            ov.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#fff;overflow:auto;padding:24px 20px 60px;font-size:18px;line-height:1.9;color:#1F2937;';
            var h = '<h1 style="font-size:22px;margin-bottom:16px;">' + document.title.replace(/</g,'&lt;') + '</h1>';
            texts.forEach(function(t){ h += '<p style="margin-bottom:16px;">' + t.replace(/</g,'&lt;') + '</p>'; });
            h += '<button onclick="document.getElementById(\'lan-reader-view\').remove()" style="position:fixed;right:16px;top:16px;padding:8px 16px;border:none;border-radius:20px;background:#111;color:#fff;">关闭</button>';
            ov.innerHTML = h;
            document.body.appendChild(ov);
          };
          document.documentElement.appendChild(b);
        })();
    """.trimIndent()

    private val builtinCopyJs = """
        (function(){
          var css = '*{user-select:text!important;-webkit-user-select:text!important}';
          var s = document.createElement('style');
          s.textContent = css;
          document.documentElement.appendChild(s);
          function free(el){
            if(!el) return;
            el.oncopy = null; el.oncut = null; el.oncontextmenu = null; el.onselectstart = null;
          }
          free(document); free(document.body);
          document.addEventListener('copy', function(e){ e.stopPropagation(); }, true);
          document.addEventListener('contextmenu', function(e){ e.stopPropagation(); }, true);
        })();
    """.trimIndent()

    data class Builtin(
        val id: String, val name: String, val version: String,
        val description: String, val fileName: String,
        val js: String, val enabledByDefault: Boolean,
        val badge: String = "", val color: Int = 0xFF2563EB.toInt()
    )

    private val builtins = listOf(
        Builtin(
            "builtin-adblock", "去广告增强", "1.0",
            "隐藏常见广告位与广告 iframe，配合菜单里的去广告开关一起用",
            "builtin-adblock.js", builtinAdblockJs, true,
            badge = "推荐", color = 0xFF22C55E.toInt()
        ),
        Builtin(
            "builtin-dark", "深色模式", "1.0",
            "把所有网页反色成深色，图片视频会再反一次保持正常",
            "builtin-dark.js", builtinDarkJs, false,
            color = 0xFF1E293B.toInt()
        ),
        Builtin(
            "builtin-speed", "视频 2 倍速", "1.0",
            "页面里所有 video 自动 2 倍速播放",
            "builtin-speed.js", builtinSpeedJs, false,
            color = 0xFF2563EB.toInt()
        ),
        Builtin(
            "builtin-translate", "网页翻译", "1.0",
            "在页面右下角加一个翻译按钮，点开用谷歌翻译看本页中文版",
            "builtin-translate.js", builtinTranslateJs, false,
            badge = "新", color = 0xFFDC2626.toInt()
        ),
        Builtin(
            "builtin-reader", "阅读模式", "1.0",
            "在页面右下角加一个阅读按钮，点开只留正文、干净排版慢慢看",
            "builtin-reader.js", builtinReaderJs, false,
            badge = "新", color = 0xFF7C3AED.toInt()
        ),
        Builtin(
            "builtin-copy", "解除复制限制", "1.0",
            "去掉网页的禁止复制、禁止右键和禁止选中，文字随便拷",
            "builtin-copy.js", builtinCopyJs, false,
            color = 0xFF0E7490.toInt()
        )
    )

    /** 商店目录：6 个览能装的用户脚本 */
    fun catalogItems(): List<Builtin> = builtins

    // ---------- 存取 ----------

    private fun extDir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    fun list(context: Context): MutableList<LanExtension> {
        ensureBuiltinFiles(context)
        val out = mutableListOf<LanExtension>()
        try {
            val arr = JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_INDEX, "[]") ?: "[]"
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val matches = mutableListOf<String>()
                val ma = o.optJSONArray("matches")
                if (ma != null) for (j in 0 until ma.length()) matches.add(ma.optString(j))
                out.add(
                    LanExtension(
                        o.optString("id"), o.optString("name"), o.optString("version"),
                        o.optString("description"), matches, o.optString("fileName"),
                        o.optBoolean("enabled", true), o.optBoolean("builtin", false),
                        o.optString("sourceUrl", "")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(context: Context, list: List<LanExtension>) {
        val arr = JSONArray()
        for (e in list) {
            val ma = JSONArray()
            for (m in e.matches) ma.put(m)
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("version", e.version)
                    .put("description", e.description)
                    .put("matches", ma)
                    .put("fileName", e.fileName)
                    .put("enabled", e.enabled)
                    .put("builtin", e.builtin)
                    .put("sourceUrl", e.sourceUrl)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_INDEX, arr.toString()).apply()
    }

    /**
     * 只把内置脚本文件写到磁盘，不往已安装列表里登记。
     * 内置脚本要从扩展商店点「安装」才进列表；老用户已登记的 3 个不受影响。
     */
    private fun ensureBuiltinFiles(context: Context) {
        val dir = extDir(context)
        for (b in builtins) {
            val file = File(dir, b.fileName)
            if (!file.exists()) file.writeText(b.js)
        }
    }

    private fun listWithoutEnsure(context: Context): MutableList<LanExtension> {
        val out = mutableListOf<LanExtension>()
        try {
            val arr = JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_INDEX, "[]") ?: "[]"
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val matches = mutableListOf<String>()
                val ma = o.optJSONArray("matches")
                if (ma != null) for (j in 0 until ma.length()) matches.add(ma.optString(j))
                out.add(
                    LanExtension(
                        o.optString("id"), o.optString("name"), o.optString("version"),
                        o.optString("description"), matches, o.optString("fileName"),
                        o.optBoolean("enabled", true), o.optBoolean("builtin", false)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    // ---------- 匹配与注入 ----------

    /** Tampermonkey 风格 glob：* 通配任意字符，缺省匹配全部。 */
    fun globMatches(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty() || p == "*://*/*" || p == "*") return true
        val sb = StringBuilder("^")
        for (c in p) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append("$")
        return try {
            Regex(sb.toString()).matches(url)
        } catch (e: Exception) {
            url.contains(p.replace("*", ""))
        }
    }

    fun matching(context: Context, url: String): List<LanExtension> {
        if (url.startsWith("file://") || url.startsWith("about:")) return emptyList()
        return list(context).filter { e ->
            e.enabled && e.matches.any { globMatches(it, url) }
        }
    }

    /** 在页面加载完成后注入。同一页面同一扩展只注一次。 */
    fun inject(webView: WebView, url: String) {
        val context = webView.context
        for (ext in matching(context, url)) {
            val code = try {
                File(extDir(context), ext.fileName).readText()
            } catch (e: Exception) {
                continue
            }
            if (code.isBlank()) continue
            val wrapped = "window.__lan_exts=window.__lan_exts||{};" +
                "if(!window.__lan_exts['" + ext.id + "']){" +
                "window.__lan_exts['" + ext.id + "']=1;" +
                "try{\n" + code + "\n}catch(e){} }"
            webView.evaluateJavascript(wrapped, null)
        }
    }

    // ---------- 安装 / 删除 ----------

    /** 解析 Tampermonkey 头 // @name // @match // @description // @version。 */
    fun parseMeta(code: String): Map<String, Any> {
        var name = ""
        var version = "1.0"
        var description = ""
        val matches = mutableListOf<String>()
        var inHeader = false
        for (raw in code.lines()) {
            val line = raw.trim()
            if (line.startsWith("// ==UserScript==")) {
                inHeader = true
                continue
            }
            if (line.startsWith("// ==/UserScript==")) break
            if (!inHeader) continue
            val body = line.removePrefix("//").trim()
            when {
                body.startsWith("@name") -> name = body.removePrefix("@name").trim()
                body.startsWith("@version") -> version = body.removePrefix("@version").trim()
                body.startsWith("@description") -> description = body.removePrefix("@description").trim()
                body.startsWith("@match") -> matches.add(body.removePrefix("@match").trim())
                body.startsWith("@include") -> matches.add(body.removePrefix("@include").trim())
            }
        }
        if (matches.isEmpty()) matches.add("*://*/*")
        return mapOf(
            "name" to name, "version" to version,
            "description" to description, "matches" to matches
        )
    }

    /** 存入脚本文件并登记，返回登记项；code 为空返回 null。sourceUrl 记下来源，供自动更新重拉。 */
    fun installFromCode(
        context: Context, code: String, fallbackName: String, sourceUrl: String = ""
    ): LanExtension? {
        if (code.isBlank()) return null
        val meta = parseMeta(code)
        val name = (meta["name"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackName
        @Suppress("UNCHECKED_CAST")
        val matches = (meta["matches"] as? List<String>) ?: listOf("*://*/*")
        val id = "user-" + System.currentTimeMillis()
        val fileName = "$id.js"
        File(extDir(context), fileName).writeText(code)
        val ext = LanExtension(
            id, name, meta["version"] as? String ?: "1.0",
            meta["description"] as? String ?: "", matches, fileName, true, false, sourceUrl
        )
        val list = list(context)
        list.add(ext)
        save(context, list)
        return ext
    }

    /** 从商店安装一个内置脚本：已安装就直接返回它，否则登记进列表（默认启用）。 */
    fun installBuiltin(context: Context, id: String): LanExtension? {
        val b = builtins.find { it.id == id } ?: return null
        ensureBuiltinFiles(context)
        val list = list(context)
        list.find { it.id == id }?.let { return it }
        val ext = LanExtension(
            b.id, b.name, b.version, b.description,
            listOf("*://*/*"), b.fileName, true, true
        )
        list.add(ext)
        save(context, list)
        return ext
    }

    /**
     * 自动更新：把从链接装的脚本按 sourceUrl 重拉一遍，拉到新内容就覆盖。
     * 返回更新了几个，失败的跳过不影响其他的。
     */
    fun updateFromUrls(context: Context): Int {
        var updated = 0
        for (ext in list(context)) {
            if (ext.sourceUrl.isBlank()) continue
            try {
                val conn = java.net.URL(ext.sourceUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.connect()
                if (conn.responseCode != 200) continue
                val code = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (code.isBlank() || code.trimStart().startsWith("<")) continue
                File(extDir(context), ext.fileName).writeText(code)
                updated++
                AppLog.i(context, "扩展", "已更新 ${ext.name}")
            } catch (e: Exception) {
                AppLog.e(context, "扩展", "更新 ${ext.name} 失败", e)
            }
        }
        return updated
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val list = list(context)
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(enabled = enabled)
        save(context, list)
    }

    fun delete(context: Context, ext: LanExtension) {
        if (ext.builtin) return
        try {
            File(extDir(context), ext.fileName).delete()
        } catch (e: Exception) {
        }
        val list = list(context)
        list.removeAll { it.id == ext.id }
        save(context, list)
    }
}
