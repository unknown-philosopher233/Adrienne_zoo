package com.adriennezoo.companion.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.ui.theme.ForestGreenPrimary
import com.adriennezoo.companion.viewmodel.MainViewModel
import org.json.JSONObject

private const val TAG = "JourneyMapView"

class MapJsBridge(private val trackProvider: () -> String?) {
    @JavascriptInterface
    fun getInitialTrack(): String {
        return trackProvider() ?: ""
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JourneyMapView(
    animal: AnimalEntity,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var trackJson by remember(animal.animalId) { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPageLoaded by remember { mutableStateOf(false) }

    fun sendTrackToWebView(wv: WebView, json: String) {
        val escaped = JSONObject.quote(json)
        val script = """
            (function() {
                if (window.updateLayout) window.updateLayout();
                if (window.loadTrack) {
                    window.loadTrack($escaped);
                } else {
                    window.__pendingTrack = $escaped;
                }
                if (window.updateLayout) window.updateLayout();
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    LaunchedEffect(animal.animalId) {
        val json = viewModel.getTrackJson(animal)
        trackJson = json
        if (json != null && webViewRef != null) {
            sendTrackToWebView(webViewRef!!, json)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                object : WebView(context) {
                    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                        super.onSizeChanged(w, h, ow, oh)
                        if (h > 0) {
                            post {
                                evaluateJavascript(
                                    """
                                    (function() {
                                        if (window.updateLayout) window.updateLayout();
                                        if (window.fitRoute) window.fitRoute();
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                    }
                }.apply {
                    WebView.setWebContentsDebuggingEnabled(true)

                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        useWideViewPort = false
                        loadWithOverviewMode = false
                    }

                    addJavascriptInterface(
                        MapJsBridge { trackJson },
                        "AndroidInterface"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(
                                TAG,
                                "JS: ${consoleMessage.message()} (line ${consoleMessage.lineNumber()} in ${consoleMessage.sourceId()})"
                            )
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            return assetLoader.shouldInterceptRequest(request.url)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.e(TAG, "WebView error on ${request.url}: ${error.description}")
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            isPageLoaded = true
                            view.evaluateJavascript("if (window.updateLayout) window.updateLayout();", null)
                            trackJson?.let { json ->
                                sendTrackToWebView(view, json)
                            }
                        }
                    }

                    loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
                if (isPageLoaded && trackJson != null) {
                    sendTrackToWebView(webView, trackJson!!)
                }
            }
        )

        if (trackJson == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ForestGreenPrimary)
            }
        }
    }

    DisposableEffect(animal.animalId) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}
