package com.rishabh.clockdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rishabh.clockdown.ui.theme.ClockdownTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClockdownTheme { App() } }
        // WorkManager's enqueue is a database write; off the main thread so it can't delay the first frame.
        thread {
            if (amizoneConnected) AmizoneSync.schedulePeriodic(applicationContext)
            UpdateChecker.scheduleDaily(applicationContext)
        }
    }
}




