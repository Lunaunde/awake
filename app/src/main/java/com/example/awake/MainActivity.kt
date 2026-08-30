package com.example.awake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.awake.data.widget.AwakeWidgetUpdater
import com.example.awake.ui.navigation.AppNavHost
import com.example.awake.ui.theme.AwakeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AwakeApplication).container
        setContent { AwakeTheme { AppNavHost(container) } }
    }

    override fun onResume() {
        super.onResume()
        AwakeWidgetUpdater.requestUpdate(this)
    }
}
