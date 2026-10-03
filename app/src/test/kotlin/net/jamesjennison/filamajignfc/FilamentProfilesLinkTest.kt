package net.jamesjennison.filamajignfc

import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentProfilesLinkTest {
    @Test fun termsBecomeOneEncodedSearchQuery() {
        assertEquals(
            "https://3dfilamentprofiles.com/filaments?q=ELEGOO+PLA%2B+True+Red",
            filamentProfilesSearchUrl(" ELEGOO ", "PLA+", null, "", "True  Red"),
        )
    }

    @Test fun hexColorAndReservedCharactersAreEncoded() {
        assertEquals("https://3dfilamentprofiles.com/filaments?q=%23ff0000", filamentProfilesSearchUrl("#ff0000"))
        assertEquals("https://3dfilamentprofiles.com/filaments?q=a%26b%3Dc", filamentProfilesSearchUrl("a&b=c"))
    }

    @Test fun noUsableTermsOpensTheCatalog() {
        assertEquals("https://3dfilamentprofiles.com/filaments", filamentProfilesSearchUrl(null, " ", ""))
    }
}
