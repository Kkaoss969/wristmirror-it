package io.github.escosis.hwtfaceswap

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import io.github.escosis.hwtfaceswap.protocol.ConnectionRecovery
import io.github.escosis.hwtfaceswap.protocol.Core
import io.github.escosis.hwtfaceswap.protocol.DirectInstallState
import io.github.escosis.hwtfaceswap.protocol.FacePolicy
import io.github.escosis.hwtfaceswap.protocol.HwtSource
import io.github.escosis.hwtfaceswap.protocol.PayloadKinds
import io.github.escosis.hwtfaceswap.protocol.TlvView
import io.github.escosis.hwtfaceswap.protocol.VersionPolicy
import io.github.escosis.hwtfaceswap.ui.OperationFailureText
import io.github.escosis.hwtfaceswap.ui.PayloadSelectionPolicy
import io.github.escosis.hwtfaceswap.store.SavedWatchStore
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalController
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal
import io.github.escosis.hwtfaceswap.transport.ConnectionService
import io.github.escosis.hwtfaceswap.transport.WatchScanner
import io.github.escosis.hwtfaceswap.transport.WatchfaceManager
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** 界面与运行时的适配层；设备写操作必须先消费一次可见确认令牌。 */
class WatchViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val app = application
    private val runtime = AppRuntime.get(application)
    private val scanner = WatchScanner(application)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val updateIo = Executors.newSingleThreadExecutor { task -> Thread(task, "wristmirror-updates").apply { isDaemon = true } }
    private val tokens = AtomicLong(System.nanoTime())
    private val prefs = app.getSharedPreferences("ui", Application.MODE_PRIVATE)
    private val savedWatches = SavedWatchStore(app)
    // Include install time so an update installed with the same version code also shows the guide once.
    private val introductionInstall = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "$code:${info.lastUpdateTime}"
    }.getOrDefault("unknown")
    private val introductionRead = IntroductionReadMarker(prefs,
        { IntroductionReadMarker.isCurrentInstallation(prefs.getString(IntroductionReadMarker.INSTALLATION_KEY, null), introductionInstall) },
        { prefs.edit().putBoolean(IntroductionReadMarker.KEY, true)
            .putString(IntroductionReadMarker.INSTALLATION_KEY, introductionInstall).commit() })
    private val mutable = MutableStateFlow(AppUiState(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        introductionPending = !introductionRead.isAcknowledged(),
    ))
    val state: StateFlow<AppUiState> = mutable
    private var closed = false
    private val startupConnectionOpportunity = StartupConnectionOpportunity()
    private var credentialEpoch = -1L
    private var savedEditorId = ""
    private var lastRuntimeMessage = ""
    private var appliedOperationRevision = -1L
    private var appliedReplacementDialogRevision = -1L
    private var appliedDeletionDialogRevision = -1L
    private var appliedActivationDialogRevision = -1L
    private var removalRevision = -1L
    private var removalToken: Long? = null
    private var removalUiAddress: String? = null
    private var scanGeneration = 0L
    private var filePickToken: Long? = null
    private var source: HwtSource? = null
    private var sourcePayloads: Map<HwtSource.Mode, HwtSource.Snapshot> = emptyMap()
    private var chosenPayloadKind: String? = null
    private var fileOnlyPreview = false
    private var startingOperation: QueuedStart? = null
    private var preparing = false
    private var pending: Pending? = null
    private var deletionPreparation: DeletionPreparation? = null
    // 名称与展开状态不是设备身份；只绑定点击时那张卡的原始四字段和连接/表单/操作上下文。
    private data class RowTarget(val rendered: FaceItem, val face: TlvView.Face, val epoch: Long,
        val address: String, val formRevision: Long, val operationRevision: Long)
    private data class Pick(val row: RowTarget, val version: String) {
        val face: TlvView.Face get() = row.face
        val epoch: Long get() = row.epoch
    }
    private data class DeletionPreparation(val token: Long, val row: RowTarget)
    private data class QueuedStart(val ticket: Long, val kind: String, val epoch: Long, val operationRevision: Long)
    private data class Pending(val token: Long, val action: () -> Unit, val cancel: () -> Unit)
    private companion object {
        // 界面提供的连接元数据只共享给运行时身份/历史观察者，不保存账户或密钥材料。
        // 新界面模型可恢复进行中的设备连接，无需持久保存候选密钥。
        @Volatile private var sessionDevice: NearbyWatch? = null
        @Volatile private var queuedConnectEpoch: Long? = null
        @Volatile private var rememberedReadyEpoch = -1L
        private var agreementSkippedEpoch = -1L
        private var startupUpdateTaken = false
        private var historyObserver: AppRuntime.Observer? = null
        private fun successfulHistory(runtime: AppRuntime, store: SavedWatchStore, s: AppRuntime.Snapshot): List<SavedWatch>? {
            if(s.epoch != runtime.connection.epoch()) return null
            if(queuedConnectEpoch != null && queuedConnectEpoch != s.epoch) queuedConnectEpoch = null
            if(s.code != "READY" || !s.ready || !runtime.connection.ready() || queuedConnectEpoch != null) return null
            val current = runtime.snapshot()
            val address = SavedWatchStore.canonicalAddress(runtime.connection.address()) ?: return null
            if(runtime.unpair.blocksAddress(address) || runtime.secure.removals().blocked(address)) return null
            if(current.epoch != s.epoch || current.code != "READY" || !current.ready || runtime.connection.epoch() != s.epoch ||
                !s.deviceAddress.equals(address, true) || !current.deviceAddress.equals(address, true)) return null
            val journal = runtime.secure.removals()
            val generation = journal.generation(address)
            val device = sessionDevice?.takeIf { it.address.equals(address, true) }
                ?: store.devices().firstOrNull { it.address.equals(address, true) }?.nearby() ?: return null
            // 快照在锁外读取，避免 runtime 发布锁与 journal 锁倒序；锁内再次核实代次、MAC 和 READY。
            return synchronized(DeviceRemovalJournal.LOCK) {
                if(!journal.usable(address, generation) || runtime.unpair.blocksAddress(address) ||
                    !runtime.connection.address().equals(address, true) || !runtime.connection.ready() || runtime.connection.epoch() != s.epoch) return@synchronized null
                if(rememberedReadyEpoch != s.epoch) store.remember(device).also { rememberedReadyEpoch = s.epoch }
                // 进程级观察者在首个 READY 后仍接收 01/07；只补首个身份字段，不重复 remember 或改变历史顺序。
                store.rememberModel(address, current.deviceModel, generation)
                store.rememberSerial(address, current.serialNumber, generation)
            }
        }
        private fun observeSuccessfulDevices(runtime: AppRuntime, application: Application) {
            if(historyObserver != null) return
            // 此观察者随进程运行时和前台服务存活，不依附界面模型。
            // 不捕获活动页面、编辑值、凭据或界面文件执行器。
            val store = SavedWatchStore(application.applicationContext)
            val handler = Handler(Looper.getMainLooper())
            val observer = object : AppRuntime.Observer {
                private var identityEpoch = -1L
                private var identityModel: String? = null
                private var identitySerial: String? = null
                override fun changed(snapshot: AppRuntime.Snapshot) {
                    // 只在首个 READY/型号/序列号回调返回、界面可见之前同步保存；传输进度不在 actor 重读历史。
                    // 此小锁只去重，读取运行时快照和 journal 之前已释放；紧接断开也不丢失已取得身份字段。
                    val newIdentity = synchronized(this) {
                        if(snapshot.code != "READY" || !snapshot.ready ||
                            identityEpoch == snapshot.epoch && identityModel == snapshot.deviceModel && identitySerial == snapshot.serialNumber) false
                        else { identityEpoch = snapshot.epoch; identityModel = snapshot.deviceModel; identitySerial = snapshot.serialNumber; true }
                    }
                    if(newIdentity) runCatching { successfulHistory(runtime, store, snapshot) }
                        .onFailure { runtime.logs.record("DEVICE_HISTORY_ERROR", "code=${Core.code(it)}") }
                    handler.post { runCatching { successfulHistory(runtime, store, snapshot) }
                        .onFailure { runtime.logs.record("DEVICE_HISTORY_ERROR", "code=${Core.code(it)}") } }
                }
                override fun rebindRequested(message: String) {}
                override fun deletionPrepared(face: TlvView.Face, epoch: Long) {}
            }
            historyObserver = observer
            runtime.addObserver(observer)
        }
    }
    private val observer = object : AppRuntime.Observer {
        override fun changed(snapshot: AppRuntime.Snapshot) { main.post { if (!closed) apply(snapshot) } }
        override fun rebindRequested(message: String) {
            val requestedEpoch = runtime.connection.epoch()
            main.post {
                val current = runtime.snapshot()
                if (!closed && !removalBlocks(runtime.connection.address()) && requestedEpoch == runtime.connection.epoch() && current.epoch == requestedEpoch && current.code == "REBIND_CONFIRM")
                    prompt("rebind", Tr.tr("重新协议绑定？", "Riassociare il protocollo?", "Re-bind the protocol?", "¿Volver a vincular el protocolo?", "Réassocier le protocole ?", "Voltar a associar o protocolo?", "Vincular o protocolo de novo?", "Ново обвързване на протокола?", "Protokol yeniden bağlansın mı?", "Protokoll neu binden?", "Заново привязать протокол?"), Tr.tr("重新绑定可能改变手表与其他应用的连接关系，是否继续？", "La riassociazione potrebbe cambiare il modo in cui il watch si collega ad altre app. Continuare?", "Re-binding may change how the watch connects to other apps. Continue?", "Volver a vincular puede cambiar cómo se conecta el reloj con otras apps. ¿Continuar?", "La réassociation peut changer la façon dont la montre se connecte aux autres apps. Continuer ?", "Voltar a associar pode mudar a forma como o relógio se liga a outras apps. Continuar?", "Vincular de novo pode mudar a forma como o relógio se conecta a outros apps. Continuar?", "Новото обвързване може да промени начина, по който часовникът се свързва с други приложения. Продължаване?", "Yeniden bağlama, saatin diğer uygulamalara bağlanma şeklini değiştirebilir. Devam edilsin mi?", "Das Neubinden kann ändern, wie sich die Uhr mit anderen Apps verbindet. Fortfahren?", "Повторная привязка может изменить подключение часов к другим приложениям. Продолжить?"), true, Tr.tr("确认重新绑定", "Conferma riassociazione", "Confirm re-binding", "Confirmar nueva vinculación", "Confirmer la réassociation", "Confirmar nova associação", "Confirmar novo vínculo", "Потвърждаване на новото обвързване", "Yeniden bağlamayı onayla", "Neubinden bestätigen", "Подтвердить повторную привязку"),
                        { runtime.connection.confirmRebind(requestedEpoch) }, { runtime.connection.disconnectIfEpoch(requestedEpoch) })
            }
        }
        // 匿名旧回调没有请求身份，不得打开逐卡确认；新入口使用请求私有的 Manager 回调。
        override fun deletionPrepared(face: TlvView.Face, epoch: Long) {}
    }
    private val removalObserver = DeviceRemovalController.Listener { snapshot ->
        main.post { if(!closed) applyRemoval(snapshot) }
    }
    init {
        observeSuccessfulDevices(runtime, app)
        applyRemoval(runtime.unpair.snapshot())
        val history = runCatching { savedWatches.devices() }.getOrElse { message(Tr.tr("无法读取设备记录：${Core.code(it)}", "Impossibile leggere i dati del dispositivo: ${Core.code(it)}", "Couldn't read device data: ${Core.code(it)}", "No se pudieron leer los datos del dispositivo: ${Core.code(it)}", "Impossible de lire les données de l'appareil : ${Core.code(it)}", "Não foi possível ler os dados do dispositivo: ${Core.code(it)}", "Não foi possível ler os dados do dispositivo: ${Core.code(it)}", "Данните на устройството не могат да се прочетат: ${Core.code(it)}", "Cihaz verileri okunamadı: ${Core.code(it)}", "Gerätedaten konnten nicht gelesen werden: ${Core.code(it)}", "Не удалось прочитать данные устройства: ${Core.code(it)}")); emptyList() }
        val autoConnect = runCatching { savedWatches.autoConnect() }.getOrElse { message(Tr.tr("无法读取自动连接设置：${Core.code(it)}", "Impossibile leggere l'impostazione di connessione automatica: ${Core.code(it)}", "Couldn't read the auto-connect setting: ${Core.code(it)}", "No se pudo leer el ajuste de conexión automática: ${Core.code(it)}", "Impossible de lire le paramètre de connexion automatique : ${Core.code(it)}", "Não foi possível ler a definição de ligação automática: ${Core.code(it)}", "Não foi possível ler a configuração de conexão automática: ${Core.code(it)}", "Настройката за автоматично свързване не може да се прочете: ${Core.code(it)}", "Otomatik bağlanma ayarı okunamadı: ${Core.code(it)}", "Die Einstellung für automatisches Verbinden konnte nicht gelesen werden: ${Core.code(it)}", "Не удалось прочитать настройку автоподключения: ${Core.code(it)}")); false }
        val autoCheck = prefs.getBoolean("auto_check_update", true)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0.1"
        mutable.update { it.copy(savedDevices = history, autoConnect = autoConnect, autoCheckUpdate = autoCheck, appVersion = version) }
        val initial = runtime.snapshot()
        if (queuedConnectEpoch != null && queuedConnectEpoch != initial.epoch) queuedConnectEpoch = null
        val activeAddress = if (queuedConnectEpoch != null) sessionDevice?.address
            else if (initial.ready || initial.connecting || initial.active || initial.code == "FAILED") runtime.connection.address() else null
        // 未有活动连接时回到最后成功设备，只读取该设备自己的账户配置。
        val initialAddress = activeAddress?.let(SavedWatchStore::canonicalAddress)?.takeUnless(::removalBlocks)
            ?: history.firstOrNull { !removalBlocks(it.address) }?.address
        if(initialAddress != null) {
            val device = deviceMetadata(initialAddress)
            mutable.update { it.copy(selectedDeviceAddress = initialAddress, watchName = device?.name ?: Tr.tr("华为手表", "Watch Huawei", "Huawei watch", "Reloj Huawei", "Montre Huawei", "Relógio Huawei", "Relógio Huawei", "Часовник Huawei", "Huawei saat", "Huawei-Uhr", "Часы Huawei")) }
        }
        restoreHuaweiId(initialAddress)
        // 先同步读取实际快照，再允许活动页面消费启动连接机会。
        apply(initial)
        runtime.logs.setListener { lines -> main.post { if (!closed) mutable.update { it.copy(logs = lines.toList()) } } }
        runtime.addObserver(observer)
        runtime.unpair.addListener(removalObserver)
    }
    private fun removalBlocks(address: String?): Boolean =
        address != null && (UiPolicy.isUnpairTarget(mutable.value, address) || runtime.unpair.blocksAddress(address) || runtime.secure.removals().blocked(address))
    private fun removalWorking(): Boolean = runtime.unpair.snapshot().working
    private fun retireRemovalUi(address: String, token: Long) {
        if(closed || (removalToken == token && removalUiAddress == address)) return
        removalToken = token; removalUiAddress = address
        // 按确认捕获的 MAC/token 幂等退休；不依赖必须观察到 pending 快照。
        // actor 可能在 request 返回后、界面首次读取 snapshot 前已完成全部清理。
        invalidatePrompt(); filePickToken = null; scanGeneration++; scanner.stop()
        val sessionMatches = sessionDevice?.address?.equals(address, true) == true
        if(sessionMatches) { sessionDevice = null; queuedConnectEpoch = null; rememberedReadyEpoch = -1L; agreementSkippedEpoch = -1L }
        val currentMatches = runtime.connection.address().equals(address, true) || mutable.value.deviceAddress.equals(address, true)
        if(currentMatches) credentialEpoch = -1L
        mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L, pickingHwt = false, scanning = false,
            needsPin = if(currentMatches) false else it.needsPin,
            selectedId = null) }
    }
    private fun applyRemoval(s: DeviceRemovalController.Snapshot) {
        if(closed || s.revision != runtime.unpair.snapshot().revision || s.revision <= removalRevision) return
        removalRevision = s.revision
        val address = s.address
        if(s.pending && address != null) retireRemovalUi(address, s.token)
        if(s.success && address != null) {
            val selectedMatches = mutable.value.selectedDeviceAddress?.equals(address, true) == true
            if(selectedMatches) { savedEditorId = ""; credentialEpoch = -1L }
            if(sessionDevice?.address?.equals(address, true) == true) { sessionDevice = null; queuedConnectEpoch = null; rememberedReadyEpoch = -1L }
            val history = runCatching { savedWatches.devices() }.getOrElse { mutable.value.savedDevices.filterNot { watch -> watch.address.equals(address, true) } }
            val otherConnection = runtime.connection.address().takeIf { it.isNotBlank() && !it.equals(address, true) }?.let(::deviceMetadata)
            mutable.update { old -> old.copy(savedDevices = history, devices = old.devices.filterNot { it.address.equals(address, true) },
                selectedDeviceAddress = if(selectedMatches) null else old.selectedDeviceAddress,
                huaweiIdInput = if(selectedMatches) "" else old.huaweiIdInput,
                accountConfigured = if(selectedMatches) false else old.accountConfigured,
                huaweiIdDirty = if(selectedMatches) false else old.huaweiIdDirty,
                watchName = if(selectedMatches) otherConnection?.name ?: "" else old.watchName,
                connectionFormRevision = old.connectionFormRevision + if(selectedMatches) 1L else 0L) }
        }
        mutable.update { it.copy(unpairing = s.pending, unpairWorking = s.working, unpairToken = s.token, unpairAddress = address,
            unpairMessage = removalUiMessage(s), unpairNeedsSettings = s.needsSettings, unpairCancelable = runtime.unpair.cancelable()) }
    }
    // 只缩短界面说明；Controller 的原始原因、日志与状态判据保持不变。
    private fun removalUiMessage(s: DeviceRemovalController.Snapshot): String {
        if(s.message.isBlank()) return ""
        if(s.success) return Tr.tr("配对已解除，设备资料已清除", "Abbinamento annullato, dati del dispositivo cancellati", "Pairing removed, device data cleared", "Emparejamiento eliminado, datos del dispositivo borrados", "Association supprimée, données de l'appareil effacées", "Emparelhamento removido, dados do dispositivo apagados", "Pareamento removido, dados do dispositivo apagados", "Сдвояването е премахнато, данните на устройството са изтрити", "Eşleştirme kaldırıldı, cihaz verileri silindi", "Kopplung entfernt, Gerätedaten gelöscht", "Сопряжение снято, данные устройства удалены")
        val detail = s.message
        return when {
            detail.startsWith(Tr.tr("无法读取待处理解除配对标记", "Impossibile leggere il contrassegno di annullamento abbinamento in sospeso", "Couldn't read the pending unpair flag", "No se pudo leer la marca de desemparejamiento pendiente", "Impossible de lire l'indicateur de dissociation en attente", "Não foi possível ler a marca de desemparelhamento pendente", "Não foi possível ler a marca de despareamento pendente", "Не може да се прочете отметката за чакащо разкачване", "Bekleyen eşleştirme kaldırma işareti okunamadı", "Die Markierung für die ausstehende Entkopplung konnte nicht gelesen werden", "Не удалось прочитать отметку ожидающего снятия сопряжения")) -> Tr.tr("无法读取待处理状态，请重新打开应用。", "Impossibile leggere lo stato in sospeso, riapri l'app.", "Couldn't read the pending state, reopen the app.", "No se pudo leer el estado pendiente, vuelve a abrir la app.", "Impossible de lire l'état en attente, rouvrez l'app.", "Não foi possível ler o estado pendente, volte a abrir a app.", "Não foi possível ler o status pendente, abra o app de novo.", "Чакащото състояние не може да се прочете, отворете приложението отново.", "Bekleyen durum okunamadı, uygulamayı yeniden açın.", "Der ausstehende Status konnte nicht gelesen werden, öffne die App erneut.", "Не удалось прочитать ожидающее состояние, откройте приложение заново.")
            detail.startsWith(Tr.tr("系统蓝牙未开启", "Bluetooth di sistema non attivo", "System Bluetooth is off", "Bluetooth del sistema desactivado", "Bluetooth système désactivé", "Bluetooth do sistema desligado", "Bluetooth do sistema desligado", "Системният Bluetooth е изключен", "Sistem Bluetooth'u kapalı", "System-Bluetooth ist aus", "Системный Bluetooth выключен")) -> Tr.tr("蓝牙已关闭，请开启后重新检查。", "Il Bluetooth è disattivato, attivalo e ricontrolla.", "Bluetooth is off, turn it on and check again.", "El Bluetooth está desactivado, actívalo y vuelve a comprobar.", "Le Bluetooth est désactivé, activez-le et vérifiez à nouveau.", "O Bluetooth está desligado, ligue-o e verifique de novo.", "O Bluetooth está desligado, ligue-o e verifique de novo.", "Bluetooth е изключен, включете го и проверете пак.", "Bluetooth kapalı, açıp tekrar kontrol edin.", "Bluetooth ist aus, schalte es ein und prüfe erneut.", "Bluetooth выключен, включите его и проверьте снова.")
            detail.startsWith(Tr.tr("附近设备连接权限不可用", "Autorizzazione Dispositivi nelle vicinanze non disponibile", "Nearby devices permission unavailable", "Permiso de Dispositivos cercanos no disponible", "Autorisation Appareils à proximité indisponible", "Permissão Dispositivos próximos indisponível", "Permissão Dispositivos por perto indisponível", "Разрешението „Устройства наблизо“ не е налично", "Yakındaki cihazlar izni yok", "Berechtigung „Geräte in der Nähe“ nicht verfügbar", "Разрешение «Устройства поблизости» недоступно")) -> Tr.tr("请在系统应用权限中授予附近设备权限后重新检查。", "Concedi l'autorizzazione Dispositivi nelle vicinanze nelle impostazioni dell'app, poi ricontrolla.", "Grant the Nearby devices permission in the app settings, then check again.", "Concede el permiso de Dispositivos cercanos en los ajustes de la app y vuelve a comprobar.", "Accordez l'autorisation Appareils à proximité dans les paramètres de l'app, puis vérifiez à nouveau.", "Conceda a permissão Dispositivos próximos nas definições da app e verifique de novo.", "Conceda a permissão Dispositivos por perto nas configurações do app e verifique de novo.", "Дайте разрешението „Устройства наблизо“ в настройките на приложението и проверете пак.", "Uygulama ayarlarından Yakındaki cihazlar iznini verin ve tekrar kontrol edin.", "Erteile die Berechtigung „Geräte in der Nähe“ in den App-Einstellungen und prüfe erneut.", "Выдайте разрешение «Устройства поблизости» в настройках приложения и проверьте снова.")
            detail.startsWith(Tr.tr("无法可靠读取此 MAC", "Impossibile leggere in modo affidabile questo MAC", "Couldn't reliably read this MAC", "No se pudo leer de forma fiable esta MAC", "Impossible de lire de façon fiable cette adresse MAC", "Não foi possível ler de forma fiável este MAC", "Não foi possível ler de forma confiável este MAC", "Този MAC не може да се прочете надеждно", "Bu MAC güvenilir şekilde okunamadı", "Diese MAC konnte nicht zuverlässig gelesen werden", "Не удалось надёжно прочитать этот MAC")) -> Tr.tr("无法确认配对状态，请重新检查。", "Impossibile confermare lo stato di abbinamento, ricontrolla.", "Couldn't confirm the pairing state, check again.", "No se pudo confirmar el estado de emparejamiento, vuelve a comprobar.", "Impossible de confirmer l'état d'association, vérifiez à nouveau.", "Não foi possível confirmar o estado de emparelhamento, verifique de novo.", "Não foi possível confirmar o status de pareamento, verifique de novo.", "Състоянието на сдвояването не може да се потвърди, проверете пак.", "Eşleştirme durumu doğrulanamadı, tekrar kontrol edin.", "Der Kopplungsstatus konnte nicht bestätigt werden, prüfe erneut.", "Не удалось подтвердить состояние сопряжения, проверьте снова.")
            detail.contains(Tr.tr("本应用清理尚未可靠保存", "pulizia dei dati dell'app non salvata in modo affidabile", "app data cleanup not reliably saved", "limpieza de datos de la app no guardada de forma fiable", "nettoyage des données de l'app non enregistré de façon fiable", "limpeza dos dados da app não guardada de forma fiável", "limpeza dos dados do app não salva de forma confiável", "изчистването на данните на приложението не е запазено надеждно", "uygulama verilerinin temizliği güvenilir şekilde kaydedilmedi", "Bereinigung der App-Daten nicht zuverlässig gespeichert", "очистка данных приложения не сохранена надёжно")) -> Tr.tr("设备资料清理未完成，请重新检查", "Pulizia dei dati del dispositivo non completata, ricontrolla", "Device data cleanup not completed, check again", "Limpieza de datos del dispositivo no completada, vuelve a comprobar", "Nettoyage des données de l'appareil non terminé, vérifiez à nouveau", "Limpeza dos dados do dispositivo não concluída, verifique de novo", "Limpeza dos dados do dispositivo não concluída, verifique de novo", "Изчистването на данните на устройството не е завършено, проверете пак", "Cihaz verilerinin temizliği tamamlanmadı, tekrar kontrol edin", "Bereinigung der Gerätedaten nicht abgeschlossen, prüfe erneut", "Очистка данных устройства не завершена, проверьте снова")
            detail.startsWith(Tr.tr("待处理标记或连接停止未可靠完成", "Contrassegno in sospeso o arresto della connessione non completati in modo affidabile", "Pending flag or connection stop not reliably completed", "Marca pendiente o parada de la conexión no completadas de forma fiable", "Indicateur en attente ou arrêt de la connexion non terminés de façon fiable", "Marca pendente ou paragem da ligação não concluídas de forma fiável", "Marca pendente ou interrupção da conexão não concluídas de forma confiável", "Чакащата отметка или спирането на връзката не са завършени надеждно", "Bekleyen işaret veya bağlantının durdurulması güvenilir şekilde tamamlanmadı", "Ausstehende Markierung oder Verbindungsstopp nicht zuverlässig abgeschlossen", "Ожидающая отметка или остановка подключения не завершены надёжно")) -> Tr.tr("操作未完成，请重新检查。", "Operazione non completata, ricontrolla.", "Operation not completed, check again.", "Operación no completada, vuelve a comprobar.", "Opération non terminée, vérifiez à nouveau.", "Operação não concluída, verifique de novo.", "Operação não concluída, verifique de novo.", "Операцията не е завършена, проверете пак.", "İşlem tamamlanmadı, tekrar kontrol edin.", "Vorgang nicht abgeschlossen, prüfe erneut.", "Операция не завершена, проверьте снова.")
            detail.contains(Tr.tr("当前表盘操作尚未结束", "L'operazione sul quadrante in corso non è ancora terminata", "The current watch face operation hasn't finished yet", "La operación de esfera en curso aún no ha terminado", "L'opération en cours sur le cadran n'est pas encore terminée", "A operação em curso no mostrador ainda não terminou", "A operação em andamento no mostrador ainda não terminou", "Текущата операция с циферблат още не е приключила", "Süren kadran işlemi henüz bitmedi", "Der laufende Zifferblatt-Vorgang ist noch nicht beendet", "Текущая операция с циферблатом ещё не завершена")) || detail.startsWith(Tr.tr("请先结束当前表盘操作", "Termina prima l'operazione sul quadrante in corso", "Finish the current watch face operation first", "Termina primero la operación de esfera en curso", "Terminez d'abord l'opération en cours sur le cadran", "Termine primeiro a operação em curso no mostrador", "Conclua primeiro a operação em andamento no mostrador", "Първо завършете текущата операция с циферблат", "Önce süren kadran işlemini bitirin", "Beende zuerst den laufenden Zifferblatt-Vorgang", "Сначала завершите текущую операцию с циферблатом")) -> Tr.tr("请先结束当前表盘操作。", "Termina prima l'operazione sul quadrante in corso.", "Finish the current watch face operation first.", "Termina primero la operación de esfera en curso.", "Terminez d'abord l'opération en cours sur le cadran.", "Termine primeiro a operação em curso no mostrador.", "Conclua primeiro a operação em andamento no mostrador.", "Първо завършете текущата операция с циферблат.", "Önce süren kadran işlemini bitirin.", "Beende zuerst den laufenden Zifferblatt-Vorgang.", "Сначала завершите текущую операцию с циферблатом.")
            s.needsSettings -> Tr.tr("请在系统蓝牙设置解除配对后返回检查", "Annulla l'abbinamento nelle impostazioni Bluetooth di sistema, poi torna qui a ricontrollare", "Unpair in system Bluetooth settings, then come back to check", "Desempareja en los ajustes Bluetooth del sistema y vuelve para comprobar", "Dissociez dans les paramètres Bluetooth du système, puis revenez vérifier", "Desemparelhe nas definições Bluetooth do sistema e volte para verificar", "Desfaça o pareamento nas configurações Bluetooth do sistema e volte para verificar", "Разкачете от системните настройки на Bluetooth и се върнете да проверите", "Sistem Bluetooth ayarlarından eşleştirmeyi kaldırın, sonra kontrol etmek için geri dönün", "Entkopple in den Bluetooth-Einstellungen des Systems und komm dann zum Prüfen zurück", "Снимите сопряжение в настройках Bluetooth системы и вернитесь для проверки")
            s.pending -> Tr.tr("正在确认解除配对", "Conferma dell'annullamento abbinamento", "Confirming unpairing", "Confirmando el desemparejamiento", "Confirmation de la dissociation", "A confirmar o desemparelhamento", "Confirmando o despareamento", "Потвърждаване на разкачването", "Eşleştirmenin kaldırılması onaylanıyor", "Entkopplung wird bestätigt", "Подтверждение снятия сопряжения")
            else -> Tr.tr("操作未完成，请重新检查。", "Operazione non completata, ricontrolla.", "Operation not completed, check again.", "Operación no completada, vuelve a comprobar.", "Opération non terminée, vérifiez à nouveau.", "Operação não concluída, verifique de novo.", "Operação não concluída, verifique de novo.", "Операцията не е завършена, проверете пак.", "İşlem tamamlanmadı, tekrar kontrol edin.", "Vorgang nicht abgeschlossen, prüfe erneut.", "Операция не завершена, проверьте снова.")
        }
    }
    private fun phaseLabel(code: String): String = when (code) {
        "IDLE" -> Tr.tr("尚未连接", "Non ancora connesso", "Not connected yet", "Aún no conectado", "Pas encore connecté", "Ainda não ligado", "Ainda não conectado", "Още не е свързано", "Henüz bağlı değil", "Noch nicht verbunden", "Ещё не подключено")
        "CONNECTING" -> Tr.tr("连接手表", "Connessione al watch", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr", "Подключение к часам")
        "SYSTEM_PAIRING" -> Tr.tr("等待系统配对", "In attesa dell'abbinamento di sistema", "Waiting for system pairing", "Esperando el emparejamiento del sistema", "En attente de l'association système", "A aguardar o emparelhamento do sistema", "Aguardando o pareamento do sistema", "Изчакване на системното сдвояване", "Sistem eşleştirmesi bekleniyor", "Warten auf die Systemkopplung", "Ожидание системного сопряжения")
        "LINK_PARAMS", "DEVICE_STATUS" -> Tr.tr("读取链路参数", "Lettura dei parametri del collegamento", "Reading link parameters", "Leyendo parámetros del enlace", "Lecture des paramètres de liaison", "A ler os parâmetros da ligação", "Lendo os parâmetros do link", "Четене на параметрите на връзката", "Bağlantı parametreleri okunuyor", "Verbindungsparameter werden gelesen", "Чтение параметров связи")
        "SECURITY", "LITE_AUTH", "ISO_AUTH", "BOND_PARAMS", "BOND_KEY" -> Tr.tr("验证设备认证", "Verifica dell'autenticazione del dispositivo", "Verifying device authentication", "Verificando la autenticación del dispositivo", "Vérification de l'authentification de l'appareil", "A verificar a autenticação do dispositivo", "Verificando a autenticação do dispositivo", "Проверка на удостоверяването на устройството", "Cihaz kimlik doğrulaması kontrol ediliyor", "Geräteauthentifizierung wird geprüft", "Проверка аутентификации устройства")
        "PIN_REQUEST", "PIN_INPUT" -> Tr.tr("等待配对码", "In attesa del codice di abbinamento", "Waiting for the pairing code", "Esperando el código de emparejamiento", "En attente du code d'association", "A aguardar o código de emparelhamento", "Aguardando o código de pareamento", "Изчакване на кода за сдвояване", "Eşleştirme kodu bekleniyor", "Warten auf den Kopplungscode", "Ожидание кода сопряжения")
        "VERIFY_SESSION" -> Tr.tr("验证当前会话密钥", "Verifica della chiave di sessione corrente", "Verifying the current session key", "Verificando la clave de la sesión actual", "Vérification de la clé de la session actuelle", "A verificar a chave da sessão atual", "Verificando a chave da sessão atual", "Проверка на ключа на текущата сесия", "Geçerli oturum anahtarı kontrol ediliyor", "Schlüssel der aktuellen Sitzung wird geprüft", "Проверка ключа текущего сеанса")
        "COMMAND_CAPS", "EXPAND_CAPS" -> Tr.tr("认证已通过，读取设备能力", "Autenticazione riuscita, lettura delle funzionalità del dispositivo", "Authentication passed, reading device capabilities", "Autenticación correcta, leyendo funciones del dispositivo", "Authentification réussie, lecture des fonctions de l'appareil", "Autenticação concluída, a ler as funções do dispositivo", "Autenticação concluída, lendo os recursos do dispositivo", "Удостоверяването е успешно, четене на функциите на устройството", "Kimlik doğrulandı, cihaz özellikleri okunuyor", "Authentifizierung erfolgreich, Gerätefunktionen werden gelesen", "Аутентификация пройдена, чтение возможностей устройства")
        "ACCOUNT", "SETTINGS", "AGREEMENTS", "REVERSE_CAPS", "SETUP_DEVICE", "ACTIVITY_TYPES", "WEAR_STATUS", "CONNECT_NOTIFY" -> Tr.tr("认证已通过，完成连接初始化", "Autenticazione riuscita, completamento dell'inizializzazione", "Authentication passed, finishing initialization", "Autenticación correcta, completando la inicialización", "Authentification réussie, fin de l'initialisation", "Autenticação concluída, a terminar a inicialização", "Autenticação concluída, finalizando a inicialização", "Удостоверяването е успешно, завършване на инициализацията", "Kimlik doğrulandı, başlatma tamamlanıyor", "Authentifizierung erfolgreich, Initialisierung wird abgeschlossen", "Аутентификация пройдена, завершение инициализации")
        "AGREEMENT_CONFIRM" -> Tr.tr("完成连接初始化", "Completamento dell'inizializzazione della connessione", "Finishing connection initialization", "Completando la inicialización de la conexión", "Fin de l'initialisation de la connexion", "A terminar a inicialização da ligação", "Finalizando a inicialização da conexão", "Завършване на инициализацията на връзката", "Bağlantı başlatması tamamlanıyor", "Verbindungsinitialisierung wird abgeschlossen", "Завершение инициализации подключения")
        "REBIND_CONFIRM", "REBINDING" -> Tr.tr("重新协议绑定", "Riassocia protocollo", "Re-bind protocol", "Volver a vincular protocolo", "Réassocier le protocole", "Voltar a associar protocolo", "Vincular protocolo de novo", "Ново обвързване на протокола", "Protokolü yeniden bağla", "Protokoll neu binden", "Заново привязать протокол")
        "READY" -> Tr.tr("连接就绪", "Connessione pronta", "Connection ready", "Conexión lista", "Connexion prête", "Ligação pronta", "Conexão pronta", "Връзката е готова", "Bağlantı hazır", "Verbindung bereit", "Подключение готово")
        else -> Tr.tr("连接未完成", "Connessione non completata", "Connection not completed", "Conexión no completada", "Connexion non terminée", "Ligação não concluída", "Conexão não concluída", "Връзката не е завършена", "Bağlantı tamamlanmadı", "Verbindung nicht abgeschlossen", "Подключение не завершено")
    }
    private fun operationLabel(kind: String, stage: String, active: Boolean, failed: Boolean, metadata: String = ""): String = when(stage) {
        "COMPLETED" -> DirectInstallState.completionNotice(metadata)
        "LISTED" -> if(kind == "direct_install") {
            if(!active && !failed) Tr.tr("表盘已安装", "Quadrante installato", "Watch face installed", "Esfera instalada", "Cadran installé", "Mostrador instalado", "Mostrador instalado", "Циферблатът е инсталиран", "Kadran yüklendi", "Zifferblatt installiert", "Циферблат установлен") else if(failed) Tr.tr("安装结果未确认", "Risultato dell'installazione non confermato", "Installation result not confirmed", "Resultado de la instalación no confirmado", "Résultat de l'installation non confirmé", "Resultado da instalação não confirmado", "Resultado da instalação não confirmado", "Резултатът от инсталирането не е потвърден", "Yükleme sonucu doğrulanmadı", "Installationsergebnis nicht bestätigt", "Результат установки не подтверждён") else Tr.tr("正在核对安装结果", "Verifica del risultato dell'installazione", "Checking installation result", "Comprobando el resultado de la instalación", "Vérification du résultat de l'installation", "A verificar o resultado da instalação", "Verificando o resultado da instalação", "Проверка на резултата от инсталирането", "Yükleme sonucu kontrol ediliyor", "Installationsergebnis wird geprüft", "Проверка результата установки")
        } else if(!active && !failed) Tr.tr("表盘已替换", "Quadrante sostituito", "Watch face replaced", "Esfera sustituida", "Cadran remplacé", "Mostrador substituído", "Mostrador substituído", "Циферблатът е заменен", "Kadran değiştirildi", "Zifferblatt ersetzt", "Циферблат заменён") else if(failed) Tr.tr("替换结果未确认", "Risultato della sostituzione non confermato", "Replacement result not confirmed", "Resultado de la sustitución no confirmado", "Résultat du remplacement non confirmé", "Resultado da substituição não confirmado", "Resultado da substituição não confirmado", "Резултатът от замяната не е потвърден", "Değiştirme sonucu doğrulanmadı", "Ergebnis des Ersetzens nicht bestätigt", "Результат замены не подтверждён") else Tr.tr("正在核对替换结果", "Verifica del risultato della sostituzione", "Checking replacement result", "Comprobando el resultado de la sustitución", "Vérification du résultat du remplacement", "A verificar o resultado da substituição", "Verificando o resultado da substituição", "Проверка на резултата от замяната", "Değiştirme sonucu kontrol ediliyor", "Ergebnis des Ersetzens wird geprüft", "Проверка результата замены")
        "DELETED" -> if(!active && !failed) Tr.tr("表盘已删除", "Quadrante eliminato", "Watch face deleted", "Esfera eliminada", "Cadran supprimé", "Mostrador eliminado", "Mostrador excluído", "Циферблатът е изтрит", "Kadran silindi", "Zifferblatt gelöscht", "Циферблат удалён") else if(failed) Tr.tr("删除结果未确认", "Risultato dell'eliminazione non confermato", "Deletion result not confirmed", "Resultado de la eliminación no confirmado", "Résultat de la suppression non confirmé", "Resultado da eliminação não confirmado", "Resultado da exclusão não confirmado", "Резултатът от изтриването не е потвърден", "Silme sonucu doğrulanmadı", "Löschergebnis nicht bestätigt", "Результат удаления не подтверждён") else Tr.tr("正在核对删除结果", "Verifica del risultato dell'eliminazione", "Checking deletion result", "Comprobando el resultado de la eliminación", "Vérification du résultat de la suppression", "A verificar o resultado da eliminação", "Verificando o resultado da exclusão", "Проверка на резултата от изтриването", "Silme sonucu kontrol ediliyor", "Löschergebnis wird geprüft", "Проверка результата удаления")
        "ACTIVATED" -> if(!active && !failed) Tr.tr("表盘已启用", "Quadrante attivato", "Watch face activated", "Esfera activada", "Cadran activé", "Mostrador ativado", "Mostrador ativado", "Циферблатът е активиран", "Kadran etkinleştirildi", "Zifferblatt aktiviert", "Циферблат активирован") else if(failed) Tr.tr("启用结果未确认", "Risultato dell'attivazione non confermato", "Activation result not confirmed", "Resultado de la activación no confirmado", "Résultat de l'activation non confirmé", "Resultado da ativação não confirmado", "Resultado da ativação não confirmado", "Резултатът от активирането не е потвърден", "Etkinleştirme sonucu doğrulanmadı", "Aktivierungsergebnis nicht bestätigt", "Результат активации не подтверждён") else Tr.tr("正在核对启用结果", "Verifica del risultato dell'attivazione", "Checking activation result", "Comprobando el resultado de la activación", "Vérification du résultat de l'activation", "A verificar o resultado da ativação", "Verificando o resultado da ativação", "Проверка на резултата от активирането", "Etkinleştirme sonucu kontrol ediliyor", "Aktivierungsergebnis wird geprüft", "Проверка результата активации")
        "NO_TRANSFER" -> Tr.tr("设备未要求文件，本次未传输", "Il dispositivo non ha richiesto file, nessun trasferimento effettuato", "The device didn't request a file, nothing was transferred", "El dispositivo no pidió ningún archivo, no se transfirió nada", "L'appareil n'a demandé aucun fichier, rien n'a été transféré", "O dispositivo não pediu nenhum ficheiro, nada foi transferido", "O dispositivo não pediu nenhum arquivo, nada foi transferido", "Устройството не поиска файл, нищо не е прехвърлено", "Cihaz dosya istemedi, hiçbir şey aktarılmadı", "Das Gerät hat keine Datei angefordert, nichts wurde übertragen", "Устройство не запросило файл, ничего не передано")
        "PRECHECK_FAILED" -> if(kind == "deletion") Tr.tr("删除未开始，尚未向手表发送指令", "Eliminazione non avviata, nessun comando inviato al watch", "Deletion not started, no command sent to the watch", "Eliminación no iniciada, ningún comando enviado al reloj", "Suppression non démarrée, aucune commande envoyée à la montre", "Eliminação não iniciada, nenhum comando enviado ao relógio", "Exclusão não iniciada, nenhum comando enviado ao relógio", "Изтриването не е започнало, не е изпратена команда към часовника", "Silme başlamadı, saate komut gönderilmedi", "Löschen nicht gestartet, kein Befehl an die Uhr gesendet", "Удаление не начато, команда на часы не отправлена") else if(kind == "activation") Tr.tr("启用未开始，请刷新后重试", "Attivazione non avviata, aggiorna e riprova", "Activation not started, refresh and try again", "Activación no iniciada, actualiza y vuelve a intentarlo", "Activation non démarrée, actualisez et réessayez", "Ativação não iniciada, atualize e tente de novo", "Ativação não iniciada, atualize e tente de novo", "Активирането не е започнало, опреснете и опитайте пак", "Etkinleştirme başlamadı, yenileyip tekrar deneyin", "Aktivierung nicht gestartet, aktualisiere und versuche es erneut", "Активация не начата, обновите и повторите") else Tr.tr("替换未开始，尚未向手表发送指令", "Sostituzione non avviata, nessun comando inviato al watch", "Replacement not started, no command sent to the watch", "Sustitución no iniciada, ningún comando enviado al reloj", "Remplacement non démarré, aucune commande envoyée à la montre", "Substituição não iniciada, nenhum comando enviado ao relógio", "Substituição não iniciada, nenhum comando enviado ao relógio", "Замяната не е започнала, не е изпратена команда към часовника", "Değiştirme başlamadı, saate komut gönderilmedi", "Ersetzen nicht gestartet, kein Befehl an die Uhr gesendet", "Замена не начата, команда на часы не отправлена")
        "TIMED_OUT" -> Tr.tr("操作超时，请重新连接并查询", "Operazione scaduta, riconnettiti e ricontrolla", "Operation timed out, reconnect and check again", "Tiempo agotado, vuelve a conectar y comprueba de nuevo", "Délai dépassé, reconnectez-vous et vérifiez à nouveau", "Tempo esgotado, volte a ligar e verifique de novo", "Tempo esgotado, conecte de novo e verifique outra vez", "Времето изтече, свържете се отново и проверете пак", "Zaman aşımı, yeniden bağlanıp tekrar kontrol edin", "Zeitüberschreitung, verbinde dich neu und prüfe erneut", "Время истекло, переподключитесь и проверьте снова")
        "FAILED", "CANCELLED" -> OperationFailureText.forReason(metadata) ?: Tr.tr("操作未可靠完成，请重新连接并查询", "Operazione non completata in modo affidabile, riconnettiti e ricontrolla", "Operation not reliably completed, reconnect and check again", "Operación no completada de forma fiable, vuelve a conectar y comprueba de nuevo", "Opération non terminée de façon fiable, reconnectez-vous et vérifiez à nouveau", "Operação não concluída de forma fiável, volte a ligar e verifique de novo", "Operação não concluída de forma confiável, conecte de novo e verifique outra vez", "Операцията не е завършена надеждно, свържете се отново и проверете пак", "İşlem güvenilir şekilde tamamlanmadı, yeniden bağlanıp tekrar kontrol edin", "Vorgang nicht zuverlässig abgeschlossen, verbinde dich neu und prüfe erneut", "Операция не завершена надёжно, переподключитесь и проверьте снова")
        "WAIT_OPERATION", "WAIT_DEVICE" -> Tr.tr("等待设备接受回执", "In attesa della conferma di ricezione dal dispositivo", "Waiting for the device's receipt", "Esperando el acuse del dispositivo", "En attente de l'accusé de l'appareil", "A aguardar a confirmação de receção do dispositivo", "Aguardando a confirmação de recebimento do dispositivo", "Изчакване на потвърждение от устройството", "Cihazın alındı onayı bekleniyor", "Warten auf die Empfangsbestätigung des Geräts", "Ожидание подтверждения получения от устройства")
        "TRANSFERRING" -> Tr.tr("传输原始内容", "Trasferimento del contenuto originale", "Transferring original content", "Transfiriendo el contenido original", "Transfert du contenu d'origine", "A transferir o conteúdo original", "Transferindo o conteúdo original", "Прехвърляне на оригиналното съдържание", "Orijinal içerik aktarılıyor", "Originalinhalt wird übertragen", "Передача исходного содержимого")
        "WAIT_REPORT", "WAIT_POST" -> Tr.tr("等待手表报告与应用确认（最长30秒）", "In attesa del report del watch e della conferma dell'app (max 30 secondi)", "Waiting for the watch report and app confirmation (up to 30 seconds)", "Esperando el informe del reloj y la confirmación de la app (hasta 30 segundos)", "En attente du rapport de la montre et de la confirmation de l'app (30 secondes max.)", "A aguardar o relatório do relógio e a confirmação da app (até 30 segundos)", "Aguardando o relatório do relógio e a confirmação do app (até 30 segundos)", "Изчакване на отчета от часовника и потвърждението от приложението (до 30 секунди)", "Saatin raporu ve uygulamanın onayı bekleniyor (en fazla 30 saniye)", "Warten auf den Bericht der Uhr und die Bestätigung der App (bis zu 30 Sekunden)", "Ожидание отчёта часов и подтверждения приложения (до 30 секунд)")
        "WAIT_LIST" -> if(kind == "deletion") Tr.tr("核对删除后的两次新列表", "Verifica dei due nuovi elenchi dopo l'eliminazione", "Checking the two new lists after deletion", "Comprobando las dos nuevas listas tras la eliminación", "Vérification des deux nouvelles listes après la suppression", "A verificar as duas novas listas após a eliminação", "Verificando as duas novas listas após a exclusão", "Проверка на двата нови списъка след изтриването", "Silme sonrası iki yeni liste kontrol ediliyor", "Die zwei neuen Listen nach dem Löschen werden geprüft", "Проверка двух новых списков после удаления") else if(kind == "activation") Tr.tr("核对当前表盘", "Verifica del quadrante attuale", "Checking the current watch face", "Comprobando la esfera actual", "Vérification du cadran actuel", "A verificar o mostrador atual", "Verificando o mostrador atual", "Проверка на текущия циферблат", "Geçerli kadran kontrol ediliyor", "Aktuelles Zifferblatt wird geprüft", "Проверка текущего циферблата") else Tr.tr("核对新版本列表", "Verifica dell'elenco con la nuova versione", "Checking the list with the new version", "Comprobando la lista con la nueva versión", "Vérification de la liste avec la nouvelle version", "A verificar a lista com a nova versão", "Verificando a lista com a nova versão", "Проверка на списъка с новата версия", "Yeni sürümlü liste kontrol ediliyor", "Liste mit der neuen Version wird geprüft", "Проверка списка с новой версией")
        else -> stage
    }
    private fun replacementIdentityMatches(dialog: ReplacementDialogInfo?, identity: WatchfaceManager.ReplacementIdentity?): Boolean =
        dialog != null && identity != null && dialog.operation.kind == (if(identity.directInstall) "direct_install" else "replacement") && dialog.token == identity.token && dialog.connectionEpoch == identity.epoch &&
            dialog.targetId == identity.id && dialog.targetVersion == identity.version
    private fun deletionIdentityMatches(dialog: ReplacementDialogInfo?, identity: WatchfaceManager.DeletionIdentity?): Boolean =
        dialog != null && dialog.operation.kind == "deletion" && identity != null && dialog.token == identity.token && dialog.connectionEpoch == identity.epoch &&
            dialog.targetId == identity.id && dialog.targetVersion == identity.version
    private fun activationIdentityMatches(dialog: ReplacementDialogInfo?, identity: WatchfaceManager.ActivationIdentity?): Boolean =
        dialog != null && dialog.operation.kind == "activation" && identity != null && dialog.token == identity.token && dialog.connectionEpoch == identity.epoch &&
            dialog.targetId == identity.id && dialog.targetVersion == identity.version
    // 保留旧 helper 名称，让所有排队/间接操作守卫同时保护三种操作。
    private fun replacementActive(): Boolean = runtime.snapshot().let { it.replacementDialog?.operation?.active == true || it.deletionDialog?.operation?.active == true || it.activationDialog?.operation?.active == true }
    private fun replacementDialog(s: AppRuntime.Snapshot): ReplacementDialogInfo? {
        val dialog = s.replacementDialog ?: return null
        if(!replacementIdentityMatches(dialog, s.replacementDialogIdentity)) return null
        val operation = dialog.operation
        val detail = when {
            operation.stage == "PRECHECK_FAILED" -> if(operation.kind == "direct_install") Tr.tr("安装未开始：${operation.detail}", "Installazione non avviata: ${operation.detail}", "Installation not started: ${operation.detail}", "Instalación no iniciada: ${operation.detail}", "Installation non démarrée : ${operation.detail}", "Instalação não iniciada: ${operation.detail}", "Instalação não iniciada: ${operation.detail}", "Инсталирането не е започнало: ${operation.detail}", "Yükleme başlamadı: ${operation.detail}", "Installation nicht gestartet: ${operation.detail}", "Установка не начата: ${operation.detail}") else replacementError(operation.detail)
            operation.stage == "CANCELLED" && operation.detail == "USER_CANCELLED_BEFORE_SEND" -> Tr.tr("替换已取消，尚未向手表发送指令", "Sostituzione annullata, nessun comando inviato al watch", "Replacement cancelled, no command sent to the watch", "Sustitución cancelada, ningún comando enviado al reloj", "Remplacement annulé, aucune commande envoyée à la montre", "Substituição cancelada, nenhum comando enviado ao relógio", "Substituição cancelada, nenhum comando enviado ao relógio", "Замяната е отменена, не е изпратена команда към часовника", "Değiştirme iptal edildi, saate komut gönderilmedi", "Ersetzen abgebrochen, kein Befehl an die Uhr gesendet", "Замена отменена, команда на часы не отправлена")
            operation.stage == "CANCELLED" -> Tr.tr("替换已取消，请重新连接并查询", "Sostituzione annullata, riconnettiti e ricontrolla", "Replacement cancelled, reconnect and check again", "Sustitución cancelada, vuelve a conectar y comprueba de nuevo", "Remplacement annulé, reconnectez-vous et vérifiez à nouveau", "Substituição cancelada, volte a ligar e verifique de novo", "Substituição cancelada, conecte de novo e verifique outra vez", "Замяната е отменена, свържете се отново и проверете пак", "Değiştirme iptal edildi, yeniden bağlanıp tekrar kontrol edin", "Ersetzen abgebrochen, verbinde dich neu und prüfe erneut", "Замена отменена, переподключитесь и проверьте снова")
            else -> operationLabel(operation.kind, operation.stage, operation.active, operation.failed, operation.detail)
        }
        return dialog.copy(operation = operation.copy(detail = detail))
    }
    private fun deletionDialog(s: AppRuntime.Snapshot): ReplacementDialogInfo? {
        val dialog = s.deletionDialog ?: return null
        if(!deletionIdentityMatches(dialog, s.deletionDialogIdentity)) return null
        val operation = dialog.operation
        val detail = if(operation.stage == "PRECHECK_FAILED") deletionError(operation.detail)
            else operationLabel(operation.kind, operation.stage, operation.active, operation.failed, operation.detail)
        return dialog.copy(fileName = "", operation = operation.copy(detail = detail), interruptRequested = false)
    }
    private fun activationDialog(s: AppRuntime.Snapshot): ReplacementDialogInfo? {
        val dialog = s.activationDialog ?: return null
        if(!activationIdentityMatches(dialog, s.activationDialogIdentity)) return null
        val operation = dialog.operation
        val detail = if(operation.stage == "PRECHECK_FAILED") activationError(operation.detail)
            else operationLabel(operation.kind, operation.stage, operation.active, operation.failed, operation.detail)
        return dialog.copy(fileName = "", operation = operation.copy(detail = detail), interruptRequested = false)
    }
    private fun apply(s: AppRuntime.Snapshot) {
        val current = runtime.snapshot()
        if(s.epoch != runtime.connection.epoch() || s.epoch != current.epoch ||
            s.operationRevision != current.operationRevision || s.replacementDialogRevision != current.replacementDialogRevision || s.deletionDialogRevision != current.deletionDialogRevision || s.activationDialogRevision != current.activationDialogRevision ||
            s.replacementIdentity !== current.replacementIdentity || s.replacementDialogIdentity !== current.replacementDialogIdentity ||
            s.deletionIdentity !== current.deletionIdentity || s.deletionDialogIdentity !== current.deletionDialogIdentity ||
            s.activationIdentity !== current.activationIdentity || s.activationDialogIdentity !== current.activationDialogIdentity ||
            s.operationRevision < appliedOperationRevision || s.replacementDialogRevision < appliedReplacementDialogRevision || s.deletionDialogRevision < appliedDeletionDialogRevision || s.activationDialogRevision < appliedActivationDialogRevision) return
        appliedOperationRevision = s.operationRevision
        appliedReplacementDialogRevision = s.replacementDialogRevision
        appliedDeletionDialogRevision = s.deletionDialogRevision
        appliedActivationDialogRevision = s.activationDialogRevision
        if(queuedConnectEpoch != null && queuedConnectEpoch != s.epoch) queuedConnectEpoch = null
        val address = SavedWatchStore.canonicalAddress(runtime.connection.address())
        if(removalBlocks(address) && (s.ready || s.connecting)) return
        val device = address?.let(::deviceMetadata)
        if(address != null && mutable.value.selectedDeviceAddress == address && credentialEpoch != s.epoch && (s.ready || s.connecting)) {
            credentialEpoch = s.epoch
            restoreHuaweiId(address)
        }
        val faces = s.faces.map { f -> FaceItem(f.id, f.version, f.type ?: 0, f.expandType, s.names["${f.id}_${f.version}"],
            FacePolicy.current(f), FacePolicy.eligible(f), FacePolicy.kind(f), activatable = FacePolicy.canActivate(f)) }
        val queuedStart = startingOperation
        val replacement = replacementDialog(s)
        val deletion = deletionDialog(s)
        val activation = activationDialog(s)
        val dialog = replacement ?: deletion ?: activation
        val op = when {
            preparing -> OperationInfo("source", "PREPARING", Tr.tr("读取并冻结本地 HWT", "Lettura e blocco dell'HWT locale", "Reading and locking the local HWT", "Leyendo y bloqueando el HWT local", "Lecture et verrouillage du HWT local", "A ler e bloquear o HWT local", "Lendo e bloqueando o HWT local", "Четене и заключване на локалния HWT", "Yerel HWT okunuyor ve kilitleniyor", "Lokale HWT wird gelesen und gesperrt", "Чтение и блокировка локального HWT"), null, true, false)
            queuedStart != null -> OperationInfo(queuedStart.kind, "WAIT_OPERATION", if(queuedStart.kind == "deletion") Tr.tr("正在开始删除", "Avvio dell'eliminazione", "Starting deletion", "Iniciando la eliminación", "Démarrage de la suppression", "A iniciar a eliminação", "Iniciando a exclusão", "Започва изтриването", "Silme başlıyor", "Löschen wird gestartet", "Запуск удаления") else Tr.tr("正在开始替换", "Avvio della sostituzione", "Starting replacement", "Iniciando la sustitución", "Démarrage du remplacement", "A iniciar a substituição", "Iniciando a substituição", "Започва замяната", "Değiştirme başlıyor", "Ersetzen wird gestartet", "Запуск замены"), null, true, false)
            dialog != null && (dialog.operation.active || !s.active) -> dialog.operation
            fileOnlyPreview && !s.active && !s.failed -> OperationInfo()
            else -> OperationInfo(s.kind, s.stage, operationLabel(s.kind, s.stage, s.active, s.failed, s.operationDetail), s.progress, s.active, s.failed)
        }
        // 就绪状态优先于本地排队标记，连接完成后不再保留“正在连接”。
        if(s.ready) queuedConnectEpoch = null
        mutable.update { old -> old.copy(phaseCode = s.code, connectionEpoch = s.epoch,
            phase = if(queuedConnectEpoch != null) Tr.tr("连接手表", "Connessione al watch", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr", "Подключение к часам") else phaseLabel(s.code), detail = connectionDetail(s),
            connected = s.ready, connecting = UiPolicy.connecting(s.ready, s.connecting, queuedConnectEpoch != null),
            watchName = if(queuedConnectEpoch != null) sessionDevice?.name ?: old.watchName else device?.name ?: old.watchName,
            deviceAddress = s.deviceAddress, serialNumber = s.serialNumber, firmware = s.firmware, deviceModel = s.deviceModel,
            batteryPercent = s.batteryPercent, freeMemoryBytes = s.freeMemoryBytes,
            width = s.width, height = s.height, capability = s.capability,
            faces = faces, listReady = s.listReady, listRefreshing = s.listRefreshing,
            selectedId = null, operation = op, replacementDialog = replacement, deletionDialog = deletion, activationDialog = activation, needsPin = s.needsPin, pinMessage = s.pinMessage,
            rebindCount = s.rebind, quarantine = (s.failed && !s.ready) || runtime.watchfaces.reconnectRequired(s.epoch),
            statusMessage = when {
                s.message == "WIRE_FACE_LIST_INCOMPLETE" -> runtimeError(s.kind, s.stage, s.message)
                s.kind == "replacement" && s.stage == "PRECHECK_FAILED" && s.message.isNotBlank() -> replacementError(s.message)
                s.kind == "deletion" && s.stage == "PRECHECK_FAILED" && s.message.isNotBlank() -> deletionError(s.message)
                s.kind == "activation" && s.stage == "PRECHECK_FAILED" && s.message.isNotBlank() -> activationError(s.message)
                s.message.isNotBlank() -> s.message
                (s.ready && old.phaseCode != "READY") || (old.listRefreshing && !s.listRefreshing) -> ""
                else -> old.statusMessage
            }) }
        if(s.message != lastRuntimeMessage) {
            lastRuntimeMessage = s.message
            if(s.message.isNotBlank()) message(runtimeError(s.kind, s.stage, s.message))
        }
        syncSuccessfulHistory(s)
        // 使用原有跳过分支，继续初始化而不发送同意声明。
        // 排队或过时快照不能操作其他连接或已推进的阶段。
        if(s.code == "AGREEMENT_CONFIRM" && s.epoch == runtime.connection.epoch() && runtime.snapshot().code == s.code && !runtime.connection.ready()) {
            if(agreementSkippedEpoch != s.epoch) {
                agreementSkippedEpoch = s.epoch
                runtime.connection.respondAgreements(s.epoch, false)
            }
        }
        if(mutable.value.prompt?.kind == "rebind" && runtime.snapshot().code != "REBIND_CONFIRM") invalidatePrompt()
    }
    private fun connectionDetail(s: AppRuntime.Snapshot): String = when(s.code) {
        "AGREEMENT_CONFIRM" -> Tr.tr("继续连接初始化", "Prosecuzione dell'inizializzazione della connessione", "Continuing connection initialization", "Continuando la inicialización de la conexión", "Poursuite de l'initialisation de la connexion", "A continuar a inicialização da ligação", "Continuando a inicialização da conexão", "Продължаване на инициализацията на връзката", "Bağlantı başlatmasına devam ediliyor", "Verbindungsinitialisierung wird fortgesetzt", "Продолжение инициализации подключения")
        "SYSTEM_PAIRING" -> Tr.tr("请在手机和手表确认系统配对", "Conferma l'abbinamento di sistema sul telefono e sul watch", "Confirm system pairing on the phone and the watch", "Confirma el emparejamiento del sistema en el teléfono y en el reloj", "Confirmez l'association système sur le téléphone et sur la montre", "Confirme o emparelhamento do sistema no telemóvel e no relógio", "Confirme o pareamento do sistema no celular e no relógio", "Потвърдете системното сдвояване на телефона и на часовника", "Telefonda ve saatte sistem eşleştirmesini onaylayın", "Bestätige die Systemkopplung auf dem Handy und auf der Uhr", "Подтвердите системное сопряжение на телефоне и на часах")
        "ACCOUNT" -> Tr.tr("同步华为 ID", "Sincronizzazione dell'ID Huawei", "Syncing the Huawei ID", "Sincronizando el ID de Huawei", "Synchronisation de l'ID Huawei", "A sincronizar o ID Huawei", "Sincronizando o ID Huawei", "Синхронизиране на Huawei ID", "Huawei ID eşitleniyor", "Huawei-ID wird synchronisiert", "Синхронизация Huawei ID")
        "PIN_REQUEST" -> Tr.tr("请求手表配对信息", "Richiesta delle informazioni di abbinamento al watch", "Requesting pairing information from the watch", "Pidiendo al reloj la información de emparejamiento", "Demande des informations d'association à la montre", "A pedir ao relógio as informações de emparelhamento", "Pedindo ao relógio as informações de pareamento", "Заявка към часовника за информация за сдвояване", "Saatten eşleştirme bilgisi isteniyor", "Kopplungsinformationen werden von der Uhr angefordert", "Запрос сведений о сопряжении у часов")
        "PIN_INPUT" -> Tr.tr("输入手表显示的配对码", "Inserisci il codice di abbinamento mostrato sul watch", "Enter the pairing code shown on the watch", "Introduce el código de emparejamiento que muestra el reloj", "Saisissez le code d'association affiché sur la montre", "Introduza o código de emparelhamento mostrado no relógio", "Insira o código de pareamento mostrado no relógio", "Въведете кода за сдвояване, показан на часовника", "Saatte görünen eşleştirme kodunu girin", "Gib den auf der Uhr angezeigten Kopplungscode ein", "Введите код сопряжения, показанный на часах")
        "BOND_PARAMS" -> Tr.tr("读取协议绑定参数", "Lettura dei parametri di associazione del protocollo", "Reading protocol binding parameters", "Leyendo los parámetros de vinculación del protocolo", "Lecture des paramètres de liaison du protocole", "A ler os parâmetros de associação do protocolo", "Lendo os parâmetros de vínculo do protocolo", "Четене на параметрите за обвързване на протокола", "Protokol bağlama parametreleri okunuyor", "Parameter der Protokollbindung werden gelesen", "Чтение параметров привязки протокола")
        "BOND_KEY" -> Tr.tr("完成协议绑定", "Completamento dell'associazione del protocollo", "Completing protocol binding", "Completando la vinculación del protocolo", "Finalisation de la liaison du protocole", "A concluir a associação do protocolo", "Concluindo o vínculo do protocolo", "Завършване на обвързването на протокола", "Protokol bağlama tamamlanıyor", "Protokollbindung wird abgeschlossen", "Завершение привязки протокола")
        "REBINDING" -> Tr.tr("正在重新进行协议绑定", "Riassociazione del protocollo in corso", "Re-binding the protocol", "Volviendo a vincular el protocolo", "Réassociation du protocole en cours", "A voltar a associar o protocolo", "Vinculando o protocolo de novo", "Ново обвързване на протокола", "Protokol yeniden bağlanıyor", "Protokoll wird neu gebunden", "Повторная привязка протокола")
        "READY" -> if(s.detail.contains(Tr.tr("部分可选回执未确认", "alcune conferme facoltative non ricevute", "some optional receipts not confirmed", "algunos acuses opcionales sin confirmar", "certains accusés facultatifs non confirmés", "algumas confirmações opcionais não recebidas", "algumas confirmações opcionais não recebidas", "някои незадължителни потвърждения не са получени", "bazı isteğe bağlı onaylar alınmadı", "einige optionale Bestätigungen nicht erhalten", "некоторые необязательные подтверждения не получены"))) Tr.tr("连接初始化完成；部分可选回执未确认", "Inizializzazione della connessione completata; alcune conferme facoltative non ricevute", "Connection initialization completed; some optional receipts not confirmed", "Inicialización de la conexión completada; algunos acuses opcionales sin confirmar", "Initialisation de la connexion terminée ; certains accusés facultatifs non confirmés", "Inicialização da ligação concluída; algumas confirmações opcionais não recebidas", "Inicialização da conexão concluída; algumas confirmações opcionais não recebidas", "Инициализацията на връзката е завършена; някои незадължителни потвърждения не са получени", "Bağlantı başlatması tamamlandı; bazı isteğe bağlı onaylar alınmadı", "Verbindungsinitialisierung abgeschlossen; einige optionale Bestätigungen nicht erhalten", "Инициализация подключения завершена; некоторые необязательные подтверждения не получены") else Tr.tr("连接初始化完成", "Inizializzazione della connessione completata", "Connection initialization completed", "Inicialización de la conexión completada", "Initialisation de la connexion terminée", "Inicialização da ligação concluída", "Inicialização da conexão concluída", "Инициализацията на връзката е завършена", "Bağlantı başlatması tamamlandı", "Verbindungsinitialisierung abgeschlossen", "Инициализация подключения завершена")
        "FAILED" -> ConnectionRecovery.describe(s.detail)
        else -> s.detail
    }
    private fun deviceMetadata(address: String): NearbyWatch? = sessionDevice?.takeIf { it.address.equals(address, true) }
        ?: mutable.value.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
    private fun savedHuaweiId(address: String?): String = if(address == null || removalBlocks(address)) "" else runCatching {
        runtime.secure.huaweiId(address)
    }.getOrElse { message(Tr.tr("无法读取该设备的华为 ID：${Core.code(it)}", "Impossibile leggere l'ID Huawei di questo dispositivo: ${Core.code(it)}", "Couldn't read this device's Huawei ID: ${Core.code(it)}", "No se pudo leer el ID de Huawei de este dispositivo: ${Core.code(it)}", "Impossible de lire l'ID Huawei de cet appareil : ${Core.code(it)}", "Não foi possível ler o ID Huawei deste dispositivo: ${Core.code(it)}", "Não foi possível ler o ID Huawei deste dispositivo: ${Core.code(it)}", "Huawei ID на това устройство не може да се прочете: ${Core.code(it)}", "Bu cihazın Huawei ID'si okunamadı: ${Core.code(it)}", "Die Huawei-ID dieses Geräts konnte nicht gelesen werden: ${Core.code(it)}", "Не удалось прочитать Huawei ID этого устройства: ${Core.code(it)}")); "" }
    private fun restoreHuaweiId(address: String?) {
        val id = savedHuaweiId(address)
        savedEditorId = id
        mutable.update { it.copy(huaweiIdInput = id, accountConfigured = id.isNotBlank(), huaweiIdDirty = false) }
    }
    private fun syncSuccessfulHistory(s: AppRuntime.Snapshot) {
        runCatching { successfulHistory(runtime, savedWatches, s) ?: savedWatches.devices() }.onSuccess { history ->
            // 断开时也同步本地身份缓存；已保存的型号与序列号不依赖旧 READY 界面任务再次执行。
            mutable.update { it.copy(savedDevices = history) }
        }.onFailure { message(Tr.tr("设备记录保存失败：${Core.code(it)}", "Salvataggio dei dati del dispositivo non riuscito: ${Core.code(it)}", "Couldn't save device data: ${Core.code(it)}", "No se pudieron guardar los datos del dispositivo: ${Core.code(it)}", "Impossible d'enregistrer les données de l'appareil : ${Core.code(it)}", "Não foi possível guardar os dados do dispositivo: ${Core.code(it)}", "Não foi possível salvar os dados do dispositivo: ${Core.code(it)}", "Данните на устройството не могат да се запазят: ${Core.code(it)}", "Cihaz verileri kaydedilemedi: ${Core.code(it)}", "Gerätedaten konnten nicht gespeichert werden: ${Core.code(it)}", "Не удалось сохранить данные устройства: ${Core.code(it)}")) }
    }
    /**
     * 未读说明不消费启动机会；当前说明 owner 正常退出后才由 Activity 再调用。
     * 此后第一次尝试仍立即消费机会，包括设置关闭或无法取得设备的情况。
     * 本界面模型在权限取消或页面重新进入时不自动重试；新模型
     * 只在共享运行时与界面均无活动操作时尝试启动连接。
     * 蓝牙和通知权限仍由活动页面的系统权限流程处理。
     */
    fun takeStartupDevice(): NearbyWatch? {
        if(mutable.value.introductionPending) return null
        val s = runtime.snapshot()
        val eligible = !closed && !removalWorking() && mutable.value.autoConnect && queuedConnectEpoch == null &&
            !runtime.connection.ready() && !s.ready && !s.connecting && !s.active && !s.needsPin && s.code != "REBIND_CONFIRM" &&
            s.replacementDialog == null && s.deletionDialog == null && !mutable.value.connecting && !mutable.value.scanning &&
            !preparing && filePickToken == null && startingOperation == null && deletionPreparation == null && !replacementActive() && pending == null && !runtime.watchfaces.busy()
        if(!startupConnectionOpportunity.take(introductionPending = false, eligible = eligible)) return null
        val device = mutable.value.savedDevices.firstOrNull { !removalBlocks(it.address) }?.nearby() ?: return null
        selectDevice(device)
        if(!mutable.value.accountConfigured) { message(Tr.tr("上次设备的华为 ID 无法读取，请重新输入后连接", "Impossibile leggere l'ID Huawei dell'ultimo dispositivo; inseriscilo di nuovo e connettiti", "Couldn't read the Huawei ID of the last device; enter it again and connect", "No se pudo leer el ID de Huawei del último dispositivo; introdúcelo de nuevo y conéctate", "Impossible de lire l'ID Huawei du dernier appareil ; saisissez-le à nouveau et connectez-vous", "Não foi possível ler o ID Huawei do último dispositivo; introduza-o de novo e ligue-se", "Não foi possível ler o ID Huawei do último dispositivo; insira-o de novo e conecte", "Huawei ID на последното устройство не може да се прочете; въведете го отново и се свържете", "Son cihazın Huawei ID'si okunamadı; tekrar girip bağlanın", "Die Huawei-ID des letzten Geräts konnte nicht gelesen werden; gib sie erneut ein und verbinde dich", "Не удалось прочитать Huawei ID последнего устройства; введите его снова и подключитесь")); return null }
        return device
    }
    private fun message(text: String) {
        if(closed) return
        runtime.logs.record(Tr.tr("界面提示", "Avviso", "Notice", "Aviso", "Avis", "Aviso", "Aviso", "Известие", "Bildirim", "Hinweis", "Уведомление"), text)
        mutable.update { it.copy(statusMessage = text, noticeSerial = it.noticeSerial + 1L, noticeMessage = text) }
    }
    override fun setTheme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); mutable.update { it.copy(theme = mode) } }
    override fun selectDevice(device: NearbyWatch) {
        if(closed || mutable.value.introductionPending || removalWorking() || startingOperation != null || deletionPreparation != null || replacementActive()) return
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message(Tr.tr("设备地址无效", "Indirizzo del dispositivo non valido", "Invalid device address", "Dirección del dispositivo no válida", "Adresse de l'appareil non valide", "Endereço do dispositivo inválido", "Endereço do dispositivo inválido", "Невалиден адрес на устройството", "Geçersiz cihaz adresi", "Ungültige Geräteadresse", "Неверный адрес устройства")); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message(Tr.tr("无法核实设备资料状态：${Core.code(it)}", "Impossibile verificare lo stato dei dati del dispositivo: ${Core.code(it)}", "Couldn't verify the device data state: ${Core.code(it)}", "No se pudo verificar el estado de los datos del dispositivo: ${Core.code(it)}", "Impossible de vérifier l'état des données de l'appareil : ${Core.code(it)}", "Não foi possível verificar o estado dos dados do dispositivo: ${Core.code(it)}", "Não foi possível verificar o status dos dados do dispositivo: ${Core.code(it)}", "Състоянието на данните на устройството не може да се провери: ${Core.code(it)}", "Cihaz verilerinin durumu doğrulanamadı: ${Core.code(it)}", "Der Status der Gerätedaten konnte nicht geprüft werden: ${Core.code(it)}", "Не удалось проверить состояние данных устройства: ${Core.code(it)}")); return }
        if(generation > 0L && knownUnpairDevice(address) == null) { message(Tr.tr("此设备记录已清除，请重新搜索后选择设备", "I dati di questo dispositivo sono stati cancellati; cercalo di nuovo e selezionalo", "This device's data was cleared; search for it again and select it", "Los datos de este dispositivo se borraron; búscalo de nuevo y selecciónalo", "Les données de cet appareil ont été effacées ; recherchez-le à nouveau et sélectionnez-le", "Os dados deste dispositivo foram apagados; procure-o de novo e selecione-o", "Os dados deste dispositivo foram apagados; procure-o de novo e selecione-o", "Данните на това устройство са изтрити; потърсете го отново и го изберете", "Bu cihazın verileri silindi; tekrar arayıp seçin", "Die Daten dieses Geräts wurden gelöscht; suche es erneut und wähle es aus", "Данные этого устройства удалены; найдите его снова и выберите")); return }
        if(mutable.value.selectedDeviceAddress != address && mutable.value.prompt != null) invalidatePrompt()
        mutable.update { it.copy(selectedDeviceAddress = address, watchName = device.name, connectionFormRevision = it.connectionFormRevision + 1L) }
        credentialEpoch = runtime.connection.epoch()
        restoreHuaweiId(address)
    }
    override fun setHuaweiId(value: String) {
        if(closed || removalWorking() || mutable.value.selectedDeviceAddress == null || removalBlocks(mutable.value.selectedDeviceAddress)) return
        mutable.update { if(it.huaweiIdInput == value) it else it.copy(huaweiIdInput = value,
            huaweiIdDirty = value.trim() != savedEditorId, connectionFormRevision = it.connectionFormRevision + 1L) }
    }
    override fun saveHuaweiId() {
        if(closed || removalWorking() || removalBlocks(mutable.value.selectedDeviceAddress) || mutable.value.connecting || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.prompt != null || mutable.value.needsPin) return
        val address = mutable.value.selectedDeviceAddress ?: run { message(Tr.tr("请先选择一台手表", "Seleziona prima un watch", "Select a watch first", "Selecciona primero un reloj", "Sélectionnez d'abord une montre", "Selecione primeiro um relógio", "Selecione primeiro um relógio", "Първо изберете часовник", "Önce bir saat seçin", "Wähle zuerst eine Uhr aus", "Сначала выберите часы")); return }
        val id = mutable.value.huaweiIdInput.trim()
        if(id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 128 || id.any(Char::isISOControl) || (id.all(Char::isDigit) && id.length > 19)) {
            message(Tr.tr("请输入有效的华为 ID", "Inserisci un ID Huawei valido", "Enter a valid Huawei ID", "Introduce un ID de Huawei válido", "Saisissez un ID Huawei valide", "Introduza um ID Huawei válido", "Insira um ID Huawei válido", "Въведете валиден Huawei ID", "Geçerli bir Huawei ID girin", "Gib eine gültige Huawei-ID ein", "Введите правильный Huawei ID")); return
        }
        runCatching { runtime.secure.saveHuaweiId(address, id) }.onSuccess {
            savedEditorId = id
            mutable.update { it.copy(huaweiIdInput = id, huaweiIdDirty = false, accountConfigured = true,
                connectionFormRevision = it.connectionFormRevision + 1L) }
            message(if(mutable.value.connected) Tr.tr("华为 ID 已保存，下次连接时生效", "ID Huawei salvato, sarà usato alla prossima connessione", "Huawei ID saved, it will be used on the next connection", "ID de Huawei guardado, se usará en la próxima conexión", "ID Huawei enregistré, il sera utilisé à la prochaine connexion", "ID Huawei guardado, será usado na próxima ligação", "ID Huawei salvo, será usado na próxima conexão", "Huawei ID е запазен и ще се използва при следващото свързване", "Huawei ID kaydedildi, sonraki bağlantıda kullanılacak", "Huawei-ID gespeichert, sie wird bei der nächsten Verbindung verwendet", "Huawei ID сохранён, он будет использован при следующем подключении") else Tr.tr("华为 ID 已保存", "ID Huawei salvato", "Huawei ID saved", "ID de Huawei guardado", "ID Huawei enregistré", "ID Huawei guardado", "ID Huawei salvo", "Huawei ID е запазен", "Huawei ID kaydedildi", "Huawei-ID gespeichert", "Huawei ID сохранён"))
        }.onFailure { message(Tr.tr("华为 ID 保存失败：${Core.code(it)}", "Salvataggio dell'ID Huawei non riuscito: ${Core.code(it)}", "Couldn't save the Huawei ID: ${Core.code(it)}", "No se pudo guardar el ID de Huawei: ${Core.code(it)}", "Impossible d'enregistrer l'ID Huawei : ${Core.code(it)}", "Não foi possível guardar o ID Huawei: ${Core.code(it)}", "Não foi possível salvar o ID Huawei: ${Core.code(it)}", "Huawei ID не може да се запази: ${Core.code(it)}", "Huawei ID kaydedilemedi: ${Core.code(it)}", "Huawei-ID konnte nicht gespeichert werden: ${Core.code(it)}", "Не удалось сохранить Huawei ID: ${Core.code(it)}")) }
    }
    // 该边界不切换设备或读取全局编辑草稿。持久 pending 只阻断目标 MAC，短工作仍全局互斥。
    private fun canAccessDeviceHuaweiId(address: String, epoch: Long): Boolean {
        val ui = mutable.value
        val removal = runtime.unpair.snapshot()
        // 清理可在 main 回报之前全部完成；旧窗口须先消费最新清理代次，不能借新 generation 重建资料。
        return !closed && removalRevision == removal.revision && ui.unpairToken == removal.token &&
            epoch == runtime.connection.epoch() && ui.connectionEpoch == epoch &&
            knownUnpairDevice(address) != null && !ui.unpairWorking && !removalWorking() && !removalBlocks(address) &&
            queuedConnectEpoch == null && !ui.connecting && !preparing && filePickToken == null &&
            startingOperation == null && deletionPreparation == null && !replacementActive() && !runtime.watchfaces.busy() && !ui.operation.active && !ui.pickingHwt &&
            pending == null && ui.prompt == null && !ui.needsPin
    }
    private fun deviceHuaweiIdSnapshotCurrent(address: String, epoch: Long, snapshot: AppRuntime.Snapshot): Boolean =
        canAccessDeviceHuaweiId(address, epoch) && snapshot.epoch == epoch && !snapshot.connecting && !snapshot.active && !snapshot.needsPin
    override fun readHuaweiIdForDevice(device: NearbyWatch): String? {
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message(Tr.tr("设备地址无效", "Indirizzo del dispositivo non valido", "Invalid device address", "Dirección del dispositivo no válida", "Adresse de l'appareil non valide", "Endereço do dispositivo inválido", "Endereço do dispositivo inválido", "Невалиден адрес на устройството", "Geçersiz cihaz adresi", "Ungültige Geräteadresse", "Неверный адрес устройства")); return null }
        val snapshot = runtime.snapshot()
        val epoch = snapshot.epoch
        if(!deviceHuaweiIdSnapshotCurrent(address, epoch, snapshot)) return null
        return runCatching {
            val journal = runtime.secure.removals()
            val generation = journal.generation(address)
            // 不在 journal 锁内读取 runtime.snapshot，避免与运行时发布的锁顺序相反。
            val id = synchronized(DeviceRemovalJournal.LOCK) {
                if(!canAccessDeviceHuaweiId(address, epoch) || !journal.usable(address, generation)) return@synchronized null
                runtime.secure.huaweiId(address)
            } ?: return@runCatching null
            if(deviceHuaweiIdSnapshotCurrent(address, epoch, runtime.snapshot()) && journal.usable(address, generation)) id else null
        }.getOrElse { message(Tr.tr("无法读取该设备的华为 ID：${Core.code(it)}", "Impossibile leggere l'ID Huawei di questo dispositivo: ${Core.code(it)}", "Couldn't read this device's Huawei ID: ${Core.code(it)}", "No se pudo leer el ID de Huawei de este dispositivo: ${Core.code(it)}", "Impossible de lire l'ID Huawei de cet appareil : ${Core.code(it)}", "Não foi possível ler o ID Huawei deste dispositivo: ${Core.code(it)}", "Não foi possível ler o ID Huawei deste dispositivo: ${Core.code(it)}", "Huawei ID на това устройство не може да се прочете: ${Core.code(it)}", "Bu cihazın Huawei ID'si okunamadı: ${Core.code(it)}", "Die Huawei-ID dieses Geräts konnte nicht gelesen werden: ${Core.code(it)}", "Не удалось прочитать Huawei ID этого устройства: ${Core.code(it)}")); null }
    }
    override fun saveHuaweiIdForDevice(device: NearbyWatch, value: String): Boolean {
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message(Tr.tr("设备地址无效", "Indirizzo del dispositivo non valido", "Invalid device address", "Dirección del dispositivo no válida", "Adresse de l'appareil non valide", "Endereço do dispositivo inválido", "Endereço do dispositivo inválido", "Невалиден адрес на устройството", "Geçersiz cihaz adresi", "Ungültige Geräteadresse", "Неверный адрес устройства")); return false }
        val snapshot = runtime.snapshot()
        val epoch = snapshot.epoch
        if(!deviceHuaweiIdSnapshotCurrent(address, epoch, snapshot)) return false
        val id = value.trim()
        if(id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 128 || id.any(Char::isISOControl) || (id.all(Char::isDigit) && id.length > 19)) {
            message(Tr.tr("请输入有效的华为 ID", "Inserisci un ID Huawei valido", "Enter a valid Huawei ID", "Introduce un ID de Huawei válido", "Saisissez un ID Huawei valide", "Introduza um ID Huawei válido", "Insira um ID Huawei válido", "Въведете валиден Huawei ID", "Geçerli bir Huawei ID girin", "Gib eine gültige Huawei-ID ein", "Введите правильный Huawei ID")); return false
        }
        return runCatching {
            val journal = runtime.secure.removals()
            val generation = journal.generation(address)
            val saved = synchronized(DeviceRemovalJournal.LOCK) {
                // 代次核对与真实写入共享现有锁；清理完成后的旧回调不能重建已删除资料。
                if(!canAccessDeviceHuaweiId(address, epoch) || !journal.usable(address, generation)) return@synchronized false
                runtime.secure.saveHuaweiId(address, id)
                true
            }
            if(!saved || !deviceHuaweiIdSnapshotCurrent(address, epoch, runtime.snapshot()) || !journal.usable(address, generation)) return@runCatching false
            if(mutable.value.selectedDeviceAddress?.equals(address, true) == true) {
                savedEditorId = id
                mutable.update { current ->
                    if(current.selectedDeviceAddress?.equals(address, true) == true) current.copy(huaweiIdInput = id,
                        huaweiIdDirty = false, accountConfigured = true, connectionFormRevision = current.connectionFormRevision + 1L)
                    else current
                }
            }
            message(if(mutable.value.connected && runtime.connection.address().equals(address, true)) Tr.tr("华为 ID 已保存，下次连接时生效", "ID Huawei salvato, sarà usato alla prossima connessione", "Huawei ID saved, it will be used on the next connection", "ID de Huawei guardado, se usará en la próxima conexión", "ID Huawei enregistré, il sera utilisé à la prochaine connexion", "ID Huawei guardado, será usado na próxima ligação", "ID Huawei salvo, será usado na próxima conexão", "Huawei ID е запазен и ще се използва при следващото свързване", "Huawei ID kaydedildi, sonraki bağlantıda kullanılacak", "Huawei-ID gespeichert, sie wird bei der nächsten Verbindung verwendet", "Huawei ID сохранён, он будет использован при следующем подключении") else Tr.tr("华为 ID 已保存", "ID Huawei salvato", "Huawei ID saved", "ID de Huawei guardado", "ID Huawei enregistré", "ID Huawei guardado", "ID Huawei salvo", "Huawei ID е запазен", "Huawei ID kaydedildi", "Huawei-ID gespeichert", "Huawei ID сохранён"))
            true
        }.getOrElse { message(Tr.tr("华为 ID 保存失败：${Core.code(it)}", "Salvataggio dell'ID Huawei non riuscito: ${Core.code(it)}", "Couldn't save the Huawei ID: ${Core.code(it)}", "No se pudo guardar el ID de Huawei: ${Core.code(it)}", "Impossible d'enregistrer l'ID Huawei : ${Core.code(it)}", "Não foi possível guardar o ID Huawei: ${Core.code(it)}", "Não foi possível salvar o ID Huawei: ${Core.code(it)}", "Huawei ID не може да се запази: ${Core.code(it)}", "Huawei ID kaydedilemedi: ${Core.code(it)}", "Huawei-ID konnte nicht gespeichert werden: ${Core.code(it)}", "Не удалось сохранить Huawei ID: ${Core.code(it)}")); false }
    }
    override fun setAutoConnect(enabled: Boolean) {
        if(closed) return
        runCatching { savedWatches.setAutoConnect(enabled) }.onSuccess { mutable.update { it.copy(autoConnect = enabled) } }
            .onFailure { message(Tr.tr("自动连接设置保存失败：${Core.code(it)}", "Salvataggio dell'impostazione di connessione automatica non riuscito: ${Core.code(it)}", "Couldn't save the auto-connect setting: ${Core.code(it)}", "No se pudo guardar el ajuste de conexión automática: ${Core.code(it)}", "Impossible d'enregistrer le paramètre de connexion automatique : ${Core.code(it)}", "Não foi possível guardar a definição de ligação automática: ${Core.code(it)}", "Não foi possível salvar a configuração de conexão automática: ${Core.code(it)}", "Настройката за автоматично свързване не може да се запази: ${Core.code(it)}", "Otomatik bağlanma ayarı kaydedilemedi: ${Core.code(it)}", "Die Einstellung für automatisches Verbinden konnte nicht gespeichert werden: ${Core.code(it)}", "Не удалось сохранить настройку автоподключения: ${Core.code(it)}")) }
    }
    override fun introductionInteractionIsCurrent(): Boolean {
        if(closed || !UiPolicy.showIntroduction(mutable.value) || removalWorking() ||
            queuedConnectEpoch != null || preparing || filePickToken != null || startingOperation != null || deletionPreparation != null || pending != null || runtime.watchfaces.busy()) return false
        val current = runtime.snapshot()
        return current.epoch == mutable.value.connectionEpoch && !current.connecting && !current.active && !current.needsPin &&
            current.code != "REBIND_CONFIRM" && current.replacementDialog == null && current.deletionDialog == null
    }
    override fun acknowledgeIntroduction(): Boolean {
        if(!introductionInteractionIsCurrent()) return false
        if(!introductionRead.acknowledge()) { message(Tr.tr("说明已读状态保存失败，请重试", "Impossibile salvare lo stato di lettura delle istruzioni, riprova", "Couldn't save the instructions read state, try again", "No se pudo guardar que leíste las instrucciones, inténtalo de nuevo", "Impossible d'enregistrer la lecture des instructions, réessayez", "Não foi possível guardar a leitura das instruções, tente de novo", "Não foi possível salvar a leitura das instruções, tente de novo", "Не може да се запази, че сте прочели инструкциите, опитайте пак", "Talimatların okunduğu bilgisi kaydedilemedi, tekrar deneyin", "Der Lesestatus der Hinweise konnte nicht gespeichert werden, versuche es erneut", "Не удалось сохранить отметку о прочтении инструкции, повторите")); return false }
        if(closed) return false
        mutable.update { it.copy(introductionPending = false) }
        return true
    }
    override fun setAutoCheckUpdate(enabled: Boolean) {
        if(closed) return
        if(prefs.edit().putBoolean("auto_check_update", enabled).commit()) mutable.update { it.copy(autoCheckUpdate = enabled) }
        else message(Tr.tr("自动检查更新设置保存失败", "Impossibile salvare l'impostazione di controllo automatico aggiornamenti", "Couldn't save the automatic update check setting", "No se pudo guardar el ajuste de comprobación automática de actualizaciones", "Impossible d'enregistrer le paramètre de vérification automatique des mises à jour", "Não foi possível guardar a definição de verificação automática de atualizações", "Não foi possível salvar a configuração de verificação automática de atualizações", "Настройката за автоматична проверка на актуализации не може да се запази", "Otomatik güncelleme denetimi ayarı kaydedilemedi", "Die Einstellung für die automatische Update-Prüfung konnte nicht gespeichert werden", "Не удалось сохранить настройку автоматической проверки обновлений"))
    }
    fun checkUpdatesAtStartup() {
        if(startupUpdateTaken) return
        startupUpdateTaken = true
        if(mutable.value.autoCheckUpdate) checkUpdatesOwned(automatic = true)
    }
    override fun checkUpdates() = checkUpdatesOwned(automatic = false)
    override fun dismissUpdate() { if(!closed) mutable.update { it.copy(pendingUpdate = null) } }
    private fun checkUpdatesOwned(automatic: Boolean) {
        if(closed || mutable.value.checkingUpdate) return
        mutable.update { it.copy(checkingUpdate = true, updateMessage = Tr.tr("正在检查更新", "Controllo aggiornamenti in corso", "Checking for updates", "Comprobando actualizaciones", "Vérification des mises à jour", "A verificar atualizações", "Verificando atualizações", "Проверка за актуализации", "Güncellemeler denetleniyor", "Nach Updates wird gesucht", "Проверка обновлений")) }
        try { updateIo.execute {
            val result = UpdateChecker.fetchLatest()
            main.post {
                if(closed) return@post
                val current = mutable.value
                val found = (result as? UpdateResult.Available)?.info?.takeIf { UpdateChecker.isNewer(it.version, current.appVersion) }
                val description = when(result) {
                    is UpdateResult.Available -> if(found != null) Tr.tr("发现新版本 ${found.version}", "Nuova versione disponibile: ${found.version}", "New version available: ${found.version}", "Nueva versión disponible: ${found.version}", "Nouvelle version disponible : ${found.version}", "Nova versão disponível: ${found.version}", "Nova versão disponível: ${found.version}", "Налична е нова версия: ${found.version}", "Yeni sürüm mevcut: ${found.version}", "Neue Version verfügbar: ${found.version}", "Доступна новая версия: ${found.version}") else Tr.tr("已是最新版本（${current.appVersion}）", "Hai già l'ultima versione (${current.appVersion})", "You already have the latest version (${current.appVersion})", "Ya tienes la última versión (${current.appVersion})", "Vous avez déjà la dernière version (${current.appVersion})", "Já tem a versão mais recente (${current.appVersion})", "Você já tem a versão mais recente (${current.appVersion})", "Вече имате най-новата версия (${current.appVersion})", "Zaten en son sürüme sahipsiniz (${current.appVersion})", "Du hast bereits die neueste Version (${current.appVersion})", "У вас уже последняя версия (${current.appVersion})")
                    UpdateResult.NoRelease -> Tr.tr("项目暂未发布发行版本", "Il progetto non ha ancora pubblicato release", "The project hasn't published any release yet", "El proyecto aún no ha publicado ninguna versión", "Le projet n'a encore publié aucune version", "O projeto ainda não publicou nenhuma versão", "O projeto ainda não publicou nenhuma versão", "Проектът още не е публикувал версия", "Proje henüz bir sürüm yayınlamadı", "Das Projekt hat noch keine Version veröffentlicht", "Проект ещё не опубликовал ни одного выпуска")
                    is UpdateResult.Failure -> result.message
                }
                runtime.logs.record(Tr.tr("更新检查", "Controllo aggiornamenti", "Update check", "Comprobación de actualizaciones", "Vérification des mises à jour", "Verificação de atualizações", "Verificação de atualizações", "Проверка за актуализации", "Güncelleme denetimi", "Update-Prüfung", "Проверка обновлений"), description)
                mutable.update { it.copy(checkingUpdate = false, updateMessage = description,
                    pendingUpdate = when {
                        automatic && !it.autoCheckUpdate -> it.pendingUpdate
                        result is UpdateResult.Failure -> it.pendingUpdate
                        else -> found
                    }) }
            }
        } } catch(_: RejectedExecutionException) {
            mutable.update { it.copy(checkingUpdate = false, updateMessage = Tr.tr("检查更新未开始，请重新打开应用后重试", "Controllo aggiornamenti non avviato, riapri l'app e riprova", "Update check not started, reopen the app and try again", "Comprobación de actualizaciones no iniciada, vuelve a abrir la app e inténtalo", "Vérification des mises à jour non démarrée, rouvrez l'app et réessayez", "Verificação de atualizações não iniciada, volte a abrir a app e tente de novo", "Verificação de atualizações não iniciada, abra o app de novo e tente outra vez", "Проверката за актуализации не е започнала, отворете приложението отново и опитайте пак", "Güncelleme denetimi başlamadı, uygulamayı yeniden açıp tekrar deneyin", "Update-Prüfung nicht gestartet, öffne die App erneut und versuche es noch einmal", "Проверка обновлений не начата, откройте приложение заново и повторите")) }
        }
    }
    override fun scan() {
        if(closed || mutable.value.introductionPending || removalWorking()) return
        if(!scanner.hasPermissions()) { message(Tr.tr("请授予附近设备权限；Android 11 或更早系统还需定位权限", "Concedi l'autorizzazione Dispositivi nelle vicinanze; su Android 11 o precedenti serve anche la posizione", "Grant the Nearby devices permission; Android 11 or earlier also needs location", "Concede el permiso de Dispositivos cercanos; Android 11 o anterior también necesita la ubicación", "Accordez l'autorisation Appareils à proximité ; Android 11 ou antérieur nécessite aussi la localisation", "Conceda a permissão Dispositivos próximos; o Android 11 ou anterior também precisa da localização", "Conceda a permissão Dispositivos por perto; o Android 11 ou anterior também precisa da localização", "Дайте разрешението „Устройства наблизо“; Android 11 и по-стари версии изискват и местоположение", "Yakındaki cihazlar iznini verin; Android 11 veya öncesi konum da gerektirir", "Erteile die Berechtigung „Geräte in der Nähe“; Android 11 oder älter braucht auch den Standort", "Выдайте разрешение «Устройства поблизости»; Android 11 и старше также требует геолокацию")); return }
        val generation = ++scanGeneration
        mutable.update { it.copy(scanning = true, statusMessage = Tr.tr("搜索附近设备及已有系统配对", "Ricerca dei dispositivi vicini e già abbinati nel sistema", "Searching nearby devices and those already paired in the system", "Buscando dispositivos cercanos y los ya emparejados en el sistema", "Recherche des appareils à proximité et de ceux déjà associés au système", "A procurar dispositivos próximos e os já emparelhados no sistema", "Procurando dispositivos por perto e os já pareados no sistema", "Търсене на устройства наблизо и вече сдвоени в системата", "Yakındaki cihazlar ve sistemde zaten eşleştirilmiş olanlar aranıyor", "Suche nach Geräten in der Nähe und bereits im System gekoppelten", "Поиск устройств поблизости и уже сопряжённых в системе")) }
        scanner.start(object : WatchScanner.Listener {
            override fun onItems(items: MutableList<WatchScanner.Item>?) {
                val result = items.orEmpty().map { NearbyWatch(it.address, it.name, it.bonded, it.rssi, if(it.bleOnly) Tr.tr("低功耗蓝牙", "Bluetooth Low Energy", "Bluetooth Low Energy", "Bluetooth de bajo consumo", "Bluetooth basse consommation", "Bluetooth de baixo consumo", "Bluetooth de baixa energia", "Bluetooth с ниска консумация", "Bluetooth Düşük Enerji", "Bluetooth Low Energy", "Bluetooth Low Energy") else Tr.tr("经典蓝牙", "Bluetooth classico", "Classic Bluetooth", "Bluetooth clásico", "Bluetooth classique", "Bluetooth clássico", "Bluetooth clássico", "Класически Bluetooth", "Klasik Bluetooth", "Klassisches Bluetooth", "Классический Bluetooth")) }
                main.post { if(!closed && scanGeneration == generation) mutable.update { it.copy(devices = result) } }
            }
            override fun onStopped(reason: String) { main.post { if(!closed && scanGeneration == generation) mutable.update { it.copy(scanning = false, statusMessage = reason) } } }
        })
    }
    override fun stopScan() { scanGeneration++; scanner.stop(); mutable.update { it.copy(scanning = false) } }
    fun platformActionRevisionIsCurrent(expectedRevision: Long): Boolean =
        !closed && !mutable.value.introductionPending && !removalWorking() && mutable.value.connectionFormRevision == expectedRevision
    fun connectionEpochIsCurrent(expectedEpoch: Long): Boolean =
        !closed && runtime.connection.epoch() == expectedEpoch && runtime.snapshot().epoch == expectedEpoch
    fun connectFromPlatform(device: NearbyWatch, huaweiId: String, expectedEpoch: Long, expectedFormRevision: Long) {
        if(closed || mutable.value.connectionFormRevision != expectedFormRevision) return
        connectOwned(device, huaweiId, expectedEpoch)
    }
    // 旧无表单令牌调用只兼容从未进入解除配对生命周期的运行时；不能复活已清理目标。
    fun connectFromPlatform(device: NearbyWatch, huaweiId: String, expectedEpoch: Long) {
        if(runtime.unpair.snapshot().revision == 0L) connectOwned(device, huaweiId, expectedEpoch)
    }
    override fun connect(device: NearbyWatch, huaweiId: String) =
        connectOwned(device, huaweiId, runtime.connection.epoch())
    private fun connectOwned(device: NearbyWatch, huaweiId: String, expectedEpoch: Long) {
        if(closed || mutable.value.introductionPending) return
        if(!connectionEpochIsCurrent(expectedEpoch)) { message(Tr.tr("连接状态已变化，请重新连接所选设备", "Lo stato della connessione è cambiato, riconnetti il dispositivo selezionato", "The connection state changed, reconnect the selected device", "El estado de la conexión cambió, vuelve a conectar el dispositivo seleccionado", "L'état de la connexion a changé, reconnectez l'appareil sélectionné", "O estado da ligação mudou, volte a ligar o dispositivo selecionado", "O status da conexão mudou, conecte de novo o dispositivo selecionado", "Състоянието на връзката се промени, свържете отново избраното устройство", "Bağlantı durumu değişti, seçili cihazı yeniden bağlayın", "Der Verbindungsstatus hat sich geändert, verbinde das ausgewählte Gerät erneut", "Состояние подключения изменилось, переподключите выбранное устройство")); return }
        if(removalWorking() || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy()) { message(Tr.tr("请先结束当前操作", "Termina prima l'operazione in corso", "Finish the current operation first", "Termina primero la operación en curso", "Terminez d'abord l'opération en cours", "Termine primeiro a operação em curso", "Conclua primeiro a operação em andamento", "Първо завършете текущата операция", "Önce süren işlemi bitirin", "Beende zuerst den laufenden Vorgang", "Сначала завершите текущую операцию")); return }
        if(Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { message(Tr.tr("请授予附近设备连接权限", "Concedi l'autorizzazione Dispositivi nelle vicinanze", "Grant the Nearby devices permission", "Concede el permiso de Dispositivos cercanos", "Accordez l'autorisation Appareils à proximité", "Conceda a permissão Dispositivos próximos", "Conceda a permissão Dispositivos por perto", "Дайте разрешението „Устройства наблизо“", "Yakındaki cihazlar iznini verin", "Erteile die Berechtigung „Geräte in der Nähe“", "Выдайте разрешение «Устройства поблизости»")); return }
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message(Tr.tr("设备地址无效", "Indirizzo del dispositivo non valido", "Invalid device address", "Dirección del dispositivo no válida", "Adresse de l'appareil non valide", "Endereço do dispositivo inválido", "Endereço do dispositivo inválido", "Невалиден адрес на устройството", "Geçersiz cihaz adresi", "Ungültige Geräteadresse", "Неверный адрес устройства")); return }
        if(removalBlocks(address)) { message(Tr.tr("此 MAC 的解除配对尚待确认或清理，未重新连接；请重新检查", "L'annullamento abbinamento di questo MAC attende ancora conferma o pulizia; nessuna riconnessione. Ricontrolla", "Unpairing of this MAC is still awaiting confirmation or cleanup; not reconnected. Check again", "El desemparejamiento de esta MAC aún espera confirmación o limpieza; no se ha reconectado. Vuelve a comprobar", "La dissociation de cette adresse MAC attend encore une confirmation ou un nettoyage ; pas de reconnexion. Vérifiez à nouveau", "O desemparelhamento deste MAC ainda aguarda confirmação ou limpeza; não foi religado. Verifique de novo", "O despareamento deste MAC ainda aguarda confirmação ou limpeza; não foi reconectado. Verifique de novo", "Разкачването на този MAC още чака потвърждение или изчистване; не е свързано отново. Проверете пак", "Bu MAC'in eşleştirme kaldırması hâlâ onay veya temizlik bekliyor; yeniden bağlanılmadı. Tekrar kontrol edin", "Die Entkopplung dieser MAC wartet noch auf Bestätigung oder Bereinigung; nicht neu verbunden. Prüfe erneut", "Снятие сопряжения этого MAC ещё ожидает подтверждения или очистки; не переподключено. Проверьте снова")); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message(Tr.tr("无法核实设备资料状态：${Core.code(it)}", "Impossibile verificare lo stato dei dati del dispositivo: ${Core.code(it)}", "Couldn't verify the device data state: ${Core.code(it)}", "No se pudo verificar el estado de los datos del dispositivo: ${Core.code(it)}", "Impossible de vérifier l'état des données de l'appareil : ${Core.code(it)}", "Não foi possível verificar o estado dos dados do dispositivo: ${Core.code(it)}", "Não foi possível verificar o status dos dados do dispositivo: ${Core.code(it)}", "Състоянието на данните на устройството не може да се провери: ${Core.code(it)}", "Cihaz verilerinin durumu doğrulanamadı: ${Core.code(it)}", "Der Status der Gerätedaten konnte nicht geprüft werden: ${Core.code(it)}", "Не удалось проверить состояние данных устройства: ${Core.code(it)}")); return }
        if(generation > 0L && knownUnpairDevice(address) == null) { message(Tr.tr("此设备记录已清除，请重新搜索后连接设备", "I dati di questo dispositivo sono stati cancellati; cercalo di nuovo e connettiti", "This device's data was cleared; search for it again and connect", "Los datos de este dispositivo se borraron; búscalo de nuevo y conéctate", "Les données de cet appareil ont été effacées ; recherchez-le à nouveau et connectez-vous", "Os dados deste dispositivo foram apagados; procure-o de novo e ligue-se", "Os dados deste dispositivo foram apagados; procure-o de novo e conecte", "Данните на това устройство са изтрити; потърсете го отново и се свържете", "Bu cihazın verileri silindi; tekrar arayıp bağlanın", "Die Daten dieses Geräts wurden gelöscht; suche es erneut und verbinde dich", "Данные этого устройства удалены; найдите его снова и подключитесь")); return }
        val id = huaweiId.trim()
        if(id.isNotEmpty() && (id.toByteArray(Charsets.UTF_8).size > 128 || (id.all(Char::isDigit) && id.length > 19))) { message(Tr.tr("华为 ID 长度不支持", "Lunghezza dell'ID Huawei non supportata", "Huawei ID length not supported", "Longitud del ID de Huawei no admitida", "Longueur de l'ID Huawei non prise en charge", "Comprimento do ID Huawei não suportado", "Tamanho do ID Huawei não suportado", "Дължината на Huawei ID не се поддържа", "Huawei ID uzunluğu desteklenmiyor", "Länge der Huawei-ID nicht unterstützt", "Длина Huawei ID не поддерживается")); return }
        if(mutable.value.selectedDeviceAddress != address) selectDevice(device)
        if(id.isEmpty()) {
            // 明确的空输入代表当前设备已加密保存的华为 ID，
            // 不代表界面草稿或另一设备的最近使用值。
            restoreHuaweiId(address)
            if(!mutable.value.accountConfigured) { message(Tr.tr("此手表尚无保存的华为 ID，请输入后连接", "Per questo watch non è salvato alcun ID Huawei; inseriscilo e connettiti", "No Huawei ID saved for this watch; enter one and connect", "No hay ningún ID de Huawei guardado para este reloj; introdúcelo y conéctate", "Aucun ID Huawei enregistré pour cette montre ; saisissez-en un et connectez-vous", "Não há ID Huawei guardado para este relógio; introduza um e ligue-se", "Não há ID Huawei salvo para este relógio; insira um e conecte", "За този часовник няма запазен Huawei ID; въведете го и се свържете", "Bu saat için kayıtlı Huawei ID yok; bir tane girip bağlanın", "Für diese Uhr ist keine Huawei-ID gespeichert; gib eine ein und verbinde dich", "Для этих часов нет сохранённого Huawei ID; введите его и подключитесь")); return }
        } else setHuaweiId(id)
        val requested = device.copy(address = address)
        val revision = mutable.value.connectionFormRevision
        val action = {
            if(closed || mutable.value.introductionPending || expectedEpoch != runtime.connection.epoch() || revision != mutable.value.connectionFormRevision || !runtime.secure.removals().usable(address, generation)) message(Tr.tr("连接状态已变化，请重新连接所选设备", "Lo stato della connessione è cambiato, riconnetti il dispositivo selezionato", "The connection state changed, reconnect the selected device", "El estado de la conexión cambió, vuelve a conectar el dispositivo seleccionado", "L'état de la connexion a changé, reconnectez l'appareil sélectionné", "O estado da ligação mudou, volte a ligar o dispositivo selecionado", "O status da conexão mudou, conecte de novo o dispositivo selecionado", "Състоянието на връзката се промени, свържете отново избраното устройство", "Bağlantı durumu değişti, seçili cihazı yeniden bağlayın", "Der Verbindungsstatus hat sich geändert, verbinde das ausgewählte Gerät erneut", "Состояние подключения изменилось, переподключите выбранное устройство"))
            else if(removalWorking() || removalBlocks(address) || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy()) message(Tr.tr("当前操作尚未结束，未切换连接", "L'operazione in corso non è terminata, connessione non cambiata", "The current operation hasn't finished, connection not switched", "La operación en curso no ha terminado, conexión no cambiada", "L'opération en cours n'est pas terminée, connexion non changée", "A operação em curso não terminou, ligação não alterada", "A operação em andamento não terminou, conexão não alterada", "Текущата операция не е приключила, връзката не е сменена", "Süren işlem bitmedi, bağlantı değiştirilmedi", "Der laufende Vorgang ist nicht beendet, Verbindung nicht gewechselt", "Текущая операция не завершена, подключение не переключено"))
            else {
                stopScan(); invalidatePrompt()
                try {
                    ContextCompat.startForegroundService(app, Intent(app, ConnectionService::class.java))
                    sessionDevice = requested; queuedConnectEpoch = expectedEpoch
                    mutable.update { it.copy(watchName = requested.name, selectedDeviceAddress = address,
                        connectionFormRevision = it.connectionFormRevision + 1L, connecting = true, statusMessage = "") }
                    // 连接层加密保存明确输入的 ID，复用当前设备认证密钥，
                    // 不重置系统配对。
                    runtime.connection.connectIfEpoch(expectedEpoch, address, requested.transport == Tr.tr("低功耗蓝牙", "Bluetooth Low Energy", "Bluetooth Low Energy", "Bluetooth de bajo consumo", "Bluetooth basse consommation", "Bluetooth de baixo consumo", "Bluetooth de baixa energia", "Bluetooth с ниска консумация", "Bluetooth Düşük Enerji", "Bluetooth Low Energy", "Bluetooth Low Energy"), id)
                } catch(e: Exception) {
                    queuedConnectEpoch = null
                    val code = if(e is SecurityException) "BLUETOOTH_PERMISSION_REQUIRED" else Core.code(e)
                    mutable.update { it.copy(connecting = runtime.snapshot().connecting) }
                    message(Tr.tr("连接未开始：$code", "Connessione non avviata: $code", "Connection not started: $code", "Conexión no iniciada: $code", "Connexion non démarrée : $code", "Ligação não iniciada: $code", "Conexão não iniciada: $code", "Свързването не е започнало: $code", "Bağlantı başlamadı: $code", "Verbindung nicht gestartet: $code", "Подключение не начато: $code"))
                }
            }
        }
        if(id.isNotEmpty() && (!id.all(Char::isDigit) || id.length == 11)) prompt("account-warning", Tr.tr("确认华为 ID", "Conferma ID Huawei", "Confirm Huawei ID", "Confirmar ID de Huawei", "Confirmer l'ID Huawei", "Confirmar ID Huawei", "Confirmar ID Huawei", "Потвърждаване на Huawei ID", "Huawei ID'yi onayla", "Huawei-ID bestätigen", "Подтвердить Huawei ID"), Tr.tr("这可能不是华为 ID。请核对与运动健康使用的 ID 是否一致，是否继续使用？", "Potrebbe non essere un ID Huawei. Verifica che sia lo stesso usato in Huawei Health. Vuoi usarlo comunque?", "This may not be a Huawei ID. Check that it's the same one used in Huawei Health. Use it anyway?", "Puede que no sea un ID de Huawei. Comprueba que sea el mismo que usas en Huawei Health. ¿Usarlo de todos modos?", "Ce n'est peut-être pas un ID Huawei. Vérifiez qu'il s'agit du même que dans Huawei Health. L'utiliser quand même ?", "Pode não ser um ID Huawei. Verifique se é o mesmo usado na Huawei Health. Usar mesmo assim?", "Pode não ser um ID Huawei. Verifique se é o mesmo usado no Huawei Health. Usar mesmo assim?", "Това може да не е Huawei ID. Проверете дали е същият като в Huawei Health. Да се използва ли въпреки това?", "Bu bir Huawei ID olmayabilir. Huawei Health'te kullandığınızla aynı olup olmadığını kontrol edin. Yine de kullanılsın mı?", "Das ist möglicherweise keine Huawei-ID. Prüfe, ob es dieselbe wie in Huawei Health ist. Trotzdem verwenden?", "Возможно, это не Huawei ID. Проверьте, что он тот же, что в Huawei Health. Всё равно использовать?"), false, Tr.tr("确认使用", "Usa comunque", "Use anyway", "Usar de todos modos", "Utiliser quand même", "Usar mesmo assim", "Usar mesmo assim", "Използване въпреки това", "Yine de kullan", "Trotzdem verwenden", "Всё равно использовать"), action)
        else action()
    }
    override fun disconnect() {
        if(closed) return
        if(replacementActive()) { message(Tr.tr("请等待当前操作完成", "Attendi il termine dell'operazione in corso", "Wait for the current operation to finish", "Espera a que termine la operación en curso", "Attendez la fin de l'opération en cours", "Aguarde o fim da operação em curso", "Aguarde o fim da operação em andamento", "Изчакайте текущата операция да приключи", "Süren işlemin bitmesini bekleyin", "Warte, bis der laufende Vorgang beendet ist", "Дождитесь завершения текущей операции")); return }
        invalidatePrompt(); mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L) }; runtime.connection.disconnect(); message(Tr.tr("已请求断开连接", "Disconnessione richiesta", "Disconnection requested", "Desconexión solicitada", "Déconnexion demandée", "Desligação pedida", "Desconexão solicitada", "Поискано е прекъсване на връзката", "Bağlantının kesilmesi istendi", "Trennung angefordert", "Отключение запрошено"))
    }
    private fun knownUnpairDevice(address: String): NearbyWatch? =
        mutable.value.devices.firstOrNull { it.address.equals(address, true) }
            ?: mutable.value.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
            ?: sessionDevice?.takeIf { it.address.equals(address, true) && runtime.connection.address().equals(address, true) }
    override fun requestUnpair(device: NearbyWatch) {
        if(closed) return
        if(runtime.unpair.snapshot().pending) { message(Tr.tr("已有一台设备等待解除配对核实，请先重新检查该设备", "Un dispositivo attende già la verifica dell'annullamento abbinamento; ricontrolla prima quello", "A device is already awaiting unpair verification; check that one first", "Ya hay un dispositivo esperando la verificación del desemparejamiento; compruébalo primero", "Un appareil attend déjà la vérification de la dissociation ; vérifiez-le d'abord", "Já há um dispositivo à espera da verificação do desemparelhamento; verifique-o primeiro", "Já há um dispositivo aguardando a verificação do despareamento; verifique-o primeiro", "Вече има устройство, което чака проверка на разкачването; първо проверете него", "Zaten eşleştirme kaldırma doğrulaması bekleyen bir cihaz var; önce onu kontrol edin", "Ein Gerät wartet bereits auf die Prüfung der Entkopplung; prüfe zuerst dieses", "Одно устройство уже ожидает проверки снятия сопряжения; сначала проверьте его")); return }
        if(preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) { message(Tr.tr("请先结束当前表盘操作，未开始解除配对", "Termina prima l'operazione sul quadrante in corso; annullamento abbinamento non avviato", "Finish the current watch face operation first; unpairing not started", "Termina primero la operación de esfera en curso; desemparejamiento no iniciado", "Terminez d'abord l'opération en cours sur le cadran ; dissociation non démarrée", "Termine primeiro a operação em curso no mostrador; desemparelhamento não iniciado", "Conclua primeiro a operação em andamento no mostrador; despareamento não iniciado", "Първо завършете текущата операция с циферблат; разкачването не е започнало", "Önce süren kadran işlemini bitirin; eşleştirme kaldırma başlamadı", "Beende zuerst den laufenden Zifferblatt-Vorgang; Entkopplung nicht gestartet", "Сначала завершите текущую операцию с циферблатом; снятие сопряжения не начато")); return }
        if(pending != null) return
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message(Tr.tr("设备地址无效，未开始解除配对", "Indirizzo del dispositivo non valido; annullamento abbinamento non avviato", "Invalid device address; unpairing not started", "Dirección del dispositivo no válida; desemparejamiento no iniciado", "Adresse de l'appareil non valide ; dissociation non démarrée", "Endereço do dispositivo inválido; desemparelhamento não iniciado", "Endereço do dispositivo inválido; despareamento não iniciado", "Невалиден адрес на устройството; разкачването не е започнало", "Geçersiz cihaz adresi; eşleştirme kaldırma başlamadı", "Ungültige Geräteadresse; Entkopplung nicht gestartet", "Неверный адрес устройства; снятие сопряжения не начато")); return }
        val target = knownUnpairDevice(address) ?: run { message(Tr.tr("请从当前已选择、发现或历史记录中的真实设备发起解除配对", "Avvia l'annullamento abbinamento da un dispositivo reale tra quelli selezionati, trovati o recenti", "Start unpairing from a real device among the selected, found or recent ones", "Inicia el desemparejamiento desde un dispositivo real entre los seleccionados, encontrados o recientes", "Lancez la dissociation depuis un appareil réel parmi ceux sélectionnés, trouvés ou récents", "Inicie o desemparelhamento a partir de um dispositivo real entre os selecionados, encontrados ou recentes", "Inicie o despareamento a partir de um dispositivo real entre os selecionados, encontrados ou recentes", "Започнете разкачването от реално устройство сред избраните, намерените или последните", "Eşleştirme kaldırmayı seçili, bulunan veya son cihazlar arasından gerçek bir cihazdan başlatın", "Starte die Entkopplung von einem echten Gerät unter den ausgewählten, gefundenen oder letzten", "Начните снятие сопряжения с реального устройства среди выбранных, найденных или недавних")); return }
        val epoch = runtime.connection.epoch()
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message(Tr.tr("无法核实待解除配对设备：${Core.code(it)}", "Impossibile verificare il dispositivo in attesa di annullamento abbinamento: ${Core.code(it)}", "Couldn't verify the device awaiting unpairing: ${Core.code(it)}", "No se pudo verificar el dispositivo pendiente de desemparejar: ${Core.code(it)}", "Impossible de vérifier l'appareil en attente de dissociation : ${Core.code(it)}", "Não foi possível verificar o dispositivo a aguardar desemparelhamento: ${Core.code(it)}", "Não foi possível verificar o dispositivo aguardando despareamento: ${Core.code(it)}", "Устройството, чакащо разкачване, не може да се провери: ${Core.code(it)}", "Eşleştirme kaldırma bekleyen cihaz doğrulanamadı: ${Core.code(it)}", "Das Gerät, das auf die Entkopplung wartet, konnte nicht geprüft werden: ${Core.code(it)}", "Не удалось проверить устройство, ожидающее снятия сопряжения: ${Core.code(it)}")); return }
        val token = tokens.incrementAndGet()
        prompt("unpair", Tr.tr("解除配对？", "Annullare l'abbinamento?", "Unpair?", "¿Desemparejar?", "Dissocier ?", "Desemparelhar?", "Desfazer pareamento?", "Разкачване?", "Eşleştirme kaldırılsın mı?", "Entkoppeln?", "Снять сопряжение?"),
            Tr.tr("${target.name}\nMAC：$address\n\n解除系统配对，并清除此设备在腕镜中的记录、华为 ID 和认证资料。手表表盘不受影响。", "${target.name}\nMAC: $address\n\nAnnulla l'abbinamento di sistema e cancella i dati di questo dispositivo in WristMirror: record, ID Huawei e credenziali di autenticazione. I quadranti sul watch non vengono toccati.", "${target.name}\nMAC: $address\n\nRemoves the system pairing and clears this device's data in WristMirror: records, Huawei ID and authentication credentials. The watch faces on the watch are not affected.", "${target.name}\nMAC: $address\n\nElimina el emparejamiento del sistema y borra los datos de este dispositivo en WristMirror: registros, ID de Huawei y credenciales de autenticación. Las esferas del reloj no se ven afectadas.", "${target.name}\nMAC : $address\n\nSupprime l'association système et efface les données de cet appareil dans WristMirror : historique, ID Huawei et identifiants d'authentification. Les cadrans de la montre ne sont pas touchés.", "${target.name}\nMAC: $address\n\nRemove o emparelhamento do sistema e apaga os dados deste dispositivo no WristMirror: registos, ID Huawei e credenciais de autenticação. Os mostradores do relógio não são afetados.", "${target.name}\nMAC: $address\n\nRemove o pareamento do sistema e apaga os dados deste dispositivo no WristMirror: registros, ID Huawei e credenciais de autenticação. Os mostradores do relógio não são afetados.", "${target.name}\nMAC: $address\n\nПремахва системното сдвояване и изтрива данните на това устройство в WristMirror: записи, Huawei ID и данни за удостоверяване. Циферблатите на часовника не се засягат.", "${target.name}\nMAC: $address\n\nSistem eşleştirmesini kaldırır ve bu cihazın WristMirror'daki verilerini siler: kayıtlar, Huawei ID ve kimlik doğrulama bilgileri. Saatteki kadranlar etkilenmez.", "${target.name}\nMAC: $address\n\nEntfernt die Systemkopplung und löscht die Daten dieses Geräts in WristMirror: Einträge, Huawei-ID und Anmeldedaten. Die Zifferblätter auf der Uhr sind nicht betroffen.", "${target.name}\nMAC: $address\n\nСнимает системное сопряжение и удаляет данные этого устройства в WristMirror: записи, Huawei ID и данные аутентификации. Циферблаты на часах не затрагиваются."),
            true, Tr.tr("解除配对", "Annulla abbinamento", "Unpair", "Desemparejar", "Dissocier", "Desemparelhar", "Desfazer pareamento", "Разкачване", "Eşleştirmeyi kaldır", "Entkoppeln", "Снять сопряжение"), {
                if(closed || epoch != runtime.connection.epoch() || knownUnpairDevice(address) == null || !runtime.secure.removals().usable(address, generation)) throw Core.Fault("UNPAIR_TARGET_CHANGED")
                if(preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
                runtime.logs.record("UNPAIR_UI_CONFIRM", "target_known=true token_consumed=true no_watchface_command=true")
                if(!runtime.unpair.request(address, token)) throw Core.Fault("UNPAIR_NOT_STARTED")
                retireRemovalUi(address, token)
                applyRemoval(runtime.unpair.snapshot())
            }, ownedToken = token)
    }
    override fun openUnpairSettings() { if(!closed) runtime.unpair.openSettings() }
    override fun retryUnpair() { if(!closed && !preparing && startingOperation == null && deletionPreparation == null && !replacementActive()) runtime.unpair.retry() }
    override fun cancelUnpair() { if(!closed && !preparing && startingOperation == null && deletionPreparation == null && !replacementActive()) runtime.unpair.cancel() }
    override fun rebindNow() {
        if(closed) return
        if(mutable.value.connected || mutable.value.connecting || runtime.connection.ready()) {
            message(Tr.tr("请先断开或取消连接，再重新协议绑定", "Disconnetti o annulla prima la connessione, poi riassocia il protocollo", "Disconnect or cancel the connection first, then re-bind the protocol", "Desconecta o cancela primero la conexión y luego vuelve a vincular el protocolo", "Déconnectez ou annulez d'abord la connexion, puis réassociez le protocole", "Desligue ou cancele primeiro a ligação e depois volte a associar o protocolo", "Desconecte ou cancele primeiro a conexão e depois vincule o protocolo de novo", "Първо прекъснете или отменете връзката, после обвържете протокола наново", "Önce bağlantıyı kesin veya iptal edin, sonra protokolü yeniden bağlayın", "Trenne oder breche zuerst die Verbindung ab und binde dann das Protokoll neu", "Сначала отключитесь или отмените подключение, затем заново привяжите протокол")); return
        }
        if(mutable.value.introductionPending || removalWorking() || preparing || startingOperation != null ||
            deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) {
            message(Tr.tr("请先结束当前操作", "Termina prima l'operazione in corso", "Finish the current operation first", "Termina primero la operación en curso", "Terminez d'abord l'opération en cours", "Termine primeiro a operação em curso", "Conclua primeiro a operação em andamento", "Първо завършете текущата операция", "Önce süren işlemi bitirin", "Beende zuerst den laufenden Vorgang", "Сначала завершите текущую операцию")); return
        }
        if(Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            message(Tr.tr("请授予附近设备连接权限", "Concedi l'autorizzazione Dispositivi nelle vicinanze", "Grant the Nearby devices permission", "Concede el permiso de Dispositivos cercanos", "Accordez l'autorisation Appareils à proximité", "Conceda a permissão Dispositivos próximos", "Conceda a permissão Dispositivos por perto", "Дайте разрешението „Устройства наблизо“", "Yakındaki cihazlar iznini verin", "Erteile die Berechtigung „Geräte in der Nähe“", "Выдайте разрешение «Устройства поблизости»")); return
        }
        val address = SavedWatchStore.canonicalAddress(mutable.value.selectedDeviceAddress ?: runtime.connection.address())
            ?: run { message(Tr.tr("请先选择设备，再重新协议绑定", "Seleziona prima un dispositivo, poi riassocia il protocollo", "Select a device first, then re-bind the protocol", "Selecciona primero un dispositivo y luego vuelve a vincular el protocolo", "Sélectionnez d'abord un appareil, puis réassociez le protocole", "Selecione primeiro um dispositivo e depois volte a associar o protocolo", "Selecione primeiro um dispositivo e depois vincule o protocolo de novo", "Първо изберете устройство, после обвържете протокола наново", "Önce bir cihaz seçin, sonra protokolü yeniden bağlayın", "Wähle zuerst ein Gerät aus und binde dann das Protokoll neu", "Сначала выберите устройство, затем заново привяжите протокол")); return }
        if(removalBlocks(address)) { message(Tr.tr("此设备的解除配对尚待确认或清理，未重新绑定", "L'annullamento abbinamento di questo dispositivo attende ancora conferma o pulizia; riassociazione non eseguita", "Unpairing of this device is still awaiting confirmation or cleanup; not re-bound", "El desemparejamiento de este dispositivo aún espera confirmación o limpieza; no se ha vuelto a vincular", "La dissociation de cet appareil attend encore une confirmation ou un nettoyage ; pas de réassociation", "O desemparelhamento deste dispositivo ainda aguarda confirmação ou limpeza; não foi associado de novo", "O despareamento deste dispositivo ainda aguarda confirmação ou limpeza; não foi vinculado de novo", "Разкачването на това устройство още чака потвърждение или изчистване; не е обвързано наново", "Bu cihazın eşleştirme kaldırması hâlâ onay veya temizlik bekliyor; yeniden bağlanmadı", "Die Entkopplung dieses Geräts wartet noch auf Bestätigung oder Bereinigung; nicht neu gebunden", "Снятие сопряжения этого устройства ещё ожидает подтверждения или очистки; повторная привязка не выполнена")); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse {
            message(Tr.tr("无法核实设备资料状态：${Core.code(it)}", "Impossibile verificare lo stato dei dati del dispositivo: ${Core.code(it)}", "Couldn't verify the device data state: ${Core.code(it)}", "No se pudo verificar el estado de los datos del dispositivo: ${Core.code(it)}", "Impossible de vérifier l'état des données de l'appareil : ${Core.code(it)}", "Não foi possível verificar o estado dos dados do dispositivo: ${Core.code(it)}", "Não foi possível verificar o status dos dados do dispositivo: ${Core.code(it)}", "Състоянието на данните на устройството не може да се провери: ${Core.code(it)}", "Cihaz verilerinin durumu doğrulanamadı: ${Core.code(it)}", "Der Status der Gerätedaten konnte nicht geprüft werden: ${Core.code(it)}", "Не удалось проверить состояние данных устройства: ${Core.code(it)}")); return
        }
        if(generation > 0L && knownUnpairDevice(address) == null) { message(Tr.tr("此设备记录已清除，请重新搜索后连接设备", "I dati di questo dispositivo sono stati cancellati; cercalo di nuovo e connettiti", "This device's data was cleared; search for it again and connect", "Los datos de este dispositivo se borraron; búscalo de nuevo y conéctate", "Les données de cet appareil ont été effacées ; recherchez-le à nouveau et connectez-vous", "Os dados deste dispositivo foram apagados; procure-o de novo e ligue-se", "Os dados deste dispositivo foram apagados; procure-o de novo e conecte", "Данните на това устройство са изтрити; потърсете го отново и се свържете", "Bu cihazın verileri silindi; tekrar arayıp bağlanın", "Die Daten dieses Geräts wurden gelöscht; suche es erneut und verbinde dich", "Данные этого устройства удалены; найдите его снова и подключитесь")); return }
        val transport = mutable.value.savedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.transport
            ?: mutable.value.devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.transport
        val bleOnly = transport == Tr.tr("低功耗蓝牙", "Bluetooth Low Energy", "Bluetooth Low Energy", "Bluetooth de bajo consumo", "Bluetooth basse consommation", "Bluetooth de baixo consumo", "Bluetooth de baixa energia", "Bluetooth с ниска консумация", "Bluetooth Düşük Enerji", "Bluetooth Low Energy", "Bluetooth Low Energy")
        prompt("rebind_now", Tr.tr("重新协议绑定？", "Riassociare il protocollo?", "Re-bind the protocol?", "¿Volver a vincular el protocolo?", "Réassocier le protocole ?", "Voltar a associar o protocolo?", "Vincular o protocolo de novo?", "Ново обвързване на протокола?", "Protokol yeniden bağlansın mı?", "Protokoll neu binden?", "Заново привязать протокол?"),
            Tr.tr("会清除本应用为该手表保存的协议密钥，并立即重新进行协议配对。手表切换省电/全能模式后通常需要这一步。\n重新绑定可能改变手表与其他应用的连接关系，是否继续？", "Verranno cancellate le chiavi di protocollo salvate dall'app per questo watch e verrà subito ripetuto l'abbinamento del protocollo. Di solito serve dopo il passaggio del watch tra modalità risparmio energetico e modalità completa.\nLa riassociazione potrebbe cambiare il modo in cui il watch si collega ad altre app. Continuare?", "The protocol keys saved by the app for this watch will be cleared and protocol pairing will be redone right away. This is usually needed after switching the watch between power-saving and full mode.\nRe-binding may change how the watch connects to other apps. Continue?", "Se borrarán las claves de protocolo que la app guardó para este reloj y se repetirá enseguida el emparejamiento del protocolo. Suele ser necesario tras cambiar el reloj entre modo de ahorro de energía y modo completo.\nVolver a vincular puede cambiar cómo se conecta el reloj con otras apps. ¿Continuar?", "Les clés de protocole enregistrées par l'app pour cette montre seront effacées et l'association du protocole sera refaite aussitôt. C'est généralement nécessaire après avoir basculé la montre entre le mode économie d'énergie et le mode complet.\nLa réassociation peut changer la façon dont la montre se connecte aux autres apps. Continuer ?", "As chaves de protocolo guardadas pela app para este relógio serão apagadas e o emparelhamento do protocolo será refeito de imediato. Normalmente é necessário depois de mudar o relógio entre o modo de poupança de energia e o modo completo.\nVoltar a associar pode mudar a forma como o relógio se liga a outras apps. Continuar?", "As chaves de protocolo salvas pelo app para este relógio serão apagadas e o pareamento do protocolo será refeito na hora. Normalmente é necessário depois de mudar o relógio entre o modo de economia de energia e o modo completo.\nVincular de novo pode mudar a forma como o relógio se conecta a outros apps. Continuar?", "Ключовете на протокола, запазени от приложението за този часовник, ще бъдат изтрити и сдвояването на протокола ще се извърши веднага отново. Обикновено е нужно след превключване на часовника между енергоспестяващ и пълен режим.\nНовото обвързване може да промени начина, по който часовникът се свързва с други приложения. Продължаване?", "Uygulamanın bu saat için kaydettiği protokol anahtarları silinecek ve protokol eşleştirmesi hemen yeniden yapılacak. Bu genellikle saat güç tasarrufu ve tam mod arasında değiştirildikten sonra gerekir.\nYeniden bağlama, saatin diğer uygulamalara bağlanma şeklini değiştirebilir. Devam edilsin mi?", "Die von der App für diese Uhr gespeicherten Protokollschlüssel werden gelöscht und die Protokollkopplung wird sofort neu durchgeführt. Das ist meist nach dem Wechsel der Uhr zwischen Energiesparmodus und Vollmodus nötig.\nDas Neubinden kann ändern, wie sich die Uhr mit anderen Apps verbindet. Fortfahren?", "Сохранённые приложением ключи протокола для этих часов будут удалены, и сопряжение протокола сразу выполнится заново. Обычно это нужно после переключения часов между режимом энергосбережения и полным режимом.\nПовторная привязка может изменить подключение часов к другим приложениям. Продолжить?"),
            true, Tr.tr("重新绑定", "Riassocia", "Re-bind", "Volver a vincular", "Réassocier", "Voltar a associar", "Vincular de novo", "Ново обвързване", "Yeniden bağla", "Neu binden", "Привязать заново"), {
                if(closed || removalWorking() || removalBlocks(address) || !runtime.secure.removals().usable(address, generation))
                    throw Core.Fault("REBIND_TARGET_CHANGED")
                stopScan(); invalidatePrompt()
                try {
                    ContextCompat.startForegroundService(app, Intent(app, ConnectionService::class.java))
                    sessionDevice = NearbyWatch(address, mutable.value.watchName.ifBlank { Tr.tr("未知名称设备", "Dispositivo senza nome", "Device with unknown name", "Dispositivo de nombre desconocido", "Appareil au nom inconnu", "Dispositivo de nome desconhecido", "Dispositivo de nome desconhecido", "Устройство с неизвестно име", "Adı bilinmeyen cihaz", "Gerät mit unbekanntem Namen", "Устройство с неизвестным именем") }, false)
                    mutable.update { it.copy(selectedDeviceAddress = address, connectionFormRevision = it.connectionFormRevision + 1L,
                        connecting = true, statusMessage = "") }
                    runtime.logs.record("REBIND_UI_CONFIRM", "target_known=true transport=${if(bleOnly) "gatt" else "rfcomm_secure"} no_auto_retry=true")
                    runtime.connection.rebind(address, bleOnly)
                } catch(e: Exception) {
                    val code = if(e is SecurityException) "BLUETOOTH_PERMISSION_REQUIRED" else Core.code(e)
                    mutable.update { it.copy(connecting = runtime.snapshot().connecting) }
                    message(Tr.tr("重新绑定未开始：$code", "Riassociazione non avviata: $code", "Re-binding not started: $code", "Nueva vinculación no iniciada: $code", "Réassociation non démarrée : $code", "Nova associação não iniciada: $code", "Novo vínculo não iniciado: $code", "Новото обвързване не е започнало: $code", "Yeniden bağlama başlamadı: $code", "Neubinden nicht gestartet: $code", "Повторная привязка не начата: $code"))
                }
            }, {})
    }
    fun verifyUnpairAfterSettings() { if(!closed) runtime.unpair.verify() }
    override fun submitPin(expectedEpoch: Long, pin: String) {
        if(closed || removalBlocks(runtime.connection.address()) || expectedEpoch != runtime.connection.epoch() || mutable.value.connectionEpoch != expectedEpoch || !mutable.value.needsPin) return
        mutable.update { it.copy(needsPin = false) }; runtime.connection.submitPin(expectedEpoch, pin)
    }
    override fun cancelPin(expectedEpoch: Long) {
        if(closed || removalBlocks(runtime.connection.address()) || expectedEpoch != runtime.connection.epoch() || mutable.value.connectionEpoch != expectedEpoch || !mutable.value.needsPin) return
        mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L) }
        runtime.connection.disconnectIfEpoch(expectedEpoch)
    }
    override fun refreshFaces() {
        if(!closed && !removalWorking() && !removalBlocks(runtime.connection.address()) && !preparing && startingOperation == null && deletionPreparation == null && !replacementActive()) {
            mutable.update { it.copy(statusMessage = "") }
            runtime.logs.record(Tr.tr("列表刷新", "Aggiornamento elenco", "Refreshing list", "Actualizando lista", "Actualisation de la liste", "A atualizar a lista", "Atualizando a lista", "Опресняване на списъка", "Liste yenileniyor", "Liste wird aktualisiert", "Обновление списка"), Tr.tr("请求读取手表表盘列表", "Richiesta dell'elenco dei quadranti al watch", "Requesting the watch face list from the watch", "Pidiendo al reloj la lista de esferas", "Demande de la liste des cadrans à la montre", "A pedir ao relógio a lista de mostradores", "Pedindo ao relógio a lista de mostradores", "Заявка към часовника за списъка с циферблати", "Saatten kadran listesi isteniyor", "Zifferblattliste wird von der Uhr angefordert", "Запрос списка циферблатов у часов"))
            runtime.watchfaces.refresh()
        }
    }
    @Deprecated("Global face selection is disabled; use the row-scoped action overloads")
    override fun selectFace(id: String) {}
    private fun replacementError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> Tr.tr("列表已过期或仍在查询，尚未发送替换指令。请刷新列表后重新选择 HWT。", "L'elenco è scaduto o ancora in caricamento; nessun comando di sostituzione inviato. Aggiorna l'elenco e riseleziona l'HWT.", "The list is expired or still loading; no replace command sent. Refresh the list and select the HWT again.", "La lista caducó o aún se está cargando; no se envió el comando de sustitución. Actualiza la lista y vuelve a seleccionar el HWT.", "La liste a expiré ou est encore en chargement ; aucune commande de remplacement envoyée. Actualisez la liste et sélectionnez à nouveau le HWT.", "A lista expirou ou ainda está a carregar; nenhum comando de substituição enviado. Atualize a lista e volte a selecionar o HWT.", "A lista expirou ou ainda está carregando; nenhum comando de substituição enviado. Atualize a lista e selecione o HWT de novo.", "Списъкът е изтекъл или още се зарежда; не е изпратена команда за замяна. Опреснете списъка и изберете HWT отново.", "Liste süresi doldu veya hâlâ yükleniyor; değiştirme komutu gönderilmedi. Listeyi yenileyip HWT'yi tekrar seçin.", "Die Liste ist abgelaufen oder lädt noch; kein Ersetzen-Befehl gesendet. Aktualisiere die Liste und wähle die HWT erneut aus.", "Список устарел или ещё загружается; команда замены не отправлена. Обновите список и выберите HWT снова.")
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE", "VERSION_CHANGED" -> Tr.tr("所选表盘或版本已变化，尚未发送替换指令。请刷新并重新选择。", "Il quadrante o la versione selezionati sono cambiati; nessun comando di sostituzione inviato. Aggiorna e riseleziona.", "The selected watch face or version changed; no replace command sent. Refresh and select again.", "La esfera o la versión seleccionadas cambiaron; no se envió el comando de sustitución. Actualiza y vuelve a seleccionar.", "Le cadran ou la version sélectionnés ont changé ; aucune commande de remplacement envoyée. Actualisez et sélectionnez à nouveau.", "O mostrador ou a versão selecionados mudaram; nenhum comando de substituição enviado. Atualize e selecione de novo.", "O mostrador ou a versão selecionados mudaram; nenhum comando de substituição enviado. Atualize e selecione de novo.", "Избраният циферблат или версия се промениха; не е изпратена команда за замяна. Опреснете и изберете отново.", "Seçili kadran veya sürüm değişti; değiştirme komutu gönderilmedi. Yenileyip tekrar seçin.", "Das ausgewählte Zifferblatt oder die Version hat sich geändert; kein Ersetzen-Befehl gesendet. Aktualisiere und wähle erneut aus.", "Выбранный циферблат или версия изменились; команда замены не отправлена. Обновите и выберите снова.")
        "SELECTED_CONNECTION_CHANGED" -> Tr.tr("连接已变化，尚未发送替换指令。请在当前手表重新选择。", "La connessione è cambiata; nessun comando di sostituzione inviato. Riseleziona sul watch attuale.", "The connection changed; no replace command sent. Select again on the current watch.", "La conexión cambió; no se envió el comando de sustitución. Vuelve a seleccionar en el reloj actual.", "La connexion a changé ; aucune commande de remplacement envoyée. Sélectionnez à nouveau sur la montre actuelle.", "A ligação mudou; nenhum comando de substituição enviado. Selecione de novo no relógio atual.", "A conexão mudou; nenhum comando de substituição enviado. Selecione de novo no relógio atual.", "Връзката се промени; не е изпратена команда за замяна. Изберете отново на текущия часовник.", "Bağlantı değişti; değiştirme komutu gönderilmedi. Geçerli saatte tekrar seçin.", "Die Verbindung hat sich geändert; kein Ersetzen-Befehl gesendet. Wähle auf der aktuellen Uhr erneut aus.", "Подключение изменилось; команда замены не отправлена. Выберите снова на текущих часах.")
        "PAYLOAD_SNAPSHOT_UNAVAILABLE" -> Tr.tr("本地文件副本已失效，尚未发送替换指令。请重新选择 HWT。", "La copia locale del file non è più valida; nessun comando di sostituzione inviato. Riseleziona l'HWT.", "The local copy of the file is no longer valid; no replace command sent. Select the HWT again.", "La copia local del archivo ya no es válida; no se envió el comando de sustitución. Vuelve a seleccionar el HWT.", "La copie locale du fichier n'est plus valide ; aucune commande de remplacement envoyée. Sélectionnez à nouveau le HWT.", "A cópia local do ficheiro já não é válida; nenhum comando de substituição enviado. Volte a selecionar o HWT.", "A cópia local do arquivo não é mais válida; nenhum comando de substituição enviado. Selecione o HWT de novo.", "Локалното копие на файла вече не е валидно; не е изпратена команда за замяна. Изберете HWT отново.", "Dosyanın yerel kopyası artık geçerli değil; değiştirme komutu gönderilmedi. HWT'yi tekrar seçin.", "Die lokale Kopie der Datei ist nicht mehr gültig; kein Ersetzen-Befehl gesendet. Wähle die HWT erneut aus.", "Локальная копия файла больше недействительна; команда замены не отправлена. Выберите HWT снова.")
        "SOURCE_SELECTION_CHANGED" -> Tr.tr("HWT 文件已变化，请重新点击替换。", "Il file HWT è cambiato, tocca di nuovo Sostituisci.", "The HWT file changed, tap Replace again.", "El archivo HWT cambió, vuelve a tocar Sustituir.", "Le fichier HWT a changé, touchez à nouveau Remplacer.", "O ficheiro HWT mudou, toque de novo em Substituir.", "O arquivo HWT mudou, toque em Substituir de novo.", "HWT файлът се промени, докоснете „Замяна“ пак.", "HWT dosyası değişti, tekrar Değiştir'e dokunun.", "Die HWT-Datei hat sich geändert, tippe erneut auf Ersetzen.", "Файл HWT изменился, снова нажмите «Заменить».")
        else -> Tr.tr("替换未开始，尚未发送指令：$code", "Sostituzione non avviata, nessun comando inviato: $code", "Replacement not started, no command sent: $code", "Sustitución no iniciada, ningún comando enviado: $code", "Remplacement non démarré, aucune commande envoyée : $code", "Substituição não iniciada, nenhum comando enviado: $code", "Substituição não iniciada, nenhum comando enviado: $code", "Замяната не е започнала, не е изпратена команда: $code", "Değiştirme başlamadı, komut gönderilmedi: $code", "Ersetzen nicht gestartet, kein Befehl gesendet: $code", "Замена не начата, команда не отправлена: $code")
    }
    private fun deletionError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> Tr.tr("列表已过期或仍在查询，尚未发送删除指令。请刷新列表后重新确认。", "L'elenco è scaduto o ancora in caricamento; nessun comando di eliminazione inviato. Aggiorna l'elenco e conferma di nuovo.", "The list is expired or still loading; no delete command sent. Refresh the list and confirm again.", "La lista caducó o aún se está cargando; no se envió el comando de eliminación. Actualiza la lista y confirma de nuevo.", "La liste a expiré ou est encore en chargement ; aucune commande de suppression envoyée. Actualisez la liste et confirmez à nouveau.", "A lista expirou ou ainda está a carregar; nenhum comando de eliminação enviado. Atualize a lista e confirme de novo.", "A lista expirou ou ainda está carregando; nenhum comando de exclusão enviado. Atualize a lista e confirme de novo.", "Списъкът е изтекъл или още се зарежда; не е изпратена команда за изтриване. Опреснете списъка и потвърдете отново.", "Liste süresi doldu veya hâlâ yükleniyor; silme komutu gönderilmedi. Listeyi yenileyip tekrar onaylayın.", "Die Liste ist abgelaufen oder lädt noch; kein Löschbefehl gesendet. Aktualisiere die Liste und bestätige erneut.", "Список устарел или ещё загружается; команда удаления не отправлена. Обновите список и подтвердите снова.")
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE", "DELETE_PREPARATION_CHANGED" -> Tr.tr("所选表盘或原版本已变化，尚未发送删除指令。请刷新并重新选择。", "Il quadrante o la versione originale selezionati sono cambiati; nessun comando di eliminazione inviato. Aggiorna e riseleziona.", "The selected watch face or original version changed; no delete command sent. Refresh and select again.", "La esfera o la versión original seleccionadas cambiaron; no se envió el comando de eliminación. Actualiza y vuelve a seleccionar.", "Le cadran ou la version d'origine sélectionnés ont changé ; aucune commande de suppression envoyée. Actualisez et sélectionnez à nouveau.", "O mostrador ou a versão original selecionados mudaram; nenhum comando de eliminação enviado. Atualize e selecione de novo.", "O mostrador ou a versão original selecionados mudaram; nenhum comando de exclusão enviado. Atualize e selecione de novo.", "Избраният циферблат или оригиналната версия се промениха; не е изпратена команда за изтриване. Опреснете и изберете отново.", "Seçili kadran veya orijinal sürüm değişti; silme komutu gönderilmedi. Yenileyip tekrar seçin.", "Das ausgewählte Zifferblatt oder die Originalversion hat sich geändert; kein Löschbefehl gesendet. Aktualisiere und wähle erneut aus.", "Выбранный циферблат или исходная версия изменились; команда удаления не отправлена. Обновите и выберите снова.")
        "SELECTED_CONNECTION_CHANGED" -> Tr.tr("连接已变化，尚未发送删除指令。请在当前手表重新选择。", "La connessione è cambiata; nessun comando di eliminazione inviato. Riseleziona sul watch attuale.", "The connection changed; no delete command sent. Select again on the current watch.", "La conexión cambió; no se envió el comando de eliminación. Vuelve a seleccionar en el reloj actual.", "La connexion a changé ; aucune commande de suppression envoyée. Sélectionnez à nouveau sur la montre actuelle.", "A ligação mudou; nenhum comando de eliminação enviado. Selecione de novo no relógio atual.", "A conexão mudou; nenhum comando de exclusão enviado. Selecione de novo no relógio atual.", "Връзката се промени; не е изпратена команда за изтриване. Изберете отново на текущия часовник.", "Bağlantı değişti; silme komutu gönderilmedi. Geçerli saatte tekrar seçin.", "Die Verbindung hat sich geändert; kein Löschbefehl gesendet. Wähle auf der aktuellen Uhr erneut aus.", "Подключение изменилось; команда удаления не отправлена. Выберите снова на текущих часах.")
        else -> Tr.tr("删除未开始，尚未发送指令：$code", "Eliminazione non avviata, nessun comando inviato: $code", "Deletion not started, no command sent: $code", "Eliminación no iniciada, ningún comando enviado: $code", "Suppression non démarrée, aucune commande envoyée : $code", "Eliminação não iniciada, nenhum comando enviado: $code", "Exclusão não iniciada, nenhum comando enviado: $code", "Изтриването не е започнало, не е изпратена команда: $code", "Silme başlamadı, komut gönderilmedi: $code", "Löschen nicht gestartet, kein Befehl gesendet: $code", "Удаление не начато, команда не отправлена: $code")
    }
    private fun activationError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> Tr.tr("列表已过期或仍在查询，请刷新后重新启用", "L'elenco è scaduto o ancora in caricamento; aggiorna e riprova ad attivare", "The list is expired or still loading; refresh and activate again", "La lista caducó o aún se está cargando; actualiza y vuelve a activar", "La liste a expiré ou est encore en chargement ; actualisez et activez à nouveau", "A lista expirou ou ainda está a carregar; atualize e ative de novo", "A lista expirou ou ainda está carregando; atualize e ative de novo", "Списъкът е изтекъл или още се зарежда; опреснете и активирайте отново", "Liste süresi doldu veya hâlâ yükleniyor; yenileyip tekrar etkinleştirin", "Die Liste ist abgelaufen oder lädt noch; aktualisiere und aktiviere erneut", "Список устарел или ещё загружается; обновите и активируйте снова")
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE" -> Tr.tr("该表盘或原版本已变化，请刷新后从对应卡片重试", "Il quadrante o la sua versione originale sono cambiati; aggiorna e riprova dalla scheda corrispondente", "The watch face or its original version changed; refresh and retry from the matching card", "La esfera o su versión original cambiaron; actualiza y reintenta desde la tarjeta correspondiente", "Le cadran ou sa version d'origine ont changé ; actualisez et réessayez depuis la carte correspondante", "O mostrador ou a sua versão original mudaram; atualize e tente de novo a partir do cartão correspondente", "O mostrador ou a versão original dele mudaram; atualize e tente de novo pelo cartão correspondente", "Циферблатът или оригиналната му версия се промениха; опреснете и опитайте пак от съответната карта", "Kadran veya orijinal sürümü değişti; yenileyip ilgili karttan tekrar deneyin", "Das Zifferblatt oder seine Originalversion hat sich geändert; aktualisiere und versuche es über die passende Karte erneut", "Циферблат или его исходная версия изменились; обновите и повторите из соответствующей карточки")
        "SELECTED_CONNECTION_CHANGED" -> Tr.tr("连接已变化，请在当前手表的对应卡片重试", "La connessione è cambiata; riprova dalla scheda corrispondente del watch attuale", "The connection changed; retry from the matching card of the current watch", "La conexión cambió; reintenta desde la tarjeta correspondiente del reloj actual", "La connexion a changé ; réessayez depuis la carte correspondante de la montre actuelle", "A ligação mudou; tente de novo a partir do cartão correspondente do relógio atual", "A conexão mudou; tente de novo pelo cartão correspondente do relógio atual", "Връзката се промени; опитайте пак от съответната карта на текущия часовник", "Bağlantı değişti; geçerli saatin ilgili kartından tekrar deneyin", "Die Verbindung hat sich geändert; versuche es über die passende Karte der aktuellen Uhr erneut", "Подключение изменилось; повторите из соответствующей карточки текущих часов")
        "SELECT_EXISTING_ACTIVATABLE_FACE" -> Tr.tr("该表盘的资源或类型未确认，不能启用", "Risorse o tipo di questo quadrante non confermati: impossibile attivarlo", "Resources or type of this watch face not confirmed: it can't be activated", "Recursos o tipo de esta esfera sin confirmar: no se puede activar", "Ressources ou type de ce cadran non confirmés : impossible de l'activer", "Recursos ou tipo deste mostrador não confirmados: não é possível ativá-lo", "Recursos ou tipo deste mostrador não confirmados: não é possível ativá-lo", "Ресурсите или типът на този циферблат не са потвърдени: не може да се активира", "Bu kadranın kaynakları veya türü doğrulanmadı: etkinleştirilemez", "Ressourcen oder Typ dieses Zifferblatts nicht bestätigt: es kann nicht aktiviert werden", "Ресурсы или тип этого циферблата не подтверждены: активировать нельзя")
        else -> Tr.tr("启用未开始：$code", "Attivazione non avviata: $code", "Activation not started: $code", "Activación no iniciada: $code", "Activation non démarrée : $code", "Ativação não iniciada: $code", "Ativação não iniciada: $code", "Активирането не е започнало: $code", "Etkinleştirme başlamadı: $code", "Aktivierung nicht gestartet: $code", "Активация не начата: $code")
    }
    private fun runtimeError(kind: String, stage: String, code: String): String {
        if(kind == "replacement" && stage == "PRECHECK_FAILED") return replacementError(code)
        if(kind == "deletion" && stage == "PRECHECK_FAILED") return deletionError(code)
        if(kind == "activation" && stage == "PRECHECK_FAILED") return activationError(code)
        return when(code) {
            "WATCHFACE_OPERATION_BUSY", "WAIT_FOR_CURRENT_OPERATION_OR_QUERY" -> Tr.tr("请等待当前操作或查询完成", "Attendi il termine dell'operazione o della richiesta in corso", "Wait for the current operation or query to finish", "Espera a que termine la operación o consulta en curso", "Attendez la fin de l'opération ou de la requête en cours", "Aguarde o fim da operação ou consulta em curso", "Aguarde o fim da operação ou consulta em andamento", "Изчакайте текущата операция или заявка да приключи", "Süren işlemin veya sorgunun bitmesini bekleyin", "Warte, bis der laufende Vorgang oder die Abfrage beendet ist", "Дождитесь завершения текущей операции или запроса")
            "CONNECT_AND_AUTHENTICATE_FIRST" -> Tr.tr("请先连接手表后重试", "Connetti prima il watch e riprova", "Connect the watch first and try again", "Conecta primero el reloj e inténtalo de nuevo", "Connectez d'abord la montre et réessayez", "Ligue primeiro o relógio e tente de novo", "Conecte primeiro o relógio e tente de novo", "Първо свържете часовника и опитайте пак", "Önce saati bağlayıp tekrar deneyin", "Verbinde zuerst die Uhr und versuche es erneut", "Сначала подключите часы и повторите")
            "LIST_QUERY_TIMEOUT_RECONNECT" -> Tr.tr("读取表盘列表超时，请重新连接后刷新", "Timeout nella lettura dell'elenco dei quadranti; riconnettiti e aggiorna", "Timed out reading the watch face list; reconnect and refresh", "Tiempo agotado al leer la lista de esferas; vuelve a conectar y actualiza", "Délai dépassé lors de la lecture de la liste des cadrans ; reconnectez-vous et actualisez", "Tempo esgotado a ler a lista de mostradores; volte a ligar e atualize", "Tempo esgotado lendo a lista de mostradores; conecte de novo e atualize", "Времето за четене на списъка с циферблати изтече; свържете се отново и опреснете", "Kadran listesi okunurken zaman aşımı; yeniden bağlanıp yenileyin", "Zeitüberschreitung beim Lesen der Zifferblattliste; verbinde dich neu und aktualisiere", "Время чтения списка циферблатов истекло; переподключитесь и обновите")
            "DEVICE_LIST_ERROR" -> Tr.tr("手表未返回有效列表，请重新刷新", "Il watch non ha restituito un elenco valido, aggiorna di nuovo", "The watch didn't return a valid list, refresh again", "El reloj no devolvió una lista válida, actualiza de nuevo", "La montre n'a pas renvoyé de liste valide, actualisez à nouveau", "O relógio não devolveu uma lista válida, atualize de novo", "O relógio não retornou uma lista válida, atualize de novo", "Часовникът не върна валиден списък, опреснете пак", "Saat geçerli bir liste döndürmedi, tekrar yenileyin", "Die Uhr hat keine gültige Liste geliefert, aktualisiere erneut", "Часы не вернули корректный список, обновите снова")
            "WIRE_FACE_LIST_INCOMPLETE" -> Tr.tr("手表列表有条目缺少版本等必需字段；该条目不会作为操作目标，其他表盘照常可用", "Nell'elenco del watch alcune voci non hanno campi obbligatori come la versione; queste voci non verranno usate come destinazione, gli altri quadranti restano utilizzabili", "Some entries in the watch list are missing required fields such as the version; they won't be used as targets, the other watch faces remain usable", "Algunas entradas de la lista del reloj no tienen campos obligatorios como la versión; no se usarán como destino, las demás esferas siguen disponibles", "Certaines entrées de la liste de la montre n'ont pas de champs obligatoires comme la version ; elles ne serviront pas de cible, les autres cadrans restent utilisables", "Algumas entradas da lista do relógio não têm campos obrigatórios como a versão; não serão usadas como destino, os outros mostradores continuam disponíveis", "Algumas entradas da lista do relógio não têm campos obrigatórios como a versão; elas não serão usadas como destino, os outros mostradores continuam disponíveis", "Някои записи в списъка на часовника нямат задължителни полета като версията; те няма да се използват като цел, другите циферблати остават достъпни", "Saat listesindeki bazı girişlerde sürüm gibi zorunlu alanlar eksik; bunlar hedef olarak kullanılmayacak, diğer kadranlar kullanılabilir", "Einigen Einträgen in der Liste der Uhr fehlen Pflichtfelder wie die Version; sie werden nicht als Ziel verwendet, die anderen Zifferblätter bleiben nutzbar", "В некоторых записях списка часов нет обязательных полей, например версии; они не будут использоваться как цель, остальные циферблаты доступны")
            "RECONNECT_AFTER_UNCERTAIN_FILE_RESULT" -> Tr.tr("上次操作尚未可靠收尾，请重新连接后刷新", "L'operazione precedente non si è chiusa in modo affidabile; riconnettiti e aggiorna", "The previous operation didn't close reliably; reconnect and refresh", "La operación anterior no se cerró de forma fiable; vuelve a conectar y actualiza", "L'opération précédente ne s'est pas terminée de façon fiable ; reconnectez-vous et actualisez", "A operação anterior não terminou de forma fiável; volte a ligar e atualize", "A operação anterior não terminou de forma confiável; conecte de novo e atualize", "Предишната операция не приключи надеждно; свържете се отново и опреснете", "Önceki işlem güvenilir şekilde kapanmadı; yeniden bağlanıp yenileyin", "Der vorherige Vorgang wurde nicht zuverlässig abgeschlossen; verbinde dich neu und aktualisiere", "Предыдущая операция не завершилась надёжно; переподключитесь и обновите")
            "DELETE_PREPARATION_CHANGED" -> Tr.tr("所选表盘已变化，请刷新列表后重新选择", "Il quadrante selezionato è cambiato; aggiorna l'elenco e riseleziona", "The selected watch face changed; refresh the list and select again", "La esfera seleccionada cambió; actualiza la lista y vuelve a seleccionar", "Le cadran sélectionné a changé ; actualisez la liste et sélectionnez à nouveau", "O mostrador selecionado mudou; atualize a lista e selecione de novo", "O mostrador selecionado mudou; atualize a lista e selecione de novo", "Избраният циферблат се промени; опреснете списъка и изберете отново", "Seçili kadran değişti; listeyi yenileyip tekrar seçin", "Das ausgewählte Zifferblatt hat sich geändert; aktualisiere die Liste und wähle erneut aus", "Выбранный циферблат изменился; обновите список и выберите снова")
            else -> Tr.tr("操作未完成：$code", "Operazione non completata: $code", "Operation not completed: $code", "Operación no completada: $code", "Opération non terminée : $code", "Operação não concluída: $code", "Operação não concluída: $code", "Операцията не е завършена: $code", "İşlem tamamlanmadı: $code", "Vorgang nicht abgeschlossen: $code", "Операция не завершена: $code")
        }
    }
    private fun replacementUiRejected(code: String, identity: WatchfaceManager.ReplacementIdentity? = null) {
        runtime.logs.record("REPLACE_UI_PRECHECK_FAILED", "code=$code command_sent=false upload_sent=false")
        if(identity != null) {
            runtime.rejectReplacementDialog(identity, code)
            if(!replacementIdentityMatches(runtime.snapshot().replacementDialog, identity)) return
        }
        if(!closed) {
            if(identity != null) apply(runtime.snapshot())
            mutable.update { it.copy(operation = if(identity == null) OperationInfo("replacement", "PRECHECK_FAILED", replacementError(code), null, false, true) else it.operation, statusMessage = replacementError(code)) }
        }
    }
    private fun deletionUiRejected(code: String, identity: WatchfaceManager.DeletionIdentity) {
        runtime.logs.record("DELETE_UI_PRECHECK_FAILED", "code=$code command_sent=false upload_sent=false")
        runtime.rejectDeletionDialog(identity, code)
        if(!closed && deletionIdentityMatches(runtime.snapshot().deletionDialog, identity)) {
            apply(runtime.snapshot())
            mutable.update { it.copy(statusMessage = deletionError(code)) }
        }
    }
    private fun matchesRow(row: FaceItem, face: TlvView.Face): Boolean =
        row.id == face.id && row.version == face.version && row.rawType == face.type && row.expandType == face.expandType
    private fun rowContextIsCurrent(target: RowTarget, snapshot: AppRuntime.Snapshot): Boolean {
        val ui = mutable.value
        return !closed && !ui.introductionPending && !removalWorking() && !removalBlocks(target.address) &&
            target.epoch == runtime.connection.epoch() && snapshot.epoch == target.epoch && ui.connectionEpoch == target.epoch &&
            runtime.connection.ready() && snapshot.ready && !snapshot.connecting && !ui.connecting && queuedConnectEpoch == null &&
            runtime.connection.address().equals(target.address, true) && snapshot.deviceAddress.equals(target.address, true) &&
            ui.selectedDeviceAddress?.equals(target.address, true) == true && ui.connectionFormRevision == target.formRevision &&
            snapshot.operationRevision == target.operationRevision
    }
    private fun captureRow(rendered: FaceItem, epoch: Long): RowTarget {
        val ui = mutable.value; val snapshot = runtime.snapshot()
        if(!UiPolicy.canOperateFaces(ui)) throw Core.Fault("FRESH_DEVICE_LIST_REQUIRED")
        if(snapshot.active || snapshot.connecting || snapshot.needsPin || snapshot.replacementDialog != null || snapshot.deletionDialog != null || snapshot.activationDialog != null) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        val address = SavedWatchStore.canonicalAddress(runtime.connection.address()) ?: throw Core.Fault("SELECTED_CONNECTION_CHANGED")
        val visible = ui.faces.filter { it.id == rendered.id }.singleOrNull() ?: throw Core.Fault("SELECTED_ID_NOT_UNIQUE")
        if(visible.version != rendered.version || visible.rawType != rendered.rawType || visible.expandType != rendered.expandType) throw Core.Fault("SELECTED_FACE_CHANGED")
        val actual = snapshot.faces.filter { it.id == rendered.id }.singleOrNull() ?: throw Core.Fault("SELECTED_ID_NOT_UNIQUE")
        if(!matchesRow(rendered, actual)) throw Core.Fault("SELECTED_FACE_CHANGED")
        val target = RowTarget(rendered, actual, epoch, address, ui.connectionFormRevision, snapshot.operationRevision)
        currentRow(target)
        return target
    }
    /** 每次使用都重验原行，不读 selectedId、不跟随新展开行、不把缺失 raw 字段修复为 0。 */
    private fun currentRow(target: RowTarget, deletionFresh: Boolean = false): Pair<TlvView.Face, AppRuntime.Snapshot> {
        val snapshot = runtime.snapshot()
        if(!rowContextIsCurrent(target, snapshot)) throw Core.Fault("SELECTED_CONNECTION_CHANGED")
        if(preparing || filePickToken != null || startingOperation != null || snapshot.needsPin) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        if(runtime.watchfaces.reconnectRequired(target.epoch)) throw Core.Fault("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT")
        val fresh = if(deletionFresh) runtime.watchfaces.deletionListFresh(target.epoch) else runtime.watchfaces.replacementListFresh(target.epoch)
        if(!snapshot.listReady || !fresh) throw Core.Fault("FRESH_DEVICE_LIST_REQUIRED")
        val actual = snapshot.faces.filter { it.id == target.rendered.id }.singleOrNull() ?: throw Core.Fault("SELECTED_ID_NOT_UNIQUE")
        if(!matchesRow(target.rendered, actual)) throw Core.Fault("SELECTED_FACE_CHANGED")
        return actual to snapshot
    }
    private fun currentDeletionFace(target: RowTarget): TlvView.Face {
        val actual = currentRow(target, deletionFresh = true).first
        if(!FacePolicy.eligible(actual)) throw Core.Fault("SELECTED_FACE_CHANGED")
        return actual
    }
    private fun currentActivationFace(target: RowTarget): TlvView.Face {
        val actual = currentRow(target).first
        if(!FacePolicy.canActivate(actual)) throw Core.Fault("SELECT_EXISTING_ACTIVATABLE_FACE")
        return actual
    }
    private fun currentPickFace(target: Pick): TlvView.Face {
        val (actual, snapshot) = currentRow(target.row)
        if(!FacePolicy.eligible(actual)) throw Core.Fault("SELECT_EXISTING_ORDINARY_FACE")
        if(VersionPolicy.next(actual.id, actual.version, snapshot.faces) != target.version) throw Core.Fault("VERSION_CHANGED")
        return actual
    }
    override fun prepareHwtPick(): Boolean {
        if(closed || removalWorking() || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || !UiPolicy.canChooseHwt(mutable.value)) return false
        // 文件只与本次系统选择请求关联，选择文件本身不捕获或操作手表目标。
        filePickToken = tokens.incrementAndGet()
        // 已结束的旧摘要不属于刚选择的文件，不能让准备文件看起来像已替换。
        fileOnlyPreview = true
        mutable.update { it.copy(pickingHwt = true, operation = if(it.operation.failed) it.operation else OperationInfo()) }
        apply(runtime.snapshot())
        runtime.logs.record("SOURCE_PICK_OPENED", "no_command_sent=true")
        return true
    }
    override fun cancelHwtPick() {
        filePickToken = null
        if(!closed) { mutable.update { it.copy(pickingHwt = false) }; apply(runtime.snapshot()) }
    }
    fun currentHwtPickToken(): Long? = filePickToken
    fun hwtPickIsCurrent(token: Long): Boolean = !closed && filePickToken == token
    fun importHwtFromPlatform(uri: Uri, expectedToken: Long) { if(hwtPickIsCurrent(expectedToken)) importHwt(uri) }
    fun cancelHwtPickFromPlatform(expectedToken: Long) { if(hwtPickIsCurrent(expectedToken)) cancelHwtPick() }
    override fun importHwt(uri: Uri) {
        if(closed || filePickToken == null) { if(!closed) message(Tr.tr("文件选择已失效，请重新选择 HWT", "La selezione del file non è più valida, riseleziona l'HWT", "The file selection is no longer valid, select the HWT again", "La selección de archivo ya no es válida, vuelve a seleccionar el HWT", "La sélection de fichier n'est plus valide, sélectionnez à nouveau le HWT", "A seleção de ficheiro já não é válida, volte a selecionar o HWT", "A seleção de arquivo não é mais válida, selecione o HWT de novo", "Изборът на файл вече не е валиден, изберете HWT отново", "Dosya seçimi artık geçerli değil, HWT'yi tekrar seçin", "Die Dateiauswahl ist nicht mehr gültig, wähle die HWT erneut aus", "Выбор файла больше недействителен, выберите HWT снова")); return }
        filePickToken = null
        preparing = true
        mutable.update { it.copy(pickingHwt = false, operation = OperationInfo("source", "PREPARING", Tr.tr("读取并冻结本地 HWT", "Lettura e blocco dell'HWT locale", "Reading and locking the local HWT", "Leyendo y bloqueando el HWT local", "Lecture et verrouillage du HWT local", "A ler e bloquear o HWT local", "Lendo e bloqueando o HWT local", "Четене и заключване на локалния HWT", "Yerel HWT okunuyor ve kilitleniyor", "Lokale HWT wird gelesen und gesperrt", "Чтение и блокировка локального HWT"), null, true)) }
        runtime.logs.record("SOURCE_READ_STARTED", "no_command_sent=true")
        try { io.execute {
            var loaded: HwtSource? = null
            try {
                val filename = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: Tr.tr("本地 HWT", "HWT locale", "Local HWT", "HWT local", "HWT local", "HWT local", "HWT local", "Локален HWT", "Yerel HWT", "Lokale HWT", "Локальный HWT")
                app.contentResolver.openInputStream(uri).use { input -> if(input == null) throw Core.Fault("HWT_INPUT_UNAVAILABLE"); loaded = HwtSource.load(input, app.filesDir) }
                val imported = loaded!!
                val kinds = PayloadKinds.available(imported)
                val payloads = LinkedHashMap<HwtSource.Mode, HwtSource.Snapshot>()
                val failures = mutableListOf<String>()
                for(kind in kinds) {
                    val mode = PayloadKinds.mode(kind) ?: continue
                    try { payloads[mode] = imported.freeze(mode).also { it.verify() } }
                    catch(e: Exception) { failures += "$kind:${Core.code(e)}" }
                }
                val preparedKinds = kinds.filter { PayloadKinds.mode(it)?.let(payloads::containsKey) == true }
                val chosen = PayloadKinds.preferred(kinds)?.takeIf { it in preparedKinds }
                val payload = chosen?.let { PayloadKinds.mode(it)?.let(payloads::get) }
                main.post {
                    preparing = false
                    if(closed) { cleanAsync(imported); return@post }
                    val old = source
                    source = imported; sourcePayloads = payloads.toMap(); chosenPayloadKind = chosen
                    old?.let(::cleanAsync)
                    runtime.logs.record("SOURCE_IMPORTED", "bytes=${payload?.size ?: 0} sha256=${payload?.hash ?: "unavailable"} source_sha256=${imported.sourceHash} payload_available=${payload != null} detected_kinds=${kinds.joinToString(",")} prepared_kinds=${preparedKinds.joinToString(",")} selected=${chosen ?: "none"} container_members=${imported.allModeMembers} container_diagnostic=${imported.containerDiagnostic} payload_failures=${failures.joinToString(",").ifEmpty { "none" }} source_content_not_rewritten=true payload_profile=watch-allmode-v1 no_command_sent=true")
                    val view = SourceInfo(
                        title=imported.title, fileName=filename, sourceBytes=imported.original.length(),
                        payloadBytes=payload?.size ?: 0, xmlVersion=imported.xmlVersion, sha256=payload?.hash ?: imported.sourceHash,
                        previewPath=imported.preview?.absolutePath, previewSha256=imported.previewHash,
                        replacementAvailable=HwtSource.Mode.BIN in payloads, payloadKind=chosen ?: PayloadKinds.NONE,
                        payloadKinds=preparedKinds, detectedPayloadKinds=kinds, screen=imported.screen,
                        payloadDiagnostic=failures.joinToString(",").ifEmpty {
                            if(!imported.allModeResource && imported.containerDiagnostic !in setOf("RAW_BIN_RESOURCE","CONTAINER_CONFIG_MISSING")) imported.containerDiagnostic
                            else if(kinds.isEmpty()) imported.containerDiagnostic else ""
                        },
                        importToken=tokens.incrementAndGet(),
                    )
                    mutable.update { it.copy(source=view, operation=OperationInfo()) }
                    apply(runtime.snapshot())
                    message(when {
                        payload != null -> Tr.tr("HWT 已就绪：${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT pronto: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT ready: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT listo: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT prêt : ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT pronto: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT pronto: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT е готов: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT hazır: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT bereit: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}", "HWT готов: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}")
                        preparedKinds.isNotEmpty() -> Tr.tr("默认载荷未就绪，请在文件详情手动选择已准备的直装类型", "Payload predefinito non pronto: scegli manualmente nei dettagli del file un tipo di installazione diretta già preparato", "Default payload not ready: pick a prepared direct-install type manually in the file details", "Carga predeterminada no lista: elige a mano en los detalles del archivo un tipo de instalación directa ya preparado", "Charge par défaut non prête : choisissez manuellement dans les détails du fichier un type d'installation directe déjà préparé", "Carga predefinida não pronta: escolha manualmente nos detalhes do ficheiro um tipo de instalação direta já preparado", "Carga padrão não pronta: escolha manualmente nos detalhes do arquivo um tipo de instalação direta já preparado", "Основното съдържание не е готово: изберете ръчно в подробностите на файла вече подготвен тип директно инсталиране", "Varsayılan içerik hazır değil: dosya ayrıntılarından hazırlanmış bir doğrudan yükleme türünü elle seçin", "Standardinhalt nicht bereit: wähle in den Dateidetails manuell einen vorbereiteten Direktinstallationstyp", "Содержимое по умолчанию не готово: выберите вручную в сведениях о файле уже подготовленный тип прямой установки")
                        else -> Tr.tr("该 HWT 未找到可传输的表盘内容，请查看文件结构诊断", "In questo HWT non è stato trovato contenuto trasferibile; controlla la diagnostica della struttura del file", "No transferable watch face content found in this HWT; check the file structure diagnostics", "No se encontró contenido de esfera transferible en este HWT; revisa el diagnóstico de la estructura del archivo", "Aucun contenu de cadran transférable trouvé dans ce HWT ; consultez le diagnostic de la structure du fichier", "Não foi encontrado conteúdo de mostrador transferível neste HWT; veja o diagnóstico da estrutura do ficheiro", "Não foi encontrado conteúdo de mostrador transferível neste HWT; veja o diagnóstico da estrutura do arquivo", "В този HWT няма съдържание на циферблат за прехвърляне; вижте диагностиката на структурата на файла", "Bu HWT'de aktarılabilir kadran içeriği bulunamadı; dosya yapısı tanılamasına bakın", "In dieser HWT wurde kein übertragbarer Zifferblattinhalt gefunden; sieh dir die Diagnose der Dateistruktur an", "В этом HWT не найдено содержимое циферблата для передачи; посмотрите диагностику структуры файла")
                    })
                }
            } catch(e: Exception) {
                loaded?.let(::cleanAsync)
                val code = if(e is SecurityException) "FILE_READ_PERMISSION_REQUIRED" else Core.code(e)
                runtime.logs.record("SOURCE_ERROR", "code=$code no_command_sent=true")
                main.post {
                    preparing = false
                    if(!closed) { mutable.update { it.copy(operation = OperationInfo()) }; apply(runtime.snapshot()); message(Tr.tr("HWT 读取失败：$code", "Lettura HWT non riuscita: $code", "HWT read failed: $code", "Error al leer el HWT: $code", "Échec de la lecture du HWT : $code", "Falha ao ler o HWT: $code", "Falha ao ler o HWT: $code", "Четенето на HWT не бе успешно: $code", "HWT okunamadı: $code", "HWT konnte nicht gelesen werden: $code", "Не удалось прочитать HWT: $code")) }
                }
            }
        } } catch(_: RejectedExecutionException) {
            preparing = false
            if(!closed) { mutable.update { it.copy(operation = OperationInfo()) }; apply(runtime.snapshot()); message(Tr.tr("文件读取未开始，请重新选择 HWT", "Lettura del file non avviata, riseleziona l'HWT", "File reading not started, select the HWT again", "Lectura del archivo no iniciada, vuelve a seleccionar el HWT", "Lecture du fichier non démarrée, sélectionnez à nouveau le HWT", "Leitura do ficheiro não iniciada, volte a selecionar o HWT", "Leitura do arquivo não iniciada, selecione o HWT de novo", "Четенето на файла не е започнало, изберете HWT отново", "Dosya okuma başlamadı, HWT'yi tekrar seçin", "Dateilesen nicht gestartet, wähle die HWT erneut aus", "Чтение файла не начато, выберите HWT снова")) }
        }
    }
    private fun selectedPayload(): HwtSource.Snapshot? =
        chosenPayloadKind?.let(PayloadKinds::mode)?.let(sourcePayloads::get)

    /** 只切换本次导入可用的载荷类型；不改变连接、列表或已冻结的字节。 */
    override fun selectPayloadKind(kind: String) {
        val ui=mutable.value
        if(closed || preparing || filePickToken!=null || startingOperation!=null || deletionPreparation!=null ||
            pending!=null || removalWorking() || removalBlocks(runtime.connection.address()) || runtime.watchfaces.busy() ||
            replacementActive() || !PayloadSelectionPolicy.canSelect(ui)) return
        if(kind !in ui.source!!.payloadKinds) return
        val mode = PayloadKinds.mode(kind) ?: return
        val payload = sourcePayloads[mode] ?: return
        if(payload.source !== source || !payload.source.matchesPayload(payload)) return
        if(chosenPayloadKind == kind) return
        chosenPayloadKind = kind
        runtime.logs.record("PAYLOAD_KIND_SELECTED", "kind=$kind bytes=${payload.size} sha256=${payload.hash} no_command_sent=true")
        mutable.update { state -> state.source?.let { state.copy(source = it.copy(
            payloadKind = kind, payloadBytes = payload.size, sha256 = payload.hash)) } ?: state }
    }
    override fun requestDirectInstall() {
        val ui = mutable.value
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || preparing || filePickToken != null ||
            startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() ||
            pending != null || ui.prompt != null || ui.needsPin || !UiPolicy.canOperateFaces(ui)) return
        val imported = source ?: run { message(Tr.tr("请先选择 HWT 文件", "Seleziona prima un file HWT", "Select an HWT file first", "Selecciona primero un archivo HWT", "Sélectionnez d'abord un fichier HWT", "Selecione primeiro um ficheiro HWT", "Selecione primeiro um arquivo HWT", "Първо изберете HWT файл", "Önce bir HWT dosyası seçin", "Wähle zuerst eine HWT-Datei aus", "Сначала выберите файл HWT")); return }
        val payload = selectedPayload() ?: run { message(Tr.tr("该 HWT 未找到可传输的表盘内容", "In questo HWT non è stato trovato contenuto trasferibile", "No transferable watch face content found in this HWT", "No se encontró contenido de esfera transferible en este HWT", "Aucun contenu de cadran transférable trouvé dans ce HWT", "Não foi encontrado conteúdo de mostrador transferível neste HWT", "Não foi encontrado conteúdo de mostrador transferível neste HWT", "В този HWT няма съдържание на циферблат за прехвърляне", "Bu HWT'de aktarılabilir kadran içeriği bulunamadı", "In dieser HWT wurde kein übertragbarer Zifferblattinhalt gefunden", "В этом HWT не найдено содержимое циферблата для передачи")); return }
        val payloadKind = PayloadKinds.kind(payload.mode)
        val snapshot = runtime.snapshot()
        val epoch = snapshot.epoch
        val address = SavedWatchStore.canonicalAddress(snapshot.deviceAddress) ?: return
        val revision = snapshot.operationRevision
        val formRevision = ui.connectionFormRevision
        fun validate() {
            val now = runtime.snapshot(); val current = mutable.value
            if(closed || current.introductionPending || removalWorking() || removalBlocks(address) || preparing || filePickToken != null ||
                startingOperation != null || deletionPreparation != null || runtime.watchfaces.busy() || now.active || now.needsPin ||
                !now.ready || !runtime.connection.ready() || now.epoch != epoch || runtime.connection.epoch() != epoch ||
                !now.deviceAddress.equals(address, true) || !runtime.connection.address().equals(address, true) ||
                current.selectedDeviceAddress?.equals(address, true) != true || current.connectionFormRevision != formRevision ||
                now.operationRevision != revision) throw Core.Fault("SELECTED_CONNECTION_CHANGED")
            if(runtime.watchfaces.reconnectRequired(epoch)) throw Core.Fault("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT")
            if(!now.listReady || !runtime.watchfaces.replacementListFresh(epoch)) throw Core.Fault("FRESH_DEVICE_LIST_REQUIRED")
            if(source !== imported || chosenPayloadKind != payloadKind || current.source?.payloadKind != payloadKind ||
                selectedPayload() !== payload || !imported.matchesPayload(payload) ||
                !payload.file.isFile || payload.file.length() != payload.size.toLong()) throw Core.Fault("SOURCE_SELECTION_CHANGED")
        }
        try {
            validate()
            val random = java.security.SecureRandom()
            val id = DirectInstallTargetPolicy.allocate(snapshot.faces.map { it.id }) { random.nextInt(DirectInstallTargetPolicy.MAX_SAFE_ID) + 1 }
                ?: throw Core.Fault("DIRECT_INSTALL_ID_UNAVAILABLE")
            val filename = ui.source?.fileName ?: imported.title
            prompt("direct_install", Tr.tr("直接安装表盘？", "Installare direttamente il quadrante?", "Install the watch face directly?", "¿Instalar la esfera directamente?", "Installer le cadran directement ?", "Instalar o mostrador diretamente?", "Instalar o mostrador diretamente?", "Директно инсталиране на циферблата?", "Kadran doğrudan yüklensin mi?", "Zifferblatt direkt installieren?", "Установить циферблат напрямую?"), Tr.tr("$filename\n格式：${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\n将传输：${PayloadKinds.detail(payloadKind)}\n传输大小：${payload.size} B\n\n请先确认目标手表是 WATCH 系列全能模式，或其他系列/模式且固件版本在 6.0.0.32 之前。", "$filename\nFormato: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nVerrà trasferito: ${PayloadKinds.detail(payloadKind)}\nDimensione: ${payload.size} B\n\nVerifica prima che il watch sia un WATCH in modalità completa, oppure di un'altra serie/modalità con firmware precedente alla 6.0.0.32.", "$filename\nFormat: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nWill transfer: ${PayloadKinds.detail(payloadKind)}\nSize: ${payload.size} B\n\nFirst make sure the watch is a WATCH series in full mode, or another series/mode with firmware older than 6.0.0.32.", "$filename\nFormato: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nSe transferirá: ${PayloadKinds.detail(payloadKind)}\nTamaño: ${payload.size} B\n\nComprueba primero que el reloj sea un WATCH en modo completo, u otra serie/modo con firmware anterior a 6.0.0.32.", "$filename\nFormat : ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nSera transféré : ${PayloadKinds.detail(payloadKind)}\nTaille : ${payload.size} B\n\nVérifiez d'abord que la montre est une WATCH en mode complet, ou une autre série/mode avec un firmware antérieur à 6.0.0.32.", "$filename\nFormato: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nSerá transferido: ${PayloadKinds.detail(payloadKind)}\nTamanho: ${payload.size} B\n\nConfirme primeiro que o relógio é um WATCH em modo completo, ou outra série/modo com firmware anterior a 6.0.0.32.", "$filename\nFormato: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nSerá transferido: ${PayloadKinds.detail(payloadKind)}\nTamanho: ${payload.size} B\n\nConfirme primeiro que o relógio é um WATCH no modo completo, ou outra série/modo com firmware anterior a 6.0.0.32.", "$filename\nФормат: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nЩе се прехвърли: ${PayloadKinds.detail(payloadKind)}\nРазмер: ${payload.size} B\n\nПърво се уверете, че часовникът е от серията WATCH в пълен режим или друга серия/режим с фърмуер по-стар от 6.0.0.32.", "$filename\nBiçim: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nAktarılacak: ${PayloadKinds.detail(payloadKind)}\nBoyut: ${payload.size} B\n\nÖnce saatin tam modda bir WATCH serisi ya da 6.0.0.32'den eski yazılımlı başka bir seri/mod olduğundan emin olun.", "$filename\nFormat: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nWird übertragen: ${PayloadKinds.detail(payloadKind)}\nGröße: ${payload.size} B\n\nStelle zuerst sicher, dass die Uhr eine WATCH im Vollmodus ist oder eine andere Serie/ein anderer Modus mit Firmware älter als 6.0.0.32.", "$filename\nФормат: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nБудет передано: ${PayloadKinds.detail(payloadKind)}\nРазмер: ${payload.size} B\n\nСначала убедитесь, что часы — серии WATCH в полном режиме или другой серии/режима с прошивкой старше 6.0.0.32."), false, Tr.tr("安装", "Installa", "Install", "Instalar", "Installer", "Instalar", "Instalar", "Инсталиране", "Yükle", "Installieren", "Установить"), {
                validate()
                if(runtime.snapshot().faces.any { DirectInstallTargetPolicy.sameNumericId(it.id, id) }) throw Core.Fault("DIRECT_INSTALL_ID_CONFLICT")
                val request = runtime.watchfaces.newDirectInstallRequest(id, DirectInstallTargetPolicy.VERSION, epoch)
                if(!runtime.openReplacementDialog(request, imported.title, filename)) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
                fileOnlyPreview = true
                apply(runtime.snapshot())
                try {
                    validate()
                    runtime.logs.record("DIRECT_INSTALL_UI_CONFIRM", "id=$id version=${DirectInstallTargetPolicy.VERSION} captured_epoch=$epoch payload=$payloadKind repacked=${payload.mode==HwtSource.Mode.CONTAINER_NO_BIN} outer_hwt_sent=false")
                    runtime.watchfaces.beginDirectInstall(payload, epoch, request)
                } catch(e: Exception) { replacementUiRejected(Core.code(e), request.identity) }
            })
        } catch(e: Exception) { replacementUiRejected(Core.code(e)) }
    }
    /** 旧无目标入口绝不选择当前/第一行；只有显式逐卡重载可以发起设备操作。 */
    override fun requestReplacement() {}
    override fun requestReplacement(face: FaceItem, epoch: Long) {
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || preparing || filePickToken != null || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || pending != null || mutable.value.prompt != null || mutable.value.needsPin) return
        val imported = source ?: run { message(Tr.tr("请先选择 HWT 文件", "Seleziona prima un file HWT", "Select an HWT file first", "Selecciona primero un archivo HWT", "Sélectionnez d'abord un fichier HWT", "Selecione primeiro um ficheiro HWT", "Selecione primeiro um arquivo HWT", "Първо изберете HWT файл", "Önce bir HWT dosyası seçin", "Wähle zuerst eine HWT-Datei aus", "Сначала выберите файл HWT")); return }
        val payload = sourcePayloads[HwtSource.Mode.BIN] ?: run { message(Tr.tr("XML 格式仅能用于 WATCH 系列全能模式直装，不支持替换；WATCH 系列全能模式只支持直装", "Il formato XML si usa solo per l'installazione diretta su WATCH in modalità completa e non supporta la sostituzione; i WATCH in modalità completa supportano solo l'installazione diretta", "The XML format can only be used for direct install on WATCH series in full mode and doesn't support replacement; WATCH series in full mode only support direct install", "El formato XML solo sirve para la instalación directa en WATCH en modo completo y no admite sustitución; los WATCH en modo completo solo admiten la instalación directa", "Le format XML ne sert qu'à l'installation directe sur les WATCH en mode complet et ne permet pas le remplacement ; les WATCH en mode complet ne prennent en charge que l'installation directe", "O formato XML só serve para a instalação direta em WATCH em modo completo e não permite substituição; os WATCH em modo completo só suportam a instalação direta", "O formato XML só serve para a instalação direta em WATCH no modo completo e não permite substituição; os WATCH no modo completo só suportam a instalação direta", "Форматът XML е само за директно инсталиране на WATCH в пълен режим и не позволява замяна; WATCH в пълен режим поддържат само директно инсталиране", "XML biçimi yalnızca tam moddaki WATCH serisinde doğrudan yükleme için kullanılabilir ve değiştirmeyi desteklemez; tam moddaki WATCH serisi yalnızca doğrudan yüklemeyi destekler", "Das XML-Format ist nur für die Direktinstallation auf WATCH im Vollmodus und unterstützt kein Ersetzen; WATCH im Vollmodus unterstützen nur die Direktinstallation", "Формат XML подходит только для прямой установки на WATCH в полном режиме и не поддерживает замену; WATCH в полном режиме поддерживают только прямую установку")); return }
        try {
            val row = captureRow(face, epoch)
            val (observed, snapshot) = currentRow(row)
            if(!FacePolicy.eligible(observed)) throw Core.Fault("SELECT_EXISTING_ORDINARY_FACE")
            val target = Pick(row, VersionPolicy.next(observed.id, observed.version, snapshot.faces))
            val current = currentPickFace(target)
            if(payload.source !== imported || !payload.file.isFile || payload.file.length() != payload.size.toLong()) throw Core.Fault("PAYLOAD_SNAPSHOT_UNAVAILABLE")
            val title = row.rendered.title
            val filename = mutable.value.source?.fileName ?: imported.title
            val currentWarning = if(FacePolicy.current(current)) Tr.tr("\n该表盘正在使用，替换时手表可能切换显示。", "\nQuesto quadrante è in uso; durante la sostituzione il watch potrebbe cambiare schermata.", "\nThis watch face is in use; the watch display may change during replacement.", "\nEsta esfera está en uso; durante la sustitución la pantalla del reloj puede cambiar.", "\nCe cadran est utilisé ; l'affichage de la montre peut changer pendant le remplacement.", "\nEste mostrador está em uso; durante a substituição o ecrã do relógio pode mudar.", "\nEste mostrador está em uso; durante a substituição a tela do relógio pode mudar.", "\nТози циферблат се използва; по време на замяната екранът на часовника може да се промени.", "\nBu kadran kullanımda; değiştirme sırasında saatin ekranı değişebilir.", "\nDieses Zifferblatt wird verwendet; beim Ersetzen kann sich die Anzeige der Uhr ändern.", "\nЭтот циферблат используется; во время замены экран часов может измениться.") else ""
            prompt("replacement", Tr.tr("替换该表盘？", "Sostituire questo quadrante?", "Replace this watch face?", "¿Sustituir esta esfera?", "Remplacer ce cadran ?", "Substituir este mostrador?", "Substituir este mostrador?", "Замяна на този циферблат?", "Bu kadran değiştirilsin mi?", "Dieses Zifferblatt ersetzen?", "Заменить этот циферблат?"), Tr.tr("$title\nID：${current.id}\n文件：$filename\n${current.version} → ${target.version}\n替换固定使用：经典/BIN 载荷 ${payload.size} B$currentWarning\n\n将覆盖该 ID 的现有内容，不使用当前全能模式直装选择。", "$title\nID: ${current.id}\nFile: $filename\n${current.version} → ${target.version}\nLa sostituzione usa sempre il payload classico/BIN: ${payload.size} B$currentWarning\n\nIl contenuto esistente con questo ID verrà sovrascritto; la scelta di installazione diretta per la modalità completa non viene usata.", "$title\nID: ${current.id}\nFile: $filename\n${current.version} → ${target.version}\nReplacement always uses the classic/BIN payload: ${payload.size} B$currentWarning\n\nThe existing content with this ID will be overwritten; the full-mode direct-install choice is not used.", "$title\nID: ${current.id}\nArchivo: $filename\n${current.version} → ${target.version}\nLa sustitución usa siempre la carga clásica/BIN: ${payload.size} B$currentWarning\n\nSe sobrescribirá el contenido existente con este ID; no se usa la elección de instalación directa del modo completo.", "$title\nID : ${current.id}\nFichier : $filename\n${current.version} → ${target.version}\nLe remplacement utilise toujours la charge classique/BIN : ${payload.size} B$currentWarning\n\nLe contenu existant avec cet ID sera écrasé ; le choix d'installation directe du mode complet n'est pas utilisé.", "$title\nID: ${current.id}\nFicheiro: $filename\n${current.version} → ${target.version}\nA substituição usa sempre a carga clássica/BIN: ${payload.size} B$currentWarning\n\nO conteúdo existente com este ID será substituído; a escolha de instalação direta do modo completo não é usada.", "$title\nID: ${current.id}\nArquivo: $filename\n${current.version} → ${target.version}\nA substituição usa sempre a carga clássica/BIN: ${payload.size} B$currentWarning\n\nO conteúdo existente com este ID será sobrescrito; a escolha de instalação direta do modo completo não é usada.", "$title\nID: ${current.id}\nФайл: $filename\n${current.version} → ${target.version}\nЗамяната винаги използва класическото/BIN съдържание: ${payload.size} B$currentWarning\n\nСъществуващото съдържание с това ID ще бъде презаписано; изборът за директно инсталиране в пълен режим не се използва.", "$title\nID: ${current.id}\nDosya: $filename\n${current.version} → ${target.version}\nDeğiştirme her zaman klasik/BIN içeriği kullanır: ${payload.size} B$currentWarning\n\nBu ID'ye sahip mevcut içeriğin üzerine yazılacak; tam mod doğrudan yükleme seçimi kullanılmaz.", "$title\nID: ${current.id}\nDatei: $filename\n${current.version} → ${target.version}\nDas Ersetzen verwendet immer den klassischen/BIN-Inhalt: ${payload.size} B$currentWarning\n\nDer vorhandene Inhalt mit dieser ID wird überschrieben; die Direktinstallationswahl für den Vollmodus wird nicht verwendet.", "$title\nID: ${current.id}\nФайл: $filename\n${current.version} → ${target.version}\nЗамена всегда использует классическое содержимое BIN: ${payload.size} B$currentWarning\n\nСуществующее содержимое с этим ID будет перезаписано; выбор прямой установки для полного режима не используется."), true, Tr.tr("替换", "Sostituisci", "Replace", "Sustituir", "Remplacer", "Substituir", "Substituir", "Замяна", "Değiştir", "Ersetzen", "Заменить"), {
                // 确认先创建会话，再廉价重验；原卡/源副本与上下文已闭包捕获，不能改选其他行。
                startReplacement(target, imported, payload, title, filename)
            })
        } catch(e: Exception) { replacementUiRejected(Core.code(e)) }
    }
    private fun startReplacement(target: Pick, imported: HwtSource, payload: HwtSource.Snapshot, title: String, fileName: String) {
        val request = runtime.watchfaces.newReplacementRequest(target.face, target.version, target.epoch)
        if(!runtime.openReplacementDialog(request, title, fileName)) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        fileOnlyPreview = true
        apply(runtime.snapshot())
        runtime.logs.record("REPLACE_UI_CONFIRM", "id=${target.face.id} version=${target.version} captured_epoch=${target.epoch} current_epoch=${runtime.connection.epoch()} token_consumed=true")
        try {
            val actual = currentPickFace(target)
            if(source !== imported || sourcePayloads[HwtSource.Mode.BIN] !== payload) throw Core.Fault("SOURCE_SELECTION_CHANGED")
            if(runtime.watchfaces.busy()) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
            runtime.watchfaces.beginReplacement(actual, target.version, payload, target.epoch, request)
        } catch(e: Exception) { replacementUiRejected(Core.code(e), request.identity) }
    }
    override fun interruptReplacement(token: Long) {
        val visible = mutable.value.replacementDialog
        if(closed || !ReplacementDialogPolicy.canInterrupt(visible, token)) return
        val captured = runtime.snapshot()
        val identity = captured.replacementDialogIdentity ?: return
        if(!replacementIdentityMatches(visible, identity) || !replacementIdentityMatches(captured.replacementDialog, identity) ||
            !ReplacementDialogPolicy.canInterrupt(captured.replacementDialog, token)) return
        val epoch = identity.epoch
        main.post {
            val current = runtime.snapshot()
            if(closed || current.replacementDialogIdentity !== identity || epoch != runtime.connection.epoch() || current.epoch != epoch ||
                !replacementIdentityMatches(mutable.value.replacementDialog, identity) || !ReplacementDialogPolicy.canInterrupt(mutable.value.replacementDialog, token) ||
                !replacementIdentityMatches(current.replacementDialog, identity) || !ReplacementDialogPolicy.canInterrupt(current.replacementDialog, token)) return@post
            runtime.interruptReplacement(identity)
            apply(runtime.snapshot())
        }
    }
    override fun dismissReplacementResult(token: Long) {
        val visible = mutable.value.replacementDialog
        if(closed || !ReplacementDialogPolicy.canDismiss(visible, token)) return
        val captured = runtime.snapshot()
        val identity = captured.replacementDialogIdentity ?: return
        if(!replacementIdentityMatches(visible, identity) || !replacementIdentityMatches(captured.replacementDialog, identity) ||
            !ReplacementDialogPolicy.canDismiss(captured.replacementDialog, token)) return
        main.post {
            val current = runtime.snapshot()
            // 失败可能已关闭并推进连接 epoch；结果关闭只要求原会话身份和非活动，不要求旧连接仍存活。
            if(closed || current.replacementDialogIdentity !== identity || !replacementIdentityMatches(mutable.value.replacementDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(mutable.value.replacementDialog, token) || !replacementIdentityMatches(current.replacementDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(current.replacementDialog, token)) return@post
            runtime.dismissReplacementResult(identity)
            apply(runtime.snapshot())
        }
    }
    override fun dismissDeletionResult(token: Long) {
        val visible = mutable.value.deletionDialog
        if(closed || !ReplacementDialogPolicy.canDismiss(visible, token)) return
        val captured = runtime.snapshot()
        val identity = captured.deletionDialogIdentity ?: return
        if(!deletionIdentityMatches(visible, identity) || !deletionIdentityMatches(captured.deletionDialog, identity) ||
            !ReplacementDialogPolicy.canDismiss(captured.deletionDialog, token)) return
        main.post {
            val current = runtime.snapshot()
            if(closed || current.deletionDialogIdentity !== identity || !deletionIdentityMatches(mutable.value.deletionDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(mutable.value.deletionDialog, token) || !deletionIdentityMatches(current.deletionDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(current.deletionDialog, token)) return@post
            runtime.dismissDeletionResult(identity)
            apply(runtime.snapshot())
        }
    }
    private fun dispatchOperation(kind: String, epoch: Long, action: () -> Unit) {
        if(removalWorking() || removalBlocks(runtime.connection.address())) throw Core.Fault("DEVICE_UNPAIR_PENDING")
        if(deletionPreparation != null || replacementActive()) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        val queued = QueuedStart(tokens.incrementAndGet(), kind, epoch, runtime.snapshot().operationRevision)
        startingOperation = queued
        mutable.update { it.copy(operation = OperationInfo(kind, "WAIT_OPERATION", if(kind == "deletion") Tr.tr("正在开始删除", "Avvio dell'eliminazione", "Starting deletion", "Iniciando la eliminación", "Démarrage de la suppression", "A iniciar a eliminação", "Iniciando a exclusão", "Започва изтриването", "Silme başlıyor", "Löschen wird gestartet", "Запуск удаления") else Tr.tr("正在开始替换", "Avvio della sostituzione", "Starting replacement", "Iniciando la sustitución", "Démarrage du remplacement", "A iniciar a substituição", "Iniciando a substituição", "Започва замяната", "Değiştirme başlıyor", "Ersetzen wird gestartet", "Запуск замены"), null, true)) }
        try {
            action()
            // 串行屏障确保已执行原生入队；只有新的操作快照才能替代此前摘要。
            runtime.connection.post {
                main.post {
                    if(!closed && startingOperation == queued) {
                        startingOperation = null
                        val current = runtime.snapshot()
                        val newOperation = current.epoch == queued.epoch && current.operationRevision > queued.operationRevision && current.kind == queued.kind
                        if(newOperation) fileOnlyPreview = false
                        else if(current.message.isNotBlank()) fileOnlyPreview = true
                        apply(current)
                        if(!newOperation && current.message.isNotBlank()) {
                            mutable.update { it.copy(operation = OperationInfo(kind, "PRECHECK_FAILED", runtimeError(kind, "PRECHECK_FAILED", current.message), null, false, true)) }
                        }
                    }
                }
            }
        } catch(e: Exception) {
            startingOperation = null
            apply(runtime.snapshot())
            throw e
        }
    }
    override fun requestActivation() {}
    override fun requestActivation(face: FaceItem, epoch: Long) {
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || preparing || filePickToken != null || startingOperation != null || deletionPreparation != null || replacementActive() ||
            runtime.watchfaces.busy() || pending != null || mutable.value.prompt != null || mutable.value.needsPin) return
        var identity: WatchfaceManager.ActivationIdentity? = null
        try {
            val row = captureRow(face, epoch)
            val actual = currentActivationFace(row)
            if(FacePolicy.current(actual)) { message(Tr.tr("该表盘正在使用", "Questo quadrante è in uso", "This watch face is in use", "Esta esfera está en uso", "Ce cadran est utilisé", "Este mostrador está em uso", "Este mostrador está em uso", "Този циферблат се използва", "Bu kadran kullanımda", "Dieses Zifferblatt wird verwendet", "Этот циферблат используется")); return }
            val request = runtime.watchfaces.newActivationRequest(actual, epoch)
            identity = request.identity
            val title = row.rendered.title
            if(!runtime.openActivationDialog(request, title)) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
            fileOnlyPreview = true
            apply(runtime.snapshot())
            runtime.logs.record("ACTIVATE_UI_REQUEST", "id=${actual.id} version=${actual.version} captured_epoch=$epoch token_consumed=true original_version=true file_budget=0")
            val current = currentActivationFace(row)
            if(runtime.watchfaces.busy()) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
            runtime.watchfaces.beginActivation(current, epoch, request)
        } catch(e: Exception) {
            val code = Core.code(e)
            identity?.let { runtime.rejectActivationDialog(it, code) }
            if(!closed) { apply(runtime.snapshot()); message(activationError(code)) }
        }
    }
    override fun dismissActivationResult(token: Long) {
        val visible = mutable.value.activationDialog
        if(closed || !ReplacementDialogPolicy.canDismiss(visible, token)) return
        val captured = runtime.snapshot(); val identity = captured.activationDialogIdentity ?: return
        if(!activationIdentityMatches(visible, identity) || !activationIdentityMatches(captured.activationDialog, identity)) return
        main.post {
            val current = runtime.snapshot()
            if(closed || current.activationDialogIdentity !== identity || !activationIdentityMatches(mutable.value.activationDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(mutable.value.activationDialog, token) || !activationIdentityMatches(current.activationDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(current.activationDialog, token)) return@post
            runtime.dismissActivationResult(identity);apply(runtime.snapshot())
        }
    }
    override fun requestDeletion() {}
    override fun requestDeletion(face: FaceItem, epoch: Long) {
        if(closed || pending != null || deletionPreparation != null || mutable.value.prompt != null || mutable.value.needsPin || filePickToken != null) return
        if(removalWorking() || removalBlocks(runtime.connection.address()) || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy()) return
        var preparation: DeletionPreparation? = null
        try {
            val row = captureRow(face, epoch)
            if(!FacePolicy.eligible(row.face)) throw Core.Fault("SELECT_EXISTING_ORDINARY_FACE")
            val captured = DeletionPreparation(tokens.incrementAndGet(), row)
            preparation = captured; deletionPreparation = captured
            runtime.logs.record(Tr.tr("删除准备", "Preparazione eliminazione", "Preparing deletion", "Preparando la eliminación", "Préparation de la suppression", "A preparar a eliminação", "Preparando a exclusão", "Подготовка за изтриване", "Silme hazırlanıyor", "Löschen wird vorbereitet", "Подготовка удаления"), "token=${captured.token} id=${row.face.id} version=${row.face.version} captured_epoch=$epoch new_list_required=true")
            runtime.watchfaces.prepareDeletion(row.face, epoch, object : WatchfaceManager.DeletionPreparationListener {
                override fun onPrepared(prepared: TlvView.Face, preparedEpoch: Long) {
                    main.post {
                        if(closed || deletionPreparation !== captured) return@post
                        deletionPreparation = null
                        confirmDeletion(captured, prepared, preparedEpoch)
                    }
                }
                override fun onFailure(code: String) {
                    main.post {
                        if(deletionPreparation !== captured) return@post
                        deletionPreparation = null
                        val current = runtime.snapshot()
                        if(rowContextIsCurrent(captured.row, current) && !replacementActive() && !current.active && pending == null && mutable.value.prompt == null) {
                            apply(current); message(deletionError(code))
                        }
                    }
                }
            })
        } catch(e: Exception) {
            if(deletionPreparation === preparation) deletionPreparation = null
            if(!closed) message(deletionError(Core.code(e)))
        }
    }
    private fun confirmDeletion(preparation: DeletionPreparation, prepared: TlvView.Face, epoch: Long) {
        val row = preparation.row
        if(!rowContextIsCurrent(row, runtime.snapshot())) return
        if(preparing || filePickToken != null || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || pending != null || mutable.value.prompt != null || mutable.value.needsPin) return
        try {
            if(epoch != row.epoch || !matchesRow(row.rendered, prepared)) throw Core.Fault("DELETE_PREPARATION_CHANGED")
            val actual = currentDeletionFace(row)
            val title = row.rendered.title
            val currentWarning = if(FacePolicy.current(actual)) Tr.tr("\n该表盘正在使用，删除后手表可能切换到其他表盘。", "\nQuesto quadrante è in uso; dopo l'eliminazione il watch potrebbe passare a un altro quadrante.", "\nThis watch face is in use; after deletion the watch may switch to another watch face.", "\nEsta esfera está en uso; tras eliminarla el reloj puede pasar a otra esfera.", "\nCe cadran est utilisé ; après la suppression, la montre peut passer à un autre cadran.", "\nEste mostrador está em uso; depois de eliminado o relógio pode passar para outro mostrador.", "\nEste mostrador está em uso; depois da exclusão o relógio pode passar para outro mostrador.", "\nТози циферблат се използва; след изтриването часовникът може да премине към друг циферблат.", "\nBu kadran kullanımda; silindikten sonra saat başka bir kadrana geçebilir.", "\nDieses Zifferblatt wird verwendet; nach dem Löschen kann die Uhr zu einem anderen Zifferblatt wechseln.", "\nЭтот циферблат используется; после удаления часы могут переключиться на другой циферблат.") else ""
            prompt("deletion", Tr.tr("删除该表盘？", "Eliminare questo quadrante?", "Delete this watch face?", "¿Eliminar esta esfera?", "Supprimer ce cadran ?", "Eliminar este mostrador?", "Excluir este mostrador?", "Изтриване на този циферблат?", "Bu kadran silinsin mi?", "Dieses Zifferblatt löschen?", "Удалить этот циферблат?"), Tr.tr("$title\nID：${actual.id}\n原版本：${actual.version}$currentWarning\n\n将从手表移除该表盘，删除后无法直接恢复。", "$title\nID: ${actual.id}\nVersione originale: ${actual.version}$currentWarning\n\nIl quadrante verrà rimosso dal watch e non potrà essere recuperato direttamente.", "$title\nID: ${actual.id}\nOriginal version: ${actual.version}$currentWarning\n\nThe watch face will be removed from the watch and can't be directly recovered.", "$title\nID: ${actual.id}\nVersión original: ${actual.version}$currentWarning\n\nLa esfera se quitará del reloj y no se podrá recuperar directamente.", "$title\nID : ${actual.id}\nVersion d'origine : ${actual.version}$currentWarning\n\nLe cadran sera retiré de la montre et ne pourra pas être récupéré directement.", "$title\nID: ${actual.id}\nVersão original: ${actual.version}$currentWarning\n\nO mostrador será removido do relógio e não poderá ser recuperado diretamente.", "$title\nID: ${actual.id}\nVersão original: ${actual.version}$currentWarning\n\nO mostrador será removido do relógio e não poderá ser recuperado diretamente.", "$title\nID: ${actual.id}\nОригинална версия: ${actual.version}$currentWarning\n\nЦиферблатът ще бъде премахнат от часовника и не може да се възстанови директно.", "$title\nID: ${actual.id}\nOrijinal sürüm: ${actual.version}$currentWarning\n\nKadran saatten kaldırılacak ve doğrudan geri getirilemeyecek.", "$title\nID: ${actual.id}\nOriginalversion: ${actual.version}$currentWarning\n\nDas Zifferblatt wird von der Uhr entfernt und kann nicht direkt wiederhergestellt werden.", "$title\nID: ${actual.id}\nИсходная версия: ${actual.version}$currentWarning\n\nЦиферблат будет удалён с часов, и восстановить его напрямую будет нельзя."), true, Tr.tr("删除", "Elimina", "Delete", "Eliminar", "Supprimer", "Eliminar", "Excluir", "Изтриване", "Sil", "Löschen", "Удалить"), { startDeletion(row, title) }, ownedToken = preparation.token)
        } catch(e: Exception) { message(deletionError(Core.code(e))) }
    }
    private fun startDeletion(row: RowTarget, title: String) {
        val face = row.face; val epoch = row.epoch
        val request = runtime.watchfaces.newDeletionRequest(face, epoch)
        if(!runtime.openDeletionDialog(request, title)) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        fileOnlyPreview = true
        apply(runtime.snapshot())
        runtime.logs.record("DELETE_UI_CONFIRM", "id=${face.id} version=${face.version} captured_epoch=$epoch current_epoch=${runtime.connection.epoch()} token_consumed=true file_budget=0")
        try {
            val actual = currentDeletionFace(row)
            if(runtime.watchfaces.busy()) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
            runtime.watchfaces.beginDeletion(actual, epoch, request)
        } catch(e: Exception) { deletionUiRejected(Core.code(e), request.identity) }
    }
    private fun prompt(kind: String, title: String, message: String, danger: Boolean, positive: String, action: () -> Unit, cancel: () -> Unit = {}, negative: String = Tr.tr("取消", "Annulla", "Cancel", "Cancelar", "Annuler", "Cancelar", "Cancelar", "Отказ", "İptal", "Abbrechen", "Отмена"), ownedToken: Long? = null) {
        invalidatePrompt(); val token = ownedToken ?: tokens.incrementAndGet(); pending = Pending(token, action, cancel); mutable.update { it.copy(prompt = ActionPrompt(token, kind, title, message, danger, positive, negative)) }
    }
    private fun invalidatePrompt() { pending = null; deletionPreparation = null; mutable.update { it.copy(prompt = null) } }
    override fun confirmAction(token: Long) { val p = pending ?: return; if(p.token != token || mutable.value.prompt?.token != token || closed) return; pending = null; mutable.update { it.copy(prompt = null) }; runCatching(p.action).onFailure { val code = Core.code(it); runtime.logs.record("UI_ACTION_ERROR", "code=$code"); message(Tr.tr("操作未开始：$code", "Operazione non avviata: $code", "Operation not started: $code", "Operación no iniciada: $code", "Opération non démarrée : $code", "Operação não iniciada: $code", "Operação não iniciada: $code", "Операцията не е започнала: $code", "İşlem başlamadı: $code", "Vorgang nicht gestartet: $code", "Операция не начата: $code")) } }
    override fun dismissAction(token: Long) { val p = pending ?: return; if(p.token != token) return; val connectionCancel = mutable.value.prompt?.kind == "account-warning"; pending = null; mutable.update { it.copy(prompt = null, connectionFormRevision = it.connectionFormRevision + if(connectionCancel) 1L else 0L) }; p.cancel() }
    override fun exportLogs(uri: Uri) { val text = runtime.logs.snapshot(); io.execute { runCatching { app.contentResolver.openOutputStream(uri, "wt").use { if(it == null) throw IllegalStateException(); it.write(text.toByteArray(Charsets.UTF_8)) } }.onSuccess { main.post { message(Tr.tr("已导出脱敏诊断日志", "Log diagnostico anonimizzato esportato", "Anonymized diagnostic log exported", "Registro de diagnóstico anonimizado exportado", "Journal de diagnostic anonymisé exporté", "Registo de diagnóstico anonimizado exportado", "Registro de diagnóstico anonimizado exportado", "Анонимният диагностичен дневник е експортиран", "Anonim tanılama günlüğü dışa aktarıldı", "Anonymisiertes Diagnoseprotokoll exportiert", "Обезличенный диагностический журнал экспортирован")) } }.onFailure { main.post { message(Tr.tr("日志导出失败", "Esportazione del log non riuscita", "Log export failed", "Error al exportar el registro", "Échec de l'exportation du journal", "Falha ao exportar o registo", "Falha ao exportar o registro", "Експортирането на дневника не бе успешно", "Günlük dışa aktarılamadı", "Protokollexport fehlgeschlagen", "Не удалось экспортировать журнал")) } } } }
    override fun clearLogs() { if(!closed && !runtime.watchfaces.busy() && !replacementActive() && !preparing && startingOperation == null && deletionPreparation == null) runtime.logs.clear() else message(Tr.tr("操作中保留日志", "Conserva i log durante le operazioni", "Keep logs during operations", "Conservar registros durante las operaciones", "Conserver les journaux pendant les opérations", "Manter registos durante as operações", "Manter registros durante as operações", "Запазване на дневниците по време на операции", "İşlemler sırasında günlükleri sakla", "Protokolle während der Vorgänge behalten", "Сохранять журналы во время операций")) }
    private fun cleanAsync(s: HwtSource) {
        val cleanup = Runnable { runCatching { HwtSource.cleanup(app.filesDir, s.directory) }.onFailure { runtime.logs.record("CACHE_ERROR", "code=${it.javaClass.simpleName}") } }
        try { io.execute(cleanup) }
        catch (_: RejectedExecutionException) {
            // 排队导入可能在界面关闭、文件执行器停止接收任务后完成。
            // 在后台清理已退休的私有副本，避免影响前台连接服务，
            // 界面关闭时不删除仍由传输运行时使用的文件。
            runCatching { Thread(cleanup, "hwt-retired-cleanup").apply { isDaemon = true }.start() }
                .onFailure { runtime.logs.record("CACHE_ERROR", "code=CLEANUP_SCHEDULING_FAILED") }
        }
    }
    override fun clearCache() {
        if(closed) return
        if(runtime.watchfaces.busy() || replacementActive() || preparing || startingOperation != null || deletionPreparation != null || filePickToken != null) { message(Tr.tr("请先结束当前表盘操作", "Termina prima l'operazione sul quadrante in corso", "Finish the current watch face operation first", "Termina primero la operación de esfera en curso", "Terminez d'abord l'opération en cours sur le cadran", "Termine primeiro a operação em curso no mostrador", "Conclua primeiro a operação em andamento no mostrador", "Първо завършете текущата операция с циферблат", "Önce süren kadran işlemini bitirin", "Beende zuerst den laufenden Zifferblatt-Vorgang", "Сначала завершите текущую операцию с циферблатом")); return }
        invalidatePrompt()
        val old = source; source = null; sourcePayloads = emptyMap(); chosenPayloadKind = null
        old?.let(::cleanAsync)
        mutable.update { it.copy(source = null) }
        message(Tr.tr("导入缓存已清除", "Cache di importazione svuotata", "Import cache cleared", "Caché de importación borrada", "Cache d'importation vidé", "Cache de importação limpa", "Cache de importação limpo", "Кешът за импортиране е изчистен", "İçe aktarma önbelleği temizlendi", "Import-Cache geleert", "Кэш импорта очищен"))
    }
    override fun onCleared() { closed = true; filePickToken = null; scanGeneration++; invalidatePrompt(); scanner.stop(); runtime.removeObserver(observer); runtime.unpair.removeListener(removalObserver); updateIo.shutdownNow(); io.shutdown(); super.onCleared() }
}
