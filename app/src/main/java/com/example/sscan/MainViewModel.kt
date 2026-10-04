package com.example.sscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sscan.data.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeStatsUiState(
    val todayCount: Int = 0,
    val totalCount: Int = 0,
    val imageCount: Int = 0,
    // 保留供后续趋势视图使用；纯函数 percentChange 由 MainStatsTest 覆盖
    val todayDeltaPercent: Double? = null,
    val totalDeltaPercent: Double? = null,
    val imageDeltaPercent: Double? = null
)

/**
 * 首页统计卡的数据源。首页此前不注入任何依赖，统计只能写死；
 * 接入仓库后三个数字与「较昨日」对比行才是真实的。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val scanRepository: ScanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeStatsUiState())
    val uiState: StateFlow<HomeStatsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            scanRepository.observeHistory().collect { records ->
                val todayStart = startOfToday()
                val yesterdayStart = todayStart - DAY_MILLIS

                val today = records.count { it.time >= todayStart }
                val yesterday = records.count { it.time in yesterdayStart until todayStart }
                val totalBeforeToday = records.count { it.time < todayStart }
                val imageToday = records.count { it.time >= todayStart && it.imageUri.isNotBlank() }
                val imageYesterday = records.count {
                    it.time in yesterdayStart until todayStart && it.imageUri.isNotBlank()
                }

                _uiState.value = HomeStatsUiState(
                    todayCount = today,
                    totalCount = records.size,
                    imageCount = records.count { it.imageUri.isNotBlank() },
                    todayDeltaPercent = percentChange(today, yesterday),
                    totalDeltaPercent = percentChange(records.size, totalBeforeToday),
                    imageDeltaPercent = percentChange(imageToday, imageYesterday)
                )
            }
        }
    }

    /** 基数为 0 时百分比没有意义，返回 null 让 UI 显示占位。 */
    @androidx.annotation.VisibleForTesting
    internal fun percentChange(current: Int, previous: Int): Double? {
        if (previous <= 0) return null
        return (current - previous) * 100.0 / previous
    }

    private fun startOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
