package com.rishabh.clockdown

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rishabh.clockdown.ui.theme.ClockdownTheme
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The only changes made to Moodle's pages: hide the site's own top bar, footer, side-panel buttons and chat bubble (the app has
 * its own bar), and keep wide pages from scrolling sideways. LAYOUT ONLY: Moodle keeps its own colours. Earlier versions
 * repainted Moodle in the app's colours, which left white text on boxes that stayed light in dark mode. Dark mode is now the
 * WebView's own darkening (see [LmsWebActivity]), which darkens every part of the page consistently.
 * It never touches, clicks, fills in or rewires anything; if Moodle changes shape, the page just shows its own bars again.
 */
object LmsStyle {
    val css: String = """
        body{padding-top:0!important;} #page{margin-top:0!important;} #topofscroll{margin-top:0!important;}
        nav.navbar,.navbar.fixed-top,#page-footer,footer,.btn-footer-popover,#ai-tutor-widget,.drawer-toggles,.drawer-left-toggle,.drawer-right-toggle{display:none!important;}
        .breadcrumb,nav[aria-label="Navigation bar"]{display:none!important;}
        html,body{overflow-x:clip!important;} /* clip, not hidden: "hidden" made Moodle's quiz page draw blank (Chromium, with its fixed-drawer layout) */
        .yui3-panel.moodle-dialogue{width:calc(100vw - 16px)!important;max-width:calc(100vw - 16px)!important;left:8px!important;min-width:0!important;}
        .yui3-panel.moodle-dialogue .moodle-dialogue-wrap,.yui3-panel.moodle-dialogue .moodle-dialogue-bd,.yui3-panel.moodle-dialogue .moodle-dialogue-hd{width:auto!important;max-width:100%!important;min-width:0!important;}
        .fp-generallayout > .fp-repo-area,.fp-generallayout > .col-md-3,.fp-generallayout > .col-md-9{flex:0 0 100%!important;max-width:100%!important;width:100%!important;}
        .fp-generallayout .fp-repo-area{display:flex!important;flex-direction:row!important;flex-wrap:wrap!important;gap:6px;}
    """.trimIndent().replace(Regex("\\s*\\n\\s*"), " ")

    /** The script that adds (or refreshes) the stylesheet. Wrapped so that any problem is swallowed and the page is left as it was. */
    val inject: String =
        "(function(){try{var s=document.getElementById('cd-style');if(!s){s=document.createElement('style');s.id='cd-style';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(css)};}catch(e){}})();"
}

/**
 * Moodle's own page, in the app, signed in with the LMS session. Used for the upload page, quizzes, wikis, links and "Open in
 * browser". Only amizone.net pages load here; any other address opens in the phone's browser instead.
 */
class LmsWebActivity : ComponentActivity() {
    private var web: WebView? = null
    private var firstUrl = ""
    private var reSignedOn = false
    private var loadProgress by mutableIntStateOf(0)
    private var problem by mutableStateOf<String?>(null)
    private var chooser by mutableStateOf(false)
    private var downloading by mutableStateOf<String?>(null) // name of the attachment being fetched
    private var noViewer by mutableStateOf<File?>(null)
    private var pageTitle by mutableStateOf("")
    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private var chooserParams: WebChromeClient.FileChooserParams? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        deliver(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }
    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        deliver(if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { arrayOf(it) } else null)
    }

    /** The WebView wants exactly ONE answer per file chooser: the files, or null for none. */
    private fun deliver(uris: Array<Uri>?) {
        chooserCallback?.onReceiveValue(uris)
        chooserCallback = null; chooserParams = null; chooser = false
    }

    @SuppressLint("SetJavaScriptEnabled") // Moodle's pages (and its file picker) don't work without it
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        firstUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val base = LmsSession.base(this)
        val cookie = LmsSession.cookie(this)
        if (base == null || cookie == null || !firstUrl.startsWith(base)) { finish(); return } // only ever the signed-in LMS
        setResult(Activity.RESULT_OK)
        putCookies(base, cookie)

        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Dark mode: the WebView darkens Moodle itself (needs this activity's night theme, Theme.Clockdown.Web). Android 13+;
            // older phones show Moodle in its normal light colours, which stay readable.
            if (android.os.Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
            Session.userAgent(this@LmsWebActivity)?.let { settings.userAgentString = it }
            setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
            webViewClient = client()
            webChromeClient = chrome()
        }
        web = w
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (w.canGoBack()) w.goBack() else finish() }
        })
        setContent {
            ClockdownTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                            Text(pageTitle.ifBlank { "Moodle" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick = { problem = null; w.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                        }
                        if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, Modifier.fillMaxWidth())
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(factory = { w }, modifier = Modifier.fillMaxSize())
                            problem?.let { message ->
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(message, style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = { problem = null; w.reload() }, Modifier.padding(top = 16.dp)) { Text("Try again") }
                                        OutlinedButton(onClick = { finish() }, Modifier.padding(top = 8.dp)) { Text("Close") }
                                    }
                                }
                            }
                        }
                    }
                    if (chooser) FileChooserDialog()
                    downloading?.let { name ->
                        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Opening file") }, text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(name); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        })
                    }
                    noViewer?.let { NoViewerDialog(it) { noViewer = null } }
                }
            }
        }
        w.loadUrl(firstUrl)
    }

    override fun onDestroy() {
        // A pending chooser must still be answered, or the page's own file button would stop working until reload.
        chooserCallback?.onReceiveValue(null); chooserCallback = null
        web?.destroy(); web = null
        super.onDestroy()
    }

    /** Sets the saved LMS cookies for the WebView, scoped to the semester's folder like the site itself does. */
    private fun putCookies(base: String, cookie: String) {
        val path = java.net.URI(base).path.trimEnd('/') + "/"
        val cm = CookieManager.getInstance()
        cookie.split(';').map { it.trim() }.filter { '=' in it }.forEach { cm.setCookie(base, "$it; Path=$path; Secure; HttpOnly") }
        cm.flush()
    }

    private var shownAPage = false // false until a Moodle page has actually been drawn here

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (LoginDetector.allowedPage(url)) return false
            // Anything that isn't Amity's own site (a link in a course, a mailto) opens outside the app.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            // A course link opened straight through (Moodle redirects to it) leaves nothing to show here: don't stay behind as a blank page.
            if (!shownAPage) finish()
            return true
        }
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { problem = null }
        override fun onPageCommitVisible(view: WebView, url: String) { shownAPage = true; view.evaluateJavascript(LmsStyle.inject, null) }
        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(LmsStyle.inject, null)
            pageTitle = view.title.orEmpty().substringBefore('|').trim()
            if (url.substringBefore('?').endsWith("/login/index.php")) signedOut(view)
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) problem = "Couldn't reach the LMS. Check your connection and try again."
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) problem = "The LMS had a problem (HTTP ${errorResponse.statusCode}). Please try again."
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel() // never proceed past a certificate error
            problem = "Couldn't verify the LMS's security certificate, so the page was not loaded."
        }
    }

    /** The page we asked for sent us to the login page: sign on again ONCE, then go back to what was asked for. */
    private fun signedOut(view: WebView) {
        if (reSignedOn) { problem = "Your LMS sign-in has expired. Open the LMS tab and pull down to refresh, then try again."; return }
        reSignedOn = true
        thread {
            val r = LmsSso.signOn(applicationContext)
            runOnUiThread {
                if (r is SsoResult.Ok) {
                    LmsSession.save(applicationContext, r.base, r.cookie, r.label)
                    putCookies(r.base, r.cookie)
                    view.loadUrl(firstUrl)
                } else problem = if (r is SsoResult.AmizoneExpired) "Amizone sign-in needed. Reconnect Amizone from the app, then try again." else "Couldn't sign in to the LMS right now. Please try again later."
            }
        }
    }

    private fun chrome() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { loadProgress = newProgress }
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            chooserCallback?.onReceiveValue(null) // a new request replaces an unanswered one
            chooserCallback = callback; chooserParams = params; chooser = true
            return true
        }
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            android.app.AlertDialog.Builder(this@LmsWebActivity).setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }.setOnCancelListener { result.cancel() }.show(); return true
        }
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            android.app.AlertDialog.Builder(this@LmsWebActivity).setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }.setNegativeButton("Cancel") { _, _ -> result.cancel() }.setOnCancelListener { result.cancel() }.show(); return true
        }
    }

    /** "Add a file": from the phone, scan pages into a PDF, or one of the PDFs scanned earlier. Nothing is chosen until the student taps. */
    @androidx.compose.runtime.Composable
    private fun FileChooserDialog() {
        val scans = remember { recentScans(this) }
        AlertDialog(
            onDismissRequest = { deliver(null) },
            title = { Text("Add a file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { chooserParams?.let { pickFile.launch(it.createIntent()) } }, Modifier.fillMaxWidth()) { Text("Choose from this phone") }
                    OutlinedButton(onClick = { scan.launch(Intent(this@LmsWebActivity, ScanActivity::class.java)) }, Modifier.fillMaxWidth()) { Text("Scan pages to PDF") }
                    Text("Moodle takes one file each time. To add more, tap its Add button again. Several pages scanned together become one PDF.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (scans.isNotEmpty()) {
                        Text("Scanned earlier", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                        scans.take(4).forEach { f ->
                            Text(f.name, Modifier.fillMaxWidth().clickable { deliver(arrayOf(androidx.core.content.FileProvider.getUriForFile(this@LmsWebActivity, "$packageName.files", f))) }.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { deliver(null) }) { Text("Cancel") } },
        )
    }

    /**
     * A file linked from a Moodle page (an attachment in an announcement, a slide deck): fetched with this page's sign-in and
     * opened at once, the same way course files are. A silent download into a hidden folder looked like "nothing happened".
     */
    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (!LoginDetector.allowedPage(url) || downloading != null) return
        val name = LmsFiles.safeName(URLUtil.guessFileName(url, cd, mime), "file")
        downloading = name
        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
        thread {
            val f = try { LmsFiles.fetchUrl(applicationContext, url, cookie, ua.orEmpty(), name) } catch (e: Exception) { null }
            runOnUiThread {
                downloading = null
                when {
                    f == null -> problem = "Couldn't download \"$name\". Check your connection and try again."
                    !viewFile(this, f) -> noViewer = f
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"

        /** [path] is relative to the semester's folder ("mod/assign/view.php?id=5"); the address is built from the saved LMS session. */
        fun open(ctx: Context, path: String) {
            val base = LmsSession.base(ctx) ?: return
            ctx.startActivity(Intent(ctx, LmsWebActivity::class.java).putExtra(EXTRA_URL, "$base/$path"))
        }

        /** PDFs the scanner made earlier, newest first. */
        fun recentScans(ctx: Context): List<File> =
            File(ctx.filesDir, LmsStore.SCANS).listFiles { f -> f.isFile && f.name.endsWith(".pdf") }?.sortedByDescending { it.lastModified() }.orEmpty()
    }
}
