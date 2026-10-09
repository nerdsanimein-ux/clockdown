package com.rishabh.clockdown

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
 * Clockdown's own colours for Moodle's pages, injected as a stylesheet. STYLING ONLY: it changes how things look (colours, rounded
 * corners, hiding the site's own top bar, footer and chat bubble, which sit OUTSIDE the content) and never touches, clicks, fills
 * in or rewires anything. Moodle's own buttons and scripts do all the uploading and submitting. If a page ever changes shape and
 * the styling stops matching, the page simply looks plainer; it keeps working.
 */
object LmsStyle {
    private fun v(dark: Boolean) = if (dark) {
        "--cd-bg:#0F1115;--cd-surface:#15171C;--cd-raised:#1B1E24;--cd-text:#E6E4EB;--cd-muted:#B0AEB9;--cd-primary:#AFA8FF;--cd-on-primary:#2A1FA0;--cd-line:#3A3944;"
    } else {
        "--cd-bg:#F6F3EC;--cd-surface:#FBF9F4;--cd-raised:#EFEBE2;--cd-text:#1C1B20;--cd-muted:#55535F;--cd-primary:#4A3FE0;--cd-on-primary:#FFFFFF;--cd-line:#CBC7D0;"
    }

    fun css(dark: Boolean): String = """
        :root{${v(dark)}color-scheme:${if (dark) "dark" else "light"};}
        html,body{background:var(--cd-bg)!important;color:var(--cd-text)!important;font-family:Roboto,"Segoe UI",system-ui,sans-serif!important;}
        body{padding-top:0!important;} #page,#page-wrapper,#region-main,#region-main-box,#page-content,.main-inner{background:transparent!important;color:var(--cd-text)!important;}
        #page{margin-top:0!important;} #topofscroll{margin-top:0!important;}
        nav.navbar,.navbar.fixed-top,#page-footer,footer,.btn-footer-popover,#ai-tutor-widget,.drawer-toggles,.drawer-left-toggle,.drawer-right-toggle{display:none!important;}
        a{color:var(--cd-primary);}
        h1,h2,h3,h4,h5,.h1,.h2,.h3,.h4,.h5{color:var(--cd-text)!important;}
        .text-muted,.dimmed_text,small,.small{color:var(--cd-muted)!important;}
        .card,.generalbox,.box,.modal-content,.filemanager,.fp-content,.fp-toolbar,.fp-navbar,.fp-repo-area,.fp-iconview,.fp-tableview,.moodle-dialogue-base .moodle-dialogue-wrap,
        .moodle-dialogue-base .moodle-dialogue-bd,.moodle-dialogue-base .moodle-dialogue-hd,.dropdown-menu,.tooltip-inner,.popover{
          background:var(--cd-surface)!important;color:var(--cd-text)!important;border-color:var(--cd-line)!important;border-radius:20px!important;}
        .moodle-dialogue-base .moodle-dialogue-hd{border-radius:20px 20px 0 0!important;}
        table.generaltable,table.generaltable th,table.generaltable td,.table{background:transparent!important;color:var(--cd-text)!important;border-color:var(--cd-line)!important;}
        input[type=text],input[type=password],input[type=search],input[type=number],input[type=url],input[type=email],select,textarea,.form-control,.custom-select,.form-select{
          background:var(--cd-surface)!important;color:var(--cd-text)!important;border:1px solid var(--cd-line)!important;border-radius:14px!important;}
        .btn{border-radius:999px!important;padding:.55rem 1.25rem!important;font-weight:600!important;}
        .btn-primary{background:var(--cd-primary)!important;border-color:var(--cd-primary)!important;color:var(--cd-on-primary)!important;}
        .btn-secondary,.btn-outline-secondary,.btn-light,.btn-link{background:transparent!important;border:1px solid var(--cd-line)!important;color:var(--cd-text)!important;}
        .alert{border-radius:16px!important;} .filepicker-filename,.fp-filename{color:var(--cd-text)!important;}
        html,body{overflow-x:hidden!important;}
        .yui3-panel.moodle-dialogue{width:calc(100vw - 16px)!important;max-width:calc(100vw - 16px)!important;left:8px!important;min-width:0!important;}
        .yui3-panel.moodle-dialogue .moodle-dialogue-wrap,.yui3-panel.moodle-dialogue .moodle-dialogue-bd,.yui3-panel.moodle-dialogue .moodle-dialogue-hd{width:auto!important;max-width:100%!important;min-width:0!important;}
        .fp-generallayout > .fp-repo-area,.fp-generallayout > .col-md-3,.fp-generallayout > .col-md-9{flex:0 0 100%!important;max-width:100%!important;width:100%!important;}
        .fp-generallayout .fp-repo-area{display:flex!important;flex-direction:row!important;flex-wrap:wrap!important;gap:6px;}
        .breadcrumb,nav[aria-label="Navigation bar"]{display:none!important;}
    """.trimIndent().replace(Regex("\\s*\\n\\s*"), " ")

    /** The script that adds (or refreshes) the stylesheet. Wrapped so that any problem is swallowed and the page is left as it was. */
    fun inject(dark: Boolean): String =
        "(function(){try{var s=document.getElementById('cd-style');if(!s){s=document.createElement('style');s.id='cd-style';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(css(dark))};}catch(e){}})();"
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

    private fun dark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (LoginDetector.allowedPage(url)) return false
            // Anything that isn't Amity's own site (a link in a course, a mailto) opens outside the app.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { problem = null }
        override fun onPageCommitVisible(view: WebView, url: String) { view.evaluateJavascript(LmsStyle.inject(dark()), null) }
        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(LmsStyle.inject(dark()), null)
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

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (!LoginDetector.allowedPage(url)) return
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url)).addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
                .addRequestHeader("User-Agent", ua.orEmpty()).setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime))
            getSystemService(DownloadManager::class.java).enqueue(req)
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
