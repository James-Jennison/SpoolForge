package net.jamesjennison.filamajignfc

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.data.DataStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceCatalogAcceptanceTest {
    @Test fun realDeviceWarmQueryLatencyAndCatalogIntegrity() = runBlocking {
        assertEquals("motorola razr 2023", Build.MODEL)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DataStore(context)
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            store.ensureCatalog()
            val ensureMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            val dao = store.catalog.catalog()
            assertEquals(22347, dao.count())
            val queries = listOf("SUNLU ABS Orange", "PLA Black", "PETG", "Bambu", "Polymaker", "eSUN")
            val results = JSONArray()
            for (query in queries) {
                val fts = query.split(" ").joinToString(" ") { "\"$it\"*" }
                repeat(5) { dao.search(fts) }
                val timings = (1..40).map {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val rows = dao.search(fts)
                    assertTrue("Expected matches for $query", rows.isNotEmpty())
                    (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }.sorted()
                results.put(JSONObject().put("query", query).put("samples", 40).put("p95_ms", timings[37]).put("max_ms", timings.last()))
            }
            val target = dao.byId("14417b3a-38d3-4643-ace8-40bdc9b7ecf3")!!
            assertEquals(230, target.nozzleMinC); assertEquals(260, target.nozzleMaxC)
            assertEquals("FF8E24", target.colorHex)
            val dbFiles = context.getDatabasePath("catalog-e3888b68.db").parentFile!!.listFiles().orEmpty()
            val output = JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("ensure_existing_catalog_ms", ensureMs)
                .put("catalog_packages", dao.count()).put("queries", results)
                .put("catalog_files_bytes_including_wal", dbFiles.filter { it.name.startsWith("catalog-e3888b68.db") }.sumOf { it.length() })
                .put("measurement", "Debug APK, actual Room DAO and device SQLite; excludes Compose rendering and keyboard latency; existing-catalog integrity time excludes Activity startup")
            File(context.filesDir, "device-catalog-acceptance.json").writeText(output.toString(2))
            for (i in 0 until results.length()) assertTrue("Warm query p95 exceeds 100ms: ${results.getJSONObject(i)}", results.getJSONObject(i).getDouble("p95_ms") < 100.0)
        } finally { store.catalog.close(); store.user.close() }
    }
}
