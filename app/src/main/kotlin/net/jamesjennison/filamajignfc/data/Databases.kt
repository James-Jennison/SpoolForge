package net.jamesjennison.filamajignfc.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import net.jamesjennison.filamajignfc.core.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

@Entity(tableName = "catalog_entries")
data class CatalogEntry(
    @PrimaryKey @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val brand: String, val material: String, val product: String,
    @ColumnInfo(name = "color_name") val colorName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    @ColumnInfo(name = "diameter_mm") val diameterMm: String,
    @ColumnInfo(name = "mass_g") val massG: Int,
    @ColumnInfo(name = "nozzle_min_c") val nozzleMinC: Int?,
    @ColumnInfo(name = "nozzle_max_c") val nozzleMaxC: Int?,
    @ColumnInfo(name = "bed_min_c") val bedMinC: Int?,
    @ColumnInfo(name = "bed_max_c") val bedMaxC: Int?,
    val gtin: String?, val sku: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: String,
    @ColumnInfo(name = "additional_color_hexes") val additionalColorHexes: String = "",
)

@Fts4(contentEntity = CatalogEntry::class)
@Entity(tableName = "catalog_fts")
data class CatalogFts(val brand: String, val material: String, val product: String, @ColumnInfo(name = "color_name") val colorName: String)

@Entity(tableName = "catalog_metadata")
data class CatalogMetadata(@PrimaryKey val id: Int = 1, val revision: String, @ColumnInfo(name = "asset_sha256") val assetSha256: String, @ColumnInfo(name = "content_sha256") val contentSha256: String, @ColumnInfo(name = "package_count") val packageCount: Int)

@Dao interface CatalogDao {
    @Query("SELECT COUNT(*) FROM catalog_entries") suspend fun count(): Int
    @Query("DELETE FROM catalog_entries") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entries: List<CatalogEntry>)
    @Query("SELECT catalog_entries.* FROM catalog_entries JOIN catalog_fts ON catalog_entries.rowid=catalog_fts.rowid WHERE catalog_fts MATCH :query ORDER BY brand,product,color_name LIMIT :limit") suspend fun search(query: String, limit: Int = 100): List<CatalogEntry>
    @Query("SELECT * FROM catalog_entries ORDER BY brand,product,color_name LIMIT :limit") suspend fun browse(limit: Int = 100): List<CatalogEntry>
    @Query("SELECT * FROM catalog_entries WHERE package_id=:id") suspend fun byId(id: String): CatalogEntry?
    @Query("SELECT * FROM catalog_entries WHERE sku IS NOT NULL AND lower(sku)=lower(:sku) ORDER BY brand,material,product,color_name LIMIT :limit") suspend fun bySku(sku: String, limit: Int = 100): List<CatalogEntry>
    @Query("SELECT * FROM catalog_entries WHERE gtin=:gtin ORDER BY brand,material,product,color_name LIMIT :limit") suspend fun byGtin(gtin: String, limit: Int = 100): List<CatalogEntry>
    @Query("SELECT * FROM catalog_entries ORDER BY package_id") suspend fun allForIntegrity(): List<CatalogEntry>
    @Query("SELECT * FROM catalog_metadata WHERE id=1") suspend fun metadata(): CatalogMetadata?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMetadata(metadata: CatalogMetadata)
}

@Database(entities = [CatalogEntry::class, CatalogFts::class, CatalogMetadata::class], version = 2, exportSchema = true)
abstract class CatalogDatabase : RoomDatabase() { abstract fun catalog(): CatalogDao }

@Entity(tableName = "custom_records")
data class CustomRecord(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_package_id") val originalPackageId: String?,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val brand: String, val material: String, val product: String,
    @ColumnInfo(name = "color_name") val colorName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    @ColumnInfo(name = "diameter_mm") val diameterMm: String,
    @ColumnInfo(name = "mass_g") val massG: Int,
    @ColumnInfo(name = "nozzle_min_c") val nozzleMinC: Int?,
    @ColumnInfo(name = "nozzle_max_c") val nozzleMaxC: Int?,
    @ColumnInfo(name = "bed_min_c") val bedMinC: Int?,
    @ColumnInfo(name = "bed_max_c") val bedMaxC: Int?,
    val gtin: String?,
    @ColumnInfo(name = "article_number") val articleNumber: String?,
    @ColumnInfo(name = "additional_color_hexes") val additionalColorHexes: String,
    @ColumnInfo(name = "source_revision") val sourceRevision: String,
    val provenance: String,
    @ColumnInfo(name = "brand_source") val brandSource: String,
    @ColumnInfo(name = "material_source") val materialSource: String,
    @ColumnInfo(name = "product_source") val productSource: String,
    @ColumnInfo(name = "color_name_source") val colorNameSource: String,
    @ColumnInfo(name = "color_hex_source") val colorHexSource: String,
    @ColumnInfo(name = "diameter_source") val diameterSource: String,
    @ColumnInfo(name = "mass_source") val massSource: String,
    @ColumnInfo(name = "nozzle_min_source") val nozzleMinSource: String?,
    @ColumnInfo(name = "nozzle_max_source") val nozzleMaxSource: String?,
    @ColumnInfo(name = "bed_min_source") val bedMinSource: String?,
    @ColumnInfo(name = "bed_max_source") val bedMaxSource: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "barcode_evidence", defaultValue = "''") val barcodeEvidence: String = "",
    @ColumnInfo(name = "transmission_distance") val transmissionDistance: String? = null,
    @ColumnInfo(name = "transmission_distance_source") val transmissionDistanceSource: String? = null,
)

@Entity(tableName = "recents")
data class Recent(@PrimaryKey @ColumnInfo(name = "record_id") val recordId: String, val kind: String, @ColumnInfo(name = "snapshot_json") val snapshotJson: String, @ColumnInfo(name = "used_at") val usedAt: Long)

@Dao interface UserDao {
    @Query("SELECT * FROM custom_records WHERE id=:id") suspend fun byId(id: String): CustomRecord?
    @Query("SELECT * FROM custom_records ORDER BY updated_at DESC") fun customs(): Flow<List<CustomRecord>>
    @Query("SELECT * FROM custom_records ORDER BY updated_at DESC") suspend fun allCustoms(): List<CustomRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: CustomRecord)
    @Query("SELECT * FROM recents ORDER BY used_at DESC LIMIT 30") fun recents(): Flow<List<Recent>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun recent(record: Recent)
    @Query("DELETE FROM recents WHERE record_id=:id") suspend fun deleteRecent(id: String)
    @Query("DELETE FROM custom_records WHERE id=:id") suspend fun deleteCustom(id: String)
}

@Database(
    entities = [
        CustomRecord::class, Recent::class, FilamentProfileEntity::class,
        ProfileObservationEntity::class, ProfileOverrideEntity::class, ProfileIdentifierEntity::class,
        PhysicalSpoolEntity::class, TagBindingEntity::class, TransmissionDistanceMeasurementEntity::class,
        AppearanceAssertionEntity::class, FullSpectrumRoleAssertionEntity::class, FullSpectrumSuitabilityAssessmentEntity::class,
        EvidenceReferenceEntity::class, OpticalCharacterizationEntity::class, FullSpectrumAssessmentFactorEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun user(): UserDao
    abstract fun canonical(): CanonicalDao
    abstract fun fullSpectrum(): FullSpectrumDao
}

val CATALOG_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE catalog_entries ADD COLUMN additional_color_hexes TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_metadata (id INTEGER NOT NULL, revision TEXT NOT NULL, asset_sha256 TEXT NOT NULL, content_sha256 TEXT NOT NULL, package_count INTEGER NOT NULL, PRIMARY KEY(id))")
    }
}

val USER_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "original_package_id TEXT", "variant_id TEXT NOT NULL DEFAULT ''", "product_id TEXT NOT NULL DEFAULT ''", "nozzle_min_c INTEGER", "nozzle_max_c INTEGER", "bed_min_c INTEGER", "bed_max_c INTEGER",
            "gtin TEXT", "article_number TEXT", "additional_color_hexes TEXT NOT NULL DEFAULT ''",
            "source_revision TEXT NOT NULL DEFAULT 'user'", "provenance TEXT NOT NULL DEFAULT 'CUSTOM'",
            "brand_source TEXT NOT NULL DEFAULT 'User'", "material_source TEXT NOT NULL DEFAULT 'User'", "product_source TEXT NOT NULL DEFAULT 'User'",
            "color_name_source TEXT NOT NULL DEFAULT 'User'", "color_hex_source TEXT NOT NULL DEFAULT 'User'", "diameter_source TEXT NOT NULL DEFAULT 'User'",
            "mass_source TEXT NOT NULL DEFAULT 'User'", "nozzle_min_source TEXT", "nozzle_max_source TEXT", "bed_min_source TEXT", "bed_max_source TEXT",
        ).forEach { db.execSQL("ALTER TABLE custom_records ADD COLUMN $it") }
    }
}

val USER_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE custom_records ADD COLUMN barcode_evidence TEXT NOT NULL DEFAULT ''") }
}

val USER_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE custom_records ADD COLUMN transmission_distance TEXT")
        db.execSQL("ALTER TABLE custom_records ADD COLUMN transmission_distance_source TEXT")
    }
}

val USER_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createCanonicalTables(db)
        backfillCanonicalTables(db)
    }
}

val USER_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) = migrateFullSpectrumV6(db)
}

val USER_MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) = addFullSpectrumSearchIndexesV7(db)
}

/** Used by a rollback build to retain the untouched v4 compatibility tables. */
val USER_ROLLBACK_5_4 = object : Migration(5, 4) {
    override fun migrate(db: SupportSQLiteDatabase) = dropCanonicalTablesForRollback(db)
}

class DataStore(private val context: Context) {
    val catalog = Room.databaseBuilder(context, CatalogDatabase::class.java, "catalog-e3888b68.db").addMigrations(CATALOG_MIGRATION_1_2).build()
    val user = Room.databaseBuilder(context, UserDatabase::class.java, "user.db").addMigrations(USER_MIGRATION_1_2, USER_MIGRATION_2_3, USER_MIGRATION_3_4, USER_MIGRATION_4_5, USER_MIGRATION_5_6, USER_MIGRATION_6_7, USER_ROLLBACK_5_4).build()
    val filaments by lazy { LocalFilamentRepository(user) }

    suspend fun ensureCatalog() {
        val manifest = context.assets.open("ofd-catalog-manifest.json").use { StrictJson(4096).parse(it.readBytes()) as? JsonValue.Obj ?: error("Catalog manifest is not an object") }
        require(manifest.string("source") == "OpenFilamentCollective/open-filament-database" && manifest.string("format") == "spoolio-tsv-gzip-v2") { "Unsupported catalog manifest" }
        val revision = manifest.string("revision") ?: error("Catalog revision missing")
        val expectedCount = manifest.int("packages") ?: error("Catalog package count missing")
        val expectedAssetDigest = manifest.string("sha256") ?: error("Catalog digest missing")
        val expectedContentDigest = manifest.string("content_sha256") ?: error("Catalog content digest missing")
        val dao = catalog.catalog()
        val metadata = dao.metadata()
        if (metadata?.revision == revision && metadata.assetSha256 == expectedAssetDigest && metadata.contentSha256 == expectedContentDigest && metadata.packageCount == expectedCount && dao.count() == expectedCount && catalogDigest(dao.allForIntegrity()) == expectedContentDigest) return

        val compressed = context.assets.open("ofd-catalog.tsv.gzip").use { it.readBytes() }
        require(sha256(compressed) == expectedAssetDigest) { "Bundled catalog digest mismatch" }
        val entries = ArrayList<CatalogEntry>(expectedCount)
        GZIPInputStream(compressed.inputStream()).bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            require(iterator.hasNext() && iterator.next() == "package_id\tvariant_id\tproduct_id\tbrand\tmaterial\tproduct\tcolor_name\tcolor_hex\tdiameter_mm\tmass_g\tnozzle_min_c\tnozzle_max_c\tbed_min_c\tbed_max_c\tgtin\tarticle_number\tsource_revision\tadditional_color_hexes") { "Unexpected catalog columns" }
            while (iterator.hasNext()) {
                val f = iterator.next().split('\t')
                require(f.size == 18) { "Malformed catalog row" }
                val entry = CatalogEntry(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7].removePrefix("#"), f[8], f[9].toIntOrNull() ?: error("Invalid mass for ${f[0]}"), f[10].toIntOrNull(), f[11].toIntOrNull(), f[12].toIntOrNull(), f[13].toIntOrNull(), f[14].ifEmpty { null }, f[15].ifEmpty { null }, f[16], f[17])
                validateCatalogEntry(entry)
                entries += entry
            }
        }
        require(entries.size == expectedCount && entries.map { it.packageId }.toSet().size == expectedCount) { "Catalog count or package identifiers are invalid" }
        require(catalogDigest(entries.sortedBy(CatalogEntry::packageId)) == expectedContentDigest) { "Catalog content digest mismatch" }
        catalog.withTransaction {
            dao.clear()
            entries.chunked(500).forEach { dao.insert(it) }
            check(dao.count() == expectedCount) { "Catalog transaction did not install the complete generation" }
            dao.saveMetadata(CatalogMetadata(revision = revision, assetSha256 = expectedAssetDigest, contentSha256 = expectedContentDigest, packageCount = expectedCount))
        }
    }
}

fun catalogDigest(entries: List<CatalogEntry>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.forEach { entry ->
        listOf(entry.packageId, entry.variantId, entry.productId, entry.brand, entry.material, entry.product, entry.colorName, entry.colorHex, entry.diameterMm, entry.massG.toString(), entry.nozzleMinC?.toString().orEmpty(), entry.nozzleMaxC?.toString().orEmpty(), entry.bedMinC?.toString().orEmpty(), entry.bedMaxC?.toString().orEmpty(), entry.gtin.orEmpty(), entry.sku.orEmpty(), entry.sourceRevision, entry.additionalColorHexes).forEach { value ->
            val bytes = value.encodeToByteArray()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun validateCatalogEntry(entry: CatalogEntry) {
    require(entry.packageId.isNotBlank() && entry.brand.isNotBlank() && entry.material.isNotBlank() && entry.product.isNotBlank()) { "Catalog identity fields are missing" }
    require(listOf(entry.brand, entry.material, entry.product, entry.colorName).all { it.length <= 200 && it.none(Char::isISOControl) }) { "Catalog text is invalid" }
    require(entry.diameterMm.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal.TEN } == true) { "Catalog diameter is invalid" }
    require(entry.massG in 1..100_000) { "Catalog mass is invalid" }
    if (entry.colorHex.isNotBlank()) normalizeHex(entry.colorHex)
    entry.additionalColorHexes.split(',').filter(String::isNotBlank).forEach(::normalizeHex)
    fun range(min: Int?, max: Int?) { require((min == null || min in 0..500) && (max == null || max in 0..500) && (min == null || max == null || min <= max)) { "Catalog temperature range is invalid" } }
    range(entry.nozzleMinC, entry.nozzleMaxC)
    range(entry.bedMinC, entry.bedMaxC)
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
