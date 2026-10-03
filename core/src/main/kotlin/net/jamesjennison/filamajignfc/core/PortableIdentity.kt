package net.jamesjennison.filamajignfc.core

const val PORTABLE_IDENTITY_SCHEMA = "filamajig.spool"
const val PORTABLE_IDENTITY_VERSION = 1
const val PORTABLE_QR_MAX_BYTES = 2_048

data class PortableSpoolIdentity(
    val profileId: String,
    val spoolId: String,
    val filament: FilamentRecord,
    val initialQuantityG: Int,
    val remainingQuantityG: Int? = null,
) {
    init {
        require(profileId.isNotBlank() && spoolId.isNotBlank())
        require(profileId != spoolId) { "Profile and spool identities must be distinct" }
        require(initialQuantityG > 0)
        require(remainingQuantityG == null || remainingQuantityG in 0..initialQuantityG)
        validateRecord(filament)
    }
}

sealed interface PortableIdentityDecodeResult {
    data class Supported(val identity: PortableSpoolIdentity, val includesSources: Boolean) : PortableIdentityDecodeResult
    data class FutureVersion(val version: Int, val raw: ByteArray) : PortableIdentityDecodeResult
    data class Rejected(val reason: String) : PortableIdentityDecodeResult
}

object PortableIdentityCodec {
    fun encodeQr(identity: PortableSpoolIdentity): ByteArray = encode(identity, includeSources = false).also {
        if (it.size > PORTABLE_QR_MAX_BYTES) throw ValidationException("Portable QR payload exceeds $PORTABLE_QR_MAX_BYTES bytes; use the export bundle")
    }

    fun encodeBundle(identity: PortableSpoolIdentity): ByteArray = encode(identity, includeSources = true)

    fun decode(bytes: ByteArray): PortableIdentityDecodeResult {
        return try {
        val root = StrictJson(maxBytes = 65_536, maxDepth = 12).parse(bytes) as? JsonValue.Obj
            ?: throw ValidationException("Portable identity must be a JSON object")
        if (root.string("schema") != PORTABLE_IDENTITY_SCHEMA) throw ValidationException("Unsupported portable identity schema")
        val version = root.int("version") ?: throw ValidationException("Portable identity version must be an integer")
        if (version > PORTABLE_IDENTITY_VERSION) return PortableIdentityDecodeResult.FutureVersion(version, bytes.copyOf())
        if (version != PORTABLE_IDENTITY_VERSION) throw ValidationException("Unsupported portable identity version: $version")
        val profileId = root.requiredString("profile_id")
        val spoolId = root.requiredString("spool_id")
        val filament = root.requiredObject("filament")
        val sources = root.objectOrNull("sources")
        fun source(key: String) = sources?.string(key).orEmpty().ifBlank { "Portable QR" }
        fun field(key: String) = FieldValue(filament.requiredString(key), source(key))
        fun optionalInt(key: String) = filament.int(key)?.let { FieldValue(it, source(key)) }
        val record = FilamentRecord(
            packageId = filament.requiredString("record_id"), variantId = filament.string("variant_id"), productId = filament.string("product_id"),
            brand = field("brand"), material = field("material"), product = field("product"), colorName = field("color_name"),
            colorHex = field("color_hex"), diameterMm = field("diameter_mm"),
            nominalMassG = FieldValue(filament.int("nominal_mass_g") ?: throw ValidationException("nominal_mass_g must be an integer"), source("nominal_mass_g")),
            nozzleMinC = optionalInt("nozzle_min_c"), nozzleMaxC = optionalInt("nozzle_max_c"),
            bedMinC = optionalInt("bed_min_c"), bedMaxC = optionalInt("bed_max_c"),
            gtin = filament.string("gtin"), sku = filament.string("sku"), sourceRevision = filament.requiredString("source_revision"),
            provenance = filament.requiredString("provenance").let { runCatching { Provenance.valueOf(it) }.getOrElse { throw ValidationException("Invalid provenance") } },
            additionalColors = filament.stringArray("additional_colors"),
            transmissionDistance = filament.string("transmission_distance")?.let { FieldValue(it, source("transmission_distance")) },
        )
        val quantity = root.requiredObject("quantity")
        PortableIdentityDecodeResult.Supported(
            PortableSpoolIdentity(profileId, spoolId, record,
                quantity.int("initial_g") ?: throw ValidationException("initial_g must be an integer"), quantity.int("remaining_g")),
            sources != null,
        )
        } catch (error: Exception) {
            PortableIdentityDecodeResult.Rejected(error.message ?: "Invalid portable identity")
        }
    }

    private fun encode(identity: PortableSpoolIdentity, includeSources: Boolean): ByteArray {
        val f = identity.filament
        val filament = linkedMapOf<String, Any?>(
            "record_id" to f.packageId, "variant_id" to f.variantId, "product_id" to f.productId,
            "brand" to f.brand.value, "material" to f.material.value, "product" to f.product.value,
            "color_name" to f.colorName.value, "color_hex" to normalizeHex(f.colorHex.value), "diameter_mm" to f.diameterMm.value,
            "nominal_mass_g" to f.nominalMassG.value, "nozzle_min_c" to f.nozzleMinC?.value, "nozzle_max_c" to f.nozzleMaxC?.value,
            "bed_min_c" to f.bedMinC?.value, "bed_max_c" to f.bedMaxC?.value, "gtin" to f.gtin, "sku" to f.sku,
            "source_revision" to f.sourceRevision, "provenance" to f.provenance.name,
            "additional_colors" to f.additionalColors.map(::normalizeHex), "transmission_distance" to f.transmissionDistance?.value,
        )
        val root = linkedMapOf<String, Any?>(
            "schema" to PORTABLE_IDENTITY_SCHEMA, "version" to PORTABLE_IDENTITY_VERSION,
            "profile_id" to identity.profileId, "spool_id" to identity.spoolId,
            "filament" to filament,
            "quantity" to linkedMapOf("initial_g" to identity.initialQuantityG, "remaining_g" to identity.remainingQuantityG),
        )
        if (includeSources) root["sources"] = linkedMapOf(
            "brand" to f.brand.source, "material" to f.material.source, "product" to f.product.source,
            "color_name" to f.colorName.source, "color_hex" to f.colorHex.source, "diameter_mm" to f.diameterMm.source,
            "nominal_mass_g" to f.nominalMassG.source, "nozzle_min_c" to f.nozzleMinC?.source,
            "nozzle_max_c" to f.nozzleMaxC?.source, "bed_min_c" to f.bedMinC?.source, "bed_max_c" to f.bedMaxC?.source,
            "transmission_distance" to f.transmissionDistance?.source,
        )
        return json(root).encodeToByteArray()
    }
}

private fun JsonValue.Obj.requiredString(key: String) = string(key)?.takeIf(String::isNotBlank)
    ?: throw ValidationException("$key must be a non-empty string")
private fun JsonValue.Obj.requiredObject(key: String) = values[key] as? JsonValue.Obj
    ?: throw ValidationException("$key must be an object")
private fun JsonValue.Obj.objectOrNull(key: String) = values[key] as? JsonValue.Obj
private fun JsonValue.Obj.stringArray(key: String): List<String> = when (val value = values[key]) {
    null -> emptyList()
    is JsonValue.Arr -> value.values.map { (it as? JsonValue.Str)?.value ?: throw ValidationException("$key must contain strings") }
    else -> throw ValidationException("$key must be an array")
}

private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString { append('"'); value.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
        '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
    } }; append('"') }
    is Int, is Long, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.filter { it.value != null }.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> throw ValidationException("Unsupported portable identity value")
}
