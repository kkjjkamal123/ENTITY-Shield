package app.entity.shield

/**
 * Just enough IPv4 / UDP / DNS to run a DNS-only local VPN: parse a query that arrived on the TUN
 * interface, build the reply packet (with a correct IPv4 header checksum), or synthesize NXDOMAIN.
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM (DnsPacketTest).
 */
object DnsPacket {
    class Query(
        val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int,
        val dns: ByteArray, val name: String, val qtype: Int,
    )

    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }

    /** IPv4 + UDP to port 53 with one DNS question, else null. */
    fun parse(pkt: ByteArray, len: Int): Query? {
        if (len < 28 || (pkt[0].toInt() ushr 4) != 4) return null
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 8 || (pkt[9].toInt() and 0xFF) != 17) return null
        if ((u16(pkt, 6) and 0x3FFF) != 0) return null // fragments: not for us
        val udp = ihl
        val dport = u16(pkt, udp + 2)
        if (dport != 53) return null
        val payload = minOf(u16(pkt, udp + 4) - 8, len - udp - 8)
        if (payload < 12) return null
        val dns = pkt.copyOfRange(udp + 8, udp + 8 + payload)
        val (name, qtype) = question(dns) ?: return null
        return Query(pkt.copyOfRange(12, 16), pkt.copyOfRange(16, 20), u16(pkt, udp), dport, dns, name, qtype)
    }

    /** First question's name (lowercase, no trailing dot) and type. */
    fun question(dns: ByteArray): Pair<String, Int>? {
        if (dns.size < 12 || u16(dns, 4) < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= dns.size) return null
            val l = dns[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0 || i + 1 + l > dns.size || sb.length > 255) return null // no compression in a question
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((dns[i + k].toInt() and 0xFF).toChar().lowercaseChar())
            i += l + 1
        }
        if (i + 4 > dns.size) return null
        return sb.toString() to u16(dns, i)
    }

    fun rcode(dns: ByteArray) = if (dns.size >= 4) dns[3].toInt() and 0x0F else -1
    fun answers(dns: ByteArray) = if (dns.size >= 8) u16(dns, 6) else 0

    /** Reply to [q] saying the name does not exist (keeps id, question and RD; sets QR, RA, RCODE=3). */
    fun nxdomain(q: Query): ByteArray {
        val qEnd = questionEnd(q.dns)
        val r = q.dns.copyOfRange(0, qEnd)
        r[2] = (0x80 or (q.dns[2].toInt() and 0x79)).toByte() // QR=1, keep opcode + RD, clear AA/TC
        r[3] = (0x80 or 3).toByte()                            // RA=1, RCODE=NXDOMAIN
        put16(r, 4, 1); put16(r, 6, 0); put16(r, 8, 0); put16(r, 10, 0)
        return r
    }

    private fun questionEnd(dns: ByteArray): Int {
        var i = 12
        while (i < dns.size && dns[i].toInt() != 0) i += (dns[i].toInt() and 0xFF) + 1
        return minOf(dns.size, i + 5)
    }

    /** IPv4/UDP packet carrying [dns] back to the querying socket (swapped addresses and ports). */
    fun reply(q: Query, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000)          // don't fragment
        p[8] = 64                     // TTL
        p[9] = 17                     // UDP
        q.dstIp.copyInto(p, 12)       // from the fake DNS address
        q.srcIp.copyInto(p, 16)       // to the app
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, q.dstPort)
        put16(p, 22, q.srcPort)
        put16(p, 24, 8 + dns.size)
        put16(p, 26, 0)               // UDP checksum is optional over IPv4
        dns.copyInto(p, 28)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Shannon entropy in bits per character (random encoded labels score > 3.5, words < 3). */
    fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = s.groupingBy { it }.eachCount().values
        return -counts.sumOf { c -> val p = c.toDouble() / s.length; p * kotlin.math.ln(p) / kotlin.math.ln(2.0) }
    }

    /** A complete IPv4/UDP DNS query packet (tests and the self-check use it). */
    fun buildQuery(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, id: Int, name: String, qtype: Int = 1): ByteArray {
        val labels = name.trim('.').split('.')
        val qn = ByteArray(labels.sumOf { it.length + 1 } + 1)
        var i = 0
        for (l in labels) { qn[i++] = l.length.toByte(); for (c in l) qn[i++] = c.code.toByte() }
        val dns = ByteArray(12 + qn.size + 4)
        put16(dns, 0, id); dns[2] = 0x01; put16(dns, 4, 1)
        qn.copyInto(dns, 12); put16(dns, 12 + qn.size, qtype); put16(dns, 14 + qn.size, 1)
        val q = Query(dstIp, srcIp, 53, srcPort, dns, name, qtype)  // reply() swaps, so pass it reversed
        return reply(q, dns)
    }
}
