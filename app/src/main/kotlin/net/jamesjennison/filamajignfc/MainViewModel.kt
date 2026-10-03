package net.jamesjennison.filamajignfc

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.jamesjennison.filamajignfc.core.*
import net.jamesjennison.filamajignfc.data.*
import java.io.File
import java.util.UUID

private val EDITABLE_FIELDS = listOf("brand", "material", "product", "colorName", "colorHex", "diameter", "mass", "nozzleMin", "nozzleMax", "bedMin", "bedMax", "transmissionDistance")
internal const val DEFAULT_DIAMETER_MM = "1.75"
internal const val DEFAULT_NOMINAL_MASS_G = "1000"
internal const val ASSUMED_DEFAULT_SOURCE = "Assumed SpoolForge default"
internal fun withPhysicalDefaults(values: Map<String, String>) = values + mapOf(
    "diameter" to values["diameter"].orEmpty().ifBlank { DEFAULT_DIAMETER_MM },
    "mass" to values["mass"].orEmpty().ifBlank { DEFAULT_NOMINAL_MASS_G },
)
data class LabelAnalysisUiState(
    val busy: Boolean = false,
    val result: LabelScanResult? = null,
    val message: String? = null,
    val startedAtElapsedMs: Long? = null,
    val attempt: Int = 0,
)
/** What the ChatGPT plan card shows. Never holds tokens. */
data class ChatGptUiState(
    val connected: Boolean = false,
    val email: String? = null,
    val signingIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val usageLimitReached: Boolean = false,
    val showWelcome: Boolean = false,
)
data class SpoolmanSyncUiState(val busy: Boolean = false, val message: String? = null, val outcomeUnknown: Boolean = false)
data class FilamentItem(val entry: CatalogEntry, val provenance: Provenance, val sources: Map<String, String>, val originalPackageId: String? = null, val barcodeEvidence: String = "", val transmissionDistance: String? = null) { fun source(field: String) = sources[field].orEmpty() }
data class CustomSeed(
    val id: String? = null, val originalPackageId: String? = null, val barcodeEvidence: String = "",
    val portableProfileId: String? = null, val portableSpoolId: String? = null,
    val portableInitialQuantityG: Int? = null, val portableRemainingQuantityG: Int? = null,
    val variantId: String = "", val productId: String = "", val gtin: String? = null, val articleNumber: String? = null, val additionalColorHexes: String = "",
    val brand: String = "", val material: String = "", val product: String = "", val color: String = "",
    val hex: String = "", val diameter: String = DEFAULT_DIAMETER_MM, val mass: String = DEFAULT_NOMINAL_MASS_G,
    val nozzleMin: String = "", val nozzleMax: String = "", val bedMin: String = "", val bedMax: String = "", val transmissionDistance: String = "",
    val sourceRevision: String = "user", val provenance: Provenance = Provenance.CUSTOM,
    val sources: Map<String, String> = emptyMap(), val originalValues: Map<String, String> = emptyMap(),
) {
    fun values() = mapOf("brand" to brand, "material" to material, "product" to product, "colorName" to color, "colorHex" to hex, "diameter" to diameter, "mass" to mass, "nozzleMin" to nozzleMin, "nozzleMax" to nozzleMax, "bedMin" to bedMin, "bedMax" to bedMax, "transmissionDistance" to transmissionDistance)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as FilamajigApplication).data
    private val pendingLabelFile = File(app.filesDir, "pending-label-analysis.jpg")
    private val restoredLabelPhoto = runCatching {
        pendingLabelFile.takeIf { it.isFile && it.length() in 1..6_000_000 }?.readBytes()
    }.getOrNull()
    var savedTagBindings by mutableStateOf<List<TagBindingEntity>>(emptyList()); private set
    val nfc = NfcCoordinator(app, onVerified = { verified ->
        runBlocking {
            store.filaments.bindVerifiedTag(
                verified.context.spoolId, verified.uid, verified.context.codecId,
                verified.payload, verified.bindingOrder,
            )
            val bindings=store.filaments.tagBindings(verified.context.spoolId)
            viewModelScope.launch { savedTagBindings=bindings }
        }
    })
    var loading by mutableStateOf(true); private set
    var query by mutableStateOf("")
    var results by mutableStateOf<List<FilamentItem>>(emptyList()); private set
    var recents by mutableStateOf<List<FilamentItem>>(emptyList()); private set
    var selected by mutableStateOf<FilamentItem?>(null)
    var error by mutableStateOf<String?>(null); private set
    val tagCodecs: List<FilamentTagCodec> = TagCodecRegistry.codecs
    var codecId by mutableStateOf(TagCodecRegistry.DEFAULT_CODEC_ID)
    var compatiblePrinterTargets by mutableStateOf(setOf(PrinterTarget.ELEGOO_CANVAS,PrinterTarget.SNAPMAKER_U1_PAXX)); private set
    var compatibilityResult by mutableStateOf(CompatibilityResolver.resolve(compatiblePrinterTargets)); private set
    var chooseByPrinters by mutableStateOf(true); private set
    /** The user's answer to "Which printer is it for?"; it decides the tag format. */
    internal var writeTarget by mutableStateOf(WriteTargets.require(WriteTargets.DEFAULT_ID)); private set
    private val gtinIndex = GtinIndex(app)
    private val ofdProvider = OfdCatalogProvider(store.catalog.catalog())
    private val localProvider = LocalCatalogProvider(store.user)
    private val communityProvider = CommunityCatalogProvider(app)
    private val gtinProvider = MergedGtinCatalogProvider(gtinIndex)
    private val chatGptStore: ChatGptStore = KeystoreChatGptStore(app)
    private val chatGptSession = ChatGptSession(chatGptStore)
    private val labelClient = LabelScanClient(
        chatGpt = ChatGptLabelAnalyzer(
            isConnected = { chatGptSession.isConnected },
            respond = chatGptSession::respond,
        ),
    )
    var chatGpt by mutableStateOf(ChatGptUiState()); private set
    private val spoolmanClient = SpoolmanSyncClient()
    private val spoolmanPrefs = app.getSharedPreferences("spoolman-settings", 0)
    var spoolmanServer by mutableStateOf(spoolmanPrefs.getString("server", "").orEmpty()); private set
    var spoolmanSync by mutableStateOf(SpoolmanSyncUiState()); private set
    var labelAnalysis by mutableStateOf(LabelAnalysisUiState(message = restoredLabelPhoto?.let { "Label analysis was interrupted. Retry the saved photo or discard it." })); private set
    var portableImport by mutableStateOf<CustomSeed?>(null); private set
    var spoolmanImports by mutableStateOf<List<SpoolmanImport>>(emptyList()); private set
    var bulkImports by mutableStateOf<List<BulkCsvRecord>>(emptyList()); private set
    var labelPhotos by mutableStateOf(restoredLabelPhoto?.let { listOf(LabelPhoto(LabelPhotoRole.PROFILE, it, emptyList())) }.orEmpty()); private set
    var barcodeMode by mutableStateOf(false); private set
    var searchNotice by mutableStateOf<String?>(null); private set
    private var searchGeneration = 0
    private var customItems: List<FilamentItem> = emptyList()
    private var catalogItems: List<FilamentItem> = emptyList()

    init {
        refreshChatGpt()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                store.ensureCatalog()
                ofdProvider.search(CatalogQuery(limit = 100)).map(CatalogCandidate::toFilamentItem)
            } }
                .onSuccess { catalogItems = it; refreshVisible(); loading = false }
                .onFailure { error = it.message; loading = false }
            store.user.user().recents().collectLatest { saved ->
                recents = saved.mapNotNull { recent ->
                    decodeSnapshot(recent.snapshotJson) ?: hydrateLegacyRecent(recent, store.catalog.catalog(), store.user.user())
                }
            }
        }
        viewModelScope.launch { store.user.user().customs().collectLatest { customItems = it.map(CustomRecord::asItem); refreshVisible() } }
    }

    fun useBarcodeLookup(enabled: Boolean) {
        searchGeneration++; barcodeMode=enabled; error=null; searchNotice=null
        results=if(enabled) emptyList() else customItems+catalogItems
    }

    private fun refreshVisible() { if (query.isBlank() && !barcodeMode) results = customItems + catalogItems }
    fun search() {
        val q = query.trim()
        if (q.length > 120) { error = "Search is limited to 120 characters"; return }
        val generation = ++searchGeneration
        val useBarcode = barcodeMode
        if(useBarcode) { results=emptyList(); searchNotice="Looking up barcode…" }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (useBarcode) return@withContext CatalogCandidateRanking.rank(
                        gtinProvider.search(CatalogQuery(identifiers = listOf("GTIN" to q), limit = 100))
                    ).map(CatalogCandidate::toFilamentItem)
                    val providerQuery = CatalogQuery(text = q, identifiers = listOf("SKU" to q), limit = 100)
                    val local=localProvider.search(providerQuery)
                    if (q.isBlank()) return@withContext local.map(CatalogCandidate::toFilamentItem)+catalogItems
                    CatalogCandidateRanking.rank(local + ofdProvider.search(providerQuery) + communityProvider.search(providerQuery),limit=100).map(CatalogCandidate::toFilamentItem)
                }
            }.onSuccess { if (generation == searchGeneration) { results = it; error = null; searchNotice = if(useBarcode) "${it.size} source candidates. Select the exact package; sources are not automatically merged." else null } }.onFailure { if(generation == searchGeneration) { results=emptyList(); searchNotice=null; error = if(useBarcode) it.message ?: "Barcode lookup failed" else "Search could not be completed" } }
        }
    }

    fun select(item: FilamentItem) {
        selected = item
        savedTagBindings=emptyList()
        viewModelScope.launch(Dispatchers.IO) { store.user.user().recent(Recent(item.entry.packageId, item.provenance.name, encodeSnapshot(item), System.currentTimeMillis())) }
        if(item.provenance!=Provenance.CATALOG)viewModelScope.launch {
            savedTagBindings = withContext(Dispatchers.IO) {
                store.filaments.portableIds(item.entry.packageId)?.let { store.filaments.tagBindings(it.spoolId) }.orEmpty()
            }
        }
    }

    fun clearLabelPhotos() { if (!labelAnalysis.busy) { labelPhotos = emptyList(); pendingLabelFile.delete() } }
    fun addLabelPhoto(jpeg: ByteArray, codes: List<LabelCode>) {
        if (labelAnalysis.busy || labelPhotos.isNotEmpty()) return
        val role = LabelPhotoRole.PROFILE
        val retained = jpeg.copyOf()
        runCatching {
            val temporary = File(pendingLabelFile.parentFile, "${pendingLabelFile.name}.tmp")
            temporary.writeBytes(retained)
            check(temporary.renameTo(pendingLabelFile)) { "Could not retain the captured label photo" }
        }.onFailure { error = "The label photo was captured but could not be retained for task recovery." }
        labelPhotos = labelPhotos + LabelPhoto(role, retained, codes.map { it.copy(photoRole = role.wireName) })
    }
    fun analyzeLabelPhotos() {
        val photos = labelPhotos
        if (labelAnalysis.busy || photos.isEmpty()) return
        val attempt = labelAnalysis.attempt + 1
        labelAnalysis = LabelAnalysisUiState(busy = true, startedAtElapsedMs = SystemClock.elapsedRealtime(), attempt = attempt)
        viewModelScope.launch {
            runCatching {
                val stillCodes = withContext(Dispatchers.Default) {
                    photos.flatMap { photo -> decodeLabelCodesFromImage(photo.jpeg).map { it.copy(photoRole = photo.role.wireName) } }
                }
                val mergedCodes = reconcileLabelCodes(photos.flatMap { it.codes } + stillCodes)
                val portableCode = mergedCodes.firstOrNull { it.format == "QR_CODE" && it.value.contains(PORTABLE_IDENTITY_SCHEMA) }
                if (portableCode != null) {
                    handlePortableDecode(PortableIdentityCodec.decode(portableCode.value.encodeToByteArray()), "QR")
                    return@launch
                }
                labelClient.analyze(photos, mergedCodes)
            }
                .onSuccess { labelAnalysis = LabelAnalysisUiState(result = it, message = "Label analyzed. Review every field before saving.", attempt = attempt) }
                .onFailure { labelAnalysis = LabelAnalysisUiState(message = "Label scan failed: ${it.message ?: "unknown error"}", attempt = attempt) }
        }
    }
    private fun chatGptFailure(failure: Throwable, connected: Boolean): ChatGptUiState {
        val error = failure as? ChatGptException
        return chatGpt.copy(
            connected = connected, signingIn = false, busy = false,
            message = if (error?.code == "cancelled") null else error?.message ?: "ChatGPT could not be reached. Try again.",
            isError = error?.code != "cancelled",
            usageLimitReached = error?.code == CHATGPT_USAGE_LIMIT_CODE,
        )
    }

    /** Loads the saved connection. Called once at start. */
    fun refreshChatGpt() {
        viewModelScope.launch {
            val connection = withContext(Dispatchers.IO) { chatGptSession.connection() }
            chatGpt = chatGpt.copy(connected = connection?.credentials != null, email = connection?.email)
        }
    }

    /** Starts browser sign-in. [openBrowser] is called on the main thread with the URL to open. */
    fun signInWithChatGpt(openBrowser: (String) -> Unit) {
        if (chatGpt.signingIn) return
        chatGpt = chatGpt.copy(signingIn = true, message = "Finish signing in to ChatGPT in your browser. If the browser seems stuck after you approve, switch back to SpoolForge.", isError = false, usageLimitReached = false)
        val app = getApplication<Application>()
        // Started now, while the app is in the foreground, so the process is not frozen once the browser covers it.
        ChatGptSignInService.start(app)
        viewModelScope.launch {
            runCatching {
                try {
                    withContext(Dispatchers.IO) {
                        chatGptSession.signIn { url -> viewModelScope.launch(Dispatchers.Main) { openBrowser(url) } }
                    }
                } finally {
                    ChatGptSignInService.stop(app)
                }
            }
                .onSuccess { connection ->
                    val firstTime = !chatGptStore.welcomed
                    chatGpt = chatGpt.copy(connected = true, email = connection.email, signingIn = false, message = null, isError = false, showWelcome = firstTime)
                }
                .onFailure { chatGpt = chatGptFailure(it, connected = false) }
        }
    }

    fun cancelChatGptSignIn() { chatGptSession.cancelSignIn() }

    fun dismissChatGptWelcome() { chatGptStore.welcomed = true; chatGpt = chatGpt.copy(showWelcome = false) }

    fun disconnectChatGpt() {
        chatGpt = chatGpt.copy(busy = true)
        viewModelScope.launch {
            val revoked = withContext(Dispatchers.IO) { runCatching { chatGptSession.disconnect() }.getOrDefault(false) }
            chatGpt = ChatGptUiState(
                message = if (revoked) "Disconnected from ChatGPT. Label scans now use the local model."
                else "Local credentials were removed, but ChatGPT did not confirm the disconnect. You can also remove SpoolForge in your ChatGPT settings.",
                isError = !revoked,
            )
        }
    }

    fun clearLabelAnalysis() { labelAnalysis = LabelAnalysisUiState(); labelPhotos = emptyList(); pendingLabelFile.delete() }
    fun consumePortableImport() { portableImport = null }

    fun importSpoolmanJson(text: String) {
        runCatching { SpoolmanCodec.decodeExport(text.encodeToByteArray()) }
            .onSuccess { spoolmanImports = it; error = null }
            .onFailure { error = it.message ?: "Spoolman JSON could not be imported" }
    }

    fun consumeSpoolmanImport(item: SpoolmanImport): CustomSeed {
        spoolmanImports = spoolmanImports - item
        return item.filament.toCustomSeed().copy(
            portableInitialQuantityG = item.initialQuantityG,
            portableRemainingQuantityG = item.remainingQuantityG,
        )
    }

    fun clearSpoolmanImports() { spoolmanImports = emptyList() }

    fun importBulkCsv(text: String) {
        runCatching { parseBulkCsv(text) }
            .onSuccess { bulkImports = it; error = null }
            .onFailure { error = it.message ?: "Bulk CSV could not be imported" }
    }
    fun consumeBulkImport(item: BulkCsvRecord): CustomSeed { bulkImports = bulkImports - item; return item.seed }
    fun clearBulkImports() { bulkImports = emptyList() }

    fun spoolmanExport(item: FilamentItem): String = SpoolmanCodec.encodeExport(item.toRecord()).decodeToString()

    fun syncSpoolmanProfile(item: FilamentItem, server: String, density: String?) {
        if (spoolmanSync.busy) return
        spoolmanSync = SpoolmanSyncUiState(busy = true, message = "Connecting to Spoolman…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { spoolmanClient.syncProfile(server, item.toRecord(), density) } }
                .onSuccess { result ->
                    spoolmanServer = server.trim()
                    spoolmanPrefs.edit().putString("server", spoolmanServer).apply()
                    spoolmanSync = SpoolmanSyncUiState(message = "Spoolman filament profile ${if (result.created) "created" else "updated"} as ID ${result.filamentId}. " + result.warnings.first())
                }
                .onFailure { failure ->
                    val unknown = (failure as? SpoolmanSyncException)?.outcomeUnknown == true
                    val recovery = if (unknown) " Run Sync again to reconcile safely using the same stable external ID." else ""
                    spoolmanSync = SpoolmanSyncUiState(message = (failure.message ?: "Spoolman sync failed") + recovery, outcomeUnknown = unknown)
                }
        }
    }

    fun clearSpoolmanSyncMessage() { spoolmanSync = SpoolmanSyncUiState() }
    fun importPortableBundle(text: String) {
        if (text.toByteArray().size > 65_536) { error = "Portable bundle is limited to 64 KiB"; return }
        handlePortableDecode(PortableIdentityCodec.decode(text.encodeToByteArray()), "bundle")
    }
    private fun handlePortableDecode(decoded: PortableIdentityDecodeResult, source: String) {
        when (decoded) {
            is PortableIdentityDecodeResult.Supported -> {
                portableImport = decoded.identity.toCustomSeed()
                labelAnalysis = LabelAnalysisUiState(message = "Portable spool $source decoded locally. Review before saving.")
                labelPhotos = emptyList()
                error = null
            }
            is PortableIdentityDecodeResult.FutureVersion -> {
                val message = "This portable identity uses newer schema version ${decoded.version}. It was not imported; the original input remains unchanged."
                labelAnalysis = LabelAnalysisUiState(message = message)
                labelPhotos = emptyList()
                error = message
            }
            is PortableIdentityDecodeResult.Rejected -> {
                val message = "Portable identity rejected: ${decoded.reason}"
                labelAnalysis = LabelAnalysisUiState(message = message)
                labelPhotos = emptyList()
                error = message
            }
        }
    }
    suspend fun portableIdentity(item: FilamentItem): PortableSpoolIdentity {
        require(item.provenance != Provenance.CATALOG) { "Save this catalog candidate locally before creating a portable spool identity" }
        val record = item.toRecord()
        val ids = withContext(Dispatchers.IO) { store.filaments.portableIds(record.packageId) }
            ?: throw ValidationException("Saved spool identity is unavailable")
        return PortableSpoolIdentity(ids.profileId, ids.spoolId, record, ids.initialQuantityG, ids.remainingQuantityG)
    }
    fun loadPortableIdentity(item: FilamentItem, ready: (PortableSpoolIdentity) -> Unit) {
        viewModelScope.launch {
            runCatching { portableIdentity(item) }
                .onSuccess { error = null; ready(it) }
                .onFailure { error = it.message ?: "Portable spool identity is unavailable" }
        }
    }
    fun selectedTagCodec(): FilamentTagCodec = TagCodecRegistry.require(codecId)
    internal fun selectWriteTarget(id: String) {
        val target = WriteTargets.require(id)
        writeTarget = target
        if (target.codecId != null) { selectTagCodec(target.codecId); return }
        chooseByPrinters = true
        compatiblePrinterTargets = target.printers
        compatibilityResult = CompatibilityResolver.resolve(target.printers)
        (compatibilityResult as? CompatibilityResult.Resolved)?.let { codecId = it.resolution.codecId }
    }
    fun selectTagCodec(id:String) {
        codecId=TagCodecRegistry.require(id).format.id
        compatiblePrinterTargets=emptySet()
        compatibilityResult=CompatibilityResolver.resolve(emptySet())
        chooseByPrinters=false
    }
    fun compatibilityReady()=!chooseByPrinters||compatibilityResult is CompatibilityResult.Resolved
    fun encodedTag(item: FilamentItem): EncodedTag = selectedTagCodec().encode(item.toRecord())
    fun intent(item: FilamentItem): OpenSpoolIntent = selectedTagCodec().intent(item.toRecord())
    fun arm(item: FilamentItem) {
        val selectedCodecId=codecId
        viewModelScope.launch {
            runCatching {
                if(chooseByPrinters&&compatibilityResult !is CompatibilityResult.Resolved)throw ValidationException((compatibilityResult as CompatibilityResult.Unsupported).reason)
                val intent=TagCodecRegistry.require(selectedCodecId).intent(item.toRecord())
                val binding=if(item.provenance==Provenance.CATALOG)null else withContext(Dispatchers.IO) {
                    store.filaments.portableIds(item.entry.packageId)?.let { WriteBindingContext(it.spoolId,selectedCodecId) }
                        ?: throw ValidationException("Saved spool identity is unavailable")
                }
                nfc.arm(intent,binding)
            }.onSuccess { error=null }.onFailure { error=it.message }
        }
    }

    fun seed(item: FilamentItem): CustomSeed {
        val e = item.entry
        val values = item.values()
        return CustomSeed(
            id = if (item.provenance == Provenance.CATALOG) "edited:" + e.packageId else e.packageId,
            originalPackageId = item.originalPackageId ?: e.packageId, barcodeEvidence = item.barcodeEvidence,
            variantId = e.variantId, productId = e.productId, gtin = e.gtin, articleNumber = e.sku, additionalColorHexes = e.additionalColorHexes,
            brand = e.brand, material = e.material, product = e.product, color = e.colorName, hex = e.colorHex,
            diameter = e.diameterMm.ifBlank { DEFAULT_DIAMETER_MM }, mass = e.massG.takeIf { it > 0 }?.toString() ?: DEFAULT_NOMINAL_MASS_G,
            nozzleMin = e.nozzleMinC?.toString().orEmpty(), nozzleMax = e.nozzleMaxC?.toString().orEmpty(),
            bedMin = e.bedMinC?.toString().orEmpty(), bedMax = e.bedMaxC?.toString().orEmpty(),
            transmissionDistance = item.transmissionDistance.orEmpty(),
            sourceRevision = e.sourceRevision,
            provenance = if (item.provenance == Provenance.CATALOG) Provenance.CATALOG_EDITED else item.provenance,
            sources = item.sources, originalValues = values,
        )
    }

    fun saveCustom(seed: CustomSeed, values: Map<String, String>) {
        val normalizedHex = runCatching { normalizeHex(values["colorHex"].orEmpty()) }.getOrElse { error = it.message; return }
        val normalized = withPhysicalDefaults(values) + ("colorHex" to normalizedHex)
        fun optionalInt(key: String): Int? {
            val raw = normalized[key].orEmpty()
            if (raw.isBlank()) return null
            return raw.toIntOrNull() ?: throw ValidationException("$key must be a whole number")
        }
        val mass = runCatching { optionalInt("mass") }.getOrElse { error = it.message; return }
        val diameter = normalized["diameter"].orEmpty()
        val transmissionDistance = normalized["transmissionDistance"].orEmpty().ifBlank { null }
        val temperatures = runCatching { listOf("nozzleMin", "nozzleMax", "bedMin", "bedMax").associateWith(::optionalInt) }.getOrElse { error = it.message; return }
        fun temp(key: String) = temperatures[key]
        val record = FilamentRecord(
            seed.id ?: UUID.randomUUID().toString(), seed.variantId, seed.productId,
            FieldValue(normalized["brand"].orEmpty(), "User"), FieldValue(normalized["material"].orEmpty(), "User"),
            FieldValue(normalized["product"].orEmpty(), "User"), FieldValue(normalized["colorName"].orEmpty(), "User"),
            FieldValue(normalizedHex, "User"), FieldValue(diameter, "User"), FieldValue(mass ?: 0, "User"),
            temp("nozzleMin")?.let { FieldValue(it, "User") }, temp("nozzleMax")?.let { FieldValue(it, "User") },
            temp("bedMin")?.let { FieldValue(it, "User") }, temp("bedMax")?.let { FieldValue(it, "User") },
            seed.gtin, seed.articleNumber, seed.sourceRevision, seed.provenance, seed.additionalColorHexes.split(',').filter(String::isNotBlank), transmissionDistance?.let { FieldValue(it, "User") },
        )
        runCatching { validateEditable(record, mass) }.onFailure { error = it.message; return }
        val sources = EDITABLE_FIELDS.associateWith { key ->
            when {
                normalized[key].orEmpty().isBlank() -> seed.sources[key].orEmpty()
                seed.originalValues.isNotEmpty() && seed.originalValues[key] != normalized[key].orEmpty() -> "Edited locally"
                seed.sources[key].isNullOrBlank() && key == "diameter" && normalized[key] == DEFAULT_DIAMETER_MM -> ASSUMED_DEFAULT_SOURCE
                seed.sources[key].isNullOrBlank() && key == "mass" && normalized[key] == DEFAULT_NOMINAL_MASS_G -> ASSUMED_DEFAULT_SOURCE
                seed.sources[key].isNullOrBlank() -> "User"
                else -> seed.sources.getValue(key)
            }
        }
        val saved = CustomRecord(
            record.packageId, seed.originalPackageId, seed.variantId, seed.productId, record.brand.value, record.material.value, record.product.value, record.colorName.value,
            normalizedHex, diameter, mass ?: 0, record.nozzleMinC?.value, record.nozzleMaxC?.value, record.bedMinC?.value, record.bedMaxC?.value,
            seed.gtin, seed.articleNumber, seed.additionalColorHexes,
            seed.sourceRevision, seed.provenance.name,
            sources.getValue("brand"), sources.getValue("material"), sources.getValue("product"), sources.getValue("colorName"), sources.getValue("colorHex"),
            sources.getValue("diameter"), sources.getValue("mass"), record.nozzleMinC?.let { sources["nozzleMin"] }, record.nozzleMaxC?.let { sources["nozzleMax"] },
            record.bedMinC?.let { sources["bedMin"] }, record.bedMaxC?.let { sources["bedMax"] }, System.currentTimeMillis(), seed.barcodeEvidence,
            transmissionDistance, transmissionDistance?.let { sources["transmissionDistance"] },
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.filaments.save(saved, seed.portableProfileId, seed.portableSpoolId, seed.portableInitialQuantityG, seed.portableRemainingQuantityG) }
            }.onSuccess {
                selected = saved.asItem(); error = null
            }.onFailure {
                error = it.message ?: "Local record could not be saved"
            }
        }
    }

    fun deleteLocal(item: FilamentItem) {
        if (item.provenance == Provenance.CATALOG) { error = "Catalog records cannot be deleted"; return }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.filaments.delete(item.entry.packageId) } }
                .onSuccess { selected = null; error = null }
                .onFailure { error = it.message ?: "Local record could not be deleted" }
        }
    }

    fun decodedSeed(): CustomSeed? {
        val decoded = nfc.lastRead as? DecodeResult.Supported ?: return null
        return decoded.convertedRecord?.toCustomSeed() ?: decodedSeedFromValues(decoded.values)
    }
    override fun onCleared() { nfc.shutdown(); super.onCleared() }
}

private fun FilamentRecord.toCustomSeed(): CustomSeed {
    fun source(value: FieldValue<*>?) = value?.source.orEmpty()
    val values = mapOf(
        "brand" to brand.value, "material" to material.value, "product" to product.value, "colorName" to colorName.value,
        "colorHex" to colorHex.value, "diameter" to diameterMm.value, "mass" to nominalMassG.value.toString(),
        "nozzleMin" to nozzleMinC?.value?.toString().orEmpty(), "nozzleMax" to nozzleMaxC?.value?.toString().orEmpty(),
        "bedMin" to bedMinC?.value?.toString().orEmpty(), "bedMax" to bedMaxC?.value?.toString().orEmpty(),
        "transmissionDistance" to transmissionDistance?.value.orEmpty(),
    )
    return CustomSeed(
        id = packageId, variantId = variantId.orEmpty(), productId = productId.orEmpty(), gtin = gtin, articleNumber = sku,
        additionalColorHexes = additionalColors.joinToString(","), brand = brand.value, material = material.value, product = product.value,
        color = colorName.value, hex = colorHex.value, diameter = diameterMm.value, mass = nominalMassG.value.toString(),
        nozzleMin = values.getValue("nozzleMin"), nozzleMax = values.getValue("nozzleMax"), bedMin = values.getValue("bedMin"), bedMax = values.getValue("bedMax"),
        transmissionDistance = values.getValue("transmissionDistance"), sourceRevision = sourceRevision, provenance = provenance,
        sources = mapOf("brand" to brand.source, "material" to material.source, "product" to product.source, "colorName" to colorName.source,
            "colorHex" to colorHex.source, "diameter" to diameterMm.source, "mass" to nominalMassG.source,
            "nozzleMin" to source(nozzleMinC), "nozzleMax" to source(nozzleMaxC), "bedMin" to source(bedMinC), "bedMax" to source(bedMaxC),
            "transmissionDistance" to source(transmissionDistance)), originalValues = values,
    )
}

internal fun decodedSeedFromValues(values: Map<String, JsonValue>): CustomSeed {
    fun text(key: String) = (values[key] as? JsonValue.Str)?.value.orEmpty()
    fun number(key: String) = when (val value = values[key]) { is JsonValue.Num -> value.lexical; is JsonValue.Str -> value.value; else -> "" }
    val fields = mapOf(
        "brand" to text("brand"), "material" to text("type"), "product" to text("subtype"), "colorName" to "",
        "colorHex" to text("color_hex"), "diameter" to number("diameter").ifBlank { DEFAULT_DIAMETER_MM }, "mass" to number("weight").ifBlank { DEFAULT_NOMINAL_MASS_G },
        "nozzleMin" to number("min_temp"), "nozzleMax" to number("max_temp"), "bedMin" to number("bed_min_temp"), "bedMax" to number("bed_max_temp"),
        "transmissionDistance" to number("transmission_distance"),
    )
    val additionalColors = (values["additional_color_hexes"] as? JsonValue.Arr)?.values?.mapNotNull { (it as? JsonValue.Str)?.value }?.joinToString(",").orEmpty()
    return CustomSeed(
        additionalColorHexes = additionalColors,
        brand = fields.getValue("brand"), material = fields.getValue("material"), product = fields.getValue("product"), color = "",
        hex = fields.getValue("colorHex"), diameter = fields.getValue("diameter"), mass = fields.getValue("mass"),
        nozzleMin = fields.getValue("nozzleMin"), nozzleMax = fields.getValue("nozzleMax"), bedMin = fields.getValue("bedMin"), bedMax = fields.getValue("bedMax"),
        transmissionDistance = fields.getValue("transmissionDistance"),
        sources = fields.filterValues(String::isNotBlank).mapValues { (key, _) -> if ((key == "diameter" && "diameter" !in values) || (key == "mass" && "weight" !in values)) ASSUMED_DEFAULT_SOURCE else "OpenSpool tag" }, originalValues = fields,
    )
}

private fun validateEditable(record: FilamentRecord, mass: Int?) {
    if (record.brand.value.isBlank() || record.material.value.isBlank()) throw ValidationException("Brand and material are required")
    listOf(record.brand.value, record.material.value, record.product.value, record.colorName.value).forEach {
        if (it.length > 200 || it.any(Char::isISOControl)) throw ValidationException("Text fields must be at most 200 printable characters")
    }
    normalizeHex(record.colorHex.value)
    if (record.diameterMm.value.isNotBlank()) {
        val diameter = record.diameterMm.value.toBigDecimalOrNull() ?: throw ValidationException("Diameter must be decimal millimetres")
        if (diameter <= java.math.BigDecimal.ZERO || diameter > java.math.BigDecimal.TEN) throw ValidationException("Diameter must be in millimetres; legacy 175 is not valid")
    }
    if (mass != null && mass !in 1..100_000) throw ValidationException("Nominal mass is outside the supported range")
    fun range(name: String, min: FieldValue<Int>?, max: FieldValue<Int>?) {
        if ((min?.value ?: 0) !in 0..500 || (max?.value ?: 0) !in 0..500 || (min != null && max != null && min.value > max.value)) throw ValidationException("$name temperature range is invalid")
    }
    range("Nozzle", record.nozzleMinC, record.nozzleMaxC); range("Bed", record.bedMinC, record.bedMaxC)
    record.transmissionDistance?.let { value ->
        val td = value.value.toBigDecimalOrNull() ?: throw ValidationException("Transmission distance must be a decimal number")
        if (td < java.math.BigDecimal("0.1") || td > java.math.BigDecimal("100")) throw ValidationException("Transmission distance must be from 0.1 to 100")
    }
}

internal fun CatalogEntry.asCatalogItem(): FilamentItem {
    val source = "OFD $sourceRevision"
    val item = FilamentItem(this, Provenance.CATALOG, emptyMap())
    return item.copy(sources = item.values().mapValues { (_, value) -> if (value.isBlank()) "" else source })
}
private fun CustomRecord.asItem(): FilamentItem {
    val normalizedDiameter = diameterMm.ifBlank { DEFAULT_DIAMETER_MM }
    val normalizedMass = massG.takeIf { it > 0 } ?: DEFAULT_NOMINAL_MASS_G.toInt()
    val normalizedGtin = gtin ?: recoveredGtinFromEvidence(barcodeEvidence)
    val entry = CatalogEntry(id, variantId, productId, brand, material, product, colorName, colorHex, normalizedDiameter, normalizedMass, nozzleMinC, nozzleMaxC, bedMinC, bedMaxC, normalizedGtin, articleNumber, sourceRevision, additionalColorHexes)
    val sources = mapOf("brand" to brandSource, "material" to materialSource, "product" to productSource, "colorName" to colorNameSource, "colorHex" to colorHexSource, "diameter" to diameterSource.ifBlank { ASSUMED_DEFAULT_SOURCE }, "mass" to massSource.ifBlank { ASSUMED_DEFAULT_SOURCE }, "nozzleMin" to nozzleMinSource.orEmpty(), "nozzleMax" to nozzleMaxSource.orEmpty(), "bedMin" to bedMinSource.orEmpty(), "bedMax" to bedMaxSource.orEmpty())
    return FilamentItem(entry, runCatching { Provenance.valueOf(provenance) }.getOrDefault(Provenance.CUSTOM), sources + ("transmissionDistance" to transmissionDistanceSource.orEmpty()), originalPackageId, barcodeEvidence, transmissionDistance)
}
private fun FilamentItem.values() = mapOf(
    "brand" to entry.brand, "material" to entry.material, "product" to entry.product, "colorName" to entry.colorName,
    "colorHex" to entry.colorHex, "diameter" to entry.diameterMm.ifBlank { DEFAULT_DIAMETER_MM }, "mass" to (entry.massG.takeIf { it > 0 }?.toString() ?: DEFAULT_NOMINAL_MASS_G),
    "nozzleMin" to entry.nozzleMinC?.toString().orEmpty(), "nozzleMax" to entry.nozzleMaxC?.toString().orEmpty(),
    "bedMin" to entry.bedMinC?.toString().orEmpty(), "bedMax" to entry.bedMaxC?.toString().orEmpty(),
    "transmissionDistance" to transmissionDistance.orEmpty(),
)
internal fun FilamentItem.toRecord(): FilamentRecord {
    val e = entry
    fun text(key: String, value: String) = FieldValue(value, source(key))
    fun number(key: String, value: Int?) = value?.let { FieldValue(it, source(key)) }
    val diameter = e.diameterMm.ifBlank { DEFAULT_DIAMETER_MM }
    val mass = e.massG.takeIf { it > 0 } ?: DEFAULT_NOMINAL_MASS_G.toInt()
    return FilamentRecord(e.packageId, e.variantId, e.productId, text("brand", e.brand), text("material", e.material), text("product", e.product), text("colorName", e.colorName), text("colorHex", e.colorHex), text("diameter", diameter), FieldValue(mass, source("mass").ifBlank { ASSUMED_DEFAULT_SOURCE }), number("nozzleMin", e.nozzleMinC), number("nozzleMax", e.nozzleMaxC), number("bedMin", e.bedMinC), number("bedMax", e.bedMaxC), e.gtin, e.sku, e.sourceRevision, provenance, e.additionalColorHexes.split(',').filter(String::isNotBlank), transmissionDistance?.let { text("transmissionDistance", it) })
}

private fun PortableSpoolIdentity.toCustomSeed(): CustomSeed {
    val f = filament
    fun source(value: FieldValue<*>?) = value?.source.orEmpty()
    val values = mapOf(
        "brand" to f.brand.value, "material" to f.material.value, "product" to f.product.value, "colorName" to f.colorName.value,
        "colorHex" to f.colorHex.value, "diameter" to f.diameterMm.value, "mass" to f.nominalMassG.value.toString(),
        "nozzleMin" to f.nozzleMinC?.value?.toString().orEmpty(), "nozzleMax" to f.nozzleMaxC?.value?.toString().orEmpty(),
        "bedMin" to f.bedMinC?.value?.toString().orEmpty(), "bedMax" to f.bedMaxC?.value?.toString().orEmpty(),
        "transmissionDistance" to f.transmissionDistance?.value.orEmpty(),
    )
    return CustomSeed(
        id = f.packageId, portableProfileId = profileId, portableSpoolId = spoolId,
        portableInitialQuantityG = initialQuantityG, portableRemainingQuantityG = remainingQuantityG,
        variantId = f.variantId.orEmpty(), productId = f.productId.orEmpty(), gtin = f.gtin, articleNumber = f.sku,
        additionalColorHexes = f.additionalColors.joinToString(","), brand = f.brand.value, material = f.material.value, product = f.product.value,
        color = f.colorName.value, hex = f.colorHex.value, diameter = f.diameterMm.value, mass = f.nominalMassG.value.toString(),
        nozzleMin = values.getValue("nozzleMin"), nozzleMax = values.getValue("nozzleMax"), bedMin = values.getValue("bedMin"), bedMax = values.getValue("bedMax"),
        transmissionDistance = values.getValue("transmissionDistance"), sourceRevision = f.sourceRevision, provenance = f.provenance,
        sources = mapOf("brand" to f.brand.source, "material" to f.material.source, "product" to f.product.source, "colorName" to f.colorName.source,
            "colorHex" to f.colorHex.source, "diameter" to f.diameterMm.source, "mass" to f.nominalMassG.source,
            "nozzleMin" to source(f.nozzleMinC), "nozzleMax" to source(f.nozzleMaxC), "bedMin" to source(f.bedMinC), "bedMax" to source(f.bedMaxC),
            "transmissionDistance" to source(f.transmissionDistance)),
        originalValues = values,
    )
}
internal fun encodeSnapshot(item: FilamentItem): String {
    val values = item.values().toMutableMap()
    values["packageId"] = item.entry.packageId; values["sourceRevision"] = item.entry.sourceRevision; values["provenance"] = item.provenance.name
    values["originalPackageId"] = item.originalPackageId.orEmpty()
    values["variantId"] = item.entry.variantId; values["productId"] = item.entry.productId
    values["barcodeEvidence"] = item.barcodeEvidence
    values["transmissionDistance"] = item.transmissionDistance.orEmpty()
    values["gtin"] = item.entry.gtin.orEmpty(); values["articleNumber"] = item.entry.sku.orEmpty(); values["additionalColorHexes"] = item.entry.additionalColorHexes
    item.sources.forEach { (key, value) -> values["source_$key"] = value }
    return encodeJsonObject(values).decodeToString()
}
internal fun decodeSnapshot(json: String): FilamentItem? = runCatching {
    val obj = StrictJson(64 * 1024).parse(json.encodeToByteArray()) as JsonValue.Obj
    require(!obj.string("packageId").isNullOrBlank()) { "Legacy or invalid recent snapshot" }
    fun text(key: String) = obj.string(key).orEmpty()
    fun int(key: String) = text(key).toIntOrNull()
    val diameter = text("diameter").ifBlank { DEFAULT_DIAMETER_MM }
    val mass = int("mass")?.takeIf { it > 0 } ?: DEFAULT_NOMINAL_MASS_G.toInt()
    val evidence = text("barcodeEvidence")
    val gtin = text("gtin").ifBlank { recoveredGtinFromEvidence(evidence).orEmpty() }.ifBlank { null }
    val entry = CatalogEntry(text("packageId"), text("variantId"), text("productId"), text("brand"), text("material"), text("product"), text("colorName"), text("colorHex"), diameter, mass, int("nozzleMin"), int("nozzleMax"), int("bedMin"), int("bedMax"), gtin, text("articleNumber").ifBlank { null }, text("sourceRevision"), text("additionalColorHexes"))
    val sources = EDITABLE_FIELDS.associateWith { key -> text("source_$key").ifBlank { if (key == "diameter" || key == "mass") ASSUMED_DEFAULT_SOURCE else "" } }
    FilamentItem(entry, runCatching { Provenance.valueOf(text("provenance")) }.getOrDefault(Provenance.CUSTOM), sources, text("originalPackageId").ifBlank { null }, evidence, text("transmissionDistance").ifBlank { null })
}.getOrNull()

internal suspend fun hydrateLegacyRecent(recent: Recent, catalog: CatalogDao, user: UserDao): FilamentItem? = runCatching {
    val obj = StrictJson(16 * 1024).parse(recent.snapshotJson.encodeToByteArray()) as JsonValue.Obj
    val id = obj.string("package_id") ?: return null
    val revision = obj.string("source_revision") ?: return null
    val local = user.byId(id)
    val item = if (local != null) local.asItem() else {
        val entry = catalog.byId(id) ?: return null
        if (revision.length < 8 || !entry.sourceRevision.startsWith(revision)) return null
        entry.asCatalogItem()
    }
    user.recent(recent.copy(snapshotJson = encodeSnapshot(item)))
    item
}.getOrNull()
