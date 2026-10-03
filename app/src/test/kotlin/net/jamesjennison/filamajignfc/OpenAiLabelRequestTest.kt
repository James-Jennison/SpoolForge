package net.jamesjennison.filamajignfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAiLabelRequestTest {
    @Test fun identicalLabelRequestUsesStableIdempotencyKey() {
        val photo = LabelPhoto(LabelPhotoRole.PROFILE, byteArrayOf(1, 2, 3), listOf(classifyLabelCode("6938936717461", "EAN_13")))
        val first = labelRequestKey(listOf(photo), photo.codes)
        val second = labelRequestKey(listOf(photo), photo.codes.reversed())
        assertEquals(first, second)
        assertEquals(64, first.removePrefix("filamajig-label-").length)
    }

    @Test fun connectionRetryReusesExactRequestAndIdempotencyKey() {
        val request = OllamaLabelRequest("stable-request", byteArrayOf(4, 5, 6))
        val seen = mutableListOf<OllamaLabelRequest>()
        val transport = LabelScanTransport { _, actual ->
            seen += actual
            if (seen.size == 1) throw IOException("interrupted")
            LabelHttpResponse(200, "ok".encodeToByteArray())
        }
        assertEquals("ok", executeOllamaWithRetry("http://100.64.0.1:11434", request, transport).decodeToString())
        assertEquals(2, seen.size)
        assertSame(request, seen[0])
        assertSame(request, seen[1])
    }

    @Test fun httpFailureIsNotBlindlyRetried() {
        var calls = 0
        val transport = LabelScanTransport { _, _ -> calls++; LabelHttpResponse(429, ByteArray(0)) }
        runCatching { executeOllamaWithRetry("http://100.64.0.1:11434", OllamaLabelRequest("stable", byteArrayOf(1)), transport) }
        assertEquals(1, calls)
    }

    @Test fun requestUsesConfiguredVisionModelAndStructuredOutput() {
        val photo = LabelPhoto(LabelPhotoRole.PROFILE, byteArrayOf(1, 2, 3), emptyList())
        val root = org.json.JSONObject(buildOllamaLabelRequest(listOf(photo), emptyList(), "qwen3.5:test").body.decodeToString())
        assertEquals("qwen3.5:test", root.getString("model"))
        assertEquals(false, root.getBoolean("stream"))
        assertEquals(false, root.getBoolean("think"))
        assertEquals("object", root.getJSONObject("format").getString("type"))
        assertEquals(1, root.getJSONArray("messages").getJSONObject(0).getJSONArray("images").length())
    }

    @Test fun ollamaErrorIsExtractedWithoutReturningWholeResponse() {
        assertEquals("Model is unavailable", ollamaErrorMessage("""{"error":"Model is unavailable"}""".encodeToByteArray()))
        assertEquals(null, ollamaErrorMessage("not json".encodeToByteArray()))
    }

    @Test fun cleartextVisionServiceIsRestrictedToLoopbackOrTailnet() {
        assertEquals(true, isAllowedVisionServiceUrl("http://100.100.100.100:11434"))
        assertEquals(true, isAllowedVisionServiceUrl("http://127.0.0.1:11434"))
        assertEquals(true, isAllowedVisionServiceUrl("https://vision.example.test"))
        assertEquals(false, isAllowedVisionServiceUrl("http://100.128.0.1:11434"))
        assertEquals(false, isAllowedVisionServiceUrl("http://192.168.1.20:11434"))
        assertEquals(false, isAllowedVisionServiceUrl("not-a-url"))
    }
}
