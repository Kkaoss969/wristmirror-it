package io.github.escosis.hwtfaceswap

import java.util.WeakHashMap

/** 本地说明标记；按软件安装版本确认，不含 MAC、账户或厂商协议接受状态。 */
internal class IntroductionReadMarker(
    private val preferenceOwner: Any,
    private val readAcknowledged: () -> Boolean,
    private val commitAcknowledged: () -> Boolean,
) {
    fun isAcknowledged(): Boolean = synchronized(uncommittedOwners) {
        !uncommittedOwners.containsKey(preferenceOwner) && runCatching(readAcknowledged).getOrDefault(false)
    }

    fun acknowledge(): Boolean = synchronized(uncommittedOwners) {
        // SharedPreferences.commit(false/throw) 可能已更新内存，不能把该值当作可靠保存。
        // 同进程新 VM 仍需显示并重试；重试必须真的 commit，不能仅重读内存后跳过写入。
        val committed = runCatching(commitAcknowledged).getOrDefault(false)
        if (committed) uncommittedOwners.remove(preferenceOwner)
        else uncommittedOwners[preferenceOwner] = Unit
        committed
    }

    companion object {
        const val KEY = "introduction_acknowledged"
        const val INSTALLATION_KEY = "introduction_acknowledged_installation"
        fun isCurrentInstallation(acknowledged: String?, current: String): Boolean =
            acknowledged != null && acknowledged == current
        private val uncommittedOwners = WeakHashMap<Any, Unit>()
    }
}

/** 说明未读不消费机会；确认后仍保持原有“首次尝试即消费，包括设置关闭/无设备”的规则。 */
internal class StartupConnectionOpportunity {
    private var taken = false

    fun take(introductionPending: Boolean, eligible: Boolean): Boolean {
        if (introductionPending || taken) return false
        taken = true
        return eligible
    }
}

/** 只在本 Activity 的真实说明退出后登记；暂停可等待 RESUMED，不向其他 Activity/owner 转移。 */
internal class IntroductionStartupCompletion(private val owner: Any) {
    // 只记本地代次/目标，不携带明文 ID；暂停期间换连接/设备/清理后不能借旧说明启动。
    private data class Receipt(val epoch: Long, val revision: Long, val address: String?, val unpairToken: Long) {
        fun isCurrent(state: AppUiState): Boolean = epoch == state.connectionEpoch && revision == state.connectionFormRevision &&
            address == state.selectedDeviceAddress && unpairToken == state.unpairToken
    }
    private var pending: Receipt? = null
    private var consumed = false

    fun recordRetired(callbackOwner: Any, acknowledged: Boolean, current: AppUiState): Boolean {
        if (callbackOwner !== owner || !acknowledged || current.introductionPending || consumed || pending != null) return false
        pending = Receipt(current.connectionEpoch, current.connectionFormRevision, current.selectedDeviceAddress, current.unpairToken)
        return true
    }

    fun take(currentOwner: Any, resumed: Boolean, current: AppUiState): Boolean {
        val receipt = pending ?: return false
        if (currentOwner !== owner || consumed || !resumed) return false
        if (!receipt.isCurrent(current)) { pending = null; consumed = true; return false }
        if (current.introductionPending || !UiPolicy.introductionModalAvailable(current)) return false
        pending = null
        consumed = true
        return true
    }
}

/** 只保护当前说明 owner 的 UI 回调；真实 VM/Activity 另核对最新运行时和连接代次。 */
internal object IntroductionPolicy {
    fun retainWindow(introductionPending: Boolean, acknowledgingHere: Boolean, acknowledgedHere: Boolean, finished: Boolean): Boolean =
        !finished && (introductionPending || acknowledgingHere || acknowledgedHere)

    fun canInteract(ownerAlive: Boolean, ownerVisible: Boolean, current: AppUiState): Boolean =
        ownerAlive && ownerVisible && UiPolicy.showIntroduction(current)

    fun canRetireDismissal(ownerAlive: Boolean, acknowledgedHere: Boolean, finished: Boolean): Boolean =
        ownerAlive && acknowledgedHere && !finished

    fun canFinishDismissal(ownerAlive: Boolean, acknowledgedHere: Boolean, finished: Boolean, current: AppUiState): Boolean =
        canRetireDismissal(ownerAlive, acknowledgedHere, finished) && !current.introductionPending && UiPolicy.introductionModalAvailable(current)

    fun canCompleteAfterDisposal(ownerAlive: Boolean, acknowledgedHere: Boolean, finished: Boolean,
        libraryFinished: Boolean, nativeDisposed: Boolean, current: AppUiState): Boolean =
        libraryFinished && nativeDisposed && canFinishDismissal(ownerAlive, acknowledgedHere, finished, current)
}

internal data class IntroductionTextPart(val text: String, val url: String? = null)
internal data class IntroductionListItem(val parts: List<IntroductionTextPart>) {
    val text: String get() = parts.joinToString("") { it.text }
}
internal data class IntroductionSection(val title: String, val items: List<IntroductionListItem>)

/** 内置使用说明；仅使用三级标题和列表，不依赖完整 Markdown 渲染。 */
internal object IntroductionContent {
    // 保留供现有说明弹窗中的复制 helper 编译使用；当前说明不生成链接片段。
    const val VMALL_URL = "https://www.vmall.com"
    val markdown = """
        ### Descrizione del progetto
        - Strumento per gestire i quadranti degli smartwatch Huawei e installare quadranti HWT di terze parti, pensato per la maggior parte dei dispositivi delle serie WATCH, GT, FIT ecc. che usano il protocollo quadranti compatibile; sulle versioni HarmonyOS più recenti, dove l'installazione diretta non funziona, i quadranti di terze parti si installano col metodo della sostituzione.
        ### Consigli d'uso
        - Prima dell'uso chiudi Huawei Health e rimuovila anche dalle app in background, per evitare che occupi il canale di connessione e faccia fallire le operazioni.
        - Si consiglia di ottenere l'ID Huawei con la funzione integrata. Un ID errato può far chiedere al watch se cancellare i dati: in quel caso non confermare, annulla e controlla la configurazione.
        - La sostituzione sovrascrive il quadrante originale: scarica prima un quadrante gratuito da Huawei Health (meglio dalla sezione dei quadranti gratuiti ufficiali che dal negozio quadranti), oppure scegli come destinazione un quadrante che non usi più. Se il quadrante originale è ancora disponibile nello store, potrai riscaricarlo.
        - I WATCH in modalità completa usano l'installazione diretta con formato XML o compatibile e non supportano la sostituzione; i WATCH in modalità risparmio energetico e le serie GT, FIT ecc. usano risorse BIN: l'installazione diretta funziona con firmware precedenti alla 6.0.0.32, con firmware più recenti si può provare la sostituzione.
        - Se dopo aver cambiato modalità un WATCH non si connette, disconnettiti e poi usa «Riassocia protocollo».
    """.trimIndent()

    val sections: List<IntroductionSection> = parse(markdown)

    /** 仅解析内置三级标题和列表，条目按普通文本显示，不生成可点击链接。 */
    internal fun parse(source: String): List<IntroductionSection> {
        val sections = mutableListOf<IntroductionSection>()
        var title: String? = null
        val items = mutableListOf<IntroductionListItem>()
        fun finishSection() {
            title?.let { sections += IntroductionSection(it, items.toList()) }
            items.clear()
        }
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("### ") -> {
                    finishSection()
                    title = line.removePrefix("### ")
                }
                line.startsWith("- ") && title != null -> {
                    val text = line.removePrefix("- ")
                    items += IntroductionListItem(listOf(IntroductionTextPart(text)))
                }
            }
        }
        finishSection()
        return sections.toList()
    }
}
