package com.adriennezoo.companion.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
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

    LaunchedEffect(animal.animalId) {
        val json = viewModel.getTrackJson(animal)
        trackJson = json
        if (isPageLoaded && webViewRef != null && json != null) {
            // Escape json for eval or call loadTrack directly
            webViewRef?.evaluateJavascript("window.loadTrack && window.loadTrack(${json});", null)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }

                    addJavascriptInterface(
                        MapJsBridge { trackJson },
                        "AndroidInterface"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            return assetLoader.shouldInterceptRequest(request.url)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            isPageLoaded = true
                            trackJson?.let { json ->
                                view.evaluateJavascript("window.loadTrack && window.loadTrack(${json});", null)
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
                    webView.evaluateJavascript("window.loadTrack && window.loadTrack(${trackJson});", null)
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
            // cleanup if needed
        }
    }
}
