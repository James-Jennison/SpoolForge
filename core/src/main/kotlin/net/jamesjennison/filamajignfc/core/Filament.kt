package net.jamesjennison.filamajignfc.core

enum class Provenance { CATALOG, CATALOG_EDITED, CUSTOM }

data class FieldValue<T>(val value: T, val source: String)

data class FilamentRecord(
    val packageId: String,
    val variantId: String?,
    val productId: String?,
    val brand: FieldValue<String>,
    val material: FieldValue<String>,
    val product: FieldValue<String>,
    val colorName: FieldValue<String>,
    val colorHex: FieldValue<String>,
    val diameterMm: FieldValue<String>,
    val nominalMassG: FieldValue<Int>,
    val nozzleMinC: FieldValue<Int>?,
    val nozzleMaxC: FieldValue<Int>?,
    val bedMinC: FieldValue<Int>?,
    val bedMaxC: FieldValue<Int>?,
    val gtin: String? = null,
    val sku: String? = null,
    val sourceRevision: String,
    val provenance: Provenance,
    val additionalColors: List<String> = emptyList(),
    val transmissionDistance: FieldValue<String>? = null,
)

enum class OpenSpoolProfile { CANONICAL, PAXX }

/** A write intent whose byte content cannot be changed after user review. */
class OpenSpoolIntent(
    record: FilamentRecord,
    val profile: OpenSpoolProfile,
    payload: ByteArray,
    omittedFields: Set<String>,
    val codecId: String = if (profile == OpenSpoolProfile.PAXX) "openspool-paxx-u1-1.0" else "openspool-1.0",
    ndefRecords: List<TagRecord>? = null,
) {
    val record: FilamentRecord = record.copy(additionalColors = record.additionalColors.toList())
    private val frozenPayload = payload.copyOf()
    val payload: ByteArray get() = frozenPayload.copyOf()
    val omittedFields: Set<String> = omittedFields.toSet()
    val ndefRecords: List<TagRecord> = (ndefRecords ?: listOf(TagRecord(2, OpenSpoolCodec.MIME, frozenPayload))).map {
        TagRecord(it.tnf, it.mime, it.payload, it.id)
    }

    internal fun payloadUnsafe(): ByteArray = frozenPayload
    override fun equals(other: Any?) = other is OpenSpoolIntent && record == other.record && profile == other.profile && codecId == other.codecId && frozenPayload.contentEquals(other.frozenPayload) && omittedFields == other.omittedFields && ndefRecords == other.ndefRecords
    override fun hashCode() = 31 * (31 * (31 * (31 * (31 * record.hashCode() + profile.hashCode()) + codecId.hashCode()) + frozenPayload.contentHashCode()) + omittedFields.hashCode()) + ndefRecords.hashCode()
}

sealed interface DecodeResult {
    data class Supported(
        val values: Map<String, JsonValue>,
        val raw: ByteArray,
        val formatName: String = "OpenSpool",
        val convertedRecord: FilamentRecord? = null,
        val warnings: List<String> = emptyList(),
    ) : DecodeResult
    data class ReadOnly(val reason: String, val raw: ByteArray) : DecodeResult
    data class Rejected(val reason: String) : DecodeResult
}

class ValidationException(message: String) : IllegalArgumentException(message)

fun normalizeHex(input: String): String {
    val value = input.removePrefix("#").uppercase()
    if (!value.matches(Regex("[0-9A-F]{6}"))) throw ValidationException("Color must be exactly six hexadecimal digits")
    return value
}

fun validateRecord(record: FilamentRecord) {
    if (record.brand.value.isBlank() || record.material.value.isBlank()) throw ValidationException("Brand and material are required")
    listOf(record.brand.value, record.material.value, record.product.value, record.colorName.value).forEach {
        if (it.length > 200 || it.any(Char::isISOControl)) throw ValidationException("Text fields must be at most 200 printable characters")
    }
    normalizeHex(record.colorHex.value)
    val diameter = record.diameterMm.value.toBigDecimalOrNull() ?: throw ValidationException("Diameter must be decimal millimetres")
    if (diameter <= java.math.BigDecimal.ZERO || diameter > java.math.BigDecimal("10")) throw ValidationException("Diameter must be in millimetres; legacy 175 is not valid")
    if (record.nominalMassG.value <= 0 || record.nominalMassG.value > 100_000) throw ValidationException("Nominal mass is outside the supported range")
    fun range(name: String, min: FieldValue<Int>?, max: FieldValue<Int>?) {
        if ((min?.value ?: 0) < 0 || (max?.value ?: 0) < 0) throw ValidationException("$name temperatures cannot be negative")
        if (min != null && max != null && min.value > max.value) throw ValidationException("$name temperature range is inverted")
        if ((min?.value ?: 0) > 500 || (max?.value ?: 0) > 500) throw ValidationException("$name temperature exceeds 500 C")
    }
    range("Nozzle", record.nozzleMinC, record.nozzleMaxC)
    range("Bed", record.bedMinC, record.bedMaxC)
    record.transmissionDistance?.let {
        val td = it.value.toBigDecimalOrNull() ?: throw ValidationException("Transmission distance must be a decimal number")
        if (td < java.math.BigDecimal("0.1") || td > java.math.BigDecimal("100")) throw ValidationException("Transmission distance must be from 0.1 to 100")
    }
    record.additionalColors.forEach(::normalizeHex)
}
