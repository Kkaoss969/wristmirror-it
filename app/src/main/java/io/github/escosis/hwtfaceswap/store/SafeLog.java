package io.github.escosis.hwtfaceswap.store;


import io.github.escosis.hwtfaceswap.Tr;
import android.content.Context;
import io.github.escosis.hwtfaceswap.protocol.TlvView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 有界保存脱敏通信元数据；噪音聚合只影响日志，不改变实际报文处理。 */
public final class SafeLog {
    static final int MAX_FILE = 256 * 1024, MAX_CRITICAL_ROWS = 400, MAX_TRAFFIC_ROWS = 100;
    private static final int NOISE_BATCH_PACKETS = 100;
    private static final long NOISE_WINDOW_MS = 1000;
    private final File file;
    private final LongSupplier clock;
    private final ArrayDeque<Row> critical = new ArrayDeque<>(), traffic = new ArrayDeque<>();
    private final SimpleDateFormat date = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private Consumer<List<String>> listener;
    private long sequence, lastNoisePublish, criticalEvictedRows, trafficEvictedRows;
    private boolean noisePublished, diskDirty, diskHasActive;
    private Noise activeNoise;
    private String deviceContext = Tr.tr("设备名称=未知 固件版本=未知", "nome dispositivo=sconosciuto versione firmware=sconosciuta", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown", "device name=unknown firmware version=unknown");

    /** Retained separately from bounded rows, including after disconnect or log clearing. */
    public synchronized void deviceContext(String name, String firmware) {
        String next = Tr.tr("设备名称=", "nome dispositivo=", "device name=", "device name=", "device name=", "device name=", "device name=", "device name=", "device name=", "device name=", "device name=") + quotedMetadata(sanitize(name == null ? Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt", "Неизвестно") : name)) +
                Tr.tr(" 固件版本=", " versione firmware=", " firmware version=", " firmware version=", " firmware version=", " firmware version=", " firmware version=", " firmware version=", " firmware version=", " firmware version=", " firmware version=") + quotedMetadata(sanitize(firmware == null ? Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt", "Неизвестно") : firmware));
        if (next.equals(deviceContext)) return;
        deviceContext = next;
        record("DEVICE_CONTEXT", deviceContext);
    }

    private static final class Row {
        final long seq;
        final String line;
        final boolean critical;
        Row(long seq, String line, boolean critical) { this.seq = seq; this.line = line; this.critical = critical; }
    }
    private static final class Counter {
        long count, bytes, first, last, encrypted, plain, metadataChanges;
        String firstMetadata = "", lastMetadata = "";
        void add(int size, long at, boolean crypted, String metadata) {
            if (count == 0) { first = at; firstMetadata = metadata; }
            else if (!lastMetadata.equals(metadata)) metadataChanges++;
            lastMetadata = metadata;
            last = at; count++; bytes += size;
            if (crypted) encrypted++; else plain++;
        }
    }
    private static final class Noise {
        final long seq, started;
        long lastSeq, count;
        final Counter rx = new Counter(), tx = new Counter();
        Noise(long seq, long started) { this.seq = seq; this.started = started; }
    }

    public SafeLog(Context c) { this(new File(c.getFilesDir(), "diagnostics")); }
    /** Package-local pure-Java seam: directory, not a device/account or external path. */
    SafeLog(File directory) { this(directory, System::currentTimeMillis); }
    SafeLog(File directory, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        if (!directory.isDirectory()) directory.mkdirs();
        file = new File(directory, "connection.log");
    }
    public synchronized void setListener(Consumer<List<String>> cb) { listener = cb; publish(); }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace('\r', ' ').replace('\n', ' ');
        s = s.replaceAll("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", "<device>");
        s = s.replaceAll("(?i)(pin|secret|auth[_-]?key|huawei[_-]?id|authToken|encAuthToken)\\s*[:=]\\s*[^ ,;]+", "$1=<redacted>");
        s = s.replaceAll("(?<![0-9a-zA-Z])[0-9]{12,20}(?![0-9a-zA-Z])", "<long-number>");
        if (s.length() <= 1200) return s;
        int end = 1200;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + " [truncated]";
    }
    public synchronized void record(String event, String metadata) {
        sealNoise(); // Never aggregate across a control result, fault or another traffic row.
        long seq = ++sequence;
        boolean important = !"RX".equals(event) && !"TX".equals(event);
        Row row = new Row(seq, stamp(clock.getAsLong()) + " seq=" + seq + " " + sanitize(event) + " " + sanitize(metadata), important);
        add(row); persist(row); publish();
    }
    public synchronized void packet(boolean incoming, int service, int command, int bytes,
                                    List<TlvView.Part> fields, boolean encrypted) {
        packet(incoming, service, command, bytes, fields, encrypted, "");
    }
    /** Caller supplies bounded diagnostic scalars only, never packet/authentication bodies. */
    public synchronized void packet(boolean incoming, int service, int command, int bytes,
                                    List<TlvView.Part> fields, boolean encrypted, String safeMetadata) {
        String metadata = sanitize(safeMetadata);
        if ((incoming && service == 0x34 && command == 1) || (!incoming && service == 0x28 && command == 6)) {
            long at = clock.getAsLong();
            // A backwards wall clock starts a new segment; seq, not time, establishes ordering.
            if (activeNoise != null && (at < activeNoise.started || at - activeNoise.started >= NOISE_WINDOW_MS)) sealNoise();
            if (activeNoise == null) {
                while (traffic.size() >= MAX_TRAFFIC_ROWS) { traffic.removeFirst(); trafficEvictedRows++; }
                activeNoise = new Noise(sequence + 1, at);
            }
            activeNoise.lastSeq = ++sequence;
            activeNoise.count++;
            (incoming ? activeNoise.rx : activeNoise.tx).add(Math.max(0, bytes), at, encrypted, metadata);
            diskDirty = true;
            if (activeNoise.count >= NOISE_BATCH_PACKETS) { sealNoise(); publishNoise(at); }
            else if (!noisePublished || at < lastNoisePublish || at - lastNoisePublish >= NOISE_WINDOW_MS) publishNoise(at);
            return;
        }
        // Authentication/body exclusion is unchanged: only service/cmd, length and tag lengths.
        record(incoming ? "RX" : "TX", String.format(Locale.ROOT,
                "service=%02X command=%02X bytes=%d encrypted=%s tags=%s", service, command, bytes,
                encrypted, fields == null ? "raw" : TlvView.tags(fields)) + (metadata.isEmpty() ? "" : " " + metadata));
    }
    private String stamp(long at) { return date.format(new Date(at)); }
    private String counter(String name, Counter c) {
        return " " + name + "_count=" + c.count + " " + name + "_total_bytes=" + c.bytes +
                " " + name + "_first=" + (c.count == 0 ? "absent" : stamp(c.first).replace(' ', 'T')) +
                " " + name + "_last=" + (c.count == 0 ? "absent" : stamp(c.last).replace(' ', 'T')) +
                " " + name + "_encrypted=" + c.encrypted + " " + name + "_plain=" + c.plain +
                " " + name + "_metadata_changes=" + c.metadataChanges +
                " " + name + "_first_metadata=" + quotedMetadata(c.firstMetadata) +
                " " + name + "_last_metadata=" + quotedMetadata(c.lastMetadata);
    }
    private static String quotedMetadata(String metadata) {
        return metadata.isEmpty() ? "absent" : "\"" + metadata.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private Row noiseRow(Noise n) {
        return new Row(n.seq, stamp(n.started) + " seq=" + n.seq + " TRAFFIC_AGGREGATE last_seq=" + n.lastSeq +
                " count=" + n.count + " metadata_only=true rx_service=34 rx_command=01 tx_service=28 tx_command=06" +
                counter("rx34_01", n.rx) + counter("tx28_06", n.tx), false);
    }
    private void sealNoise() {
        if (activeNoise == null) return;
        Row row = noiseRow(activeNoise);
        activeNoise = null;
        add(row); persist(row);
    }
    private void add(Row row) {
        ArrayDeque<Row> target = row.critical ? critical : traffic;
        target.addLast(row);
        int limit = row.critical ? MAX_CRITICAL_ROWS : MAX_TRAFFIC_ROWS;
        while (target.size() > limit) {
            target.removeFirst();
            if (row.critical) criticalEvictedRows++; else trafficEvictedRows++;
        }
    }
    private List<Row> ordered() {
        List<Row> out = new ArrayList<>(critical.size() + traffic.size() + 1);
        out.addAll(critical); out.addAll(traffic);
        if (activeNoise != null) out.add(noiseRow(activeNoise));
        out.sort(Comparator.comparingLong(r -> r.seq));
        return out;
    }
    private List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Row row : ordered()) out.add(row.line);
        return Collections.unmodifiableList(out);
    }
    private static byte[] encoded(Row row) { return (row.line + "\n").getBytes(StandardCharsets.UTF_8); }
    private void persist(Row row) {
        // A snapshot may have persisted a still-growing segment. Rewrite instead of appending
        // that same segment a second time. Normal rows and completed noise batches use append.
        try {
            byte[] bytes = encoded(row);
            if (diskHasActive || file.length() + bytes.length > MAX_FILE) rewriteDisk();
            else try (OutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
            diskDirty = false;
        } catch (IOException ignored) { diskDirty = true; }
    }
    private void rewriteDisk() throws IOException {
        List<Row> kept = ordered();
        long size = 0;
        for (Row row : kept) size += encoded(row).length;
        // Prefer dropping old traffic, then old critical rows. Never slice UTF-8 or a line.
        while (size > MAX_FILE && !kept.isEmpty()) {
            int remove = 0;
            for (int i = 0; i < kept.size(); i++) if (!kept.get(i).critical) { remove = i; break; }
            size -= encoded(kept.remove(remove)).length;
        }
        try (OutputStream out = new FileOutputStream(file, false)) {
            for (Row row : kept) out.write(encoded(row));
        }
        diskHasActive = activeNoise != null;
    }
    private void publishNoise(long at) {
        // Count boundaries persist batches, but do not publish 100 callbacks in one instant.
        if (!noisePublished || at < lastNoisePublish || at - lastNoisePublish >= NOISE_WINDOW_MS) {
            noisePublished = true; lastNoisePublish = at; publish();
        }
    }
    private void publish() { if (listener != null) listener.accept(lines()); }
    public synchronized String snapshot() {
        if (diskDirty) try { rewriteDisk(); diskDirty = false; } catch (IOException ignored) {}
        StringBuilder b = new StringBuilder(Tr.tr("腕镜诊断日志\n日志包含通信元数据，认证正文、账户 ID、配对码与会话密钥已排除。\n本地查询序号用于关联操作，不代表手表报文中的随机数。\n噪音仅按元数据聚合；seq为本地报文/事件顺序，首尾时间不是业务语义。\n有界保留，不是完整点击/通信历史：关键400行、流量100行（含活跃聚合段）；磁盘至多256KiB，可能比本导出保留更少。\n", "Log diagnostico di WristMirror\nIl log contiene metadati di comunicazione; payload di autenticazione, ID account, codici di abbinamento e chiavi di sessione sono esclusi.\nI numeri di sequenza locali servono a correlare le operazioni e non corrispondono ai numeri casuali dei pacchetti del watch.\nIl rumore è aggregato solo per metadati; seq è l'ordine locale di pacchetti/eventi, gli orari di inizio e fine non hanno significato operativo.\nConservazione limitata, non è una cronologia completa di tocchi/comunicazioni: 400 righe chiave, 100 righe di traffico (incluse le sezioni aggregate attive); su disco al massimo 256 KiB, quindi potrebbe conservare meno di questa esportazione.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n", "WristMirror diagnostic log\nThe log contains communication metadata; authentication payloads, account IDs, pairing codes and session keys are excluded.\nLocal sequence numbers are used to correlate operations and don't match the random numbers in the watch packets.\nNoise is aggregated by metadata only; seq is the local packet/event order, first and last times have no business meaning.\nBounded retention, not a complete tap/communication history: 400 key lines, 100 traffic lines (including active aggregate segments); at most 256 KiB on disk, which may keep less than this export.\n"));
        b.append(Tr.tr("最近一次连接信息（不表示当前仍已连接）：", "Informazioni sull'ultima connessione (non indica che sia ancora connesso):", "Last connection information (doesn't mean it's still connected):", "Información de la última conexión (no indica que siga conectado):", "Informations sur la dernière connexion (n'indique pas qu'elle est toujours active) :", "Informações da última ligação (não indica que ainda está ligado):", "Informações da última conexão (não indica que ainda está conectado):", "Информация за последната връзка (не означава, че още е свързано):", "Son bağlantı bilgisi (hâlâ bağlı olduğu anlamına gelmez):", "Informationen zur letzten Verbindung (heißt nicht, dass sie noch besteht):", "Сведения о последнем подключении (не означает, что оно всё ещё активно):")).append(deviceContext).append('\n');
        b.append("retention critical_evicted_rows=").append(criticalEvictedRows)
                .append(" traffic_evicted_rows=").append(trafficEvictedRows)
                .append(" eviction_unit=rows active_aggregate=").append(activeNoise != null)
                .append(" active_aggregate_packets=").append(activeNoise == null ? 0 : activeNoise.count)
                .append("\n\n");
        for (String line : lines()) b.append(line).append('\n');
        return b.toString();
    }
    public synchronized void clear() {
        critical.clear(); traffic.clear(); activeNoise = null;
        criticalEvictedRows = trafficEvictedRows = 0;
        noisePublished = false; diskHasActive = false;
        try (OutputStream out = new FileOutputStream(file, false)) { out.write(new byte[0]); diskDirty = false; }
        catch (IOException ignored) { diskDirty = true; }
        publish();
    }
}
