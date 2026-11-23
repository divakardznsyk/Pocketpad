package app.pocketpad

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Receives files from the laptop (pulled by the phone, see below) while the app is open.
 * Wire format: [2-byte BE name length][name UTF-8][8-byte BE size][bytes]
 * Arrives as a temp file; MainActivity decides where it goes.
 */
class FileReceiver(
    private val ctx: Context,
    private val ipProvider: () -> String,
    private val onFile: (File, String) -> Unit,
    private val onEvent: (String) -> Unit,
) {
    @Volatile
    private var running = false

    /**
     * The phone always reaches the laptop (phone to laptop works everywhere), so it is the one that opens
     * the connections: a keep-alive ping to the laptop every 2 s keeps the laptop's "connected" light on and
     * gives the laptop a way to say "I have a file for you" (PPOF + token), which the phone then pulls over TCP.
     */
    fun start() {
        running = true
        thread(name = "pocketpad-keepalive", isDaemon = true) { keepAlive() }
    }

    fun stop() {
        running = false
    }

    private fun keepAlive() {
        var sock: DatagramSocket? = null
        var cur = ""
        val seen = HashSet<Long>()
        val msg = "PPAD?".toByteArray()
        try {
            while (running) {
                val ip = ipProvider()
                if (ip.isBlank()) {
                    Thread.sleep(1000)
                    continue
                }
                if (sock == null || ip != cur) {
                    sock?.close()
                    val addr = try { InetAddress.getByName(ip) } catch (_: Exception) { Thread.sleep(1000); continue }
                    sock = try { DatagramSocket(InetSocketAddress(Net.localFor(addr), 0)) } catch (_: Exception) { Thread.sleep(1000); continue }
                    cur = ip
                }
                try { sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), Net.PAD_PORT)) } catch (_: Exception) {}
                val end = System.currentTimeMillis() + 2000
                val buf = ByteArray(64)
                val p = DatagramPacket(buf, buf.size)
                while (running) {
                    val left = (end - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    try {
                        sock.soTimeout = left
                        sock.receive(p)
                        if (p.length >= 12 && String(buf, 0, 4) == "PPOF") {
                            val token = ByteBuffer.wrap(buf, 4, 8).long
                            val from = p.address
                            if (seen.add(token)) thread { pull(from, token) }
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            sock?.close()
        }
    }

    /** Connect to the laptop, say which offer we want, then read the file in the usual format. */
    private fun pull(laptop: InetAddress, token: Long) {
        try {
            val s = Socket()
            Net.localFor(laptop)?.let { s.bind(InetSocketAddress(it, 0)) }
            s.connect(InetSocketAddress(laptop, Net.OFFER_PORT), 4000)
            val o = DataOutputStream(s.getOutputStream())
            o.writeLong(token)
            o.flush()
            handle(s)
        } catch (e: Exception) {
            onEvent("Receive failed: ${e.message}")
        }
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

                // Into a temp file first; the app then saves it where the person chose.
                val tmp = File.createTempFile("in", ".tmp", ctx.cacheDir)
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var left = size
                    while (left > 0) {
                        val n = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) {
                            tmp.delete()
                            throw java.io.IOException("Connection closed early")
                        }
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                onFile(tmp, name)
            }
        } catch (e: Exception) {
            onEvent("Receive failed: ${e.message}")
        }
    }
}
