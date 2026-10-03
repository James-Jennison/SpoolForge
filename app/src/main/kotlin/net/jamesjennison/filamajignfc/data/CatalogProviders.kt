package net.jamesjennison.filamajignfc.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import net.jamesjennison.filamajignfc.FilamentItem
import net.jamesjennison.filamajignfc.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class OfdCatalogProvider(private val dao: CatalogDao) : CatalogProvider {
    override val descriptor = CatalogProviderDescriptor("ofd", "Open Filament Database", "MIT", "OpenFilamentCollective/open-filament-database")

    override suspend fun search(query: CatalogQuery): List<CatalogCandidate> {
        val entries = linkedMapOf<String, Pair<CatalogEntry, CatalogMatchTier>>()
        query.identifiers.forEach { (scheme, value) ->
            val matches = when (scheme.uppercase()) {
                "GTIN", "EAN", "UPC", "UPC-A" -> dao.byGtin(value.trim().trimStart('0').ifEmpty { "0" }, query.limit)
                "SKU", "MPN", "ARTICLE_NUMBER" -> dao.bySku(value.trim(), query.limit)
                else -> emptyList()
            }
            matches.forEach { entries[it.packageId] = it to CatalogMatchTier.EXACT_IDENTIFIER }
        }
        val terms = query.searchTerms()
        if (terms.isNotEmpty()) {
            dao.search(ftsQuery(terms), query.limit).forEach { entry ->
                val tier = if (query.fields.matchesExactly(entry)) CatalogMatchTier.EXACT_STRUCTURED
                    else if (query.fields.hasValues()) CatalogMatchTier.PARTIAL_STRUCTURED else CatalogMatchTier.TEXT
                entries.putIfAbsent(entry.packageId, entry to tier)
            }
        } else if (query.identifiers.isEmpty()) {
            dao.browse(query.limit).forEach { entries[it.packageId] = it to CatalogMatchTier.TEXT }
        }
        return entries.values.map { (entry, tier) -> entry.toCandidate(descriptor.id, tier) }
    }

    override suspend fun profile(providerRecordId: String) = dao.byId(providerRecordId)?.toProfile(descriptor.id, EvidenceKind.CATALOG)
}

class LocalCatalogProvider(private val database: UserDatabase) : CatalogProvider {
    override val descriptor = CatalogProviderDescriptor("local", "Local records", "User-owned")

    override suspend fun search(query: CatalogQuery): List<CatalogCandidate> {
        val terms = query.searchTerms()
        val lookups = (terms + query.identifiers.map { it.second.trim() }).filter(String::isNotBlank)
        var ids:List<String>?=null
        for (lookup in lookups) {
            val found=database.canonical().searchLegacyRecordIds(lookup,query.limit)
            ids=ids?.filter { it in found } ?: found
        }
        val resolvedIds=(ids ?: database.canonical().searchLegacyRecordIds("",query.limit)).take(query.limit)
        val records=mutableListOf<CustomRecord>()
        for (id in resolvedIds) database.user().byId(id)?.let(records::add)
        return records.map { record ->
            val exactIdentifier = query.identifiers.any { (_, value) -> value.equals(record.gtin, true) || value.equals(record.articleNumber, true) }
            record.toCandidate(if (exactIdentifier) CatalogMatchTier.EXACT_IDENTIFIER else CatalogMatchTier.TEXT)
        }
    }

    override suspend fun profile(providerRecordId: String) = database.user().byId(providerRecordId)?.toProfile()
}

class CommunityCatalogProvider(private val context: Context) : CatalogProvider {
    override val descriptor = CatalogProviderDescriptor(
        "spoolmandb-community", "SpoolmanDB Community", "MIT", "Icezaza2543/SpoolmanDB-Community"
    )
    private val manifest by lazy {
        context.assets.open("community-catalog-manifest.json").bufferedReader().use { JSONObject(it.readText()) }.also {
            require(it.getString("format") == "filamajig-community-sqlite-v1")
        }
    }
    @Volatile private var installedFile: File? = null

    override suspend fun search(query: CatalogQuery): List<CatalogCandidate> {
        SQLiteDatabase.openDatabase(ensureFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val matches = linkedMapOf<String, CatalogMatchTier>()
            query.identifiers.forEach { (scheme, raw) ->
                val normalizedScheme = when (scheme.uppercase()) { "EAN", "UPC", "UPC-A" -> "GTIN"; "MPN", "ARTICLE_NUMBER" -> "SKU"; else -> scheme.uppercase() }
                val value = if (normalizedScheme == "GTIN") Gtin.normalize(raw) else raw.trim().uppercase()
                if (value != null && normalizedScheme in setOf("GTIN", "SKU")) {
                    db.rawQuery("SELECT record_id FROM identifiers WHERE scheme=? AND normalized_value=? ORDER BY record_id LIMIT ?", arrayOf(normalizedScheme, value, query.limit.toString())).use { cursor ->
                        while (cursor.moveToNext()) matches[cursor.getString(0)] = CatalogMatchTier.EXACT_IDENTIFIER
                    }
                }
            }
            val terms = query.searchTerms()
            if (terms.isNotEmpty()) {
                db.rawQuery("SELECT record_id FROM records_fts WHERE records_fts MATCH ? ORDER BY record_id LIMIT ?", arrayOf(ftsQuery(terms), query.limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) matches.putIfAbsent(cursor.getString(0), CatalogMatchTier.TEXT)
                }
            } else if (query.identifiers.isEmpty()) {
                db.rawQuery("SELECT record_id FROM records ORDER BY manufacturer,product,material,record_id LIMIT ?", arrayOf(query.limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) matches[cursor.getString(0)] = CatalogMatchTier.TEXT
                }
            }
            return matches.mapNotNull { (id, initialTier) ->
                record(db, id)?.let { row ->
                    val tier = if (initialTier == CatalogMatchTier.EXACT_IDENTIFIER) initialTier
                        else if (query.fields.matchesExactly(row.profile)) CatalogMatchTier.EXACT_STRUCTURED
                        else if (query.fields.hasValues()) CatalogMatchTier.PARTIAL_STRUCTURED else CatalogMatchTier.TEXT
                    val reasons = listOf(tier.name.lowercase().replace('_', ' '))
                    val evidence = JSONObject().put("provider", descriptor.id).put("record_id", id)
                        .put("match_reasons", JSONArray(reasons)).put("conflicting_fields", JSONArray())
                        .put("source_pointer", row.sourcePointer).put("record_sha256", row.recordSha256)
                        .put("color_selection", JSONObject().put("rule", "explicit color_hex, then first color_hexes value")
                            .put("primary", row.allColorHexes.firstOrNull()).put("all", JSONArray(row.allColorHexes)))
                        .toString()
                    CatalogCandidate(row.profile, descriptor.id, id, reasons, tier, evidence = evidence)
                }
            }
        }
    }

    override suspend fun profile(providerRecordId: String): FilamentProfile? =
        SQLiteDatabase.openDatabase(ensureFile().path, null, SQLiteDatabase.OPEN_READONLY).use { record(it, providerRecordId)?.profile }

    private fun record(db: SQLiteDatabase, id: String): CommunityRow? = db.rawQuery(
        "SELECT record_id,manufacturer,product,material,color_hex,additional_color_hexes,diameter_mm,filament_mass_g,spool_mass_g,nozzle_min_c,nozzle_max_c,bed_min_c,bed_max_c,identifiers_json,source_pointer,record_sha256 FROM records WHERE record_id=?",
        arrayOf(id)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toCommunityRow(manifest.getString("revision")) else null }

    @Synchronized private fun ensureFile(): File {
        installedFile?.takeIf(File::isFile)?.let { return it }
        val expected = manifest.getString("sha256")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val file = File(context.filesDir, "community-$expected.sqlite")
        if (file.isFile && file.sha256() == expected) return file.also { installedFile = it }
        val temporary = File.createTempFile("community-", ".tmp", context.filesDir)
        try {
            val limit = manifest.getLong("uncompressed_bytes")
            require(limit in 1..150_000_000)
            GZIPInputStream(context.assets.open("community-catalog.sqlite.gzip")).use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) { val read = input.read(buffer); if (read < 0) break; count += read; require(count <= limit); output.write(buffer, 0, read) }
                require(count == limit)
            } }
            require(temporary.sha256() == expected) { "Bundled Community catalog checksum mismatch" }
            check(temporary.renameTo(file)) { "Could not install Community catalog" }
            return file.also { installedFile = it }
        } finally { temporary.delete() }
    }
}

private data class CommunityRow(
    val profile: FilamentProfile,
    val allColorHexes: List<String>,
    val sourcePointer: String,
    val recordSha256: String,
)

class MergedGtinCatalogProvider(private val index: GtinIndex) : CatalogProvider {
    override val descriptor = CatalogProviderDescriptor("merged-gtin", "Merged exact GTIN index", "MIT", "OFD + OpenPrintTag + SpoolmanDB Community")
    override suspend fun search(query: CatalogQuery): List<CatalogCandidate> {
        val raw = query.identifiers.firstOrNull { it.first.uppercase() in setOf("GTIN", "EAN", "UPC", "UPC-A") }?.second ?: return emptyList()
        return index.lookup(raw).take(query.limit).map { item ->
            val source = runCatching { JSONObject(item.barcodeEvidence).getJSONObject("snapshot").getString("source") }.getOrDefault("Merged GTIN")
            val providerId = when (source) { "OFD" -> "ofd"; "OpenPrintTag" -> "openprinttag"; "SpoolmanDB Community" -> "spoolmandb-community"; else -> descriptor.id }
            CatalogCandidate(item.toProfile(providerId), providerId, item.entry.packageId, listOf("exact GTIN"), CatalogMatchTier.EXACT_IDENTIFIER,
                runCatching { JSONObject(item.barcodeEvidence).getJSONArray("conflicting_fields").strings().toSet() }.getOrDefault(emptySet()), item.barcodeEvidence)
        }
    }
    override suspend fun profile(providerRecordId: String): FilamentProfile? = index.lookupByPackageId(providerRecordId)?.let { item ->
        val source = runCatching { JSONObject(item.barcodeEvidence).getJSONObject("snapshot").getString("source") }.getOrDefault("Merged GTIN")
        val providerId = when (source) { "OFD" -> "ofd"; "OpenPrintTag" -> "openprinttag"; "SpoolmanDB Community" -> "spoolmandb-community"; else -> descriptor.id }
        item.toProfile(providerId)
    }
}

fun CatalogCandidate.toFilamentItem(): FilamentItem {
    val profile = profile
    fun <T> value(observed: ObservedValue<T>?) = observed?.value
    val gtin = profile.identifiers.firstOrNull { it.scheme == "GTIN" }?.value
    val sku = profile.identifiers.firstOrNull { it.scheme == "SKU" }?.value
    val entry = CatalogEntry(providerRecordId, "", "", profile.brand.value, profile.material.value, value(profile.productLine).orEmpty(),
        value(profile.colorName).orEmpty(), value(profile.colorHex).orEmpty(), value(profile.diameterMm)?.stripTrailingZeros()?.toPlainString().orEmpty(),
        value(profile.nominalFilamentMassG) ?: 0, value(profile.nozzleTemperature)?.minimumC, value(profile.nozzleTemperature)?.maximumC,
        value(profile.bedTemperature)?.minimumC, value(profile.bedTemperature)?.maximumC, gtin, sku,
        profile.brand.source.revision.orEmpty(), "")
    val fields = mapOf("brand" to profile.brand.source, "material" to profile.material.source, "product" to profile.productLine?.source,
        "colorName" to profile.colorName?.source, "colorHex" to profile.colorHex?.source, "diameter" to profile.diameterMm?.source,
        "mass" to profile.nominalFilamentMassG?.source, "nozzleMin" to profile.nozzleTemperature?.source,
        "nozzleMax" to profile.nozzleTemperature?.source, "bedMin" to profile.bedTemperature?.source, "bedMax" to profile.bedTemperature?.source)
    val sources = fields.mapValues { (_, source) -> source?.let { "${it.provider} ${it.revision.orEmpty()} · ${it.recordId.orEmpty()} · ${it.kind.name.lowercase()}" }.orEmpty() }
    val candidateEvidence = evidence ?: JSONObject().put("provider", providerId).put("record_id", providerRecordId).put("match_reasons", JSONArray(matchReasons))
        .put("conflicting_fields", JSONArray(conflictingFields.toList().sorted())).toString()
    return FilamentItem(entry, if (providerId == "local") Provenance.CUSTOM else Provenance.CATALOG, sources, barcodeEvidence = candidateEvidence)
}

private fun CatalogEntry.toCandidate(providerId: String, tier: CatalogMatchTier) =
    CatalogCandidate(toProfile(providerId, EvidenceKind.CATALOG), providerId, packageId, listOf(tier.name.lowercase().replace('_', ' ')), tier)

private fun CatalogEntry.toProfile(providerId: String, kind: EvidenceKind): FilamentProfile {
    val source = SourceRef(providerId, packageId, sourceRevision, kind = kind)
    fun <T> observed(value: T?) = value?.let { ObservedValue(it, source) }
    return FilamentProfile("$providerId:$packageId", brand = ObservedValue(brand, source), productLine = observed(product), material = ObservedValue(material, source),
        colorName = observed(colorName.takeIf(String::isNotBlank)), colorHex = observed(colorHex.takeIf(String::isNotBlank)),
        diameterMm = observed(diameterMm.toBigDecimalOrNull()), nominalFilamentMassG = observed(massG.takeIf { it > 0 }),
        nozzleTemperature = if (nozzleMinC != null || nozzleMaxC != null) observed(TemperatureRange(nozzleMinC, nozzleMaxC)) else null,
        bedTemperature = if (bedMinC != null || bedMaxC != null) observed(TemperatureRange(bedMinC, bedMaxC)) else null,
        identifiers = listOfNotNull(gtin?.let { ExternalIdentifier("GTIN", it, source = source) }, sku?.let { ExternalIdentifier("SKU", it, source = source) }))
}

private fun CustomRecord.toProfile(): FilamentProfile {
    fun source(fieldSource: String?, field: String) = SourceRef(fieldSource?.takeIf(String::isNotBlank) ?: "local", "$id#$field", sourceRevision, kind = if (fieldSource == "User") EvidenceKind.USER else EvidenceKind.CATALOG)
    fun <T> observed(value: T?, provenance: String?, field: String) = value?.let { ObservedValue(it, source(provenance, field)) }
    return FilamentProfile("local:$id", brand = ObservedValue(brand, source(brandSource, "brand")), productLine = observed(product, productSource, "product"),
        material = ObservedValue(material, source(materialSource, "material")), colorName = observed(colorName.takeIf(String::isNotBlank), colorNameSource, "colorName"),
        colorHex = observed(colorHex.takeIf(String::isNotBlank), colorHexSource, "colorHex"), diameterMm = observed(diameterMm.toBigDecimalOrNull(), diameterSource, "diameter"),
        nominalFilamentMassG = observed(massG.takeIf { it > 0 }, massSource, "mass"),
        nozzleTemperature = if (nozzleMinC != null || nozzleMaxC != null) observed(TemperatureRange(nozzleMinC, nozzleMaxC), nozzleMinSource, "nozzle") else null,
        bedTemperature = if (bedMinC != null || bedMaxC != null) observed(TemperatureRange(bedMinC, bedMaxC), bedMinSource, "bed") else null,
        identifiers = listOfNotNull(gtin?.let { ExternalIdentifier("GTIN", it, source = source(null, "gtin")) }, articleNumber?.let { ExternalIdentifier("SKU", it, source = source(null, "sku")) }))
}

private fun CustomRecord.toCandidate(tier: CatalogMatchTier) = CatalogCandidate(toProfile(), "local", id, listOf(tier.name.lowercase().replace('_', ' ')), tier)

private fun Cursor.toCommunityRow(revision: String): CommunityRow {
    fun text(index: Int) = getString(index)
    fun nullableInt(index: Int) = if (isNull(index)) null else getInt(index)
    val id = text(0); val source = SourceRef("spoolmandb-community", id, revision, kind = EvidenceKind.COMMUNITY)
    fun <T> observed(value: T?) = value?.let { ObservedValue(it, source) }
    val identifiers = JSONArray(text(13)).objects().map { ExternalIdentifier(it.getString("scheme"), it.getString("value"), source = source) }
    val profile = FilamentProfile("spoolmandb-community:$id", manufacturer = observed(text(1)), brand = ObservedValue(text(1), source), productLine = observed(text(2)),
        material = ObservedValue(text(3), source), colorHex = observed(text(4).takeIf(String::isNotBlank)), diameterMm = observed(text(6).toBigDecimal()),
        nominalFilamentMassG = observed(getInt(7)), nominalSpoolMassG = observed(nullableInt(8)),
        nozzleTemperature = if (!isNull(9) || !isNull(10)) observed(TemperatureRange(nullableInt(9), nullableInt(10))) else null,
        bedTemperature = if (!isNull(11) || !isNull(12)) observed(TemperatureRange(nullableInt(11), nullableInt(12))) else null, identifiers = identifiers)
    val colors = listOf(text(4)) + text(5).split(',')
    return CommunityRow(profile, colors.filter(String::isNotBlank).distinct(), text(14), text(15))
}

private fun FilamentItem.toProfile(providerId: String): FilamentProfile = entry.toProfile(providerId, EvidenceKind.CATALOG)
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun File.sha256() = inputStream().use { input -> val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest().joinToString("") { "%02x".format(it) } }
private fun CatalogQuery.searchTerms() = buildList {
    text?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank)?.let(::addAll)
    listOf(fields.brand, fields.product, fields.material, fields.colorName).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.let(::addAll)
}.take(8)
private fun ftsQuery(terms: List<String>) = terms.joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
private fun CatalogStructuredFields.hasValues() = listOf(brand, product, material, colorName, diameterMm, nominalMassG?.toString()).any { !it.isNullOrBlank() }
private fun CatalogStructuredFields.matchesExactly(entry: CatalogEntry): Boolean {
    val diameter = diameterMm
    return brand?.equals(entry.brand, true) != false && product?.equals(entry.product, true) != false && material?.equals(entry.material, true) != false &&
        colorName?.equals(entry.colorName, true) != false && (diameter == null || diameter.toBigDecimalOrNull()?.compareTo(entry.diameterMm.toBigDecimalOrNull()) == 0) &&
        (nominalMassG == null || nominalMassG == entry.massG)
}
private fun CatalogStructuredFields.matchesExactly(profile: FilamentProfile): Boolean {
    val diameter = diameterMm
    return brand?.equals(profile.brand.value, true) != false && product?.equals(profile.productLine?.value, true) != false && material?.equals(profile.material.value, true) != false &&
        colorName?.equals(profile.colorName?.value, true) != false && (diameter == null || diameter.toBigDecimalOrNull()?.compareTo(profile.diameterMm?.value) == 0) &&
        (nominalMassG == null || nominalMassG == profile.nominalFilamentMassG?.value)
}
