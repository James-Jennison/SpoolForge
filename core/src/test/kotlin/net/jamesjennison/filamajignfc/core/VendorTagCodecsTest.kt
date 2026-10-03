package net.jamesjennison.filamajignfc.core

import kotlin.test.*

class VendorTagCodecsTest {
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun record(color:String="FAFAFA")=FilamentRecord(
        "spool",null,null,FieldValue("ELEGOO","test"),FieldValue("PLA","test"),FieldValue("PLA Basic","test"),
        FieldValue("White","test"),FieldValue(color,"test"),FieldValue("1.75","test"),FieldValue(1000,"test"),
        FieldValue(205,"test"),FieldValue(220,"test"),FieldValue(55,"test"),FieldValue(65,"test"),
        sourceRevision="test",provenance=Provenance.CUSTOM,transmissionDistance=FieldValue("6.6","test"),
    )

    @Test fun `canvas payload is one raw format and round trips supported profile fields`() {
        val encoded=ElegooCanvasTagCodec.encode(record())
        assertEquals(TagTransport.NTAG_RAW,encoded.format.transport)
        assertEquals(1,encoded.ndefRecords.size)
        assertEquals(ElegooCanvasTagCodec.format.id,encoded.ndefRecords.single().mime)
        assertEquals(ElegooCanvasTagCodec.PAYLOAD_BYTES,encoded.sizeBytes)
        assertFalse(encoded.payload.decodeToString().contains("openspool",ignoreCase=true))
        val decoded=assertIs<DecodeResult.Supported>(ElegooCanvasTagCodec.decode(encoded.payload)).convertedRecord!!
        assertEquals("ELEGOO",decoded.brand.value)
        assertEquals(ElegooCanvasTagCodec.BRAND_SOURCE,decoded.brand.source)
        assertEquals("ELEGOO CANVAS tag",decoded.material.source)
        assertEquals("PLA",decoded.material.value)
        assertEquals("PLA Basic",decoded.product.value)
        assertEquals("FAFAFA",decoded.colorHex.value)
        assertEquals("1.75",decoded.diameterMm.value)
        assertEquals(1000,decoded.nominalMassG.value)
        assertEquals(205,decoded.nozzleMinC?.value)
        assertEquals(220,decoded.nozzleMaxC?.value)
    }

    @Test fun `canvas reports a non-ELEGOO brand as omitted instead of rejecting it`() {
        val elegoo=ElegooCanvasTagCodec.encode(record())
        val other=ElegooCanvasTagCodec.encode(record().copy(brand=FieldValue("Snapmaker","test")))
        assertContentEquals(elegoo.payload,other.payload)
        assertTrue("brand" in other.omittedFields)
        assertTrue("brand" in elegoo.omittedFields)
        assertTrue("manufacturer" in other.includedWireFields)
        assertEquals("ELEGOO",assertIs<DecodeResult.Supported>(ElegooCanvasTagCodec.decode(other.payload)).convertedRecord!!.brand.value)
    }

    @Test fun `canvas encoding is byte exact against pinned OpenRFID processor offsets`() {
        val expected=hex("36eeeeeeee0000000080766500090000fafafaff00cd00dc0000000000af03e8000000000000000000")
        val encoded=ElegooCanvasTagCodec.encodeCanvasPayload(record())
        assertContentEquals(expected,encoded.copyOfRange(0,expected.size))
        assertTrue(encoded.copyOfRange(expected.size,encoded.size).all{it==0.toByte()})
    }

    @Test fun `pinned OpenRFID factory fixture decodes at exact field offsets`() {
        val fixture=ByteArray(ElegooCanvasTagCodec.PAYLOAD_BYTES)
        hex("36eeeeeeee0000000080766500000000ff0000ff00be00e60000000000af03e80036c8000000000000").copyInto(fixture)
        val decoded=assertIs<DecodeResult.Supported>(ElegooCanvasTagCodec.decode(fixture)).convertedRecord!!
        assertEquals("PLA",decoded.product.value)
        assertEquals("FF0000",decoded.colorHex.value)
        assertEquals(190,decoded.nozzleMinC?.value)
        assertEquals(230,decoded.nozzleMaxC?.value)
        assertEquals("1.75",decoded.diameterMm.value)
        assertEquals(1000,decoded.nominalMassG.value)
    }

    @Test fun `encoder reproduces every pinned OpenRFID processor-consumed fixture byte`() {
        val pinned=hex("36eeeeeeee0000000080766500000000ff0000ff00be00e60000000000af03e80036c8000000000000")
        val profile=record("FF0000").copy(product=FieldValue("PLA","test"),nozzleMinC=FieldValue(190,"test"),nozzleMaxC=FieldValue(230,"test"))
        val encoded=ElegooCanvasTagCodec.encodeCanvasPayload(profile)
        // Pinned ElegooTagProcessor consumes offsets 0..31. Factory offsets
        // 32..40 are production metadata that it does not read and SpoolForge
        // does not invent; the encoder leaves them zero.
        assertContentEquals(pinned.copyOfRange(0,32),encoded.copyOfRange(0,32))
        assertTrue(encoded.copyOfRange(32,41).all{it==0.toByte()})
    }

    @Test fun `canvas one-format rule rejects competing bytes outside pages 16 through 31`() {
        val area=ByteArray(ElegooCanvasTagCodec.INSPECTED_USER_BYTES)
        ElegooCanvasTagCodec.encodeCanvasPayload(record()).copyInto(area,ElegooCanvasTagCodec.USER_AREA_OFFSET)
        assertFalse(ElegooCanvasTagCodec.hasCompetingUserData(area))
        area[0]=0x03
        assertTrue(ElegooCanvasTagCodec.hasCompetingUserData(area))
        area[0]=0
        area[(15-4)*4]=0x01
        assertTrue(ElegooCanvasTagCodec.hasCompetingUserData(area))
        area[(15-4)*4]=0
        area[(32-4)*4]=0x01
        assertTrue(ElegooCanvasTagCodec.hasCompetingUserData(area))
        area[(32-4)*4]=0
        area[area.lastIndex]=0x01
        assertTrue(ElegooCanvasTagCodec.hasCompetingUserData(area))
    }

    @Test fun `canvas one-format rule permits the factory empty NDEF marker but no message`() {
        val area=ByteArray(ElegooCanvasTagCodec.INSPECTED_USER_BYTES)
        byteArrayOf(0x03,0x00,0xFE.toByte(),0x00).copyInto(area)
        ElegooCanvasTagCodec.encodeCanvasPayload(record()).copyInto(area,ElegooCanvasTagCodec.USER_AREA_OFFSET)
        assertFalse(ElegooCanvasTagCodec.hasCompetingUserData(area))
        area[1]=0x01
        assertTrue(ElegooCanvasTagCodec.hasCompetingUserData(area))
    }

    @Test fun `Anycubic TigerTag OpenTag3D and OpenPrintTag round trip supported fields`() {
        listOf(AnycubicAceTagCodec,TigerTagWriteCodec,OpenTag3dWriteCodec,OpenPrintTagWriteCodec).forEach { codec ->
            val encoded=codec.encode(record())
            assertIs<DecodeResult.Supported>(codec.decode(encoded.payload),codec.format.displayName)
        }
    }

    @Test fun `QIDI requires exact registered palette color and round trips its block`() {
        val encoded=QidiBoxTagCodec.encode(record())
        assertEquals(16,encoded.sizeBytes)
        assertIs<DecodeResult.Supported>(QidiBoxTagCodec.decode(encoded.payload))
        assertFailsWith<ValidationException>{QidiBoxTagCodec.encode(record("FFFFFF"))}
    }

    @Test fun `Creality CFS encrypts three blocks and round trips supported fields`() {
        val encoded=CrealityCfsTagCodec.encode(record())
        assertEquals(48,encoded.sizeBytes)
        val decoded=assertIs<DecodeResult.Supported>(CrealityCfsTagCodec.decode(encoded.payload))
        assertEquals("PLA",decoded.convertedRecord?.material?.value)
        assertEquals("FAFAFA",decoded.convertedRecord?.colorHex?.value)
        assertEquals(1000,decoded.convertedRecord?.nominalMassG?.value)
        assertFailsWith<ValidationException>{CrealityCfsTagCodec.encode(record().copy(diameterMm=FieldValue("2.85","test")))}
    }

    @Test fun `Creality CFS derives the documented sector key from a four byte uid`() {
        assertContentEquals(byteArrayOf(0x1F,0x1E,0x83.toByte(),0xA9.toByte(),0x71,0x82.toByte()),CrealityCfsTagCodec.authenticationKey(byteArrayOf(0x60,0xEA.toByte(),0x12,0x21)))
        assertFailsWith<IllegalArgumentException>{CrealityCfsTagCodec.authenticationKey(byteArrayOf(1,2,3,4,5,6,7))}
    }

    @Test fun `Creality CFS validates blank and finalized transport trailers`() {
        val trailer=ByteArray(16){0xFF.toByte()}.also{byteArrayOf(0xFF.toByte(),0x07,0x80.toByte(),0x69).copyInto(it,6)}
        assertTrue(CrealityCfsTagCodec.hasSupportedBlankTrailer(trailer))
        trailer[8]=0
        assertFalse(CrealityCfsTagCodec.hasSupportedBlankTrailer(trailer))
        assertFalse(CrealityCfsTagCodec.hasSupportedBlankTrailer(ByteArray(15)))
        byteArrayOf(0xFF.toByte(),0x07,0x80.toByte(),0x69).copyInto(trailer,6)
        val derived=byteArrayOf(1,2,3,4,5,6);derived.copyInto(trailer,10)
        assertTrue(CrealityCfsTagCodec.hasFinalizedTrailer(trailer,derived))
        trailer[15]=7
        assertFalse(CrealityCfsTagCodec.hasFinalizedTrailer(trailer,derived))
    }

    @Test fun `registry exposes each unblocked target format`() {
        assertEquals(setOf("openspool-1.0","openspool-paxx-u1-1.0","elegoo-canvas-1.0","openprinttag-current-write","anycubic-ace-v2","creality-cfs-1","opentag3d-2.000-write","qidi-box-1","tigertag-2.1"),TagCodecRegistry.codecs.map{it.format.id}.toSet())
    }
}
