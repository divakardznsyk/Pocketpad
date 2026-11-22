package app.pocketpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var presets: MutableList<Preset>
    private var sel = 0

    private lateinit var hero: DotText
    private lateinit var heroSub: TextView
    private lateinit var heroDot: View
    private lateinit var pillText: TextView
    private lateinit var pillDot: View
    private lateinit var chipRow: LinearLayout
    private lateinit var ipField: EditText
    private lateinit var hint: TextView
    private lateinit var shareStatus: TextView
    private lateinit var usbBox: LinearLayout
    private lateinit var usbStatus: TextView
    private lateinit var segWifi: TextView
    private lateinit var segUsb: TextView

    private var receiver: FileReceiver? = null
    private val ui = Handler(Looper.getMainLooper())

    private val prefs get() = getSharedPreferences("pocketpad", Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presets = PresetStore.load(this)
        sel = PresetStore.selected(this, presets.size)
        Net.applyMode(this, prefs.getInt("mode", 0) == 1)
        buildUi()
        ipField.setText(prefs.getString("ip", ""))
        refreshPreset()
        setMode(prefs.getInt("mode", 0))
        handleShareIntent(intent)
        ui.postDelayed({ discover() }, 800)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        receiver = FileReceiver(this) { msg -> runOnUiThread { setShare(msg, true) } }.also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        // Presets may have been edited on the controller screen.
        presets = PresetStore.load(this)
        sel = PresetStore.selected(this, presets.size)
        if (::hero.isInitialized) {
            refreshPreset()
            updateUsbStatus()
        }
    }

    override fun onStop() {
        super.onStop()
        receiver?.stop()
        receiver = null
    }

    // ---------- small UI helpers ----------

    private fun shape(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun ripple(content: Drawable, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(Color.WHITE, radius))

    private fun mono(s: String, sp: Float, color: Int = Theme.ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s.uppercase()
            textSize = sp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = 0.06f
        }

    private fun body(s: String, sp: Float, color: Int = Theme.sub): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    /** kind: 0 outline, 1 filled (off-white), 2 accent. */
    private fun styleAs(v: TextView, kind: Int) {
        val fill = when (kind) {
            1 -> Theme.ink
            2 -> Theme.accent
            else -> Color.TRANSPARENT
        }
        v.setTextColor(if (kind == 0) Theme.ink else Theme.bg)
        v.background = ripple(shape(fill, 100, if (kind == 0) Theme.outline else 0), 100)
    }

    private fun pill(label: String, kind: Int, height: Int = 52, onClick: () -> Unit): TextView =
        mono(label, 13f, Theme.ink, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            styleAs(this, kind)
            setOnClickListener { onClick() }
            layoutParams = lp(match, dp(height), 12)
        }

    private fun hairline(): View = View(this).apply {
        setBackgroundColor(Theme.line)
        layoutParams = lp(match, 1, 26).apply { bottomMargin = dp(22) }
    }

    private fun section(label: String): TextView =
        mono(label, 11f, Theme.sub, true).apply { setPadding(0, 0, 0, dp(6)) }

    private fun dot(sizeDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun setDot(v: View, state: Int) { // 0 hollow, 1 grey, 2 accent
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (state) {
                2 -> setColor(Theme.accent)
                1 -> setColor(Theme.sub)
                else -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Theme.sub)
                }
            }
        }
    }

    // ---------- screen ----------

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(40))
        }

        // top bar: dot-matrix name + link pill
        val title = DotText(this).apply {
            text = "POCKETPAD"
            maxPitch = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
        }
        pillDot = dot(8).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        pillText = mono("", 11f, Theme.ink, true)
        val linkPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(shape(Color.TRANSPARENT, 100, Theme.outline), 100)
            setPadding(dp(14), dp(9), dp(16), dp(9))
            isClickable = true
            setOnClickListener { discover() }
            addView(pillDot)
            addView(pillText)
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(linkPill)
        })

        // hero: current preset in big dots, accent dot when the laptop is found
        hero = DotText(this).apply {
            maxPitch = dp(9).toFloat()
            layoutParams = FrameLayout.LayoutParams(match, wrap)
        }
        heroDot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END)
        }
        col.addView(FrameLayout(this).apply {
            addView(hero)
            addView(heroDot)
            layoutParams = lp(match, wrap, 44)
        })
        heroSub = mono("", 11f, Theme.sub).apply { setPadding(0, dp(14), 0, 0) }
        col.addView(heroSub)

        col.addView(pill("START PLAYING   >", 1, 60) { openController() }.apply { layoutParams = lp(match, dp(60), 28) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("CUSTOMISE", 0, 48) { customise() }.apply {
            layoutParams = lp(0, dp(48), 0, 1f).apply { marginEnd = dp(6) }
        })
        row.addView(pill("STICK SPEED", 0, 48) { sensitivityDialog() }.apply {
            layoutParams = lp(0, dp(48), 0, 1f).apply { marginStart = dp(6) }
        })
        row.layoutParams = lp(match, wrap, 12)
        col.addView(row)

        // presets
        col.addView(section("PRESETS").apply { setPadding(0, dp(28), 0, dp(10)) })
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })
        col.addView(body("One preset per game. Each keeps its own layout, button changes and stick speed. Hold a preset for more options.", 13f)
            .apply { setPadding(0, dp(12), 0, 0) })

        // files
        col.addView(hairline())
        col.addView(section("FILES"))
        col.addView(body("Send anything to the laptop, or use the drop zone in the laptop app. Keep this screen open to receive.", 14f))
        col.addView(pill("SEND TO LAPTOP", 0) { pickFiles() })
        shareStatus = mono("", 12f, Theme.accent).apply {
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(shareStatus)

        // connection
        col.addView(hairline())
        col.addView(section("CONNECTION"))
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        segWifi = mono("WI-FI / HOTSPOT", 12f, Theme.ink, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { setMode(0) }
            layoutParams = lp(0, dp(46), 0, 1f).apply { marginEnd = dp(6) }
        }
        segUsb = mono("USB CABLE", 12f, Theme.ink, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { setMode(1) }
            layoutParams = lp(0, dp(46), 0, 1f).apply { marginStart = dp(6) }
        }
        seg.addView(segWifi)
        seg.addView(segUsb)
        col.addView(seg)

        usbBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(body("1.  Plug the phone into the laptop.\n2.  Turn on USB tethering.\n3.  Tap Find below.", 14f, Theme.ink))
            addView(pill("OPEN TETHERING SETTINGS", 0, 46) { openTetherSettings() })
            usbStatus = mono("", 12f, Theme.sub, true).apply { setPadding(0, dp(12), 0, 0) }
            addView(usbStatus)
        }
        col.addView(usbBox)

        ipField = EditText(this).apply {
            hint = "192.168.1.5"
            setHintTextColor(Color.rgb(90, 90, 94))
            setTextColor(Theme.ink)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            background = shape(Color.TRANSPARENT, 100, Theme.outline)
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val find = mono("FIND", 12f, Theme.bg, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            styleAs(this, 1)
            setOnClickListener { discover() }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(48)).apply { marginStart = dp(10) }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ipField)
            addView(find)
            layoutParams = lp(match, wrap, 16)
        })
        hint = body("", 13f).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(hint)

        // battery
        col.addView(hairline())
        col.addView(section("BATTERY"))
        col.addView(toggleRow("DIM SCREEN WHILE PLAYING", "dim", true))
        col.addView(toggleRow("LOW-LATENCY WI-FI", "ll", true))
        col.addView(body("The USB cable also charges the phone while you play, and hotspot drains it fastest.", 13f)
            .apply { setPadding(0, dp(12), 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.bg)
            isFillViewport = true
            fitsSystemWindows = true
            addView(col)
        })
    }

    private fun toggleRow(label: String, key: String, def: Boolean): View {
        val state = TextView(this)
        fun paint() {
            val on = prefs.getBoolean(key, def)
            state.text = if (on) "ON" else "OFF"
            state.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            state.textSize = 12f
            state.gravity = Gravity.CENTER
            styleAs(state, if (on) 1 else 0)
        }
        state.isClickable = true
        state.setOnClickListener {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply()
            paint()
        }
        paint()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mono(label, 12f, Theme.ink).apply { layoutParams = LinearLayout.LayoutParams(0, wrap, 1f) })
            addView(state, LinearLayout.LayoutParams(dp(68), dp(38)))
            layoutParams = lp(match, dp(52))
        }
    }

    // ---------- presets ----------

    private fun cleanName(s: String): String {
        val ok = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .:+/-_"
        val t = s.uppercase().filter { it in ok }.trim().take(10)
        return t.ifBlank { "PRESET" }
    }

    private fun savePresets() = PresetStore.save(this, presets, sel)

    private fun refreshPreset() {
        val p = presets[sel]
        hero.text = p.name
        heroSub.text = "PRESET  -  ${if (p.playstation) "PS5" else "XBOX"} LOOK"
        chipRow.removeAllViews()
        for ((i, pr) in presets.withIndex()) {
            val chip = mono(pr.name, 12f, Theme.ink, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(20), 0, dp(20), 0)
                styleAs(this, if (i == sel) 1 else 0)
                setOnClickListener {
                    sel = i
                    savePresets()
                    refreshPreset()
                }
                setOnLongClickListener {
                    presetMenu(i)
                    true
                }
                layoutParams = LinearLayout.LayoutParams(wrap, dp(42)).apply { marginEnd = dp(8) }
            }
            chipRow.addView(chip)
        }
        chipRow.addView(mono("+ NEW", 12f, Theme.ink, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(20), 0, dp(20), 0)
            styleAs(this, 0)
            setOnClickListener { newPresetDialog() }
            layoutParams = LinearLayout.LayoutParams(wrap, dp(42))
        })
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun nameInput(initial: String): EditText = EditText(this).apply {
        setText(initial)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        setSelection(text.length)
    }

    private fun newPresetDialog() {
        val input = nameInput("")
        input.hint = "GAME NAME"
        dialog().setTitle("New preset")
            .setView(input)
            .setPositiveButton("Xbox look") { _, _ -> addPreset(cleanName(input.text.toString()), false) }
            .setNegativeButton("PS5 look") { _, _ -> addPreset(cleanName(input.text.toString()), true) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun addPreset(name: String, ps: Boolean) {
        presets.add(Preset(name, ps))
        sel = presets.size - 1
        savePresets()
        refreshPreset()
    }

    private fun presetMenu(i: Int) {
        val items = if (presets.size > 1) arrayOf("Rename", "Duplicate", "Delete") else arrayOf("Rename", "Duplicate")
        dialog().setTitle(presets[i].name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val input = nameInput(presets[i].name)
                        dialog().setTitle("Rename preset").setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                presets[i].name = cleanName(input.text.toString())
                                savePresets()
                                refreshPreset()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    1 -> {
                        presets.add(presets[i].copyAs(cleanName(presets[i].name + " 2")))
                        sel = presets.size - 1
                        savePresets()
                        refreshPreset()
                    }
                    else -> {
                        presets.removeAt(i)
                        sel = sel.coerceIn(0, presets.size - 1)
                        savePresets()
                        refreshPreset()
                    }
                }
            }.show()
    }

    private fun sensitivityDialog() {
        val p = presets[sel]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        box.addView(body("Higher means the stick reaches full speed with a smaller thumb movement. Try 2.0 if the camera feels slow.", 13f))
        fun row(title: String, get: () -> Float, set: (Float) -> Unit) {
            val label = mono("", 12f, Theme.ink, true).apply { setPadding(0, dp(18), 0, dp(4)) }
            fun upd() {
                label.text = "$title   ${"%.1f".format(get())}X"
            }
            upd()
            val bar = SeekBar(this).apply {
                max = 20 // 1.0x .. 3.0x
                progress = ((get() - 1f) * 10f).toInt().coerceIn(0, 20)
                progressTintList = ColorStateList.valueOf(Theme.accent)
                thumbTintList = ColorStateList.valueOf(Theme.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                        set(1f + v / 10f)
                        upd()
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            box.addView(label)
            box.addView(bar)
        }
        row("LEFT STICK", { p.leftGain }, { p.leftGain = it })
        row("RIGHT STICK (CAMERA)", { p.rightGain }, { p.rightGain = it })
        dialog().setTitle("Stick speed - ${p.name}")
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener { savePresets() }
            .show()
    }

    // ---------- connection ----------

    private fun setMode(m: Int) {
        prefs.edit().putInt("mode", m).apply()
        Net.applyMode(this, m == 1)
        styleAs(segWifi, if (m == 0) 1 else 0)
        styleAs(segUsb, if (m == 1) 1 else 0)
        segWifi.setTextColor(if (m == 0) Theme.bg else Theme.ink)
        segUsb.setTextColor(if (m == 1) Theme.bg else Theme.ink)
        usbBox.visibility = if (m == 1) View.VISIBLE else View.GONE
        updateUsbStatus()
    }

    private fun updateUsbStatus() {
        if (!::usbStatus.isInitialized) return
        val up = Net.usbLinkUp()
        usbStatus.text = if (up) "USB LINK DETECTED" else "USB LINK NOT DETECTED"
        usbStatus.setTextColor(if (up) Theme.accent else Theme.sub)
    }

    private fun openTetherSettings() {
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (i in tries) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }

    /** state: 0 searching, 1 found, 2 offline */
    private fun setLink(state: Int, name: String = "") {
        setDot(pillDot, when (state) { 1 -> 2; 0 -> 1; else -> 0 })
        setDot(heroDot, if (state == 1) 2 else 0)
        pillText.text = when (state) {
            1 -> name.uppercase()
            0 -> "SEARCHING"
            else -> "OFFLINE"
        }
    }

    private fun currentIp(): String = ipField.text.toString().trim()

    private fun saveIp(ip: String) {
        prefs.edit().putString("ip", ip).apply()
    }

    private fun discover() {
        setLink(0)
        hint.text = ""
        updateUsbStatus()
        val saved = currentIp()
        thread {
            val found = Net.discover(saved)
            runOnUiThread {
                if (found != null) {
                    ipField.setText(found.first)
                    saveIp(found.first)
                    setLink(1, found.second)
                    hint.text = "Connected to ${found.second}."
                } else {
                    setLink(2)
                    hint.text = if (prefs.getInt("mode", 0) == 1)
                        "Not found. Check the cable is plugged in, USB tethering is on, and Pocketpad is open on the laptop."
                    else
                        "Not found. Open Pocketpad on the laptop and join the same Wi-Fi, or turn on this phone's hotspot and connect the laptop to it. You can also type the address shown in the laptop app."
                }
            }
        }
    }

    private fun openController() {
        val ip = currentIp()
        if (ip.isEmpty()) {
            hint.text = "Enter the laptop address first, or tap Find."
            return
        }
        saveIp(ip)
        savePresets()
        startActivity(Intent(this, GamepadActivity::class.java).putExtra("ip", ip))
    }

    private fun customise() {
        savePresets()
        startActivity(Intent(this, GamepadActivity::class.java).putExtra("edit", true))
    }

    // ---------- sending files ----------

    private fun setShare(msg: String, ok: Boolean) {
        shareStatus.setTextColor(if (ok) Theme.accent else Theme.ink)
        shareStatus.text = msg
        shareStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        sendAll(uris)
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) sendAll(uris)
    }

    private fun sendAll(uris: List<Uri>) {
        val ip = currentIp()
        if (ip.isEmpty()) {
            setShare("Find the laptop first.", false)
            return
        }
        setShare("Sending ${uris.size} file(s)...", true)
        thread {
            var sent = 0
            for (u in uris) {
                try {
                    sendUri(ip, u)
                    sent++
                } catch (e: Exception) {
                    runOnUiThread { setShare("Send failed: ${e.message}", false) }
                    return@thread
                }
            }
            runOnUiThread { setShare("Sent $sent file(s). They're in Downloads\\Pocketpad on the laptop.", true) }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

    private fun querySize(uri: Uri): Long =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else -1L
        } ?: -1L

    private fun sendUri(ip: String, uri: Uri) {
        val name = queryName(uri) ?: "file"
        var size = querySize(uri)
        var tmp: File? = null
        val input: InputStream
        if (size <= 0) {
            // Size unknown: copy to a temp file so we can announce the exact length.
            val f = File(cacheDir, "send.tmp")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            size = f.length()
            tmp = f
            input = f.inputStream()
        } else {
            input = contentResolver.openInputStream(uri)!!
        }
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, Net.FILE_PORT), 4000)
                val out = BufferedOutputStream(s.getOutputStream(), 256 * 1024)
                val dos = DataOutputStream(out)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                dos.writeShort(nameBytes.size)
                dos.write(nameBytes)
                dos.writeLong(size)
                dos.flush()
                input.copyTo(out, 256 * 1024)
                out.flush()
            }
        } finally {
            input.close()
            tmp?.delete()
        }
    }
}
