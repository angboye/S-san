package com.example.sscan.core.auth

import android.content.Context
import com.example.sscan.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Mock（假数据）实现：用于开发和调试。
 *
 * 行为：
 *   1. sendCode：模拟 800ms 网络延迟后，在日志中打印"验证码：123456"，固定用 123456 作为验证码。
 *   2. verifyCode：只要手机号格式合法且输入的验证码等于 "123456"，即视为通过。
 *
 * 将来接入真实短信 API 时：
 *   - 新建 RealSmsService 实现 SmsService 接口；
 *   - 在 AppModule 中替换 @Provides 的返回值即可；
 *   - 上层 LoginViewModel/LoginActivity 完全不需要修改。
 */
class MockSmsService @Inject constructor(
    @ApplicationContext private val context: Context
) : SmsService {

    companion object {
        /** 统一的固定 Mock 验证码。接入真实 API 时整个 class 都可以删掉。 */
        const val MOCK_CODE = "123456"

        /** 手机号格式检查（中国大陆 11 位手机号）。 */
        private val PHONE_PATTERN = Regex("^1[3-9]\\d{9}$")
    }

    override suspend fun sendCode(phone: String): SmsResult {
        // 1. 基础格式检查
        if (phone.isBlank() || !PHONE_PATTERN.matches(phone)) {
            return SmsResult.Failure(context.getString(R.string.login_phone_invalid))
        }
        // 2. 模拟网络请求耗时
        delay(800L)
        // 3. 为了调试方便，在日志里输出验证码；生产环境不要这样做。
        android.util.Log.d("MockSmsService", "【Mock】已向 $phone 发送验证码：$MOCK_CODE")
        return SmsResult.Success(context.getString(R.string.login_code_sent_mock, MOCK_CODE))
    }

    override suspend fun verifyCode(phone: String, code: String): SmsResult {
        if (phone.isBlank() || !PHONE_PATTERN.matches(phone)) {
            return SmsResult.Failure(context.getString(R.string.login_phone_invalid))
        }
        if (code.isBlank() || code.length != 6) {
            return SmsResult.Failure(context.getString(R.string.login_code_invalid_length))
        }
        // 模拟远程校验延迟
        delay(600L)
        return if (code == MOCK_CODE) {
            SmsResult.Success()
        } else {
            SmsResult.Failure(context.getString(R.string.login_code_incorrect))
        }
    }
}
