@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.jamesjennison.filamajignfc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import net.jamesjennison.filamajignfc.core.Provenance

/** The top of the home screen: find a filament by typing, or add one by scanning its label or entering it by hand. */
@Composable internal fun HomeHeader(model: MainViewModel, scanLabel: () -> Unit, addManually: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            model.query, { model.query = it; model.search() },
            Modifier.fillMaxWidth().padding(top = 4.dp),
            label = { Text("Search filaments") },
            placeholder = { Text("Brand, material, color, or barcode") },
            singleLine = true,
            trailingIcon = { if (model.query.isNotEmpty()) TextButton(onClick = { model.query = ""; model.search() }) { Text("Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = scanLabel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Scan a label") }
            OutlinedButton(onClick = addManually, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Add manually") }
        }
        val analysis = model.labelAnalysis
        analysis.message?.let { Notice(it, it.startsWith("Label scan failed")) }
        if (!analysis.busy && analysis.result == null && model.labelPhotos.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::analyzeLabelPhotos, modifier = Modifier.weight(1f)) { Text("Try the scan again") }
                OutlinedButton(onClick = model::clearLabelAnalysis, modifier = Modifier.weight(1f)) { Text("Discard photo") }
            }
        }
        model.searchNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The overflow menu in the top bar: everything that is not finding or adding one filament. */
@Composable internal fun HomeMenu(importBundle: () -> Unit, importSpoolman: () -> Unit, importSpreadsheet: () -> Unit, aiScanning: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "More options" }) { Text("⋮", fontSize = 24.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Import a SpoolForge QR or file") }, onClick = { open = false; importBundle() })
            DropdownMenuItem(text = { Text("Import from Spoolman") }, onClick = { open = false; importSpoolman() })
            DropdownMenuItem(text = { Text("Add many from a spreadsheet") }, onClick = { open = false; importSpreadsheet() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Label scanning account") }, onClick = { open = false; aiScanning() })
        }
    }
}

/**
 * The filament list. With nothing typed it shows recent filaments, then saved ones, then the catalog to browse;
 * with a search it shows the matches and offers a way forward when the filament is not among them.
 */
internal fun LazyListScope.filamentList(query: String, busy: Boolean, recents: List<FilamentItem>, results: List<FilamentItem>, select: (FilamentItem) -> Unit, addManually: () -> Unit) {
    val searching = query.isNotBlank()
    val recent = recentWorthShowing(recents, results)
    if (!searching && recent.isNotEmpty()) {
        item { SectionTitle("Recent") }
        items(recent, key = { "recent-" + it.entry.packageId }) { FilamentRow(it, select) }
    }
    if (searching) {
        item {
            SectionTitle(when (results.size) {
                0 -> if (busy) "Searching…" else "No filaments match"
                1 -> "1 result"
                in 2..99 -> "${results.size} results"
                else -> "First ${results.size} results — keep typing to narrow down"
            })
        }
        items(results, key = { it.entry.packageId }) { FilamentRow(it, select) }
        if (!busy) item { NotFoundActions(query, addManually) }
    } else {
        val (saved, catalog) = results.partition { it.provenance != Provenance.CATALOG }
        if (saved.isNotEmpty()) {
            item { SectionTitle("Your filaments") }
            items(saved, key = { it.entry.packageId }) { FilamentRow(it, select) }
        }
        if (catalog.isNotEmpty()) {
            item { SectionTitle("Browse the catalog") }
            items(catalog, key = { it.entry.packageId }) { FilamentRow(it, select) }
        }
    }
}

/**
 * Recent filaments, unless the list right below already shows the same few. With only a handful saved,
 * a Recent section would repeat "Your filaments" line for line.
 */
internal fun recentWorthShowing(recents: List<FilamentItem>, results: List<FilamentItem>): List<FilamentItem> {
    val recent = recents.take(5)
    val saved = results.filter { it.provenance != Provenance.CATALOG }.map { it.entry.packageId }.toSet()
    return if (saved.size <= 5 && recent.all { it.entry.packageId in saved }) emptyList() else recent
}

@Composable private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable private fun FilamentRow(item: FilamentItem, select: (FilamentItem) -> Unit) {
    val e = item.entry
    Card(Modifier.fillMaxWidth().clickable { select(item) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = runCatching { Color(("#" + e.colorHex.removePrefix("#")).toColorInt()) }.getOrNull()
            Box(
                Modifier.size(44.dp)
                    .background(color ?: MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "${displayColor(item)} color sample" },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName(item), fontWeight = FontWeight.SemiBold)
                Text("${displayColor(item)} · ${e.material}", style = MaterialTheme.typography.bodyMedium)
                Text(packageSummary(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Barcode matches can return several packages of the same filament, so say which source each came from.
                val evidence = item.barcodeEvidence
                val source = when {
                    isCatalogBarcodeEvidence(evidence) -> barcodeSummary(evidence)
                    isProviderCandidateEvidence(evidence) -> providerCandidateSummary(evidence)
                    else -> null
                }?.substringBefore(" · ")?.takeIf(String::isNotBlank)
                source?.let { Text("From $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun NotFoundActions(query: String, addManually: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Not the one you're after?", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = addManually) { Text("Add it manually") }
            TextButton(onClick = { uriHandler.openUri(filamentProfilesSearchUrl(query)) }) { Text("Look it up on 3D Filament Profiles") }
        }
    }
}
