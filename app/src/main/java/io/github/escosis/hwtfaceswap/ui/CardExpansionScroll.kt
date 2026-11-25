package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.FaceItem
import io.github.escosis.hwtfaceswap.SourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

/** 当前外卡与扣除顶栏/底栏后的实际可视区域，单位均为布局像素。 */
internal data class CardExpansionGeometry(
    val width: Int,
    val cardTop: Float,
    val cardBottom: Float,
    val viewportTop: Float,
    val viewportBottom: Float,
    val edgeMargin: Float,
) {
    val valid: Boolean get() = width > 0 &&
            listOf(cardTop, cardBottom, viewportTop, viewportBottom, edgeMargin).all { it.isFinite() } &&
            cardBottom > cardTop && viewportBottom > viewportTop && edgeMargin >= 0f
}

/** 实时跟随：只补底部缺口，且绝不让标题被推出顶栏；完全可见时零距离。 */
internal object CardExpansionVisibilityPolicy {
    fun followDistance(geometry: CardExpansionGeometry): Float {
        if (!geometry.valid) return 0f
        val margin = geometry.edgeMargin.toDouble()
        val bottomGap = geometry.cardBottom - (geometry.viewportBottom - margin)
        if (bottomGap <= 0.5) return 0f
        val topRoom = geometry.cardTop - (geometry.viewportTop + margin)
        if (topRoom <= 0.5) return 0f
        return minOf(bottomGap, topRoom).toFloat()
    }
}

internal data class CardExpansionKey(val epoch: Long, val id: String, val version: String)

internal class CardExpansionIntent internal constructor(
    val owner: Any,
    val key: CardExpansionKey,
    val serial: Long,
    val measuredWidth: Int,
    initialHeight: Int,
    internal val stillCurrent: () -> Boolean,
) {
    internal var previousHeight = initialHeight
    internal var finishedHeight: Int? = null
    internal var measurementFinished = false
    // IntSize 可以在 tween 真正结束前舍入到目标；自动滚动还必须等真实 finished 回调。
    internal var animationComplete = false
    internal var geometry: () -> CardExpansionGeometry? = { null }
    internal var canceled = false
}

internal data class CardExpansionRowFrame(
    val key: CardExpansionKey,
    val face: FaceItem,
    val guard: FaceCardGuardFrame,
    val source: SourceInfo?,
    val renderedRowWasUnique: Boolean,
)

/** Cosmetic one-line name updates preserve an intent; row, source and operation context changes do not. */
internal object CardExpansionPolicy {
    fun capture(state: AppUiState, face: FaceItem, epoch: Long) = CardExpansionRowFrame(
        CardExpansionKey(epoch, face.id, face.version), face, FaceCardGuardFrame.capture(state), state.source,
        FaceCardPolicy.rowCurrent(face, epoch, state),
    )

    fun current(frame: CardExpansionRowFrame, latest: AppUiState): Boolean =
        frame.renderedRowWasUnique && !frame.guard.listRefreshing && !latest.listRefreshing &&
                frame.guard == FaceCardGuardFrame.capture(latest) && frame.source == latest.source &&
                FaceCardPolicy.rowCurrent(frame.face, frame.key.epoch, latest)
}

/**
 * 真实 Card 外层已布局高度的纯账本。只由显式展开意图开启；无意图的测量、收起、名称/列表
 * 更新均不创建意图。高度变化只用于触发一次“当前可视缺口”的跟随计算，不再作为滚动距离。
 */
internal class CardExpansionLedger {
    private val intents = linkedMapOf<Any, CardExpansionIntent>()
    private var nextSerial = 0L
    private var userDragging = false

    fun begin(owner: Any, key: CardExpansionKey, width: Int, height: Int, stillCurrent: () -> Boolean): CardExpansionIntent? {
        cancel(owner)
        if (userDragging || width <= 0 || height <= 0) return null
        val intent = CardExpansionIntent(owner, key, ++nextSerial, width, height, stillCurrent)
        intents[owner] = intent
        return intent
    }

    /** 高度真的变了才发信号；滚动本身不改变高度，不会自激循环。 */
    fun measured(intent: CardExpansionIntent, width: Int, height: Int): Boolean {
        if (!current(intent)) return false
        if (width != intent.measuredWidth || height <= 0) {
            cancel(intent.owner)
            return false
        }
        val changed = height != intent.previousHeight
        intent.previousHeight = height
        if (intent.finishedHeight == height) intent.measurementFinished = true
        return changed
    }

    /** 内层真实展开内容的测量只定义结束高度，绝不产生滚动像素。 */
    fun targetMeasured(intent: CardExpansionIntent, width: Int, height: Int): Boolean {
        if (!current(intent)) return false
        if (width != intent.measuredWidth || height <= 0 || (intent.finishedHeight != null && intent.finishedHeight != height)) {
            cancel(intent.owner)
            return false
        }
        intent.finishedHeight = height
        if (intent.previousHeight == height) intent.measurementFinished = true
        return true
    }

    /** 可在最后一次外层布局之前或之后到达，旧动画的不同 target 不能结束新意图。 */
    fun animationFinished(intent: CardExpansionIntent, targetHeight: Int): Boolean {
        if (!current(intent) || (intent.finishedHeight != null && intent.finishedHeight != targetHeight)) return false
        intent.animationComplete = true
        intent.finishedHeight = targetHeight
        if (intent.previousHeight == targetHeight) intent.measurementFinished = true
        return true
    }

    fun current(intent: CardExpansionIntent): Boolean {
        if (intent.canceled || userDragging || intents[intent.owner] !== intent) return false
        if (!intent.stillCurrent()) {
            cancel(intent.owner)
            return false
        }
        return true
    }

    fun pending(): List<CardExpansionIntent> =
        intents.values.toList().filter { current(it) }

    fun validate() {
        for (intent in intents.values.toList()) current(intent)
    }

    fun cancel(owner: Any) {
        intents.remove(owner)?.canceled = true
    }

    /** 旧动画的退出不得取消同一 owner 后来创建的新意图。 */
    fun cancel(intent: CardExpansionIntent) {
        if (intents[intent.owner] === intent) cancel(intent.owner)
        else intent.canceled = true
    }

    fun cancelAll() {
        for (intent in intents.values) intent.canceled = true
        intents.clear()
    }

    fun setUserDragging(dragging: Boolean) {
        userDragging = dragging
        if (dragging) cancelAll()
        // 结束拖动不恢复旧意图；必须再次显式展开才有新滚动预算。
    }
}

// Compose bridge: only public Foundation / Compose APIs, with no distance or timing estimates.
internal class CardExpansionScrollController {
    internal val ledger = CardExpansionLedger()
    internal val signals = Channel<Unit>(Channel.CONFLATED)
    private var disposed = false
    private val userDrags = mutableSetOf<Any>()

    fun userDragStarted(token: Any) {
        if (disposed) return
        userDrags.add(token)
        ledger.setUserDragging(true)
    }

    fun userDragEnded(token: Any) {
        if (disposed) return
        userDrags.remove(token)
        ledger.setUserDragging(userDrags.isNotEmpty())
    }

    fun begin(
        owner: Any,
        key: CardExpansionKey,
        width: Int,
        height: Int,
        geometry: () -> CardExpansionGeometry? = { null },
        stillCurrent: () -> Boolean,
    ): CardExpansionIntent? {
        if (disposed) return null
        // 最新的显式展开拥有滚动焦点；其它卡片仍保持各自展开状态。
        ledger.cancelAll()
        return ledger.begin(owner, key, width, height, stillCurrent)?.also { it.geometry = geometry }
    }

    fun measured(intent: CardExpansionIntent, width: Int, height: Int) {
        if (!disposed && ledger.measured(intent, width, height)) signals.trySend(Unit)
    }

    fun targetMeasured(intent: CardExpansionIntent, width: Int, height: Int) {
        if (!disposed && ledger.targetMeasured(intent, width, height)) signals.trySend(Unit)
    }

    fun animationFinished(intent: CardExpansionIntent, targetHeight: Int) {
        if (!disposed && ledger.animationFinished(intent, targetHeight)) signals.trySend(Unit)
    }

    fun cancel(owner: Any) = ledger.cancel(owner)
    fun cancelAll() = ledger.cancelAll()
    fun validate() = ledger.validate()

    fun dispose() {
        disposed = true
        userDrags.clear()
        ledger.cancelAll()
        signals.close()
    }
}

/**
 * 展开动画每帧调用一次：用当前几何算“底部还差多少露出、顶部还能让多少”，
 * 只滚这个缺口。动画结束后再滚一次最终缺口即退役，不做追尾或端点重试。
 */
internal suspend fun applyCardExpansionFollow(
    controller: CardExpansionScrollController,
    listState: LazyListState,
    intent: CardExpansionIntent,
) {
    if (!controller.ledger.current(intent)) return
    try {
        listState.scroll(MutatePriority.Default) {
            if (!controller.ledger.current(intent)) return@scroll
            val geometry = intent.geometry() ?: return@scroll
            if (!geometry.valid || geometry.width != intent.measuredWidth) {
                controller.ledger.cancel(intent)
                return@scroll
            }
            val distance = CardExpansionVisibilityPolicy.followDistance(geometry)
            if (distance > 0.5f) scrollBy(distance)
        }
        // 动画已经结束，本次就是最后一次跟随：退役，后续高度不再变化。
        if (intent.animationComplete) controller.ledger.cancel(intent)
    } catch (canceled: CancellationException) {
        controller.ledger.cancel(intent)
        if (!currentCoroutineContext().isActive) throw canceled
    }
}

/** Passive observation also catches a horizontal pager drag; it never consumes or synthesizes input. */
internal fun Modifier.cancelCardExpansionOnUserDrag(controller: CardExpansionScrollController): Modifier = pointerInput(controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val token = Any()
        var dragging = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!dragging && ((change.position - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.count { it.pressed } > 1)) {
                    dragging = true
                    controller.userDragStarted(token)
                }
            }
        } finally {
            if (dragging) controller.userDragEnded(token)
        }
    }
}

@Composable
internal fun rememberCardExpansionScroll(listState: LazyListState): CardExpansionScrollController {
    val controller = remember(listState) { CardExpansionScrollController() }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller, listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> controller.userDragStarted(interaction)
                is DragInteraction.Stop -> controller.userDragEnded(interaction.start)
                is DragInteraction.Cancel -> controller.userDragEnded(interaction.start)
            }
        }
    }
    LaunchedEffect(controller, listState) {
        // 不再等整段动画结束；每个 measured 信号都在当前帧/下一帧补一次可见缺口。
        for (signal in controller.signals) {
            for (intent in controller.ledger.pending()) {
                applyCardExpansionFollow(controller, listState, intent)
            }
        }
    }
    return controller
}