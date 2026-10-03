package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogProviderTest {
    @Test fun exactIdentifiersRankFirstAndAmbiguousRecordsRemainSeparate() {
        val exactA = candidate("a", "PLA", "1.75", 1000, CatalogMatchTier.EXACT_IDENTIFIER)
        val text = candidate("text", "PLA", "1.75", 1000, CatalogMatchTier.TEXT, identifier = "other")
        val exactB = candidate("b", "PETG", "2.85", 2500, CatalogMatchTier.EXACT_IDENTIFIER)

        val ranked = CatalogCandidateRanking.rank(listOf(text, exactB, exactA))

        assertEquals(listOf("b", "a", "text"), ranked.map(CatalogCandidate::providerRecordId))
        assertEquals(3, ranked.size)
        assertEquals(setOf("material", "diameterMm", "nominalMassG"), ranked[0].conflictingFields)
        assertEquals(ranked[0].conflictingFields, ranked[1].conflictingFields)
    }

    @Test fun rankingIsDeterministicAcrossInputOrder() {
        val candidates = listOf(
            candidate("z", "PLA", "1.75", 1000, CatalogMatchTier.TEXT, identifier = "z"),
            candidate("a", "PLA", "1.75", 1000, CatalogMatchTier.EXACT_STRUCTURED, identifier = "a"),
        )
        assertEquals(CatalogCandidateRanking.rank(candidates), CatalogCandidateRanking.rank(candidates.reversed()))
        assertTrue(CatalogCandidateRanking.rank(candidates, 1).single().providerRecordId == "a")
    }

    private fun candidate(id: String, material: String, diameter: String, mass: Int, tier: CatalogMatchTier, identifier: String = "shared"): CatalogCandidate {
        val source = SourceRef("fixture", id, "1", kind = EvidenceKind.CATALOG)
        val profile = FilamentProfile("fixture:$id", brand = ObservedValue("Brand", source), productLine = ObservedValue("Product", source),
            material = ObservedValue(material, source), diameterMm = ObservedValue(BigDecimal(diameter), source), nominalFilamentMassG = ObservedValue(mass, source),
            identifiers = listOf(ExternalIdentifier("GTIN", identifier, source = source)))
        return CatalogCandidate(profile, "fixture", id, listOf(tier.name), tier)
    }
}
