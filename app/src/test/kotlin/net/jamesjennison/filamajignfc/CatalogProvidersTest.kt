package net.jamesjennison.filamajignfc

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.core.*
import net.jamesjennison.filamajignfc.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogProvidersTest {
    @Test fun communitySupportsExactAndStructuredOfflineSearchWithProvenance() = runBlocking {
        val provider = CommunityCatalogProvider(RuntimeEnvironment.getApplication())
        val exact = provider.search(CatalogQuery(identifiers = listOf("GTIN" to "7340002119380"), limit = 20))
        val ranked = CatalogCandidateRanking.rank(exact)
        assertEquals(4, ranked.size)
        assertEquals(setOf(1000, 2500), ranked.mapNotNull { it.profile.nominalFilamentMassG?.value }.toSet())
        assertEquals(setOf(BigDecimal("1.75"), BigDecimal("2.85")), ranked.mapNotNull { it.profile.diameterMm?.value }.toSet())
        assertTrue(ranked.all { it.matchTier == CatalogMatchTier.EXACT_IDENTIFIER })
        assertTrue(ranked.all { it.conflictingFields.containsAll(setOf("diameterMm", "nominalMassG")) })
        assertTrue(ranked.flatMap { listOf(it.profile.brand, it.profile.material, it.profile.productLine, it.profile.diameterMm, it.profile.nominalFilamentMassG) }
            .filterNotNull().all { it.source.provider == provider.descriptor.id && it.source.recordId != null && it.source.revision?.startsWith("artifact:") == true })
        val evidence = ranked.first().toFilamentItem().barcodeEvidence
        assertTrue(isProviderCandidateEvidence(evidence))
        assertTrue(providerCandidateSummary(evidence).startsWith("SpoolmanDB Community · exact identifier"))
        val colorSelection = JSONObject(evidence).getJSONObject("color_selection")
        assertEquals("explicit color_hex, then first color_hexes value", colorSelection.getString("rule"))
        assertTrue(colorSelection.getJSONArray("all").length() >= 1)

        val multicolor = provider.search(CatalogQuery(identifiers = listOf("SKU" to "DUAL 12"), limit = 10)).single()
        val multicolorEvidence = JSONObject(multicolor.toFilamentItem().barcodeEvidence).getJSONObject("color_selection")
        assertEquals("000000", multicolorEvidence.getString("primary"))
        assertEquals(listOf("000000", "FFD700"), (0 until multicolorEvidence.getJSONArray("all").length()).map { index ->
            multicolorEvidence.getJSONArray("all").getString(index)
        })

        val structured = provider.search(CatalogQuery(fields = CatalogStructuredFields(brand = "Gryddle", material = "PLA+"), text = "Black", limit = 100))
        assertTrue(structured.isNotEmpty())
        val exactStructured = structured.filter { it.matchTier == CatalogMatchTier.EXACT_STRUCTURED }
        assertTrue(exactStructured.isNotEmpty())
        assertTrue(exactStructured.all { it.profile.brand.value.equals("Gryddle", true) && it.profile.material.value.equals("PLA+", true) })
        assertEquals(structured.map(CatalogCandidate::providerRecordId), provider.search(CatalogQuery(fields = CatalogStructuredFields(brand = "Gryddle", material = "PLA+"), text = "Black", limit = 100)).map(CatalogCandidate::providerRecordId))
        val skuMatches = provider.search(CatalogQuery(identifiers = listOf("SKU" to "33102"), limit = 10))
        assertTrue(skuMatches.isNotEmpty())
        assertEquals(3, skuMatches.size)
        assertTrue(skuMatches.all { candidate ->
            candidate.profile.identifiers.any { it.scheme == "SKU" && it.value == "33102" }
        })
    }

    @Test fun malformedIdentifierReturnsNoCandidateAndCorruptSidecarRepairs() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val provider = CommunityCatalogProvider(app)
        assertTrue(provider.search(CatalogQuery(identifiers = listOf("GTIN" to "12345678"))).isEmpty())
        assertTrue(provider.search(CatalogQuery(text = "Gizmo Dorks HIPS White", limit = 10)).isNotEmpty())
        val installed = app.filesDir.listFiles()!!.single { it.name.startsWith("community-") && it.extension == "sqlite" }
        installed.writeText("damaged")
        val repaired = CommunityCatalogProvider(app)
        assertTrue(repaired.search(CatalogQuery(text = "Gizmo Dorks HIPS White", limit = 10)).isNotEmpty())
        assertTrue(installed.readBytes().copyOfRange(0, 15).contentEquals("SQLite format 3".toByteArray()))
    }

    @Test fun ofdAndLocalAdaptersReturnCanonicalCandidatesWithoutMerging() = runBlocking<Unit> {
        val app: Application = RuntimeEnvironment.getApplication()
        val store = DataStore(app)
        try {
            store.ensureCatalog()
            val ofd = OfdCatalogProvider(store.catalog.catalog()).search(CatalogQuery(text = "SUNLU ABS Orange", limit = 20))
            assertTrue(ofd.isNotEmpty())
            assertTrue(ofd.all { it.providerId == "ofd" && it.profile.brand.source.provider == "ofd" })
            val identifierQuery = CatalogQuery(identifiers = listOf("SKU" to "33102"), limit = 20)
            val combined = CatalogCandidateRanking.rank(
                OfdCatalogProvider(store.catalog.catalog()).search(identifierQuery) + CommunityCatalogProvider(app).search(identifierQuery)
            )
            assertEquals(5, combined.size)
            assertTrue(combined.all { it.matchTier == CatalogMatchTier.EXACT_IDENTIFIER })
            assertEquals(setOf("ofd", "spoolmandb-community"), combined.map(CatalogCandidate::providerId).toSet())
        } finally { store.catalog.close(); store.user.close() }

        val db = Room.inMemoryDatabaseBuilder(app, UserDatabase::class.java).allowMainThreadQueries().build()
        try {
            val record = CustomRecord(
                "local-provider", null, "", "", "Owner Brand", "PLA", "Basic", "Blue", "0000FF", "1.75", 1000,
                200, 220, 50, 60, null, "OWNER-PLA-BLUE", "", "user", "CUSTOM", "User", "User", "User", "User", "User",
                "User", "User", "User", "User", "User", "User", 99, "", null, null,
            )
            LocalFilamentRepository(db).save(record)
            val provider = LocalCatalogProvider(db)
            val text = provider.search(CatalogQuery(text = "Owner Blue"))
            val sku = provider.search(CatalogQuery(identifiers = listOf("SKU" to "OWNER-PLA-BLUE")))
            assertEquals(listOf("local-provider"), text.map(CatalogCandidate::providerRecordId))
            assertEquals(CatalogMatchTier.EXACT_IDENTIFIER, sku.single().matchTier)
            assertEquals(EvidenceKind.USER, sku.single().profile.brand.source.kind)
        } finally { db.close() }
    }
}
