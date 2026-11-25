package io.github.escosis.hwtfaceswap.protocol

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
        "Il formato BIN supporta solo l'installazione diretta su WATCH in modalità risparmio energetico e su serie GT, FIT ecc. (firmware precedente alla 6.0.0.32) e la sostituzione, non i WATCH in modalità completa; il formato XML supporta solo l'installazione diretta su WATCH in modalità completa; il formato compatibile supporta entrambe le installazioni dirette e la sostituzione della prima."

    /** 三种格式各自的用途；措辞固定，避免界面各处以不同口径解释。 */
    @JvmStatic
    fun formatNote(kinds: List<String>): String = when {
        BIN in kinds && (CONTAINER in kinds || STRIPPED in kinds) ->
            "Il formato compatibile supporta l'installazione diretta su WATCH in modalità completa (senza sostituzione) e anche l'installazione diretta su WATCH in modalità risparmio energetico e su serie GT, FIT ecc. (firmware precedente alla 6.0.0.32) e la sostituzione."
        BIN in kinds ->
            "Il formato BIN supporta solo l'installazione diretta su WATCH in modalità risparmio energetico e su serie GT, FIT ecc. (firmware precedente alla 6.0.0.32) e la sostituzione; non supporta i WATCH in modalità completa."
        CONTAINER in kinds || STRIPPED in kinds ->
            "Il formato XML supporta solo l'installazione diretta su WATCH in modalità completa; non supporta la sostituzione né altre serie o modalità."
        else -> "Nessun formato di quadrante utilizzabile riconosciuto."
    }

    /** 详情与提示共用的三分类：只有 BIN、只有 XML、两者兼有（兼容格式）。 */
    @JvmStatic
    fun displayFormat(kinds: List<String>): String = when {
        BIN in kinds && (CONTAINER in kinds || STRIPPED in kinds) -> "Formato compatibile"
        BIN in kinds -> "Formato BIN"
        CONTAINER in kinds || STRIPPED in kinds -> "Formato XML"
        else -> "Nessun formato di quadrante utilizzabile riconosciuto"
    }

    @JvmStatic
    fun supportedTypes(kinds: List<String>): String {
        val types = mutableListOf<String>()
        if (BIN in kinds) types.add("Formato BIN")
        if (CONTAINER in kinds || STRIPPED in kinds) types.add("Formato XML")
        return types.joinToString(" / ").ifEmpty { "Nessuna risorsa di quadrante utilizzabile riconosciuta" }
    }

    @JvmStatic
    fun label(kind: String): String = when (kind) {
        BIN -> "Formato BIN"
        CONTAINER -> "Formato XML"
        STRIPPED -> "Formato XML (derivato)"
        else -> "Nessun contenuto trasferibile"
    }

    @JvmStatic
    fun shortLabel(kind: String): String = when (kind) {
        BIN -> "BIN"
        CONTAINER -> "XML"
        STRIPPED -> "XML derivato"
        else -> "Sconosciuto"
    }

    @JvmStatic
    fun detail(kind: String): String = when (kind) {
        BIN -> "watchface.bin interno (formato BIN, solo per sovrascrivere un quadrante esistente)"
        CONTAINER -> "Byte originali di com.huawei.watchface interno (formato XML, include watchface.bin)"
        STRIPPED -> "Payload per modalità completa ricostruito rimuovendo watchface.bin dalla radice del contenitore interno; gli altri elementi sono copiati byte per byte"
        else -> "Nessuno"
    }

    /** 覆盖既有表盘仍只使用经典 BIN；全能模式载荷没有经过验证的覆盖路径。 */
    @JvmStatic
    fun replacementAllowed(kind: String): Boolean = kind == BIN
}