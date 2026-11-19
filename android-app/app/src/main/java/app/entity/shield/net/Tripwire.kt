package app.entity.shield.net

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * LAN tripwire: a phone has no reason to receive connections on these ports, so anything that touches
 * them is another device on the same Wi-Fi probing the phone (port scan, password guessing, or the
 * ADB-over-network worms that hunt port 5555). Each connection is logged with the first bytes the
 * other side sent, then closed; nothing is served. Ports below 1024 need root, so only high ports.
 */
class Tripwire(private val ctx: Context) {
    private val sockets = mutableListOf<ServerSocket>()

    @Synchronized
    fun start() {
        if (sockets.isNotEmpty()) return
        val bound = mutableListOf<Int>()
        for (p in PORTS) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p), 16)
                sockets += ss
                bound += p
                thread(name = "trip-$p", isDaemon = true) { accept(ss, p) }
            } catch (e: IOException) {
                Log.i(TAG, "port $p unavailable: ${e.message}") // e.g. 5555 when adbd listens on Wi-Fi
            }
        }
        NetGuard.setListening(bound)
        Log.i(TAG, "tripwire listening on $bound")
    }

    private fun accept(ss: ServerSocket, port: Int) {
        while (!ss.isClosed) {
            val c: Socket = try { ss.accept() } catch (e: IOException) { break }
            thread(isDaemon = true) { inspect(c, port) }
        }
    }

    private fun inspect(c: Socket, port: Int) {
        val ip = c.inetAddress?.hostAddress ?: "?"
        var banner = ""
        try {
            c.soTimeout = 1500
            val buf = ByteArray(128)
            val n = c.getInputStream().read(buf)
            if (n > 0) banner = String(buf, 0, n, Charsets.ISO_8859_1).map { if (it.code in 32..126) it else '.' }.joinToString("")
        } catch (_: IOException) {
        } finally {
            runCatching { c.close() }
        }
        if (ip == "127.0.0.1" || ip == "::1") return
        NetGuard.onTrip(ctx, ip, port, banner)
    }

    @Synchronized
    fun stop() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        NetGuard.setListening(emptyList())
    }

    companion object {
        private const val TAG = "ShieldNet"
        val PORTS = intArrayOf(2222, 2323, 5555, 8080, 8443, 9999)
    }
}
