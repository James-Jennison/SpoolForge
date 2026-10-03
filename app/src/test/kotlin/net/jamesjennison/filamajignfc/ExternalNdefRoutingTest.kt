package net.jamesjennison.filamajignfc

import android.app.Application
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import net.jamesjennison.filamajignfc.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalNdefRoutingTest {
    @Test fun openTag3dMimeRoutesToReadOnlyConversionWithoutRequiringSingleNdefRecord() {
        val payload = ByteArray(224)
        putUInt(payload, 0, 2, 2000); putText(payload, 2, 5, "PLA"); putText(payload, 12, 16, "Example")
        payload[0x3c] = 1; payload[0x3d] = 2; payload[0x3e] = 3; payload[0x3f] = -1
        putUInt(payload, 0x8c, 2, 1750); putUInt(payload, 0x9e, 2, 1000)
        val uri = NdefRecord.createUri("https://opentag3d.info")
        val mime = NdefRecord.createMime(OpenTag3dV2Adapter.format.mimeType, payload)
        val nfc = NfcCoordinator(RuntimeEnvironment.getApplication())
        try {
            val decoded = nfc.decodeSingleTag(NdefMessage(arrayOf(uri, mime))) as DecodeResult.Supported
            assertEquals("OpenTag3D 2.000", decoded.formatName)
            assertEquals("Example", decoded.convertedRecord?.brand?.value)
        } finally { nfc.shutdown() }
    }

    @Test fun twoSupportedFilamentRecordsRemainAmbiguous() {
        val first = NdefRecord.createMime("application/json", "{}".encodeToByteArray())
        val second = NdefRecord.createMime(OpenPrintTagAdapter.format.mimeType, byteArrayOf(0xa0.toByte(), 0xa0.toByte()))
        val nfc = NfcCoordinator(RuntimeEnvironment.getApplication())
        try {
            val result = nfc.decodeSingleTag(NdefMessage(arrayOf(first, second))) as DecodeResult.Rejected
            assertTrue(result.reason.startsWith("Multiple NDEF records"))
        } finally { nfc.shutdown() }
    }

    private fun putText(out: ByteArray, offset: Int, length: Int, value: String) = value.encodeToByteArray().take(length).forEachIndexed { i, b -> out[offset + i] = b }
    private fun putUInt(out: ByteArray, offset: Int, length: Int, value: Long) { repeat(length) { i -> out[offset + length - 1 - i] = (value ushr (i * 8)).toByte() } }
}
