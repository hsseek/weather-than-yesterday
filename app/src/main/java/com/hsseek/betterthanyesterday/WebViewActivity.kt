package com.hsseek.betterthanyesterday

import android.annotation.SuppressLint
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme

const val EXTRA_URL_KEY = "bty_intent_extra_url"
const val FAQ_URL = "https://blog.naver.com/seoulworkshop/222898712063"
const val POLICY_URL = "https://blog.naver.com/seoulworkshop/223052494801"
const val BLOG_URL = "https://bit.ly/3DSRrjv"

class WebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url: String = intent.extras?.getString(EXTRA_URL_KEY) ?: FAQ_URL

        enableEdgeToEdge()

        setContent {
            BetterThanYesterdayTheme(isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    WebViewScreen(this, url)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebViewScreen(
    context: Context,
    url: String,
) {
    val backEnabled = remember { mutableStateOf(false) }
    var webView: WebView? = null

    AndroidView(
        modifier = Modifier.statusBarsPadding(),
        factory = {
        WebView(context).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    backEnabled.value = view.canGoBack()
                }
            }
            settings.javaScriptEnabled = true
            loadUrl(url)
            webView = this
        }
    }, update = { webView = it })
    BackHandler(enabled = backEnabled.value) {
        webView?.goBack()
    }
}