package com.rishabh.clockdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rishabh.clockdown.ui.theme.ClockdownTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (amizoneConnected) AmizoneSync.schedulePeriodic(applicationContext)
        UpdateChecker.scheduleDaily(applicationContext)
        setContent { ClockdownTheme { App() } }
    }
}
