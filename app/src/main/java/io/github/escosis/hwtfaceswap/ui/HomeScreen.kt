package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.R
import io.github.escosis.hwtfaceswap.protocol.DeviceInfo
import io.github.escosis.hwtfaceswap.protocol.DeviceTelemetry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 主页只显示模型信息；状态卡只按最新连接状态切页，不发起连接或设备查询。 */
@Composable
internal fun HomeScreen(
    state: AppUiState,
    insets: PaddingValues,
    onOpenStatus: (Int, () -> Boolean) -> Unit,
    scrollModifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets),
        contentPadding = PaddingValues(
            start = insets.calculateStartPadding(layoutDirection) + 12.dp,
            top = insets.calculateTopPadding() + 12.dp,
            end = insets.calculateEndPadding(layoutDirection) + 12.dp,
            bottom = insets.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "connection_status") { HomeConnectionStatusCard(state, onOpenStatus) }
        item(key = "device_info") { HomeDeviceInfoCard(state) }
    }
}

/** 保留原状态卡的绿红配色、高度和右下角水印。 */
@Composable
private fun HomeConnectionStatusCard(state: AppUiState, onOpenStatus: (Int, () -> Boolean) -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestOnOpenStatus by rememberUpdatedState(onOpenStatus)
    val navigationOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val navigationRequest = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    DisposableEffect(navigationOwner) {
        onDispose {
            navigationOwner.set(false)
            navigationRequest.incrementAndGet()
        }
    }
    val connected = state.connected
    val isDark = isSystemInDarkTheme()
    val cardColor = when {
        MiuixTheme.isDynamicColor && connected -> MiuixTheme.colorScheme.secondaryContainer
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.errorContainer
        connected && isDark -> Color(0xFF1A3825)
        connected -> Color(0xFFDFFAE4)
        isDark -> Color(0xFF3D1F1F)
        else -> Color(0xFFFFE8E0)
    }
    val iconTint = when {
        MiuixTheme.isDynamicColor && connected -> MiuixTheme.colorScheme.primary
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.error
        connected -> Color(0xFF4ADE80)
        else -> Color(0xFFEF4444)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = cardColor),
        onClick = statusClick@{
            val current = latestState
            if (!navigationOwner.get() || !homeStatusFrameCurrent(state, current)) return@statusClick
            val request = navigationRequest.incrementAndGet()
            val destination = homeStatusDestination(current)
            // 父级排队和执行切页时都会再次检查，不复用旧卡片、旧选择或旧请求。
            latestOnOpenStatus(destination) {
                navigationOwner.get() && navigationRequest.get() == request &&
                    homeStatusFrameCurrent(current, latestState) && homeStatusDestination(latestState) == destination
            }
        },
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(Modifier.height(120.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().offset(27.dp, 31.dp), contentAlignment = Alignment.BottomEnd) {
                Icon(
                    painter = painterResource(
                        if (connected) R.drawable.ic_round_check_circle_outline else R.drawable.ic_round_error_outline,
                    ),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(110.dp),
                )
            }
            Box(Modifier.fillMaxSize().padding(16.dp, 10.dp), contentAlignment = Alignment.BottomStart) {
                Text(
                    text = Tr.tr("应用版本 ${state.appVersion}", "Versione app ${state.appVersion}", "App version ${state.appVersion}", "Versión de la app ${state.appVersion}", "Version de l'app ${state.appVersion}", "Versão da app ${state.appVersion}", "Versão do app ${state.appVersion}", "Версия на приложението ${state.appVersion}", "Uygulama sürümü ${state.appVersion}", "App-Version ${state.appVersion}"),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.fillMaxSize().padding(16.dp, 14.dp), contentAlignment = Alignment.TopStart) {
                Column {
                    Text(
                        text = when {
                            connected -> Tr.tr("已连接", "Connesso", "Connected", "Conectado", "Connecté", "Ligado", "Conectado", "Свързано", "Bağlı", "Verbunden")
                            state.connecting -> Tr.tr("连接中", "Connessione…", "Connecting…", "Conectando…", "Connexion…", "A ligar…", "Conectando…", "Свързване…", "Bağlanıyor…", "Verbinden…")
                            else -> Tr.tr("未连接", "Non connesso", "Not connected", "No conectado", "Non connecté", "Não ligado", "Não conectado", "Не е свързано", "Bağlı değil", "Nicht verbunden")
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    homeDeviceName(state)?.let { name ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = name.uiText(),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun homeDeviceName(state: AppUiState): String? {
    // 已连接或正在连接时，只采用连接模型中的真实名称；不从历史推测当前设备。
    if (state.connected || state.connecting) return state.watchName.ifBlank { Tr.tr("未知名称设备", "Dispositivo senza nome", "Device with unknown name", "Dispositivo de nombre desconocido", "Appareil au nom inconnu", "Dispositivo de nome desconhecido", "Dispositivo de nome desconhecido", "Устройство с неизвестно име", "Adı bilinmeyen cihaz", "Gerät mit unbekanntem Namen") }
    val address = state.selectedDeviceAddress?.takeIf { it.isNotBlank() } ?: return null
    val selectedName = state.devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name
        ?: state.savedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name
    return selectedName?.takeIf { it.isNotBlank() } ?: Tr.tr("未知名称设备", "Dispositivo senza nome", "Device with unknown name", "Dispositivo de nombre desconocido", "Appareil au nom inconnu", "Dispositivo de nome desconhecido", "Dispositivo de nome desconhecido", "Устройство с неизвестно име", "Adı bilinmeyen cihaz", "Gerät mit unbekanntem Namen")
}

private fun homeKnownText(value: String): String? = value.takeIf { it.isNotBlank() && it != Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt") }

/** 只校验显示地址，沿用模型的真实 MAC 格式；不从最近或无关设备推测地址。 */
private fun homeCanonicalAddress(address: String?): String? =
    address?.takeIf { it.matches(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")) }?.uppercase(java.util.Locale.ROOT)

private fun homeMacAddress(state: AppUiState): String? =
    (if (state.connected) homeCanonicalAddress(state.deviceAddress) else null)
        ?: homeCanonicalAddress(state.selectedDeviceAddress)

/** 型号只属于实际连接 MAC，否则属于当前选择；首个已存型号不因离线或新连接变化。 */
private fun homeDeviceModel(state: AppUiState): String? {
    val address = homeMacAddress(state) ?: return null
    val cached = state.savedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.model
    val known = runCatching { DeviceInfo.modelText(cached) }.getOrNull()
    if (known != null) return known
    return if (state.connected && homeCanonicalAddress(state.deviceAddress) == address) {
        runCatching { DeviceInfo.modelText(state.deviceModel) }.getOrNull()
    } else null
}

/** 序列号与型号一样属于明确 MAC 的首个缓存；连接状态变化不清空或借用其他设备标识。 */
private fun homeSerialNumber(state: AppUiState): String? {
    val address = homeMacAddress(state) ?: return null
    val cached = state.savedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.serialNumber
    val known = runCatching { DeviceTelemetry.serialText(cached) }.getOrNull()
    if (known != null) return known
    return if (state.connected && homeCanonicalAddress(state.deviceAddress) == address) {
        runCatching { DeviceTelemetry.serialText(state.serialNumber) }.getOrNull()
    } else null
}

private fun homeStatusDestination(state: AppUiState): Int = if (state.connected) 2 else 1

private fun homeStatusFrameCurrent(rendered: AppUiState, current: AppUiState): Boolean =
    rendered.connectionEpoch == current.connectionEpoch &&
        rendered.connectionFormRevision == current.connectionFormRevision &&
        rendered.connected == current.connected && rendered.connecting == current.connecting &&
        homeCanonicalAddress(rendered.selectedDeviceAddress) == homeCanonicalAddress(current.selectedDeviceAddress) &&
        homeMacAddress(rendered) == homeMacAddress(current) &&
        rendered.prompt?.token == current.prompt?.token && rendered.needsPin == current.needsPin

/** 每行依次为标签、显示内容与复制内容；标识符不做截断或文字替换。 */
private fun homeInfoRows(state: AppUiState): List<Triple<String, String, String?>> {
    val address = homeMacAddress(state)
    val model = homeDeviceModel(state)
    val serial = homeSerialNumber(state)
    val firmware = homeKnownText(state.firmware)
    val resolution = if (state.width != null && state.height != null) "${state.width} × ${state.height}" else null
    val battery = state.batteryPercent?.takeIf { it in 0..100 }?.let { "$it%" }
    val freeMemory = state.freeMemoryBytes?.takeIf { it > 0L }?.let(::homeMemoryText)
    return listOf(
        Triple(Tr.tr("型号代码", "Codice modello", "Model code", "Código de modelo", "Code du modèle", "Código do modelo", "Código do modelo", "Код на модела", "Model kodu", "Modellcode"), model ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), model),
        Triple(Tr.tr("蓝牙地址（MAC）", "Indirizzo Bluetooth (MAC)", "Bluetooth address (MAC)", "Dirección Bluetooth (MAC)", "Adresse Bluetooth (MAC)", "Endereço Bluetooth (MAC)", "Endereço Bluetooth (MAC)", "Bluetooth адрес (MAC)", "Bluetooth adresi (MAC)", "Bluetooth-Adresse (MAC)"), address ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), address),
        Triple(Tr.tr("序列号", "Numero di serie", "Serial number", "Número de serie", "Numéro de série", "Número de série", "Número de série", "Сериен номер", "Seri numarası", "Seriennummer"), serial ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), serial),
        Triple(Tr.tr("固件版本", "Versione firmware", "Firmware version", "Versión de firmware", "Version du firmware", "Versão do firmware", "Versão do firmware", "Версия на фърмуера", "Yazılım sürümü", "Firmware-Version"), firmware ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), firmware),
        Triple(Tr.tr("表盘分辨率", "Risoluzione quadrante", "Watch face resolution", "Resolución de la esfera", "Résolution du cadran", "Resolução do mostrador", "Resolução do mostrador", "Резолюция на циферблата", "Kadran çözünürlüğü", "Zifferblattauflösung"), resolution ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), resolution),
        Triple(Tr.tr("电量", "Batteria", "Battery", "Batería", "Batterie", "Bateria", "Bateria", "Батерия", "Pil", "Akku"), battery ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), battery),
        Triple(Tr.tr("剩余内存", "Memoria libera", "Free memory", "Memoria libre", "Mémoire libre", "Memória livre", "Memória livre", "Свободна памет", "Boş bellek", "Freier Speicher"), freeMemory ?: Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), freeMemory),
    ).map { if (state.connected || it.first == Tr.tr("蓝牙地址（MAC）", "Indirizzo Bluetooth (MAC)", "Bluetooth address (MAC)", "Dirección Bluetooth (MAC)", "Adresse Bluetooth (MAC)", "Endereço Bluetooth (MAC)", "Endereço Bluetooth (MAC)", "Bluetooth адрес (MAC)", "Bluetooth adresi (MAC)", "Bluetooth-Adresse (MAC)") || it.first == Tr.tr("型号代码", "Codice modello", "Model code", "Código de modelo", "Code du modèle", "Código do modelo", "Código do modelo", "Код на модела", "Model kodu", "Modellcode") || it.first == Tr.tr("序列号", "Numero di serie", "Serial number", "Número de serie", "Numéro de série", "Número de série", "Número de série", "Сериен номер", "Seri numarası", "Seriennummer")) it else Triple(it.first, Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt"), null) }
}

/** 复制必须对应当前显示的同一地址、选择和连接代次；MAC、已知型号与序列号不要求仍处于连接状态。 */
private fun homeCopyValue(rendered: AppUiState, current: AppUiState, label: String, renderedValue: String?): String? {
    if (renderedValue == null || rendered.connectionEpoch != current.connectionEpoch ||
        rendered.connectionFormRevision != current.connectionFormRevision ||
        homeCanonicalAddress(rendered.selectedDeviceAddress) != homeCanonicalAddress(current.selectedDeviceAddress) ||
        homeMacAddress(rendered) != homeMacAddress(current)
    ) return null
    if (label != Tr.tr("蓝牙地址（MAC）", "Indirizzo Bluetooth (MAC)", "Bluetooth address (MAC)", "Dirección Bluetooth (MAC)", "Adresse Bluetooth (MAC)", "Endereço Bluetooth (MAC)", "Endereço Bluetooth (MAC)", "Bluetooth адрес (MAC)", "Bluetooth adresi (MAC)", "Bluetooth-Adresse (MAC)") && label != Tr.tr("型号代码", "Codice modello", "Model code", "Código de modelo", "Code du modèle", "Código do modelo", "Código do modelo", "Код на модела", "Model kodu", "Modellcode") && label != Tr.tr("序列号", "Numero di serie", "Serial number", "Número de serie", "Numéro de série", "Número de série", "Número de série", "Сериен номер", "Seri numarası", "Seriennummer") && (!rendered.connected || !current.connected)) return null
    val latestValue = homeInfoRows(current).firstOrNull { it.first == label }?.third
    return latestValue?.takeIf { it == renderedValue }
}

/** 输入为腕连归一后的显示等效值，不将其当成设备原始精确字节数。 */
private fun homeMemoryText(bytes: Long): String {
    val megabytes = bytes / (1024L * 1024L)
    return if (megabytes >= 1024L) {
        String.format(java.util.Locale.ROOT, "%.2f GB", megabytes / 1024.0)
    } else {
        "$megabytes MB"
    }
}

@Composable
private fun HomeDeviceInfoCard(state: AppUiState) {
    val context = LocalContext.current
    val latestState by rememberUpdatedState(state)
    val copyOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(copyOwner) { onDispose { copyOwner.set(false) } }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        val rows = homeInfoRows(state)
        val icons = listOf(
            R.drawable.ic_code,
            R.drawable.ic_bluetooth_connect,
            R.drawable.ic_tag,
            R.drawable.ic_developer_board,
            R.drawable.ic_aspect_ratio,
            R.drawable.ic_battery_full,
            R.drawable.ic_storage,
        )
        rows.forEachIndexed { index, (label, value, copyValue) ->
            CopyableHomeInfoRow(
                iconRes = icons[index],
                label = label,
                value = value,
                bottomPadding = if (index == rows.lastIndex) 0.dp else 12.dp,
                onCopy = {
                    val current = latestState
                    // 未知也保留按压高亮，但不能从旧未知帧、旧设备或已销毁页面复制。
                    if (copyOwner.get()) {
                        homeCopyValue(state, current, label, copyValue)?.let {
                            copyHomeInfo(context, label, it)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CopyableHomeInfoRow(
    iconRes: Int,
    label: String,
    value: String,
    bottomPadding: Dp = 12.dp,
    onCopy: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp).size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onCopy,
                ),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                softWrap = true,
            )
        }
    }
}

private fun copyHomeInfo(context: Context, label: String, value: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(context, Tr.tr("复制失败，请重试", "Copia non riuscita, riprova", "Copy failed, try again", "Error al copiar, inténtalo de nuevo", "Échec de la copie, réessayez", "Falha ao copiar, tente de novo", "Falha ao copiar, tente de novo", "Копирането не бе успешно, опитайте пак", "Kopyalanamadı, tekrar deneyin", "Kopieren fehlgeschlagen, versuche es erneut"), Toast.LENGTH_SHORT).show()
            return
        }
        val clip = ClipData.newPlainText(label, value).apply {
            // 标记为敏感内容，避免系统剪贴板预览回显设备标识符。
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, Tr.tr("已复制", "Copiato", "Copied", "Copiado", "Copié", "Copiado", "Copiado", "Копирано", "Kopyalandı", "Kopiert"), Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, Tr.tr("复制失败，请重试", "Copia non riuscita, riprova", "Copy failed, try again", "Error al copiar, inténtalo de nuevo", "Échec de la copie, réessayez", "Falha ao copiar, tente de novo", "Falha ao copiar, tente de novo", "Копирането не бе успешно, опитайте пак", "Kopyalanamadı, tekrar deneyin", "Kopieren fehlgeschlagen, versuche es erneut"), Toast.LENGTH_SHORT).show()
    }
}
