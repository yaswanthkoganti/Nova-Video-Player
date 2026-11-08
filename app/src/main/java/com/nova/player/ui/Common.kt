package com.nova.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, t ->
            FilterChip(selected = i == selected, onClick = { onPick(i) }, label = { Text(t) })
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/** 0 = "automatic" (theme colour). */
val AccentPalette = listOf(
    0, 0xFFFF5252.toInt(), 0xFFFF9100.toInt(), 0xFFFFD740.toInt(), 0xFF76FF03.toInt(), 0xFF00E676.toInt(),
    0xFF18FFFF.toInt(), 0xFF40C4FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFE040FB.toInt(), 0xFFFF4081.toInt(), 0xFFFFFFFF.toInt(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(selected: Int, withHue: Boolean = false, onPick: (Int) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentPalette.forEach { c ->
                val fill = if (c == 0) MaterialTheme.colorScheme.primary else Color(c)
                val on = c == selected
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(fill)
                        .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.4f), CircleShape)
                        .clickable { onPick(c) },
                    contentAlignment = Alignment.Center,
                ) { if (c == 0) Text("A", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
            }
        }
        if (withHue) {
            val hsv = FloatArray(3)
            if (selected != 0) android.graphics.Color.colorToHSV(selected, hsv)
            Text("Custom hue", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Slider(hsv[0], { h -> onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, 0.85f, 1f))) }, valueRange = 0f..360f)
        }
    }
}
