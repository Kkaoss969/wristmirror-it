package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.NearbyWatch
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal enum class HistoryConnectResult { STARTED, CONFIGURE_ID, UNAVAILABLE }

// 退出期间只保留最后一帧的非敏感展示资格；动作始终另走当前窗口/设备守卫。
private data class HistoryOperationPresentation(
    val name: String,
    val address: String,
    val connected: Boolean,
    val targetConnected: Boolean,
    val unpairing: Boolean,
    val canConnect: Boolean,
    val canEditHuaweiId: Boolean,
    val canUnpair: Boolean,
)

/** 历史宿主中的独立 keyed 面板；非共享调用仍使用默认真实 Miuix WindowDialog。 */
@Composable
internal fun HistoryOperationDialog(
    state: AppUiState,
    device: NearbyWatch,
    show: Boolean,
    incarnation: Long,
    canConnect: Boolean,
    canEditHuaweiId: Boolean,
    canUnpair: Boolean,
    isCurrent: () -> Boolean,
    onConfigureHuaweiId: () -> Boolean,
    onConnect: () -> HistoryConnectResult,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    inHistoryWindow: Boolean = false,
) {
    val currentPresentation = HistoryOperationPresentation(
        name = device.name.ifBlank { Tr.tr("未命名手表", "Watch senza nome", "Unnamed watch", "Reloj sin nombre", "Montre sans nom", "Relógio sem nome", "Relógio sem nome", "Часовник без име", "Adsız saat", "Unbenannte Uhr") }.uiText(),
        address = device.address,
        connected = state.connected,
        targetConnected = device.address.equals(state.deviceAddress, true),
        unpairing = state.unpairing,
        canConnect = canConnect,
        canEditHuaweiId = canEditHuaweiId,
        canUnpair = canUnpair,
    )
    var lastPresentation by remember(incarnation) { mutableStateOf(currentPresentation) }
    var feedback by remember(incarnation) { mutableStateOf("") }
    SideEffect { if (show) lastPresentation = currentPresentation }
    val presentation = if (show) currentPresentation else lastPresentation

    val panelContent: @Composable () -> Unit = {
        val dismiss = LocalDismissState.current
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HuaweiIdDeviceCard(presentation.name, presentation.address)
            ActionButton(
                text = Tr.tr("连接手表", "Connessione al watch", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr"),
                onClick = {
                    if (isCurrent() && canConnect) {
                        when (onConnect()) {
                            HistoryConnectResult.STARTED -> Unit
                            HistoryConnectResult.CONFIGURE_ID -> onConfigureHuaweiId()
                            HistoryConnectResult.UNAVAILABLE -> if (isCurrent()) {
                                feedback = Tr.tr("当前无法连接这台设备，请核对配置或稍后重试。", "Al momento non è possibile connettere questo dispositivo; controlla la configurazione o riprova più tardi.", "This device can't be connected right now; check the configuration or try again later.", "Ahora no se puede conectar este dispositivo; revisa la configuración o inténtalo más tarde.", "Impossible de connecter cet appareil pour le moment ; vérifiez la configuration ou réessayez plus tard.", "De momento não é possível ligar este dispositivo; verifique a configuração ou tente mais tarde.", "No momento não é possível conectar este dispositivo; verifique a configuração ou tente mais tarde.", "В момента това устройство не може да се свърже; проверете настройките или опитайте по-късно.", "Bu cihaz şu anda bağlanamıyor; yapılandırmayı kontrol edin veya daha sonra deneyin.", "Dieses Gerät kann gerade nicht verbunden werden; prüfe die Konfiguration oder versuche es später.")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = presentation.canConnect,
            )
            if (presentation.connected) {
                MutedText(
                    if (presentation.targetConnected) Tr.tr("这台设备当前已连接。", "Questo dispositivo è già connesso.", "This device is already connected.", "Este dispositivo ya está conectado.", "Cet appareil est déjà connecté.", "Este dispositivo já está ligado.", "Este dispositivo já está conectado.", "Това устройство вече е свързано.", "Bu cihaz zaten bağlı.", "Dieses Gerät ist bereits verbunden.")
                    else Tr.tr("请先断开当前连接，再连接这台设备。", "Disconnetti prima la connessione attuale, poi connetti questo dispositivo.", "Disconnect the current connection first, then connect this device.", "Desconecta primero la conexión actual y luego conecta este dispositivo.", "Déconnectez d'abord la connexion actuelle, puis connectez cet appareil.", "Desligue primeiro a ligação atual e depois ligue este dispositivo.", "Desconecte primeiro a conexão atual e depois conecte este dispositivo.", "Първо прекъснете текущата връзка, после свържете това устройство.", "Önce geçerli bağlantıyı kesin, sonra bu cihazı bağlayın.", "Trenne zuerst die aktuelle Verbindung und verbinde dann dieses Gerät."),
                )
            }
            ActionButton(
                text = Tr.tr("配置华为 ID", "Configura ID Huawei", "Configure Huawei ID", "Configurar ID de Huawei", "Configurer l'ID Huawei", "Configurar ID Huawei", "Configurar ID Huawei", "Настройка на Huawei ID", "Huawei ID'yi yapılandır", "Huawei-ID einrichten"),
                onClick = { if (isCurrent() && canEditHuaweiId) onConfigureHuaweiId() },
                modifier = Modifier.fillMaxWidth(),
                enabled = presentation.canEditHuaweiId,
            )
            ActionButton(
                text = Tr.tr("解除配对", "Annulla abbinamento", "Unpair", "Desemparejar", "Dissocier", "Desemparelhar", "Desfazer pareamento", "Разкачване", "Eşleştirmeyi kaldır", "Entkoppeln"),
                onClick = { if (isCurrent() && canUnpair) onUnpair() },
                modifier = Modifier.fillMaxWidth(),
                destructive = true,
                enabled = presentation.canUnpair,
            )
            if (presentation.unpairing) MutedText(Tr.tr("已有设备等待解除配对核实，请先完成下方状态中的检查。", "Un dispositivo attende la verifica dell'annullamento abbinamento; completa prima il controllo nello stato qui sotto.", "A device is awaiting unpair verification; finish the check in the status below first.", "Un dispositivo espera la verificación del desemparejamiento; termina primero la comprobación en el estado de abajo.", "Un appareil attend la vérification de la dissociation ; terminez d'abord la vérification dans l'état ci-dessous.", "Um dispositivo aguarda a verificação do desemparelhamento; termine primeiro a verificação no estado abaixo.", "Um dispositivo aguarda a verificação do despareamento; conclua primeiro a verificação no status abaixo.", "Устройство чака проверка на разкачването; първо завършете проверката в състоянието по-долу.", "Bir cihaz eşleştirme kaldırma doğrulaması bekliyor; önce aşağıdaki durumdaki kontrolü tamamlayın.", "Ein Gerät wartet auf die Prüfung der Entkopplung; schließe zuerst die Prüfung im Status unten ab."))
            if (feedback.isNotBlank()) MutedText(feedback)
            // LocalDismissState 是 0.9.3 提供的可空、无参 dismiss 请求；不是动画完成通知。
            ActionButton(Tr.tr("关闭", "Chiudi", "Close", "Cerrar", "Fermer", "Fechar", "Fechar", "Затваряне", "Kapat", "Schließen"), onClick = { if (isCurrent()) dismiss?.invoke() }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (inHistoryWindow) panelContent()
    else WindowDialog(show = show, title = Tr.tr("设备操作", "Azioni dispositivo", "Device actions", "Acciones del dispositivo", "Actions de l'appareil", "Ações do dispositivo", "Ações do dispositivo", "Действия с устройството", "Cihaz işlemleri", "Geräteaktionen"), onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished, content = panelContent)
}

/** 非敏感的最后展示快照。明文不进入此模型、更不进入待切换窗口描述符。 */
private data class HuaweiIdPresentation(
    val name: String,
    val address: String,
    val dirty: Boolean,
    val configured: Boolean,
    val connected: Boolean,
    val canEdit: Boolean,
    val canConnect: Boolean,
    val canSave: Boolean,
    val feedback: String,
)

/** 选中设备的全局编辑值与历史 MAC 的本地草稿共用全部字段、按钮和布局。 */
@Composable
internal fun HuaweiIdDialog(
    show: Boolean,
    incarnation: Long,
    name: String,
    address: String,
    value: String,
    dirty: Boolean,
    configured: Boolean,
    connected: Boolean,
    canEdit: Boolean,
    canConnect: Boolean,
    isCurrent: () -> Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    feedback: String = "",
    inHistoryWindow: Boolean = false,
) {
    val currentPresentation = HuaweiIdPresentation(
        name, address, dirty, configured, connected, canEdit, canConnect,
        canSave = canEdit && value.isNotBlank(), feedback = feedback,
    )
    var lastPresentation by remember(incarnation) { mutableStateOf(currentPresentation) }
    // 仅窗口存活期间的普通 remember：退出仍显示掩码，实际退出/移除时清空，不使用 rememberSaveable。
    var lastValue by remember(incarnation) { mutableStateOf(value) }
    var revealId by remember(incarnation) { mutableStateOf(false) }
    val acquisition = rememberHuaweiIdAcquisition(
        ownerKey = "$incarnation:$address",
        enabled = show && canEdit,
        isCurrent = isCurrent,
        onAcquired = { id ->
            if (show && isCurrent() && canEdit) {
                revealId = true
                onValueChange(id)
            }
        },
    )
    SideEffect {
        if (show) {
            lastPresentation = currentPresentation
            lastValue = value
        }
    }
    DisposableEffect(incarnation) {
        onDispose { lastValue = ""; revealId = false }
    }
    val presentation = if (show) currentPresentation else lastPresentation
    val presentedValue = if (show) value else lastValue
    LaunchedEffect(incarnation, feedback, show) {
        if (show && isCurrent() && presentation.feedback.isNotBlank()) {
            acquisition.notify(presentation.feedback)
        }
    }
    // show=false 的第一帧同步遮蔽，不等 LaunchedEffect；不靠清空字段来改变退出中的按钮资格。
    val visiblyRevealed = show && revealId

    val panelContent: @Composable () -> Unit = {
        val dismiss = LocalDismissState.current
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HuaweiIdDeviceCard(presentation.name, presentation.address)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Tr.tr("这台设备的华为 ID", "ID Huawei di questo dispositivo", "Huawei ID for this device", "ID de Huawei de este dispositivo", "ID Huawei de cet appareil", "ID Huawei deste dispositivo", "ID Huawei deste dispositivo", "Huawei ID на това устройство", "Bu cihazın Huawei ID'si", "Huawei-ID dieses Geräts"),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Badge(
                    text = when {
                        presentation.dirty -> Tr.tr("待保存", "Da salvare", "Unsaved", "Sin guardar", "Non enregistré", "Por guardar", "Não salvo", "Незапазено", "Kaydedilmedi", "Nicht gespeichert")
                        presentation.configured -> Tr.tr("已保存", "Salvato", "Saved", "Guardado", "Enregistré", "Guardado", "Salvo", "Запазено", "Kaydedildi", "Gespeichert")
                        else -> Tr.tr("待配置", "Da configurare", "To configure", "Por configurar", "À configurer", "Por configurar", "A configurar", "За настройка", "Yapılandırılacak", "Einzurichten")
                    },
                    highlighted = presentation.configured && !presentation.dirty,
                )
            }
            MiuixField(
                value = presentedValue,
                onValueChange = { if (isCurrent() && canEdit && !acquisition.busy) onValueChange(it) },
                label = Tr.tr("输入华为 ID", "Inserisci ID Huawei", "Enter Huawei ID", "Introduce el ID de Huawei", "Saisissez l'ID Huawei", "Introduza o ID Huawei", "Insira o ID Huawei", "Въведете Huawei ID", "Huawei ID'yi girin", "Huawei-ID eingeben"),
                enabled = presentation.canEdit && !acquisition.busy,
                retainOutlineWhenDisabled = true,
                keyboardType = KeyboardType.Text,
                visualTransformation = if (visiblyRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { if (isCurrent() && !acquisition.busy) revealId = !revealId },
                        modifier = Modifier.padding(horizontal = 6.dp),
                        enabled = !acquisition.busy,
                    ) {
                        Text(
                            if (visiblyRevealed) Tr.tr("隐藏", "Nascondi", "Hide", "Ocultar", "Masquer", "Ocultar", "Ocultar", "Скриване", "Gizle", "Verbergen") else Tr.tr("显示", "Mostra", "Show", "Mostrar", "Afficher", "Mostrar", "Mostrar", "Показване", "Göster", "Anzeigen"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
            )
            MutedText(
                if (presentation.connected) Tr.tr("修改后保存，下次连接时生效。", "Salva le modifiche; avranno effetto alla prossima connessione.", "Save your changes; they take effect on the next connection.", "Guarda los cambios; se aplicarán en la próxima conexión.", "Enregistrez les modifications ; elles s'appliqueront à la prochaine connexion.", "Guarde as alterações; aplicam-se na próxima ligação.", "Salve as alterações; elas valem a partir da próxima conexão.", "Запазете промените; те ще важат от следващото свързване.", "Değişiklikleri kaydedin; sonraki bağlantıda geçerli olur.", "Speichere die Änderungen; sie gelten ab der nächsten Verbindung.")
                else Tr.tr("为这台设备填写与运动健康一致的华为 ID。", "Inserisci per questo dispositivo lo stesso ID Huawei usato in Huawei Health.", "Enter the same Huawei ID used in Huawei Health for this device.", "Introduce para este dispositivo el mismo ID de Huawei que usas en Huawei Health.", "Saisissez pour cet appareil le même ID Huawei que dans Huawei Health.", "Introduza para este dispositivo o mesmo ID Huawei usado na Huawei Health.", "Insira para este dispositivo o mesmo ID Huawei usado no Huawei Health.", "Въведете за това устройство същия Huawei ID, който използвате в Huawei Health.", "Bu cihaz için Huawei Health'te kullandığınız Huawei ID'yi girin.", "Gib für dieses Gerät dieselbe Huawei-ID ein, die du in Huawei Health verwendest."),
            )
            ActionButton(
                text = Tr.tr("一键获取华为 ID", "Ottieni ID Huawei con un tocco", "Get Huawei ID with one tap", "Obtener ID de Huawei con un toque", "Obtenir l'ID Huawei en un geste", "Obter ID Huawei com um toque", "Obter ID Huawei com um toque", "Вземане на Huawei ID с едно докосване", "Huawei ID'yi tek dokunuşla al", "Huawei-ID mit einem Tipp holen"),
                onClick = acquisition.start,
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = show && presentation.canEdit && !acquisition.busy,
            )
            if (!presentation.connected) {
                ActionButton(
                    text = Tr.tr("连接手表", "Connessione al watch", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr"),
                    onClick = { if (isCurrent() && canConnect && !acquisition.busy) onConnect() },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = presentation.canConnect && !acquisition.busy,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(Tr.tr("关闭", "Chiudi", "Close", "Cerrar", "Fermer", "Fechar", "Fechar", "Затваряне", "Kapat", "Schließen"), onClick = { if (isCurrent()) dismiss?.invoke() }, modifier = Modifier.weight(1f))
                ActionButton(
                    text = Tr.tr("保存 ID", "Salva ID", "Save ID", "Guardar ID", "Enregistrer l'ID", "Guardar ID", "Salvar ID", "Запазване на ID", "ID'yi kaydet", "ID speichern"),
                    onClick = { if (isCurrent() && canEdit && !acquisition.busy) onSave() },
                    modifier = Modifier.weight(1f),
                    primary = presentation.connected,
                    enabled = presentation.canSave && !acquisition.busy,
                )
            }
        }
    }
    if (inHistoryWindow) panelContent()
    else WindowDialog(
        show = show,
        title = Tr.tr("配置华为 ID", "Configura ID Huawei", "Configure Huawei ID", "Configurar ID de Huawei", "Configurer l'ID Huawei", "Configurar ID Huawei", "Configurar ID Huawei", "Настройка на Huawei ID", "Huawei ID'yi yapılandır", "Huawei-ID einrichten"),
        onDismissRequest = onDismiss,
        onDismissFinished = {
            lastValue = ""
            revealId = false
            onDismissFinished()
        },
        content = panelContent,
    )
}

/** 只在独立配置窗口真正打开后读取目标 MAC；没有改选、断开或借用另一台设备的编辑器。 */
@Composable
internal fun HistoryHuaweiIdDialog(
    state: AppUiState,
    device: NearbyWatch,
    show: Boolean,
    incarnation: Long,
    canConnect: Boolean,
    canEditHuaweiId: Boolean,
    isCurrent: () -> Boolean,
    onReadHuaweiId: () -> String?,
    onSaveHuaweiId: (String) -> Boolean,
    onSaveAndConnect: (String) -> Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    inHistoryWindow: Boolean = false,
    isOwnerCurrent: () -> Boolean = isCurrent,
) {
    var draft by remember(incarnation) { mutableStateOf("") }
    var savedId by remember(incarnation) { mutableStateOf("") }
    var loaded by remember(incarnation) { mutableStateOf(false) }
    var feedback by remember(incarnation) { mutableStateOf("") }
    // 可提前测量空的独立面板，但 show=false 不读 ID；只在旧面板销毁且父守卫消费后启动读取。
    LaunchedEffect(incarnation, show) {
        if (!show || !isOwnerCurrent()) return@LaunchedEffect
        val id = onReadHuaweiId()
        if (!show || !isOwnerCurrent()) return@LaunchedEffect
        if (id == null) feedback = Tr.tr("无法读取这台设备的华为 ID，请稍后重试。", "Impossibile leggere l'ID Huawei di questo dispositivo, riprova più tardi.", "Couldn't read this device's Huawei ID, try again later.", "No se pudo leer el ID de Huawei de este dispositivo, inténtalo más tarde.", "Impossible de lire l'ID Huawei de cet appareil, réessayez plus tard.", "Não foi possível ler o ID Huawei deste dispositivo, tente mais tarde.", "Não foi possível ler o ID Huawei deste dispositivo, tente mais tarde.", "Huawei ID на това устройство не може да се прочете, опитайте по-късно.", "Bu cihazın Huawei ID'si okunamadı, daha sonra deneyin.", "Die Huawei-ID dieses Geräts konnte nicht gelesen werden, versuche es später.")
        else {
            draft = id
            savedId = id
            loaded = true
        }
    }
    DisposableEffect(incarnation) {
        onDispose { draft = ""; savedId = ""; loaded = false; feedback = "" }
    }
    HuaweiIdDialog(
        show = show,
        incarnation = incarnation,
        name = device.name.ifBlank { Tr.tr("未命名手表", "Watch senza nome", "Unnamed watch", "Reloj sin nombre", "Montre sans nom", "Relógio sem nome", "Relógio sem nome", "Часовник без име", "Adsız saat", "Unbenannte Uhr") }.uiText(),
        address = device.address,
        value = draft,
        dirty = draft.trim() != savedId,
        configured = savedId.isNotBlank(),
        connected = state.connected,
        canEdit = canEditHuaweiId && loaded,
        canConnect = canConnect && loaded && draft.isNotBlank(),
        isCurrent = isCurrent,
        onValueChange = { if (isCurrent() && canEditHuaweiId) { draft = it; feedback = "" } },
        onSave = {
            if (isCurrent() && canEditHuaweiId && loaded) {
                val value = draft.trim()
                if (onSaveHuaweiId(value) && isCurrent()) {
                    draft = value
                    savedId = value
                    feedback = ""
                } else if (isCurrent()) feedback = Tr.tr("华为 ID 未保存，请核对输入或稍后重试。", "ID Huawei non salvato; controlla i dati inseriti o riprova più tardi.", "Huawei ID not saved; check what you entered or try again later.", "ID de Huawei no guardado; revisa lo que escribiste o inténtalo más tarde.", "ID Huawei non enregistré ; vérifiez la saisie ou réessayez plus tard.", "ID Huawei não guardado; verifique o que escreveu ou tente mais tarde.", "ID Huawei não salvo; verifique o que você digitou ou tente mais tarde.", "Huawei ID не е запазен; проверете въведеното или опитайте по-късно.", "Huawei ID kaydedilmedi; girdiğinizi kontrol edin veya daha sonra deneyin.", "Huawei-ID nicht gespeichert; prüfe die Eingabe oder versuche es später.")
            }
        },
        onConnect = {
            if (isCurrent() && canConnect && loaded && !onSaveAndConnect(draft.trim()) && isCurrent()) {
                feedback = Tr.tr("当前无法连接这台设备，请核对配置或稍后重试。", "Al momento non è possibile connettere questo dispositivo; controlla la configurazione o riprova più tardi.", "This device can't be connected right now; check the configuration or try again later.", "Ahora no se puede conectar este dispositivo; revisa la configuración o inténtalo más tarde.", "Impossible de connecter cet appareil pour le moment ; vérifiez la configuration ou réessayez plus tard.", "De momento não é possível ligar este dispositivo; verifique a configuração ou tente mais tarde.", "No momento não é possível conectar este dispositivo; verifique a configuração ou tente mais tarde.", "В момента това устройство не може да се свърже; проверете настройките или опитайте по-късно.", "Bu cihaz şu anda bağlanamıyor; yapılandırmayı kontrol edin veya daha sonra deneyin.", "Dieses Gerät kann gerade nicht verbunden werden; prüfe die Konfiguration oder versuche es später.")
            }
        },
        onDismiss = onDismiss,
        onDismissFinished = {
            draft = ""
            savedId = ""
            loaded = false
            feedback = ""
            onDismissFinished()
        },
        feedback = feedback,
        inHistoryWindow = inHistoryWindow,
    )
}

@Composable
private fun HuaweiIdDeviceCard(name: String, address: String) {
    SectionCard {
        Text(
            text = name,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = address,
            modifier = Modifier.fillMaxWidth(),
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            softWrap = true,
        )
    }
}
