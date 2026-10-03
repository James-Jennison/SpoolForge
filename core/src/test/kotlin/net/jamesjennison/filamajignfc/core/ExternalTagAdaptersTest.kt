package net.jamesjennison.filamajignfc.core

import java.io.ByteArrayOutputStream
import kotlin.test.*

class ExternalTagAdaptersTest {
    @Test fun `decodes official OpenPrintTag fixture and applies documented diameter fallback`() {
        val payload = checkNotNull(javaClass.getResourceAsStream("/external/openprinttag-01-payload.bin")).readBytes()
        val decoded = OpenPrintTagAdapter.decode(payload) as DecodeResult.Supported
        val record = assertNotNull(decoded.convertedRecord)
        assertEquals("Prusament", record.brand.value)
        assertEquals("PLA Prusa Galaxy Black", record.material.value)
        assertEquals("3D3E3D", record.colorHex.value)
        assertEquals("1.75", record.diameterMm.value)
        assertEquals(1012, record.nominalMassG.value)
        assertEquals(205, record.nozzleMinC?.value)
        assertEquals(225, record.nozzleMaxC?.value)
        assertEquals(40, record.bedMinC?.value)
        assertEquals(60, record.bedMaxC?.value)
        assertEquals("8594173675001", record.gtin)
        assertTrue(decoded.warnings.any { "1.75 mm default" in it })
        assertContentEquals(payload, decoded.raw)
    }

    @Test fun `decodes current OPT diameter and preserves unknown fields as a warning`() {
        val main = cborMap(linkedMapOf(
            9L to 1L, 10L to "PETG Basic", 11L to "Example", 16L to 1000L,
            19L to byteArrayOf(1, 2, 3, -1), 27L to 2.7, 34L to 230L, 35L to 250L,
            37L to 70L, 38L to 90L, 61L to 2850L, 99L to "future",
        ), indefinite = true)
        val payload = cborMap(emptyMap()) + main
        val decoded = OpenPrintTagAdapter.decode(payload) as DecodeResult.Supported
        val record = assertNotNull(decoded.convertedRecord)
        assertEquals("2.85", record.diameterMm.value)
        assertEquals("010203", record.colorHex.value)
        assertEquals("2.7", record.transmissionDistance?.value)
        assertTrue(decoded.warnings.any { "99" in it })
    }

    @Test fun `rejects truncated and malformed OPT payloads`() {
        assertIs<DecodeResult.Rejected>(OpenPrintTagAdapter.decode(byteArrayOf()))
        assertIs<DecodeResult.Rejected>(OpenPrintTagAdapter.decode(byteArrayOf(0xa1.toByte(), 0x00)))
    }

    @Test fun `decodes OpenTag3D version 2 core`() {
        val payload = ByteArray(224)
        putUInt(payload, 0x00, 2, 2000)
        putText(payload, 0x02, 5, "PLA")
        putText(payload, 0x07, 5, "Basic")
        putText(payload, 0x0C, 16, "Anycubic")
        putText(payload, 0x1C, 32, "Magenta")
        payload[0x3C] = 0xCE.toByte(); payload[0x3D] = 0x4F; payload[0x3E] = 0x80.toByte(); payload[0x3F] = 0xFF.toByte()
        putText(payload, 0x6C, 16, "AHPLMG-107")
        putUInt(payload, 0x7C, 6, 6938936700173)
        putUInt(payload, 0x8C, 2, 1750)
        payload[0x91] = 38; payload[0x92] = 46; payload[0x95] = 11; payload[0x96] = 13
        putUInt(payload, 0x9E, 2, 1000)
        payload[0xA7] = 27

        val decoded = OpenTag3dV2Adapter.decode(payload) as DecodeResult.Supported
        val record = assertNotNull(decoded.convertedRecord)
        assertEquals("Anycubic", record.brand.value)
        assertEquals("PLA Basic", record.material.value)
        assertEquals("CE4F80", record.colorHex.value)
        assertEquals("1.75", record.diameterMm.value)
        assertEquals(190, record.nozzleMinC?.value)
        assertEquals(230, record.nozzleMaxC?.value)
        assertEquals("2.7", record.transmissionDistance?.value)
        assertEquals("6938936700173", record.gtin)
    }

    @Test fun `new OpenTag3D major is retained read-only and truncation is rejected`() {
        val future = ByteArray(224).also { putUInt(it, 0, 2, 3000) }
        assertIs<DecodeResult.ReadOnly>(OpenTag3dV2Adapter.decode(future))
        assertIs<DecodeResult.Rejected>(OpenTag3dV2Adapter.decode(ByteArray(223)))
    }

    private fun putText(out: ByteArray, offset: Int, length: Int, value: String) = value.encodeToByteArray().take(length).forEachIndexed { i, b -> out[offset + i] = b }
    private fun putUInt(out: ByteArray, offset: Int, length: Int, value: Long) { repeat(length) { i -> out[offset + length - 1 - i] = (value ushr (i * 8)).toByte() } }

    private fun cborMap(values: Map<Long, Any?>, indefinite: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (indefinite) out.write(0xbf) else head(out, 5, values.size.toLong())
        values.forEach { (key, value) -> head(out, 0, key); writeValue(out, value) }
        if (indefinite) out.write(0xff)
        return out.toByteArray()
    }
    private fun writeValue(out: ByteArrayOutputStream, value: Any?) = when (value) {
        is Long -> head(out, 0, value)
        is String -> { val bytes = value.encodeToByteArray(); head(out, 3, bytes.size.toLong()); out.write(bytes) }
        is ByteArray -> { head(out, 2, value.size.toLong()); out.write(value) }
        is Double -> { out.write(0xfb); val bits = value.toBits(); repeat(8) { i -> out.write((bits ushr (56 - i * 8)).toInt()) } }
        else -> error("unsupported fixture value")
    }
    private fun head(out: ByteArrayOutputStream, major: Int, value: Long) {
        when {
            value < 24 -> out.write((major shl 5) or value.toInt())
            value <= 0xff -> { out.write((major shl 5) or 24); out.write(value.toInt()) }
            value <= 0xffff -> { out.write((major shl 5) or 25); out.write((value ushr 8).toInt()); out.write(value.toInt()) }
            else -> { out.write((major shl 5) or 27); repeat(8) { i -> out.write((value ushr (56 - i * 8)).toInt()) } }
        }
    }
}
