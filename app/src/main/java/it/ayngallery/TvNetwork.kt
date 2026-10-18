package it.ayngallery

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okio.buffer

internal class TvNetworkFailure(val reason: String) : IOException("TV network request failed")

/** The validated DNS results are the very addresses OkHttp connects to, not a separate preflight. */
internal object TvNetwork {
    fun validUrl(value: String): Boolean = runCatching {
        require(value.length <= 8192 && value.none { it.isWhitespace() || it.isISOControl() })
        val url = value.toHttpUrl()
        require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        require(!url.host.endsWith(".local", true) && !url.host.endsWith(".localhost", true) && !url.host.endsWith(".internal", true) && url.host != "localhost")
        // No encoded controls, including nested encodings commonly used to conceal CR/LF.
        require(!Regex("(?i)%(?:25)*(?:00|0a|0d)").containsMatchIn(value))
        true
    }.getOrDefault(false)

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] in setOf(0, 10, 127) || b[0] >= 224 ||
            b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 ||
            b[0] == 172 && b[1] in 16..31 || b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 2) ||
            b[0] == 198 && b[1] in 18..19 || b[0] == 198 && b[1] == 51 && b[2] == 100 ||
            b[0] == 203 && b[1] == 0 && b[2] == 113)
        // Restrict IPv6 to globally routable unicast; reject transition/tunnel and documentation ranges.
        return b.size == 16 && b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x02) &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8))
    }

    internal fun guardedDns(delegate: Dns): Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = delegate.lookup(hostname)
                if (resolved.isEmpty() || resolved.any { !publicAddress(it) }) throw TvNetworkFailure("BLOCKED")
                return resolved
            }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().dns(guardedDns(Dns.SYSTEM)).followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addNetworkInterceptor response
                val manifest = body.contentType()?.toString()?.contains("mpegurl", true) == true || chain.request().url.encodedPath.endsWith(".m3u8", true)
                val limit = if (manifest) TvPlaylist.MAX_BYTES.toLong() else 16L * 1024 * 1024
                if (body.contentLength() > limit) { response.close(); throw TvNetworkFailure("LARGE") }
                val bounded = object : okio.ForwardingSource(body.source()) {
                    var total = 0L
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val count = super.read(sink, minOf(byteCount, limit - total + 1))
                        if (count > 0) total += count
                        if (total > limit) throw TvNetworkFailure("LARGE")
                        return count
                    }
                }.buffer()
                response.newBuilder().body(object : okhttp3.ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = bounded
                }).build()
            }
            .addInterceptor { chain ->
                var request = chain.request()
                var redirects = 0
                while (true) {
                    if (!validUrl(request.url.toString())) throw TvNetworkFailure("BLOCKED")
                    // Literal IP URLs can bypass DNS in some transports. Apply the same policy here.
                    val host = request.url.host
                    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                        if (!publicAddress(InetAddress.getByName(host))) throw TvNetworkFailure("BLOCKED")
                    }
                    val response = chain.proceed(request)
                    if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                    val next = response.header("Location")?.let(request.url::resolve)
                    response.close()
                    if (++redirects > 3 || next == null || !validUrl(next.toString())) throw TvNetworkFailure("BLOCKED")
                    request = request.newBuilder().url(next).removeHeader("Authorization").removeHeader("Cookie").build()
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }.build()
    }

    suspend fun bytes(url: String, limit: Int): ByteArray {
        if (!validUrl(url)) throw TvNetworkFailure("BLOCKED")
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val result = runCatching { response.use {
            if (!response.isSuccessful) throw TvNetworkFailure(if (response.code == 403) "FORBIDDEN" else if (response.code == 404) "NOT_FOUND" else "NETWORK")
            val body = response.body ?: throw TvNetworkFailure("NETWORK")
            if (body.contentLength() > limit) throw TvNetworkFailure("LARGE")
            body.byteStream().use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (result.size() + n > limit) throw TvNetworkFailure("LARGE")
                    result.write(buffer, 0, n)
                }
                result.toByteArray()
            }
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
        }
    }
}
