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
        // Set only when a Timer widget's own click or "Create new timer" started us; see Widgets.kt/WidgetConfig.kt.
        val editEventId = intent.getIntExtra(EXTRA_EDIT_EVENT, -1).takeIf { it >= 0 }
        val bindWidgetId = intent.getIntExtra(EXTRA_BIND_WIDGET, -1).takeIf { it >= 0 }
        setContent { ClockdownTheme { App(editEventId, bindWidgetId) } }
        // WorkManager's enqueue is a database write; off the main thread so it can't delay the first frame.
        thread {
            if (amizoneConnected) AmizoneSync.schedulePeriodic(applicationContext)
            UpdateChecker.scheduleDaily(applicationContext)
        }
    }
}




