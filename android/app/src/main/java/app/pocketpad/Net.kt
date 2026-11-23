package app.pocketpad

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class Found(val ip: String, val name: String, val rtt: Int)

object Net {
    const val PAD_PORT = 39500    // laptop listens (controller packets + discovery)
    const val FILE_PORT = 39501   // laptop listens (files from phone)
    const val PHONE_PORT = 39502  // phone listens (files from laptop)

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Wi-Fi mode: when the Wi-Fi has no internet, Android likes to route app traffic over mobile data,
     * which breaks local connections, so we bind the app to the Wi-Fi network.
     * USB / hotspot mode: the phone is the router, so we must NOT be bound to any Wi-Fi network.
     */
    fun applyMode(ctx: Context, usb: Boolean) {
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
            wifiCallback?.let {
                try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
            }
            wifiCallback = null
            cm.bindProcessToNetwork(null)
            if (usb) return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                }
            }
            wifiCallback = cb
            cm.requestNetwork(req, cb)
        } catch (_: Exception) {
            // Not fatal: local networking usually still works.
        }
    }

    /** True when the phone is sharing a USB cable connection (USB tethering is on). */
    fun usbLinkUp(): Boolean {
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val ni = all.nextElement()
                val n = ni.name.lowercase()
                if (!ni.isUp) continue
                if (n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb")) return true
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val ni = all.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** Looks for the laptop app. Returns address, computer name and round trip in ms, or null. Blocks up to ~3 seconds. */
    fun discover(savedIp: String?): Found? {
        val msg = "PPAD?".toByteArray()
        val s = DatagramSocket()
        try {
            s.broadcast = true
            s.soTimeout = 700
            repeat(4) {
                val t0 = System.nanoTime()
                for (b in broadcastAddresses()) {
                    try { s.send(DatagramPacket(msg, msg.size, b, PAD_PORT)) } catch (_: Exception) {}
                }
                try {
                    s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), PAD_PORT))
                } catch (_: Exception) {
                }
                if (!savedIp.isNullOrBlank()) {
                    try {
                        s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(savedIp), PAD_PORT))
                    } catch (_: Exception) {
                    }
                }
                val buf = ByteArray(256)
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                    val txt = String(p.data, 0, p.length)
                    if (txt.startsWith("PPAD!")) {
                        return Found(p.address.hostAddress ?: "", txt.substring(5), ((System.nanoTime() - t0) / 1_000_000L).toInt())
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            s.close()
        }
        return null
    }
}
