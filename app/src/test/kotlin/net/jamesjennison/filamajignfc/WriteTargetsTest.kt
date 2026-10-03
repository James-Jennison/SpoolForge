package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.CompatibilityResolver
import net.jamesjennison.filamajignfc.core.CompatibilityResult
import net.jamesjennison.filamajignfc.core.TagCodecRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteTargetsTest {
    @Test fun everyTargetLeadsToARegisteredTagFormat() {
        WriteTargets.all.forEach { target ->
            val codecId = target.codecId ?: (CompatibilityResolver.resolve(target.printers) as CompatibilityResult.Resolved).resolution.codecId
            assertEquals(codecId, TagCodecRegistry.require(codecId).format.id)
            assertTrue(target.label.isNotBlank() && target.tagToUse.isNotBlank())
        }
    }

    @Test fun targetsAreUniqueAndCoverEveryWritableFormat() {
        assertEquals(WriteTargets.all.size, WriteTargets.all.map { it.id }.toSet().size)
        assertEquals(WriteTargets.all.size, WriteTargets.all.map { it.label }.toSet().size)
        val reachable = WriteTargets.all.map { it.codecId ?: (CompatibilityResolver.resolve(it.printers) as CompatibilityResult.Resolved).resolution.codecId }.toSet()
        assertEquals(TagCodecRegistry.codecs.map { it.format.id }.toSet(), reachable)
        assertEquals(WriteTargets.DEFAULT_ID, WriteTargets.all.first().id)
    }

    @Test fun onlyTargetsWithReaderEvidenceDropTheUnconfirmedWarning() {
        assertEquals(setOf("canvas", "u1-paxx", "openspool"), WriteTargets.all.filter { it.unconfirmedReader == null }.map { it.id }.toSet())
        assertEquals("Snapmaker U1", WriteTargets.require(WriteTargets.DEFAULT_ID).unconfirmedReader)
    }
}
