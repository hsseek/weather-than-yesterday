package com.hsseek.betterthanyesterday

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme

const val EXTRA_URL_KEY = "bty_intent_extra_url"
const val FAQ_URL = "https://blog.naver.com/seoulworkshop/222898712063"
const val BLOG_URL = "https://bit.ly/3DSRrjv"

class WebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url: String = intent.extras?.getString(EXTRA_URL_KEY) ?: FAQ_URL

        setContent {
            BetterThanYesterdayTheme(isSystemInDarkTheme()) {
                // Make the status bar look transparent.
                val systemUiController = rememberSystemUiController()
                systemUiController.setSystemBarsColor(color = Color.White)

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

    AndroidView(factory = {
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