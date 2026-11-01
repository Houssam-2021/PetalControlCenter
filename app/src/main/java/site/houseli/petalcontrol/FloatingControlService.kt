package site.houseli.petalcontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** User-authorized floating edge handle. No interception of stock Samsung gestures. */
class FloatingControlService : Service() {
    companion object {
        private const val NOTIF_CHANNEL = "petal_floating_control"
        private const val NOTIF_ID = 411
        const val ACTION_STOP = "site.houseli.petalcontrol.STOP"
    }

    private lateinit var wm: WindowManager
    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panel: WebView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelBridge: NativeActions? = null
    private val animationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backdropAnimation: android.animation.ValueAnimator? = null
    private val refresh = object : Runnable {
        override fun run() {
            panel?.let { view -> panelBridge?.updateLevels(view); animationHandler.postDelayed(this, 1600) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        makeForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating control requires overlay permission", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        showHandle()
        return START_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private fun makeForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(NOTIF_CHANNEL, "Petal Control floating handle",
                NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keep a small floating handle visible so you can open the custom controls"
            nm.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FloatingControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Petal Control is active")
            .setContentText("Tap the floating handle; you can stop it here")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_delete, "Stop", stop).build())
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun showHandle() {
        if (handle != null || !Settings.canDrawOverlays(this)) return
        val bubble = TextView(this).apply {
            text = "☷"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(240, 15, 119, 205), Color.argb(235, 3, 50, 116))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(90, 227, 251, 255))
            }
            elevation = dp(8).toFloat()
            contentDescription = "Open Petal Control"
        }
        val params = WindowManager.LayoutParams(
            dp(42), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 2
            y = dp(190)
        }
        var yStart = 0f
        var initialY = 0
        var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    yStart = e.rawY
                    initialY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawY - yStart) > dp(7)) moved = true
                    if (moved) {
                        params.y = (initialY + e.rawY - yStart).toInt()
                            .coerceIn(dp(40), resources.displayMetrics.heightPixels - dp(150))
                        try { wm.updateViewLayout(bubble, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPanel()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(bubble, params)
            handle = bubble
            handleParams = params
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't create floating handle", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPanel() {
        if (panel != null || !Settings.canDrawOverlays(this)) return
        handle?.visibility = View.GONE
        val view = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.configureApp('overlay')", null)
                    panelBridge?.updateLevels(view)
                    animationHandler.removeCallbacks(refresh)
                    animationHandler.postDelayed(refresh, 1600)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
            }
        }
        val bridge = NativeActions(this, overlayDismiss = { hidePanel() }, overlayExit = { animateBackdrop(false) })
        panelBridge = bridge
        view.addJavascriptInterface(bridge, "Android")
        var windowFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
        if (Build.VERSION.SDK_INT >= 31) windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            dimAmount = .15f
            if (Build.VERSION.SDK_INT >= 31) setBlurBehindRadius(dp(45))
        }
        try {
            params.dimAmount = 0f
            if (Build.VERSION.SDK_INT >= 31) params.setBlurBehindRadius(0)
            wm.addView(view, params)
            panel = view
            panelParams = params
            animateBackdrop(true)
            view.loadUrl("file:///android_asset/index.html?overlay=1")
        } catch (e: Exception) {
            view.destroy()
            handle?.visibility = View.VISIBLE
            Toast.makeText(this, "Couldn't display Control Center", Toast.LENGTH_LONG).show()
        }
    }

    /** Match the reference cross-fade by easing OS background dim and blur together. */
    private fun animateBackdrop(show: Boolean) {
        val view = panel ?: return
        val params = panelParams ?: return
        backdropAnimation?.cancel()
        val from = if (show) 0f else 1f
        val to = if (show) 1f else 0f
        backdropAnimation = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (show) 520L else 510L
            interpolator = android.view.animation.PathInterpolator(.22f, .79f, .28f, 1f)
            addUpdateListener { animation ->
                if (panel !== view) return@addUpdateListener
                val progress = animation.animatedValue as Float
                params.dimAmount = progress * .16f
                if (Build.VERSION.SDK_INT >= 31) params.setBlurBehindRadius(dp((46f * progress).toInt()))
                try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
            }
            start()
        }
    }

    private fun hidePanel() {
        backdropAnimation?.cancel()
        backdropAnimation = null
        val previous = panel ?: return
        panel = null
        animationHandler.removeCallbacks(refresh)
        panelBridge?.dispose()
        panelBridge = null
        try { wm.removeView(previous) } catch (_: Exception) { }
        previous.removeJavascriptInterface("Android")
        previous.destroy()
        handle?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        hidePanel()
        handle?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        handle = null
        super.onDestroy()
    }
}
