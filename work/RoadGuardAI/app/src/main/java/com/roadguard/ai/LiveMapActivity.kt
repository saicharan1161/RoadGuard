package com.roadguard.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import javax.net.ssl.HttpsURLConnection

/** Bundled map only; native pinned requests keep pairing secrets out of JavaScript. */
class LiveMapActivity : Activity() {
    private lateinit var web:WebView
    @Volatile private var visible=false
    @Volatile private var connection:HttpsURLConnection?=null
    private val host="appassets.androidplatform.net"
    private val files=setOf("map.html","map.js","map-state.js","map.css","leaflet.js","leaflet.css")
    private val policy="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src https://tile.openstreetmap.org data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
    private fun response(code:Int,text:String)=WebResourceResponse("application/json","UTF-8",code,if(code==200)"OK" else "Unavailable",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        web=WebView(this)
        val root=android.widget.LinearLayout(this).apply {orientation=android.widget.LinearLayout.VERTICAL;setBackgroundColor(android.graphics.Color.rgb(12,20,32))}
        root.addView(android.widget.Button(this).apply {text="Back to RoadGuard";setOnClickListener {finish()}},android.widget.LinearLayout.LayoutParams(-1,-2))
        root.addView(web,android.widget.LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) {v,insets->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        web.settings.apply {
            javaScriptEnabled=true
            allowFileAccess=false;allowContentAccess=false
            mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
            userAgentString=userAgentString+" RoadGuard/"+packageManager.getPackageInfo(packageName,0).versionName
            cacheMode=android.webkit.WebSettings.LOAD_DEFAULT
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,url:String):Boolean {
                val uri=android.net.Uri.parse(url)
                if(uri.scheme=="https" && uri.host=="www.openstreetmap.org" && uri.path=="/copyright") {
                    try {startActivity(Intent(Intent.ACTION_VIEW,uri))} catch(_:Exception) {}
                }
                return true
            }
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                val uri=request.url
                if(uri.scheme!="https" || request.method!="GET")return response(403,"{}")
                if(uri.host=="tile.openstreetmap.org" && uri.path?.matches(Regex("/\\d+/\\d+/\\d+\\.png"))==true)return null
                if(uri.host!=host)return response(403,"{}")
                if(uri.path=="/location") {
                    if(!visible)return response(503,"{}")
                    var c:HttpsURLConnection?=null
                    return try {
                        c=PhoneRoutes.get(this@LiveMapActivity,"/location"+if(uri.getQueryParameter("refresh")=="1")"?refresh=1" else "")
                        connection=c
                        if(c.responseCode!=200)response(c.responseCode,"{}") else {
                            val data=c.inputStream.use { input ->
                                val buffer=java.io.ByteArrayOutputStream();val block=ByteArray(4096)
                                while(true){val n=input.read(block);if(n<0)break;if(buffer.size()+n>65536)throw java.io.IOException("Location response too large");buffer.write(block,0,n)}
                                buffer.toString("UTF-8")
                            }
                            org.json.JSONObject(data)
                            response(200,data)
                        }
                    } catch(_:javax.net.ssl.SSLException) {response(403,"{}")}
                    catch(_:Exception) {response(503,"{}")}
                    finally {c?.disconnect();connection=null}
                }
                val file=uri.path?.removePrefix("/") ?: ""
                if(file !in files)return response(404,"{}")
                return try {
                    val mime=when {file.endsWith(".html")->"text/html";file.endsWith(".css")->"text/css";else->"application/javascript"}
                    WebResourceResponse(mime,"UTF-8",200,"OK",mapOf("Content-Security-Policy" to policy,"Referrer-Policy" to "strict-origin-when-cross-origin","X-Content-Type-Options" to "nosniff"),assets.open("live-map/$file"))
                } catch(_:Exception){response(503,"{}")}
            }
        }
        web.loadUrl("https://$host/map.html")
    }
    override fun onResume(){super.onResume();visible=true;web.onResume();web.evaluateJavascript("if(window.roadGuardVisible)window.roadGuardVisible(true)",null)}
    override fun onPause(){visible=false;connection?.disconnect();web.evaluateJavascript("if(window.roadGuardVisible)window.roadGuardVisible(false)",null);web.onPause();super.onPause()}
    override fun onDestroy(){connection?.disconnect();web.stopLoading();web.destroy();super.onDestroy()}
}
