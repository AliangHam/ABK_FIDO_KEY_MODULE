package com.abk.extension.fido

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class FidoSyncService : Service() {
    @Volatile
    private var running = false

    @Volatile
    private var syncRequested = true

    @Volatile
    private var syncInFlight = false

    @Volatile
    private var lastSyncReason = "service_start"

    @Volatile
    private var lastPromptRequestId = -1

    @Volatile
    private var lastObservedStoreGeneration = -1
    private val syncStateLock = Any()

    override fun onCreate() {
        super.onCreate()
        RootShell.init()
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        Log.i(TAG, "服务已创建")
        thread(name = "abk-fido-service-loop") {
            serviceLoop()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastSyncReason = intent?.getStringExtra(EXTRA_REASON)
            ?: intent?.action
            ?: "service_restart"
        syncRequested = true
        Log.i(TAG, "onStartCommand 原因=$lastSyncReason")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Log.i(TAG, "服务已销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun serviceLoop() {
        while (running) {
            runCatching {
                maybeHandlePendingAuth()
            }.onFailure {
                Log.w("AbkFidoCompanion", "认证循环失败", it)
            }

            runCatching {
                maybeScheduleStoreSync()
            }.onFailure {
                Log.w(TAG, "存储代数轮询失败", it)
            }

            kickSyncIfNeeded()

            try {
                Thread.sleep(750)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun maybeScheduleStoreSync() {
        val generation = FidoKernelBridge.readStoreGeneration() ?: return
        if (lastObservedStoreGeneration == -1) {
            lastObservedStoreGeneration = generation
            return
        }
        if (generation == lastObservedStoreGeneration) {
            return
        }
        lastObservedStoreGeneration = generation
        synchronized(syncStateLock) {
            syncRequested = true
            lastSyncReason = "store_generation_$generation"
        }
        Log.i(TAG, "检测到存储代数变化=$generation")
    }

    private fun kickSyncIfNeeded() {
        var reason = ""
        synchronized(syncStateLock) {
            if (!syncRequested || syncInFlight) {
                return
            }
            syncRequested = false
            syncInFlight = true
            reason = lastSyncReason
        }
        thread(name = "abk-fido-sync") {
            try {
                Log.i(TAG, "执行同步 原因=$reason")
                val result = MetadataSyncCoordinator(applicationContext).syncNow(reason)
                publishState(result, reason)
            } finally {
                synchronized(syncStateLock) {
                    syncInFlight = false
                }
            }
        }
    }

    private fun maybeHandlePendingAuth() {
        val pending = FidoKernelBridge.readPendingAuthRequest() ?: return
        Log.i(
            TAG,
            "待认证请求 requestId=${pending.requestId} 命令=${pending.command} 站点=${pending.rpId} uv=${pending.uv} rk=${pending.rk}",
        )
        if (pending.requestId == lastPromptRequestId || BiometricAuthBridge.isAuthenticating) {
            Log.i(
                TAG,
                "跳过提示 requestId=${pending.requestId} 上次=$lastPromptRequestId 认证中=${BiometricAuthBridge.isAuthenticating}",
            )
            return
        }
        lastPromptRequestId = pending.requestId
        BiometricAuthBridge.begin(pending.requestId)
        Log.i(TAG, "启动认证提示 requestId=${pending.requestId}")
        val launch =
            RootShell.launchFidoAuthPromptActivity(
                requestId = pending.requestId,
                command = pending.command,
                rpId = pending.rpId,
            )
        if (!launch.success) {
            Log.w(TAG, "启动认证提示失败 requestId=${pending.requestId} 输出=${launch.stdout}")
            Handler(Looper.getMainLooper()).post {
                Toast
                    .makeText(
                        this,
                        getString(R.string.auth_prompt_launch_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            FidoKernelBridge.deny(pending.requestId)
            BiometricAuthBridge.finish(false)
            return
        }

        val result = BiometricAuthBridge.await(AUTH_PROMPT_TIMEOUT_MS)
        Log.i(TAG, "认证结果 requestId=${pending.requestId} 结果=${result?.toString() ?: "超时"}")
        when (result) {
            true -> {
                FidoKernelBridge.allow(pending.requestId)
            }

            false -> {
                FidoKernelBridge.deny(pending.requestId)
            }

            null -> {
                FidoKernelBridge.deny(pending.requestId)
            }
        }
    }

    private fun publishState(
        result: SyncResult,
        reason: String,
    ) {
        Log.i(TAG, "状态发布 成功=${result.success} 原因=$reason 消息=${result.userMessage(this)}")
        runCatching {
            HostBridge(
                resolver = contentResolver,
                authority = ABK_EXTENSION_DEFAULT_HOST_PROVIDER,
                extensionId = ABK_EXTENSION_DEFAULT_ID,
            ).writeState(
                summary = result.userMessage(this),
                success = result.success,
                reason = reason,
            )
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AbkFidoCompanion"
        const val ACTION_SYNC_NOW = "com.abk.extension.fido.action.SYNC_NOW"
        const val EXTRA_REASON = "reason"
        private const val AUTH_PROMPT_TIMEOUT_MS = 25_000L

        private const val CHANNEL_ID = "abk_fido_companion"
        private const val NOTIFICATION_ID = 1002
    }
}
