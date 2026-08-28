package com.example.awake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.awake.ui.theme.AwakeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AwakeTheme {
                TestScreen()
            }
        }
    }
}

@Composable
fun TestScreen() {
    val clickCount = remember {
        mutableStateOf(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Kotlin 运行成功！")

        Button(
            onClick = {
                clickCount.value++
            },
            modifier = Modifier.padding(top = 20.dp)
        ) {
            Text(text = "点击测试")
        }

        Text(
            text = "点击次数：${clickCount.value}",
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}