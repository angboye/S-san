package com.example.sscan.feature.history.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sscan.R
import com.example.sscan.data.local.entity.ScanHistoryEntity
import com.example.sscan.data.repository.ScanRepository
import com.example.sscan.domain.usecase.MoveHistoryToTrashUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryTimeRange {
    ALL, TODAY, YESTERDAY, THIS_MONTH, LAST_MONTH, LAST_7_DAYS, LAST_30_DAYS, CUSTOM
}

enum class HistoryContentType {
    ALL, WITH_IMAGE, TEXT_ONLY
}

data class HistoryItemUi(
    val id: Long,
    val title: String,
    val result: String,
    val imageUri: String,
    val timeLabel: String,
    val hasImage: Boolean,
    val note: String,
    val groupName: String,
    val timeMillis: Long
)

data class HistoryUiState(
    val items: List<HistoryItemUi> = emptyList(),
    val totalCount: Int = 0,
    val matchedCount: Int = 0,
    val todayCount: Int = 0,
    val imageSavedCount: Int = 0,
    val searchQuery: String = "",
    val selectedRange: HistoryTimeRange = HistoryTimeRange.ALL,
    val isEmpty: Boolean = true,
    val message: String? = null,
    val activeFilterDesc: String = ""
)

data class FilterSnapshot(
    val range: HistoryTimeRange,
    val startMillis: Long?,
    val endMillis: Long?,
    val type: HistoryContentType
)

private fun HistoryTimeRange.label(context: Context): String = when (this) {
    HistoryTimeRange.ALL -> context.getString(R.string.history_filter_all)
    HistoryTimeRange.TODAY -> context.getString(R.string.history_filter_shortcut_today)
    HistoryTimeRange.YESTERDAY -> context.getString(R.string.history_filter_shortcut_yesterday)
    HistoryTimeRange.THIS_MONTH -> context.getString(R.string.history_filter_shortcut_this_month)
    HistoryTimeRange.LAST_MONTH -> context.getString(R.string.history_filter_shortcut_last_month)
    HistoryTimeRange.LAST_7_DAYS -> context.getString(R.string.history_filter_shortcut_last_7)
    HistoryTimeRange.LAST_30_DAYS -> context.getString(R.string.history_filter_shortcut_last_30)
    HistoryTimeRange.CUSTOM -> context.getString(R.string.history_filter_custom)
}

private fun HistoryContentType.label(context: Context): String = when (this) {
    HistoryContentType.ALL -> context.getString(R.string.history_filter_content_all)
    HistoryContentType.WITH_IMAGE -> context.getString(R.string.history_filter_content_image)
    HistoryContentType.TEXT_ONLY -> context.getString(R.string.history_filter_content_text)
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    private val moveHistoryToTrashUseCase: MoveHistoryToTrashUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    /** 全部记录（非过滤），供编辑模式使用 */
    private val _allRecords = MutableStateFlow<List<ScanHistoryEntity>>(emptyList())
    val allRecords: StateFlow<List<ScanHistoryEntity>> = _allRecords.asStateFlow()
    private val searchQuery = MutableStateFlow("")
    private val timeRange = MutableStateFlow(HistoryTimeRange.ALL)
    private val customStartMillis = MutableStateFlow<Long?>(null)
    private val customEndMillis = MutableStateFlow<Long?>(null)
    private val contentType = MutableStateFlow(HistoryContentType.ALL)

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        viewModelScope.launch {
            combine(
                searchQuery,
                timeRange,
                customStartMillis,
                customEndMillis,
                contentType
            ) { query, range, start, end, type ->
                val resolved = resolveStartEnd(range, start, end)
                HistoryFilter(
                    query = query.trim(),
                    range = range,
                    startTime = resolved.first,
                    endTime = resolved.second,
                    contentType = type
                )
            }.flatMapLatest { filter ->
                combine(
                    scanRepository.observeHistory(),
                    scanRepository.observeHistoryFiltered(filter.query, filter.startTime)
                ) { allItems, rawFilteredItems ->
                    val filteredByTime = if (filter.endTime != null) {
                        rawFilteredItems.filter { it.time <= filter.endTime }
                    } else {
                        rawFilteredItems
                    }
                    val finalFiltered = when (filter.contentType) {
                        HistoryContentType.ALL -> filteredByTime
                        HistoryContentType.WITH_IMAGE -> filteredByTime.filter { it.imageUri.isNotBlank() }
                        HistoryContentType.TEXT_ONLY -> filteredByTime.filter { it.imageUri.isBlank() }
                    }
                    HistorySourceState(
                        allItems = allItems,
                        filteredItems = finalFiltered,
                        filter = filter
                    )
                }
            }.collect { source ->
                _allRecords.value = source.allItems
                _uiState.update { current ->
                    source.toUiState(
                        message = current.message
                    )
                }
            }
        }
    }

    private fun resolveStartEnd(
        range: HistoryTimeRange,
        customStart: Long?,
        customEnd: Long?,
        now: Long = System.currentTimeMillis()
    ): Pair<Long?, Long?> {
        return when (range) {
            HistoryTimeRange.ALL -> Pair(null, null)
            HistoryTimeRange.CUSTOM -> Pair(customStart, customEnd)
            HistoryTimeRange.TODAY -> Pair(startOfDay(now), endOfDay(now))
            HistoryTimeRange.YESTERDAY -> {
                val yesterday = now - 24L * 60L * 60L * 1000L
                Pair(startOfDay(yesterday), endOfDay(yesterday))
            }

            HistoryTimeRange.THIS_MONTH -> Pair(startOfMonth(now), endOfMonth(now))
            HistoryTimeRange.LAST_MONTH -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.MONTH, -1)
                }
                Pair(startOfMonth(cal.timeInMillis), endOfMonth(cal.timeInMillis))
            }

            HistoryTimeRange.LAST_7_DAYS -> Pair(now - 7L * 24L * 60L * 60L * 1000L, now)
            HistoryTimeRange.LAST_30_DAYS -> Pair(now - 30L * 24L * 60L * 60L * 1000L, now)
        }
    }

    private fun startOfDay(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfDay(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun startOfMonth(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfMonth(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query.trim()
    }

    fun applyFilter(
        range: HistoryTimeRange,
        startMillis: Long?,
        endMillis: Long?,
        type: HistoryContentType
    ) {
        timeRange.value = range
        customStartMillis.value = startMillis
        customEndMillis.value = endMillis
        contentType.value = type
    }

    fun resetFilter() {
        timeRange.value = HistoryTimeRange.ALL
        customStartMillis.value = null
        customEndMillis.value = null
        contentType.value = HistoryContentType.ALL
    }

    fun getCurrentFilter(): FilterSnapshot {
        return FilterSnapshot(
            range = timeRange.value,
            startMillis = customStartMillis.value,
            endMillis = customEndMillis.value,
            type = contentType.value
        )
    }

    fun moveToTrash(id: Long) {
        viewModelScope.launch {
            moveHistoryToTrashUseCase(id)
        }
    }

    /** 更新某条记录的扫码结果文本 */
    fun updateHistoryResult(id: Long, newResult: String) {
        viewModelScope.launch {
            scanRepository.updateHistoryResult(id, newResult)
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ===== 编辑模式操作 =====

    fun renameGroup(oldName: String, newName: String) {
        viewModelScope.launch {
            scanRepository.renameGroup(oldName, newName)
        }
    }

    fun createGroup(groupName: String) {
        viewModelScope.launch {
            scanRepository.createGroup(groupName)
        }
    }

    fun moveToGroup(groupName: String, ids: List<Long>) {
        viewModelScope.launch {
            scanRepository.moveToGroup(groupName, ids)
        }
    }

    fun moveSelectedToTrash(ids: List<Long>) {
        viewModelScope.launch {
            scanRepository.moveToTrashByIds(ids)
        }
    }

    fun deleteGroup(groupName: String, ids: List<Long>) {
        viewModelScope.launch {
            scanRepository.deleteGroup(groupName, ids)
        }
    }

    suspend fun getAllGroupNames(): List<String> {
        return scanRepository.getAllGroupNames()
    }

    private fun HistorySourceState.toUiState(message: String?): HistoryUiState {
        val mappedItems = filteredItems.map { mapHistoryItem(it) }

        return HistoryUiState(
            items = mappedItems,
            totalCount = allItems.size,
            matchedCount = mappedItems.size,
            todayCount = allItems.count(::isTodayRecord),
            imageSavedCount = allItems.count { it.imageUri.isNotBlank() },
            searchQuery = filter.query,
            selectedRange = filter.range,
            isEmpty = mappedItems.isEmpty(),
            activeFilterDesc = filter.description(context),
            message = message
        )
    }

    private fun mapHistoryItem(entity: ScanHistoryEntity): HistoryItemUi {
        return HistoryItemUi(
            id = entity.id,
            title = entity.customTitle.ifBlank { entity.result },
            result = entity.result,
            imageUri = entity.imageUri,
            timeLabel = formatter.format(Date(entity.time)),
            hasImage = entity.imageUri.isNotBlank(),
            note = entity.customTitle,
            groupName = entity.groupName,
            timeMillis = entity.time
        )
    }

    private fun isTodayRecord(entity: ScanHistoryEntity): Boolean {
        val todayPrefix = dayFormatter.format(Date())
        return formatter.format(Date(entity.time)).startsWith(todayPrefix)
    }

    private data class HistorySourceState(
        val allItems: List<ScanHistoryEntity>,
        val filteredItems: List<ScanHistoryEntity>,
        val filter: HistoryFilter
    )

    private data class HistoryFilter(
        val query: String,
        val range: HistoryTimeRange,
        val startTime: Long?,
        val endTime: Long?,
        val contentType: HistoryContentType
    ) {
        fun description(context: Context): String = buildString {
            if (range != HistoryTimeRange.ALL) {
                append(range.label(context))
            }
            if (contentType != HistoryContentType.ALL) {
                if (isNotEmpty()) append(" / ")
                append(contentType.label(context))
            }
        }
    }
}
