package io.github.escosis.hwtfaceswap.protocol;


import io.github.escosis.hwtfaceswap.Tr;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 已证腕连产品、电池和音乐存储显示字段；不猜 RAM、全局容量或型号能力。 */
public final class DeviceTelemetry {
    public static final int CONFIG_SERVICE = 0x01;
    public static final int PRODUCT_COMMAND = 0x07;
    public static final int SERIAL_TAG = 0x09;
    public static final int BATTERY_COMMAND = 0x08;
    public static final int BATTERY_TAG = 0x01;
    public static final int MUSIC_SERVICE = 0x25;
    public static final int MUSIC_STORAGE_COMMAND = 0x04;
    public static final int MUSIC_FREE_TAG = 0x02;
    public static final long MIB = 1024L * 1024L;
    /** 本地显示防御上限，不声称原协议规定序列号固定长度或字符形状。 */
    public static final int MAX_SERIAL_BYTES = 128;

    private DeviceTelemetry() {}

    public static boolean matches(int service, int command) {
        return service == CONFIG_SERVICE && (command == PRODUCT_COMMAND || command == BATTERY_COMMAND)
                || service == MUSIC_SERVICE && command == MUSIC_STORAGE_COMMAND;
    }

    /** 原 w0.p0 的 25/04 只读请求；不复现未在原能力行声明的 25/0D。 */
    public static byte[] musicStorageRequest() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int tag : new int[] { 1, 2, 3 }) out.write(Core.tlv(tag, new byte[0]));
        return out.toByteArray();
    }

    public static Stats parse(int service, int command, byte[] plain) throws IOException {
        if (!matches(service, command)) throw new Core.Fault("DEVICE_STATS_HEADER_MISMATCH");
        return parse(service, command, TlvView.decode(plain));
    }

    /** 只消费正确路由的根层字段；缺失不等于零，其他回包字段不作替代。 */
    public static Stats parse(int service, int command, List<TlvView.Part> fields) throws IOException {
        if (service == CONFIG_SERVICE && command == PRODUCT_COMMAND) {
            return new Stats(serialNumber(fields), null, null);
        }
        if (service == CONFIG_SERVICE && command == BATTERY_COMMAND) {
            return new Stats(null, batteryPercent(fields), null);
        }
        if (service == MUSIC_SERVICE && command == MUSIC_STORAGE_COMMAND) {
            return new Stats(null, null, musicFreeMemoryBytes(fields));
        }
        throw new Core.Fault("DEVICE_STATS_HEADER_MISMATCH");
    }

    /** w0.L → n.h(09)：UTF-8 序列号；只去一个尾 NUL 和显示边缘空格，不从名称/HUID兜底。 */
    public static String serialNumber(List<TlvView.Part> fields) throws IOException {
        checkHeader(fields);
        TlvView.Part field = Wire.unique(fields, SERIAL_TAG);
        if (field == null || field.value.length == 0) return null;
        if (field.value.length > MAX_SERIAL_BYTES + 1) throw new Core.Fault("PRODUCT_SERIAL_SIZE");
        int end = field.value.length;
        if (field.value[end - 1] == 0) end--;
        if (end > MAX_SERIAL_BYTES) throw new Core.Fault("PRODUCT_SERIAL_SIZE");
        if (end == 0) return null;
        String text = Core.utf8(Arrays.copyOf(field.value, end));
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) throw new Core.Fault("PRODUCT_SERIAL_CONTROL");
        }
        String serial = text.trim();
        return serial.isEmpty() ? null : serial;
    }

    /** 已解码序列号及本地缓存的同一有界校验；不补造字段、不替换坏字符，未知占位不作首个值。 */
    public static String serialText(String text) throws IOException {
        if (text == null) return null;
        if (text.length() > MAX_SERIAL_BYTES) throw new Core.Fault("PRODUCT_SERIAL_SIZE");
        for (int i = 0; i < text.length();) {
            int ch = text.codePointAt(i);
            i += Character.charCount(ch);
            if (ch >= 0xd800 && ch <= 0xdfff) throw new Core.Fault("INVALID_UTF8");
            if (Character.isISOControl(ch)) throw new Core.Fault("PRODUCT_SERIAL_CONTROL");
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SERIAL_BYTES) {
            throw new Core.Fault("PRODUCT_SERIAL_SIZE");
        }
        String serial = text.trim();
        return serial.isEmpty() || serial.equalsIgnoreCase("unknown") || serial.equals(Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt", "Неизвестно")) ||
                serial.equals(Tr.tr("未知序列号", "Numero di serie sconosciuto", "Unknown serial number", "Número de serie desconocido", "Numéro de série inconnu", "Número de série desconhecido", "Número de série desconhecido", "Неизвестен сериен номер", "Bilinmeyen seri numarası", "Unbekannte Seriennummer", "Неизвестный серийный номер")) || serial.equalsIgnoreCase("null") || serial.equalsIgnoreCase("n/a")
                ? null : serial;
    }

    /** w0.y → n.e(01)：非空根字段首字节 &255，范围 0..100；后缀不作数值/其他电量猜测。 */
    public static Integer batteryPercent(List<TlvView.Part> fields) throws IOException {
        checkHeader(fields);
        TlvView.Part field = Wire.unique(fields, BATTERY_TAG);
        if (field == null || field.value.length == 0) return null;
        int percent = field.value[0] & 255;
        if (percent > 100) throw new Core.Fault("BATTERY_PERCENT_RANGE");
        return Integer.valueOf(percent);
    }

    /**
     * 25/04 根 02 是腕连 w0.H 使用的音乐存储可用量，不是 RAM，也未证明为全局空闲存储。
     * 此返回值仅为“原 UI 归一 MB × 2^20”的展示等效值，绝不是原始报文的精确 bytes。
     * 已复核 H → y0.h → r4.T：raw<=0 未知；0<raw<=2^20 直接当 MB；
     * raw>2^20 时 floor(raw/2^20) 当 MB；T 以 1024 MB/GB 显示。
     * 不自行根据型号/数值另猜单位，不修饰原有阈值的不连续性。
     * n.d 接受 1..3 字节大端正数，4 字节为 signed int；本地拒绝超出 4 字节，
     * 并保守拒绝 uint32 高位为 1 的范围，避免把原负值/溢出重新包装成大容量。
     */
    public static Long musicFreeMemoryBytes(List<TlvView.Part> fields) throws IOException {
        checkHeader(fields);
        TlvView.Part field = Wire.unique(fields, MUSIC_FREE_TAG);
        if (field == null || field.value.length == 0) return null;
        long raw = Core.number(field.value);
        if (raw > Integer.MAX_VALUE) throw new Core.Fault("MUSIC_STORAGE_RANGE");
        if (raw == 0) return null;
        long displayMb = raw <= MIB ? raw : Math.max(1L, raw / MIB);
        return Long.valueOf(displayMb * MIB);
    }

    private static void checkHeader(List<TlvView.Part> fields) throws IOException {
        Long error = Wire.number(fields, 0x7f);
        if (error != null && error != 0 && error != 100000) throw new Core.Fault("DEVICE_STATS_REJECTED");
    }

    /** 每次只带本次实际观测到的字段；null 不是清空值，也不表示成功。 */
    public static final class Stats {
        public final String serialNumber;
        public final Integer batteryPercent;
        public final Long freeMemoryBytes;

        private Stats(String serialNumber, Integer batteryPercent, Long freeMemoryBytes) {
            this.serialNumber = serialNumber;
            this.batteryPercent = batteryPercent;
            this.freeMemoryBytes = freeMemoryBytes;
        }

        public boolean hasObservedFields() {
            return serialNumber != null || batteryPercent != null || freeMemoryBytes != null;
        }
    }

    /**
     * 纯内存、单连接批次的只读请求归属。调用方必须先校验封装，加密时严格解密，再 complete/observe；
     * 本类不持有或推进连接的 READY、sessionVerified、认证、列表或写操作状态。
     */
    public static final class ProbeOwner {
        private final long epoch;
        private boolean initializationClaimed, readyClaimed, productPending, batteryPending, storagePending;
        private Stats observed = new Stats(null, null, null), delivered = new Stats(null, null, null);

        public ProbeOwner(long epoch) { this.epoch = epoch; }
        public long epoch() { return epoch; }

        /** 在原 init 的 07/05/08/02 发送前标记；重复入口不能另发产品/电池查询。 */
        public boolean markInitializationRequests(long currentEpoch) {
            if (currentEpoch != epoch || initializationClaimed) return false;
            initializationClaimed = true;
            productPending = batteryPending = true;
            return true;
        }

        /** 已认领的 01/08 即使完成也保留封装检查路由；不恢复 pending 或授予认证/发布。 */
        public boolean batteryQueryClaimed(long currentEpoch) {
            return currentEpoch == epoch && initializationClaimed;
        }

        /** 在首次已验证 READY 时一次决策并先标记；无支持也不随 refresh/再次 READY 重查。 */
        public boolean markReadyStorageRequest(long currentEpoch, boolean sessionVerified, boolean ready,
                boolean supported) {
            if (currentEpoch != epoch || !sessionVerified || !ready || readyClaimed) return false;
            readyClaimed = true;
            storagePending = supported;
            return supported;
        }

        public boolean pending(long currentEpoch, int service, int command) {
            if (currentEpoch != epoch) return false;
            if (service == CONFIG_SERVICE && command == PRODUCT_COMMAND) return productPending;
            if (service == CONFIG_SERVICE && command == BATTERY_COMMAND) return batteryPending;
            return service == MUSIC_SERVICE && command == MUSIC_STORAGE_COMMAND && storagePending;
        }

        /** 首个已校验封装的归属回包（01/08 可为原版明文）消耗一次；不为未知/坏字段重试。 */
        public boolean complete(long currentEpoch, int service, int command) {
            if (!pending(currentEpoch, service, command)) return false;
            if (service == CONFIG_SERVICE && command == PRODUCT_COMMAND) productPending = false;
            else if (service == CONFIG_SERVICE && command == BATTERY_COMMAND) batteryPending = false;
            else storagePending = false;
            return true;
        }

        public void observe(long currentEpoch, Stats update) {
            if (currentEpoch != epoch || update == null) return;
            observed = new Stats(update.serialNumber == null ? observed.serialNumber : update.serialNumber,
                    update.batteryPercent == null ? observed.batteryPercent : update.batteryPercent,
                    update.freeMemoryBytes == null ? observed.freeMemoryBytes : update.freeMemoryBytes);
        }

        /** 发布也先标记，回调重入不可重复；初始化缓存到 READY，晚到回包只带新实际字段。 */
        public Stats publication(long currentEpoch, boolean sessionVerified, boolean ready) {
            if (currentEpoch != epoch || !sessionVerified || !ready) return null;
            Stats delta = new Stats(Objects.equals(observed.serialNumber, delivered.serialNumber) ? null : observed.serialNumber,
                    Objects.equals(observed.batteryPercent, delivered.batteryPercent) ? null : observed.batteryPercent,
                    Objects.equals(observed.freeMemoryBytes, delivered.freeMemoryBytes) ? null : observed.freeMemoryBytes);
            if (!delta.hasObservedFields()) return null;
            delivered = observed;
            return delta;
        }
    }
}
