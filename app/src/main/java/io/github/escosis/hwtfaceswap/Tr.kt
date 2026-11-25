package io.github.escosis.hwtfaceswap

import java.util.Locale

/** Testi tradotti (generato da traduzione/traduci.py del repository wristmirror-it): sceglie la lingua del telefono.
 * Ordine degli argomenti: zh, it, en, es, fr, pt, br, bg, tr, de. Lingue non previste: inglese. */
object Tr {
    @JvmStatic
    fun indice(): Int {
        val l = Locale.getDefault()
        return when (l.language) {
            "zh" -> 0
            "it" -> 1
            "en" -> 2
            "es" -> 3
            "fr" -> 4
            "bg" -> 7
            "tr" -> 8
            "de" -> 9
            "pt" -> if (l.country.equals("PT", ignoreCase = true)) 5 else 6
            else -> 2
        }
    }

    @JvmStatic
    fun tr(vararg testi: String): String = testi.getOrElse(indice()) { testi[0] }
}
