package app.pocketpad

import android.app.Activity
import android.app.AlertDialog
import android.animation.LayoutTransition
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.webkit.MimeTypeMap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
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
import android.widget.Toast
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

    private lateinit var title: TextView
    private lateinit var homeScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var clip: EditText
    private lateinit var modeHelp: TextView
    private lateinit var segBt: TextView
    private var applyingClip = false
    private val clipSend = Runnable { receiver?.sendText(clip.text.toString()) }
    private lateinit var hero: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroLink: TextView
    private lateinit var pillText: TextView
    private lateinit var pillDot: View
    private lateinit var chipRow: LinearLayout
    private lateinit var ipField: EditText
    private lateinit var hint: TextView
    private lateinit var shareStatus: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var saveAsk: TextView
    private lateinit var saveFolder: TextView
    private lateinit var usbBox: LinearLayout
    private lateinit var usbStatus: TextView
    private lateinit var segWifi: TextView
    private lateinit var segUsb: TextView

    private var receiver: FileReceiver? = null
    private val ui = Handler(Looper.getMainLooper())

    private var linkState = 0
    private var linkName = ""
    private var linkRtt = -1

    // incoming files waiting to be saved
    private class Pending(val tmp: File, val name: String)
    private class Entry(val text: String, val ok: Boolean, val uri: Uri?)
    private val pending = ArrayDeque<Pending>()
    private val log = ArrayList<Entry>()
    private var asking = false
    private var resumed = false
    private var eggBusy = false

    private val prefs get() = getSharedPreferences("pocketpad", Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presets = PresetStore.load(this)
        sel = PresetStore.selected(this, presets.size)
        Net.applyMode(this, prefs.getInt("mode", 0) >= 1)
        buildUi()
        ipField.setText(prefs.getString("ip", ""))
        refreshPreset()
        refreshSave()
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
        if (receiver == null) receiver = FileReceiver(
            this,
            ipProvider = { prefs.getString("ip", "") ?: "" },
            onFile = { tmp, name -> runOnUiThread { pending.add(Pending(tmp, name)); pump() } },
            onText = { t ->
                runOnUiThread {
                    if (::clip.isInitialized && clip.text.toString() != t) {
                        applyingClip = true
                        clip.setText(t)
                        clip.setSelection(clip.text.length)
                        applyingClip = false
                    }
                }
            },
            onEvent = { msg -> runOnUiThread { addLog(msg, msg.startsWith("RECEIVING"), null) } },
        ).also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Presets may have been edited on the controller screen.
        presets = PresetStore.load(this)
        sel = PresetStore.selected(this, presets.size)
        if (::hero.isInitialized) {
            refreshPreset()
            updateUsbStatus()
        }
        pump()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    // Not onStop: files from the laptop must still arrive while the controller screen is open.
    override fun onDestroy() {
        super.onDestroy()
        receiver?.stop()
        receiver = null
    }

    // ---------- small UI helpers ----------

    private fun cham(fill: Int, stroke: Int, cutDp: Int, tick: Int = 0) =
        ChamferDrawable(fill, stroke, dp(1).toFloat(), dp(cutDp).toFloat(), tick)

    private fun ripple(content: Drawable, cutDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, cham(Color.WHITE, 0, cutDp))

    private fun tv(s: String, sp: Float, color: Int = Theme.ink, bold: Boolean = true, upper: Boolean = true): TextView =
        TextView(this).apply {
            text = if (upper) s.uppercase() else s
            textSize = sp
            setTextColor(color)
            typeface = Theme.cond(bold)
            letterSpacing = if (upper) 0.1f else 0.02f
        }

    private fun body(s: String, sp: Float, color: Int = Theme.sub): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = Theme.cond(false)
            setLineSpacing(0f, 1.15f)
        }

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    /** kind: 0 outline, 1 filled yellow. */
    private fun styleAs(v: TextView, kind: Int) {
        v.setTextColor(if (kind == 1) Theme.bg else Theme.ink)
        v.background = ripple(if (kind == 1) cham(Theme.accent, Theme.accent, 10) else cham(Theme.surface, Theme.outline, 10), 10)
    }

    private fun btn(label: String, kind: Int, h: Int = 52, top: Int = 12, onClick: () -> Unit): TextView =
        tv(label, 14f, Theme.ink).apply {
            gravity = Gravity.CENTER
            isClickable = true
            styleAs(this, kind)
            setOnClickListener { onClick() }
            layoutParams = lp(match, dp(h), top)
        }

    private fun card(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cham(Theme.surface, Theme.line, 14, Theme.accent)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = lp(match, wrap, 16)
            for (v in views) addView(v)
        }

    private fun section(label: String): TextView =
        tv("// $label", 11f, Theme.cyan).apply { setPadding(0, 0, 0, dp(8)) }

    private fun dot(sizeDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun setDot(v: View, color: Int) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    // ---------- screen ----------

    private inner class Sliders : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink; strokeWidth = dp(2).toFloat() }
        override fun onDraw(c: Canvas) {
            for (i in 0..2) {
                val y = height * (0.3f + 0.2f * i)
                c.drawLine(width * 0.28f, y, width * 0.72f, y, p)
                c.drawCircle(width * (0.4f + 0.1f * ((i * 2) % 3)), y, dp(3).toFloat(), p)
            }
        }
    }

    private fun scroll(col: View) = ScrollView(this).apply {
        setBackgroundColor(Theme.bg)
        isFillViewport = true
        fitsSystemWindows = true
        addView(col)
    }

    private fun colView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(40))
        // cards below an opening section slide instead of jumping
        layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(180)
        }
    }

    private inner class Chevron : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.sub
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = android.graphics.Path()
        override fun onDraw(c: Canvas) {
            path.reset()
            path.moveTo(width * 0.38f, height * 0.25f)
            path.lineTo(width * 0.64f, height * 0.5f)
            path.lineTo(width * 0.38f, height * 0.75f)
            c.drawPath(path, p)
        }
    }

    /** A card with a tappable heading that opens and closes its content smoothly. */
    private fun accordion(title: String, vararg content: View): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
            for (v in content) addView(v)
        }
        val chev = Chevron().apply { layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(32)
            isClickable = true
            addView(tv(title, 13f).apply { layoutParams = LinearLayout.LayoutParams(0, wrap, 1f) })
            addView(chev)
            setOnClickListener {
                val open = inner.visibility == View.GONE
                inner.visibility = if (open) View.VISIBLE else View.GONE
                chev.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
            }
        }
        return card(head, inner).apply {
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(180)
            }
        }
    }

    private fun row(vararg v: View, top: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(match, wrap, top)
        for (x in v) addView(x)
    }

    private fun half(label: String, kind: Int, h: Int, first: Boolean, onClick: () -> Unit) =
        btn(label, kind, h, 0, onClick).apply {
            layoutParams = lp(0, dp(h), 0, 1f).apply { if (first) marginEnd = dp(6) else marginStart = dp(6) }
        }

    private fun buildUi() {
        // ----- home: play, send files, clipboard -----
        val home = colView()
        title = tv("Pocketpad", 24f, Theme.ink).apply {
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
            setOnClickListener { egg() }
        }
        pillDot = dot(8).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        pillText = tv("", 11f, Theme.ink)
        val linkPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(cham(Theme.surface, Theme.outline, 8), 8)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { discover() }
            addView(pillDot)
            addView(pillText)
        }
        val gear = Sliders().apply {
            background = ripple(cham(Theme.surface, Theme.outline, 8), 8)
            isClickable = true
            setOnClickListener { showSettings(true) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)).apply { marginStart = dp(8) }
        }
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(linkPill)
            addView(gear)
        })

        hero = tv("", 36f, Theme.ink).apply { letterSpacing = 0.04f; setPadding(0, dp(2), 0, 0) }
        heroSub = tv("", 12f, Theme.sub)
        heroLink = tv("", 12f, Theme.sub).apply { setPadding(0, dp(6), 0, 0) }
        home.addView(card(
            tv("// Play", 11f, Theme.cyan), hero, heroSub, heroLink,
            btn("Start playing", 1, 56, 16) { openController() },
            row(half("Change preset", 0, 44, true) { pickPreset() }, half("Customise", 0, 44, false) { customise() }, top = 10),
        ))

        shareStatus = tv("", 12f, Theme.accent).apply { visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(card(
            tv("// Files", 11f, Theme.cyan),
            btn("Send to laptop", 0, 52, 10) { pickFiles() },
            shareStatus, logBox,
        ))

        clip = EditText(this).apply {
            hint = "Type or paste here. It shows up on the laptop live, and the other way round."
            setHintTextColor(Color.rgb(90, 88, 74))
            setTextColor(Theme.ink)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(15000))
            background = cham(Color.BLACK, Theme.outline, 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(match, wrap, 10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (applyingClip) return
                    ui.removeCallbacks(clipSend)
                    ui.postDelayed(clipSend, 150)
                }
            })
        }
        fun third(label: String, onClick: () -> Unit) = btn(label, 0, 44, 0, onClick).apply {
            layoutParams = lp(0, dp(44), 0, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
        }
        home.addView(card(
            tv("// Clipboard", 11f, Theme.cyan), clip,
            row(
                third("Copy") {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("pocketpad", clip.text.toString()))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                },
                third("Paste") {
                    val t = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)
                    if (!t.isNullOrEmpty()) clip.setText(t)
                },
                third("Clear") { clip.setText("") },
                top = 10,
            ),
        ))

        // ----- settings: everything else, folded away -----
        val set = colView()
        set.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv("Settings", 24f, Theme.ink).apply {
                letterSpacing = 0.2f
                layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
            })
            addView(tv("Back", 12f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                background = ripple(cham(Theme.surface, Theme.outline, 8), 8)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setOnClickListener { showSettings(false) }
            })
        })

        val build = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        set.addView(tv("Build $build", 11f, Theme.sub).apply { setPadding(0, dp(4), 0, 0) })

        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        set.addView(accordion("Presets",
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            },
            body("One preset per game: layout, button changes, opacity, stick and touchpad speed. Hold a preset for more options.", 13f)
                .apply { setPadding(0, dp(10), 0, 0) },
            btn("Sensitivity", 0, 48, 12) { sensitivityDialog() },
        ))

        saveAsk = tv("Ask every time", 12f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { prefs.edit().remove("tree").apply(); refreshSave() }
            layoutParams = lp(0, dp(44), 0, 1f).apply { marginEnd = dp(6) }
        }
        saveFolder = tv("Choose folder", 12f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 3) }
            layoutParams = lp(0, dp(44), 0, 1f).apply { marginStart = dp(6) }
        }
        set.addView(accordion("Save received files", row(saveAsk, saveFolder)))

        fun seg(label: String, m: Int, first: Boolean = false, last: Boolean = false) = tv(label, 12f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { setMode(m) }
            layoutParams = lp(0, dp(44), 0, 1f).apply {
                if (!first) marginStart = dp(4)
                if (!last) marginEnd = dp(4)
            }
        }
        segWifi = seg("Wi-Fi", 0, first = true)
        segUsb = seg("USB", 1)
        segBt = seg("Bluetooth", 2, last = true)
        usbStatus = tv("", 12f, Theme.sub).apply { setPadding(0, dp(12), 0, 0) }
        modeHelp = body("", 14f, Theme.ink)
        usbBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(modeHelp)
            addView(btn("Open tethering settings", 0, 44) { openTetherSettings() })
            addView(usbStatus)
        }
        ipField = EditText(this).apply {
            hint = "192.168.1.5"
            setHintTextColor(Color.rgb(90, 88, 74))
            setTextColor(Theme.ink)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            background = cham(Theme.surface, Theme.outline, 10)
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val find = tv("Find", 12f, Theme.bg).apply {
            gravity = Gravity.CENTER
            isClickable = true
            styleAs(this, 1)
            setOnClickListener { discover() }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(48)).apply { marginStart = dp(10) }
        }
        hint = body("", 13f).apply { setPadding(0, dp(12), 0, 0) }
        set.addView(accordion("Connection",
            row(segWifi, segUsb, segBt),
            usbBox,
            row(ipField, find, top = 16),
            hint,
        ))

        set.addView(accordion("Performance",
            toggleRow("Dim screen while playing", "dim", true),
            toggleRow("Low-latency Wi-Fi", "ll", true),
            toggleRow("Haptics", "hap", true),
            body("The USB cable also charges the phone while you play. Hotspot and Bluetooth drain it fastest.", 13f)
                .apply { setPadding(0, dp(12), 0, 0) },
        ))

        homeScroll = scroll(home)
        settingsScroll = scroll(set).apply { visibility = View.GONE }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Theme.bg)
            addView(homeScroll)
            addView(settingsScroll)
        })
    }

    private fun showSettings(on: Boolean) {
        settingsScroll.visibility = if (on) View.VISIBLE else View.GONE
        homeScroll.visibility = if (on) View.GONE else View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::settingsScroll.isInitialized && settingsScroll.visibility == View.VISIBLE) showSettings(false)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun pickPreset() {
        dialog().setTitle("Preset")
            .setItems(presets.map { it.name }.toTypedArray()) { _, i ->
                sel = i
                savePresets()
                refreshPreset()
            }.show()
    }

    private fun toggleRow(label: String, key: String, def: Boolean): View {
        val state = tv("", 12f)
        fun paint() {
            val on = prefs.getBoolean(key, def)
            state.text = if (on) "ON" else "OFF"
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
            addView(tv(label, 12f).apply { layoutParams = LinearLayout.LayoutParams(0, wrap, 1f) })
            addView(state, LinearLayout.LayoutParams(dp(68), dp(38)))
            layoutParams = lp(match, dp(52))
        }
    }

    // ---------- easter egg ----------

    private fun scramble(to: String, done: () -> Unit) {
        val glyphs = "#@\$%&01<>/|"
        val total = 14
        var step = 0
        val r = object : Runnable {
            override fun run() {
                val n = to.length * step / total
                title.text = to.take(n) + to.drop(n).map { if (it == ' ') ' ' else glyphs.random() }.joinToString("")
                title.setTextColor(if (step == total) Theme.accent else if (step % 2 == 0) Theme.cyan else Theme.red)
                if (step++ < total) ui.postDelayed(this, 45) else done()
            }
        }
        ui.post(r)
    }

    private fun egg() {
        if (eggBusy) return
        eggBusy = true
        scramble("BY DIVAKAR ;)") {
            ui.postDelayed({
                scramble("POCKETPAD") {
                    title.setTextColor(Theme.ink)
                    eggBusy = false
                }
            }, 1600)
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
        heroSub.text = "${if (p.playstation) "PS5" else "XBOX"} LOOK  //  LS ${"%.1f".format(p.leftGain)}  RS ${"%.1f".format(p.rightGain)}  PAD ${"%.1f".format(p.padSpeed)}"
        chipRow.removeAllViews()
        for ((i, pr) in presets.withIndex()) {
            val chip = tv(pr.name, 12f).apply {
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
        chipRow.addView(tv("+ New", 12f).apply {
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
        box.addView(body("Higher means faster with a smaller thumb movement. You can also change these live in a game with TUNE.", 13f))
        fun row(title: String, get: () -> Float, set: (Float) -> Unit, lo: Float = 1f, steps: Int = 20) {
            val label = tv("", 12f).apply { setPadding(0, dp(18), 0, dp(4)) }
            fun upd() {
                label.text = "$title   ${"%.1f".format(get())}X"
            }
            upd()
            val bar = SeekBar(this).apply {
                max = steps
                progress = Math.round((get() - lo) * 10f).coerceIn(0, steps)
                progressTintList = ColorStateList.valueOf(Theme.accent)
                thumbTintList = ColorStateList.valueOf(Theme.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                        set(lo + v / 10f)
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
        row("TOUCHPAD", { p.padSpeed }, { p.padSpeed = it }, 0.5f, 35)
        dialog().setTitle("Sensitivity - ${p.name}")
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener { savePresets(); refreshPreset() }
            .show()
    }

    // ---------- connection ----------

    private fun setMode(m: Int) {
        prefs.edit().putInt("mode", m).apply()
        Net.applyMode(this, m >= 1)
        styleAs(segWifi, if (m == 0) 1 else 0)
        styleAs(segUsb, if (m == 1) 1 else 0)
        styleAs(segBt, if (m == 2) 1 else 0)
        usbBox.visibility = if (m >= 1) View.VISIBLE else View.GONE
        modeHelp.text = if (m == 1) "1.  Plug the phone into the laptop.\n2.  Turn on USB tethering.\n3.  Tap Find."
        else "1.  Pair the phone and laptop in Bluetooth settings.\n2.  On the phone turn on Bluetooth tethering.\n3.  On the laptop: Bluetooth settings, the phone, Connect using, Access point.\n4.  Tap Find. Slower than the cable, so expect more delay."
        updateUsbStatus()
    }

    private fun updateUsbStatus() {
        if (!::usbStatus.isInitialized) return
        val bt = prefs.getInt("mode", 0) == 2
        val up = if (bt) Net.btLinkUp() else Net.usbLinkUp()
        usbStatus.text = (if (bt) "BLUETOOTH LINK " else "USB LINK ") + (if (up) "DETECTED" else "NOT DETECTED")
        usbStatus.setTextColor(if (up) Theme.cyan else Theme.sub)
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
    private fun setLink(state: Int, name: String = "", rtt: Int = -1) {
        linkState = state
        setDot(pillDot, when (state) { 1 -> Theme.cyan; 0 -> Theme.accent; else -> Theme.red })
        pillText.text = when (state) {
            1 -> name.uppercase()
            0 -> "SEARCHING"
            else -> "OFFLINE"
        }
        heroLink.text = when (state) {
            1 -> "LINKED  //  ${name.uppercase()}" + if (rtt >= 0) "  //  $rtt MS" else ""
            0 -> "SEARCHING FOR LAPTOP"
            else -> "NO LAPTOP FOUND"
        }
        heroLink.setTextColor(when (state) { 1 -> Theme.cyan; 0 -> Theme.accent; else -> Theme.red })
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
                    ipField.setText(found.ip)
                    saveIp(found.ip)
                    setLink(1, found.name, found.rtt)
                    hint.text = "Connected to ${found.name}."
                } else {
                    setLink(2)
                    hint.text = if (prefs.getInt("mode", 0) >= 1)
                        "Not found. Check the cable or Bluetooth link is up, tethering is on, and Pocketpad is open on the laptop."
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
        shareStatus.setTextColor(if (ok) Theme.accent else Theme.red)
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

    // ---------- saving received files ----------

    private fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun treeLabel(tree: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { "" }
        val path = id.substringAfter(':', "")
        return (path.substringAfterLast('/').ifBlank { "Storage" }).uppercase().take(18)
    }

    private fun refreshSave() {
        val tree = prefs.getString("tree", null)?.let { Uri.parse(it) }
        styleAs(saveAsk, if (tree == null) 1 else 0)
        styleAs(saveFolder, if (tree != null) 1 else 0)
        saveFolder.text = if (tree != null) "FOLDER: ${treeLabel(tree)}" else "CHOOSE FOLDER"
    }

    private fun addLog(text: String, ok: Boolean, uri: Uri?) {
        log.add(0, Entry(text, ok, uri))
        if (log.size > 5) log.removeAt(log.size - 1)
        logBox.removeAllViews()
        for (e in log) {
            logBox.addView(tv(e.text + if (e.uri != null) "   >" else "", 12f, if (e.ok) Theme.ink else Theme.red).apply {
                setPadding(0, dp(10), 0, 0)
                if (e.uri != null) setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, mime(e.text)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) {
                        addLog("NO APP TO OPEN THAT FILE", false, null)
                    }
                }
            })
        }
    }

    /** Save the next received file: straight into the chosen folder, or ask where each time. */
    private fun pump() {
        if (asking || pending.isEmpty() || !resumed) return
        val p = pending.first()
        val tree = prefs.getString("tree", null)?.let { Uri.parse(it) }
        if (tree != null) {
            pending.removeFirst()
            thread {
                try {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val dest = DocumentsContract.createDocument(contentResolver, parent, mime(p.name), p.name)
                        ?: throw java.io.IOException("could not create file")
                    copyTo(p, dest)
                    runOnUiThread { addLog("SAVED ${p.name}  //  ${treeLabel(tree)}", true, dest); pump() }
                } catch (e: Exception) {
                    runOnUiThread {
                        prefs.edit().remove("tree").apply()
                        refreshSave()
                        pending.addFirst(p)
                        addLog("FOLDER UNAVAILABLE, ASKING INSTEAD", false, null)
                        pump()
                    }
                }
            }
            return
        }
        asking = true
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime(p.name)
            putExtra(Intent.EXTRA_TITLE, p.name)
        }, 2)
    }

    private fun copyTo(p: Pending, dest: Uri) {
        contentResolver.openOutputStream(dest)!!.use { o -> p.tmp.inputStream().use { it.copyTo(o) } }
        p.tmp.delete()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3) {
            val t = data?.data
            if (resultCode == RESULT_OK && t != null) {
                try {
                    contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    prefs.edit().putString("tree", t.toString()).apply()
                } catch (_: Exception) {
                    addLog("COULD NOT USE THAT FOLDER", false, null)
                }
            }
            refreshSave()
            return
        }
        if (requestCode == 2) {
            asking = false
            val p = pending.removeFirstOrNull()
            val dest = data?.data
            if (p != null) {
                if (resultCode == RESULT_OK && dest != null) {
                    thread {
                        try {
                            copyTo(p, dest)
                            runOnUiThread { addLog("SAVED ${p.name}", true, dest); pump() }
                        } catch (e: Exception) {
                            runOnUiThread { addLog("SAVE FAILED: ${e.message}", false, null); pump() }
                        }
                    }
                } else {
                    p.tmp.delete()
                    addLog("DISCARDED ${p.name}", false, null)
                    pump()
                }
            }
            return
        }
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
            runOnUiThread { setShare("", true); addLog("SENT $sent FILE(S) TO LAPTOP", true, null) }
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
                Net.localFor(java.net.InetAddress.getByName(ip))?.let { s.bind(InetSocketAddress(it, 0)) }
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
