package com.abk.extension.fido

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class FidoAuthPromptActivity : FragmentActivity() {
    private var requestId: Int = -1
    private var isResultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        Log.i(TAG, "onCreate requestId=$requestId 期望=${BiometricAuthBridge.expectedRequestId}")
        if (requestId <= 0 || requestId != BiometricAuthBridge.expectedRequestId) {
            Log.w(TAG, "请求 ID 无效,提前结束")
            finish()
            return
        }

        val rpId = intent.getStringExtra(EXTRA_RP_ID).orEmpty()
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()

        val biometricManager = BiometricManager.from(this)
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = biometricManager.canAuthenticate(authenticators)
        Log.i(TAG, "可认证=$canAuth 站点=${rpId.ifBlank { command }}")
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            sendResultAndFinish(false, getString(R.string.auth_biometric_unavailable))
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(TAG, "认证成功 requestId=$requestId")
                    sendResultAndFinish(true, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w(TAG, "认证错误 requestId=$requestId 码=$errorCode 消息=$errString")
                    sendResultAndFinish(
                        false,
                        if (errString.isNotBlank()) errString.toString()
                        else getString(R.string.auth_biometric_denied)
                    )
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_prompt_title))
            .setSubtitle(getString(R.string.auth_prompt_subtitle, rpId.ifBlank { command }))
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy requestId=$requestId 已发送=$isResultSent")
        sendResultAndFinish(false, null)
    }

    private fun sendResultAndFinish(success: Boolean, message: String?) {
        if (isResultSent) return
        isResultSent = true
        Log.i(TAG, "发送结果 requestId=$requestId 成功=$success 消息=${message.orEmpty()}")
        if (!message.isNullOrBlank()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
        BiometricAuthBridge.finish(success)
        finish()
    }

    companion object {
        private const val TAG = "AbkFidoAuth"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_RP_ID = "rp_id"
    }
}
