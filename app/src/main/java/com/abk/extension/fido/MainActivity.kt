package com.abk.extension.fido

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textview.MaterialTextView
import kotlin.concurrent.thread

/**
 * Native View (Material 3 components) launcher GUI for the ABK FIDO
 * companion. Shows live kernel state and lets the user approve or deny
 * a pending authentication request. No Compose, no com.abk.kernel.
 */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var gateButton: MaterialButton
    private lateinit var pendingCard: MaterialCardView
    private lateinit var pendingDetail: TextView
    private lateinit var lastActionText: TextView
    private var pendingRequestId = -1

    private val refreshRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 2_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildUi()
        setContentView(root)
        // Avoid overlapping the status bar / system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            view.setPadding(
                view.paddingLeft,
                insets.systemWindowInsetTop,
                view.paddingRight,
                insets.systemWindowInsetBottom,
            )
            insets
        }
        refreshRunnable.run()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    private fun buildUi(): ViewGroup {
        val root =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
        root.addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // --- Status card ---
        statusText =
            MaterialTextView(this).apply {
                textSize = 13f
                setTextColor(0xFF0D47A1.toInt())
            }
        val statusCard =
            MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 2f
                setContentPadding(dp(16), dp(16), dp(16), dp(16))
            }
        statusCard.addView(statusText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.addView(statusCard, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        lastActionText =
            MaterialTextView(this).apply {
                textSize = 12f
                visibility = ViewGroup.GONE
            }
        column.addView(lastActionText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.addView(
            MaterialDivider(this),
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )

        // --- Pending auth card ---
        pendingDetail =
            MaterialTextView(this).apply {
                textSize = 13f
            }
        val allowButton =
            MaterialButton(this).apply {
                text = "允许"
                setOnClickListener { decide(true) }
            }
        val denyButton =
            MaterialButton(this).apply {
                text = "拒绝"
                setOnClickListener { decide(false) }
            }
        pendingCard =
            MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 2f
                setContentPadding(dp(16), dp(16), dp(16), dp(16))
                visibility = ViewGroup.GONE
            }
        val pendingCol =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        pendingCol.addView(
            MaterialTextView(this).apply {
                text = "待认证请求"
                textSize = 16f
            },
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        pendingCol.addView(pendingDetail, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val btnRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        btnRow.addView(allowButton)
        btnRow.addView(denyButton)
        pendingCol.addView(btnRow, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pendingCard.addView(pendingCol, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.addView(pendingCard, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // --- Actions ---
        gateButton =
            MaterialButton(this).apply {
                setOnClickListener { toggleGate() }
            }
        column.addView(gateButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        column.addView(
            MaterialButton(this).apply {
                text = "恢复存储 (restore_metadata)"
                setOnClickListener {
                    thread {
                        RootShell.init()
                        val r = FidoKernelBridge.restoreMetadata()
                        toast(if (r.success) "已触发恢复" else "恢复失败: ${r.stdout}")
                    }
                }
            },
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        return root
    }

    private fun refresh() {
        thread {
            RootShell.init()
            val count = FidoKernelBridge.readCredentialCount()
            val gen = FidoKernelBridge.readStoreGeneration()
            val trace = FidoKernelBridge.readLastTrace()
            val gate = FidoKernelBridge.readAuthGate()
            val pending = FidoKernelBridge.readPendingAuthRequest()
            handler.post {
                val sb = StringBuilder()
                sb.append("凭证数: ${count ?: "?"}\n")
                sb.append("store_generation: ${gen ?: "?"}\n")
                sb.append("auth_gate: ${gate?.let { if (it) "开" else "关" } ?: "?"}\n")
                if (trace.isNotBlank()) sb.append("最近: $trace")
                statusText.text = sb.toString()

                gateButton.text = if (gate == true) "关闭 auth_gate" else "开启 auth_gate"

                if (pending != null) {
                    pendingRequestId = pending.requestId
                    pendingDetail.text =
                        "#${pending.requestId}  ${pending.command}\n" +
                            "rp: ${pending.rpId}\n" +
                            "uv=${pending.uv}  rk=${pending.rk}"
                    pendingCard.visibility = ViewGroup.VISIBLE
                } else {
                    pendingRequestId = -1
                    pendingCard.visibility = ViewGroup.GONE
                }
            }
        }
    }

    private fun decide(allow: Boolean) {
        val id = pendingRequestId
        if (id <= 0) return
        thread {
            RootShell.init()
            val r =
                if (allow) FidoKernelBridge.allow(id)
                else FidoKernelBridge.deny(id)
            handler.post {
                lastActionText.text = if (r.success) "已${if (allow) "允许" else "拒绝"} #$id" else "写入失败: ${r.stdout}"
                lastActionText.visibility = ViewGroup.VISIBLE
            }
        }
    }

    private fun toggleGate() {
        thread {
            RootShell.init()
            val cur = FidoKernelBridge.readAuthGate()
            if (cur != null) FidoKernelBridge.setAuthGate(!cur)
        }
    }

    private fun toast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
