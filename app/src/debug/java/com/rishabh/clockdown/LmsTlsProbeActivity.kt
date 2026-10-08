package com.rishabh.clockdown

import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Debug builds only: loads the public LMS login page in a WebView and logs whether TLS worked (logcat tag LmsTls). Never loads anything signed in. */
class LmsTlsProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { textSize = 18f; setPadding(32, 120, 32, 32); text = "LMS TLS probe: loading…" }
        val web = WebView(this).apply {
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel() // never proceed
                    Log.w("LmsTls", "RESULT ssl-error primary=${error.primaryError}"); label.text = "LMS TLS probe: SSL ERROR ${error.primaryError}"
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) Log.w("LmsTls", "RESULT http=${errorResponse.statusCode}")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (label.text.startsWith("LMS TLS probe: loading")) { Log.w("LmsTls", "RESULT loaded title=${view.title}"); label.text = "LMS TLS probe: LOADED (${view.title})" }
                }
            }
        }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(label); addView(web, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        })
        web.loadUrl("https://alms06.amizone.net/moodle_26odd/login/index.php")
    }
}
