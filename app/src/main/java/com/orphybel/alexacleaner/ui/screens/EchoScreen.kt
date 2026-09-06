package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.ui.MainViewModel
import com.orphybel.alexacleaner.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoScreen(modifier: Modifier, state: UiState, vm: MainViewModel) {
    val echos = state.snapshot?.echos ?: emptyList()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Appareils Echo / Alexa")
                    Text("${echos.size} enregistrés · ${echos.count { !it.online }} hors ligne", style = MaterialTheme.typography.bodySmall)
                }
            },
            actions = { IconButton(onClick = { vm.refresh() }, enabled = !state.loading) { Icon(Icons.Default.Refresh, contentDescription = "Actualiser") } },
        )
        Text(
            "Lecture seule : la désinscription d'un Echo passe par une autre API Amazon (« Gérer votre contenu et vos appareils ») " +
                "que cette application n'automatise pas. Les appareils fantômes se trouvent presque toujours dans l'onglet Appareils connectés.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(echos, key = { it.serialNumber }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.accountName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(e.deviceFamily, e.deviceType, e.softwareVersion?.let { "v$it" }, e.serialNumber).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (e.online) "En ligne" else "Hors ligne",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (e.online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
