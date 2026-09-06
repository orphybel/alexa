package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.domain.AppSettings
import com.orphybel.alexacleaner.core.domain.PurgeMethod
import com.orphybel.alexacleaner.ui.MainViewModel
import com.orphybel.alexacleaner.ui.UiState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier, state: UiState, vm: MainViewModel) {
    val s = state.settings
    fun save(transform: (AppSettings) -> AppSettings) = vm.saveSettings(transform(s))

    var intervalMenu by remember { mutableStateOf(false) }
    var sourcesDialog by remember { mutableStateOf(false) }
    var typesDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Réglages") })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle("Compte")
            Text(
                "Connecté${state.customerName?.let { " en tant que $it" } ?: ""} · ${state.region.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.snapshot?.let { Text("Dernière liste chargée : ${formatDate(it.fetchedAt)}", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmLogout = true }) { Text("Se déconnecter") }

            SectionTitle("Analyses planifiées")
            Text(
                "Une analyse recharge la liste en arrière-plan et met à jour la durée hors ligne de chaque appareil. " +
                    "C'est ce qui rend possible le filtre « hors ligne depuis N jours » et la suppression automatique.",
                style = MaterialTheme.typography.bodySmall,
            )
            Box {
                OutlinedButton(onClick = { intervalMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(AppSettings.SCAN_INTERVALS.firstOrNull { it.first == s.scanIntervalHours }?.second ?: "${s.scanIntervalHours} h", Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = intervalMenu, onDismissRequest = { intervalMenu = false }) {
                    AppSettings.SCAN_INTERVALS.forEach { (hours, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { save { it.copy(scanIntervalHours = hours) }; intervalMenu = false })
                    }
                }
            }
            Text("Analyses effectuées : ${state.history.scanCount}${state.history.lastScanAt?.let { " · dernière le ${formatDate(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.scanNow() }) { Text("Analyser maintenant") }
                TextButton(onClick = { confirmHistory = true }) { Text("Effacer l'historique") }
            }
            SwitchRow("Notification après chaque analyse", s.notifyOnScan) { v -> save { it.copy(notifyOnScan = v) } }

            SectionTitle("Suppression automatique")
            val rule = s.autoPurge
            SwitchRow(
                "Activer",
                rule.enabled,
                description = "À chaque analyse planifiée, les appareils remplissant tous les critères ci-dessous sont supprimés (méthode un par un).",
            ) { v -> save { it.copy(autoPurge = rule.copy(enabled = v)) } }
            if (rule.enabled) {
                SwitchRow(
                    "Simulation uniquement",
                    rule.dryRun,
                    description = "Recommandé au début : une notification indique ce qui aurait été supprimé, sans rien supprimer.",
                ) { v -> save { it.copy(autoPurge = rule.copy(dryRun = v)) } }
                LabeledSlider("Analyses consécutives hors ligne (min.)", rule.minConsecutiveOfflineScans, 1..30) { v ->
                    save { it.copy(autoPurge = rule.copy(minConsecutiveOfflineScans = v)) }
                }
                LabeledSlider("Jours hors ligne (min.)", rule.minOfflineDays, 0..90, format = { if (it == 0) "peu importe" else "$it j" }) { v ->
                    save { it.copy(autoPurge = rule.copy(minOfflineDays = v)) }
                }
                LabeledSlider("Maximum par exécution", rule.maxDevicesPerRun, 1..500, step = 1) { v ->
                    save { it.copy(autoPurge = rule.copy(maxDevicesPerRun = v)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sourcesDialog = true }) {
                        Text(if (rule.sources.isEmpty()) "Sources : toutes" else "Sources : ${rule.sources.size}")
                    }
                    OutlinedButton(onClick = { typesDialog = true }) {
                        Text(if (rule.excludeTypes.isEmpty()) "Types exclus : aucun" else "Types exclus : ${rule.excludeTypes.size}")
                    }
                }
                SwitchRow("Inclure les appareils désactivés", rule.includeDisabled) { v -> save { it.copy(autoPurge = rule.copy(includeDisabled = v)) } }
                SwitchRow(
                    "Seulement si toute la source est hors ligne",
                    rule.onlyDeadSources,
                    description = "Ne cible que les appareils dont la skill ou le hub n'a plus aucun appareil joignable.",
                ) { v -> save { it.copy(autoPurge = rule.copy(onlyDeadSources = v)) } }
                if (s.scanIntervalHours <= 0) {
                    Text(
                        "Les analyses planifiées sont désactivées : la suppression automatique ne s'exécutera jamais.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionTitle("Valeurs par défaut de suppression")
            val d = s.purgeDefaults
            LabeledSlider("Pause entre deux suppressions", d.delayMs.toInt(), 100..3000, step = 100, format = { "$it ms" }) { v ->
                save { it.copy(purgeDefaults = d.copy(delayMs = v.toLong())) }
            }
            LabeledSlider("Nouvelles tentatives", d.maxRetries, 0..6) { v -> save { it.copy(purgeDefaults = d.copy(maxRetries = v)) } }
            SwitchRow("Sauvegarde JSON avant suppression", d.backupBeforePurge) { v -> save { it.copy(purgeDefaults = d.copy(backupBeforePurge = v)) } }
            if (d.method == PurgeMethod.WIPE_AND_REDISCOVER) {
                TextButton(onClick = { save { it.copy(purgeDefaults = d.copy(method = PurgeMethod.SEQUENTIAL)) } }) {
                    Text("Méthode par défaut : oubli global → repasser à « un par un »")
                }
            }

            SectionTitle("Exports et sauvegardes")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.exportJson()?.let { vm.share(it) } }, enabled = state.snapshot != null) { Text("Partager JSON") }
                OutlinedButton(onClick = { vm.exportCsv()?.let { vm.share(it) } }, enabled = state.snapshot != null) { Text("Partager CSV") }
            }
            TextButton(onClick = { showFiles = true }) { Text("Fichiers exportés et sauvegardes (${vm.exportFiles().size})") }

            SectionTitle("Diagnostic")
            Text(
                "Teste chaque point d'API Amazon utilisé (liste, GraphQL, statut, suppression sur un identifiant inexistant) et " +
                    "note le code HTTP et le début de la réponse dans le journal technique. Aucun appareil n'est modifié.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { vm.runDiagnostics(); showDebug = true }, enabled = !state.loading) { Text("Tester les points d'API Amazon") }
            TextButton(onClick = { vm.refreshDebugLog(); showDebug = !showDebug }) { Text(if (showDebug) "Masquer le journal technique" else "Afficher le journal technique") }
            if (showDebug) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        state.debugLog.takeLast(80).forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    }
                }
                TextButton(onClick = { vm.share(vm.exportDebugLog()) }) { Text("Partager le journal technique") }
            }

            SectionTitle("À propos")
            Text(
                "Cette application n'est pas affiliée à Amazon. Elle s'appuie sur l'API privée d'alexa.amazon.* utilisée par l'application Alexa officielle, " +
                    "documentée par la communauté (alexa-remote-control, alexa-cookie2, alexapy). Amazon peut la modifier sans préavis : " +
                    "si la liste ne se charge plus, une mise à jour de l'application sera nécessaire. Utilisation à vos risques.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (sourcesDialog) {
        MultiSelectDialog(
            title = "Sources concernées par l'auto-suppression",
            options = state.sources,
            selected = s.autoPurge.sources,
            onDismiss = { sourcesDialog = false },
            onConfirm = { sel -> save { it.copy(autoPurge = it.autoPurge.copy(sources = sel)) }; sourcesDialog = false },
        )
    }
    if (typesDialog) {
        MultiSelectDialog(
            title = "Types jamais supprimés automatiquement",
            options = state.types,
            selected = s.autoPurge.excludeTypes,
            labelOf = ::typeLabel,
            onDismiss = { typesDialog = false },
            onConfirm = { sel -> save { it.copy(autoPurge = it.autoPurge.copy(excludeTypes = sel)) }; typesDialog = false },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("Le jeton de session et la liste en cache seront supprimés de l'appareil. Les analyses planifiées seront arrêtées.") },
            confirmButton = { TextButton(onClick = { vm.logout(); confirmLogout = false }) { Text("Déconnexion") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Annuler") } },
        )
    }
    if (confirmHistory) {
        AlertDialog(
            onDismissRequest = { confirmHistory = false },
            title = { Text("Effacer l'historique des analyses ?") },
            text = { Text("Les durées hors ligne mesurées repartiront de zéro.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmHistory = false }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text("Annuler") } },
        )
    }
    if (showFiles) {
        FilesDialog(files = vm.exportFiles(), onShare = vm::share, onSave = vm::saveToDownloads, onDismiss = { showFiles = false })
    }
}

@Composable
private fun FilesDialog(files: List<File>, onShare: (File) -> Unit, onSave: (File) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fichiers exportés") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (files.isEmpty()) Text("Aucun fichier pour le moment.")
                files.forEach { f ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall)
                        Text("${f.length() / 1024} Ko · ${formatDate(f.lastModified())}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onShare(f) }) { Text("Partager") }
                            TextButton(onClick = { onSave(f) }) { Text("→ Téléchargements") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
