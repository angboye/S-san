package com.example.sscan.core.scanner

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode

enum class ScanCodeMode {
    ALL,
    QR_ONLY,
    BARCODE_ONLY;

    companion object {
        fun fromName(value: String?): ScanCodeMode {
            return entries.firstOrNull { it.name == value } ?: ALL
        }
    }
}

object MlKitProvider {
    fun createScanner(
        mode: ScanCodeMode,
        zoomCallback: ((Float) -> Boolean)? = null,
        maxSupportedZoomRatio: Float? = null
    ): BarcodeScanner {
        val builder = BarcodeScannerOptions.Builder()

        when (mode) {
            ScanCodeMode.ALL -> Unit
            ScanCodeMode.QR_ONLY -> {
                builder.setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            }

            ScanCodeMode.BARCODE_ONLY -> {
                builder.setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E
                )
            }
        }

        if (zoomCallback != null) {
            val zoomSuggestionOptions = ZoomSuggestionOptions.Builder(zoomCallback).apply {
                if (maxSupportedZoomRatio != null) {
                    setMaxSupportedZoomRatio(maxSupportedZoomRatio)
                }
            }.build()
            builder.setZoomSuggestionOptions(zoomSuggestionOptions)
        }

        return BarcodeScanning.getClient(builder.build())
    }
}
