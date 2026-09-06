package com.orphybel.alexacleaner.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orphybel.alexacleaner.auth.LoginActivity
import com.orphybel.alexacleaner.core.model.Region
import com.orphybel.alexacleaner.ui.screens.DevicesScreen
import com.orphybel.alexacleaner.ui.screens.EchoScreen
import com.orphybel.alexacleaner.ui.screens.LoginScreen
import com.orphybel.alexacleaner.ui.screens.LogsScreen
import com.orphybel.alexacleaner.ui.screens.PurgeScreen
import com.orphybel.alexacleaner.ui.screens.SettingsScreen
import com.orphybel.alexacleaner.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var pendingLogin: LoginRequest? = null

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pendingLogin
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && request != null) {
            val code = data.getStringExtra(LoginActivity.RESULT_CODE)
            if (code != null) {
                vm.completeLogin(request, code, data.getStringExtra(LoginActivity.RESULT_COOKIES), data.getStringExtra(LoginActivity.RESULT_FRC))
            }
        }
        pendingLogin = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppTheme {
                AppRoot(vm = vm, onLogin = ::startLogin)
            }
        }
    }

    private fun startLogin(region: Region) {
        val request = vm.beginLogin(region)
        pendingLogin = request
        loginLauncher.launch(vm.loginIntent(request))
    }
}

@Composable
fun AppRoot(vm: MainViewModel, onLogin: (Region) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.dismissMessages()
        }
    }

    if (!state.loggedIn) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            LoginScreen(
                modifier = Modifier.padding(padding),
                defaultRegion = state.region,
                inProgress = state.loginInProgress,
                onLogin = onLogin,
            )
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.screen != Screen.PURGE) {
                NavigationBar {
                    NavigationBarItem(
                        selected = state.screen == Screen.DEVICES,
                        onClick = { vm.navigate(Screen.DEVICES) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Appareils") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.ECHOS,
                        onClick = { vm.navigate(Screen.ECHOS) },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("Echo") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.LOGS,
                        onClick = { vm.navigate(Screen.LOGS) },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        label = { Text("Journal") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.SETTINGS,
                        onClick = { vm.navigate(Screen.SETTINGS) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Réglages") },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state.screen) {
            Screen.DEVICES -> DevicesScreen(modifier, state, vm)
            Screen.ECHOS -> EchoScreen(modifier, state, vm)
            Screen.PURGE -> PurgeScreen(modifier, state, vm)
            Screen.LOGS -> LogsScreen(modifier, state, vm)
            Screen.SETTINGS -> SettingsScreen(modifier, state, vm)
        }
    }
}
