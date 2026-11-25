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
    val markdown = Tr.tr("""
        ### 项目说明
        - 一个华为手表表盘管理和第三方 HWT 表盘安装工具，面向绝大多数采用兼容表盘协议的 WATCH、GT、FIT 等系列设备，第三方表盘安装通过替换法支持直装法失灵的新鸿蒙版本。
        ### 使用建议
        - 使用前请先退出运动健康并清理其后台，避免同时占用连接通道导致操作失败。
        - 建议使用内置功能获取华为 ID。填写错误可能导致手表询问是否清空数据，遇到此提示请勿确认，应取消并核对配置。
        - 替换会覆盖原表盘，请先从运动健康下载免费表盘（建议从“官方免费表盘”中下载而非“表盘市场”中），或选择不再使用的表盘作为目标。原表盘仍在商店提供时，可重新下载恢复。
        - WATCH 系列全能模式使用 XML 或兼容格式直装，不支持替换；WATCH 省电模式以及 GT、FIT 等系列使用 BIN 资源，直装适用于固件 6.0.0.32 之前，较新固件可尝试替换。
        - WATCH 系列切换模式后，若无法连接，可先断开连接，再使用“重新协议绑定”。
    """, """
        ### Descrizione del progetto
        - Strumento per gestire i quadranti degli smartwatch Huawei e installare quadranti HWT di terze parti, pensato per la maggior parte dei dispositivi delle serie WATCH, GT, FIT ecc. che usano il protocollo quadranti compatibile; sulle versioni HarmonyOS più recenti, dove l'installazione diretta non funziona, i quadranti di terze parti si installano col metodo della sostituzione. Autore: Escosis (github.com/Escosis/WristMirror, licenza Apache-2.0). Questa è una traduzione non ufficiale, distribuita da Kkaoss969 con Gateway Assistant.
        ### Consigli d'uso
        - Prima dell'uso chiudi Huawei Health e rimuovila anche dalle app in background, per evitare che occupi il canale di connessione e faccia fallire le operazioni.
        - Si consiglia di ottenere l'ID Huawei con la funzione integrata. Un ID errato può far chiedere al watch se cancellare i dati: in quel caso non confermare, annulla e controlla la configurazione.
        - La sostituzione sovrascrive il quadrante originale: scarica prima un quadrante gratuito da Huawei Health (meglio dalla sezione dei quadranti gratuiti ufficiali che dal negozio quadranti), oppure scegli come destinazione un quadrante che non usi più. Se il quadrante originale è ancora disponibile nello store, potrai riscaricarlo.
        - I WATCH in modalità completa usano l'installazione diretta con formato XML o compatibile e non supportano la sostituzione; i WATCH in modalità risparmio energetico e le serie GT, FIT ecc. usano risorse BIN: l'installazione diretta funziona con firmware precedenti alla 6.0.0.32, con firmware più recenti si può provare la sostituzione.
        - Se dopo aver cambiato modalità un WATCH non si connette, disconnettiti e poi usa «Riassocia protocollo».
    """, """
        ### About the project
        - A tool to manage Huawei watch faces and install third-party HWT watch faces, for most WATCH, GT, FIT etc. series devices using the compatible watch face protocol; on newer HarmonyOS versions where direct install fails, third-party watch faces are installed by replacement. Author: Escosis (github.com/Escosis/WristMirror, Apache-2.0 license). This is an unofficial translation, distributed by Kkaoss969 with Gateway Assistant.
        ### Usage tips
        - Before use, close Huawei Health and remove it from recent apps, so it doesn't hold the connection channel and make operations fail.
        - Get the Huawei ID with the built-in feature. A wrong ID may make the watch ask whether to erase its data: if that happens don't confirm, cancel and check the configuration.
        - Replacement overwrites the original watch face: first download a free watch face from Huawei Health (better from the official free watch faces than from the watch face store), or pick one you no longer use as the target. If the original is still offered in the store, you can download it again.
        - WATCH series in full mode use direct install with the XML or compatible format and don't support replacement; WATCH in power-saving mode and GT, FIT etc. series use BIN resources: direct install works with firmware older than 6.0.0.32, on newer firmware try replacement.
        - If a WATCH won't connect after switching mode, disconnect first, then use «Re-bind protocol».
    """, """
        ### Sobre el proyecto
        - Herramienta para gestionar esferas de relojes Huawei e instalar esferas HWT de terceros, para la mayoría de dispositivos de las series WATCH, GT, FIT, etc. con el protocolo de esferas compatible; en las versiones recientes de HarmonyOS donde la instalación directa falla, las esferas de terceros se instalan por sustitución. Autor: Escosis (github.com/Escosis/WristMirror, licencia Apache-2.0). Esta es una traducción no oficial, distribuida por Kkaoss969 con Gateway Assistant.
        ### Consejos de uso
        - Antes de usarla, cierra Huawei Health y quítala de las apps recientes, para que no ocupe el canal de conexión y haga fallar las operaciones.
        - Obtén el ID de Huawei con la función integrada. Un ID incorrecto puede hacer que el reloj pregunte si borrar sus datos: en ese caso no confirmes, cancela y revisa la configuración.
        - La sustitución sobrescribe la esfera original: descarga primero una esfera gratuita desde Huawei Health (mejor de las esferas gratuitas oficiales que de la tienda de esferas), o elige como destino una que ya no uses. Si la original sigue en la tienda, podrás volver a descargarla.
        - Los WATCH en modo completo usan la instalación directa con formato XML o compatible y no admiten la sustitución; los WATCH en modo de ahorro de energía y las series GT, FIT, etc. usan recursos BIN: la instalación directa funciona con firmware anterior a 6.0.0.32, con firmware más reciente prueba la sustitución.
        - Si tras cambiar de modo un WATCH no se conecta, desconéctate primero y luego usa «Volver a vincular protocolo».
    """, """
        ### À propos du projet
        - Outil pour gérer les cadrans des montres Huawei et installer des cadrans HWT tiers, pour la plupart des appareils des séries WATCH, GT, FIT, etc. utilisant le protocole de cadrans compatible ; sur les versions récentes de HarmonyOS où l'installation directe échoue, les cadrans tiers s'installent par remplacement. Auteur : Escosis (github.com/Escosis/WristMirror, licence Apache-2.0). Ceci est une traduction non officielle, distribuée par Kkaoss969 avec Gateway Assistant.
        ### Conseils d'utilisation
        - Avant l'utilisation, fermez Huawei Health et retirez-la des apps récentes, pour qu'elle n'occupe pas le canal de connexion et ne fasse pas échouer les opérations.
        - Obtenez l'ID Huawei avec la fonction intégrée. Un ID erroné peut amener la montre à demander d'effacer ses données : dans ce cas ne confirmez pas, annulez et vérifiez la configuration.
        - Le remplacement écrase le cadran d'origine : téléchargez d'abord un cadran gratuit depuis Huawei Health (plutôt parmi les cadrans gratuits officiels que dans la boutique de cadrans), ou choisissez comme cible un cadran que vous n'utilisez plus. Si l'original est toujours proposé dans la boutique, vous pourrez le retélécharger.
        - Les WATCH en mode complet utilisent l'installation directe au format XML ou compatible et ne prennent pas en charge le remplacement ; les WATCH en mode économie d'énergie et les séries GT, FIT, etc. utilisent des ressources BIN : l'installation directe fonctionne avec un firmware antérieur à 6.0.0.32, avec un firmware plus récent essayez le remplacement.
        - Si une WATCH ne se connecte plus après un changement de mode, déconnectez-vous d'abord, puis utilisez «Réassocier le protocole».
    """, """
        ### Sobre o projeto
        - Ferramenta para gerir mostradores de relógios Huawei e instalar mostradores HWT de terceiros, para a maioria dos dispositivos das séries WATCH, GT, FIT, etc. com o protocolo de mostradores compatível; nas versões recentes do HarmonyOS em que a instalação direta falha, os mostradores de terceiros instalam-se por substituição. Autor: Escosis (github.com/Escosis/WristMirror, licença Apache-2.0). Esta é uma tradução não oficial, distribuída por Kkaoss969 com o Gateway Assistant.
        ### Conselhos de utilização
        - Antes de usar, feche a Huawei Health e remova-a das apps recentes, para não ocupar o canal de ligação e fazer falhar as operações.
        - Obtenha o ID Huawei com a função integrada. Um ID errado pode fazer o relógio perguntar se quer apagar os dados: nesse caso não confirme, cancele e verifique a configuração.
        - A substituição sobrescreve o mostrador original: transfira primeiro um mostrador gratuito da Huawei Health (de preferência dos mostradores gratuitos oficiais e não da loja de mostradores), ou escolha como destino um que já não use. Se o original ainda estiver na loja, poderá transferi-lo de novo.
        - Os WATCH em modo completo usam a instalação direta com formato XML ou compatível e não suportam a substituição; os WATCH em modo de poupança de energia e as séries GT, FIT, etc. usam recursos BIN: a instalação direta funciona com firmware anterior a 6.0.0.32, com firmware mais recente experimente a substituição.
        - Se depois de mudar de modo um WATCH não se ligar, desligue primeiro e depois use «Voltar a associar protocolo».
    """, """
        ### Sobre o projeto
        - Ferramenta para gerenciar mostradores de relógios Huawei e instalar mostradores HWT de terceiros, para a maioria dos dispositivos das séries WATCH, GT, FIT etc. com o protocolo de mostradores compatível; nas versões recentes do HarmonyOS em que a instalação direta falha, os mostradores de terceiros são instalados por substituição. Autor: Escosis (github.com/Escosis/WristMirror, licença Apache-2.0). Esta é uma tradução não oficial, distribuída por Kkaoss969 com o Gateway Assistant.
        ### Dicas de uso
        - Antes de usar, feche o Huawei Health e remova-o dos apps recentes, para ele não ocupar o canal de conexão e fazer as operações falharem.
        - Obtenha o ID Huawei com a função integrada. Um ID errado pode fazer o relógio perguntar se quer apagar os dados: nesse caso não confirme, cancele e verifique a configuração.
        - A substituição sobrescreve o mostrador original: baixe primeiro um mostrador gratuito pelo Huawei Health (de preferência dos mostradores gratuitos oficiais, não da loja de mostradores), ou escolha como destino um que você não usa mais. Se o original ainda estiver na loja, você poderá baixá-lo de novo.
        - Os WATCH no modo completo usam a instalação direta com formato XML ou compatível e não suportam a substituição; os WATCH no modo de economia de energia e as séries GT, FIT etc. usam recursos BIN: a instalação direta funciona com firmware anterior a 6.0.0.32, com firmware mais recente tente a substituição.
        - Se depois de mudar de modo um WATCH não conectar, desconecte primeiro e depois use «Vincular protocolo de novo».
    """, """
        ### За проекта
        - Инструмент за управление на циферблати на часовници Huawei и инсталиране на HWT циферблати от трети страни, за повечето устройства от сериите WATCH, GT, FIT и др. със съвместимия протокол за циферблати; на по-новите версии на HarmonyOS, където директното инсталиране не работи, циферблатите от трети страни се инсталират чрез замяна. Автор: Escosis (github.com/Escosis/WristMirror, лиценз Apache-2.0). Това е неофициален превод, разпространяван от Kkaoss969 с Gateway Assistant.
        ### Съвети за употреба
        - Преди употреба затворете Huawei Health и я махнете от последните приложения, за да не заема канала за връзка и да не проваля операциите.
        - Вземете Huawei ID с вградената функция. Грешен ID може да накара часовника да попита дали да изтрие данните си: тогава не потвърждавайте, отменете и проверете настройките.
        - Замяната презаписва оригиналния циферблат: първо изтеглете безплатен циферблат от Huawei Health (по-добре от официалните безплатни циферблати, отколкото от магазина за циферблати) или изберете за цел такъв, който вече не използвате. Ако оригиналът още се предлага в магазина, можете да го изтеглите отново.
        - WATCH в пълен режим използват директно инсталиране с формат XML или съвместим формат и не поддържат замяна; WATCH в енергоспестяващ режим и сериите GT, FIT и др. използват ресурси BIN: директното инсталиране работи с фърмуер по-стар от 6.0.0.32, при по-нов фърмуер опитайте замяна.
        - Ако след смяна на режима WATCH не се свързва, първо прекъснете връзката, после използвайте „Ново обвързване на протокола“.
    """, """
        ### Proje hakkında
        - Huawei saat kadranlarını yönetmek ve üçüncü taraf HWT kadranları yüklemek için bir araç; uyumlu kadran protokolünü kullanan WATCH, GT, FIT vb. serilerin çoğu cihaz için. Doğrudan yüklemenin çalışmadığı yeni HarmonyOS sürümlerinde üçüncü taraf kadranlar değiştirme yoluyla yüklenir. Geliştirici: Escosis (github.com/Escosis/WristMirror, Apache-2.0 lisansı). Bu, Kkaoss969 tarafından Gateway Assistant ile dağıtılan resmî olmayan bir çeviridir.
        ### Kullanım önerileri
        - Kullanmadan önce Huawei Health'i kapatın ve son uygulamalardan kaldırın; böylece bağlantı kanalını meşgul edip işlemlerin başarısız olmasına yol açmaz.
        - Huawei ID'yi yerleşik özellikle alın. Yanlış bir ID, saatin verilerini silmeyi sormasına yol açabilir: bu durumda onaylamayın, iptal edip yapılandırmayı kontrol edin.
        - Değiştirme orijinal kadranın üzerine yazar: önce Huawei Health'ten ücretsiz bir kadran indirin (kadran mağazası yerine resmî ücretsiz kadranlardan) veya artık kullanmadığınız bir kadranı hedef olarak seçin. Orijinal mağazada hâlâ varsa yeniden indirebilirsiniz.
        - Tam moddaki WATCH serisi XML veya uyumlu biçimle doğrudan yükleme kullanır ve değiştirmeyi desteklemez; güç tasarrufu modundaki WATCH ile GT, FIT vb. seriler BIN kaynakları kullanır: doğrudan yükleme 6.0.0.32'den eski yazılımlarda çalışır, daha yeni yazılımlarda değiştirmeyi deneyin.
        - Mod değiştirdikten sonra bir WATCH bağlanmıyorsa önce bağlantıyı kesin, sonra «Protokolü yeniden bağla»yı kullanın.
    """, """
        ### Über das Projekt
        - Ein Werkzeug zum Verwalten von Huawei-Zifferblättern und zum Installieren von HWT-Zifferblättern von Drittanbietern, für die meisten Geräte der Serien WATCH, GT, FIT usw. mit dem kompatiblen Zifferblattprotokoll; auf neueren HarmonyOS-Versionen, auf denen die Direktinstallation nicht funktioniert, werden Zifferblätter von Drittanbietern durch Ersetzen installiert. Autor: Escosis (github.com/Escosis/WristMirror, Apache-2.0-Lizenz). Dies ist eine inoffizielle Übersetzung, verteilt von Kkaoss969 mit Gateway Assistant.
        ### Nutzungstipps
        - Schließe vor der Nutzung Huawei Health und entferne es aus den letzten Apps, damit es den Verbindungskanal nicht belegt und Vorgänge scheitern lässt.
        - Hole die Huawei-ID mit der integrierten Funktion. Eine falsche ID kann dazu führen, dass die Uhr fragt, ob ihre Daten gelöscht werden sollen: dann nicht bestätigen, sondern abbrechen und die Konfiguration prüfen.
        - Das Ersetzen überschreibt das ursprüngliche Zifferblatt: lade zuerst ein kostenloses Zifferblatt aus Huawei Health herunter (besser aus den offiziellen kostenlosen Zifferblättern als aus dem Zifferblatt-Shop) oder wähle als Ziel eines, das du nicht mehr verwendest. Wird das Original noch im Shop angeboten, kannst du es erneut herunterladen.
        - WATCH im Vollmodus verwenden die Direktinstallation mit XML- oder kompatiblem Format und unterstützen kein Ersetzen; WATCH im Energiesparmodus und GT-, FIT- usw. Serien verwenden BIN-Ressourcen: die Direktinstallation funktioniert mit Firmware älter als 6.0.0.32, bei neuerer Firmware versuche das Ersetzen.
        - Verbindet sich eine WATCH nach einem Moduswechsel nicht, trenne zuerst die Verbindung und verwende dann „Protokoll neu binden“.
    """).trimIndent()

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
