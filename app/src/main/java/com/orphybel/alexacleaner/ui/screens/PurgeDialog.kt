package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.domain.PurgeMethod
import com.orphybel.alexacleaner.core.domain.PurgeOptions

/** Two-step dialog: choose the method/options, then confirm by typing the number of devices. */
@Composable
fun PurgeDialog(count: Int, totalDevices: Int, defaults: PurgeOptions, onDismiss: () -> Unit, onConfirm: (PurgeOptions) -> Unit) {
    var options by remember { mutableStateOf(defaults.copy(dryRun = false)) }
    var step by remember { mutableStateOf(1) }
    var typed by remember { mutableStateOf("") }

    val effectiveCount = if (options.method == PurgeMethod.WIPE_AND_REDISCOVER) totalDevices else count
    val estimate = when (options.method) {
        PurgeMethod.SEQUENTIAL -> (count * (options.delayMs + 400)) / 1000
        PurgeMethod.PARALLEL -> (count * (options.delayMs + 400)) / 1000 / options.parallelism.coerceAtLeast(1)
        PurgeMethod.WIPE_AND_REDISCOVER -> 10L
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 1) "Supprimer $count appareil(s)" else "Confirmation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (step == 1) {
                    Text("Méthode", style = MaterialTheme.typography.labelLarge)
                    PurgeMethod.values().forEach { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = options.method == m, onClick = { options = options.copy(method = m) })
                            Column {
                                Text(m.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(m.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (options.method) {
                        PurgeMethod.SEQUENTIAL -> {
                            LabeledSlider("Pause entre deux suppressions", options.delayMs.toInt(), 100..3000, step = 100, format = { "$it ms" }) {
                                options = options.copy(delayMs = it.toLong())
                            }
                        }
                        PurgeMethod.PARALLEL -> {
                            LabeledSlider("Suppressions simultanées", options.parallelism, 2..8) { options = options.copy(parallelism = it) }
                            LabeledSlider("Pause par appel", options.delayMs.toInt(), 0..2000, step = 100, format = { "$it ms" }) {
                                options = options.copy(delayMs = it.toLong())
                            }
                        }
                        PurgeMethod.WIPE_AND_REDISCOVER -> {
                            SwitchRow("Relancer la découverte juste après", options.rediscoverAfterWipe) { options = options.copy(rediscoverAfterWipe = it) }
                        }
                    }
                    if (options.method != PurgeMethod.WIPE_AND_REDISCOVER) {
                        LabeledSlider("Nouvelles tentatives par appareil", options.maxRetries, 0..6) { options = options.copy(maxRetries = it) }
                        LabeledSlider(
                            "Arrêt après N échecs consécutifs",
                            options.abortAfterConsecutiveFailures,
                            0..30,
                            format = { if (it == 0) "jamais" else "$it" },
                        ) { options = options.copy(abortAfterConsecutiveFailures = it) }
                    }
                    SwitchRow("Sauvegarde JSON avant suppression", options.backupBeforePurge) { options = options.copy(backupBeforePurge = it) }
                    SwitchRow("Simulation (aucune suppression réelle)", options.dryRun) { options = options.copy(dryRun = it) }
                    Spacer(Modifier.height(8.dp))
                    Text("Durée estimée : ~${formatDuration(estimate * 1000)}", style = MaterialTheme.typography.bodySmall)
                } else {
                    if (options.dryRun) {
                        Text("Mode simulation : $effectiveCount appareil(s) seront listés dans le journal, rien ne sera supprimé chez Amazon.")
                    } else {
                        Text(
                            if (options.method == PurgeMethod.WIPE_AND_REDISCOVER) {
                                "ATTENTION : cette méthode supprime les $totalDevices appareils connectés du compte, pas seulement les $count sélectionnés. " +
                                    "Groupes, noms personnalisés et routines liées seront perdus. Les appareils joignables devraient être redécouverts, mais ce n'est pas garanti pour toutes les skills."
                            } else {
                                "$count appareil(s) vont être supprimés définitivement du compte Alexa. Un appareil supprimé par erreur devra être redécouvert depuis sa skill."
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Pour confirmer, saisissez le nombre $effectiveCount :")
                        OutlinedTextField(value = typed, onValueChange = { typed = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(onClick = { step = 2 }, enabled = effectiveCount > 0) { Text("Continuer") }
            } else {
                TextButton(
                    onClick = { onConfirm(options) },
                    enabled = options.dryRun || typed == effectiveCount.toString(),
                ) { Text(if (options.dryRun) "Lancer la simulation" else "Supprimer définitivement") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 2) step = 1 else onDismiss() }) { Text(if (step == 2) "Retour" else "Annuler") }
        },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
