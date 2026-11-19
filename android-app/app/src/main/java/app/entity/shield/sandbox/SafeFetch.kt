package app.entity.shield.sandbox

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One request of a redirect chain. status 0 = no response (the chain stopped here, see FetchResult.error). */
data class Hop(val url: String, val status: Int, val ip: String, val ms: Long, val location: String?)

/** Leaf certificate of the last HTTPS hop. Times are epoch ms; ageDays = days since notBefore. */
data class CertInfo(val subject: String, val issuer: String, val notBefore: Long, val notAfter: Long, val sans: Int, val ageDays: Int)

data class FetchResult(
    val hops: List<Hop>, val finalUrl: String, val status: Int, val contentType: String, val contentDisposition: String,
    val body: ByteArray, val truncated: Boolean, val cert: CertInfo?, val error: String?,
    val setCookies: Int, val totalMs: Long, val schemeRedirect: String?, val userinfoInUrl: Boolean,
)

/**
 * A deliberately small HTTP/1.1 GET client for visiting a suspicious link on the phone itself.
 * It never sends cookies, auth or a referer, refuses private/internal addresses (checked on every hop,
 * connecting to the exact IP that was checked so DNS rebinding cannot swap it), never downgrades TLS,
 * bounds every byte it reads (headers, body, decompressed body) and every wait (one overall deadline).
 * Failures are reported as codes in [FetchResult.error], never thrown:
 * bad_scheme, bad_url, dns, private_ip, connect, tls_invalid, timeout, too_many_redirects, io.
 * Pure JDK (no Android types) so it runs in JVM unit tests (SafeFetchTest).
 */
object SafeFetch {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun fetch(
        url: String, maxHops: Int = 8, maxBytes: Int = 768 * 1024, deadlineMs: Long = 10_000,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        allowPrivate: Boolean = false /* tests only */, userAgent: String = DEFAULT_UA,
    ): FetchResult = Run(maxBytes.coerceAtLeast(0), deadlineMs, resolver, allowPrivate,
        userAgent.filter { it in ' '..'~' }).go(url, maxHops)

    /** True for addresses a link must never make the phone talk to (LAN, loopback, carrier NAT, ...). */
    internal fun isBlocked(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address.map { it.toInt() and 0xFF }
        if (b.size == 4) return blockedV4(b[0], b[1])
        if ((b[0] and 0xFE) == 0xFC) return true                                         // fc00::/7 unique-local
        val zero10 = (0 until 10).all { b[it] == 0 }
        if (zero10 && b[10] == b[11] && (b[10] == 0xFF || b[10] == 0)) return blockedV4(b[12], b[13]) // ::ffff:v4, ::v4
        if (b[0] == 0 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B) return blockedV4(b[12], b[13]) // NAT64 64:ff9b::v4
        if (b[0] == 0x20 && b[1] == 0x02) return blockedV4(b[2], b[3])                     // 6to4 2002:v4::
        return false
    }

    private fun blockedV4(o0: Int, o1: Int) = o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224 ||
        (o0 == 169 && o1 == 254) || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) || (o0 == 100 && o1 in 64..127)

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val MAX_HEADER_BYTES = 32 * 1024

    private class Fail(val code: String) : Exception(code)

    /** A parsed http(s) URL: userinfo and fragment dropped, host lower-case ASCII (IDN converted), path percent-safe. */
    private class Target(val https: Boolean, val host: String, val port: Int, val pathQuery: String) {
        val scheme get() = if (https) "https" else "http"
        val origin get() = "$scheme://" + (if (':' in host) "[$host]" else host) + (if (port != (if (https) 443 else 80)) ":$port" else "")
        override fun toString() = origin + pathQuery
    }

    /** All mutable state of one fetch() call. */
    private class Run(
        val maxBytes: Int, deadlineMs: Long, val resolver: (String) -> List<InetAddress>,
        val allowPrivate: Boolean, val userAgent: String,
    ) {
        val t0 = System.nanoTime()
        val deadline = t0 + deadlineMs * 1_000_000L
        val hops = ArrayList<Hop>()
        val body = ByteArrayOutputStream()
        var status = 0; var contentType = ""; var disposition = ""; var error: String? = null
        var truncated = false; var cert: CertInfo? = null; var cookies = 0
        var userinfo = false; var schemeRedirect: String? = null

        fun remaining() = (deadline - System.nanoTime()) / 1_000_000L

        fun go(input: String, maxHops: Int): FetchResult {
            var finalUrl = input.trim()
            var target: Target? = try { parse(input, fromUser = true) } catch (e: Exception) { error = code(e); null }
            var redirects = 0
            while (target != null) {
                finalUrl = target.toString()
                val location = try { hop(target) } catch (e: Exception) { error = code(e); null } ?: break
                if (redirects++ >= maxHops) { error = "too_many_redirects"; break }
                val next = try { absolute(target, location) } catch (e: Exception) { error = "bad_url"; break }
                val scheme = SCHEME.find(next)!!.groupValues[1].lowercase()
                if (scheme != "http" && scheme != "https") { schemeRedirect = scheme; break }   // intent:, market:, upi:, tel:, ...
                target = try { parse(next, fromUser = false) } catch (e: Exception) { error = code(e); null }
            }
            return FetchResult(hops, finalUrl, status, contentType, disposition, body.toByteArray(), truncated, cert, error,
                cookies, (System.nanoTime() - t0) / 1_000_000L, schemeRedirect, userinfo)
        }

        fun code(e: Exception) = when (e) {
            is Fail -> e.code
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns"
            is IllegalArgumentException, is java.net.URISyntaxException -> "bad_url"
            else -> if (remaining() <= 0) "timeout" else "io"
        }

        /** One request. Returns the raw Location to follow, or null when this response is the final one. */
        fun hop(t: Target): String? {
            val started = System.nanoTime()
            var ip = ""; var code = 0; var location: String? = null; var sock: Socket? = null
            status = 0; contentType = ""; disposition = ""; cert = null   // describe the last hop only
            try {
                if (remaining() <= 0) throw Fail("timeout")
                val addrs = resolve(t.host)
                if (addrs.isEmpty()) throw Fail("dns")
                if (!allowPrivate) addrs.firstOrNull(::isBlocked)?.let { ip = it.hostAddress ?: ""; throw Fail("private_ip") }
                sock = connect(addrs, t.port)
                ip = sock.inetAddress.hostAddress ?: ""
                if (t.https) sock = handshake(sock, t.host, t.port)
                sock.getOutputStream().apply { write(request(t)); flush() }
                val input = BufferedInputStream(DeadlineInput(sock, ::remaining), 16 * 1024)
                val (st, headers) = readHead(input)
                fun h(name: String) = headers.lastOrNull { it.first == name }?.second
                code = st; status = st
                cookies += headers.count { it.first == "set-cookie" }
                contentType = h("content-type") ?: ""; disposition = h("content-disposition") ?: ""
                location = h("location")?.trim()?.takeIf { it.isNotEmpty() }
                if (st in REDIRECTS && location != null) return location
                truncated = readBody(input, ::h, st)
                return null
            } finally {
                hops += Hop(t.toString(), code, ip, (System.nanoTime() - started) / 1_000_000L, location)
                try { sock?.close() } catch (e: IOException) { }
            }
        }

        /** Runs the resolver on a helper thread so a hung DNS lookup still honours the deadline. */
        fun resolve(host: String): List<InetAddress> {
            val task = FutureTask(Callable { resolver(host) })
            Thread(task, "SafeFetch-dns").apply { isDaemon = true }.start()
            return try { task.get(remaining().coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { task.cancel(true); throw Fail("timeout") }
            catch (e: ExecutionException) { throw Fail("dns") }
        }

        /** Connects to the already-vetted IPs in order (never by name), sharing the remaining deadline between them. */
        fun connect(addrs: List<InetAddress>, port: Int): Socket {
            val tries = addrs.take(4)
            for ((i, a) in tries.withIndex()) {
                val left = remaining()
                if (left <= 0) throw Fail("timeout")
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(a, port), (left / (tries.size - i)).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                    return s
                } catch (e: IOException) { s.close() }
            }
            throw Fail(if (remaining() <= 0) "timeout" else "connect")
        }

        /** TLS with SNI and full certificate + hostname validation. Any failure is final: no insecure retry. */
        fun handshake(raw: Socket, host: String, port: Int): Socket {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            try {
                ssl.sslParameters = ssl.sslParameters.apply {
                    if (':' !in host && !host.all { it.isDigit() || it == '.' }) {
                        try { serverNames = listOf(SNIHostName(host)) } catch (e: IllegalArgumentException) { }
                    }
                    endpointIdentificationAlgorithm = "HTTPS"   // JDK and Conscrypt also check the name during the handshake
                }
                ssl.soTimeout = remaining().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                ssl.startHandshake()
                (ssl.session.peerCertificates.firstOrNull() as? X509Certificate)?.let { cert = certInfo(it) }
                val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                // The desktop JDK's default verifier is a stub that rejects everything (its real check is the
                // endpoint identification above); Android's default verifier checks the certificate's SANs.
                val jdkStub = verifier.javaClass.name.endsWith("HttpsURLConnection\$DefaultHostnameVerifier")
                if (!verifier.verify(host, ssl.session) && !jdkStub) throw Fail("tls_invalid")
                return ssl
            } catch (e: Exception) {
                try { ssl.close() } catch (c: IOException) { }
                throw if (e is SocketTimeoutException || e is Fail) e else Fail("tls_invalid")
            }
        }

        fun request(t: Target): ByteArray = ("GET ${t.pathQuery} HTTP/1.1\r\n" +
            "Host: ${t.origin.substringAfter("://")}\r\n" +
            "User-Agent: $userAgent\r\n" +
            "Accept: text/html,application/xhtml+xml,*/*;q=0.8\r\n" +
            "Accept-Language: en-IN,en;q=0.9,hi;q=0.8\r\n" +
            "Accept-Encoding: gzip, identity\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)

        /** Body via chunked / Content-Length / read-to-close, then gzip/deflate, all capped. Returns `truncated`. */
        fun readBody(input: InputStream, h: (String) -> String?, code: Int): Boolean {
            if (code in 100..199 || code == 204 || code == 304) return false
            val raw = Limited(input, maxBytes + 65_536L)   // wire bytes (incl. chunk framing): bounds a compressed body too
            val length = h("content-length")?.trim()?.toLongOrNull()
            val framed = when {
                h("transfer-encoding")?.lowercase()?.contains("chunked") == true -> Chunked(raw)
                length != null && length >= 0 -> Limited(raw, length)
                else -> raw
            }
            try {
                decode(framed, h("content-encoding")).use { dec ->
                    val buf = ByteArray(8192)
                    while (body.size() < maxBytes) {
                        val n = dec.read(buf, 0, minOf(buf.size, maxBytes - body.size()))
                        if (n < 0) return raw.hit
                        body.write(buf, 0, n)
                    }
                    return dec.read() >= 0 || raw.hit   // one more byte means the cap cut the body short
                }
            } catch (e: IOException) {
                if (e is SocketTimeoutException || !raw.hit) throw e
                return true   // wire cap reached mid-stream (e.g. inside gzip data): keep what was decoded
            }
        }
    }

    /** Parses user input or an absolute redirect target. Scheme-less input becomes https://. */
    private fun Run.parse(raw: String, fromUser: Boolean): Target {
        var s = raw.trim().replace('\\', '/')            // browsers treat '\' as '/' in http(s) URLs
        var scheme = SCHEME.find(s)?.groupValues?.get(1)
        if (fromUser && scheme != null && s.getOrNull(scheme.length + 1)?.isDigit() == true &&
            ('.' in scheme || scheme.equals("localhost", ignoreCase = true))) scheme = null   // "example.com:8080/x", not "tel:123"
        if (scheme == null) { s = "https://" + s.trimStart('/'); scheme = "https" }
        val https = when (scheme.lowercase()) { "https" -> true; "http" -> false; else -> throw Fail("bad_scheme") }
        val rest = s.substring(scheme.length + 1).trimStart('/')
        val cut = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        var auth = rest.substring(0, cut)
        val at = auth.lastIndexOf('@')
        if (at >= 0) { userinfo = true; auth = auth.substring(at + 1) }
        val bracket = auth.startsWith("[")
        val hostEnd = if (bracket) auth.indexOf(']') + 1 else auth.lastIndexOf(':').let { if (it < 0) auth.length else it }
        if (bracket && hostEnd == 0) throw Fail("bad_url")
        val rawHost = auth.substring(0, hostEnd).removePrefix("[").removeSuffix("]")
        val host = (if (bracket) rawHost else IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED)).lowercase().trimEnd('.')
        if (host.isEmpty() || (!bracket && ':' in host) || host.any { !(it in 'a'..'z' || it in '0'..'9' || it in ".-_:") }) throw Fail("bad_url")
        val portText = auth.substring(hostEnd).removePrefix(":")
        val port = if (portText.isEmpty()) (if (https) 443 else 80) else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw Fail("bad_url")
        val path = rest.substring(cut).substringBefore('#')
        return Target(https, host, port, encode(if (path.startsWith("/")) path else "/$path"))
    }

    /** Resolves a Location header against the current URL (RFC 3986 cases Java's URI gets wrong are done by hand). */
    private fun absolute(base: Target, location: String): String {
        val loc = location.replace('\\', '/')
        return when {
            SCHEME.containsMatchIn(loc) -> loc
            loc.startsWith("//") -> "${base.scheme}:$loc"
            loc.startsWith("/") -> base.origin + loc
            loc.startsWith("?") -> base.origin + base.pathQuery.substringBefore('?') + loc
            loc.startsWith("#") -> base.toString()
            else -> URI("http://x" + base.pathQuery).resolve(URI(encode(loc))).let { u ->
                base.origin + u.rawPath + (u.rawQuery?.let { "?$it" } ?: "")
            }
        }
    }

    /** Percent-encodes what java.net.URI rejects (spaces, non-ASCII, quotes, stray '%'), keeping valid escapes. */
    private fun encode(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val hex = "0123456789ABCDEF"
        fun isHex(i: Int) = i < b.size && b[i].toInt().toChar().let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val sb = StringBuilder(b.size + 16)
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            val badPct = c == '%'.code && !(isHex(i + 1) && isHex(i + 2))
            if (c <= 0x20 || c >= 0x7F || badPct || c.toChar() in "\"<>^`{|}[]") sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
            else sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Status line + headers (names lower-cased), at most 32 KB in total; 1xx interim responses are skipped. */
    private fun readHead(input: InputStream): Pair<Int, List<Pair<String, String>>> {
        var budget = MAX_HEADER_BYTES
        while (true) {
            val statusLine = readLine(input, budget) ?: throw IOException("no response")
            budget -= statusLine.length + 2
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            if (!statusLine.startsWith("HTTP/") || code == null) throw IOException("not HTTP")
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val line = readLine(input, budget) ?: break
                budget -= line.length + 2
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers += line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }
            if (code in 100..199 && code != 101) continue
            return code to headers
        }
    }

    /** One CRLF/LF-terminated ISO-8859-1 line of at most [max] chars; null at EOF before any byte. */
    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= max) throw IOException("line too long")
            sb.append(b.toChar())
        }
    }

    /** gzip / deflate (zlib or raw) by sniffing the first bytes; anything else passes through untouched. */
    private fun decode(src: InputStream, encoding: String?): InputStream {
        val enc = encoding?.substringAfterLast(',')?.trim()?.lowercase() ?: return src
        if (enc != "gzip" && enc != "x-gzip" && enc != "deflate") return src
        val pb = PushbackInputStream(src, 2)
        val head = ByteArray(2)
        var n = 0
        while (n < 2) { val r = pb.read(head, n, 2 - n); if (r < 0) break; n += r }
        pb.unread(head, 0, n)
        if (n < 2) return pb
        val b0 = head[0].toInt() and 0xFF; val b1 = head[1].toInt() and 0xFF
        if (b0 == 0x1F && b1 == 0x8B) return GZIPInputStream(pb, 8192)
        if (enc != "deflate") return pb   // claimed gzip but is not: keep the bytes as they are
        val inflater = Inflater((b0 and 0x0F) != 8 || ((b0 shl 8) or b1) % 31 != 0)   // no zlib header: raw deflate
        return object : InflaterInputStream(pb, inflater, 8192) {
            override fun close() { try { super.close() } finally { inflater.end() } }
        }
    }

    private fun certInfo(c: X509Certificate): CertInfo {
        val sub = c.subjectX500Principal.name; val iss = c.issuerX500Principal.name
        val sans = try { c.subjectAlternativeNames?.size ?: 0 } catch (e: Exception) { 0 }
        return CertInfo(dnAttr(sub, "CN") ?: sub, dnAttr(iss, "O") ?: dnAttr(iss, "CN") ?: iss, c.notBefore.time, c.notAfter.time,
            sans, ((System.currentTimeMillis() - c.notBefore.time) / 86_400_000L).toInt())
    }

    /** Value of one attribute of an RFC 2253 DN ("CN=a\, b,O=c"), honouring backslash escapes. */
    private fun dnAttr(dn: String, key: String): String? {
        val parts = ArrayList<String>(); val sb = StringBuilder(); var i = 0
        while (i < dn.length) {
            val c = dn[i]
            when {
                c == '\\' && i + 1 < dn.length -> { sb.append(dn[i + 1]); i++ }
                c == ',' || c == '+' -> { parts += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        parts += sb.toString()
        return parts.firstOrNull { it.substringBefore('=').trim().equals(key, ignoreCase = true) }?.substringAfter('=')?.trim()
    }

    /** Re-arms the socket read timeout to whatever is left of the overall deadline before every read. */
    private class DeadlineInput(private val socket: Socket, private val remaining: () -> Long) : FilterInputStream(socket.getInputStream()) {
        private fun arm() {
            val left = remaining()
            if (left <= 0) throw SocketTimeoutException("deadline")
            socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        override fun read(): Int { arm(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return super.read(b, off, len) }
    }

    /** Ends after [left] bytes; [hit] records that a read was attempted past the limit. */
    private class Limited(src: InputStream, private var left: Long) : FilterInputStream(src) {
        var hit = false
        override fun read(): Int {
            if (left <= 0) { hit = true; return -1 }
            return super.read().also { if (it >= 0) left-- }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) { hit = true; return -1 }
            return super.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }
        override fun available() = minOf(super.available().toLong(), left).toInt()
        override fun skip(n: Long) = 0L
    }

    /** Transfer-Encoding: chunked. Size lines are capped; trailers are read and dropped. */
    private class Chunked(private val src: InputStream) : InputStream() {
        private var left = 0L
        private var done = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (len == 0) return 0
            if (left == 0L) {
                val line = readLine(src, 1024) ?: throw IOException("eof before chunk")
                left = line.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("bad chunk size")
                if (left == 0L) {
                    repeat(64) { if (readLine(src, 8192).isNullOrEmpty()) { done = true; return -1 } }
                    throw IOException("too many trailers")
                }
            }
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("eof inside chunk")
            left -= n
            if (left == 0L) readLine(src, 16)   // CRLF after the chunk data
            return n
        }
    }
}
