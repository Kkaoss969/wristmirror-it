package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import io.github.escosis.hwtfaceswap.ActionPrompt
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.FaceItem
import io.github.escosis.hwtfaceswap.ReplacementDialogInfo
import io.github.escosis.hwtfaceswap.SourceInfo
import io.github.escosis.hwtfaceswap.ThemeMode
import io.github.escosis.hwtfaceswap.UiPolicy
import io.github.escosis.hwtfaceswap.protocol.Core
import io.github.escosis.hwtfaceswap.protocol.PayloadKinds

/** Explicit app theme wins over the system setting; never infer dark mode from color luminance. */
internal fun faceCardIconAlpha(theme: ThemeMode, systemDark: Boolean): Float =
    if (theme == ThemeMode.DARK || (theme == ThemeMode.SYSTEM && systemDark)) 0.7f else 0.9f

/** 逐卡操作只使用原始 ID / 版本观察；显示名称不参与目标身份。 */
internal enum class FaceCardAction(val label: String) {
    REPLACE(Tr.tr("替换", "Sostituisci", "Replace", "Sustituir", "Remplacer", "Substituir", "Substituir", "Замяна", "Değiştir", "Ersetzen")), ENABLE(Tr.tr("启用", "Attiva", "Activate", "Activar", "Activer", "Ativar", "Ativar", "Активиране", "Etkinleştir", "Aktivieren")), DELETE(Tr.tr("删除", "Elimina", "Delete", "Eliminar", "Supprimer", "Eliminar", "Excluir", "Изтриване", "Sil", "Löschen")),
}

internal enum class FacePageAction { REFRESH, CHOOSE_HWT, CLEAR_IMPORT_CACHE, DIRECT_INSTALL }

internal data class FaceCardDisabledReason(val key: String, val message: String)

/** 只冻结实际影响入口资格的界面上下文，不把名称回显或进度文本当作目标。 */
internal data class FaceCardGuardFrame(
    val epoch: Long,
    val deviceAddress: String,
    val introductionPending: Boolean,
    val connected: Boolean,
    val connecting: Boolean,
    val listReady: Boolean,
    val listRefreshing: Boolean,
    val quarantine: Boolean,
    val pickingHwt: Boolean,
    val operationActive: Boolean,
    val replacementDialog: ReplacementDialogInfo?,
    val activationDialog: ReplacementDialogInfo?,
    val deletionDialog: ReplacementDialogInfo?,
    val prompt: ActionPrompt?,
    val needsPin: Boolean,
    val unpairWorking: Boolean,
    val unpairing: Boolean,
    val unpairAddress: String?,
    val unpairToken: Long,
) {
    companion object {
        fun capture(state: AppUiState) = FaceCardGuardFrame(
            state.connectionEpoch, state.deviceAddress, state.introductionPending,
            state.connected, state.connecting, state.listReady, state.listRefreshing,
            state.quarantine, state.pickingHwt, state.operation.active,
            state.replacementDialog, state.activationDialog, state.deletionDialog,
            state.prompt, state.needsPin, state.unpairWorking, state.unpairing,
            state.unpairAddress, state.unpairToken,
        )
    }
}

internal data class FaceCardActionFrame(
    val action: FaceCardAction,
    val face: FaceItem,
    val epoch: Long,
    val guard: FaceCardGuardFrame,
    val source: SourceInfo?,
    val renderedRowWasUnique: Boolean,
    val disabledReason: FaceCardDisabledReason?,
) {
    val enabled: Boolean get() = disabledReason == null && renderedRowWasUnique
}

internal data class FacePageActionFrame(
    val action: FacePageAction,
    val guard: FaceCardGuardFrame,
    val source: SourceInfo?,
    val disabledReason: FaceCardDisabledReason?,
) {
    val enabled: Boolean get() = disabledReason == null
}

/** 实际窗口/布局由 Compose 桥接提供；任何缺失项均拒绝动作和禁用提示。 */
internal data class FaceCardInteractionGuard(
    val ownerAlive: Boolean = false,
    val frameCurrent: Boolean = false,
    val actionsCurrent: Boolean = false,
    val contextCurrent: Boolean = false,
    val lifecycleOwnerCurrent: Boolean = false,
    val windowOwnerCurrent: Boolean = false,
    val resumed: Boolean = false,
    val windowFocused: Boolean = false,
    val attached: Boolean = false,
    val visibleBounds: Boolean = false,
    val intentCurrent: Boolean = false,
) {
    val allowsInteraction: Boolean get() = ownerAlive && frameCurrent && actionsCurrent &&
        contextCurrent && lifecycleOwnerCurrent && windowOwnerCurrent && resumed &&
        windowFocused && attached && visibleBounds && intentCurrent
}

/** 真实 UI 使用的纯入口策略。既不查询手表，也不推定协议、许可或安装结果。 */
internal object FaceCardPolicy {
    private val introduction = FaceCardDisabledReason("introduction", Tr.tr("请先阅读并确认使用说明", "Leggi e conferma prima le istruzioni d'uso", "Read and confirm the usage notes first", "Lee y confirma primero las instrucciones de uso", "Lisez et confirmez d'abord les instructions d'utilisation", "Leia e confirme primeiro as instruções de utilização", "Leia e confirme primeiro as instruções de uso", "Първо прочетете и потвърдете инструкциите за употреба", "Önce kullanım talimatlarını okuyup onaylayın", "Lies und bestätige zuerst die Nutzungshinweise"))
    private val unpair = FaceCardDisabledReason("unpair", Tr.tr("解除配对处理中，请稍候", "Annullamento abbinamento in corso, attendi", "Unpairing in progress, please wait", "Desemparejamiento en curso, espera", "Dissociation en cours, patientez", "Desemparelhamento em curso, aguarde", "Despareamento em andamento, aguarde", "Разкачване в ход, изчакайте", "Eşleştirme kaldırılıyor, bekleyin", "Entkopplung läuft, bitte warten"))
    private val busy = FaceCardDisabledReason("busy", Tr.tr("操作进行中，请保持连接并等待。", "Operazione in corso, mantieni la connessione e attendi.", "Operation in progress, keep the connection and wait.", "Operación en curso, mantén la conexión y espera.", "Opération en cours, gardez la connexion et patientez.", "Operação em curso, mantenha a ligação e aguarde.", "Operação em andamento, mantenha a conexão e aguarde.", "Операцията е в ход, запазете връзката и изчакайте.", "İşlem sürüyor, bağlantıyı koruyup bekleyin.", "Vorgang läuft, halte die Verbindung und warte."))
    private val disconnected = FaceCardDisabledReason("disconnected", Tr.tr("请先前往设备页连接手表", "Vai prima alla pagina Dispositivi e connetti il watch", "Go to the Devices page first and connect the watch", "Ve primero a la página Dispositivos y conecta el reloj", "Allez d'abord sur la page Appareils et connectez la montre", "Vá primeiro à página Dispositivos e ligue o relógio", "Vá primeiro à página Dispositivos e conecte o relógio", "Първо отидете на страницата „Устройства“ и свържете часовника", "Önce Cihazlar sayfasına gidip saati bağlayın", "Geh zuerst zur Seite Geräte und verbinde die Uhr"))
    private val quarantined = FaceCardDisabledReason("quarantine", Tr.tr("请重新连接后刷新表盘列表", "Riconnettiti e aggiorna l'elenco dei quadranti", "Reconnect and refresh the watch face list", "Vuelve a conectar y actualiza la lista de esferas", "Reconnectez-vous et actualisez la liste des cadrans", "Volte a ligar e atualize a lista de mostradores", "Conecte de novo e atualize a lista de mostradores", "Свържете се отново и опреснете списъка с циферблати", "Yeniden bağlanıp kadran listesini yenileyin", "Verbinde dich neu und aktualisiere die Zifferblattliste"))
    private val refreshing = FaceCardDisabledReason("refreshing", Tr.tr("正在刷新表盘列表，请稍候", "Aggiornamento dell'elenco dei quadranti, attendi", "Refreshing the watch face list, please wait", "Actualizando la lista de esferas, espera", "Actualisation de la liste des cadrans, patientez", "A atualizar a lista de mostradores, aguarde", "Atualizando a lista de mostradores, aguarde", "Опресняване на списъка с циферблати, изчакайте", "Kadran listesi yenileniyor, bekleyin", "Zifferblattliste wird aktualisiert, bitte warten"))
    private val expired = FaceCardDisabledReason("expired", Tr.tr("请刷新表盘列表", "Aggiorna l'elenco dei quadranti", "Refresh the watch face list", "Actualiza la lista de esferas", "Actualisez la liste des cadrans", "Atualize a lista de mostradores", "Atualize a lista de mostradores", "Опреснете списъка с циферблати", "Kadran listesini yenileyin", "Aktualisiere die Zifferblattliste"))
    private val sourceRequired = FaceCardDisabledReason("source", Tr.tr("请先选择 HWT 文件", "Seleziona prima un file HWT", "Select an HWT file first", "Selecciona primero un archivo HWT", "Sélectionnez d'abord un fichier HWT", "Selecione primeiro um ficheiro HWT", "Selecione primeiro um arquivo HWT", "Първо изберете HWT файл", "Önce bir HWT dosyası seçin", "Wähle zuerst eine HWT-Datei aus"))
    private val alreadyCurrent = FaceCardDisabledReason("current", Tr.tr("该表盘正在使用，无需重复启用", "Questo quadrante è già in uso, non serve attivarlo", "This watch face is already in use, no need to activate it", "Esta esfera ya está en uso, no hace falta activarla", "Ce cadran est déjà utilisé, inutile de l'activer", "Este mostrador já está em uso, não é preciso ativá-lo", "Este mostrador já está em uso, não precisa ativá-lo", "Този циферблат вече се използва, не е нужно да го активирате", "Bu kadran zaten kullanımda, etkinleştirmeye gerek yok", "Dieses Zifferblatt wird bereits verwendet, kein Aktivieren nötig"))

    /** 覆盖/删除沿用 selectable，启用只看独立的 activatable；不放宽特殊表盘覆盖门槛。 */
    fun reason(state: AppUiState, face: FaceItem, action: FaceCardAction): FaceCardDisabledReason? {
        commonReason(state)?.let { return it }
        if (!rowCurrent(face, state.connectionEpoch, state)) return expired
        when (action) {
            FaceCardAction.ENABLE -> {
                if (!face.activatable) return FaceCardDisabledReason("activation_unsupported", Tr.tr("该表盘的类型或资源信息不支持启用，请刷新后重试", "Tipo o risorse di questo quadrante non consentono l'attivazione; aggiorna e riprova", "The type or resources of this watch face don't allow activation; refresh and try again", "El tipo o los recursos de esta esfera no permiten activarla; actualiza y vuelve a intentarlo", "Le type ou les ressources de ce cadran ne permettent pas de l'activer ; actualisez et réessayez", "O tipo ou os recursos deste mostrador não permitem ativá-lo; atualize e tente de novo", "O tipo ou os recursos deste mostrador não permitem ativá-lo; atualize e tente de novo", "Типът или ресурсите на този циферблат не позволяват активиране; опреснете и опитайте пак", "Bu kadranın türü veya kaynakları etkinleştirmeye izin vermiyor; yenileyip tekrar deneyin", "Typ oder Ressourcen dieses Zifferblatts erlauben keine Aktivierung; aktualisiere und versuche es erneut"))
                if (face.current) return alreadyCurrent
            }
            FaceCardAction.REPLACE, FaceCardAction.DELETE -> {
                if (!face.selectable) return overwriteReason(face, action)
                if (action == FaceCardAction.REPLACE && state.source == null) return sourceRequired
                if (action == FaceCardAction.REPLACE && state.source?.replacementAvailable == false)
                    return FaceCardDisabledReason(
                        "bin_missing",
                        if (state.source.payloadKinds.any { it == PayloadKinds.CONTAINER || it == PayloadKinds.STRIPPED })
                            Tr.tr("XML 格式仅支持 WATCH 系列全能模式直装，不支持替换", "Il formato XML supporta solo l'installazione diretta su WATCH in modalità completa, non la sostituzione", "The XML format only supports direct install on WATCH series in full mode, not replacement", "El formato XML solo admite la instalación directa en WATCH en modo completo, no la sustitución", "Le format XML ne prend en charge que l'installation directe sur les WATCH en mode complet, pas le remplacement", "O formato XML só suporta a instalação direta em WATCH em modo completo, não a substituição", "O formato XML só suporta a instalação direta em WATCH no modo completo, não a substituição", "Форматът XML поддържа само директно инсталиране на WATCH в пълен режим, не и замяна", "XML biçimi yalnızca tam moddaki WATCH serisinde doğrudan yüklemeyi destekler, değiştirmeyi desteklemez", "Das XML-Format unterstützt nur die Direktinstallation auf WATCH im Vollmodus, kein Ersetzen")
                        else Tr.tr("该 HWT 未找到可传输的表盘内容", "In questo HWT non è stato trovato contenuto trasferibile", "No transferable watch face content found in this HWT", "No se encontró contenido de esfera transferible en este HWT", "Aucun contenu de cadran transférable trouvé dans ce HWT", "Não foi encontrado conteúdo de mostrador transferível neste HWT", "Não foi encontrado conteúdo de mostrador transferível neste HWT", "В този HWT няма съдържание на циферблат за прехвърляне", "Bu HWT'de aktarılabilir kadran içeriği bulunamadı", "In dieser HWT wurde kein übertragbarer Zifferblattinhalt gefunden"),
                    )
            }
        }
        return null
    }

    fun capture(state: AppUiState, face: FaceItem, epoch: Long, action: FaceCardAction): FaceCardActionFrame {
        val unique = rowCurrent(face, epoch, state)
        return FaceCardActionFrame(
            action, face, epoch, FaceCardGuardFrame.capture(state),
            if (action == FaceCardAction.REPLACE) state.source else null,
            unique, if (unique) reason(state, face, action) else expired,
        )
    }

    /** 两个状态都必须含唯一的同一观察；仅换名称不改变 ID / 版本或操作资格。 */
    fun rowCurrent(rendered: FaceItem, epoch: Long, latest: AppUiState): Boolean {
        if (latest.connectionEpoch != epoch) return false
        val actual = latest.faces.singleOrNull { it.id == rendered.id } ?: return false
        return sameObservation(rendered, actual)
    }

    fun sameObservation(rendered: FaceItem, actual: FaceItem): Boolean =
        rendered.id == actual.id && rendered.version == actual.version &&
            rendered.rawType == actual.rawType && rendered.expandType == actual.expandType &&
            rendered.current == actual.current && rendered.selectable == actual.selectable &&
            rendered.activatable == actual.activatable && rendered.kind == actual.kind

    fun current(frame: FaceCardActionFrame, latest: AppUiState): Boolean =
        frame.renderedRowWasUnique && frame.guard == FaceCardGuardFrame.capture(latest) &&
            rowCurrent(frame.face, frame.epoch, latest) &&
            (frame.action != FaceCardAction.REPLACE || frame.source == latest.source) &&
            frame.disabledReason == reason(latest, frame.face, frame.action)

    /** enabled 回调只允许原本可用的帧，不用最新资格把旧 disabled 帧提升为真实动作。 */
    fun invokeEnabled(
        frame: FaceCardActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
    ): Boolean {
        if (!frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !current(frame, latest)) return false
        when (frame.action) {
            FaceCardAction.REPLACE -> renderedActions.requestReplacement(frame.face, frame.epoch)
            FaceCardAction.ENABLE -> renderedActions.requestActivation(frame.face, frame.epoch)
            FaceCardAction.DELETE -> renderedActions.requestDeletion(frame.face, frame.epoch)
        }
        return true
    }

    /** 禁用 tap 只能解释当前仍匹配的原渲染原因，绝不调用动作、自动刷新或重定向到别的卡。 */
    fun invokeDisabledHint(
        frame: FaceCardActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
        hint: (String) -> Unit,
    ): Boolean {
        val reason = frame.disabledReason ?: return false
        if (frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !current(frame, latest)) return false
        hint(reason.message)
        return true
    }

    fun capturePageAction(state: AppUiState, action: FacePageAction) = FacePageActionFrame(
        action, FaceCardGuardFrame.capture(state), state.source, pageReason(state, action),
    )

    fun pageCurrent(frame: FacePageActionFrame, latest: AppUiState): Boolean =
        frame.guard == FaceCardGuardFrame.capture(latest) && frame.source == latest.source &&
            frame.disabledReason == pageReason(latest, frame.action)

    fun invokePageEnabled(
        frame: FacePageActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
        pickHwt: () -> Unit,
    ): Boolean {
        if (!frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !pageCurrent(frame, latest)) return false
        when (frame.action) {
            FacePageAction.REFRESH -> renderedActions.refreshFaces()
            FacePageAction.CHOOSE_HWT -> pickHwt()
            FacePageAction.CLEAR_IMPORT_CACHE -> renderedActions.clearCache()
            FacePageAction.DIRECT_INSTALL -> renderedActions.requestDirectInstall()
        }
        return true
    }

    fun invokePageDisabledHint(
        frame: FacePageActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
        hint: (String) -> Unit,
    ): Boolean {
        val reason = frame.disabledReason ?: return false
        if (frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !pageCurrent(frame, latest)) return false
        hint(reason.message)
        return true
    }

    private fun modalReason(state: AppUiState): FaceCardDisabledReason? = when {
        state.introductionPending -> introduction
        state.unpairWorking || UiPolicy.isUnpairTarget(state, state.deviceAddress) -> unpair
        state.operation.active || state.operationDialog != null || state.pickingHwt ||
            state.connecting || state.prompt != null || state.needsPin -> busy
        else -> null
    }

    private fun commonReason(state: AppUiState): FaceCardDisabledReason? = modalReason(state) ?: when {
        !state.connected -> disconnected
        state.quarantine -> quarantined
        state.listRefreshing -> refreshing
        !state.listReady -> expired
        else -> null
    }

    private fun pageReason(state: AppUiState, action: FacePageAction): FaceCardDisabledReason? =
        modalReason(state) ?: if (action == FacePageAction.DIRECT_INSTALL) {
            commonReason(state) ?: when {
                state.source == null -> sourceRequired
                state.source.payloadKind !in state.source.payloadKinds || PayloadKinds.mode(state.source.payloadKind) == null || state.source.payloadBytes <= 0 ->
                    FaceCardDisabledReason("bin_missing", Tr.tr("该 HWT 未找到可传输的表盘内容", "In questo HWT non è stato trovato contenuto trasferibile", "No transferable watch face content found in this HWT", "No se encontró contenido de esfera transferible en este HWT", "Aucun contenu de cadran transférable trouvé dans ce HWT", "Não foi encontrado conteúdo de mostrador transferível neste HWT", "Não foi encontrado conteúdo de mostrador transferível neste HWT", "В този HWT няма съдържание на циферблат за прехвърляне", "Bu HWT'de aktarılabilir kadran içeriği bulunamadı", "In dieser HWT wurde kein übertragbarer Zifferblattinhalt gefunden"))
                state.source.payloadBytes > Core.MAX_MEMBER -> FaceCardDisabledReason("direct_size", Tr.tr("表盘内容超过直接安装的大小上限", "Il contenuto del quadrante supera la dimensione massima per l'installazione diretta", "The watch face content exceeds the size limit for direct install", "El contenido de la esfera supera el tamaño máximo para la instalación directa", "Le contenu du cadran dépasse la taille maximale pour l'installation directe", "O conteúdo do mostrador excede o tamanho máximo para a instalação direta", "O conteúdo do mostrador excede o tamanho máximo para a instalação direta", "Съдържанието на циферблата надвишава максималния размер за директно инсталиране", "Kadran içeriği doğrudan yükleme için en büyük boyutu aşıyor", "Der Zifferblattinhalt überschreitet die Maximalgröße für die Direktinstallation"))
                else -> null
            }
        } else if (action == FacePageAction.REFRESH) when {
            !state.connected -> disconnected
            state.quarantine -> quarantined
            state.listRefreshing -> refreshing
            else -> null
        } else null

    private fun overwriteReason(face: FaceItem, action: FaceCardAction): FaceCardDisabledReason {
        val label = action.label
        val special = face.kind in setOf(Tr.tr("趣味贴纸", "Adesivi divertenti", "Fun stickers", "Pegatinas divertidas", "Autocollants amusants", "Autocolantes divertidos", "Adesivos divertidos", "Забавни стикери", "Eğlenceli çıkartmalar", "Lustige Sticker"), Tr.tr("相册表盘", "Quadrante galleria", "Photo watch face", "Esfera de fotos", "Cadran photo", "Mostrador de fotos", "Mostrador de fotos", "Циферблат със снимки", "Fotoğraf kadranı", "Foto-Zifferblatt"), Tr.tr("视频表盘", "Quadrante video", "Video watch face", "Esfera de vídeo", "Cadran vidéo", "Mostrador de vídeo", "Mostrador de vídeo", "Видео циферблат", "Video kadranı", "Video-Zifferblatt"), Tr.tr("万花筒表盘", "Quadrante caleidoscopio", "Kaleidoscope watch face", "Esfera caleidoscopio", "Cadran kaléidoscope", "Mostrador caleidoscópio", "Mostrador caleidoscópio", "Калейдоскопен циферблат", "Kaleydoskop kadranı", "Kaleidoskop-Zifferblatt"), Tr.tr("穿戴表盘", "Quadrante indossabile", "Wearable watch face", "Esfera ponible", "Cadran portable", "Mostrador vestível", "Mostrador vestível", "Носим циферблат", "Giyilebilir kadran", "Tragbares Zifferblatt"))
        return if (special) FaceCardDisabledReason(
            "special_${action.name}",
            if (face.activatable) Tr.tr("${face.kind}仅支持启用，不支持$label", "${face.kind}: supporta solo l'attivazione, non «$label»", "${face.kind}: only activation is supported, not «$label»", "${face.kind}: solo admite la activación, no «$label»", "${face.kind} : seule l'activation est prise en charge, pas «$label»", "${face.kind}: só suporta a ativação, não «$label»", "${face.kind}: só suporta a ativação, não «$label»", "${face.kind}: поддържа се само активиране, не и „$label“", "${face.kind}: yalnızca etkinleştirme desteklenir, «$label» desteklenmez", "${face.kind}: nur Aktivierung unterstützt, nicht „$label“")
            else Tr.tr("${face.kind}不支持$label；启用还需有效的已安装资源信息", "${face.kind}: «$label» non supportato; per l'attivazione servono anche informazioni valide sulle risorse installate", "${face.kind}: «$label» not supported; activation also needs valid installed resource info", "${face.kind}: «$label» no admitido; para activarla también hace falta información válida de los recursos instalados", "${face.kind} : «$label» non pris en charge ; l'activation nécessite aussi des infos valides sur les ressources installées", "${face.kind}: «$label» não suportado; para ativar também são precisas informações válidas dos recursos instalados", "${face.kind}: «$label» não suportado; para ativar também são necessárias informações válidas dos recursos instalados", "${face.kind}: „$label“ не се поддържа; за активиране са нужни и валидни данни за инсталираните ресурси", "${face.kind}: «$label» desteklenmiyor; etkinleştirme için geçerli yüklü kaynak bilgisi de gerekir", "${face.kind}: „$label“ nicht unterstützt; für die Aktivierung sind auch gültige Infos zu installierten Ressourcen nötig"),
        ) else FaceCardDisabledReason("overwrite_${action.name}", Tr.tr("该类型表盘不支持$label，仅可对符合条件的普通表盘执行此操作", "Questo tipo di quadrante non supporta «$label»; l'operazione è possibile solo sui quadranti standard idonei", "This watch face type doesn't support «$label»; the operation is only possible on eligible standard watch faces", "Este tipo de esfera no admite «$label»; la operación solo es posible en esferas estándar aptas", "Ce type de cadran ne prend pas en charge «$label» ; l'opération n'est possible que sur les cadrans standard éligibles", "Este tipo de mostrador não suporta «$label»; a operação só é possível em mostradores padrão elegíveis", "Este tipo de mostrador não suporta «$label»; a operação só é possível em mostradores padrão elegíveis", "Този тип циферблат не поддържа „$label“; операцията е възможна само за подходящи стандартни циферблати", "Bu kadran türü «$label» desteklemiyor; işlem yalnızca uygun standart kadranlarda yapılabilir", "Dieser Zifferblatttyp unterstützt „$label“ nicht; der Vorgang ist nur bei geeigneten Standard-Zifferblättern möglich"))
    }
}
