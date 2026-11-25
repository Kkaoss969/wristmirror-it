package io.github.escosis.hwtfaceswap

/** 界面操作资格集中计算，与手表协议的接受判断分开。 */
internal object UiPolicy {
    fun connecting(ready: Boolean, nativeConnecting: Boolean, queued: Boolean): Boolean =
        !ready && (nativeConnecting || queued)

    fun isUnpairTarget(state: AppUiState, address: String?): Boolean =
        state.unpairing && address != null && state.unpairAddress?.equals(address, ignoreCase = true) == true

    /** 说明只占用空闲模态槽；长 pending 等待系统设置不等于工作/确认窗，不与首启互相等待。 */
    fun introductionModalAvailable(state: AppUiState): Boolean =
        !state.needsPin && state.prompt == null && state.operationDialog == null && !state.operation.active &&
            !state.connecting && !state.pickingHwt && !state.scanning && !state.listRefreshing &&
            !state.unpairWorking

    fun showIntroduction(state: AppUiState): Boolean = state.introductionPending && introductionModalAvailable(state)

    fun showUpdate(state: AppUiState): Boolean =
        !state.introductionPending && state.operationDialog == null && !state.operation.active &&
            !state.connecting && !state.pickingHwt && !state.scanning && !state.listRefreshing && state.prompt == null && !state.needsPin

    fun canRequestUnpair(state: AppUiState): Boolean =
        !state.introductionPending && !state.unpairWorking && !state.unpairing && !state.connecting && !state.operation.active && !state.pickingHwt &&
            state.operationDialog == null && state.prompt == null && !state.needsPin

    fun canEditHuaweiId(state: AppUiState): Boolean =
        !state.introductionPending && !state.unpairWorking && !isUnpairTarget(state, state.selectedDeviceAddress) && !state.connecting && !state.operation.active && state.operationDialog == null && state.prompt == null && !state.needsPin

    fun canOperateFaces(state: AppUiState): Boolean =
        !state.introductionPending && !state.unpairWorking && state.connected && !isUnpairTarget(state, state.deviceAddress) && state.listReady && !state.listRefreshing && !state.quarantine &&
            !state.operation.active && !state.pickingHwt && !state.connecting && state.operationDialog == null && state.prompt == null && !state.needsPin

    /** 普通列表查询与删除前置查询共用短提示，后置核验仍由操作进度展示。 */
    fun showFaceRefreshNotice(state: AppUiState): Boolean =
        state.connected && state.listRefreshing && !state.quarantine && !state.connecting &&
            !state.introductionPending && !state.unpairWorking && !isUnpairTarget(state, state.deviceAddress) &&
            !state.operation.active && !state.pickingHwt && state.operationDialog == null && state.prompt == null && !state.needsPin

    fun canChooseHwt(state: AppUiState): Boolean =
        !state.introductionPending && !state.unpairWorking && !state.operation.active && !state.pickingHwt && !state.connecting && state.operationDialog == null && state.prompt == null && !state.needsPin
}
