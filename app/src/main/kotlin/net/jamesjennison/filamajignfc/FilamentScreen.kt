@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.jamesjennison.filamajignfc

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import net.jamesjennison.filamajignfc.core.*

/**
 * One filament: what it is, then the one thing most people came for — writing a tag for their printer.
 * Everything else (editing, exports, where the data came from, format internals) sits below or behind an expander.
 */
@Composable internal fun FilamentScreen(model: MainViewModel, item: FilamentItem, edit: () -> Unit, back: () -> Unit) {
    val e = item.entry
    val saved = item.provenance != Provenance.CATALOG
    var confirmDelete by remember(e.packageId) { mutableStateOf(false) }
    var showPortable by remember(e.packageId) { mutableStateOf(false) }
    var showSpoolmanSync by remember(e.packageId) { mutableStateOf(false) }
    var portableIdentity by remember(e.packageId) { mutableStateOf<PortableSpoolIdentity?>(null) }

    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = back) { Text("‹ Back") }
        FilamentHeader(item)
        FilamentFacts(item)
        WriteTagCard(model, item)
        if (saved) Text(savedTagsSummary(model.savedTagBindings), style = MaterialTheme.typography.bodyMedium)
        else Text("Save this filament to keep track of the tags you write for it.", style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Edit" else "Edit and save") }
        if (saved) MoreActions(
            model, item,
            showPortable = { model.loadPortableIdentity(item) { portableIdentity = it; showPortable = true } },
            showSpoolmanSync = { showSpoolmanSync = true },
            delete = { confirmDelete = true },
        ) else SearchElsewhereButton(item)
        model.spoolmanSync.message?.let { Notice(it, model.spoolmanSync.outcomeUnknown || it.contains("failed", ignoreCase = true) || it.contains("rejected", ignoreCase = true)) }
        AboutThisData(item)
        Spacer(Modifier.height(60.dp))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this filament?") },
        text = { Text("${displayName(item)} will be removed from this device, along with its entry under Recent.") },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; model.deleteLocal(item) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
    )
    if (showPortable && portableIdentity != null) PortableIdentityDialog(portableIdentity!!) { showPortable = false }
    if (showSpoolmanSync) SpoolmanSyncDialog(model.spoolmanServer, model.spoolmanSync.busy, dismiss = { showSpoolmanSync = false }, sync = { server, density -> model.syncSpoolmanProfile(item, server, density); showSpoolmanSync = false })
}

@Composable private fun FilamentHeader(item: FilamentItem) {
    val e = item.entry
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val color = runCatching { Color(("#" + e.colorHex.removePrefix("#")).toColorInt()) }.getOrNull()
        Box(
            Modifier.size(64.dp)
                .background(color ?: MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .semantics { contentDescription = "${displayColor(item)} color sample" },
        )
        Column(Modifier.weight(1f)) {
            Text(displayName(item), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${displayColor(item)} · ${e.material}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun FilamentFacts(item: FilamentItem) {
    val e = item.entry
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Info("Nozzle", temperature(e.nozzleMinC, e.nozzleMaxC))
        Info("Bed", temperature(e.bedMinC, e.bedMaxC))
        Info("Spool", packageSummary(e))
        item.transmissionDistance?.let { Info("TD", "$it mm (HueForge)") }
    }
}

@Composable private fun WriteTagCard(model: MainViewModel, item: FilamentItem) {
    val target = model.writeTarget
    val encodedResult = remember(item, model.codecId) { runCatching { model.encodedTag(item) } }
    val encoded = encodedResult.getOrNull()
    val unsupported = (model.compatibilityResult as? CompatibilityResult.Unsupported)?.takeIf { model.chooseByPrinters }
    var menu by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val writing = model.nfc.state.phase !in setOf(WritePhase.DRAFT, WritePhase.COMPLETED, WritePhase.CANCELLED, WritePhase.REJECTED, WritePhase.FAILED_BEFORE_WRITE, WritePhase.UNRESOLVED_ARCHIVED)

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Write a tag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { if (!writing) menu = it }, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    target.label, {}, readOnly = true, enabled = !writing, label = { Text("Which printer is it for?") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    WriteTargets.all.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { model.selectWriteTarget(option.id); menu = false })
                    }
                }
            }
            Text("Use a blank ${target.tagToUse} tag.")
            unsupported?.let { Notice(it.reason, true) }
            encodedResult.exceptionOrNull()?.message?.let { Notice("This filament can't be written to that tag yet: $it", true) }

            NfcPanel(model.nfc)
            if (!writing) Button(
                onClick = { model.arm(item) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = encoded != null && model.compatibilityReady(),
            ) { Text("Write tag") }

            encoded?.let { tag ->
                val lost = valuesLeftOffTag(item, tag.omittedFields)
                if (lost.isNotEmpty()) Text("This kind of tag can't hold this filament's ${lost.joinToString()}.", style = MaterialTheme.typography.bodySmall)
            }
            target.setupNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            target.unconfirmedReader?.let { reader -> Text("Not yet confirmed on a real $reader reader. SpoolForge checks the tag after writing, but try it in your printer before relying on it.", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide technical details" else "Technical details") }
            if (details) WriteTechnicalDetails(model, item, encoded)
        }
    }
}

/** The format internals that used to fill the screen. Kept word for word where they describe a safety behavior. */
@Composable private fun WriteTechnicalDetails(model: MainViewModel, item: FilamentItem, encoded: EncodedTag?) {
    val codec = model.selectedTagCodec()
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        encoded?.let {
            val storage = when (it.format.transport) {
                TagTransport.NDEF -> "NDEF message, ${it.ndefMessageSizeBytes} bytes"
                TagTransport.NTAG_RAW -> "Raw NTAG user area, ${it.sizeBytes} bytes"
                TagTransport.MIFARE_CLASSIC_QIDI -> "MIFARE Classic data block, ${it.sizeBytes} bytes"
                TagTransport.MIFARE_CLASSIC_CFS -> "Encrypted MIFARE Classic data blocks, ${it.sizeBytes} bytes"
            }
            Text("Format: ${it.format.displayName} (${it.format.compatibilityTarget})", style = small)
            Text("Storage: $storage", style = small)
            Text("Written fields: ${it.includedWireFields.sorted().joinToString()}", style = small)
        }
        (model.compatibilityResult as? CompatibilityResult.Resolved)?.takeIf { model.chooseByPrinters }?.resolution?.let { resolution ->
            resolution.targetCompatibility.forEach { Text("${it.target.displayName}: ${it.note}", style = small) }
            resolution.warnings.forEach { Text(it, style = small) }
            if (PrinterTarget.SNAPMAKER_U1_PAXX in resolution.targets && resolution.codecId == ElegooCanvasTagCodec.format.id) Text("In PAXX firmware-config, select OpenRFID, then enable [elegoo_tag_processor] in /oem/printer_data/config/extended/openrfid_user.cfg. Upstream disables it by default because factory Elegoo tag placement can read unreliably. PAXX/OpenRFID is community firmware; this mode does not imply Snapmaker or ELEGOO endorsement.", style = small)
        }
        if (codec === ElegooCanvasTagCodec) Text("Writes only the ELEGOO CANVAS raw filament block to pages 16–31 of a verified, unlocked NTAG215. All 504 user bytes are inspected first; outside the CANVAS block only blank bytes or the factory empty-NDEF marker are allowed, and existing data is rejected rather than erased. The brand is not written: the CANVAS manufacturer word is a fixed format marker, so readers show the spool as ELEGOO-format filament.", style = small)
        if (codec === PaxxU1ExtendedTagCodec && item.transmissionDistance != null) Text("transmission_distance is a numeric SpoolForge extension. PAXX ${PaxxU1ExtendedTagCodec.TARGET_FIRMWARE} ignores it; the other U1 fields remain compatible.", style = small)
        if (codec === CrealityCfsTagCodec) Text("Creality CFS uses three encrypted blocks on a phone-supported MIFARE Classic 1K tag.", style = small)
        if (codec === QidiBoxTagCodec) Text("QIDI Box writes only documented material, palette color, and manufacturer codes.", style = small)
    }
}

@Composable private fun MoreActions(model: MainViewModel, item: FilamentItem, showPortable: () -> Unit, showSpoolmanSync: () -> Unit, delete: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Print a QR label or export") }, onClick = { open = false; showPortable() })
            DropdownMenuItem(text = { Text("Share as Spoolman file") }, onClick = {
                open = false
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, model.spoolmanExport(item))
                }, "Export for Spoolman"))
            })
            DropdownMenuItem(text = { Text("Send to my Spoolman server") }, enabled = !model.spoolmanSync.busy, onClick = { open = false; showSpoolmanSync() })
            DropdownMenuItem(text = { Text("Look up on 3D Filament Profiles") }, onClick = { open = false; uriHandler.openUri(filamentProfilesSearchUrl(item.entry.brand, item.entry.material, item.entry.colorName)) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; delete() })
        }
    }
}

@Composable private fun SearchElsewhereButton(item: FilamentItem) {
    val uriHandler = LocalUriHandler.current
    OutlinedButton(onClick = { uriHandler.openUri(filamentProfilesSearchUrl(item.entry.brand, item.entry.material, item.entry.colorName)) }, modifier = Modifier.fillMaxWidth()) { Text("Look up on 3D Filament Profiles") }
}

/** Where each value came from, plus any barcode or QR evidence. Collapsed, because most people never need it. */
@Composable private fun AboutThisData(item: FilamentItem) {
    val e = item.entry
    var open by rememberSaveable(e.packageId) { mutableStateOf(false) }
    val catalogBarcode = isCatalogBarcodeEvidence(item.barcodeEvidence)
    val providerCandidate = isProviderCandidateEvidence(item.barcodeEvidence)
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide where this data came from" else "Where this data came from") }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Info("Source", when {
            catalogBarcode -> barcodeSummary(item.barcodeEvidence)
            providerCandidate -> providerCandidateSummary(item.barcodeEvidence)
            else -> when (item.provenance) {
                Provenance.CATALOG -> "Open Filament Database"
                Provenance.CATALOG_EDITED -> "Open Filament Database, edited by you"
                Provenance.CUSTOM -> "Added on this device"
            }
        })
        listOf("brand" to "Brand", "material" to "Material", "colorHex" to "Color").forEach { (field, label) ->
            item.source(field).substringBefore(" · ").takeIf(String::isNotBlank)?.let { Info(label, it.take(40)) }
        }
        Info("Barcode", retailGtinDisplay(e.gtin, item.barcodeEvidence))
        if (item.barcodeEvidence.isNotBlank() && !catalogBarcode && !providerCandidate) DetectedCodeEvidence(item.barcodeEvidence)
        if (catalogBarcode) BarcodeEvidence(item)
        if (providerCandidate) ProviderCandidateEvidence(item.barcodeEvidence)
        if (item.provenance != Provenance.CUSTOM) Text("Catalog revision ${e.sourceRevision}", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun displayName(item: FilamentItem): String {
    val e = item.entry
    val brand = displayBrand(e.brand)
    val product = e.product.ifBlank { e.material }
    return if (product.startsWith(brand, ignoreCase = true)) product else "$brand $product"
}

/** Brands typed or scanned in lower case read as mistakes; names with their own capitals (eSUN, ELEGOO) are left alone. */
internal fun displayBrand(brand: String): String {
    val trimmed = brand.trim()
    return if (trimmed.isNotEmpty() && trimmed == trimmed.lowercase()) trimmed.replaceFirstChar(Char::uppercase) else trimmed
}

/** A color the user can read: the name when there is a real one, otherwise the hex code. */
internal fun displayColor(item: FilamentItem): String {
    val e = item.entry
    val name = e.colorName.trim()
    val nameIsHex = name.removePrefix("#").let { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
    return when {
        name.isNotEmpty() && !nameIsHex -> name.replaceFirstChar(Char::uppercase)
        e.colorHex.isNotBlank() -> "#" + e.colorHex.removePrefix("#").uppercase()
        else -> "Unknown color"
    }
}

/**
 * The omitted fields that matter for this filament: ones it actually has a value for.
 * A field the codec reports under a name this function does not know is listed, so nothing is hidden by accident.
 */
internal fun valuesLeftOffTag(item: FilamentItem, omitted: Set<String>): List<String> {
    val e = item.entry
    val present: Map<String, Boolean> = mapOf(
        "brand" to e.brand.isNotBlank(), "colorName" to e.colorName.isNotBlank(), "product" to e.product.isNotBlank(),
        "additionalColors" to e.additionalColorHexes.isNotBlank(), "transmissionDistance" to (item.transmissionDistance != null),
        "sku" to (e.sku != null), "gtin" to (e.gtin != null),
        "bedTemperatureRange" to (e.bedMinC != null || e.bedMaxC != null), "bedTemperatureTargets" to (e.bedMinC != null || e.bedMaxC != null),
        "bedMin" to (e.bedMinC != null), "bedMax" to (e.bedMaxC != null), "nozzleMin" to (e.nozzleMinC != null), "nozzleMax" to (e.nozzleMaxC != null),
        "temperatures" to (e.nozzleMinC != null || e.nozzleMaxC != null || e.bedMinC != null || e.bedMaxC != null),
    )
    return omitted.filter { present[it] ?: true }.map(::fieldLabel).sorted()
}

internal fun savedTagsSummary(bindings: List<net.jamesjennison.filamajignfc.data.TagBindingEntity>): String = when (bindings.size) {
    0 -> "No tags written for this spool yet."
    else -> "Tags written for this spool: " + bindings.joinToString { binding ->
        runCatching { TagCodecRegistry.require(binding.codecId).format.displayName }.getOrDefault(binding.codecId)
    } + "."
}

private fun temperature(min: Int?, max: Int?) = when {
    min == null && max == null -> "Not specified"
    min == max -> "$min °C"
    else -> "${min ?: "?"}–${max ?: "?"} °C"
}
