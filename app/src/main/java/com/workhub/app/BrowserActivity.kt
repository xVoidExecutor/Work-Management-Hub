package com.workhub.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class BrowserActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private lateinit var vault: Vault
    private val popups = ArrayList<WebView>()
    private val submitted = HashSet<String>()
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var desktop = false
    private var asked = ""

    private val mobileUa by lazy { WebSettings.getDefaultUserAgent(this).replace("; wv", "") }
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val fillJs = """
function(u,p,s){
 var pw=document.querySelector('input[type=password]');
 if(!pw||pw.value||pw.offsetParent===null)return;
 function set(el,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
 var ins=[].slice.call(document.querySelectorAll('input')).filter(function(i){return ['text','email','tel',''].indexOf(i.type)>-1&&i.offsetParent!==null});
 var us=null;
 ins.forEach(function(i){if(i.compareDocumentPosition(pw)&4)us=i;});
 if(us&&!us.value&&u)set(us,u);
 set(pw,p);
 if(s){setTimeout(function(){var f=pw.form;var b=f&&f.querySelector('button[type=submit],input[type=submit],button:not([type])');if(b)b.click();else if(f&&f.requestSubmit)f.requestSubmit();},400);}
}
"""

    private val captureJs = """
(function(){
 if(window.__hubCap)return;window.__hubCap=1;
 function grab(){
  var pw=document.querySelector('input[type=password]');
  if(!pw||!pw.value)return;
  var user='';
  [].slice.call(document.querySelectorAll('input')).forEach(function(i){
   if(['text','email','tel',''].indexOf(i.type)>-1&&i.value&&i.compareDocumentPosition(pw)&4)user=i.value;
  });
  HubNative.save(location.hostname,user,pw.value);
 }
 document.addEventListener('submit',grab,true);
 document.addEventListener('click',function(e){
  var t=e.target;
  if(t&&t.closest&&t.closest('button,input[type=submit],[role=button]'))grab();
 },true);
})();
"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070914.toInt()
        window.navigationBarColor = 0xFF0B0E20.toInt()
        vault = Vault(this)

        root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        web = makeWeb()

        val nav = LinearLayout(this).apply {
            setBackgroundColor(0xFF0B0E20.toInt())
            gravity = Gravity.CENTER
        }
        fun btn(t: String, f: (View) -> Unit) = TextView(this).apply {
            text = t
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { f(it) }
        }
        fun add(t: String, f: (View) -> Unit) {
            nav.addView(btn(t, f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        add("🏠") { finish() }
        add("◀") { onBackPressed() }
        add("▶") { if (cur().canGoForward()) cur().goForward() }
        add("⟳") { cur().reload() }
        add("⋮") { menu(it) }

        col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        col.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        web.loadUrl(intent.getStringExtra("url") ?: "about:blank")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cur(): WebView = popups.lastOrNull() ?: web

    private fun hostOf(url: String?): String? = Uri.parse(url ?: "").host?.removePrefix("www.")

    private fun makeWeb(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(0xFF070914.toInt())
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)

        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            userAgentString = if (desktop) desktopUa else mobileUa
        }

        w.addJavascriptInterface(Bridge(), "HubNative")

        w.setDownloadListener { url, ua, cd, mime, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                req.addRequestHeader("User-Agent", ua)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, cd, mime)
                )
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "This download is not supported", Toast.LENGTH_SHORT).show()
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") return false
                try {
                    startActivity(
                        if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        else Intent(Intent.ACTION_VIEW, u)
                    )
                } catch (e: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                bar.visibility = View.GONE
                inject(view, url)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val pop = makeWeb()
                popups.add(pop)
                root.addView(
                    pop,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                )
                (resultMsg.obj as WebView.WebViewTransport).webView = pop
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                closePopup(window)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = filePathCallback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(fileChooserParams.createIntent(), 77)
                    true
                } catch (e: Exception) {
                    chooser = null
                    false
                }
            }
        }
        return w
    }

    private fun closePopup(w: WebView) {
        popups.remove(w)
        root.removeView(w)
        w.destroy()
    }

    private fun inject(v: WebView, url: String) {
        val host = hostOf(url) ?: return
        v.evaluateJavascript(captureJs, null)
        val c = vault.get(host) ?: return
        val submit = submitted.add(host)
        val js = "($fillJs)(${JSONObject.quote(c.first)},${JSONObject.quote(c.second)},$submit)"
        for (d in longArrayOf(0, 900, 2500)) {
            v.postDelayed({ v.evaluateJavascript(js, null) }, d)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun save(host: String, user: String, pw: String) {
            val h = host.removePrefix("www.")
            if (pw.isEmpty()) return
            val saved = vault.get(h)
            if (saved != null && saved.first == user && saved.second == pw) return
            val key = h + user + pw
            if (key == asked) return
            asked = key
            this@BrowserActivity.runOnUiThread {
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("Save login?")
                    .setMessage("$user @ $h")
                    .setPositiveButton("Save") { _, _ -> vault.put(h, user, pw) }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
    }

    private fun menu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (desktop) "Mobile site" else "Desktop site")
        m.menu.add(0, 2, 0, "Copy link")
        m.menu.add(0, 3, 0, "Open in browser")
        m.menu.add(0, 4, 0, "Forget saved login")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    desktop = !desktop
                    web.settings.userAgentString = if (desktop) desktopUa else mobileUa
                    web.reload()
                }
                2 -> {
                    val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("url", cur().url ?: ""))
                    Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                }
                3 -> try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cur().url ?: "")))
                } catch (e: Exception) {
                }
                4 -> {
                    hostOf(cur().url)?.let { h -> vault.remove(h) }
                    Toast.makeText(this, "Saved login removed", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        m.show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            popups.isNotEmpty() -> closePopup(popups.last())
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooser = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        popups.toList().forEach { it.destroy() }
        web.destroy()
        super.onDestroy()
    }
}
