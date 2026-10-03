package net.jamesjennison.filamajignfc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jamesjennison.filamajignfc.core.Gtin
import net.jamesjennison.filamajignfc.core.JsonValue
import net.jamesjennison.filamajignfc.core.PORTABLE_QR_MAX_BYTES
import net.jamesjennison.filamajignfc.core.StrictJson
import net.jamesjennison.filamajignfc.core.string
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LabelCode(
    val value: String,
    val format: String,
    val kind: String,
    val gtin: String? = null,
    val photoRole: String? = null,
)
data class LabelScanResult(val seed: CustomSeed, val review: List<String>, val codes: List<LabelCode>, val provider: String, val model: String)
enum class LabelPhotoRole(val wireName: String, val title: String) {
    PROFILE("profile_label", "filament profile label"),
}
data class LabelPhoto(val role: LabelPhotoRole, val jpeg: ByteArray, val codes: List<LabelCode>)
internal data class OllamaLabelRequest(val requestKey: String, val body: ByteArray)
internal data class LabelHttpResponse(val status: Int, val body: ByteArray)
internal fun interface LabelScanTransport {
    @Throws(IOException::class)
    fun execute(baseUrl: String, request: OllamaLabelRequest): LabelHttpResponse
}

private fun isAmazonStyleIdentifierCandidate(value: String) = value.trim().uppercase().matches(Regex("X00[A-Z0-9]{7}"))

internal fun classifyLabelCode(value: String, format: String, normalizedGtin: String? = null): LabelCode {
    val clean = value.trim().take(PORTABLE_QR_MAX_BYTES)
    val gtin = normalizedGtin ?: if (format in setOf("EAN_8", "EAN_13", "UPC_A", "UPC_E")) Gtin.normalize(clean) else null
    val kind = when {
        gtin != null -> "GTIN"
        isAmazonStyleIdentifierCandidate(clean) -> "Amazon-style identifier candidate"
        format == "QR_CODE" -> "QR payload"
        format == "CODE_128" -> "Code 128 identifier"
        else -> "$format identifier"
    }
    return LabelCode(clean, format, kind, gtin)
}

private const val OLLAMA_PROVIDER = "ollama-spectrum-gpu"
private const val LABEL_PROMPT = "Extract only filament product facts visible in this package image. Never supply general material knowledge or infer temperatures that are not printed. label_brand is the brand or product-family name visibly printed on the package. product must preserve the complete visible product or variant wording, including descriptive phrases; do not replace a specific printed product phrase with only the material plus the word Filament. manufacturer is the company name only when that company name or logo is visibly printed; do not infer a parent company from product-family knowledge or packaging style. Keep retail GTIN, manufacturer SKU, marketplace identifiers, QR data, and lot numbers distinct. transmission_distance is the numeric HueForge TD value only when TD or transmission distance is visibly printed; do not confuse it with diameter. For every field, photo_roles must contain profile_label when the photo visibly supports the claim; use an empty array for absent fields. Use null with basis absent when a field is not visible. Confidence always means confidence that the claim is correct; for a null absent claim, it means confidence that the field is not visible. Estimate color_hex from the visibly represented filament color when possible; return six hexadecimal digits without #."

private fun labelClaimSchema(valueSchema: JSONObject) = JSONObject()
    .put("type", "object")
    .put("properties", JSONObject()
        .put("value", JSONObject().put("anyOf", JSONArray().put(valueSchema).put(JSONObject().put("type", "null"))))
        .put("confidence", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1))
        .put("evidence", JSONObject().put("type", JSONArray().put("string").put("null")))
        .put("basis", JSONObject().put("type", "string").put("enum", JSONArray(listOf("printed", "barcode", "visual_estimate", "absent"))))
        .put("photo_roles", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray().put("profile_label")))))
    .put("required", JSONArray(listOf("value", "confidence", "evidence", "basis", "photo_roles")))
    .put("additionalProperties", false)

internal fun labelResponseSchema(): JSONObject {
    fun typed(type: String) = JSONObject().put("type", type)
    val fieldSchemas = linkedMapOf(
        "label_brand" to typed("string"), "manufacturer" to typed("string"), "product" to typed("string"),
        "material" to typed("string"), "color" to typed("string"),
        "color_hex" to typed("string").put("pattern", "^[0-9A-Fa-f]{6}$"),
        "diameter_mm" to typed("number"), "net_weight_g" to typed("integer"),
        "nozzle_min_c" to typed("integer"), "nozzle_max_c" to typed("integer"),
        "bed_min_c" to typed("integer"), "bed_max_c" to typed("integer"),
        "transmission_distance" to typed("number"), "gtin" to typed("string"), "sku" to typed("string"), "lot" to typed("string"),
    )
    val properties = JSONObject()
    fieldSchemas.forEach { (name, schema) -> properties.put(name, labelClaimSchema(schema)) }
    properties.put("other_codes", JSONObject().put("type", "array").put("items", JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("value", typed("string")).put("kind", typed("string")).put("evidence", typed("string")))
        .put("required", JSONArray(listOf("value", "kind", "evidence")))
        .put("additionalProperties", false)))
    properties.put("needs_user_review", JSONObject().put("type", "array").put("items", typed("string")))
    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(fieldSchemas.keys.toList() + listOf("other_codes", "needs_user_review")))
        .put("additionalProperties", false)
}

private fun directOllamaBody(photos: List<LabelPhoto>, codes: List<LabelCode>, model: String): ByteArray {
    val prompt = labelPromptWithCodes(codes)
    val images = JSONArray()
    photos.forEach { photo ->
        images.put(Base64.getEncoder().encodeToString(photo.jpeg))
    }
    return JSONObject()
        .put("model", model)
        .put("stream", false)
        .put("think", false)
        .put("keep_alive", "10m")
        .put("format", labelResponseSchema())
        .put("messages", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("content", "$prompt Photo role: ${photos.single().role.wireName}")
            .put("images", images)))
        .put("options", JSONObject().put("temperature", 0).put("seed", 0).put("num_ctx", 8192))
        .toString().encodeToByteArray()
}

internal fun labelRequestKey(photos: List<LabelPhoto>, codes: List<LabelCode>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    photos.forEach { photo ->
        val role = photo.role.wireName.encodeToByteArray()
        digest.update(role); digest.update(0); digest.update(ByteBuffer.allocate(4).putInt(photo.jpeg.size).array()); digest.update(photo.jpeg)
    }
    codes.sortedWith(compareBy(LabelCode::format, LabelCode::value, LabelCode::kind)).forEach { code ->
        listOf(code.format, code.value, code.kind, code.photoRole.orEmpty()).forEach { value ->
            digest.update(value.encodeToByteArray()); digest.update(0)
        }
    }
    val requestId = digest.digest().joinToString("") { "%02x".format(it) }
    return "filamajig-label-$requestId"
}

internal fun buildOllamaLabelRequest(photos: List<LabelPhoto>, codes: List<LabelCode>, model: String): OllamaLabelRequest {
    return OllamaLabelRequest(labelRequestKey(photos, codes), directOllamaBody(photos, codes, model))
}

internal fun ollamaErrorMessage(bytes: ByteArray): String? = runCatching {
    val root = StrictJson(16_384).parse(bytes) as? JsonValue.Obj ?: return@runCatching null
    (root.values["error"] as? JsonValue.Str)?.value?.take(500)
}.getOrNull()

private val labelHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun isAllowedVisionServiceUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    if (url.scheme == "https") return true
    if (url.scheme != "http") return false
    if (url.host in setOf("127.0.0.1", "localhost", "::1")) return true
    val octets = url.host.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets[0] == 100 && octets[1] in 64..127 && octets.all { it in 0..255 }
}

private val directLabelScanTransport = LabelScanTransport { baseUrl, request ->
    val httpRequest = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/api/chat")
        .header("Host", "localhost")
        .post(request.body.toRequestBody("application/json".toMediaType()))
        .build()
    labelHttpClient.newCall(httpRequest).execute().use { response ->
        LabelHttpResponse(response.code, response.body?.bytes() ?: ByteArray(0))
    }
}

internal fun executeOllamaWithRetry(
    baseUrl: String,
    request: OllamaLabelRequest,
    transport: LabelScanTransport,
): ByteArray {
    var connectionFailure: IOException? = null
    repeat(2) { attempt ->
        try {
            val response = transport.execute(baseUrl, request)
            if (response.status !in 200..299) {
                val message = ollamaErrorMessage(response.body)
                error(message ?: "Local vision service returned HTTP ${response.status}")
            }
            return response.body
        } catch (failure: IOException) {
            connectionFailure = failure
            if (attempt == 1) throw IOException("Local vision service could not be reached. Make sure Tailscale is connected and try again.", failure)
        }
    }
    throw connectionFailure ?: IOException("Local vision service was unavailable")
}

/** Puts a model's extracted label JSON into the envelope that [parseLabelScan] reads. */
internal fun wrapExtractedLabel(provider: String, model: String, outputText: String): ByteArray {
    // A model answering without an enforced schema may wrap the JSON in prose or a code fence.
    val start = outputText.indexOf('{')
    val end = outputText.lastIndexOf('}')
    require(start >= 0 && end > start) { "The vision model returned no structured label result" }
    val extracted = JSONObject(outputText.substring(start, end + 1))
    val fields = JSONObject()
    extracted.keys().forEach { key -> if (key !in setOf("other_codes", "needs_user_review")) fields.put(key, extracted.get(key)) }
    return JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("fields", fields)
        .put("other_codes", extracted.optJSONArray("other_codes") ?: JSONArray())
        .put("needs_user_review", extracted.optJSONArray("needs_user_review") ?: JSONArray())
        .toString().encodeToByteArray()
}

private fun unwrapOllamaResponse(bytes: ByteArray): ByteArray {
    val raw = JSONObject(bytes.decodeToString())
    val outputText = raw.optJSONObject("message")?.optString("content").orEmpty()
    require(outputText.isNotBlank()) { "Local vision model returned no structured label result" }
    return wrapExtractedLabel(OLLAMA_PROVIDER, raw.optString("model", BuildConfig.OLLAMA_VISION_MODEL), outputText)
}

internal const val CHATGPT_PLAN_PROVIDER = "chatgpt-plan"

/**
 * The one ChatGPT model used for label scanning. It was chosen by running every model the plan
 * offered against the six reviewed label fixtures on the device (2026-10-03): it tied for the
 * most exact fields, reconstructed every product term, made the fewest unsupported claims, and
 * was the fastest by a wide margin. See docs/AI_LABEL_SCANNING.md for the numbers.
 */
internal const val CHATGPT_LABEL_MODEL = "gpt-5.6-terra"
internal const val CHATGPT_LABEL_MODEL_NAME = "GPT-5.6 Terra"

private fun labelPromptWithCodes(codes: List<LabelCode>): String {
    val codeEvidence = JSONArray().apply { codes.take(32).forEach { code ->
        put(JSONObject().put("value", code.value).put("format", code.format).put("kind", code.kind).put("gtin", code.gtin).put("photo_role", code.photoRole))
    } }
    return "$LABEL_PROMPT Local ZXing decoded these symbols independently. Treat them as code evidence, keep marketplace IDs and QR payloads distinct, and do not convert them into GTINs unless their format and check digit prove that classification: $codeEvidence"
}

/**
 * Builds the Responses API request for a label photo, charged to the user's ChatGPT plan.
 * Plan usage requires `store: false` and `stream: true`. With [structured] the reply is constrained
 * to the label schema; without it the schema is described in the prompt instead, for models or
 * plans that reject structured output.
 */
internal fun buildChatGptLabelBody(photos: List<LabelPhoto>, codes: List<LabelCode>, model: String, structured: Boolean): JSONObject {
    val prompt = labelPromptWithCodes(codes) +
        if (structured) "" else " Reply with one JSON object only, no prose and no code fence, matching this JSON Schema exactly: ${labelResponseSchema()}"
    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
    photos.forEach { photo ->
        content.put(JSONObject().put("type", "input_text").put("text", "Photo role: ${photo.role.wireName}"))
        content.put(JSONObject()
            .put("type", "input_image")
            .put("image_url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(photo.jpeg)}")
            .put("detail", "high"))
    }
    val body = JSONObject()
        .put("model", model)
        .put("store", false)
        .put("stream", true)
        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
    if (structured) {
        body.put("text", JSONObject().put("format", JSONObject()
            .put("type", "json_schema").put("name", "filament_label").put("strict", true).put("schema", labelResponseSchema())))
    }
    return body
}

/**
 * Analyzes a label through the signed-in ChatGPT plan.
 * [isConnected] is checked first so an app that never signed in goes straight to the local model.
 */
internal class ChatGptLabelAnalyzer(
    private val isConnected: () -> Boolean,
    private val model: String = CHATGPT_LABEL_MODEL,
    private val respond: (JSONObject) -> String,
) {
    val available: Boolean get() = isConnected()

    fun analyze(photos: List<LabelPhoto>, codes: List<LabelCode>): ByteArray {
        val outputText = try {
            respond(buildChatGptLabelBody(photos, codes, model, structured = true))
        } catch (failure: ChatGptException) {
            // Structured output is not guaranteed under plan usage. Retry once with the schema in the prompt.
            val genericRejection = (failure.status == 400 || failure.status == 422) && (failure.code == "api_error" || failure.code.startsWith("invalid_request"))
            val formatRejected = failure.code == CHATGPT_UNSUPPORTED_CAPABILITY_CODE || genericRejection
            if (!formatRejected) throw failure
            respond(buildChatGptLabelBody(photos, codes, model, structured = false))
        }
        return wrapExtractedLabel(CHATGPT_PLAN_PROVIDER, model, outputText)
    }
}

class LabelScanClient internal constructor(
    private val baseUrl: String = BuildConfig.OLLAMA_BASE_URL,
    private val model: String = BuildConfig.OLLAMA_VISION_MODEL,
    private val transport: LabelScanTransport = directLabelScanTransport,
    private val chatGpt: ChatGptLabelAnalyzer? = null,
) {
    /**
     * Uses the signed-in ChatGPT plan when there is one, and the local vision model otherwise.
     * If ChatGPT fails, the local model answers instead and the result says so.
     */
    suspend fun analyze(photos: List<LabelPhoto>, codes: List<LabelCode>): LabelScanResult = withContext(Dispatchers.IO) {
        require(photos.size == 1) { "Capture exactly one label photo" }
        require(photos.single().jpeg.size in 1..6_000_000) { "The label photo must be 6 MB or smaller" }
        if (chatGpt == null || !chatGpt.available) return@withContext analyzeLocally(photos, codes)
        try {
            parseLabelScan(chatGpt.analyze(photos, codes), codes)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (planFailure: Exception) {
            val reason = planFailure.message ?: "unknown error"
            val local = runCatching { analyzeLocally(photos, codes) }.getOrElse {
                // The ChatGPT failure is the one the user can act on.
                throw IllegalStateException("ChatGPT: $reason The local model was also unavailable.", planFailure)
            }
            local.copy(review = listOf("ChatGPT could not analyze this label ($reason). The local model answered instead.") + local.review)
        }
    }

    private fun analyzeLocally(photos: List<LabelPhoto>, codes: List<LabelCode>): LabelScanResult {
        require(baseUrl.isNotBlank()) { "Local vision service is not configured in this build" }
        require(isAllowedVisionServiceUrl(baseUrl)) { "Local vision service must use HTTPS, loopback, or the encrypted Tailscale network" }
        require(model.isNotBlank()) { "Local vision model is not configured in this build" }
        val request = buildOllamaLabelRequest(photos, codes, model)
        val response = executeOllamaWithRetry(baseUrl, request, transport)
        return parseLabelScan(unwrapOllamaResponse(response), codes)
    }
}

internal fun parseLabelScan(bytes: ByteArray, localCodes: List<LabelCode>): LabelScanResult {
    val root = StrictJson(128 * 1024).parse(bytes) as? JsonValue.Obj ?: error("Invalid label service response")
    val fields = root.values["fields"] as? JsonValue.Obj ?: error("Label service omitted fields")
    fun claim(name: String): JsonValue.Obj? = fields.values[name] as? JsonValue.Obj
    fun value(name: String): String = when (val v = claim(name)?.values?.get("value")) {
        is JsonValue.Str -> v.value; is JsonValue.Num -> v.lexical; else -> ""
    }
    fun source(name: String): String {
        val c = claim(name) ?: return ""
        if (value(name).isBlank()) return ""
        val confidence = (c.values["confidence"] as? JsonValue.Num)?.lexical?.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100))?.toInt()
        val basis = (c.values["basis"] as? JsonValue.Str)?.value ?: "AI"
        val photoRoles = (c.values["photo_roles"] as? JsonValue.Arr)?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
        val photoText = photoRoles.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · photos ")
        if (basis == "catalog") return (c.values["evidence"] as? JsonValue.Str)?.value ?: "Local catalog candidate"
        return "AI label scan · ${root.string("model").orEmpty()} · $basis${confidence?.let { " · $it%" }.orEmpty()}${photoText.orEmpty()}"
    }
    val reconciledCodes = reconcileLabelCodes(localCodes)
    val localGtins = reconciledCodes.mapNotNull(LabelCode::gtin).distinct()
    val deterministicGtin = localGtins.singleOrNull()
    val aiGtin = value("gtin").let(Gtin::normalize)
    val chosenGtin = if (localGtins.size > 1) null else deterministicGtin ?: aiGtin
    val marketplaceCandidateValues = localCodes
        .filter { it.kind == "Amazon-style identifier candidate" }
        .map { it.value.trim().uppercase() }
        .toSet()
    val aiSkuClaim = value("sku").trim()
    val aiSkuIsAmazonStyleCandidate = isAmazonStyleIdentifierCandidate(aiSkuClaim)
    val aiSku = aiSkuClaim.takeIf { candidate ->
        candidate.isNotEmpty() && !aiSkuIsAmazonStyleCandidate && candidate.uppercase() !in marketplaceCandidateValues
    }
    val extractedBrand = value("label_brand").trim()
    val packagingConditions = setOf("new", "used", "renewed", "refurbished")
    val ignoredPackagingConditionBrand = (marketplaceCandidateValues.isNotEmpty() || aiSkuIsAmazonStyleCandidate) && extractedBrand.lowercase() in packagingConditions
    val chosenBrand = extractedBrand.takeUnless { ignoredPackagingConditionBrand }.orEmpty()
    val extractedColorHex = value("color_hex").removePrefix("#").uppercase().takeIf { it.matches(Regex("[0-9A-F]{6}")) }
    val colorHex = extractedColorHex ?: "808080"
    val mapping = mapOf(
        "brand" to "label_brand", "material" to "material", "product" to "product", "colorName" to "color", "colorHex" to "color_hex",
        "diameter" to "diameter_mm", "mass" to "net_weight_g", "nozzleMin" to "nozzle_min_c", "nozzleMax" to "nozzle_max_c", "bedMin" to "bed_min_c", "bedMax" to "bed_max_c",
        "transmissionDistance" to "transmission_distance",
    )
    val values = mapping.mapValues { (_, remote) -> when (remote) {
        "color_hex" -> colorHex
        "label_brand" -> chosenBrand
        "diameter_mm" -> value(remote).ifBlank { DEFAULT_DIAMETER_MM }
        "net_weight_g" -> value(remote).ifBlank { DEFAULT_NOMINAL_MASS_G }
        else -> value(remote)
    } }
    val sources = mapping.mapValues { (local, remote) -> when {
        remote == "color_hex" && extractedColorHex == null -> "Placeholder color · review required"
        remote == "label_brand" && ignoredPackagingConditionBrand -> "Ignored packaging condition · review required"
        remote == "diameter_mm" && value(remote).isBlank() -> ASSUMED_DEFAULT_SOURCE
        remote == "net_weight_g" && value(remote).isBlank() -> ASSUMED_DEFAULT_SOURCE
        values.getValue(local).isBlank() -> "Not found on label"
        else -> source(remote)
    } }
    val review = ((root.values["needs_user_review"] as? JsonValue.Arr)?.values ?: emptyList()).mapNotNull { (it as? JsonValue.Str)?.value }.toMutableList()
    if (extractedColorHex == null) review += "Confirm the color swatch; the service did not return a valid color hex value."
    if (localGtins.size > 1) review += "Multiple valid GTINs were decoded from this label. No GTIN was selected; confirm the product code manually."
    if (deterministicGtin != null && aiGtin != null && deterministicGtin != aiGtin) review += "The printed-code decoder and AI read different GTINs; the deterministic decoder was retained."
    if (value("sku").isBlank() && marketplaceCandidateValues.isNotEmpty()) review += "Amazon-style identifier candidates were retained as evidence and were not used as the manufacturer SKU without confirmation."
    if (aiSkuClaim.isNotBlank() && aiSku == null) review += "The AI returned $aiSkuClaim as an Amazon-style identifier candidate. It was not used as the manufacturer SKU without confirmation."
    if (ignoredPackagingConditionBrand) review += "The AI brand matched a marketplace packaging condition, so the brand was left blank for confirmation."
    value("manufacturer").takeIf(String::isNotBlank)?.let { review += "Manufacturer detected: $it. The current spool schema stores the visible brand separately; confirm the brand field." }
    value("lot").takeIf(String::isNotBlank)?.let { review += "Lot detected: $it. Lot storage is not yet supported, so record it elsewhere before saving if needed." }
    val evidence = reconciledCodes.joinToString("\n") {
        "${it.kind} (${it.format})${it.photoRole?.let { role -> " [$role]" }.orEmpty()}: ${it.value}"
    }
    val seed = CustomSeed(
        gtin = chosenGtin, articleNumber = aiSku,
        brand = values.getValue("brand"), material = values.getValue("material"), product = values.getValue("product"), color = values.getValue("colorName"), hex = values.getValue("colorHex"),
        diameter = values.getValue("diameter"), mass = values.getValue("mass"), nozzleMin = values.getValue("nozzleMin"), nozzleMax = values.getValue("nozzleMax"), bedMin = values.getValue("bedMin"), bedMax = values.getValue("bedMax"),
        transmissionDistance = values.getValue("transmissionDistance"),
        sourceRevision = "ai-label-scan", provenance = net.jamesjennison.filamajignfc.core.Provenance.CUSTOM,
        sources = sources, originalValues = values, barcodeEvidence = evidence,
    )
    return LabelScanResult(seed, review.distinct(), reconciledCodes, root.string("provider").orEmpty(), root.string("model").orEmpty())
}

/**
 * A 1-D reader can interpret a subset of a larger UPC/EAN symbol as a valid EAN-8.
 * Prefer the complete retail symbol, while retaining genuinely distinct symbols of
 * the same length so ambiguous packaging still requires review.
 */
internal fun reconcileLabelCodes(codes: List<LabelCode>): List<LabelCode> {
    val distinct = codes.distinctBy { "${it.format}:${it.value}" }
    val longestRetailDigits = distinct.filter { it.gtin != null }
        .maxOfOrNull { it.value.filter(Char::isDigit).length } ?: 0
    return distinct.filterNot { code ->
        code.gtin != null && code.format in setOf("EAN_8", "UPC_E") && longestRetailDigits > 8
    }
}

internal fun recoveredGtinFromEvidence(evidence: String): String? {
    return labelCodesFromEvidence(evidence).mapNotNull(LabelCode::gtin).distinct().singleOrNull()
}

internal fun labelCodesFromEvidence(evidence: String): List<LabelCode> {
    val prefix = Regex("^(.+) \\(([^)]+)\\)(?: \\[[^]]+])?$")
    val parsed = evidence.lineSequence().mapNotNull { line ->
        val marker = line.indexOf(": ")
        if (marker < 0) return@mapNotNull null
        val match = prefix.matchEntire(line.substring(0, marker)) ?: return@mapNotNull null
        val role = Regex("\\[([^]]+)]$").find(line.substring(0, marker))?.groupValues?.get(1)
        classifyLabelCode(line.substring(marker + 2), match.groupValues[2]).copy(kind = match.groupValues[1], photoRole = role)
    }.toList()
    return reconcileLabelCodes(parsed)
}
