@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package net.jamesjennison.spoolio

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import net.jamesjennison.spoolio.core.*

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val model: MainViewModel by viewModels()
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent { SpoolioTheme { SpoolioApp(model, adapter != null) } }
    }
    override fun onResume() { super.onResume(); adapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, null) }
    override fun onPause() { adapter?.disableReaderMode(this); super.onPause() }
    override fun onTagDiscovered(tag: Tag) = model.nfc.onTag(tag)
}

@Composable private fun SpoolioTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(primary = Color(0xFF286354), secondary = Color(0xFF7A5731), background = Color(0xFFF8F6F0), surface = Color(0xFFFFFCF5), error = Color(0xFF9B2C2C))
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable fun SpoolioApp(model: MainViewModel, nfcAvailable: Boolean) {
    var custom by remember { mutableStateOf<CustomSeed?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("Spoolio", fontWeight = FontWeight.Bold); Text("Offline filament tag manager", style = MaterialTheme.typography.labelMedium) } }) },
        floatingActionButton = { if (model.selected == null) ExtendedFloatingActionButton(onClick = { custom = CustomSeed(); showCustom = true }) { Text("Custom filament") } },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {
            if (!nfcAvailable) Notice("NFC is unavailable on this device. Offline catalog remains usable.", true)
            model.error?.let { Notice(it, true) }
            OutlinedTextField(model.query, { model.query = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Search brand, material, product, color, or package ID") }, singleLine = true, trailingIcon = { TextButton(onClick = model::search) { Text("Search") } })
            NfcPanel(model.nfc)
            when {
                model.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                model.selected != null -> Detail(model, model.selected!!, { custom = model.seed(model.selected!!); showCustom = true }) { model.selected = null }
                else -> {
                    ReadResult(model.nfc.lastRead) { model.decodedSeed()?.let { custom = it; showCustom = true } }
                    CatalogList(model.recents, model.results, model::select)
                }
            }
        }
    }
    if (showCustom) CustomDialog(custom ?: CustomSeed(), onDismiss = { showCustom = false }, onSave = { values -> model.saveCustom(custom ?: CustomSeed(), values); showCustom = false })
}

@Composable private fun ReadResult(result: DecodeResult?, edit: () -> Unit) {
    when (result) {
        is DecodeResult.Supported -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(Modifier.padding(12.dp)) { Text("Supported OpenSpool tag read", fontWeight = FontWeight.Bold); Text("Fields were decoded without rewriting the tag."); TextButton(onClick = edit) { Text("Edit as a local record") } } }
        is DecodeResult.ReadOnly -> Notice("Read-only tag: ${result.reason}", false)
        is DecodeResult.Rejected -> Notice("Tag read: ${result.reason}", false)
        null -> Unit
    }
}

@Composable private fun CatalogList(recents: List<FilamentItem>, entries: List<FilamentItem>, select: (FilamentItem) -> Unit) {
    if (recents.isNotEmpty()) {
        Text("Recent", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
        recents.take(5).forEach { item ->
            val e = item.entry
            TextButton(onClick = { select(item) }, modifier = Modifier.fillMaxWidth()) { Text((e.brand + " · " + e.product.ifBlank { e.material }) + " — " + e.colorName.ifBlank { "#" + e.colorHex }, modifier = Modifier.fillMaxWidth()) }
        }
    }
    Text("${entries.size} matches shown", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(entries, key = { it.entry.packageId }) { item ->
            val e = item.entry
            Card(Modifier.fillMaxWidth().clickable { select(item) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val color = runCatching { Color(android.graphics.Color.parseColor(e.colorHex)) }.getOrElse { Color.Gray }
                    Box(Modifier.size(42.dp).background(color, RoundedCornerShape(12.dp)).semantics { contentDescription = "${e.colorName.ifBlank { "Filament" }} color sample" })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.brand} · ${e.product.ifBlank { e.material }}", fontWeight = FontWeight.SemiBold)
                        Text("${e.material} — ${e.colorName.ifBlank { "#" + e.colorHex }}")
                        Text(packageSummary(e), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun Detail(model: MainViewModel, item: FilamentItem, edit: () -> Unit, back: () -> Unit) {
    val e = item.entry
    val intentResult = remember(item, model.profile) { runCatching { model.intent(item) } }
    val intent = intentResult.getOrNull()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = back) { Text("‹ Back to catalog") }
        Text("${e.brand} ${e.product.ifBlank { e.material }}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${e.colorName.ifBlank { "#" + e.colorHex }} · ${e.material}")
        Info("Exact package", packageSummary(e)); Info("Nozzle", range(e.nozzleMinC, e.nozzleMaxC)); Info("Bed", range(e.bedMinC, e.bedMaxC)); Info("Barcode", e.gtin ?: "Missing in source")
        Info("Provenance", when (item.provenance) { Provenance.CATALOG -> "OFD ${e.sourceRevision} · catalog"; Provenance.CATALOG_EDITED -> "Locally edited from OFD ${e.sourceRevision}"; Provenance.CUSTOM -> "Local custom" })
        Text("Field sources: brand ${item.source("brand")}; material ${item.source("material")}; color ${item.source("colorHex")}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text(if (item.provenance == Provenance.CATALOG) "Edit locally" else "Edit saved record") }
        Text("Tag compatibility", fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OpenSpoolProfile.entries.forEachIndexed { i, p -> SegmentedButton(selected = model.profile == p, onClick = { model.profile = p }, shape = SegmentedButtonDefaults.itemShape(i, 2), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (p == OpenSpoolProfile.CANONICAL) "Canonical" else "PAXX") } }
        }
        intent?.let { Notice("NDEF payload ${it.payload.size} bytes. Not written in this profile: ${it.omittedFields.sorted().joinToString { fieldLabel(it) }}.", false) }
        intentResult.exceptionOrNull()?.message?.let { Notice("Complete missing or invalid fields before writing: $it", true) }
        Button(onClick = { model.arm(item) }, modifier = Modifier.fillMaxWidth(), enabled = intent != null) { Text("Prepare verified NFC write") }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable private fun NfcPanel(nfc: NfcCoordinator) {
    nfc.readDiagnostic?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
    val s = nfc.state
    if (s.phase != WritePhase.DRAFT || nfc.unresolvedArchiveCount > 0) Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = if (s.phase == WritePhase.VERIFIED) Color(0xFFDDEFE6) else Color(0xFFFFF1D8))) {
        Column(Modifier.padding(14.dp)) {
            Text(phaseLabel(s.phase), fontWeight = FontWeight.Bold); Text(s.detail)
            if (s.phase == WritePhase.NEEDS_OVERWRITE_CONSENT) Button(onClick = nfc::approveOverwrite) { Text("Approve overwrite for this tag") }
            if (s.phase !in setOf(WritePhase.VERIFIED, WritePhase.WRITE_OUTCOME_UNKNOWN, WritePhase.UNRESOLVED_ARCHIVED)) TextButton(onClick = nfc::cancel) { Text("Cancel before writing") }
            if (s.phase == WritePhase.WRITE_OUTCOME_UNKNOWN) Text("This unresolved operation remains recorded. Re-tap the same tag for read-only reconciliation.", style = MaterialTheme.typography.bodySmall)
            if (s.phase == WritePhase.WRITE_OUTCOME_UNKNOWN) OutlinedButton(onClick = nfc::archiveUnknown) { Text("Archive unresolved result; start another") }
            if (nfc.unresolvedArchiveCount > 0) {
                var showHistory by remember { mutableStateOf(false) }
                TextButton(onClick = { showHistory = !showHistory }) { Text("View ${nfc.unresolvedArchiveCount} unresolved write result(s)") }
                if (showHistory) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    nfc.archivedWrites().forEach { entry ->
                        Text("Unresolved · ${java.util.Date(entry.timestamp)} · UID ${entry.uid.joinToString("") { "%02X".format(it) }}", fontWeight = FontWeight.Bold)
                        Text("Intended data: ${entry.payload.decodeToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Archived without confirming the tag's contents.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun Info(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.width(105.dp), fontWeight = FontWeight.SemiBold); Text(value, Modifier.weight(1f)) } }
@Composable private fun Notice(text: String, error: Boolean) { Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) { Text(text, Modifier.padding(12.dp)) } }
private fun range(a: Int?, b: Int?) = when { a == null && b == null -> "Missing in source"; a == b -> "$a °C"; else -> "${a ?: "?"}–${b ?: "?"} °C" }
private fun packageSummary(e: net.jamesjennison.spoolio.data.CatalogEntry) = e.diameterMm.ifBlank { "Diameter missing" } + (if (e.diameterMm.isNotBlank()) " mm" else "") + " · " + if (e.massG > 0) "${e.massG} g" else "Mass missing"

@Composable private fun CustomDialog(seed: CustomSeed, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val values = remember(seed) { mutableStateMapOf<String, String>().apply { putAll(seed.values()) } }
    val fields = listOf("brand" to "Brand", "material" to "Material", "product" to "Product (optional label)", "colorName" to "Color name (optional label)", "colorHex" to "Color hex", "diameter" to "Diameter mm", "mass" to "Mass g", "nozzleMin" to "Nozzle minimum °C", "nozzleMax" to "Nozzle maximum °C", "bedMin" to "Bed minimum °C", "bedMax" to "Bed maximum °C")
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (seed.id == null) "Custom filament" else "Edit filament locally") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Blank optional or physical fields remain missing. Each changed field is marked Edited locally.", style = MaterialTheme.typography.bodySmall); fields.forEach { (key, label) -> OutlinedTextField(values[key].orEmpty(), { values[key] = it }, label = { Text(label) }, supportingText = seed.sources[key]?.takeIf(String::isNotBlank)?.let { { Text("Source: $it") } }, singleLine = true) } } },
        confirmButton = { Button(onClick = { onSave(values.toMap()) }) { Text("Save locally") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fieldLabel(value: String) = mapOf("product" to "product label", "diameter" to "diameter", "mass" to "mass", "nozzleMin" to "nozzle minimum", "nozzleMax" to "nozzle maximum", "bedMin" to "bed minimum", "bedMax" to "bed maximum", "additionalColors" to "additional colors", "packageId" to "package identifier", "gtin" to "barcode", "sku" to "article number", "provenance" to "field provenance")[value] ?: value
private fun phaseLabel(phase: WritePhase) = when (phase) { WritePhase.AWAITING_TAG -> "Ready for tag"; WritePhase.INSPECTING -> "Inspecting tag"; WritePhase.NEEDS_OVERWRITE_CONSENT -> "Overwrite approval needed"; WritePhase.WRITING -> "Writing — keep tag in place"; WritePhase.VERIFYING -> "Verifying fresh read"; WritePhase.VERIFIED -> "Write verified"; WritePhase.CANCELLED -> "Cancelled before writing"; WritePhase.REJECTED -> "Tag rejected"; WritePhase.FAILED_BEFORE_WRITE -> "Write not attempted"; WritePhase.WRITE_OUTCOME_UNKNOWN -> "Write outcome unknown"; WritePhase.UNRESOLVED_ARCHIVED -> "Unresolved result archived"; WritePhase.DRAFT -> "Draft" }
