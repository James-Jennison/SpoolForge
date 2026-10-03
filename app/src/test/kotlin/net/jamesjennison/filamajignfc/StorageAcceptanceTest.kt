package net.jamesjennison.filamajignfc

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageAcceptanceTest {
    private inline fun <T : androidx.room.RoomDatabase> T.use(block: (T) -> Unit) {
        try { block(this) } finally { close() }
    }

    private val context get() = RuntimeEnvironment.getApplication()

    private fun legacyDatabase(name: String, schema: String): SQLiteDatabase {
        return schemaDatabase(name,schema,1)
    }

    private fun schemaDatabase(name:String,schema:String,version:Int):SQLiteDatabase {
        val file = context.getDatabasePath(name)
        file.delete()
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val document = JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.$schema/$version.json")!!.bufferedReader().readText()).getJSONObject("database")
        val entities = document.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        }
        for (i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i)
            val indices=entity.optJSONArray("indices") ?: continue
            for(j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
        }
        val setup = document.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    @Test fun queuedArchiveCannotRunAfterDisposal() {
        val journal = WriteJournal(context)
        val uid = byteArrayOf(1, 2, 3)
        val payload = byteArrayOf(4, 5, 6)
        assertTrue(journal.save(uid, payload))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        executor.execute { entered.countDown(); release.await() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val nfc = NfcCoordinator(context, executor)
        try {
            nfc.archiveUnknown() // queued behind a deterministic barrier
            nfc.shutdown()
        } finally { release.countDown(); executor.shutdown() }
        assertTrue(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertArrayEquals(uid, journal.load()!!.uid)
        assertArrayEquals(payload, journal.load()!!.payload)
        assertTrue(journal.archives().isEmpty())
        assertEquals(net.jamesjennison.filamajignfc.core.WritePhase.WRITE_OUTCOME_UNKNOWN, nfc.state.phase)
    }

    @Test fun emptyTagReadIsDistinguishedFromAmbiguousContents() {
        val nfc = NfcCoordinator(context)
        try {
            val blank = nfc.decodeSingleOpenSpool(null) as net.jamesjennison.filamajignfc.core.DecodeResult.Rejected
            assertTrue(blank.reason.startsWith("No NDEF message returned"))
            val record = android.nfc.NdefRecord.createMime("application/json", "{}".toByteArray())
            assertEquals("no message (null)", nfc.messageSummary(null))
            val summary = nfc.messageSummary(android.nfc.NdefMessage(arrayOf(record)))
            assertTrue(summary.startsWith("1 record(s),"))
            assertFalse(summary.contains("{}"))
            assertTrue(summary.contains("SHA256"))
            val multiple = nfc.decodeSingleOpenSpool(android.nfc.NdefMessage(arrayOf(record, record))) as net.jamesjennison.filamajignfc.core.DecodeResult.Rejected
            assertTrue(multiple.reason.startsWith("Multiple NDEF records"))
        } finally { nfc.shutdown() }
    }

    @Test fun postWriteVerifierRetriesOnlyReadsUntilMessageAppears() {
        val expected = android.nfc.NdefMessage(arrayOf(android.nfc.NdefRecord.createMime("application/json", "{}".toByteArray())))
        val reads = ArrayDeque<android.nfc.NdefMessage?>(listOf(null, null, expected))
        val pauses = mutableListOf<Long>()
        val actual = readFreshNdefWithRetries(pause = pauses::add) { reads.removeFirst() }
        assertSame(expected, actual)
        assertEquals(listOf(180L, 180L), pauses)
        assertTrue(reads.isEmpty())
    }

    @Test fun postWriteVerifierStopsAfterBoundedNullReads() {
        var reads = 0
        val actual = readFreshNdefWithRetries(attempts = 3, pause = { _ -> }) { reads++; null }
        assertNull(actual)
        assertEquals(3, reads)
    }

    @Test fun journalDistinguishesVerificationPendingFromUnknownAcrossRestart() {
        val journal = WriteJournal(context)
        val binding=WriteBindingContext("spool:one","openspool-paxx-u1-1.0")
        assertTrue(journal.save(byteArrayOf(7), byteArrayOf(8), verificationPending = true, bindingContext = binding, tagNumber = 2, cfsTrailerVerificationRequired = true))
        val restored=WriteJournal(context).load()!!
        assertTrue(restored.verificationPending)
        assertEquals(binding,restored.bindingContext)
        assertEquals(2,restored.tagNumber)
        assertTrue(restored.cfsTrailerVerificationRequired)
        assertTrue(journal.save(byteArrayOf(7), byteArrayOf(8), verificationPending = false))
        assertFalse(WriteJournal(context).load()!!.verificationPending)
        assertNull(WriteJournal(context).load()!!.bindingContext)
        assertEquals(1,WriteJournal(context).load()!!.tagNumber)
        assertFalse(WriteJournal(context).load()!!.cfsTrailerVerificationRequired)
    }

    @Test fun verifiedBindingCallbackFailureRetainsReadOnlyRetryContextUntilPersistenceSucceeds() {
        val journal=WriteJournal(context)
        journal.clear()
        val binding=WriteBindingContext("spool:retry","openspool-paxx-u1-1.0")
        val write=VerifiedTagWrite(binding,byteArrayOf(0x0A,0x0B),"verified".encodeToByteArray(),2)
        val failed=finalizeVerifiedBinding(write,{ throw java.io.IOException("database unavailable") },journal)
        assertEquals(BindingPersistence.RETRY_REQUIRED,failed.persistence)
        val retained=journal.load()!!
        assertTrue(retained.verificationPending)
        assertEquals(binding,retained.bindingContext)
        assertEquals(2,retained.tagNumber)
        assertArrayEquals(write.uid,retained.uid)
        assertArrayEquals(write.payload,retained.payload)
        var saved:VerifiedTagWrite?=null
        val retried=finalizeVerifiedBinding(write,{ saved=it },journal)
        assertEquals(BindingPersistence.SAVED,retried.persistence)
        assertEquals(write,saved)
        assertNull(journal.load())
    }

    @Test fun allUiCommandsAfterCoordinatorDisposalAreHarmless() {
        val nfc = NfcCoordinator(context)
        nfc.shutdown()
        val source = net.jamesjennison.filamajignfc.core.FieldValue("Test", "test")
        val record = net.jamesjennison.filamajignfc.core.FilamentRecord("p", null, null, source, source, source, source,
            net.jamesjennison.filamajignfc.core.FieldValue("FF8800", "test"), net.jamesjennison.filamajignfc.core.FieldValue("1.75", "test"),
            net.jamesjennison.filamajignfc.core.FieldValue(1000, "test"), null, null, null, null, sourceRevision="test", provenance=net.jamesjennison.filamajignfc.core.Provenance.CUSTOM)
        nfc.arm(net.jamesjennison.filamajignfc.core.OpenSpoolCodec.encode(record, net.jamesjennison.filamajignfc.core.OpenSpoolProfile.CANONICAL))
        nfc.approveOverwrite(); nfc.cancel(); nfc.archiveUnknown()
        assertEquals(net.jamesjennison.filamajignfc.core.WritePhase.DRAFT, nfc.state.phase)
    }

    @Test fun unresolvedArchiveRetainsEveryEntryBeyondTwenty() {
        assertEquals("ABFF00", byteArrayOf(0xAB.toByte(), 0xFF.toByte(), 0).joinToString("") { "%02X".format(it) })
        val journal = WriteJournal(context)
        assertTrue(journal.save(byteArrayOf(99), byteArrayOf(1, 2, 3)))
        for (i in 0..24) assertTrue(journal.archive(byteArrayOf(i.toByte()), byteArrayOf(1, 2, 3)))
        assertNull(WriteJournal(context).load())
        assertEquals(25, WriteJournal(context).archives().size)
        assertEquals((0..24).toSet(), WriteJournal(context).archives().map { it.uid.single().toInt() }.toSet())
        assertEquals(25, WriteJournal(context).archiveCount())
        val prefs = context.getSharedPreferences("nfc-write-journal", 0)
        for (i in 0..24) assertTrue("Missing unresolved entry $i", prefs.contains("archive_$i"))
    }

    @Test fun missingCatalogValuesDoNotClaimFieldProvenance() {
        val entry = CatalogEntry("p", "v", "product", "Owner", "PLA", "Example", "", "FF8800", "1.75", 1000, null, null, null, null, null, null, "pinned")
        val item = entry.asCatalogItem()
        assertEquals("", item.source("nozzleMin"))
        assertEquals("", item.source("colorName"))
        assertEquals("OFD pinned", item.source("brand"))
    }

    @Test fun recentSnapshotRetainsTemperatureColorsAndSources() {
        val entry = CatalogEntry("p", "v", "product", "Owner", "ABS", "Example", "Orange", "FF8800", "1.75", 1000, 230, 260, 90, 110, "gtin", "article", "pinned", "FFFFFF,000000")
        val sources = listOf("brand", "material", "product", "colorName", "colorHex", "diameter", "mass", "nozzleMin", "nozzleMax", "bedMin", "bedMax", "transmissionDistance").associateWith { "Source for $it" }
        val item = FilamentItem(entry, net.jamesjennison.filamajignfc.core.Provenance.CATALOG_EDITED, sources, "original", transmissionDistance = "6.6")
        assertEquals(item, decodeSnapshot(encodeSnapshot(item)))
        assertNull(decodeSnapshot("{\"package_id\":\"legacy\",\"source_revision\":\"e3888b68\"}"))
    }

    @Test fun recentSnapshotRecoversCompleteRetailCodeFromLegacyScanEvidence() {
        val entry = CatalogEntry("scan", "", "", "GRYDDLE", "PLA+", "GRAY PLA+ • 1.75 mm", "Gray", "888888", "1.75", 1100, 200, 210, 55, 60, null, null, "ai-label-scan")
        val evidence = "GTIN (UPC_A): 027680274484\nGTIN (EAN_8): 22117762\nGTIN (UPC_E): 04481867"
        val item = FilamentItem(entry, net.jamesjennison.filamajignfc.core.Provenance.CUSTOM, emptyMap(), barcodeEvidence = evidence)
        assertEquals("00027680274484", decodeSnapshot(encodeSnapshot(item))?.entry?.gtin)
    }

    @Test fun paxxTagImportRetainsTransmissionDistanceSourceAndDefaults() {
        val decoded = net.jamesjennison.filamajignfc.core.OpenSpoolCodec.decode("""{"protocol":"openspool","version":"1.0","brand":"SUNLU","type":"PLA","color_hex":"FF0000","transmission_distance":6.6}""".encodeToByteArray()) as net.jamesjennison.filamajignfc.core.DecodeResult.Supported
        val seed = decodedSeedFromValues(decoded.values)
        assertEquals("6.6", seed.transmissionDistance)
        assertEquals("OpenSpool tag", seed.sources["transmissionDistance"])
        assertEquals("1.75", seed.diameter)
        assertEquals("1000", seed.mass)
        assertEquals(ASSUMED_DEFAULT_SOURCE, seed.sources["diameter"])
        assertEquals(ASSUMED_DEFAULT_SOURCE, seed.sources["mass"])
    }

    @Test fun blankPhysicalValuesReceiveDefaultsAndExplicitValuesSurvive() {
        val defaulted = withPhysicalDefaults(mapOf("diameter" to "", "mass" to ""))
        assertEquals("1.75", defaulted["diameter"])
        assertEquals("1000", defaulted["mass"])
        val explicit = withPhysicalDefaults(mapOf("diameter" to "2.85", "mass" to "750"))
        assertEquals("2.85", explicit["diameter"])
        assertEquals("750", explicit["mass"])
    }

    @Test fun scannedQrEvidenceIsDistinctFromCatalogBarcodeEvidence() {
        val qr = "QR payload (QR_CODE): https://spooldb.com/f/9403"
        assertFalse(isCatalogBarcodeEvidence(qr))
        assertEquals("QR payload: https://spooldb.com/f/9403", scannedCodeSummary(qr))
        val catalog = """{"candidate_count":1,"snapshot":{"source":"OFD"}}"""
        assertTrue(isCatalogBarcodeEvidence(catalog))
    }

    @Test fun retailGtinUsesHumanReadableDecodedSymbolWhenAvailable() {
        val evidence = "GTIN (UPC_A) [profile_label]: 027680274484\nQR payload (QR_CODE): {}"
        assertEquals("027680274484 (UPC-A)", retailGtinDisplay("00027680274484", evidence))
        assertEquals("027680274484 (UPC-A)", retailGtinDisplay(null, evidence))
        val existingScanEvidence = evidence + "\nGTIN (EAN_8): 22117762\nGTIN (UPC_E): 04481867"
        assertEquals("00027680274484", recoveredGtinFromEvidence(existingScanEvidence))
        assertEquals("027680274484 (UPC-A)", retailGtinDisplay(null, existingScanEvidence))
        assertTrue(scannedCodeSummary(existingScanEvidence).contains("QR/RFID profile"))
        assertFalse(scannedCodeSummary(existingScanEvidence).contains("22117762"))
        assertFalse(scannedCodeSummary(existingScanEvidence).contains("04481867"))
    }

    @Test fun versionTwoMigrationPreservesEditsAndStoresBarcodeEvidence() = runBlocking {
        val file=context.getDatabasePath("gtin-migration-user");file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.UserDatabase/2.json")!!.bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) { val e=entities.getJSONObject(i);db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName"))) }
            val values=android.content.ContentValues()
            db.rawQuery("PRAGMA table_info(custom_records)",null).use { c -> while(c.moveToNext()) { val name=c.getString(1);if(c.getInt(3)==1) { if(c.getString(2)=="INTEGER") values.put(name,0) else values.put(name,"") } } }
            values.put("id","keep-local");values.put("brand","SUNLU");values.put("nozzle_min_c",235);values.put("nozzle_min_source","Edited locally");db.insertOrThrow("custom_records",null,values);db.version=2
        }
        val evidence=GtinIndex(context).lookup("7340002119380").first().barcodeEvidence
        Room.databaseBuilder(context,UserDatabase::class.java,"gtin-migration-user").addMigrations(USER_MIGRATION_2_3, USER_MIGRATION_3_4, USER_MIGRATION_4_5, USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            val item=db.user().byId("keep-local")!!;assertEquals(235,item.nozzleMinC);assertEquals("Edited locally",item.nozzleMinSource);assertEquals("",item.barcodeEvidence)
            db.user().save(item.copy(barcodeEvidence=evidence))
        }
        Room.databaseBuilder(context,UserDatabase::class.java,"gtin-migration-user").allowMainThreadQueries().build().use { db ->
            assertEquals(evidence,db.user().byId("keep-local")!!.barcodeEvidence)
            db.user().recent(Recent("keep-local","CUSTOM","{}",1))
            LocalFilamentRepository(db).delete("keep-local")
            assertNull(db.user().byId("keep-local"))
            assertTrue(db.user().recents().first().isEmpty())
        }
    }

    @Test fun userMigrationPreservesCustomAndLegacyRecent() = runBlocking {
        legacyDatabase("migration-user", "UserDatabase").use { db ->
            db.execSQL("INSERT INTO custom_records VALUES ('local-1','Owner','PLA','Sample','Orange','FF8800','1.75',1000,123)")
            db.execSQL("INSERT INTO recents VALUES ('old-package','catalog','{\"package_id\":\"old-package\",\"source_revision\":\"e3888b68\"}',456)")
        }
        Room.databaseBuilder(context, UserDatabase::class.java, "migration-user").addMigrations(USER_MIGRATION_1_2, USER_MIGRATION_2_3, USER_MIGRATION_3_4, USER_MIGRATION_4_5, USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            db.openHelper.writableDatabase.query("SELECT brand,color_hex,mass_g,updated_at,nozzle_min_c,brand_source FROM custom_records").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("Owner", row.getString(0)); assertEquals("FF8800", row.getString(1)); assertEquals(1000, row.getInt(2)); assertEquals(123, row.getInt(3)); assertTrue(row.isNull(4)); assertEquals("User", row.getString(5))
            }
            val catalog = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).allowMainThreadQueries().build()
            try {
                val legacy = Recent("local-1", "custom", "{\"package_id\":\"local-1\",\"source_revision\":\"user\"}", 789)
                val restored = hydrateLegacyRecent(legacy, catalog.catalog(), db.user())
                assertEquals("Owner", restored!!.entry.brand)
                assertEquals(1000, restored.entry.massG)
                assertEquals("User", restored.source("brand"))
            } finally { catalog.close() }
            db.openHelper.writableDatabase.query("SELECT snapshot_json,used_at FROM recents WHERE record_id='old-package'").use { row ->
                assertTrue(row.moveToFirst()); assertTrue(row.getString(0).contains("old-package")); assertEquals(456, row.getInt(1))
            }
        }
    }

    @Test fun transmissionDistanceMigrationPreservesExistingRecords() = runBlocking {
        val file=context.getDatabasePath("td-migration-user");file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.UserDatabase/3.json")!!.bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) { val e=entities.getJSONObject(i);db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName"))) }
            val values=android.content.ContentValues()
            db.rawQuery("PRAGMA table_info(custom_records)",null).use { c -> while(c.moveToNext()) { val name=c.getString(1);if(c.getInt(3)==1) { if(c.getString(2)=="INTEGER") values.put(name,0) else values.put(name,"") } } }
            values.put("id","keep-before-td");values.put("brand","SUNLU");values.put("material","PLA");db.insertOrThrow("custom_records",null,values);db.version=3
        }
        Room.databaseBuilder(context,UserDatabase::class.java,"td-migration-user").addMigrations(USER_MIGRATION_3_4, USER_MIGRATION_4_5, USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            val before=db.user().byId("keep-before-td")!!
            assertNull(before.transmissionDistance);assertNull(before.transmissionDistanceSource)
            db.user().save(before.copy(transmissionDistance="6.6",transmissionDistanceSource="Measured locally"))
        }
        Room.databaseBuilder(context,UserDatabase::class.java,"td-migration-user").allowMainThreadQueries().build().use { db ->
            val after=db.user().byId("keep-before-td")!!
            assertEquals("6.6",after.transmissionDistance);assertEquals("Measured locally",after.transmissionDistanceSource)
        }
    }

    @Test fun canonicalMigrationBackfillsEveryLegacyValueWithoutChangingTheLegacyRow() = runBlocking {
        val file = context.getDatabasePath("canonical-migration-user")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.UserDatabase/4.json")!!.bufferedReader().readText()).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val values = android.content.ContentValues()
            db.rawQuery("PRAGMA table_info(custom_records)", null).use { columns ->
                while (columns.moveToNext()) {
                    if (columns.getInt(3) == 1) {
                        if (columns.getString(2) == "INTEGER") values.put(columns.getString(1), 0) else values.put(columns.getString(1), "")
                    }
                }
            }
            values.put("id", "saved-1")
            values.put("original_package_id", "ofd-package")
            values.put("variant_id", "variant-7")
            values.put("product_id", "product-9")
            values.put("brand", "SUNLU")
            values.put("material", "ABS")
            values.put("product", "ABS Basic")
            values.put("color_name", "Orange")
            values.put("color_hex", "FF8E24")
            values.put("diameter_mm", "1.75")
            values.put("mass_g", 1000)
            values.put("nozzle_min_c", 235)
            values.put("nozzle_max_c", 260)
            values.put("bed_min_c", 90)
            values.put("bed_max_c", 110)
            values.put("gtin", "6933582308674")
            values.put("article_number", "SLU-24156")
            values.put("additional_color_hexes", "FFFFFF,000000")
            values.put("source_revision", "e3888b68")
            values.put("provenance", "CATALOG_EDITED")
            values.put("brand_source", "OFD e3888b68")
            values.put("material_source", "OFD e3888b68")
            values.put("product_source", "OFD e3888b68")
            values.put("color_name_source", "AI label scan")
            values.put("color_hex_source", "AI label scan")
            values.put("diameter_source", "Assumed default")
            values.put("mass_source", "Edited locally")
            values.put("nozzle_min_source", "Edited locally")
            values.put("nozzle_max_source", "OFD e3888b68")
            values.put("bed_min_source", "OFD e3888b68")
            values.put("bed_max_source", "OFD e3888b68")
            values.put("updated_at", 123456789L)
            values.put("barcode_evidence", "GTIN (EAN_13): 6933582308674")
            values.put("transmission_distance", "2.7")
            values.put("transmission_distance_source", "Measured locally")
            db.insertOrThrow("custom_records", null, values)
            db.version = 4
        }

        Room.databaseBuilder(context, UserDatabase::class.java, "canonical-migration-user")
            .addMigrations(USER_MIGRATION_4_5, USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
                val legacy = db.user().byId("saved-1")!!
                assertEquals("SUNLU", legacy.brand)
                assertEquals(235, legacy.nozzleMinC)
                assertEquals("Edited locally", legacy.nozzleMinSource)
                assertEquals("GTIN (EAN_13): 6933582308674", legacy.barcodeEvidence)

                val profileId = legacyProfileId("saved-1")
                assertEquals("saved-1", db.canonical().profile(profileId)!!.legacyRecordId)
                val observations = db.canonical().observations(profileId).associateBy { it.fieldName }
                assertEquals("235", observations.getValue("nozzleMinimumC").valueText)
                assertEquals("USER", observations.getValue("nozzleMinimumC").evidenceKind)
                assertEquals("LABEL", observations.getValue("colorName").evidenceKind)
                assertEquals("INFERRED", observations.getValue("diameterMm").evidenceKind)
                assertEquals("GTIN (EAN_13): 6933582308674", observations.getValue("barcodeEvidence").valueText)
                assertEquals(observations.keys, db.canonical().overrides(profileId).map { it.fieldName }.toSet())
                assertEquals(setOf("GTIN", "MANUFACTURER_PART_NUMBER"), db.canonical().identifiers(profileId).map { it.scheme }.toSet())
                assertEquals(1000, db.canonical().spools(profileId).single().initialQuantityG)
                assertEquals("2.7", db.canonical().tdMeasurements(profileId).single().valueText)
            }
    }

    @Test fun fullSpectrumMigrationIsAdditiveAndMapsLegacyEvidenceWithoutUpgradingCertainty() = runBlocking {
        val name = "full-spectrum-migration-user"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.UserDatabase/5.json")!!.bufferedReader().readText()).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                val tableName=entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",tableName))
                entity.optJSONArray("indices")?.let { indices ->
                    for (index in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}",tableName))
                    }
                }
            }
            db.execSQL("INSERT INTO filament_profiles VALUES ('profile-m7',NULL,1,1)")
            db.execSQL("INSERT INTO transmission_distance_measurements(measurement_id,profile_id,value_text,source_provider,source_record_id,source_revision,evidence_kind,measured_at,instrument,created_at) VALUES ('td-m7','profile-m7','2.7','Legacy',NULL,NULL,'MEASURED',NULL,NULL,1)")
            db.version=5
        }
        Room.databaseBuilder(context,UserDatabase::class.java,name).addMigrations(USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            val td=db.fullSpectrum().opticalCharacterizations("profile-m7").single()
            assertEquals("transmission_distance",td.propertyKey); assertEquals("mm",td.unit)
            assertEquals("MEASURED_UNSPECIFIED",td.origin); assertEquals("LEGACY_UNSPECIFIED",td.methodKey)
            assertEquals("2.7",db.canonical().tdMeasurements("profile-m7").single().valueText)
            db.fullSpectrum().putEvidence(EvidenceReferenceEntity("user-m7", "USER", "Local user", null, null, null, 2, "Acceptance fixture"))
            db.fullSpectrum().putRole(FullSpectrumRoleAssertionEntity(
                assertionId="role-c", profileId="profile-m7", roleKey="C", roleCategory="MIXING_PRIMARY",
                authority="USER_CONFIRMED", assertionState="ACTIVE", supersededByAssertionId=null,
                evidenceId="user-m7", confidence=null, notes="User assertion", assertedAt=2,
            ))
            db.fullSpectrum().putSuitability(FullSpectrumSuitabilityAssessmentEntity(
                assessmentId="observed", profileId="profile-m7", assessmentKind="OBSERVED", rating="UNTESTED",
                scopeKind="ROLE", roleAssertionId="role-c", policyId=null, policyVersion=null, factorRefs="[]",
                rationale="Selected for testing", evidenceId="user-m7", confidence=null, assessor="Local user",
                assessedAt=3, supersededByAssessmentId=null,
            ))
            assertEquals("C",db.fullSpectrum().roles("profile-m7").single().roleKey)
            assertEquals("UNTESTED",db.fullSpectrum().suitability("profile-m7").single().rating)
        }
    }

    @Test fun fullSpectrumMigrationQuarantinesMalformedLegacyTdInsteadOfCreatingNumericEvidence() = runBlocking {
        val name = "full-spectrum-malformed-td-migration"
        val file=context.getDatabasePath(name); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.UserDatabase/5.json")!!.bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i); val tableName=entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",tableName))
                entity.optJSONArray("indices")?.let { indices -> for(index in 0 until indices.length()) db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}",tableName)) }
            }
            db.execSQL("INSERT INTO filament_profiles VALUES ('profile-invalid',NULL,1,1)")
            listOf("unknown","0","2.7 mm","101").forEachIndexed { index,value ->
                db.execSQL("INSERT INTO transmission_distance_measurements(measurement_id,profile_id,value_text,source_provider,source_record_id,source_revision,evidence_kind,measured_at,instrument,created_at) VALUES (?,?,?,?,NULL,NULL,'MEASURED',NULL,NULL,?)", arrayOf<Any?>("bad-$index","profile-invalid",value,"Legacy",index+1))
            }
            db.version=5
        }
        Room.databaseBuilder(context,UserDatabase::class.java,name).addMigrations(USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            val migrated=db.fullSpectrum().opticalCharacterizations("profile-invalid")
            assertTrue(migrated.none { it.propertyKey=="transmission_distance" })
            assertEquals(4,migrated.count { it.propertyKey=="legacy_transmission_distance_unparsed" })
            assertEquals(setOf("unknown","0","2.7 mm","101"),migrated.map { it.valueText }.toSet())
            assertEquals(4,db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM evidence_references").use { row -> row.moveToFirst(); row.getInt(0) })
        }
    }

    @Test fun fullSpectrumMigrationPreservesValidConflictsAndUiDoesNotSelectAWinner() = runBlocking {
        val name = "full-spectrum-conflicting-td-migration"
        val file=context.getDatabasePath(name); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("net.jamesjennison.filamajignfc.data.UserDatabase/5.json")!!.bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i); val tableName=entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",tableName))
                entity.optJSONArray("indices")?.let { indices -> for(index in 0 until indices.length()) db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}",tableName)) }
            }
            db.execSQL("INSERT INTO filament_profiles VALUES ('profile-conflict',NULL,1,1)")
            listOf("2.4","2.7").forEachIndexed { index,value ->
                db.execSQL("INSERT INTO transmission_distance_measurements(measurement_id,profile_id,value_text,source_provider,source_record_id,source_revision,evidence_kind,measured_at,instrument,created_at) VALUES (?,?,?,?,NULL,NULL,'MEASURED',NULL,NULL,?)", arrayOf<Any?>("valid-$index","profile-conflict",value,"Legacy",index+1))
            }
            db.version=5
        }
        Room.databaseBuilder(context,UserDatabase::class.java,name).addMigrations(USER_MIGRATION_5_6, USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            val optical=db.fullSpectrum().opticalCharacterizations("profile-conflict")
            assertEquals(setOf("2.4","2.7"),optical.mapNotNull { it.valueText }.toSet())
            assertTrue(optical.all { it.supersededByCharacterizationId==null })
            val summary=fullSpectrumUiSummary(FullSpectrumProfileData(optical,emptyList(),emptyList(),emptyList()))
            assertTrue(summary.optical.startsWith("Conflicting evidence"))
            assertTrue(summary.optical.contains("2.4 mm")); assertTrue(summary.optical.contains("2.7 mm"))
        }
    }

    @Test fun canonicalStoreKeepsSharedSpoolsConflictsTwoTagsAndPinnedChoice() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().use { db ->
            val dao = db.canonical()
            dao.putProfile(FilamentProfileEntity("profile-shared", null, 1, 1))
            dao.putSpool(PhysicalSpoolEntity("spool-a", "profile-shared", null, 1000, 800, null, null, null, null, null, null, null, "{}", 1, 1))
            dao.putSpool(PhysicalSpoolEntity("spool-b", "profile-shared", null, 1000, 1000, null, null, null, null, null, null, null, "{}", 2, 2))
            val maker = ProfileObservationEntity("obs-maker", "profile-shared", "nozzleMinimumC", "210", "Manufacturer", "sku", "1", 1, "MANUFACTURER", "1.0", 1)
            val community = maker.copy(observationId = "obs-community", valueText = "205", sourceProvider = "Community", evidenceKind = "COMMUNITY", confidence = null)
            dao.putObservations(listOf(maker, community))
            dao.putOverrides(listOf(ProfileOverrideEntity("profile-shared", "nozzleMinimumC", "210", "obs-maker", "USER_SELECTED", 3)))
            dao.putObservations(listOf(maker.copy(observationId = "obs-refresh", valueText = "215", sourceRevision = "2", createdAt = 4)))
            dao.putTagBinding(TagBindingEntity("tag-a", "spool-a", "NFC-A", "0102", "openspool-1.0", "AA", 5, 1, 5))
            dao.putTagBinding(TagBindingEntity("tag-b", "spool-a", "NFC-A", "0304", "paxx-u1-extended", "BB", 6, 2, 6))

            assertEquals(setOf("spool-a", "spool-b"), dao.spools("profile-shared").map { it.spoolId }.toSet())
            assertEquals(setOf("205", "210", "215"), dao.observations("profile-shared").map { it.valueText }.toSet())
            assertEquals("210", dao.overrides("profile-shared").single().valueText)
            assertEquals(listOf(1, 2), dao.tagBindings("spool-a").map { it.bindingOrder })
        }
    }

    @Test fun savingAndDeletingARecordKeepsLegacyAndCanonicalStoresInOneTransaction() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().use { db ->
            val record = CustomRecord(
                "local-dual", null, "", "", "Owner", "PLA", "Basic", "Blue", "0000FF", "1.75", 1000,
                200, 220, 50, 60, null, null, "", "user", "CUSTOM", "User", "User", "User", "User", "User",
                "Assumed default", "Assumed default", "User", "User", "User", "User", 99, "", null, null,
            )
            val repository = LocalFilamentRepository(db)
            repository.save(record)
            assertNotNull(db.user().byId("local-dual"))
            assertNotNull(db.canonical().profile(legacyProfileId("local-dual")))
            assertEquals(1, db.canonical().spools(legacyProfileId("local-dual")).size)
            repository.delete("local-dual")
            assertNull(db.user().byId("local-dual"))
            assertNull(db.canonical().profile(legacyProfileId("local-dual")))
        }
    }

    @Test fun repositoryBindsTwoVerifiedTagsReplacesSlotsAndCascadesWithSpool() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().use { db ->
            val record = CustomRecord(
                "bound-spool", null, "", "", "Owner", "PLA", "Basic", "Blue", "0000FF", "1.75", 1000,
                200, 220, 50, 60, null, null, "", "user", "CUSTOM", "User", "User", "User", "User", "User",
                "Assumed default", "Assumed default", "User", "User", "User", "User", 99, "", null, null,
            )
            val repository=LocalFilamentRepository(db)
            repository.save(record)
            val spoolId=repository.portableIds(record.id)!!.spoolId
            repository.bindVerifiedTag(spoolId,byteArrayOf(1,2),"openspool-paxx-u1-1.0","first".encodeToByteArray(),1,100)
            repository.bindVerifiedTag(spoolId,byteArrayOf(3,4),"openspool-paxx-u1-1.0","second".encodeToByteArray(),2,200)
            assertEquals(listOf("0102","0304"),repository.tagBindings(spoolId).map { it.uidHex })
            repository.bindVerifiedTag(spoolId,byteArrayOf(5,6),"openspool-1.0","replacement".encodeToByteArray(),1,300)
            val bindings=repository.tagBindings(spoolId)
            assertEquals(listOf(1,2),bindings.map { it.bindingOrder })
            assertEquals("0506",bindings.first().uidHex)
            assertEquals("openspool-1.0",bindings.first().codecId)
            repository.delete(record.id)
            assertTrue(repository.tagBindings(spoolId).isEmpty())
        }
    }

    @Test fun importedPortableIdentitySurvivesResaveSupportsSharedProfilesAndRejectsSpoolCollisions() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().use { db ->
            fun record(id: String, updatedAt: Long) = CustomRecord(
                id, null, "", "", "Prismático", "PLA", "Basic", "雪", "00ADFF", "1.75", 1000,
                190, 230, 35, 65, null, null, "", "portable", "CUSTOM", "Portable bundle", "Portable bundle",
                "Portable bundle", "Portable bundle", "Portable bundle", "Portable bundle", "Portable bundle",
                "Portable bundle", "Portable bundle", "Portable bundle", "Portable bundle", updatedAt, "", "2.7", "Portable bundle",
            )
            val repository = LocalFilamentRepository(db)
            repository.save(record("portable-a", 10), "profile:shared", "spool:a", 1100, 725)
            assertEquals(SavedPortableIdentity("profile:shared", "spool:a", 1100, 725), repository.portableIds("portable-a"))

            repository.save(record("portable-a", 11))
            assertEquals(SavedPortableIdentity("profile:shared", "spool:a", 1100, 725), repository.portableIds("portable-a"))
            repository.assertUserRole("portable-a", "C", 12)
            repository.recordObservedSuitability("portable-a", net.jamesjennison.filamajignfc.core.SuitabilityRating.GOOD, "Observed print", 13)
            assertEquals("C", repository.fullSpectrum("portable-a")!!.roles.single().roleKey)
            assertEquals("GOOD", repository.fullSpectrum("portable-a")!!.suitability.single().rating)

            repository.save(record("portable-a", 14).copy(transmissionDistance=null, transmissionDistanceSource=null))
            val clearedSpectrum = repository.fullSpectrum("portable-a")!!
            val clearMarker = clearedSpectrum.opticalCharacterizations.last()
            assertNull(clearMarker.valueText)
            assertEquals(clearMarker.characterizationId, clearedSpectrum.opticalCharacterizations.first { it.valueText == "2.7" }.supersededByCharacterizationId)
            assertTrue(fullSpectrumUiSummary(clearedSpectrum).optical.startsWith("Unknown"))

            val reassignment = runCatching { repository.save(record("portable-a", 15), "profile:other", "spool:other", 1000, 1000) }
            assertTrue(reassignment.exceptionOrNull()!!.message!!.contains("different profile"))
            assertEquals("Prismático", db.user().byId("portable-a")!!.brand)
            assertEquals(SavedPortableIdentity("profile:shared", "spool:a", 1100, 725), repository.portableIds("portable-a"))

            repository.save(record("portable-b", 13), "profile:shared", "spool:b", 1100, 1100)
            assertEquals(setOf("spool:a", "spool:b"), db.canonical().spools("profile:shared").map { it.spoolId }.toSet())
            assertEquals(SavedPortableIdentity("profile:shared", "spool:b", 1100, 1100), repository.portableIds("portable-b"))

            val collision = runCatching { repository.save(record("portable-c", 14), "profile:other", "spool:a", 1000, 1000) }
            assertTrue(collision.exceptionOrNull()!!.message!!.contains("already assigned"))
            assertNull(db.user().byId("portable-c"))

            repository.delete("portable-b")
            assertNull(db.user().byId("portable-b"))
            assertEquals(listOf("spool:a"), db.canonical().spools("profile:shared").map { it.spoolId })
            assertNotNull(db.canonical().profile("profile:shared"))
            repository.delete("portable-a")
            assertNull(db.canonical().profile("profile:shared"))
        }
    }

    @Test fun deletingLegacyRecordPreservesAProfileUsedByAnotherPhysicalSpool() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().use { db ->
            val record = CustomRecord(
                "shared-local", null, "", "", "Owner", "PLA", "Basic", "Blue", "0000FF", "1.75", 1000,
                null, null, null, null, null, null, "", "user", "CUSTOM", "User", "User", "User", "User", "User",
                "User", "User", null, null, null, null, 99, "", null, null,
            )
            val repository = LocalFilamentRepository(db)
            repository.save(record)
            val profileId = legacyProfileId(record.id)
            db.canonical().putSpool(PhysicalSpoolEntity("second-owned-spool", profileId, null, 1000, 700, null, null, null, null, null, null, null, "{}", 100, 100))
            repository.delete(record.id)
            assertNull(db.user().byId(record.id))
            assertNull(db.canonical().profile(profileId)!!.legacyRecordId)
            assertEquals("second-owned-spool", db.canonical().spools(profileId).single().spoolId)
        }
    }

    @Test fun fullSpectrumSearchUsesCanonicalIndexesAndReturnsEvidenceBearingAlternates() = runBlocking {
        Room.inMemoryDatabaseBuilder(context,UserDatabase::class.java).allowMainThreadQueries().build().use { db ->
            fun record(id:String,brand:String,td:String)=CustomRecord(
                id,null,"","",brand,"PLA","Basic","Cyan","00ADFF","1.75",1000,190,230,35,65,null,null,"","user","CUSTOM",
                "User","User","User","User","User","User","User","User","User","User","User",10,"",td,"User",
            )
            val repository=LocalFilamentRepository(db)
            repository.save(record("cyan-a","Alpha", "2.7"))
            repository.save(record("cyan-b","Beta", "3.1"))
            repository.assertUserRole("cyan-a","C",20)
            repository.assertUserRole("cyan-b","C",21)
            repository.recordObservedSuitability("cyan-a",net.jamesjennison.filamajignfc.core.SuitabilityRating.GOOD,"Observed",22)

            val ids=repository.searchFullSpectrum(net.jamesjennison.filamajignfc.core.FullSpectrumSearchFilter(
                roleKey="C",tdMinimumMm="2.5",tdMaximumMm="2.9",tdOrigin="USER_REPORTED_UNSPECIFIED",
                suitability=net.jamesjennison.filamajignfc.core.SuitabilityRating.GOOD,
                testState=net.jamesjennison.filamajignfc.core.FullSpectrumTestState.TESTED,
                ownership=net.jamesjennison.filamajignfc.core.InventoryOwnership.OWNED,
            ))
            assertEquals(listOf("cyan-a"),ids)
            assertEquals(setOf("cyan-a","cyan-b"),repository.alternateCandidates("C").map { it.recordId }.toSet())
            assertTrue(repository.alternateCandidates("C").all { it.evidenceId?.startsWith("user-role-evidence:")==true })
            assertEquals(listOf("cyan-a"),db.canonical().searchLegacyRecordIds("Alpha",100))
        }
    }

    @Test fun m8MigrationAddsOnlySearchIndexesAndPreservesM7Rows() = runBlocking {
        val name="m8-search-migration-user"
        schemaDatabase(name,"UserDatabase",6).use { db ->
            db.execSQL("INSERT INTO filament_profiles VALUES ('profile-a','record-a',1,1)")
        }
        Room.databaseBuilder(context,UserDatabase::class.java,name).addMigrations(USER_MIGRATION_6_7).allowMainThreadQueries().build().use { db ->
            assertEquals(7,db.openHelper.writableDatabase.version)
            assertNotNull(db.canonical().profile("profile-a"))
            val expected=setOf(
                "index_profile_observations_field_name_value_text_profile_id",
                "index_optical_characterizations_property_key_origin_superseded_by_characterization_id_profile_id",
                "index_full_spectrum_role_assertions_role_key_assertion_state_superseded_by_assertion_id_profile_id",
                "index_full_spectrum_assessments_rating_assessment_kind_superseded_by_assessment_id_profile_id",
            )
            val found=mutableSetOf<String>()
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='index'").use { rows -> while(rows.moveToNext()) found+=rows.getString(0) }
            assertTrue(found.containsAll(expected))
        }
    }

    @Test fun rollbackMigrationDropsOnlyCanonicalTablesAndRetainsLegacyBackup() {
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().use { room ->
            val db = room.openHelper.writableDatabase
            db.execSQL("INSERT INTO custom_records(id,original_package_id,variant_id,product_id,brand,material,product,color_name,color_hex,diameter_mm,mass_g,nozzle_min_c,nozzle_max_c,bed_min_c,bed_max_c,gtin,article_number,additional_color_hexes,source_revision,provenance,brand_source,material_source,product_source,color_name_source,color_hex_source,diameter_source,mass_source,nozzle_min_source,nozzle_max_source,bed_min_source,bed_max_source,updated_at,barcode_evidence,transmission_distance,transmission_distance_source) VALUES ('rollback-safe',NULL,'','','Owner','PLA','','','','1.75',1000,NULL,NULL,NULL,NULL,NULL,NULL,'','user','CUSTOM','User','User','User','User','User','User','User',NULL,NULL,NULL,NULL,1,'',NULL,NULL)")
            USER_ROLLBACK_5_4.migrate(db)
            db.query("SELECT brand,material FROM custom_records WHERE id='rollback-safe'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Owner", row.getString(0))
                assertEquals("PLA", row.getString(1))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='filament_profiles'").use { assertFalse(it.moveToFirst()) }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='recents'").use { assertTrue(it.moveToFirst()) }
        }
    }

    @Test fun catalogMigrationMatchesGeneratedRoomSchema() {
        legacyDatabase("migration-catalog", "CatalogDatabase").use { db ->
            db.execSQL("INSERT INTO catalog_entries VALUES ('old-p','old-v','old-product','Owner','PLA','Sample','Orange','FF8800','1.75',1000,200,220,50,60,NULL,NULL,'old-revision')")
        }
        Room.databaseBuilder(context, CatalogDatabase::class.java, "migration-catalog").addMigrations(CATALOG_MIGRATION_1_2).allowMainThreadQueries().build().use { db ->
            assertEquals(2, db.openHelper.writableDatabase.version)
            db.openHelper.writableDatabase.query("SELECT brand,nozzle_min_c,additional_color_hexes FROM catalog_entries WHERE package_id='old-p'").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("Owner", row.getString(0)); assertEquals(200, row.getInt(1)); assertEquals("", row.getString(2))
            }
        }
    }

    @Test fun actualCatalogStoreRepairsSameCountCorruption() = runBlocking {
        val store = DataStore(context)
        try {
            store.ensureCatalog()
            val dao = store.catalog.catalog()
            assertEquals(22347, dao.count())
            val skuEntry = dao.allForIntegrity().first { !it.sku.isNullOrBlank() }
            val skuCandidates = dao.bySku(skuEntry.sku!!.swapCase())
            assertTrue(skuCandidates.any { it.packageId == skuEntry.packageId })
            assertTrue(skuCandidates.all { it.sku.equals(skuEntry.sku, ignoreCase = true) })
            val target = dao.browse(1).single()
            store.catalog.openHelper.writableDatabase.execSQL("UPDATE catalog_entries SET color_hex='BADBAD' WHERE package_id=?", arrayOf(target.packageId))
            assertEquals(22347, dao.count())
            assertEquals("BADBAD", dao.byId(target.packageId)!!.colorHex)
            store.ensureCatalog()
            assertEquals(target, dao.byId(target.packageId))
            assertEquals(dao.metadata()!!.contentSha256, catalogDigest(dao.allForIntegrity()))
        } finally { store.catalog.close(); store.user.close() }
    }

    private fun String.swapCase() = map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
}
