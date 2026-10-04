package com.example.sscan.feature.auth.presentation

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sscan.MainActivity
import com.example.sscan.R
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.databinding.ActivityLoginBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppSettings.getLoggedInPhone(this) != null) {
            openMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.phoneInput.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    viewModel.onPhoneChanged(s?.toString().orEmpty())
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    Unit
            }
        )

        binding.codeInput.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    viewModel.onCodeChanged(s?.toString().orEmpty())
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    Unit
            }
        )

        binding.sendCodeButton.setOnClickListener {
            viewModel.sendCode()
        }

        binding.loginButton.setOnClickListener {
            viewModel.login(applicationContext)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        renderEvent(event)
                    }
                }
            }
        }
    }

    private fun renderState(state: LoginUiState) {
        binding.sendCodeButton.isEnabled = !state.isCountingDown && !state.isSendingCode
        binding.sendCodeButton.text = when {
            state.isCountingDown -> getString(R.string.login_resend_countdown, state.countdownSeconds)
            state.isSendingCode -> getString(R.string.login_sending_code)
            else -> getString(R.string.login_send_code)
        }

        binding.loginButton.text = if (state.isLoggingIn) {
            getString(R.string.login_logging_in)
        } else {
            getString(R.string.login_confirm)
        }
        binding.loginButton.isEnabled = !state.isLoggingIn
    }

    private fun renderEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ShowError -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                binding.statusText.isVisible = false
            }

            is LoginEvent.CodeSent -> {
                binding.statusText.text = event.message
                binding.statusText.isVisible = true
            }

            is LoginEvent.LoginSuccess -> {
                Toast.makeText(
                    this,
                    getString(R.string.login_login_success, event.phone),
                    Toast.LENGTH_SHORT
                ).show()
                openMain()
            }
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }
}
