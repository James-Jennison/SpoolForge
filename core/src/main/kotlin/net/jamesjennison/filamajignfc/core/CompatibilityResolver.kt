package net.jamesjennison.filamajignfc.core

enum class PrinterTarget(val id: String, val displayName: String) {
    ELEGOO_CANVAS("elegoo_canvas", "Elegoo CANVAS"),
    SNAPMAKER_U1_PAXX("snapmaker_u1_paxx", "Snapmaker U1 / PAXX"),
    SNAPMAKER_U1_STOCK("snapmaker_u1_stock", "Snapmaker U1 — stock firmware"),
}

enum class PhysicalTagFamily(val id: String, val displayName: String, val writableBytes: Int) {
    NTAG215("ntag215", "NTAG215", 504),
}

enum class CompatibilityPath { NATIVE, ALTERNATE_FIRMWARE }

data class TargetCompatibility(
    val target: PrinterTarget,
    val canRead: Boolean,
    val canWrite: Boolean,
    val path: CompatibilityPath,
    val firmwareRequirement: String? = null,
    val rfidSystemRequirement: String? = null,
    val processorRequirement: String? = null,
    val note: String,
)

data class CompatibilityResolution(
    val targets: Set<PrinterTarget>,
    val physicalTags: Set<PhysicalTagFamily>,
    val codecId: String,
    val encodingName: String,
    val targetCompatibility: List<TargetCompatibility>,
    val warnings: List<String> = emptyList(),
)

sealed interface CompatibilityResult {
    data class Resolved(val resolution: CompatibilityResolution) : CompatibilityResult
    data class Unsupported(val reason: String) : CompatibilityResult
}

object CompatibilityResolver {
    private val canvas = TargetCompatibility(
        target = PrinterTarget.ELEGOO_CANVAS,
        canRead = true,
        canWrite = false,
        path = CompatibilityPath.NATIVE,
        note = "Implementation-compatible with the native CANVAS format; physical reader acceptance is still required.",
    )
    private val paxx = TargetCompatibility(
        target = PrinterTarget.SNAPMAKER_U1_PAXX,
        canRead = true,
        canWrite = false,
        path = CompatibilityPath.ALTERNATE_FIRMWARE,
        firmwareRequirement = "PAXX extended firmware",
        rfidSystemRequirement = "OpenRFID",
        processorRequirement = "Elegoo tag processor enabled",
        note = "Implementation-compatible through OpenRFID and [elegoo_tag_processor]; physical U1 acceptance is still required and the processor is disabled by default upstream.",
    )

    fun resolve(targets: Set<PrinterTarget>): CompatibilityResult {
        if (targets.isEmpty()) return CompatibilityResult.Unsupported("Select at least one compatible printer")
        if (PrinterTarget.SNAPMAKER_U1_STOCK in targets) {
            return CompatibilityResult.Unsupported("Snapmaker U1 stock firmware does not support ELEGOO CANVAS tags; select Snapmaker U1 / PAXX instead")
        }
        return when (targets) {
            setOf(PrinterTarget.ELEGOO_CANVAS) -> canvasResolution(targets, listOf(canvas))
            setOf(PrinterTarget.ELEGOO_CANVAS, PrinterTarget.SNAPMAKER_U1_PAXX) -> canvasResolution(targets, listOf(canvas, paxx))
            setOf(PrinterTarget.SNAPMAKER_U1_PAXX) -> CompatibilityResult.Resolved(
                CompatibilityResolution(
                    targets = targets,
                    physicalTags = setOf(PhysicalTagFamily.NTAG215),
                    codecId = PaxxU1ExtendedTagCodec.format.id,
                    encodingName = "PAXX/OpenSpool",
                    targetCompatibility = listOf(paxx.copy(processorRequirement = "OpenSpool processor (enabled by default)", note = "Uses the existing PAXX/OpenSpool workflow.")),
                ),
            )
            else -> CompatibilityResult.Unsupported("That printer combination does not yet have a verified one-tag rule")
        }
    }

    private fun canvasResolution(targets: Set<PrinterTarget>, compatibility: List<TargetCompatibility>) = CompatibilityResult.Resolved(
        CompatibilityResolution(
            targets = targets,
            physicalTags = setOf(PhysicalTagFamily.NTAG215),
            codecId = ElegooCanvasTagCodec.format.id,
            encodingName = "ELEGOO CANVAS",
            targetCompatibility = compatibility,
            warnings = if (PrinterTarget.SNAPMAKER_U1_PAXX in targets) listOf("U1 support is community firmware compatibility, not stock Snapmaker support or vendor endorsement.") else emptyList(),
        ),
    )
}

enum class Ntag21xProduct(val displayName: String, val userBytes: Int) {
    NTAG213("NTAG213", 144), NTAG215("NTAG215", 504), NTAG216("NTAG216", 888),
}

data class Ntag215WriteInspection(val product: Ntag21xProduct, val writableBytes: Int)

object Ntag215WriteValidator {
    fun identify(getVersion: ByteArray): Ntag21xProduct? {
        if (getVersion.size < 8 || getVersion[1] != 0x04.toByte() || getVersion[2] != 0x04.toByte()) return null
        return when (getVersion[6].toInt() and 0xFF) {
            0x0F -> Ntag21xProduct.NTAG213
            0x11 -> Ntag21xProduct.NTAG215
            0x13 -> Ntag21xProduct.NTAG216
            else -> null
        }
    }

    fun inspect(getVersion: ByteArray, dynamicLockPage: ByteArray, config0Page: ByteArray, requiredBytes: Int): Ntag215WriteInspection {
        val product = identify(getVersion) ?: throw ValidationException("The tag did not identify as an NXP NTAG213/215/216")
        if (product != Ntag21xProduct.NTAG215) throw ValidationException("Elegoo CANVAS + Snapmaker U1/PAXX requires NTAG215; detected ${product.displayName}")
        if (requiredBytes <= 0 || requiredBytes > product.userBytes) throw ValidationException("The CANVAS payload does not fit the detected NTAG215")
        if (dynamicLockPage.size < 4 || config0Page.size < 4) throw ValidationException("NTAG215 lock/configuration pages could not be inspected")
        if (dynamicLockPage[3] != 0xBD.toByte() || dynamicLockPage[1] != 0.toByte() || (dynamicLockPage[2].toInt() and 0xF0) != 0) {
            throw ValidationException("NTAG215 dynamic-lock control bytes contain an unsupported reserved value")
        }
        // NTAG215 dynamic-lock byte 0 bit 0 locks pages 16-31. Byte 2 bit 0 is
        // the corresponding block-lock bit. Other bits govern later ranges.
        if ((dynamicLockPage[0].toInt() and 0x01) != 0 || (dynamicLockPage[2].toInt() and 0x01) != 0) {
            throw ValidationException("NTAG215 pages 16–31 are permanently locked or block-locked")
        }
        val auth0 = config0Page[3].toInt() and 0xFF
        if (auth0 <= 31) throw ValidationException("NTAG215 pages 16–31 require password authentication and cannot be safely written")
        return Ntag215WriteInspection(product, product.userBytes)
    }
}
