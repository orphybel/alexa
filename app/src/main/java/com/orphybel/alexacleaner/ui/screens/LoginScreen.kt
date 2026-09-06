package com.orphybel.alexacleaner.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orphybel.alexacleaner.core.model.Region

@Composable
fun LoginScreen(modifier: Modifier, defaultRegion: Region, inProgress: Boolean, onLogin: (Region, Boolean) -> Unit) {
    var region by remember { mutableStateOf(defaultRegion) }
    var menu by remember { mutableStateOf(false) }
    var regionalSignIn by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Alexa Cleaner", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Supprime en masse les appareils connectés fantômes ou hors ligne de votre compte Alexa.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        Text("Marketplace Amazon", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth(), enabled = !inProgress) {
                Text(region.label, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Region.ALL.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label) }, onClick = { region = r; menu = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "Page de connexion sur ${region.amazonHost}",
            regionalSignIn,
            description = if (regionalSignIn) {
                "Mode alternatif : la page de connexion est ouverte sur le domaine régional."
            } else {
                "Recommandé : la page de connexion est ouverte sur www.amazon.com (compte global), " +
                    "puis les cookies ${region.cookieDomain} sont obtenus automatiquement. Activez ce mode seulement si l'autre échoue."
            },
        ) { regionalSignIn = it }
        Spacer(Modifier.height(16.dp))

        if (inProgress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Enregistrement de la session…", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onClick = { onLogin(region, regionalSignIn) }, modifier = Modifier.fillMaxWidth()) {
                Text("Se connecter avec Amazon")
            }
        }

        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("À savoir avant de continuer", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "• La connexion s'effectue sur la page officielle d'Amazon, dans une vue web intégrée ; " +
                        "le mot de passe n'est jamais lu par cette application.\n" +
                        "• L'application utilise l'API privée du site alexa.amazon.* (la même que l'application Alexa). " +
                        "Amazon ne la documente pas et peut la modifier à tout moment.\n" +
                        "• Une suppression est définitive : un appareil supprimé par erreur doit être redécouvert " +
                        "depuis sa skill ou son hub, et perd son nom personnalisé, ses groupes et ses routines.\n" +
                        "• Une sauvegarde JSON de la liste est proposée avant chaque suppression.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
