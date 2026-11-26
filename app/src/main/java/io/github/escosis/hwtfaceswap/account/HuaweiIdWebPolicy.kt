package io.github.escosis.hwtfaceswap.account


import io.github.escosis.hwtfaceswap.Tr
import java.net.URI

/** Pure parsing/navigation rules. Never inspect a page DOM, export cookies, or treat an AGC team ID as an account UID. */
object HuaweiIdWebPolicy {
    const val START_URL = "https://www.vmall.com/"
    val COOKIE_URLS: List<String> = listOf(START_URL, "https://m.vmall.com/")
    private val requestPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validRequest(value: String?): Boolean = value != null && requestPattern.matches(value)
    fun validId(value: String?): Boolean = value != null && value.length in 1..19 &&
        value.all { it in '0'..'9' } && value.any { it != '0' }

    private fun host(url: String?): String? {
        if (url == null || url.length > 8192) return null
        return runCatching {
            val uri = URI(url)
            if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) null
            else uri.host?.lowercase(java.util.Locale.ROOT)
        }.getOrNull()
    }

    private fun within(host: String, domain: String) = host == domain || host.endsWith(".$domain")
    fun allowsNavigation(url: String?): Boolean = host(url)?.let { within(it, "vmall.com") || within(it, "huawei.com") } == true
    fun allowsEmbeddedNavigation(url: String?): Boolean = host(url) != null
    fun isMallPage(url: String?): Boolean = host(url)?.let { within(it, "vmall.com") } == true
    fun displayHost(url: String?): String = host(url)?.takeIf { allowsNavigation(url) } ?: Tr.tr("华为商城", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store", "Huawei Store")

    /** Read only the exact uid cookie from fixed mall origins. Conflicting, oversized or ambiguous values fail closed. */
    fun idFromCookies(headers: List<String?>): String? {
        var found: String? = null
        for (header in headers) {
            if (header == null) continue
            if (header.length > 32768) return null
            for (part in header.split(';')) {
                val equals = part.indexOf('=')
                if (equals < 0 || part.substring(0, equals).trim() != "uid") continue
                val value = part.substring(equals + 1).trim()
                if (!validId(value)) return null
                if (found != null && found != value) return null
                found = value
            }
        }
        return found
    }
}
