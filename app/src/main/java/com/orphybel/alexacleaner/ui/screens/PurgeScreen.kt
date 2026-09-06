package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.domain.PurgeStatus
import com.orphybel.alexacleaner.ui.MainViewModel
import com.orphybel.alexacleaner.ui.Screen
import com.orphybel.alexacleaner.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurgeScreen(modifier: Modifier, state: UiState, vm: MainViewModel) {
    val p = state.purge
    val running = state.purgeRunning
    val currentRun = state.runs.firstOrNull()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (running) "Suppression en cours" else "Suppression terminée") },
            navigationIcon = {
                IconButton(onClick = { vm.navigate(Screen.DEVICES) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
            },
        )
        Column(Modifier.padding(16.dp)) {
            if (p != null) {
                val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${p.done} / ${p.total} traités · ${p.ok} réussis · ${p.failed} échec(s)", style = MaterialTheme.typography.bodyMedium)
                Text(p.last, style = MaterialTheme.typography.bodySmall)
                p.summary?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("Bilan : ${s.succeeded}/${s.total} réussis en ${formatDuration(s.durationMs)}")
                            if (s.failed > 0) append(", ${s.failed} échec(s)")
                            if (s.aborted) append(" — interrompu : ${s.abortReason ?: "?"}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (s.failed > 0 || s.aborted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text("Aucune suppression en cours.")
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = { vm.cancelPurge() }) { Text("Arrêter") }
                    Text(
                        "La suppression continue en arrière-plan si vous quittez l'application.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Button(onClick = { vm.navigate(Screen.DEVICES) }) { Text("Retour aux appareils") }
                    OutlinedButton(onClick = { vm.navigate(Screen.LOGS) }) { Text("Journal complet") }
                }
            }
        }
        HorizontalDivider()
        val records = currentRun?.records?.asReversed() ?: emptyList()
        if (records.isEmpty()) {
            Text(
                if (running) "En attente des premiers résultats…" else "Aucun détail disponible.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(records, key = { it.target.applianceId + it.at }) { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.target.friendlyName, style = MaterialTheme.typography.bodyMedium)
                            Text(r.message, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            r.status.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.status == PurgeStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
