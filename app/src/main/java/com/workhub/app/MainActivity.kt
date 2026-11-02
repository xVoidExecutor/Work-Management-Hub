package com.workhub.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070914.toInt()
        window.navigationBarColor = 0xFF070914.toInt()

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(0xFF070914.toInt())
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                openExternal(u)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean = false
        }

        web.loadUrl("https://$HOST/assets/index.html")
    }

    private fun openExternal(u: Uri) {
        try {
            if (u.scheme == "http" || u.scheme == "https") {
                startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", u.toString()))
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, u))
            }
        } catch (e: Exception) {
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){var b=document.getElementById('bk');if(b){b.click();return 1}return 0})()"
        ) { r -> if (r != "1") finish() }
    }
}
