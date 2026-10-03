package net.jamesjennison.filamajignfc.core

import kotlin.test.*

class SpoolmanCodecTest {
    @Test fun `imports official Spoolman spool export shape with provenance`() {
        val json = """[{"id":42,"initial_weight":1000,"remaining_weight":625,
          "filament.id":7,"filament.name":"PolyLite Orange","filament.vendor.id":3,"filament.vendor.name":"Polymaker",
          "filament.material":"PLA","filament.density":1.24,"filament.diameter":1.75,"filament.weight":1000,
          "filament.article_number":"6938936700173","filament.settings_extruder_temp":220,"filament.settings_bed_temp":60,
          "filament.color_hex":"FF8E24","used_weight":375,"archived":false}]""".encodeToByteArray()
        val imported = SpoolmanCodec.decodeExport(json).single()
        assertEquals(42, imported.spoolmanSpoolId)
        assertEquals(7, imported.spoolmanFilamentId)
        assertEquals(625, imported.remainingQuantityG)
        assertEquals("Polymaker", imported.filament.brand.value)
        assertEquals("PLA", imported.filament.material.value)
        assertEquals("FF8E24", imported.filament.colorHex.value)
        assertEquals("6938936700173", imported.filament.gtin)
        assertTrue(imported.filament.brand.source.startsWith("Spoolman JSON export"))
    }

    @Test fun `imports filament-only export and marks safe review defaults`() {
        val imported = SpoolmanCodec.decodeExport("""[{"id":8,"name":"Mystery","material":"PETG","vendor":null,"density":1.27}]""".encodeToByteArray()).single()
        assertEquals("Unknown vendor", imported.filament.brand.value)
        assertEquals("1.75", imported.filament.diameterMm.value)
        assertEquals(1000, imported.filament.nominalMassG.value)
        assertTrue(imported.warnings.any { "Vendor" in it })
        assertTrue(imported.warnings.any { "Diameter" in it })
    }

    @Test fun `builds reviewed Spoolman create requests without performing sync`() {
        val source = "test"
        val record = FilamentRecord("local-1", null, null, FieldValue("SUNLU",source),FieldValue("PLA",source),FieldValue("Meta",source),FieldValue("Orange",source),FieldValue("FF8E24",source),FieldValue("1.75",source),FieldValue(1000,source),FieldValue(200,source),FieldValue(220,source),FieldValue(55,source),FieldValue(65,source),"6938936700173","PLA-ORANGE","test",Provenance.CUSTOM)
        val requests = SpoolmanCodec.syncRequests(record, remainingQuantityG = 900)
        assertTrue(requests.vendorJson.decodeToString().contains("\"name\":\"SUNLU\""))
        val filament = requests.filamentJson.decodeToString()
        assertTrue(filament.contains("\"density\":1.24"))
        assertFalse(filament.contains("vendor_id"))
        assertTrue(requests.spoolJson(12).decodeToString().contains("\"filament_id\":12"))
        assertTrue(requests.warnings.any { "reviewed" in it })
        val withVendor = SpoolmanCodec.filamentRequest(record, 9).decodeToString()
        assertTrue(withVendor.contains("\"vendor_id\":9"))
    }

    @Test fun `rejects unsupported density inference and invalid exports`() {
        val source = "test"
        val record = FilamentRecord("local-2",null,null,FieldValue("Brand",source),FieldValue("PEEK",source),FieldValue("",source),FieldValue("Black",source),FieldValue("000000",source),FieldValue("1.75",source),FieldValue(1000,source),null,null,null,null,null,null,"test",Provenance.CUSTOM)
        assertFailsWith<ValidationException> { SpoolmanCodec.syncRequests(record) }
        assertFailsWith<ValidationException> { SpoolmanCodec.decodeExport("[]".encodeToByteArray()) }
        assertFailsWith<ValidationException> { SpoolmanCodec.decodeExport("not json".encodeToByteArray()) }
    }
}
