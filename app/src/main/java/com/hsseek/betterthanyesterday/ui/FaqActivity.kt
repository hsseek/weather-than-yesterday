package com.hsseek.betterthanyesterday.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme
import com.hsseek.betterthanyesterday.ui.theme.White

class FaqActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BetterThanYesterdayTheme {
                // Make the status bar transparent.
                val systemUiController = rememberSystemUiController()
                systemUiController.setSystemBarsColor(
                    color = White
                )

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    WebViewScreen()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebViewScreen() {
        val backEnabled = remember { mutableStateOf(false) }
        var webView: WebView? = null
        val url = "https://blog.naver.com/seoulworkshop/222898712063"

        AndroidView(factory = {
            WebView(this).apply {
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
}