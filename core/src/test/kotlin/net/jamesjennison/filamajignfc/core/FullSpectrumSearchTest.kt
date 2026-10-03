package net.jamesjennison.filamajignfc.core

import kotlin.test.*

class FullSpectrumSearchTest {
    @Test fun `completeness reports missing primaries and anchors without scoring`() {
        val result=SetCompletenessService.analyze(listOf("C","Y","WHITE"))
        assertFalse(result.cmygComplete)
        assertEquals(setOf("M","G"),result.missingMixingRoles)
        assertEquals(setOf("WHITE"),result.anchors)
    }

    @Test fun `complete CMYG is deterministic regardless of order or duplicates`() {
        assertTrue(SetCompletenessService.analyze(listOf("G","C","M","Y","C")).cmygComplete)
    }

    @Test fun `search bounds reject inverted TD range`() {
        assertFailsWith<IllegalArgumentException> { FullSpectrumSearchFilter(tdMinimumMm="3",tdMaximumMm="2") }
    }
}
