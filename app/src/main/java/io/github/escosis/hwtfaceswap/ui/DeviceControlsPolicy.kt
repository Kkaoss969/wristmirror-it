package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.UiPolicy

/** 仅属于设备顶卡的 UI 门；不改变配置窗、历史连接或 Runtime 的真实动作资格。 */
internal enum class DeviceControl { CONNECT, CONFIGURE_ID, UNPAIR }

internal enum class DeviceControlHint(val text: String) {
    SELECT_DEVICE(Tr.tr("请先选择设备", "Seleziona prima un dispositivo", "Select a device first", "Selecciona primero un dispositivo", "Sélectionnez d'abord un appareil", "Selecione primeiro um dispositivo", "Selecione primeiro um dispositivo", "Първо изберете устройство", "Önce bir cihaz seçin", "Wähle zuerst ein Gerät aus", "Сначала выберите устройство")),
    CONFIGURE_ID(Tr.tr("请先配置华为 ID", "Configura prima l'ID Huawei", "Configure the Huawei ID first", "Configura primero el ID de Huawei", "Configurez d'abord l'ID Huawei", "Configure primeiro o ID Huawei", "Configure primeiro o ID Huawei", "Първо настройте Huawei ID", "Önce Huawei ID'yi yapılandırın", "Richte zuerst die Huawei-ID ein", "Сначала настройте Huawei ID")),
}

/** 纯判断，不读取/保存 ID，不创建设备，不扫描、连接或打开窗口。 */
internal object DeviceControlsPolicy {
    private fun hasSelection(state: AppUiState): Boolean = !state.selectedDeviceAddress.isNullOrBlank()

    private fun selectedDeviceKnown(state: AppUiState): Boolean {
        val address = state.selectedDeviceAddress?.takeIf { it.isNotBlank() } ?: return false
        return state.devices.any { it.address.equals(address, true) } ||
            state.savedDevices.any { it.address.equals(address, true) }
    }

    // 顶卡须已有此设备的可靠配置；已配置设备的非空 dirty 草稿仍保留原连接资格。
    private fun idAvailable(state: AppUiState): Boolean = state.accountConfigured && state.huaweiIdInput.isNotBlank()

    fun enabled(state: AppUiState, control: DeviceControl): Boolean = hasSelection(state) && when (control) {
        DeviceControl.CONNECT -> selectedDeviceKnown(state) && idAvailable(state) && canSelectWatch(state) &&
            !UiPolicy.isUnpairTarget(state, state.selectedDeviceAddress)
        DeviceControl.CONFIGURE_ID -> UiPolicy.canEditHuaweiId(state)
        DeviceControl.UNPAIR -> UiPolicy.canRequestUnpair(state)
    }

    fun hint(state: AppUiState, control: DeviceControl): DeviceControlHint? {
        // Native、进行中的操作/选择以及解除配对状态不借禁用控件发出误导提示。
        if (state.connected || state.connecting || !canShowDeviceWindow(state) || state.scanning ||
            state.unpairing || state.quarantine
        ) return null
        if (!hasSelection(state)) return DeviceControlHint.SELECT_DEVICE
        return if (control == DeviceControl.CONNECT && !idAvailable(state)) DeviceControlHint.CONFIGURE_ID else null
    }

    /** 只比较当前帧；ID 只原位比较，不另存草稿或把它放入待执行请求。 */
    fun frameMatches(rendered: AppUiState, current: AppUiState): Boolean =
        rendered.connectionEpoch == current.connectionEpoch &&
            rendered.connectionFormRevision == current.connectionFormRevision &&
            rendered.selectedDeviceAddress.equals(current.selectedDeviceAddress, true) &&
            rendered.deviceAddress.equals(current.deviceAddress, true) &&
            rendered.connected == current.connected && rendered.connecting == current.connecting &&
            rendered.huaweiIdInput == current.huaweiIdInput && rendered.accountConfigured == current.accountConfigured &&
            rendered.huaweiIdDirty == current.huaweiIdDirty && selectedDeviceKnown(rendered) == selectedDeviceKnown(current) &&
            rendered.unpairToken == current.unpairToken && rendered.unpairing == current.unpairing &&
            rendered.unpairWorking == current.unpairWorking && rendered.unpairAddress.equals(current.unpairAddress, true) &&
            rendered.prompt?.token == current.prompt?.token && rendered.needsPin == current.needsPin &&
            rendered.operation.active == current.operation.active &&
            rendered.operationDialog?.token == current.operationDialog?.token &&
            rendered.operationDialog?.operation?.kind == current.operationDialog?.operation?.kind &&
            rendered.introductionPending == current.introductionPending && rendered.pickingHwt == current.pickingHwt &&
            rendered.scanning == current.scanning && rendered.quarantine == current.quarantine

    private fun currentContext(
        rendered: AppUiState,
        current: AppUiState,
        ownerActive: Boolean,
        renderedLocalEpoch: Long,
        currentLocalEpoch: Long,
        localWindowPresent: Boolean,
    ): Boolean = ownerActive && renderedLocalEpoch == currentLocalEpoch && !localWindowPresent &&
        canShowDeviceWindow(current) && frameMatches(rendered, current)

    fun canAct(
        rendered: AppUiState,
        current: AppUiState,
        control: DeviceControl,
        ownerActive: Boolean,
        renderedLocalEpoch: Long,
        currentLocalEpoch: Long,
        localWindowPresent: Boolean,
    ): Boolean = currentContext(rendered, current, ownerActive, renderedLocalEpoch, currentLocalEpoch, localWindowPresent) &&
        enabled(rendered, control) && enabled(current, control)

    fun hintIfCurrent(
        rendered: AppUiState,
        current: AppUiState,
        control: DeviceControl,
        ownerActive: Boolean,
        renderedLocalEpoch: Long,
        currentLocalEpoch: Long,
        localWindowPresent: Boolean,
    ): DeviceControlHint? {
        if (!currentContext(rendered, current, ownerActive, renderedLocalEpoch, currentLocalEpoch, localWindowPresent) ||
            enabled(rendered, control) || enabled(current, control)
        ) return null
        val expected = hint(rendered, control) ?: return null
        return expected.takeIf { it == hint(current, control) }
    }
}
