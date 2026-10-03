package net.jamesjennison.filamajignfc.core

import kotlin.test.*

/** Contract fixtures for SnapmakerU1-Extended-Firmware v1.5.2-paxx12-21 at 8d97e83f. */
class PaxxPinnedReleaseCodecTest {
    private fun record(material: String, product: String, brand: String="Fixture", color: String="AFAFAF", mass: Int=1000) = FilamentRecord(
        "fixture", null, null, FieldValue(brand,"fixture"), FieldValue(material,"fixture"),
        FieldValue(product,"fixture"), FieldValue("Fixture","fixture"), FieldValue(color,"fixture"),
        FieldValue("1.75","fixture"), FieldValue(mass,"fixture"), FieldValue(230,"fixture"),
        FieldValue(260,"fixture"), FieldValue(50,"fixture"), FieldValue(65,"fixture"),
        sourceRevision="v1.5.2-paxx12-21", provenance=Provenance.CUSTOM,
    )

    @Test fun registryHasStableIdsAndPinnedCompatibilityTarget() {
        assertContains(TagCodecRegistry.codecs.map { it.format.id },"openspool-1.0")
        assertContains(TagCodecRegistry.codecs.map { it.format.id },"openspool-paxx-u1-1.0")
        assertSame(ElegooCanvasTagCodec,TagCodecRegistry.require(TagCodecRegistry.DEFAULT_CODEC_ID))
        assertContains(PaxxU1ExtendedTagCodec.format.compatibilityTarget,PaxxU1ExtendedTagCodec.TARGET_FIRMWARE)
        assertFailsWith<ValidationException> { TagCodecRegistry.require("future") }
    }

    @Test fun standardFixtureIsByteStableAndReportsExactWireContract() {
        val encoded=StandardOpenSpoolTagCodec.encode(record("PLA","Basic"))
        assertEquals("""{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"AFAFAF","brand":"Fixture","min_temp":"230","max_temp":"260"}""",encoded.payload.decodeToString())
        assertEquals(setOf("protocol","version","type","color_hex","brand","min_temp","max_temp"),encoded.includedWireFields)
        assertTrue(encoded.fits(492))
        assertIs<DecodeResult.Supported>(StandardOpenSpoolTagCodec.decode(encoded.payload))
    }

    @Test fun paxxFixtureIsByteStableUsesNumericTypesAndReportsExactWireContract() {
        val encoded=PaxxU1ExtendedTagCodec.encode(record("PETG","Rapid PETG").copy(
            additionalColors=listOf("EEFFEE","FF00FF"), transmissionDistance=FieldValue("2.70","fixture"),
        ))
        assertEquals("""{"protocol":"openspool","version":"1.0","type":"PETG","color_hex":"AFAFAF","brand":"Fixture","min_temp":230,"max_temp":260,"subtype":"Rapid","bed_min_temp":50,"bed_max_temp":65,"diameter":1.75,"weight":1000,"additional_color_hexes":["EEFFEE", "FF00FF"],"transmission_distance":2.7}""",encoded.payload.decodeToString())
        assertEquals(setOf("protocol","version","type","color_hex","brand","min_temp","max_temp","subtype","bed_min_temp","bed_max_temp","diameter","weight","additional_color_hexes","transmission_distance"),encoded.includedWireFields)
        assertTrue(encoded.fits(492))
        assertIs<DecodeResult.Supported>(PaxxU1ExtendedTagCodec.decode(encoded.payload))
    }

    @Test fun pinnedReleaseTypeAndSubtypeMatrixMatchesParserPassthroughExamples() {
        val cases=mapOf(
            record("PLA","Silk multicolor") to ("PLA" to "Silk"),
            record("PETG","Rapid") to ("PETG" to "Rapid"),
            record("ABS","Transparent") to ("ABS" to "Transparent"),
            record("TPU","Flexible",mass=500) to ("TPU" to "Flexible"),
            record("PVA","Support") to ("PVA" to "Support"),
        )
        cases.forEach { (source, expected) ->
            val decoded=PaxxU1ExtendedTagCodec.decode(PaxxU1ExtendedTagCodec.encode(source).payload) as DecodeResult.Supported
            assertEquals(expected.first,(decoded.values.getValue("type") as JsonValue.Str).value)
            assertEquals(expected.second,(decoded.values.getValue("subtype") as JsonValue.Str).value)
        }
    }
}
