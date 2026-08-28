package com.example.awake.ui.auth

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.awake.data.remote.CasWebViewCoordinator
import com.example.awake.data.repository.LocalTimetableRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    coordinator: CasWebViewCoordinator,
    local: LocalTimetableRepository,
    onAuthenticated: () -> Unit,
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf("正在打开华南理工官方登录页…") }
    val context = LocalContext.current
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("官方登录") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text("请仅在官方页面输入本人账号。Awake 不读取或保存密码。", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text(status, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { webView ->
                        coordinator.attach(webView, {
                            status = "登录成功，正在返回…"
                            kotlinx.coroutines.MainScope().launch {
                                local.saveLoggedInProfile(null, null)
                                onAuthenticated()
                            }
                        }, { reason -> status = reason })
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )
        }
    }
}
