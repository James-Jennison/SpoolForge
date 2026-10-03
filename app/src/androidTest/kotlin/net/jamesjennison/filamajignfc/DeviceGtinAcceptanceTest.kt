package net.jamesjennison.filamajignfc
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.filamajignfc.data.GtinIndex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class DeviceGtinAcceptanceTest {
    @Test fun bundledCandidatesAndRealLookupTiming() {
        assertEquals("motorola razr 2023",Build.MODEL)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val index=GtinIndex(context);val start=SystemClock.elapsedRealtimeNanos()
        val rows=index.lookup("7340002119380");val initial=(SystemClock.elapsedRealtimeNanos()-start)/1e6
        assertEquals(4,rows.count { it.entry.packageId.startsWith("community:") })
        assertTrue(rows.all { it.barcodeEvidence.contains("07340002119380") })
        val times=(1..20).map { val t=SystemClock.elapsedRealtimeNanos();assertEquals(rows.map { it.entry.packageId },GtinIndex(context).lookup("07340002119380").map { it.entry.packageId });(SystemClock.elapsedRealtimeNanos()-t)/1e6 }.sorted()
        val manifest=JSONObject(context.assets.open("gtin-index-manifest.json").bufferedReader().readText())
        val sidecar=File(context.filesDir,"gtin-${manifest.getString("sha256")}.sqlite")
        sidecar.writeText("damaged")
        assertEquals(rows.map { it.entry.packageId },GtinIndex(context).lookup("7340002119380").map { it.entry.packageId })
        assertEquals("SQLite format 3",sidecar.inputStream().use { String(it.readNBytes(15)) })
        File(context.filesDir,"device-gtin-acceptance.json").writeText(JSONObject().put("model",Build.MODEL).put("initial_lookup_ms",initial).put("samples",20).put("fresh_index_each_sample",true).put("p95_ms",times[18]).put("max_ms",times.last()).put("candidates",rows.size).put("corrupt_sidecar_repaired",true).put("measurement","Every timed sample creates a fresh GtinIndex and performs actual sidecar hash validation plus SQLite lookup; excludes Compose rendering. No predefined latency acceptance threshold.").toString(2))
    }
}
