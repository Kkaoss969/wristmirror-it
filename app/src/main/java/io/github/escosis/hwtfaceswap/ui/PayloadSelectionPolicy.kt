package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.SourceInfo
import io.github.escosis.hwtfaceswap.UiPolicy
import io.github.escosis.hwtfaceswap.protocol.PayloadKinds

/** 类型选择只修改本地直装意图；仍拒绝旧文件/旧 owner/模态等待中的回调。 */
internal data class PayloadSelectionFrame(
    val source: SourceInfo,
    val kind: String,
    val guard: FaceCardGuardFrame,
    val enabled: Boolean,
)

internal object PayloadSelectionPolicy {
    fun canSelect(state: AppUiState): Boolean =
        state.source != null && UiPolicy.canChooseHwt(state) && !state.unpairing && !state.listRefreshing

    fun capture(source: SourceInfo, kind: String, state: AppUiState) = PayloadSelectionFrame(
        source, kind, FaceCardGuardFrame.capture(state),
        source === state.source && canSelect(state) && kind in source.payloadKinds && PayloadKinds.mode(kind) != null,
    )

    fun current(frame: PayloadSelectionFrame, latest: AppUiState): Boolean =
        frame.source === latest.source && frame.guard == FaceCardGuardFrame.capture(latest) &&
            frame.enabled == capture(frame.source, frame.kind, latest).enabled

    fun invoke(
        frame: PayloadSelectionFrame,
        latest: AppUiState,
        actions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
    ): Boolean {
        if (!frame.enabled || !interaction.allowsInteraction || actions !== latestActions || !current(frame, latest)) return false
        actions.selectPayloadKind(frame.kind)
        return true
    }
}
