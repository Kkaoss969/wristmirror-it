package io.github.escosis.hwtfaceswap.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.captionBarPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import top.yukonga.miuix.kmp.utils.getRoundedCorner
import top.yukonga.miuix.kmp.utils.platformDialogProperties

/** 普通关闭与转配置共用收起曲线，避免交接分支的匀速运动显得更慢。 */
internal fun historyWindowExitSpec(): TweenSpec<Float> =
    tween(durationMillis = HISTORY_WINDOW_EXIT_MILLIS, easing = DecelerateEasing(1.5f))

/**
 * 仅历史操作/配置的真实窗口宿主。从第一次 composition 就拥有完整面板的上下位移，
 * 不启动 Miuix 的整屏位移入场，因此不存在旧窗口退空后等新 native Dialog 入场的空档。
 * 使用公开默认尺寸/边距/圆角与相同字段内容；只提供普通系统返回/外点关闭，不复制库的预测返回动画。
 */
@Composable
internal fun HistoryDeviceWindow(
    panel: DeviceDialog,
    show: Boolean,
    pending: PendingDeviceWindow?,
    isCurrentPanel: (DeviceDialog) -> Boolean,
    isCurrentHandoff: (PendingDeviceWindow) -> Boolean,
    onHandoffFinished: (PendingDeviceWindow, Long) -> Boolean,
    onDismiss: () -> Unit,
    onCancelHost: (DeviceDialog) -> Boolean,
    onDismissFinished: () -> Unit,
    content: @Composable (DeviceDialog, Boolean, () -> Boolean) -> Unit,
) {
    val progress = remember { Animatable(1f) }
    val dimProgress = remember { Animatable(0f) }
    var measurement by remember { mutableStateOf<HistoryPanelMeasurement?>(null) }
    var viewportBottom by remember { mutableStateOf<Float?>(null) }
    var nativeVisible by remember { mutableStateOf(true) }
    var turn by remember { mutableStateOf<PendingDeviceWindow?>(null) }
    val disposedPanelEpoch = remember { mutableStateOf<Long?>(null) }
    val hostOwner = remember(panel.hostEpoch) { panel }
    val latestPanel by rememberUpdatedState(panel)
    val latestShow by rememberUpdatedState(show)
    val latestPending by rememberUpdatedState(pending)
    val latestIsCurrentPanel by rememberUpdatedState(isCurrentPanel)
    val latestIsCurrentHandoff by rememberUpdatedState(isCurrentHandoff)
    val latestOnHandoffFinished by rememberUpdatedState(onHandoffFinished)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnCancelHost by rememberUpdatedState(onCancelHost)
    val latestOnDismissFinished by rememberUpdatedState(onDismissFinished)

    LaunchedEffect(panel.epoch, show, pending) {
        // 不让动画先于真实首帧测量前进；预备新面板已在底边测量好时，此等待立即满足。
        snapshotFlow { measurement?.takeIf { it.isFor(panel.epoch) } }.filterNotNull().first()
        val handoff = pending
        when {
            handoff != null -> runDeviceWindowHandoffExit(
                animateExit = {
                    // 与普通关闭同速下退到实测底边；没有淡出/缩放或额外等待。
                    progress.animateTo(1f, historyWindowExitSpec())
                },
                isCurrent = { latestPending == handoff && latestIsCurrentHandoff(handoff) },
                retirePanel = {
                    if (measurement?.takeIf { it.isFor(handoff.afterExit.epoch) }?.geometry?.isBelowBottom(progress.value) == true) {
                        // 同逻辑 key 的 native 子 composition 曾重建时，旧回执不能充当本次退休回执。
                        disposedPanelEpoch.value = null
                        turn = handoff
                    }
                },
            )
            !show -> {
                // 普通历史关闭仍为 260ms 下退与 250ms 背景淡出，旧按钮展示资格不变。
                coroutineScope {
                    launch { dimProgress.animateTo(0f, tween(250, easing = DecelerateEasing(1.5f))) }
                    progress.animateTo(1f, historyWindowExitSpec())
                }
                disposedPanelEpoch.value = null
                nativeVisible = false
            }
            else -> coroutineScope {
                launch { dimProgress.animateTo(1f, tween(300, easing = DecelerateEasing(1.5f))) }
                // 同一真实宿主直接从实测底边上移；不会再次启动库的 native-window 入场。
                progress.animateTo(0f, tween(HISTORY_WINDOW_ENTER_MILLIS, easing = LinearOutSlowInEasing))
            }
        }
    }

    if (!nativeVisible) {
        // 回执在子 composition 销毁后到达也必须能唤醒宿主，不依赖一次性延后读取。
        HistoryWindowDisposalEffect(panel.epoch, disposedPanelEpoch) { retiredEpoch ->
            if (!nativeVisible && latestPanel == panel && retiredEpoch == panel.epoch) latestOnDismissFinished()
        }
        return
    }

    val completed = turn?.takeIf { it.afterExit == panel }
    val presentedPanel = completed?.next ?: panel
    val active = show && presentedPanel == panel
    val interactive: () -> Boolean = {
        nativeVisible && latestShow && latestPanel == presentedPanel && latestIsCurrentPanel(presentedPanel) &&
            measurement?.takeIf { it.isFor(presentedPanel.epoch) }?.geometry?.isVisible(progress.value) == true
    }
    val requestDismiss: () -> Unit = {
        val ready = measurement?.isFor(latestPanel.epoch) == true
        if (latestShow && latestIsCurrentPanel(latestPanel) && ready) latestOnDismiss()
        else if (latestOnCancelHost(hostOwner)) {
            // 退休/排队/无布局时也可 Back 或点遮罩安全取消，不能困在不可操作的模态遮罩中。
            turn = null
            nativeVisible = false
        }
    }
    // 原面板 onDispose 位于独立 Dialog composition；回执读取必须参与宿主的 snapshot 观察。
    HistoryWindowDisposalEffect(completed?.afterExit?.epoch, disposedPanelEpoch) { retiredEpoch ->
        if (completed != null && latestPending == completed && latestIsCurrentHandoff(completed) &&
            latestOnHandoffFinished(completed, retiredEpoch)) turn = null
    }

    val defaults = platformDialogProperties()
    val properties = remember(defaults) {
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = defaults.dismissOnClickOutside,
            securePolicy = defaults.securePolicy,
            usePlatformDefaultWidth = defaults.usePlatformDefaultWidth,
            decorFitsSystemWindows = defaults.decorFitsSystemWindows,
            windowTitle = defaults.windowTitle,
            windowType = defaults.windowType,
            windowToken = defaults.windowToken,
        )
    }
    Dialog(onDismissRequest = requestDismiss, properties = properties) {
        RemovePlatformDialogDefaultEffects()
        val windowSize = LocalWindowInfo.current.containerDpSize
        val large = isLargeHistoryWindow(windowSize.width.value, windowSize.height.value)
        val outside = DialogDefaults.outsideMargin
        val inside = DialogDefaults.insideMargin
        val corner = (getRoundedCorner() - if (large) 0.dp else outside.width).coerceAtLeast(32.dp)
        val topInset = maxOf(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            WindowInsets.captionBar.asPaddingValues().calculateTopPadding(),
            WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
        )
        val dimColor = MiuixTheme.colorScheme.windowDimming
        Box(Modifier.fillMaxSize()
            .drawBehind { drawRect(dimColor.copy(alpha = dimColor.alpha * dimProgress.value)) }
            .pointerInput(Unit) { detectTapGestures { requestDismiss() } }
            .semantics { onClick(label = "Chiudi") { requestDismiss(); true } },
        ) {
            Box(
                Modifier.imePadding().navigationBarsPadding().captionBarPadding().fillMaxSize()
                    .clipToBounds()
                    .onPlaced { coordinates ->
                        // 实测可见 viewport 底边，不能让新面板先在键盘/系统栏后面走一段不可见入场。
                        viewportBottom = coordinates.positionInRoot().y + coordinates.size.height
                    }
                    .padding(horizontal = outside.width).padding(top = topInset, bottom = outside.height),
            ) {
                Column(
                    Modifier.align(if (large) Alignment.Center else Alignment.BottomCenter)
                        .widthIn(max = DialogDefaults.MaxWidth)
                        .heightIn(max = if (large) windowSize.height * (2f / 3f) else Dp.Unspecified)
                        .onPlaced { coordinates ->
                            // 此节点在移动子层之前读取真实未平移的完整面板；换高度/IME/横竖屏同帧重算。
                            measurement = HistoryPanelMeasurement(presentedPanel.epoch,
                                HistoryPanelGeometry(coordinates.positionInRoot().y, coordinates.size.height.toFloat(),
                                    viewportBottom ?: 0f))
                        }
                        .layout { measurable, constraints ->
                            val measured = measurable.measure(constraints)
                            layout(measured.width, measured.height) {
                                measured.placeWithLayer(0, 0) {
                                    // onPlaced 在此子层之前执行；无测量时只作保守隐藏，绝不闪出原位面板。
                                    translationY = measurement?.takeIf { it.isFor(presentedPanel.epoch) }?.geometry?.offset(progress.value)
                                        ?: windowSize.height.toPx()
                                }
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures { /* 面板内的空白不触发外点关闭。 */ } }
                        .squircleSurface(color = DialogDefaults.backgroundColor(), cornerRadius = corner)
                        .padding(horizontal = inside.width, vertical = inside.height),
                ) {
                    key(presentedPanel.epoch) {
                        DisposableEffect(presentedPanel.epoch) {
                            onDispose { disposedPanelEpoch.value = presentedPanel.epoch }
                        }
                        Text(
                            text = if (presentedPanel.window == DeviceWindow.HISTORY) "Azioni dispositivo" else "Configura ID Huawei",
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            fontSize = MiuixTheme.textStyles.title4.fontSize,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = DialogDefaults.titleColor(),
                        )
                        CompositionLocalProvider(LocalDismissState provides requestDismiss) {
                            content(presentedPanel, active, interactive)
                        }
                    }
                }
            }
        }
    }
}
