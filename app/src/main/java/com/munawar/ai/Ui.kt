package com.munawar.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.sin

val BgColor = Color(0xFF0B0B1E)
val CardColor = Color(0xFF1B1B3A)

@Composable
fun MunawarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8C7BFF),
            secondary = Color(0xFF18FFFF),
            tertiary = Color(0xFFFF4081),
            background = BgColor,
            surface = CardColor,
            onBackground = Color.White,
            onSurface = Color.White,
            onPrimary = Color.White,
        ),
        content = content,
    )
}

/** Siri-style colourful orb. All animation is read inside the draw phase, so it never recomposes. */
@Composable
fun Orb(phase: Phase, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val time = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "t",
    )
    val levelAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        Bus.level.collectLatest { levelAnim.animateTo(it, tween(120)) }
    }

    Canvas(modifier) {
        val tt = time.value
        val r0 = size.minDimension / 2f
        val c = center
        val amp = when (phase) {
            Phase.LISTENING -> levelAnim.value
            Phase.SPEAKING -> 0.35f + 0.3f * (sin(tt * 6f) * 0.5f + 0.5f)
            Phase.THINKING -> 0.25f + 0.1f * sin(tt * 4f)
            Phase.OFF -> 0.05f
        }
        val r = r0 * (0.62f + 0.12f * amp)

        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x558C7BFF), Color.Transparent), center = c, radius = r0),
            radius = r0,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF1B1B4B), Color(0xFF07071A)), center = c, radius = r),
            radius = r,
            center = c,
        )
        val path = Path().apply { addOval(Rect(c, r)) }
        clipPath(path) {
            val colors = listOf(Color(0xFF18FFFF), Color(0xFFFF4081), Color(0xFF8C7BFF), Color(0xFFFFB300))
            val alpha = if (phase == Phase.OFF) 0.35f else 0.85f
            colors.forEachIndexed { i, col ->
                val ph = tt * (1f + i * 0.35f) + i * 1.7f
                val off = r * (0.28f + 0.22f * amp)
                val pc = Offset(c.x + cos(ph) * off, c.y + sin(ph * 1.2f) * off)
                val rr = r * (0.65f + 0.2f * amp)
                drawCircle(
                    brush = Brush.radialGradient(listOf(col.copy(alpha = alpha), Color.Transparent), center = pc, radius = rr),
                    radius = rr,
                    center = pc,
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = r, center = c, style = Stroke(width = 2f))
    }
}

@Composable
fun Bubble(m: Msg) {
    val align = if (m.fromUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(
            color = if (m.fromUser) Color(0xFF2A2A5C) else CardColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp),
        ) {
            Text(
                m.text,
                Modifier.padding(12.dp),
                color = Color.White,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            )
        }
    }
}

@Composable
fun MicButton(running: Boolean, onClick: () -> Unit) {
    val colors = if (running) listOf(Color(0xFFFF4081), Color(0xFFFF8A65)) else listOf(Color(0xFF8C7BFF), Color(0xFF18FFFF))
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (running) "■" else "🎤", fontSize = 30.sp, color = Color.White)
    }
}
