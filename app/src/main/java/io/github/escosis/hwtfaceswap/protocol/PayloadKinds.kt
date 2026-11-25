package io.github.escosis.hwtfaceswap.protocol


import io.github.escosis.hwtfaceswap.Tr
/**
 * 载荷类型只描述“本地 HWT 里能取出什么”，不代表手表端会接纳该内容。
 * 经典表盘取内层根 watchface.bin；Watch 全能模式取内层资源容器原字节；
 * 只有内层同时含 bin 与 watchface/ 资源树时，才额外提供移除 bin 的派生容器。
 */
object PayloadKinds {
    const val BIN = "bin"
    const val CONTAINER = "container"
    const val STRIPPED = "container_no_bin"
    const val NONE = "none"

    @JvmStatic
    fun available(source: HwtSource): List<String> {
        val kinds = mutableListOf<String>()
        if (source.payloadBin()) kinds.add(BIN)
        if (source.payloadContainer()) kinds.add(CONTAINER)
        return kinds
    }

    @JvmStatic
    fun kind(mode: HwtSource.Mode): String = when (mode) {
        HwtSource.Mode.BIN -> BIN
        HwtSource.Mode.CONTAINER -> CONTAINER
        HwtSource.Mode.CONTAINER_NO_BIN -> STRIPPED
        HwtSource.Mode.HWT -> NONE
    }

    @JvmStatic
    fun mode(kind: String): HwtSource.Mode? = when (kind) {
        BIN -> HwtSource.Mode.BIN
        CONTAINER -> HwtSource.Mode.CONTAINER
        STRIPPED -> HwtSource.Mode.CONTAINER_NO_BIN
        else -> null
    }

    @JvmStatic
    fun preferred(kinds: List<String>): String? = when {
        CONTAINER in kinds -> CONTAINER
        BIN in kinds -> BIN
        else -> null
    }

    /** 直装只用内层容器原字节（XML 格式）；BIN 只用于覆盖既有表盘。 */
    @JvmStatic
    fun directInstallKinds(kinds: List<String>): List<String> =
        if (CONTAINER in kinds) listOf(CONTAINER) else emptyList()

    /** 两种命名口径的汇总说明，仅用于兼容旧调用；界面按文件格式使用 formatNote(kinds)。 */
    @JvmStatic
    fun formatNote(): String =
        Tr.tr("BIN 格式仅支持 WATCH 系列省电模式以及 GT、FIT 等系列直装（6.0.0.32 之前）和替换，不支持 WATCH 系列全能模式；XML 格式仅支持 WATCH 系列全能模式直装；兼容格式同时支持两种直装并支持前者的替换。", "Il formato BIN supporta solo l'installazione diretta su WATCH in modalità risparmio energetico e su serie GT, FIT ecc. (firmware precedente alla 6.0.0.32) e la sostituzione, non i WATCH in modalità completa; il formato XML supporta solo l'installazione diretta su WATCH in modalità completa; il formato compatibile supporta entrambe le installazioni dirette e la sostituzione della prima.", "The BIN format only supports direct install on WATCH series in power-saving mode and GT, FIT etc. series (firmware older than 6.0.0.32) and replacement, not WATCH series in full mode; the XML format only supports direct install on WATCH series in full mode; the compatible format supports both direct installs and replacement for the former.", "El formato BIN solo admite la instalación directa en WATCH en modo de ahorro de energía y en las series GT, FIT, etc. (firmware anterior a 6.0.0.32) y la sustitución, no los WATCH en modo completo; el formato XML solo admite la instalación directa en WATCH en modo completo; el formato compatible admite ambas instalaciones directas y la sustitución del primero.", "Le format BIN ne prend en charge que l'installation directe sur les WATCH en mode économie d'énergie et les séries GT, FIT, etc. (firmware antérieur à 6.0.0.32) et le remplacement, pas les WATCH en mode complet ; le format XML ne prend en charge que l'installation directe sur les WATCH en mode complet ; le format compatible prend en charge les deux installations directes et le remplacement du premier.", "O formato BIN só suporta a instalação direta em WATCH em modo de poupança de energia e nas séries GT, FIT, etc. (firmware anterior a 6.0.0.32) e a substituição, não os WATCH em modo completo; o formato XML só suporta a instalação direta em WATCH em modo completo; o formato compatível suporta as duas instalações diretas e a substituição do primeiro.", "O formato BIN só suporta a instalação direta em WATCH no modo de economia de energia e nas séries GT, FIT etc. (firmware anterior a 6.0.0.32) e a substituição, não os WATCH no modo completo; o formato XML só suporta a instalação direta em WATCH no modo completo; o formato compatível suporta as duas instalações diretas e a substituição do primeiro.", "Форматът BIN поддържа само директно инсталиране на WATCH в енергоспестяващ режим и на сериите GT, FIT и др. (фърмуер по-стар от 6.0.0.32) и замяна, но не и WATCH в пълен режим; форматът XML поддържа само директно инсталиране на WATCH в пълен режим; съвместимият формат поддържа и двете директни инсталирания и замяна за първото.", "BIN biçimi yalnızca güç tasarrufu modundaki WATCH serisinde ve GT, FIT vb. serilerde (6.0.0.32'den eski yazılım) doğrudan yüklemeyi ve değiştirmeyi destekler, tam moddaki WATCH serisini desteklemez; XML biçimi yalnızca tam moddaki WATCH serisinde doğrudan yüklemeyi destekler; uyumlu biçim her iki doğrudan yüklemeyi ve ilki için değiştirmeyi destekler.", "Das BIN-Format unterstützt nur die Direktinstallation auf WATCH im Energiesparmodus und auf GT-, FIT- usw. Serien (Firmware älter als 6.0.0.32) sowie das Ersetzen, nicht WATCH im Vollmodus; das XML-Format unterstützt nur die Direktinstallation auf WATCH im Vollmodus; das kompatible Format unterstützt beide Direktinstallationen und das Ersetzen für die erste.")

    /** 三种格式各自的用途；措辞固定，避免界面各处以不同口径解释。 */
    @JvmStatic
    fun formatNote(kinds: List<String>): String = when {
        BIN in kinds && (CONTAINER in kinds || STRIPPED in kinds) ->
            Tr.tr("兼容格式支持 WATCH 系列全能模式直装（不支持替换），也支持 WATCH 系列省电模式以及 GT、FIT 等系列直装（6.0.0.32 之前）和替换。", "Il formato compatibile supporta l'installazione diretta su WATCH in modalità completa (senza sostituzione) e anche l'installazione diretta su WATCH in modalità risparmio energetico e su serie GT, FIT ecc. (firmware precedente alla 6.0.0.32) e la sostituzione.", "The compatible format supports direct install on WATCH series in full mode (no replacement), as well as direct install on WATCH series in power-saving mode and GT, FIT etc. series (firmware older than 6.0.0.32) and replacement.", "El formato compatible admite la instalación directa en WATCH en modo completo (sin sustitución) y también la instalación directa en WATCH en modo de ahorro de energía y en las series GT, FIT, etc. (firmware anterior a 6.0.0.32) y la sustitución.", "Le format compatible prend en charge l'installation directe sur les WATCH en mode complet (sans remplacement), ainsi que l'installation directe sur les WATCH en mode économie d'énergie et les séries GT, FIT, etc. (firmware antérieur à 6.0.0.32) et le remplacement.", "O formato compatível suporta a instalação direta em WATCH em modo completo (sem substituição) e também a instalação direta em WATCH em modo de poupança de energia e nas séries GT, FIT, etc. (firmware anterior a 6.0.0.32) e a substituição.", "O formato compatível suporta a instalação direta em WATCH no modo completo (sem substituição) e também a instalação direta em WATCH no modo de economia de energia e nas séries GT, FIT etc. (firmware anterior a 6.0.0.32) e a substituição.", "Съвместимият формат поддържа директно инсталиране на WATCH в пълен режим (без замяна), както и директно инсталиране на WATCH в енергоспестяващ режим и на сериите GT, FIT и др. (фърмуер по-стар от 6.0.0.32) и замяна.", "Uyumlu biçim, tam moddaki WATCH serisinde doğrudan yüklemeyi (değiştirme olmadan) ve ayrıca güç tasarrufu modundaki WATCH serisinde ve GT, FIT vb. serilerde (6.0.0.32'den eski yazılım) doğrudan yüklemeyi ve değiştirmeyi destekler.", "Das kompatible Format unterstützt die Direktinstallation auf WATCH im Vollmodus (ohne Ersetzen) sowie die Direktinstallation auf WATCH im Energiesparmodus und auf GT-, FIT- usw. Serien (Firmware älter als 6.0.0.32) und das Ersetzen.")
        BIN in kinds ->
            Tr.tr("BIN 格式仅支持 WATCH 系列省电模式以及 GT、FIT 等系列直装（6.0.0.32 之前）和替换，不支持 WATCH 系列全能模式。", "Il formato BIN supporta solo l'installazione diretta su WATCH in modalità risparmio energetico e su serie GT, FIT ecc. (firmware precedente alla 6.0.0.32) e la sostituzione; non supporta i WATCH in modalità completa.", "The BIN format only supports direct install on WATCH series in power-saving mode and GT, FIT etc. series (firmware older than 6.0.0.32) and replacement; it doesn't support WATCH series in full mode.", "El formato BIN solo admite la instalación directa en WATCH en modo de ahorro de energía y en las series GT, FIT, etc. (firmware anterior a 6.0.0.32) y la sustitución; no admite los WATCH en modo completo.", "Le format BIN ne prend en charge que l'installation directe sur les WATCH en mode économie d'énergie et les séries GT, FIT, etc. (firmware antérieur à 6.0.0.32) et le remplacement ; il ne prend pas en charge les WATCH en mode complet.", "O formato BIN só suporta a instalação direta em WATCH em modo de poupança de energia e nas séries GT, FIT, etc. (firmware anterior a 6.0.0.32) e a substituição; não suporta os WATCH em modo completo.", "O formato BIN só suporta a instalação direta em WATCH no modo de economia de energia e nas séries GT, FIT etc. (firmware anterior a 6.0.0.32) e a substituição; não suporta os WATCH no modo completo.", "Форматът BIN поддържа само директно инсталиране на WATCH в енергоспестяващ режим и на сериите GT, FIT и др. (фърмуер по-стар от 6.0.0.32) и замяна; не поддържа WATCH в пълен режим.", "BIN biçimi yalnızca güç tasarrufu modundaki WATCH serisinde ve GT, FIT vb. serilerde (6.0.0.32'den eski yazılım) doğrudan yüklemeyi ve değiştirmeyi destekler; tam moddaki WATCH serisini desteklemez.", "Das BIN-Format unterstützt nur die Direktinstallation auf WATCH im Energiesparmodus und auf GT-, FIT- usw. Serien (Firmware älter als 6.0.0.32) sowie das Ersetzen; WATCH im Vollmodus werden nicht unterstützt.")
        CONTAINER in kinds || STRIPPED in kinds ->
            Tr.tr("XML 格式仅支持 WATCH 系列全能模式直装，不支持替换，也不支持其他系列或模式。", "Il formato XML supporta solo l'installazione diretta su WATCH in modalità completa; non supporta la sostituzione né altre serie o modalità.", "The XML format only supports direct install on WATCH series in full mode; it doesn't support replacement or other series or modes.", "El formato XML solo admite la instalación directa en WATCH en modo completo; no admite la sustitución ni otras series o modos.", "Le format XML ne prend en charge que l'installation directe sur les WATCH en mode complet ; ni le remplacement, ni les autres séries ou modes.", "O formato XML só suporta a instalação direta em WATCH em modo completo; não suporta a substituição nem outras séries ou modos.", "O formato XML só suporta a instalação direta em WATCH no modo completo; não suporta a substituição nem outras séries ou modos.", "Форматът XML поддържа само директно инсталиране на WATCH в пълен режим; не поддържа замяна, нито други серии или режими.", "XML biçimi yalnızca tam moddaki WATCH serisinde doğrudan yüklemeyi destekler; değiştirmeyi ve diğer serileri veya modları desteklemez.", "Das XML-Format unterstützt nur die Direktinstallation auf WATCH im Vollmodus; weder Ersetzen noch andere Serien oder Modi.")
        else -> Tr.tr("未识别可用的表盘格式。", "Nessun formato di quadrante utilizzabile riconosciuto.", "No usable watch face format recognized.", "No se reconoció ningún formato de esfera utilizable.", "Aucun format de cadran utilisable reconnu.", "Nenhum formato de mostrador utilizável reconhecido.", "Nenhum formato de mostrador utilizável reconhecido.", "Не е разпознат използваем формат на циферблат.", "Kullanılabilir kadran biçimi tanınmadı.", "Kein nutzbares Zifferblattformat erkannt.")
    }

    /** 详情与提示共用的三分类：只有 BIN、只有 XML、两者兼有（兼容格式）。 */
    @JvmStatic
    fun displayFormat(kinds: List<String>): String = when {
        BIN in kinds && (CONTAINER in kinds || STRIPPED in kinds) -> Tr.tr("兼容格式", "Formato compatibile", "Compatible format", "Formato compatible", "Format compatible", "Formato compatível", "Formato compatível", "Съвместим формат", "Uyumlu biçim", "Kompatibles Format")
        BIN in kinds -> Tr.tr("BIN 格式", "Formato BIN", "BIN format", "Formato BIN", "Format BIN", "Formato BIN", "Formato BIN", "Формат BIN", "BIN biçimi", "BIN-Format")
        CONTAINER in kinds || STRIPPED in kinds -> Tr.tr("XML 格式", "Formato XML", "XML format", "Formato XML", "Format XML", "Formato XML", "Formato XML", "Формат XML", "XML biçimi", "XML-Format")
        else -> Tr.tr("未识别可用的表盘格式", "Nessun formato di quadrante utilizzabile riconosciuto", "No usable watch face format recognized", "No se reconoció ningún formato de esfera utilizable", "Aucun format de cadran utilisable reconnu", "Nenhum formato de mostrador utilizável reconhecido", "Nenhum formato de mostrador utilizável reconhecido", "Не е разпознат използваем формат на циферблат", "Kullanılabilir kadran biçimi tanınmadı", "Kein nutzbares Zifferblattformat erkannt")
    }

    @JvmStatic
    fun supportedTypes(kinds: List<String>): String {
        val types = mutableListOf<String>()
        if (BIN in kinds) types.add(Tr.tr("BIN 格式", "Formato BIN", "BIN format", "Formato BIN", "Format BIN", "Formato BIN", "Formato BIN", "Формат BIN", "BIN biçimi", "BIN-Format"))
        if (CONTAINER in kinds || STRIPPED in kinds) types.add(Tr.tr("XML 格式", "Formato XML", "XML format", "Formato XML", "Format XML", "Formato XML", "Formato XML", "Формат XML", "XML biçimi", "XML-Format"))
        return types.joinToString(" / ").ifEmpty { Tr.tr("未识别可用的表盘资源", "Nessuna risorsa di quadrante utilizzabile riconosciuta", "No usable watch face resource recognized", "No se reconoció ningún recurso de esfera utilizable", "Aucune ressource de cadran utilisable reconnue", "Nenhum recurso de mostrador utilizável reconhecido", "Nenhum recurso de mostrador utilizável reconhecido", "Не е разпознат използваем ресурс на циферблат", "Kullanılabilir kadran kaynağı tanınmadı", "Keine nutzbare Zifferblatt-Ressource erkannt") }
    }

    @JvmStatic
    fun label(kind: String): String = when (kind) {
        BIN -> Tr.tr("BIN 格式", "Formato BIN", "BIN format", "Formato BIN", "Format BIN", "Formato BIN", "Formato BIN", "Формат BIN", "BIN biçimi", "BIN-Format")
        CONTAINER -> Tr.tr("XML 格式", "Formato XML", "XML format", "Formato XML", "Format XML", "Formato XML", "Formato XML", "Формат XML", "XML biçimi", "XML-Format")
        STRIPPED -> Tr.tr("XML 格式（派生）", "Formato XML (derivato)", "XML format (derived)", "Formato XML (derivado)", "Format XML (dérivé)", "Formato XML (derivado)", "Formato XML (derivado)", "Формат XML (производен)", "XML biçimi (türetilmiş)", "XML-Format (abgeleitet)")
        else -> Tr.tr("无可传输内容", "Nessun contenuto trasferibile", "No transferable content", "Sin contenido transferible", "Aucun contenu transférable", "Sem conteúdo transferível", "Sem conteúdo transferível", "Няма съдържание за прехвърляне", "Aktarılabilir içerik yok", "Kein übertragbarer Inhalt")
    }

    @JvmStatic
    fun shortLabel(kind: String): String = when (kind) {
        BIN -> "BIN"
        CONTAINER -> "XML"
        STRIPPED -> Tr.tr("XML 派生", "XML derivato", "Derived XML", "XML derivado", "XML dérivé", "XML derivado", "XML derivado", "Производен XML", "Türetilmiş XML", "Abgeleitetes XML")
        else -> Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt")
    }

    @JvmStatic
    fun detail(kind: String): String = when (kind) {
        BIN -> Tr.tr("内层 watchface.bin（BIN 格式，仅用于覆盖既有表盘）", "watchface.bin interno (formato BIN, solo per sovrascrivere un quadrante esistente)", "Inner watchface.bin (BIN format, only for overwriting an existing watch face)", "watchface.bin interno (formato BIN, solo para sobrescribir una esfera existente)", "watchface.bin interne (format BIN, uniquement pour écraser un cadran existant)", "watchface.bin interno (formato BIN, só para substituir um mostrador existente)", "watchface.bin interno (formato BIN, só para sobrescrever um mostrador existente)", "Вътрешен watchface.bin (формат BIN, само за презаписване на съществуващ циферблат)", "İç watchface.bin (BIN biçimi, yalnızca mevcut bir kadranın üzerine yazmak için)", "Inneres watchface.bin (BIN-Format, nur zum Überschreiben eines vorhandenen Zifferblatts)")
        CONTAINER -> Tr.tr("内层 com.huawei.watchface 原字节（XML 格式，含 watchface.bin）", "Byte originali di com.huawei.watchface interno (formato XML, include watchface.bin)", "Original bytes of inner com.huawei.watchface (XML format, includes watchface.bin)", "Bytes originales de com.huawei.watchface interno (formato XML, incluye watchface.bin)", "Octets d'origine de com.huawei.watchface interne (format XML, inclut watchface.bin)", "Bytes originais de com.huawei.watchface interno (formato XML, inclui watchface.bin)", "Bytes originais de com.huawei.watchface interno (formato XML, inclui watchface.bin)", "Оригинални байтове на вътрешния com.huawei.watchface (формат XML, включва watchface.bin)", "İç com.huawei.watchface'in orijinal baytları (XML biçimi, watchface.bin içerir)", "Originalbytes des inneren com.huawei.watchface (XML-Format, enthält watchface.bin)")
        STRIPPED -> Tr.tr("由内层容器移除根 watchface.bin 后重新封装的全能模式载荷，其余成员逐字节复制", "Payload per modalità completa ricostruito rimuovendo watchface.bin dalla radice del contenitore interno; gli altri elementi sono copiati byte per byte", "Full-mode payload repackaged by removing the root watchface.bin from the inner container; the other members are copied byte for byte", "Carga para modo completo reempaquetada quitando watchface.bin de la raíz del contenedor interno; los demás elementos se copian byte a byte", "Charge pour le mode complet reconditionnée en retirant watchface.bin de la racine du conteneur interne ; les autres éléments sont copiés octet par octet", "Carga para modo completo reempacotada removendo watchface.bin da raiz do contentor interno; os outros elementos são copiados byte a byte", "Carga para o modo completo reempacotada removendo watchface.bin da raiz do contêiner interno; os outros elementos são copiados byte a byte", "Съдържание за пълен режим, препакетирано чрез премахване на watchface.bin от корена на вътрешния контейнер; другите елементи се копират байт по байт", "İç kapsayıcının kökündeki watchface.bin kaldırılarak yeniden paketlenmiş tam mod içeriği; diğer öğeler bayt bayt kopyalanır", "Inhalt für den Vollmodus, neu verpackt durch Entfernen von watchface.bin aus dem Stamm des inneren Containers; die übrigen Elemente werden Byte für Byte kopiert")
        else -> Tr.tr("无", "Nessuno", "None", "Ninguno", "Aucun", "Nenhum", "Nenhum", "Няма", "Yok", "Keine")
    }

    /** 覆盖既有表盘仍只使用经典 BIN；全能模式载荷没有经过验证的覆盖路径。 */
    @JvmStatic
    fun replacementAllowed(kind: String): Boolean = kind == BIN
}