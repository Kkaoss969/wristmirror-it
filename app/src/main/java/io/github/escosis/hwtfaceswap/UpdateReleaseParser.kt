package io.github.escosis.hwtfaceswap

import io.github.escosis.hwtfaceswap.protocol.MiniJson
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** 纯发布解析器，复用有大小、深度、节点数和重复键限制的 JSON 解析器。 */
internal object UpdateReleaseParser {
    const val MAX_RESPONSE_BYTES = MiniJson.MAX_BYTES

    fun parse(body: ByteArray): UpdateResult {
        if (body.size > MAX_RESPONSE_BYTES) return failure(Tr.tr("更新响应过大，已停止检查", "Risposta del servizio aggiornamenti troppo grande; controllo interrotto", "Update service response too large; check stopped", "Respuesta del servicio de actualizaciones demasiado grande; comprobación detenida", "Réponse du service de mise à jour trop volumineuse ; vérification arrêtée", "Resposta do serviço de atualizações demasiado grande; verificação parada", "Resposta do serviço de atualizações grande demais; verificação interrompida", "Отговорът на услугата за актуализации е твърде голям; проверката е спряна", "Güncelleme hizmetinin yanıtı çok büyük; denetim durduruldu", "Antwort des Update-Dienstes zu groß; Prüfung gestoppt", "Ответ сервиса обновлений слишком большой; проверка остановлена"))
        val release = try {
            MiniJson.parseObject(body)
        } catch (_: IOException) {
            return failure(Tr.tr("发布信息无效，请稍后重试", "Informazioni sulla release non valide, riprova più tardi", "Invalid release information, try again later", "Información de la versión no válida, inténtalo más tarde", "Informations de version non valides, réessayez plus tard", "Informações da versão inválidas, tente mais tarde", "Informações da versão inválidas, tente mais tarde", "Невалидна информация за версията, опитайте по-късно", "Sürüm bilgisi geçersiz, daha sonra deneyin", "Ungültige Versionsinformationen, versuche es später", "Неверные сведения о выпуске, повторите позже"))
        } catch (_: IllegalArgumentException) {
            return failure(Tr.tr("发布信息无效，请稍后重试", "Informazioni sulla release non valide, riprova più tardi", "Invalid release information, try again later", "Información de la versión no válida, inténtalo más tarde", "Informations de version non valides, réessayez plus tard", "Informações da versão inválidas, tente mais tarde", "Informações da versão inválidas, tente mais tarde", "Невалидна информация за версията, опитайте по-късно", "Sürüm bilgisi geçersiz, daha sonra deneyin", "Ungültige Versionsinformationen, versuche es später", "Неверные сведения о выпуске, повторите позже"))
        }

        val draft = release["draft"] as? Boolean
            ?: return failure(Tr.tr("发布信息缺少正式版本标记", "Nelle informazioni sulla release manca il contrassegno di versione ufficiale", "The release information lacks the official version flag", "A la información de la versión le falta la marca de versión oficial", "Il manque l'indicateur de version officielle dans les informations de version", "Falta a marca de versão oficial nas informações da versão", "Falta a marca de versão oficial nas informações da versão", "В информацията за версията липсва отметката за официална версия", "Sürüm bilgisinde resmî sürüm işareti eksik", "In den Versionsinformationen fehlt die Kennzeichnung als offizielle Version", "В сведениях о выпуске нет отметки официальной версии"))
        val prerelease = release["prerelease"] as? Boolean
            ?: return failure(Tr.tr("发布信息缺少正式版本标记", "Nelle informazioni sulla release manca il contrassegno di versione ufficiale", "The release information lacks the official version flag", "A la información de la versión le falta la marca de versión oficial", "Il manque l'indicateur de version officielle dans les informations de version", "Falta a marca de versão oficial nas informações da versão", "Falta a marca de versão oficial nas informações da versão", "В информацията за версията липсва отметката за официална версия", "Sürüm bilgisinde resmî sürüm işareti eksik", "In den Versionsinformationen fehlt die Kennzeichnung als offizielle Version", "В сведениях о выпуске нет отметки официальной версии"))
        if (draft) return failure(Tr.tr("该发布为草稿，未提供正式更新", "Questa release è una bozza: nessun aggiornamento ufficiale disponibile", "This release is a draft: no official update available", "Esta versión es un borrador: no hay actualización oficial", "Cette version est un brouillon : aucune mise à jour officielle", "Esta versão é um rascunho: nenhuma atualização oficial", "Esta versão é um rascunho: nenhuma atualização oficial", "Тази версия е чернова: няма официална актуализация", "Bu sürüm bir taslak: resmî güncelleme yok", "Diese Version ist ein Entwurf: kein offizielles Update", "Этот выпуск — черновик: официального обновления нет"))
        if (prerelease) return failure(Tr.tr("该发布为预发行版本，未提供正式更新", "Questa release è una pre-release: nessun aggiornamento ufficiale disponibile", "This release is a pre-release: no official update available", "Esta versión es preliminar: no hay actualización oficial", "Cette version est une préversion : aucune mise à jour officielle", "Esta versão é uma pré-versão: nenhuma atualização oficial", "Esta versão é uma pré-versão: nenhuma atualização oficial", "Тази версия е предварителна: няма официална актуализация", "Bu sürüm bir ön sürüm: resmî güncelleme yok", "Diese Version ist eine Vorabversion: kein offizielles Update", "Этот выпуск — предварительный: официального обновления нет"))

        val tagName = release["tag_name"] as? String
            ?: return failure(Tr.tr("发布信息缺少版本号", "Nelle informazioni sulla release manca il numero di versione", "The release information lacks the version number", "A la información de la versión le falta el número de versión", "Il manque le numéro de version dans les informations de version", "Falta o número da versão nas informações da versão", "Falta o número da versão nas informações da versão", "В информацията за версията липсва номерът на версията", "Sürüm bilgisinde sürüm numarası eksik", "In den Versionsinformationen fehlt die Versionsnummer", "В сведениях о выпуске нет номера версии"))
        val version = UpdateVersion.parse(tagName)
            ?: return failure(Tr.tr("发布版本号无效，已停止检查", "Numero di versione della release non valido; controllo interrotto", "Invalid release version number; check stopped", "Número de versión no válido; comprobación detenida", "Numéro de version non valide ; vérification arrêtée", "Número de versão inválido; verificação parada", "Número de versão inválido; verificação interrompida", "Невалиден номер на версията; проверката е спряна", "Sürüm numarası geçersiz; denetim durduruldu", "Ungültige Versionsnummer; Prüfung gestoppt", "Неверный номер версии выпуска; проверка остановлена"))
        val releaseUrl = release["html_url"] as? String
            ?: return failure(Tr.tr("发布信息缺少发布页链接", "Nelle informazioni sulla release manca il link alla pagina della release", "The release information lacks the release page link", "A la información de la versión le falta el enlace a su página", "Il manque le lien vers la page de la version", "Falta a ligação para a página da versão", "Falta o link da página da versão", "Липсва връзка към страницата на версията", "Sürüm sayfasının bağlantısı eksik", "Der Link zur Versionsseite fehlt", "Нет ссылки на страницу выпуска"))
        if (!isSafeReleaseUrl(releaseUrl, tagName)) {
            return failure(Tr.tr("发布页链接不安全，已停止检查", "Il link alla pagina della release non è sicuro; controllo interrotto", "The release page link is not secure; check stopped", "El enlace a la página de la versión no es seguro; comprobación detenida", "Le lien vers la page de la version n'est pas sûr ; vérification arrêtée", "A ligação para a página da versão não é segura; verificação parada", "O link da página da versão não é seguro; verificação interrompida", "Връзката към страницата на версията не е сигурна; проверката е спряна", "Sürüm sayfasının bağlantısı güvenli değil; denetim durduruldu", "Der Link zur Versionsseite ist nicht sicher; Prüfung gestoppt", "Ссылка на страницу выпуска небезопасна; проверка остановлена"))
        }

        val assets = release["assets"] as? List<*>
            ?: return failure(Tr.tr("发布信息缺少下载列表", "Nelle informazioni sulla release manca l'elenco dei download", "The release information lacks the download list", "A la información de la versión le falta la lista de descargas", "Il manque la liste des téléchargements dans les informations de version", "Falta a lista de transferências nas informações da versão", "Falta a lista de downloads nas informações da versão", "В информацията за версията липсва списъкът за изтегляне", "Sürüm bilgisinde indirme listesi eksik", "In den Versionsinformationen fehlt die Downloadliste", "В сведениях о выпуске нет списка загрузок"))
        var downloadUrl = ""
        for (entry in assets) {
            val asset = entry as? Map<*, *>
                ?: return failure(Tr.tr("发布下载信息无效，请稍后重试", "Informazioni di download della release non valide, riprova più tardi", "Invalid release download information, try again later", "Información de descarga no válida, inténtalo más tarde", "Informations de téléchargement non valides, réessayez plus tard", "Informações de transferência inválidas, tente mais tarde", "Informações de download inválidas, tente mais tarde", "Невалидна информация за изтегляне, опитайте по-късно", "İndirme bilgisi geçersiz, daha sonra deneyin", "Ungültige Download-Informationen, versuche es später", "Неверные сведения о загрузке, повторите позже"))
            val name = asset["name"] as? String
                ?: return failure(Tr.tr("发布下载信息缺少文件名", "Nelle informazioni di download manca il nome del file", "The download information lacks the file name", "A la información de descarga le falta el nombre del archivo", "Il manque le nom du fichier dans les informations de téléchargement", "Falta o nome do ficheiro nas informações de transferência", "Falta o nome do arquivo nas informações de download", "В информацията за изтегляне липсва името на файла", "İndirme bilgisinde dosya adı eksik", "In den Download-Informationen fehlt der Dateiname", "В сведениях о загрузке нет имени файла"))
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val candidate = asset["browser_download_url"] as? String
                ?: return failure(Tr.tr("发布下载信息缺少下载链接", "Nelle informazioni di download manca il link di download", "The download information lacks the download link", "A la información de descarga le falta el enlace", "Il manque le lien de téléchargement", "Falta a ligação de transferência", "Falta o link de download", "Липсва връзката за изтегляне", "İndirme bağlantısı eksik", "Der Download-Link fehlt", "В сведениях о загрузке нет ссылки"))
            if (!isSafeDownloadUrl(candidate, tagName, name)) {
                return failure(Tr.tr("下载链接不安全，已停止检查", "Il link di download non è sicuro; controllo interrotto", "The download link is not secure; check stopped", "El enlace de descarga no es seguro; comprobación detenida", "Le lien de téléchargement n'est pas sûr ; vérification arrêtée", "A ligação de transferência não é segura; verificação parada", "O link de download não é seguro; verificação interrompida", "Връзката за изтегляне не е сигурна; проверката е спряна", "İndirme bağlantısı güvenli değil; denetim durduruldu", "Der Download-Link ist nicht sicher; Prüfung gestoppt", "Ссылка для загрузки небезопасна; проверка остановлена"))
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
        return uri.rawPath == "/Kkaoss969/wristmirror-it/releases/tag/$tagName"
    }

    private fun isSafeDownloadUrl(value: String, tagName: String, name: String): Boolean {
        val uri = safeGithubUri(value) ?: return false
        val prefix = "/Kkaoss969/wristmirror-it/releases/download/$tagName/"
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
