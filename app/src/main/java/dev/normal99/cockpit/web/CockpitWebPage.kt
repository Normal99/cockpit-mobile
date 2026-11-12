package dev.normal99.cockpit.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.normal99.cockpit.cockpit.sha256Fingerprint
import dev.normal99.cockpit.data.HostConnection

/** Extra CSS so pages built for a desktop browser sit well on a phone (PatternFly pages are mostly responsive already). */
private const val MOBILE_CSS = """
html { -webkit-text-size-adjust: 100%; }
body { overscroll-behavior-y: contain; }
.pf-v6-c-page__main-section, .pf-v5-c-page__main-section { padding-inline: 12px !important; }
table { max-width: 100%; }
"""

private val INJECT = """
(function() {
  if (!document.querySelector('meta[name=viewport]')) {
    var m = document.createElement('meta'); m.name = 'viewport'; m.content = 'width=device-width, initial-scale=1';
    document.head && document.head.appendChild(m);
  }
  if (!document.getElementById('cockpit-mobile-css')) {
    var s = document.createElement('style'); s.id = 'cockpit-mobile-css'; s.textContent = ${'"'}${MOBILE_CSS.replace("\n", " ")}${'"'};
    (document.head || document.documentElement).appendChild(s);
  }
})();
""".trimIndent()

/** WebView reports the URL as the title when a page has no <title>; keep the menu label then. */
private fun isRealTitle(view: WebView, title: String) =
    title.isNotBlank() && !title.startsWith("http") && view.url?.contains(title) != true

/**
 * Any Cockpit page (Cockpit's own or a mod such as the Claude page) running standalone in a WebView.
 * It shares the app's login: the session cookie is handed over, so the page's cockpit.js connects to the same
 * session, with the same admin access. Only the host's pinned certificate (or a publicly trusted one) is accepted.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CockpitWebPage(conn: HostConnection, path: String, modifier: Modifier = Modifier, onTitle: (String) -> Unit = {}) {
    val base = conn.session.endpoint.base
    val url = conn.session.endpoint.url(path).toString()
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    conn.session.webCookies().forEach { setCookie(base.toString(), it) }
                    flush()
                }
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        // Proceeds only for the exact certificate pinned for this host; everything else is cancelled.
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            val cert = error.certificate.x509Certificate
                            val pinned = conn.host.pinnedSha256
                            if (cert != null && pinned != null && sha256Fingerprint(cert).equals(pinned, ignoreCase = true)) handler.proceed()
                            else handler.cancel()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.host == base.host && (u.port == -1 || u.port == base.port) && u.scheme?.startsWith("http") == true) return false
                            // Links out of Cockpit (GitHub, Moonlight, docs) go to the browser.
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            view.evaluateJavascript(INJECT, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(INJECT, null)
                            canGoBack = view.canGoBack()
                            view.title?.takeIf { isRealTitle(view, it) }?.let(onTitle)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView, title: String?) {
                            title?.takeIf { isRealTitle(view, it) }?.let(onTitle)
                        }

                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            pickFiles.launch(params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*")
                            return true
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
        )
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}
