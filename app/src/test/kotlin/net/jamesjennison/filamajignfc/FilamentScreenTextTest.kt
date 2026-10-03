package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.Provenance
import net.jamesjennison.filamajignfc.data.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentScreenTextTest {
    private fun item(brand: String = "snapmaker", product: String = "PLA Translucent", colorName: String = "gray", hex: String = "808080", td: String? = null, bedMax: Int? = 65) =
        FilamentItem(CatalogEntry("p", "v", "pr", brand, "PLA", product, colorName, hex, "1.75", 1000, 190, 230, 25, bedMax, null, null, "rev"), Provenance.CUSTOM, emptyMap(), transmissionDistance = td)

    @Test fun lowerCaseBrandsAreCapitalizedAndStyledBrandsAreKept() {
        assertEquals("Snapmaker PLA Translucent", displayName(item()))
        assertEquals("eSUN PLA+", displayName(item(brand = "eSUN", product = "PLA+")))
        assertEquals("ELEGOO PLA Basic", displayName(item(brand = "ELEGOO", product = "ELEGOO PLA Basic")))
    }

    @Test fun colorShowsANameWhenThereIsOneAndTheHexCodeOtherwise() {
        assertEquals("Gray", displayColor(item()))
        assertEquals("#FF00FF", displayColor(item(colorName = "FF00FF", hex = "ff00ff")))
        assertEquals("#808080", displayColor(item(colorName = "")))
        assertEquals("Unknown color", displayColor(item(colorName = "", hex = "")))
    }

    @Test fun onlyValuesTheFilamentHasAreReportedAsLeftOffTheTag() {
        val omitted = setOf("brand", "colorName", "bedTemperatureRange", "additionalColors", "transmissionDistance", "sku", "gtin")
        assertEquals(listOf("bed temperature range", "brand", "color name"), valuesLeftOffTag(item(), omitted))
        assertEquals(listOf("bed temperature range", "brand", "color name", "transmission distance"), valuesLeftOffTag(item(td = "6.5"), omitted))
        assertEquals(listOf("somethingNew"), valuesLeftOffTag(item(), setOf("somethingNew")))
    }
}
