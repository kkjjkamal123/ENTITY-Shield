package app.entity.shield.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/** Plain-HTTP tests against a local server; "fake.test" resolves to 127.0.0.1 and allowPrivate lets us reach it. */
class SafeFetchTest {
    /** Tiny HTTP server on 127.0.0.1. [raw] = hand the socket over without reading the request first. */
    private class Server(private val raw: Boolean = false, private val handler: (path: String, s: Socket) -> Unit) : Closeable {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val connections = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val s = try { socket.accept() } catch (e: IOException) { break }
                    connections.incrementAndGet()
                    thread(isDaemon = true) {
                        try {
                            s.use {
                                val path = if (raw) "" else readRequest(it.getInputStream()).also { h -> requests += h }
                                    .lineSequence().first().split(' ')[1]
                                handler(path, it)
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        }

        override fun close() = socket.close()

        private fun readRequest(input: InputStream): String {
            val b = ByteArrayOutputStream()
            while (!b.toString("ISO-8859-1").endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; b.write(c) }
            return b.toString("ISO-8859-1")
        }
    }

    private fun respond(s: Socket, status: String, headers: List<String> = emptyList(), body: ByteArray = ByteArray(0)) {
        val head = "HTTP/1.1 $status\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n"
        s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
    }

    private val local: (String) -> List<InetAddress> = { h ->
        if (h == "fake.test") listOf(InetAddress.getByName("127.0.0.1")) else throw UnknownHostException(h)
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun followsRedirectChainAndRecordsHops() {
        Server { path, s ->
            when (path) {
                "/start" -> respond(s, "302 Found", listOf("Location: a/b", "Set-Cookie: sid=1", "Set-Cookie: t=2"))
                "/a/b" -> respond(s, "301 Moved Permanently", listOf("Location: ../final?x=1", "Content-Length: 0"))
                "/final?x=1" -> respond(s, "200 OK", listOf("Content-Type: text/html; charset=utf-8", "Content-Length: 11"),
                    "<h1>ok</h1>".toByteArray())
                else -> respond(s, "404 Not Found")
            }
        }.use { srv ->
            val r = SafeFetch.fetch("http://fake.test:${srv.port}/start", resolver = local, allowPrivate = true)
            assertNull(r.error)
            assertEquals(listOf(302, 301, 200), r.hops.map { it.status })
            assertEquals(listOf("a/b", "../final?x=1", null), r.hops.map { it.location })
            assertEquals("http://fake.test:${srv.port}/a/b", r.hops[1].url)
            assertEquals("http://fake.test:${srv.port}/final?x=1", r.finalUrl)
            assertTrue(r.hops.all { it.ip == "127.0.0.1" })
            assertEquals(200, r.status)
            assertEquals("text/html; charset=utf-8", r.contentType)
            assertEquals("<h1>ok</h1>", String(r.body))
            assertFalse(r.truncated)
            assertEquals(2, r.setCookies)
            assertNull(r.cert)
            assertEquals(3, srv.requests.size)
            for (h in srv.requests) {
                assertTrue(h, h.contains("Host: fake.test:${srv.port}\r\n"))
                assertTrue(h.contains("User-Agent: ${SafeFetch.DEFAULT_UA}\r\n"))
                assertTrue(h.contains("Accept-Encoding: gzip, identity\r\n") && h.contains("Connection: close\r\n"))
                assertTrue(h.contains("Accept-Language: en-IN,en;q=0.9,hi;q=0.8\r\n"))
                for (banned in listOf("cookie:", "referer:", "authorization:")) assertFalse(h, h.lowercase().contains(banned))
            }
        }
    }

    @Test fun decodesChunkedBody() {
        Server { _, s ->
            val body = "7\r\nHello, \r\n8;ext=1\r\nchunked \r\n5\r\nworld\r\n0\r\nX-Trailer: 1\r\n\r\n"
            respond(s, "200 OK", listOf("Content-Type: text/plain", "Transfer-Encoding: chunked"), body.toByteArray())
        }.use { srv ->
            val r = SafeFetch.fetch("http://fake.test:${srv.port}/", resolver = local, allowPrivate = true)
            assertNull(r.error)
            assertEquals("Hello, chunked world", String(r.body))
            assertFalse(r.truncated)
        }
    }

    @Test fun decodesGzipAndDeflate() {
        val page = ("<html><body>" + "<p>Some repeated content for compression.</p>".repeat(1000) + "</body></html>").toByteArray()
        val deflated = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(page) } }.toByteArray()
        Server { path, s ->
            when (path) {
                "/gz" -> gzip(page).let { respond(s, "200 OK", listOf("Content-Encoding: gzip", "Content-Length: ${it.size}"), it) }
                "/df" -> respond(s, "200 OK", listOf("Content-Encoding: deflate"), deflated)   // read to close
                "/gzchunked" -> gzip(page).let { gz ->
                    val half = gz.size / 2
                    val chunked = ByteArrayOutputStream().apply {
                        write("${Integer.toHexString(half)}\r\n".toByteArray()); write(gz, 0, half); write("\r\n".toByteArray())
                        write("${Integer.toHexString(gz.size - half)}\r\n".toByteArray()); write(gz, half, gz.size - half)
                        write("\r\n0\r\n\r\n".toByteArray())
                    }.toByteArray()
                    respond(s, "200 OK", listOf("Transfer-Encoding: chunked", "Content-Encoding: gzip"), chunked)
                }
            }
        }.use { srv ->
            for (p in listOf("/gz", "/df", "/gzchunked")) {
                val r = SafeFetch.fetch("http://fake.test:${srv.port}$p", resolver = local, allowPrivate = true)
                assertNull(p, r.error)
                assertTrue(p, page.contentEquals(r.body))
                assertFalse(r.truncated)
            }
        }
    }

    @Test fun capsBodyAndSurvivesZipBomb() {
        val big = ByteArray(100_000) { 'a'.code.toByte() }
        val bomb = gzip(ByteArray(32 * 1024 * 1024))   // 32 MB of zeros, ~32 KB on the wire
        Server { path, s ->
            when (path) {
                "/big" -> respond(s, "200 OK", listOf("Content-Length: ${big.size}"), big)
                "/exact" -> respond(s, "200 OK", listOf("Content-Length: 10000"), big.copyOf(10_000))
                "/bomb" -> respond(s, "200 OK", listOf("Content-Encoding: gzip", "Content-Length: ${bomb.size}"), bomb)
            }
        }.use { srv ->
            val r = SafeFetch.fetch("http://fake.test:${srv.port}/big", maxBytes = 10_000, resolver = local, allowPrivate = true)
            assertNull(r.error)
            assertEquals(10_000, r.body.size)
            assertTrue(r.truncated)
            val exact = SafeFetch.fetch("http://fake.test:${srv.port}/exact", maxBytes = 10_000, resolver = local, allowPrivate = true)
            assertEquals(10_000, exact.body.size)
            assertFalse(exact.truncated)
            val t0 = System.nanoTime()
            val b = SafeFetch.fetch("http://fake.test:${srv.port}/bomb", maxBytes = 65_536, resolver = local, allowPrivate = true)
            assertNull(b.error)
            assertEquals(65_536, b.body.size)
            assertTrue(b.truncated)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
        }
    }

    @Test fun stopsAtAppSchemeRedirect() {
        Server { path, s ->
            when (path) {
                "/go" -> respond(s, "302 Found", listOf("Location: intent://scan/#Intent;scheme=zxing;package=com.evil.app;end"))
                "/m" -> respond(s, "307 Temporary Redirect", listOf("Location: market://details?id=com.evil.app"))
            }
        }.use { srv ->
            val r = SafeFetch.fetch("http://fake.test:${srv.port}/go", resolver = local, allowPrivate = true)
            assertEquals("intent", r.schemeRedirect)
            assertNull(r.error)
            assertEquals(1, r.hops.size)
            assertTrue(r.hops[0].location!!.startsWith("intent://"))
            assertEquals("http://fake.test:${srv.port}/go", r.finalUrl)
            assertEquals("market", SafeFetch.fetch("http://fake.test:${srv.port}/m", resolver = local, allowPrivate = true).schemeRedirect)
            assertEquals(2, srv.connections.get())
        }
    }

    @Test fun stallingAndTricklingServersHitTheDeadline() {
        Server { path, s ->
            when (path) {
                "/stall" -> Thread.sleep(20_000)
                "/trickle" -> {
                    respond(s, "200 OK", listOf("Content-Length: 1000000"))
                    repeat(200) { s.getOutputStream().apply { write('x'.code); flush() }; Thread.sleep(100) }
                }
            }
        }.use { srv ->
            for (p in listOf("/stall", "/trickle")) {
                val t0 = System.nanoTime()
                val r = SafeFetch.fetch("http://fake.test:${srv.port}$p", deadlineMs = 1500, resolver = local, allowPrivate = true)
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertEquals(p, "timeout", r.error)
                assertTrue("$p took $ms ms", ms < 2500)
                if (p == "/trickle") assertTrue("partial body kept", r.body.isNotEmpty() && r.status == 200)
                else assertEquals(0, r.hops.single().status)
            }
        }
    }

    @Test fun refusesPrivateAddressesWithoutConnecting() {
        Server { _, s -> respond(s, "200 OK", listOf("Content-Length: 2"), "hi".toByteArray()) }.use { srv ->
            val blocked = listOf("127.0.0.1", "192.168.1.1", "10.0.0.1", "100.64.1.1", "172.16.5.4", "169.254.169.254", "0.0.0.0",
                "0.1.2.3", "224.0.0.1", "255.255.255.255", "::1", "::", "fd00::1", "fe80::1", "::ffff:10.0.0.1")
            for (ip in blocked) {
                val r = SafeFetch.fetch("http://fake.test:${srv.port}/", resolver = { listOf(InetAddress.getByName(ip)) })
                assertEquals(ip, "private_ip", r.error)
                assertEquals(0, r.hops.single().status)
            }
            // one bad address among good ones is enough
            val mixed = SafeFetch.fetch("http://fake.test:${srv.port}/",
                resolver = { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1")) })
            assertEquals("private_ip", mixed.error)
            assertEquals("192.168.1.1", mixed.hops.single().ip)
            assertEquals(0, srv.connections.get())
        }
    }

    @Test fun classifiesAddresses() {
        fun v6(vararg b: Int) = Inet6Address.getByAddress(null, ByteArray(16) { b.getOrElse(it) { 0 }.toByte() }, 0)
        assertTrue(SafeFetch.isBlocked(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 192, 168, 0, 1)))   // ::ffff:192.168.0.1 kept as v6
        assertTrue(SafeFetch.isBlocked(v6(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)))   // NAT64 of 10.0.0.1
        assertFalse(SafeFetch.isBlocked(v6(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)))
        assertTrue(SafeFetch.isBlocked(v6(0xFC, 0)))
        for (ok in listOf("8.8.8.8", "100.128.0.1", "100.63.255.255", "172.32.0.1", "192.169.0.1", "2001:4860:4860::8888"))
            assertFalse(ok, SafeFetch.isBlocked(InetAddress.getByName(ok)))
    }

    @Test fun rejectsOtherSchemesAndBadInput() {
        for (u in listOf("javascript:alert(1)", "ftp://x.example/a", "intent://scan#Intent;end", "data:text/html,hi", "tel:123"))
            SafeFetch.fetch(u, resolver = local).let { assertEquals(u, "bad_scheme", it.error); assertTrue(it.hops.isEmpty()) }
        assertEquals("bad_url", SafeFetch.fetch("http://fake.test:99999/", resolver = local).error)
        assertEquals("bad_url", SafeFetch.fetch("http://", resolver = local).error)
        val dns = SafeFetch.fetch("http://nowhere.test/", resolver = local)
        assertEquals("dns", dns.error)
        assertEquals("http://nowhere.test/", dns.finalUrl)
    }

    @Test fun schemelessInputIsHttpsAndTlsFailureIsFinal() {
        Server(raw = true) { _, s -> respond(s, "200 OK", listOf("Content-Length: 2"), "hi".toByteArray()) }.use { srv ->
            val r = SafeFetch.fetch("fake.test:${srv.port}/x", deadlineMs = 5000, resolver = local, allowPrivate = true)
            assertEquals("tls_invalid", r.error)
            assertEquals("https://fake.test:${srv.port}/x", r.finalUrl)
            assertEquals(1, r.hops.size)
            assertTrue(r.body.isEmpty())
            Thread.sleep(100)
            assertEquals(1, srv.connections.get())   // no plain-HTTP retry
        }
    }

    @Test fun stripsUserinfo() {
        Server { _, s -> respond(s, "200 OK", listOf("Content-Length: 2"), "ok".toByteArray()) }.use { srv ->
            val r = SafeFetch.fetch("http://admin:secret@fake.test:${srv.port}/ok#frag", resolver = local, allowPrivate = true)
            assertNull(r.error)
            assertTrue(r.userinfoInUrl)
            assertEquals("http://fake.test:${srv.port}/ok", r.finalUrl)
            val h = srv.requests.single()
            assertTrue(h.startsWith("GET /ok HTTP/1.1\r\n"))
            assertFalse(h.contains("secret") || h.contains("admin") || h.lowercase().contains("authorization"))
        }
    }

    @Test fun limitsRedirectsAndReportsConnectFailure() {
        Server { _, s -> respond(s, "302 Found", listOf("Location: /loop")) }.use { srv ->
            val r = SafeFetch.fetch("http://fake.test:${srv.port}/loop", maxHops = 3, resolver = local, allowPrivate = true)
            assertEquals("too_many_redirects", r.error)
            assertEquals(4, r.hops.size)
            assertEquals(302, r.status)
        }
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val c = SafeFetch.fetch("http://fake.test:$closedPort/", deadlineMs = 5000, resolver = local, allowPrivate = true)
        assertEquals("connect", c.error)
    }
}
