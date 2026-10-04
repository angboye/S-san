package com.example.sscan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sscan.core.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「扫码抓取慢」真凶的回归防线。
 *
 * 原来的 bug：getScanThrottleMs 优先读 KEY_CONTINUOUS_INTERVAL_MS(默认 800ms)，
 * 而 setContinuousIntervalMs 又反写 KEY_SCAN_THROTTLE_MS —— 双向污染。
 * 用户在设置里调过一次「连续扫码间隔」，ML Kit 分析器节流就变成 800ms，
 * 每秒只识别 1.25 帧。
 *
 * 这两个参数语义完全不同，必须保持解耦：
 *   连续扫码间隔 = 连续模式下两次「保存」之间的业务间隔，800ms 合理
 *   分析器节流   = 每隔多久跑一次 ML Kit 识别，800ms 是灾难
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsThrottleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        context.getSharedPreferences("s_scan_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun putRawThrottle(value: Long) {
        context.getSharedPreferences("s_scan_settings", Context.MODE_PRIVATE)
            .edit().putLong("scan_throttle_ms", value).commit()
    }

    @Test
    fun defaults_areIndependent() {
        assertEquals("分析器节流默认应为 0(不主动丢帧)", 0L, AppSettings.getScanThrottleMs(context))
        assertEquals("连续扫码间隔默认 800ms", 800L, AppSettings.getContinuousIntervalMs(context))
    }

    /** 核心回归：调连续扫码间隔，绝不能拖慢分析器。 */
    @Test
    fun settingContinuousInterval_doesNotAffectAnalyzerThrottle() {
        AppSettings.setContinuousIntervalMs(context, 1500L)

        assertEquals(1500L, AppSettings.getContinuousIntervalMs(context))
        assertEquals(
            "设了连续扫码间隔后，分析器节流必须仍是默认值 —— 这正是『扫码慢』的根因",
            0L,
            AppSettings.getScanThrottleMs(context)
        )
    }

    /** 反方向同样不能污染。 */
    @Test
    fun settingAnalyzerThrottle_doesNotAffectContinuousInterval() {
        AppSettings.setScanThrottleMs(context, 33L)

        assertEquals(33L, AppSettings.getScanThrottleMs(context))
        assertEquals(800L, AppSettings.getContinuousIntervalMs(context))
    }

    @Test
    fun bothKeys_canHoldDifferentValuesSimultaneously() {
        AppSettings.setContinuousIntervalMs(context, 1000L)
        AppSettings.setScanThrottleMs(context, 50L)

        assertEquals(1000L, AppSettings.getContinuousIntervalMs(context))
        assertEquals(50L, AppSettings.getScanThrottleMs(context))
    }

    /** 老用户的值已被污染，启动时应被清洗。 */
    @Test
    fun sanitizeLegacyThrottle_resetsPollutedValue() {
        putRawThrottle(800L)
        AppSettings.sanitizeLegacyThrottle(context)
        assertEquals("被连续扫码间隔污染过的值应重置", 0L, AppSettings.getScanThrottleMs(context))
    }

    @Test
    fun sanitizeLegacyThrottle_keepsReasonableValue() {
        putRawThrottle(50L)
        AppSettings.sanitizeLegacyThrottle(context)
        assertEquals("合理范围内的值不应被改动", 50L, AppSettings.getScanThrottleMs(context))
    }

    @Test
    fun sanitizeLegacyThrottle_boundaryAt200() {
        putRawThrottle(200L)
        AppSettings.sanitizeLegacyThrottle(context)
        assertEquals("200 是边界，不应被重置", 200L, AppSettings.getScanThrottleMs(context))

        putRawThrottle(201L)
        AppSettings.sanitizeLegacyThrottle(context)
        assertEquals("超过 200 应被重置", 0L, AppSettings.getScanThrottleMs(context))
    }
}
