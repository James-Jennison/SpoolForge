package net.jamesjennison.filamajignfc.core

import org.junit.Assert.*
import org.junit.Test

class TdOriginTest {
    @Test fun `legacy TD origins preserve uncertainty`() {
        assertEquals(TdOrigin.MANUFACTURER_PUBLISHED,TdOrigin.fromLegacy("MANUFACTURER"))
        assertEquals(TdOrigin.MEASURED_UNSPECIFIED,TdOrigin.fromLegacy("MEASURED"))
        assertEquals(TdOrigin.UNKNOWN_UNSPECIFIED,TdOrigin.fromLegacy("new-value"))
        assertEquals(TdOrigin.UNKNOWN_UNSPECIFIED,TdOrigin.fromLegacy(null))
    }
}
