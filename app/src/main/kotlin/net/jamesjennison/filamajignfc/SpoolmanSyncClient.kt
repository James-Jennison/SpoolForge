package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.*
import java.net.URI
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SpoolmanHttpResponse(val code: Int, val body: ByteArray)
fun interface SpoolmanTransport { fun request(method: String, url: String, body: ByteArray?): SpoolmanHttpResponse }
data class SpoolmanSyncResult(val vendorId: Int, val filamentId: Int, val created: Boolean, val warnings: List<String>)
class SpoolmanSyncException(message: String, val outcomeUnknown: Boolean = false) : Exception(message)

class SpoolmanSyncClient(private val transport: SpoolmanTransport = SpoolmanTransport(::httpRequest)) {
    fun syncProfile(server: String, record: FilamentRecord, density: String?): SpoolmanSyncResult {
        val base = normalizeServer(server)
        val requests = SpoolmanCodec.syncRequests(record, density = density)
        val vendorExternal = "filamajig:${record.brand.value}"
        val existingVendor = findId("$base/vendor?external_id=${encoded(vendorExternal)}")
        val vendorId = existingVendor ?: createId("$base/vendor", requests.vendorJson, "vendor")
        val filamentExternal = "filamajig:${record.packageId}"
        val existing = findId("$base/filament?external_id=${encoded(filamentExternal)}", priorChange = existingVendor == null)
        val filamentBody = SpoolmanCodec.filamentRequest(record, vendorId, density)
        val filamentId = if (existing == null) createId("$base/filament", filamentBody, "filament") else {
            mutate("PATCH", "$base/filament/$existing", filamentBody, "filament update")
            existing
        }
        return SpoolmanSyncResult(vendorId, filamentId, existing == null, requests.warnings)
    }

    private fun findId(url: String, priorChange: Boolean = false): Int? {
        val stage = if (priorChange) "after the vendor step; retry is safe because external IDs are stable" else "before any change"
        val response = runCatching { transport.request("GET", url, null) }.getOrElse { throw SpoolmanSyncException("Spoolman lookup failed $stage: ${it.message}") }
        if (response.code !in 200..299) throw SpoolmanSyncException("Spoolman lookup returned HTTP ${response.code} $stage")
        val array = StrictJson(256_000, 16).parse(response.body) as? JsonValue.Arr ?: throw SpoolmanSyncException("Spoolman lookup response was not an array")
        if (array.values.size > 1) throw SpoolmanSyncException("Spoolman returned multiple records for a unique legacy SpoolForge external ID")
        return (array.values.singleOrNull() as? JsonValue.Obj)?.int("id")
            ?: if (array.values.isEmpty()) null else throw SpoolmanSyncException("Spoolman lookup result had no numeric id")
    }
    private fun createId(url: String, body: ByteArray, kind: String): Int {
        val response = mutate("POST", url, body, "$kind creation")
        return (StrictJson(256_000, 16).parse(response.body) as? JsonValue.Obj)?.int("id")
            ?: throw SpoolmanSyncException("Spoolman $kind response had no numeric id", outcomeUnknown = true)
    }
    private fun mutate(method: String, url: String, body: ByteArray, action: String): SpoolmanHttpResponse {
        val response = runCatching { transport.request(method, url, body) }.getOrElse {
            throw SpoolmanSyncException("Spoolman $action outcome is unknown because the connection ended: ${it.message}", outcomeUnknown = true)
        }
        if (response.code !in 200..299) throw SpoolmanSyncException("Spoolman rejected $action with HTTP ${response.code}")
        return response
    }
    private fun normalizeServer(input: String): String {
        val uri = runCatching { URI(input.trim()) }.getOrElse { throw SpoolmanSyncException("Spoolman server URL is invalid") }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) throw SpoolmanSyncException("Use an http(s) Spoolman server URL without credentials, query, or fragment")
        if (uri.scheme == "http" && !isLocalHost(uri.host)) throw SpoolmanSyncException("Use HTTPS for a Spoolman server outside the local network")
        val root = input.trim().trimEnd('/').removeSuffix("/api/v1")
        return "$root/api/v1"
    }
    private fun isLocalHost(host: String): Boolean {
        val value = host.trim('[', ']').lowercase()
        if (value == "localhost" || value.endsWith(".local")) return true
        val octets = value.split('.').map { it.toIntOrNull() }
        if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
            val first = octets[0]!!
            val second = octets[1]!!
            return first == 10 || first == 127 || first == 192 && second == 168 || first == 172 && second in 16..31 || first == 169 && second == 254
        }
        return value == "::1" || value.startsWith("fc") || value.startsWith("fd") || value.startsWith("fe8") || value.startsWith("fe9") || value.startsWith("fea") || value.startsWith("feb")
    }
    private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}

private val spoolmanHttpClient = OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
private fun httpRequest(method: String, url: String, body: ByteArray?): SpoolmanHttpResponse {
    val requestBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder().url(url).header("Accept", "application/json").method(method, requestBody).build()
    spoolmanHttpClient.newCall(request).execute().use { response ->
        val stream = response.body?.byteStream() ?: return SpoolmanHttpResponse(response.code, ByteArray(0))
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= 256_000) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        if (out.size() > 256_000) throw SpoolmanSyncException("Spoolman response exceeded 256 KB", outcomeUnknown = method != "GET")
        return SpoolmanHttpResponse(response.code, out.toByteArray())
    }
}
