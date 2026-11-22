package app.pocketpad

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
}

/** Streams the state to the laptop over UDP: immediately on change, and every 50 ms as a heartbeat. */
class PadSender(private val state: PadState, private val host: InetAddress) : Thread("pocketpad-sender") {
    @Volatile
    var running = true

    override fun run() {
        val socket = try {
            DatagramSocket().also { it.trafficClass = 0x10 } // low-delay hint
        } catch (_: Exception) {
            return
        }
        val cur = ByteArray(14)
        val prev = ByteArray(14)
        val bb = ByteBuffer.wrap(cur).order(ByteOrder.LITTLE_ENDIAN)
        val packet = DatagramPacket(cur, cur.size, host, Net.PAD_PORT)
        var lastSend = 0L

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
            if (!cur.contentEquals(prev) || now - lastSend > 50_000_000L) {
                try {
                    socket.send(packet)
                } catch (_: IOException) {
                    // network hiccup, try again on the next loop
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
                lastSend = now
            }
            try {
                sleep(2)
            } catch (_: InterruptedException) {
                break
            }
        }
        // Tell the laptop everything is released.
        java.util.Arrays.fill(cur, 2, 14, 0.toByte())
        try { socket.send(packet) } catch (_: IOException) {}
        socket.close()
    }
}
