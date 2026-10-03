package net.jamesjennison.filamajignfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BulkCsvImportTest {
    @Test fun acceptsUtf8BomFromSpreadsheetExport() {
        val record = parseBulkCsv("\uFEFFbrand,material,color\nExample,PLA,Blue").single()
        assertEquals("Example", record.seed.brand)
    }

    @Test fun identifierFieldsRetainBulkRowProvenance() {
        val record = parseBulkCsv("brand,material,color,gtin,sku\nGizmo Dorks,HIPS,White,887503120752,A09C").single()
        assertEquals("Bulk CSV row 2", record.seed.sources["gtin"])
        assertEquals("Bulk CSV row 2", record.seed.sources["articleNumber"])
    }

    @Test fun importsCsvWithDefaultsAndProvenance() {
        val records = parseBulkCsv("brand,material,color,product,gtin,sku\nGryddle,PLA+,Gray,Gray PLA+,027680274484,G-PLA-GRAY")
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(2, record.row)
        assertEquals("Gryddle", record.seed.brand)
        assertEquals("00027680274484", record.seed.gtin)
        assertEquals("1.75", record.seed.diameter)
        assertEquals("1000", record.seed.mass)
        assertEquals(ASSUMED_DEFAULT_SOURCE, record.seed.sources.getValue("diameter"))
        assertEquals(2, record.warnings.size)
    }

    @Test fun supportsTabsQuotedFieldsAndExplicitPhysicalValues() {
        val records = parseBulkCsv("brand\tmaterial\tcolor\tproduct\tdiameter_mm\tweight_g\n\"Example, Inc.\"\tPETG\tBlue\t\"Fast, Clear\"\t2.85\t750")
        assertEquals("Example, Inc.", records.single().seed.brand)
        assertEquals("Fast, Clear", records.single().seed.product)
        assertEquals("2.85", records.single().seed.diameter)
        assertEquals("750", records.single().seed.mass)
        assertTrue(records.single().warnings.isEmpty())
    }

    @Test fun rejectsMalformedOrAmbiguousBulkInput() {
        assertFailsWith<IllegalArgumentException> { parseBulkCsv("brand,material,color\nOnly,two") }
        assertFailsWith<IllegalArgumentException> { parseBulkCsv("brand,material,color\nA,PLA,Red,extra") }
        assertFailsWith<IllegalArgumentException> { parseBulkCsv("brand,material,color,gtin\nA,PLA,Red,123") }
        assertFailsWith<IllegalArgumentException> { parseBulkCsv("brand,material,color\n\"A,PLA,Red") }
    }
}
