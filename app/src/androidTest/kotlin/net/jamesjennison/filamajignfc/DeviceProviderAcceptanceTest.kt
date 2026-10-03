package net.jamesjennison.filamajignfc

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.core.CatalogCandidateRanking
import net.jamesjennison.filamajignfc.core.CatalogQuery
import net.jamesjennison.filamajignfc.data.CommunityCatalogProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceProviderAcceptanceTest {
    @Test fun communityProviderRunsOfflineOnRazr2023() = runBlocking {
        assertEquals("motorola razr 2023", Build.MODEL)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = CommunityCatalogProvider(context)
        val firstStart = SystemClock.elapsedRealtimeNanos()
        val gtin = CatalogCandidateRanking.rank(provider.search(CatalogQuery(identifiers = listOf("GTIN" to "7340002119380"), limit = 20)))
        val firstMs = (SystemClock.elapsedRealtimeNanos() - firstStart) / 1e6
        assertEquals(4, gtin.size)
        assertTrue(gtin.all { it.profile.brand.source.provider == "spoolmandb-community" })
        val warmTimes = (1..20).map {
            val start = SystemClock.elapsedRealtimeNanos()
            val sku = provider.search(CatalogQuery(identifiers = listOf("SKU" to "33102"), limit = 20))
            assertEquals(3, sku.size)
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }.sorted()
        val text = provider.search(CatalogQuery(text = "Gizmo Dorks HIPS White", limit = 20))
        assertTrue(text.isNotEmpty())
        File(context.filesDir, "device-provider-acceptance.json").writeText(
            JSONObject().put("model", Build.MODEL).put("provider", provider.descriptor.id)
                .put("gtin_ambiguous_candidates", gtin.size).put("community_sku_candidates", 3)
                .put("structured_text_candidates", text.size).put("initial_lookup_ms", firstMs)
                .put("warm_samples", warmTimes.size).put("warm_p95_ms", warmTimes[18]).put("warm_max_ms", warmTimes.last())
                .put("measurement", "Provider initialization, installed-sidecar hash validation and queries run in the target app process. Timing excludes Compose rendering; no predefined latency threshold.")
                .toString(2)
        )
    }
}
