package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.AnycubicAceTagCodec
import net.jamesjennison.filamajignfc.core.CrealityCfsTagCodec
import net.jamesjennison.filamajignfc.core.OpenPrintTagWriteCodec
import net.jamesjennison.filamajignfc.core.OpenTag3dWriteCodec
import net.jamesjennison.filamajignfc.core.PrinterTarget
import net.jamesjennison.filamajignfc.core.QidiBoxTagCodec
import net.jamesjennison.filamajignfc.core.StandardOpenSpoolTagCodec
import net.jamesjennison.filamajignfc.core.TigerTagWriteCodec

/**
 * One answer to "Which printer is this tag for?". It is either a printer combination that the
 * compatibility resolver turns into a tag format, or a tag format that is written directly.
 */
internal data class WriteTarget(
    val id: String,
    val label: String,
    /** The blank tag the user needs, in the words printed on tag packaging. */
    val tagToUse: String,
    val printers: Set<PrinterTarget> = emptySet(),
    val codecId: String? = null,
    /** The reader that has not yet accepted a tag written by this app, or null when every reader for this target has. */
    val unconfirmedReader: String? = label,
    val setupNote: String? = null,
) {
    init { require(printers.isNotEmpty() != (codecId != null)) { "A write target is either a printer set or a tag format" } }
}

internal object WriteTargets {
    const val DEFAULT_ID = "canvas-u1"
    const val PHONE_ONLY_ID = "phone-only"

    val all: List<WriteTarget> = listOf(
        WriteTarget(
            DEFAULT_ID, "Elegoo CANVAS and Snapmaker U1 (PAXX)", "NTAG215",
            printers = setOf(PrinterTarget.ELEGOO_CANVAS, PrinterTarget.SNAPMAKER_U1_PAXX), unconfirmedReader = "Snapmaker U1",
            setupNote = "The Snapmaker U1 needs PAXX firmware with OpenRFID selected and the Elegoo tag reader turned on. Stock U1 firmware can't read this tag.",
        ),
        WriteTarget("canvas", "Elegoo CANVAS", "NTAG215", printers = setOf(PrinterTarget.ELEGOO_CANVAS), unconfirmedReader = null),
        WriteTarget(
            "u1-paxx", "Snapmaker U1 (PAXX firmware)", "NTAG215 or NTAG216",
            printers = setOf(PrinterTarget.SNAPMAKER_U1_PAXX), unconfirmedReader = null,
            setupNote = "The Snapmaker U1 needs PAXX extended firmware.",
        ),
        WriteTarget(
            "creality-cfs", "Creality CFS", "MIFARE Classic 1K", codecId = CrealityCfsTagCodec.format.id,
            setupNote = "Creality spools carry a tag on each side. After the first tag you can write a matching second one.",
        ),
        WriteTarget("anycubic-ace", "Anycubic ACE Pro", "NTAG213, NTAG215 or NTAG216", codecId = AnycubicAceTagCodec.format.id),
        WriteTarget(
            "qidi-box", "QIDI Box", "MIFARE Classic 1K", codecId = QidiBoxTagCodec.format.id,
            setupNote = "QIDI only accepts colors from its own list, so the color must match one of them exactly.",
        ),
        WriteTarget("tigertag", "TigerTag", "NTAG213, NTAG215 or NTAG216", codecId = TigerTagWriteCodec.format.id),
        WriteTarget(
            "openprinttag", "Prusa OpenPrintTag", "NFC-V (ISO 15693) tag", codecId = OpenPrintTagWriteCodec.format.id,
            setupNote = "The tag must already be formatted and unprotected. SpoolForge does not format or unlock tags.",
        ),
        WriteTarget("opentag3d", "OpenTag3D", "NTAG215 or NTAG216", codecId = OpenTag3dWriteCodec.format.id),
        WriteTarget(
            PHONE_ONLY_ID, "My printer doesn't read tags", "NTAG215 or NTAG216",
            codecId = StandardOpenSpoolTagCodec.format.id, unconfirmedReader = null,
            setupNote = "Your printer won't read this tag. It lets this phone recognize the spool when you tap it. You can also print a QR label from More.",
        ),
        WriteTarget(
            "openspool", "OpenSpool (standard)", "NTAG215 or NTAG216",
            codecId = StandardOpenSpoolTagCodec.format.id, unconfirmedReader = null,
        ),
    )

    fun require(id: String): WriteTarget = all.firstOrNull { it.id == id } ?: error("Unknown write target $id")
}
