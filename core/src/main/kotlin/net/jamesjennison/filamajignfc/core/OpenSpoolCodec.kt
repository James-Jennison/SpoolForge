package net.jamesjennison.filamajignfc.core

object OpenSpoolCodec {
    const val MIME = "application/json"
    private val canonical = setOf("protocol","version","type","color_hex","brand","min_temp","max_temp")
    private val paxx = canonical + setOf("subtype","bed_min_temp","bed_max_temp","diameter","weight","additional_color_hexes","transmission_distance")

    fun encode(record: FilamentRecord, profile: OpenSpoolProfile, codecId:String = if(profile==OpenSpoolProfile.PAXX) "openspool-paxx-u1-1.0" else "openspool-1.0"): OpenSpoolIntent {
        validateRecord(record)
        if (profile == OpenSpoolProfile.PAXX && record.additionalColors.size > 4) throw ValidationException("PAXX supports at most four additional colors; this record remains available locally")
        val type = if (profile == OpenSpoolProfile.PAXX) paxxMainType(record.material.value) else record.material.value
        val fields = linkedMapOf<String,Any>("protocol" to "openspool", "version" to "1.0", "type" to type, "color_hex" to normalizeHex(record.colorHex.value), "brand" to record.brand.value)
        record.nozzleMinC?.let { fields["min_temp"] = if (profile == OpenSpoolProfile.PAXX) it.value else it.value.toString() }
        record.nozzleMaxC?.let { fields["max_temp"] = if (profile == OpenSpoolProfile.PAXX) it.value else it.value.toString() }
        if(profile == OpenSpoolProfile.PAXX) {
            fields["subtype"] = paxxSubtype(record.material.value, record.product.value)
            record.bedMinC?.let { fields["bed_min_temp"] = it.value }
            record.bedMaxC?.let { fields["bed_max_temp"] = it.value }
            fields["diameter"] = record.diameterMm.value.toBigDecimal().stripTrailingZeros()
            fields["weight"] = record.nominalMassG.value
            if(record.additionalColors.isNotEmpty()) fields["additional_color_hexes"] = record.additionalColors.map(::normalizeHex)
            record.transmissionDistance?.let { fields["transmission_distance"] = it.value.toBigDecimal().stripTrailingZeros() }
        }
        val mapping = linkedMapOf("brand" to "brand","material" to "type","product" to "subtype","color" to "color_hex","diameter" to "diameter","mass" to "weight","nozzleMin" to "min_temp","nozzleMax" to "max_temp","bedMin" to "bed_min_temp","bedMax" to "bed_max_temp","additionalColors" to "additional_color_hexes","packageId" to null,"gtin" to null,"sku" to null,"provenance" to null)
        if (record.transmissionDistance != null) mapping["transmissionDistance"] = "transmission_distance"
        val omitted = mapping.filterValues { it==null||it !in fields }.keys.toMutableSet()
        if (profile == OpenSpoolProfile.PAXX && record.product.value.isNotBlank() && fields["subtype"] != record.product.value) omitted += "product"
        return OpenSpoolIntent(record, profile, encodeJsonObject(fields), omitted,codecId)
    }

    fun decode(payload: ByteArray): DecodeResult {
      return try {
        val root = StrictJson().parse(payload) as? JsonValue.Obj ?: return DecodeResult.Rejected("OpenSpool payload must be an object")
        if(root.string("protocol") != "openspool") return DecodeResult.Rejected("Not an OpenSpool payload")
        val version=root.string("version") ?: return DecodeResult.Rejected("OpenSpool version must be a string")
        if(version != "1.0") return DecodeResult.ReadOnly("Unsupported OpenSpool version", payload.copyOf())
        val known = paxx + "alpha"
        if(root.values.keys.any { it !in known }) return DecodeResult.ReadOnly("Unknown fields must be preserved read-only", payload.copyOf())
        if("alpha" in root.values) return DecodeResult.ReadOnly("Alpha cannot yet be edited without verified lossless semantics",payload.copyOf())
        fun requiredText(key:String):String {
            val value=root.values[key] as? JsonValue.Str ?: throw ValidationException("$key must be a string")
            if(value.value.isBlank() || value.value.length>200 || value.value.any(Char::isISOControl)) throw ValidationException("$key is empty or invalid")
            return value.value
        }
        requiredText("type"); normalizeHex(requiredText("color_hex")); requiredText("brand")
        if("subtype" in root.values) requiredText("subtype")
        if("diameter" in root.values) {
            val raw=numberText(root.values["diameter"]) ?: throw ValidationException("diameter must be a decimal-millimetre number")
            val diameter=raw.toBigDecimalOrNull() ?: throw ValidationException("diameter must be a decimal-millimetre number")
            if(diameter<=java.math.BigDecimal.ZERO||diameter>java.math.BigDecimal.TEN) throw ValidationException("diameter must be decimal millimetres; legacy 175 is invalid")
        }
        fun temp(key:String):Int? {
            if(key !in root.values)return null
            val raw=numberText(root.values[key]) ?: throw ValidationException("$key must be an integer")
            if(!raw.matches(Regex("(?:0|[1-9][0-9]{0,2})"))) throw ValidationException("$key must be an integer from 0 to 500")
            return raw.toInt().also{if(it>500)throw ValidationException("$key exceeds 500 C")}
        }
        fun range(a:String,b:String){val min=temp(a);val max=temp(b);if(min!=null&&max!=null&&min>max)throw ValidationException("Temperature range is inverted")}
        range("min_temp","max_temp");range("bed_min_temp","bed_max_temp")
        if("weight" in root.values){val weight=root.int("weight")?:throw ValidationException("weight must be an integer number");if(weight !in 1..100_000)throw ValidationException("weight is outside the supported range")}
        if("transmission_distance" in root.values){val td=(root.values["transmission_distance"] as? JsonValue.Num)?.lexical?.toBigDecimalOrNull()?:throw ValidationException("transmission_distance must be a number");if(td<java.math.BigDecimal("0.1")||td>java.math.BigDecimal("100"))throw ValidationException("transmission_distance must be from 0.1 to 100")}
        if("additional_color_hexes" in root.values){val colors=(root.values["additional_color_hexes"] as? JsonValue.Arr)?.values?:throw ValidationException("additional_color_hexes must be an array");if(colors.size>4)throw ValidationException("At most four additional colors are supported");colors.forEach{normalizeHex((it as? JsonValue.Str)?.value?:throw ValidationException("Additional colors must be strings"))}}
        DecodeResult.Supported(root.values, payload.copyOf())
      } catch(e: ValidationException) { DecodeResult.Rejected(e.message ?: "Malformed JSON") }
    }

    fun semanticallyMatches(intent: OpenSpoolIntent, actual: ByteArray): Boolean {
        val expected = decode(intent.payloadUnsafe()) as? DecodeResult.Supported ?: return false
        val observed = decode(actual) as? DecodeResult.Supported ?: return false
        return expected.values == observed.values
    }

    private fun numberText(value: JsonValue?): String? = when (value) {
        is JsonValue.Num -> value.lexical
        is JsonValue.Str -> value.value
        else -> null
    }

    private fun paxxMainType(material: String): String {
        val normalized = material.trim().uppercase()
        return Regex("(?:^|[^A-Z0-9])(PETG|PLA|ABS|TPU|PVA)(?:$|[^A-Z0-9])").find(normalized)?.groupValues?.get(1)
            ?: throw ValidationException("PAXX/U1 cannot map material '$material'. Use Canonical for this record or select a supported U1 material.")
    }

    private fun paxxSubtype(material: String, product: String): String {
        val description = "$material $product".uppercase()
        return when {
            "95A" in description && ("HIGH FLOW" in description || Regex("(?:^|\\s)HF(?:\\s|$)").containsMatchIn(description)) -> "95A HF"
            "95A" in description -> "95A"
            "SNAPSPEED" in description -> "SnapSpeed"
            "TRANSPARENT" in description || "CLEAR" in description -> "Transparent"
            "RAPID" in description -> "Rapid"
            "FLEXIBLE" in description -> "Flexible"
            "MATTE" in description -> "Matte"
            "SILK" in description -> "Silk"
            "SUPPORT" in description -> "Support"
            "HIGH FLOW" in description || Regex("(?:^|\\s)HF(?:\\s|$)").containsMatchIn(description) -> "HF"
            else -> "Basic"
        }
    }
}
