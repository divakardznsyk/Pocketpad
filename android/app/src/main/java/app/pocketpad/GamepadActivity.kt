package app.pocketpad

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.net.InetAddress

class GamepadActivity : Activity() {
    private val state = PadState()
    private lateinit var view: GamepadView
    private var sender: PadSender? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Use the whole screen, including the area around the camera hole.
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view = GamepadView(this, state)
        view.onClose = { finish() }
        setContentView(view)
        hideBars()
    }

    override fun onResume() {
        super.onResume()
        hideBars()

        // Ask Wi-Fi for its lowest-latency mode while we're on screen.
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pocketpad").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (_: Exception) {
        }

        val ip = intent.getStringExtra("ip") ?: return
        view.pingMs = -2
        Thread {
            try {
                val s = PadSender(state, InetAddress.getByName(ip))
                s.onPing = { ms ->
                    view.pingMs = ms
                    view.postInvalidate()
                }
                sender = s
                s.start()
            } catch (_: Exception) {
                view.pingMs = -1
                view.postInvalidate()
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        sender?.running = false
        sender = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
