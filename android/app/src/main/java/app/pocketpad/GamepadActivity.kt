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
    private lateinit var presets: MutableList<Preset>
    private var sel = 0
    private var sender: PadSender? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("pocketpad", Context.MODE_PRIVATE)

        // Use the whole screen, including the area around the camera hole.
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Battery: a dimmed OLED screen is the biggest saver while playing.
            if (prefs.getBoolean("dim", true)) it.screenBrightness = 0.2f
        }
        // Highest refresh rate at the current resolution (120 Hz on this phone) = smoother, lower touch-to-pixel delay.
        try {
            val d = display
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        } catch (_: Exception) {
        }

        presets = PresetStore.load(this)
        sel = PresetStore.selected(this, presets.size)
        view = GamepadView(this, state, presets[sel])
        view.haptics = prefs.getBoolean("hap", true)
        view.onSave = { PresetStore.save(this, presets, sel) }
        view.onClose = {
            PresetStore.save(this, presets, sel)
            finish()
        }
        setContentView(view)
        hideBars()

        if (intent.getBooleanExtra("edit", false)) {
            view.exitOnDone = true
            view.startEditing()
        }
    }

    override fun onResume() {
        super.onResume()
        hideBars()

        val prefs = getSharedPreferences("pocketpad", Context.MODE_PRIVATE)
        val ip = intent.getStringExtra("ip")
        if (ip == null || intent.getBooleanExtra("edit", false)) return

        // Ask Wi-Fi for its lowest-latency mode while we're on screen (costs a little battery, so it's optional).
        if (prefs.getBoolean("ll", true)) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pocketpad").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            } catch (_: Exception) {
            }
        }

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
        PresetStore.save(this, presets, sel)
        sender?.finish()
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
