package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
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
        title = Tr.tr("运行日志", "Log di esecuzione", "Run log", "Registro de ejecución", "Journal d'exécution", "Registo de execução", "Registro de execução", "Дневник на изпълнението", "Çalışma günlüğü", "Laufprotokoll"),
        fullscreen = true,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_backspace),
                    contentDescription = Tr.tr("返回", "Indietro", "Back", "Atrás", "Retour", "Voltar", "Voltar", "Назад", "Geri", "Zurück"),
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
                            title = Tr.tr("导出日志", "Esporta log", "Export log", "Exportar registro", "Exporter le journal", "Exportar registo", "Exportar registro", "Експортиране на дневника", "Günlüğü dışa aktar", "Protokoll exportieren"),
                            summary = Tr.tr("通过系统文档保存器导出诊断记录", "Esporta il log diagnostico tramite il salvataggio documenti di sistema", "Export the diagnostic log through the system document saver", "Exporta el registro de diagnóstico con el guardado de documentos del sistema", "Exporter le journal de diagnostic via l'enregistreur de documents du système", "Exportar o registo de diagnóstico com o gravador de documentos do sistema", "Exportar o registro de diagnóstico pelo salvador de documentos do sistema", "Експортиране на диагностичния дневник чрез системния инструмент за запазване на документи", "Tanılama günlüğünü sistemin belge kaydedicisiyle dışa aktar", "Diagnoseprotokoll über den Dokumentspeicher des Systems exportieren"),
                            startAction = { PreferenceIcon(R.drawable.ic_file_upload) },
                            enabled = state.logs.isNotEmpty(),
                            onClick = { if(owner.get() && latestState.logs.isNotEmpty()) onExportLogs() },
                        )
                        ArrowPreference(
                            title = Tr.tr("清空日志", "Svuota log", "Clear log", "Vaciar registro", "Vider le journal", "Limpar registo", "Limpar registro", "Изчистване на дневника", "Günlüğü temizle", "Protokoll leeren"),
                            summary = Tr.tr("清除本次运行的诊断记录", "Cancella il log diagnostico di questa sessione", "Clear this session's diagnostic log", "Borrar el registro de diagnóstico de esta sesión", "Effacer le journal de diagnostic de cette session", "Apagar o registo de diagnóstico desta sessão", "Apagar o registro de diagnóstico desta sessão", "Изтриване на диагностичния дневник за тази сесия", "Bu oturumun tanılama günlüğünü sil", "Diagnoseprotokoll dieser Sitzung löschen"),
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
            item(key = "log_count") { SectionHeading(Tr.tr("日志记录（${state.logs.size}）", "Voci di log (${state.logs.size})", "Log entries (${state.logs.size})", "Entradas del registro (${state.logs.size})", "Entrées du journal (${state.logs.size})", "Entradas do registo (${state.logs.size})", "Entradas do registro (${state.logs.size})", "Записи в дневника (${state.logs.size})", "Günlük kayıtları (${state.logs.size})", "Protokolleinträge (${state.logs.size})")) }
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
                                        text = Tr.tr("暂无运行日志，连接设备或执行操作后生成。", "Ancora nessun log: viene generato dopo aver connesso un dispositivo o eseguito operazioni.", "No log yet: it's created after connecting a device or performing operations.", "Aún no hay registro: se crea tras conectar un dispositivo o hacer operaciones.", "Pas encore de journal : il est créé après la connexion d'un appareil ou des opérations.", "Ainda não há registo: é criado depois de ligar um dispositivo ou fazer operações.", "Ainda não há registro: ele é criado depois de conectar um dispositivo ou fazer operações.", "Още няма дневник: създава се след свързване на устройство или извършване на операции.", "Henüz günlük yok: bir cihaz bağladıktan veya işlem yaptıktan sonra oluşturulur.", "Noch kein Protokoll: es entsteht nach dem Verbinden eines Geräts oder nach Vorgängen."),
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
