@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.rishabh.clockdown

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Full-screen add/edit: a live preview of the card on top, the essentials next, then everything that changes its look. */
@Composable
fun EditScreen(event: Event, onClose: () -> Unit, onSave: (Event) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)

    var name by remember { mutableStateOf(event.name) }
    var emoji by remember { mutableStateOf(event.emoji ?: "⏰") }
    var color by remember { mutableIntStateOf(if (event.id == 0) (0 until PALETTE_SIZE).random() else event.colorIndex()) }
    var style by remember { mutableIntStateOf(if (event.id == 0) 0 else event.styleIndex()) }
    var start by remember { mutableStateOf(event.startMillis.toLocal()) }
    var alarm by remember { mutableStateOf(event.alarmMinutes.toString()) }

    val millis = start.atZone(zone).toInstant().toEpochMilli()
    val inFuture = millis > System.currentTimeMillis()
    val preview = event.copy(
        name = name, startMillis = millis, emoji = emoji, colorIdx = color, progressStyle = style,
        alarmMinutes = alarm.toIntOrNull() ?: 0,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (event.id == 0) "New event" else "Edit event", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                actions = {
                    onDelete?.let { IconButton(onClick = it) { Icon(Icons.Filled.Delete, contentDescription = "Delete") } }
                    Button(
                        enabled = name.isNotBlank() && inFuture,
                        onClick = { onSave(preview.copy(name = name.trim(), source = MANUAL)) },
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Save", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EventCard(preview, System.currentTimeMillis(), Modifier.width(230.dp))
            }

            OutlinedTextField(
                name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large, textStyle = MaterialTheme.typography.titleMedium,
            )

            Section("When") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PickerButton("📅  " + start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), Modifier.weight(1f)) {
                        DatePickerDialog(
                            ctx, { _, y, m, d -> start = LocalDateTime.of(LocalDate.of(y, m + 1, d), start.toLocalTime()) },
                            start.year, start.monthValue - 1, start.dayOfMonth,
                        ).show()
                    }
                    PickerButton("🕘  " + start.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), Modifier.weight(1f)) {
                        TimePickerDialog(
                            ctx, { _, h, m -> start = start.withHour(h).withMinute(m) },
                            start.hour, start.minute, DateFormat.is24HourFormat(ctx),
                        ).show()
                    }
                }
                if (!inFuture) Text("Pick a time in the future", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Section("Alarm before it starts") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Off", 5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m", 60 to "1h").forEach { (m, label) ->
                        FilterChip(selected = alarm.toIntOrNull() == m, onClick = { alarm = m.toString() }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    alarm, { alarm = it.filter(Char::isDigit).take(4) },
                    label = { Text("Custom minutes (0 = none)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Section("Emoji") {
                // Three rows that scroll sideways: the whole picker costs ~170dp of height instead of ~350dp.
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(3), modifier = Modifier.height(52.dp * 3 + 6.dp * 2),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(EMOJIS) { em ->
                        val selected = em == emoji
                        val size by animateDpAsState(if (selected) 52.dp else 44.dp, spring(dampingRatio = 0.5f, stiffness = 500f), label = "emoji")
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(size).clip(CircleShape)
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .bouncy { emoji = em },
                                contentAlignment = Alignment.Center,
                            ) { Text(em, fontSize = 24.sp) }
                        }
                    }
                }
            }

            Section("Colour") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(PALETTE_SIZE) { i ->
                        val c = paletteColor(i)
                        val selected = i == color
                        val size by animateDpAsState(if (selected) 42.dp else 32.dp, spring(dampingRatio = 0.5f, stiffness = 500f), label = "swatch")
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(size).clip(CircleShape).background(c).bouncy { color = i }, contentAlignment = Alignment.Center) {
                                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color(onColor(c.toArgb())), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            Section("Progress style") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Ring", "Dots", "Bars").forEachIndexed { i, label ->
                        StyleTile(label, i, selected = style == i, color = paletteColor(color), modifier = Modifier.weight(1f)) { style = i }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun PickerButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier.bouncy(onClick), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
    }
}

/** A real sample of each progress style, drawn in the event's colour. */
@Composable
private fun StyleTile(label: String, style: Int, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val fg = Color(onColor(color.toArgb()))
    Column(
        modifier.bouncy(onClick).clip(MaterialTheme.shapes.large).background(color)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier.alpha(0.55f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            ProgressVisual(style, 0.6f, fg, if (style == 0) Modifier.size(44.dp) else Modifier)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}
