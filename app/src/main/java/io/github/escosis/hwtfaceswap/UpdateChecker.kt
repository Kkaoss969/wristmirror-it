package io.github.escosis.hwtfaceswap

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** 已校验的正式发布；没有 APK 资产时下载链接为空，仍可打开发布页。 */
data class UpdateInfo(
    val tagName: String,
    val version: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

/** 只有 404 表示尚未发布；有效发布是否较新由调用者结合当前版本判断。 */
sealed class UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult()
    object NoRelease : UpdateResult()
    data class Failure(val message: String) : UpdateResult()
}

object UpdateChecker {
    const val REPOSITORY_URL = "https://github.com/Escosis/WristMirror"
    const val RELEASES_URL = "https://api.github.com/repos/Kkaoss969/wristmirror-it/releases/latest"
    private const val TIMEOUT_MS = 15_000

    /** 阻塞检查，调用者应放在 IO 协程；连接、响应头和响应体共用十五秒预算。 */
    fun fetchLatest(): UpdateResult = fetchLatest(
        connectionFactory = { URI(RELEASES_URL).toURL().openConnection() as HttpsURLConnection },
    )

    /** 任一版本不符合三段数字格式时返回 false，不猜测或截断非法版本。 */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = UpdateVersion.parse(remoteVersion) ?: return false
        val current = UpdateVersion.parse(currentVersion) ?: return false
        return remote > current
    }

    /** 仅供离线测试注入连接与短时限，不改变公开接口或真实网络地址。 */
    internal fun fetchLatest(
        connectionFactory: () -> HttpsURLConnection,
        timeoutMillis: Int = TIMEOUT_MS,
    ): UpdateResult {
        require(timeoutMillis in 1..TIMEOUT_MS)
        if (Thread.currentThread().isInterrupted) return UpdateResult.Failure(Tr.tr("检查更新已取消", "Controllo aggiornamenti annullato", "Update check cancelled", "Comprobación de actualizaciones cancelada", "Vérification des mises à jour annulée", "Verificação de atualizações cancelada", "Verificação de atualizações cancelada", "Проверката за актуализации е отменена", "Güncelleme denetimi iptal edildi", "Update-Prüfung abgebrochen"))
        val request = ReleaseRequest(connectionFactory, timeoutMillis)
        val task = FutureTask<UpdateResult> { request.fetch() }
        val worker = Thread(task, Tr.tr("腕镜更新检查", "Controllo aggiornamenti WristMirror", "WristMirror update check", "Comprobación de actualizaciones de WristMirror", "Vérification des mises à jour de WristMirror", "Verificação de atualizações do WristMirror", "Verificação de atualizações do WristMirror", "Проверка за актуализации на WristMirror", "WristMirror güncelleme denetimi", "WristMirror-Update-Prüfung")).apply { isDaemon = true }
        return try {
            worker.start()
            task.get(request.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            request.cancel()
            UpdateResult.Failure(Tr.tr("检查更新超时，请稍后重试", "Timeout del controllo aggiornamenti, riprova più tardi", "Update check timed out, try again later", "Tiempo agotado al comprobar actualizaciones, inténtalo más tarde", "Délai dépassé lors de la vérification des mises à jour, réessayez plus tard", "Tempo esgotado na verificação de atualizações, tente mais tarde", "Tempo esgotado na verificação de atualizações, tente mais tarde", "Времето за проверка на актуализации изтече, опитайте по-късно", "Güncelleme denetimi zaman aşımına uğradı, daha sonra deneyin", "Zeitüberschreitung bei der Update-Prüfung, versuche es später"))
        } catch (_: SocketTimeoutException) {
            task.cancel(true)
            request.cancel()
            UpdateResult.Failure(Tr.tr("检查更新超时，请稍后重试", "Timeout del controllo aggiornamenti, riprova più tardi", "Update check timed out, try again later", "Tiempo agotado al comprobar actualizaciones, inténtalo más tarde", "Délai dépassé lors de la vérification des mises à jour, réessayez plus tard", "Tempo esgotado na verificação de atualizações, tente mais tarde", "Tempo esgotado na verificação de atualizações, tente mais tarde", "Времето за проверка на актуализации изтече, опитайте по-късно", "Güncelleme denetimi zaman aşımına uğradı, daha sonra deneyin", "Zeitüberschreitung bei der Update-Prüfung, versuche es später"))
        } catch (_: InterruptedException) {
            task.cancel(true)
            request.cancel()
            Thread.currentThread().interrupt()
            UpdateResult.Failure(Tr.tr("检查更新已取消", "Controllo aggiornamenti annullato", "Update check cancelled", "Comprobación de actualizaciones cancelada", "Vérification des mises à jour annulée", "Verificação de atualizações cancelada", "Verificação de atualizações cancelada", "Проверката за актуализации е отменена", "Güncelleme denetimi iptal edildi", "Update-Prüfung abgebrochen"))
        } catch (_: ExecutionException) {
            UpdateResult.Failure(Tr.tr("检查更新失败，请稍后重试", "Controllo aggiornamenti non riuscito, riprova più tardi", "Update check failed, try again later", "Error al comprobar actualizaciones, inténtalo más tarde", "Échec de la vérification des mises à jour, réessayez plus tard", "Falha na verificação de atualizações, tente mais tarde", "Falha na verificação de atualizações, tente mais tarde", "Проверката за актуализации не бе успешна, опитайте по-късно", "Güncelleme denetimi başarısız, daha sonra deneyin", "Update-Prüfung fehlgeschlagen, versuche es später"))
        } catch (_: SecurityException) {
            task.cancel(true)
            request.cancel()
            UpdateResult.Failure(Tr.tr("无法联网检查更新，请稍后重试", "Impossibile connettersi a Internet per controllare gli aggiornamenti, riprova più tardi", "Can't reach the internet to check for updates, try again later", "Sin acceso a internet para comprobar actualizaciones, inténtalo más tarde", "Pas d'accès à internet pour vérifier les mises à jour, réessayez plus tard", "Sem acesso à internet para verificar atualizações, tente mais tarde", "Sem acesso à internet para verificar atualizações, tente mais tarde", "Няма интернет за проверка на актуализации, опитайте по-късно", "Güncellemeleri denetlemek için internete erişilemiyor, daha sonra deneyin", "Kein Internet für die Update-Prüfung, versuche es später"))
        }
    }

    private class ReleaseRequest(
        private val connectionFactory: () -> HttpsURLConnection,
        private val timeoutMillis: Int,
    ) {
        private val startedAt = System.nanoTime()
        private val cancelled = AtomicBoolean(false)
        private val activeConnection = AtomicReference<HttpsURLConnection?>(null)
        private val activeInput = AtomicReference<InputStream?>(null)

        fun remainingMillis(): Int {
            val remaining = timeoutMillis * 1_000_000L - (System.nanoTime() - startedAt)
            if (cancelled.get() || Thread.currentThread().isInterrupted || remaining <= 0L) {
                throw SocketTimeoutException()
            }
            return ((remaining + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
        }

        fun fetch(): UpdateResult {
            var connection: HttpsURLConnection? = null
            return try {
                val opened = connectionFactory()
                connection = opened
                activeConnection.set(opened)
                remainingMillis()
                if (opened.url.toExternalForm() != RELEASES_URL) {
                    return UpdateResult.Failure(Tr.tr("更新服务地址不安全，已停止检查", "L'indirizzo del servizio aggiornamenti non è sicuro; controllo interrotto", "The update service address is not secure; check stopped", "La dirección del servicio de actualizaciones no es segura; comprobación detenida", "L'adresse du service de mise à jour n'est pas sûre ; vérification arrêtée", "O endereço do serviço de atualizações não é seguro; verificação parada", "O endereço do serviço de atualizações não é seguro; verificação interrompida", "Адресът на услугата за актуализации не е сигурен; проверката е спряна", "Güncelleme hizmetinin adresi güvenli değil; denetim durduruldu", "Die Adresse des Update-Dienstes ist nicht sicher; Prüfung gestoppt"))
                }
                opened.apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = remainingMillis()
                    readTimeout = remainingMillis()
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "WristMirror-UpdateChecker")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                opened.connect()
                opened.readTimeout = remainingMillis()
                val status = opened.responseCode
                remainingMillis()
                when (status) {
                    404 -> return UpdateResult.NoRelease
                    403, 429 -> return UpdateResult.Failure(Tr.tr("更新服务访问受限，请稍后重试", "Accesso al servizio aggiornamenti limitato, riprova più tardi", "Access to the update service is restricted, try again later", "Acceso al servicio de actualizaciones limitado, inténtalo más tarde", "Accès au service de mise à jour limité, réessayez plus tard", "Acesso ao serviço de atualizações limitado, tente mais tarde", "Acesso ao serviço de atualizações limitado, tente mais tarde", "Достъпът до услугата за актуализации е ограничен, опитайте по-късно", "Güncelleme hizmetine erişim kısıtlı, daha sonra deneyin", "Zugriff auf den Update-Dienst eingeschränkt, versuche es später"))
                    in 300..399 -> return UpdateResult.Failure(Tr.tr("更新服务发生跳转，已停止检查", "Il servizio aggiornamenti ha effettuato un reindirizzamento; controllo interrotto", "The update service redirected; check stopped", "El servicio de actualizaciones redirigió; comprobación detenida", "Le service de mise à jour a effectué une redirection ; vérification arrêtée", "O serviço de atualizações redirecionou; verificação parada", "O serviço de atualizações redirecionou; verificação interrompida", "Услугата за актуализации пренасочи; проверката е спряна", "Güncelleme hizmeti yönlendirme yaptı; denetim durduruldu", "Der Update-Dienst hat umgeleitet; Prüfung gestoppt"))
                    in 500..599 -> return UpdateResult.Failure(Tr.tr("更新服务暂时不可用，请稍后重试", "Servizio aggiornamenti temporaneamente non disponibile, riprova più tardi", "Update service temporarily unavailable, try again later", "Servicio de actualizaciones no disponible temporalmente, inténtalo más tarde", "Service de mise à jour temporairement indisponible, réessayez plus tard", "Serviço de atualizações temporariamente indisponível, tente mais tarde", "Serviço de atualizações temporariamente indisponível, tente mais tarde", "Услугата за актуализации е временно недостъпна, опитайте по-късно", "Güncelleme hizmeti geçici olarak kullanılamıyor, daha sonra deneyin", "Update-Dienst vorübergehend nicht verfügbar, versuche es später"))
                    200 -> Unit
                    else -> return UpdateResult.Failure(Tr.tr("检查更新失败（HTTP $status），请稍后重试", "Controllo aggiornamenti non riuscito (HTTP $status), riprova più tardi", "Update check failed (HTTP $status), try again later", "Error al comprobar actualizaciones (HTTP $status), inténtalo más tarde", "Échec de la vérification des mises à jour (HTTP $status), réessayez plus tard", "Falha na verificação de atualizações (HTTP $status), tente mais tarde", "Falha na verificação de atualizações (HTTP $status), tente mais tarde", "Проверката за актуализации не бе успешна (HTTP $status), опитайте по-късно", "Güncelleme denetimi başarısız (HTTP $status), daha sonra deneyin", "Update-Prüfung fehlgeschlagen (HTTP $status), versuche es später"))
                }
                val encoding = opened.contentEncoding
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                    return UpdateResult.Failure(Tr.tr("更新服务返回了不支持的响应格式", "Il servizio aggiornamenti ha restituito un formato di risposta non supportato", "The update service returned an unsupported response format", "El servicio de actualizaciones devolvió un formato no compatible", "Le service de mise à jour a renvoyé un format de réponse non pris en charge", "O serviço de atualizações devolveu um formato não suportado", "O serviço de atualizações retornou um formato não suportado", "Услугата за актуализации върна неподдържан формат", "Güncelleme hizmeti desteklenmeyen bir yanıt biçimi döndürdü", "Der Update-Dienst hat ein nicht unterstütztes Antwortformat geliefert"))
                }
                if (opened.contentLengthLong > UpdateReleaseParser.MAX_RESPONSE_BYTES) {
                    return UpdateResult.Failure(Tr.tr("更新响应过大，已停止检查", "Risposta del servizio aggiornamenti troppo grande; controllo interrotto", "Update service response too large; check stopped", "Respuesta del servicio de actualizaciones demasiado grande; comprobación detenida", "Réponse du service de mise à jour trop volumineuse ; vérification arrêtée", "Resposta do serviço de atualizações demasiado grande; verificação parada", "Resposta do serviço de atualizações grande demais; verificação interrompida", "Отговорът на услугата за актуализации е твърде голям; проверката е спряна", "Güncelleme hizmetinin yanıtı çok büyük; denetim durduruldu", "Antwort des Update-Dienstes zu groß; Prüfung gestoppt"))
                }
                opened.readTimeout = remainingMillis()
                val body = opened.inputStream.use { input ->
                    activeInput.set(input)
                    try {
                        readLimited(opened, input)
                    } finally {
                        activeInput.compareAndSet(input, null)
                    }
                }
                remainingMillis()
                UpdateReleaseParser.parse(body)
            } catch (_: SocketTimeoutException) {
                UpdateResult.Failure(Tr.tr("检查更新超时，请稍后重试", "Timeout del controllo aggiornamenti, riprova più tardi", "Update check timed out, try again later", "Tiempo agotado al comprobar actualizaciones, inténtalo más tarde", "Délai dépassé lors de la vérification des mises à jour, réessayez plus tard", "Tempo esgotado na verificação de atualizações, tente mais tarde", "Tempo esgotado na verificação de atualizações, tente mais tarde", "Времето за проверка на актуализации изтече, опитайте по-късно", "Güncelleme denetimi zaman aşımına uğradı, daha sonra deneyin", "Zeitüberschreitung bei der Update-Prüfung, versuche es später"))
            } catch (_: ResponseTooLargeException) {
                UpdateResult.Failure(Tr.tr("更新响应过大，已停止检查", "Risposta del servizio aggiornamenti troppo grande; controllo interrotto", "Update service response too large; check stopped", "Respuesta del servicio de actualizaciones demasiado grande; comprobación detenida", "Réponse du service de mise à jour trop volumineuse ; vérification arrêtée", "Resposta do serviço de atualizações demasiado grande; verificação parada", "Resposta do serviço de atualizações grande demais; verificação interrompida", "Отговорът на услугата за актуализации е твърде голям; проверката е спряна", "Güncelleme hizmetinin yanıtı çok büyük; denetim durduruldu", "Antwort des Update-Dienstes zu groß; Prüfung gestoppt"))
            } catch (_: SSLException) {
                UpdateResult.Failure(Tr.tr("更新服务的安全连接失败，请稍后重试", "Connessione sicura al servizio aggiornamenti non riuscita, riprova più tardi", "Secure connection to the update service failed, try again later", "Falló la conexión segura con el servicio de actualizaciones, inténtalo más tarde", "Échec de la connexion sécurisée au service de mise à jour, réessayez plus tard", "Falhou a ligação segura ao serviço de atualizações, tente mais tarde", "Falhou a conexão segura com o serviço de atualizações, tente mais tarde", "Сигурната връзка с услугата за актуализации не бе успешна, опитайте по-късно", "Güncelleme hizmetiyle güvenli bağlantı kurulamadı, daha sonra deneyin", "Sichere Verbindung zum Update-Dienst fehlgeschlagen, versuche es später"))
            } catch (_: IOException) {
                UpdateResult.Failure(Tr.tr("检查更新失败，请检查网络后重试", "Controllo aggiornamenti non riuscito, verifica la rete e riprova", "Update check failed, check your network and try again", "Error al comprobar actualizaciones, revisa la red y reintenta", "Échec de la vérification des mises à jour, vérifiez le réseau et réessayez", "Falha na verificação de atualizações, verifique a rede e tente de novo", "Falha na verificação de atualizações, verifique a rede e tente de novo", "Проверката за актуализации не бе успешна, проверете мрежата и опитайте пак", "Güncelleme denetimi başarısız, ağı kontrol edip tekrar deneyin", "Update-Prüfung fehlgeschlagen, prüfe das Netzwerk und versuche es erneut"))
            } catch (_: SecurityException) {
                UpdateResult.Failure(Tr.tr("无法联网检查更新，请稍后重试", "Impossibile connettersi a Internet per controllare gli aggiornamenti, riprova più tardi", "Can't reach the internet to check for updates, try again later", "Sin acceso a internet para comprobar actualizaciones, inténtalo más tarde", "Pas d'accès à internet pour vérifier les mises à jour, réessayez plus tard", "Sem acesso à internet para verificar atualizações, tente mais tarde", "Sem acesso à internet para verificar atualizações, tente mais tarde", "Няма интернет за проверка на актуализации, опитайте по-късно", "Güncellemeleri denetlemek için internete erişilemiyor, daha sonra deneyin", "Kein Internet für die Update-Prüfung, versuche es später"))
            } catch (_: Exception) {
                UpdateResult.Failure(Tr.tr("检查更新失败，请稍后重试", "Controllo aggiornamenti non riuscito, riprova più tardi", "Update check failed, try again later", "Error al comprobar actualizaciones, inténtalo más tarde", "Échec de la vérification des mises à jour, réessayez plus tard", "Falha na verificação de atualizações, tente mais tarde", "Falha na verificação de atualizações, tente mais tarde", "Проверката за актуализации не бе успешна, опитайте по-късно", "Güncelleme denetimi başarısız, daha sonra deneyin", "Update-Prüfung fehlgeschlagen, versuche es später"))
            } finally {
                try {
                    closeQuietly(connection?.errorStream)
                } catch (_: Exception) {
                    // 错误响应也必须回收，清理失败不覆盖原始结果。
                }
                disconnectQuietly(connection)
                activeConnection.compareAndSet(connection, null)
            }
        }

        private fun readLimited(connection: HttpsURLConnection, input: InputStream): ByteArray {
            val limit = UpdateReleaseParser.MAX_RESPONSE_BYTES
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                connection.readTimeout = remainingMillis()
                // 最多额外读取一个字节来判断超限，不能无界读完响应。
                val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
                remainingMillis()
                if (count == -1) break
                if (count == 0) continue
                total += count
                if (total > limit) throw ResponseTooLargeException()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        fun cancel() {
            cancelled.set(true)
            val connection = activeConnection.getAndSet(null)
            val input = activeInput.getAndSet(null)
            if (connection == null && input == null) return
            // 部分平台的流关闭可能阻塞，连接回收不能拖延十五秒的返回时限。
            Thread({
                disconnectQuietly(connection)
                closeQuietly(input)
            }, Tr.tr("腕镜更新连接回收", "Chiusura connessione aggiornamenti WristMirror", "Closing WristMirror update connection", "Cierre de la conexión de actualizaciones de WristMirror", "Fermeture de la connexion de mise à jour de WristMirror", "Fecho da ligação de atualizações do WristMirror", "Encerramento da conexão de atualizações do WristMirror", "Затваряне на връзката за актуализации на WristMirror", "WristMirror güncelleme bağlantısı kapatılıyor", "WristMirror-Update-Verbindung wird geschlossen")).apply { isDaemon = true }.start()
        }
    }

    private class ResponseTooLargeException : IOException()

    private fun closeQuietly(input: InputStream?) {
        try {
            input?.close()
        } catch (_: Exception) {
            // 清理异常不覆盖检查结果。
        }
    }

    private fun disconnectQuietly(connection: HttpsURLConnection?) {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
            // 清理异常不覆盖检查结果。
        }
    }
}
