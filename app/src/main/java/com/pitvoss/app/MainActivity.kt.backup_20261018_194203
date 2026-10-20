package com.pitvoss.app

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pool = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "LockhartNative")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }
    inner class Bridge {
        @JavascriptInterface fun rpc(ip: String, method: String, params: String, requestId: Int) {
            // Only read-only RPC is enabled in this initial Android build.
            if (method != "GetState" || !Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(ip)) {
                deliver(requestId, "", "Unsupported RPC or invalid IP"); return
            }
            pool.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL("http://$ip/rpc").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 4000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val payload = JSONObject().put("id", requestId).put("method", method).put("params", JSONObject(params)).toString()
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    deliver(requestId, body, "")
                } catch (ex: Exception) {
                    deliver(requestId, "", ex.message ?: "Network request failed")
                } finally { conn?.disconnect() }
            }
        }
    }
    private fun deliver(id: Int, body: String, error: String) {
        val args = "${id},${JSONObject.quote(body)},${JSONObject.quote(error)}"
        runOnUiThread { web.evaluateJavascript("window.receiveRpc($args)", null) }
    }
    override fun onDestroy() { pool.shutdownNow(); web.destroy(); super.onDestroy() }
}
