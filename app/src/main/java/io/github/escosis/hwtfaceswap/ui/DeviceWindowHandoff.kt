package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.UiPolicy

internal enum class DeviceWindow { DISCOVERY, HUAWEI_ID, HISTORY, HISTORY_HUAWEI_ID }

/** 窗口代次与连接/清理快照只属于当前页面，不进入恢复状态或设备存储。 */
internal data class DeviceDialog(
    val window: DeviceWindow,
    val address: String? = null,
    val epoch: Long,
    val connectionEpoch: Long,
    val selectedAddress: String? = null,
    val unpairToken: Long = 0L,
    // 只有同一次历史操作 -> 配置切换共享真实宿主；逻辑窗口 epoch 与草稿仍各自独立。
    val hostEpoch: Long = epoch,
)

// 下一窗口只携带目标与守卫；完整退出之前不读取、更不保存明文草稿。
internal data class PendingDeviceWindow(val afterExit: DeviceDialog, val next: DeviceDialog, val observedRevision: Long)

// 历史宿主的普通关闭与配置切换均保留原 260ms 下退；不再用淡出替代可见的上下移动。
internal const val HISTORY_WINDOW_EXIT_MILLIS = 260
internal const val HISTORY_WINDOW_ENTER_MILLIS = 260

/** onPlaced 实测未平移的完整面板与可见 viewport 底边；含标题/圆角/边距，排除 IME/系统栏遮挡。 */
internal data class HistoryPanelGeometry(val topPx: Float, val heightPx: Float, val windowBottomPx: Float) {
    val isPlaced: Boolean get() = topPx.isFinite() && heightPx.isFinite() && windowBottomPx.isFinite() &&
        heightPx > 0f && windowBottomPx > 0f && windowBottomPx > topPx
    val distanceToBottom: Float get() = (windowBottomPx - topPx).coerceAtLeast(0f)
    fun offset(progress: Float): Float = distanceToBottom * progress.coerceIn(0f, 1f)
    fun isVisible(progress: Float): Boolean = isPlaced &&
        topPx + offset(progress) < windowBottomPx && topPx + heightPx + offset(progress) > 0f
    fun isBelowBottom(progress: Float): Boolean = isPlaced && topPx + offset(progress) >= windowBottomPx
}

internal data class HistoryPanelMeasurement(val epoch: Long, val geometry: HistoryPanelGeometry) {
    fun isFor(panelEpoch: Long): Boolean = epoch == panelEpoch && geometry.isPlaced
}

internal fun isLargeHistoryWindow(widthDp: Float, heightDp: Float): Boolean = widthDp >= 840f && heightDp >= 480f

/** 等真实下移动画返回；取消/异常不退休旧面板，更不在 finally 中复活待打开窗口。 */
internal suspend fun runDeviceWindowHandoffExit(
    animateExit: suspend () -> Unit,
    isCurrent: () -> Boolean,
    retirePanel: () -> Unit,
) {
    if (!isCurrent()) return
    animateExit()
    if (isCurrent()) retirePanel()
}

internal fun String.isSelectedIn(state: AppUiState): Boolean = equals(state.selectedDeviceAddress, ignoreCase = true)

internal fun canShowDeviceWindow(state: AppUiState): Boolean =
    !state.introductionPending && !state.unpairWorking && !state.connecting && !state.operation.active && !state.pickingHwt &&
        state.operationDialog == null && state.prompt == null && !state.needsPin

internal fun canSelectWatch(state: AppUiState): Boolean = !state.connected && canShowDeviceWindow(state)

internal fun canShowHistoryWindow(state: AppUiState): Boolean = canShowDeviceWindow(state) && !state.pickingHwt

internal fun canEditHistoryHuaweiId(state: AppUiState, address: String): Boolean =
    canShowHistoryWindow(state) && !UiPolicy.isUnpairTarget(state, address)

/** 退出宿主只保留仍合法的目标上下文；这不赋予任何点击资格。 */
internal fun canRetainDeviceWindow(expected: DeviceDialog, state: AppUiState, ownerActive: Boolean): Boolean =
    ownerActive && expected.connectionEpoch == state.connectionEpoch && expected.unpairToken == state.unpairToken &&
        expected.selectedAddress.equals(state.selectedDeviceAddress, true) && canShowDeviceWindow(state) && when (expected.window) {
            DeviceWindow.DISCOVERY -> canSelectWatch(state)
            DeviceWindow.HUAWEI_ID -> expected.address?.isSelectedIn(state) == true && UiPolicy.canEditHuaweiId(state)
            DeviceWindow.HISTORY, DeviceWindow.HISTORY_HUAWEI_ID -> expected.address != null &&
                canEditHistoryHuaweiId(state, expected.address) && state.savedDevices.any { it.address.equals(expected.address, true) }
        }

internal fun isDeviceDialogCurrent(
    expected: DeviceDialog,
    opened: DeviceDialog?,
    state: AppUiState,
    ownerActive: Boolean,
    ownerEpoch: Long,
): Boolean = expected == opened && expected.epoch == ownerEpoch && canRetainDeviceWindow(expected, state, ownerActive)

internal fun isPendingDeviceWindowCurrent(
    expected: PendingDeviceWindow,
    pending: PendingDeviceWindow?,
    exiting: DeviceDialog?,
    opened: DeviceDialog?,
    state: AppUiState,
    ownerActive: Boolean,
    ownerEpoch: Long,
): Boolean = ownerActive && expected == pending && expected.afterExit == exiting && opened == null &&
    expected.next.epoch == ownerEpoch && expected.afterExit.window == DeviceWindow.HISTORY &&
    expected.next.window == DeviceWindow.HISTORY_HUAWEI_ID && expected.afterExit.address.equals(expected.next.address, true) &&
    expected.afterExit.hostEpoch == expected.next.hostEpoch && expected.afterExit.epoch != expected.next.epoch &&
    state.connectionFormRevision == expected.observedRevision && canRetainDeviceWindow(expected.afterExit, state, ownerActive) &&
    canRetainDeviceWindow(expected.next, state, ownerActive)

/**
 * 只取消仍归此真实历史宿主所有的 UI，不恢复任何已退休面板的设备动作。
 * 同一次受守卫的下退/上移可改变逻辑 epoch，但不能借旧宿主取消同 MAC 重开的窗口或 B。
 * 编辑回显失效时仍可安全中止自己的待切换 UI；这不会放宽继续/读取/保存的 revision 守卫。
 */
internal fun canCancelHistoryWindow(
    expectedHost: DeviceDialog,
    opened: DeviceDialog?,
    exiting: DeviceDialog?,
    pending: PendingDeviceWindow?,
    state: AppUiState,
    ownerActive: Boolean,
    ownerEpoch: Long,
): Boolean {
    val current = opened ?: exiting ?: return false
    val history = setOf(DeviceWindow.HISTORY, DeviceWindow.HISTORY_HUAWEI_ID)
    if (expectedHost.window !in history || current.window !in history || expectedHost.address == null ||
        expectedHost.hostEpoch != current.hostEpoch || !expectedHost.address.equals(current.address, true) ||
        expectedHost.connectionEpoch != current.connectionEpoch || expectedHost.unpairToken != current.unpairToken ||
        !expectedHost.selectedAddress.equals(current.selectedAddress, true) || !canRetainDeviceWindow(current, state, ownerActive)
    ) return false
    return when {
        opened != null -> isDeviceDialogCurrent(current, opened, state, ownerActive, ownerEpoch)
        pending != null -> pending.afterExit == current && current.window == DeviceWindow.HISTORY &&
            pending.next.window == DeviceWindow.HISTORY_HUAWEI_ID && pending.next.hostEpoch == current.hostEpoch &&
            pending.next.address.equals(current.address, true) && pending.next.epoch == ownerEpoch &&
            canRetainDeviceWindow(pending.next, state, ownerActive)
        else -> ownerEpoch == current.epoch + 1L
    }
}

/** 普通关闭通知不得消费切换；旧 keyed 面板已销毁的同一完成回调才可打开下一逻辑窗口。 */
internal fun canFinishDeviceWindowExit(
    expected: DeviceDialog,
    exiting: DeviceDialog?,
    opened: DeviceDialog?,
    pending: PendingDeviceWindow?,
    completedHandoff: PendingDeviceWindow?,
    state: AppUiState,
    ownerActive: Boolean,
    ownerEpoch: Long,
    retiredPanelEpoch: Long? = null,
): Boolean = ownerActive && expected == exiting && opened == null && when {
    pending == null -> completedHandoff == null
    completedHandoff != pending || retiredPanelEpoch != expected.epoch -> false
    else -> pending.afterExit == expected &&
        isPendingDeviceWindowCurrent(pending, pending, exiting, opened, state, ownerActive, ownerEpoch)
}
