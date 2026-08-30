package com.example.awake.ui.auth

import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.awake.data.remote.ScutAccessMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedMode = uiState.selectedMode
    val webViewState = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(selectedMode) {
        webViewState.value?.let { webView ->
            viewModel.attach(webView, selectedMode)
        }
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text("官方登录") },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "请仅在官方页面输入本人账号。Awake 不读取或保存密码。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "选择网络入口",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AccessModeCard(
                    mode = ScutAccessMode.DIRECT,
                    selected = selectedMode == ScutAccessMode.DIRECT,
                    onClick = { viewModel.selectMode(ScutAccessMode.DIRECT) },
                    modifier = Modifier.weight(1f)
                )
                AccessModeCard(
                    mode = ScutAccessMode.WEB_VPN,
                    selected = selectedMode == ScutAccessMode.WEB_VPN,
                    onClick = { viewModel.selectMode(ScutAccessMode.WEB_VPN) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (selectedMode == ScutAccessMode.WEB_VPN) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        "WebVPN 模式会打开学校官方门户。当前先完成门户访问；学校 WebVPN 的代理地址结构确认后，再接入课表接口。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Text(
                uiState.status,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "请先在下方官方页面完成账号、验证码及页面跳转，直到进入课表界面，再点击确认。Awake 不会自动点击或读取你的凭证。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { viewModel.confirmCurrentPage(onAuthenticated) },
                enabled = !uiState.confirming,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(if (uiState.confirming) "正在确认…" else "确认已进入课表，开始提取")
            }
            AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).also { webView ->
                            webViewState.value = webView
                            viewModel.attach(webView, selectedMode)
                        }
                    },
                    onRelease = { webView ->
                        webViewState.value = null
                        viewModel.cancel(webView)
                        webView.destroy()
                    }
                )
        }
    }
}

@Composable
private fun AccessModeCard(
    mode: ScutAccessMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = if (mode == ScutAccessMode.DIRECT) Icons.Default.Public else Icons.Default.Lock
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Column {
                Text(mode.title, color = contentColor, fontWeight = FontWeight.SemiBold)
                Text(mode.description, color = contentColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
