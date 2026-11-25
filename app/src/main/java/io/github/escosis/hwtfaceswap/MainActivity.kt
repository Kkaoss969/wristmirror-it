package io.github.escosis.hwtfaceswap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import io.github.escosis.hwtfaceswap.ui.WanJingApp

/** 系统界面边界；设备、账户和协议行为由界面模型与运行时处理。 */
class MainActivity : ComponentActivity() {
    private val watchViewModel by viewModels<WatchViewModel>()
    private val introductionStartupCompletion = IntroductionStartupCompletion(this)
    private var platformMessage by mutableStateOf("")
    private var pendingBluetoothAction: BluetoothAction? = null
    private var pendingNotificationAction: BluetoothAction? = null
    private var notificationRequested = false
    private var bluetoothRequestSerial = 0L
    private var pendingHwtPickToken: Long? = null

    private data class BluetoothAction(
        val serial: Long,
        val needsNotification: Boolean,
        val isValid: () -> Boolean,
        val run: () -> Unit,
    )

    private fun isCurrent(action: BluetoothAction): Boolean =
        !isFinishing && !isDestroyed && action.serial == bluetoothRequestSerial && action.isValid()

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (action == null || !isCurrent(action)) return@registerForActivityResult
        if (requiredBluetoothPermissions().all(::permissionGranted)) {
            ensureBluetoothEnabled(action)
        } else {
            platformMessage = if (Build.VERSION.SDK_INT >= 31) {
                Tr.tr("需要附近设备权限才能搜索或连接手表。可在系统设置中授权后重试。", "Serve l'autorizzazione Dispositivi nelle vicinanze per cercare il watch o connettersi. Puoi concederla nelle impostazioni di sistema e riprovare.", "Nearby devices permission is needed to search for or connect to the watch. You can grant it in system settings and try again.", "Se necesita el permiso de Dispositivos cercanos para buscar el reloj o conectarse. Puedes concederlo en los ajustes del sistema y volver a intentarlo.", "L'autorisation Appareils à proximité est nécessaire pour rechercher la montre ou s'y connecter. Accordez-la dans les paramètres système et réessayez.", "É necessária a permissão Dispositivos próximos para procurar o relógio ou ligar-se. Pode concedê-la nas definições do sistema e tentar de novo.", "É necessária a permissão Dispositivos por perto para procurar o relógio ou conectar. Você pode concedê-la nas configurações do sistema e tentar de novo.", "Нужно е разрешението „Устройства наблизо“, за да търсите часовника или да се свържете. Можете да го дадете в системните настройки и да опитате пак.", "Saati aramak veya bağlanmak için Yakındaki cihazlar izni gerekiyor. Sistem ayarlarından verip tekrar deneyebilirsiniz.", "Die Berechtigung „Geräte in der Nähe“ wird benötigt, um die Uhr zu suchen oder zu verbinden. Du kannst sie in den Systemeinstellungen erteilen und es erneut versuchen.")
            } else {
                Tr.tr("Android 11 及更早版本需要定位权限用于蓝牙搜索。", "Su Android 11 e versioni precedenti serve l'autorizzazione alla posizione per la ricerca Bluetooth.", "On Android 11 and earlier, location permission is needed for Bluetooth scanning.", "En Android 11 y anteriores se necesita el permiso de ubicación para buscar por Bluetooth.", "Sous Android 11 et versions antérieures, l'autorisation de localisation est nécessaire pour la recherche Bluetooth.", "No Android 11 e anteriores é necessária a permissão de localização para a pesquisa Bluetooth.", "No Android 11 e anteriores é necessária a permissão de localização para a busca Bluetooth.", "На Android 11 и по-стари версии за търсене по Bluetooth е нужно разрешение за местоположение.", "Android 11 ve önceki sürümlerde Bluetooth taraması için konum izni gerekir.", "Unter Android 11 und älter wird für die Bluetooth-Suche die Standortberechtigung benötigt.")
            }
        }
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (action == null || !isCurrent(action)) return@registerForActivityResult
        if (bluetoothEnabled()) {
            requestNotificationIfNeeded(action)
        } else {
            platformMessage = Tr.tr("蓝牙尚未开启。请开启系统蓝牙后重试。", "Il Bluetooth non è attivo. Attiva il Bluetooth di sistema e riprova.", "Bluetooth is off. Turn on system Bluetooth and try again.", "El Bluetooth está desactivado. Actívalo y vuelve a intentarlo.", "Le Bluetooth est désactivé. Activez-le et réessayez.", "O Bluetooth está desligado. Ligue-o e tente de novo.", "O Bluetooth está desligado. Ligue o Bluetooth e tente de novo.", "Bluetooth е изключен. Включете го и опитайте пак.", "Bluetooth kapalı. Açın ve tekrar deneyin.", "Bluetooth ist aus. Schalte es ein und versuche es erneut.")
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (action == null || !isCurrent(action)) return@registerForActivityResult
        if (!granted) {
            platformMessage = Tr.tr("通知权限未开启，连接仍可进行。后台连接状态可在系统任务管理中查看。", "Le notifiche non sono autorizzate, ma la connessione può comunque procedere. Lo stato della connessione in background è visibile nella gestione attività del sistema.", "Notifications are not allowed, but the connection can still proceed. The background connection status is visible in the system task manager.", "Las notificaciones no están permitidas, pero la conexión puede continuar. El estado de la conexión en segundo plano se ve en el administrador de tareas del sistema.", "Les notifications ne sont pas autorisées, mais la connexion peut continuer. L'état de la connexion en arrière-plan est visible dans le gestionnaire de tâches du système.", "As notificações não estão autorizadas, mas a ligação pode continuar. O estado da ligação em segundo plano vê-se no gestor de tarefas do sistema.", "As notificações não estão permitidas, mas a conexão pode continuar. O status da conexão em segundo plano aparece no gerenciador de tarefas do sistema.", "Известията не са разрешени, но връзката може да продължи. Състоянието на връзката във фонов режим се вижда в диспечера на задачите на системата.", "Bildirimlere izin verilmedi, ancak bağlantı devam edebilir. Arka plandaki bağlantı durumu sistemin görev yöneticisinde görünür.", "Benachrichtigungen sind nicht erlaubt, die Verbindung kann aber trotzdem laufen. Der Verbindungsstatus im Hintergrund ist im Task-Manager des Systems sichtbar.")
        }
        action.run()
    }

    private val openHwtDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val token = pendingHwtPickToken
        pendingHwtPickToken = null
        // 被解除配对确认退休的旧系统选择结果不能导入文件或取得新的持久授权。
        if(token == null || !watchViewModel.hwtPickIsCurrent(token)) return@registerForActivityResult
        if (uri != null) {
            persistDocumentGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            watchViewModel.importHwtFromPlatform(uri, token)
        } else {
            watchViewModel.cancelHwtPickFromPlatform(token)
        }
    }

    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            persistDocumentGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            watchViewModel.exportLogs(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRequested = savedInstanceState?.getBoolean("notificationRequested") ?: false
        pendingHwtPickToken = savedInstanceState?.takeIf { it.containsKey("pendingHwtPickToken") }?.getLong("pendingHwtPickToken")
        enableEdgeToEdge()
        setContent {
            val state by watchViewModel.state.collectAsStateWithLifecycle()
            WanJingApp(
                state = state,
                actions = watchViewModel,
                platformMessage = platformMessage,
                onDismissPlatformMessage = {
                    if(platformMessage.isNotBlank()) AppRuntime.get(this).logs.record(Tr.tr("系统提示", "Avviso di sistema", "System notice", "Aviso del sistema", "Avis système", "Aviso do sistema", "Aviso do sistema", "Системно известие", "Sistem bildirimi", "Systemhinweis"), platformMessage)
                    platformMessage = ""
                },
                onScan = ::scanWithPermissions,
                onConnect = { device, internalId -> connectWithPermissions(device, internalId) },
                onPickHwt = ::pickHwt,
                onExportLogs = ::exportLogs,
                onIntroductionDismissed = {
                    if(!isFinishing && !isDestroyed && introductionStartupCompletion.recordRetired(this,
                            acknowledged = !watchViewModel.state.value.introductionPending, current = watchViewModel.state.value)) {
                        consumeIntroductionStartupCompletion()
                    }
                },
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // PAUSED 时完成的说明退场，返回前台后复核当前状态；无收据时不会在 onResume 重连。
                watchViewModel.state.collect { consumeIntroductionStartupCompletion() }
            }
        }
        connectAtStartup()
        watchViewModel.checkUpdatesAtStartup()
    }

    override fun onResume() {
        super.onResume()
        io.github.escosis.hwtfaceswap.account.HuaweiIdWebSession.pruneAtStartup(this)
        // 设置页返回不是成功证据：重新经过当前适配器/权限/MAC 的真实 bond-state 读取。
        watchViewModel.verifyUnpairAfterSettings()
    }

    private fun consumeIntroductionStartupCompletion() {
        val visible = !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if(introductionStartupCompletion.take(this, visible, watchViewModel.state.value)) connectAtStartup()
    }

    private fun connectAtStartup() {
        val device = watchViewModel.takeStartupDevice() ?: return
        connectWithPermissions(device, "", automatic = true)
    }

    private fun connectWithPermissions(device: NearbyWatch, internalId: String, automatic: Boolean = false) {
        val requested = watchViewModel.state.value
        val revision = requested.connectionFormRevision
        val draft = requested.huaweiIdInput
        val epoch = requested.connectionEpoch
        // 过时的界面帧不能向新表单提供旧的明确凭据。
        // 历史或启动连接使用空输入标记，复用当前设备已加密保存的 ID。
        if (requested.selectedDeviceAddress != device.address ||
            (internalId.isNotEmpty() && internalId.trim() != draft.trim())
        ) return
        val isValid = {
            val current = watchViewModel.state.value
            !current.introductionPending && (!automatic || current.autoConnect) && current.selectedDeviceAddress == device.address &&
                current.connectionFormRevision == revision && watchViewModel.platformActionRevisionIsCurrent(revision) &&
                !UiPolicy.isUnpairTarget(current, device.address) && current.huaweiIdInput == draft &&
                current.connectionEpoch == epoch && watchViewModel.connectionEpochIsCurrent(epoch) &&
                !current.connected && !current.connecting &&
                !current.operation.active && current.prompt == null && !current.needsPin
        }
        withBluetoothPermissions(needsNotification = true, isValid = isValid) {
            watchViewModel.connectFromPlatform(device, internalId, epoch, revision)
        }
    }

    private fun scanWithPermissions() {
        val revision = watchViewModel.state.value.connectionFormRevision
        val isValid = {
            val current = watchViewModel.state.value
            !current.introductionPending && watchViewModel.platformActionRevisionIsCurrent(revision) &&
                !current.connected && !current.connecting && !current.operation.active &&
                current.prompt == null && !current.needsPin
        }
        withBluetoothPermissions(needsNotification = false, isValid = isValid) { watchViewModel.scan() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notificationRequested", notificationRequested)
        pendingHwtPickToken?.let { outState.putLong("pendingHwtPickToken", it) }
        super.onSaveInstanceState(outState)
    }

    private fun requiredBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun withBluetoothPermissions(needsNotification: Boolean, isValid: () -> Boolean, action: () -> Unit) {
        if (!isValid()) return
        // 系统活动结果不带本地令牌，因此保持原请求的归属。
        // 后续点击可修改表单，但不能替换该回调已捕获的操作。
        if (pendingBluetoothAction != null || pendingNotificationAction != null) {
            platformMessage = Tr.tr("请先完成系统权限或蓝牙提示，再重新点击。", "Completa prima la richiesta di autorizzazione o del Bluetooth, poi tocca di nuovo.", "Finish the permission or Bluetooth prompt first, then tap again.", "Termina primero la solicitud de permiso o de Bluetooth y vuelve a tocar.", "Terminez d'abord la demande d'autorisation ou de Bluetooth, puis touchez à nouveau.", "Termine primeiro o pedido de permissão ou de Bluetooth e toque de novo.", "Conclua primeiro o pedido de permissão ou de Bluetooth e toque de novo.", "Първо завършете заявката за разрешение или за Bluetooth, после докоснете пак.", "Önce izin veya Bluetooth isteğini tamamlayın, sonra tekrar dokunun.", "Schließe zuerst die Berechtigungs- oder Bluetooth-Anfrage ab und tippe dann erneut.")
            return
        }
        platformMessage = ""
        val pending = BluetoothAction(++bluetoothRequestSerial, needsNotification, isValid, action)
        val missing = requiredBluetoothPermissions().filterNot(::permissionGranted)
        if (missing.isEmpty()) {
            ensureBluetoothEnabled(pending)
        } else {
            pendingBluetoothAction = pending
            bluetoothPermissions.launch(missing.toTypedArray())
        }
    }

    private fun bluetoothEnabled(): Boolean = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    private fun ensureBluetoothEnabled(action: BluetoothAction) {
        if (!isCurrent(action)) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            platformMessage = Tr.tr("这台设备没有可用的蓝牙适配器。", "Questo dispositivo non ha un adattatore Bluetooth disponibile.", "This device has no Bluetooth adapter available.", "Este dispositivo no tiene un adaptador Bluetooth disponible.", "Cet appareil n'a pas d'adaptateur Bluetooth disponible.", "Este dispositivo não tem um adaptador Bluetooth disponível.", "Este dispositivo não tem um adaptador Bluetooth disponível.", "Това устройство няма наличен Bluetooth адаптер.", "Bu cihazda kullanılabilir Bluetooth bağdaştırıcısı yok.", "Dieses Gerät hat keinen verfügbaren Bluetooth-Adapter.")
            return
        }
        if (bluetoothEnabled()) {
            requestNotificationIfNeeded(action)
        } else {
            pendingBluetoothAction = action
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: ActivityNotFoundException) {
                pendingBluetoothAction = null
                platformMessage = Tr.tr("无法打开系统蓝牙开关，请在系统设置中开启蓝牙。", "Impossibile attivare il Bluetooth; attivalo dalle impostazioni di sistema.", "Couldn't turn on Bluetooth; turn it on in system settings.", "No se pudo activar el Bluetooth; actívalo en los ajustes del sistema.", "Impossible d'activer le Bluetooth ; activez-le dans les paramètres système.", "Não foi possível ligar o Bluetooth; ligue-o nas definições do sistema.", "Não foi possível ligar o Bluetooth; ligue-o nas configurações do sistema.", "Bluetooth не може да се включи; включете го от системните настройки.", "Bluetooth açılamadı; sistem ayarlarından açın.", "Bluetooth konnte nicht eingeschaltet werden; schalte es in den Systemeinstellungen ein.")
            } catch (_: SecurityException) {
                pendingBluetoothAction = null
                platformMessage = Tr.tr("蓝牙权限已被撤销，请重新授权后重试。", "L'autorizzazione Bluetooth è stata revocata; concedila di nuovo e riprova.", "Bluetooth permission was revoked; grant it again and retry.", "Se revocó el permiso de Bluetooth; concédelo de nuevo y reintenta.", "L'autorisation Bluetooth a été révoquée ; accordez-la à nouveau et réessayez.", "A permissão de Bluetooth foi revogada; conceda-a de novo e tente outra vez.", "A permissão de Bluetooth foi revogada; conceda-a de novo e tente outra vez.", "Разрешението за Bluetooth е отнето; дайте го отново и опитайте пак.", "Bluetooth izni geri alındı; tekrar verin ve yeniden deneyin.", "Die Bluetooth-Berechtigung wurde entzogen; erteile sie erneut und versuche es noch einmal.")
            }
        }
    }

    private fun requestNotificationIfNeeded(action: BluetoothAction) {
        if (!isCurrent(action)) return
        if (action.needsNotification && Build.VERSION.SDK_INT >= 33 &&
            !permissionGranted(Manifest.permission.POST_NOTIFICATIONS) && !notificationRequested
        ) {
            notificationRequested = true
            pendingNotificationAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action.run()
        }
    }

    private fun pickHwt() {
        if(pendingHwtPickToken != null) { platformMessage = Tr.tr("请先完成此前的系统文件选择，再重新点击。", "Completa prima la selezione del file in corso, poi tocca di nuovo.", "Finish the current file selection first, then tap again.", "Termina primero la selección de archivo en curso y vuelve a tocar.", "Terminez d'abord la sélection de fichier en cours, puis touchez à nouveau.", "Termine primeiro a seleção de ficheiro em curso e toque de novo.", "Conclua primeiro a seleção de arquivo em andamento e toque de novo.", "Първо завършете текущия избор на файл, после докоснете пак.", "Önce süren dosya seçimini tamamlayın, sonra tekrar dokunun.", "Schließe zuerst die laufende Dateiauswahl ab und tippe dann erneut."); return }
        if (!watchViewModel.prepareHwtPick()) return
        val token = watchViewModel.currentHwtPickToken() ?: return
        pendingHwtPickToken = token
        try {
            openHwtDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            pendingHwtPickToken = null
            watchViewModel.cancelHwtPickFromPlatform(token)
            platformMessage = Tr.tr("系统没有可用的文件选择器。请选择提供 Storage Access Framework 的文件应用。", "Nel sistema non è disponibile alcun selettore di file. Usa un'app file che supporti lo Storage Access Framework.", "No file picker is available on this system. Use a file app that supports the Storage Access Framework.", "No hay ningún selector de archivos en el sistema. Usa una app de archivos compatible con Storage Access Framework.", "Aucun sélecteur de fichiers n'est disponible. Utilisez une app de fichiers compatible avec le Storage Access Framework.", "Não há nenhum seletor de ficheiros no sistema. Use uma app de ficheiros compatível com o Storage Access Framework.", "Não há nenhum seletor de arquivos no sistema. Use um app de arquivos compatível com o Storage Access Framework.", "В системата няма инструмент за избор на файлове. Използвайте приложение за файлове, което поддържа Storage Access Framework.", "Sistemde dosya seçici yok. Storage Access Framework destekleyen bir dosya uygulaması kullanın.", "Im System ist keine Dateiauswahl verfügbar. Verwende eine Datei-App, die das Storage Access Framework unterstützt.")
        }
    }

    private fun exportLogs() {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        try {
            createLogDocument.launch(Tr.tr("腕镜-脱敏日志-$stamp.txt", "WristMirror-log-anonimizzato-$stamp.txt", "WristMirror-anonymized-log-$stamp.txt", "WristMirror-registro-anonimizado-$stamp.txt", "WristMirror-journal-anonymise-$stamp.txt", "WristMirror-registo-anonimizado-$stamp.txt", "WristMirror-registro-anonimizado-$stamp.txt", "WristMirror-anonimen-dnevnik-$stamp.txt", "WristMirror-anonim-gunluk-$stamp.txt", "WristMirror-anonymisiertes-Protokoll-$stamp.txt"))
        } catch (_: ActivityNotFoundException) {
            platformMessage = Tr.tr("系统没有可用的文档保存器，暂时无法导出日志。", "Nel sistema non è disponibile alcun salvataggio documenti; al momento non è possibile esportare i log.", "No document saver is available on this system; logs can't be exported right now.", "No hay ningún guardado de documentos en el sistema; ahora no se pueden exportar los registros.", "Aucun enregistreur de documents n'est disponible ; impossible d'exporter les journaux pour l'instant.", "Não há nenhum gravador de documentos no sistema; de momento não é possível exportar os registos.", "Não há nenhum salvador de documentos no sistema; no momento não é possível exportar os registros.", "В системата няма инструмент за запазване на документи; в момента дневниците не могат да се експортират.", "Sistemde belge kaydedici yok; şu anda günlükler dışa aktarılamıyor.", "Im System ist kein Dokumentspeicher verfügbar; Protokolle können gerade nicht exportiert werden.")
        }
    }

    private fun persistDocumentGrant(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // 部分文档提供方授予临时访问权限，可用于当前操作。
        }
    }
}
