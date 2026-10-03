package net.jamesjennison.filamajignfc

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64

private fun claim(value: Any?, basis: String = "printed") = JSONObject()
    .put("value", value ?: JSONObject.NULL).put("confidence", 0.9).put("evidence", JSONObject.NULL).put("basis", basis)
    .put("photo_roles", org.json.JSONArray().put("profile_label"))

private fun extractedLabel(brand: String): String {
    val root = JSONObject()
    listOf("manufacturer", "product", "nozzle_min_c", "nozzle_max_c", "bed_min_c", "bed_max_c", "transmission_distance", "gtin", "sku", "lot", "diameter_mm", "net_weight_g")
        .forEach { root.put(it, claim(null, "absent")) }
    root.put("label_brand", claim(brand)).put("material", claim("PLA")).put("color", claim("Cyan")).put("color_hex", claim("00FFFF"))
    return root.put("other_codes", org.json.JSONArray()).put("needs_user_review", org.json.JSONArray()).toString()
}

private fun ollamaReply(brand: String) = JSONObject().put("model", "local-model")
    .put("message", JSONObject().put("content", extractedLabel(brand))).toString().toByteArray()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatGptLabelScanTest {
    private val photo = LabelPhoto(LabelPhotoRole.PROFILE, byteArrayOf(1, 2, 3), emptyList())
    private val localTransport = LabelScanTransport { _, _ -> LabelHttpResponse(200, ollamaReply("LOCALBRAND")) }

    @Test fun planRequestCarriesTheImageAndTheRequiredPlanUsageFlags() {
        val body = buildChatGptLabelBody(listOf(photo), emptyList(), "m-vision", structured = true)
        assertEquals("m-vision", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        val image = content.getJSONObject(2)
        assertEquals("input_image", image.getString("type"))
        assertEquals("data:image/jpeg;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}", image.getString("image_url"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        assertTrue(format.getJSONObject("schema").getJSONObject("properties").has("label_brand"))

        val unstructured = buildChatGptLabelBody(listOf(photo), emptyList(), "m-vision", structured = false)
        assertFalse(unstructured.has("text"))
        assertTrue(unstructured.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").contains("JSON Schema"))
    }

    @Test fun wrapperToleratesProseAndCodeFencesAroundTheJson() {
        val wrapped = JSONObject(wrapExtractedLabel(CHATGPT_PLAN_PROVIDER, "m", "Here you go:\n```json\n${extractedLabel("ACME")}\n```").decodeToString())
        assertEquals("chatgpt-plan", wrapped.getString("provider"))
        assertEquals("ACME", wrapped.getJSONObject("fields").getJSONObject("label_brand").getString("value"))
        assertEquals(0, wrapped.getJSONArray("other_codes").length())
    }

    @Test fun labelScansUseTheBenchmarkedModelByDefault() {
        val requests = mutableListOf<JSONObject>()
        ChatGptLabelAnalyzer({ true }) { body -> requests += body; extractedLabel("X") }.analyze(listOf(photo), emptyList())
        assertEquals("gpt-5.6-terra", requests.single().getString("model"))
    }

    @Test fun signedInScansUseTheChatGptPlan() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val analyzer = ChatGptLabelAnalyzer({ true }, "m-vision") { body -> requests += body; extractedLabel("PLANBRAND") }
        val result = LabelScanClient("http://100.64.0.1:11434", "local-model", localTransport, analyzer).analyze(listOf(photo), emptyList())
        assertEquals("chatgpt-plan", result.provider)
        assertEquals("m-vision", result.model)
        assertEquals("PLANBRAND", result.seed.brand)
        assertEquals(1, requests.size)
        assertTrue(result.seed.sources.getValue("brand").contains("m-vision"))
    }

    @Test fun signedOutScansGoStraightToTheLocalModel() = runBlocking {
        val analyzer = ChatGptLabelAnalyzer({ false }, "m-vision") { error("must not be called") }
        val result = LabelScanClient("http://100.64.0.1:11434", "local-model", localTransport, analyzer).analyze(listOf(photo), emptyList())
        assertEquals("LOCALBRAND", result.seed.brand)
        assertTrue(result.review.none { it.contains("ChatGPT") })
    }

    @Test fun rejectedStructuredOutputRetriesOnceWithTheSchemaInThePrompt() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val analyzer = ChatGptLabelAnalyzer({ true }, "m-vision") { body ->
            requests += body
            if (body.has("text")) throw ChatGptException(CHATGPT_UNSUPPORTED_CAPABILITY_CODE, "unsupported", status = 400)
            "```json\n${extractedLabel("RETRYBRAND")}\n```"
        }
        val result = LabelScanClient("http://100.64.0.1:11434", "local-model", localTransport, analyzer).analyze(listOf(photo), emptyList())
        assertEquals("RETRYBRAND", result.seed.brand)
        assertEquals(listOf(true, false), requests.map { it.has("text") })
    }

    @Test fun usageLimitFallsBackToTheLocalModelAndSaysSo() = runBlocking {
        val analyzer = ChatGptLabelAnalyzer({ true }, "m-vision") {
            throw ChatGptException(CHATGPT_USAGE_LIMIT_CODE, "Your ChatGPT plan usage limit has been reached.", status = 429)
        }
        val result = LabelScanClient("http://100.64.0.1:11434", "local-model", localTransport, analyzer).analyze(listOf(photo), emptyList())
        assertEquals("LOCALBRAND", result.seed.brand)
        assertTrue(result.review.first().contains("usage limit") && result.review.first().contains("local model answered"))
    }

    @Test fun whenBothProvidersFailTheChatGptReasonIsReported() {
        val analyzer = ChatGptLabelAnalyzer({ true }) { throw ChatGptException("model_not_found", "This model is not available for your ChatGPT connection.", status = 404) }
        val offline = LabelScanTransport { _, _ -> throw IOException("no tailnet") }
        val failure = runCatching { runBlocking { LabelScanClient("http://100.64.0.1:11434", "local-model", offline, analyzer).analyze(listOf(photo), emptyList()) } }.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("not available for your ChatGPT connection"))
        assertTrue(failure.message!!.contains("local model was also unavailable"))
    }
}
