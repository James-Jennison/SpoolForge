package net.jamesjennison.filamajignfc.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import net.jamesjennison.filamajignfc.core.*

internal const val LEGACY_PROFILE_PREFIX = "legacy-profile:"
internal const val LEGACY_SPOOL_PREFIX = "legacy-spool:"

@Entity(
    tableName = "filament_profiles",
    indices = [Index(value = ["legacy_record_id"], unique = true)],
)
data class FilamentProfileEntity(
    @PrimaryKey @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "legacy_record_id") val legacyRecordId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "profile_observations",
    foreignKeys = [ForeignKey(
        entity = FilamentProfileEntity::class,
        parentColumns = ["profile_id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profile_id"), Index(value = ["profile_id", "field_name"]), Index(value = ["field_name", "value_text", "profile_id"])],
)
data class ProfileObservationEntity(
    @PrimaryKey @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "field_name") val fieldName: String,
    @ColumnInfo(name = "value_text") val valueText: String,
    @ColumnInfo(name = "source_provider") val sourceProvider: String,
    @ColumnInfo(name = "source_record_id") val sourceRecordId: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: String?,
    @ColumnInfo(name = "observed_at") val observedAt: Long?,
    @ColumnInfo(name = "evidence_kind") val evidenceKind: String,
    val confidence: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A pinned current value. New provider observations never overwrite this row. */
@Entity(
    tableName = "profile_overrides",
    primaryKeys = ["profile_id", "field_name"],
    foreignKeys = [ForeignKey(
        entity = FilamentProfileEntity::class,
        parentColumns = ["profile_id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profile_id")],
)
data class ProfileOverrideEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "field_name") val fieldName: String,
    @ColumnInfo(name = "value_text") val valueText: String,
    @ColumnInfo(name = "source_observation_id") val sourceObservationId: String?,
    val reason: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "profile_identifiers",
    foreignKeys = [ForeignKey(
        entity = FilamentProfileEntity::class,
        parentColumns = ["profile_id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profile_id"), Index(value = ["scheme", "value"])],
)
data class ProfileIdentifierEntity(
    @PrimaryKey @ColumnInfo(name = "identifier_id") val identifierId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val scheme: String,
    val value: String,
    val issuer: String?,
    val market: String?,
    @ColumnInfo(name = "source_provider") val sourceProvider: String,
    @ColumnInfo(name = "source_record_id") val sourceRecordId: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: String?,
    @ColumnInfo(name = "evidence_kind") val evidenceKind: String,
    val confidence: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "physical_spools",
    foreignKeys = [ForeignKey(
        entity = FilamentProfileEntity::class,
        parentColumns = ["profile_id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("profile_id"), Index(value = ["legacy_record_id"], unique = true)],
)
data class PhysicalSpoolEntity(
    @PrimaryKey @ColumnInfo(name = "spool_id") val spoolId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "legacy_record_id") val legacyRecordId: String?,
    @ColumnInfo(name = "initial_quantity_g") val initialQuantityG: Int,
    @ColumnInfo(name = "remaining_quantity_g") val remainingQuantityG: Int?,
    @ColumnInfo(name = "purchase_date_epoch_day") val purchaseDateEpochDay: Long?,
    @ColumnInfo(name = "purchase_price_minor_units") val purchasePriceMinorUnits: Long?,
    @ColumnInfo(name = "currency_code") val currencyCode: String?,
    val vendor: String?,
    @ColumnInfo(name = "storage_location") val storageLocation: String?,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    val notes: String?,
    @ColumnInfo(name = "custom_metadata_json") val customMetadataJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "tag_bindings",
    foreignKeys = [ForeignKey(
        entity = PhysicalSpoolEntity::class,
        parentColumns = ["spool_id"],
        childColumns = ["spool_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("spool_id"),
        Index(value = ["spool_id", "binding_order"], unique = true),
        Index(value = ["technology", "uid_hex"], unique = true),
    ],
)
data class TagBindingEntity(
    @PrimaryKey @ColumnInfo(name = "tag_binding_id") val tagBindingId: String,
    @ColumnInfo(name = "spool_id") val spoolId: String,
    val technology: String,
    @ColumnInfo(name = "uid_hex") val uidHex: String,
    @ColumnInfo(name = "codec_id") val codecId: String,
    @ColumnInfo(name = "payload_sha256") val payloadSha256: String?,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long?,
    @ColumnInfo(name = "binding_order") val bindingOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "transmission_distance_measurements",
    foreignKeys = [ForeignKey(
        entity = FilamentProfileEntity::class,
        parentColumns = ["profile_id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profile_id")],
)
data class TransmissionDistanceMeasurementEntity(
    @PrimaryKey @ColumnInfo(name = "measurement_id") val measurementId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "value_text") val valueText: String,
    @ColumnInfo(name = "source_provider") val sourceProvider: String,
    @ColumnInfo(name = "source_record_id") val sourceRecordId: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: String?,
    @ColumnInfo(name = "evidence_kind") val evidenceKind: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long?,
    val instrument: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface CanonicalDao {
    @Upsert suspend fun putProfile(value: FilamentProfileEntity)
    @Upsert suspend fun putObservations(values: List<ProfileObservationEntity>)
    @Upsert suspend fun putOverrides(values: List<ProfileOverrideEntity>)
    @Upsert suspend fun putIdentifiers(values: List<ProfileIdentifierEntity>)
    @Upsert suspend fun putSpool(value: PhysicalSpoolEntity)
    @Upsert suspend fun putTagBinding(value: TagBindingEntity)
    @Upsert suspend fun putTdMeasurement(value: TransmissionDistanceMeasurementEntity)

    @Query("SELECT * FROM filament_profiles WHERE profile_id=:id") suspend fun profile(id: String): FilamentProfileEntity?
    @Query("SELECT * FROM filament_profiles WHERE legacy_record_id=:legacyId") suspend fun profileForLegacyRecord(legacyId: String): FilamentProfileEntity?
    @Query("SELECT * FROM profile_observations WHERE profile_id=:profileId ORDER BY field_name, created_at, observation_id") suspend fun observations(profileId: String): List<ProfileObservationEntity>
    @Query("SELECT * FROM profile_overrides WHERE profile_id=:profileId ORDER BY field_name") suspend fun overrides(profileId: String): List<ProfileOverrideEntity>
    @Query("SELECT * FROM profile_identifiers WHERE profile_id=:profileId ORDER BY scheme, value") suspend fun identifiers(profileId: String): List<ProfileIdentifierEntity>
    @Query("SELECT * FROM physical_spools WHERE profile_id=:profileId ORDER BY created_at, spool_id") suspend fun spools(profileId: String): List<PhysicalSpoolEntity>
    @Query("SELECT * FROM physical_spools WHERE spool_id=:id") suspend fun spool(id: String): PhysicalSpoolEntity?
    @Query("SELECT * FROM physical_spools WHERE legacy_record_id=:legacyId") suspend fun spoolForLegacyRecord(legacyId: String): PhysicalSpoolEntity?
    @Query("SELECT * FROM tag_bindings WHERE spool_id=:spoolId ORDER BY binding_order, created_at") suspend fun tagBindings(spoolId: String): List<TagBindingEntity>
    @Query("SELECT * FROM tag_bindings WHERE technology=:technology AND uid_hex=:uidHex") suspend fun tagBindingByUid(technology: String, uidHex: String): TagBindingEntity?
    @Query("SELECT * FROM transmission_distance_measurements WHERE profile_id=:profileId ORDER BY created_at, measurement_id") suspend fun tdMeasurements(profileId: String): List<TransmissionDistanceMeasurementEntity>
    @Query("DELETE FROM profile_observations WHERE observation_id LIKE :prefix || '%'") suspend fun deleteObservationsWithPrefix(prefix: String)
    @Query("DELETE FROM profile_overrides WHERE profile_id=:profileId") suspend fun deleteOverrides(profileId: String)
    @Query("DELETE FROM profile_identifiers WHERE identifier_id LIKE :prefix || '%'") suspend fun deleteIdentifiersWithPrefix(prefix: String)
    @Query("DELETE FROM transmission_distance_measurements WHERE measurement_id=:id") suspend fun deleteTdMeasurement(id: String)
    @Query("DELETE FROM physical_spools WHERE legacy_record_id=:legacyId") suspend fun deleteLegacySpool(legacyId: String)
    @Query("DELETE FROM filament_profiles WHERE legacy_record_id=:legacyId") suspend fun deleteLegacyProfile(legacyId: String)
    @Query("DELETE FROM filament_profiles WHERE profile_id=:profileId") suspend fun deleteProfile(profileId: String)
    @Query("DELETE FROM tag_bindings WHERE spool_id=:spoolId AND binding_order=:bindingOrder") suspend fun deleteTagBindingAt(spoolId: String, bindingOrder: Int)
    @Query("DELETE FROM tag_bindings WHERE technology=:technology AND uid_hex=:uidHex") suspend fun deleteTagBindingByUid(technology: String, uidHex: String)
    @Query("SELECT COUNT(*) FROM physical_spools WHERE profile_id=:profileId") suspend fun spoolCount(profileId: String): Int
    @Query("""
        SELECT DISTINCT fp.legacy_record_id FROM filament_profiles fp
        WHERE fp.legacy_record_id IS NOT NULL AND (
            :query = '' OR EXISTS (
                SELECT 1 FROM profile_overrides po
                WHERE po.profile_id=fp.profile_id AND lower(po.value_text) LIKE '%' || lower(:query) || '%'
            ) OR EXISTS (
                SELECT 1 FROM profile_identifiers pi
                WHERE pi.profile_id=fp.profile_id AND lower(pi.value) = lower(:query)
            )
        )
        ORDER BY fp.updated_at DESC, fp.profile_id LIMIT :limit
    """) suspend fun searchLegacyRecordIds(query:String,limit:Int=100):List<String>
    @Query("UPDATE filament_profiles SET legacy_record_id=NULL WHERE profile_id=:profileId") suspend fun detachLegacyProfile(profileId: String)

    @Transaction
    suspend fun syncLegacyRecord(record: CustomRecord, requestedProfileId: String? = null, requestedSpoolId: String? = null,
                                 requestedInitialQuantityG: Int? = null, requestedRemainingQuantityG: Int? = null) {
        val existingSpool = spoolForLegacyRecord(record.id)
        require(requestedProfileId == null || existingSpool == null || existingSpool.profileId == requestedProfileId) {
            "Local record is already assigned to a different profile identity"
        }
        require(requestedSpoolId == null || existingSpool == null || existingSpool.spoolId == requestedSpoolId) {
            "Local record is already assigned to a different spool identity"
        }
        val profileId = existingSpool?.profileId ?: profileForLegacyRecord(record.id)?.profileId ?: requestedProfileId ?: legacyProfileId(record.id)
        val spoolId = existingSpool?.spoolId ?: requestedSpoolId ?: legacySpoolId(record.id)
        require(profileId.isNotBlank() && spoolId.isNotBlank() && profileId != spoolId)
        val conflictingSpool = spool(spoolId)
        require(conflictingSpool == null || conflictingSpool.legacyRecordId == record.id) { "Spool identity is already assigned to another local record" }
        val observationPrefix = "legacy-observation:${record.id}:"
        val identifierPrefix = "legacy-identifier:${record.id}:"
        val existingProfile = profile(profileId)
        putProfile(existingProfile?.copy(updatedAt = record.updatedAt)
            ?: FilamentProfileEntity(profileId, record.id, record.updatedAt, record.updatedAt))
        deleteObservationsWithPrefix(observationPrefix)
        deleteOverrides(profileId)
        deleteIdentifiersWithPrefix(identifierPrefix)

        val observations = record.legacyObservations(profileId, observationPrefix)
        if (observations.isNotEmpty()) putObservations(observations)
        putOverrides(observations.map {
            ProfileOverrideEntity(profileId, it.fieldName, it.valueText, it.observationId, "LEGACY_CURRENT_VALUE", record.updatedAt)
        })
        val identifiers = record.legacyIdentifiers(profileId, identifierPrefix)
        if (identifiers.isNotEmpty()) putIdentifiers(identifiers)
        val quantity = record.massG.takeIf { it > 0 } ?: 1000
        val initialQuantity = requestedInitialQuantityG ?: existingSpool?.initialQuantityG ?: quantity
        val remainingQuantity = requestedRemainingQuantityG ?: existingSpool?.remainingQuantityG ?: initialQuantity
        require(initialQuantity > 0 && remainingQuantity in 0..initialQuantity)
        putSpool(PhysicalSpoolEntity(
            spoolId, profileId, record.id, initialQuantity, remainingQuantity,
            null, null, null, null, null, null, null, "{}", record.updatedAt, record.updatedAt,
        ))
    }

    @Transaction
    suspend fun bindVerifiedTag(spoolId: String, technology: String, uidHex: String, codecId: String,
                                payloadSha256: String, verifiedAt: Long, bindingOrder: Int) {
        require(spool(spoolId) != null) { "Physical spool does not exist" }
        require(bindingOrder in 1..2) { "A spool supports tag positions one and two" }
        require(technology.isNotBlank() && uidHex.matches(Regex("[0-9A-F]+")) && uidHex.length % 2 == 0) { "Tag identity is invalid" }
        require(codecId.isNotBlank() && payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "Verified tag metadata is invalid" }
        val previous = tagBindingByUid(technology, uidHex)
        deleteTagBindingAt(spoolId, bindingOrder)
        deleteTagBindingByUid(technology, uidHex)
        putTagBinding(TagBindingEntity(
            "tag:${technology.lowercase()}:$uidHex", spoolId, technology, uidHex, codecId,
            payloadSha256, verifiedAt, bindingOrder, previous?.createdAt ?: verifiedAt,
        ))
    }
}

internal fun legacyProfileId(recordId: String) = "$LEGACY_PROFILE_PREFIX$recordId"
internal fun legacySpoolId(recordId: String) = "$LEGACY_SPOOL_PREFIX$recordId"

private data class LegacyField(val name: String, val value: String?, val source: String?)

private fun CustomRecord.legacyObservations(profileId: String, prefix: String): List<ProfileObservationEntity> {
    val fields = listOf(
        LegacyField("brand", brand, brandSource), LegacyField("material", material, materialSource),
        LegacyField("product", product, productSource), LegacyField("colorName", colorName, colorNameSource),
        LegacyField("colorHex", colorHex, colorHexSource), LegacyField("diameterMm", diameterMm, diameterSource),
        LegacyField("nominalFilamentMassG", massG.takeIf { it > 0 }?.toString(), massSource),
        LegacyField("nozzleMinimumC", nozzleMinC?.toString(), nozzleMinSource),
        LegacyField("nozzleMaximumC", nozzleMaxC?.toString(), nozzleMaxSource),
        LegacyField("bedMinimumC", bedMinC?.toString(), bedMinSource),
        LegacyField("bedMaximumC", bedMaxC?.toString(), bedMaxSource),
        LegacyField("additionalColorHexes", additionalColorHexes, colorHexSource),
        LegacyField("transmissionDistance", transmissionDistance, transmissionDistanceSource),
    )
    return fields.mapNotNull { field ->
        val value = field.value?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val id = "$prefix${field.name}"
        ProfileObservationEntity(id, profileId, field.name, value, field.source.orEmpty().ifBlank { "Legacy record" }, id@this.id, sourceRevision, updatedAt, evidenceKind(field.source), null, updatedAt)
    }
}

private fun CustomRecord.legacyIdentifiers(profileId: String, prefix: String): List<ProfileIdentifierEntity> = buildList {
    gtin?.takeIf(String::isNotBlank)?.let { value ->
        add(ProfileIdentifierEntity("${prefix}gtin", profileId, "GTIN", value, null, null, "Legacy record", id, sourceRevision, evidenceKind(provenance), null, updatedAt))
    }
    articleNumber?.takeIf(String::isNotBlank)?.let { value ->
        add(ProfileIdentifierEntity("${prefix}article-number", profileId, "MANUFACTURER_PART_NUMBER", value, brand.takeIf(String::isNotBlank), null, "Legacy record", id, sourceRevision, evidenceKind(provenance), null, updatedAt))
    }
}

private fun evidenceKind(source: String?): String = when {
    source.orEmpty().contains("edit", ignoreCase = true) || source.orEmpty().contains("user", ignoreCase = true) -> "USER"
    source.orEmpty().contains("measur", ignoreCase = true) -> "MEASURED"
    source.orEmpty().contains("assum", ignoreCase = true) -> "INFERRED"
    source.orEmpty().contains("label", ignoreCase = true) || source.orEmpty().contains("scan", ignoreCase = true) || source.orEmpty().contains("tag", ignoreCase = true) -> "LABEL"
    source.orEmpty().contains("communit", ignoreCase = true) -> "COMMUNITY"
    else -> "CATALOG"
}

internal fun createCanonicalTables(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS `filament_profiles` (`profile_id` TEXT NOT NULL, `legacy_record_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`profile_id`))")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_filament_profiles_legacy_record_id` ON `filament_profiles` (`legacy_record_id`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `profile_observations` (`observation_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `field_name` TEXT NOT NULL, `value_text` TEXT NOT NULL, `source_provider` TEXT NOT NULL, `source_record_id` TEXT, `source_revision` TEXT, `observed_at` INTEGER, `evidence_kind` TEXT NOT NULL, `confidence` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`observation_id`), FOREIGN KEY(`profile_id`) REFERENCES `filament_profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_observations_profile_id` ON `profile_observations` (`profile_id`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_observations_profile_id_field_name` ON `profile_observations` (`profile_id`, `field_name`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `profile_overrides` (`profile_id` TEXT NOT NULL, `field_name` TEXT NOT NULL, `value_text` TEXT NOT NULL, `source_observation_id` TEXT, `reason` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`profile_id`, `field_name`), FOREIGN KEY(`profile_id`) REFERENCES `filament_profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_overrides_profile_id` ON `profile_overrides` (`profile_id`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `profile_identifiers` (`identifier_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `scheme` TEXT NOT NULL, `value` TEXT NOT NULL, `issuer` TEXT, `market` TEXT, `source_provider` TEXT NOT NULL, `source_record_id` TEXT, `source_revision` TEXT, `evidence_kind` TEXT NOT NULL, `confidence` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`identifier_id`), FOREIGN KEY(`profile_id`) REFERENCES `filament_profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_identifiers_profile_id` ON `profile_identifiers` (`profile_id`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_identifiers_scheme_value` ON `profile_identifiers` (`scheme`, `value`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `physical_spools` (`spool_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `legacy_record_id` TEXT, `initial_quantity_g` INTEGER NOT NULL, `remaining_quantity_g` INTEGER, `purchase_date_epoch_day` INTEGER, `purchase_price_minor_units` INTEGER, `currency_code` TEXT, `vendor` TEXT, `storage_location` TEXT, `opened_at` INTEGER, `notes` TEXT, `custom_metadata_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`spool_id`), FOREIGN KEY(`profile_id`) REFERENCES `filament_profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_physical_spools_profile_id` ON `physical_spools` (`profile_id`)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_physical_spools_legacy_record_id` ON `physical_spools` (`legacy_record_id`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `tag_bindings` (`tag_binding_id` TEXT NOT NULL, `spool_id` TEXT NOT NULL, `technology` TEXT NOT NULL, `uid_hex` TEXT NOT NULL, `codec_id` TEXT NOT NULL, `payload_sha256` TEXT, `verified_at` INTEGER, `binding_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`tag_binding_id`), FOREIGN KEY(`spool_id`) REFERENCES `physical_spools`(`spool_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_bindings_spool_id` ON `tag_bindings` (`spool_id`)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_bindings_spool_id_binding_order` ON `tag_bindings` (`spool_id`, `binding_order`)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_bindings_technology_uid_hex` ON `tag_bindings` (`technology`, `uid_hex`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `transmission_distance_measurements` (`measurement_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `value_text` TEXT NOT NULL, `source_provider` TEXT NOT NULL, `source_record_id` TEXT, `source_revision` TEXT, `evidence_kind` TEXT NOT NULL, `measured_at` INTEGER, `instrument` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`measurement_id`), FOREIGN KEY(`profile_id`) REFERENCES `filament_profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_transmission_distance_measurements_profile_id` ON `transmission_distance_measurements` (`profile_id`)")
}

internal fun backfillCanonicalTables(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO filament_profiles(profile_id,legacy_record_id,created_at,updated_at) SELECT 'legacy-profile:'||id,id,updated_at,updated_at FROM custom_records")

    fun observation(field: String, valueExpression: String, sourceExpression: String) {
        val kind = evidenceKindSql(sourceExpression)
        db.execSQL("INSERT OR REPLACE INTO profile_observations(observation_id,profile_id,field_name,value_text,source_provider,source_record_id,source_revision,observed_at,evidence_kind,confidence,created_at) SELECT 'legacy-observation:'||id||':$field','legacy-profile:'||id,'$field',CAST($valueExpression AS TEXT),COALESCE(NULLIF($sourceExpression,''),'Legacy record'),id,source_revision,updated_at,$kind,NULL,updated_at FROM custom_records WHERE $valueExpression IS NOT NULL AND CAST($valueExpression AS TEXT)<>''")
        db.execSQL("INSERT OR REPLACE INTO profile_overrides(profile_id,field_name,value_text,source_observation_id,reason,updated_at) SELECT 'legacy-profile:'||id,'$field',CAST($valueExpression AS TEXT),'legacy-observation:'||id||':$field','LEGACY_CURRENT_VALUE',updated_at FROM custom_records WHERE $valueExpression IS NOT NULL AND CAST($valueExpression AS TEXT)<>''")
    }

    observation("brand", "brand", "brand_source")
    observation("material", "material", "material_source")
    observation("product", "product", "product_source")
    observation("colorName", "color_name", "color_name_source")
    observation("colorHex", "color_hex", "color_hex_source")
    observation("diameterMm", "diameter_mm", "diameter_source")
    observation("nominalFilamentMassG", "CASE WHEN mass_g>0 THEN mass_g END", "mass_source")
    observation("nozzleMinimumC", "nozzle_min_c", "nozzle_min_source")
    observation("nozzleMaximumC", "nozzle_max_c", "nozzle_max_source")
    observation("bedMinimumC", "bed_min_c", "bed_min_source")
    observation("bedMaximumC", "bed_max_c", "bed_max_source")
    observation("additionalColorHexes", "additional_color_hexes", "color_hex_source")
    observation("transmissionDistance", "transmission_distance", "transmission_distance_source")
    observation("barcodeEvidence", "barcode_evidence", "provenance")
    observation("originalPackageId", "original_package_id", "provenance")
    observation("variantId", "variant_id", "provenance")
    observation("productId", "product_id", "provenance")

    val provenanceKind = evidenceKindSql("provenance")
    db.execSQL("INSERT OR REPLACE INTO profile_identifiers(identifier_id,profile_id,scheme,value,issuer,market,source_provider,source_record_id,source_revision,evidence_kind,confidence,created_at) SELECT 'legacy-identifier:'||id||':gtin','legacy-profile:'||id,'GTIN',gtin,NULL,NULL,'Legacy record',id,source_revision,$provenanceKind,NULL,updated_at FROM custom_records WHERE gtin IS NOT NULL AND gtin<>''")
    db.execSQL("INSERT OR REPLACE INTO profile_identifiers(identifier_id,profile_id,scheme,value,issuer,market,source_provider,source_record_id,source_revision,evidence_kind,confidence,created_at) SELECT 'legacy-identifier:'||id||':article-number','legacy-profile:'||id,'MANUFACTURER_PART_NUMBER',article_number,NULLIF(brand,''),NULL,'Legacy record',id,source_revision,$provenanceKind,NULL,updated_at FROM custom_records WHERE article_number IS NOT NULL AND article_number<>''")
    db.execSQL("INSERT OR IGNORE INTO physical_spools(spool_id,profile_id,legacy_record_id,initial_quantity_g,remaining_quantity_g,purchase_date_epoch_day,purchase_price_minor_units,currency_code,vendor,storage_location,opened_at,notes,custom_metadata_json,created_at,updated_at) SELECT 'legacy-spool:'||id,'legacy-profile:'||id,id,CASE WHEN mass_g>0 THEN mass_g ELSE 1000 END,CASE WHEN mass_g>0 THEN mass_g ELSE 1000 END,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'{}',updated_at,updated_at FROM custom_records")
    val tdKind = evidenceKindSql("transmission_distance_source")
    db.execSQL("INSERT OR REPLACE INTO transmission_distance_measurements(measurement_id,profile_id,value_text,source_provider,source_record_id,source_revision,evidence_kind,measured_at,instrument,created_at) SELECT 'legacy-td:'||id,'legacy-profile:'||id,transmission_distance,COALESCE(NULLIF(transmission_distance_source,''),'Legacy record'),id,source_revision,$tdKind,NULL,NULL,updated_at FROM custom_records WHERE transmission_distance IS NOT NULL AND transmission_distance<>''")
}

private fun evidenceKindSql(sourceExpression: String) = "CASE WHEN lower(COALESCE($sourceExpression,'')) LIKE '%edit%' OR lower(COALESCE($sourceExpression,'')) LIKE '%user%' THEN 'USER' WHEN lower(COALESCE($sourceExpression,'')) LIKE '%measur%' THEN 'MEASURED' WHEN lower(COALESCE($sourceExpression,'')) LIKE '%assum%' THEN 'INFERRED' WHEN lower(COALESCE($sourceExpression,'')) LIKE '%label%' OR lower(COALESCE($sourceExpression,'')) LIKE '%scan%' OR lower(COALESCE($sourceExpression,'')) LIKE '%tag%' THEN 'LABEL' WHEN lower(COALESCE($sourceExpression,'')) LIKE '%communit%' THEN 'COMMUNITY' ELSE 'CATALOG' END"

internal fun dropCanonicalTablesForRollback(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS tag_bindings")
    db.execSQL("DROP TABLE IF EXISTS transmission_distance_measurements")
    db.execSQL("DROP TABLE IF EXISTS physical_spools")
    db.execSQL("DROP TABLE IF EXISTS profile_identifiers")
    db.execSQL("DROP TABLE IF EXISTS profile_overrides")
    db.execSQL("DROP TABLE IF EXISTS profile_observations")
    db.execSQL("DROP TABLE IF EXISTS filament_profiles")
}

class LocalFilamentRepository internal constructor(private val database: UserDatabase) {
    private suspend fun profileIdForRecord(recordId: String): String? =
        database.canonical().spoolForLegacyRecord(recordId)?.profileId
            ?: database.canonical().profileForLegacyRecord(recordId)?.profileId

    suspend fun save(record: CustomRecord, profileId: String? = null, spoolId: String? = null,
                     initialQuantityG: Int? = null, remainingQuantityG: Int? = null) = database.withTransaction {
        database.user().save(record)
        database.canonical().syncLegacyRecord(record, profileId, spoolId, initialQuantityG, remainingQuantityG)
        val resolvedProfileId = profileIdForRecord(record.id)
            ?: error("Saved local record has no canonical profile")
        val value = record.transmissionDistance?.takeIf(String::isNotBlank)
        val previous = database.fullSpectrum().opticalCharacterizations(resolvedProfileId)
            .lastOrNull { it.propertyKey == "transmission_distance" && it.supersededByCharacterizationId == null }?.valueText
        if (value != previous) {
            val characterizationId = "local-optical:$resolvedProfileId:${record.updatedAt}"
            val evidenceId = "local-td-evidence:$resolvedProfileId:${record.updatedAt}"
            database.fullSpectrum().putEvidence(EvidenceReferenceEntity(
                evidenceId, if (value == null) "USER" else evidenceKind(record.transmissionDistanceSource),
                if (value == null) "Local user" else record.transmissionDistanceSource.orEmpty().ifBlank { "Local record" }, record.id,
                record.sourceRevision, null, record.updatedAt, "Compatibility editor input",
            ))
            database.fullSpectrum().supersedeCurrentTransmissionDistance(resolvedProfileId, characterizationId)
            database.fullSpectrum().putOpticalCharacterization(OpticalCharacterizationEntity(
                characterizationId=characterizationId, profileId=resolvedProfileId,
                propertyKey="transmission_distance", valueText=value, unit="mm", methodKey="USER_ENTRY_UNSPECIFIED",
                methodVersion=null, origin=if (value == null) TdOrigin.USER_REPORTED_UNSPECIFIED.name else TdOrigin.fromLegacy(evidenceKind(record.transmissionDistanceSource)).name,
                geometry=null, wallThicknessMm=null, layerHeightMm=null, evidenceId=evidenceId, confidence=null,
                notes=if (value == null) "Transmission distance cleared by user" else null, environmentalNotes=null,
                measuredAt=null, instrument=null, supersededByCharacterizationId=null, createdAt=record.updatedAt,
            ))
        }
    }

    suspend fun delete(recordId: String) = database.withTransaction {
        val profileId = database.canonical().spoolForLegacyRecord(recordId)?.profileId
            ?: database.canonical().profileForLegacyRecord(recordId)?.profileId
            ?: legacyProfileId(recordId)
        database.user().deleteRecent(recordId)
        database.canonical().deleteLegacySpool(recordId)
        if (database.canonical().spoolCount(profileId) == 0) database.canonical().deleteProfile(profileId)
        else if (database.canonical().profile(profileId)?.legacyRecordId == recordId) database.canonical().detachLegacyProfile(profileId)
        database.user().deleteCustom(recordId)
    }

    suspend fun portableIds(recordId: String): SavedPortableIdentity? {
        val spool = database.canonical().spoolForLegacyRecord(recordId) ?: return null
        return SavedPortableIdentity(spool.profileId, spool.spoolId, spool.initialQuantityG, spool.remainingQuantityG)
    }

    suspend fun bindVerifiedTag(spoolId: String, uid: ByteArray, codecId: String, payload: ByteArray,
                                bindingOrder: Int, verifiedAt: Long = System.currentTimeMillis()) {
        val uidHex = uid.joinToString("") { "%02X".format(it) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        database.canonical().bindVerifiedTag(spoolId, "NFC-A", uidHex, codecId, digest, verifiedAt, bindingOrder)
    }

    suspend fun tagBindings(spoolId: String): List<TagBindingEntity> = database.canonical().tagBindings(spoolId)

    suspend fun fullSpectrum(recordId: String): FullSpectrumProfileData? {
        val profileId = profileIdForRecord(recordId) ?: return null
        return FullSpectrumProfileData(
            database.fullSpectrum().opticalCharacterizations(profileId), database.fullSpectrum().appearances(profileId),
            database.fullSpectrum().roles(profileId), database.fullSpectrum().suitability(profileId),
        )
    }

    suspend fun searchFullSpectrum(filter:FullSpectrumSearchFilter,limit:Int=100):List<String> =
        database.fullSpectrum().searchRecordIds(
            filter.roleKey?.let(FullSpectrumRoles::validate), filter.tdOrigin?.let(TdOrigins::validate),
            filter.tdMinimumMm?.toDoubleOrNull(), filter.tdMaximumMm?.toDoubleOrNull(),
            filter.suitability?.name, filter.testState?.name, filter.ownership?.name, limit,
        )

    suspend fun alternateCandidates(roleKey:String,limit:Int=20):List<FullSpectrumCandidateEvidence> =
        database.fullSpectrum().alternateCandidates(FullSpectrumRoles.validate(roleKey),limit)

    suspend fun assertUserRole(recordId: String, roleKey: String, now: Long = System.currentTimeMillis()) {
        val profileId = profileIdForRecord(recordId)
            ?: error("Save this filament locally before adding Full Spectrum evidence")
        val normalized = FullSpectrumRoles.validate(roleKey)
        database.withTransaction {
            val evidenceId="user-role-evidence:$profileId:$normalized:$now"
            database.fullSpectrum().putEvidence(EvidenceReferenceEntity(evidenceId,"USER","Local user",recordId,null,null,now,"User-confirmed role"))
            database.fullSpectrum().putRole(FullSpectrumRoleAssertionEntity(
                "role:$profileId:$normalized:$now", profileId, normalized,
                FullSpectrumRoles.category(normalized).name, RoleAuthority.USER_CONFIRMED.name,
                AssertionState.ACTIVE.name, null, evidenceId, null, null, now,
            ))
        }
    }

    suspend fun recordObservedSuitability(recordId: String, rating: SuitabilityRating, rationale: String, now: Long = System.currentTimeMillis()) {
        require(rationale.isNotBlank())
        val profileId = profileIdForRecord(recordId)
            ?: error("Save this filament locally before recording Full Spectrum evidence")
        database.withTransaction {
            val evidenceId="user-suitability-evidence:$profileId:$now"
            database.fullSpectrum().putEvidence(EvidenceReferenceEntity(evidenceId,"USER","Local user",recordId,null,null,now,"User-observed suitability"))
            database.fullSpectrum().putSuitability(FullSpectrumSuitabilityAssessmentEntity(
                "suitability:$profileId:$now", profileId, SuitabilityKind.OBSERVED.name, rating.name,
                SuitabilityScope.PROFILE.name, null, null, null, "[]", rationale,
                evidenceId, null, "Local user", now, null,
            ))
        }
    }
}

data class SavedPortableIdentity(val profileId: String, val spoolId: String, val initialQuantityG: Int, val remainingQuantityG: Int?)
