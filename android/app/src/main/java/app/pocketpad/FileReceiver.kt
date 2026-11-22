package app.pocketpad

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Receives files from the laptop on Net.PHONE_PORT while the app is open.
 * Wire format: [2-byte BE name length][name UTF-8][8-byte BE size][bytes]
 * Files land in Downloads/Pocketpad.
 */
class FileReceiver(private val ctx: Context, private val onEvent: (String) -> Unit) {
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        running = true
        thread(name = "pocketpad-recv") {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(Net.PHONE_PORT))
                server = s
                while (running) {
                    val c = s.accept()
                    thread { handle(c) }
                }
            } catch (e: Exception) {
                if (running) onEvent("Receiver problem: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
    }

    private fun handle(c: Socket) {
        try {
            c.use {
                val din = DataInputStream(BufferedInputStream(it.getInputStream(), 256 * 1024))
                val len = din.readUnsignedShort()
                val nameBytes = ByteArray(len)
                din.readFully(nameBytes)
                var name = String(nameBytes, Charsets.UTF_8).substringAfterLast('/').substringAfterLast('\\')
                if (name.isBlank()) name = "file"
                val size = din.readLong()

                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pocketpad")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create file in Downloads")

                resolver.openOutputStream(uri)!!.use { out ->
                    val buf = ByteArray(256 * 1024)
                    var left = size
                    while (left > 0) {
                        val n = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw java.io.IOException("Connection closed early")
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                onEvent("Received $name (saved in Downloads/Pocketpad)")
            }
        } catch (e: Exception) {
            onEvent("Receive failed: ${e.message}")
        }
    }
}
