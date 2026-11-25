package io.github.escosis.hwtfaceswap

import io.github.escosis.hwtfaceswap.protocol.MiniJson
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** 纯发布解析器，复用有大小、深度、节点数和重复键限制的 JSON 解析器。 */
internal object UpdateReleaseParser {
    const val MAX_RESPONSE_BYTES = MiniJson.MAX_BYTES

    fun parse(body: ByteArray): UpdateResult {
        if (body.size > MAX_RESPONSE_BYTES) return failure("Risposta del servizio aggiornamenti troppo grande; controllo interrotto")
        val release = try {
            MiniJson.parseObject(body)
        } catch (_: IOException) {
            return failure("Informazioni sulla release non valide, riprova più tardi")
        } catch (_: IllegalArgumentException) {
            return failure("Informazioni sulla release non valide, riprova più tardi")
        }

        val draft = release["draft"] as? Boolean
            ?: return failure("Nelle informazioni sulla release manca il contrassegno di versione ufficiale")
        val prerelease = release["prerelease"] as? Boolean
            ?: return failure("Nelle informazioni sulla release manca il contrassegno di versione ufficiale")
        if (draft) return failure("Questa release è una bozza: nessun aggiornamento ufficiale disponibile")
        if (prerelease) return failure("Questa release è una pre-release: nessun aggiornamento ufficiale disponibile")

        val tagName = release["tag_name"] as? String
            ?: return failure("Nelle informazioni sulla release manca il numero di versione")
        val version = UpdateVersion.parse(tagName)
            ?: return failure("Numero di versione della release non valido; controllo interrotto")
        val releaseUrl = release["html_url"] as? String
            ?: return failure("Nelle informazioni sulla release manca il link alla pagina della release")
        if (!isSafeReleaseUrl(releaseUrl, tagName)) {
            return failure("Il link alla pagina della release non è sicuro; controllo interrotto")
        }

        val assets = release["assets"] as? List<*>
            ?: return failure("Nelle informazioni sulla release manca l'elenco dei download")
        var downloadUrl = ""
        for (entry in assets) {
            val asset = entry as? Map<*, *>
                ?: return failure("Informazioni di download della release non valide, riprova più tardi")
            val name = asset["name"] as? String
                ?: return failure("Nelle informazioni di download manca il nome del file")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val candidate = asset["browser_download_url"] as? String
                ?: return failure("Nelle informazioni di download manca il link di download")
            if (!isSafeDownloadUrl(candidate, tagName, name)) {
                return failure("Il link di download non è sicuro; controllo interrotto")
            }
            // 所有 APK 链接都必须通过校验，避免资产排序掩盖不安全链接。
            if (downloadUrl.isEmpty()) downloadUrl = candidate
        }

        return UpdateResult.Available(
            UpdateInfo(
                tagName = tagName,
                version = version.version,
                downloadUrl = downloadUrl,
                releaseUrl = releaseUrl,
            ),
        )
    }

    private fun isSafeReleaseUrl(value: String, tagName: String): Boolean {
        val uri = safeGithubUri(value) ?: return false
        return uri.rawPath == "/Escosis/WristMirror/releases/tag/$tagName"
    }

    private fun isSafeDownloadUrl(value: String, tagName: String, name: String): Boolean {
        val uri = safeGithubUri(value) ?: return false
        val prefix = "/Escosis/WristMirror/releases/download/$tagName/"
        val rawPath = uri.rawPath ?: return false
        if (!rawPath.startsWith(prefix)) return false
        val rawName = rawPath.removePrefix(prefix)
        if (rawName.isEmpty() || '/' in rawName || '\\' in rawName) return false
        val decodedPath = uri.path ?: return false
        if (!decodedPath.startsWith(prefix)) return false
        val decodedName = decodedPath.removePrefix(prefix)
        if (decodedName != name || !decodedName.endsWith(".apk", ignoreCase = true)) return false
        // 拒绝残留百分号，避免二次解码把文件名变成跳转或路径分隔符。
        return decodedName.none { it == '/' || it == '\\' || it == '%' || it.code < 32 || it.code == 127 || it == '\uFFFD' }
    }

    private fun safeGithubUri(value: String): URI? {
        if (value.length !in 1..2048) return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.rawQuery != null || uri.rawFragment != null || uri.isOpaque
        ) return null
        return uri
    }

    private fun failure(message: String) = UpdateResult.Failure(message)
}
