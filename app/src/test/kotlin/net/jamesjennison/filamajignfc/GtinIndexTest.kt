package net.jamesjennison.filamajignfc
import android.app.Application
import net.jamesjennison.filamajignfc.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class GtinIndexTest {
    @Test fun ambiguousBarcodePreservesPackageCandidatesAndSnapshotProvenance() {
        val index=GtinIndex(RuntimeEnvironment.getApplication())
        val results=index.lookup("7340002119380")
        val community=results.filter { it.entry.packageId.startsWith("community:") }
        assertEquals(setOf(1000,2500),community.map { it.entry.massG }.toSet())
        assertEquals(setOf("1.75","2.85"),community.map { it.entry.diameterMm }.toSet())
        assertEquals(4,community.size)
        for(item in results){
            val e=JSONObject(item.barcodeEvidence)
            assertEquals("07340002119380",e.getString("gtin14"))
            assertTrue(e.getString("record_pointer").isNotBlank())
            assertEquals("MIT",e.getJSONObject("snapshot").getString("license"))
            assertTrue(item.source("mass").isNotBlank())
            val edited=item.copy(sources=item.sources+("brand" to "Edited locally"))
            val editedRestored=decodeSnapshot(encodeSnapshot(edited))!!
            assertEquals("Edited locally",editedRestored.source("brand"))
            assertEquals(e.getJSONObject("fields").getJSONObject("brand").getString("origin"),JSONObject(editedRestored.barcodeEvidence).getJSONObject("fields").getJSONObject("brand").getString("origin"))
            val restored=decodeSnapshot(encodeSnapshot(item))!!
            assertEquals(item.barcodeEvidence,restored.barcodeEvidence)
            assertEquals(item.sources.filterKeys { it in listOf("brand","material","product","colorName","colorHex","diameter","mass","nozzleMin","nozzleMax","bedMin","bedMax") }.filterValues { it.isNotBlank() }, restored.sources.filterValues { it.isNotBlank() })
        }
        assertEquals(results.map { it.entry.packageId },index.lookup("07340002119380").map { it.entry.packageId })
    }
    @Test fun alteredInstalledSidecarIsRepairedBeforeLookup() {
        val app=RuntimeEnvironment.getApplication();val index=GtinIndex(app)
        val before=index.lookup("7340002119380").map { it.entry.packageId }
        val file=app.filesDir.listFiles()!!.single { it.name.startsWith("gtin-")&&it.extension=="sqlite" }
        file.writeText("damaged")
        val repairedIndex=GtinIndex(app)
        assertEquals(before,repairedIndex.lookup("7340002119380").map { it.entry.packageId })
        assertTrue(file.readBytes().copyOfRange(0, 15).contentEquals("SQLite format 3".toByteArray()))
        assertTrue(file.length() > "damaged".length)
        assertTrue(index.lookup("00036000291452").isEmpty())
    }
}
