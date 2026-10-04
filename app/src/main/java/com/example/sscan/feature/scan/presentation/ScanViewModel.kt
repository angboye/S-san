package com.example.sscan.feature.scan.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.data.repository.ScanRepository
import com.example.sscan.domain.usecase.SaveScanWithImageUseCase
import com.google.mlkit.vision.barcode.common.Barcode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ScanUiState(
    val isProcessing: Boolean = false,
    val latestResults: List<String> = emptyList(),
    val lastScanTime: Long = 0L,
    val ignoredByThrottle: Boolean = false,
    val ignoredDuplicates: List<String> = emptyList(),
    val savedCount: Int = 0,
    val lastSavedImageUri: String? = null,
    val blurImageRejected: Boolean = false
)

enum class ScanDispatchResult {
    ACCEPTED,
    DUPLICATE,
    THROTTLED,
    EMPTY
}

enum class ScanFeedbackEvent {
    SUCCESS,
    FAILED,
    DUPLICATE,
    TOO_FAST,
    BLUR_REJECTED
}

sealed interface ScanUiEvent {
    data class Feedback(val event: ScanFeedbackEvent) : ScanUiEvent
    data class Error(val message: String) : ScanUiEvent
    data class ImageSaved(val imageUri: String) : ScanUiEvent
}

@HiltViewModel
// 统一管理扫码页的状态、去重、节流和保存结果反馈。
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanRepository: ScanRepository,
    private val saveScanWithImageUseCase: SaveScanWithImageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<ScanUiEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private var lastAcceptedScanTime = 0L
    private val recentResults = LinkedHashMap<String, Long>()
    private var targetGroupName: String = ""

    // 连续扫码间隔从设置读取（默认 450ms）。设置项 continuous_interval_ms 为 0 时回退到默认值。
    private val defaultThrottleWindowMs = 450L
    private val duplicateSuppressWindowMs = 5_000L
    private val maxCacheSize = 100

    // 对外暴露的唯一扫码入口：先做节流和去重，再交给保存用例。
    fun onScanResults(
        barcodes: List<Barcode>,
        capturedImageFile: File?
    ): ScanDispatchResult = onScanRawResults(extractRawResults(barcodes), capturedImageFile)

    /**
     * 与 onScanResults 同一套逻辑，但接收已提取的文本。
     * ML Kit 的 Barcode 没有公开构造器，测试无法直接构造，故留此接缝。
     */
    @androidx.annotation.VisibleForTesting
    internal fun onScanRawResults(
        rawResults: List<String>,
        capturedImageFile: File?
    ): ScanDispatchResult {
        val now = System.currentTimeMillis()
        if (isBlockedByThrottle(now)) {
            updateThrottleState()
            emitFeedback(ScanFeedbackEvent.TOO_FAST)
            return ScanDispatchResult.THROTTLED
        }

        if (rawResults.isEmpty()) {
            return ScanDispatchResult.EMPTY
        }

        pruneExpiredCache(now)
        val classifiedResults = classifyResults(rawResults, now)
        val newResults = classifiedResults.accepted
        val duplicateResults = classifiedResults.duplicates

        if (newResults.isEmpty()) {
            updateDuplicateState(duplicateResults)
            emitFeedback(ScanFeedbackEvent.DUPLICATE)
            return ScanDispatchResult.DUPLICATE
        }

        markAcceptedResults(newResults, now)
        updateProcessingState(
            acceptedResults = newResults,
            duplicateResults = duplicateResults,
            timestamp = now
        )
        // 必须在这里快照：persistScanResults 里读 targetGroupName 的位置在 isDuplicate()
        // 挂起之后，而顶部芯片随时可点——保存途中改文件夹会把这条记录写进新文件夹。
        val groupSnapshot = targetGroupName
        viewModelScope.launch {
            persistScanResults(newResults, capturedImageFile, groupSnapshot)
        }

        return ScanDispatchResult.ACCEPTED
    }

    fun onScanError(throwable: Throwable) {
        _uiState.update {
            it.copy(
                isProcessing = false,
                blurImageRejected = false
            )
        }
        emitError(throwable.message ?: "Scan failed")
        emitFeedback(ScanFeedbackEvent.FAILED)
    }

    fun setTargetGroupName(groupName: String) {
        targetGroupName = groupName.trim()
    }

    private fun isBlockedByThrottle(now: Long): Boolean {
        val throttleMs = AppSettings.getContinuousIntervalMs(context).let {
            if (it <= 0L) defaultThrottleWindowMs else it
        }
        return now - lastAcceptedScanTime < throttleMs
    }

    private fun extractRawResults(barcodes: List<Barcode>): List<String> {
        return barcodes
            .mapNotNull { it.rawValue?.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
    }

    private fun classifyResults(rawResults: List<String>, now: Long): ClassifiedResults {
        if (AppSettings.isAllowDuplicateScan(context)) {
            return ClassifiedResults(accepted = rawResults, duplicates = emptyList())
        }

        val accepted = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        rawResults.forEach { result ->
            val lastSeenAt = recentResults[result]
            if (lastSeenAt != null && now - lastSeenAt < duplicateSuppressWindowMs) {
                duplicates += result
            } else {
                accepted += result
            }
        }
        return ClassifiedResults(accepted = accepted, duplicates = duplicates)
    }

    private fun markAcceptedResults(results: List<String>, now: Long) {
        lastAcceptedScanTime = now
        results.forEach { recentResults[it] = now }
        trimCacheIfNeeded()
    }

    private fun updateThrottleState() {
        _uiState.update {
            it.copy(
                ignoredByThrottle = true,
                blurImageRejected = false
            )
        }
    }

    private fun updateDuplicateState(duplicateResults: List<String>) {
        _uiState.update {
            it.copy(
                latestResults = duplicateResults,
                ignoredByThrottle = false,
                ignoredDuplicates = duplicateResults,
                blurImageRejected = false
            )
        }
    }

    private fun updateProcessingState(
        acceptedResults: List<String>,
        duplicateResults: List<String>,
        timestamp: Long
    ) {
        _uiState.update {
            it.copy(
                isProcessing = true,
                latestResults = acceptedResults,
                lastScanTime = timestamp,
                ignoredByThrottle = false,
                ignoredDuplicates = duplicateResults,
                blurImageRejected = false
            )
        }
    }

    private suspend fun persistScanResults(
        results: List<String>,
        capturedImageFile: File?,
        groupName: String
    ) {
        try {
            val historyCheckEnabled = !AppSettings.isAllowDuplicateScan(context)
            val freshResults: List<String>
            val historicalDuplicates: List<String>
            if (historyCheckEnabled) {
                val fresh = mutableListOf<String>()
                val duplicates = mutableListOf<String>()
                results.forEach { result ->
                    if (scanRepository.isDuplicate(result)) {
                        duplicates += result
                    } else {
                        fresh += result
                    }
                }
                freshResults = fresh
                historicalDuplicates = duplicates
            } else {
                freshResults = results
                historicalDuplicates = emptyList()
            }

            if (freshResults.isEmpty()) {
                capturedImageFile?.takeIf { it.exists() }?.delete()
                _uiState.update { currentState ->
                    currentState.copy(
                        isProcessing = false,
                        ignoredDuplicates = (currentState.ignoredDuplicates + historicalDuplicates).distinct(),
                        blurImageRejected = false
                    )
                }
                emitFeedback(ScanFeedbackEvent.DUPLICATE)
                return
            }

            if (historicalDuplicates.isNotEmpty()) {
                _uiState.update { currentState ->
                    currentState.copy(
                        ignoredDuplicates = (currentState.ignoredDuplicates + historicalDuplicates).distinct()
                    )
                }
            }

            val result = saveScanWithImageUseCase(
                results = freshResults,
                capturedImageFile = capturedImageFile,
                groupName = groupName,
                forceSaveImage = groupName.isNotBlank()
            )
            _uiState.update { currentState ->
                currentState.copy(
                    isProcessing = false,
                    savedCount = currentState.savedCount + result.savedCount,
                    lastSavedImageUri = result.imageUri,
                    blurImageRejected = result.blurRejected
                )
            }
            emitFeedback(
                if (result.blurRejected) {
                    ScanFeedbackEvent.BLUR_REJECTED
                } else {
                    ScanFeedbackEvent.SUCCESS
                }
            )
            if (!result.imageUri.isNullOrBlank()) {
                _events.emit(ScanUiEvent.ImageSaved(result.imageUri))
            }
        } catch (throwable: Throwable) {
            capturedImageFile?.takeIf { it.exists() }?.delete()
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    blurImageRejected = false
                )
            }
            emitError(throwable.message ?: "Save scan result failed")
            emitFeedback(ScanFeedbackEvent.FAILED)
        }
    }

    private fun pruneExpiredCache(now: Long) {
        val iterator = recentResults.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= duplicateSuppressWindowMs) {
                iterator.remove()
            }
        }
    }

    private fun trimCacheIfNeeded() {
        while (recentResults.size > maxCacheSize) {
            val oldestKey = recentResults.entries.firstOrNull()?.key ?: return
            recentResults.remove(oldestKey)
        }
    }

    private fun emitFeedback(event: ScanFeedbackEvent) {
        _events.tryEmit(ScanUiEvent.Feedback(event))
    }

    private fun emitError(message: String) {
        _events.tryEmit(ScanUiEvent.Error(message))
    }

    private data class ClassifiedResults(
        val accepted: List<String>,
        val duplicates: List<String>
    )

}
