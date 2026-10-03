package net.jamesjennison.filamajignfc.core

import kotlin.test.*

class PortableIdentityTest {
    private fun sample(product: String = "PLA Basic") = PortableSpoolIdentity(
        profileId = "profile:manufacturer:cyan", spoolId = "spool:owned:01",
        filament = FilamentRecord(
            "record-1", "variant-1", "product-1", FieldValue("MarsWork", "Label scan"), FieldValue("PLA", "Label scan"),
            FieldValue(product, "Label scan"), FieldValue("Cyan", "Label scan"), FieldValue("00ADFF", "Label scan"),
            FieldValue("1.75", "Assumed SpoolForge default"), FieldValue(1000, "Label scan"), FieldValue(190, "Label scan"),
            FieldValue(230, "Label scan"), FieldValue(35, "Label scan"), FieldValue(65, "Label scan"), "6938936717461", "MW-CYAN",
            "label:sha256", Provenance.CUSTOM, listOf("FF00AA"), FieldValue("2.7", "Printed label"),
        ), initialQuantityG = 1000, remainingQuantityG = 875,
    )

    @Test fun compactQrRoundTripsOfflineAndKeepsSpoolSeparateFromProfile() {
        val expected = sample()
        val encoded = PortableIdentityCodec.encodeQr(expected)
        assertTrue(encoded.size <= PORTABLE_QR_MAX_BYTES)
        val decoded = assertIs<PortableIdentityDecodeResult.Supported>(PortableIdentityCodec.decode(encoded))
        assertEquals(expected.profileId, decoded.identity.profileId)
        assertEquals(expected.spoolId, decoded.identity.spoolId)
        assertEquals(expected.filament.brand.value, decoded.identity.filament.brand.value)
        assertEquals(expected.filament.transmissionDistance?.value, decoded.identity.filament.transmissionDistance?.value)
        assertEquals(expected.remainingQuantityG, decoded.identity.remainingQuantityG)
        assertFalse(decoded.includesSources)
        assertNotEquals(decoded.identity.profileId, decoded.identity.spoolId)
        assertTrue(encoded.decodeToString().contains("MarsWork"))
    }

    @Test fun exportBundleRoundTripsUnicodeAndEveryFieldSource() {
        val expected = sample("PLA Básico 雪色 🧵")
        val encoded = PortableIdentityCodec.encodeBundle(expected)
        val result = PortableIdentityCodec.decode(encoded)
        val decoded = assertIs<PortableIdentityDecodeResult.Supported>(result, result.toString())
        assertEquals(expected, decoded.identity)
        assertTrue(decoded.includesSources)
        assertEquals("PLA Básico 雪色 🧵", decoded.identity.filament.product.value)
        assertEquals("Printed label", decoded.identity.filament.transmissionDistance?.source)
    }

    @Test fun futureVersionIsPreservedWithoutInterpretation() {
        val future = "{\"schema\":\"filamajig.spool\",\"version\":2,\"new\":true}".encodeToByteArray()
        val decoded = assertIs<PortableIdentityDecodeResult.FutureVersion>(PortableIdentityCodec.decode(future))
        assertEquals(2, decoded.version)
        assertContentEquals(future, decoded.raw)
    }

    @Test fun malformedIdentityAndOversizedQrAreRejected() {
        assertIs<PortableIdentityDecodeResult.Rejected>(PortableIdentityCodec.decode("{}".encodeToByteArray()))
        val oversized = sample().let { it.copy(filament = it.filament.copy(sourceRevision = "x".repeat(3000))) }
        assertFailsWith<ValidationException> { PortableIdentityCodec.encodeQr(oversized) }
        assertFailsWith<IllegalArgumentException> { sample().copy(spoolId = sample().profileId) }
    }

    @Test fun strictJsonAcceptsUnicodeSurrogatePairsAndRejectsUnpairedSurrogates() {
        val escaped = StrictJson().parse("{\"value\":\"\\uD83E\\uDDF5\"}".encodeToByteArray()) as JsonValue.Obj
        assertEquals("🧵", escaped.string("value"))
        val literal = StrictJson().parse("{\"value\":\"雪色 🧵\"}".encodeToByteArray()) as JsonValue.Obj
        assertEquals("雪色 🧵", literal.string("value"))
        assertFailsWith<ValidationException> { StrictJson().parse("{\"value\":\"\\uD83E\"}".encodeToByteArray()) }
        assertFailsWith<ValidationException> { StrictJson().parse("{\"value\":\"\\uDDF5\"}".encodeToByteArray()) }
    }
}
