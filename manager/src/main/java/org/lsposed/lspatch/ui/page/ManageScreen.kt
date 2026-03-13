package org.lsposed.lspatch.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.result.ResultRecipient
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.LSPApplication
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.ui.component.CenterTopBar
import org.lsposed.lspatch.ui.page.destinations.SelectAppsScreenDestination
import org.lsposed.lspatch.ui.page.manage.AppManageBody
import org.lsposed.lspatch.ui.page.manage.AppManageFab
import org.lsposed.lspatch.ui.page.manage.ModuleManageBody
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.lspatch.util.DhizukuApi
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPagerApi::class)
@Destination
@Composable
fun ManageScreen(
    navigator: DestinationsNavigator,
    resultRecipient: ResultRecipient<SelectAppsScreenDestination, SelectAppsResult>
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState()

    Scaffold(
        topBar = { CenterTopBar(stringResource(org.lsposed.lspatch.ui.page.BottomBarDestination.Manage.label)) },
        floatingActionButton = { if (pagerState.currentPage == 0) AppManageFab(navigator) }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            Column {
                // nouveau : indicateur + actions rapides pour la méthode d'installation
                InstallMethodIndicator()

                TabRow(
                    contentColor = MaterialTheme.colorScheme.secondary,
                    selectedTabIndex = pagerState.currentPage
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 16.dp),
                            text = stringResource(R.string.apps)
                        )
                    }
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 16.dp),
                            text = stringResource(R.string.modules)
                        )
                    }
                }

                HorizontalPager(count = 2, state = pagerState) { page ->
                    when (page) {
                        0 -> AppManageBody(navigator, resultRecipient)
                        1 -> ModuleManageBody()
                    }
                }
            }
        }
    }
}

// --- Constantes de clé (doivent correspondre à SettingsScreen) ---
private const val PREF_INSTALL_METHOD = "install_method"
private const val INSTALL_AUTO = "AUTO"
private const val INSTALL_SHIZUKU = "SHIZUKU"
private const val INSTALL_DHIZUKU = "DHIZUKU"
private const val INSTALL_ROOT = "ROOT"

/**
 * Petit composant qui affiche la méthode d'install active et fournit des actions rapides :
 * - demander la permission Shizuku si sélectionnée
 * - initialiser Dhizuku si sélectionnée
 *
 * Ce composant est purement UX : il n'altère pas la logique d'installation existante.
 */
@Composable
private fun InstallMethodIndicator() {
    val context = LocalContext.current
    val prefs = lspApp.prefs
    val scope = rememberCoroutineScope()

    // lecture "à la volée" de la préférence (non réactive si modifiée ailleurs ; ok pour quick UI)
    var method by remember { mutableStateOf(prefs.getString(PREF_INSTALL_METHOD, INSTALL_AUTO) ?: INSTALL_AUTO) }

    // Mettre à jour la valeur lorsque l'écran redevient visible pourrait être fait en ajoutant un
    // listener SharedPreferences, mais pour conserver la simplicité on rafraîchit lors des recompositions
    DisposableEffect(Unit) {
        onDispose { /* rien */ }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icône statut
            when (method) {
                INSTALL_SHIZUKU -> {
                    if (ShizukuApi.isPermissionGranted) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Shizuku OK", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Outlined.Warning, contentDescription = "Shizuku Missing", tint = MaterialTheme.colorScheme.error)
                    }
                }
                INSTALL_DHIZUKU -> {
                    if (DhizukuApi.isPermissionGranted) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Dhizuku OK", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Outlined.Warning, contentDescription = "Dhizuku Missing", tint = MaterialTheme.colorScheme.error)
                    }
                }
                INSTALL_ROOT -> {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = "Root (assume)", tint = MaterialTheme.colorScheme.primary)
                }
                else -> {
                    // AUTO
                    // show a neutral icon
                    Icon(Icons.Outlined.Settings, contentDescription = "Auto", tint = Color.Unspecified)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (method) {
                        INSTALL_SHIZUKU -> "Install method: Shizuku"
                        INSTALL_DHIZUKU -> "Install method: Dhizuku"
                        INSTALL_ROOT -> "Install method: Root"
                        else -> "Install method: Auto (Dhizuku → Shizuku → Root)"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                // statut détaillé
                Text(
                    text = when (method) {
                        INSTALL_SHIZUKU -> if (ShizukuApi.isPermissionGranted) "Shizuku permission granted" else "Shizuku permission not granted"
                        INSTALL_DHIZUKU -> if (DhizukuApi.isPermissionGranted) "Dhizuku permission granted" else "Dhizuku not available / permission not granted"
                        INSTALL_ROOT -> "Using device root (if available)"
                        else -> {
                            val s = if (DhizukuApi.isPermissionGranted) "Dhizuku available" else if (ShizukuApi.isPermissionGranted) "Shizuku available" else "No privileged backend available"
                            "Auto: $s"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Actions : request permission / init Dhizuku
            if (method == INSTALL_SHIZUKU && !ShizukuApi.isPermissionGranted) {
                Button(onClick = {
                    // request permission — Shizuku.requestPermission must be called from UI thread
                    try {
                        if (ShizukuApi.isBinderAvailable && !ShizukuApi.isPermissionGranted) {
                            Shizuku.requestPermission(114514)
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    // refresh local read
                    method = prefs.getString(PREF_INSTALL_METHOD, INSTALL_AUTO) ?: INSTALL_AUTO
                }) {
                    Text("Request Shizuku")
                }
            } else if (method == INSTALL_DHIZUKU && !DhizukuApi.isPermissionGranted) {
                Button(onClick = {
                    // Try init Dhizuku SDK (may prompt or set state)
                    try {
                        DhizukuApi.init(context)
                    } catch (_: Throwable) {}
                    method = prefs.getString(PREF_INSTALL_METHOD, INSTALL_AUTO) ?: INSTALL_AUTO
                }) {
                    Text("Init Dhizuku")
                }
            } else {
                // small spacer to keep layout balanced
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}