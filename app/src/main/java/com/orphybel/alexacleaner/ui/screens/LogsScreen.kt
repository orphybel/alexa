package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.domain.PurgeStatus
import com.orphybel.alexacleaner.ui.MainViewModel
import com.orphybel.alexacleaner.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier, state: UiState, vm: MainViewModel) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Journal des suppressions") },
            actions = {
                IconButton(onClick = { confirmClear = true }, enabled = state.runs.isNotEmpty()) {
                    Icon(Icons.Default.Delete, contentDescription = "Effacer le journal")
                }
            },
        )
        if (state.runs.isEmpty()) {
            Text("Aucune suppression enregistrée.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.runs, key = { it.runId }) { run ->
                val s = run.summary
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == run.runId) null else run.runId }
                        .padding(16.dp),
                ) {
                    Text(
                        formatDate(s?.startedAt ?: run.records.firstOrNull()?.at) + (s?.let { " · ${it.method.label}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (s != null) {
                            buildString {
                                append("${s.succeeded}/${s.total} réussis")
                                if (s.dryRun > 0) append(" (simulation)")
                                if (s.failed > 0) append(", ${s.failed} échec(s)")
                                if (s.aborted) append(" — ${s.abortReason ?: "interrompu"}")
                                append(" · ${formatDuration(s.durationMs)}")
                            }
                        } else {
                            "${run.records.size} entrée(s), pas de bilan (exécution interrompue ?)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((s?.failed ?: 0) > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (expanded == run.runId) {
                        run.records.forEach { r ->
                            Row(Modifier.padding(top = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.target.friendlyName, style = MaterialTheme.typography.bodySmall)
                                    Text("${r.target.source} · ${r.message}", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    r.status.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (r.status == PurgeStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer le journal ?") },
            text = { Text("L'historique des suppressions sera supprimé de l'application. Les sauvegardes JSON restent disponibles dans les réglages.") },
            confirmButton = { TextButton(onClick = { vm.clearLogs(); confirmClear = false }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}
