package net.jamesjennison.filamajignfc

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.ResultMetadataType
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.Result
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BarcodeCameraTest {
    private fun pixels(format: BarcodeFormat, value: String, square: Boolean = false, width: Int = 800): Triple<ByteArray, Int, Int> {
        val matrix = MultiFormatWriter().encode(value, format, if (square) 500 else width, if (square) 500 else 400)
        val bytes = ByteArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0 else 255.toByte() }
        return Triple(bytes, matrix.width, matrix.height)
    }

    private fun decode(format: BarcodeFormat, value: String, square: Boolean = false, width: Int = 800): String? {
        val (bytes, width, height) = pixels(format, value, square, width)
        return decodeGtin(barcodeReader(), bytes, width, height)
    }

    @Test fun rotatesCameraLuminanceWithoutChangingPixels() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(byteArrayOf(5, 3, 1, 6, 4, 2), rotateLuminance(source, 2, 3, 90).first)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), rotateLuminance(source, 2, 3, 180).first)
        assertArrayEquals(byteArrayOf(2, 4, 6, 1, 3, 5), rotateLuminance(source, 2, 3, 270).first)
    }

    @Test fun bundledDecoderReadsKnownIndexedEan13() {
        val matrix = EAN13Writer().encode("7340002119380", BarcodeFormat.EAN_13, 800, 400)
        val pixels = ByteArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0 else 255.toByte() }
        assertEquals("07340002119380", decodeGtin(barcodeReader(), pixels, matrix.width, matrix.height))
    }

    @Test fun normalizesUpceAndGs1Code128ToGtin14() {
        assertEquals("00012345000065", normalizeDecodedBarcode(Result("01234565", null, null, BarcodeFormat.UPC_E)))
        val gs1 = Result("0107340002119380", null, null, BarcodeFormat.CODE_128).apply {
            putMetadata(ResultMetadataType.SYMBOLOGY_IDENTIFIER, "]C1")
        }
        assertEquals("07340002119380", normalizeDecodedBarcode(gs1))
    }

    @Test fun numericGtinQrIsAcceptedButArbitraryQrContentIsRejected() {
        val matrix = QRCodeWriter().encode("6938936717461", BarcodeFormat.QR_CODE, 500, 500)
        val pixels = ByteArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0 else 255.toByte() }
        assertEquals("06938936717461", decodeGtin(barcodeReader(), pixels, matrix.width, matrix.height))
        assertEquals(null, normalizeDecodedBarcode(Result("https://example.invalid/product", null, null, BarcodeFormat.QR_CODE)))
    }

    @Test fun nonGs1LinearFormatsCannotMasqueradeAsGtin() {
        val valid = "6938936717461"
        assertEquals(null, normalizeDecodedBarcode(Result(valid, null, null, BarcodeFormat.CODE_39)))
        assertEquals(null, normalizeDecodedBarcode(Result(valid, null, null, BarcodeFormat.CODABAR)))
        assertEquals(null, normalizeDecodedBarcode(Result(valid, null, null, BarcodeFormat.CODE_128)))
        assertEquals(null, normalizeDecodedBarcode(Result(valid, null, null, BarcodeFormat.ITF)))
        assertEquals("07340002119380", normalizeDecodedBarcode(Result("07340002119380", null, null, BarcodeFormat.ITF)))
        assertEquals(null, normalizeDecodedBarcode(Result("0107340002119380", null, null, BarcodeFormat.CODE_128)))
        val malformedGs1 = Result("019638936717461", null, null, BarcodeFormat.CODE_128).apply {
            putMetadata(ResultMetadataType.SYMBOLOGY_IDENTIFIER, "]C1")
        }
        assertEquals(null, normalizeDecodedBarcode(malformedGs1))
    }

    @Test fun decoderAcceptsEverySupportedRetailFormatEndToEnd() {
        assertEquals("00000096385074", decode(BarcodeFormat.EAN_8, "96385074"))
        assertEquals("00012345678905", decode(BarcodeFormat.UPC_A, "012345678905"))
        // ZXing's synthetic UPC-E writer/reader exceeds its variance tolerance when
        // integer scaling makes every module very wide; 200 px exercises the real decoder.
        assertEquals("00012345000065", decode(BarcodeFormat.UPC_E, "01234565", width = 200))
        assertEquals("07340002119380", decode(BarcodeFormat.ITF, "07340002119380"))
        // ZXing's U+00F1 escape emits the leading FNC1 that distinguishes GS1-128.
        assertEquals("07340002119380", decode(BarcodeFormat.CODE_128, "\u00f10107340002119380"))
        assertEquals(null, decode(BarcodeFormat.CODE_128, "0107340002119380"))
    }

    @Test fun capturedStillDecoderUsesExactImageAndFindsMultipleSymbols() {
        val width = 1400; val height = 600
        val pixels = IntArray(width * height) { android.graphics.Color.WHITE }
        fun place(matrix: com.google.zxing.common.BitMatrix, left: Int, top: Int) {
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) pixels[(top + y) * width + left + x] = android.graphics.Color.BLACK
        }
        place(MultiFormatWriter().encode("6938936717461", BarcodeFormat.EAN_13, 800, 300), 20, 150)
        place(MultiFormatWriter().encode("https://qr12.cn/CRDd80", BarcodeFormat.QR_CODE, 420, 420), 950, 90)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
        val codes = decodeLabelCodesFromImage(stream.toByteArray())
        assertTrue(codes.any { it.gtin == "06938936717461" })
        assertTrue(codes.any { it.kind == "QR payload" && it.value == "https://qr12.cn/CRDd80" })
    }

    @Test fun capturedStillDecoderFindsSmallMarketplaceCodeAlongsideOtherSymbols() {
        val width = 1800; val height = 1200
        val pixels = IntArray(width * height) { android.graphics.Color.WHITE }
        fun place(matrix: com.google.zxing.common.BitMatrix, left: Int, top: Int) {
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) pixels[(top + y) * width + left + x] = android.graphics.Color.BLACK
        }
        place(MultiFormatWriter().encode("6938936717461", BarcodeFormat.EAN_13, 780, 260), 40, 850)
        place(MultiFormatWriter().encode("X00444MZE3", BarcodeFormat.CODE_128, 540, 140), 1180, 80)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
        val codes = decodeLabelCodesFromImage(stream.toByteArray())
        assertTrue(codes.any { it.gtin == "06938936717461" })
        assertTrue(codes.any { it.kind == "Amazon-style identifier candidate" && it.value == "X00444MZE3" })
    }
}
