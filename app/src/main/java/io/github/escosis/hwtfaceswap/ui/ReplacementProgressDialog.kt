package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.OperationInfo
import io.github.escosis.hwtfaceswap.ReplacementDialogInfo
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun pageOperationCardVisible(operation: OperationInfo): Boolean =
    operation.kind != "direct_install" && operation.kind != "replacement" && operation.kind != "deletion" && operation.kind != "activation" &&
        (operation.active || operation.failed || operation.stage.isNotBlank())

internal fun replacementDialogSameOwner(rendered: ReplacementDialogInfo, current: ReplacementDialogInfo?): Boolean =
    current != null && rendered.operation.kind == current.operation.kind &&
        rendered.token == current.token && rendered.connectionEpoch == current.connectionEpoch &&
        rendered.targetId == current.targetId && rendered.targetVersion == current.targetVersion

internal fun replacementDialogCanDismiss(ownerAlive: Boolean, rendered: ReplacementDialogInfo, current: ReplacementDialogInfo?): Boolean =
    ownerAlive && replacementDialogSameOwner(rendered, current) && current?.operation?.active == false

internal fun replacementDialogSucceeded(info: ReplacementDialogInfo): Boolean =
    !info.operation.active && !info.operation.failed && when (info.operation.kind) {
        "replacement", "direct_install" -> info.operation.stage == "LISTED"
        "deletion" -> info.operation.stage == "DELETED"
        "activation" -> info.operation.stage == "ACTIVATED"
        else -> false
    }

internal fun operationDialogTitle(info: ReplacementDialogInfo): String = when(info.operation.kind) {
    "direct_install" -> Tr.tr("安装表盘", "Installa quadrante", "Install watch face", "Instalar esfera", "Installer le cadran", "Instalar mostrador", "Instalar mostrador", "Инсталиране на циферблат", "Kadran yükle", "Zifferblatt installieren", "Установить циферблат")
    "deletion" -> Tr.tr("删除表盘", "Elimina quadrante", "Delete watch face", "Eliminar esfera", "Supprimer le cadran", "Eliminar mostrador", "Excluir mostrador", "Изтриване на циферблат", "Kadranı sil", "Zifferblatt löschen", "Удалить циферблат")
    "activation" -> Tr.tr("启用表盘", "Attiva quadrante", "Activate watch face", "Activar esfera", "Activer le cadran", "Ativar mostrador", "Ativar mostrador", "Активиране на циферблат", "Kadranı etkinleştir", "Zifferblatt aktivieren", "Активировать циферблат")
    else -> Tr.tr("替换表盘", "Sostituisci quadrante", "Replace watch face", "Sustituir esfera", "Remplacer le cadran", "Substituir mostrador", "Substituir mostrador", "Замяна на циферблат", "Kadranı değiştir", "Zifferblatt ersetzen", "Заменить циферблат")
}

internal fun operationDialogStatusText(info: ReplacementDialogInfo): String = when {
    info.operation.kind == "direct_install" && info.operation.stage == "COMPLETED" && !info.operation.active && !info.operation.failed ->
        if (info.operation.detail.startsWith(Tr.tr("客户端直装流程已结束", "Procedura di installazione diretta lato app terminata", "Client-side direct install finished", "Instalación directa por parte de la app terminada", "Installation directe côté app terminée", "Instalação direta do lado da app terminada", "Instalação direta pelo app terminada", "Директното инсталиране от приложението приключи", "Uygulama tarafındaki doğrudan yükleme bitti", "App-seitige Direktinstallation beendet", "Прямая установка со стороны приложения завершена"))) info.operation.detail
        else io.github.escosis.hwtfaceswap.protocol.DirectInstallState.completionNotice(info.operation.detail)
    replacementDialogSucceeded(info) -> when(info.operation.kind) { "direct_install" -> Tr.tr("表盘已安装", "Quadrante installato", "Watch face installed", "Esfera instalada", "Cadran installé", "Mostrador instalado", "Mostrador instalado", "Циферблатът е инсталиран", "Kadran yüklendi", "Zifferblatt installiert", "Циферблат установлен"); "deletion" -> Tr.tr("表盘已删除", "Quadrante eliminato", "Watch face deleted", "Esfera eliminada", "Cadran supprimé", "Mostrador eliminado", "Mostrador excluído", "Циферблатът е изтрит", "Kadran silindi", "Zifferblatt gelöscht", "Циферблат удалён"); "activation" -> Tr.tr("表盘已启用", "Quadrante attivato", "Watch face activated", "Esfera activada", "Cadran activé", "Mostrador ativado", "Mostrador ativado", "Циферблатът е активиран", "Kadran etkinleştirildi", "Zifferblatt aktiviert", "Циферблат активирован"); else -> Tr.tr("表盘已替换", "Quadrante sostituito", "Watch face replaced", "Esfera sustituida", "Cadran remplacé", "Mostrador substituído", "Mostrador substituído", "Циферблатът е заменен", "Kadran değiştirildi", "Zifferblatt ersetzt", "Циферблат заменён") }
    info.operation.stage == "CANCELLED" -> when(info.operation.kind) { "direct_install" -> Tr.tr("安装已取消", "Installazione annullata", "Installation cancelled", "Instalación cancelada", "Installation annulée", "Instalação cancelada", "Instalação cancelada", "Инсталирането е отменено", "Yükleme iptal edildi", "Installation abgebrochen", "Установка отменена"); "deletion" -> Tr.tr("删除已取消", "Eliminazione annullata", "Deletion cancelled", "Eliminación cancelada", "Suppression annulée", "Eliminação cancelada", "Exclusão cancelada", "Изтриването е отменено", "Silme iptal edildi", "Löschen abgebrochen", "Удаление отменено"); "activation" -> Tr.tr("启用已取消", "Attivazione annullata", "Activation cancelled", "Activación cancelada", "Activation annulée", "Ativação cancelada", "Ativação cancelada", "Активирането е отменено", "Etkinleştirme iptal edildi", "Aktivierung abgebrochen", "Активация отменена"); else -> Tr.tr("替换已取消", "Sostituzione annullata", "Replacement cancelled", "Sustitución cancelada", "Remplacement annulé", "Substituição cancelada", "Substituição cancelada", "Замяната е отменена", "Değiştirme iptal edildi", "Ersetzen abgebrochen", "Замена отменена") }
    else -> info.operation.detail.ifBlank { info.operation.stage.ifBlank {
        when(info.operation.kind) { "deletion" -> Tr.tr("等待删除状态", "In attesa dello stato di eliminazione", "Waiting for deletion status", "Esperando el estado de la eliminación", "En attente de l'état de la suppression", "A aguardar o estado da eliminação", "Aguardando o status da exclusão", "Изчакване на състоянието на изтриването", "Silme durumu bekleniyor", "Warten auf den Löschstatus", "Ожидание состояния удаления"); "activation" -> Tr.tr("等待启用状态", "In attesa dello stato di attivazione", "Waiting for activation status", "Esperando el estado de la activación", "En attente de l'état de l'activation", "A aguardar o estado da ativação", "Aguardando o status da ativação", "Изчакване на състоянието на активирането", "Etkinleştirme durumu bekleniyor", "Warten auf den Aktivierungsstatus", "Ожидание состояния активации"); else -> Tr.tr("等待替换状态", "In attesa dello stato di sostituzione", "Waiting for replacement status", "Esperando el estado de la sustitución", "En attente de l'état du remplacement", "A aguardar o estado da substituição", "Aguardando o status da substituição", "Изчакване на състоянието на замяната", "Değiştirme durumu bekleniyor", "Warten auf den Ersetzungsstatus", "Ожидание состояния замены") }
    } }
}

internal fun replacementDialogProperties(info: ReplacementDialogInfo): DialogProperties = DialogProperties(
    dismissOnBackPress = !info.operation.active,
    dismissOnClickOutside = false,
)

/** Native Dialog consumes active Back itself; no library predictive-back translation is installed. */
@Composable
internal fun ReplacementProgressDialog(state: AppUiState, actions: AppActions) {
    // 共享最新快照必须先于 keyed/early-return 分支：旧 key 退休前也要看见新任务或高优先级窗口。
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    val info = state.operationDialog ?: return
    if (state.prompt != null || state.needsPin) return
    key(info.operation.kind, info.token) {
        val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
        DisposableEffect(owner) { onDispose { owner.set(false) } }
        fun dismiss() {
            val current = latestState
            if (current.prompt == null && !current.needsPin && replacementDialogCanDismiss(owner.get(), info, current.operationDialog)) {
                when(info.operation.kind) {
                    "deletion" -> latestActions.dismissDeletionResult(info.token)
                    "activation" -> latestActions.dismissActivationResult(info.token)
                    else -> latestActions.dismissReplacementResult(info.token)
                }
            }
        }
        Dialog(
            onDismissRequest = ::dismiss,
            properties = replacementDialogProperties(info),
        ) {
            Card(
                modifier = Modifier.widthIn(max = DialogDefaults.MaxWidth).fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                cornerRadius = 32.dp,
                insideMargin = PaddingValues(horizontal = DialogDefaults.insideMargin.width, vertical = DialogDefaults.insideMargin.height),
                colors = CardDefaults.defaultColors(
                    color = DialogDefaults.backgroundColor(),
                    contentColor = DialogDefaults.titleColor(),
                ),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(operationDialogTitle(info), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Medium)
                    if (info.title.isNotBlank()) Text(info.title.uiText(), style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if ((info.operation.kind == "replacement" || info.operation.kind == "direct_install") && info.fileName.isNotBlank()) Text(
                        Tr.tr("HWT 文件：${info.fileName.uiText()}", "File HWT: ${info.fileName.uiText()}", "HWT file: ${info.fileName.uiText()}", "Archivo HWT: ${info.fileName.uiText()}", "Fichier HWT : ${info.fileName.uiText()}", "Ficheiro HWT: ${info.fileName.uiText()}", "Arquivo HWT: ${info.fileName.uiText()}", "HWT файл: ${info.fileName.uiText()}", "HWT dosyası: ${info.fileName.uiText()}", "HWT-Datei: ${info.fileName.uiText()}", "Файл HWT: ${info.fileName.uiText()}"), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val succeeded = replacementDialogSucceeded(info)
                    val stageText = operationDialogStatusText(info)
                    Text(
                        stageText.uiText(), style = MiuixTheme.textStyles.body1,
                        color = when {
                            succeeded -> MiuixTheme.colorScheme.primary
                            info.operation.failed -> MiuixTheme.colorScheme.error
                            else -> MiuixTheme.colorScheme.onSurface
                        },
                    )
                    if (info.operation.active) {
                        val percent = info.operation.progress?.coerceIn(0, 100)
                        val progress = percent?.div(100f)
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().semantics {
                                progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate
                                    else ProgressBarRangeInfo(progress, 0f..1f)
                            },
                        )
                        if (percent != null) MutedText("$percent%")
                    } else {
                        Spacer(Modifier.height(2.dp))
                        ActionButton(Tr.tr("关闭", "Chiudi", "Close", "Cerrar", "Fermer", "Fechar", "Fechar", "Затваряне", "Kapat", "Schließen", "Закрыть"), ::dismiss, Modifier.fillMaxWidth(), primary = true)
                    }
                }
            }
        }
    }
}
