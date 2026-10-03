package net.jamesjennison.filamajignfc.core

import java.math.RoundingMode

/**
 * ELEGOO CANVAS' raw Type 2 tag filament block.
 *
 * Factory tags place this block at absolute tag byte 0x40 (NTAG page 16).
 * The codec intentionally emits only that one vendor format. It does not add an
 * OpenSpool record or otherwise attempt to make a dual-format tag.
 */
object ElegooCanvasTagCodec : FilamentTagCodec {
    const val TARGET_PAGE = 16
    const val PAYLOAD_BYTES = 64
    const val INSPECTED_USER_BYTES = 504
    const val USER_AREA_OFFSET = (TARGET_PAGE - 4) * 4
    private const val FILAMENT_DATA_BYTES = 41

    override val format = TagFormat(
        id = "elegoo-canvas-1.0",
        displayName = "ELEGOO CANVAS",
        mimeType = "application/vnd.elegoo.canvas-raw",
        protocolVersion = "1.0",
        compatibilityTarget = "ELEGOO CANVAS; Snapmaker U1 only through PAXX/OpenRFID Elegoo support",
        transport = TagTransport.NTAG_RAW,
    )

    private val fields = setOf(
        "manufacturer", "material", "material_subtype", "color", "nozzle_range", "diameter", "weight",
    )

    /** Local fields that no CANVAS reader can receive. `brand` is lost because the wire manufacturer word is a fixed marker. */
    val OMITTED_FIELDS = setOf("brand", "colorName", "bedTemperatureRange", "additionalColors", "transmissionDistance", "sku", "gtin")

    /** Provenance recorded on a decoded brand: the value is a format convention, not data read from the tag. */
    const val BRAND_SOURCE = "ELEGOO CANVAS format marker (fixed 0xEEEEEEEE, not a brand field)"

    override fun intent(record: FilamentRecord): OpenSpoolIntent {
        val payload = encodeCanvasPayload(record)
        return OpenSpoolIntent(
            record = record,
            profile = OpenSpoolProfile.CANONICAL,
            payload = payload,
            omittedFields = OMITTED_FIELDS,
            codecId = format.id,
            ndefRecords = listOf(TagRecord(-1, format.id, payload)),
        )
    }

    override fun encode(record: FilamentRecord) = intent(record).let {
        EncodedTag(it.payload, format, it.omittedFields, fields, it.ndefRecords)
    }

    override fun decode(payload: ByteArray): DecodeResult {
        if (payload.size != PAYLOAD_BYTES) return DecodeResult.Rejected("ELEGOO CANVAS data must be a $PAYLOAD_BYTES-byte raw filament block")
        if (payload[0] != 0x36.toByte() || payload.copyOfRange(1, 5).any { it != 0xEE.toByte() }) {
            return DecodeResult.Rejected("Not an ELEGOO CANVAS filament block")
        }
        fun u16(offset: Int) = ((payload[offset].toInt() and 255) shl 8) or (payload[offset + 1].toInt() and 255)
        val subtype = u16(12)
        val material = MATERIAL_SUBTYPES[subtype]
            ?: return DecodeResult.ReadOnly("Unknown ELEGOO CANVAS material/subtype code %04X".format(subtype), payload.copyOf())
        val color = payload.copyOfRange(16, 19).joinToString("") { "%02X".format(it.toInt() and 255) }
        return supportedRecord(
            raw = payload,
            prefix = "elegoo-canvas",
            source = "ELEGOO CANVAS tag",
            brand = "ELEGOO",
            brandSource = BRAND_SOURCE,
            material = material.family,
            product = material.product,
            color = color,
            diameter = u16(28).toBigDecimal().movePointLeft(2).toPlainString(),
            mass = u16(30),
            nozzleMin = u16(20),
            nozzleMax = u16(22),
            bedMin = null,
            bedMax = null,
            warnings = listOf(
                "ELEGOO CANVAS tags do not carry a brand: the fixed 0xEEEEEEEE manufacturer marker identifies the format, not the filament maker, so the brand is reported as ELEGOO by convention",
                "ELEGOO CANVAS tags do not carry bed temperatures or color names",
            ),
        )
    }

    fun encodeCanvasPayload(record: FilamentRecord): ByteArray {
        validateRecord(record)
        // The 0xEEEEEEEE manufacturer marker is a format constant that every CANVAS reader requires; it cannot
        // carry the profile's brand. The brand is therefore reported as an omitted field rather than rejected.
        val family = materialFamily(record.material.value)
        val material = materialCode(family, "${record.material.value} ${record.product.value}")
        val min = record.nozzleMinC?.value ?: throw ValidationException("ELEGOO CANVAS requires a nozzle minimum temperature")
        val max = record.nozzleMaxC?.value ?: throw ValidationException("ELEGOO CANVAS requires a nozzle maximum temperature")
        val diameter = record.diameterMm.value.toBigDecimal().multiply(java.math.BigDecimal(100)).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
        val weight = record.nominalMassG.value
        if (diameter !in 1..65535) throw ValidationException("ELEGOO CANVAS diameter must fit hundredths of a millimetre")
        if (weight !in 1..65535) throw ValidationException("ELEGOO CANVAS weight must fit 16 bits")
        val out = ByteArray(PAYLOAD_BYTES)
        fun put16(offset: Int, value: Int) { out[offset] = (value ushr 8).toByte(); out[offset + 1] = value.toByte() }
        fun put32(offset: Int, value: Int) { out[offset] = (value ushr 24).toByte(); out[offset + 1] = (value ushr 16).toByte(); out[offset + 2] = (value ushr 8).toByte(); out[offset + 3] = value.toByte() }
        out[0] = 0x36
        put32(1, 0xEEEEEEEE.toInt())
        put32(8, MATERIAL_MAIN_CODES.getValue(family))
        put16(12, material.code)
        val rgb = normalizeHex(record.colorHex.value).toInt(16)
        out[16] = (rgb ushr 16).toByte(); out[17] = (rgb ushr 8).toByte(); out[18] = rgb.toByte(); out[19] = 0xFF.toByte()
        put16(20, min); put16(22, max); put16(28, diameter); put16(30, weight)
        check(out.copyOfRange(FILAMENT_DATA_BYTES, PAYLOAD_BYTES).all { it == 0.toByte() })
        return out
    }

    /**
     * The one-tag rule is deliberately exclusive: a CANVAS write may not leave
     * an NDEF/OpenSpool or other competing payload elsewhere in the inspected
     * Type 2 user area. Non-zero bytes are rejected, never cleared implicitly.
     */
    fun hasCompetingUserData(userArea: ByteArray): Boolean {
        require(userArea.size == INSPECTED_USER_BYTES)
        val emptyNdefPrefix = byteArrayOf(0x03, 0x00, 0xFE.toByte(), 0x00)
        val factoryEmptyNdef = userArea.copyOfRange(0, emptyNdefPrefix.size).contentEquals(emptyNdefPrefix)
        return userArea.indices.any { index ->
            val allowedEmptyNdefByte = factoryEmptyNdef && index < emptyNdefPrefix.size
            index !in USER_AREA_OFFSET until USER_AREA_OFFSET + PAYLOAD_BYTES && !allowedEmptyNdefByte && userArea[index] != 0.toByte()
        }
    }

    private fun materialFamily(value: String): String = Regex("(?:^|[^A-Z0-9])(PETG|PLA|ABS|TPU|PVA)(?:$|[^A-Z0-9])")
        .find(value.trim().uppercase())?.groupValues?.get(1)
        ?: throw ValidationException("ELEGOO CANVAS cannot map material '$value'. Choose a format that supports this material.")

    private fun materialCode(family: String, description: String): CanvasMaterial {
        val text = description.uppercase()
        val code = when (family) {
            "PLA" -> when {
                "RAPID" in text -> 0x000A; "SILK" in text -> 0x0003
                "CARBON" in text || "-CF" in text -> 0x0004; "MATTE" in text -> 0x0006
                "FLUO" in text -> 0x0007; "WOOD" in text -> 0x0008; "MARBLE" in text -> 0x000B
                "GALAXY" in text -> 0x000C; "RED COPPER" in text -> 0x000D; "SPARKLE" in text -> 0x000E
                "PLA+" in text -> 0x0001; "PRO" in text -> 0x0002; "BASIC" in text -> 0x0009
                else -> 0x0000
            }
            "PETG" -> when { "RAPID" in text -> 0x0105; "-CF" in text || "CARBON" in text -> 0x0101; "-GF" in text || "GLASS" in text -> 0x0102; "PRO" in text -> 0x0103; "TRANSLUCENT" in text || "TRANSPARENT" in text -> 0x0104; else -> 0x0100 }
            "ABS" -> if ("-GF" in text || "GLASS" in text) 0x0201 else 0x0200
            "TPU" -> when { "RAPID" in text && "95A" in text -> 0x0302; "95A" in text -> 0x0301; else -> 0x0300 }
            "PVA" -> 0x0700
            else -> error("Unsupported CANVAS family")
        }
        return MATERIAL_SUBTYPES.getValue(code)
    }

    private data class CanvasMaterial(val code: Int, val family: String, val product: String)

    private val MATERIAL_SUBTYPES = buildMap {
        fun add(family: String, familyId: Int, values: Map<Int, String>) = values.forEach { (modifier, product) ->
            val code = (familyId shl 8) or modifier
            put(code, CanvasMaterial(code, family, product))
        }
        add("PLA", 0x00, mapOf(0x00 to "PLA", 0x01 to "PLA+", 0x02 to "PLA Pro", 0x03 to "PLA Silk", 0x04 to "PLA-CF", 0x05 to "PLA Carbon", 0x06 to "PLA Matte", 0x07 to "PLA Fluo", 0x08 to "PLA Wood", 0x09 to "PLA Basic", 0x0A to "PLA Rapid+", 0x0B to "PLA Marble", 0x0C to "PLA Galaxy", 0x0D to "PLA Red Copper", 0x0E to "PLA Sparkle"))
        add("PETG", 0x01, mapOf(0x00 to "PETG", 0x01 to "PETG-CF", 0x02 to "PETG-GF", 0x03 to "PETG Pro", 0x04 to "PETG Translucent", 0x05 to "PETG Rapid"))
        add("ABS", 0x02, mapOf(0x00 to "ABS", 0x01 to "ABS-GF"))
        add("TPU", 0x03, mapOf(0x00 to "TPU", 0x01 to "TPU 95A", 0x02 to "TPU Rapid 95A"))
        add("PVA", 0x07, mapOf(0x00 to "PVA"))
    }

    private val MATERIAL_MAIN_CODES = mapOf(
        "PLA" to 0x00807665, "PETG" to 0x80698471.toInt(), "ABS" to 0x00656683,
        "TPU" to 0x00848085, "PVA" to 0x00808665,
    )
}
