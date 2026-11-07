package com.mxff.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    lateinit var web: WebView
    lateinit var bridge: NativeBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        bridge = NativeBridge(this)
        web.addJavascriptInterface(bridge, "MXFFNative")
        web.settings.allowFileAccess = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.scheme == "file") return false
                bridge.openUri(r.url.toString()); return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
        }
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl("file:///android_asset/www/index.html") else web.restoreState(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        emit(JSONObject().put("type", "status"))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onResume() { super.onResume(); visible = true; OverlayService.setAppOpen(true) }
    override fun onPause() { visible = false; OverlayService.setAppOpen(false); super.onPause() }

    override fun onDestroy() { bridge.shutdown(); if (instance === this) instance = null; super.onDestroy() }

    fun emit(event: JSONObject) {
        val js = "window.MXFF_onNative && window.MXFF_onNative(${JSONObject.quote(event.toString())})"
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    companion object { var instance: MainActivity? = null; @Volatile var visible = false }
}
