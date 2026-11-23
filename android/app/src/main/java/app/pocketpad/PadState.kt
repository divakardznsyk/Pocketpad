package app.pocketpad

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Button bits, must match windows/GamepadHost.cs */
object Bit {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val L3 = 1 shl 8
    const val R3 = 1 shl 9
    const val UP = 1 shl 10
    const val DOWN = 1 shl 11
    const val LEFT = 1 shl 12
    const val RIGHT = 1 shl 13
}

/** Current controller state, written by the touch view, read by the sender thread. */
class PadState {
    @Volatile var buttons = 0
    @Volatile var lt = 0
    @Volatile var rt = 0
    @Volatile var lx = 0
    @Volatile var ly = 0
    @Volatile var rx = 0
    @Volatile var ry = 0

    // Touchpad: movement piles up here until the sender drains it; bit 0 left click, bit 1 right click.
    val mouseDx = AtomicInteger()
    val mouseDy = AtomicInteger()
    @Volatile var mouseButtons = 0

    fun addMouse(dx: Int, dy: Int) {
        mouseDx.addAndGet(dx)
        mouseDy.addAndGet(dy)
        changed()
    }

    /** Wakes the sender; set by [PadSender]. */
    @Volatile var listener: Runnable? = null

    /** Call after changing any value above. */
    fun changed() {
        listener?.run()
    }
}

/**
 * Streams the state to the laptop over UDP.
 * It sleeps until something changes (sending at once) and otherwise only sends a small heartbeat
 * every 100 ms, so an idle controller barely wakes the CPU or the radio.
 */
class PadSender(private val state: PadState, private val host: InetAddress) : Thread("pocketpad-sender") {
    @Volatile
    var running = true

    /** Called about every 2 seconds with the round-trip time in ms, or -1 when the laptop stopped answering. */
    var onPing: ((Int) -> Unit)? = null

    @Volatile
    private var lastPong = 0L

    override fun run() {
        val socket = try {
            DatagramSocket(InetSocketAddress(Net.localFor(host), 0)).also { it.trafficClass = 0x10 } // low-delay hint
        } catch (_: Exception) {
            return
        }
        state.listener = Runnable { LockSupport.unpark(this) }

        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val mouse = ByteArray(6)
        val mouseBb = ByteBuffer.wrap(mouse).order(ByteOrder.LITTLE_ENDIAN)
        val mousePacket = DatagramPacket(mouse, mouse.size, host, Net.PAD_PORT)
        var lastMouseButtons = 0
        val cur = ByteArray(14)
        val prev = ByteArray(14)
        val bb = ByteBuffer.wrap(cur).order(ByteOrder.LITTLE_ENDIAN)
        val packet = DatagramPacket(cur, cur.size, host, Net.PAD_PORT)
        var lastSend = 0L

        // Ping: "PPNG" + sender's nanoTime; the laptop echoes it back unchanged.
        val ping = ByteArray(12)
        val pingBuf = ByteBuffer.wrap(ping)
        pingBuf.put("PPNG".toByteArray())
        val pingPacket = DatagramPacket(ping, ping.size, host, Net.PAD_PORT)
        var lastPing = 0L
        lastPong = System.nanoTime()

        socket.soTimeout = 500
        val listener = Thread {
            val b = ByteArray(32)
            val p = DatagramPacket(b, b.size)
            while (running) {
                try {
                    socket.receive(p)
                    if (p.length >= 12 && String(b, 0, 4) == "PPNG") {
                        val sent = ByteBuffer.wrap(b, 4, 8).long
                        lastPong = System.nanoTime()
                        onPing?.invoke(((lastPong - sent) / 1_000_000L).toInt())
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: IOException) {
                    if (!running) break
                }
            }
        }
        listener.isDaemon = true
        listener.start()

        while (running) {
            bb.put(0, 1)
            bb.put(1, 1)
            bb.putShort(2, state.buttons.toShort())
            bb.put(4, state.lt.toByte())
            bb.put(5, state.rt.toByte())
            bb.putShort(6, state.lx.toShort())
            bb.putShort(8, state.ly.toShort())
            bb.putShort(10, state.rx.toShort())
            bb.putShort(12, state.ry.toShort())

            val now = System.nanoTime()
            if (!cur.contentEquals(prev) || now - lastSend > 100_000_000L) {
                try {
                    socket.send(packet)
                } catch (_: IOException) {
                    // network hiccup, try again on the next wake-up
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
                lastSend = now
            }
            val mdx = state.mouseDx.getAndSet(0).coerceIn(-32767, 32767)
            val mdy = state.mouseDy.getAndSet(0).coerceIn(-32767, 32767)
            val mb = state.mouseButtons
            if (mdx != 0 || mdy != 0 || mb != lastMouseButtons) {
                mouseBb.put(0, 2)
                mouseBb.put(1, mb.toByte())
                mouseBb.putShort(2, mdx.toShort())
                mouseBb.putShort(4, mdy.toShort())
                try { socket.send(mousePacket) } catch (_: IOException) {}
                lastMouseButtons = mb
            }
            if (now - lastPing > 2_000_000_000L) {
                pingBuf.putLong(4, now)
                try { socket.send(pingPacket) } catch (_: IOException) {}
                lastPing = now
                if (now - lastPong > 5_000_000_000L) onPing?.invoke(-1)
            }
            // Sleep until a touch wakes us, or at most until the next heartbeat is due.
            LockSupport.parkNanos(this, 100_000_000L)
            if (Thread.interrupted()) break
        }
        state.listener = null
        // Tell the laptop everything is released.
        java.util.Arrays.fill(cur, 2, 14, 0.toByte())
        try { socket.send(packet) } catch (_: IOException) {}
        if (lastMouseButtons != 0) {
            mouseBb.put(1, 0)
            mouseBb.putShort(2, 0)
            mouseBb.putShort(4, 0)
            try { socket.send(mousePacket) } catch (_: IOException) {}
        }
        socket.close()
    }

    /** Stop and wake the loop right away. */
    fun finish() {
        running = false
        LockSupport.unpark(this)
    }
}
