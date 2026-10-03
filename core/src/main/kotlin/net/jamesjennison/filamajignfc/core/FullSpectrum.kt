package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal

enum class TdOrigin {
    MANUFACTURER_PUBLISHED, INDEPENDENT_MEASURED, USER_MEASURED, EXPERIMENTALLY_INFERRED,
    ESTIMATED, CATALOG_REPORTED, COMMUNITY_REPORTED, LABEL_REPORTED,
    USER_REPORTED_UNSPECIFIED, MEASURED_UNSPECIFIED, INFERRED_UNSPECIFIED, UNKNOWN_UNSPECIFIED;

    companion object {
        fun fromLegacy(value: String?): TdOrigin = when (value?.uppercase()) {
            "MANUFACTURER" -> MANUFACTURER_PUBLISHED
            "CATALOG" -> CATALOG_REPORTED
            "COMMUNITY" -> COMMUNITY_REPORTED
            "LABEL" -> LABEL_REPORTED
            "USER" -> USER_REPORTED_UNSPECIFIED
            "MEASURED" -> MEASURED_UNSPECIFIED
            "INFERRED" -> INFERRED_UNSPECIFIED
            else -> UNKNOWN_UNSPECIFIED
        }
    }
}

object TdOrigins {
    private val extension = Regex("x-[a-z0-9][a-z0-9-]{0,31}\\.[a-z0-9][a-z0-9_.-]{0,63}")
    val core: Set<String> = TdOrigin.entries.mapTo(linkedSetOf()) { it.name }

    fun validate(value: String): String {
        val trimmed = value.trim()
        require(trimmed in core || extension.matches(trimmed)) {
            "TD origin must be a core value or x-<owner>.<value> extension"
        }
        return trimmed
    }
}

enum class FullSpectrumRoleCategory { MIXING_PRIMARY, ANCHOR, OTHER }
enum class RoleAuthority { MANUFACTURER_DECLARED, DATABASE_DECLARED, INFERRED_CANDIDATE, USER_CONFIRMED }
enum class AssertionState { ACTIVE, SUPERSEDED, RETRACTED }
enum class SuitabilityKind { RECOMMENDED, OBSERVED }
enum class SuitabilityRating { EXCELLENT, GOOD, USABLE, POOR, UNSUITABLE, UNTESTED, UNKNOWN }
enum class SuitabilityScope { PROFILE, ROLE }

object FullSpectrumRoles {
    val canonical = setOf("C", "M", "Y", "G", "WHITE", "BLACK", "NEUTRAL", "SPECIALTY")
    fun validate(key: String): String {
        val normalized = key.trim().uppercase()
        require(normalized in canonical || normalized.matches(Regex("[A-Z0-9][A-Z0-9_.:-]{1,63}"))) {
            "Role must be canonical or a namespaced extension"
        }
        return normalized
    }
    fun category(key: String) = when (validate(key)) {
        "C", "M", "Y", "G" -> FullSpectrumRoleCategory.MIXING_PRIMARY
        "WHITE", "BLACK" -> FullSpectrumRoleCategory.ANCHOR
        else -> FullSpectrumRoleCategory.OTHER
    }
}

data class OpticalCharacterization(
    val id: String,
    val filamentProfileId: String,
    val propertyKey: String = "transmission_distance",
    val value: BigDecimal? = null,
    val unit: String = "mm",
    val methodKey: String,
    val methodVersion: String? = null,
    val originKey: String = TdOrigin.UNKNOWN_UNSPECIFIED.name,
    val testGeometry: String? = null,
    val wallThicknessMm: BigDecimal? = null,
    val layerHeightMm: BigDecimal? = null,
    val evidenceUrl: String? = null,
    val confidence: BigDecimal? = null,
    val measuredAtEpochMs: Long? = null,
    val instrument: String? = null,
    val environmentalNotes: String? = null,
    val notes: String? = null,
    val createdAtEpochMs: Long,
    val supersededByCharacterizationId: String? = null,
) {
    init {
        require(id.isNotBlank() && filamentProfileId.isNotBlank() && propertyKey.isNotBlank() && methodKey.isNotBlank())
        require(propertyKey != "transmission_distance" || unit == "mm")
        require(value == null || value > BigDecimal.ZERO)
        if (propertyKey == "transmission_distance" && value != null) require(value in BigDecimal("0.1")..BigDecimal("100"))
        TdOrigins.validate(originKey)
        require(wallThicknessMm == null || wallThicknessMm > BigDecimal.ZERO)
        require(layerHeightMm == null || layerHeightMm > BigDecimal.ZERO)
        require(confidence == null || confidence in BigDecimal.ZERO..BigDecimal.ONE)
        require(evidenceUrl == null || evidenceUrl.startsWith("https://"))
    }
}

data class AppearanceAssertion(
    val id: String,
    val filamentProfileId: String,
    val appearanceKey: String,
    val assertionValue: String,
    val evidence: String? = null,
    val confidence: BigDecimal? = null,
    val assertedAtEpochMs: Long,
) {
    init {
        require(id.isNotBlank() && filamentProfileId.isNotBlank())
        require(appearanceKey.matches(Regex("[a-z0-9][a-z0-9_.:-]{0,63}")))
        require(assertionValue.isNotBlank())
        require(confidence == null || confidence in BigDecimal.ZERO..BigDecimal.ONE)
    }
}

data class FullSpectrumRoleAssertion(
    val id: String, val filamentProfileId: String, val roleKey: String,
    val category: FullSpectrumRoleCategory = FullSpectrumRoles.category(roleKey),
    val authority: RoleAuthority, val state: AssertionState = AssertionState.ACTIVE,
    val supersededByAssertionId: String? = null, val evidence: String? = null,
    val confidence: BigDecimal? = null, val notes: String? = null, val assertedAtEpochMs: Long,
) {
    init {
        require(id.isNotBlank() && filamentProfileId.isNotBlank())
        require(category == FullSpectrumRoles.category(roleKey))
        require((state == AssertionState.SUPERSEDED) == (supersededByAssertionId != null))
        require(confidence == null || confidence in BigDecimal.ZERO..BigDecimal.ONE)
    }
}

data class FullSpectrumSuitabilityAssessment(
    val id: String, val filamentProfileId: String, val kind: SuitabilityKind,
    val rating: SuitabilityRating, val scope: SuitabilityScope,
    val roleAssertionId: String? = null, val policyId: String? = null,
    val policyVersion: String? = null, val factorReferences: List<String> = emptyList(),
    val rationale: String, val evidence: String? = null, val confidence: BigDecimal? = null,
    val assessor: String, val assessedAtEpochMs: Long, val supersededByAssessmentId: String? = null,
) {
    init {
        require(id.isNotBlank() && filamentProfileId.isNotBlank() && rationale.isNotBlank() && assessor.isNotBlank())
        require((scope == SuitabilityScope.ROLE) == (roleAssertionId != null))
        require((policyId == null) == (policyVersion == null))
        require(confidence == null || confidence in BigDecimal.ZERO..BigDecimal.ONE)
    }
}
