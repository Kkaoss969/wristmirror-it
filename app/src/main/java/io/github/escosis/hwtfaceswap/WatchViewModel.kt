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
                    prompt("rebind", "Riassociare il protocollo?", "La riassociazione potrebbe cambiare il modo in cui il watch si collega ad altre app. Continuare?", true, "Conferma riassociazione",
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
        val history = runCatching { savedWatches.devices() }.getOrElse { message("Impossibile leggere i dati del dispositivo: ${Core.code(it)}"); emptyList() }
        val autoConnect = runCatching { savedWatches.autoConnect() }.getOrElse { message("Impossibile leggere l'impostazione di connessione automatica: ${Core.code(it)}"); false }
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
            mutable.update { it.copy(selectedDeviceAddress = initialAddress, watchName = device?.name ?: "Watch Huawei") }
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
        if(s.success) return "Abbinamento annullato, dati del dispositivo cancellati"
        val detail = s.message
        return when {
            detail.startsWith("Impossibile leggere il contrassegno di annullamento abbinamento in sospeso") -> "Impossibile leggere lo stato in sospeso, riapri l'app."
            detail.startsWith("Bluetooth di sistema non attivo") -> "Il Bluetooth è disattivato, attivalo e ricontrolla."
            detail.startsWith("Autorizzazione Dispositivi nelle vicinanze non disponibile") -> "Concedi l'autorizzazione Dispositivi nelle vicinanze nelle impostazioni dell'app, poi ricontrolla."
            detail.startsWith("Impossibile leggere in modo affidabile questo MAC") -> "Impossibile confermare lo stato di abbinamento, ricontrolla."
            detail.contains("pulizia dei dati dell'app non salvata in modo affidabile") -> "Pulizia dei dati del dispositivo non completata, ricontrolla"
            detail.startsWith("Contrassegno in sospeso o arresto della connessione non completati in modo affidabile") -> "Operazione non completata, ricontrolla."
            detail.contains("L'operazione sul quadrante in corso non è ancora terminata") || detail.startsWith("Termina prima l'operazione sul quadrante in corso") -> "Termina prima l'operazione sul quadrante in corso."
            s.needsSettings -> "Annulla l'abbinamento nelle impostazioni Bluetooth di sistema, poi torna qui a ricontrollare"
            s.pending -> "Conferma dell'annullamento abbinamento"
            else -> "Operazione non completata, ricontrolla."
        }
    }
    private fun phaseLabel(code: String): String = when (code) {
        "IDLE" -> "Non ancora connesso"
        "CONNECTING" -> "Connessione al watch"
        "SYSTEM_PAIRING" -> "In attesa dell'abbinamento di sistema"
        "LINK_PARAMS", "DEVICE_STATUS" -> "Lettura dei parametri del collegamento"
        "SECURITY", "LITE_AUTH", "ISO_AUTH", "BOND_PARAMS", "BOND_KEY" -> "Verifica dell'autenticazione del dispositivo"
        "PIN_REQUEST", "PIN_INPUT" -> "In attesa del codice di abbinamento"
        "VERIFY_SESSION" -> "Verifica della chiave di sessione corrente"
        "COMMAND_CAPS", "EXPAND_CAPS" -> "Autenticazione riuscita, lettura delle funzionalità del dispositivo"
        "ACCOUNT", "SETTINGS", "AGREEMENTS", "REVERSE_CAPS", "SETUP_DEVICE", "ACTIVITY_TYPES", "WEAR_STATUS", "CONNECT_NOTIFY" -> "Autenticazione riuscita, completamento dell'inizializzazione"
        "AGREEMENT_CONFIRM" -> "Completamento dell'inizializzazione della connessione"
        "REBIND_CONFIRM", "REBINDING" -> "Riassocia protocollo"
        "READY" -> "Connessione pronta"
        else -> "Connessione non completata"
    }
    private fun operationLabel(kind: String, stage: String, active: Boolean, failed: Boolean, metadata: String = ""): String = when(stage) {
        "COMPLETED" -> DirectInstallState.completionNotice(metadata)
        "LISTED" -> if(kind == "direct_install") {
            if(!active && !failed) "Quadrante installato" else if(failed) "Risultato dell'installazione non confermato" else "Verifica del risultato dell'installazione"
        } else if(!active && !failed) "Quadrante sostituito" else if(failed) "Risultato della sostituzione non confermato" else "Verifica del risultato della sostituzione"
        "DELETED" -> if(!active && !failed) "Quadrante eliminato" else if(failed) "Risultato dell'eliminazione non confermato" else "Verifica del risultato dell'eliminazione"
        "ACTIVATED" -> if(!active && !failed) "Quadrante attivato" else if(failed) "Risultato dell'attivazione non confermato" else "Verifica del risultato dell'attivazione"
        "NO_TRANSFER" -> "Il dispositivo non ha richiesto file, nessun trasferimento effettuato"
        "PRECHECK_FAILED" -> if(kind == "deletion") "Eliminazione non avviata, nessun comando inviato al watch" else if(kind == "activation") "Attivazione non avviata, aggiorna e riprova" else "Sostituzione non avviata, nessun comando inviato al watch"
        "TIMED_OUT" -> "Operazione scaduta, riconnettiti e ricontrolla"
        "FAILED", "CANCELLED" -> OperationFailureText.forReason(metadata) ?: "Operazione non completata in modo affidabile, riconnettiti e ricontrolla"
        "WAIT_OPERATION", "WAIT_DEVICE" -> "In attesa della conferma di ricezione dal dispositivo"
        "TRANSFERRING" -> "Trasferimento del contenuto originale"
        "WAIT_REPORT", "WAIT_POST" -> "In attesa del report del watch e della conferma dell'app (max 30 secondi)"
        "WAIT_LIST" -> if(kind == "deletion") "Verifica dei due nuovi elenchi dopo l'eliminazione" else if(kind == "activation") "Verifica del quadrante attuale" else "Verifica dell'elenco con la nuova versione"
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
            operation.stage == "PRECHECK_FAILED" -> if(operation.kind == "direct_install") "Installazione non avviata: ${operation.detail}" else replacementError(operation.detail)
            operation.stage == "CANCELLED" && operation.detail == "USER_CANCELLED_BEFORE_SEND" -> "Sostituzione annullata, nessun comando inviato al watch"
            operation.stage == "CANCELLED" -> "Sostituzione annullata, riconnettiti e ricontrolla"
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
            preparing -> OperationInfo("source", "PREPARING", "Lettura e blocco dell'HWT locale", null, true, false)
            queuedStart != null -> OperationInfo(queuedStart.kind, "WAIT_OPERATION", if(queuedStart.kind == "deletion") "Avvio dell'eliminazione" else "Avvio della sostituzione", null, true, false)
            dialog != null && (dialog.operation.active || !s.active) -> dialog.operation
            fileOnlyPreview && !s.active && !s.failed -> OperationInfo()
            else -> OperationInfo(s.kind, s.stage, operationLabel(s.kind, s.stage, s.active, s.failed, s.operationDetail), s.progress, s.active, s.failed)
        }
        // 就绪状态优先于本地排队标记，连接完成后不再保留“正在连接”。
        if(s.ready) queuedConnectEpoch = null
        mutable.update { old -> old.copy(phaseCode = s.code, connectionEpoch = s.epoch,
            phase = if(queuedConnectEpoch != null) "Connessione al watch" else phaseLabel(s.code), detail = connectionDetail(s),
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
        "AGREEMENT_CONFIRM" -> "Prosecuzione dell'inizializzazione della connessione"
        "SYSTEM_PAIRING" -> "Conferma l'abbinamento di sistema sul telefono e sul watch"
        "ACCOUNT" -> "Sincronizzazione dell'ID Huawei"
        "PIN_REQUEST" -> "Richiesta delle informazioni di abbinamento al watch"
        "PIN_INPUT" -> "Inserisci il codice di abbinamento mostrato sul watch"
        "BOND_PARAMS" -> "Lettura dei parametri di associazione del protocollo"
        "BOND_KEY" -> "Completamento dell'associazione del protocollo"
        "REBINDING" -> "Riassociazione del protocollo in corso"
        "READY" -> if(s.detail.contains("alcune conferme facoltative non ricevute")) "Inizializzazione della connessione completata; alcune conferme facoltative non ricevute" else "Inizializzazione della connessione completata"
        "FAILED" -> ConnectionRecovery.describe(s.detail)
        else -> s.detail
    }
    private fun deviceMetadata(address: String): NearbyWatch? = sessionDevice?.takeIf { it.address.equals(address, true) }
        ?: mutable.value.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
    private fun savedHuaweiId(address: String?): String = if(address == null || removalBlocks(address)) "" else runCatching {
        runtime.secure.huaweiId(address)
    }.getOrElse { message("Impossibile leggere l'ID Huawei di questo dispositivo: ${Core.code(it)}"); "" }
    private fun restoreHuaweiId(address: String?) {
        val id = savedHuaweiId(address)
        savedEditorId = id
        mutable.update { it.copy(huaweiIdInput = id, accountConfigured = id.isNotBlank(), huaweiIdDirty = false) }
    }
    private fun syncSuccessfulHistory(s: AppRuntime.Snapshot) {
        runCatching { successfulHistory(runtime, savedWatches, s) ?: savedWatches.devices() }.onSuccess { history ->
            // 断开时也同步本地身份缓存；已保存的型号与序列号不依赖旧 READY 界面任务再次执行。
            mutable.update { it.copy(savedDevices = history) }
        }.onFailure { message("Salvataggio dei dati del dispositivo non riuscito: ${Core.code(it)}") }
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
        if(!mutable.value.accountConfigured) { message("Impossibile leggere l'ID Huawei dell'ultimo dispositivo; inseriscilo di nuovo e connettiti"); return null }
        return device
    }
    private fun message(text: String) {
        if(closed) return
        runtime.logs.record("Avviso", text)
        mutable.update { it.copy(statusMessage = text, noticeSerial = it.noticeSerial + 1L, noticeMessage = text) }
    }
    override fun setTheme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); mutable.update { it.copy(theme = mode) } }
    override fun selectDevice(device: NearbyWatch) {
        if(closed || mutable.value.introductionPending || removalWorking() || startingOperation != null || deletionPreparation != null || replacementActive()) return
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("Indirizzo del dispositivo non valido"); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message("Impossibile verificare lo stato dei dati del dispositivo: ${Core.code(it)}"); return }
        if(generation > 0L && knownUnpairDevice(address) == null) { message("I dati di questo dispositivo sono stati cancellati; cercalo di nuovo e selezionalo"); return }
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
        val address = mutable.value.selectedDeviceAddress ?: run { message("Seleziona prima un watch"); return }
        val id = mutable.value.huaweiIdInput.trim()
        if(id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 128 || id.any(Char::isISOControl) || (id.all(Char::isDigit) && id.length > 19)) {
            message("Inserisci un ID Huawei valido"); return
        }
        runCatching { runtime.secure.saveHuaweiId(address, id) }.onSuccess {
            savedEditorId = id
            mutable.update { it.copy(huaweiIdInput = id, huaweiIdDirty = false, accountConfigured = true,
                connectionFormRevision = it.connectionFormRevision + 1L) }
            message(if(mutable.value.connected) "ID Huawei salvato, sarà usato alla prossima connessione" else "ID Huawei salvato")
        }.onFailure { message("Salvataggio dell'ID Huawei non riuscito: ${Core.code(it)}") }
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
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("Indirizzo del dispositivo non valido"); return null }
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
        }.getOrElse { message("Impossibile leggere l'ID Huawei di questo dispositivo: ${Core.code(it)}"); null }
    }
    override fun saveHuaweiIdForDevice(device: NearbyWatch, value: String): Boolean {
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("Indirizzo del dispositivo non valido"); return false }
        val snapshot = runtime.snapshot()
        val epoch = snapshot.epoch
        if(!deviceHuaweiIdSnapshotCurrent(address, epoch, snapshot)) return false
        val id = value.trim()
        if(id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 128 || id.any(Char::isISOControl) || (id.all(Char::isDigit) && id.length > 19)) {
            message("Inserisci un ID Huawei valido"); return false
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
            message(if(mutable.value.connected && runtime.connection.address().equals(address, true)) "ID Huawei salvato, sarà usato alla prossima connessione" else "ID Huawei salvato")
            true
        }.getOrElse { message("Salvataggio dell'ID Huawei non riuscito: ${Core.code(it)}"); false }
    }
    override fun setAutoConnect(enabled: Boolean) {
        if(closed) return
        runCatching { savedWatches.setAutoConnect(enabled) }.onSuccess { mutable.update { it.copy(autoConnect = enabled) } }
            .onFailure { message("Salvataggio dell'impostazione di connessione automatica non riuscito: ${Core.code(it)}") }
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
        if(!introductionRead.acknowledge()) { message("Impossibile salvare lo stato di lettura delle istruzioni, riprova"); return false }
        if(closed) return false
        mutable.update { it.copy(introductionPending = false) }
        return true
    }
    override fun setAutoCheckUpdate(enabled: Boolean) {
        if(closed) return
        if(prefs.edit().putBoolean("auto_check_update", enabled).commit()) mutable.update { it.copy(autoCheckUpdate = enabled) }
        else message("Impossibile salvare l'impostazione di controllo automatico aggiornamenti")
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
        mutable.update { it.copy(checkingUpdate = true, updateMessage = "Controllo aggiornamenti in corso") }
        try { updateIo.execute {
            val result = UpdateChecker.fetchLatest()
            main.post {
                if(closed) return@post
                val current = mutable.value
                val found = (result as? UpdateResult.Available)?.info?.takeIf { UpdateChecker.isNewer(it.version, current.appVersion) }
                val description = when(result) {
                    is UpdateResult.Available -> if(found != null) "Nuova versione disponibile: ${found.version}" else "Hai già l'ultima versione (${current.appVersion})"
                    UpdateResult.NoRelease -> "Il progetto non ha ancora pubblicato release"
                    is UpdateResult.Failure -> result.message
                }
                runtime.logs.record("Controllo aggiornamenti", description)
                mutable.update { it.copy(checkingUpdate = false, updateMessage = description,
                    pendingUpdate = when {
                        automatic && !it.autoCheckUpdate -> it.pendingUpdate
                        result is UpdateResult.Failure -> it.pendingUpdate
                        else -> found
                    }) }
            }
        } } catch(_: RejectedExecutionException) {
            mutable.update { it.copy(checkingUpdate = false, updateMessage = "Controllo aggiornamenti non avviato, riapri l'app e riprova") }
        }
    }
    override fun scan() {
        if(closed || mutable.value.introductionPending || removalWorking()) return
        if(!scanner.hasPermissions()) { message("Concedi l'autorizzazione Dispositivi nelle vicinanze; su Android 11 o precedenti serve anche la posizione"); return }
        val generation = ++scanGeneration
        mutable.update { it.copy(scanning = true, statusMessage = "Ricerca dei dispositivi vicini e già abbinati nel sistema") }
        scanner.start(object : WatchScanner.Listener {
            override fun onItems(items: MutableList<WatchScanner.Item>?) {
                val result = items.orEmpty().map { NearbyWatch(it.address, it.name, it.bonded, it.rssi, if(it.bleOnly) "Bluetooth Low Energy" else "Bluetooth classico") }
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
        if(!connectionEpochIsCurrent(expectedEpoch)) { message("Lo stato della connessione è cambiato, riconnetti il dispositivo selezionato"); return }
        if(removalWorking() || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy()) { message("Termina prima l'operazione in corso"); return }
        if(Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { message("Concedi l'autorizzazione Dispositivi nelle vicinanze"); return }
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("Indirizzo del dispositivo non valido"); return }
        if(removalBlocks(address)) { message("L'annullamento abbinamento di questo MAC attende ancora conferma o pulizia; nessuna riconnessione. Ricontrolla"); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message("Impossibile verificare lo stato dei dati del dispositivo: ${Core.code(it)}"); return }
        if(generation > 0L && knownUnpairDevice(address) == null) { message("I dati di questo dispositivo sono stati cancellati; cercalo di nuovo e connettiti"); return }
        val id = huaweiId.trim()
        if(id.isNotEmpty() && (id.toByteArray(Charsets.UTF_8).size > 128 || (id.all(Char::isDigit) && id.length > 19))) { message("Lunghezza dell'ID Huawei non supportata"); return }
        if(mutable.value.selectedDeviceAddress != address) selectDevice(device)
        if(id.isEmpty()) {
            // 明确的空输入代表当前设备已加密保存的华为 ID，
            // 不代表界面草稿或另一设备的最近使用值。
            restoreHuaweiId(address)
            if(!mutable.value.accountConfigured) { message("Per questo watch non è salvato alcun ID Huawei; inseriscilo e connettiti"); return }
        } else setHuaweiId(id)
        val requested = device.copy(address = address)
        val revision = mutable.value.connectionFormRevision
        val action = {
            if(closed || mutable.value.introductionPending || expectedEpoch != runtime.connection.epoch() || revision != mutable.value.connectionFormRevision || !runtime.secure.removals().usable(address, generation)) message("Lo stato della connessione è cambiato, riconnetti il dispositivo selezionato")
            else if(removalWorking() || removalBlocks(address) || preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy()) message("L'operazione in corso non è terminata, connessione non cambiata")
            else {
                stopScan(); invalidatePrompt()
                try {
                    ContextCompat.startForegroundService(app, Intent(app, ConnectionService::class.java))
                    sessionDevice = requested; queuedConnectEpoch = expectedEpoch
                    mutable.update { it.copy(watchName = requested.name, selectedDeviceAddress = address,
                        connectionFormRevision = it.connectionFormRevision + 1L, connecting = true, statusMessage = "") }
                    // 连接层加密保存明确输入的 ID，复用当前设备认证密钥，
                    // 不重置系统配对。
                    runtime.connection.connectIfEpoch(expectedEpoch, address, requested.transport == "Bluetooth Low Energy", id)
                } catch(e: Exception) {
                    queuedConnectEpoch = null
                    val code = if(e is SecurityException) "BLUETOOTH_PERMISSION_REQUIRED" else Core.code(e)
                    mutable.update { it.copy(connecting = runtime.snapshot().connecting) }
                    message("Connessione non avviata: $code")
                }
            }
        }
        if(id.isNotEmpty() && (!id.all(Char::isDigit) || id.length == 11)) prompt("account-warning", "Conferma ID Huawei", "Potrebbe non essere un ID Huawei. Verifica che sia lo stesso usato in Huawei Health. Vuoi usarlo comunque?", false, "Usa comunque", action)
        else action()
    }
    override fun disconnect() {
        if(closed) return
        if(replacementActive()) { message("Attendi il termine dell'operazione in corso"); return }
        invalidatePrompt(); mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L) }; runtime.connection.disconnect(); message("Disconnessione richiesta")
    }
    private fun knownUnpairDevice(address: String): NearbyWatch? =
        mutable.value.devices.firstOrNull { it.address.equals(address, true) }
            ?: mutable.value.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
            ?: sessionDevice?.takeIf { it.address.equals(address, true) && runtime.connection.address().equals(address, true) }
    override fun requestUnpair(device: NearbyWatch) {
        if(closed) return
        if(runtime.unpair.snapshot().pending) { message("Un dispositivo attende già la verifica dell'annullamento abbinamento; ricontrolla prima quello"); return }
        if(preparing || startingOperation != null || deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) { message("Termina prima l'operazione sul quadrante in corso; annullamento abbinamento non avviato"); return }
        if(pending != null) return
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("Indirizzo del dispositivo non valido; annullamento abbinamento non avviato"); return }
        val target = knownUnpairDevice(address) ?: run { message("Avvia l'annullamento abbinamento da un dispositivo reale tra quelli selezionati, trovati o recenti"); return }
        val epoch = runtime.connection.epoch()
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message("Impossibile verificare il dispositivo in attesa di annullamento abbinamento: ${Core.code(it)}"); return }
        val token = tokens.incrementAndGet()
        prompt("unpair", "Annullare l'abbinamento?",
            "${target.name}\nMAC: $address\n\nAnnulla l'abbinamento di sistema e cancella i dati di questo dispositivo in WristMirror: record, ID Huawei e credenziali di autenticazione. I quadranti sul watch non vengono toccati.",
            true, "Annulla abbinamento", {
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
            message("Disconnetti o annulla prima la connessione, poi riassocia il protocollo"); return
        }
        if(mutable.value.introductionPending || removalWorking() || preparing || startingOperation != null ||
            deletionPreparation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) {
            message("Termina prima l'operazione in corso"); return
        }
        if(Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            message("Concedi l'autorizzazione Dispositivi nelle vicinanze"); return
        }
        val address = SavedWatchStore.canonicalAddress(mutable.value.selectedDeviceAddress ?: runtime.connection.address())
            ?: run { message("Seleziona prima un dispositivo, poi riassocia il protocollo"); return }
        if(removalBlocks(address)) { message("L'annullamento abbinamento di questo dispositivo attende ancora conferma o pulizia; riassociazione non eseguita"); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse {
            message("Impossibile verificare lo stato dei dati del dispositivo: ${Core.code(it)}"); return
        }
        if(generation > 0L && knownUnpairDevice(address) == null) { message("I dati di questo dispositivo sono stati cancellati; cercalo di nuovo e connettiti"); return }
        val transport = mutable.value.savedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.transport
            ?: mutable.value.devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.transport
        val bleOnly = transport == "Bluetooth Low Energy"
        prompt("rebind_now", "Riassociare il protocollo?",
            "Verranno cancellate le chiavi di protocollo salvate dall'app per questo watch e verrà subito ripetuto l'abbinamento del protocollo. Di solito serve dopo il passaggio del watch tra modalità risparmio energetico e modalità completa.\nLa riassociazione potrebbe cambiare il modo in cui il watch si collega ad altre app. Continuare?",
            true, "Riassocia", {
                if(closed || removalWorking() || removalBlocks(address) || !runtime.secure.removals().usable(address, generation))
                    throw Core.Fault("REBIND_TARGET_CHANGED")
                stopScan(); invalidatePrompt()
                try {
                    ContextCompat.startForegroundService(app, Intent(app, ConnectionService::class.java))
                    sessionDevice = NearbyWatch(address, mutable.value.watchName.ifBlank { "Dispositivo senza nome" }, false)
                    mutable.update { it.copy(selectedDeviceAddress = address, connectionFormRevision = it.connectionFormRevision + 1L,
                        connecting = true, statusMessage = "") }
                    runtime.logs.record("REBIND_UI_CONFIRM", "target_known=true transport=${if(bleOnly) "gatt" else "rfcomm_secure"} no_auto_retry=true")
                    runtime.connection.rebind(address, bleOnly)
                } catch(e: Exception) {
                    val code = if(e is SecurityException) "BLUETOOTH_PERMISSION_REQUIRED" else Core.code(e)
                    mutable.update { it.copy(connecting = runtime.snapshot().connecting) }
                    message("Riassociazione non avviata: $code")
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
            runtime.logs.record("Aggiornamento elenco", "Richiesta dell'elenco dei quadranti al watch")
            runtime.watchfaces.refresh()
        }
    }
    @Deprecated("Global face selection is disabled; use the row-scoped action overloads")
    override fun selectFace(id: String) {}
    private fun replacementError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> "L'elenco è scaduto o ancora in caricamento; nessun comando di sostituzione inviato. Aggiorna l'elenco e riseleziona l'HWT."
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE", "VERSION_CHANGED" -> "Il quadrante o la versione selezionati sono cambiati; nessun comando di sostituzione inviato. Aggiorna e riseleziona."
        "SELECTED_CONNECTION_CHANGED" -> "La connessione è cambiata; nessun comando di sostituzione inviato. Riseleziona sul watch attuale."
        "PAYLOAD_SNAPSHOT_UNAVAILABLE" -> "La copia locale del file non è più valida; nessun comando di sostituzione inviato. Riseleziona l'HWT."
        "SOURCE_SELECTION_CHANGED" -> "Il file HWT è cambiato, tocca di nuovo Sostituisci."
        else -> "Sostituzione non avviata, nessun comando inviato: $code"
    }
    private fun deletionError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> "L'elenco è scaduto o ancora in caricamento; nessun comando di eliminazione inviato. Aggiorna l'elenco e conferma di nuovo."
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE", "DELETE_PREPARATION_CHANGED" -> "Il quadrante o la versione originale selezionati sono cambiati; nessun comando di eliminazione inviato. Aggiorna e riseleziona."
        "SELECTED_CONNECTION_CHANGED" -> "La connessione è cambiata; nessun comando di eliminazione inviato. Riseleziona sul watch attuale."
        else -> "Eliminazione non avviata, nessun comando inviato: $code"
    }
    private fun activationError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> "L'elenco è scaduto o ancora in caricamento; aggiorna e riprova ad attivare"
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE" -> "Il quadrante o la sua versione originale sono cambiati; aggiorna e riprova dalla scheda corrispondente"
        "SELECTED_CONNECTION_CHANGED" -> "La connessione è cambiata; riprova dalla scheda corrispondente del watch attuale"
        "SELECT_EXISTING_ACTIVATABLE_FACE" -> "Risorse o tipo di questo quadrante non confermati: impossibile attivarlo"
        else -> "Attivazione non avviata: $code"
    }
    private fun runtimeError(kind: String, stage: String, code: String): String {
        if(kind == "replacement" && stage == "PRECHECK_FAILED") return replacementError(code)
        if(kind == "deletion" && stage == "PRECHECK_FAILED") return deletionError(code)
        if(kind == "activation" && stage == "PRECHECK_FAILED") return activationError(code)
        return when(code) {
            "WATCHFACE_OPERATION_BUSY", "WAIT_FOR_CURRENT_OPERATION_OR_QUERY" -> "Attendi il termine dell'operazione o della richiesta in corso"
            "CONNECT_AND_AUTHENTICATE_FIRST" -> "Connetti prima il watch e riprova"
            "LIST_QUERY_TIMEOUT_RECONNECT" -> "Timeout nella lettura dell'elenco dei quadranti; riconnettiti e aggiorna"
            "DEVICE_LIST_ERROR" -> "Il watch non ha restituito un elenco valido, aggiorna di nuovo"
            "WIRE_FACE_LIST_INCOMPLETE" -> "Nell'elenco del watch alcune voci non hanno campi obbligatori come la versione; queste voci non verranno usate come destinazione, gli altri quadranti restano utilizzabili"
            "RECONNECT_AFTER_UNCERTAIN_FILE_RESULT" -> "L'operazione precedente non si è chiusa in modo affidabile; riconnettiti e aggiorna"
            "DELETE_PREPARATION_CHANGED" -> "Il quadrante selezionato è cambiato; aggiorna l'elenco e riseleziona"
            else -> "Operazione non completata: $code"
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
        if(closed || filePickToken == null) { if(!closed) message("La selezione del file non è più valida, riseleziona l'HWT"); return }
        filePickToken = null
        preparing = true
        mutable.update { it.copy(pickingHwt = false, operation = OperationInfo("source", "PREPARING", "Lettura e blocco dell'HWT locale", null, true)) }
        runtime.logs.record("SOURCE_READ_STARTED", "no_command_sent=true")
        try { io.execute {
            var loaded: HwtSource? = null
            try {
                val filename = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "HWT locale"
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
                        payload != null -> "HWT pronto: ${PayloadKinds.displayFormat(view.detectedPayloadKinds)}"
                        preparedKinds.isNotEmpty() -> "Payload predefinito non pronto: scegli manualmente nei dettagli del file un tipo di installazione diretta già preparato"
                        else -> "In questo HWT non è stato trovato contenuto trasferibile; controlla la diagnostica della struttura del file"
                    })
                }
            } catch(e: Exception) {
                loaded?.let(::cleanAsync)
                val code = if(e is SecurityException) "FILE_READ_PERMISSION_REQUIRED" else Core.code(e)
                runtime.logs.record("SOURCE_ERROR", "code=$code no_command_sent=true")
                main.post {
                    preparing = false
                    if(!closed) { mutable.update { it.copy(operation = OperationInfo()) }; apply(runtime.snapshot()); message("Lettura HWT non riuscita: $code") }
                }
            }
        } } catch(_: RejectedExecutionException) {
            preparing = false
            if(!closed) { mutable.update { it.copy(operation = OperationInfo()) }; apply(runtime.snapshot()); message("Lettura del file non avviata, riseleziona l'HWT") }
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
        val imported = source ?: run { message("Seleziona prima un file HWT"); return }
        val payload = selectedPayload() ?: run { message("In questo HWT non è stato trovato contenuto trasferibile"); return }
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
            prompt("direct_install", "Installare direttamente il quadrante?", "$filename\nFormato: ${PayloadKinds.displayFormat(ui.source?.detectedPayloadKinds ?: listOf(payloadKind))}\nVerrà trasferito: ${PayloadKinds.detail(payloadKind)}\nDimensione: ${payload.size} B\n\nVerifica prima che il watch sia un WATCH in modalità completa, oppure di un'altra serie/modalità con firmware precedente alla 6.0.0.32.", false, "Installa", {
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
        val imported = source ?: run { message("Seleziona prima un file HWT"); return }
        val payload = sourcePayloads[HwtSource.Mode.BIN] ?: run { message("Il formato XML si usa solo per l'installazione diretta su WATCH in modalità completa e non supporta la sostituzione; i WATCH in modalità completa supportano solo l'installazione diretta"); return }
        try {
            val row = captureRow(face, epoch)
            val (observed, snapshot) = currentRow(row)
            if(!FacePolicy.eligible(observed)) throw Core.Fault("SELECT_EXISTING_ORDINARY_FACE")
            val target = Pick(row, VersionPolicy.next(observed.id, observed.version, snapshot.faces))
            val current = currentPickFace(target)
            if(payload.source !== imported || !payload.file.isFile || payload.file.length() != payload.size.toLong()) throw Core.Fault("PAYLOAD_SNAPSHOT_UNAVAILABLE")
            val title = row.rendered.title
            val filename = mutable.value.source?.fileName ?: imported.title
            val currentWarning = if(FacePolicy.current(current)) "\nQuesto quadrante è in uso; durante la sostituzione il watch potrebbe cambiare schermata." else ""
            prompt("replacement", "Sostituire questo quadrante?", "$title\nID: ${current.id}\nFile: $filename\n${current.version} → ${target.version}\nLa sostituzione usa sempre il payload classico/BIN: ${payload.size} B$currentWarning\n\nIl contenuto esistente con questo ID verrà sovrascritto; la scelta di installazione diretta per la modalità completa non viene usata.", true, "Sostituisci", {
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
        mutable.update { it.copy(operation = OperationInfo(kind, "WAIT_OPERATION", if(kind == "deletion") "Avvio dell'eliminazione" else "Avvio della sostituzione", null, true)) }
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
            if(FacePolicy.current(actual)) { message("Questo quadrante è in uso"); return }
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
            runtime.logs.record("Preparazione eliminazione", "token=${captured.token} id=${row.face.id} version=${row.face.version} captured_epoch=$epoch new_list_required=true")
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
            val currentWarning = if(FacePolicy.current(actual)) "\nQuesto quadrante è in uso; dopo l'eliminazione il watch potrebbe passare a un altro quadrante." else ""
            prompt("deletion", "Eliminare questo quadrante?", "$title\nID: ${actual.id}\nVersione originale: ${actual.version}$currentWarning\n\nIl quadrante verrà rimosso dal watch e non potrà essere recuperato direttamente.", true, "Elimina", { startDeletion(row, title) }, ownedToken = preparation.token)
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
    private fun prompt(kind: String, title: String, message: String, danger: Boolean, positive: String, action: () -> Unit, cancel: () -> Unit = {}, negative: String = "Annulla", ownedToken: Long? = null) {
        invalidatePrompt(); val token = ownedToken ?: tokens.incrementAndGet(); pending = Pending(token, action, cancel); mutable.update { it.copy(prompt = ActionPrompt(token, kind, title, message, danger, positive, negative)) }
    }
    private fun invalidatePrompt() { pending = null; deletionPreparation = null; mutable.update { it.copy(prompt = null) } }
    override fun confirmAction(token: Long) { val p = pending ?: return; if(p.token != token || mutable.value.prompt?.token != token || closed) return; pending = null; mutable.update { it.copy(prompt = null) }; runCatching(p.action).onFailure { val code = Core.code(it); runtime.logs.record("UI_ACTION_ERROR", "code=$code"); message("Operazione non avviata: $code") } }
    override fun dismissAction(token: Long) { val p = pending ?: return; if(p.token != token) return; val connectionCancel = mutable.value.prompt?.kind == "account-warning"; pending = null; mutable.update { it.copy(prompt = null, connectionFormRevision = it.connectionFormRevision + if(connectionCancel) 1L else 0L) }; p.cancel() }
    override fun exportLogs(uri: Uri) { val text = runtime.logs.snapshot(); io.execute { runCatching { app.contentResolver.openOutputStream(uri, "wt").use { if(it == null) throw IllegalStateException(); it.write(text.toByteArray(Charsets.UTF_8)) } }.onSuccess { main.post { message("Log diagnostico anonimizzato esportato") } }.onFailure { main.post { message("Esportazione del log non riuscita") } } } }
    override fun clearLogs() { if(!closed && !runtime.watchfaces.busy() && !replacementActive() && !preparing && startingOperation == null && deletionPreparation == null) runtime.logs.clear() else message("Conserva i log durante le operazioni") }
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
        if(runtime.watchfaces.busy() || replacementActive() || preparing || startingOperation != null || deletionPreparation != null || filePickToken != null) { message("Termina prima l'operazione sul quadrante in corso"); return }
        invalidatePrompt()
        val old = source; source = null; sourcePayloads = emptyMap(); chosenPayloadKind = null
        old?.let(::cleanAsync)
        mutable.update { it.copy(source = null) }
        message("Cache di importazione svuotata")
    }
    override fun onCleared() { closed = true; filePickToken = null; scanGeneration++; invalidatePrompt(); scanner.stop(); runtime.removeObserver(observer); runtime.unpair.removeListener(removalObserver); updateIo.shutdownNow(); io.shutdown(); super.onCleared() }
}
