package io.github.escosis.hwtfaceswap.ui

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
    "direct_install" -> "Installa quadrante"
    "deletion" -> "Elimina quadrante"
    "activation" -> "Attiva quadrante"
    else -> "Sostituisci quadrante"
}

internal fun operationDialogStatusText(info: ReplacementDialogInfo): String = when {
    info.operation.kind == "direct_install" && info.operation.stage == "COMPLETED" && !info.operation.active && !info.operation.failed ->
        if (info.operation.detail.startsWith("Procedura di installazione diretta lato app terminata")) info.operation.detail
        else io.github.escosis.hwtfaceswap.protocol.DirectInstallState.completionNotice(info.operation.detail)
    replacementDialogSucceeded(info) -> when(info.operation.kind) { "direct_install" -> "Quadrante installato"; "deletion" -> "Quadrante eliminato"; "activation" -> "Quadrante attivato"; else -> "Quadrante sostituito" }
    info.operation.stage == "CANCELLED" -> when(info.operation.kind) { "direct_install" -> "Installazione annullata"; "deletion" -> "Eliminazione annullata"; "activation" -> "Attivazione annullata"; else -> "Sostituzione annullata" }
    else -> info.operation.detail.ifBlank { info.operation.stage.ifBlank {
        when(info.operation.kind) { "deletion" -> "In attesa dello stato di eliminazione"; "activation" -> "In attesa dello stato di attivazione"; else -> "In attesa dello stato di sostituzione" }
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
                        "File HWT: ${info.fileName.uiText()}", style = MiuixTheme.textStyles.body2,
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
                        ActionButton("Chiudi", ::dismiss, Modifier.fillMaxWidth(), primary = true)
                    }
                }
            }
        }
    }
}
