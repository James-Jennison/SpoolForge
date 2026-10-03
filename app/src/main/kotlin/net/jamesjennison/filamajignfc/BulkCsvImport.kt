package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.Gtin

data class BulkCsvRecord(val row: Int, val seed: CustomSeed, val warnings: List<String>)

internal fun parseBulkCsv(text: String): List<BulkCsvRecord> {
    require(text.toByteArray().size <= 256_000) { "Bulk CSV is limited to 256 KB" }
    val delimiter = if (text.lineSequence().firstOrNull().orEmpty().contains('\t')) '\t' else ','
    val rows = parseDelimited(text, delimiter)
    require(rows.size >= 2) { "Bulk CSV needs a header and at least one record" }
    require(rows.size <= 101) { "Bulk CSV supports at most 100 records" }
    val headers = rows.first().mapIndexed { index, value ->
        value.trim().removePrefix(if (index == 0) "\uFEFF" else "").lowercase()
    }
    require(headers.none(String::isBlank) && headers.distinct().size == headers.size) { "Bulk CSV headers must be unique and non-empty" }
    val supported = setOf("brand", "material", "color", "product", "color_hex", "diameter_mm", "weight_g", "nozzle_min_c", "nozzle_max_c", "bed_min_c", "bed_max_c", "gtin", "sku", "transmission_distance")
    val unknown = headers.filter { it !in supported }
    require(unknown.isEmpty()) { "Unsupported bulk CSV header(s): ${unknown.joinToString()}" }
    fun required(name: String) = require(name in headers) { "Bulk CSV requires a $name column" }
    required("brand"); required("material"); required("color")
    return rows.drop(1).mapIndexed { index, fields ->
        val rowNumber = index + 2
        require(fields.size == headers.size) { "Bulk CSV row $rowNumber has ${fields.size} fields; expected ${headers.size}" }
        val values = headers.zip(fields.map(String::trim)).toMap()
        require(values.getValue("brand").isNotBlank()) { "Bulk CSV row $rowNumber has no brand" }
        require(values.getValue("material").isNotBlank()) { "Bulk CSV row $rowNumber has no material" }
        require(values.getValue("color").isNotBlank()) { "Bulk CSV row $rowNumber has no color" }
        val rawGtin = values["gtin"].orEmpty()
        val gtin = rawGtin.takeIf(String::isNotBlank)?.let { value ->
            requireNotNull(Gtin.normalize(value)) { "Bulk CSV row $rowNumber has an invalid GTIN" }
        }
        val diameter = values["diameter_mm"].orEmpty().ifBlank { DEFAULT_DIAMETER_MM }
        val mass = values["weight_g"].orEmpty().ifBlank { DEFAULT_NOMINAL_MASS_G }
        val warnings = buildList {
            if (values["diameter_mm"].isNullOrBlank()) add("Diameter defaulted to $DEFAULT_DIAMETER_MM mm")
            if (values["weight_g"].isNullOrBlank()) add("Weight defaulted to $DEFAULT_NOMINAL_MASS_G g")
        }
        val source = "Bulk CSV row $rowNumber"
        val fieldSources = mapOf(
            "gtin" to source, "articleNumber" to source,
            "brand" to source, "material" to source, "product" to source, "colorName" to source,
            "colorHex" to source, "diameter" to if (values["diameter_mm"].isNullOrBlank()) ASSUMED_DEFAULT_SOURCE else source,
            "mass" to if (values["weight_g"].isNullOrBlank()) ASSUMED_DEFAULT_SOURCE else source,
            "nozzleMin" to source, "nozzleMax" to source, "bedMin" to source, "bedMax" to source,
            "transmissionDistance" to source,
        )
        val seed = CustomSeed(
            gtin = gtin, articleNumber = values["sku"]?.takeIf(String::isNotBlank),
            brand = values.getValue("brand"), material = values.getValue("material"), product = values["product"].orEmpty(),
            color = values.getValue("color"), hex = values["color_hex"].orEmpty().removePrefix("#"), diameter = diameter, mass = mass,
            nozzleMin = values["nozzle_min_c"].orEmpty(), nozzleMax = values["nozzle_max_c"].orEmpty(),
            bedMin = values["bed_min_c"].orEmpty(), bedMax = values["bed_max_c"].orEmpty(), transmissionDistance = values["transmission_distance"].orEmpty(),
            sourceRevision = "bulk-csv", sources = fieldSources,
        )
        BulkCsvRecord(rowNumber, seed, warnings)
    }
}

private fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var quoteClosed = false
    var index = 0
    fun finishField() { require(field.length <= 4096) { "Bulk CSV field exceeds 4096 characters" }; row += field.toString(); field.clear() }
    fun finishRow() { finishField(); if (row.any(String::isNotBlank)) rows += row; row = mutableListOf() }
    while (index < text.length) {
        val char = text[index]
        when {
            quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> { field.append('"'); index++ }
            quoted && char == '"' -> { quoted = false; quoteClosed = true }
            !quoted && char == '"' -> {
                require(field.isEmpty() && !quoteClosed) { "Bulk CSV quote must begin a field" }
                quoted = true
            }
            !quoted && char == delimiter -> { finishField(); quoteClosed = false }
            !quoted && char == '\n' -> { finishRow(); quoteClosed = false }
            !quoted && char == '\r' -> if (index + 1 >= text.length || text[index + 1] != '\n') { finishRow(); quoteClosed = false }
            else -> {
                require(!quoteClosed) { "Bulk CSV has characters after a closing quote" }
                field.append(char)
            }
        }
        index++
    }
    require(!quoted) { "Bulk CSV has an unterminated quoted field" }
    if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
    return rows
}
