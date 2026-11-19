package app.entity.shield.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {
    private val app = byteArrayOf(10, 215.toByte(), 173.toByte(), 1)
    private val dns = byteArrayOf(10, 215.toByte(), 173.toByte(), 2)

    @Test fun parsesAQuery() {
        val pkt = DnsPacket.buildQuery(app, dns, 40000, 0x1234, "Sbi-KYC-Update.in", 1)
        val q = DnsPacket.parse(pkt, pkt.size)
        assertNotNull(q)
        assertEquals("sbi-kyc-update.in", q!!.name)
        assertEquals(1, q.qtype)
        assertEquals(40000, q.srcPort)
        assertEquals(53, q.dstPort)
        assertEquals(0x1234, DnsPacket.u16(q.dns, 0))
    }

    @Test fun ipv4HeaderChecksumIsValid() {
        val pkt = DnsPacket.buildQuery(app, dns, 40000, 7, "example.com")
        // a correct header sums (with its checksum) to 0xFFFF, i.e. checksum() of it is 0
        assertEquals(0, DnsPacket.checksum(pkt, 0, 20))
    }

    @Test fun nxdomainKeepsIdAndQuestion() {
        val pkt = DnsPacket.buildQuery(app, dns, 40001, 0xBEEF, "bad.example.xyz", 28)
        val q = DnsPacket.parse(pkt, pkt.size)!!
        val r = DnsPacket.nxdomain(q)
        assertEquals(0xBEEF, DnsPacket.u16(r, 0))
        assertEquals(3, DnsPacket.rcode(r))
        assertTrue((r[2].toInt() and 0x80) != 0)                // QR set
        assertEquals(0, DnsPacket.answers(r))
        assertEquals("bad.example.xyz" to 28, DnsPacket.question(r))
    }

    @Test fun replySwapsAddressesAndPorts() {
        val pkt = DnsPacket.buildQuery(app, dns, 40002, 1, "a.b")
        val q = DnsPacket.parse(pkt, pkt.size)!!
        val out = DnsPacket.reply(q, DnsPacket.nxdomain(q))
        assertEquals(0, DnsPacket.checksum(out, 0, 20))
        assertTrue(out.copyOfRange(12, 16).contentEquals(dns))  // from the fake resolver
        assertTrue(out.copyOfRange(16, 20).contentEquals(app))  // back to the app
        assertEquals(53, DnsPacket.u16(out, 20))
        assertEquals(40002, DnsPacket.u16(out, 22))
    }

    @Test fun ignoresNonDns() {
        val pkt = DnsPacket.buildQuery(app, dns, 40003, 1, "a.b")
        pkt[23] = 80 // destination port 53 -> 80 (high byte stays 0)
        assertNull(DnsPacket.parse(pkt, pkt.size))
        assertNull(DnsPacket.parse(ByteArray(10), 10))
    }

    @Test fun entropySeparatesRandomFromWords() {
        assertTrue(DnsPacket.entropy("x7k2q9vz8m3n1p4r6t5w0y") > 3.5)
        assertTrue(DnsPacket.entropy("mail") < 2.5)
    }
}
