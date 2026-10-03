package net.jamesjennison.filamajignfc

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Captures one user-reviewed still; the cache file is deleted immediately after reading. */
@Composable
fun LabelCamera(onDismiss: () -> Unit, onCapture: (ByteArray, List<LabelCode>) -> Unit) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var message by remember { mutableStateOf("Fill the guide with the filament label. Include its QR code when present.") }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    var busy by remember { mutableStateOf(false) }
    val codes = remember { mutableStateMapOf<String, LabelCode>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it; if (!it) message = "Camera permission was not granted." }
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    LaunchedEffect(landscape, capture, analysisUseCase) {
        // CameraX use cases retain their original target rotation unless it is
        // updated explicitly while this activity handles configuration changes.
        withFrameNanos { }
        preview.display?.rotation?.let { rotation ->
            capture?.targetRotation = rotation
            analysisUseCase?.targetRotation = rotation
        }
    }
    val currentCapture by rememberUpdatedState(onCapture)
    val startCapture: () -> Unit = start@{
        val imageCapture = capture ?: return@start
        busy = true; message = "Capturing label…"
        val file = File.createTempFile("filamajig-label-", ".jpg", context.cacheDir)
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                // Hand the captured bytes to the ViewModel before any suspend point.
                // A configuration change must not cancel the label analysis between
                // ImageCapture completion and the durable ViewModel-owned request.
                runCatching { file.readBytes() }
                    .onSuccess { bytes ->
                        val previewCodes = codes.values.toList()
                        codes.clear()
                        file.delete(); busy = false; currentCapture(bytes, previewCodes)
                    }
                    .onFailure { file.delete(); busy = false; message = "Capture could not be read. Keep the label still and try again." }
            }
            override fun onError(error: ImageCaptureException) { file.delete(); busy = false; message = "Capture failed. Keep the label still and try again." }
        })
    }
    val header: @Composable ColumnScope.() -> Unit = {
        Text("Scan label or QR", style = MaterialTheme.typography.headlineSmall)
        Text(message)
        if (codes.isNotEmpty()) Text("Code detected: ${codes.values.joinToString { it.kind }}", style = MaterialTheme.typography.bodySmall)
    }
    val actions: @Composable ColumnScope.() -> Unit = {
        Button(enabled = permitted && capture != null && !busy, onClick = startCapture, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Saving photo…" else "Capture label and analyze")
        }
        OutlinedButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
    }
    val previewPane: @Composable BoxScope.() -> Unit = {
        if (permitted) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).fillMaxWidth(.94f).fillMaxHeight(.72f)
                .border(3.dp, Color.White, MaterialTheme.shapes.medium)
                .semantics { contentDescription = "Align the filament label inside this frame" })
        } else {
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.align(Alignment.Center)) { Text("Allow camera") }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (landscape) {
                Row(Modifier.safeDrawingPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1.35f).fillMaxHeight(), content = previewPane)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        header()
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
            } else {
                Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header()
                    Box(Modifier.weight(1f).fillMaxWidth(), content = previewPane)
                    actions()
                }
            }
        }
    }
    if (permitted) DisposableEffect(owner, preview) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val disposed = AtomicBoolean(false)
        val reader = barcodeReader()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val stillResolution = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        ).build()
        val imageCapture = ImageCapture.Builder()
            .setResolutionSelector(stillResolution)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        capture = imageCapture
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
        val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysisUseCase = analysis
        analysis.setAnalyzer(executor) { frame ->
            try {
                if (!disposed.get()) {
                    val (bytes, width, height) = frame.rotatedLuminance()
                    decodeLabelCode(reader, bytes, width, height)?.let { code -> main.execute { if (!disposed.get()) codes["${code.format}:${code.value}"] = code } }
                }
            } finally { frame.close() }
        }
        future.addListener({
            if (!disposed.get()) try { provider = future.get(); provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageCapture, analysis) }
            catch (_: Exception) { message = "Camera unavailable. Close and retry." }
        }, main)
        onDispose { disposed.set(true); capture = null; analysisUseCase = null; analysis.clearAnalyzer(); provider?.unbind(cameraPreview, imageCapture, analysis); executor.shutdownNow() }
    }
}
