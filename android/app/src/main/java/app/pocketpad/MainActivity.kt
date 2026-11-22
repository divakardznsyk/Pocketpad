package app.pocketpad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private lateinit var status: TextView
    private lateinit var shareStatus: TextView
    private var receiver: FileReceiver? = null
    private val ui = Handler(Looper.getMainLooper())

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
        receiver = FileReceiver(this) { msg -> runOnUiThread { shareStatus.text = msg } }.also { it.start() }
    }

    override fun onStop() {
        super.onStop()
        receiver?.stop()
        receiver = null
    }

    // ---------- UI ----------

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setBackgroundColor(if (primary) Color.rgb(245, 197, 66) else Color.rgb(40, 40, 46))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(8) }
        }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        val grey = Color.rgb(160, 160, 168)

        col.addView(label("Pocketpad", 30f, bold = true))

        status = label("Looking for your laptop…", 15f, grey)
        col.addView(status)

        ipField = EditText(this).apply {
            hint = "Laptop address, e.g. 192.168.1.5"
            setHintTextColor(Color.rgb(100, 100, 108))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        col.addView(ipField)
        col.addView(button("Find laptop", false) { discover() })

        col.addView(label("Controller", 20f, bold = true).apply { setPadding(0, dp(28), 0, dp(4)) })
        col.addView(label("Turns this phone into an Xbox controller for your laptop.", 14f, grey))
        col.addView(button("Open controller", true) { openController() })

        col.addView(label("Share files", 20f, bold = true).apply { setPadding(0, dp(28), 0, dp(4)) })
        col.addView(
            label(
                "Send files to the laptop, or tap \"Send file to phone\" in the laptop app. Keep this screen open while receiving.",
                14f, grey
            )
        )
        col.addView(button("Send files to laptop", false) { pickFiles() })
        shareStatus = label("", 14f, Color.rgb(110, 220, 140))
        col.addView(shareStatus)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(14, 14, 16))
            isFillViewport = true
            addView(col)
            fitsSystemWindows = true
        })
    }

    // ---------- Connection ----------

    private fun currentIp(): String = ipField.text.toString().trim()

    private fun saveIp(ip: String) {
        getSharedPreferences("pocketpad", Context.MODE_PRIVATE).edit().putString("ip", ip).apply()
    }

    private fun discover() {
        status.setTextColor(Color.rgb(160, 160, 168))
        status.text = "Looking for your laptop…"
        val saved = currentIp()
        thread {
            val found = Net.discover(saved)
            runOnUiThread {
                if (found != null) {
                    ipField.setText(found.first)
                    saveIp(found.first)
                    status.setTextColor(Color.rgb(110, 220, 140))
                    status.text = "Found ${found.second}  •  ${found.first}"
                } else {
                    status.setTextColor(Color.rgb(255, 130, 120))
                    status.text = "Laptop not found. Open Pocketpad on the laptop, make sure both are on the same Wi-Fi " +
                        "(or phone hotspot), or type the address shown in the laptop app."
                }
            }
        }
    }

    private fun openController() {
        val ip = currentIp()
        if (ip.isEmpty()) {
            status.text = "Enter or find the laptop address first."
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
            shareStatus.setTextColor(Color.rgb(255, 130, 120))
            shareStatus.text = "Find the laptop first (address is empty)."
            return
        }
        shareStatus.setTextColor(Color.rgb(110, 220, 140))
        shareStatus.text = "Sending ${uris.size} file(s)…"
        thread {
            var sent = 0
            for (u in uris) {
                try {
                    sendUri(ip, u)
                    sent++
                } catch (e: Exception) {
                    runOnUiThread {
                        shareStatus.setTextColor(Color.rgb(255, 130, 120))
                        shareStatus.text = "Send failed: ${e.message}"
                    }
                    return@thread
                }
            }
            runOnUiThread {
                shareStatus.setTextColor(Color.rgb(110, 220, 140))
                shareStatus.text = "Sent $sent file(s) to the laptop (Downloads/Pocketpad)"
            }
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
