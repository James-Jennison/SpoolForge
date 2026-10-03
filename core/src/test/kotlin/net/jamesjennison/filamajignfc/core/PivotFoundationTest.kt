package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal
import kotlin.test.*

class PivotFoundationTest {
    private val user = SourceRef("local", kind = EvidenceKind.USER)
    private val profile = FilamentProfile(
        id = "profile-1",
        brand = ObservedValue("Example", user),
        material = ObservedValue("PLA", user),
        colorHex = ObservedValue("#00aDff", user),
        diameterMm = ObservedValue(BigDecimal("1.75"), user),
        nominalFilamentMassG = ObservedValue(1000, user),
        currentTransmissionDistance = ObservedValue(BigDecimal("2.7"), user),
    )

    @Test fun `several physical spools reference one profile without duplicating it`() {
        val a = PhysicalSpool("a", profile.id, 1000, 800)
        val b = PhysicalSpool("b", profile.id, 1000, 1000)
        assertEquals(profile.id, a.filamentProfileId)
        assertEquals(profile.id, b.filamentProfileId)
        assertNotEquals(a.id, b.id)
    }

    @Test fun `canonical profile validates color diameter quantities and td`() {
        assertEquals("00ADFF", normalizeHex(profile.colorHex!!.value))
        assertFailsWith<IllegalArgumentException> { profile.copy(diameterMm = ObservedValue(BigDecimal("175"), user)) }
        assertFailsWith<IllegalArgumentException> { profile.copy(currentTransmissionDistance = ObservedValue(BigDecimal.ZERO, user)) }
        assertFailsWith<IllegalArgumentException> { PhysicalSpool("s", profile.id, 1000, 1001) }
    }

    @Test fun `provenance retains disagreements rather than merging sources`() {
        val manufacturer = SourceRef("maker", "sku-1", "2026-09", kind = EvidenceKind.MANUFACTURER)
        val catalog = SourceRef("ofd", "uuid-1", "v2026.09.06", kind = EvidenceKind.CATALOG)
        val twoClaims = listOf(ObservedValue(205, manufacturer), ObservedValue(215, catalog))
        assertEquals(2, twoClaims.map { it.source }.distinct().size)
        assertEquals(listOf(205, 215), twoClaims.map { it.value })
    }

    @Test fun `tag codecs expose distinct targets and byte capacity`() {
        val legacy = FilamentRecord(
            "pkg", null, null, FieldValue("Example", "local"), FieldValue("PLA", "local"),
            FieldValue("Basic", "local"), FieldValue("Cyan", "local"), FieldValue("00ADFF", "local"),
            FieldValue("1.75", "local"), FieldValue(1000, "local"), FieldValue(190, "local"),
            FieldValue(230, "local"), FieldValue(35, "local"), FieldValue(65, "local"),
            sourceRevision = "local", provenance = Provenance.CUSTOM,
        )
        val standard = StandardOpenSpoolTagCodec.encode(legacy)
        val paxx = PaxxU1ExtendedTagCodec.encode(legacy)
        assertNotEquals(standard.format.id, paxx.format.id)
        assertFalse(standard.payload.decodeToString().contains("\"diameter\""))
        assertContains(paxx.payload.decodeToString(), "\"diameter\":1.75")
        assertTrue(paxx.fits(paxx.ndefMessageSizeBytes))
        assertFalse(paxx.fits(paxx.ndefMessageSizeBytes - 1))
        assertIs<DecodeResult.Supported>(PaxxU1ExtendedTagCodec.decode(paxx.payload))
    }
}
