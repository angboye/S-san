package com.example.sscan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sscan.feature.scan.presentation.ScanFeedbackEvent
import com.example.sscan.feature.scan.presentation.ScanFeedbackManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 扫码语音播报的判定逻辑。
 *
 * 原实现把成功语音写成：
 *   successSpeechEnabled = event != SUCCESS || !continuousScanEnabled || continuousSpeechEnabled
 * 对 SUCCESS 求值即 `false || !连续扫码 || 连续语音`。而连续扫码默认开、
 * 连续语音默认关 —— 三项全 false，导致默认配置下扫码成功根本不播报。
 * 这组测试就是钉死这个回归。
 */
@RunWith(AndroidJUnit4::class)
class ScanSpeechTest {

    private lateinit var manager: ScanFeedbackManager

    @Before
    fun setUp() {
        manager = ScanFeedbackManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun success_speaks_whenMasterSwitchOn() {
        assertTrue(
            "总开关打开时扫码成功必须播报",
            manager.shouldSpeak(ScanFeedbackEvent.SUCCESS, speechEnabled = true, duplicateSpeechEnabled = true)
        )
    }

    @Test
    fun failed_speaks_whenMasterSwitchOn() {
        assertTrue(
            manager.shouldSpeak(ScanFeedbackEvent.FAILED, speechEnabled = true, duplicateSpeechEnabled = true)
        )
    }

    @Test
    fun duplicate_followsDuplicateSwitch() {
        assertTrue(
            manager.shouldSpeak(ScanFeedbackEvent.DUPLICATE, speechEnabled = true, duplicateSpeechEnabled = true)
        )
        assertFalse(
            "重复播报子开关关闭时不应播报",
            manager.shouldSpeak(ScanFeedbackEvent.DUPLICATE, speechEnabled = true, duplicateSpeechEnabled = false)
        )
    }

    @Test
    fun masterSwitchOff_silencesEverything() {
        for (event in ScanFeedbackEvent.entries) {
            assertFalse(
                "总开关关闭时 $event 不应播报",
                manager.shouldSpeak(event, speechEnabled = false, duplicateSpeechEnabled = true)
            )
        }
    }

    /** 「扫描过快」是高频噪声，即使总开关开着也不播。 */
    @Test
    fun tooFast_neverSpeaks() {
        assertFalse(
            manager.shouldSpeak(ScanFeedbackEvent.TOO_FAST, speechEnabled = true, duplicateSpeechEnabled = true)
        )
    }

    @Test
    fun blurRejected_speaks_soUserKnowsImageWasDropped() {
        assertTrue(
            manager.shouldSpeak(ScanFeedbackEvent.BLUR_REJECTED, speechEnabled = true, duplicateSpeechEnabled = true)
        )
    }
}
