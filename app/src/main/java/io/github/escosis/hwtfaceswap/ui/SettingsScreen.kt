package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.R
import io.github.escosis.hwtfaceswap.ThemeMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(
    state: AppUiState,
    actions: AppActions,
    insets: PaddingValues,
    onNavigateToAbout: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onOverlayExpandedChange: (Boolean) -> Unit = {},
    scrollModifier: Modifier = Modifier,
) {
    var themeExpanded by remember { mutableStateOf(false) }
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val currentOnOverlayExpandedChange by rememberUpdatedState(onOverlayExpandedChange)
    fun normalSettingsAllowed(): Boolean = owner.get() && !latestState.introductionPending
    SideEffect { currentOnOverlayExpandedChange(themeExpanded && !state.introductionPending) }
    LaunchedEffect(state.introductionPending) { if(state.introductionPending) themeExpanded = false }
    DisposableEffect(owner) { onDispose { owner.set(false); currentOnOverlayExpandedChange(false) } }

    LazyColumn(
        modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets),
        contentPadding = pageContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "app_config") {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "Connessione automatica all'avvio",
                    summary = "All'avvio dell'app connetti l'ultimo watch connesso correttamente",
                    checked = state.autoConnect,
                    enabled = !state.introductionPending,
                    onCheckedChange = { if(normalSettingsAllowed()) latestActions.setAutoConnect(it) },
                    startAction = { PreferenceIcon(R.drawable.ic_link) },
                )
            }
        }
        item(key = "theme") {
            Card(Modifier.fillMaxWidth()) {
                if(state.introductionPending) {
                    // 直接移除普通 popup owner，避免仅 enabled=false 仍保留库内部已展开状态。
                    BasicComponent(title = "Tema", summary = "Scegli l'aspetto dell'app", enabled = false,
                        startAction = { PreferenceIcon(R.drawable.ic_palette, "Tema") })
                } else OverlayDropdownPreference(
                    title = "Tema",
                    summary = "Scegli l'aspetto dell'app",
                    items = listOf("Segui il sistema", "Chiaro", "Scuro"),
                    startAction = { PreferenceIcon(R.drawable.ic_palette, "Tema") },
                    selectedIndex = state.theme.ordinal,
                    onExpandedChange = { themeExpanded = it && normalSettingsAllowed() },
                    onSelectedIndexChange = { if(normalSettingsAllowed()) latestActions.setTheme(ThemeMode.entries[it]) },
                )
            }
        }
        item(key = "update") {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "Controllo automatico aggiornamenti",
                        summary = "Controlla nuove versioni all'avvio dell'app",
                        checked = state.autoCheckUpdate,
                        enabled = !state.introductionPending,
                        onCheckedChange = { if(normalSettingsAllowed()) latestActions.setAutoCheckUpdate(it) },
                        startAction = { PreferenceIcon(R.drawable.ic_round_autorenew) },
                    )
                    ArrowPreference(
                        title = "Controlla aggiornamenti",
                        summary = state.updateMessage.ifBlank { "Versione attuale ${state.appVersion}" },
                        startAction = { PreferenceIcon(R.drawable.ic_round_system_update_alt) },
                        enabled = !state.introductionPending && !state.checkingUpdate,
                        onClick = { if(normalSettingsAllowed() && !latestState.checkingUpdate) latestActions.checkUpdates() },
                    )
                }
            }
        }
        item(key = "logs_entry") {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "Log di esecuzione",
                    startAction = { PreferenceIcon(R.drawable.ic_logs, "Log di esecuzione") },
                    enabled = !state.introductionPending,
                    onClick = { if(normalSettingsAllowed()) onNavigateToLogs() },
                )
            }
        }
        item(key = "about_entry") {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "Informazioni",
                    startAction = { PreferenceIcon(R.drawable.ic_round_info, "Informazioni") },
                    enabled = !state.introductionPending,
                    onClick = { if(normalSettingsAllowed()) onNavigateToAbout() },
                )
            }
        }
    }
}

@Composable
internal fun PreferenceIcon(iconRes: Int, contentDescription: String? = null) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = Modifier.padding(end = 6.dp).size(24.dp),
        tint = MiuixTheme.colorScheme.onSurface,
    )
}
