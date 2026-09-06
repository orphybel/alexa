package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.domain.DeviceInsight
import com.orphybel.alexacleaner.core.domain.PurgeOptions
import com.orphybel.alexacleaner.core.domain.SortKey
import com.orphybel.alexacleaner.core.domain.StatusFilter
import com.orphybel.alexacleaner.ui.MainViewModel
import com.orphybel.alexacleaner.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(modifier: Modifier, state: UiState, vm: MainViewModel) {
    var showSearch by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sourcesDialog by remember { mutableStateOf(false) }
    var typesDialog by remember { mutableStateOf(false) }
    var offlineDialog by remember { mutableStateOf(false) }
    var purgeDialog by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<DeviceInsight?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Appareils connectés")
                    val total = state.insights.size
                    Text(
                        if (state.snapshot == null) "Aucune donnée" else "$total au total · ${state.offlineCount} hors ligne · ${state.filtered.size} affichés",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            actions = {
                IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Default.Search, contentDescription = "Rechercher") }
                IconButton(onClick = { vm.refresh() }, enabled = !state.loading) { Icon(Icons.Default.Refresh, contentDescription = "Actualiser") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Trier") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortKey.values().forEach { key ->
                            DropdownMenuItem(
                                text = { Text((if (state.filter.sort == key) "✓ " else "") + "Trier par ${key.label.lowercase()}") },
                                onClick = { vm.updateFilter { it.copy(sort = key) }; sortMenu = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (state.filter.sortDescending) "✓ Ordre inverse" else "Ordre inverse") },
                            onClick = { vm.updateFilter { it.copy(sortDescending = !it.sortDescending) }; sortMenu = false },
                        )
                        DropdownMenuItem(text = { Text("Réinitialiser les filtres") }, onClick = { vm.resetFilter(); sortMenu = false })
                    }
                }
            },
        )

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (showSearch) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = { q -> vm.updateFilter { it.copy(query = q) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Nom, fabricant, type, identifiant…") },
                trailingIcon = {
                    IconButton(onClick = { vm.updateFilter { it.copy(query = "") }; showSearch = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                },
            )
        }

        FilterBar(
            state = state,
            vm = vm,
            onSources = { sourcesDialog = true },
            onTypes = { typesDialog = true },
            onOffline = { offlineDialog = true },
        )

        SelectionBar(state, vm)

        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            when {
                state.snapshot == null && state.loading -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Chargement de la liste depuis Amazon… Cela peut prendre une minute si le compte contient beaucoup d'appareils.")
                }
                state.snapshot == null -> Column(Modifier.padding(32.dp)) {
                    Text("Aucune liste chargée.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.refresh() }) { Text("Charger les appareils") }
                }
                state.filtered.isEmpty() -> Text(
                    "Aucun appareil ne correspond aux filtres.",
                    Modifier.padding(32.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.filtered, key = { it.id }) { insight ->
                        DeviceRow(
                            insight = insight,
                            checked = insight.id in state.selected,
                            onToggle = { vm.toggleSelected(insight.id) },
                            onOpen = { details = insight },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        if (state.selected.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${state.selected.size} sélectionné(s)", modifier = Modifier.weight(1f))
                    Button(onClick = { purgeDialog = true }, enabled = !state.purgeRunning) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.purgeRunning) "Suppression en cours" else "Supprimer")
                    }
                }
            }
        } else if (state.purgeRunning) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Une suppression est en cours", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.navigate(com.orphybel.alexacleaner.ui.Screen.PURGE) }) { Text("Voir") }
                }
            }
        }
    }

    if (sourcesDialog) {
        MultiSelectDialog(
            title = "Sources (skills, hubs, fabricants)",
            options = state.sources,
            selected = state.filter.sources,
            onDismiss = { sourcesDialog = false },
            onConfirm = { sel -> vm.updateFilter { it.copy(sources = sel) }; sourcesDialog = false },
        )
    }
    if (typesDialog) {
        MultiSelectDialog(
            title = "Types d'appareils",
            options = state.types,
            selected = state.filter.types,
            labelOf = ::typeLabel,
            onDismiss = { typesDialog = false },
            onConfirm = { sel -> vm.updateFilter { it.copy(types = sel) }; typesDialog = false },
        )
    }
    if (offlineDialog) {
        OfflineDurationDialog(state, vm) { offlineDialog = false }
    }
    if (purgeDialog) {
        PurgeDialog(
            count = state.selected.size,
            totalDevices = state.insights.size,
            defaults = state.settings.purgeDefaults,
            onDismiss = { purgeDialog = false },
            onConfirm = { options: PurgeOptions -> purgeDialog = false; vm.startPurge(options) },
        )
    }
    details?.let { insight ->
        DeviceDetailsDialog(
            insight = insight,
            onDismiss = { details = null },
            onDeleteOnly = {
                details = null
                vm.startPurge(state.settings.purgeDefaults.copy(dryRun = false), listOf(insight.device))
            },
        )
    }
}

@Composable
private fun FilterBar(state: UiState, vm: MainViewModel, onSources: () -> Unit, onTypes: () -> Unit, onOffline: () -> Unit) {
    val f = state.filter
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusFilter.values().forEach { s ->
            val count = when (s) {
                StatusFilter.ALL -> state.insights.size
                StatusFilter.OFFLINE -> state.offlineCount
                StatusFilter.ONLINE -> state.insights.size - state.offlineCount - state.unknownCount
                StatusFilter.UNKNOWN -> state.unknownCount
            }
            FilterChip(selected = f.status == s, onClick = { vm.updateFilter { it.copy(status = s) } }, label = { Text("${s.label} ($count)") })
        }
        FilterChip(
            selected = f.sources.isNotEmpty(),
            onClick = onSources,
            label = { Text(if (f.sources.isEmpty()) "Sources" else "Sources (${f.sources.size})") },
        )
        FilterChip(
            selected = f.types.isNotEmpty(),
            onClick = onTypes,
            label = { Text(if (f.types.isEmpty()) "Types" else "Types (${f.types.size})") },
        )
        FilterChip(
            selected = f.minOfflineDays > 0 || f.minOfflineScans > 0,
            onClick = onOffline,
            label = {
                Text(
                    when {
                        f.minOfflineDays > 0 && f.minOfflineScans > 0 -> "Hors ligne ≥ ${f.minOfflineDays} j / ${f.minOfflineScans} analyses"
                        f.minOfflineDays > 0 -> "Hors ligne ≥ ${f.minOfflineDays} j"
                        f.minOfflineScans > 0 -> "Hors ligne ≥ ${f.minOfflineScans} analyses"
                        else -> "Durée hors ligne"
                    },
                )
            },
        )
        FilterChip(
            selected = f.onlyDuplicates,
            onClick = { vm.updateFilter { it.copy(onlyDuplicates = !it.onlyDuplicates) } },
            label = { Text("Doublons (${state.duplicateCount})") },
        )
        FilterChip(
            selected = f.onlyDeadSources,
            onClick = { vm.updateFilter { it.copy(onlyDeadSources = !it.onlyDeadSources) } },
            label = { Text("Sources entièrement hors ligne") },
        )
        FilterChip(
            selected = f.onlyDisabled,
            onClick = { vm.updateFilter { it.copy(onlyDisabled = !it.onlyDisabled) } },
            label = { Text("Désactivés") },
        )
    }
}

@Composable
private fun SelectionBar(state: UiState, vm: MainViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sélection :", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { vm.selectAllFiltered() }, enabled = state.filtered.isNotEmpty()) { Text("Tous les affichés (${state.filtered.size})") }
        TextButton(onClick = { vm.invertSelectionInFiltered() }, enabled = state.filtered.isNotEmpty()) { Text("Inverser") }
        TextButton(onClick = { vm.clearSelection() }, enabled = state.selected.isNotEmpty()) { Text("Aucun") }
    }
}

@Composable
private fun DeviceRow(insight: DeviceInsight, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val d = insight.device
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(d.friendlyName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = buildList {
                add(d.source)
                add(typeLabel(d.primaryType))
                if (!d.isEnabled) add("désactivé")
                if (insight.isDuplicate) add("doublon")
                if (insight.sourceIsDead) add("source hors ligne")
                insight.offlineDays?.let { days ->
                    add(if (days == 0) "hors ligne (vu aujourd'hui)" else "hors ligne depuis $days j")
                }
                if (insight.consecutiveOfflineScans > 1) add("${insight.consecutiveOfflineScans} analyses")
            }
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val color = statusColor(d)
        Box(
            Modifier
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(statusLabel(d), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OfflineDurationDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(state.filter.minOfflineDays) }
    var scans by remember { mutableStateOf(state.filter.minOfflineScans) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Durée hors ligne") },
        text = {
            Column {
                Text(
                    "Amazon ne fournit pas de date de dernière connexion fiable. L'application mesure elle-même la durée hors ligne " +
                        "à partir de ses analyses (${state.history.scanCount} effectuée(s) à ce jour). Activez les analyses planifiées dans les réglages pour affiner.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider("Hors ligne depuis au moins", days, 0..90, format = { if (it == 0) "peu importe" else "$it jour(s)" }) { days = it }
                LabeledSlider("Analyses consécutives hors ligne", scans, 0..30, format = { if (it == 0) "peu importe" else "$it" }) { scans = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.updateFilter { it.copy(minOfflineDays = days, minOfflineScans = scans) }; onDismiss() }) { Text("Appliquer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun DeviceDetailsDialog(insight: DeviceInsight, onDismiss: () -> Unit, onDeleteOnly: () -> Unit) {
    val d = insight.device
    var showRaw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.friendlyName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailLine("Statut", statusLabel(d))
                DetailLine("Source", d.source)
                DetailLine("Fabricant", d.manufacturerName ?: "—")
                DetailLine("Modèle", d.modelName ?: "—")
                DetailLine("Description", d.friendlyDescription ?: "—")
                DetailLine("Types", d.applianceTypes.joinToString { typeLabel(it) }.ifBlank { "—" })
                DetailLine("Activé", if (d.isEnabled) "oui" else "non")
                DetailLine("Connecté via", d.connectedVia ?: "—")
                DetailLine("Skill", d.skillId ?: "—")
                DetailLine("Ajouté le", formatDate(d.createdAt))
                DetailLine("Vu par Amazon le", formatDate(d.lastSeenAt))
                insight.history?.let { h ->
                    DetailLine("Vu en ligne par l'app", formatDate(h.lastOnlineAt))
                    DetailLine("Analyses hors ligne", "${h.consecutiveOfflineScans} consécutives / ${h.offlineScans} sur ${h.totalScans}")
                }
                DetailLine("Identifiant", d.applianceId)
                DetailLine("Entity ID", d.entityId ?: "—")
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Masquer le JSON brut" else "Voir le JSON brut") }
                if (showRaw) {
                    Text(d.raw ?: "(non conservé)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDeleteOnly) { Text("Supprimer cet appareil") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label : ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
