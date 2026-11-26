package io.github.escosis.hwtfaceswap.store


import io.github.escosis.hwtfaceswap.Tr
import android.content.Context
import android.content.SharedPreferences
import io.github.escosis.hwtfaceswap.NearbyWatch
import io.github.escosis.hwtfaceswap.SavedWatch
import io.github.escosis.hwtfaceswap.protocol.Core
import io.github.escosis.hwtfaceswap.protocol.DeviceInfo
import io.github.escosis.hwtfaceswap.protocol.DeviceTelemetry
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal
import java.util.Base64
import java.util.Locale
import java.util.WeakHashMap

/**
 * 本应用成功连接过的设备元数据，以及用户选择启用的启动自动连接偏好。
 * 此偏好存储不保存账号 ID、PIN、本机身份或认证密钥。
 * 条目按实际 READY 观察的先后顺序排列，最近一次在前，即使系统时钟回拨也不改变排序。
 * 候选设备与握手失败的连接不得调用 remember。
 */
class SavedWatchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("saved-watches", Context.MODE_PRIVATE)
    private val removals = DeviceRemovalJournal(context)

    fun devices(): List<SavedWatch> = synchronized(DeviceRemovalJournal.LOCK) {
        val encoded = prefs.getString(DEVICES, "").orEmpty()
        if (encoded.length > MAX_STORAGE_CHARS) return@synchronized emptyList()
        val lines = encoded.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != FORMAT) return@synchronized emptyList()
        val seen = HashSet<String>()
        buildList {
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size != 4) continue
                val address = canonicalAddress(parts[0]) ?: continue
                val transport = when (parts[1]) { "BLE" -> Tr.tr("低功耗蓝牙", "Bluetooth Low Energy", "Bluetooth Low Energy", "Bluetooth de bajo consumo", "Bluetooth basse consommation", "Bluetooth de baixo consumo", "Bluetooth de baixa energia", "Bluetooth с ниска консумация", "Bluetooth Düşük Enerji", "Bluetooth Low Energy", "Bluetooth Low Energy"); "RFCOMM" -> Tr.tr("经典蓝牙", "Bluetooth classico", "Classic Bluetooth", "Bluetooth clásico", "Bluetooth classique", "Bluetooth clássico", "Bluetooth clássico", "Класически Bluetooth", "Klasik Bluetooth", "Klassisches Bluetooth", "Классический Bluetooth"); else -> continue }
                val connectedAt = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: continue
                val name = runCatching { String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= MAX_NAME_CHARS } ?: continue
                if (seen.add(address)) add(SavedWatch(address, name, transport, connectedAt, cachedModel(address), cachedSerial(address)))
            }
        }
    }

    fun autoConnect(): Boolean = prefs.getString(AUTO_CONNECT, "false") == "true"

    fun setAutoConnect(enabled: Boolean) {
        if (!prefs.edit().putString(AUTO_CONNECT, enabled.toString()).commit()) throw Core.Fault("LOCAL_AUTO_CONNECT_WRITE_FAILED")
    }

    /** 仅在独立检查当前连接满足 READY 与连接批次条件后调用。 */
    @JvmOverloads
    fun remember(device: NearbyWatch, connectedAt: Long = System.currentTimeMillis()): List<SavedWatch> = synchronized(DeviceRemovalJournal.LOCK) {
        val address = canonicalAddress(device.address) ?: throw Core.Fault("DEVICE_ADDRESS_INVALID")
        // 与所有实例及凭据存储共用锁；旧 READY 回调不能越过待处理标记重写历史。
        removals.requireUsable(address)
        val transport = when (device.transport) { Tr.tr("低功耗蓝牙", "Bluetooth Low Energy", "Bluetooth Low Energy", "Bluetooth de bajo consumo", "Bluetooth basse consommation", "Bluetooth de baixo consumo", "Bluetooth de baixa energia", "Bluetooth с ниска консумация", "Bluetooth Düşük Enerji", "Bluetooth Low Energy", "Bluetooth Low Energy") -> device.transport; Tr.tr("经典蓝牙", "Bluetooth classico", "Classic Bluetooth", "Bluetooth clásico", "Bluetooth classique", "Bluetooth clássico", "Bluetooth clássico", "Класически Bluetooth", "Klasik Bluetooth", "Klassisches Bluetooth", "Классический Bluetooth") -> device.transport; else -> throw Core.Fault("DEVICE_TRANSPORT_INVALID") }
        val name = device.name.take(MAX_NAME_CHARS).ifBlank { Tr.tr("华为手表", "Watch Huawei", "Huawei watch", "Reloj Huawei", "Montre Huawei", "Relógio Huawei", "Relógio Huawei", "Часовник Huawei", "Huawei saat", "Huawei-Uhr", "Часы Huawei") }
        val previous = devices()
        val saved = SavedWatch(address, name, transport, connectedAt.coerceAtLeast(1L), previous.firstOrNull { it.address == address }?.model,
            previous.firstOrNull { it.address == address }?.serialNumber)
        val next = listOf(saved) + previous.filterNot { it.address == address }
        persist(next, "LOCAL_DEVICE_HISTORY_WRITE_FAILED")
        next
    }

    /** 仅同一已验证 READY 的实际型号可调用；晚到 01/07 不重排历史，首个有效值永不被覆盖。 */
    fun rememberModel(address: String, model: String?, expectedGeneration: Long): List<SavedWatch> = synchronized(DeviceRemovalJournal.LOCK) {
        val mac = canonicalAddress(address) ?: throw Core.Fault("DEVICE_ADDRESS_INVALID")
        if (!removals.usable(mac, expectedGeneration)) throw Core.Fault("DEVICE_HISTORY_CHANGED")
        val history = devices()
        val saved = history.firstOrNull { it.address == mac } ?: return@synchronized history
        val first = saved.model ?: runCatching { DeviceInfo.modelText(model) }.getOrNull() ?: return@synchronized history
        val pending = pendingModelWrites[prefs]?.containsKey(mac) == true
        if (saved.model != null && !pending) return@synchronized history
        // commit(false) 也可能改了偏好的内存视图。跨 store 实例保留首个值并重试同值，不能误报持久成功或换型号。
        pendingModelWrites.getOrPut(prefs) { HashMap() }[mac] = first
        try {
            if (!prefs.edit().putString(MODEL_PREFIX + mac, first).commit()) throw Core.Fault("LOCAL_DEVICE_HISTORY_WRITE_FAILED")
        } catch (_: RuntimeException) { throw Core.Fault("LOCAL_DEVICE_HISTORY_WRITE_FAILED") }
        pendingModelWrites[prefs]?.remove(mac)
        history.map { if (it.address == mac) it.copy(model = first) else it }
    }

    private fun cachedModel(address: String): String? = pendingModelWrites[prefs]?.get(address)
        ?: runCatching { DeviceInfo.modelText(prefs.getString(MODEL_PREFIX + address, null)) }.getOrNull()

    /** 仅同一已验证 READY 的实际序列号可调用；首个值稳定保留，晚到信息不重排历史。 */
    fun rememberSerial(address: String, serialNumber: String?, expectedGeneration: Long): List<SavedWatch> = synchronized(DeviceRemovalJournal.LOCK) {
        val mac = canonicalAddress(address) ?: throw Core.Fault("DEVICE_ADDRESS_INVALID")
        if (!removals.usable(mac, expectedGeneration)) throw Core.Fault("DEVICE_HISTORY_CHANGED")
        val history = devices()
        val saved = history.firstOrNull { it.address == mac } ?: return@synchronized history
        val first = saved.serialNumber ?: runCatching { DeviceTelemetry.serialText(serialNumber) }.getOrNull() ?: return@synchronized history
        val pending = pendingSerialWrites[prefs]?.containsKey(mac) == true
        if (saved.serialNumber != null && !pending) return@synchronized history
        // 与型号一样跨实例保留失败写入的首个值；commit(false) 的内存回显不能阻止同值持久重试。
        pendingSerialWrites.getOrPut(prefs) { HashMap() }[mac] = first
        try {
            if (!prefs.edit().putString(SERIAL_PREFIX + mac, first).commit()) throw Core.Fault("LOCAL_DEVICE_HISTORY_WRITE_FAILED")
        } catch (_: RuntimeException) { throw Core.Fault("LOCAL_DEVICE_HISTORY_WRITE_FAILED") }
        pendingSerialWrites[prefs]?.remove(mac)
        history.map { if (it.address == mac) it.copy(serialNumber = first) else it }
    }

    private fun cachedSerial(address: String): String? = pendingSerialWrites[prefs]?.get(address)
        ?: runCatching { DeviceTelemetry.serialText(prefs.getString(SERIAL_PREFIX + address, null)) }.getOrNull()

    /** 只删除指定 MAC 的历史与型号/序列号缓存；保留顺序、其他设备及自动连接偏好。失败不代表持久删除。 */
    fun removeDevice(address: String): List<SavedWatch> = synchronized(DeviceRemovalJournal.LOCK) {
        val mac = canonicalAddress(address) ?: throw Core.Fault("DEVICE_ADDRESS_INVALID")
        val next = devices().filterNot { it.address == mac }
        persist(next, "LOCAL_DEVICE_HISTORY_CLEAR_FAILED", mac)
        pendingModelWrites[prefs]?.remove(mac)
        pendingSerialWrites[prefs]?.remove(mac)
        next
    }

    private fun persist(devices: List<SavedWatch>, failure: String, removedModelAddress: String? = null) {
        val encoded = buildString {
            append(FORMAT)
            for (watch in devices) {
                append('\n').append(watch.address).append('\t')
                append(if (watch.transport == Tr.tr("低功耗蓝牙", "Bluetooth Low Energy", "Bluetooth Low Energy", "Bluetooth de bajo consumo", "Bluetooth basse consommation", "Bluetooth de baixo consumo", "Bluetooth de baixa energia", "Bluetooth с ниска консумация", "Bluetooth Düşük Enerji", "Bluetooth Low Energy", "Bluetooth Low Energy")) "BLE" else "RFCOMM").append('\t')
                append(watch.lastConnectedAt).append('\t')
                append(Base64.getEncoder().encodeToString(watch.name.toByteArray(Charsets.UTF_8)))
            }
        }
        try {
            if (encoded.length > MAX_STORAGE_CHARS) throw Core.Fault(failure)
            val editor = prefs.edit().putString(DEVICES, encoded)
            if (removedModelAddress != null) {
                editor.remove(MODEL_PREFIX + removedModelAddress)
                editor.remove(SERIAL_PREFIX + removedModelAddress)
            }
            if (!editor.commit()) throw Core.Fault(failure)
        } catch (_: RuntimeException) { throw Core.Fault(failure) }
    }

    companion object {
        private const val DEVICES = "ready-devices-v1"
        private const val AUTO_CONNECT = "auto-connect"
        private const val FORMAT = "1"
        // 型号单独按 MAC 存储，原格式 1 的四字段历史行保持逐字节兼容。
        private const val MODEL_PREFIX = "first-model-v1_"
        private val pendingModelWrites = WeakHashMap<SharedPreferences, MutableMap<String, String>>()
        private const val SERIAL_PREFIX = "first-serial-v1_"
        private val pendingSerialWrites = WeakHashMap<SharedPreferences, MutableMap<String, String>>()
        private const val MAX_NAME_CHARS = 256
        private const val MAX_STORAGE_CHARS = 256 * 1024

        fun canonicalAddress(address: String): String? = address.takeIf { it.matches(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")) }?.uppercase(Locale.ROOT)
    }
}
