package app.pocketpad

import android.app.Activity
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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var ipField: EditText
    private lateinit var pillText: TextView
    private lateinit var pillDot: View
    private lateinit var hint: TextView
    private lateinit var shareStatus: TextView
    private var receiver: FileReceiver? = null
    private val ui = Handler(Looper.getMainLooper())

    private val cBg = Color.rgb(10, 10, 12)
    private val cCard = Color.rgb(20, 20, 23)
    private val cLine = Color.rgb(38, 38, 44)
    private val cText = Color.rgb(242, 242, 244)
    private val cSub = Color.rgb(139, 139, 149)
    private val cAccent = Color.rgb(53, 224, 161)
    private val cBad = Color.rgb(255, 107, 99)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.bindToWifi(this)
        buildUi()
        ipField.setText(getSharedPreferences("pocketpad", Context.MODE_PRIVATE).getString("ip", ""))
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

    override fun onStop() {
        super.onStop()
        receiver?.stop()
        receiver = null
    }

    // ---------- UI building blocks ----------

    private fun shape(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun ripple(content: Drawable, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(Color.WHITE, radius))

    private fun text(
        s: String, sp: Float, color: Int = cText, medium: Boolean = false,
    ): TextView = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(8, 24, 18) else cText)
            val base = if (primary) shape(cAccent, 16) else shape(Color.rgb(30, 30, 35), 16, cLine)
            background = ripple(base, 16)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
                .apply { topMargin = dp(16) }
        }

    private fun card(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cCard, 24, cLine)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            views.forEach { addView(it) }
        }

    private fun setPill(color: Int, label: String) {
        pillDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        pillText.text = label
    }

    private fun setShare(msg: String, ok: Boolean) {
        shareStatus.setTextColor(if (ok) cAccent else cBad)
        shareStatus.text = msg
        shareStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // Header: title + connection pill
        pillDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) } }
        pillText = text("", 13f, cText, true)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(shape(cCard, 20, cLine), 20)
            setPadding(dp(14), dp(9), dp(16), dp(9))
            isClickable = true
            setOnClickListener { discover() }
            addView(pillDot)
            addView(pillText)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Pocketpad", 26f, cText, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pill)
        }
        col.addView(header)
        setPill(cSub, "Searching…")

        // Controller
        col.addView(
            card(
                text("Controller", 20f, cText, true),
                text("Play PC games with this phone. It shows up as an Xbox controller.", 14f, cSub)
                    .apply { setPadding(0, dp(6), 0, 0) },
                button("Start playing", true) { openController() },
            )
        )

        // Files
        shareStatus = text("", 14f, cAccent).apply {
            visibility = View.GONE
            setPadding(0, dp(14), 0, 0)
        }
        col.addView(
            card(
                text("Files", 20f, cText, true),
                text("Send anything to the laptop, or use Send in the laptop app. Keep this screen open to receive.", 14f, cSub)
                    .apply { setPadding(0, dp(6), 0, 0) },
                button("Send to laptop", false) { pickFiles() },
                shareStatus,
            )
        )

        // Connection
        ipField = EditText(this).apply {
            hint = "192.168.1.5"
            setHintTextColor(Color.rgb(90, 90, 98))
            setTextColor(cText)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            background = shape(Color.rgb(13, 13, 15), 14, cLine)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val find = text("Find", 15f, cText, true).apply {
            gravity = Gravity.CENTER
            background = ripple(shape(Color.rgb(30, 30, 35), 14, cLine), 14)
            isClickable = true
            setOnClickListener { discover() }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(48)).apply { marginStart = dp(10) }
        }
        hint = text("", 13f, cSub).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(
            card(
                text("Laptop address", 14f, cSub, true).apply { setPadding(0, 0, 0, dp(10)) },
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(ipField)
                    addView(find)
                },
                hint,
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            fitsSystemWindows = true
            addView(col)
        })
    }

    // ---------- Connection ----------

    private fun currentIp(): String = ipField.text.toString().trim()

    private fun saveIp(ip: String) {
        getSharedPreferences("pocketpad", Context.MODE_PRIVATE).edit().putString("ip", ip).apply()
    }

    private fun discover() {
        setPill(cSub, "Searching…")
        hint.text = ""
        val saved = currentIp()
        thread {
            val found = Net.discover(saved)
            runOnUiThread {
                if (found != null) {
                    ipField.setText(found.first)
                    saveIp(found.first)
                    setPill(cAccent, found.second)
                    hint.text = "Connected to ${found.second}."
                } else {
                    setPill(cBad, "Not connected")
                    hint.text = "Open Pocketpad on the laptop and join the same Wi-Fi, or turn on this phone's hotspot " +
                        "and connect the laptop to it. You can also type the address shown in the laptop app."
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
        startActivity(Intent(this, GamepadActivity::class.java).putExtra("ip", ip))
    }

    // ---------- Sending files ----------

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
        setShare("Sending ${uris.size} file(s)…", true)
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
