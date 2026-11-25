package io.github.escosis.hwtfaceswap.transport;


import io.github.escosis.hwtfaceswap.Tr;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import io.github.escosis.hwtfaceswap.protocol.*;
import io.github.escosis.hwtfaceswap.store.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** 本应用串行协议执行器，保留系统配对，分离持久密钥与会话密钥。 */
public final class WatchConnection implements LinkChannel.Listener {
    public interface Listener {
        void onConnection(String code,String detail,boolean ready,boolean connecting,int rebindCount,long epoch);
        void onPin(boolean needed,String message);
        void onRebind(String message);
        void onReady(long epoch,LinkCapabilities caps);
        /** 当前连接合法加密产品回复中实际读到的固件；已验证 READY 后公开，新连接时由接收方重置。 */
        default void onDeviceInfo(long epoch,String firmware) {}
        /** 仅同一连接的合法加密 01/07 根 0A；会话已验证且 READY 后公开，不发额外请求。 */
        default void onDeviceModel(long epoch,String model) {}
        /** 只传实际非空字段，null 不覆盖既有观测；freeMemoryBytes 是腕连音乐存储归一显示等效值，非原报文精确 bytes/RAM。 */
        default void onDeviceStats(long epoch,String serialNumber,Integer batteryPercent,Long freeMemoryBytes) {}
        void onPacket(long epoch,int service,int command,byte[] plain,boolean encrypted);
        void onClosed(long closedEpoch,String reason);
    }
    private final Context context;private final SecureStore store;private final SafeLog log;private final SecureRandom random=new SecureRandom();private final HandlerThread thread=new HandlerThread("gt7-protocol");private final Handler actor;
    private Listener listener;private LinkChannel channel;private FrameCodec.Decoder decoder=new FrameCodec.Decoder();private LinkCapabilities caps=new LinkCapabilities();private CryptoSession cipher;private IsoAuth iso;private LiteAuth lite;
    private volatile long epoch;private volatile boolean ready;private volatile String phase="IDLE";private volatile String address="";private String huaweiId="";private boolean ble,usedSavedKey;private byte[] selfId,pin,stored;private int rebindCount;private long deadline;private BroadcastReceiver bondReceiver;private long legacyCounter;
    private boolean sessionVerified,initUnconfirmed;private long stageSerial;private int initService=-1,initCommand=-1,nextInit=-1;
    /** 自动重建计数与用户明确停止标记；只用于传输层失败后的有界重试。 */
    private int autoRetry;private boolean userStopped;private int savedKeyRejections;
    private final java.util.concurrent.atomic.AtomicLong connectRequestSerial=new java.util.concurrent.atomic.AtomicLong();
    private final Set<Integer> auxiliaryPending=new HashSet<>();private String deviceName="HUAWEI Watch";
    private DeviceTelemetry.ProbeOwner telemetry;private String observedFirmware,publishedFirmware,observedModel,publishedModel;
    private volatile java.util.function.Predicate<String> pendingRemovalGuard=ignored->false;
    private volatile java.util.function.Predicate<String> knownDeviceModelGuard=ignored->false;
    public WatchConnection(Context context,SecureStore store,SafeLog log){this.context=context.getApplicationContext();this.store=store;this.log=log;thread.start();actor=new Handler(thread.getLooper());}
    public void setListener(Listener listener){this.listener=listener;}
    /** 应用确认产生的短预留也阻断旧 FIFO 连接任务，无需先全局断开另一个设备。 */
    public void setPendingRemovalGuard(java.util.function.Predicate<String> guard){pendingRemovalGuard=Objects.requireNonNull(guard);}
    /** 只读本地首个型号缓存；正常 01/07 仍供固件/序列号初始化，已知型号不重复提取或公开。 */
    public void setKnownDeviceModelGuard(java.util.function.Predicate<String> guard){knownDeviceModelGuard=Objects.requireNonNull(guard);}
    public long epoch(){return epoch;}public boolean ready(){return ready;}public LinkCapabilities capabilities(){return caps;}public String address(){return address;}
    public void post(Runnable task){actor.post(task);}public void execute(Runnable task){if(Looper.myLooper()==thread.getLooper())task.run();else actor.post(task);}public void delay(Runnable task,long ms){actor.postDelayed(task,ms);}
    private static byte[] concat(byte[]... parts)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p);return b.toByteArray();}
    private static byte[] num(long value,int length){byte[] b=new byte[length];for(int i=length-1;i>=0;i--){b[i]=(byte)value;value>>>=8;}return b;}
    private void stage(String code,String detail,long timeout){phase=code;stageSerial++;deadline=timeout>0?SystemClock.elapsedRealtime()+timeout:0;log.record("LINK_STATE","phase="+code+" rebind_attempt="+rebindCount);if(listener!=null)listener.onConnection(code,detail,ready,!code.equals("IDLE")&&!code.equals("FAILED")&&!code.equals("READY"),rebindCount,epoch);}
    public void connect(String mac,boolean bleOnly,String account){enqueueConnect(-1,mac,bleOnly,account);}
    /** 连接批次和每 MAC 清理代次均绑定入队请求；迟到结果不能重建已清除的资料。 */
    public void connectIfEpoch(long expectedEpoch,String mac,boolean bleOnly,String account){enqueueConnect(expectedEpoch,mac,bleOnly,account);}
    private void enqueueConnect(long expectedEpoch,String mac,boolean bleOnly,String account){
        final long request=connectRequestSerial.incrementAndGet();log.record("CONNECT_REQUEST","request="+request+" source=connect_api expected_epoch_required="+(expectedEpoch>=0)+" transport="+(bleOnly?"gatt":"rfcomm_secure")+" diagnostic_profile=2140-v1");
        if(pendingRemovalGuard.test(mac)){log.record("CONNECT_NOT_QUEUED","pending_target=true");return;}
        final long generation;try{synchronized(io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal.LOCK){store.removals().requireUsable(mac);generation=store.removals().generation(mac);}}catch(IOException e){log.record("CONNECT_NOT_QUEUED","code="+Core.code(e));return;}
        actor.post(()->{
            if(pendingRemovalGuard.test(mac)||(expectedEpoch>=0&&epoch!=expectedEpoch)||!store.removals().usable(mac,generation)){
                log.record("CONNECT_CALLBACK_INVALIDATED","epoch_or_device_generation_changed=true");return;
            }
            connectActor(mac,bleOnly,account);
        });
    }
    private void connectActor(String mac,boolean bleOnly,String account){rebindCount=0;savedKeyRejections=0;userStopped=false;autoRetry=0;address=mac;ble=bleOnly;try{huaweiId=account==null||account.trim().isEmpty()?store.huaweiId(mac):account.trim();if(huaweiId.isEmpty())throw new Core.Fault("HUAWEI_INTERNAL_ID_REQUIRED");if(huaweiId.getBytes(StandardCharsets.UTF_8).length>128)throw new Core.Fault("HUAWEI_INTERNAL_ID_TOO_LONG");store.saveHuaweiId(mac,huaweiId);open(false);}catch(SecurityException e){fail("BLUETOOTH_PERMISSION_REQUIRED");}catch(IOException e){fail(Core.code(e));}}
    @SuppressLint("MissingPermission") private void open(boolean rebinding)throws IOException{
        store.removals().requireUsable(address);
        final long closingEpoch=epoch;final boolean hadChannel=channel!=null||ready;closeLocal();if(hadChannel&&listener!=null)listener.onClosed(closingEpoch,"CONNECTION_REPLACED");epoch++;ready=false;log.deviceContext(null,null);telemetry=new DeviceTelemetry.ProbeOwner(epoch);legacyCounter=0;caps=new LinkCapabilities();decoder=new FrameCodec.Decoder();selfId=store.identity(address);stored=store.authKey(address);usedSavedKey=stored!=null;pin=null;
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null||!adapter.isEnabled())throw new Core.Fault("BLUETOOTH_DISABLED");BluetoothDevice device;
        try{device=adapter.getRemoteDevice(address);}catch(IllegalArgumentException e){throw new Core.Fault("DEVICE_ADDRESS_INVALID");}
        final long own=epoch;final String ownAddress=address;stage("CONNECTING",rebinding?Tr.tr("重新进行协议绑定", "Riassocia protocollo", "Re-bind protocol", "Volver a vincular protocolo", "Réassocier le protocole", "Voltar a associar protocolo", "Vincular protocolo de novo", "Ново обвързване на протокола", "Protokolü yeniden bağla", "Protokoll neu binden"):Tr.tr("正在打开手表通讯通道", "Apertura del canale di comunicazione con il watch", "Opening the watch communication channel", "Abriendo el canal de comunicación con el reloj", "Ouverture du canal de communication avec la montre", "A abrir o canal de comunicação com o relógio", "Abrindo o canal de comunicação com o relógio", "Отваряне на комуникационния канал с часовника", "Saatle iletişim kanalı açılıyor", "Kommunikationskanal zur Uhr wird geöffnet"),20000);tick(own);
        if(device.getBondState()!=BluetoothDevice.BOND_BONDED&&!ble){
            stage("SYSTEM_PAIRING",Tr.tr("请在手机和手表确认系统配对", "Conferma l'abbinamento di sistema sul telefono e sul watch", "Confirm system pairing on the phone and the watch", "Confirma el emparejamiento del sistema en el teléfono y en el reloj", "Confirmez l'association système sur le téléphone et sur la montre", "Confirme o emparelhamento do sistema no telemóvel e no relógio", "Confirme o pareamento do sistema no celular e no relógio", "Потвърдете системното сдвояване на телефона и на часовника", "Telefonda ve saatte sistem eşleştirmesini onaylayın", "Bestätige die Systemkopplung auf dem Handy und auf der Uhr"),60000);bondReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){BluetoothDevice received=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class):intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);try{if(received==null||!received.getAddress().equals(ownAddress))return;}catch(SecurityException e){actor.post(()->{if(epoch==own&&address.equals(ownAddress))fail("BLUETOOTH_PERMISSION_REQUIRED");});return;}int state=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.ERROR);actor.post(()->{if(epoch!=own)return;if(state==BluetoothDevice.BOND_BONDED){unregisterBond();openChannel(adapter,device,own);}else if(state==BluetoothDevice.BOND_NONE)fail("SYSTEM_PAIRING_REJECTED");});}};IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bondReceiver,filter,Context.RECEIVER_EXPORTED);else context.registerReceiver(bondReceiver,filter);if(!device.createBond())throw new Core.Fault("SYSTEM_PAIRING_NOT_STARTED");
        }else openChannel(adapter,device,own);
    }
    private void openChannel(BluetoothAdapter adapter,BluetoothDevice device,long own){if(epoch!=own)return;stage("CONNECTING",Tr.tr("正在连接手表", "Connessione al watch in corso", "Connecting to the watch", "Conectando con el reloj", "Connexion à la montre en cours", "A ligar ao relógio", "Conectando ao relógio", "Свързване с часовника", "Saate bağlanılıyor", "Verbindung mit der Uhr wird hergestellt"),20000);channel=ble?new GattChannel(context,device,own,this):new RfcommChannel(adapter,device,own,this,true);channel.connect();}
    private void unregisterBond(){if(bondReceiver!=null){try{context.unregisterReceiver(bondReceiver);}catch(IllegalArgumentException ignored){}bondReceiver=null;}}
    public void disconnectIfEpoch(long expected){actor.post(()->{if(epoch==expected)disconnectActor();});}
    public void disconnect(){actor.post(this::disconnectActor);}
    /** 解除配对专用串行屏障：仅退休目标 MAC 的本应用通道与会话，不发设备删除/重置报文。 */
    public void stopForUnpair(String mac){
        if(Looper.myLooper()!=thread.getLooper())throw new IllegalStateException("UNPAIR_REQUIRES_PROTOCOL_QUEUE");
        if(mac==null||!address.equalsIgnoreCase(mac))return;
        long closingEpoch=epoch;closeLocal();if(caps.serverNonce!=null)Arrays.fill(caps.serverNonce,(byte)0);
        userStopped=true;autoRetry=0;
        caps=new LinkCapabilities();decoder=new FrameCodec.Decoder();deviceName="HUAWEI Watch";
        address="";huaweiId="";ble=false;usedSavedKey=false;rebindCount=0;legacyCounter=deadline=0;epoch++;
        stage("IDLE",Tr.tr("已停止该设备的本应用连接，等待系统解除配对", "Connessione dell'app a questo dispositivo interrotta, in attesa dell'annullamento abbinamento di sistema", "The app's connection to this device was stopped, waiting for system unpairing", "Conexión de la app con este dispositivo detenida, esperando el desemparejamiento del sistema", "Connexion de l'app à cet appareil arrêtée, en attente de la dissociation système", "Ligação da app a este dispositivo parada, a aguardar o desemparelhamento do sistema", "Conexão do app com este dispositivo interrompida, aguardando o despareamento do sistema", "Връзката на приложението с това устройство е спряна, изчакване на системното разкачване", "Uygulamanın bu cihazla bağlantısı durduruldu, sistemin eşleştirmeyi kaldırması bekleniyor", "Verbindung der App zu diesem Gerät gestoppt, warten auf die Systementkopplung"),0);
        if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,"DEVICE_UNPAIR_PENDING");}
    }
    private void disconnectActor(){userStopped=true;autoRetry=0;long closingEpoch=epoch;closeLocal();epoch++;stage("IDLE",Tr.tr("连接已断开", "Connessione interrotta", "Connection lost", "Conexión perdida", "Connexion perdue", "Ligação perdida", "Conexão perdida", "Връзката е прекъсната", "Bağlantı koptu", "Verbindung getrennt"),0);if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,"USER_DISCONNECTED");}}
    public void onOpen(long own,String name){actor.post(()->{if(own!=epoch||store.removals().blocked(address))return;try{try{deviceName=WristInitialization.boundedDeviceName(name);}catch(IOException ignored){deviceName="HUAWEI Watch";}log.deviceContext(deviceName,null);stage("LINK_PARAMS",Tr.tr("读取链路参数", "Lettura dei parametri del collegamento", "Reading link parameters", "Leyendo parámetros del enlace", "Lecture des paramètres de liaison", "A ler os parâmetros da ligação", "Lendo os parâmetros do link", "Четене на параметрите на връзката", "Bağlantı parametreleri okunuyor", "Verbindungsparameter werden gelesen"),15000);sendPlain(1,1,LinkCapabilities.linkRequest());}catch(IOException e){fail(Core.code(e));}});}
    public void onFailure(long own,String error){actor.post(()->{if(own==epoch&&!store.removals().blocked(address))fail(error);});}
    public void onBytes(long own,byte[] bytes){final byte[] data=bytes.clone();actor.post(()->{if(own!=epoch||store.removals().blocked(address))return;try{int before=decoder.errors();List<FrameCodec.Packet> packets=decoder.receive(data,SystemClock.elapsedRealtime());if(decoder.errors()!=before)log.record("FRAME_INVALID","reason="+decoder.lastError()+" count="+decoder.errors());if(decoder.errors()>16)throw new Core.Fault("TOO_MANY_INVALID_FRAMES");for(FrameCodec.Packet p:packets){if(own!=epoch)return;byte[] body=p.body();boolean expected=expectedAuthPacket(p.service,p.command),business=ready&&knownBusinessPacket(p.service,p.command);
                if(!expected&&!business){logUnhandled(p.service,p.command,body);if(ready&&listener!=null)listener.onPacket(own,p.service,p.command,body,false);continue;}
                boolean encrypted;try{encrypted=CryptoSession.wrapped(body);}catch(IOException e){if(expected)throw e;log.record("RX_NON_TLV_OR_PARTIAL_ENVELOPE_DROPPED","service="+p.service+" command="+p.command+" no_plaintext_downgrade=true");continue;}
                if(encrypted){if(cipher==null)throw new Core.Fault("ENCRYPTED_BEFORE_SESSION_KEY");body=cipher.open(body);}
                List<TlvView.Part> fields=null;
                if(expected)try{fields=TlvView.decode(body);}catch(IOException e){
                    // 仅原封装/解密校验已通过的可选遥测正文可忽略；封装、GCM、密钥与认证失败仍严格关闭。
                    if(!encrypted||!deviceStatsPacket(p.service,p.command))throw e;
                    if(telemetry!=null)telemetry.complete(own,p.service,p.command);
                    log.record("DEVICE_STATS_UNAVAILABLE","service="+p.service+" command="+p.command+" bytes="+body.length+" code="+Core.code(e)+" ready_gate=false");continue;
                }
                log.packet(true,p.service,p.command,body.length,fields,encrypted);if(expected)handle(own,p.service,p.command,body,fields,encrypted);else if(listener!=null)listener.onPacket(own,p.service,p.command,body,encrypted);
            }}catch(Exception e){fail(e instanceof Core.Fault?Core.code(e):"PROTOCOL_RX_FAILED");}});}
    private boolean expectedAuthPacket(int service,int command){
        if(deviceStatsPacket(service,command))return true;
        if(cipher!=null&&service==1&&auxiliaryPending.contains(command))return true;
        if(sessionVerified&&service==initService&&command==initCommand)return true;
        if(service!=1)return false;switch(phase){case "LINK_PARAMS":return command==1;case "DEVICE_STATUS":return command==0x16;case "SECURITY":return command==0x33;case "PIN_REQUEST":return command==0x2c;case "ISO_AUTH":return command==0x28;case "LITE_AUTH":return command==0x13;case "BOND_PARAMS":return command==0x0f;case "BOND_KEY":return command==0x0e;case "VERIFY_SESSION":return command==2;case "COMMAND_CAPS":return command==3;default:return false;}
    }
    // 本次已查询 01/08 完成后仍验封装/GCM；重复回包不能通过 complete，也不能成为就绪门槛。
    private boolean deviceStatsPacket(int service,int command){return cipher!=null&&DeviceTelemetry.matches(service,command)&&(telemetry!=null&&(telemetry.pending(epoch,service,command)||service==DeviceTelemetry.CONFIG_SERVICE&&command==DeviceTelemetry.BATTERY_COMMAND&&telemetry.batteryQueryClaimed(epoch))||sessionVerified&&ready);}
    private void logUnhandled(int service,int command,byte[] body){
        if(service!=0x34||command!=1||!ready){log.packet(true,service,command,body.length,null,false,"envelope_checked=false dispatch=unhandled");return;}
        boolean encrypted=false;String metadata;
        try{
            encrypted=CryptoSession.wrapped(body);byte[] observation=body;
            if(encrypted){if(cipher==null)throw new Core.Fault("ENCRYPTED_BEFORE_SESSION_KEY");observation=cipher.open(body);}
            metadata="envelope_checked=true envelope_verified="+encrypted+" "+Wire.p2pMetadata(observation);
        }catch(IOException e){metadata="p2p_metadata_unavailable=true code="+Core.code(e)+" payload_not_logged=true no_ack=true diagnostics_only=true";}
        log.packet(true,service,command,body.length,null,encrypted,metadata);
    }
    private static boolean knownBusinessPacket(int service,int command){return Wire.businessPacket(service,command);}
    private static String safeNumberObservation(List<TlvView.Part> fields,int tag){try{return String.valueOf(Wire.number(fields,tag));}catch(IOException ignored){return "invalid";}}
    private void handle(long own,int service,int command,byte[] body,List<TlvView.Part> fields,boolean encrypted)throws IOException{
        if(service==1&&command==1&&phase.equals("LINK_PARAMS")){caps.parseLink(body);stage("DEVICE_STATUS",Tr.tr("读取预认证状态", "Lettura dello stato di pre-autenticazione", "Reading pre-authentication state", "Leyendo el estado de preautenticación", "Lecture de l'état de pré-authentification", "A ler o estado de pré-autenticação", "Lendo o status de pré-autenticação", "Четене на състоянието на предварителното удостоверяване", "Ön kimlik doğrulama durumu okunuyor", "Status der Vorauthentifizierung wird gelesen"),15000);sendPlain(1,0x16,Core.tlv(1,new byte[0]));return;}
        if(service==1&&command==0x16&&phase.equals("DEVICE_STATUS")){Long error=Wire.number(fields,0x7f),observed=Wire.number(fields,1);long status=DeviceStatusPolicy.status(observed);boolean advisory=DeviceStatusPolicy.advisory(error),cont=DeviceStatusPolicy.mayContinue(status);log.record("DEVICE_STATUS_OBSERVED","error="+error+" status="+safeNumberObservation(fields,1)+" auth_mode="+caps.authMode+" transport="+(ble?"gatt":"rfcomm_secure")+" error_is_advisory="+advisory+" status_gate_only=true continue="+cont+" reference=gadgetbridge_wrist_native");if(advisory)log.record("DEVICE_STATUS_ADVISORY_ERROR","error="+error+" not_a_gate=true no_retry_sent=true reference=gadgetbridge_wrist_native");if(!cont)throw new Core.Fault(DeviceStatusPolicy.stopReason(status));if(caps.authMode==0)throw new Core.Fault("LEGACY_NORMAL_AUTH_UNSUPPORTED");stage("SECURITY",Tr.tr("协商设备认证算法", "Negoziazione dell'algoritmo di autenticazione del dispositivo", "Negotiating the device authentication algorithm", "Negociando el algoritmo de autenticación del dispositivo", "Négociation de l'algorithme d'authentification de l'appareil", "A negociar o algoritmo de autenticação do dispositivo", "Negociando o algoritmo de autenticação do dispositivo", "Договаряне на алгоритъма за удостоверяване на устройството", "Cihaz kimlik doğrulama algoritması belirleniyor", "Authentifizierungsalgorithmus des Geräts wird ausgehandelt"),15000);sendPlain(1,0x33,caps.securityRequest(selfId,Build.MODEL));return;}
        if(service==1&&command==0x33&&phase.equals("SECURITY")){caps.parseSecurity(body);if(!caps.iso()&&!caps.lite())throw new Core.Fault("AUTH_TYPE_UNSUPPORTED");if(caps.iso()&&stored!=null)startIso(false,stored);else if(caps.lite()&&caps.authVersion==2)startLite();else requestPin();return;}
        if(service==1&&command==0x2c&&phase.equals("PIN_REQUEST")){
            TlvView.Part ct=Wire.unique(fields,1),iv=Wire.unique(fields,2);byte[] magic=null,decoded=null;
            try{if(ct==null||iv==null)throw new Core.Fault("PIN_REPLY_MISSING");magic=HuaweiCrypto.pinMagic(caps.authVersion);decoded=caps.encryptMethod==1?HuaweiCrypto.aesGcmDecrypt(magic,iv.value,ct.value,null):HuaweiCrypto.aesCbcDecrypt(magic,iv.value,ct.value);if(decoded.length==0||decoded.length>64)throw new Core.Fault("PIN_REPLY_LENGTH");}
            catch(IOException e){if(decoded!=null)Arrays.fill(decoded,(byte)0);stage("PIN_INPUT",Tr.tr("等待手表配对码，或重新在手表确认配对", "In attesa del codice di abbinamento del watch, oppure conferma di nuovo l'abbinamento sul watch", "Waiting for the watch pairing code, or confirm pairing on the watch again", "Esperando el código de emparejamiento del reloj, o confirma de nuevo el emparejamiento en el reloj", "En attente du code d'association de la montre, ou confirmez à nouveau l'association sur la montre", "A aguardar o código de emparelhamento do relógio, ou confirme de novo o emparelhamento no relógio", "Aguardando o código de pareamento do relógio, ou confirme o pareamento no relógio de novo", "Изчакване на кода за сдвояване от часовника или потвърдете сдвояването на часовника отново", "Saatin eşleştirme kodu bekleniyor ya da eşleştirmeyi saatte tekrar onaylayın", "Warten auf den Kopplungscode der Uhr, oder bestätige die Kopplung auf der Uhr erneut"),120000);if(listener!=null)listener.onPin(true,Tr.tr("请输入手表上显示的配对码后继续。", "Inserisci il codice di abbinamento mostrato sul watch per continuare.", "Enter the pairing code shown on the watch to continue.", "Introduce el código de emparejamiento que muestra el reloj para continuar.", "Saisissez le code d'association affiché sur la montre pour continuer.", "Introduza o código de emparelhamento mostrado no relógio para continuar.", "Insira o código de pareamento mostrado no relógio para continuar.", "Въведете кода за сдвояване, показан на часовника, за да продължите.", "Devam etmek için saatte görünen eşleştirme kodunu girin.", "Gib den auf der Uhr angezeigten Kopplungscode ein, um fortzufahren."));return;}
            finally{if(magic!=null)Arrays.fill(magic,(byte)0);}
            pin=decoded;if(listener!=null)listener.onPin(false,"");
            // 只在配对码取得或解密失败时请求手动输入；取得有效配对码后，
            // 后继认证构造或发送失败必须关闭，不能假装仍在等待配对码。
            if(caps.iso())startIso(true,pin);else startLite();return;
        }
        if(service==1&&command==0x28&&phase.equals("ISO_AUTH")){handleIso(fields);return;}
        if(service==1&&command==0x13&&phase.equals("LITE_AUTH")){lite.validate(body);stage("BOND_PARAMS",Tr.tr("读取本应用协议绑定参数", "Lettura dei parametri di associazione del protocollo dell'app", "Reading this app's protocol binding parameters", "Leyendo los parámetros de vinculación del protocolo de esta app", "Lecture des paramètres de liaison du protocole de cette app", "A ler os parâmetros de associação do protocolo desta app", "Lendo os parâmetros de vínculo do protocolo deste app", "Четене на параметрите за обвързване на протокола на това приложение", "Bu uygulamanın protokol bağlama parametreleri okunuyor", "Parameter der Protokollbindung dieser App werden gelesen"),15000);String virtual=store.virtualMac(address);String compact=virtual.replace(":","");sendPlain(1,0x0f,concat(Core.tlv(1,new byte[0]),Core.tlv(3,compact.substring(compact.length()-6).getBytes(StandardCharsets.US_ASCII)),Core.tlv(4,new byte[]{2}),Core.tlv(5,new byte[0]),Core.tlv(7,virtual.getBytes(StandardCharsets.US_ASCII)),Core.tlv(9,new byte[0])));return;}
        if(service==1&&command==0x0f&&phase.equals("BOND_PARAMS")){TlvView.Part counter=Wire.unique(fields,9);if(counter==null||counter.value.length<4)throw new Core.Fault("BOND_COUNTER_MISSING");legacyCounter=Core.number(Arrays.copyOf(counter.value,4));byte[] iv=new byte[16];random.nextBytes(iv);long next=(legacyCounter+1)&0xffffffffL;if(next==0)next=1;System.arraycopy(num(next,4),0,iv,12,4);legacyCounter=next;byte[] key=lite.cipherKey(),active=lite.activeKey(),enc=(caps.encryptMethod==1||caps.deviceSupportType==4)?HuaweiCrypto.aesGcmEncrypt(key,iv,active,null):HuaweiCrypto.aesCbcEncrypt(key,iv,active);Arrays.fill(key,(byte)0);String virtual=store.virtualMac(address).replace(":","");stage("BOND_KEY",Tr.tr("完成协议绑定", "Completamento dell'associazione del protocollo", "Completing protocol binding", "Completando la vinculación del protocolo", "Finalisation de la liaison du protocole", "A concluir a associação do protocolo", "Concluindo o vínculo do protocolo", "Завършване на обвързването на протокола", "Protokol bağlama tamamlanıyor", "Protokollbindung wird abgeschlossen"),15000);sendPlain(1,0x0e,concat(Core.tlv(1,new byte[0]),Core.tlv(3,new byte[]{0}),Core.tlv(5,virtual.substring(virtual.length()-6).getBytes(StandardCharsets.US_ASCII)),Core.tlv(6,enc),Core.tlv(7,iv)));return;}
        if(service==1&&command==0x0e&&phase.equals("BOND_KEY")){Long error=Wire.number(fields,0x7f);if(error!=null&&error!=0&&error!=100000)throw new Core.Fault("BOND_KEY_REJECTED");byte[] key=lite.activeKey();store.saveAuthKey(address,key);activateCandidate(key);Arrays.fill(key,(byte)0);return;}
        if(service==1&&command==2&&phase.equals("VERIFY_SESSION")){
            if(!encrypted)throw new Core.Fault("SESSION_KEY_PROBE_REPLY_NOT_ENCRYPTED");checkInitHeader(fields,"SERVICE_CAPABILITIES_REJECTED");caps.parseServices(body);sessionVerified=true;
            log.record("SESSION_VERIFIED","encrypted_service_reply=true initialization_complete=false");stage("COMMAND_CAPS",Tr.tr("当前会话已验证，正在读取设备能力", "Sessione corrente verificata, lettura delle funzionalità del dispositivo", "Current session verified, reading device capabilities", "Sesión actual verificada, leyendo funciones del dispositivo", "Session actuelle vérifiée, lecture des fonctions de l'appareil", "Sessão atual verificada, a ler as funções do dispositivo", "Sessão atual verificada, lendo os recursos do dispositivo", "Текущата сесия е проверена, четене на функциите на устройството", "Geçerli oturum doğrulandı, cihaz özellikleri okunuyor", "Aktuelle Sitzung geprüft, Gerätefunktionen werden gelesen"),15000);sendEncrypted(1,3,caps.commandsForSupportedServices());return;
        }
        if(service==1&&command==3&&phase.equals("COMMAND_CAPS")){checkInitHeader(fields,"COMMAND_CAPABILITIES_REJECTED");caps.parseCommands(body);afterCaps();return;}
        if(cipher!=null&&DeviceTelemetry.matches(service,command)){
            // 原 w0.H/y 和实机日志允许 01/08 明文电量；仅本次已发送请求的归属内缓存。
            // 它不是会话证明，仍须加密 01/02 验证及 READY 才发布；其他明文遥测仍拒绝。
            if(!encrypted&&(service!=DeviceTelemetry.CONFIG_SERVICE||command!=DeviceTelemetry.BATTERY_COMMAND)){log.record("DEVICE_STATS_UNAVAILABLE","service="+service+" command="+command+" encrypted=false ready_gate=false");return;}
            if(own!=epoch||telemetry==null||!telemetry.complete(own,service,command)){log.record("DEVICE_STATS_UNOWNED_REPLY","service="+service+" command="+command+" published=false");return;}
            try{
                checkInitHeader(fields,"DEVICE_STATS_REJECTED");
                if(service==DeviceInfo.SERVICE&&command==DeviceInfo.PRODUCT_COMMAND)observeProductInfo(own,fields);
                else{telemetry.observe(own,DeviceTelemetry.parse(service,command,fields));publishDeviceInformation(own);}
                log.record("DEVICE_STATS_REPLY","service="+service+" command="+command+" encrypted="+encrypted+" ready_gate=false");
            }catch(IOException e){log.record("DEVICE_STATS_UNAVAILABLE","service="+service+" command="+command+" code="+Core.code(e)+" ready_gate=false");}return;
        }
        if(cipher!=null&&service==1&&auxiliaryPending.remove(command)){
            try{checkInitHeader(fields,"AUXILIARY_REPLY_ERROR");log.record("INIT_AUX_REPLY",Tr.tr("指令=", "comando=", "command=", "command=", "command=", "command=", "command=", "command=", "command=", "command=")+command+Tr.tr(" 已收到=true 不作为就绪门槛=true", " ricevuto=true non vincolante per la prontezza=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true", " received=true not a readiness gate=true"));}catch(IOException e){log.record("INIT_AUX_UNAVAILABLE",Tr.tr("指令=", "comando=", "command=", "command=", "command=", "command=", "command=", "command=", "command=", "command=")+command+Tr.tr(" 代码=", " codice=", " code=", " code=", " code=", " code=", " code=", " code=", " code=", " code=")+Core.code(e)+Tr.tr(" 不作为就绪门槛=true", " non vincolante per la prontezza=true", " not a readiness gate=true", " not a readiness gate=true", " not a readiness gate=true", " not a readiness gate=true", " not a readiness gate=true", " not a readiness gate=true", " not a readiness gate=true", " not a readiness gate=true"));}return;
        }
        if(sessionVerified&&service==initService&&command==initCommand){handleInitReply(body,fields);return;}
        if(ready&&listener!=null)listener.onPacket(epoch,service,command,body,encrypted);
    }
    /** 名称、固件、序列号、型号独立校验；可早用名称初始化，其余信息只能已验证 READY 后公开。 */
    private void observeProductInfo(long own,List<TlvView.Part> fields){
        if(own!=epoch||telemetry==null)return;
        try{String firmware=WristInitialization.productFirmware(fields);if(firmware!=null)observedFirmware=firmware;}
        catch(IOException e){log.record("DEVICE_INFO_UNAVAILABLE",Tr.tr("来源=01/07 字段=07 代码=", "origine=01/07 campo=07 codice=", "source=01/07 field=07 code=", "source=01/07 field=07 code=", "source=01/07 field=07 code=", "source=01/07 field=07 code=", "source=01/07 field=07 code=", "source=01/07 field=07 code=", "source=01/07 field=07 code=", "source=01/07 field=07 code=")+Core.code(e)+Tr.tr(" 保留未知=true", " mantieni sconosciuto=true", " keep unknown=true", " keep unknown=true", " keep unknown=true", " keep unknown=true", " keep unknown=true", " keep unknown=true", " keep unknown=true", " keep unknown=true"));}
        if(observedModel==null&&!knownDeviceModelGuard.test(address)){
            try{observedModel=DeviceInfo.model(fields);}
            catch(IOException e){log.record("DEVICE_MODEL_UNAVAILABLE",Tr.tr("来源=01/07 字段=0A 代码=", "origine=01/07 campo=0A codice=", "source=01/07 field=0A code=", "source=01/07 field=0A code=", "source=01/07 field=0A code=", "source=01/07 field=0A code=", "source=01/07 field=0A code=", "source=01/07 field=0A code=", "source=01/07 field=0A code=", "source=01/07 field=0A code=")+Core.code(e)+Tr.tr(" 保留缓存=true", " mantieni cache=true", " keep cache=true", " keep cache=true", " keep cache=true", " keep cache=true", " keep cache=true", " keep cache=true", " keep cache=true", " keep cache=true"));}
        }
        try{telemetry.observe(own,DeviceTelemetry.parse(DeviceInfo.SERVICE,DeviceInfo.PRODUCT_COMMAND,fields));}
        catch(IOException e){log.record("DEVICE_STATS_UNAVAILABLE","service=1 command=7 field=9 code="+Core.code(e)+" ready_gate=false");}
        try{deviceName=WristInitialization.productName(fields,deviceName);}catch(IOException e){log.record("PRODUCT_NAME_UNAVAILABLE",Tr.tr("来源=01/07 代码=", "origine=01/07 codice=", "source=01/07 code=", "source=01/07 code=", "source=01/07 code=", "source=01/07 code=", "source=01/07 code=", "source=01/07 code=", "source=01/07 code=", "source=01/07 code=")+Core.code(e)+Tr.tr(" 保留原名称=true", " mantieni nome originale=true", " keep original name=true", " keep original name=true", " keep original name=true", " keep original name=true", " keep original name=true", " keep original name=true", " keep original name=true", " keep original name=true"));}
        publishDeviceInformation(own);
    }
    private void publishDeviceInformation(long own){
        if(own!=epoch||!sessionVerified||!ready||cipher==null||telemetry==null||listener==null)return;
        log.deviceContext(deviceName,observedFirmware);
        if(observedFirmware!=null&&!observedFirmware.equals(publishedFirmware)){
            publishedFirmware=observedFirmware; // 回调前标记，防止重入重复公开。
            log.record("DEVICE_INFO_OBSERVED",Tr.tr("来源=01/07 字段=07 固件有效=true", "origine=01/07 campo=07 firmware valido=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true", "source=01/07 field=07 firmware valid=true"));listener.onDeviceInfo(own,observedFirmware);
        }
        if(own!=epoch||!sessionVerified||!ready||cipher==null||telemetry==null||listener==null)return;
        if(observedModel!=null&&publishedModel==null&&!knownDeviceModelGuard.test(address)&&
                !store.removals().blocked(address)&&!pendingRemovalGuard.test(address)){
            publishedModel=observedModel;
            log.record("DEVICE_MODEL_OBSERVED",Tr.tr("来源=01/07 字段=0A 型号有效=true", "origine=01/07 campo=0A modello valido=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true", "source=01/07 field=0A model valid=true"));listener.onDeviceModel(own,observedModel);
        }
        if(own!=epoch||!sessionVerified||!ready||cipher==null||telemetry==null||listener==null)return;
        DeviceTelemetry.Stats update=telemetry.publication(own,sessionVerified,ready);
        if(update!=null){
            log.record("DEVICE_STATS_OBSERVED","serial_valid="+(update.serialNumber!=null)+" battery_valid="+(update.batteryPercent!=null)+" music_storage_display_valid="+(update.freeMemoryBytes!=null));
            listener.onDeviceStats(own,update.serialNumber,update.batteryPercent,update.freeMemoryBytes);
        }
    }
    private void requestPin()throws IOException{stage("PIN_REQUEST",Tr.tr("请求手表协议配对信息", "Richiesta delle informazioni di abbinamento del protocollo al watch", "Requesting protocol pairing information from the watch", "Pidiendo al reloj la información de emparejamiento del protocolo", "Demande des informations d'association du protocole à la montre", "A pedir ao relógio as informações de emparelhamento do protocolo", "Pedindo ao relógio as informações de pareamento do protocolo", "Заявка към часовника за информация за сдвояване на протокола", "Saatten protokol eşleştirme bilgisi isteniyor", "Kopplungsinformationen des Protokolls werden von der Uhr angefordert"),15000);sendPlain(1,0x2c,Core.tlv(1,new byte[0]));}
    public void submitPin(long expectedEpoch,String entered){actor.post(()->{if(epoch!=expectedEpoch||store.removals().blocked(address)||!phase.equals("PIN_INPUT"))return;try{byte[] input=entered==null?new byte[0]:entered.trim().getBytes(StandardCharsets.UTF_8);if(input.length<1||input.length>64)throw new Core.Fault("PIN_INPUT_LENGTH");pin=input;if(listener!=null)listener.onPin(false,"");if(caps.iso())startIso(true,pin);else startLite();}catch(IOException e){fail(Core.code(e));}});}
    private void startLite()throws IOException{if(lite!=null)lite.close();lite=new LiteAuth(caps,pin,stored,random);stage("LITE_AUTH",Tr.tr("验证设备 Lite 认证证明", "Verifica dell'attestazione Lite del dispositivo", "Verifying the device Lite attestation", "Verificando la certificación Lite del dispositivo", "Vérification de l'attestation Lite de l'appareil", "A verificar a certificação Lite do dispositivo", "Verificando a certificação Lite do dispositivo", "Проверка на Lite удостоверението на устройството", "Cihazın Lite onayı doğrulanıyor", "Lite-Attestierung des Geräts wird geprüft"),15000);sendPlain(1,0x13,lite.requestBody(pin));}
    private void startIso(boolean pairing,byte[] secret)throws IOException{if(iso!=null)iso.close();usedSavedKey=!pairing;iso=new IsoAuth(pairing,selfId,secret,System.currentTimeMillis(),random);stage("ISO_AUTH",pairing?Tr.tr("验证新的协议配对证明", "Verifica della nuova prova di abbinamento del protocollo", "Verifying the new protocol pairing proof", "Verificando la nueva prueba de emparejamiento del protocolo", "Vérification de la nouvelle preuve d'association du protocole", "A verificar a nova prova de emparelhamento do protocolo", "Verificando a nova prova de pareamento do protocolo", "Проверка на новото доказателство за сдвояване на протокола", "Yeni protokol eşleştirme kanıtı doğrulanıyor", "Neuer Kopplungsnachweis des Protokolls wird geprüft"):Tr.tr("使用本应用持久凭据协商新会话密钥", "Negoziazione di una nuova chiave di sessione con le credenziali permanenti dell'app", "Negotiating a new session key with the app's persistent credentials", "Negociando una nueva clave de sesión con las credenciales permanentes de la app", "Négociation d'une nouvelle clé de session avec les identifiants permanents de l'app", "A negociar uma nova chave de sessão com as credenciais permanentes da app", "Negociando uma nova chave de sessão com as credenciais permanentes do app", "Договаряне на нов ключ за сесията с постоянните данни на приложението", "Uygulamanın kalıcı kimlik bilgileriyle yeni oturum anahtarı belirleniyor", "Neuer Sitzungsschlüssel wird mit den dauerhaften Anmeldedaten der App ausgehandelt"),15000);sendPlain(1,0x28,iso.requestTlv(iso.startRequest()));}
    private void handleIso(List<TlvView.Part> fields)throws IOException{
        if(iso==null)throw new Core.Fault("ISO_CONTEXT_MISSING");
        log.record("ISO_REPLY_SHAPE",IsoReplyHandler.describe(iso,fields));
        // 先识别错误字段和控制回执，再解析 01 字段正文。
        // 在认证封装层规范合法结束字节，认证证明校验仍由原状态机完成。
        try(IsoReplyHandler.Result reply=IsoReplyHandler.apply(iso,fields)) {
        if(reply.kind==IsoReplyHandler.Kind.PEER_REJECTED){peerRejected();return;}
        if(reply.kind==IsoReplyHandler.Kind.CONTROL_COMPLETION){log.record("ISO_CONTROL_COMPLETION","operation="+iso.operation()+" tag1_json_parsed=false initialized=false");byte[] candidate=reply.candidate();if(candidate==null){byte[] learned=store.authKey(address);if(learned==null)throw new Core.Fault("ISO_PAIR_SECRET_NOT_STORED");try{startIso(false,learned);}finally{Arrays.fill(learned,(byte)0);}}else{try{activateCandidate(candidate);}finally{Arrays.fill(candidate,(byte)0);}}return;}
        saveLearnedSecret();stage("ISO_AUTH",Tr.tr("验证 ISO 阶段 ", "Verifica fase ISO", "Verifying ISO stage", "Verificando la fase ISO", "Vérification de l'étape ISO", "A verificar a fase ISO", "Verificando a fase ISO", "Проверка на етап ISO", "ISO aşaması doğrulanıyor", "ISO-Phase wird geprüft")+iso.expectedStage(),15000);if(reply.next!=null)sendPlain(1,0x28,iso.requestTlv(reply.next));
        }catch(IsoAuth.PeerError e){saveLearnedSecret();peerRejected();}catch(IOException e){saveLearnedSecret();throw e;}
    }
    private void saveLearnedSecret()throws IOException{if(iso==null)return;byte[] learned=iso.learnedSecret();if(learned!=null)try{store.saveAuthKey(address,learned);}finally{Arrays.fill(learned,(byte)0);}}
    private void peerRejected()throws IOException{if(iso.operation()==2&&usedSavedKey&&rebindCount==0){stage("REBIND_CONFIRM",Tr.tr("设备拒绝已存协议凭据，可重新绑定", "Il dispositivo ha rifiutato le credenziali di protocollo salvate; è possibile riassociare", "The device rejected the saved protocol credentials; you can re-bind", "El dispositivo rechazó las credenciales de protocolo guardadas; puedes volver a vincular", "L'appareil a refusé les identifiants de protocole enregistrés ; vous pouvez réassocier", "O dispositivo recusou as credenciais de protocolo guardadas; pode voltar a associar", "O dispositivo recusou as credenciais de protocolo salvas; você pode vincular de novo", "Устройството отказа запазените данни за протокола; можете да обвържете наново", "Cihaz kayıtlı protokol bilgilerini reddetti; yeniden bağlayabilirsiniz", "Das Gerät hat die gespeicherten Protokolldaten abgelehnt; du kannst neu binden"),120000);if(listener!=null)listener.onRebind(Tr.tr("设备拒绝保存的协议密钥。重新绑定可能改变手表与其他应用的连接关系，是否继续？", "Il dispositivo ha rifiutato la chiave di protocollo salvata. La riassociazione potrebbe cambiare il modo in cui il watch si collega ad altre app. Continuare?", "The device rejected the saved protocol key. Re-binding may change how the watch connects to other apps. Continue?", "El dispositivo rechazó la clave de protocolo guardada. Volver a vincular puede cambiar cómo se conecta el reloj con otras apps. ¿Continuar?", "L'appareil a refusé la clé de protocole enregistrée. La réassociation peut changer la façon dont la montre se connecte aux autres apps. Continuer ?", "O dispositivo recusou a chave de protocolo guardada. Voltar a associar pode mudar a forma como o relógio se liga a outras apps. Continuar?", "O dispositivo recusou a chave de protocolo salva. Vincular de novo pode mudar a forma como o relógio se conecta a outros apps. Continuar?", "Устройството отказа запазения ключ на протокола. Новото обвързване може да промени начина, по който часовникът се свързва с други приложения. Продължаване?", "Cihaz kayıtlı protokol anahtarını reddetti. Yeniden bağlama, saatin diğer uygulamalara bağlanma şeklini değiştirebilir. Devam edilsin mi?", "Das Gerät hat den gespeicherten Protokollschlüssel abgelehnt. Das Neubinden kann ändern, wie sich die Uhr mit anderen Apps verbindet. Fortfahren?"));}else throw new Core.Fault("ISO_PEER_ERROR");}
    public void confirmRebind(long expectedEpoch){actor.post(()->{if(epoch!=expectedEpoch||!phase.equals("REBIND_CONFIRM")||rebindCount!=0)return;try{final String consentAddress=address;store.clearAuthKey(consentAddress);rebindCount=1;closeLocal();final long consentEpoch=++epoch;stage("REBINDING",Tr.tr("正在重新绑定手表", "Riassociazione del watch in corso", "Re-binding the watch", "Volviendo a vincular el reloj", "Réassociation de la montre", "A voltar a associar o relógio", "Vinculando o relógio de novo", "Ново обвързване на часовника", "Saat yeniden bağlanıyor", "Uhr wird neu gebunden"),15000);actor.postDelayed(()->{if(epoch!=consentEpoch||!phase.equals("REBINDING")||!address.equals(consentAddress))return;try{open(true);}catch(SecurityException e){fail("BLUETOOTH_PERMISSION_REQUIRED");}catch(IOException e){fail(Core.code(e));}},800);}catch(IOException e){fail(Core.code(e));}});}
    private void activateCandidate(byte[] key)throws IOException{
        if(cipher!=null)cipher.close();cipher=new CryptoSession(key,caps.encryptMethod,caps.deviceSupportType,legacyCounter,random);sessionVerified=false;initUnconfirmed=false;initService=initCommand=nextInit=-1;
        auxiliaryPending.clear();auxiliaryPending.add(5);
        if(telemetry==null||telemetry.epoch()!=epoch)telemetry=new DeviceTelemetry.ProbeOwner(epoch);
        final boolean initialQueries=telemetry.markInitializationRequests(epoch); // 必须在 channel.send/任何回调之前认领。
        stage("VERIFY_SESSION",Tr.tr("认证证明通过，读取产品与能力并同步手机时间；等待会话加密回复", "Attestazione verificata: lettura di prodotto e funzionalità e sincronizzazione dell'ora del telefono; in attesa della risposta di cifratura della sessione", "Attestation passed: reading product and capabilities and syncing the phone time; waiting for the session encryption reply", "Certificación verificada: leyendo producto y funciones y sincronizando la hora del teléfono; esperando la respuesta de cifrado de la sesión", "Attestation vérifiée : lecture du produit et des fonctions et synchronisation de l'heure du téléphone ; en attente de la réponse de chiffrement de la session", "Certificação verificada: a ler o produto e as funções e a sincronizar a hora do telemóvel; a aguardar a resposta de cifragem da sessão", "Certificação verificada: lendo o produto e os recursos e sincronizando a hora do celular; aguardando a resposta de criptografia da sessão", "Удостоверението е проверено: четене на продукта и функциите и синхронизиране на часа на телефона; изчакване на отговора за шифроване на сесията", "Onay doğrulandı: ürün ve özellikler okunuyor ve telefon saati eşitleniyor; oturum şifreleme yanıtı bekleniyor", "Attestierung geprüft: Produkt und Funktionen werden gelesen und die Uhrzeit des Handys synchronisiert; warten auf die Antwort zur Sitzungsverschlüsselung"),15000);
        // 首次仍是已证 07 + 05 + 08 + 02 顺序；07/08 复用这一查询，不在 READY/Home/刷新补发。
        if(initialQueries)sendEncrypted(1,7,WristInitialization.productRequest());long now=System.currentTimeMillis();sendEncrypted(1,5,WristInitialization.timeRequest(now,TimeZone.getDefault().getOffset(now)));
        if(initialQueries)sendEncrypted(1,8,Core.tlv(1,new byte[0]));sendEncrypted(1,2,LinkCapabilities.servicesRequest());
    }
    private static void checkInitHeader(List<TlvView.Part> fields,String code)throws IOException{Long error=Wire.number(fields,0x7f);if(error!=null&&error!=0&&error!=100000)throw new Core.Fault(code);}
    private void afterCaps()throws IOException{advanceInit(0);}
    /** 可选请求的一千五百毫秒继续分支参考已证回调，不作为认证证明。 */
    private void initRequest(String code,String detail,int service,int command,byte[] body,int next,boolean optional)throws IOException{
        if(!sessionVerified||cipher==null||ready)throw new Core.Fault("INITIALIZATION_SESSION_REQUIRED");
        initService=service;initCommand=command;nextInit=next;stage(code,detail,optional?0:15000);final long own=epoch,serial=stageSerial;
        sendEncrypted(service,command,body);
        if(optional)actor.postDelayed(()->{
            if(epoch!=own||stageSerial!=serial||!phase.equals(code)||ready||!sessionVerified)return;
            initUnconfirmed=true;initService=initCommand=nextInit=-1;log.record("INIT_OPTIONAL_TIMEOUT","phase="+code+" continued=true acknowledgment_confirmed=false");
            try{advanceInit(next);}catch(IOException e){fail(Core.code(e));}
        },WristInitialization.OPTIONAL_WAIT_MS);
    }
    private void handleInitReply(byte[] body,List<TlvView.Part> fields)throws IOException{
        final String current=phase;final int next=nextInit;initService=initCommand=nextInit=-1;
        try{
            checkInitHeader(fields,current.equals("CONNECT_NOTIFY")?"CONNECT_NOTIFY_REJECTED":"INIT_OPTIONAL_REPLY_ERROR");
            if(current.equals("EXPAND_CAPS"))caps.parseExpand(body);
            if(current.equals("ACCOUNT")){TlvView.Part result=Wire.unique(fields,2);log.record("ACCOUNT_REPLY","result="+(result==null||result.value.length==0?"absent":Integer.toString(result.value[0]&255))+" account_switch_sent=false");}
            log.record("INIT_REPLY","phase="+current+" received=true header_error="+String.valueOf(Wire.number(fields,0x7f))+" default_success_assumed=false");
        }catch(IOException e){
            if(current.equals("CONNECT_NOTIFY"))throw e;
            initUnconfirmed=true;log.record("INIT_OPTIONAL_UNCONFIRMED","phase="+current+" code="+Core.code(e)+" continued=true");
        }
        advanceInit(next);
    }
    private void skipped(String code){log.record("INIT_SKIPPED","phase="+code+" supported_or_requested=false");}
    private void advanceInit(int step)throws IOException{
        if(!sessionVerified||cipher==null||ready||phase.equals("FAILED")||phase.equals("IDLE"))throw new Core.Fault("INITIALIZATION_SESSION_REQUIRED");
        switch(step){
            case 0:if(caps.hasCommand(1,0x37)){initRequest("EXPAND_CAPS",Tr.tr("读取扩展能力", "Lettura delle funzionalità estese", "Reading extended capabilities", "Leyendo funciones ampliadas", "Lecture des fonctions étendues", "A ler as funções avançadas", "Lendo os recursos estendidos", "Четене на разширените функции", "Genişletilmiş özellikler okunuyor", "Erweiterte Funktionen werden gelesen"),1,0x37,Core.tlv(1,new byte[0]),1,true);return;}skipped("EXPAND_CAPS");advanceInit(1);return;
            case 1:if(caps.accountSupported()&&!huaweiId.isEmpty()){initRequest("ACCOUNT",Tr.tr("附送内部账户 ID", "Invio dell'ID account interno", "Sending the internal account ID", "Enviando el ID de cuenta interno", "Envoi de l'ID de compte interne", "A enviar o ID de conta interno", "Enviando o ID de conta interno", "Изпращане на вътрешния ID на профила", "Dahili hesap ID'si gönderiliyor", "Interne Konto-ID wird gesendet"),0x1a,5,caps.accountRequest(huaweiId),2,true);return;}skipped("ACCOUNT");advanceInit(2);return;
            case 2:if(caps.hasCommand(1,0x31)){initRequest("SETTINGS",Tr.tr("读取初始化设置项", "Lettura delle impostazioni di inizializzazione", "Reading initialization settings", "Leyendo los ajustes de inicialización", "Lecture des paramètres d'initialisation", "A ler as definições de inicialização", "Lendo as configurações de inicialização", "Четене на настройките за инициализация", "Başlatma ayarları okunuyor", "Initialisierungseinstellungen werden gelesen"),1,0x31,WristInitialization.settingsRequest(),3,true);return;}skipped("SETTINGS");advanceInit(3);return;
            case 3:if(caps.hasCommand(1,0x30)){stage("AGREEMENT_CONFIRM",Tr.tr("继续完成连接初始化", "Completamento dell'inizializzazione della connessione", "Completing connection initialization", "Completando la inicialización de la conexión", "Finalisation de l'initialisation de la connexion", "A concluir a inicialização da ligação", "Concluindo a inicialização da conexão", "Завършване на инициализацията на връзката", "Bağlantı başlatması tamamlanıyor", "Verbindungsinitialisierung wird abgeschlossen"),120000);return;}skipped("AGREEMENTS");advanceInit(4);return;
            case 4:if(caps.hasCommand(1,0x3f)){initRequest("REVERSE_CAPS",Tr.tr("发送原协议反向能力声明", "Invio della dichiarazione di capacità inversa del protocollo originale", "Sending the original protocol's reverse capability declaration", "Enviando la declaración de capacidad inversa del protocolo original", "Envoi de la déclaration de capacité inverse du protocole d'origine", "A enviar a declaração de capacidade inversa do protocolo original", "Enviando a declaração de capacidade inversa do protocolo original", "Изпращане на декларацията за обратна възможност на оригиналния протокол", "Orijinal protokolün ters yetenek bildirimi gönderiliyor", "Erklärung der umgekehrten Fähigkeiten des Originalprotokolls wird gesendet"),1,0x3f,WristInitialization.reverseRequest(),5,true);return;}skipped("REVERSE_CAPS");advanceInit(5);return;
            case 5:if(caps.bit(109)){initRequest("SETUP_DEVICE",Tr.tr("初始化多设备连接状态", "Inizializzazione dello stato di connessione multi-dispositivo", "Initializing the multi-device connection state", "Inicializando el estado de conexión multidispositivo", "Initialisation de l'état de connexion multi-appareils", "A inicializar o estado de ligação multidispositivo", "Inicializando o status de conexão multidispositivo", "Инициализиране на състоянието на връзка с няколко устройства", "Çoklu cihaz bağlantı durumu başlatılıyor", "Status der Mehrgeräteverbindung wird initialisiert"),1,0x3e,WristInitialization.setupRequest(deviceName),6,true);return;}skipped("SETUP_DEVICE");advanceInit(6);return;
            case 6:if(caps.hasCommand(1,0x12)){initRequest("ACTIVITY_TYPES",Tr.tr("读取活动类型支持", "Lettura dei tipi di attività supportati", "Reading supported activity types", "Leyendo los tipos de actividad admitidos", "Lecture des types d'activité pris en charge", "A ler os tipos de atividade suportados", "Lendo os tipos de atividade suportados", "Четене на поддържаните видове активност", "Desteklenen etkinlik türleri okunuyor", "Unterstützte Aktivitätstypen werden gelesen"),1,0x12,Core.tlv(1,new byte[0]),7,true);return;}skipped("ACTIVITY_TYPES");advanceInit(7);return;
            case 7:if(caps.bit(109)){initRequest("WEAR_STATUS",Tr.tr("读取佩戴状态", "Lettura dello stato di indossamento", "Reading wear state", "Leyendo el estado de uso", "Lecture de l'état de port", "A ler o estado de utilização", "Lendo o status de uso", "Четене на състоянието на носене", "Takılma durumu okunuyor", "Tragestatus wird gelesen"),1,0x3d,Core.tlv(1,new byte[0]),8,true);return;}skipped("WEAR_STATUS");advanceInit(8);return;
            case 8:if(caps.hasCommand(1,0x35)){initRequest("CONNECT_NOTIFY",Tr.tr("通知手表连接初始化完成，等待当前请求回执", "Notifica al watch del completamento dell'inizializzazione, in attesa della conferma", "Notifying the watch that initialization is complete, waiting for the receipt", "Avisando al reloj de que la inicialización terminó, esperando el acuse", "Notification à la montre de la fin de l'initialisation, en attente de l'accusé", "A avisar o relógio do fim da inicialização, a aguardar a confirmação", "Avisando o relógio do fim da inicialização, aguardando a confirmação", "Известяване на часовника за края на инициализацията, изчакване на потвърждение", "Saate başlatmanın bittiği bildiriliyor, onay bekleniyor", "Uhr wird über das Ende der Initialisierung informiert, warten auf die Bestätigung"),1,0x35,WristInitialization.connectedRequest(),9,false);return;}skipped("CONNECT_NOTIFY");finishReady();return;
            case 9:finishReady();return;
            default:throw new Core.Fault("INITIALIZATION_STEP_INVALID");
        }
    }
    /** 不记忆或默认同意，单次明确选择绑定当前通讯连接批次。 */
    public void respondAgreements(long expectedEpoch,boolean accept){actor.post(()->{
        if(epoch!=expectedEpoch||!phase.equals("AGREEMENT_CONFIRM")||!sessionVerified||cipher==null||ready)return;
        try{if(accept){log.record("AGREEMENT_CHOICE","explicit_accept=true");initRequest("AGREEMENTS",Tr.tr("发送你明确同意的三项协议声明", "Invio delle tre dichiarazioni di accordo che hai accettato esplicitamente", "Sending the three agreement declarations you explicitly accepted", "Enviando las tres declaraciones de acuerdo que aceptaste expresamente", "Envoi des trois déclarations d'accord que vous avez expressément acceptées", "A enviar as três declarações de acordo que aceitou expressamente", "Enviando as três declarações de acordo que você aceitou expressamente", "Изпращане на трите декларации за споразумения, които изрично сте приели", "Açıkça kabul ettiğiniz üç sözleşme bildirimi gönderiliyor", "Die drei ausdrücklich akzeptierten Vereinbarungserklärungen werden gesendet"),1,0x30,WristInitialization.agreementsRequest(System.currentTimeMillis()),4,true);}
            else{log.record("AGREEMENT_CHOICE","explicit_accept=false sent=false");advanceInit(4);}}
        catch(IOException e){fail(Core.code(e));}
    });}
    private void finishReady()throws IOException{
        if(!sessionVerified||cipher==null)throw new Core.Fault("VERIFIED_SESSION_REQUIRED");if(ready)return;
        final long own=epoch;
        final boolean storageProbe=telemetry!=null&&telemetry.markReadyStorageRequest(own,sessionVerified,true,caps.hasService(DeviceTelemetry.MUSIC_SERVICE)&&caps.hasCommand(DeviceTelemetry.MUSIC_SERVICE,DeviceTelemetry.MUSIC_STORAGE_COMMAND));
        ready=true;initService=initCommand=nextInit=-1;autoRetry=0;savedKeyRejections=0;
        log.record("INITIALIZATION_COMPLETE","session_verified=true optional_unconfirmed="+initUnconfirmed+" coexistence_verified=false");
        stage("READY",initUnconfirmed?Tr.tr("连接已就绪，部分初始化回执待确认", "Connessione pronta, alcune conferme di inizializzazione in attesa", "Connection ready, some initialization receipts pending", "Conexión lista, algunos acuses de inicialización pendientes", "Connexion prête, certains accusés d'initialisation en attente", "Ligação pronta, algumas confirmações de inicialização pendentes", "Conexão pronta, algumas confirmações de inicialização pendentes", "Връзката е готова, някои потвърждения за инициализация чакат", "Bağlantı hazır, bazı başlatma onayları bekliyor", "Verbindung bereit, einige Initialisierungsbestätigungen ausstehend"):Tr.tr("连接初始化完成", "Inizializzazione della connessione completata", "Connection initialization completed", "Inicialización de la conexión completada", "Initialisation de la connexion terminée", "Inicialização da ligação concluída", "Inicialização da conexão concluída", "Инициализацията на връзката е завършена", "Bağlantı başlatması tamamlandı", "Verbindungsinitialisierung abgeschlossen"),0);if(listener!=null)listener.onReady(own,caps);
        publishDeviceInformation(own);
        if(own!=epoch||!ready||!sessionVerified||cipher==null)return;
        log.record("DEVICE_STATS_PROBE","product_reused=true battery_reused=true music_storage_supported="+storageProbe+" additional_read_queries="+(storageProbe?1:0)+" repeat_on_refresh=false");
        if(storageProbe)sendStorageProbe(own);
    }
    /** 仅此 READY 入口发送一次；可选发送不可用不重试，密钥/加密故障仍在 catch 之外严格处理。 */
    private void sendStorageProbe(long own)throws IOException{
        if(own!=epoch||!ready||!sessionVerified||cipher==null||telemetry==null||!telemetry.pending(own,DeviceTelemetry.MUSIC_SERVICE,DeviceTelemetry.MUSIC_STORAGE_COMMAND))return;
        byte[] body=DeviceTelemetry.musicStorageRequest(),payload=cipher.seal(body);
        log.packet(false,DeviceTelemetry.MUSIC_SERVICE,DeviceTelemetry.MUSIC_STORAGE_COMMAND,body.length,TlvView.decode(body),true);
        try{
            if(channel==null)throw new Core.Fault("CHANNEL_NOT_READY");
            channel.send(FrameCodec.encode(DeviceTelemetry.MUSIC_SERVICE,DeviceTelemetry.MUSIC_STORAGE_COMMAND,payload,caps.sliceSize),caps.mtu,caps.intervalMs);
        }catch(IOException e){
            if(telemetry!=null)telemetry.complete(own,DeviceTelemetry.MUSIC_SERVICE,DeviceTelemetry.MUSIC_STORAGE_COMMAND);
            log.record("DEVICE_STATS_UNAVAILABLE","service=37 command=4 code="+Core.code(e)+" retry=false ready_gate=false");
        }
    }
    public void sendControl(int service,int command,byte[] body)throws IOException{if(!ready||cipher==null)throw new Core.Fault("AUTHENTICATED_CONNECTION_REQUIRED");sendEncrypted(service,command,body);}
    public void sendFile(int command,byte[] body,boolean encrypt)throws IOException{if(!ready||cipher==null)throw new Core.Fault("AUTHENTICATED_CONNECTION_REQUIRED");send(0x28,command,body,encrypt);}
    private void sendPlain(int service,int command,byte[] body)throws IOException{send(service,command,body,false);}private void sendEncrypted(int service,int command,byte[] body)throws IOException{if(cipher==null)throw new Core.Fault("SESSION_KEY_REQUIRED");send(service,command,body,true);}
    private void send(int service,int command,byte[] body,boolean encrypt)throws IOException{store.removals().requireUsable(address);if(channel==null)throw new Core.Fault("CHANNEL_NOT_READY");byte[] payload=encrypt?cipher.seal(body):body;List<TlvView.Part> p=null;try{p=TlvView.decode(body);}catch(IOException ignored){}log.packet(false,service,command,body.length,p,encrypt);channel.send(FrameCodec.encode(service,command,payload,caps.sliceSize),caps.mtu,caps.intervalMs);}
    private void tick(long own){actor.postDelayed(()->{if(epoch!=own||phase.equals("IDLE")||phase.equals("FAILED"))return;if(deadline!=0&&SystemClock.elapsedRealtime()>deadline){fail("CONNECTION_STAGE_TIMEOUT_"+phase);return;}tick(own);},1000);}
    public void protocolFailure(String code){if(Looper.myLooper()==thread.getLooper())fail(code);else actor.post(()->fail(code));}
    private void fail(String code){if(escalateRebind(code))return;long closingEpoch=epoch;log.record("LINK_FAILED","code="+code);closeLocal();ready=false;epoch++;stage("FAILED",code,0);if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,code);}scheduleRecovery(code);}
    /** 连续使用已存密钥被拒达到阈值后，改为提供一次需要用户确认的重新绑定入口，不再只报失败。 */
    private boolean escalateRebind(String code){
        if(!RebindPolicy.savedKeyRejection(code)||!usedSavedKey||rebindCount!=0||address==null||address.isEmpty())return false;
        ++savedKeyRejections;
        if(!RebindPolicy.reached(savedKeyRejections))return false;
        log.record("REBIND_OFFERED","cause="+code+" saved_key_rejections="+savedKeyRejections+" threshold="+RebindPolicy.THRESHOLD+" no_device_write=true");
        long closingEpoch=epoch;closeLocal();ready=false;epoch++;
        stage("REBIND_CONFIRM",Tr.tr("连续连接失败，建议重新协议绑定", "Connessione fallita più volte di seguito, si consiglia di riassociare il protocollo", "Repeated connection failures, re-binding the protocol is recommended", "Varios fallos de conexión seguidos, se recomienda volver a vincular el protocolo", "Échecs de connexion répétés, la réassociation du protocole est conseillée", "Várias falhas de ligação seguidas, recomenda-se voltar a associar o protocolo", "Várias falhas de conexão seguidas, recomenda-se vincular o protocolo de novo", "Няколко неуспешни свързвания подред, препоръчва се ново обвързване на протокола", "Art arda bağlantı hataları, protokolün yeniden bağlanması önerilir", "Wiederholte Verbindungsfehler, Neubinden des Protokolls empfohlen"),120000);
        if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,code);
            listener.onRebind(Tr.tr("已连续 ", "Per", "For", "Durante", "À", "Durante", "Por", "Вече", "Art arda", "Bereits")+savedKeyRejections+Tr.tr(" 次连接被设备拒绝，手表可能已不在原来的协议绑定状态（例如切换了省电/全能模式）。重新绑定会更换本应用与手表的协议密钥，可能影响手表与其他应用的连接关系，是否继续？", " volte di seguito la connessione è stata rifiutata dal dispositivo; il watch potrebbe non essere più nello stato di associazione del protocollo originale (ad es. dopo il passaggio tra modalità risparmio energetico e completa). La riassociazione sostituisce le chiavi di protocollo tra l'app e il watch e potrebbe influire sul collegamento del watch con altre app. Continuare?", " times in a row the connection was rejected by the device; the watch may no longer be in its original protocol binding state (e.g. after switching between power-saving and full mode). Re-binding replaces the protocol keys between the app and the watch and may affect how the watch connects to other apps. Continue?", " veces seguidas el dispositivo rechazó la conexión; puede que el reloj ya no esté en su vinculación de protocolo original (p. ej. tras cambiar entre ahorro de energía y modo completo). Volver a vincular sustituye las claves de protocolo entre la app y el reloj y puede afectar a cómo se conecta el reloj con otras apps. ¿Continuar?", " reprises de suite l'appareil a refusé la connexion ; la montre n'est peut-être plus dans son état de liaison de protocole d'origine (par ex. après un passage entre économie d'énergie et mode complet). La réassociation remplace les clés de protocole entre l'app et la montre et peut modifier la façon dont la montre se connecte aux autres apps. Continuer ?", " vezes seguidas o dispositivo recusou a ligação; o relógio pode já não estar na associação de protocolo original (por ex. após mudar entre poupança de energia e modo completo). Voltar a associar substitui as chaves de protocolo entre a app e o relógio e pode afetar a forma como o relógio se liga a outras apps. Continuar?", " vezes seguidas o dispositivo recusou a conexão; talvez o relógio não esteja mais no vínculo de protocolo original (por ex. após mudar entre economia de energia e modo completo). Vincular de novo substitui as chaves de protocolo entre o app e o relógio e pode afetar a forma como o relógio se conecta a outros apps. Continuar?", " пъти подред устройството отказа връзката; възможно е часовникът вече да не е в оригиналното обвързване на протокола (напр. след превключване между енергоспестяващ и пълен режим). Новото обвързване сменя ключовете на протокола между приложението и часовника и може да засегне свързването на часовника с други приложения. Продължаване?", " kez cihaz bağlantıyı reddetti; saat artık orijinal protokol bağlama durumunda olmayabilir (ör. güç tasarrufu ve tam mod arasında geçişten sonra). Yeniden bağlama, uygulama ile saat arasındaki protokol anahtarlarını değiştirir ve saatin diğer uygulamalara bağlanmasını etkileyebilir. Devam edilsin mi?", " Mal hintereinander hat das Gerät die Verbindung abgelehnt; die Uhr ist eventuell nicht mehr in ihrer ursprünglichen Protokollbindung (z. B. nach einem Wechsel zwischen Energiesparmodus und Vollmodus). Das Neubinden ersetzt die Protokollschlüssel zwischen App und Uhr und kann beeinflussen, wie sich die Uhr mit anderen Apps verbindet. Fortfahren?"));}
        return true;
    }
    /** 页面按钮：显式给 MAC 重新绑定；先清本应用持久协议密钥，再走一次完整 open(rebinding)。 */
    public void rebind(String mac,boolean bleOnly){
        // companion 里的 canonicalAddress 不是 @JvmStatic，这里按同一规则本地规范化。
        final String target=mac!=null&&mac.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")?mac.toUpperCase(java.util.Locale.ROOT):null;
        actor.post(()->{
            if(target==null){fail("DEVICE_ADDRESS_INVALID");return;}
            if(store.removals().blocked(target)){log.record("REBIND_NOT_STARTED","reason=pending_removal");return;}
            try{store.removals().requireUsable(target);}catch(IOException e){log.record("REBIND_NOT_STARTED","code="+Core.code(e));return;}
            if((!ready&&!phase.equals("IDLE")&&!phase.equals("FAILED"))||(!address.isEmpty()&&!address.equalsIgnoreCase(target))){closeLocal();ready=false;}
            final long own=++epoch;address=target;ble=bleOnly;rebindCount=1;savedKeyRejections=0;
            userStopped=false;autoRetry=0;
            try{huaweiId=store.huaweiId(target);if(huaweiId==null||huaweiId.trim().isEmpty())throw new Core.Fault("HUAWEI_INTERNAL_ID_REQUIRED");}catch(IOException e){fail(Core.code(e));return;}
            try{
                log.record("REBIND_REQUESTED","source=page_button user_consented=true force=true no_auto_retry=true");
                store.clearAuthKey(target);closeLocal();ready=false;
                stage("REBINDING",Tr.tr("正在重新绑定手表", "Riassociazione del watch in corso", "Re-binding the watch", "Volviendo a vincular el reloj", "Réassociation de la montre", "A voltar a associar o relógio", "Vinculando o relógio de novo", "Ново обвързване на часовника", "Saat yeniden bağlanıyor", "Uhr wird neu gebunden"),15000);
                final String targetAddress=address;
                actor.postDelayed(()->{if(epoch!=own||!phase.equals("REBINDING")||!targetAddress.equalsIgnoreCase(address))return;
                    try{open(true);}catch(SecurityException e){fail("BLUETOOTH_PERMISSION_REQUIRED");}catch(IOException e){fail(Core.code(e));}},800);
            }catch(IOException e){fail(Core.code(e));}
        });
    }
    /**
     * 与腕连一致的两次有界恢复：800 ms 一次、1200 ms 一次，第三次只明确失败。
     * 只对传输层失败生效；用户明确断开、待解除配对或协议语义失败一律不自动重建。
     */
    private void scheduleRecovery(String code){
        if(autoRetry>=ConnectionRecovery.MAX_ATTEMPTS)return;
        if(userStopped||code==null||!ConnectionRecovery.retryable(code))return;
        if(address==null||address.isEmpty()||store.removals().blocked(address))return;
        final int attempt=++autoRetry;final long delay=ConnectionRecovery.delayMs(attempt);final long own=epoch;
        log.record("AUTO_RECONNECT_SCHEDULED","attempt="+attempt+" of="+ConnectionRecovery.MAX_ATTEMPTS+" delay_ms="+delay+" cause="+ConnectionRecovery.cause(code)+" user_stopped=false");
        actor.postDelayed(()->{
            if(own!=epoch||userStopped||store.removals().blocked(address)){log.record("AUTO_RECONNECT_CANCELLED","attempt="+attempt+" reason=epoch_or_removal_changed");return;}
            try{open(false);}catch(SecurityException e){fail("BLUETOOTH_PERMISSION_REQUIRED");}catch(IOException e){fail(Core.code(e));}
        },delay);
    }
    private void closeLocal(){unregisterBond();ready=false;sessionVerified=false;initService=initCommand=nextInit=-1;auxiliaryPending.clear();telemetry=null;observedFirmware=publishedFirmware=observedModel=publishedModel=null;stageSerial++;if(channel!=null)channel.close();channel=null;if(cipher!=null)cipher.close();cipher=null;if(iso!=null)iso.close();iso=null;if(lite!=null)lite.close();lite=null;for(byte[] b:new byte[][]{stored,selfId,pin})if(b!=null)Arrays.fill(b,(byte)0);stored=null;selfId=null;pin=null;decoder.clear();}
}
