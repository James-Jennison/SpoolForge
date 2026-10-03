package net.jamesjennison.filamajignfc.core

import java.math.BigDecimal

enum class EvidenceKind { MANUFACTURER, CATALOG, COMMUNITY, LABEL, USER, MEASURED, INFERRED }

data class SourceRef(
    val provider: String,
    val recordId: String? = null,
    val revision: String? = null,
    val retrievedAtEpochMs: Long? = null,
    val kind: EvidenceKind,
    val confidence: BigDecimal? = null,
)

data class ObservedValue<T>(val value: T, val source: SourceRef)

data class ExternalIdentifier(
    val scheme: String,
    val value: String,
    val issuer: String? = null,
    val market: String? = null,
    val source: SourceRef,
)

data class TemperatureRange(val minimumC: Int?, val maximumC: Int?) {
    init {
        require(minimumC == null || minimumC in 0..500)
        require(maximumC == null || maximumC in 0..500)
        require(minimumC == null || maximumC == null || minimumC <= maximumC)
    }
}

data class TransmissionDistanceMeasurement(
    val value: BigDecimal,
    val source: SourceRef,
    val measuredAtEpochMs: Long? = null,
    val instrument: String? = null,
) {
    init { require(value >= BigDecimal("0.1") && value <= BigDecimal("100")) }
}

/** A reusable product/material definition. It is deliberately independent of any owned spool or tag format. */
data class FilamentProfile(
    val id: String,
    val manufacturer: ObservedValue<String>? = null,
    val brand: ObservedValue<String>,
    val productLine: ObservedValue<String>? = null,
    val material: ObservedValue<String>,
    val subtype: ObservedValue<String>? = null,
    val colorName: ObservedValue<String>? = null,
    val colorHex: ObservedValue<String>? = null,
    val diameterMm: ObservedValue<BigDecimal>? = null,
    val nominalFilamentMassG: ObservedValue<Int>? = null,
    val nominalSpoolMassG: ObservedValue<Int>? = null,
    val densityGPerCm3: ObservedValue<BigDecimal>? = null,
    val filamentLengthM: ObservedValue<BigDecimal>? = null,
    val nozzleTemperature: ObservedValue<TemperatureRange>? = null,
    val bedTemperature: ObservedValue<TemperatureRange>? = null,
    val chamberRecommendationC: ObservedValue<Int>? = null,
    val maximumVolumetricFlowMm3S: ObservedValue<BigDecimal>? = null,
    val dryingTemperatureC: ObservedValue<Int>? = null,
    val dryingDurationMinutes: ObservedValue<Int>? = null,
    val identifiers: List<ExternalIdentifier> = emptyList(),
    val currentTransmissionDistance: ObservedValue<BigDecimal>? = null,
    val transmissionDistanceHistory: List<TransmissionDistanceMeasurement> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(brand.value.isNotBlank())
        require(material.value.isNotBlank())
        colorHex?.let { normalizeHex(it.value) }
        diameterMm?.let { require(it.value > BigDecimal.ZERO && it.value <= BigDecimal.TEN) }
        nominalFilamentMassG?.let { require(it.value in 1..100_000) }
        nominalSpoolMassG?.let { require(it.value in 1..100_000) }
        currentTransmissionDistance?.let { require(it.value >= BigDecimal("0.1") && it.value <= BigDecimal("100")) }
    }
}

data class TagBinding(
    val technology: String,
    val uidHex: String,
    val codecId: String,
    val payloadSha256: String? = null,
    val verifiedAtEpochMs: Long? = null,
)

/** One owned physical spool. Several instances may reference the same FilamentProfile. */
data class PhysicalSpool(
    val id: String,
    val filamentProfileId: String,
    val initialQuantityG: Int,
    val remainingQuantityG: Int? = null,
    val purchaseDateEpochDay: Long? = null,
    val purchasePriceMinorUnits: Long? = null,
    val currencyCode: String? = null,
    val vendor: String? = null,
    val storageLocation: String? = null,
    val openedAtEpochMs: Long? = null,
    val notes: String? = null,
    val customMetadata: Map<String, String> = emptyMap(),
    val tagBindings: List<TagBinding> = emptyList(),
) {
    init {
        require(id.isNotBlank() && filamentProfileId.isNotBlank())
        require(initialQuantityG > 0)
        require(remainingQuantityG == null || remainingQuantityG in 0..initialQuantityG)
        require(currencyCode == null || currencyCode.matches(Regex("[A-Z]{3}")))
    }
}
