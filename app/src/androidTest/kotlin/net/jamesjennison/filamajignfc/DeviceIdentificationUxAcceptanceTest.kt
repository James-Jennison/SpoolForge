package net.jamesjennison.filamajignfc

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jamesjennison.filamajignfc.data.GtinIndex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DeviceIdentificationUxAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun onePhotoAiReachesEditableMarsWorkRecordWithinOneMinute() = runBlocking {
        assertEquals("motorola razr 2023", Build.MODEL)
        val privatePhoto = File(context.filesDir, "m6-benchmark-label.jpg")
        assertTrue("Stage the private M6 benchmark photo before running this test", privatePhoto.isFile)
        val jpeg = privatePhoto.readBytes()
        val decodeStarted = SystemClock.elapsedRealtimeNanos()
        val codes = decodeLabelCodesFromImage(jpeg).map { it.copy(photoRole = LabelPhotoRole.PROFILE.wireName) }
        val localDecodeMs = elapsedMs(decodeStarted)
        assertTrue("Expected the visible QR code to decode locally", codes.any { it.format == "QR_CODE" })

        val aiStarted = SystemClock.elapsedRealtimeNanos()
        val result = withTimeout(90_000) {
            LabelScanClient().analyze(listOf(LabelPhoto(LabelPhotoRole.PROFILE, jpeg, codes)), codes)
        }
        val aiMs = elapsedMs(aiStarted)
        assertTrue("AI label-to-review path exceeded one minute: $aiMs ms", aiMs < 60_000)
        assertEquals("MARSWORK", result.seed.brand.uppercase())
        assertTrue(result.seed.material.uppercase().contains("PLA"))
        assertEquals("Cyan", result.seed.color)
        assertEquals("2.7", result.seed.transmissionDistance)
        assertEquals(DEFAULT_DIAMETER_MM, result.seed.diameter)
        assertEquals(DEFAULT_NOMINAL_MASS_G, result.seed.mass)

        val retailStarted = SystemClock.elapsedRealtimeNanos()
        val retail = GtinIndex(context).lookup("7340002119380")
        val retailMs = elapsedMs(retailStarted)
        assertTrue(retail.isNotEmpty())

        val manualStarted = SystemClock.elapsedRealtimeNanos()
        val manual = parseBulkCsv("brand,material,color,product,gtin\nGizmo Dorks,HIPS,White,HIPS Filament,887503120752").single()
        val manualMs = elapsedMs(manualStarted)
        assertEquals("Gizmo Dorks", manual.seed.brand)
        assertEquals("HIPS", manual.seed.material)
        assertEquals(DEFAULT_DIAMETER_MM, manual.seed.diameter)
        assertEquals(DEFAULT_NOMINAL_MASS_G, manual.seed.mass)

        File(context.filesDir, "device-identification-m6-acceptance.json").writeText(
            JSONObject().put("model", Build.MODEL).put("app_version", BuildConfig.VERSION_NAME)
                .put("photo_count", 1).put("local_qr_codes", codes.count { it.format == "QR_CODE" })
                .put("local_code_decode_ms", localDecodeMs).put("ai_label_to_review_ms", aiMs)
                .put("ai_under_one_minute", aiMs < 60_000).put("retail_gtin_lookup_ms", retailMs)
                .put("manual_csv_prepare_ms", manualMs).put("manual_obscure_filament", "Gizmo Dorks HIPS White")
                .put("ai_fields", JSONObject().put("brand", result.seed.brand).put("material", result.seed.material)
                    .put("color", result.seed.color).put("transmission_distance", result.seed.transmissionDistance)
                    .put("diameter_mm", result.seed.diameter).put("weight_g", result.seed.mass))
                .put("measurement", "Device pipeline timings end when an editable seed is available; camera framing and human review time are excluded.")
                .toString(2)
        )
        privatePhoto.delete()
        Unit
    }

    @Test fun interruptedNetworkRetainsRetryIdentityAndReturnsUsefulFailure() = runBlocking {
        val seen = mutableListOf<String>()
        val client = LabelScanClient("http://100.64.0.1:11434", "test-model", LabelScanTransport { _, request ->
            seen += request.requestKey
            throw IOException("simulated network interruption")
        })
        val failure = runCatching {
            client.analyze(listOf(LabelPhoto(LabelPhotoRole.PROFILE, byteArrayOf(1), emptyList())), emptyList())
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("Tailscale"))
        assertEquals(2, seen.size)
        assertEquals(seen[0], seen[1])
    }

    @Test fun interruptedProcessRestoresCapturedPhotoForExplicitRetry() {
        val pending = File(context.filesDir, "pending-label-analysis.jpg")
        pending.writeBytes(byteArrayOf(1, 2, 3, 4))
        val restored = MainViewModel(context.applicationContext as FilamajigApplication)
        assertEquals(1, restored.labelPhotos.size)
        assertTrue(restored.labelAnalysis.message.orEmpty().contains("interrupted"))
        restored.clearLabelAnalysis()
        assertTrue(!pending.exists())
    }

    private fun elapsedMs(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
}
