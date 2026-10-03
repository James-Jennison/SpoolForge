package net.jamesjennison.filamajignfc

import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class DevicePortableIdentityAcceptanceTest {
    @Test fun generatedQrRoundTripsOfflineOnRazr2023() = runBlocking {
        assertEquals("motorola razr 2023", Build.MODEL)
        val fieldSource = "Printed label"
        val identity = PortableSpoolIdentity(
            "legacy-profile:portable-device", "legacy-spool:portable-device",
            FilamentRecord(
                "portable-device", null, null, FieldValue("SpoolForge QA", fieldSource), FieldValue("PLA+", fieldSource),
                FieldValue("Prismático 雪", fieldSource), FieldValue("Cyan", fieldSource), FieldValue("00ADFF", fieldSource),
                FieldValue("1.75", fieldSource), FieldValue(1000, fieldSource), FieldValue(195, fieldSource), FieldValue(225, fieldSource),
                FieldValue(50, fieldSource), FieldValue(60, fieldSource), null, "QA-PORTABLE", "device-test", Provenance.CUSTOM,
                listOf("FF00AA"), FieldValue("2.7", fieldSource),
            ), 1000, 840,
        )
        val payload = PortableIdentityCodec.encodeQr(identity)
        val matrix = QRCodeWriter().encode(payload.decodeToString(), BarcodeFormat.QR_CODE, 1000, 1000,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val detected = decodeLabelCodesFromImage(jpeg).single { it.format == "QR_CODE" }
        assertEquals(payload.decodeToString(), detected.value)
        val decoded = PortableIdentityCodec.decode(detected.value.encodeToByteArray()) as PortableIdentityDecodeResult.Supported
        assertEquals(identity.profileId, decoded.identity.profileId)
        assertEquals(identity.spoolId, decoded.identity.spoolId)
        assertEquals("Prismático 雪", decoded.identity.filament.product.value)
        assertEquals(840, decoded.identity.remainingQuantityG)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "device-portable-identity-acceptance.json").writeText(JSONObject()
            .put("model", Build.MODEL).put("schema", PORTABLE_IDENTITY_SCHEMA).put("version", PORTABLE_IDENTITY_VERSION)
            .put("payload_bytes", payload.size).put("profile_id_distinct_from_spool_id", identity.profileId != identity.spoolId)
            .put("qr_decode", "PASS").put("unicode_round_trip", "PASS").put("network_required", false).toString(2))
    }
}
