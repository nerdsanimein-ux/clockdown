@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.rishabh.clockdown

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Emoji on a soft cookie-shaped badge (one of Material 3 Expressive's 35 shapes). */
@Composable
fun EmojiBadge(emoji: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = (size.value * 0.5f).sp) }
}

/** Ring, dots or bars (style 0, 1, 2) filling as [progress] goes 0..1, springing to each new value. */
@Composable
fun ProgressVisual(style: Int, progress: Float, color: Color, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow), label = "progress")
    val track = color.copy(alpha = 0.25f)
    when (style) {
        0 -> Canvas(modifier.size(56.dp)) {
            val stroke = size.minDimension * 0.16f
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        1 -> Canvas(modifier.fillMaxWidth().aspectRatio(10f / 3f)) {
            val cols = 10
            val rows = 3
            val cell = size.width / cols
            val filled = (p * cols * rows).roundToInt()
            for (i in 0 until cols * rows) {
                val c = Offset((i % cols + 0.5f) * cell, (i / cols + 0.5f) * cell)
                drawCircle(if (i < filled) color else track, cell * 0.32f, c)
            }
        }
        else -> Canvas(modifier.fillMaxWidth().height(34.dp)) {
            val n = 14
            val gap = size.width * 0.02f
            val w = (size.width - gap * (n - 1)) / n
            val filled = (p * n).roundToInt()
            for (i in 0 until n) {
                drawRoundRect(
                    if (i < filled) color else track, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(w / 2),
                )
            }
        }
    }
}

/** Ticking countdown; each digit slides when it changes. Digits get fixed-width cells so nothing jitters. */
@Composable
fun Countdown(ms: Long, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val total = ms.coerceAtLeast(0) / 1000
    val text = (if (total >= 86400) "${total / 86400}d " else "") +
        "%02d:%02d:%02d".format(total % 86400 / 3600, total % 3600 / 60, total % 60)
    val cell = with(LocalDensity.current) { fontSize.toDp() * 0.6f }
    val style = MaterialTheme.typography.displayLarge.copy(fontSize = fontSize, lineHeight = fontSize * 1.1f, color = color)
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        text.forEachIndexed { i, ch ->
            if (ch.isDigit()) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = { (slideInVertically { -it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut()) },
                    modifier = Modifier.width(cell),
                    label = "digit$i",
                ) { c -> Text(c.toString(), style = style, textAlign = TextAlign.Center, modifier = Modifier.width(cell)) }
            } else Text(ch.toString(), style = if (ch == ':') style.copy(fontSize = fontSize * 0.8f) else style.copy(fontSize = fontSize * 0.5f))
        }
    }
}

/** Springy press feedback for cards. */
fun Modifier.bouncy(onClick: (() -> Unit)?): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }.then(
        if (onClick == null) Modifier
        else Modifier.pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() }) },
    )
}

/** Fade-and-rise entrance, staggered by [index]. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 10) * 45L)
        a.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 220f))
    }
    val rise = with(LocalDensity.current) { 48.dp.toPx() }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * rise }
}
