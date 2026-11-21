package app.entity.shield.net


import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DNS-only local VPN. Only the fake resolver address is routed into the TUN interface, so the rest of
 * the phone's traffic never touches this service. Every DNS question is attributed to the app that
 * asked (getConnectionOwnerUid), scored on the phone, then forwarded to the network's own resolver,
 * or answered NXDOMAIN if it is a scam domain. Nothing is sent anywhere else, and Shield itself is
 * excluded from the VPN so its forwarding sockets go straight out.
 */
class ShieldVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var out: FileOutputStream? = null
    private var pool: ExecutorService? = null
    private val tripwire by lazy { Tripwire(applicationContext) }
    private var upstreamCache: Pair<Long, List<InetAddress>>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGuard()
            stopSelf()
            return START_NOT_STICKY
        }
        startGuard()
        return START_STICKY
    }

    private fun startGuard() {
        alwaysOn = isAlwaysOn
        app.entity.shield.util.KeepAlive.setNetWanted(this, true)   // also covers guards turned on before 0.5.2
        if (running) return
        kotlin.concurrent.thread(name = "dns-lists") { DnsPolicy.warm(applicationContext) } // 4 MB list, ~20 ms, off the main thread
        val b = Builder()
            .setSession("ENTITY Shield")
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .setBlocking(true)
            .setMtu(1500)
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = runCatching { b.establish() }.getOrNull()
        if (fd == null) {
            Log.w(TAG, "VPN not established (consent missing?)")
            NetGuard.setVpn(false)
            return
        }
        tun = fd
        out = FileOutputStream(fd.fileDescriptor)
        // Bounded: a lookup storm (or a slow resolver holding every worker for 2.5 s) must not queue
        // queries without limit. When full, the oldest waiting query is dropped: its app has already
        // timed out and will retry.
        pool = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED), ThreadPoolExecutor.DiscardOldestPolicy())
        running = true
        thread(name = "dns-tun") { readLoop(fd) }
        NetGuard.setVpn(true)
        NetGuard.blocking = getSharedPreferences("shield", 0).getBoolean("dns_block", true)
        refreshNetworkInfo()
        tripwire.start()
        Log.i(TAG, "Network Guard on (dns=$DNS_ADDR upstream=${upstream().joinToString { it.hostAddress ?: "" }})")
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        var errors = 0
        while (running) {
            val n = try { input.read(buf) } catch (e: IOException) {
                if (!running) break
                // A broken TUN fd fails every read: back off instead of spinning a core at 100%.
                if (++errors > 50) { Log.w(TAG, "tun keeps failing (${e.message}), stopping"); break }
                SystemClock.sleep(minOf(1000L, 20L * errors)); continue
            }
            if (n <= 0) continue
            errors = 0
            val q = try { DnsPacket.parse(buf, n) } catch (t: Throwable) { null } ?: continue
            val t0 = SystemClock.elapsedRealtimeNanos()
            try { pool?.execute { handle(q, t0) } } catch (e: RejectedExecutionException) { if (!running) break }
        }
    }

    private fun handle(q: DnsPacket.Query, t0: Long) {
        try {
            val uid = owner(q)
            val d = NetGuard.judge(this, q.name, uid)
            val block = NetGuard.blocking && d.action == DnsPolicy.Action.BLOCK && (q.qtype == 1 || q.qtype == 28 || q.qtype == 65)
            val answer = if (block) DnsPacket.nxdomain(q) else forward(q.dns)
            if (answer != null) write(DnsPacket.reply(q, answer))
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            NetGuard.record(this, uid, q.name, q.qtype, d, block, answer?.let { DnsPacket.rcode(it) } ?: -1, ms)
        } catch (t: Throwable) {
            Log.w(TAG, "query ${q.name} failed: ${t.message}")
        }
    }

    private fun forward(dns: ByteArray): ByteArray? {
        for (server in upstream()) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 2500
                    s.send(DatagramPacket(dns, dns.size, server, 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    return rb.copyOf(rp.length)
                }
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                continue
            }
        }
        return null
    }

    /** The real network's resolvers (Shield is outside its own VPN, so activeNetwork is the real one). */
    private fun upstream(): List<InetAddress> {
        upstreamCache?.let { (at, list) -> if (SystemClock.elapsedRealtime() - at < 30_000 && list.isNotEmpty()) return list }
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val found = lp?.dnsServers.orEmpty().sortedBy { if (it is Inet4Address) 0 else 1 }
        val list = found.ifEmpty { listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
        upstreamCache = SystemClock.elapsedRealtime() to list
        return list
    }

    private fun refreshNetworkInfo() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val ip = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress ?: ""
        val dns = upstream().joinToString(", ") { it.hostAddress ?: "" }
        android.os.Handler(mainLooper).post { NetGuard.phoneIp = ip; NetGuard.upstream = dns }
    }

    private fun owner(q: DnsPacket.Query): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return runCatching {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort),
                InetSocketAddress(InetAddress.getByAddress(q.dstIp), q.dstPort),
            )
        }.getOrDefault(-1)
    }

    @Synchronized
    private fun write(p: ByteArray) {
        try { out?.write(p) } catch (e: IOException) { Log.w(TAG, "tun write: ${e.message}") }
    }

    private fun stopGuard() {
        if (!running && tun == null) return
        running = false
        tripwire.stop()
        runCatching { tun?.close() }
        tun = null
        out = null
        pool?.shutdownNow()
        pool = null
        NetGuard.setVpn(false)
        Log.i(TAG, "Network Guard off")
    }

    override fun onRevoke() {
        // the user turned it off in system settings, or another VPN app took over: don't fight it at boot
        app.entity.shield.util.KeepAlive.setNetWanted(this, false)
        stopGuard()
        stopSelf()
    }

    override fun onDestroy() {
        stopGuard()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShieldNet"
        private const val MAX_QUEUED = 256
        const val VPN_ADDR = "10.215.173.1"
        const val DNS_ADDR = "10.215.173.2"
        const val ACTION_START = "app.entity.shield.NET_START"
        const val ACTION_STOP = "app.entity.shield.NET_STOP"

        /** The system "Always-on VPN" switch is on for Shield (known once the guard has started). */
        @Volatile var alwaysOn = false

        /** null when consent is already given; otherwise the system consent Intent to launch. */
        fun consentIntent(ctx: Context): Intent? = prepare(ctx)

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
