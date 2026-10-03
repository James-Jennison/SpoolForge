package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class FullSpectrumTest {
    @Test fun `legacy TD origins preserve uncertainty`() {
        assertEquals(TdOrigin.MANUFACTURER_PUBLISHED,TdOrigin.fromLegacy("MANUFACTURER"))
        assertEquals(TdOrigin.MEASURED_UNSPECIFIED,TdOrigin.fromLegacy("MEASURED"))
        assertEquals(TdOrigin.UNKNOWN_UNSPECIFIED,TdOrigin.fromLegacy("new-value"))
        assertEquals(TdOrigin.UNKNOWN_UNSPECIFIED,TdOrigin.fromLegacy(null))
    }

    @Test fun `canonical roles have stable categories and extensions remain possible`() {
        assertEquals(FullSpectrumRoleCategory.MIXING_PRIMARY,FullSpectrumRoles.category("c"))
        assertEquals(FullSpectrumRoleCategory.ANCHOR,FullSpectrumRoles.category("WHITE"))
        assertEquals(FullSpectrumRoleCategory.OTHER,FullSpectrumRoles.category("vendor:pearlescent"))
        assertThrows(IllegalArgumentException::class.java) { FullSpectrumRoles.validate("?") }
    }

    @Test fun `role and suitability scopes reject contradictory states`() {
        assertThrows(IllegalArgumentException::class.java) {
            FullSpectrumRoleAssertion("a","p","C",FullSpectrumRoleCategory.ANCHOR,RoleAuthority.USER_CONFIRMED,assertedAtEpochMs=1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullSpectrumSuitabilityAssessment("s","p",SuitabilityKind.OBSERVED,SuitabilityRating.GOOD,SuitabilityScope.ROLE,rationale="Observed palette",assessor="user",assessedAtEpochMs=1)
        }
    }

    @Test fun `optical characterization validates physical units and confidence`() {
        fun optical(
            unit:String="mm", origin:String=TdOrigin.USER_MEASURED.name,
            confidence:BigDecimal?=BigDecimal("0.8"), evidenceUrl:String?=null,
        ) = OpticalCharacterization("o","p",value=BigDecimal("2.7"),unit=unit,methodKey="TD-1",
            originKey=origin,confidence=confidence,evidenceUrl=evidenceUrl,createdAtEpochMs=1)
        assertEquals("mm",optical().unit)
        assertEquals("x-ajax3d.td1",optical(origin="x-ajax3d.td1").originKey)
        assertThrows(IllegalArgumentException::class.java) { optical(unit="inch") }
        assertThrows(IllegalArgumentException::class.java) { optical(origin="future_guess") }
        assertThrows(IllegalArgumentException::class.java) { optical(confidence=BigDecimal("1.1")) }
        assertThrows(IllegalArgumentException::class.java) { optical(evidenceUrl="http://example.test") }
    }

    @Test fun `appearance assertion requires an extensible typed key`() {
        assertEquals("silk", AppearanceAssertion("a","p","silk","present",assertedAtEpochMs=1).appearanceKey)
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceAssertion("a","p","Silk effect","present",assertedAtEpochMs=1)
        }
    }
}
