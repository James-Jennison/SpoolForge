package net.jamesjennison.filamajignfc.core

import kotlin.test.*

class OpenSpoolCodecTest {
    private fun record(diameter:String="1.75",min:Int?=230,max:Int?=260)=FilamentRecord(
        "14417b3a-38d3-4643-ace8-40bdc9b7ecf3","4f6c118e-7196-4e13-9345-2f263cf0b430","5a04c396-3b00-474a-9af8-d6a09018d85f",
        FieldValue("SUNLU","OFD"),FieldValue("ABS","OFD"),FieldValue("ABS","OFD"),FieldValue("Orange","OFD"),FieldValue("#FF8E24","OFD"),FieldValue(diameter,"OFD"),FieldValue(1000,"OFD"),min?.let{FieldValue(it,"OFD")},max?.let{FieldValue(it,"OFD")},FieldValue(90,"OFD"),FieldValue(110,"OFD"),sourceRevision="e3888b68",provenance=Provenance.CATALOG)
    @Test fun `paxx uses documented numeric fields and round trips semantically`() {
        val intent=OpenSpoolCodec.encode(record(),OpenSpoolProfile.PAXX)
        val json=intent.payload.decodeToString()
        assertContains(json,"\"diameter\":1.75")
        assertContains(json,"\"min_temp\":230")
        assertContains(json,"\"bed_min_temp\":90")
        assertTrue(OpenSpoolCodec.semanticallyMatches(intent,intent.payload))
    }
    @Test fun `paxx maps common variants to U1 recognized vocabulary`() {
        val plus=OpenSpoolCodec.encode(record().copy(material=FieldValue("PLA+","scan"),product=FieldValue("PLA Plus","scan")),OpenSpoolProfile.PAXX)
        assertContains(plus.payload.decodeToString(),"\"type\":\"PLA\"")
        assertContains(plus.payload.decodeToString(),"\"subtype\":\"Basic\"")
        assertContains(plus.omittedFields,"product")
        val matte=OpenSpoolCodec.encode(record().copy(material=FieldValue("PLA","scan"),product=FieldValue("Matte PLA","scan")),OpenSpoolProfile.PAXX)
        assertContains(matte.payload.decodeToString(),"\"subtype\":\"Matte\"")
        assertFailsWith<ValidationException>{OpenSpoolCodec.encode(record().copy(material=FieldValue("HIPS","scan")),OpenSpoolProfile.PAXX)}
    }
    @Test fun `paxx preserves transmission distance as numeric extension`() {
        val intent=OpenSpoolCodec.encode(record().copy(transmissionDistance=FieldValue("6.60","3D Filament Profiles")),OpenSpoolProfile.PAXX)
        assertContains(intent.payload.decodeToString(),"\"transmission_distance\":6.6")
        assertFalse("transmissionDistance" in intent.omittedFields)
        assertTrue(OpenSpoolCodec.semanticallyMatches(intent,intent.payload))
        assertContains(OpenSpoolCodec.encode(intent.record,OpenSpoolProfile.CANONICAL).omittedFields,"transmissionDistance")
    }
    @Test fun `transmission distance range and json type are enforced`() {
        listOf("0","100.1","not-a-number").forEach { value -> assertFailsWith<ValidationException>{ OpenSpoolCodec.encode(record().copy(transmissionDistance=FieldValue(value,"test")),OpenSpoolProfile.PAXX) } }
        assertIs<DecodeResult.Rejected>(OpenSpoolCodec.decode("""{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"FF0000","brand":"B","transmission_distance":"6.6"}""".encodeToByteArray()))
    }
    @Test fun `omission preview follows actual serialized fields`() {val missing=OpenSpoolCodec.encode(record(min=null,max=null),OpenSpoolProfile.PAXX);assertContains(missing.omittedFields,"nozzleMin");assertContains(missing.omittedFields,"nozzleMax");val canonical=OpenSpoolCodec.encode(record(),OpenSpoolProfile.CANONICAL);assertContains(canonical.omittedFields,"diameter");assertContains(canonical.omittedFields,"product")}
    @Test fun `legacy diameter is rejected`() { assertFailsWith<ValidationException>{OpenSpoolCodec.encode(record("175"),OpenSpoolProfile.PAXX)} }
    @Test fun `inverted temperatures are rejected`() { assertFailsWith<ValidationException>{OpenSpoolCodec.encode(record(min=260,max=230),OpenSpoolProfile.CANONICAL)} }
    @Test fun `duplicate keys are rejected`() { assertIs<DecodeResult.Rejected>(OpenSpoolCodec.decode("""{"protocol":"openspool","protocol":"openspool","version":"1.0"}""".encodeToByteArray())) }
    @Test fun `unknown version and fields are read only`() {
        assertIs<DecodeResult.ReadOnly>(OpenSpoolCodec.decode("""{"protocol":"openspool","version":"2.0"}""".encodeToByteArray()))
        assertIs<DecodeResult.ReadOnly>(OpenSpoolCodec.decode("""{"protocol":"openspool","version":"1.0","future":1}""".encodeToByteArray()))
    }
    @Test fun `mandatory fields and exact json types are enforced`() {
        listOf(
            """{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"FF0000"}""",
            """{"protocol":"openspool","version":"1.0","type":1,"color_hex":"FF0000","brand":"B"}""",
            """{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":1,"brand":"B"}""",
            """{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"FF0000","brand":"B","diameter":"175"}""",
            """{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"FF0000","brand":"B","max_temp":"501"}""",
            """{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"FF0000","brand":"B","weight":-1}"""
        ).forEach { assertIs<DecodeResult.Rejected>(OpenSpoolCodec.decode(it.encodeToByteArray()),it) }
    }
    @Test fun `alpha is preserved read only and extra colors are not truncated`() {
        assertIs<DecodeResult.ReadOnly>(OpenSpoolCodec.decode("""{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"FF0000","brand":"B","alpha":255}""".encodeToByteArray()))
        assertFailsWith<ValidationException>{OpenSpoolCodec.encode(record().copy(additionalColors=listOf("000000","111111","222222","333333","444444")),OpenSpoolProfile.PAXX)}
    }
    @Test fun `unicode digits lone surrogate and excessive number are rejected`() {
        listOf("""{"n":١}""","""{"n":${"1".repeat(129)}}""","""{"s":"\uD800"}""").forEach{assertFailsWith<ValidationException>{StrictJson().parse(it.encodeToByteArray())}}
        val paired=StrictJson().parse("""{"s":"\uD83D\uDE00"}""".encodeToByteArray()) as JsonValue.Obj
        assertEquals("😀",paired.string("s"))
    }
    @Test fun `utf8 capacity counts bytes`() { val r=record().copy(brand=FieldValue("Märkä","custom")); val intent=OpenSpoolCodec.encode(r,OpenSpoolProfile.CANONICAL); assertTrue(intent.payload.size>intent.payload.decodeToString().length) }
    @Test fun sourceColorsRemainVendorNeutralAndExportLimitIsProfileSpecific() {
        val many = record().copy(additionalColors = listOf("000000", "111111", "222222", "333333", "444444"))
        validateRecord(many)
        val canonical = OpenSpoolCodec.encode(many, OpenSpoolProfile.CANONICAL)
        assertContains(canonical.omittedFields, "additionalColors")
        assertEquals(5, canonical.record.additionalColors.size)
        assertFailsWith<ValidationException> { OpenSpoolCodec.encode(many, OpenSpoolProfile.PAXX) }
        assertIs<DecodeResult.Supported>(OpenSpoolCodec.decode(OpenSpoolCodec.encode(many.copy(additionalColors=many.additionalColors.take(4)), OpenSpoolProfile.PAXX).payload))
        assertFailsWith<ValidationException> { validateRecord(many.copy(additionalColors = listOf("not-a-color"))) }
    }
    @Test fun canonicalKeepsMissingProductAbsentWhilePaxxUsesItsDocumentedBasicFallback() {
        val sparse=record().copy(product=FieldValue("","tag"),colorName=FieldValue("","tag"))
        val canonical=OpenSpoolCodec.encode(sparse,OpenSpoolProfile.CANONICAL)
        val paxx=OpenSpoolCodec.encode(sparse,OpenSpoolProfile.PAXX)
        assertFalse(canonical.payload.decodeToString().contains("subtype"))
        assertContains(paxx.payload.decodeToString(),"\"subtype\":\"Basic\"")
    }
}
