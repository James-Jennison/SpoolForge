@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package net.jamesjennison.filamajignfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.jamesjennison.filamajignfc.core.*

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val model: MainViewModel by viewModels()
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FilamajigTheme { FilamajigApp(model, adapter != null) } }
    }
    override fun onResume() { super.onResume(); adapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_V, null) }
    override fun onPause() { adapter?.disableReaderMode(this); super.onPause() }
    override fun onTagDiscovered(tag: Tag) = model.nfc.onTag(tag)
}

@Composable fun FilamajigApp(model: MainViewModel, nfcAvailable: Boolean) {
    val focusManager=LocalFocusManager.current
    val keyboardController=LocalSoftwareKeyboardController.current
    var custom by remember { mutableStateOf<CustomSeed?>(null) }
    var showLabelScanner by rememberSaveable { mutableStateOf(false) }
    var showPortableImport by rememberSaveable { mutableStateOf(false) }
    var showSpoolmanImport by rememberSaveable { mutableStateOf(false) }
    var showBulkImport by rememberSaveable { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    var labelReview by remember { mutableStateOf<List<String>>(emptyList()) }
    var labelCodes by remember { mutableStateOf<List<LabelCode>>(emptyList()) }
    val labelAnalysis = model.labelAnalysis
    LaunchedEffect(labelAnalysis.result) {
        labelAnalysis.result?.let { result ->
            custom = result.seed
            labelReview = result.review + "Provider: ${result.provider} ${result.model}"
            labelCodes = result.codes
            showCustom = true
        }
    }
    LaunchedEffect(model.portableImport) {
        model.portableImport?.let { imported ->
            custom = imported
            labelReview = listOf("Imported from a self-contained SpoolForge portable spool QR. Profile and physical-spool identities were decoded locally.")
            labelCodes = emptyList()
            showCustom = true
            model.consumePortableImport()
        }
    }
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold); Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium) } }) },
        floatingActionButton = { if (model.selected == null) ExtendedFloatingActionButton(onClick = { custom = CustomSeed(); showCustom = true }) { Text("Custom filament") } },
    ) { padding ->
        // One scrolling surface: status/history can never pin the detail form underneath them.
        key(model.selected?.entry?.packageId) {
            LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    if (!nfcAvailable) Notice("NFC is unavailable on this device. Offline catalog remains usable.", true)
                    model.error?.let { Notice(it, true) }
                    if (model.selected == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = model.barcodeMode, onCheckedChange = model::useBarcodeLookup, modifier = Modifier.semantics { contentDescription = "Exact barcode lookup" }); Text("Exact barcode lookup") }
                        OutlinedTextField(model.query, { model.query = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text(if(model.barcodeMode) "GTIN / EAN / UPC-A" else "Brand, product, color, package ID, or SKU") }, singleLine = true, trailingIcon = { TextButton(onClick = model::search) { Text("Search") } })
                        Button(
                            onClick = { model.clearLabelPhotos(); showLabelScanner = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Scan label or QR with AI") }
                        ChatGptPlanCard(model)
                        if (model.query.isNotBlank()) OutlinedButton(onClick = { uriHandler.openUri(filamentProfilesSearchUrl(model.query)) }, modifier = Modifier.fillMaxWidth()) { Text("Search 3D Filament Profiles for this") }
                        OutlinedButton(onClick = { showPortableImport = true }, modifier = Modifier.fillMaxWidth()) { Text("Import portable spool bundle") }
                        OutlinedButton(onClick = { showSpoolmanImport = true }, modifier = Modifier.fillMaxWidth()) { Text("Import Spoolman JSON") }
                        OutlinedButton(onClick = { showBulkImport = true }, modifier = Modifier.fillMaxWidth()) { Text("Bulk add CSV") }
                        labelAnalysis.message?.let { Notice(it, it.startsWith("Label scan failed")) }
                        if (!labelAnalysis.busy && labelAnalysis.result == null && model.labelPhotos.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = model::analyzeLabelPhotos, modifier = Modifier.weight(1f)) { Text("Retry analysis") }
                                OutlinedButton(onClick = model::clearLabelAnalysis, modifier = Modifier.weight(1f)) { Text("Discard photo") }
                            }
                        }
                        model.searchNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (model.selected == null) NfcPanel(model.nfc)
                }
                when {
                    model.loading -> item { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    model.selected != null -> item { FilamentScreen(model, model.selected!!, { custom = model.seed(model.selected!!); showCustom = true }) { model.selected = null } }
                    else -> {
                        item { ReadResult(model.nfc.lastRead) { model.decodedSeed()?.let { custom = it; showCustom = true } } }
                        catalogItems(if(model.barcodeMode) emptyList() else model.recents, model.results, model::select)
                    }
                }
            }
        }
    }

    if (showLabelScanner) LabelCamera(
        onDismiss = { showLabelScanner = false; model.clearLabelPhotos() },
        onCapture = { bytes, codes ->
            model.addLabelPhoto(bytes, codes)
            showLabelScanner = false
            model.analyzeLabelPhotos()
        },
    )
    if (labelAnalysis.busy) LabelAnalysisOverlay(labelAnalysis)
    if (showPortableImport) PortableImportDialog(
        dismiss = { showPortableImport = false },
        import = { text -> model.importPortableBundle(text); showPortableImport = false },
    )
    if (showSpoolmanImport) SpoolmanImportDialog(
        dismiss = { showSpoolmanImport = false },
        import = { text -> model.importSpoolmanJson(text); showSpoolmanImport = false },
    )
    if (showBulkImport) BulkCsvImportDialog(
        dismiss = { showBulkImport = false },
        import = { text -> model.importBulkCsv(text); showBulkImport = false },
    )
    if (model.spoolmanImports.isNotEmpty() && !showCustom) SpoolmanImportReviewDialog(
        imports = model.spoolmanImports,
        dismiss = model::clearSpoolmanImports,
        review = { imported ->
            custom = model.consumeSpoolmanImport(imported)
            labelReview = imported.warnings + "Provider: Spoolman JSON export"
            labelCodes = emptyList()
            showCustom = true
        },
    )
    if (model.bulkImports.isNotEmpty() && model.spoolmanImports.isEmpty() && !showCustom) BulkCsvReviewDialog(
        imports = model.bulkImports,
        dismiss = model::clearBulkImports,
        review = { imported ->
            custom = model.consumeBulkImport(imported)
            labelReview = imported.warnings + "Imported from bulk CSV row ${imported.row}; review before saving."
            labelCodes = emptyList()
            showCustom = true
        },
    )
    if (showCustom) CustomDialog(custom ?: CustomSeed(), labelReview, labelCodes, onDismiss = { showCustom = false; labelReview = emptyList(); labelCodes = emptyList(); model.clearLabelAnalysis() }, onSave = { values -> model.saveCustom(custom ?: CustomSeed(), values); showCustom = false; labelReview = emptyList(); labelCodes = emptyList(); model.clearLabelAnalysis() })
}

@Composable private fun BulkCsvImportDialog(dismiss: () -> Unit, import: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Bulk add filament records") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste CSV or tab-separated data with brand, material, and color columns. Up to 100 rows are reviewed one at a time before saving.")
            Text("Optional: product, color_hex, diameter_mm, weight_g, nozzle_min_c, nozzle_max_c, bed_min_c, bed_max_c, gtin, sku, transmission_distance.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { if (it.toByteArray().size <= 256_000) text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("CSV or TSV") })
        } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { import(text) }, enabled = text.isNotBlank()) { Text("Review records") } },
    )
}

@Composable private fun BulkCsvReviewDialog(imports: List<BulkCsvRecord>, dismiss: () -> Unit, review: (BulkCsvRecord) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Review bulk records") },
        text = { LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${imports.size} record(s) remain. Select each record to review and save locally.") }
            items(imports) { imported ->
                OutlinedButton(onClick = { review(imported) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${imported.seed.brand} · ${imported.seed.product.ifBlank { imported.seed.material }}", fontWeight = FontWeight.Bold)
                        Text("${imported.seed.material} · ${imported.seed.color} · row ${imported.row}")
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable private fun SpoolmanImportDialog(dismiss: () -> Unit, import: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("Import Spoolman JSON") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste a Spoolman spool or filament JSON export. Every selected record opens for review before it is saved locally.")
            OutlinedTextField(text, { if (it.length <= 2_000_000) text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("Spoolman JSON") })
        } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { import(text) }, enabled = text.isNotBlank()) { Text("Read export") } },
    )
}

@Composable private fun SpoolmanImportReviewDialog(imports: List<SpoolmanImport>, dismiss: () -> Unit, review: (SpoolmanImport) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("Review Spoolman records") },
        text = { LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${imports.size} record(s) remain. Select one to review and save; source and physical-spool quantities are preserved.") }
            items(imports) { imported ->
                val f = imported.filament
                OutlinedButton(onClick = { review(imported) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text("${f.brand.value} · ${f.product.value.ifBlank { f.material.value }}", fontWeight = FontWeight.Bold); Text("${f.material.value} · ${f.colorName.value.ifBlank { "#${f.colorHex.value}" }} · ${imported.remainingQuantityG ?: imported.initialQuantityG} g") }
                }
            }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable private fun PortableImportDialog(dismiss: () -> Unit, import: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Import portable spool") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste a SpoolForge portable spool JSON bundle. It is decoded entirely on this device.")
            OutlinedTextField(text, { if (it.length <= 65_536) text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("Portable JSON bundle") })
        } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { import(text) }, enabled = text.isNotBlank()) { Text("Review import") } },
    )
}

@Composable private fun LabelAnalysisOverlay(state: LabelAnalysisUiState) {
    val stages = listOf(
        "Reading printed label fields…",
        "Checking barcodes and identifiers…",
        "Preparing the editable filament record…",
    )
    var stage by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember(state.startedAtElapsedMs) { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1100)
            stage = (stage + 1) % stages.size
        }
    }
    LaunchedEffect(state.startedAtElapsedMs) {
        while (true) {
            elapsedSeconds = state.startedAtElapsedMs?.let { ((android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) / 1000L) } ?: 0L
            kotlinx.coroutines.delay(250)
        }
    }
    Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.safeDrawingPadding().padding(32.dp).semantics {
                    paneTitle = "Label analysis in progress"
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(64.dp).semantics { contentDescription = "Analyzing label" }, strokeWidth = 6.dp)
                Spacer(Modifier.height(28.dp))
                Text("Analyzing filament label", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Crossfade(targetState = stage, animationSpec = tween(350), label = "label-analysis-stage") { index ->
                    Text(stages[index], style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                Text("Keep SpoolForge open. You can rotate the phone while this finishes.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Attempt ${state.attempt} · ${elapsedSeconds}s elapsed", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable private fun ReadResult(result: DecodeResult?, edit: () -> Unit) {
    when (result) {
        is DecodeResult.Supported -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(Modifier.padding(12.dp)) {
            Text("Supported ${result.formatName} tag read", fontWeight = FontWeight.Bold)
            Text("Fields were decoded without rewriting the tag.")
            result.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = edit) { Text(if (result.convertedRecord == null) "Edit as a local record" else "Convert to a local record") }
        } }
        is DecodeResult.ReadOnly -> Notice("Read-only tag: ${result.reason}", false)
        is DecodeResult.Rejected -> Notice("Tag read: ${result.reason}", false)
        null -> Unit
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.catalogItems(recents: List<FilamentItem>, entries: List<FilamentItem>, select: (FilamentItem) -> Unit) {
    if (recents.isNotEmpty()) {
        item { Text("Recent", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold) }
        recents.take(5).forEach { item ->
            val e = item.entry
            item { TextButton(onClick = { select(item) }, modifier = Modifier.fillMaxWidth()) { Text((e.brand + " · " + e.product.ifBlank { e.material }) + " — " + e.colorName.ifBlank { "#" + e.colorHex }, modifier = Modifier.fillMaxWidth()) } }
        }
    }
    item { Text("${entries.size} matches shown", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge) }
        items(entries, key = { it.entry.packageId }) { item ->
            val e = item.entry
            Card(Modifier.fillMaxWidth().clickable { select(item) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val color = runCatching { Color(android.graphics.Color.parseColor("#" + e.colorHex.removePrefix("#"))) }.getOrElse { Color.Gray }
                    Box(Modifier.size(42.dp).background(color, RoundedCornerShape(12.dp)).semantics { contentDescription = "${e.colorName.ifBlank { "Filament" }} color sample" })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.brand} · ${e.product.ifBlank { e.material }}", fontWeight = FontWeight.SemiBold)
                        Text("${e.material} — ${e.colorName.ifBlank { "#" + e.colorHex }}")
                        Text(packageSummary(e), style = MaterialTheme.typography.bodySmall)
                        if(item.barcodeEvidence.isNotBlank()) Text(
                            when {
                                isCatalogBarcodeEvidence(item.barcodeEvidence) -> barcodeSummary(item.barcodeEvidence)
                                isProviderCandidateEvidence(item.barcodeEvidence) -> providerCandidateSummary(item.barcodeEvidence)
                                else -> scannedCodeSummary(item.barcodeEvidence)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
}

@Composable internal fun SpoolmanSyncDialog(initialServer: String, busy: Boolean, dismiss: () -> Unit, sync: (String, String?) -> Unit) {
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var density by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("Sync filament profile") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SpoolForge will find or create the vendor, then find or create this filament by stable external ID. It will not create a physical spool during live sync.")
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Spoolman server URL") }, placeholder = { Text("http://192.168.1.10:7912") }, singleLine = true)
            OutlinedTextField(density, { density = it }, Modifier.fillMaxWidth(), label = { Text("Density g/cm³ (optional)") }, supportingText = { Text("Leave blank to review the material-based value shown after sync.") }, singleLine = true)
            Text("Tapping Sync changes the named Spoolman server. A connection loss after a request is reported as an unknown outcome and is never retried automatically.", style = MaterialTheme.typography.bodySmall)
        } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { sync(server, density.ifBlank { null }) }, enabled = server.isNotBlank() && !busy) { Text("Sync") } },
    )
}

@Composable internal fun PortableIdentityDialog(identity: PortableSpoolIdentity, dismiss: () -> Unit) {
    val context = LocalContext.current
    var temperatures by rememberSaveable { mutableStateOf(true) }
    var identifiers by rememberSaveable { mutableStateOf(true) }
    var transmissionDistance by rememberSaveable { mutableStateOf(true) }
    val qrBytes = remember(identity) { PortableIdentityCodec.encodeQr(identity) }
    val qr = remember(qrBytes.contentHashCode()) {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(qrBytes.decodeToString(), com.google.zxing.BarcodeFormat.QR_CODE, 720, 720,
            mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }.asImageBitmap()
    }
    val f = identity.filament
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Portable spool label") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${f.brand.value} · ${f.product.value.ifBlank { f.material.value }}", fontWeight = FontWeight.Bold)
            Text("${f.material.value} — ${f.colorName.value.ifBlank { "#${f.colorHex.value}" }} · ${f.diameterMm.value} mm · ${identity.initialQuantityG} g")
            Image(qr, "Self-contained SpoolForge portable spool QR", Modifier.fillMaxWidth().aspectRatio(1f))
            Text("QR schema $PORTABLE_IDENTITY_SCHEMA/$PORTABLE_IDENTITY_VERSION · ${qrBytes.size} bytes", style = MaterialTheme.typography.bodySmall)
            LabelOption("Show temperatures", temperatures) { temperatures = it }
            LabelOption("Show identifiers", identifiers) { identifiers = it }
            LabelOption("Show transmission distance", transmissionDistance) { transmissionDistance = it }
            if (temperatures) Text("Nozzle ${range(f.nozzleMinC?.value, f.nozzleMaxC?.value)} · Bed ${range(f.bedMinC?.value, f.bedMaxC?.value)}")
            if (identifiers) listOfNotNull(f.gtin?.let { "GTIN $it" }, f.sku?.let { "SKU $it" }).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ")) }
            if (transmissionDistance) f.transmissionDistance?.let { Text("Transmission distance ${it.value} mm") }
            Text("Profile ID: ${identity.profileId}\nSpool ID: ${identity.spoolId}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val bundle = PortableIdentityCodec.encodeBundle(identity).decodeToString()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.filamajig.spool+json"
                    putExtra(Intent.EXTRA_TEXT, bundle)
                }, "Export portable spool"))
            }, modifier = Modifier.fillMaxWidth()) { Text("Share export bundle") }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable private fun LabelOption(text: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, Modifier.weight(1f)); Switch(checked, change)
    }
}

@Composable internal fun NfcPanel(nfc: NfcCoordinator) {
    var showDiagnostics by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val s = nfc.state
    val optionalSecondTag = s.tagNumber == 2 && s.phase in setOf(WritePhase.AWAITING_TAG, WritePhase.INSPECTING, WritePhase.NEEDS_OVERWRITE_CONSENT, WritePhase.REJECTED, WritePhase.FAILED_BEFORE_WRITE)
    val active = s.phase !in setOf(WritePhase.DRAFT, WritePhase.UNRESOLVED_ARCHIVED, WritePhase.CANCELLED)
    if (active) Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = if (s.phase == WritePhase.VERIFIED) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(phaseLabel(s.phase), fontWeight = FontWeight.Bold)
            Text(s.detail)
            nfc.detectedTagType?.let{Text(it,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)}
            if (s.phase == WritePhase.NEEDS_OVERWRITE_CONSENT) Button(onClick = nfc::approveOverwrite) { Text("Approve overwrite for this tag") }
            if (s.phase == WritePhase.VERIFIED && s.tagNumber == 1 && s.intent != null) {
                Button(onClick = nfc::finishWithOneTag, modifier = Modifier.fillMaxWidth()) { Text("Finish with one tag") }
                OutlinedButton(onClick = nfc::armSecondTag, modifier = Modifier.fillMaxWidth()) { Text("Write matching second tag") }
            }
            if (optionalSecondTag) OutlinedButton(onClick = nfc::finishWithOneTag, modifier = Modifier.fillMaxWidth()) { Text("Cancel second tag; keep first") }
            if (!optionalSecondTag && s.phase !in setOf(WritePhase.VERIFIED, WritePhase.COMPLETED, WritePhase.VERIFICATION_PENDING, WritePhase.WRITE_OUTCOME_UNKNOWN)) TextButton(onClick = nfc::cancel) { Text("Cancel before writing") }
            if (s.phase == WritePhase.VERIFICATION_PENDING) OutlinedButton(onClick = nfc::archiveUnknown) { Text("Archive unverified result; start another") }
            if (s.phase == WritePhase.WRITE_OUTCOME_UNKNOWN) OutlinedButton(onClick = nfc::archiveUnknown) { Text("Archive unresolved result; start another") }
            if (nfc.cfsFinalizationResumeAvailable && s.phase in setOf(WritePhase.VERIFICATION_PENDING, WritePhase.WRITE_OUTCOME_UNKNOWN)) Button(onClick = nfc::approveCfsFinalizationResume, modifier = Modifier.fillMaxWidth()) { Text("Approve CFS trailer completion") }
        }
    }
    if (nfc.readDiagnostic != null || nfc.unresolvedArchiveCount > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (nfc.readDiagnostic != null) TextButton(onClick = { showDiagnostics = true }) { Text("Tag diagnostics") }
        if (nfc.unresolvedArchiveCount > 0) TextButton(onClick = { showHistory = true }) { Text("Unresolved history (${nfc.unresolvedArchiveCount})") }
    }
    nfc.bindingStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (showDiagnostics) AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text("Last tag read diagnostics") }, text = {
        Text(nfc.readDiagnostic.orEmpty(), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } })
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("Unresolved write history") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            nfc.archivedWrites().forEach { entry ->
                Text("Unresolved · ${java.util.Date(entry.timestamp)} · UID ${entry.uid.joinToString("") { "%02X".format(it) }}", fontWeight = FontWeight.Bold)
                Text("Intended data: ${entry.payload.decodeToString()}", style = MaterialTheme.typography.bodySmall)
                Text("Archived without confirming the tag's contents.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } })
}

internal fun wireFieldSummary(payload: ByteArray): String {
    val root=StrictJson().parse(payload) as JsonValue.Obj
    return root.values.entries.joinToString { (name,value) ->
        val type=when(value){is JsonValue.Str->"string";is JsonValue.Num->"number";is JsonValue.Arr->"array";is JsonValue.Bool->"boolean";JsonValue.Null->"null";is JsonValue.Obj->"object"}
        "$name ($type)"
    }
}

internal fun barcodeSummary(evidence: String): String = runCatching {
    val e = org.json.JSONObject(evidence)
    val count = e.getInt("candidate_count")
    "${e.getJSONObject("snapshot").getString("source")} · ${e.getString("scope")} · $count candidate(s) for barcode"
}.getOrDefault("Barcode source evidence unavailable")

internal fun isCatalogBarcodeEvidence(evidence: String): Boolean = runCatching {
    val value = org.json.JSONObject(evidence)
    value.has("candidate_count") && value.optJSONObject("snapshot") != null
}.getOrDefault(false)

internal fun isProviderCandidateEvidence(evidence: String): Boolean = runCatching {
    val value = org.json.JSONObject(evidence)
    value.optString("provider").isNotBlank() && value.optString("record_id").isNotBlank() && value.optJSONArray("match_reasons") != null
}.getOrDefault(false)

internal fun providerCandidateSummary(evidence: String): String = runCatching {
    val value = org.json.JSONObject(evidence)
    val provider = when (val id = value.getString("provider")) {
        "ofd" -> "Open Filament Database"
        "spoolmandb-community" -> "SpoolmanDB Community"
        "openprinttag" -> "OpenPrintTag"
        "local" -> "Local record"
        else -> id
    }
    val reasons = value.getJSONArray("match_reasons").let { array -> (0 until array.length()).map(array::getString) }
    "$provider · ${reasons.joinToString().ifBlank { "candidate" }} · ${value.getString("record_id")}"
}.getOrDefault("Catalog candidate provenance unavailable")

internal fun scannedCodeSummary(evidence: String): String = evidence.lineSequence()
    .let { original ->
        val parsed = labelCodesFromEvidence(evidence)
        if (parsed.isEmpty()) original.map(String::trim).filter(String::isNotBlank)
        else parsed.asSequence().map { "${it.kind} (${it.format})${it.photoRole?.let { role -> " [$role]" }.orEmpty()}: ${it.value}" }
    }
    .take(3)
    .map { line ->
        val marker = line.indexOf(": ")
        if (marker < 0) line.take(96) else {
            val label = line.substring(0, marker)
            val payload = line.substring(marker + 2)
            if (label.startsWith("QR payload")) summarizeQrPayload(payload) else "$label: ${payload.take(72)}${if (payload.length > 72) "…" else ""}"
        }
    }
    .joinToString("\n")
    .ifBlank { "Detected code evidence unavailable" }

internal fun retailGtinDisplay(gtin: String?, evidence: String): String {
    val resolved = gtin ?: recoveredGtinFromEvidence(evidence) ?: return "Not detected"
    val decoded = labelCodesFromEvidence(evidence).firstOrNull { it.gtin == resolved }
    return if (decoded != null) "${decoded.value} (${decoded.format.replace('_', '-')})" else resolved
}

private fun summarizeQrPayload(payload: String): String = runCatching {
    val value = org.json.JSONObject(payload)
    val schema = value.optString("schema").ifBlank { "structured profile" }
    val identity = listOf("brand", "material", "color").map(value::optString).filter(String::isNotBlank)
    "QR/RFID profile: $schema${identity.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ", separator = " · ").orEmpty()}"
}.getOrElse { "QR payload: ${payload.take(72)}${if (payload.length > 72) "…" else ""}" }

@Composable internal fun ProviderCandidateEvidence(evidence: String) {
    var show by remember { mutableStateOf(false) }
    val value = remember(evidence) { runCatching { org.json.JSONObject(evidence) }.getOrNull() }
    val conflicts = value?.optJSONArray("conflicting_fields")?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
    if (conflicts.isNotEmpty()) Notice("Candidates sharing this identifier disagree on: ${conflicts.joinToString()}.", false)
    TextButton(onClick = { show = true }) { Text("Provider match details") }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Provider candidate provenance") },
        text = { Text(evidence, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
    )
}

@Composable internal fun DetectedCodeEvidence(evidence: String) {
    var show by remember { mutableStateOf(false) }
    Info("Detected code", scannedCodeSummary(evidence))
    if (evidence.length > scannedCodeSummary(evidence).length) {
        TextButton(onClick = { show = true }) { Text("View complete code payload") }
    }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Detected code evidence") },
        text = { Text(evidence, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
    )
}

@Composable internal fun BarcodeEvidence(item: FilamentItem) {
    var show by remember { mutableStateOf(false) }
    val evidence = remember(item.barcodeEvidence) { runCatching { org.json.JSONObject(item.barcodeEvidence) }.getOrNull() }
    evidence?.let { ev ->
        val flags = ev.optJSONArray("flags")
        val conflicts = ev.optJSONArray("conflicting_fields")
        val flagText = flags?.let { (0 until it.length()).mapNotNull(it::optString).filter(String::isNotBlank) }.orEmpty()
        val conflictText = conflicts?.let { (0 until it.length()).mapNotNull(it::optString).filter(String::isNotBlank) }.orEmpty()
        if(flagText.isNotEmpty() || conflictText.isNotEmpty()) Notice("Confirm package details. " + flagText.joinToString("; ") + if(conflictText.isNotEmpty()) " Sources/candidates disagree on: " + conflictText.joinToString() else "", false)
        TextButton(onClick = { show = true }) { Text("Barcode match and field sources") }
        if(show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Barcode provenance") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Exact normalized GTIN: ${ev.optString("gtin14", "Unavailable")}")
                Text("Original identifier: ${ev.optString("raw_identifier", "Unavailable")}")
                Text("Source record: ${ev.optString("source_record", "Unavailable")}")
                Text("Location: ${ev.optString("record_pointer", "Unavailable")}")
                val snapshot = ev.optJSONObject("snapshot")
                Text("Source: ${snapshot?.optString("source", "Unavailable") ?: "Unavailable"}")
                Text("Snapshot: ${snapshot?.optString("revision", "Unavailable") ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                Text("License: ${snapshot?.optString("license", "Unavailable") ?: "Unavailable"}")
                Text(snapshot?.optString("url", "Unavailable") ?: "Unavailable", style = MaterialTheme.typography.bodySmall)
                Text("Original barcode match evidence. Locally edited fields keep their own sources. No automatic field merging; matching sources may share upstream data.")
                Text("Original source fields", fontWeight = FontWeight.Bold)
                val originalFields = ev.optJSONObject("fields")
                originalFields?.keys()?.asSequence()?.sorted()?.forEach { field ->
                    val claim = originalFields.optJSONObject(field)
                    Text("$field: ${claim?.optString("value", "Unavailable") ?: "Unavailable"}\n${claim?.optString("origin", "Unavailable") ?: "Unavailable"} · ${claim?.optString("kind", "Unavailable") ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Current field sources (including local edits)", fontWeight = FontWeight.Bold)
                item.sources.filterValues { it.isNotBlank() }.forEach { (field, source) -> Text("$field: $source", style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } })
    }
}

@Composable internal fun Info(label: String, value: String) {
    if (label.length > 17) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(value, Modifier.padding(start = 12.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.width(105.dp), fontWeight = FontWeight.SemiBold)
            Text(value, Modifier.weight(1f))
        }
    }
}
@Composable internal fun Notice(text: String, error: Boolean) { Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) { Text(text, Modifier.padding(12.dp)) } }
private fun range(a: Int?, b: Int?) = when { a == null && b == null -> "Missing in source"; a == b -> "$a °C"; else -> "${a ?: "?"}–${b ?: "?"} °C" }
internal fun packageSummary(e: net.jamesjennison.filamajignfc.data.CatalogEntry): String {
    val diameter = e.diameterMm.ifBlank { DEFAULT_DIAMETER_MM }
    val mass = e.massG.takeIf { it > 0 } ?: DEFAULT_NOMINAL_MASS_G.toInt()
    return "$diameter mm · $mass g"
}

@Composable private fun CustomDialog(seed: CustomSeed, review: List<String> = emptyList(), codes: List<LabelCode> = emptyList(), onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val values = remember(seed) { mutableStateMapOf<String, String>().apply { putAll(seed.values()) } }
    val fields = listOf("brand" to "Brand", "material" to "Material", "product" to "Product (optional label)", "colorName" to "Color name (optional label)", "colorHex" to "Color hex", "diameter" to "Diameter mm", "mass" to "Mass g", "nozzleMin" to "Nozzle minimum °C", "nozzleMax" to "Nozzle maximum °C", "bedMin" to "Bed minimum °C", "bedMax" to "Bed maximum °C", "transmissionDistance" to "Transmission distance (HueForge TD)")
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (seed.id == null) "Custom filament" else "Edit filament locally") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Review every value before saving. Missing diameter and mass default to 1.75 mm and 1000 g; other blank optional fields remain missing. Each changed field is marked Edited locally.", style = MaterialTheme.typography.bodySmall)
            if (review.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(10.dp)) { Text("Review notes", fontWeight = FontWeight.Bold); review.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
            if (seed.gtin != null || seed.articleNumber != null || seed.barcodeEvidence.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(10.dp)) {
                seed.gtin?.let { Text("GTIN: $it"); seed.sources["gtin"]?.let { source -> Text("Source: $source", style = MaterialTheme.typography.bodySmall) } }
                seed.articleNumber?.let { Text("SKU / identifier: $it"); seed.sources["articleNumber"]?.let { source -> Text("Source: $source", style = MaterialTheme.typography.bodySmall) } }
                if(seed.barcodeEvidence.isNotBlank()) Text(scannedCodeSummary(seed.barcodeEvidence), style = MaterialTheme.typography.bodySmall)
            } }
            OutlinedButton(onClick = { uriHandler.openUri(filamentProfilesSearchUrl(values["brand"], values["material"], values["colorName"])) }, modifier = Modifier.fillMaxWidth()) { Text("Search 3D Filament Profiles") }
            Text("Opens their search in your browser with this brand, material, and color filled in. SpoolForge does not copy profile data until 3D Filament Profiles provides an authorized API or export.", style = MaterialTheme.typography.bodySmall)
            fields.forEach { (key, label) -> OutlinedTextField(values[key].orEmpty(), { values[key] = it }, label = { Text(label) }, supportingText = seed.sources[key]?.takeIf(String::isNotBlank)?.let { source -> { Text(if(source == "Not found on label") source else "Source: $source") } }, singleLine = true) }
        } },
        confirmButton = { Button(onClick = { onSave(values.toMap()) }) { Text("Save locally") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun fieldLabel(value: String) = mapOf("brand" to "brand", "colorName" to "color name", "bedTemperatureRange" to "bed temperature range", "bedTemperatureTargets" to "bed temperature targets", "temperatures" to "temperatures", "weight" to "weight", "drying" to "drying settings", "packageIdentity" to "package identity", "remainingWeight" to "remaining weight", "signature" to "signature", "product" to "product label", "diameter" to "diameter", "mass" to "mass", "nozzleMin" to "nozzle minimum", "nozzleMax" to "nozzle maximum", "bedMin" to "bed minimum", "bedMax" to "bed maximum", "transmissionDistance" to "transmission distance", "additionalColors" to "additional colors", "packageId" to "package identifier", "gtin" to "barcode", "sku" to "article number", "provenance" to "field provenance")[value] ?: value
private const val FILAMENT_PROFILES_CATALOG_URL = "https://3dfilamentprofiles.com/filaments"

/**
 * Link to 3D Filament Profiles' own search for the given terms. Their search box accepts brand, material,
 * type, color, SKU, GTIN, or a #hex color; with no usable terms this is the catalog front page.
 */
internal fun filamentProfilesSearchUrl(vararg terms: String?): String {
    val query = terms.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ").replace(Regex("\\s+"), " ").take(120)
    return if (query.isEmpty()) FILAMENT_PROFILES_CATALOG_URL else "$FILAMENT_PROFILES_CATALOG_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
}
private fun phaseLabel(phase: WritePhase) = when (phase) { WritePhase.AWAITING_TAG -> "Ready for tag"; WritePhase.INSPECTING -> "Inspecting tag"; WritePhase.NEEDS_OVERWRITE_CONSENT -> "Overwrite approval needed"; WritePhase.WRITING -> "Writing — keep tag in place"; WritePhase.VERIFYING -> "Verifying fresh read"; WritePhase.VERIFICATION_PENDING -> "Verification needed"; WritePhase.VERIFIED -> "Write verified"; WritePhase.COMPLETED -> "Tagging complete"; WritePhase.CANCELLED -> "Cancelled before writing"; WritePhase.REJECTED -> "Tag rejected"; WritePhase.FAILED_BEFORE_WRITE -> "Write not attempted"; WritePhase.WRITE_OUTCOME_UNKNOWN -> "Write outcome unknown"; WritePhase.UNRESOLVED_ARCHIVED -> "Unresolved result archived"; WritePhase.DRAFT -> "Draft" }
