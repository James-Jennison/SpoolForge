package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class ExternalTagFormat(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val radioTechnology: String,
    val sourceRevision: String,
)

object ExternalTagRegistry {
    val formats = listOf(OpenTag3dV2Adapter.format, OpenPrintTagAdapter.format)

    fun decode(mimeType: String, payload: ByteArray): DecodeResult? = when (mimeType.lowercase()) {
        OpenTag3dV2Adapter.format.mimeType -> OpenTag3dV2Adapter.decode(payload)
        OpenPrintTagAdapter.format.mimeType -> OpenPrintTagAdapter.decode(payload)
        else -> null
    }
}

object OpenTag3dV2Adapter {
    val format = ExternalTagFormat(
        id = "opentag3d-2.000",
        displayName = "OpenTag3D 2.000",
        mimeType = "application/opentag3d",
        radioTechnology = "NFC-A / NDEF Type 2",
        sourceRevision = "d0f706896e772663404edab8aa814633ba3c6543",
    )
    private const val CORE_SIZE = 0xE0

    fun decode(payload: ByteArray): DecodeResult {
      return try {
        if (payload.size < CORE_SIZE) return DecodeResult.Rejected("OpenTag3D payload is shorter than its 224-byte core")
        val version = uint(payload, 0x00, 2).toInt()
        val major = version / 1000
        if (major > 2) return DecodeResult.ReadOnly("OpenTag3D major version ${versionText(version)} is newer than supported 2.000", payload.copyOf())
        if (major < 1) return DecodeResult.Rejected("Invalid OpenTag3D version ${versionText(version)}")

        val source = "${format.displayName} tag (${format.sourceRevision.take(8)})"
        val materialBase = text(payload, 0x02, 5).required("material")
        val modifier = text(payload, 0x07, 5)
        val brand = text(payload, 0x0C, 16).required("manufacturer")
        val colorName = text(payload, 0x1C, 32)
        val primary = rgba(payload, 0x3C, required = true)!!
        val colors = listOfNotNull(rgba(payload, 0x40), rgba(payload, 0x44), rgba(payload, 0x48))
        val sku = text(payload, 0x6C, 16).ifBlank { null }
        val barcode = uint(payload, 0x7C, 6).takeIf { it != BigInteger.ZERO }?.toString()
        val diameterMicrons = uint(payload, 0x8C, 2).toInt()
        val mass = uint(payload, 0x9E, 2).toInt()
        if (diameterMicrons <= 0) throw ValidationException("OpenTag3D diameter is missing")
        if (mass <= 0) throw ValidationException("OpenTag3D target weight is missing")
        val material = listOf(materialBase, modifier).filter(String::isNotBlank).joinToString(" ")
        val nozzleTarget = scaledTemp(payload[0x90])
        val bedTarget = scaledTemp(payload[0x94])
        val nozzleMin = scaledTemp(payload[0x91]) ?: nozzleTarget
        val nozzleMax = scaledTemp(payload[0x92]) ?: nozzleTarget
        val bedMin = scaledTemp(payload[0x95]) ?: bedTarget
        val bedMax = scaledTemp(payload[0x96]) ?: bedTarget
        val tdRaw = payload[0xA7].toInt() and 0xff
        val record = FilamentRecord(
            packageId = stableId("opentag3d", payload), variantId = sku, productId = barcode,
            brand = FieldValue(brand, source), material = FieldValue(material, source), product = FieldValue(modifier, source),
            colorName = FieldValue(colorName, source), colorHex = FieldValue(primary, source),
            diameterMm = FieldValue(BigDecimal(diameterMicrons).movePointLeft(3).stripTrailingZeros().toPlainString(), source),
            nominalMassG = FieldValue(mass, source), nozzleMinC = nozzleMin?.let { FieldValue(it, source) },
            nozzleMaxC = nozzleMax?.let { FieldValue(it, source) }, bedMinC = bedMin?.let { FieldValue(it, source) },
            bedMaxC = bedMax?.let { FieldValue(it, source) }, gtin = barcode, sku = sku,
            sourceRevision = format.sourceRevision, provenance = Provenance.CUSTOM, additionalColors = colors,
            transmissionDistance = tdRaw.takeIf { it > 0 }?.let { FieldValue(BigDecimal(it).movePointLeft(1).stripTrailingZeros().toPlainString(), source) },
        )
        validateRecord(record)
        val warnings = buildList {
            if (version > 2000 && major == 2) add("OpenTag3D minor version ${versionText(version)} is newer than supported 2.000; known fields were converted")
            if (payload.size > CORE_SIZE) add("${payload.size - CORE_SIZE} extension byte(s) remain preserved in the read-only source payload")
        }
        DecodeResult.Supported(record.toOpenSpoolValues(), payload.copyOf(), format.displayName, record, warnings)
      } catch (e: ValidationException) {
        DecodeResult.Rejected(e.message ?: "Malformed OpenTag3D payload")
      }
    }

    private fun scaledTemp(value: Byte): Int? = (value.toInt() and 0xff).takeIf { it > 0 }?.times(5)
    private fun versionText(version: Int) = "%d.%03d".format(version / 1000, version % 1000)
    private fun text(data: ByteArray, offset: Int, length: Int): String {
        val bytes = data.copyOfRange(offset, offset + length).let { b -> b.copyOf(b.indexOf(0).takeIf { it >= 0 } ?: b.size) }
        return decodeUtf8(bytes).trim()
    }
    private fun String.required(name: String) = also { if (isBlank()) throw ValidationException("OpenTag3D $name is missing") }
    private fun rgba(data: ByteArray, offset: Int, required: Boolean = false): String? {
        val alpha = data[offset + 3].toInt() and 0xff
        if (alpha == 0) {
            if (required) throw ValidationException("OpenTag3D primary color is transparent or missing")
            return null
        }
        return (0..2).joinToString("") { "%02X".format(data[offset + it].toInt() and 0xff) }
    }
}

object OpenPrintTagAdapter {
    val format = ExternalTagFormat(
        id = "openprinttag-current-read",
        displayName = "OpenPrintTag",
        mimeType = "application/vnd.openprinttag",
        radioTechnology = "NFC-V / ISO 15693",
        sourceRevision = "7e09cc38df1c8e7824a67f5b1ae93071f52519ad",
    )
    private val materialTypes = mapOf(0L to "PLA", 1L to "PETG", 2L to "TPU", 3L to "ABS", 4L to "ASA", 5L to "PC", 6L to "PCTG", 7L to "PP", 8L to "PA6", 9L to "PA11", 10L to "PA12", 11L to "PA66", 12L to "CPE", 13L to "TPE", 14L to "HIPS", 15L to "PHA", 16L to "PET", 17L to "PEI", 18L to "PBT", 19L to "PVB", 20L to "PVA", 21L to "PEKK", 22L to "PEEK", 23L to "BVOH", 24L to "TPC", 25L to "PPS", 26L to "PPSU", 27L to "PVC", 28L to "PEBA", 29L to "PVDF", 30L to "PPA", 31L to "PCL", 32L to "PES", 33L to "PMMA", 34L to "POM", 35L to "PPE", 36L to "PS", 37L to "PSU", 38L to "TPI", 39L to "SBS", 40L to "OBC", 41L to "EVA", 42L to "PA612")
    private val knownKeys = setOf(0L,1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,13L,14L,15L,16L,17L,18L,19L,20L,21L,22L,23L,24L,27L,28L,29L,30L,31L,32L,33L,34L,35L,36L,37L,38L,39L,40L,41L,42L,43L,44L,45L,46L,47L,48L,49L,50L,51L,52L,53L,54L,55L,56L,57L,58L,59L,60L,61L,62L)

    fun decode(payload: ByteArray): DecodeResult = try {
        val parser = CborReader(payload)
        val meta = parser.read() as? Map<*, *> ?: throw ValidationException("OpenPrintTag meta region must be a CBOR map")
        val mainOffset = (meta[0L] as? Number)?.toInt() ?: parser.offset
        if (mainOffset !in parser.offset..payload.lastIndex) throw ValidationException("OpenPrintTag main region offset is invalid")
        parser.offset = mainOffset
        val main = parser.read() as? Map<*, *> ?: throw ValidationException("OpenPrintTag main region must be a CBOR map")
        val keyed = main.entries.mapNotNull { (k, v) -> (k as? Number)?.toLong()?.let { it to v } }.toMap()
        val source = "OpenPrintTag tag (${format.sourceRevision.take(8)})"
        val brand = keyed.text(11).required("brand_name")
        val materialName = keyed.text(10)
        val type = (keyed[9] as? Number)?.toLong()?.let(materialTypes::get).orEmpty()
        val material = materialName.ifBlank { type }.required("material_name or material_type")
        val color = (keyed[19] as? ByteArray)?.takeIf { it.size in 3..4 }?.take(3)?.joinToString("") { "%02X".format(it.toInt() and 0xff) } ?: "808080"
        val diameter = when {
            keyed[61] is Number -> BigDecimal((keyed[61] as Number).toString()).movePointLeft(3)
            keyed[30] is Number -> BigDecimal((keyed[30] as Number).toString())
            else -> BigDecimal("1.75")
        }.stripTrailingZeros().toPlainString()
        val mass = number(keyed[17]) ?: number(keyed[16]) ?: 1000
        val gtin = (keyed[4] as? Number)?.let { numberText(it) }
        val sku = keyed.text(6).ifBlank { null }
        val record = FilamentRecord(
            packageId = stableId("openprinttag", payload), variantId = sku, productId = gtin,
            brand = FieldValue(brand, source), material = FieldValue(material, source), product = FieldValue(materialName, source),
            colorName = FieldValue("", source), colorHex = FieldValue(color, source), diameterMm = FieldValue(diameter, source),
            nominalMassG = FieldValue(mass, source), nozzleMinC = number(keyed[34])?.let { FieldValue(it, source) },
            nozzleMaxC = number(keyed[35])?.let { FieldValue(it, source) }, bedMinC = number(keyed[37])?.let { FieldValue(it, source) },
            bedMaxC = number(keyed[38])?.let { FieldValue(it, source) }, gtin = gtin, sku = sku,
            sourceRevision = format.sourceRevision, provenance = Provenance.CUSTOM,
            additionalColors = (20L..24L).mapNotNull { key -> (keyed[key] as? ByteArray)?.takeIf { it.size in 3..4 }?.take(3)?.joinToString("") { "%02X".format(it.toInt() and 0xff) } },
            transmissionDistance = decimal(keyed[27])?.let { FieldValue(it, source) },
        )
        validateRecord(record)
        val unknown = keyed.keys.filter { it !in knownKeys }.sorted()
        val warnings = buildList {
            if (61L !in keyed && 30L !in keyed) add("Filament diameter was absent; OpenPrintTag's 1.75 mm default was applied")
            if (61L in keyed && 30L in keyed) add("Both diameter keys were present; current micrometre key 61 was preferred")
            if (19L !in keyed) add("Primary color was absent; neutral gray was used for local review")
            if (unknown.isNotEmpty()) add("Unknown CBOR field key(s) ${unknown.joinToString()} remain preserved in the read-only source payload")
        }
        DecodeResult.Supported(record.toOpenSpoolValues(), payload.copyOf(), format.displayName, record, warnings)
    } catch (e: ValidationException) {
        DecodeResult.Rejected(e.message ?: "Malformed OpenPrintTag payload")
    }

    private fun Map<Long, Any?>.text(key: Long) = this[key] as? String ?: ""
    private fun String.required(name: String) = also { if (isBlank()) throw ValidationException("OpenPrintTag $name is missing") }
    private fun number(value: Any?): Int? = (value as? Number)?.let { runCatching { BigDecimal(it.toString()).intValueExact() }.getOrNull() }
    private fun decimal(value: Any?): String? = (value as? Number)?.let { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() }
    private fun numberText(value: Number) = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}

internal fun FilamentRecord.toOpenSpoolValues(): Map<String, JsonValue> = linkedMapOf<String, JsonValue>().apply {
    put("brand", JsonValue.Str(brand.value)); put("type", JsonValue.Str(material.value)); put("subtype", JsonValue.Str(product.value))
    put("color_hex", JsonValue.Str(colorHex.value)); put("diameter", JsonValue.Num(diameterMm.value)); put("weight", JsonValue.Num(nominalMassG.value.toString()))
    nozzleMinC?.let { put("min_temp", JsonValue.Num(it.value.toString())) }; nozzleMaxC?.let { put("max_temp", JsonValue.Num(it.value.toString())) }
    bedMinC?.let { put("bed_min_temp", JsonValue.Num(it.value.toString())) }; bedMaxC?.let { put("bed_max_temp", JsonValue.Num(it.value.toString())) }
    transmissionDistance?.let { put("transmission_distance", JsonValue.Num(it.value)) }
    if (additionalColors.isNotEmpty()) put("additional_color_hexes", JsonValue.Arr(additionalColors.map(JsonValue::Str)))
}

private fun stableId(prefix: String, bytes: ByteArray): String = "$prefix:" + MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
private fun uint(data: ByteArray, offset: Int, length: Int) = BigInteger(1, data.copyOfRange(offset, offset + length))
private fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { throw ValidationException("Tag contains invalid UTF-8") }

internal class CborReader(private val data: ByteArray, private val maxDepth: Int = 16) {
    var offset: Int = 0
    fun read(): Any? = value(0)
    private fun value(depth: Int): Any? {
        if (depth > maxDepth) fail("CBOR nesting limit exceeded")
        val initial = byte()
        val major = initial ushr 5
        val info = initial and 31
        return when (major) {
            0 -> length(info)
            1 -> -1L - length(info)
            2 -> byteString(info, depth)
            3 -> textString(info, depth)
            4 -> array(info, depth)
            5 -> map(info, depth)
            6 -> { length(info); value(depth + 1) }
            7 -> simple(info)
            else -> fail("Unsupported CBOR major type")
        }
    }
    private fun byteString(info: Int, depth: Int): ByteArray {
        if (info != 31) return take(length(info).checkedLength())
        val chunks = mutableListOf<ByteArray>()
        while (!breakAhead()) chunks += value(depth + 1) as? ByteArray ?: fail("Indefinite byte string contains a non-byte chunk")
        return chunks.fold(ByteArray(0)) { a, b -> a + b }
    }
    private fun textString(info: Int, depth: Int): String {
        if (info != 31) return decodeUtf8(take(length(info).checkedLength()))
        val out = StringBuilder()
        while (!breakAhead()) out.append(value(depth + 1) as? String ?: fail("Indefinite text string contains a non-text chunk"))
        return out.toString()
    }
    private fun array(info: Int, depth: Int): List<Any?> {
        val out = mutableListOf<Any?>()
        if (info == 31) while (!breakAhead()) out += value(depth + 1) else repeat(length(info).checkedLength()) { out += value(depth + 1) }
        return out
    }
    private fun map(info: Int, depth: Int): Map<Any?, Any?> {
        val out = linkedMapOf<Any?, Any?>()
        fun pair() { val key = value(depth + 1); if (key in out) fail("Duplicate CBOR map key"); out[key] = value(depth + 1) }
        if (info == 31) while (!breakAhead()) pair() else repeat(length(info).checkedLength()) { pair() }
        return out
    }
    private fun simple(info: Int): Any? = when (info) {
        in 0..19 -> info.toLong(); 20 -> false; 21 -> true; 22, 23 -> null
        24 -> byte().toLong(); 25 -> half(uint16()); 26 -> Float.fromBits(uint32().toInt()).toDouble(); 27 -> Double.fromBits(uint64())
        31 -> fail("Unexpected CBOR break"); else -> fail("Unsupported CBOR simple value")
    }
    private fun half(bits: Int): Double {
        val sign = if (bits and 0x8000 == 0) 1.0 else -1.0
        val exp = bits ushr 10 and 0x1f; val frac = bits and 0x3ff
        return when (exp) { 0 -> sign * Math.scalb(frac.toDouble(), -24); 31 -> if (frac == 0) sign * Double.POSITIVE_INFINITY else Double.NaN; else -> sign * Math.scalb((frac + 1024).toDouble(), exp - 25) }
    }
    private fun length(info: Int): Long = when (info) { in 0..23 -> info.toLong(); 24 -> byte().toLong(); 25 -> uint16().toLong(); 26 -> uint32(); 27 -> uint64().also { if (it < 0) fail("CBOR integer exceeds signed range") }; 31 -> fail("Indefinite length is invalid here"); else -> fail("Reserved CBOR additional information") }
    private fun Long.checkedLength(): Int = takeIf { it in 0..minOf(Int.MAX_VALUE.toLong(), data.size.toLong()) }?.toInt() ?: fail("CBOR length exceeds payload")
    private fun breakAhead(): Boolean = if (offset < data.size && (data[offset].toInt() and 0xff) == 0xff) { offset++; true } else false
    private fun take(count: Int): ByteArray { if (offset + count > data.size) fail("Unexpected end of CBOR"); return data.copyOfRange(offset, offset + count).also { offset += count } }
    private fun byte(): Int { if (offset >= data.size) fail("Unexpected end of CBOR"); return data[offset++].toInt() and 0xff }
    private fun uint16() = (byte() shl 8) or byte()
    private fun uint32() = (byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong()
    private fun uint64(): Long { var result = 0L; repeat(8) { result = (result shl 8) or byte().toLong() }; return result }
    private fun fail(message: String): Nothing = throw ValidationException("$message at offset $offset")
}
