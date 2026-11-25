package io.github.escosis.hwtfaceswap.protocol;


import io.github.escosis.hwtfaceswap.Tr;
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
            return Tr.tr("通讯通道读取中断或数据流结束，具体原因尚未确认。符合条件时最多自动恢复两次；若仍失败，请保存日志后再连接。", "Lettura dal canale di comunicazione interrotta o flusso terminato, causa non ancora accertata. Se possibile verrà ripristinato automaticamente fino a due volte; se non funziona ancora, salva il log e riconnettiti.", "Reading from the communication channel was interrupted or the stream ended, cause not confirmed yet. If possible it will recover automatically up to twice; if it still fails, save the log and reconnect.", "La lectura del canal de comunicación se interrumpió o terminó el flujo, causa aún sin confirmar. Si es posible se recuperará automáticamente hasta dos veces; si sigue fallando, guarda el registro y vuelve a conectar.", "La lecture du canal de communication a été interrompue ou le flux s'est terminé, cause pas encore confirmée. Si possible, la récupération se fera automatiquement jusqu'à deux fois ; si l'échec persiste, enregistrez le journal et reconnectez-vous.", "A leitura do canal de comunicação foi interrompida ou o fluxo terminou, causa ainda não confirmada. Se possível, a recuperação será automática até duas vezes; se continuar a falhar, guarde o registo e volte a ligar.", "A leitura do canal de comunicação foi interrompida ou o fluxo terminou, causa ainda não confirmada. Se possível, a recuperação será automática até duas vezes; se continuar falhando, salve o registro e conecte de novo.", "Четенето от комуникационния канал беше прекъснато или потокът приключи, причината още не е потвърдена. Ако е възможно, ще се възстанови автоматично до два пъти; ако пак не стане, запазете дневника и се свържете отново.", "İletişim kanalından okuma kesildi veya akış bitti, neden henüz doğrulanmadı. Mümkünse en fazla iki kez otomatik olarak kurtarılacak; yine olmazsa günlüğü kaydedip yeniden bağlanın.", "Das Lesen aus dem Kommunikationskanal wurde unterbrochen oder der Datenstrom endete, Ursache noch nicht bestätigt. Wenn möglich, wird bis zu zweimal automatisch wiederhergestellt; schlägt es weiter fehl, speichere das Protokoll und verbinde dich neu.");
        if ("LINK_OPEN_FAILED".equals(cause))
            return Tr.tr("通讯通道未能建立，不能据此判断手表主动拒绝。符合条件时按800/1200毫秒最多恢复两次；仍失败请保留日志。", "Impossibile aprire il canale di comunicazione; ciò non indica che il watch abbia rifiutato. Se possibile verrà ripristinato fino a due volte (dopo 800/1200 ms); se non funziona ancora, conserva il log.", "The communication channel couldn't be opened; this doesn't mean the watch refused. If possible it will recover up to twice (after 800/1200 ms); if it still fails, keep the log.", "No se pudo abrir el canal de comunicación; esto no significa que el reloj lo rechazara. Si es posible se recuperará hasta dos veces (tras 800/1200 ms); si sigue fallando, conserva el registro.", "Le canal de communication n'a pas pu être ouvert ; cela ne signifie pas que la montre a refusé. Si possible, la récupération se fera jusqu'à deux fois (après 800/1200 ms) ; si l'échec persiste, gardez le journal.", "Não foi possível abrir o canal de comunicação; isto não significa que o relógio recusou. Se possível, a recuperação será feita até duas vezes (após 800/1200 ms); se continuar a falhar, guarde o registo.", "Não foi possível abrir o canal de comunicação; isso não significa que o relógio recusou. Se possível, a recuperação será feita até duas vezes (após 800/1200 ms); se continuar falhando, guarde o registro.", "Комуникационният канал не може да се отвори; това не означава, че часовникът е отказал. Ако е възможно, ще се възстанови до два пъти (след 800/1200 ms); ако пак не стане, запазете дневника.", "İletişim kanalı açılamadı; bu, saatin reddettiği anlamına gelmez. Mümkünse en fazla iki kez kurtarılacak (800/1200 ms sonra); yine olmazsa günlüğü saklayın.", "Der Kommunikationskanal konnte nicht geöffnet werden; das heißt nicht, dass die Uhr abgelehnt hat. Wenn möglich, wird bis zu zweimal wiederhergestellt (nach 800/1200 ms); schlägt es weiter fehl, behalte das Protokoll.");
        if ("LINK_WRITE_FAILED".equals(cause))
            return Tr.tr("写入通讯通道失败，具体原因尚未确认。符合条件时可有界恢复；请核对日志与当前连接状态。", "Scrittura sul canale di comunicazione non riuscita, causa non ancora accertata. Se possibile verrà tentato un ripristino limitato; controlla il log e lo stato della connessione.", "Writing to the communication channel failed, cause not confirmed yet. If possible a limited recovery will be attempted; check the log and the connection state.", "Falló la escritura en el canal de comunicación, causa aún sin confirmar. Si es posible se intentará una recuperación limitada; revisa el registro y el estado de la conexión.", "L'écriture sur le canal de communication a échoué, cause pas encore confirmée. Si possible, une récupération limitée sera tentée ; vérifiez le journal et l'état de la connexion.", "Falhou a escrita no canal de comunicação, causa ainda não confirmada. Se possível, será tentada uma recuperação limitada; veja o registo e o estado da ligação.", "Falhou a escrita no canal de comunicação, causa ainda não confirmada. Se possível, será tentada uma recuperação limitada; veja o registro e o status da conexão.", "Записът в комуникационния канал не бе успешен, причината още не е потвърдена. Ако е възможно, ще се опита ограничено възстановяване; проверете дневника и състоянието на връзката.", "İletişim kanalına yazılamadı, neden henüz doğrulanmadı. Mümkünse sınırlı bir kurtarma denenecek; günlüğü ve bağlantı durumunu kontrol edin.", "Schreiben in den Kommunikationskanal fehlgeschlagen, Ursache noch nicht bestätigt. Wenn möglich, wird eine begrenzte Wiederherstellung versucht; prüfe das Protokoll und den Verbindungsstatus.");
        if ("STAGE_TIMEOUT".equals(cause))
            return Tr.tr("当前阶段未在期限内完成，腕镜已中止本次会话。请保存日志，核对停在哪个阶段。", "La fase corrente non è stata completata in tempo e WristMirror ha interrotto la sessione. Salva il log e controlla in quale fase si è fermata.", "The current stage didn't complete in time and WristMirror stopped the session. Save the log and check which stage it stopped at.", "La fase actual no terminó a tiempo y WristMirror detuvo la sesión. Guarda el registro y comprueba en qué fase se detuvo.", "L'étape en cours ne s'est pas terminée à temps et WristMirror a arrêté la session. Enregistrez le journal et vérifiez à quelle étape elle s'est arrêtée.", "A fase atual não terminou a tempo e o WristMirror parou a sessão. Guarde o registo e veja em que fase parou.", "A fase atual não terminou a tempo e o WristMirror interrompeu a sessão. Salve o registro e veja em que fase parou.", "Текущият етап не приключи навреме и WristMirror спря сесията. Запазете дневника и проверете на кой етап е спряла.", "Geçerli aşama zamanında tamamlanmadı ve WristMirror oturumu durdurdu. Günlüğü kaydedin ve hangi aşamada durduğuna bakın.", "Die aktuelle Phase wurde nicht rechtzeitig abgeschlossen und WristMirror hat die Sitzung beendet. Speichere das Protokoll und prüfe, in welcher Phase sie stehen blieb.");
        if ("BLUETOOTH_DISABLED".equals(cause)) return Tr.tr("手机蓝牙已关闭，请打开蓝牙后重连。", "Il Bluetooth del telefono è disattivato, attivalo e riconnettiti.", "Phone Bluetooth is off, turn it on and reconnect.", "El Bluetooth del teléfono está desactivado, actívalo y vuelve a conectar.", "Le Bluetooth du téléphone est désactivé, activez-le et reconnectez-vous.", "O Bluetooth do telemóvel está desligado, ligue-o e volte a ligar.", "O Bluetooth do celular está desligado, ligue-o e conecte de novo.", "Bluetooth на телефона е изключен, включете го и се свържете отново.", "Telefonun Bluetooth'u kapalı, açıp yeniden bağlanın.", "Bluetooth auf dem Handy ist aus, schalte es ein und verbinde dich neu.");
        if ("PERMISSION_REQUIRED".equals(cause)) return Tr.tr("缺少附近设备权限，无法建立通讯通道。", "Manca l'autorizzazione Dispositivi nelle vicinanze: impossibile aprire il canale di comunicazione.", "Nearby devices permission missing: can't open the communication channel.", "Falta el permiso de Dispositivos cercanos: no se puede abrir el canal de comunicación.", "Autorisation Appareils à proximité manquante : impossible d'ouvrir le canal de communication.", "Falta a permissão Dispositivos próximos: não é possível abrir o canal de comunicação.", "Falta a permissão Dispositivos por perto: não é possível abrir o canal de comunicação.", "Липсва разрешението „Устройства наблизо“: комуникационният канал не може да се отвори.", "Yakındaki cihazlar izni eksik: iletişim kanalı açılamıyor.", "Berechtigung „Geräte in der Nähe“ fehlt: Kommunikationskanal kann nicht geöffnet werden.");
        if ("PAIRING_REJECTED".equals(cause)) return Tr.tr("系统配对被拒绝或取消，请在手机和手表上重新配对。", "Abbinamento di sistema rifiutato o annullato; ripeti l'abbinamento sul telefono e sul watch.", "System pairing rejected or cancelled; pair again on the phone and the watch.", "Emparejamiento del sistema rechazado o cancelado; vuelve a emparejar en el teléfono y en el reloj.", "Association système refusée ou annulée ; associez à nouveau le téléphone et la montre.", "Emparelhamento do sistema recusado ou cancelado; volte a emparelhar no telemóvel e no relógio.", "Pareamento do sistema recusado ou cancelado; pareie de novo no celular e no relógio.", "Системното сдвояване е отказано или отменено; сдвоете отново телефона и часовника.", "Sistem eşleştirmesi reddedildi veya iptal edildi; telefonda ve saatte tekrar eşleştirin.", "Systemkopplung abgelehnt oder abgebrochen; kopple Handy und Uhr erneut.");
        if (c.startsWith("WATCHFACE_OPERATION_UNCERTAIN_RECONNECT"))
            return Tr.tr("上一次文件操作的结果无法确认，请重新连接后先刷新列表再操作。", "Impossibile confermare l'esito dell'ultima operazione sui file; riconnettiti e aggiorna l'elenco prima di procedere.", "The result of the last file operation can't be confirmed; reconnect and refresh the list before continuing.", "No se puede confirmar el resultado de la última operación con archivos; vuelve a conectar y actualiza la lista antes de seguir.", "Le résultat de la dernière opération sur les fichiers ne peut pas être confirmé ; reconnectez-vous et actualisez la liste avant de continuer.", "Não é possível confirmar o resultado da última operação de ficheiros; volte a ligar e atualize a lista antes de continuar.", "Não é possível confirmar o resultado da última operação com arquivos; conecte de novo e atualize a lista antes de continuar.", "Резултатът от последната операция с файлове не може да се потвърди; свържете се отново и опреснете списъка, преди да продължите.", "Son dosya işleminin sonucu doğrulanamıyor; devam etmeden önce yeniden bağlanıp listeyi yenileyin.", "Das Ergebnis des letzten Dateivorgangs kann nicht bestätigt werden; verbinde dich neu und aktualisiere die Liste, bevor du weitermachst.");
        return c.isEmpty() ? Tr.tr("连接未完成", "Connessione non completata", "Connection not completed", "Conexión no completada", "Connexion non terminée", "Ligação não concluída", "Conexão não concluída", "Връзката не е завършена", "Bağlantı tamamlanmadı", "Verbindung nicht abgeschlossen") : Tr.tr("连接未完成：", "Connessione non completata:", "Connection not completed:", "Conexión no completada:", "Connexion non terminée :", "Ligação não concluída:", "Conexão não concluída:", "Връзката не е завършена:", "Bağlantı tamamlanmadı:", "Verbindung nicht abgeschlossen:") + c;
    }
}