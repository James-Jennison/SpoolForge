package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.*
import kotlin.test.*

class SpoolmanSyncClientTest {
    private val record = FilamentRecord("local 1",null,null,FieldValue("SUNLU","test"),FieldValue("PLA","test"),FieldValue("Basic","test"),FieldValue("Orange","test"),FieldValue("FF8E24","test"),FieldValue("1.75","test"),FieldValue(1000,"test"),FieldValue(200,"test"),FieldValue(220,"test"),FieldValue(55,"test"),FieldValue(65,"test"),null,"PLA-ORANGE","test",Provenance.CUSTOM)

    @Test fun createsMissingVendorAndFilamentUsingStableExternalIds() {
        val calls = mutableListOf<Triple<String,String,String?>>()
        val replies = ArrayDeque(listOf(
            SpoolmanHttpResponse(200, "[]".encodeToByteArray()),
            SpoolmanHttpResponse(200, "{\"id\":3}".encodeToByteArray()),
            SpoolmanHttpResponse(200, "[]".encodeToByteArray()),
            SpoolmanHttpResponse(200, "{\"id\":7}".encodeToByteArray()),
        ))
        val result = SpoolmanSyncClient { method, url, body -> calls += Triple(method,url,body?.decodeToString()); replies.removeFirst() }.syncProfile("http://spoolman.local:7912/", record, null)
        assertEquals(3, result.vendorId); assertEquals(7, result.filamentId); assertTrue(result.created)
        assertEquals(listOf("GET","POST","GET","POST"), calls.map { it.first })
        assertTrue(calls[0].second.contains("/api/v1/vendor?external_id=filamajig%3ASUNLU"))
        assertTrue(calls[2].second.contains("filamajig%3Alocal+1"))
        assertTrue(calls[3].third.orEmpty().contains("\"vendor_id\":3"))
    }

    @Test fun updatesExistingFilamentInsteadOfCreatingDuplicate() {
        val methods = mutableListOf<String>()
        val replies = ArrayDeque(listOf(
            SpoolmanHttpResponse(200, "[{\"id\":3}]".encodeToByteArray()),
            SpoolmanHttpResponse(200, "[{\"id\":7}]".encodeToByteArray()),
            SpoolmanHttpResponse(200, "{\"id\":7}".encodeToByteArray()),
        ))
        val result = SpoolmanSyncClient { method, _, _ -> methods += method; replies.removeFirst() }.syncProfile("https://example.test/api/v1", record, "1.25")
        assertFalse(result.created); assertEquals(7, result.filamentId)
        assertEquals(listOf("GET","GET","PATCH"), methods)
    }

    @Test fun mutationTransportFailureIsAnUnknownOutcomeAndIsNotRetried() {
        var calls = 0
        val client = SpoolmanSyncClient { method, _, _ ->
            calls++
            if (method == "GET") SpoolmanHttpResponse(200, "[]".encodeToByteArray()) else throw java.io.IOException("lost")
        }
        val failure = assertFailsWith<SpoolmanSyncException> { client.syncProfile("http://localhost:7912", record, null) }
        assertTrue(failure.outcomeUnknown)
        assertEquals(2, calls)
    }

    @Test fun invalidServerIsRejectedBeforeNetworkUse() {
        var called = false
        val client = SpoolmanSyncClient { _,_,_ -> called = true; error("unexpected") }
        assertFailsWith<SpoolmanSyncException> { client.syncProfile("ftp://user:pass@example.test/x", record, null) }
        assertFalse(called)
    }

    @Test fun cleartextIsLimitedToLocalNetworkAddresses() {
        val client = SpoolmanSyncClient { _,_,_ -> error("unexpected") }
        assertFailsWith<SpoolmanSyncException> { client.syncProfile("http://example.test:7912", record, null) }
        assertFailsWith<SpoolmanSyncException> { client.syncProfile("http://8.8.8.8:7912", record, null) }
        assertFailsWith<SpoolmanSyncException> { client.syncProfile("http://spoolman:7912", record, null) }
        val localAddresses = listOf("http://localhost:7912", "http://spoolman.local:7912", "http://192.168.1.9:7912", "http://10.0.0.2:7912", "http://172.31.0.2:7912", "http://[::1]:7912")
        localAddresses.forEach { address ->
            var called = false
            val local = SpoolmanSyncClient { _,_,_ -> called = true; throw java.io.IOException("stop") }
            assertFailsWith<SpoolmanSyncException>(address) { local.syncProfile(address, record, null) }
            assertTrue(called, address)
        }
    }

    @Test fun retryAfterLostFilamentCreateReconcilesWithoutDuplicateCreate() {
        var filamentLookup = 0
        val methods = mutableListOf<String>()
        val client = SpoolmanSyncClient { method, url, _ ->
            methods += method
            when {
                url.contains("/vendor?") -> SpoolmanHttpResponse(200, "[{\"id\":3}]".encodeToByteArray())
                url.contains("/filament?") && filamentLookup++ == 0 -> SpoolmanHttpResponse(200, "[]".encodeToByteArray())
                url.contains("/filament?") -> SpoolmanHttpResponse(200, "[{\"id\":7}]".encodeToByteArray())
                method == "POST" -> throw java.io.IOException("response lost after commit")
                method == "PATCH" -> SpoolmanHttpResponse(200, "{\"id\":7}".encodeToByteArray())
                else -> error("unexpected $method $url")
            }
        }
        assertTrue(assertFailsWith<SpoolmanSyncException> { client.syncProfile("https://example.test", record, null) }.outcomeUnknown)
        val recovered = client.syncProfile("https://example.test", record, null)
        assertEquals(7, recovered.filamentId)
        assertEquals(1, methods.count { it == "POST" })
        assertEquals(1, methods.count { it == "PATCH" })
    }
}
