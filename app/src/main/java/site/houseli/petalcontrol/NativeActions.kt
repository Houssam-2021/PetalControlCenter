package site.houseli.petalcontrol

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.nfc.NfcAdapter
import android.bluetooth.BluetoothAdapter
import android.location.LocationManager
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Read-only system state plus permission-gated, documented platform operations.
 * The bridge is exposed ONLY to the bundled local HTML page; no third party URL is loaded.
 * All requested operations are initiated by an explicit user tap or drag.
 */
class NativeActions(
    private val context: Context,
    private val overlayRequest: (() -> Unit)? = null,
    private val overlayDismiss: (() -> Unit)? = null,
    private val overlayExit: (() -> Unit)? = null,
    private val cameraPermissionRequest: (() -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile private var torchKnown = false
    @Volatile private var torchOn = false
    @Volatile private var torchReady = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            try {
                if (cameras.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    torchKnown = true
                    torchReady = true
                    torchOn = enabled
                }
            } catch (_: Exception) { }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            torchReady = false
        }
    }
    init { try { cameras.registerTorchCallback(torchCallback, handler) } catch (_: Exception) {} }
    fun dispose() { try { cameras.unregisterTorchCallback(torchCallback) } catch (_: Exception) {} }

    private fun onUi(action: () -> Unit) { handler.post(action) }
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    @JavascriptInterface fun requestFloatingControl() = onUi {
        overlayRequest?.invoke() ?: toast("Open Petal Control to enable the floating handle")
    }
    @JavascriptInterface fun closeOverlay() = onUi { overlayDismiss?.invoke() }
    @JavascriptInterface fun onPanelClosed() = onUi { overlayExit?.invoke() }

    private fun startSettings(action: String) {
        try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { toast("Android settings unavailable") }
        }
    }

    @JavascriptInterface fun openSettings(which: String): Boolean {
        onUi {
            val action = when (which) {
                "wifi", "mobile" -> if (Build.VERSION.SDK_INT >= 29)
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "plane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "rotate", "auto", "theme", "eye", "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "wallet" -> Settings.ACTION_NFC_SETTINGS
                "pin" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "sync" -> Settings.ACTION_SYNC_SETTINGS
                "settings" -> Settings.ACTION_SETTINGS
                "video", "cast" -> Settings.ACTION_CAST_SETTINGS
                "link", "nearby" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
                "nfc" -> Settings.ACTION_NFC_SETTINGS
                "mute", "vibrate", "moon", "music", "sound" -> Settings.ACTION_SOUND_SETTINGS
                "mediaAccess" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            if (which == "video") {
                // There is no documented public API to start Samsung's system screen recorder.
                toast("Use Samsung Quick Settings for the built-in screen recorder")
            } else if (which == "wallet") {
                val walletIntent = listOf("com.google.android.apps.walletnfcrel", "com.samsung.android.spay")
                    .firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                if (walletIntent != null) {
                    overlayDismiss?.invoke()
                    try { context.startActivity(walletIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: Exception) { toast("Unable to open wallet app") }
                } else {
                    overlayDismiss?.invoke()
                    startSettings(Settings.ACTION_NFC_SETTINGS)
                }
            } else if (which == "home") {
                // Launch a real installed smart-home app; never draw simulated device states.
                val homeIntent = listOf("com.samsung.android.oneconnect", "com.google.android.apps.chromecast.app")
                    .firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                if (homeIntent == null) {
                    toast("Install Google Home or SmartThings to use Home controls")
                } else {
                    if (overlayDismiss != null) overlayDismiss.invoke()
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(homeIntent) } catch (_: Exception) { toast("Could not open a smart-home app") }
                }
            } else if (which == "flash") {
                toggleTorch()
            } else if (which == "link") {
                toast("Sharing connections is controlled by the Android system")
                overlayDismiss?.invoke()
                startSettings(Settings.ACTION_WIRELESS_SETTINGS)
            } else {
                // Don't leave an always-on-top WebView obscuring the settings panel.
                if (overlayDismiss != null) overlayDismiss.invoke()
                startSettings(action)
            }
        }
        return true
    }

    private fun requireWriteSettings(): Boolean {
        if (Settings.System.canWrite(context)) return true
        toast("Allow Modify system settings to enable this control")
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
        return false
    }

    @JavascriptInterface fun setBrightness(value: Int) = onUi {
        if (!requireWriteSettings()) return@onUi
        try {
            val mode = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                toast("Turn off Auto brightness before adjusting brightness manually")
                return@onUi
            }
            val level = (value.coerceIn(1, 100) * 255f / 100f).roundToInt()
            if (!Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level))
                toast("Brightness change not supported")
        } catch (_: Exception) { toast("Brightness change failed") }
    }

    @JavascriptInterface fun setAutoBrightness(enabled: Boolean) = onUi {
        if (!requireWriteSettings()) return@onUi
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        } catch (_: Exception) { toast("Unable to change Auto brightness") }
    }

    @JavascriptInterface fun toggleRotation() = onUi {
        if (!requireWriteSettings()) return@onUi
        try {
            val current = Settings.System.getInt(context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                if (current == 1) 0 else 1)
        } catch (_: Exception) { toast("Rotation control not supported on this device") }
    }

    @JavascriptInterface fun setVolume(value: Int) = onUi {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                (value.coerceIn(0, 100) * max / 100f).roundToInt(), 0)
        } catch (_: Exception) { toast("Unable to adjust media volume") }
    }

    @JavascriptInterface fun setSoundMode(mode: String): Boolean {
        if (mode !in listOf("normal", "silent", "vibrate")) return false
        onUi {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val desired = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            try {
                audio.ringerMode = desired
            } catch (_: SecurityException) {
                toast("Allow Do Not Disturb access to change sound mode")
                startSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            } catch (_: Exception) { toast("This phone does not allow direct sound mode changes") }
        }
        return true
    }

    @JavascriptInterface fun toggleTorch() = onUi {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (context is Activity) {
                cameraPermissionRequest?.invoke() ?: toast("Grant camera permission to use flashlight")
            } else {
                toast("Open Petal Control and grant camera permission for flashlight")
                try { context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
            return@onUi
        }
        try {
            val cameraId = cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                cameras.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) { toast("No compatible rear flashlight found"); return@onUi }
            cameras.setTorchMode(cameraId, !torchOn)
        } catch (_: Exception) { toast("Flashlight unavailable (camera may be in use)") }
    }

    @JavascriptInterface fun toggleDnd() = onUi {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                toast("Allow Do Not Disturb access to toggle it")
                startSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                return@onUi
            }
            nm.setInterruptionFilter(if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (_: Exception) { toast("Unable to change Do Not Disturb") }
    }

    private fun sessionController(): MediaController? {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.getActiveSessions(ComponentName(context, ControlMediaListener::class.java)).firstOrNull()
        } catch (_: Exception) { null }
    }

    @JavascriptInterface fun media(command: String) = onUi {
        val session = sessionController()
        try {
            if (session != null) {
                when (command) {
                    "play" -> {
                        if (session.playbackState?.state == PlaybackState.STATE_PLAYING)
                            session.transportControls.pause() else session.transportControls.play()
                    }
                    "next" -> session.transportControls.skipToNext()
                    "previous" -> session.transportControls.skipToPrevious()
                }
            } else {
                val key = when (command) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> return@onUi
                }
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        } catch (_: Exception) { toast("Unable to control the current media session") }
    }

    /** JSON with observed system state. A missing value is represented as null, NEVER faked. */
    @JavascriptInterface fun getState(): String {
        val result = JSONObject()
        result.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        result.put("android", Build.VERSION.SDK_INT)
        try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            result.put("brightness", (raw * 100f / 255f).roundToInt())
            result.put("autoBrightness", Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            result.put("rotation", Settings.System.getInt(context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
        } catch (_: Exception) { }
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            result.put("volume", if (max > 0) (100f * cur / max).roundToInt() else 0)
            result.put("ringer", when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
        } catch (_: Exception) { }
        try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            result.put("wifiEnabled", wifiManager.isWifiEnabled)
            result.put("wifiConnected", wifi)
            result.put("cellConnected", cell)
            // Note: cellular transport ≠ mobile data switch enabled; do not claim to know that setting.
        } catch (_: Exception) { }
        // Bluetooth is public information on Android 9-11. Android 12+ requires
        // BLUETOOTH_CONNECT, so leave it unknown instead of requesting an unrelated permission.
        if (Build.VERSION.SDK_INT < 31) try {
            result.put("bluetoothEnabled", BluetoothAdapter.getDefaultAdapter()?.isEnabled)
        } catch (_: Exception) { }
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= 28) result.put("location", locationManager.isLocationEnabled)
        } catch (_: Exception) { }
        try {
            val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) result.put("battery", level * 100 / scale)
            }
        } catch (_: Exception) { }
        if (torchKnown) result.put("torch", torchOn)
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            result.put("dnd", manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (_: Exception) { }
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            result.put("batterySaver", pm.isPowerSaveMode)
        } catch (_: Exception) { }
        try {
            result.put("planeMode", Settings.Global.getInt(context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        } catch (_: Exception) { }
        try {
            result.put("nfc", NfcAdapter.getDefaultAdapter(context)?.isEnabled ?: false)
        } catch (_: Exception) { }
        try {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            val mediaAccess = enabled.contains(context.packageName)
            result.put("mediaAccess", mediaAccess)
            if (mediaAccess) {
                val controller = sessionController()
                if (controller != null) {
                    val md = controller.metadata
                    result.put("mediaTitle", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown track")
                    result.put("mediaArtist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                    result.put("mediaPlaying", controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                } else {
                    result.put("mediaTitle", "No active media")
                    result.put("mediaPlaying", false)
                }
            }
        } catch (_: Exception) { }
        return result.toString()
    }

    /** Called from Activity/overlay WebView after load; subsequent changes are polled while visible. */
    fun updateLevels(webView: WebView) {
        val state = getState()
        webView.evaluateJavascript("window.applyNativeState($state)", null)
    }
}
