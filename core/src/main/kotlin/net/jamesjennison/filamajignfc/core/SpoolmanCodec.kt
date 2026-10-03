package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal
import java.security.MessageDigest

const val SPOOLMAN_SOURCE_REVISION = "81636f253ecd6ac76fcfe5d13fe2cbb417095823"

data class SpoolmanImport(
    val filament: FilamentRecord,
    val spoolmanFilamentId: Int?,
    val spoolmanSpoolId: Int?,
    val initialQuantityG: Int,
    val remainingQuantityG: Int?,
    val warnings: List<String>,
)

data class SpoolmanSyncRequests(
    val vendorJson: ByteArray,
    val filamentJson: ByteArray,
    val spoolJson: (filamentId: Int) -> ByteArray,
    val warnings: List<String>,
)

object SpoolmanCodec {
    fun encodeExport(record: FilamentRecord, initialQuantityG: Int = record.nominalMassG.value, remainingQuantityG: Int? = null): ByteArray {
        validateRecord(record)
        if (remainingQuantityG != null && remainingQuantityG !in 0..initialQuantityG) throw ValidationException("Remaining quantity must be between zero and initial quantity")
        val values = linkedMapOf<String, Any?>(
            "filament.name" to listOf(record.product.value, record.colorName.value).filter(String::isNotBlank).joinToString(" — ").ifBlank { record.material.value },
            "filament.vendor.name" to record.brand.value, "filament.vendor.external_id" to "filamajig:${record.brand.value}",
            "filament.material" to record.material.value, "filament.diameter" to record.diameterMm.value.toBigDecimal(), "filament.weight" to record.nominalMassG.value,
            "filament.article_number" to (record.sku ?: record.gtin), "filament.settings_extruder_temp" to record.nozzleMaxC?.value,
            "filament.settings_bed_temp" to record.bedMaxC?.value, "filament.color_hex" to normalizeHex(record.colorHex.value).takeIf { record.additionalColors.isEmpty() },
            "filament.multi_color_hexes" to record.additionalColors.takeIf { it.isNotEmpty() }?.joinToString(",") { normalizeHex(it) },
            "filament.external_id" to "filamajig:${record.packageId}", "initial_weight" to initialQuantityG, "remaining_weight" to remainingQuantityG,
        )
        return json(listOf(values)).encodeToByteArray()
    }

    fun decodeExport(bytes: ByteArray): List<SpoolmanImport> {
        val root = try { StrictJson(2_000_000, 24).parse(bytes) } catch (e: Exception) { throw ValidationException("Invalid Spoolman JSON: ${e.message}") }
        val objects = when (root) {
            is JsonValue.Arr -> root.values.mapIndexed { index, value -> value as? JsonValue.Obj ?: throw ValidationException("Spoolman item $index must be an object") }
            is JsonValue.Obj -> listOf(root)
            else -> throw ValidationException("Spoolman export must be a JSON object or array")
        }
        if (objects.isEmpty()) throw ValidationException("Spoolman export contains no records")
        return objects.mapIndexed { index, obj -> decodeItem(obj, index) }
    }

    fun syncRequests(record: FilamentRecord, initialQuantityG: Int = record.nominalMassG.value, remainingQuantityG: Int? = null, density: String? = null): SpoolmanSyncRequests {
        validateRecord(record)
        if (remainingQuantityG != null && remainingQuantityG !in 0..initialQuantityG) throw ValidationException("Remaining quantity must be between zero and initial quantity")
        val normalizedDensity = density?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
        val inferredDensity = normalizedDensity ?: materialDensity(record.material.value)
        val warnings = buildList {
            if (normalizedDensity == null) add("Spoolman requires density; ${inferredDensity.stripTrailingZeros().toPlainString()} g/cm3 was inferred from material and must be reviewed")
            add("Generated requests do not update a server until the user explicitly runs a sync")
        }
        val vendor = linkedMapOf<String, Any?>("name" to record.brand.value, "external_id" to "filamajig:${record.brand.value}")
        fun filament(vendorId: Int?) = linkedMapOf<String, Any?>(
            "name" to listOf(record.product.value, record.colorName.value).filter(String::isNotBlank).joinToString(" — ").ifBlank { record.material.value },
            "vendor_id" to vendorId, "material" to record.material.value, "density" to inferredDensity,
            "diameter" to record.diameterMm.value.toBigDecimal(), "weight" to record.nominalMassG.value,
            "article_number" to (record.sku ?: record.gtin), "settings_extruder_temp" to record.nozzleMaxC?.value,
            "settings_bed_temp" to record.bedMaxC?.value, "color_hex" to normalizeHex(record.colorHex.value).takeIf { record.additionalColors.isEmpty() },
            "multi_color_hexes" to record.additionalColors.takeIf { it.isNotEmpty() }?.joinToString(",") { normalizeHex(it) },
            "multi_color_direction" to record.additionalColors.takeIf { it.isNotEmpty() }?.let { "longitudinal" },
            "external_id" to "filamajig:${record.packageId}",
        )
        val spool: (Int) -> ByteArray = { filamentId -> json(linkedMapOf("filament_id" to filamentId, "initial_weight" to initialQuantityG, "remaining_weight" to remainingQuantityG)).encodeToByteArray() }
        return SpoolmanSyncRequests(json(vendor).encodeToByteArray(), json(filament(null)).encodeToByteArray(), spool, warnings)
    }

    fun filamentRequest(record: FilamentRecord, vendorId: Int, density: String? = null): ByteArray {
        val request = syncRequests(record, density = density)
        val obj = StrictJson().parse(request.filamentJson) as JsonValue.Obj
        val values = obj.values.toMutableMap()
        values["vendor_id"] = JsonValue.Num(vendorId.toString())
        return jsonValue(JsonValue.Obj(LinkedHashMap(values))).encodeToByteArray()
    }

    private fun decodeItem(obj: JsonValue.Obj, index: Int): SpoolmanImport {
        val nestedFilament = obj.values["filament"] as? JsonValue.Obj
        val flatSpool = obj.values.keys.any { it.startsWith("filament.") }
        val filament = nestedFilament ?: if (flatSpool) obj.unprefix("filament.") else obj
        val vendor = (filament.values["vendor"] as? JsonValue.Obj) ?: if (flatSpool || filament.values.keys.any { it.startsWith("vendor.") }) filament.unprefix("vendor.") else null
        val source = "Spoolman JSON export (${SPOOLMAN_SOURCE_REVISION.take(8)})"
        val brand = vendor?.string("name").orEmpty().ifBlank { "Unknown vendor" }
        val material = filament.string("material").orEmpty().ifBlank { throw ValidationException("Spoolman item $index has no material") }
        val name = filament.string("name").orEmpty()
        val colors = filament.string("multi_color_hexes").orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        val primary = filament.string("color_hex").orEmpty().take(6).ifBlank { colors.firstOrNull()?.take(6) ?: "808080" }
        val diameter = filament.number("diameter")?.stripTrailingZeros()?.toPlainString() ?: "1.75"
        val mass = filament.number("weight")?.intExact() ?: 1000
        val filamentId = filament.int("id")
        val isSpool = nestedFilament != null || flatSpool
        val spoolId = if (isSpool) obj.int("id") else null
        val initial = obj.number("initial_weight")?.intExact() ?: mass
        val remaining = obj.number("remaining_weight")?.intExact()
        val article = filament.string("article_number")
        val record = FilamentRecord(
            packageId = "spoolman:" + (spoolId?.toString() ?: filamentId?.toString() ?: digest(obj)),
            variantId = filamentId?.toString(), productId = spoolId?.toString(), brand = FieldValue(brand, source),
            material = FieldValue(material, source), product = FieldValue(name, source), colorName = FieldValue(name, source),
            colorHex = FieldValue(primary, source), diameterMm = FieldValue(diameter, source), nominalMassG = FieldValue(mass, source),
            nozzleMinC = filament.int("settings_extruder_temp")?.let { FieldValue(it, source) },
            nozzleMaxC = filament.int("settings_extruder_temp")?.let { FieldValue(it, source) },
            bedMinC = filament.int("settings_bed_temp")?.let { FieldValue(it, source) },
            bedMaxC = filament.int("settings_bed_temp")?.let { FieldValue(it, source) },
            gtin = article?.filter(Char::isDigit)?.takeIf { it.length in setOf(8,12,13,14) }, sku = article,
            sourceRevision = SPOOLMAN_SOURCE_REVISION, provenance = Provenance.CUSTOM,
            additionalColors = colors.drop(if (filament.string("color_hex").isNullOrBlank()) 1 else 0).map { it.take(6) },
        )
        validateRecord(record)
        val warnings = buildList {
            if (vendor == null) add("Vendor was absent; review the placeholder brand")
            if (filament.string("color_hex").isNullOrBlank() && colors.isEmpty()) add("Color was absent; neutral gray was used for local review")
            if (filament.values["diameter"] == null) add("Diameter was absent; SpoolForge's 1.75 mm default was applied")
            if (filament.values["weight"] == null) add("Weight was absent; SpoolForge's 1000 g default was applied")
            if (isSpool) add("Spoolman physical spool ${spoolId ?: "unknown"} was imported separately from filament type ${filamentId ?: "unknown"}")
        }
        return SpoolmanImport(record, filamentId, spoolId, initial, remaining, warnings)
    }

    private fun JsonValue.Obj.number(key: String) = when (val v = values[key]) { is JsonValue.Num -> v.lexical.toBigDecimalOrNull(); else -> null }
    private fun JsonValue.Obj.unprefix(prefix: String) = JsonValue.Obj(LinkedHashMap(values.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }))
    private fun BigDecimal.intExact() = runCatching { intValueExact() }.getOrElse { throw ValidationException("Spoolman weight must be a whole number of grams") }
    private fun materialDensity(material: String): BigDecimal = when {
        "PETG" in material.uppercase() -> BigDecimal("1.27")
        "ABS" in material.uppercase() -> BigDecimal("1.04")
        "ASA" in material.uppercase() -> BigDecimal("1.07")
        "TPU" in material.uppercase() -> BigDecimal("1.21")
        "PLA" in material.uppercase() -> BigDecimal("1.24")
        else -> throw ValidationException("Spoolman sync requires a reviewed density for material '$material'")
    }
    private fun digest(obj: JsonValue.Obj) = MessageDigest.getInstance("SHA-256").digest(jsonValue(obj).encodeToByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> jsonString(value)
    is BigDecimal -> value.stripTrailingZeros().toPlainString()
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.filter { it.value != null }.joinToString(",", "{", "}") { jsonString(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> throw ValidationException("Unsupported Spoolman JSON value")
}
private fun jsonValue(value: JsonValue): String = when (value) {
    is JsonValue.Obj -> value.values.entries.joinToString(",", "{", "}") { jsonString(it.key) + ":" + jsonValue(it.value) }
    is JsonValue.Arr -> value.values.joinToString(",", "[", "]", transform = ::jsonValue)
    is JsonValue.Str -> jsonString(value.value); is JsonValue.Num -> value.lexical
    is JsonValue.Bool -> value.value.toString(); JsonValue.Null -> "null"
}
private fun jsonString(value: String) = buildString { append('"'); value.forEach { c -> when (c) {
    '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
    '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
} }; append('"') }
