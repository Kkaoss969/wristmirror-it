package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
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
            Toast.makeText(context, Tr.tr("正在刷新表盘列表，请稍候", "Aggiornamento dell'elenco dei quadranti, attendi", "Refreshing the watch face list, please wait", "Actualizando la lista de esferas, espera", "Actualisation de la liste des cadrans, patientez", "A atualizar a lista de mostradores, aguarde", "Atualizando a lista de mostradores, aguarde", "Опресняване на списъка с циферблати, изчакайте", "Kadran listesi yenileniyor, bekleyin", "Zifferblattliste wird aktualisiert, bitte warten"), Toast.LENGTH_SHORT).show()
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
                            Text(if (sourceSnapshot == null) Tr.tr("HWT 文件：未选择", "File HWT: non selezionato", "HWT file: not selected", "Archivo HWT: no seleccionado", "Fichier HWT : non sélectionné", "Ficheiro HWT: não selecionado", "Arquivo HWT: não selecionado", "HWT файл: не е избран", "HWT dosyası: seçilmedi", "HWT-Datei: nicht ausgewählt") else Tr.tr("HWT 文件：", "File HWT:", "HWT file:", "Archivo HWT:", "Fichier HWT :", "Ficheiro HWT:", "Arquivo HWT:", "HWT файл:", "HWT dosyası:", "HWT-Datei:"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            sourceSnapshot?.let { file ->
                                Text(file.fileName.uiText(), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (sourceSnapshot != null) ExpandButton(showSource, Tr.tr("文件信息", "Informazioni file", "File info", "Información del archivo", "Infos du fichier", "Informações do ficheiro", "Informações do arquivo", "Информация за файла", "Dosya bilgisi", "Dateiinfos"), onClick = toggleSource)
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
        item(key = "list_heading") { SectionHeading(Tr.tr("全部表盘（${state.faces.size}）", "Tutti i quadranti (${state.faces.size})", "All watch faces (${state.faces.size})", "Todas las esferas (${state.faces.size})", "Tous les cadrans (${state.faces.size})", "Todos os mostradores (${state.faces.size})", "Todos os mostradores (${state.faces.size})", "Всички циферблати (${state.faces.size})", "Tüm kadranlar (${state.faces.size})", "Alle Zifferblätter (${state.faces.size})")) }
        if (state.faces.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    title = when {
                        state.listRefreshing -> Tr.tr("正在读取表盘", "Lettura dei quadranti", "Reading watch faces", "Leyendo esferas", "Lecture des cadrans", "A ler os mostradores", "Lendo os mostradores", "Четене на циферблатите", "Kadranlar okunuyor", "Zifferblätter werden gelesen")
                        state.listReady -> Tr.tr("手表返回了空列表", "Il watch ha restituito un elenco vuoto", "The watch returned an empty list", "El reloj devolvió una lista vacía", "La montre a renvoyé une liste vide", "O relógio devolveu uma lista vazia", "O relógio retornou uma lista vazia", "Часовникът върна празен списък", "Saat boş bir liste döndürdü", "Die Uhr hat eine leere Liste geliefert")
                        else -> Tr.tr("等待读取表盘", "In attesa della lettura dei quadranti", "Waiting to read watch faces", "Esperando para leer las esferas", "En attente de la lecture des cadrans", "A aguardar a leitura dos mostradores", "Aguardando a leitura dos mostradores", "Изчакване за четене на циферблатите", "Kadranların okunması bekleniyor", "Warten auf das Lesen der Zifferblätter")
                    },
                    message = if (state.connected) Tr.tr("点击刷新，等待手表返回表盘列表。", "Tocca Aggiorna e attendi che il watch restituisca l'elenco dei quadranti.", "Tap Refresh and wait for the watch to return the watch face list.", "Toca Actualizar y espera a que el reloj devuelva la lista de esferas.", "Touchez Actualiser et attendez que la montre renvoie la liste des cadrans.", "Toque em Atualizar e aguarde que o relógio devolva a lista de mostradores.", "Toque em Atualizar e aguarde o relógio retornar a lista de mostradores.", "Докоснете „Опресняване“ и изчакайте часовникът да върне списъка с циферблати.", "Yenile'ye dokunun ve saatin kadran listesini döndürmesini bekleyin.", "Tippe auf Aktualisieren und warte, bis die Uhr die Zifferblattliste liefert.") else Tr.tr("请先前往设备页连接手表。", "Vai prima alla pagina Dispositivi e connetti il watch.", "Go to the Devices page first and connect the watch.", "Ve primero a la página Dispositivos y conecta el reloj.", "Allez d'abord sur la page Appareils et connectez la montre.", "Vá primeiro à página Dispositivos e ligue o relógio.", "Vá primeiro à página Dispositivos e conecte o relógio.", "Първо отидете на страницата „Устройства“ и свържете часовника.", "Önce Cihazlar sayfasına gidip saati bağlayın.", "Geh zuerst zur Seite Geräte und verbinde die Uhr."),
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
                        Text(Tr.tr("正在使用", "In uso", "In use", "En uso", "Utilisé", "Em uso", "Em uso", "Използва се", "Kullanımda", "In Verwendung"), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                    ExpandButton(expanded, Tr.tr("表盘信息", "Informazioni quadrante", "Watch face info", "Información de la esfera", "Infos du cadran", "Informações do mostrador", "Informações do mostrador", "Информация за циферблата", "Kadran bilgisi", "Zifferblatt-Infos"), onClick = toggle)
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ValueRow(Tr.tr("版本", "Versione", "Version", "Versión", "Version", "Versão", "Versão", "Версия", "Sürüm", "Version"), face.version.ifBlank { Tr.tr("手表未提供", "Non fornita dal watch", "Not provided by the watch", "No proporcionado por el reloj", "Non fourni par la montre", "Não fornecido pelo relógio", "Não fornecido pelo relógio", "Не е посочено от часовника", "Saat tarafından belirtilmedi", "Von der Uhr nicht angegeben") })
                            ValueRow("ID", face.id)
                            ValueRow(Tr.tr("类型", "Tipo", "Type", "Tipo", "Type", "Tipo", "Tipo", "Тип", "Tür", "Typ"), face.kind)
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
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = Tr.tr("展开箭头", "Freccia di espansione", "Expand arrow", "Flecha de expansión", "Flèche de développement", "Seta de expansão", "Seta de expansão", "Стрелка за разгъване", "Genişletme oku", "Aufklapppfeil"))
    IconButton(onClick = onClick) {
        Icon(
            imageVector = WatchIcons.Expand,
            contentDescription = (if (expanded) Tr.tr("收起", "Comprimi", "Collapse", "Contraer", "Réduire", "Recolher", "Recolher", "Свиване", "Daralt", "Zuklappen") else Tr.tr("展开", "Espandi", "Expand", "Expandir", "Développer", "Expandir", "Expandir", "Разгъване", "Genişlet", "Aufklappen")) + label,
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
                ValueRow(Tr.tr("名称", "Nome", "Name", "Nombre", "Nom", "Nome", "Nome", "Име", "Ad", "Name"), source.title, compact = true)
                // 格式按文件自身结构显示，不按默认直装载荷改写。
                ValueRow(Tr.tr("格式", "Formato", "Format", "Formato", "Format", "Formato", "Formato", "Формат", "Biçim", "Format"), PayloadKinds.displayFormat(source.detectedPayloadKinds), compact = true)
                ValueRow(Tr.tr("大小", "Dimensione", "Size", "Tamaño", "Taille", "Tamanho", "Tamanho", "Размер", "Boyut", "Größe"), formatBytes(source.sourceBytes), compact = true)
                ValueRow(Tr.tr("版本", "Versione", "Version", "Versión", "Version", "Versão", "Versão", "Версия", "Sürüm", "Version"), source.xmlVersion.ifBlank { Tr.tr("未提供", "Non fornito", "Not provided", "No proporcionado", "Non fourni", "Não fornecido", "Não fornecido", "Не е посочено", "Belirtilmedi", "Nicht angegeben") }, compact = true)
                ValueRow(Tr.tr("屏幕", "Schermo", "Screen", "Pantalla", "Écran", "Ecrã", "Tela", "Екран", "Ekran", "Bildschirm"), source.screen, compact = true)
            }
        }
        if (source.payloadDiagnostic.isNotBlank()) ValueRow(Tr.tr("诊断", "Diagnostica", "Diagnostics", "Diagnóstico", "Diagnostic", "Diagnóstico", "Diagnóstico", "Диагностика", "Tanılama", "Diagnose"), source.payloadDiagnostic, compact = true)
        MutedText(PayloadKinds.formatNote(source.detectedPayloadKinds), Modifier.fillMaxWidth())
    }
}
