package net.jamesjennison.filamajignfc.core

/** Where a transmission distance value came from. Stored as text in the user database. */
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
