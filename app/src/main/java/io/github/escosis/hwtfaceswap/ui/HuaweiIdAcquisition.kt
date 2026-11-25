package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebActivity
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebPolicy
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** 仅保存非敏感的获取进度；ID 只经回调进入当前设备草稿。 */
internal class HuaweiIdAcquisition(private val context: Context) {
    var busy by mutableStateOf(false)
        internal set
    var start: () -> Unit = {}
        internal set
    private var feedbackToast: Toast? = null

    fun notify(message: String) {
        cancelFeedback()
        feedbackToast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    fun cancelFeedback() {
        feedbackToast?.cancel()
        feedbackToast = null
    }
}

private class HuaweiIdRequest(val token: String) {
    var launched = false
    var handlingResult = false
    var job: Job? = null
}

private class HuaweiIdOwner {
    var alive = true
    var request by mutableStateOf<HuaweiIdRequest?>(null)
}

/** ownerKey 必须同时包含窗口世代与设备地址；不跨窗口保存请求或明文 ID。 */
@Composable
internal fun rememberHuaweiIdAcquisition(
    ownerKey: String,
    enabled: Boolean,
    isCurrent: () -> Boolean,
    onAcquired: (String) -> Unit,
): HuaweiIdAcquisition = key(ownerKey) {
    val context = LocalContext.current
    val cleanupContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val owner = remember { HuaweiIdOwner() }
    val acquisition = remember { HuaweiIdAcquisition(cleanupContext) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val latestOnAcquired by rememberUpdatedState(onAcquired)
    val currentAtComposition = isCurrent()

    fun owns(request: HuaweiIdRequest): Boolean = owner.alive && owner.request === request
    fun editable(): Boolean = latestEnabled && latestIsCurrent()

    fun abandonRequest() {
        val request = owner.request ?: return
        // 先撤销资格，再取消协程；晚到结果不能回填或释放新请求的保留权。
        owner.request = null
        acquisition.busy = false
        request.job?.cancel()
        HuaweiIdWebSession.abandon(cleanupContext, request.token, request.launched)
    }

    DisposableEffect(owner) {
        onDispose {
            owner.alive = false
            abandonRequest()
            acquisition.cancelFeedback()
        }
    }
    // show/canEdit 或当前设备资格撤销时立即放弃，包含退出动画仍挂载的窗口。
    DisposableEffect(owner, enabled, currentAtComposition) {
        onDispose { abandonRequest() }
    }

    val launchedRequest = owner.request
    // 每次重试也重新注册：无 token 的旧系统取消结果不能误清理下一次登录。
    val launcher = key(launchedRequest?.token) {
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val request = launchedRequest
            if (request != null && owns(request) && request.launched && !request.handlingResult) {
                request.handlingResult = true
                acquisition.notify(Tr.tr("正在清理登录数据…", "Pulizia dei dati di accesso…", "Clearing login data…", "Borrando los datos de inicio de sesión…", "Effacement des données de connexion…", "A apagar os dados de início de sessão…", "Apagando os dados de login…", "Изтриване на данните за вход…", "Giriş verileri temizleniyor…", "Anmeldedaten werden gelöscht…"))
                request.job = scope.launch {
                    var cleaned = false
                    try {
                        // 成功、取消、错误结果均须先等子进程退出并验证目录清空。
                        cleaned = HuaweiIdWebSession.clearAfterExit(cleanupContext)
                        if (!owns(request)) return@launch
                        if (!cleaned) {
                            acquisition.notify(Tr.tr("登录数据未确认清理完成，本次不会填入 ID，请稍后重试。", "Pulizia dei dati di accesso non confermata; l'ID non verrà inserito questa volta, riprova più tardi.", "Login data cleanup not confirmed; the ID won't be filled in this time, try again later.", "Limpieza de los datos de inicio de sesión sin confirmar; esta vez no se rellenará el ID, inténtalo más tarde.", "Effacement des données de connexion non confirmé ; l'ID ne sera pas rempli cette fois, réessayez plus tard.", "Limpeza dos dados de início de sessão não confirmada; desta vez o ID não será preenchido, tente mais tarde.", "Limpeza dos dados de login não confirmada; desta vez o ID não será preenchido, tente mais tarde.", "Изтриването на данните за вход не е потвърдено; този път ID няма да се попълни, опитайте по-късно.", "Giriş verilerinin temizliği doğrulanmadı; ID bu kez doldurulmayacak, daha sonra deneyin.", "Löschen der Anmeldedaten nicht bestätigt; die ID wird diesmal nicht eingetragen, versuche es später."))
                            return@launch
                        }
                        if (!editable()) return@launch
                        val data = result.data
                        val sameRequest = data?.getStringExtra(HuaweiIdWebActivity.EXTRA_REQUEST) == request.token
                        val id = if (result.resultCode == Activity.RESULT_OK && sameRequest) {
                            data?.getStringExtra(HuaweiIdWebActivity.EXTRA_ID)
                        } else null
                        when {
                            result.resultCode == Activity.RESULT_OK && sameRequest && HuaweiIdWebPolicy.validId(id) && id != null -> {
                                // 回调仍只修改草稿；保存和连接由原有按钮单独执行。
                                if (owns(request) && editable()) {
                                    latestOnAcquired(id)
                                    acquisition.notify(Tr.tr("已获取到华为 ID 并填入，请核对后保存。", "ID Huawei ottenuto e inserito; controllalo prima di salvare.", "Huawei ID retrieved and filled in; check it before saving.", "ID de Huawei obtenido y rellenado; revísalo antes de guardar.", "ID Huawei obtenu et rempli ; vérifiez-le avant d'enregistrer.", "ID Huawei obtido e preenchido; verifique-o antes de guardar.", "ID Huawei obtido e preenchido; confira antes de salvar.", "Huawei ID е получен и попълнен; проверете го, преди да запазите.", "Huawei ID alındı ve dolduruldu; kaydetmeden önce kontrol edin.", "Huawei-ID abgerufen und eingetragen; prüfe sie vor dem Speichern."))
                                }
                            }
                            result.resultCode == Activity.RESULT_CANCELED && sameRequest &&
                                data?.getStringExtra(HuaweiIdWebActivity.EXTRA_ERROR) == "CANCELLED" -> {
                                acquisition.notify(Tr.tr("已取消获取，登录数据已清理。", "Recupero annullato, dati di accesso cancellati.", "Retrieval cancelled, login data cleared.", "Obtención cancelada, datos de inicio de sesión borrados.", "Récupération annulée, données de connexion effacées.", "Obtenção cancelada, dados de início de sessão apagados.", "Obtenção cancelada, dados de login apagados.", "Получаването е отменено, данните за вход са изтрити.", "Alma iptal edildi, giriş verileri temizlendi.", "Abruf abgebrochen, Anmeldedaten gelöscht."))
                            }
                            else -> acquisition.notify(Tr.tr("未获取到有效的华为 ID，登录数据已清理，请重试或手动填写。", "Nessun ID Huawei valido ottenuto; dati di accesso cancellati. Riprova o inseriscilo manualmente.", "No valid Huawei ID retrieved; login data cleared. Try again or enter it manually.", "No se obtuvo un ID de Huawei válido; datos de inicio de sesión borrados. Inténtalo de nuevo o introdúcelo a mano.", "Aucun ID Huawei valide obtenu ; données de connexion effacées. Réessayez ou saisissez-le manuellement.", "Não foi obtido um ID Huawei válido; dados de início de sessão apagados. Tente de novo ou introduza-o manualmente.", "Não foi obtido um ID Huawei válido; dados de login apagados. Tente de novo ou insira manualmente.", "Не е получен валиден Huawei ID; данните за вход са изтрити. Опитайте пак или го въведете ръчно.", "Geçerli bir Huawei ID alınamadı; giriş verileri temizlendi. Tekrar deneyin veya elle girin.", "Keine gültige Huawei-ID abgerufen; Anmeldedaten gelöscht. Versuche es erneut oder gib sie manuell ein."))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (owns(request) && editable()) {
                            acquisition.notify(if (cleaned) {
                                Tr.tr("未获取到有效的华为 ID，请重试或手动填写。", "Nessun ID Huawei valido ottenuto; riprova o inseriscilo manualmente.", "No valid Huawei ID retrieved; try again or enter it manually.", "No se obtuvo un ID de Huawei válido; inténtalo de nuevo o introdúcelo a mano.", "Aucun ID Huawei valide obtenu ; réessayez ou saisissez-le manuellement.", "Não foi obtido um ID Huawei válido; tente de novo ou introduza-o manualmente.", "Não foi obtido um ID Huawei válido; tente de novo ou insira manualmente.", "Не е получен валиден Huawei ID; опитайте пак или го въведете ръчно.", "Geçerli bir Huawei ID alınamadı; tekrar deneyin veya elle girin.", "Keine gültige Huawei-ID abgerufen; versuche es erneut oder gib sie manuell ein.")
                            } else {
                                Tr.tr("登录数据未确认清理完成，本次不会填入 ID，请稍后重试。", "Pulizia dei dati di accesso non confermata; l'ID non verrà inserito questa volta, riprova più tardi.", "Login data cleanup not confirmed; the ID won't be filled in this time, try again later.", "Limpieza de los datos de inicio de sesión sin confirmar; esta vez no se rellenará el ID, inténtalo más tarde.", "Effacement des données de connexion non confirmé ; l'ID ne sera pas rempli cette fois, réessayez plus tard.", "Limpeza dos dados de início de sessão não confirmada; desta vez o ID não será preenchido, tente mais tarde.", "Limpeza dos dados de login não confirmada; desta vez o ID não será preenchido, tente mais tarde.", "Изтриването на данните за вход не е потвърдено; този път ID няма да се попълни, опитайте по-късно.", "Giriş verilerinin temizliği doğrulanmadı; ID bu kez doldurulmayacak, daha sonra deneyin.", "Löschen der Anmeldedaten nicht bestätigt; die ID wird diesmal nicht eingetragen, versuche es später.")
                            })
                        }
                    } finally {
                        if (owns(request)) {
                            owner.request = null
                            acquisition.busy = false
                            if (cleaned) HuaweiIdWebSession.release(request.token)
                            else HuaweiIdWebSession.abandon(cleanupContext, request.token, request.launched)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(launchedRequest) {
        val request = launchedRequest ?: return@LaunchedEffect
        if (!owns(request)) return@LaunchedEffect
        request.job = currentCoroutineContext()[Job]
        try {
            // 父实现负责 IO 与跨进程锁；此处不在主线程自行操作网站目录。
            val prepared = HuaweiIdWebSession.prepare(cleanupContext, request.token)
            if (!owns(request)) return@LaunchedEffect
            if (!editable()) {
                abandonRequest()
                return@LaunchedEffect
            }
            if (!prepared) {
                acquisition.notify(Tr.tr("无法准备隔离登录，请稍后重试或手动填写。", "Impossibile preparare l'accesso isolato; riprova più tardi o inseriscilo manualmente.", "Couldn't prepare the isolated login; try again later or enter it manually.", "No se pudo preparar el inicio de sesión aislado; inténtalo más tarde o introdúcelo a mano.", "Impossible de préparer la connexion isolée ; réessayez plus tard ou saisissez-le manuellement.", "Não foi possível preparar o início de sessão isolado; tente mais tarde ou introduza-o manualmente.", "Não foi possível preparar o login isolado; tente mais tarde ou insira manualmente.", "Изолираният вход не може да се подготви; опитайте по-късно или въведете ръчно.", "Yalıtılmış giriş hazırlanamadı; daha sonra deneyin veya elle girin.", "Isolierte Anmeldung konnte nicht vorbereitet werden; versuche es später oder gib sie manuell ein."))
                owner.request = null
                acquisition.busy = false
                HuaweiIdWebSession.release(request.token)
                return@LaunchedEffect
            }
            val intent = HuaweiIdWebActivity.launchIntent(context, request.token)
            // launch 抛异常也交给幂等 abandon 处理可能已启动的子页。
            request.launched = true
            acquisition.notify(Tr.tr("请自行登录，将自动获取华为 ID，关闭并清理网站数据。", "Accedi tu stesso: l'ID Huawei verrà recuperato automaticamente, poi la pagina verrà chiusa e i dati del sito cancellati.", "Sign in yourself: the Huawei ID will be retrieved automatically, then the page will close and the site data will be cleared.", "Inicia sesión tú mismo: el ID de Huawei se obtendrá automáticamente, luego la página se cerrará y se borrarán los datos del sitio.", "Connectez-vous vous-même : l'ID Huawei sera récupéré automatiquement, puis la page se fermera et les données du site seront effacées.", "Inicie sessão: o ID Huawei será obtido automaticamente, depois a página fecha-se e os dados do site são apagados.", "Faça login: o ID Huawei será obtido automaticamente, depois a página fecha e os dados do site são apagados.", "Влезте сами: Huawei ID ще се получи автоматично, после страницата ще се затвори и данните на сайта ще се изтрият.", "Kendiniz giriş yapın: Huawei ID otomatik olarak alınacak, ardından sayfa kapanacak ve site verileri silinecek.", "Melde dich selbst an: die Huawei-ID wird automatisch abgerufen, danach wird die Seite geschlossen und die Websitedaten werden gelöscht."))
            launcher.launch(intent)
        } catch (cancelled: CancellationException) {
            if (owns(request)) abandonRequest()
            throw cancelled
        } catch (_: Exception) {
            if (owns(request)) {
                acquisition.notify(Tr.tr("无法打开隔离登录，请稍后重试或手动填写。", "Impossibile aprire l'accesso isolato; riprova più tardi o inseriscilo manualmente.", "Couldn't open the isolated login; try again later or enter it manually.", "No se pudo abrir el inicio de sesión aislado; inténtalo más tarde o introdúcelo a mano.", "Impossible d'ouvrir la connexion isolée ; réessayez plus tard ou saisissez-le manuellement.", "Não foi possível abrir o início de sessão isolado; tente mais tarde ou introduza-o manualmente.", "Não foi possível abrir o login isolado; tente mais tarde ou insira manualmente.", "Изолираният вход не може да се отвори; опитайте по-късно или въведете ръчно.", "Yalıtılmış giriş açılamadı; daha sonra deneyin veya elle girin.", "Isolierte Anmeldung konnte nicht geöffnet werden; versuche es später oder gib sie manuell ein."))
                abandonRequest()
            }
        }
    }

    acquisition.start = start@{
        if (!owner.alive || !editable() || owner.request != null) return@start
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            acquisition.notify(Tr.tr("当前系统不支持隔离网页登录，请手动填写华为 ID", "Il sistema non supporta l'accesso web isolato; inserisci manualmente l'ID Huawei", "This system doesn't support isolated web login; enter the Huawei ID manually", "Este sistema no admite el inicio de sesión web aislado; introduce el ID de Huawei a mano", "Ce système ne prend pas en charge la connexion web isolée ; saisissez l'ID Huawei manuellement", "Este sistema não suporta o início de sessão web isolado; introduza o ID Huawei manualmente", "Este sistema não suporta o login web isolado; insira o ID Huawei manualmente", "Тази система не поддържа изолиран уеб вход; въведете Huawei ID ръчно", "Bu sistem yalıtılmış web girişini desteklemiyor; Huawei ID'yi elle girin", "Dieses System unterstützt keine isolierte Web-Anmeldung; gib die Huawei-ID manuell ein"))
            return@start
        }
        val request = HuaweiIdRequest(UUID.randomUUID().toString())
        if (!HuaweiIdWebSession.reserve(request.token)) {
            acquisition.notify(Tr.tr("已有登录获取正在进行，请稍后重试。", "Un recupero tramite accesso è già in corso, riprova più tardi.", "A login retrieval is already in progress, try again later.", "Ya hay una obtención por inicio de sesión en curso, inténtalo más tarde.", "Une récupération par connexion est déjà en cours, réessayez plus tard.", "Já há uma obtenção por início de sessão em curso, tente mais tarde.", "Já há uma obtenção por login em andamento, tente mais tarde.", "Вече тече получаване чрез вход, опитайте по-късно.", "Zaten süren bir giriş alma işlemi var, daha sonra deneyin.", "Ein Abruf per Anmeldung läuft bereits, versuche es später."))
            return@start
        }
        owner.request = request
        acquisition.busy = true
        acquisition.notify(Tr.tr("正在准备隔离登录…", "Preparazione dell'accesso isolato…", "Preparing isolated login…", "Preparando el inicio de sesión aislado…", "Préparation de la connexion isolée…", "A preparar o início de sessão isolado…", "Preparando o login isolado…", "Подготовка на изолирания вход…", "Yalıtılmış giriş hazırlanıyor…", "Isolierte Anmeldung wird vorbereitet…"))
    }
    acquisition
}
