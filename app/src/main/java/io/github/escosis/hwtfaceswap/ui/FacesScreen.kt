package io.github.escosis.hwtfaceswap.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.FaceItem
import io.github.escosis.hwtfaceswap.SourceInfo
import io.github.escosis.hwtfaceswap.protocol.PayloadKinds
import io.github.escosis.hwtfaceswap.UiPolicy
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Referential snapshots also replace equal-valued objects, so old callbacks cannot retain a new
// actions/context owner merely because its equals() implementation says the objects are equal.
private class FacesScreenRenderFrame(
    val state: AppUiState,
    val actions: AppActions,
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    val windowInfo: WindowInfo,
    val pickHwt: () -> Unit,
)

private class SourceToggleFrame(val source: SourceInfo?, val expanded: Boolean)
private class FaceExpansionFrame(val face: FaceItem, val epoch: Long, val expanded: Boolean)
private class FaceViewportFrame(val insets: PaddingValues, val density: Density)

@Composable
internal fun FacesScreen(
    state: AppUiState,
    actions: AppActions,
    insets: PaddingValues,
    onPickHwt: () -> Unit,
    scrollModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val renderFrame = FacesScreenRenderFrame(state, actions, context, lifecycleOwner, windowInfo, onPickHwt)
    val latestFrame by rememberUpdatedState(renderFrame)
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val host = remember(owner) {
        FaceActionHost(
            state = { latestFrame.state }, actions = { latestFrame.actions },
            context = { latestFrame.context }, lifecycleOwner = { latestFrame.lifecycleOwner },
            windowInfo = { latestFrame.windowInfo }, pickHwt = { latestFrame.pickHwt },
            ownerCurrent = { owner.get() },
        )
    }
    val viewportCoordinates = remember(owner) { arrayOfNulls<LayoutCoordinates>(1) }
    val viewportFrame = FaceViewportFrame(insets, LocalDensity.current)
    val latestViewportFrame by rememberUpdatedState(viewportFrame)
    val readGeometry: (LayoutCoordinates) -> CardExpansionGeometry? = remember(owner) {
        geometry@{ card ->
            val viewport = viewportCoordinates[0]
            if (!owner.get() || !card.isAttached || viewport?.isAttached != true) return@geometry null
            val visible = viewport.boundsInWindow(clipBounds = true)
            if (visible.isEmpty) return@geometry null
            val rawViewport = viewport.boundsInWindow(clipBounds = false)
            val latest = latestViewportFrame
            // Miuix Scaffold 的内容原点仍为(0,0)，PaddingValues 内的真实顶/底栏高度需扣除。
            val topInset = with(latest.density) { latest.insets.calculateTopPadding().toPx() }
            val bottomInset = with(latest.density) { latest.insets.calculateBottomPadding().toPx() }
            val cardTop = card.boundsInWindow(clipBounds = false).top
            CardExpansionGeometry(
                width = card.size.width,
                cardTop = cardTop,
                cardBottom = cardTop + card.size.height,
                viewportTop = maxOf(visible.top, rawViewport.top + topInset),
                viewportBottom = minOf(visible.bottom, rawViewport.bottom - bottomInset),
                edgeMargin = with(latest.density) { 12.dp.toPx() },
            )
        }
    }
    DisposableEffect(owner) {
        onDispose {
            owner.set(false)
            viewportCoordinates[0] = null
        }
    }
    val listState = rememberLazyListState()
    val expansionScroll = rememberCardExpansionScroll(listState)
    // Validation only cancels invalid in-flight intents; refresh/name/state updates never create one.
    LaunchedEffect(renderFrame) { expansionScroll.validate() }
    // 只随同一连接的刷新开始触发一次；型号回显、进度等普通重组不会重复提示。
    LaunchedEffect(state.connectionEpoch, state.listRefreshing) {
        val current = host.state()
        if (owner.get() && current.connectionEpoch == state.connectionEpoch && UiPolicy.showFaceRefreshNotice(current)) {
            Toast.makeText(context, "Aggiornamento dell'elenco dei quadranti, attendi", Toast.LENGTH_SHORT).show()
        }
    }
    LazyColumn(
        state = listState,
        modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets)
            .onGloballyPositioned { if (owner.get()) viewportCoordinates[0] = it }
            .cancelCardExpansionOnUserDrag(expansionScroll),
        contentPadding = pageContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "face_and_file_actions") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 16.dp)) {
                // 标题与动画详情共用完整源快照。换源即销毁旧子树，不把旧退出预览挂在新标题下。
                key(state.source) {
                    val sourceSnapshot = remember { state.source }
                    val sourceOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
                    DisposableEffect(sourceOwner) { onDispose { sourceOwner.set(false) } }
                    var showSource by rememberSaveable(sourceSnapshot) { mutableStateOf(sourceSnapshot != null) }
                    val renderedExpanded = showSource
                    val sourceTransition = remember { MutableTransitionState(false) }
                    sourceTransition.targetState = showSource && sourceSnapshot != null
                    val toggleFrame = SourceToggleFrame(sourceSnapshot, renderedExpanded)
                    val interaction = rememberFaceUiInteraction(
                        toggleFrame, host, actions, ownerCurrent = { sourceOwner.get() },
                        intentCurrent = {
                            sourceToggleCurrent(toggleFrame.source, host.state().source, toggleFrame.expanded, showSource)
                        },
                    )
                    val toggleSource: () -> Unit = {
                        if (interaction.guard().allowsInteraction) {
                            expansionScroll.cancelAll()
                            showSource = !toggleFrame.expanded
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().then(interaction.modifier)
                            .then(if (sourceSnapshot != null) Modifier.clickable(role = Role.Button, onClick = toggleSource) else Modifier)
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (sourceSnapshot == null) "File HWT: non selezionato" else "File HWT:", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            sourceSnapshot?.let { file ->
                                Text(file.fileName.uiText(), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (sourceSnapshot != null) ExpandButton(showSource, "Informazioni file", onClick = toggleSource)
                    }
                    AnimatedVisibility(
                        visibleState = sourceTransition,
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(160, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                        exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                            shrinkVertically(tween(160, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(12.dp))
                            sourceSnapshot?.let { SourceDetails(it, state, actions, host, { sourceOwner.get() && showSource }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                FacePageActions(state, actions, host, onPickHwt)
            }
        }
        if (pageOperationCardVisible(state.operation)) {
            item(key = "operation") { OperationCard(state.operation) }
        }
        item(key = "list_heading") { SectionHeading("Tutti i quadranti (${state.faces.size})") }
        if (state.faces.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    title = when {
                        state.listRefreshing -> "Lettura dei quadranti"
                        state.listReady -> "Il watch ha restituito un elenco vuoto"
                        else -> "In attesa della lettura dei quadranti"
                    },
                    message = if (state.connected) "Tocca Aggiorna e attendi che il watch restituisca l'elenco dei quadranti." else "Vai prima alla pagina Dispositivi e connetti il watch.",
                    icon = WatchIcons.Faces,
                )
            }
        } else {
            // Include epoch and index so even a malformed duplicate-ID list has unique owners/keys.
            itemsIndexed(state.faces, key = { index, face -> "${state.connectionEpoch}:$index:${face.id}:${face.version}" }) { _, face ->
                FaceRow(face, state.connectionEpoch, state, actions, host, expansionScroll, readGeometry)
            }
        }
    }
}

/** 同一源的旧展开帧不能再次翻转状态；完整元数据改变也视为换源。 */
private fun sourceToggleCurrent(
    renderedSource: SourceInfo?,
    latestSource: SourceInfo?,
    renderedExpanded: Boolean,
    latestExpanded: Boolean,
): Boolean = renderedSource != null && renderedSource == latestSource && renderedExpanded == latestExpanded

@Composable
private fun FaceRow(
    face: FaceItem,
    epoch: Long,
    state: AppUiState,
    actions: AppActions,
    host: FaceActionHost,
    expansionScroll: CardExpansionScrollController,
    geometryFor: (LayoutCoordinates) -> CardExpansionGeometry?,
) {
    var expanded by rememberSaveable(epoch, face.id, face.version) { mutableStateOf(false) }
    val renderedExpanded = expanded
    val rowOwner = remember(epoch, face.id, face.version) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val coordinates = remember(rowOwner) { arrayOfNulls<LayoutCoordinates>(1) }
    val activeIntent = remember(rowOwner) { arrayOfNulls<CardExpansionIntent>(1) }
    DisposableEffect(rowOwner) {
        onDispose {
            rowOwner.set(false)
            coordinates[0] = null
            activeIntent[0] = null
            expansionScroll.cancel(rowOwner)
        }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val toggleFrame = FaceExpansionFrame(face, epoch, renderedExpanded)
    val headerInteraction = rememberFaceUiInteraction(
        toggleFrame, host, actions, ownerCurrent = { rowOwner.get() },
        intentCurrent = {
            toggleFrame.expanded == expanded && FaceCardPolicy.rowCurrent(toggleFrame.face, toggleFrame.epoch, host.state())
        },
    )
    val toggle: () -> Unit = {
        if (headerInteraction.guard().allowsInteraction) {
            // 任何新的明确展开/收起操作都让旧自动滚动让出控制，不只取消这一张卡。
            expansionScroll.cancelAll()
            if (toggleFrame.expanded) {
                activeIntent[0] = null
                expanded = false
            } else {
                val placed = coordinates[0]
                val scrollFrame = CardExpansionPolicy.capture(state, face, epoch)
                var intent: CardExpansionIntent? = null
                if (placed?.isAttached == true) {
                    intent = expansionScroll.begin(
                        rowOwner, scrollFrame.key, placed.size.width, placed.size.height,
                        geometry = { coordinates[0]?.takeIf { it.isAttached }?.let(geometryFor) },
                    ) {
                        val current = host.state()
                        val currentPlacement = coordinates[0]
                        rowOwner.get() && host.ownerCurrent() && activeIntent[0] === intent && expanded &&
                            CardExpansionPolicy.current(scrollFrame, current) &&
                            host.actions() === actions && host.context() === context &&
                            host.lifecycleOwner() === lifecycleOwner && host.windowInfo() === windowInfo &&
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && windowInfo.isWindowFocused &&
                            currentPlacement?.isAttached == true && !currentPlacement.boundsInWindow(clipBounds = true).isEmpty
                    }
                }
                activeIntent[0] = intent
                expanded = true
            }
        }
    }
    val colors = MiuixTheme.colorScheme
    val renderedIntent = activeIntent[0]
    // Explicit layout-node boundaries, not observer order on one modifier chain: the outer Box
    // measures its animated child, while the inner non-animated Card reports the content target.
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { placed ->
            if (rowOwner.get()) coordinates[0] = placed
            if (renderedExpanded && expanded && renderedIntent != null && activeIntent[0] === renderedIntent) {
                expansionScroll.measured(renderedIntent, placed.size.width, placed.size.height)
            }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().animateContentSize(
                animationSpec = tween(160, easing = FastOutSlowInEasing),
                finishedListener = { initial, target ->
                    if (renderedExpanded && expanded && renderedIntent != null && activeIntent[0] === renderedIntent && target.height >= initial.height) {
                        expansionScroll.animationFinished(renderedIntent, target.height)
                    }
                },
            ),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().onSizeChanged { target ->
                    // The real measured target only defines settlement, never the scroll budget.
                    if (renderedExpanded && expanded && renderedIntent != null && activeIntent[0] === renderedIntent) {
                        expansionScroll.targetMeasured(renderedIntent, target.width, target.height)
                    }
                },
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(vertical = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().then(headerInteraction.modifier)
                        .clickable(role = Role.Button, onClick = toggle)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        face.title.uiText(), modifier = Modifier.weight(1f),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = colors.onSurfaceContainer,
                    )
                    if (face.current) {
                        Spacer(Modifier.width(8.dp))
                        Text("In uso", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                    ExpandButton(expanded, "Informazioni quadrante", onClick = toggle)
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ValueRow("Versione", face.version.ifBlank { "Non fornita dal watch" })
                            ValueRow("ID", face.id)
                            ValueRow("Tipo", face.kind)
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp,
                            color = colors.outline.copy(alpha = 0.5f),
                        )
                        FaceCardActions(
                            face, epoch, state, actions, host, ownerCurrent = { rowOwner.get() },
                            expandedIntentCurrent = { expanded && renderedExpanded },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandButton(expanded: Boolean, label: String, onClick: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = "Freccia di espansione")
    IconButton(onClick = onClick) {
        Icon(
            imageVector = WatchIcons.Expand,
            contentDescription = (if (expanded) "Comprimi" else "Espandi") + label,
            modifier = Modifier.size(24.dp).rotate(angle),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SourceDetails(
    source: SourceInfo, state: AppUiState, actions: AppActions, host: FaceActionHost,
    ownerCurrent: () -> Boolean, modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HwtPreview(source)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueRow("Nome", source.title, compact = true)
                // 格式按文件自身结构显示，不按默认直装载荷改写。
                ValueRow("Formato", PayloadKinds.displayFormat(source.detectedPayloadKinds), compact = true)
                ValueRow("Dimensione", formatBytes(source.sourceBytes), compact = true)
                ValueRow("Versione", source.xmlVersion.ifBlank { "Non fornito" }, compact = true)
                ValueRow("Schermo", source.screen, compact = true)
            }
        }
        if (source.payloadDiagnostic.isNotBlank()) ValueRow("Diagnostica", source.payloadDiagnostic, compact = true)
        MutedText(PayloadKinds.formatNote(source.detectedPayloadKinds), Modifier.fillMaxWidth())
    }
}
