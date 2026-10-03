package net.jamesjennison.filamajignfc.core

enum class FullSpectrumTestState { TESTED, UNTESTED }
enum class InventoryOwnership { OWNED, PROFILE_ONLY }

data class FullSpectrumSearchFilter(
    val roleKey: String? = null,
    val tdMinimumMm: String? = null,
    val tdMaximumMm: String? = null,
    val tdOrigin: String? = null,
    val suitability: SuitabilityRating? = null,
    val testState: FullSpectrumTestState? = null,
    val ownership: InventoryOwnership? = null,
) {
    val isEmpty:Boolean get() = roleKey==null && tdMinimumMm==null && tdMaximumMm==null && tdOrigin==null && suitability==null && testState==null && ownership==null
    init {
        roleKey?.let(FullSpectrumRoles::validate)
        tdOrigin?.let(TdOrigins::validate)
        val minimum = tdMinimumMm?.toBigDecimalOrNull()
        val maximum = tdMaximumMm?.toBigDecimalOrNull()
        require(tdMinimumMm == null || minimum != null)
        require(tdMaximumMm == null || maximum != null)
        require(minimum == null || minimum.signum() >= 0)
        require(maximum == null || maximum.signum() >= 0)
        require(minimum == null || maximum == null || minimum <= maximum)
    }
}

data class SetCompleteness(
    val activeRoles: Set<String>,
    val missingMixingRoles: Set<String>,
    val anchors: Set<String>,
) {
    val cmygComplete: Boolean get() = missingMixingRoles.isEmpty()
}

object SetCompletenessService {
    private val mixingRoles = linkedSetOf("C", "M", "Y", "G")
    private val anchorRoles = linkedSetOf("WHITE", "BLACK")

    fun analyze(activeRoleKeys: Iterable<String>): SetCompleteness {
        val roles = activeRoleKeys.map(FullSpectrumRoles::validate).toSet()
        return SetCompleteness(roles, mixingRoles - roles, roles intersect anchorRoles)
    }
}
