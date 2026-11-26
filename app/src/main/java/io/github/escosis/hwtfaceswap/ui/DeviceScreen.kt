package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.NearbyWatch
import io.github.escosis.hwtfaceswap.UiPolicy
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// 历史配置的“连接”先保存指定 MAC；仅等待该次保存的回显，不把明文放进待执行动作。
private data class HistoryIdConnectRequest(
    val source: DeviceDialog,
    val observedRevision: Long,
    val expectedRevision: Long,
    val ownerEpoch: Long,
)

// 待切换动作只记录明确点击的设备、局部代次与回显版本，不携带华为 ID 草稿。
private data class DeviceSelectionRequest(
    val device: NearbyWatch,
    val reconnect: Boolean,
    val observedRevision: Long,
    val observedEpoch: Long,
    val ownerEpoch: Long,
    val unpairToken: Long,
)

@Composable
internal fun DeviceScreen(
    state: AppUiState,
    actions: AppActions,
    insets: PaddingValues,
    onScan: () -> Unit,
    onConnect: (NearbyWatch, String) -> Unit,
    onOpenFaces: () -> Unit,
    onWindowExpandedChange: (Boolean) -> Unit = {},
    scrollModifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<DeviceDialog?>(null) }
    var exitingDialog by remember { mutableStateOf<DeviceDialog?>(null) }
    var pendingWindow by remember { mutableStateOf<PendingDeviceWindow?>(null) }
    var selectionRequest by remember { mutableStateOf<DeviceSelectionRequest?>(null) }
    var historyIdConnectRequest by remember { mutableStateOf<HistoryIdConnectRequest?>(null) }
    var query by remember { mutableStateOf("") }
    val latestState by rememberUpdatedState(state)
    val latestContext by rememberUpdatedState(LocalContext.current)
    val latestActions by rememberUpdatedState(actions)
    val latestOnScan by rememberUpdatedState(onScan)
    val latestOnConnect by rememberUpdatedState(onConnect)
    val latestOnOpenFaces by rememberUpdatedState(onOpenFaces)
    val callbackOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val dialogEpoch = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val selectedDevice = selectedWatch(state)
    val canSelect = canSelectWatch(state)
    val localWindowsAllowed = canShowDeviceWindow(state)
    val openedDialog = dialog
    val effectiveDialog = openedDialog?.let {
        isDeviceDialogCurrent(it, dialog, state, callbackOwner.get(), dialogEpoch.get())
    } == true
    val effectiveExit = dialog == null && exitingDialog?.let { canRetainDeviceWindow(it, state, callbackOwner.get()) } == true
    val effectivePending = pendingWindow?.let {
        isPendingDeviceWindowCurrent(it, pendingWindow, exitingDialog, dialog, state, callbackOwner.get(), dialogEpoch.get())
    } == true
    // 真实窗口退出、下一窗口和选择/保存回显均占用局部窗口；Native 确认/PIN 始终立即优先。
    val windowExpanded = localWindowsAllowed && (effectiveDialog || effectiveExit || effectivePending ||
        ((selectionRequest != null || historyIdConnectRequest != null) && canSelect))
    val latestOnWindowExpandedChange by rememberUpdatedState(onWindowExpandedChange)
    SideEffect { if (callbackOwner.get()) latestOnWindowExpandedChange(windowExpanded) }
    DisposableEffect(callbackOwner) {
        onDispose {
            callbackOwner.set(false)
            latestOnWindowExpandedChange(false)
        }
    }

    fun canUseTopControl(control: DeviceControl, rendered: AppUiState, renderedLocalEpoch: Long): Boolean =
        DeviceControlsPolicy.canAct(rendered, latestState, control, callbackOwner.get(), renderedLocalEpoch, dialogEpoch.get(),
            dialog != null || exitingDialog != null || pendingWindow != null || selectionRequest != null || historyIdConnectRequest != null)

    fun showTopControlHint(control: DeviceControl, rendered: AppUiState, renderedLocalEpoch: Long) {
        val hint = DeviceControlsPolicy.hintIfCurrent(rendered, latestState, control, callbackOwner.get(), renderedLocalEpoch, dialogEpoch.get(),
            dialog != null || exitingDialog != null || pendingWindow != null || selectionRequest != null || historyIdConnectRequest != null) ?: return
        // 与真实动作彻底分离：不关闭窗口、不扫描/选择/连接/解除配对，也不打开配置。
        Toast.makeText(latestContext, hint.text, Toast.LENGTH_SHORT).show()
    }

    fun newDialog(window: DeviceWindow, address: String? = null, hostEpoch: Long? = null): DeviceDialog {
        val epoch = dialogEpoch.incrementAndGet()
        return DeviceDialog(window, address, epoch, latestState.connectionEpoch,
            latestState.selectedDeviceAddress, latestState.unpairToken, hostEpoch ?: epoch)
    }

    fun isCurrentWindow(expected: DeviceDialog, current: AppUiState = latestState): Boolean =
        isDeviceDialogCurrent(expected, dialog, current, callbackOwner.get(), dialogEpoch.get())

    fun isCurrentPendingWindow(expected: PendingDeviceWindow, current: AppUiState = latestState): Boolean =
        isPendingDeviceWindowCurrent(expected, pendingWindow, exitingDialog, dialog, current, callbackOwner.get(), dialogEpoch.get())

    fun closeDialog(animateExit: Boolean = false) {
        if (!callbackOwner.get()) return
        val opened = dialog
        val retain = opened?.takeIf { animateExit && isCurrentWindow(it) }
        // 先使所有动作同步失效；历史面板在真实宿主内下退，其余窗口仍走库的 show=false。
        dialog = null
        pendingWindow = null
        selectionRequest = null
        historyIdConnectRequest = null
        exitingDialog = retain
        dialogEpoch.incrementAndGet()
        if (opened?.window == DeviceWindow.DISCOVERY && latestState.scanning) latestActions.stopScan()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun cancelHistoryHost(expectedHost: DeviceDialog): Boolean {
        if (!canCancelHistoryWindow(expectedHost, dialog, exitingDialog, pendingWindow,
                latestState, callbackOwner.get(), dialogEpoch.get())) return false
        // Back/外点是宿主级取消，不借用旧面板动作。立即撤销待切换/回显，不发起任何设备操作。
        closeDialog()
        return true
    }

    fun finishDialogExit(expected: DeviceDialog, completedHandoff: PendingDeviceWindow? = null, retiredPanelEpoch: Long? = null): Boolean {
        // 旧面板完全下退并销毁之后才消费切换；旧 A/B 或同 A 重开的回调不能复活下一窗口。
        val pending = pendingWindow
        if (!canFinishDeviceWindowExit(expected, exitingDialog, dialog, pending, completedHandoff,
                latestState, callbackOwner.get(), dialogEpoch.get(), retiredPanelEpoch)) return false
        exitingDialog = null
        pendingWindow = null
        if (pending != null) dialog = pending.next
        return true
    }

    fun openHistoryWindow(device: NearbyWatch, expectedEpoch: Long) {
        val current = latestState
        if (!callbackOwner.get() || current.connectionEpoch != expectedEpoch || !canShowHistoryWindow(current) ||
            UiPolicy.isUnpairTarget(current, device.address)
        ) return
        val target = current.savedDevices.firstOrNull { it.address.equals(device.address, true) } ?: return
        // 仅打开本地元数据窗口：不选择设备、不改 B 的编辑器、不扫描或触发系统权限。
        closeDialog()
        dialog = newDialog(DeviceWindow.HISTORY, target.address)
    }

    fun configureHistoryHuaweiId(expected: DeviceDialog): Boolean {
        val address = expected.address ?: return false
        if (expected.window != DeviceWindow.HISTORY || !isCurrentWindow(expected) ||
            !canEditHistoryHuaweiId(latestState, address)
        ) return false
        closeDialog(animateExit = true)
        if (exitingDialog != expected) return false
        pendingWindow = PendingDeviceWindow(expected, newDialog(DeviceWindow.HISTORY_HUAWEI_ID, address, expected.hostEpoch), latestState.connectionFormRevision)
        // 不预读 ID：旧 keyed 面板完全低于底边且销毁后，才在同一真实宿主中创建独立配置代次并上移。
        return true
    }

    fun readHistoryHuaweiId(expected: DeviceDialog): String? {
        val current = latestState
        val address = expected.address ?: return null
        if (!isCurrentWindow(expected, current) || !canEditHistoryHuaweiId(current, address)) return null
        val device = current.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby() ?: return null
        val id = latestActions.readHuaweiIdForDevice(device)
        return id?.takeIf { isCurrentWindow(expected) && canEditHistoryHuaweiId(latestState, address) }
    }

    fun saveHistoryHuaweiId(expected: DeviceDialog, value: String): Boolean {
        val current = latestState
        val address = expected.address ?: return false
        if (expected.window != DeviceWindow.HISTORY_HUAWEI_ID || !isCurrentWindow(expected, current) ||
            !canEditHistoryHuaweiId(current, address)
        ) return false
        val device = current.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby() ?: return false
        return latestActions.saveHuaweiIdForDevice(device, value) && isCurrentWindow(expected)
    }

    fun saveAndConnectHistoryHuaweiId(expected: DeviceDialog, value: String): Boolean {
        val current = latestState
        val address = expected.address ?: return false
        if (!isCurrentWindow(expected, current) || !canSelectWatch(current) || value.isBlank()) return false
        if (!saveHistoryHuaweiId(expected, value)) return false
        // 同 A 的 per-MAC 保存会有一个全局回显；先消费该精确回显，再走原 select 的 revision+1 收据。
        val expectedRevision = current.connectionFormRevision + if (address.isSelectedIn(current)) 1L else 0L
        closeDialog()
        historyIdConnectRequest = HistoryIdConnectRequest(expected, current.connectionFormRevision, expectedRevision,
            dialogEpoch.incrementAndGet())
        return true
    }

    fun isCurrentHistoryIdConnectRequest(expected: HistoryIdConnectRequest, current: AppUiState): Boolean =
        callbackOwner.get() && historyIdConnectRequest == expected && dialogEpoch.get() == expected.ownerEpoch &&
            dialog == null && pendingWindow == null && selectionRequest == null &&
            canRetainDeviceWindow(expected.source, current, callbackOwner.get()) && canSelectWatch(current) &&
            (current.connectionFormRevision == expected.observedRevision || current.connectionFormRevision == expected.expectedRevision)

    fun openDiscovery() {
        if (!callbackOwner.get() || !canSelectWatch(latestState)) return
        closeDialog()
        query = ""
        dialog = newDialog(DeviceWindow.DISCOVERY)
        latestOnScan()
    }

    fun openSelectedWindow(window: DeviceWindow, expectedAddress: String) {
        val current = latestState
        if (!callbackOwner.get() || !canShowDeviceWindow(current) || !expectedAddress.isSelectedIn(current)) return
        if (window == DeviceWindow.HUAWEI_ID && !UiPolicy.canEditHuaweiId(current)) return
        closeDialog()
        dialog = newDialog(window, current.selectedDeviceAddress)
    }

    fun unpairObservedWatch(device: NearbyWatch, expectedEpoch: Long, expectedUnpairToken: Long) {
        val current = latestState
        if (!callbackOwner.get() || current.connectionEpoch != expectedEpoch || current.unpairToken != expectedUnpairToken ||
            !UiPolicy.canRequestUnpair(current)
        ) return
        val address = device.address
        val known = address.isSelectedIn(current) || current.savedDevices.any { it.address.equals(address, true) } ||
            current.devices.any { it.address.equals(address, true) }
        if (!known) return
        closeDialog()
        latestActions.requestUnpair(device)
    }

    fun chooseWatch(device: NearbyWatch, reconnect: Boolean, expectedWindow: DeviceDialog? = null): Boolean {
        val current = latestState
        if (!callbackOwner.get() || !canSelectWatch(current) || UiPolicy.isUnpairTarget(current, device.address) ||
            (expectedWindow != null && !isCurrentWindow(expectedWindow, current))
        ) return false
        val known = current.devices.any { it.address.equals(device.address, true) } ||
            current.savedDevices.any { it.address.equals(device.address, true) }
        if (!known) return false
        // 实际选择/连接/Native 操作不排队等局部动画；普通关闭与历史配置切换才保留退出。
        closeDialog()
        selectionRequest = DeviceSelectionRequest(device, reconnect, current.connectionFormRevision, current.connectionEpoch,
            dialogEpoch.incrementAndGet(), current.unpairToken)
        latestActions.selectDevice(device)
        return true
    }

    fun connectHistoryWatch(expected: DeviceDialog): HistoryConnectResult {
        val current = latestState
        val address = expected.address ?: return HistoryConnectResult.UNAVAILABLE
        if (!isCurrentWindow(expected, current) || !canSelectWatch(current) || UiPolicy.isUnpairTarget(current, address)) return HistoryConnectResult.UNAVAILABLE
        val device = current.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby() ?: return HistoryConnectResult.UNAVAILABLE
        val id = readHistoryHuaweiId(expected) ?: return HistoryConnectResult.UNAVAILABLE
        if (!isCurrentWindow(expected) || !canSelectWatch(latestState)) return HistoryConnectResult.UNAVAILABLE
        // 没有真实保存的 ID 时只在原窗口完整退出后配置 A，不先选择目标、更不发送空认证。
        if (id.isBlank()) return HistoryConnectResult.CONFIGURE_ID
        return if (chooseWatch(device, reconnect = true, expectedWindow = expected)) HistoryConnectResult.STARTED else HistoryConnectResult.UNAVAILABLE
    }

    fun isCurrentSelectionRequest(expected: DeviceSelectionRequest, current: AppUiState): Boolean =
        callbackOwner.get() && selectionRequest == expected && dialogEpoch.get() == expected.ownerEpoch &&
            current.connectionEpoch == expected.observedEpoch && current.unpairToken == expected.unpairToken &&
            canSelectWatch(current) && !current.pickingHwt && !UiPolicy.isUnpairTarget(current, expected.device.address) &&
            (!expected.reconnect || current.savedDevices.any { it.address.equals(expected.device.address, true) })

    fun connectSelectedWatch(expectedAddress: String, expectedEpoch: Long) {
        val current = latestState
        if (!callbackOwner.get() || current.connectionEpoch != expectedEpoch || !canSelectWatch(current) || !expectedAddress.isSelectedIn(current)) return
        val device = selectedWatch(current) ?: return
        val enteredId = current.huaweiIdInput.trim()
        if (enteredId.isBlank()) {
            openSelectedWindow(DeviceWindow.HUAWEI_ID, expectedAddress)
            return
        }
        closeDialog()
        if (current.scanning) latestActions.stopScan()
        // 先同步目标设备，再写回本次点击读取的最新草稿，避免切换回显覆盖输入。
        // 系统权限与华为 ID 的原确认仍由父界面的连接入口处理。
        latestActions.selectDevice(device)
        latestActions.setHuaweiId(enteredId)
        latestOnConnect(device, enteredId)
    }

    LaunchedEffect(selectionRequest, state.selectedDeviceAddress, state.connectionFormRevision, state.connectionEpoch,
        state.savedDevices, state.unpairToken, state.unpairing, state.unpairAddress, state.pickingHwt, localWindowsAllowed) {
        val request = selectionRequest ?: return@LaunchedEffect
        val current = latestState
        if (!isCurrentSelectionRequest(request, current)) {
            if (selectionRequest == request) selectionRequest = null
            return@LaunchedEffect
        }
        // 只接受本次 selectDevice 的单次回显；期间新的编辑/切换不能借旧点击发起权限请求。
        if (current.connectionFormRevision == request.observedRevision) return@LaunchedEffect
        if (current.connectionFormRevision != request.observedRevision + 1L || !request.device.address.isSelectedIn(current)) {
            selectionRequest = null
            return@LaunchedEffect
        }
        val device = request.device.copy(address = current.selectedDeviceAddress ?: request.device.address)
        if (request.reconnect) {
            val id = latestActions.readHuaweiIdForDevice(device)
            val afterRead = latestState
            if (!isCurrentSelectionRequest(request, afterRead) || afterRead.connectionFormRevision != request.observedRevision + 1L ||
                !request.device.address.isSelectedIn(afterRead)
            ) {
                if (selectionRequest == request) selectionRequest = null
                return@LaunchedEffect
            }
            selectionRequest = null
            if (id != null && id.isNotBlank() && afterRead.accountConfigured) latestOnConnect(device, "")
            else dialog = newDialog(DeviceWindow.HISTORY_HUAWEI_ID, device.address)
        } else {
            selectionRequest = null
            dialog = newDialog(DeviceWindow.HUAWEI_ID, device.address)
        }
    }

    LaunchedEffect(historyIdConnectRequest, state.selectedDeviceAddress, state.connectionFormRevision, state.connectionEpoch,
        state.savedDevices, state.unpairToken, state.unpairing, state.unpairAddress, state.pickingHwt, localWindowsAllowed) {
        val request = historyIdConnectRequest ?: return@LaunchedEffect
        val current = latestState
        if (!isCurrentHistoryIdConnectRequest(request, current)) {
            if (historyIdConnectRequest == request) historyIdConnectRequest = null
            return@LaunchedEffect
        }
        if (current.connectionFormRevision != request.expectedRevision) return@LaunchedEffect
        val device = current.savedDevices.firstOrNull { it.address.equals(request.source.address, true) }?.nearby()
        historyIdConnectRequest = null
        if (device != null) chooseWatch(device, reconnect = true)
    }

    LaunchedEffect(state.selectedDeviceAddress, state.connectionFormRevision, state.connectionEpoch, state.savedDevices, state.unpairToken,
        state.unpairing, state.unpairAddress, state.pickingHwt, localWindowsAllowed, canSelect) {
        val opened = dialog
        if (!localWindowsAllowed || (opened != null && !isCurrentWindow(opened))) {
            // Native 提示/配对码、外部改选、连接代次或清理代次变化：同步守卫已失效，立即移除宿主。
            closeDialog()
            return@LaunchedEffect
        }
        val exiting = exitingDialog
        val pending = pendingWindow
        if ((exiting != null && !canRetainDeviceWindow(exiting, latestState, callbackOwner.get())) ||
            (pending != null && !isCurrentPendingWindow(pending))
        ) {
            exitingDialog = null
            pendingWindow = null
            dialogEpoch.incrementAndGet()
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val topControlsEpoch = dialogEpoch.get()
    LazyColumn(
        modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets),
        contentPadding = PaddingValues(
            start = insets.calculateStartPadding(layoutDirection) + 12.dp,
            top = insets.calculateTopPadding() + 12.dp,
            end = insets.calculateEndPadding(layoutDirection) + 12.dp,
            bottom = insets.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "selected_watch") {
            SelectedWatchCard(
                state = state,
                device = selectedDevice,
                onConnect = {
                    if (canUseTopControl(DeviceControl.CONNECT, state, topControlsEpoch)) {
                        state.selectedDeviceAddress?.let { connectSelectedWatch(it, state.connectionEpoch) }
                    }
                },
                onConfigureId = {
                    if (canUseTopControl(DeviceControl.CONFIGURE_ID, state, topControlsEpoch)) {
                        state.selectedDeviceAddress?.let { openSelectedWindow(DeviceWindow.HUAWEI_ID, it) }
                    }
                },
                onUnpair = {
                    if (canUseTopControl(DeviceControl.UNPAIR, state, topControlsEpoch) && latestState.selectedDeviceAddress == state.selectedDeviceAddress) {
                        state.selectedDeviceAddress?.let { address ->
                            val target = selectedDevice ?: NearbyWatch(address, state.watchName.ifBlank { Tr.tr("未知名称设备", "Dispositivo senza nome", "Device with unknown name", "Dispositivo de nombre desconocido", "Appareil au nom inconnu", "Dispositivo de nome desconhecido", "Dispositivo de nome desconhecido", "Устройство с неизвестно име", "Adı bilinmeyen cihaz", "Gerät mit unbekanntem Namen", "Устройство с неизвестным именем") }, false)
                            unpairObservedWatch(target, state.connectionEpoch, state.unpairToken)
                        }
                    }
                },
                onRebind = {
                    val current = latestState
                    if (callbackOwner.get() && current.connectionEpoch == state.connectionEpoch &&
                        current.selectedDeviceAddress == state.selectedDeviceAddress && !current.connected && !current.connecting &&
                        canShowDeviceWindow(current) && !current.unpairing && !current.scanning
                    ) latestActions.rebindNow()
                },
                onDisabledHint = { control -> showTopControlHint(control, state, topControlsEpoch) },
                onOpenFaces = {
                    val current = latestState
                    if (callbackOwner.get() && current.connected && !current.quarantine && canShowDeviceWindow(current) &&
                        current.connectionEpoch == state.connectionEpoch && state.selectedDeviceAddress?.isSelectedIn(current) == true
                    ) latestOnOpenFaces()
                },
                onDisconnect = {
                    val current = latestState
                    if (callbackOwner.get() && current.connectionEpoch == state.connectionEpoch &&
                        state.selectedDeviceAddress?.isSelectedIn(current) == true &&
                        canUseConnectionActions(current) && (current.connected || current.connecting)
                    ) latestActions.disconnect()
                },
            )
        }
        if (pageOperationCardVisible(state.operation)) {
            item(key = "operation") { OperationCard(state.operation) }
        }
        if (state.unpairMessage.isNotBlank()) {
            item(key = "unpair_status") {
                SectionCard {
                    Text(Tr.tr("解除配对", "Annulla abbinamento", "Unpair", "Desemparejar", "Dissocier", "Desemparelhar", "Desfazer pareamento", "Разкачване", "Eşleştirmeyi kaldır", "Entkoppeln", "Снять сопряжение"), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    state.unpairAddress?.let { address ->
                        DeviceAddress(address)
                        Spacer(Modifier.height(8.dp))
                    }
                    MutedText(state.unpairMessage)
                    if (state.unpairAddress != null && state.unpairing) {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (state.unpairNeedsSettings) {
                                ActionButton(Tr.tr("系统蓝牙设置", "Impostazioni Bluetooth di sistema", "System Bluetooth settings", "Ajustes Bluetooth del sistema", "Paramètres Bluetooth du système", "Definições Bluetooth do sistema", "Configurações Bluetooth do sistema", "Системни настройки на Bluetooth", "Sistem Bluetooth ayarları", "Bluetooth-Einstellungen des Systems", "Настройки Bluetooth системы"), onClick = {
                                    if (callbackOwner.get() && latestState.unpairing && !latestState.unpairWorking && latestState.unpairAddress == state.unpairAddress && latestState.unpairToken == state.unpairToken) latestActions.openUnpairSettings()
                                }, modifier = Modifier.weight(1f), primary = true, enabled = !state.unpairWorking)
                            }
                            ActionButton(Tr.tr("重新检查", "Ricontrolla", "Check again", "Volver a comprobar", "Vérifier à nouveau", "Verificar de novo", "Verificar de novo", "Проверка пак", "Tekrar kontrol et", "Erneut prüfen", "Проверить снова"), onClick = {
                                if (callbackOwner.get() && latestState.unpairing && !latestState.unpairWorking && latestState.unpairAddress == state.unpairAddress && latestState.unpairToken == state.unpairToken) latestActions.retryUnpair()
                            }, modifier = Modifier.weight(1f), enabled = !state.unpairWorking)
                            if (state.unpairCancelable) {
                                ActionButton(Tr.tr("取消解除配对", "Revoca annullamento abbinamento", "Undo unpairing", "Deshacer desemparejamiento", "Annuler la dissociation", "Anular desemparelhamento", "Cancelar despareamento", "Отмяна на разкачването", "Eşleştirme kaldırmayı geri al", "Entkopplung rückgängig machen", "Отменить снятие сопряжения"), onClick = {
                                    if (callbackOwner.get() && latestState.unpairing && !latestState.unpairWorking && latestState.unpairCancelable && latestState.unpairToken == state.unpairToken) latestActions.cancelUnpair()
                                }, modifier = Modifier.weight(1f), enabled = !state.unpairWorking)
                            }
                        }
                    }
                }
            }
        }
        if (state.savedDevices.isNotEmpty()) {
            item(key = "saved_watches") {
                Column {
                    SectionHeading(Tr.tr("历史设备", "Dispositivi recenti", "Recent devices", "Dispositivos recientes", "Appareils récents", "Dispositivos recentes", "Dispositivos recentes", "Последни устройства", "Son cihazlar", "Letzte Geräte", "Недавние устройства"))
                    Card(Modifier.fillMaxWidth()) {
                        state.savedDevices.forEachIndexed { index, saved ->
                            val device = saved.nearby()
                            HistoryWatchPreference(
                                device = device,
                                summary = when {
                                    UiPolicy.isUnpairTarget(state, device.address) -> Tr.tr("解除配对待完成", "Annullamento abbinamento da completare", "Unpairing to complete", "Desemparejamiento por completar", "Dissociation à terminer", "Desemparelhamento por concluir", "Despareamento a concluir", "Разкачването предстои да завърши", "Tamamlanacak eşleştirme kaldırma", "Entkopplung abzuschließen", "Снятие сопряжения не завершено")
                                    state.connected && device.address.equals(state.deviceAddress, ignoreCase = true) -> Tr.tr("当前已连接", "Connesso ora", "Connected now", "Conectado ahora", "Connecté maintenant", "Ligado agora", "Conectado agora", "Свързано сега", "Şu anda bağlı", "Jetzt verbunden", "Подключено сейчас")
                                    device.address.isSelectedIn(state) && state.connecting -> Tr.tr("正在连接", "Connessione in corso", "Connecting", "Conectando", "Connexion…", "A ligar", "Conectando", "Свързване", "Bağlanıyor", "Verbinden", "Подключение")
                                    device.address.isSelectedIn(state) -> Tr.tr("已选择，点击查看操作", "Selezionato, tocca per le azioni", "Selected, tap for actions", "Seleccionado, toca para ver acciones", "Sélectionné, touchez pour les actions", "Selecionado, toque para ver ações", "Selecionado, toque para ver ações", "Избрано, докоснете за действия", "Seçildi, işlemler için dokunun", "Ausgewählt, für Aktionen tippen", "Выбрано, нажмите для действий")
                                    index == 0 -> Tr.tr("上次连接，点击查看操作", "Ultima connessione, tocca per le azioni", "Last connected, tap for actions", "Última conexión, toca para ver acciones", "Dernière connexion, touchez pour les actions", "Última ligação, toque para ver ações", "Última conexão, toque para ver ações", "Последна връзка, докоснете за действия", "Son bağlantı, işlemler için dokunun", "Zuletzt verbunden, für Aktionen tippen", "Последнее подключение, нажмите для действий")
                                    else -> Tr.tr("点击查看操作", "Tocca per le azioni", "Tap for actions", "Toca para ver acciones", "Touchez pour les actions", "Toque para ver ações", "Toque para ver ações", "Докоснете за действия", "İşlemler için dokunun", "Für Aktionen tippen", "Нажмите для действий")
                                },
                                enabled = canShowHistoryWindow(state) && !UiPolicy.isUnpairTarget(state, device.address),
                                onClick = { openHistoryWindow(device, state.connectionEpoch) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "add_watch") {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = Tr.tr("添加设备", "Aggiungi dispositivo", "Add device", "Añadir dispositivo", "Ajouter un appareil", "Adicionar dispositivo", "Adicionar dispositivo", "Добавяне на устройство", "Cihaz ekle", "Gerät hinzufügen", "Добавить устройство"),
                    summary = Tr.tr("搜索附近的手表", "Cerca watch nelle vicinanze", "Search nearby watches", "Buscar relojes cercanos", "Rechercher des montres à proximité", "Procurar relógios próximos", "Procurar relógios por perto", "Търсене на часовници наблизо", "Yakındaki saatleri ara", "Uhren in der Nähe suchen", "Искать часы поблизости"),
                    startAction = { IconTile(WatchIcons.Search) },
                    enabled = canSelect,
                    onClick = ::openDiscovery,
                )
            }
        }
    }

    // Native 确认/PIN 与失效目标立即优先；只有一次历史下退/上移保留物理宿主，逻辑面板始终分代次。
    val dialogToRender = openedDialog?.takeIf { effectiveDialog } ?: exitingDialog?.takeIf { effectiveExit }
    if (localWindowsAllowed && dialogToRender != null) key(dialogToRender.hostEpoch) {
        // 每个动作和完成回调仍捕获逻辑窗口 epoch；相同 MAC 重开有全新的宿主与面板。
        val show = isCurrentWindow(dialogToRender, state)
        val handoff = pendingWindow?.takeIf { effectivePending && it.afterExit == dialogToRender }
        when (dialogToRender.window) {
            DeviceWindow.DISCOVERY -> DiscoveryDialog(
                state = state,
                query = query,
                show = show,
                incarnation = dialogToRender.epoch,
                onQueryChange = { if (isCurrentWindow(dialogToRender)) query = it },
                onScan = {
                    if (isCurrentWindow(dialogToRender)) {
                        if (latestState.scanning) latestActions.stopScan()
                        else if (canSelectWatch(latestState)) latestOnScan()
                    }
                },
                onChoose = { chooseWatch(it, reconnect = false, expectedWindow = dialogToRender) },
                onDismiss = { if (isCurrentWindow(dialogToRender)) closeDialog(animateExit = true) },
                onDismissFinished = { finishDialogExit(dialogToRender) },
            )
            DeviceWindow.HUAWEI_ID -> {
                val address = dialogToRender.address
                if (address != null) HuaweiIdDialog(
                    show = show,
                    incarnation = dialogToRender.epoch,
                    name = watchDisplayName(state, selectedDevice, address),
                    address = address,
                    value = state.huaweiIdInput,
                    dirty = state.huaweiIdDirty,
                    configured = state.accountConfigured,
                    connected = state.connected,
                    canEdit = UiPolicy.canEditHuaweiId(state),
                    canConnect = canSelectWatch(state) && selectedDevice != null && state.huaweiIdInput.isNotBlank(),
                    isCurrent = { isCurrentWindow(dialogToRender) },
                    onValueChange = { if (isCurrentWindow(dialogToRender)) latestActions.setHuaweiId(it) },
                    onSave = { if (isCurrentWindow(dialogToRender)) latestActions.saveHuaweiId() },
                    onConnect = {
                        if (isCurrentWindow(dialogToRender)) connectSelectedWatch(address, dialogToRender.connectionEpoch)
                    },
                    onDismiss = { if (isCurrentWindow(dialogToRender)) closeDialog(animateExit = true) },
                    onDismissFinished = { finishDialogExit(dialogToRender) },
                )
            }
            DeviceWindow.HISTORY, DeviceWindow.HISTORY_HUAWEI_ID -> HistoryDeviceWindow(
                panel = dialogToRender,
                show = show,
                pending = handoff,
                isCurrentPanel = { isCurrentWindow(it) },
                isCurrentHandoff = { isCurrentPendingWindow(it) },
                onHandoffFinished = { completed, retiredEpoch ->
                    finishDialogExit(completed.afterExit, completed, retiredEpoch)
                },
                onDismiss = { if (isCurrentWindow(dialogToRender)) closeDialog(animateExit = true) },
                onCancelHost = { cancelHistoryHost(it) },
                onDismissFinished = { finishDialogExit(dialogToRender) },
            ) { pane, active, interactive ->
                val address = pane.address
                val device = state.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
                if (address != null && device != null) {
                    if (pane.window == DeviceWindow.HISTORY) HistoryOperationDialog(
                        state = state,
                        device = device,
                        show = active,
                        incarnation = pane.epoch,
                        canConnect = canSelectWatch(state) && !UiPolicy.isUnpairTarget(state, address),
                        canEditHuaweiId = canEditHistoryHuaweiId(state, address),
                        canUnpair = UiPolicy.canRequestUnpair(state),
                        isCurrent = { interactive() && isCurrentWindow(pane) },
                        onConfigureHuaweiId = { configureHistoryHuaweiId(pane) },
                        onConnect = { connectHistoryWatch(pane) },
                        onUnpair = {
                            if (interactive() && isCurrentWindow(pane)) {
                                unpairObservedWatch(device, pane.connectionEpoch, pane.unpairToken)
                            }
                        },
                        onDismiss = { if (isCurrentWindow(pane)) closeDialog(animateExit = true) },
                        onDismissFinished = { finishDialogExit(pane) },
                        inHistoryWindow = true,
                    ) else HistoryHuaweiIdDialog(
                        state = state,
                        device = device,
                        show = active,
                        incarnation = pane.epoch,
                        canConnect = canSelectWatch(state) && !UiPolicy.isUnpairTarget(state, address),
                        canEditHuaweiId = canEditHistoryHuaweiId(state, address),
                        isCurrent = { interactive() && isCurrentWindow(pane) },
                        isOwnerCurrent = { isCurrentWindow(pane) },
                        onReadHuaweiId = { readHistoryHuaweiId(pane) },
                        onSaveHuaweiId = { saveHistoryHuaweiId(pane, it) },
                        onSaveAndConnect = { saveAndConnectHistoryHuaweiId(pane, it) },
                        onDismiss = { if (isCurrentWindow(pane)) closeDialog(animateExit = true) },
                        onDismissFinished = { finishDialogExit(pane) },
                        inHistoryWindow = true,
                    )
                }
            }
        }
    }
}

private fun selectedWatch(state: AppUiState): NearbyWatch? {
    val address = state.selectedDeviceAddress ?: return null
    val device = state.devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
        ?: state.savedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.nearby()
    return device?.copy(address = address)
}

private fun canUseConnectionActions(state: AppUiState): Boolean =
    !state.unpairWorking && !state.operation.active && state.operationDialog == null && state.prompt == null && !state.needsPin

@Composable
private fun SelectedWatchCard(
    state: AppUiState,
    device: NearbyWatch?,
    onConnect: () -> Unit,
    onConfigureId: () -> Unit,
    onUnpair: () -> Unit,
    onRebind: () -> Unit,
    onDisabledHint: (DeviceControl) -> Unit,
    onOpenFaces: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val latestState by rememberUpdatedState(state)
    val controlsOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(controlsOwner) { onDispose { controlsOwner.set(false) } }
    fun invokeControl(control: DeviceControl, action: () -> Unit) {
        if (controlsOwner.get() && DeviceControlsPolicy.frameMatches(state, latestState) &&
            DeviceControlsPolicy.enabled(state, control) && DeviceControlsPolicy.enabled(latestState, control)
        ) action()
    }
    fun disabledHint(control: DeviceControl): (() -> Unit)? {
        val expected = DeviceControlsPolicy.hint(state, control) ?: return null
        return {
            if (controlsOwner.get() && DeviceControlsPolicy.frameMatches(state, latestState) &&
                !DeviceControlsPolicy.enabled(latestState, control) && expected == DeviceControlsPolicy.hint(latestState, control)
            ) onDisabledHint(control)
        }
    }
    val selectedAddress = state.selectedDeviceAddress?.takeIf { it.isNotBlank() }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconTile(WatchIcons.Watch, size = 32)
            Spacer(Modifier.width(10.dp))
            Text(
                text = Tr.tr("连接手表", "Connessione al watch", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr", "Подключение к часам"),
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body1,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (selectedAddress != null) {
            WatchName(state, device, selectedAddress)
            Spacer(Modifier.height(6.dp))
            DeviceAddress(selectedAddress)
            Spacer(Modifier.height(10.dp))
        }
        MutedText(
            when {
                selectedAddress == null -> Tr.tr("搜索附近设备，选择后配置华为 ID 并连接。", "Cerca i dispositivi vicini, selezionane uno, configura l'ID Huawei e connettiti.", "Search nearby devices, pick one, configure the Huawei ID and connect.", "Busca dispositivos cercanos, elige uno, configura el ID de Huawei y conéctate.", "Recherchez les appareils à proximité, choisissez-en un, configurez l'ID Huawei et connectez-vous.", "Procure dispositivos próximos, escolha um, configure o ID Huawei e ligue-se.", "Procure dispositivos por perto, escolha um, configure o ID Huawei e conecte.", "Потърсете устройства наблизо, изберете едно, настройте Huawei ID и се свържете.", "Yakındaki cihazları arayın, birini seçin, Huawei ID'yi yapılandırıp bağlanın.", "Suche Geräte in der Nähe, wähle eines, richte die Huawei-ID ein und verbinde dich.", "Найдите устройства поблизости, выберите одно, настройте Huawei ID и подключитесь.")
                UiPolicy.isUnpairTarget(state, selectedAddress) -> Tr.tr("解除配对尚未完成，请在下方状态中继续。", "L'annullamento abbinamento non è ancora completato, prosegui dallo stato qui sotto.", "Unpairing isn't complete yet, continue from the status below.", "El desemparejamiento aún no ha terminado, continúa desde el estado de abajo.", "La dissociation n'est pas encore terminée, continuez depuis l'état ci-dessous.", "O desemparelhamento ainda não terminou, continue a partir do estado abaixo.", "O despareamento ainda não terminou, continue pelo status abaixo.", "Разкачването още не е завършено, продължете от състоянието по-долу.", "Eşleştirme kaldırma henüz tamamlanmadı, aşağıdaki durumdan devam edin.", "Die Entkopplung ist noch nicht abgeschlossen, mach beim Status unten weiter.", "Снятие сопряжения ещё не завершено, продолжите из состояния ниже.")
                state.quarantine -> Tr.tr("设备操作已暂停，请断开后重新连接。", "Operazioni sul dispositivo sospese, disconnettiti e riconnettiti.", "Device operations paused, disconnect and reconnect.", "Operaciones del dispositivo en pausa, desconecta y vuelve a conectar.", "Opérations sur l'appareil en pause, déconnectez-vous et reconnectez-vous.", "Operações do dispositivo em pausa, desligue e volte a ligar.", "Operações do dispositivo pausadas, desconecte e conecte de novo.", "Операциите с устройството са на пауза, прекъснете връзката и се свържете отново.", "Cihaz işlemleri duraklatıldı, bağlantıyı kesip yeniden bağlanın.", "Gerätevorgänge pausiert, trenne und verbinde dich neu.", "Операции с устройством приостановлены, отключитесь и подключитесь снова.")
                state.connected -> Tr.tr("在此管理连接，设备状态与信息可在主页查看。", "Gestisci qui le connessioni; stato e informazioni del dispositivo sono nella Home.", "Manage connections here; device status and info are on the Home page.", "Gestiona aquí las conexiones; el estado y la información del dispositivo están en Inicio.", "Gérez les connexions ici ; l'état et les infos de l'appareil sont sur l'Accueil.", "Faça aqui a gestão das ligações; o estado e as informações do dispositivo estão no Início.", "Gerencie as conexões aqui; o status e as informações do dispositivo ficam no Início.", "Управлявайте връзките тук; състоянието и информацията за устройството са в „Начало“.", "Bağlantıları burada yönetin; cihaz durumu ve bilgileri Ana sayfada.", "Verwalte hier die Verbindungen; Status und Infos des Geräts findest du auf Start.", "Управляйте подключениями здесь; состояние и сведения об устройстве — на Главной.")
                state.connecting -> Tr.tr("正在建立连接，可取消后重新选择设备。", "Connessione in corso; puoi annullare e scegliere un altro dispositivo.", "Connecting; you can cancel and pick another device.", "Conectando; puedes cancelar y elegir otro dispositivo.", "Connexion en cours ; vous pouvez annuler et choisir un autre appareil.", "A ligar; pode cancelar e escolher outro dispositivo.", "Conectando; você pode cancelar e escolher outro dispositivo.", "Свързване; можете да отмените и да изберете друго устройство.", "Bağlanıyor; iptal edip başka bir cihaz seçebilirsiniz.", "Verbindung wird hergestellt; du kannst abbrechen und ein anderes Gerät wählen.", "Подключение; можно отменить и выбрать другое устройство.")
                !state.accountConfigured || state.huaweiIdInput.isBlank() -> Tr.tr("请先为这台设备配置华为 ID。", "Configura prima l'ID Huawei per questo dispositivo.", "Configure the Huawei ID for this device first.", "Configura primero el ID de Huawei de este dispositivo.", "Configurez d'abord l'ID Huawei de cet appareil.", "Configure primeiro o ID Huawei deste dispositivo.", "Configure primeiro o ID Huawei deste dispositivo.", "Първо настройте Huawei ID за това устройство.", "Önce bu cihaz için Huawei ID'yi yapılandırın.", "Richte zuerst die Huawei-ID für dieses Gerät ein.", "Сначала настройте Huawei ID для этого устройства.")
                state.huaweiIdDirty -> Tr.tr("华为 ID 有未保存修改。", "Ci sono modifiche non salvate all'ID Huawei.", "There are unsaved changes to the Huawei ID.", "Hay cambios sin guardar en el ID de Huawei.", "L'ID Huawei a des modifications non enregistrées.", "Há alterações não guardadas no ID Huawei.", "Há alterações não salvas no ID Huawei.", "Има незапазени промени в Huawei ID.", "Huawei ID'de kaydedilmemiş değişiklikler var.", "Es gibt nicht gespeicherte Änderungen an der Huawei-ID.", "В Huawei ID есть несохранённые изменения.")
                else -> Tr.tr("连接后可管理此手表的表盘。", "Dopo la connessione potrai gestire i quadranti di questo watch.", "Once connected you can manage this watch's watch faces.", "Una vez conectado podrás gestionar las esferas de este reloj.", "Une fois connecté, vous pourrez gérer les cadrans de cette montre.", "Depois de ligado poderá gerir os mostradores deste relógio.", "Depois de conectar você poderá gerenciar os mostradores deste relógio.", "След свързване ще можете да управлявате циферблатите на този часовник.", "Bağlandıktan sonra bu saatin kadranlarını yönetebilirsiniz.", "Nach dem Verbinden kannst du die Zifferblätter dieser Uhr verwalten.", "После подключения вы сможете управлять циферблатами этих часов.")
            },
        )
        Spacer(Modifier.height(18.dp))
        when {
            state.connected -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    Tr.tr("管理表盘", "Gestisci quadranti", "Manage watch faces", "Gestionar esferas", "Gérer les cadrans", "Gerir mostradores", "Gerenciar mostradores", "Управление на циферблати", "Kadranları yönet", "Zifferblätter verwalten", "Управлять циферблатами"), onOpenFaces, Modifier.weight(1f), primary = true,
                    enabled = !state.quarantine && canShowDeviceWindow(state),
                )
                ActionButton(Tr.tr("断开连接", "Disconnetti", "Disconnect", "Desconectar", "Déconnecter", "Desligar", "Desconectar", "Прекъсване на връзката", "Bağlantıyı kes", "Trennen", "Отключить"), onDisconnect, Modifier.weight(1f), enabled = canUseConnectionActions(state))
            }
            state.connecting -> ActionButton(
                Tr.tr("取消连接", "Annulla connessione", "Cancel connection", "Cancelar conexión", "Annuler la connexion", "Cancelar ligação", "Cancelar conexão", "Отмяна на свързването", "Bağlanmayı iptal et", "Verbinden abbrechen", "Отменить подключение"), onDisconnect, Modifier.fillMaxWidth(), enabled = canUseConnectionActions(state),
            )
            else -> ActionButton(
                text = Tr.tr("连接手表", "Connessione al watch", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr", "Подключение к часам"),
                onClick = { invokeControl(DeviceControl.CONNECT, onConnect) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT),
                disabledHint = disabledHint(DeviceControl.CONNECT),
            )
        }
        Spacer(Modifier.height(10.dp))
        ActionButton(
            text = Tr.tr("配置华为 ID", "Configura ID Huawei", "Configure Huawei ID", "Configurar ID de Huawei", "Configurer l'ID Huawei", "Configurar ID Huawei", "Configurar ID Huawei", "Настройка на Huawei ID", "Huawei ID'yi yapılandır", "Huawei-ID einrichten", "Настроить Huawei ID"),
            onClick = { invokeControl(DeviceControl.CONFIGURE_ID, onConfigureId) },
            modifier = Modifier.fillMaxWidth(),
            enabled = DeviceControlsPolicy.enabled(state, DeviceControl.CONFIGURE_ID),
            disabledHint = disabledHint(DeviceControl.CONFIGURE_ID),
        )
        Spacer(Modifier.height(10.dp))
        val context = LocalContext.current
        val rebindEnabled = selectedAddress != null && !state.connected && !state.connecting &&
            !state.unpairing && !state.scanning && !state.quarantine && canShowDeviceWindow(state)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                text = Tr.tr("重新协议绑定", "Riassocia protocollo", "Re-bind protocol", "Volver a vincular protocolo", "Réassocier le protocole", "Voltar a associar protocolo", "Vincular protocolo de novo", "Ново обвързване на протокола", "Protokolü yeniden bağla", "Protokoll neu binden", "Заново привязать протокол"),
                onClick = {
                    if (controlsOwner.get() && DeviceControlsPolicy.frameMatches(state, latestState) &&
                        !latestState.connected && !latestState.connecting && !latestState.unpairing
                    ) onRebind()
                },
                modifier = Modifier.weight(1f),
                destructive = true,
                enabled = rebindEnabled,
                disabledHint = if (state.connected || state.connecting) ({
                    if (controlsOwner.get() && DeviceControlsPolicy.frameMatches(state, latestState) &&
                        canShowDeviceWindow(latestState) && (latestState.connected || latestState.connecting)
                    ) Toast.makeText(context, Tr.tr("请先断开或取消连接，再重新协议绑定", "Disconnetti o annulla prima la connessione, poi riassocia il protocollo", "Disconnect or cancel the connection first, then re-bind the protocol", "Desconecta o cancela primero la conexión y luego vuelve a vincular el protocolo", "Déconnectez ou annulez d'abord la connexion, puis réassociez le protocole", "Desligue ou cancele primeiro a ligação e depois volte a associar o protocolo", "Desconecte ou cancele primeiro a conexão e depois vincule o protocolo de novo", "Първо прекъснете или отменете връзката, после обвържете протокола наново", "Önce bağlantıyı kesin veya iptal edin, sonra protokolü yeniden bağlayın", "Trenne oder breche zuerst die Verbindung ab und binde dann das Protokoll neu", "Сначала отключитесь или отмените подключение, затем заново привяжите протокол"), Toast.LENGTH_SHORT).show()
                }) else null,
            )
            ActionButton(
                text = Tr.tr("解除配对", "Annulla abbinamento", "Unpair", "Desemparejar", "Dissocier", "Desemparelhar", "Desfazer pareamento", "Разкачване", "Eşleştirmeyi kaldır", "Entkoppeln", "Снять сопряжение"),
                onClick = { invokeControl(DeviceControl.UNPAIR, onUnpair) },
                modifier = Modifier.weight(1f),
                destructive = true,
                enabled = DeviceControlsPolicy.enabled(state, DeviceControl.UNPAIR),
                disabledHint = disabledHint(DeviceControl.UNPAIR),
            )
        }
        Spacer(Modifier.height(14.dp))
        MutedText(Tr.tr("WATCH 系列切换模式后建议点击重新协议绑定。", "Dopo aver cambiato modalità su un WATCH si consiglia di toccare Riassocia protocollo.", "After switching mode on a WATCH, tapping Re-bind protocol is recommended.", "Tras cambiar de modo en un WATCH se recomienda tocar Volver a vincular protocolo.", "Après un changement de mode sur une WATCH, il est conseillé de toucher Réassocier le protocole.", "Depois de mudar de modo num WATCH recomenda-se tocar em Voltar a associar protocolo.", "Depois de mudar de modo em um WATCH, recomenda-se tocar em Vincular protocolo de novo.", "След смяна на режима на WATCH се препоръчва да докоснете „Ново обвързване на протокола“.", "Bir WATCH'ta mod değiştirdikten sonra Protokolü yeniden bağla'ya dokunmanız önerilir.", "Nach einem Moduswechsel auf einer WATCH wird empfohlen, auf Protokoll neu binden zu tippen.", "После смены режима на WATCH рекомендуется нажать «Заново привязать протокол»."))
    }
}

private fun watchDisplayName(state: AppUiState, device: NearbyWatch?, address: String): String {
    val matchingDevice = device?.takeIf { it.address.equals(address, ignoreCase = true) }
    val deviceName = matchingDevice?.name?.takeIf { it.isNotBlank() }
    // 活动设备可能尚未进入发现或成功记录；只回退显示名称，不猜测连接元数据。
    val activeName = state.watchName.takeIf {
        (state.connected || state.connecting) && address.isSelectedIn(state) && it.isNotBlank()
    }
    return (deviceName ?: activeName ?: if (matchingDevice != null) Tr.tr("未命名手表", "Watch senza nome", "Unnamed watch", "Reloj sin nombre", "Montre sans nom", "Relógio sem nome", "Relógio sem nome", "Часовник без име", "Adsız saat", "Unbenannte Uhr", "Часы без имени") else Tr.tr("所选手表", "Watch selezionato", "Selected watch", "Reloj seleccionado", "Montre sélectionnée", "Relógio selecionado", "Relógio selecionado", "Избран часовник", "Seçili saat", "Ausgewählte Uhr", "Выбранные часы")).uiText()
}

@Composable
private fun WatchName(state: AppUiState, device: NearbyWatch?, address: String) {
    Text(
        text = watchDisplayName(state, device, address),
        style = MiuixTheme.textStyles.title4,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 地址单独占满可用宽度，允许换行但不截断任何字符。 */
@Composable
private fun DeviceAddress(address: String) {
    Text(
        text = address,
        modifier = Modifier.fillMaxWidth(),
        style = MiuixTheme.textStyles.body2,
        fontFamily = FontFamily.Monospace,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        softWrap = true,
    )
}

/** 历史专用：MAC 与多行名称/摘要同列，库自身的 endActions 因此针对完整内容垂直居中。 */
@Composable
private fun HistoryWatchPreference(device: NearbyWatch, summary: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val layoutDirection = LocalLayoutDirection.current
    val summaryColor = if (enabled) colors.onSurfaceVariantSummary else colors.disabledOnSecondaryVariant
    BasicComponent(
        startAction = { IconTile(WatchIcons.Watch) },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp).graphicsLayer {
                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                },
                tint = if (enabled) colors.onSurfaceVariantActions else colors.disabledOnSecondaryVariant,
            )
        },
        enabled = enabled,
        onClick = onClick,
        onClickLabel = Tr.tr("查看设备操作", "Mostra azioni del dispositivo", "Show device actions", "Ver acciones del dispositivo", "Voir les actions de l'appareil", "Ver ações do dispositivo", "Ver ações do dispositivo", "Действия с устройството", "Cihaz işlemlerini göster", "Geräteaktionen anzeigen", "Показать действия устройства"),
    ) {
        Text(
            text = device.name.ifBlank { Tr.tr("未命名手表", "Watch senza nome", "Unnamed watch", "Reloj sin nombre", "Montre sans nom", "Relógio sem nome", "Relógio sem nome", "Часовник без име", "Adsız saat", "Unbenannte Uhr", "Часы без имени") }.uiText(),
            // body1 默认 16sp，比 ArrowPreference 的 headline1 默认 17sp 略小。
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = if (enabled) colors.onBackground else colors.disabledOnSecondaryVariant,
            softWrap = true,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = summary, style = MiuixTheme.textStyles.body2, color = summaryColor)
        Spacer(Modifier.height(8.dp))
        Text(
            text = device.address,
            modifier = Modifier.fillMaxWidth(),
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            color = summaryColor,
            softWrap = true,
        )
    }
}

@Composable
private fun WatchPreference(device: NearbyWatch, summary: String, enabled: Boolean, onClick: () -> Unit) {
    ArrowPreference(
        title = device.name.ifBlank { Tr.tr("未命名手表", "Watch senza nome", "Unnamed watch", "Reloj sin nombre", "Montre sans nom", "Relógio sem nome", "Relógio sem nome", "Часовник без име", "Adsız saat", "Unbenannte Uhr", "Часы без имени") }.uiText(),
        summary = summary,
        startAction = { IconTile(WatchIcons.Watch) },
        bottomAction = { DeviceAddress(device.address) },
        enabled = enabled,
        onClick = onClick,
    )
}

private data class DiscoveryPresentation(
    val devices: List<NearbyWatch>,
    val selectedAddress: String?,
    val scanning: Boolean,
    val canSelect: Boolean,
    val query: String,
)

@Composable
private fun DiscoveryDialog(
    state: AppUiState,
    query: String,
    show: Boolean,
    incarnation: Long,
    onQueryChange: (String) -> Unit,
    onScan: () -> Unit,
    onChoose: (NearbyWatch) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    // 不保留 AppUiState（其中含选中设备 ID）；正常关闭时只冻结最后非敏感展示。
    val currentPresentation = DiscoveryPresentation(state.devices, state.selectedDeviceAddress, state.scanning, canSelectWatch(state), query)
    var lastPresentation by remember(incarnation) { mutableStateOf(currentPresentation) }
    SideEffect { if (show) lastPresentation = currentPresentation }
    val presentation = if (show) currentPresentation else lastPresentation
    val filter = presentation.query.trim()
    val visibleDevices = presentation.devices.filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true) || it.address.contains(filter, ignoreCase = true)
    }
    WindowDialog(show = show, title = Tr.tr("添加设备", "Aggiungi dispositivo", "Add device", "Añadir dispositivo", "Ajouter un appareil", "Adicionar dispositivo", "Adicionar dispositivo", "Добавяне на устройство", "Cihaz ekle", "Gerät hinzufügen", "Добавить устройство"), onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
        val dismiss = LocalDismissState.current
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                MiuixField(
                    value = presentation.query,
                    onValueChange = onQueryChange,
                    label = Tr.tr("设备名称或蓝牙地址", "Nome del dispositivo o indirizzo Bluetooth", "Device name or Bluetooth address", "Nombre del dispositivo o dirección Bluetooth", "Nom de l'appareil ou adresse Bluetooth", "Nome do dispositivo ou endereço Bluetooth", "Nome do dispositivo ou endereço Bluetooth", "Име на устройството или Bluetooth адрес", "Cihaz adı veya Bluetooth adresi", "Gerätename oder Bluetooth-Adresse", "Имя устройства или адрес Bluetooth"),
                    leadingIcon = {
                        Icon(WatchIcons.Search, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    },
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    text = if (presentation.scanning) Tr.tr("停止搜索", "Interrompi ricerca", "Stop searching", "Detener búsqueda", "Arrêter la recherche", "Parar pesquisa", "Parar busca", "Спиране на търсенето", "Aramayı durdur", "Suche beenden", "Остановить поиск") else Tr.tr("搜索附近手表", "Cerca watch nelle vicinanze", "Search nearby watches", "Buscar relojes cercanos", "Rechercher des montres à proximité", "Procurar relógios próximos", "Procurar relógios por perto", "Търсене на часовници наблизо", "Yakındaki saatleri ara", "Uhren in der Nähe suchen", "Искать часы поблизости"),
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth(),
                    primary = !presentation.scanning,
                    enabled = presentation.scanning || presentation.canSelect,
                )
                Spacer(Modifier.height(10.dp))
                MutedText(if (presentation.scanning) Tr.tr("正在搜索，点击设备即可配置并连接。", "Ricerca in corso, tocca un dispositivo per configurarlo e connetterti.", "Searching, tap a device to configure it and connect.", "Buscando, toca un dispositivo para configurarlo y conectarte.", "Recherche en cours, touchez un appareil pour le configurer et vous connecter.", "A procurar, toque num dispositivo para o configurar e ligar.", "Procurando, toque em um dispositivo para configurar e conectar.", "Търсене, докоснете устройство, за да го настроите и да се свържете.", "Aranıyor, yapılandırıp bağlanmak için bir cihaza dokunun.", "Suche läuft, tippe auf ein Gerät, um es einzurichten und zu verbinden.", "Идёт поиск, нажмите на устройство, чтобы настроить его и подключиться.") else Tr.tr("选择一个手表，为它配置华为 ID。", "Seleziona un watch e configura il suo ID Huawei.", "Select a watch and configure its Huawei ID.", "Selecciona un reloj y configura su ID de Huawei.", "Sélectionnez une montre et configurez son ID Huawei.", "Selecione um relógio e configure o seu ID Huawei.", "Selecione um relógio e configure o ID Huawei dele.", "Изберете часовник и настройте неговия Huawei ID.", "Bir saat seçin ve Huawei ID'sini yapılandırın.", "Wähle eine Uhr aus und richte ihre Huawei-ID ein.", "Выберите часы и настройте для них Huawei ID."))
            }
            if (visibleDevices.isEmpty()) {
                item(key = "empty") {
                    SectionCard {
                        Text(
                            when {
                                filter.isNotBlank() && presentation.devices.isNotEmpty() -> Tr.tr("没有匹配设备", "Nessun dispositivo corrispondente", "No matching devices", "Ningún dispositivo coincide", "Aucun appareil correspondant", "Nenhum dispositivo corresponde", "Nenhum dispositivo corresponde", "Няма съвпадащи устройства", "Eşleşen cihaz yok", "Keine passenden Geräte", "Нет подходящих устройств")
                                presentation.scanning -> Tr.tr("正在寻找你的手表", "Ricerca del tuo watch in corso", "Looking for your watch", "Buscando tu reloj", "Recherche de votre montre", "À procura do seu relógio", "Procurando seu relógio", "Търсене на вашия часовник", "Saatiniz aranıyor", "Deine Uhr wird gesucht", "Поиск ваших часов")
                                else -> Tr.tr("还没有发现设备", "Ancora nessun dispositivo trovato", "No devices found yet", "Aún no se ha encontrado ningún dispositivo", "Aucun appareil trouvé pour l'instant", "Ainda não foi encontrado nenhum dispositivo", "Nenhum dispositivo encontrado ainda", "Още не са намерени устройства", "Henüz cihaz bulunamadı", "Noch keine Geräte gefunden", "Устройства ещё не найдены")
                            },
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        MutedText(
                            if (filter.isNotBlank() && presentation.devices.isNotEmpty()) Tr.tr("试试其他名称或清空筛选内容。", "Prova un altro nome o svuota il filtro.", "Try another name or clear the filter.", "Prueba otro nombre o borra el filtro.", "Essayez un autre nom ou effacez le filtre.", "Experimente outro nome ou limpe o filtro.", "Tente outro nome ou limpe o filtro.", "Опитайте друго име или изчистете филтъра.", "Başka bir ad deneyin veya filtreyi temizleyin.", "Versuche einen anderen Namen oder leere den Filter.", "Попробуйте другое имя или очистите фильтр.")
                            else Tr.tr("让手表靠近手机并开启蓝牙，保持设备可被发现。", "Avvicina il watch al telefono, attiva il Bluetooth e rendi il dispositivo rilevabile.", "Bring the watch close to the phone, turn on Bluetooth and make the device discoverable.", "Acerca el reloj al teléfono, activa el Bluetooth y haz que el dispositivo sea visible.", "Approchez la montre du téléphone, activez le Bluetooth et rendez l'appareil visible.", "Aproxime o relógio do telemóvel, ligue o Bluetooth e torne o dispositivo visível.", "Aproxime o relógio do celular, ligue o Bluetooth e deixe o dispositivo visível.", "Приближете часовника до телефона, включете Bluetooth и направете устройството видимо.", "Saati telefona yaklaştırın, Bluetooth'u açın ve cihazı görünür yapın.", "Halte die Uhr nah ans Handy, schalte Bluetooth ein und mach das Gerät sichtbar.", "Поднесите часы к телефону, включите Bluetooth и сделайте устройство видимым."),
                        )
                    }
                }
            } else {
                items(visibleDevices, key = { it.address }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        WatchPreference(
                            device = device,
                            summary = when {
                                device.address.equals(presentation.selectedAddress, true) -> Tr.tr("已选择，点击配置华为 ID", "Selezionato, tocca per configurare l'ID Huawei", "Selected, tap to configure the Huawei ID", "Seleccionado, toca para configurar el ID de Huawei", "Sélectionné, touchez pour configurer l'ID Huawei", "Selecionado, toque para configurar o ID Huawei", "Selecionado, toque para configurar o ID Huawei", "Избрано, докоснете, за да настроите Huawei ID", "Seçildi, Huawei ID'yi yapılandırmak için dokunun", "Ausgewählt, tippe, um die Huawei-ID einzurichten", "Выбрано, нажмите, чтобы настроить Huawei ID")
                                device.bonded -> Tr.tr("已配对，点击选择并配置华为 ID", "Abbinato, tocca per selezionarlo e configurare l'ID Huawei", "Paired, tap to select it and configure the Huawei ID", "Emparejado, toca para seleccionarlo y configurar el ID de Huawei", "Associé, touchez pour le sélectionner et configurer l'ID Huawei", "Emparelhado, toque para o selecionar e configurar o ID Huawei", "Pareado, toque para selecionar e configurar o ID Huawei", "Сдвоено, докоснете, за да го изберете и настроите Huawei ID", "Eşleştirildi, seçip Huawei ID'yi yapılandırmak için dokunun", "Gekoppelt, tippe, um es auszuwählen und die Huawei-ID einzurichten", "Сопряжено, нажмите, чтобы выбрать и настроить Huawei ID")
                                else -> Tr.tr("点击选择并配置华为 ID", "Tocca per selezionarlo e configurare l'ID Huawei", "Tap to select it and configure the Huawei ID", "Toca para seleccionarlo y configurar el ID de Huawei", "Touchez pour le sélectionner et configurer l'ID Huawei", "Toque para o selecionar e configurar o ID Huawei", "Toque para selecionar e configurar o ID Huawei", "Докоснете, за да го изберете и настроите Huawei ID", "Seçip Huawei ID'yi yapılandırmak için dokunun", "Tippe, um es auszuwählen und die Huawei-ID einzurichten", "Нажмите, чтобы выбрать и настроить Huawei ID")
                            },
                            enabled = presentation.canSelect,
                            onClick = { onChoose(device) },
                        )
                    }
                }
            }
            item(key = "close") { ActionButton(Tr.tr("关闭", "Chiudi", "Close", "Cerrar", "Fermer", "Fechar", "Fechar", "Затваряне", "Kapat", "Schließen", "Закрыть"), onClick = { dismiss?.invoke() }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
