package com.example.sscan.feature.auth.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sscan.R
import com.example.sscan.core.auth.SmsResult
import com.example.sscan.core.auth.SmsService
import com.example.sscan.core.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 登录页面 ViewModel。
 *
 * 职责：
 *   1. 管理手机号输入、验证码输入；
 *   2. 通过 SmsService 发送验证码并校验；
 *   3. 校验通过后写入登录态；
 *   4. 维护倒计时（60s 内不允许重复发送）。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val smsService: SmsService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(capacity = Channel.CONFLATED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    private var countdownJob: Job? = null

    /** 用户输入手机号时调用。 */
    fun onPhoneChanged(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone.trim())
    }

    /** 用户输入验证码时调用。 */
    fun onCodeChanged(code: String) {
        _uiState.value = _uiState.value.copy(code = code.trim())
    }

    /** 点击“发送验证码”时调用。 */
    fun sendCode() {
        if (_uiState.value.isSendingCode) return
        if (_uiState.value.isCountingDown) return

        val phone = _uiState.value.phone
        if (phone.isBlank()) {
            _events.trySend(LoginEvent.ShowError(context.getString(R.string.login_phone_empty)))
            return
        }

        _uiState.value = _uiState.value.copy(isSendingCode = true)

        viewModelScope.launch {
            when (val result = smsService.sendCode(phone)) {
                is SmsResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSendingCode = false,
                        isCountingDown = true
                    )
                    _events.trySend(
                        LoginEvent.CodeSent(
                            result.message ?: context.getString(R.string.login_code_sent)
                        )
                    )
                    startCountdown()
                }

                is SmsResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isSendingCode = false)
                    _events.trySend(LoginEvent.ShowError(result.message))
                }
            }
        }
    }

    /** 点击“登录”时调用。 */
    fun login(applicationContext: android.content.Context) {
        if (_uiState.value.isLoggingIn) return

        val phone = _uiState.value.phone
        val code = _uiState.value.code
        if (phone.isBlank()) {
            _events.trySend(LoginEvent.ShowError(context.getString(R.string.login_phone_empty)))
            return
        }
        if (code.isBlank()) {
            _events.trySend(LoginEvent.ShowError(context.getString(R.string.login_code_empty)))
            return
        }

        _uiState.value = _uiState.value.copy(isLoggingIn = true)

        viewModelScope.launch {
            when (val result = smsService.verifyCode(phone, code)) {
                is SmsResult.Success -> {
                    AppSettings.saveLoginInfo(
                        context = applicationContext,
                        phone = phone,
                        loginAt = System.currentTimeMillis()
                    )
                    _uiState.value = _uiState.value.copy(isLoggingIn = false)
                    _events.trySend(LoginEvent.LoginSuccess(phone))
                }

                is SmsResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoggingIn = false)
                    _events.trySend(LoginEvent.ShowError(result.message))
                }
            }
        }
    }

    /** 60 秒倒计时，结束后允许再次发送。 */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = 60
            _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
            while (remaining > 0) {
                kotlinx.coroutines.delay(1000L)
                remaining -= 1
                _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
            }
            _uiState.value = _uiState.value.copy(isCountingDown = false, countdownSeconds = 0)
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }
}

/**
 * 登录页状态。
 */
data class LoginUiState(
    val phone: String = "",
    val code: String = "",
    val isSendingCode: Boolean = false,
    val isCountingDown: Boolean = false,
    val countdownSeconds: Int = 0,
    val isLoggingIn: Boolean = false
)

/**
 * 一次性事件（Toast / 页面跳转）。
 */
sealed class LoginEvent {
    data class ShowError(val message: String) : LoginEvent()
    data class CodeSent(val message: String) : LoginEvent()
    data class LoginSuccess(val phone: String) : LoginEvent()
}
