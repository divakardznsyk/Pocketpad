package app.pocketpad

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
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

    /**
     * The phone's own address on the same subnet as [peer]. Sockets are bound to it because with mobile data off
     * (hotspot, no default network) an unbound socket can fail with "network unreachable" even for the laptop next to it.
     */
    fun localFor(peer: InetAddress): InetAddress? {
        try {
            if (peer !is Inet4Address) return null
            val p = ByteBuffer.wrap(peer.address).int
            val all = NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val ni = all.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val bits = ia.networkPrefixLength.toInt()
                    val mask = if (bits == 0) 0 else -1 shl (32 - bits)
                    if ((ByteBuffer.wrap(a.address).int and mask) == (p and mask)) return a
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Looks for the laptop app on every network the phone has. Returns address, name and round trip in ms, or null. */
    fun discover(savedIp: String?): Found? {
        val msg = "PPAD?".toByteArray()
        val cands = ArrayList<Pair<InetAddress?, InetAddress?>>() // local address, its broadcast address
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val ni = all.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) if (ia.address is Inet4Address) cands.add(Pair(ia.address, ia.broadcast))
            }
        } catch (_: Exception) {
        }
        if (cands.isEmpty()) cands.add(Pair(null, null))
        repeat(2) {
            for ((local, bc) in cands) {
                val s = try { DatagramSocket(InetSocketAddress(local, 0)) } catch (_: Exception) { continue }
                try {
                    s.broadcast = true
                    s.soTimeout = 500
                    val t0 = System.nanoTime()
                    val targets = ArrayList<InetAddress>()
                    bc?.let { targets.add(it) }
                    try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
                    if (!savedIp.isNullOrBlank()) try { targets.add(InetAddress.getByName(savedIp)) } catch (_: Exception) {}
                    for (t in targets) try { s.send(DatagramPacket(msg, msg.size, t, PAD_PORT)) } catch (_: Exception) {}
                    val buf = ByteArray(256)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val txt = String(p.data, 0, p.length)
                    if (txt.startsWith("PPAD!")) {
                        return Found(p.address.hostAddress ?: "", txt.substring(5), ((System.nanoTime() - t0) / 1_000_000L).toInt())
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                } finally {
                    s.close()
                }
            }
        }
        return null
    }
}
