package com.example.sscan.core.auth

/**
 * 短信验证码服务抽象接口。
 *
 * 将来接入真实的短信 API（阿里云、腾讯云、容联云通讯等）时，
 * 只需再实现一个 RealSmsService 并替换掉 AppModule 中的注入即可，
 * LoginViewModel 的上层代码无需任何改动。
 */
interface SmsService {

    /**
     * 发送短信验证码。
     *
     * @param phone 手机号码（不带国家码）
     * @return 结果：成功时返回 true；失败时返回 false 和错误消息。
     */
    suspend fun sendCode(phone: String): SmsResult

    /**
     * 校验短信验证码。
     *
     * @param phone 手机号码
     * @param code 用户输入的验证码
     * @return 结果：校验通过返回 true
     */
    suspend fun verifyCode(phone: String, code: String): SmsResult
}

/**
 * 短信操作结果。
 */
sealed class SmsResult {
    data class Success(val message: String? = null) : SmsResult()
    data class Failure(val message: String) : SmsResult()
}
