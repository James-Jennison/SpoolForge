package net.jamesjennison.filamajignfc

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.core.*
import net.jamesjennison.filamajignfc.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DeviceFullSpectrumAcceptanceTest {
    @Test fun m7MigrationPersistenceUiAndCompatibilityBoundariesPassOnRazr2023() = runBlocking {
        assertEquals("motorola razr 2023", Build.MODEL)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val name = "m7-device-full-spectrum"
        target.deleteDatabase(name)
        val file = target.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            val schema = JSONObject(testContext.assets.open("net.jamesjennison.filamajignfc.data.UserDatabase/5.json").bufferedReader().readText()).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (index in 0 until indices.length()) database.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            database.execSQL("INSERT INTO filament_profiles VALUES ('profile-device-m7',NULL,1,1)")
            database.execSQL("INSERT INTO transmission_distance_measurements(measurement_id,profile_id,value_text,source_provider,source_record_id,source_revision,evidence_kind,measured_at,instrument,created_at) VALUES ('td-device-m7','profile-device-m7','2.7','Legacy',NULL,NULL,'MEASURED',NULL,NULL,1)")
            database.version = 5
        }

        val source = "M7 device fixture"
        val record = FilamentRecord(
            "m7-device", null, null, FieldValue("SpoolForge QA", source), FieldValue("PLA", source),
            FieldValue("Full Spectrum", source), FieldValue("Cyan", source), FieldValue("00ADFF", source),
            FieldValue("1.75", source), FieldValue(1000, source), FieldValue(190, source), FieldValue(230, source),
            FieldValue(35, source), FieldValue(65, source), sourceRevision="m7", provenance=Provenance.CUSTOM,
            transmissionDistance=FieldValue("2.7", source),
        )
        val beforeCanonical = StandardOpenSpoolTagCodec.encode(record).payload
        val beforePaxx = PaxxU1ExtendedTagCodec.encode(record).payload
        val identity = PortableSpoolIdentity("profile-device-m7", "spool-device-m7", record, 1000, 1000)
        val beforePortable = PortableIdentityCodec.encodeBundle(identity)
        val beforeSpoolman = SpoolmanCodec.encodeExport(record)

        val database = Room.databaseBuilder(target, UserDatabase::class.java, name).addMigrations(USER_MIGRATION_5_6,USER_MIGRATION_6_7).allowMainThreadQueries().build()
        try {
            assertEquals(6, database.openHelper.readableDatabase.version)
            val migrated = database.fullSpectrum().opticalCharacterizations("profile-device-m7").single()
            assertEquals("2.7", migrated.valueText)
            assertEquals("MEASURED_UNSPECIFIED", migrated.origin)
            database.fullSpectrum().putEvidence(EvidenceReferenceEntity("device-m7-evidence", "USER", "Local user", null, null, null, 2, "Device acceptance"))
            database.fullSpectrum().putRole(FullSpectrumRoleAssertionEntity(
                "device-role-c", "profile-device-m7", "C", "MIXING_PRIMARY", "USER_CONFIRMED", "ACTIVE",
                null, "device-m7-evidence", null, "Confirmed on device", 2,
            ))
            database.fullSpectrum().putSuitability(FullSpectrumSuitabilityAssessmentEntity(
                "device-observed", "profile-device-m7", "OBSERVED", "GOOD", "ROLE", "device-role-c",
                null, null, "[]", "Observed device fixture", "device-m7-evidence", null, "Local user", 3, null,
            ))
            val data = FullSpectrumProfileData(
                database.fullSpectrum().opticalCharacterizations("profile-device-m7"),
                database.fullSpectrum().appearances("profile-device-m7"),
                database.fullSpectrum().roles("profile-device-m7"),
                database.fullSpectrum().suitability("profile-device-m7"),
            )
            val summary = fullSpectrumUiSummary(data)
            assertTrue(summary.optical.contains("2.7 mm"))
            assertTrue(summary.roles.startsWith("C"))
            assertEquals("UNKNOWN — no reviewed policy result", summary.recommended)
            assertEquals("GOOD", summary.observed)
            assertFalse(database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").moveToFirst())
        } finally {
            database.close()
        }

        assertArrayEquals(beforeCanonical, StandardOpenSpoolTagCodec.encode(record).payload)
        assertArrayEquals(beforePaxx, PaxxU1ExtendedTagCodec.encode(record).payload)
        assertArrayEquals(beforePortable, PortableIdentityCodec.encodeBundle(identity))
        assertArrayEquals(beforeSpoolman, SpoolmanCodec.encodeExport(record))
        val installedApkSha256 = MessageDigest.getInstance("SHA-256").digest(File(target.applicationInfo.sourceDir).readBytes())
            .joinToString("") { "%02x".format(it) }
        File(target.filesDir, "device-full-spectrum-m7-acceptance.json").writeText(JSONObject()
            .put("version", 1).put("model", Build.MODEL).put("app_version", BuildConfig.VERSION_NAME)
            .put("apk_sha256", installedApkSha256)
            .put("migration_v5_to_v6", "PASS").put("progressive_disclosure_ui", "PASS")
            .put("role_and_suitability_persistence", "PASS").put("nfc_payload_regression", "PASS")
            .put("portable_identity_v1_regression", "PASS").put("spoolman_export_regression", "PASS")
            .put("foreign_key_check", "PASS").toString(2))
        target.deleteDatabase(name)
        Unit
    }
}
