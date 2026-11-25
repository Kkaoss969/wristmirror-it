package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置中的独立日志页，保留脱敏原文和系统文档导出入口。 */
@Composable
internal fun LogsScreen(
    state: AppUiState,
    actions: AppActions,
    onExportLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val latestState by rememberUpdatedState(state)
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(owner) { onDispose { owner.set(false) } }
    val busy = logsBusy(state)
    CollapsingPage(
        title = "Log di esecuzione",
        fullscreen = true,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_backspace),
                    contentDescription = "Indietro",
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    ) { insets, scrollModifier ->
        LazyColumn(
            modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets),
            contentPadding = pageContentPadding(insets),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "log_actions") {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = "Esporta log",
                            summary = "Esporta il log diagnostico tramite il salvataggio documenti di sistema",
                            startAction = { PreferenceIcon(R.drawable.ic_file_upload) },
                            enabled = state.logs.isNotEmpty(),
                            onClick = { if(owner.get() && latestState.logs.isNotEmpty()) onExportLogs() },
                        )
                        ArrowPreference(
                            title = "Svuota log",
                            summary = "Cancella il log diagnostico di questa sessione",
                            startAction = { PreferenceIcon(R.drawable.ic_clean) },
                            enabled = !busy && state.logs.isNotEmpty(),
                            onClick = {
                                val current = latestState
                                if(owner.get() && !logsBusy(current) && current.logs.isNotEmpty()) actions.clearLogs()
                            },
                        )
                    }
                }
            }
            item(key = "log_count") { SectionHeading("Voci di log (${state.logs.size})") }
            item(key = "log_text") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if(state.logs.isEmpty()) {
                                item(key = "empty_logs") {
                                    Text(
                                        text = "Ancora nessun log: viene generato dopo aver connesso un dispositivo o eseguito operazioni.",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                            } else {
                                items(state.logs) { line ->
                                    Text(
                                        text = line,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun logsBusy(state: AppUiState): Boolean =
    state.operation.active || state.connecting || state.scanning || state.prompt != null || state.needsPin
