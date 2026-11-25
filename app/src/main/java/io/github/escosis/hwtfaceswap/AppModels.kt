package io.github.escosis.hwtfaceswap

import android.net.Uri

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class NearbyWatch(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val rssi: Int? = null,
    val transport: String = Tr.tr("经典蓝牙", "Bluetooth classico", "Classic Bluetooth", "Bluetooth clásico", "Bluetooth classique", "Bluetooth clássico", "Bluetooth clássico", "Класически Bluetooth", "Klasik Bluetooth", "Klassisches Bluetooth"),
    val likelyHuawei: Boolean = true,
) {
    val addressLabel: String get() = address.ifBlank { Tr.tr("地址未提供", "Indirizzo non fornito", "Address not provided", "Dirección no proporcionada", "Adresse non fournie", "Endereço não fornecido", "Endereço não fornecido", "Не е посочен адрес", "Adres belirtilmedi", "Adresse nicht angegeben") }
}
/** 记录此前已就绪的连接，不代表设备当前的系统配对状态。 */
data class SavedWatch(
    val address: String,
    val name: String,
    val transport: String = Tr.tr("经典蓝牙", "Bluetooth classico", "Classic Bluetooth", "Bluetooth clásico", "Bluetooth classique", "Bluetooth clássico", "Bluetooth clássico", "Класически Bluetooth", "Klasik Bluetooth", "Klassisches Bluetooth"),
    val lastConnectedAt: Long = 0L,
    /** 同一 MAC 首次可靠读到的根层 0A 型号；缺失不覆盖，随原历史删除路径清除。 */
    val model: String? = null,
    /** 同一 MAC 首次可靠读到的根层 09 序列号；离线保留，随原历史删除路径清除。 */
    val serialNumber: String? = null,
) {
    fun nearby(): NearbyWatch = NearbyWatch(address, name, bonded = false, transport = transport)
}
data class FaceItem(
    val id: String,
    val version: String,
    val rawType: Long,
    val expandType: Long?,
    val name: String? = null,
    val current: Boolean = false,
    val selectable: Boolean = false,
    val kind: String = Tr.tr("类型未确认", "Tipo non confermato", "Type not confirmed", "Tipo no confirmado", "Type non confirmé", "Tipo não confirmado", "Tipo não confirmado", "Типът не е потвърден", "Tür doğrulanmadı", "Typ nicht bestätigt"),
    val activatable: Boolean = false,
) {
    val title: String get() = when (kind) {
        Tr.tr("趣味贴纸", "Adesivi divertenti", "Fun stickers", "Pegatinas divertidas", "Autocollants amusants", "Autocolantes divertidos", "Adesivos divertidos", "Забавни стикери", "Eğlenceli çıkartmalar", "Lustige Sticker"), Tr.tr("相册表盘", "Quadrante galleria", "Photo watch face", "Esfera de fotos", "Cadran photo", "Mostrador de fotos", "Mostrador de fotos", "Циферблат със снимки", "Fotoğraf kadranı", "Foto-Zifferblatt"), Tr.tr("视频表盘", "Quadrante video", "Video watch face", "Esfera de vídeo", "Cadran vidéo", "Mostrador de vídeo", "Mostrador de vídeo", "Видео циферблат", "Video kadranı", "Video-Zifferblatt") -> kind
        else -> name?.takeIf { it.isNotBlank() } ?: Tr.tr("表盘 $id", "Quadrante $id", "Watch face $id", "Esfera $id", "Cadran $id", "Mostrador $id", "Mostrador $id", "Циферблат $id", "Kadran $id", "Zifferblatt $id")
    }
}
data class SourceInfo(
    val title: String,
    val fileName: String,
    val sourceBytes: Long,
    val payloadBytes: Int,
    val xmlVersion: String,
    val sha256: String,
    val previewPath: String? = null,
    val previewSha256: String? = null,
    /** 是否已冻结可用于覆盖的经典 BIN；与直装类型选择独立。 */
    val replacementAvailable: Boolean = true,
    val payloadKind: String = "bin",
    /** 只列已成功冻结的直装载荷；检测到但未准备好的类型不能被操作。 */
    val payloadKinds: List<String> = listOf("bin"),
    val detectedPayloadKinds: List<String> = payloadKinds,
    val screen: String = Tr.tr("未提供", "Non fornito", "Not provided", "No proporcionado", "Non fourni", "Não fornecido", "Não fornecido", "Не е посочено", "Belirtilmedi", "Nicht angegeben"),
    val payloadDiagnostic: String = "",
    /** 同一文件重新导入也更换 UI owner；本地序号不是线上资源或随机数。 */
    val importToken: Long = 0,
)
data class OperationInfo(
    val kind: String = "",
    val stage: String = "",
    val detail: String = "",
    val progress: Int? = null,
    val active: Boolean = false,
    val failed: Boolean = false,
)
/** 替换或删除会话的展示快照；kind 区分操作，token 是不随进度修订变化的本地任务身份。 */
data class ReplacementDialogInfo(
    val token: Long,
    val connectionEpoch: Long,
    val targetId: String,
    val targetVersion: String,
    val title: String,
    val fileName: String,
    val operation: OperationInfo,
    val interruptRequested: Boolean = false,
)

/** 只保护界面令牌与关闭资格，不代替运行时的身份、代次或协议判断。 */
internal object ReplacementDialogPolicy {
    fun canInterrupt(dialog: ReplacementDialogInfo?, token: Long): Boolean =
        dialog != null && dialog.operation.kind == "replacement" && dialog.token == token && dialog.operation.active && !dialog.interruptRequested

    fun canDismiss(dialog: ReplacementDialogInfo?, token: Long): Boolean =
        dialog != null && dialog.token == token && !dialog.operation.active
}

data class ActionPrompt(
    val token: Long,
    val kind: String,
    val title: String,
    val message: String,
    val destructive: Boolean,
    val positiveLabel: String,
    val negativeLabel: String = Tr.tr("取消", "Annulla", "Cancel", "Cancelar", "Annuler", "Cancelar", "Cancelar", "Отказ", "İptal", "Abbrechen"),
)
data class AppUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val scanning: Boolean = false,
    val devices: List<NearbyWatch> = emptyList(),
    val savedDevices: List<SavedWatch> = emptyList(),
    val autoConnect: Boolean = false,
    /** 每安装的本地说明已读状态；旧公开界面 fixture 默认不占用模态窗口。 */
    val introductionPending: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    val checkingUpdate: Boolean = false,
    val updateMessage: String = "",
    val pendingUpdate: UpdateInfo? = null,
    val appVersion: String = "1.0.1",
    val selectedDeviceAddress: String? = null,
    val connectionFormRevision: Long = 0L,
    // 明文编辑值只留在进程内，不进入界面恢复数据、普通偏好或日志。
    val huaweiIdInput: String = "",
    val phaseCode: String = "IDLE",
    val connectionEpoch: Long = 0,
    val phase: String = Tr.tr("尚未连接", "Non ancora connesso", "Not connected yet", "Aún no conectado", "Pas encore connecté", "Ainda não ligado", "Ainda não conectado", "Още не е свързано", "Henüz bağlı değil", "Noch nicht verbunden"),
    val detail: String = Tr.tr("搜索设备，输入华为 ID 后连接", "Cerca il dispositivo, inserisci l'ID Huawei e connettiti", "Search for the device, enter the Huawei ID and connect", "Busca el dispositivo, introduce el ID de Huawei y conéctate", "Recherchez l'appareil, saisissez l'ID Huawei et connectez-vous", "Procure o dispositivo, introduza o ID Huawei e ligue-se", "Procure o dispositivo, insira o ID Huawei e conecte", "Потърсете устройството, въведете Huawei ID и се свържете", "Cihazı arayın, Huawei ID'yi girin ve bağlanın", "Suche das Gerät, gib die Huawei-ID ein und verbinde dich"),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val watchName: String = "",
    val deviceAddress: String = "",
    val serialNumber: String = Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"),
    val firmware: String = Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"),
    /** 当前已验证连接实际读到的型号；主页优先使用 savedDevices 中不可覆盖的同 MAC 缓存。 */
    val deviceModel: String? = null,
    val batteryPercent: Int? = null,
    val freeMemoryBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val capability: Long? = null,
    val faces: List<FaceItem> = emptyList(),
    val listReady: Boolean = false,
    val listRefreshing: Boolean = false,
    val selectedId: String? = null,
    val source: SourceInfo? = null,
    val pickingHwt: Boolean = false,
    val operation: OperationInfo = OperationInfo(),
    val replacementDialog: ReplacementDialogInfo? = null,
    val deletionDialog: ReplacementDialogInfo? = null,
    val activationDialog: ReplacementDialogInfo? = null,
    val prompt: ActionPrompt? = null,
    val needsPin: Boolean = false,
    val pinMessage: String = Tr.tr("请在手表确认配对，输入手表显示的配对码", "Conferma l'abbinamento sul watch e inserisci il codice di abbinamento mostrato sul watch", "Confirm pairing on the watch and enter the pairing code shown on the watch", "Confirma el emparejamiento en el reloj e introduce el código que muestra el reloj", "Confirmez l'association sur la montre et saisissez le code affiché sur la montre", "Confirme o emparelhamento no relógio e introduza o código mostrado no relógio", "Confirme o pareamento no relógio e insira o código mostrado no relógio", "Потвърдете сдвояването на часовника и въведете кода, показан на часовника", "Eşleştirmeyi saatte onaylayın ve saatte görünen kodu girin", "Bestätige die Kopplung auf der Uhr und gib den auf der Uhr angezeigten Code ein"),
    val accountConfigured: Boolean = false,
    val huaweiIdDirty: Boolean = false,
    // 解除配对包含系统设置/重试等待；只有真实 BOND_NONE 与持久清理均成功才结束。
    val unpairing: Boolean = false,
    val unpairWorking: Boolean = false,
    val unpairToken: Long = 0L,
    val unpairAddress: String? = null,
    val unpairMessage: String = "",
    val unpairNeedsSettings: Boolean = false,
    /** 只有尚未开始资料清理时才能撤回解除配对。 */
    val unpairCancelable: Boolean = false,
    val rebindCount: Int = 0,
    val quarantine: Boolean = false,
    val logs: List<String> = emptyList(),
    val statusMessage: String = "",
    val noticeSerial: Long = 0L,
    val noticeMessage: String = "",
) {
    /** 运行时只保留一项可见操作会话；界面与旧回调使用同一个全模态守卫。 */
    val operationDialog: ReplacementDialogInfo? get() = replacementDialog ?: deletionDialog ?: activationDialog
}

/** 界面操作边界，由实际运行时完成连接与表盘操作。 */
interface AppActions {
    fun setTheme(mode: ThemeMode)
    fun scan()
    fun stopScan()
    fun selectDevice(device: NearbyWatch)
    fun setHuaweiId(value: String)
    fun saveHuaweiId()
    /** 只读取当前已知目标 MAC 的加密配置；空串表示未配置，null 表示不可读或当前不可操作。 */
    fun readHuaweiIdForDevice(device: NearbyWatch): String?
    /** 只保存指定设备的配置；失败返回 false，其他设备的当前编辑值保持不变。 */
    fun saveHuaweiIdForDevice(device: NearbyWatch, value: String): Boolean
    fun setAutoConnect(enabled: Boolean)
    /** 仅检查本地说明窗口资格，不代表厂商协议接受。真实 VM 还核对最新 Runtime。 */
    fun introductionInteractionIsCurrent(): Boolean = false
    /** 只有可靠保存本地已读标记才返回 true；不在此入口连接手表。 */
    fun acknowledgeIntroduction(): Boolean = false
    fun setAutoCheckUpdate(enabled: Boolean)
    fun checkUpdates()
    fun dismissUpdate()
    fun connect(device: NearbyWatch, huaweiId: String)
    fun disconnect()
    /** 创建绑定真实目标 MAC 的一次可见确认，继续使用 confirmAction/dismissAction。 */
    fun requestUnpair(device: NearbyWatch)
    fun openUnpairSettings()
    fun retryUnpair()
    /** 撤回尚未开始清理的解除配对标记；一次资料都没有删除。 */
    fun cancelUnpair() {}
    /** 用户确认后清除本应用协议密钥并重新绑定；页面常驻入口。 */
    fun rebindNow() {}
    fun submitPin(expectedEpoch: Long, pin: String)
    fun cancelPin(expectedEpoch: Long)
    fun refreshFaces()
    fun selectFace(id: String)
    fun prepareHwtPick(): Boolean
    fun cancelHwtPick()
    fun importHwt(uri: Uri)
    fun requestReplacement()
    fun requestDirectInstall() {}
    /** 只切换本次导入可用的载荷类型（经典 BIN / 全能模式容器 / 移除 bin 的派生容器）。 */
    fun selectPayloadKind(kind: String) {}
    /** 逐卡入口绑定该卡原始观察，不依赖全局选中状态。 */
    fun requestReplacement(face: FaceItem, epoch: Long) {}
    fun requestActivation(face: FaceItem, epoch: Long) {}
    fun requestDeletion(face: FaceItem, epoch: Long) {}
    /** 仅显式中断当前令牌；实际停止前仍保持 active。旧界面假实现可保持无操作。 */
    fun interruptReplacement(token: Long) {}
    /** 仅关闭同一令牌的非活动结果，不改变原生操作、错误或隔离状态。 */
    fun dismissReplacementResult(token: Long) {}
    /** 对所选已安装普通表盘使用原版本启用，不需要 HWT 文件。 */
    fun requestActivation() {}
    fun dismissActivationResult(token: Long) {}
    fun requestDeletion()
    /** 只关闭同一删除令牌的非活动结果；不提供删除中断，不改变错误、隔离或其他操作。 */
    fun dismissDeletionResult(token: Long) {}
    fun confirmAction(token: Long)
    fun dismissAction(token: Long)
    fun exportLogs(uri: Uri)
    fun clearLogs()
    fun clearCache()
}
