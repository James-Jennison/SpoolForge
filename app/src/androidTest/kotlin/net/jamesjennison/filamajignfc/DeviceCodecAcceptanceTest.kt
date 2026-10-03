package net.jamesjennison.filamajignfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.filamajignfc.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceCodecAcceptanceTest {
    @Test fun registeredPinnedPaxxPayloadFitsNtag215OnRazr2023() {
        assertEquals("motorola razr 2023",Build.MODEL)
        val source="Device fixture"
        val record=FilamentRecord(
            "m4-device",null,null,FieldValue("SpoolForge QA",source),FieldValue("PETG",source),
            FieldValue("Rapid",source),FieldValue("Multicolor",source),FieldValue("AFAFAF",source),
            FieldValue("1.75",source),FieldValue(1000,source),FieldValue(230,source),FieldValue(260,source),
            FieldValue(70,source),FieldValue(90,source),sourceRevision=PaxxU1ExtendedTagCodec.TARGET_FIRMWARE,
            provenance=Provenance.CUSTOM,additionalColors=listOf("EEFFEE","FF00FF"),transmissionDistance=FieldValue("2.7",source),
        )
        val codec=TagCodecRegistry.require(TagCodecRegistry.DEFAULT_CODEC_ID)
        val encoded=codec.encode(record)
        val ndefBytes=NdefMessage(arrayOf(NdefRecord.createMime(codec.format.mimeType,encoded.payload))).toByteArray().size
        assertEquals(PaxxU1ExtendedTagCodec.TARGET_FIRMWARE,codec.format.compatibilityTarget.substringAfterLast(' '))
        assertTrue(ndefBytes<=492)
        assertTrue(encoded.includedWireFields.containsAll(setOf("diameter","weight","subtype","additional_color_hexes")))
        assertTrue(codec.decode(encoded.payload) is DecodeResult.Supported)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir,"device-codec-m4-acceptance.json").writeText(JSONObject()
            .put("model",Build.MODEL).put("codec_id",codec.format.id).put("firmware_target",PaxxU1ExtendedTagCodec.TARGET_FIRMWARE)
            .put("payload_bytes",encoded.sizeBytes).put("ndef_message_bytes",ndefBytes).put("ntag215_capacity",492)
            .put("semantic_round_trip","PASS").put("physical_write_required",true).toString(2))
    }
}
