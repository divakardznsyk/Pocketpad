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

object Net {
    const val PAD_PORT = 39500    // laptop listens (controller packets + discovery)
    const val FILE_PORT = 39501   // laptop listens (files from phone)
    const val PHONE_PORT = 39502  // phone listens (files from laptop)

    /**
     * When the Wi-Fi has no internet, Android likes to route app traffic over mobile data,
     * which breaks local connections. Binding the process to the Wi-Fi network avoids that.
     * If the phone is the hotspot (no Wi-Fi client network), nothing happens and that's fine.
     */
    fun bindToWifi(ctx: Context) {
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                }
            })
        } catch (_: Exception) {
            // Not fatal: local networking usually still works.
        }
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

    /** Looks for the laptop app. Returns (ip, computerName) or null. Blocks up to ~3 seconds. */
    fun discover(savedIp: String?): Pair<String, String>? {
        val msg = "PPAD?".toByteArray()
        val s = DatagramSocket()
        try {
            s.broadcast = true
            s.soTimeout = 700
            repeat(4) {
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
                        return Pair(p.address.hostAddress ?: "", txt.substring(5))
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
