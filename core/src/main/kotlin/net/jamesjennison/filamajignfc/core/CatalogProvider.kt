package net.jamesjennison.filamajignfc.core

data class CatalogProviderDescriptor(
    val id: String,
    val displayName: String,
    val license: String,
    val attribution: String? = null,
)

data class CatalogQuery(
    val text: String? = null,
    val identifiers: List<Pair<String, String>> = emptyList(),
    val fields: CatalogStructuredFields = CatalogStructuredFields(),
    val limit: Int = 100,
) {
    init { require(limit in 1..500) }
}

data class CatalogStructuredFields(
    val brand: String? = null,
    val product: String? = null,
    val material: String? = null,
    val colorName: String? = null,
    val diameterMm: String? = null,
    val nominalMassG: Int? = null,
)

enum class CatalogMatchTier { EXACT_IDENTIFIER, EXACT_STRUCTURED, PARTIAL_STRUCTURED, TEXT }

data class CatalogCandidate(
    val profile: FilamentProfile,
    val providerId: String,
    val providerRecordId: String,
    val matchReasons: List<String>,
    val matchTier: CatalogMatchTier,
    val conflictingFields: Set<String> = emptySet(),
    val evidence: String? = null,
)

/** Maps a source's schema into canonical profiles without letting it own application persistence. */
interface CatalogProvider {
    val descriptor: CatalogProviderDescriptor
    suspend fun search(query: CatalogQuery): List<CatalogCandidate>
    suspend fun profile(providerRecordId: String): FilamentProfile?
}

/** Stable ranking only. Candidate records remain separate even when identifiers overlap. */
object CatalogCandidateRanking {
    fun rank(candidates: List<CatalogCandidate>, limit: Int = 100): List<CatalogCandidate> {
        require(limit in 1..500)
        val withConflicts = annotateIdentifierConflicts(candidates)
        return withConflicts.sortedWith(
            compareBy<CatalogCandidate>({ it.matchTier.ordinal }, { normalized(it.profile.brand.value) },
                { normalized(it.profile.productLine?.value.orEmpty()) }, { normalized(it.profile.material.value) },
                { normalized(it.profile.colorName?.value.orEmpty()) }, { it.providerId }, { it.providerRecordId })
        ).take(limit)
    }

    private fun annotateIdentifierConflicts(candidates: List<CatalogCandidate>): List<CatalogCandidate> {
        val groups = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        candidates.forEachIndexed { index, candidate -> candidate.profile.identifiers.forEach { identifier ->
            groups.getOrPut(normalized(identifier.scheme) to normalized(identifier.value)) { mutableListOf() }.add(index)
        } }
        val conflicts = Array(candidates.size) { mutableSetOf<String>() }
        groups.values.filter { it.distinct().size > 1 }.forEach { indexes ->
            fun values(block: (FilamentProfile) -> String?) = indexes.mapNotNull { block(candidates[it].profile)?.trim()?.takeIf(String::isNotEmpty) }.map(::normalized).toSet()
            val fields = listOf(
                "brand" to { p: FilamentProfile -> p.brand.value },
                "product" to { p: FilamentProfile -> p.productLine?.value },
                "material" to { p: FilamentProfile -> p.material.value },
                "colorName" to { p: FilamentProfile -> p.colorName?.value },
                "colorHex" to { p: FilamentProfile -> p.colorHex?.value },
                "diameterMm" to { p: FilamentProfile -> p.diameterMm?.value?.stripTrailingZeros()?.toPlainString() },
                "nominalMassG" to { p: FilamentProfile -> p.nominalFilamentMassG?.value?.toString() },
            ).filter { (_, read) -> values(read).size > 1 }.map { it.first }
            indexes.forEach { conflicts[it].addAll(fields) }
        }
        return candidates.mapIndexed { index, candidate -> candidate.copy(conflictingFields = candidate.conflictingFields + conflicts[index]) }
    }

    private fun normalized(value: String) = value.trim().lowercase()
}
