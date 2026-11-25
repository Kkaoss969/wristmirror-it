package io.github.escosis.hwtfaceswap.ui

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
                            val target = selectedDevice ?: NearbyWatch(address, state.watchName.ifBlank { "Dispositivo senza nome" }, false)
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
                    Text("Annulla abbinamento", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
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
                                ActionButton("Impostazioni Bluetooth di sistema", onClick = {
                                    if (callbackOwner.get() && latestState.unpairing && !latestState.unpairWorking && latestState.unpairAddress == state.unpairAddress && latestState.unpairToken == state.unpairToken) latestActions.openUnpairSettings()
                                }, modifier = Modifier.weight(1f), primary = true, enabled = !state.unpairWorking)
                            }
                            ActionButton("Ricontrolla", onClick = {
                                if (callbackOwner.get() && latestState.unpairing && !latestState.unpairWorking && latestState.unpairAddress == state.unpairAddress && latestState.unpairToken == state.unpairToken) latestActions.retryUnpair()
                            }, modifier = Modifier.weight(1f), enabled = !state.unpairWorking)
                            if (state.unpairCancelable) {
                                ActionButton("Revoca annullamento abbinamento", onClick = {
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
                    SectionHeading("Dispositivi recenti")
                    Card(Modifier.fillMaxWidth()) {
                        state.savedDevices.forEachIndexed { index, saved ->
                            val device = saved.nearby()
                            HistoryWatchPreference(
                                device = device,
                                summary = when {
                                    UiPolicy.isUnpairTarget(state, device.address) -> "Annullamento abbinamento da completare"
                                    state.connected && device.address.equals(state.deviceAddress, ignoreCase = true) -> "Connesso ora"
                                    device.address.isSelectedIn(state) && state.connecting -> "Connessione in corso"
                                    device.address.isSelectedIn(state) -> "Selezionato, tocca per le azioni"
                                    index == 0 -> "Ultima connessione, tocca per le azioni"
                                    else -> "Tocca per le azioni"
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
                    title = "Aggiungi dispositivo",
                    summary = "Cerca watch nelle vicinanze",
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
                text = "Connessione al watch",
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
                selectedAddress == null -> "Cerca i dispositivi vicini, selezionane uno, configura l'ID Huawei e connettiti."
                UiPolicy.isUnpairTarget(state, selectedAddress) -> "L'annullamento abbinamento non è ancora completato, prosegui dallo stato qui sotto."
                state.quarantine -> "Operazioni sul dispositivo sospese, disconnettiti e riconnettiti."
                state.connected -> "Gestisci qui le connessioni; stato e informazioni del dispositivo sono nella Home."
                state.connecting -> "Connessione in corso; puoi annullare e scegliere un altro dispositivo."
                !state.accountConfigured || state.huaweiIdInput.isBlank() -> "Configura prima l'ID Huawei per questo dispositivo."
                state.huaweiIdDirty -> "Ci sono modifiche non salvate all'ID Huawei."
                else -> "Dopo la connessione potrai gestire i quadranti di questo watch."
            },
        )
        Spacer(Modifier.height(18.dp))
        when {
            state.connected -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    "Gestisci quadranti", onOpenFaces, Modifier.weight(1f), primary = true,
                    enabled = !state.quarantine && canShowDeviceWindow(state),
                )
                ActionButton("Disconnetti", onDisconnect, Modifier.weight(1f), enabled = canUseConnectionActions(state))
            }
            state.connecting -> ActionButton(
                "Annulla connessione", onDisconnect, Modifier.fillMaxWidth(), enabled = canUseConnectionActions(state),
            )
            else -> ActionButton(
                text = "Connessione al watch",
                onClick = { invokeControl(DeviceControl.CONNECT, onConnect) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT),
                disabledHint = disabledHint(DeviceControl.CONNECT),
            )
        }
        Spacer(Modifier.height(10.dp))
        ActionButton(
            text = "Configura ID Huawei",
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
                text = "Riassocia protocollo",
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
                    ) Toast.makeText(context, "Disconnetti o annulla prima la connessione, poi riassocia il protocollo", Toast.LENGTH_SHORT).show()
                }) else null,
            )
            ActionButton(
                text = "Annulla abbinamento",
                onClick = { invokeControl(DeviceControl.UNPAIR, onUnpair) },
                modifier = Modifier.weight(1f),
                destructive = true,
                enabled = DeviceControlsPolicy.enabled(state, DeviceControl.UNPAIR),
                disabledHint = disabledHint(DeviceControl.UNPAIR),
            )
        }
        Spacer(Modifier.height(14.dp))
        MutedText("Dopo aver cambiato modalità su un WATCH si consiglia di toccare Riassocia protocollo.")
    }
}

private fun watchDisplayName(state: AppUiState, device: NearbyWatch?, address: String): String {
    val matchingDevice = device?.takeIf { it.address.equals(address, ignoreCase = true) }
    val deviceName = matchingDevice?.name?.takeIf { it.isNotBlank() }
    // 活动设备可能尚未进入发现或成功记录；只回退显示名称，不猜测连接元数据。
    val activeName = state.watchName.takeIf {
        (state.connected || state.connecting) && address.isSelectedIn(state) && it.isNotBlank()
    }
    return (deviceName ?: activeName ?: if (matchingDevice != null) "Watch senza nome" else "Watch selezionato").uiText()
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
        onClickLabel = "Mostra azioni del dispositivo",
    ) {
        Text(
            text = device.name.ifBlank { "Watch senza nome" }.uiText(),
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
        title = device.name.ifBlank { "Watch senza nome" }.uiText(),
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
    WindowDialog(show = show, title = "Aggiungi dispositivo", onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
        val dismiss = LocalDismissState.current
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                MiuixField(
                    value = presentation.query,
                    onValueChange = onQueryChange,
                    label = "Nome del dispositivo o indirizzo Bluetooth",
                    leadingIcon = {
                        Icon(WatchIcons.Search, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    },
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    text = if (presentation.scanning) "Interrompi ricerca" else "Cerca watch nelle vicinanze",
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth(),
                    primary = !presentation.scanning,
                    enabled = presentation.scanning || presentation.canSelect,
                )
                Spacer(Modifier.height(10.dp))
                MutedText(if (presentation.scanning) "Ricerca in corso, tocca un dispositivo per configurarlo e connetterti." else "Seleziona un watch e configura il suo ID Huawei.")
            }
            if (visibleDevices.isEmpty()) {
                item(key = "empty") {
                    SectionCard {
                        Text(
                            when {
                                filter.isNotBlank() && presentation.devices.isNotEmpty() -> "Nessun dispositivo corrispondente"
                                presentation.scanning -> "Ricerca del tuo watch in corso"
                                else -> "Ancora nessun dispositivo trovato"
                            },
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        MutedText(
                            if (filter.isNotBlank() && presentation.devices.isNotEmpty()) "Prova un altro nome o svuota il filtro."
                            else "Avvicina il watch al telefono, attiva il Bluetooth e rendi il dispositivo rilevabile.",
                        )
                    }
                }
            } else {
                items(visibleDevices, key = { it.address }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        WatchPreference(
                            device = device,
                            summary = when {
                                device.address.equals(presentation.selectedAddress, true) -> "Selezionato, tocca per configurare l'ID Huawei"
                                device.bonded -> "Abbinato, tocca per selezionarlo e configurare l'ID Huawei"
                                else -> "Tocca per selezionarlo e configurare l'ID Huawei"
                            },
                            enabled = presentation.canSelect,
                            onClick = { onChoose(device) },
                        )
                    }
                }
            }
            item(key = "close") { ActionButton("Chiudi", onClick = { dismiss?.invoke() }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
