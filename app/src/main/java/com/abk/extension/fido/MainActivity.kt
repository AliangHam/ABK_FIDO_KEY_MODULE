package com.abk.extension.fido

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AbkFidoColors) {
                Surface(Modifier.fillMaxSize()) {
                    FidoMainScreen()
                }
            }
        }
    }
}

/** Gold-on-navy MD3 palette matching the app icon. */
private val AbkFidoColors = lightColorScheme(
    primary = Color(0xFFB26A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE082),
    onPrimaryContainer = Color(0xFF3E2A00),
    secondary = Color(0xFF0D47A1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBBDEFB),
    onSecondaryContainer = Color(0xFF002B57),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFF6F8FC),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FidoMainScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("加载中…") }
    var gate by remember { mutableStateOf<Boolean?>(null) }
    var pending by remember { mutableStateOf<PendingAuthRequest?>(null) }
    var lastAction by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                RootShell.init()
                val count = FidoKernelBridge.readCredentialCount()
                val gen = FidoKernelBridge.readStoreGeneration()
                val trace = FidoKernelBridge.readLastTrace()
                val g = FidoKernelBridge.readAuthGate()
                val p = FidoKernelBridge.readPendingAuthRequest()
                status =
                    buildString {
                        appendLine("凭证数: ${count ?: "?"}")
                        appendLine("store_generation: ${gen ?: "?"}")
                        appendLine("auth_gate: ${g?.let { if (it) "开" else "关" } ?: "?"}")
                        if (trace.isNotBlank()) appendLine("最近: $trace")
                    }
                gate = g
                pending = p
            }
            delay(2_000)
        }
    }

    fun toast(message: String) {
        // Toast must be shown on a thread with a Looper; always hop to main.
        (context as? MainActivity)?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ABK FIDO Companion") })
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("驱动状态", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (lastAction.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(lastAction, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            pending?.let { req ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("待认证请求 #${req.requestId}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${req.command}  rp=${req.rpId}  uv=${req.uv}  rk=${req.rk}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    lastAction = "已发送: allow #${req.requestId}"
                                    toast("允许 #${req.requestId}")
                                    kotlin.concurrent.thread {
                                        RootShell.init()
                                        FidoKernelBridge.allow(req.requestId)
                                    }
                                },
                            ) { Text("允许") }
                            OutlinedButton(
                                onClick = {
                                    lastAction = "已发送: deny #${req.requestId}"
                                    toast("拒绝 #${req.requestId}")
                                    kotlin.concurrent.thread {
                                        RootShell.init()
                                        FidoKernelBridge.deny(req.requestId)
                                    }
                                },
                            ) { Text("拒绝") }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    kotlin.concurrent.thread {
                        RootShell.init()
                        val cur = FidoKernelBridge.readAuthGate()
                        if (cur != null) FidoKernelBridge.setAuthGate(!cur)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (gate == true) "关闭 auth_gate" else "开启 auth_gate") }

            OutlinedButton(
                onClick = {
                    kotlin.concurrent.thread {
                        RootShell.init()
                        val r = FidoKernelBridge.restoreMetadata()
                        toast(if (r.success) "已触发恢复" else "恢复失败: ${r.stdout}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复存储 (restore_metadata)") }
        }
    }
}
