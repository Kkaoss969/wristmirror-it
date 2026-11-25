package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.protocol.DeviceRefusal

/**
 * 只把设备明确给出的“表盘/文件数量受限、空间不足”翻成可执行提示。
 * 其它失败原因保持原有通用文案：不把未解释的码说成容量问题，也不假装安装成功。
 */
object OperationFailureText {
    @JvmStatic
    fun forReason(reason: String): String? {
        val code = DeviceRefusal.codeOf(reason)
        return when {
            DeviceRefusal.limited(code) -> "Raggiunto il numero massimo di quadranti/file sul watch: elimina dal watch un quadrante che non usi (e, se hai musica, anche qualche brano) e riprova"
            DeviceRefusal.noSpace(code) -> "Spazio di archiviazione del watch insufficiente: libera spazio eliminando quadranti o musica e riprova"
            else -> null
        }
    }
}