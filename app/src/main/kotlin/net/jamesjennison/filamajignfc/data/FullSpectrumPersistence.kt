package net.jamesjennison.filamajignfc.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import net.jamesjennison.filamajignfc.core.*

@Entity(tableName="evidence_references",indices=[Index(value=["kind","provider","record_id"])])
data class EvidenceReferenceEntity(
    @PrimaryKey @ColumnInfo(name="evidence_id") val evidenceId:String,
    val kind:String, val provider:String, @ColumnInfo(name="record_id") val recordId:String?,
    val revision:String?, val url:String?, @ColumnInfo(name="retrieved_at") val retrievedAt:Long?, val notes:String?,
)

@Entity(tableName="optical_characterizations",foreignKeys=[ForeignKey(entity=FilamentProfileEntity::class,parentColumns=["profile_id"],childColumns=["profile_id"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=EvidenceReferenceEntity::class,parentColumns=["evidence_id"],childColumns=["evidence_id"],onDelete=ForeignKey.RESTRICT)],indices=[Index("profile_id"),Index("evidence_id"),Index(value=["profile_id","created_at"]),Index(value=["property_key","origin","superseded_by_characterization_id","profile_id"])])
data class OpticalCharacterizationEntity(
    @PrimaryKey @ColumnInfo(name="characterization_id") val characterizationId:String,
    @ColumnInfo(name="profile_id") val profileId:String,
    @ColumnInfo(name="property_key") val propertyKey:String,
    @ColumnInfo(name="value_text") val valueText:String?,
    val unit:String, @ColumnInfo(name="method_key") val methodKey:String,
    @ColumnInfo(name="method_version") val methodVersion:String?, val origin:String, val geometry:String?,
    @ColumnInfo(name="wall_thickness_mm") val wallThicknessMm:String?, @ColumnInfo(name="layer_height_mm") val layerHeightMm:String?,
    @ColumnInfo(name="evidence_id") val evidenceId:String?, val confidence:String?, val notes:String?,
    @ColumnInfo(name="environmental_notes") val environmentalNotes:String?,
    @ColumnInfo(name="measured_at") val measuredAt:Long?, val instrument:String?,
    @ColumnInfo(name="superseded_by_characterization_id") val supersededByCharacterizationId:String?,
    @ColumnInfo(name="created_at") val createdAt:Long,
)

@Entity(tableName = "appearance_assertions", foreignKeys = [ForeignKey(entity=FilamentProfileEntity::class,parentColumns=["profile_id"],childColumns=["profile_id"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=EvidenceReferenceEntity::class,parentColumns=["evidence_id"],childColumns=["evidence_id"],onDelete=ForeignKey.RESTRICT)], indices=[Index("profile_id"),Index("evidence_id"),Index(value=["profile_id","appearance_key"])])
data class AppearanceAssertionEntity(
    @PrimaryKey @ColumnInfo(name="assertion_id") val assertionId:String,
    @ColumnInfo(name="profile_id") val profileId:String,
    @ColumnInfo(name="appearance_key") val appearanceKey:String,
    @ColumnInfo(name="assertion_value") val assertionValue:String,
    @ColumnInfo(name="evidence_kind") val evidenceKind:String,
    @ColumnInfo(name="evidence_id") val evidenceId:String?,
    val confidence:String?, @ColumnInfo(name="asserted_at") val assertedAt:Long,
)

@Entity(tableName="full_spectrum_role_assertions", foreignKeys=[ForeignKey(entity=FilamentProfileEntity::class,parentColumns=["profile_id"],childColumns=["profile_id"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=EvidenceReferenceEntity::class,parentColumns=["evidence_id"],childColumns=["evidence_id"],onDelete=ForeignKey.RESTRICT)], indices=[Index("profile_id"),Index("evidence_id"),Index(value=["profile_id","role_key","assertion_state"]),Index("superseded_by_assertion_id"),Index(value=["role_key","assertion_state","superseded_by_assertion_id","profile_id"])])
data class FullSpectrumRoleAssertionEntity(
    @PrimaryKey @ColumnInfo(name="assertion_id") val assertionId:String,
    @ColumnInfo(name="profile_id") val profileId:String,
    @ColumnInfo(name="role_key") val roleKey:String,
    @ColumnInfo(name="role_category") val roleCategory:String,
    val authority:String, @ColumnInfo(name="assertion_state") val assertionState:String,
    @ColumnInfo(name="superseded_by_assertion_id") val supersededByAssertionId:String?,
    @ColumnInfo(name="evidence_id") val evidenceId:String?, val confidence:String?, val notes:String?,
    @ColumnInfo(name="asserted_at") val assertedAt:Long,
)

@Entity(tableName="full_spectrum_assessments", foreignKeys=[ForeignKey(entity=FilamentProfileEntity::class,parentColumns=["profile_id"],childColumns=["profile_id"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=FullSpectrumRoleAssertionEntity::class,parentColumns=["assertion_id"],childColumns=["role_assertion_id"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=EvidenceReferenceEntity::class,parentColumns=["evidence_id"],childColumns=["evidence_id"],onDelete=ForeignKey.RESTRICT)], indices=[Index("profile_id"),Index("role_assertion_id"),Index("evidence_id"),Index(value=["profile_id","assessment_kind","rating"]),Index(value=["rating","assessment_kind","superseded_by_assessment_id","profile_id"])])
data class FullSpectrumSuitabilityAssessmentEntity(
    @PrimaryKey @ColumnInfo(name="assessment_id") val assessmentId:String,
    @ColumnInfo(name="profile_id") val profileId:String,
    @ColumnInfo(name="assessment_kind") val assessmentKind:String, val rating:String,
    @ColumnInfo(name="scope_kind") val scopeKind:String,
    @ColumnInfo(name="role_assertion_id") val roleAssertionId:String?,
    @ColumnInfo(name="policy_id") val policyId:String?, @ColumnInfo(name="policy_version") val policyVersion:String?,
    @ColumnInfo(name="factor_refs") val factorRefs:String, val rationale:String,
    @ColumnInfo(name="evidence_id") val evidenceId:String?, val confidence:String?, val assessor:String,
    @ColumnInfo(name="assessed_at") val assessedAt:Long,
    @ColumnInfo(name="superseded_by_assessment_id") val supersededByAssessmentId:String?,
)

@Entity(tableName="full_spectrum_assessment_factors",primaryKeys=["assessment_id","factor_key"],foreignKeys=[ForeignKey(entity=FullSpectrumSuitabilityAssessmentEntity::class,parentColumns=["assessment_id"],childColumns=["assessment_id"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=EvidenceReferenceEntity::class,parentColumns=["evidence_id"],childColumns=["evidence_id"],onDelete=ForeignKey.RESTRICT)],indices=[Index("assessment_id"),Index("evidence_id")])
data class FullSpectrumAssessmentFactorEntity(
    @ColumnInfo(name="assessment_id") val assessmentId:String,@ColumnInfo(name="factor_key") val factorKey:String,
    @ColumnInfo(name="value_text") val valueText:String?,@ColumnInfo(name="evidence_id") val evidenceId:String?,val rationale:String?,
)

data class FullSpectrumProfileData(
    val opticalCharacterizations:List<OpticalCharacterizationEntity>,
    val appearances:List<AppearanceAssertionEntity>,
    val roles:List<FullSpectrumRoleAssertionEntity>,
    val suitability:List<FullSpectrumSuitabilityAssessmentEntity>,
)

data class FullSpectrumCandidateEvidence(
    @ColumnInfo(name="record_id") val recordId:String,
    @ColumnInfo(name="profile_id") val profileId:String,
    @ColumnInfo(name="role_key") val roleKey:String,
    val authority:String,
    @ColumnInfo(name="evidence_id") val evidenceId:String?,
)

@Dao interface FullSpectrumDao {
    @Upsert suspend fun putEvidence(value:EvidenceReferenceEntity)
    @Upsert suspend fun putOpticalCharacterization(value:OpticalCharacterizationEntity)
    @Upsert suspend fun putAppearance(value:AppearanceAssertionEntity)
    @Upsert suspend fun putRole(value:FullSpectrumRoleAssertionEntity)
    @Upsert suspend fun putSuitability(value:FullSpectrumSuitabilityAssessmentEntity)
    @Upsert suspend fun putAssessmentFactors(values:List<FullSpectrumAssessmentFactorEntity>)
    @Query("UPDATE optical_characterizations SET superseded_by_characterization_id=:replacementId WHERE profile_id=:profileId AND property_key='transmission_distance' AND superseded_by_characterization_id IS NULL")
    suspend fun supersedeCurrentTransmissionDistance(profileId:String,replacementId:String)
    @Query("SELECT * FROM optical_characterizations WHERE profile_id=:profileId ORDER BY created_at,characterization_id") suspend fun opticalCharacterizations(profileId:String):List<OpticalCharacterizationEntity>
    @Query("SELECT * FROM appearance_assertions WHERE profile_id=:profileId ORDER BY asserted_at,assertion_id") suspend fun appearances(profileId:String):List<AppearanceAssertionEntity>
    @Query("SELECT * FROM full_spectrum_role_assertions WHERE profile_id=:profileId ORDER BY asserted_at,assertion_id") suspend fun roles(profileId:String):List<FullSpectrumRoleAssertionEntity>
    @Query("SELECT * FROM full_spectrum_assessments WHERE profile_id=:profileId ORDER BY assessed_at,assessment_id") suspend fun suitability(profileId:String):List<FullSpectrumSuitabilityAssessmentEntity>
    @Query("""
        SELECT DISTINCT fp.legacy_record_id FROM filament_profiles fp
        WHERE fp.legacy_record_id IS NOT NULL
          AND (:roleKey IS NULL OR EXISTS (SELECT 1 FROM full_spectrum_role_assertions r WHERE r.profile_id=fp.profile_id AND r.role_key=:roleKey AND r.assertion_state='ACTIVE' AND r.superseded_by_assertion_id IS NULL))
          AND (:tdOrigin IS NULL AND :tdMinimum IS NULL AND :tdMaximum IS NULL OR EXISTS (SELECT 1 FROM optical_characterizations o WHERE o.profile_id=fp.profile_id AND o.property_key='transmission_distance' AND o.value_text IS NOT NULL AND o.superseded_by_characterization_id IS NULL AND (:tdOrigin IS NULL OR o.origin=:tdOrigin) AND (:tdMinimum IS NULL OR CAST(o.value_text AS REAL)>=:tdMinimum) AND (:tdMaximum IS NULL OR CAST(o.value_text AS REAL)<=:tdMaximum)))
          AND (:suitability IS NULL OR EXISTS (SELECT 1 FROM full_spectrum_assessments a WHERE a.profile_id=fp.profile_id AND a.rating=:suitability AND a.superseded_by_assessment_id IS NULL))
          AND (:testState IS NULL OR (:testState='TESTED' AND EXISTS (SELECT 1 FROM full_spectrum_assessments a WHERE a.profile_id=fp.profile_id AND a.assessment_kind='OBSERVED' AND a.rating NOT IN ('UNKNOWN','UNTESTED') AND a.superseded_by_assessment_id IS NULL)) OR (:testState='UNTESTED' AND NOT EXISTS (SELECT 1 FROM full_spectrum_assessments a WHERE a.profile_id=fp.profile_id AND a.assessment_kind='OBSERVED' AND a.rating NOT IN ('UNKNOWN','UNTESTED') AND a.superseded_by_assessment_id IS NULL)))
          AND (:ownership IS NULL OR (:ownership='OWNED' AND EXISTS (SELECT 1 FROM physical_spools s WHERE s.profile_id=fp.profile_id)) OR (:ownership='PROFILE_ONLY' AND NOT EXISTS (SELECT 1 FROM physical_spools s WHERE s.profile_id=fp.profile_id)))
        ORDER BY fp.updated_at DESC, fp.profile_id LIMIT :limit
    """) suspend fun searchRecordIds(roleKey:String?,tdOrigin:String?,tdMinimum:Double?,tdMaximum:Double?,suitability:String?,testState:String?,ownership:String?,limit:Int=100):List<String>
    @Query("""
        SELECT fp.legacy_record_id AS record_id,fp.profile_id,r.role_key,r.authority,r.evidence_id
        FROM full_spectrum_role_assertions r JOIN filament_profiles fp ON fp.profile_id=r.profile_id
        WHERE fp.legacy_record_id IS NOT NULL AND r.role_key=:roleKey AND r.assertion_state='ACTIVE' AND r.superseded_by_assertion_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM full_spectrum_role_assertions newer WHERE newer.profile_id=r.profile_id AND newer.role_key=r.role_key AND newer.assertion_state='ACTIVE' AND newer.superseded_by_assertion_id IS NULL AND (newer.asserted_at>r.asserted_at OR (newer.asserted_at=r.asserted_at AND newer.assertion_id>r.assertion_id)))
        ORDER BY r.authority,fp.updated_at DESC,fp.profile_id LIMIT :limit
    """) suspend fun alternateCandidates(roleKey:String,limit:Int=20):List<FullSpectrumCandidateEvidence>
}

internal fun addFullSpectrumSearchIndexesV7(db:SupportSQLiteDatabase) {
    db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_observations_field_name_value_text_profile_id ON profile_observations(field_name,value_text,profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_optical_characterizations_property_key_origin_superseded_by_characterization_id_profile_id ON optical_characterizations(property_key,origin,superseded_by_characterization_id,profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_role_assertions_role_key_assertion_state_superseded_by_assertion_id_profile_id ON full_spectrum_role_assertions(role_key,assertion_state,superseded_by_assertion_id,profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessments_rating_assessment_kind_superseded_by_assessment_id_profile_id ON full_spectrum_assessments(rating,assessment_kind,superseded_by_assessment_id,profile_id)")
}

internal fun migrateFullSpectrumV6(db:SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS evidence_references (evidence_id TEXT NOT NULL,kind TEXT NOT NULL,provider TEXT NOT NULL,record_id TEXT,revision TEXT,url TEXT,retrieved_at INTEGER,notes TEXT,PRIMARY KEY(evidence_id))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_references_kind_provider_record_id ON evidence_references(kind,provider,record_id)")
    db.execSQL("INSERT OR IGNORE INTO evidence_references(evidence_id,kind,provider,record_id,revision,url,retrieved_at,notes) SELECT 'legacy-evidence:'||measurement_id,evidence_kind,source_provider,source_record_id,source_revision,NULL,created_at,'Migrated without upgrading legacy certainty' FROM transmission_distance_measurements")
    db.execSQL("CREATE TABLE IF NOT EXISTS optical_characterizations (characterization_id TEXT NOT NULL,profile_id TEXT NOT NULL,property_key TEXT NOT NULL,value_text TEXT,unit TEXT NOT NULL,method_key TEXT NOT NULL,method_version TEXT,origin TEXT NOT NULL,geometry TEXT,wall_thickness_mm TEXT,layer_height_mm TEXT,evidence_id TEXT,confidence TEXT,notes TEXT,environmental_notes TEXT,measured_at INTEGER,instrument TEXT,superseded_by_characterization_id TEXT,created_at INTEGER NOT NULL,PRIMARY KEY(characterization_id),FOREIGN KEY(profile_id) REFERENCES filament_profiles(profile_id) ON DELETE CASCADE,FOREIGN KEY(evidence_id) REFERENCES evidence_references(evidence_id) ON DELETE RESTRICT)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_optical_characterizations_profile_id ON optical_characterizations(profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_optical_characterizations_evidence_id ON optical_characterizations(evidence_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_optical_characterizations_profile_id_created_at ON optical_characterizations(profile_id,created_at)")
    migrateLegacyTdCharacterizations(db)
    db.execSQL("CREATE TABLE IF NOT EXISTS appearance_assertions (assertion_id TEXT NOT NULL,profile_id TEXT NOT NULL,appearance_key TEXT NOT NULL,assertion_value TEXT NOT NULL,evidence_kind TEXT NOT NULL,evidence_id TEXT,confidence TEXT,asserted_at INTEGER NOT NULL,PRIMARY KEY(assertion_id),FOREIGN KEY(profile_id) REFERENCES filament_profiles(profile_id) ON DELETE CASCADE,FOREIGN KEY(evidence_id) REFERENCES evidence_references(evidence_id) ON DELETE RESTRICT)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_appearance_assertions_profile_id ON appearance_assertions(profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_appearance_assertions_evidence_id ON appearance_assertions(evidence_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_appearance_assertions_profile_id_appearance_key ON appearance_assertions(profile_id,appearance_key)")
    db.execSQL("CREATE TABLE IF NOT EXISTS full_spectrum_role_assertions (assertion_id TEXT NOT NULL,profile_id TEXT NOT NULL,role_key TEXT NOT NULL,role_category TEXT NOT NULL,authority TEXT NOT NULL,assertion_state TEXT NOT NULL,superseded_by_assertion_id TEXT,evidence_id TEXT,confidence TEXT,notes TEXT,asserted_at INTEGER NOT NULL,PRIMARY KEY(assertion_id),FOREIGN KEY(profile_id) REFERENCES filament_profiles(profile_id) ON DELETE CASCADE,FOREIGN KEY(evidence_id) REFERENCES evidence_references(evidence_id) ON DELETE RESTRICT)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_role_assertions_profile_id ON full_spectrum_role_assertions(profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_role_assertions_evidence_id ON full_spectrum_role_assertions(evidence_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_role_assertions_profile_id_role_key_assertion_state ON full_spectrum_role_assertions(profile_id,role_key,assertion_state)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_role_assertions_superseded_by_assertion_id ON full_spectrum_role_assertions(superseded_by_assertion_id)")
    db.execSQL("CREATE TABLE IF NOT EXISTS full_spectrum_assessments (assessment_id TEXT NOT NULL,profile_id TEXT NOT NULL,assessment_kind TEXT NOT NULL,rating TEXT NOT NULL,scope_kind TEXT NOT NULL,role_assertion_id TEXT,policy_id TEXT,policy_version TEXT,factor_refs TEXT NOT NULL,rationale TEXT NOT NULL,evidence_id TEXT,confidence TEXT,assessor TEXT NOT NULL,assessed_at INTEGER NOT NULL,superseded_by_assessment_id TEXT,PRIMARY KEY(assessment_id),FOREIGN KEY(profile_id) REFERENCES filament_profiles(profile_id) ON DELETE CASCADE,FOREIGN KEY(role_assertion_id) REFERENCES full_spectrum_role_assertions(assertion_id) ON DELETE RESTRICT,FOREIGN KEY(evidence_id) REFERENCES evidence_references(evidence_id) ON DELETE RESTRICT,CHECK ((scope_kind='PROFILE' AND role_assertion_id IS NULL) OR (scope_kind='ROLE' AND role_assertion_id IS NOT NULL)))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessments_profile_id ON full_spectrum_assessments(profile_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessments_role_assertion_id ON full_spectrum_assessments(role_assertion_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessments_evidence_id ON full_spectrum_assessments(evidence_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessments_profile_id_assessment_kind_rating ON full_spectrum_assessments(profile_id,assessment_kind,rating)")
    db.execSQL("CREATE TABLE IF NOT EXISTS full_spectrum_assessment_factors (assessment_id TEXT NOT NULL,factor_key TEXT NOT NULL,value_text TEXT,evidence_id TEXT,rationale TEXT,PRIMARY KEY(assessment_id,factor_key),FOREIGN KEY(assessment_id) REFERENCES full_spectrum_assessments(assessment_id) ON DELETE CASCADE,FOREIGN KEY(evidence_id) REFERENCES evidence_references(evidence_id) ON DELETE RESTRICT)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessment_factors_assessment_id ON full_spectrum_assessment_factors(assessment_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_full_spectrum_assessment_factors_evidence_id ON full_spectrum_assessment_factors(evidence_id)")
}

private fun migrateLegacyTdCharacterizations(db: SupportSQLiteDatabase) {
    db.query("SELECT measurement_id,profile_id,value_text,evidence_kind,measured_at,instrument,created_at FROM transmission_distance_measurements").use { rows ->
        val insert = "INSERT OR IGNORE INTO optical_characterizations(characterization_id,profile_id,property_key,value_text,unit,method_key,method_version,origin,geometry,wall_thickness_mm,layer_height_mm,evidence_id,confidence,notes,environmental_notes,measured_at,instrument,superseded_by_characterization_id,created_at) VALUES (?,?,?,?,?,'LEGACY_UNSPECIFIED',NULL,?,NULL,NULL,NULL,?,NULL,?,NULL,?,?,NULL,?)"
        while (rows.moveToNext()) {
            val measurementId = rows.getString(0)
            val rawValue = rows.getString(2)
            val numericValue = rawValue.trim().toBigDecimalOrNull()
                ?.takeIf { it >= java.math.BigDecimal("0.1") && it <= java.math.BigDecimal("100") }
            val accepted = numericValue != null
            db.execSQL(insert, arrayOf<Any?>(
                (if (accepted) "legacy-optical:" else "legacy-optical-quarantine:") + measurementId,
                rows.getString(1),
                if (accepted) "transmission_distance" else "legacy_transmission_distance_unparsed",
                rawValue,
                if (accepted) "mm" else "",
                TdOrigin.fromLegacy(rows.getString(3)).name,
                "legacy-evidence:$measurementId",
                if (accepted) null else "Quarantined during v5-to-v6 migration; not accepted as numeric TD",
                if (rows.isNull(4)) null else rows.getLong(4),
                if (rows.isNull(5)) null else rows.getString(5),
                rows.getLong(6),
            ))
        }
    }
}
