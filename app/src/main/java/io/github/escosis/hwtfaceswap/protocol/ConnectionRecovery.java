package io.github.escosis.hwtfaceswap.protocol;

/**
 * 传输层失败后的有界恢复节奏。数值不是自创的，而是照抄腕连
 * {@code com.watch.assistant.bridge.w0.l0(String,u0)} 的恢复调度：
 *
 * <pre>
 *   attempts+=1;
 *   if(!reconnected && attempts==1) postDelayed(恢复, 800ms);   // "Watch service busy. Retrying..."
 *   else if(!reconnected && attempts==2) postDelayed(恢复, 1200ms); // "Watch service busy. Reconnecting..."
 *   else 终止："Watch service rejected the operation after reconnect (100004)."
 * </pre>
 *
 * 也就是说原厂只给两次机会：第一次 800 ms、第二次 1200 ms，第三次直接放弃并明确报错，
 * 绝不会无限重连。本类保持同样的次数与间隔。
 *
 * 与腕连的唯一映射差异：腕连第一次是"同一会话内重试"，因为他们面对的是 100004（服务忙，
 * 链路仍在）；我们面对的是 RFCOMM 读失败（链路已断，同会话重试无意义），所以两次机会
 * 都落到"重建通道"上，次数和间隔仍与腕连一致。
 *
 * 纯策略：不引用 Android，不发送任何报文。
 */
public final class ConnectionRecovery {
    /** 与腕连 attempts==1 的 postDelayed 相同。 */
    public static final long FIRST_DELAY_MS = 800L;
    /** 与腕连 attempts==2 的 postDelayed 相同。 */
    public static final long SECOND_DELAY_MS = 1200L;
    /** 腕连第三次不再调度，直接报"after reconnect"失败；我们也只给两次。 */
    public static final int MAX_ATTEMPTS = 2;

    private ConnectionRecovery() {}

    /** attempt 从 1 开始；越界钳到最后一档，绝不返回 0 造成忙等。 */
    public static long delayMs(int attempt) {
        if (attempt <= 1) return FIRST_DELAY_MS;
        return SECOND_DELAY_MS;
    }

    public static int maxAttempts() { return MAX_ATTEMPTS; }

    /** 是否值得自动重建：仅传输/超时层失败，且这些失败重试不会改变设备侧的安全状态。 */
    public static boolean retryable(String code) {
        if (code == null) return false;
        String c = code.trim();
        if (c.isEmpty()) return false;
        if (c.startsWith("BLUETOOTH_PERMISSION_REQUIRED")) return false;
        if (c.startsWith("BLUETOOTH_DISABLED")) return false;
        if (c.startsWith("DEVICE_ADDRESS_INVALID")) return false;
        if (c.startsWith("HUAWEI_INTERNAL_ID_")) return false;
        if (c.startsWith("SYSTEM_PAIRING_REJECTED")) return false;
        if (c.startsWith("SYSTEM_PAIRING_NOT_STARTED")) return false;
        if (c.startsWith("ISO_PEER_ERROR")) return false;
        if (c.startsWith("PEER_REJECTED")) return false;
        // 不确定的文件结果必须由用户重新核对，不能靠自动重建把它变成"看起来成功"。
        if (c.startsWith("WATCHFACE_OPERATION_UNCERTAIN_RECONNECT")) return false;
        if (c.startsWith("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT")) return false;
        if (c.startsWith("RFCOMM_")) return true;
        if (c.startsWith("CONNECTION_STAGE_TIMEOUT_")) return true;
        return false;
    }

    /** 把原始失败串归一成稳定原因，便于日志统计与界面文案。 */
    public static String cause(String code) {
        if (code == null) return "UNKNOWN";
        String c = code.trim();
        if (c.startsWith("RFCOMM_READ_FAILED")) return "LINK_READ_FAILED";
        if (c.startsWith("RFCOMM_EOF")) return "LINK_STREAM_ENDED";
        if (c.startsWith("RFCOMM_WRITE_FAILED")) return "LINK_WRITE_FAILED";
        if (c.startsWith("RFCOMM_CONNECT_FAILED")) return "LINK_OPEN_FAILED";
        if (c.startsWith("CONNECTION_STAGE_TIMEOUT_")) return "STAGE_TIMEOUT";
        if (c.startsWith("BLUETOOTH_DISABLED")) return "BLUETOOTH_DISABLED";
        if (c.startsWith("BLUETOOTH_PERMISSION_REQUIRED")) return "PERMISSION_REQUIRED";
        if (c.startsWith("SYSTEM_PAIRING_REJECTED")) return "PAIRING_REJECTED";
        return "OTHER";
    }

    /** 界面上直接可读的失败说明；不承诺任何未经验证的设备行为。 */
    public static String describe(String code) {
        String c = code == null ? "" : code.trim();
        String cause = cause(c);
        if ("LINK_READ_FAILED".equals(cause) || "LINK_STREAM_ENDED".equals(cause))
            return "Lettura dal canale di comunicazione interrotta o flusso terminato, causa non ancora accertata. Se possibile verrà ripristinato automaticamente fino a due volte; se non funziona ancora, salva il log e riconnettiti.";
        if ("LINK_OPEN_FAILED".equals(cause))
            return "Impossibile aprire il canale di comunicazione; ciò non indica che il watch abbia rifiutato. Se possibile verrà ripristinato fino a due volte (dopo 800/1200 ms); se non funziona ancora, conserva il log.";
        if ("LINK_WRITE_FAILED".equals(cause))
            return "Scrittura sul canale di comunicazione non riuscita, causa non ancora accertata. Se possibile verrà tentato un ripristino limitato; controlla il log e lo stato della connessione.";
        if ("STAGE_TIMEOUT".equals(cause))
            return "La fase corrente non è stata completata in tempo e WristMirror ha interrotto la sessione. Salva il log e controlla in quale fase si è fermata.";
        if ("BLUETOOTH_DISABLED".equals(cause)) return "Il Bluetooth del telefono è disattivato, attivalo e riconnettiti.";
        if ("PERMISSION_REQUIRED".equals(cause)) return "Manca l'autorizzazione Dispositivi nelle vicinanze: impossibile aprire il canale di comunicazione.";
        if ("PAIRING_REJECTED".equals(cause)) return "Abbinamento di sistema rifiutato o annullato; ripeti l'abbinamento sul telefono e sul watch.";
        if (c.startsWith("WATCHFACE_OPERATION_UNCERTAIN_RECONNECT"))
            return "Impossibile confermare l'esito dell'ultima operazione sui file; riconnettiti e aggiorna l'elenco prima di procedere.";
        return c.isEmpty() ? "Connessione non completata" : "Connessione non completata:" + c;
    }
}