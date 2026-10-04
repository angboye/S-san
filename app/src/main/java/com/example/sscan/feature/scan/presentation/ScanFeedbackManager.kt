package com.example.sscan.feature.scan.presentation

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import java.util.Locale

class ScanFeedbackManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var ttsReady = false
    private var textToSpeech: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null

    /** 初始化完成前到达的播报请求，等引擎就绪后补播一次。 */
    private var pendingSpeech: String? = null

    fun initialize() {
        textToSpeech = TextToSpeech(context, this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            Log.w(TAG, "TTS 引擎初始化失败: status=$status")
            return
        }
        ttsReady = applyBestAvailableLanguage()
        if (ttsReady) {
            pendingSpeech?.let { speak(it) }
        }
        pendingSpeech = null
    }

    /**
     * 依次尝试：应用当前语言 -> 简体中文 -> 英文 -> 引擎默认。
     *
     * 原实现直接 `textToSpeech.language = Locale.CHINA` 且不看返回值，
     * 设备没装中文语音数据时 speak() 会静默失败，而外部还以为一切正常。
     */
    private fun applyBestAvailableLanguage(): Boolean {
        val tts = textToSpeech ?: return false
        val candidates = listOfNotNull(
            context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0),
            Locale.SIMPLIFIED_CHINESE,
            Locale.US
        )
        for (locale in candidates) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.i(TAG, "TTS 使用语言: $locale")
                return true
            }
        }
        Log.w(TAG, "TTS 无可用语音数据，语音播报将不可用")
        return false
    }

    fun performFeedback(
        event: ScanFeedbackEvent,
        message: String,
        toneEnabled: Boolean,
        vibrationEnabled: Boolean,
        speechEnabled: Boolean,
        duplicateSpeechEnabled: Boolean
    ) {
        if (message.isNotBlank()) {
            if (event == ScanFeedbackEvent.SUCCESS) {
                showToast(message)
            }
            if (shouldSpeak(event, speechEnabled, duplicateSpeechEnabled)) {
                speak(message)
            }
        }

        val isAudibleEvent = event == ScanFeedbackEvent.SUCCESS ||
            event == ScanFeedbackEvent.BLUR_REJECTED
        if (toneEnabled && isAudibleEvent) {
            playTone(event)
        }
        if (vibrationEnabled && isAudibleEvent) {
            vibrateOnce()
        }
    }

    /**
     * 根据事件类型播放不同提示音：
     * - 成功：短促清脆的双音（上行），辨识度高
     * - 模糊拒绝：单声短音，提示重新对焦
     */
    private fun playTone(event: ScanFeedbackEvent) {
        when (event) {
            ScanFeedbackEvent.SUCCESS -> {
                // 先高后低两声，短促清脆
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 120)
            }
            ScanFeedbackEvent.BLUR_REJECTED -> {
                // 单声低沉短音，提示模糊
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }
            else -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
            }
        }
    }

    /**
     * 播报判定。总开关关闭则一律不播；「扫描过快」是高频噪声，不播。
     * 连续扫码模式下成功也照常播报——用户不用盯屏幕就知道有没有扫上。
     */
    @androidx.annotation.VisibleForTesting
    internal fun shouldSpeak(
        event: ScanFeedbackEvent,
        speechEnabled: Boolean,
        duplicateSpeechEnabled: Boolean
    ): Boolean {
        if (!speechEnabled) return false
        return when (event) {
            ScanFeedbackEvent.SUCCESS,
            ScanFeedbackEvent.FAILED,
            ScanFeedbackEvent.BLUR_REJECTED -> true
            ScanFeedbackEvent.DUPLICATE -> duplicateSpeechEnabled
            ScanFeedbackEvent.TOO_FAST -> false
        }
    }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun speak(message: String) {
        if (message.isBlank()) return
        if (!ttsReady) {
            // 引擎还没就绪，先记下，onInit 成功后补播
            pendingSpeech = message
            return
        }
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun vibrateOnce() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60L)
        }
    }

    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        toneGenerator?.release()
        toneGenerator = null
        ttsReady = false
    }

    private companion object {
        const val TAG = "ScanFeedback"
        const val UTTERANCE_ID = "scan_feedback"
    }
}
