package io.github.escosis.hwtfaceswap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Dialog 内容是独立 Composition：其旧面板 onDispose 可能晚于宿主的 SideEffect。
 * 必须在 composition 中读取回执，才能由子 composition 的写入重新触发宿主；
 * 不能只在延后 effect 中读取，并假定跨 composition 的销毁/effect 有全局顺序。
 */
@Composable
internal fun HistoryWindowDisposalEffect(
    expectedPanelEpoch: Long?,
    disposedPanelEpoch: State<Long?>,
    onDisposed: (Long) -> Unit,
) {
    val receipt = disposedPanelEpoch.value
    val latestOnDisposed by rememberUpdatedState(onDisposed)
    LaunchedEffect(expectedPanelEpoch, receipt) {
        if (expectedPanelEpoch != null && receipt == expectedPanelEpoch) latestOnDisposed(expectedPanelEpoch)
    }
}
