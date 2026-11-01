package site.houseli.petalcontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var page: WebView
    private lateinit var bridge: NativeActions
    private var waitingForOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 37, 104)
        window.navigationBarColor = Color.rgb(5, 31, 70)
        page = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    bridge.updateLevels(view)
                    view.evaluateJavascript("window.configureApp('preview')", null)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    return request.url.scheme != "file"
                }
            }
        }
        bridge = NativeActions(this, overlayRequest = { requestOverlay() }, cameraPermissionRequest = { requestCameraPermission() })
        page.addJavascriptInterface(bridge, "Android")
        setContentView(page)
        page.loadUrl("file:///android_asset/index.html")
        maybeRequestNotificationPermission()
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 203)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Ask only after the user opts into floating controls, not at first launch.
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            startFloatingControl()
        } else {
            waitingForOverlay = true
            Toast.makeText(this,
                "Allow 'Display over other apps' to use the floating handle", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun startFloatingControl() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 202)
            return
        }
        try {
            val service = Intent(this, FloatingControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
            else startService(service)
            Toast.makeText(this,
                "Floating control enabled. Tap its edge handle to open Control Center.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot start floating control: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202) {
            // The foreground-service notification still exists even when permission is denied.
            try {
                val service = Intent(this, FloatingControlService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
                else startService(service)
            } catch (_: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            startFloatingControl()
        }
        if (::page.isInitialized && ::bridge.isInitialized) page.post { bridge.updateLevels(page) }
    }

    override fun onDestroy() {
        if (::page.isInitialized) {
            if (::bridge.isInitialized) bridge.dispose()
            page.removeJavascriptInterface("Android")
            page.destroy()
        }
        super.onDestroy()
    }
}
