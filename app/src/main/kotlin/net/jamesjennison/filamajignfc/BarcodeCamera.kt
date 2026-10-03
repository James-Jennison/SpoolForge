package net.jamesjennison.filamajignfc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.oned.UPCEReader
import net.jamesjennison.filamajignfc.core.Gtin
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera frames stay in memory and are decoded locally. No capture, storage, telemetry or upload. */
@Composable
fun BarcodeCamera(onDismiss: () -> Unit, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var message by remember { mutableStateOf("Place the retail barcode or numeric GTIN QR inside the guide.") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it; if (!it) message = "Camera permission was not granted. You can still enter the barcode manually." }
    val currentResult by rememberUpdatedState(onBarcode)
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scan filament barcode", style = MaterialTheme.typography.headlineSmall)
                Text(message)
                if (permitted) Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.Center).fillMaxWidth(.92f).height(180.dp)
                            .border(3.dp, Color.White, MaterialTheme.shapes.medium)
                    )
                }
                else Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel / enter manually") }
            }
        }
    }
    if (permitted) DisposableEffect(owner, preview) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val reader = barcodeReader()
        val future = ProcessCameraProvider.getInstance(context)
        val disposed = AtomicBoolean(false)
        var delivered = false
        var provider: ProcessCameraProvider? = null
        val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        ).build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var frames = 0
        analysis.setAnalyzer(analysisExecutor) { frame ->
            try {
                if (!disposed.get() && !delivered) {
                    val (bytes, width, height) = frame.rotatedLuminance()
                    val raw = decodeGtin(reader, bytes, width, height)
                    frames++
                    if (frames == 1 || frames % 90 == 0) {
                        Log.d("SpoolForgeScanner", "analyzing frame=$frames ${width}x$height rotation=${frame.imageInfo.rotationDegrees}")
                        mainExecutor.execute {
                            if (!disposed.get() && !delivered) message = "Camera active · scanning ($frames frames). Keep the full barcode inside the preview."
                        }
                    }
                    if (raw != null) {
                        Log.d("SpoolForgeScanner", "decoded a valid GTIN after $frames frames")
                        delivered = true
                        mainExecutor.execute { if (!disposed.get()) currentResult(raw) }
                    }
                }
            } finally { frame.close() }
        }
        future.addListener({
            if (!disposed.get()) try {
                provider = future.get()
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            } catch (_: Exception) { message = "Camera unavailable. Close and retry, or enter the barcode manually." }
        }, mainExecutor)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(cameraPreview, analysis)
            analysisExecutor.shutdownNow()
        }
    }
}

internal fun ImageProxy.rotatedLuminance(): Triple<ByteArray, Int, Int> {
    val plane = planes[0]
    val crop = cropRect
    val source = ByteArray(crop.width() * crop.height())
    val buffer = plane.buffer.duplicate()
    val base = buffer.position()
    for (y in 0 until crop.height()) for (x in 0 until crop.width()) {
        source[y * crop.width() + x] = buffer.get(base + (crop.top + y) * plane.rowStride + (crop.left + x) * plane.pixelStride)
    }
    return rotateLuminance(source, crop.width(), crop.height(), imageInfo.rotationDegrees)
}

internal fun rotateLuminance(source: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> = when (degrees) {
        0 -> Triple(source, width, height)
        90 -> Triple(ByteArray(source.size).also { out -> for (y in 0 until height) for (x in 0 until width) out[x * height + height - 1 - y] = source[y * width + x] }, height, width)
        180 -> Triple(ByteArray(source.size).also { out -> for (i in source.indices) out[source.lastIndex - i] = source[i] }, width, height)
        270 -> Triple(ByteArray(source.size).also { out -> for (y in 0 until height) for (x in 0 until width) out[(width - 1 - x) * height + y] = source[y * width + x] }, height, width)
        else -> Triple(source, width, height)
}

internal fun barcodeReader() = MultiFormatReader().apply {
    setHints(mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.ITF, BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE,
        ),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ASSUME_GS1 to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    ))
}

internal fun decodeGtin(reader: MultiFormatReader, bytes: ByteArray, width: Int, height: Int): String? {
    val full = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    val sources = buildList {
        add(full)
        // Smaller regions make thin package barcodes occupy more of ZXing's scan rows.
        if (width >= 8 && height >= 8) {
            add(full.crop(width / 10, 0, width * 8 / 10, height))
            add(full.crop(0, height / 5, width, height * 3 / 5))
        }
    }
    for (source in sources) {
        decode(reader, source, false)?.let { return normalizeDecodedBarcode(it) }
        decode(reader, source, true)?.let { return normalizeDecodedBarcode(it) }
    }
    return null
}

internal fun decodeLabelCode(reader: MultiFormatReader, bytes: ByteArray, width: Int, height: Int): LabelCode? {
    val full = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    val sources = buildList {
        add(full)
        if (width >= 8 && height >= 8) {
            add(full.crop(width / 10, 0, width * 8 / 10, height))
            add(full.crop(0, height / 5, width, height * 3 / 5))
        }
    }
    for (source in sources) {
        for (global in listOf(false, true)) {
            val result = decode(reader, source, global) ?: continue
            return classifyLabelCode(result.text, result.barcodeFormat.name, normalizeDecodedBarcode(result))
        }
    }
    return null
}

/** Decode evidence from the exact captured still, including multiple symbols when present. */
internal fun decodeLabelCodesFromImage(bytes: ByteArray): List<LabelCode> {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
    var width = bitmap.width
    var height = bitmap.height
    var pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
    bitmap.recycle()
    val found = linkedMapOf<String, LabelCode>()
    repeat(4) {
        val source = RGBLuminanceSource(width, height, pixels)
        for (region in capturedStillRegions(source)) {
            for (global in listOf(false, true)) {
                val binarizer = if (global) GlobalHistogramBinarizer(region) else HybridBinarizer(region)
                val bitmap = BinaryBitmap(binarizer)
                val results = runCatching { GenericMultipleBarcodeReader(barcodeReader()).decodeMultiple(bitmap) }.getOrDefault(emptyArray())
                val single = runCatching { barcodeReader().decode(bitmap) }.getOrNull()
                (results.asList() + listOfNotNull(single)).forEach { result ->
                    val code = classifyLabelCode(result.text, result.barcodeFormat.name, normalizeDecodedBarcode(result))
                    found["${code.format}:${code.value}"] = code
                }
            }
        }
        val rotated = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) rotated[x * height + height - 1 - y] = pixels[y * width + x]
        pixels = rotated; val oldWidth = width; width = height; height = oldWidth
    }
    return found.values.toList()
}

/** Overlapping regions let a small linear code occupy enough scan rows in a full label photo. */
internal fun capturedStillRegions(source: LuminanceSource): List<LuminanceSource> = buildList {
    add(source)
    if (!source.isCropSupported || source.width < 12 || source.height < 12) return@buildList
    val width = source.width
    val height = source.height
    add(source.crop(0, 0, width, height * 3 / 5))
    add(source.crop(0, height / 5, width, height * 3 / 5))
    add(source.crop(0, height * 2 / 5, width, height * 3 / 5))
    add(source.crop(0, 0, width * 2 / 3, height))
    add(source.crop(width / 3, 0, width * 2 / 3, height))
    add(source.crop(0, 0, width * 2 / 3, height * 2 / 3))
    add(source.crop(width / 3, 0, width * 2 / 3, height * 2 / 3))
    add(source.crop(0, height / 3, width * 2 / 3, height * 2 / 3))
    add(source.crop(width / 3, height / 3, width * 2 / 3, height * 2 / 3))
}

private fun decode(reader: MultiFormatReader, source: LuminanceSource, global: Boolean): Result? = try {
    val binarizer = if (global) GlobalHistogramBinarizer(source) else HybridBinarizer(source)
    reader.decodeWithState(BinaryBitmap(binarizer))
} catch (_: Exception) { null } finally { reader.reset() }

internal fun normalizeDecodedBarcode(result: Result): String? {
    val text = result.text.trim()
    if (result.barcodeFormat == BarcodeFormat.UPC_E) {
        Gtin.normalize(UPCEReader.convertUPCEtoUPCA(text))?.let { return it }
    }
    if (result.barcodeFormat in setOf(BarcodeFormat.EAN_8, BarcodeFormat.EAN_13, BarcodeFormat.UPC_A, BarcodeFormat.QR_CODE)) {
        return Gtin.normalize(text)
    }
    if (result.barcodeFormat == BarcodeFormat.ITF) {
        return text.takeIf { it.length == 14 }?.let(Gtin::normalize)
    }
    if (result.barcodeFormat == BarcodeFormat.CODE_128) {
        // Only a Code 128 symbol whose decoded metadata proves a leading FNC1 is GS1-128.
        // This intentionally accepts a standalone AI (01), rather than guessing where a
        // following variable-length application identifier might begin.
        if (result.resultMetadata?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER) != "]C1") return null
        val compact = text.replace("\u001d", "").removePrefix("]C1")
        val digits = compact.removePrefix("01")
        if (compact.startsWith("01") && digits.length == 14 && digits.all(Char::isDigit)) {
            Gtin.normalize(digits)?.let { return it }
        }
    }
    return null
}
