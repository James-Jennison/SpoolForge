package net.jamesjennison.filamajignfc.core
import kotlin.test.*
class GtinTest {
    @Test fun paddedRepresentationsResolveToSameKey() {
        assertEquals("00036000291452", Gtin.normalize("036000291452"))
        assertEquals(Gtin.normalize("036000291452"), Gtin.normalize("0036000291452"))
        assertEquals("07340002119380", Gtin.normalize("7340002119380"))
    }
    @Test fun invalidInputsNeverBecomeBarcodeMatches() {
        listOf("036000291453", "00000000", "1234", "1e12", "036000-291452", "٠٣٦٠٠٠٢٩١٤٥٢", "https://example.com/036000291452").forEach { assertNull(Gtin.normalize(it), it) }
    }
}
