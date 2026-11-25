package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
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
            DeviceRefusal.limited(code) -> Tr.tr("手表表盘/文件数量已达上限，请先在手表上删除一个不用的表盘（如装了音乐也清理一些）后重试", "Raggiunto il numero massimo di quadranti/file sul watch: elimina dal watch un quadrante che non usi (e, se hai musica, anche qualche brano) e riprova", "The watch has reached the maximum number of watch faces/files: delete an unused watch face on the watch (and some music, if you have any) and try again", "El reloj ha alcanzado el número máximo de esferas/archivos: elimina en el reloj una esfera que no uses (y algo de música, si tienes) e inténtalo de nuevo", "La montre a atteint le nombre maximal de cadrans/fichiers : supprimez sur la montre un cadran inutilisé (et un peu de musique, si vous en avez) et réessayez", "O relógio atingiu o número máximo de mostradores/ficheiros: elimine no relógio um mostrador que não use (e alguma música, se tiver) e tente de novo", "O relógio atingiu o número máximo de mostradores/arquivos: exclua no relógio um mostrador que você não usa (e algumas músicas, se tiver) e tente de novo", "Часовникът е достигнал максималния брой циферблати/файлове: изтрийте на часовника неизползван циферблат (и малко музика, ако имате) и опитайте пак", "Saat en fazla kadran/dosya sayısına ulaştı: saatte kullanmadığınız bir kadranı (ve varsa biraz müziği) silip tekrar deneyin", "Die Uhr hat die Höchstzahl an Zifferblättern/Dateien erreicht: lösche auf der Uhr ein ungenutztes Zifferblatt (und etwas Musik, falls vorhanden) und versuche es erneut")
            DeviceRefusal.noSpace(code) -> Tr.tr("手表存储空间不足，请清理表盘或音乐后重试", "Spazio di archiviazione del watch insufficiente: libera spazio eliminando quadranti o musica e riprova", "Not enough storage on the watch: free up space by deleting watch faces or music and try again", "Espacio insuficiente en el reloj: libera espacio eliminando esferas o música e inténtalo de nuevo", "Stockage insuffisant sur la montre : libérez de l'espace en supprimant des cadrans ou de la musique et réessayez", "Armazenamento insuficiente no relógio: liberte espaço eliminando mostradores ou música e tente de novo", "Armazenamento insuficiente no relógio: libere espaço excluindo mostradores ou músicas e tente de novo", "Няма достатъчно място на часовника: освободете място, като изтриете циферблати или музика, и опитайте пак", "Saatte yeterli depolama alanı yok: kadran veya müzik silerek yer açın ve tekrar deneyin", "Nicht genug Speicher auf der Uhr: schaffe Platz, indem du Zifferblätter oder Musik löschst, und versuche es erneut")
            else -> null
        }
    }
}