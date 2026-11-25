package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.NearbyWatch
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal enum class HistoryConnectResult { STARTED, CONFIGURE_ID, UNAVAILABLE }

// 退出期间只保留最后一帧的非敏感展示资格；动作始终另走当前窗口/设备守卫。
private data class HistoryOperationPresentation(
    val name: String,
    val address: String,
    val connected: Boolean,
    val targetConnected: Boolean,
    val unpairing: Boolean,
    val canConnect: Boolean,
    val canEditHuaweiId: Boolean,
    val canUnpair: Boolean,
)

/** 历史宿主中的独立 keyed 面板；非共享调用仍使用默认真实 Miuix WindowDialog。 */
@Composable
internal fun HistoryOperationDialog(
    state: AppUiState,
    device: NearbyWatch,
    show: Boolean,
    incarnation: Long,
    canConnect: Boolean,
    canEditHuaweiId: Boolean,
    canUnpair: Boolean,
    isCurrent: () -> Boolean,
    onConfigureHuaweiId: () -> Boolean,
    onConnect: () -> HistoryConnectResult,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    inHistoryWindow: Boolean = false,
) {
    val currentPresentation = HistoryOperationPresentation(
        name = device.name.ifBlank { "Watch senza nome" }.uiText(),
        address = device.address,
        connected = state.connected,
        targetConnected = device.address.equals(state.deviceAddress, true),
        unpairing = state.unpairing,
        canConnect = canConnect,
        canEditHuaweiId = canEditHuaweiId,
        canUnpair = canUnpair,
    )
    var lastPresentation by remember(incarnation) { mutableStateOf(currentPresentation) }
    var feedback by remember(incarnation) { mutableStateOf("") }
    SideEffect { if (show) lastPresentation = currentPresentation }
    val presentation = if (show) currentPresentation else lastPresentation

    val panelContent: @Composable () -> Unit = {
        val dismiss = LocalDismissState.current
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HuaweiIdDeviceCard(presentation.name, presentation.address)
            ActionButton(
                text = "Connessione al watch",
                onClick = {
                    if (isCurrent() && canConnect) {
                        when (onConnect()) {
                            HistoryConnectResult.STARTED -> Unit
                            HistoryConnectResult.CONFIGURE_ID -> onConfigureHuaweiId()
                            HistoryConnectResult.UNAVAILABLE -> if (isCurrent()) {
                                feedback = "Al momento non è possibile connettere questo dispositivo; controlla la configurazione o riprova più tardi."
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = presentation.canConnect,
            )
            if (presentation.connected) {
                MutedText(
                    if (presentation.targetConnected) "Questo dispositivo è già connesso."
                    else "Disconnetti prima la connessione attuale, poi connetti questo dispositivo.",
                )
            }
            ActionButton(
                text = "Configura ID Huawei",
                onClick = { if (isCurrent() && canEditHuaweiId) onConfigureHuaweiId() },
                modifier = Modifier.fillMaxWidth(),
                enabled = presentation.canEditHuaweiId,
            )
            ActionButton(
                text = "Annulla abbinamento",
                onClick = { if (isCurrent() && canUnpair) onUnpair() },
                modifier = Modifier.fillMaxWidth(),
                destructive = true,
                enabled = presentation.canUnpair,
            )
            if (presentation.unpairing) MutedText("Un dispositivo attende la verifica dell'annullamento abbinamento; completa prima il controllo nello stato qui sotto.")
            if (feedback.isNotBlank()) MutedText(feedback)
            // LocalDismissState 是 0.9.3 提供的可空、无参 dismiss 请求；不是动画完成通知。
            ActionButton("Chiudi", onClick = { if (isCurrent()) dismiss?.invoke() }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (inHistoryWindow) panelContent()
    else WindowDialog(show = show, title = "Azioni dispositivo", onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished, content = panelContent)
}

/** 非敏感的最后展示快照。明文不进入此模型、更不进入待切换窗口描述符。 */
private data class HuaweiIdPresentation(
    val name: String,
    val address: String,
    val dirty: Boolean,
    val configured: Boolean,
    val connected: Boolean,
    val canEdit: Boolean,
    val canConnect: Boolean,
    val canSave: Boolean,
    val feedback: String,
)

/** 选中设备的全局编辑值与历史 MAC 的本地草稿共用全部字段、按钮和布局。 */
@Composable
internal fun HuaweiIdDialog(
    show: Boolean,
    incarnation: Long,
    name: String,
    address: String,
    value: String,
    dirty: Boolean,
    configured: Boolean,
    connected: Boolean,
    canEdit: Boolean,
    canConnect: Boolean,
    isCurrent: () -> Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    feedback: String = "",
    inHistoryWindow: Boolean = false,
) {
    val currentPresentation = HuaweiIdPresentation(
        name, address, dirty, configured, connected, canEdit, canConnect,
        canSave = canEdit && value.isNotBlank(), feedback = feedback,
    )
    var lastPresentation by remember(incarnation) { mutableStateOf(currentPresentation) }
    // 仅窗口存活期间的普通 remember：退出仍显示掩码，实际退出/移除时清空，不使用 rememberSaveable。
    var lastValue by remember(incarnation) { mutableStateOf(value) }
    var revealId by remember(incarnation) { mutableStateOf(false) }
    val acquisition = rememberHuaweiIdAcquisition(
        ownerKey = "$incarnation:$address",
        enabled = show && canEdit,
        isCurrent = isCurrent,
        onAcquired = { id ->
            if (show && isCurrent() && canEdit) {
                revealId = true
                onValueChange(id)
            }
        },
    )
    SideEffect {
        if (show) {
            lastPresentation = currentPresentation
            lastValue = value
        }
    }
    DisposableEffect(incarnation) {
        onDispose { lastValue = ""; revealId = false }
    }
    val presentation = if (show) currentPresentation else lastPresentation
    val presentedValue = if (show) value else lastValue
    LaunchedEffect(incarnation, feedback, show) {
        if (show && isCurrent() && presentation.feedback.isNotBlank()) {
            acquisition.notify(presentation.feedback)
        }
    }
    // show=false 的第一帧同步遮蔽，不等 LaunchedEffect；不靠清空字段来改变退出中的按钮资格。
    val visiblyRevealed = show && revealId

    val panelContent: @Composable () -> Unit = {
        val dismiss = LocalDismissState.current
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HuaweiIdDeviceCard(presentation.name, presentation.address)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ID Huawei di questo dispositivo",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Badge(
                    text = when {
                        presentation.dirty -> "Da salvare"
                        presentation.configured -> "Salvato"
                        else -> "Da configurare"
                    },
                    highlighted = presentation.configured && !presentation.dirty,
                )
            }
            MiuixField(
                value = presentedValue,
                onValueChange = { if (isCurrent() && canEdit && !acquisition.busy) onValueChange(it) },
                label = "Inserisci ID Huawei",
                enabled = presentation.canEdit && !acquisition.busy,
                retainOutlineWhenDisabled = true,
                keyboardType = KeyboardType.Text,
                visualTransformation = if (visiblyRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { if (isCurrent() && !acquisition.busy) revealId = !revealId },
                        modifier = Modifier.padding(horizontal = 6.dp),
                        enabled = !acquisition.busy,
                    ) {
                        Text(
                            if (visiblyRevealed) "Nascondi" else "Mostra",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
            )
            MutedText(
                if (presentation.connected) "Salva le modifiche; avranno effetto alla prossima connessione."
                else "Inserisci per questo dispositivo lo stesso ID Huawei usato in Huawei Health.",
            )
            ActionButton(
                text = "Ottieni ID Huawei con un tocco",
                onClick = acquisition.start,
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = show && presentation.canEdit && !acquisition.busy,
            )
            if (!presentation.connected) {
                ActionButton(
                    text = "Connessione al watch",
                    onClick = { if (isCurrent() && canConnect && !acquisition.busy) onConnect() },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = presentation.canConnect && !acquisition.busy,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Chiudi", onClick = { if (isCurrent()) dismiss?.invoke() }, modifier = Modifier.weight(1f))
                ActionButton(
                    text = "Salva ID",
                    onClick = { if (isCurrent() && canEdit && !acquisition.busy) onSave() },
                    modifier = Modifier.weight(1f),
                    primary = presentation.connected,
                    enabled = presentation.canSave && !acquisition.busy,
                )
            }
        }
    }
    if (inHistoryWindow) panelContent()
    else WindowDialog(
        show = show,
        title = "Configura ID Huawei",
        onDismissRequest = onDismiss,
        onDismissFinished = {
            lastValue = ""
            revealId = false
            onDismissFinished()
        },
        content = panelContent,
    )
}

/** 只在独立配置窗口真正打开后读取目标 MAC；没有改选、断开或借用另一台设备的编辑器。 */
@Composable
internal fun HistoryHuaweiIdDialog(
    state: AppUiState,
    device: NearbyWatch,
    show: Boolean,
    incarnation: Long,
    canConnect: Boolean,
    canEditHuaweiId: Boolean,
    isCurrent: () -> Boolean,
    onReadHuaweiId: () -> String?,
    onSaveHuaweiId: (String) -> Boolean,
    onSaveAndConnect: (String) -> Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    inHistoryWindow: Boolean = false,
    isOwnerCurrent: () -> Boolean = isCurrent,
) {
    var draft by remember(incarnation) { mutableStateOf("") }
    var savedId by remember(incarnation) { mutableStateOf("") }
    var loaded by remember(incarnation) { mutableStateOf(false) }
    var feedback by remember(incarnation) { mutableStateOf("") }
    // 可提前测量空的独立面板，但 show=false 不读 ID；只在旧面板销毁且父守卫消费后启动读取。
    LaunchedEffect(incarnation, show) {
        if (!show || !isOwnerCurrent()) return@LaunchedEffect
        val id = onReadHuaweiId()
        if (!show || !isOwnerCurrent()) return@LaunchedEffect
        if (id == null) feedback = "Impossibile leggere l'ID Huawei di questo dispositivo, riprova più tardi."
        else {
            draft = id
            savedId = id
            loaded = true
        }
    }
    DisposableEffect(incarnation) {
        onDispose { draft = ""; savedId = ""; loaded = false; feedback = "" }
    }
    HuaweiIdDialog(
        show = show,
        incarnation = incarnation,
        name = device.name.ifBlank { "Watch senza nome" }.uiText(),
        address = device.address,
        value = draft,
        dirty = draft.trim() != savedId,
        configured = savedId.isNotBlank(),
        connected = state.connected,
        canEdit = canEditHuaweiId && loaded,
        canConnect = canConnect && loaded && draft.isNotBlank(),
        isCurrent = isCurrent,
        onValueChange = { if (isCurrent() && canEditHuaweiId) { draft = it; feedback = "" } },
        onSave = {
            if (isCurrent() && canEditHuaweiId && loaded) {
                val value = draft.trim()
                if (onSaveHuaweiId(value) && isCurrent()) {
                    draft = value
                    savedId = value
                    feedback = ""
                } else if (isCurrent()) feedback = "ID Huawei non salvato; controlla i dati inseriti o riprova più tardi."
            }
        },
        onConnect = {
            if (isCurrent() && canConnect && loaded && !onSaveAndConnect(draft.trim()) && isCurrent()) {
                feedback = "Al momento non è possibile connettere questo dispositivo; controlla la configurazione o riprova più tardi."
            }
        },
        onDismiss = onDismiss,
        onDismissFinished = {
            draft = ""
            savedId = ""
            loaded = false
            feedback = ""
            onDismissFinished()
        },
        feedback = feedback,
        inHistoryWindow = inHistoryWindow,
    )
}

@Composable
private fun HuaweiIdDeviceCard(name: String, address: String) {
    SectionCard {
        Text(
            text = name,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = address,
            modifier = Modifier.fillMaxWidth(),
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            softWrap = true,
        )
    }
}
