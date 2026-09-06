package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.model.Reachability
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

fun formatDate(millis: Long?): String = millis?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}min ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}min"
    }
}

fun statusLabel(d: SmartHomeDevice): String = when (d.reachability) {
    Reachability.REACHABLE -> "En ligne"
    Reachability.UNREACHABLE -> "Hors ligne"
    Reachability.UNKNOWN -> "Inconnu"
}

@Composable
fun statusColor(d: SmartHomeDevice): Color = when (d.reachability) {
    Reachability.REACHABLE -> Color(0xFF2E7D32)
    Reachability.UNREACHABLE -> MaterialTheme.colorScheme.error
    Reachability.UNKNOWN -> MaterialTheme.colorScheme.outline
}

fun typeLabel(type: String): String = when (type) {
    "LIGHT" -> "Lumière"
    "SMARTPLUG" -> "Prise"
    "SWITCH" -> "Interrupteur"
    "THERMOSTAT" -> "Thermostat"
    "SMARTLOCK" -> "Serrure"
    "CAMERA" -> "Caméra"
    "SPEAKER" -> "Enceinte"
    "TV" -> "Télévision"
    "DOORBELL" -> "Sonnette"
    "ALEXA_VOICE_ENABLED" -> "Appareil Alexa"
    "SCENE_TRIGGER", "ACTIVITY_TRIGGER" -> "Scène"
    "CONTACT_SENSOR" -> "Capteur d'ouverture"
    "MOTION_SENSOR" -> "Capteur de mouvement"
    "TEMPERATURE_SENSOR" -> "Capteur de température"
    "FAN" -> "Ventilateur"
    "VACUUM_CLEANER" -> "Aspirateur"
    "OTHER", "AUTRE" -> "Autre"
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun LabeledSlider(label: String, value: Int, range: IntRange, step: Int = 1, format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange(((v / step).roundToInt() * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}

/** Checkbox list with counts; an empty selection means "everything". */
@Composable
fun MultiSelectDialog(
    title: String,
    options: List<Pair<String, Int>>,
    selected: Set<String>,
    labelOf: (String) -> String = { it },
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (current.isEmpty()) "Aucune case cochée = tout afficher" else "${current.size} sélectionné(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn {
                    items(options, key = { it.first }) { (value, count) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = value in current, onCheckedChange = { checked -> current = if (checked) current + value else current - value })
                            Text(labelOf(value), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("$count", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current) }) { Text("Appliquer") } },
        dismissButton = {
            Row {
                TextButton(onClick = { current = emptySet() }) { Text("Tout") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
