package io.github.escosis.hwtfaceswap.transport;

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
        final long own=epoch;final String ownAddress=address;stage("CONNECTING",rebinding?"Riassocia protocollo":"Apertura del canale di comunicazione con il watch",20000);tick(own);
        if(device.getBondState()!=BluetoothDevice.BOND_BONDED&&!ble){
            stage("SYSTEM_PAIRING","Conferma l'abbinamento di sistema sul telefono e sul watch",60000);bondReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){BluetoothDevice received=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class):intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);try{if(received==null||!received.getAddress().equals(ownAddress))return;}catch(SecurityException e){actor.post(()->{if(epoch==own&&address.equals(ownAddress))fail("BLUETOOTH_PERMISSION_REQUIRED");});return;}int state=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.ERROR);actor.post(()->{if(epoch!=own)return;if(state==BluetoothDevice.BOND_BONDED){unregisterBond();openChannel(adapter,device,own);}else if(state==BluetoothDevice.BOND_NONE)fail("SYSTEM_PAIRING_REJECTED");});}};IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bondReceiver,filter,Context.RECEIVER_EXPORTED);else context.registerReceiver(bondReceiver,filter);if(!device.createBond())throw new Core.Fault("SYSTEM_PAIRING_NOT_STARTED");
        }else openChannel(adapter,device,own);
    }
    private void openChannel(BluetoothAdapter adapter,BluetoothDevice device,long own){if(epoch!=own)return;stage("CONNECTING","Connessione al watch in corso",20000);channel=ble?new GattChannel(context,device,own,this):new RfcommChannel(adapter,device,own,this,true);channel.connect();}
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
        stage("IDLE","Connessione dell'app a questo dispositivo interrotta, in attesa dell'annullamento abbinamento di sistema",0);
        if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,"DEVICE_UNPAIR_PENDING");}
    }
    private void disconnectActor(){userStopped=true;autoRetry=0;long closingEpoch=epoch;closeLocal();epoch++;stage("IDLE","Connessione interrotta",0);if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,"USER_DISCONNECTED");}}
    public void onOpen(long own,String name){actor.post(()->{if(own!=epoch||store.removals().blocked(address))return;try{try{deviceName=WristInitialization.boundedDeviceName(name);}catch(IOException ignored){deviceName="HUAWEI Watch";}log.deviceContext(deviceName,null);stage("LINK_PARAMS","Lettura dei parametri del collegamento",15000);sendPlain(1,1,LinkCapabilities.linkRequest());}catch(IOException e){fail(Core.code(e));}});}
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
        if(service==1&&command==1&&phase.equals("LINK_PARAMS")){caps.parseLink(body);stage("DEVICE_STATUS","Lettura dello stato di pre-autenticazione",15000);sendPlain(1,0x16,Core.tlv(1,new byte[0]));return;}
        if(service==1&&command==0x16&&phase.equals("DEVICE_STATUS")){Long error=Wire.number(fields,0x7f),observed=Wire.number(fields,1);long status=DeviceStatusPolicy.status(observed);boolean advisory=DeviceStatusPolicy.advisory(error),cont=DeviceStatusPolicy.mayContinue(status);log.record("DEVICE_STATUS_OBSERVED","error="+error+" status="+safeNumberObservation(fields,1)+" auth_mode="+caps.authMode+" transport="+(ble?"gatt":"rfcomm_secure")+" error_is_advisory="+advisory+" status_gate_only=true continue="+cont+" reference=gadgetbridge_wrist_native");if(advisory)log.record("DEVICE_STATUS_ADVISORY_ERROR","error="+error+" not_a_gate=true no_retry_sent=true reference=gadgetbridge_wrist_native");if(!cont)throw new Core.Fault(DeviceStatusPolicy.stopReason(status));if(caps.authMode==0)throw new Core.Fault("LEGACY_NORMAL_AUTH_UNSUPPORTED");stage("SECURITY","Negoziazione dell'algoritmo di autenticazione del dispositivo",15000);sendPlain(1,0x33,caps.securityRequest(selfId,Build.MODEL));return;}
        if(service==1&&command==0x33&&phase.equals("SECURITY")){caps.parseSecurity(body);if(!caps.iso()&&!caps.lite())throw new Core.Fault("AUTH_TYPE_UNSUPPORTED");if(caps.iso()&&stored!=null)startIso(false,stored);else if(caps.lite()&&caps.authVersion==2)startLite();else requestPin();return;}
        if(service==1&&command==0x2c&&phase.equals("PIN_REQUEST")){
            TlvView.Part ct=Wire.unique(fields,1),iv=Wire.unique(fields,2);byte[] magic=null,decoded=null;
            try{if(ct==null||iv==null)throw new Core.Fault("PIN_REPLY_MISSING");magic=HuaweiCrypto.pinMagic(caps.authVersion);decoded=caps.encryptMethod==1?HuaweiCrypto.aesGcmDecrypt(magic,iv.value,ct.value,null):HuaweiCrypto.aesCbcDecrypt(magic,iv.value,ct.value);if(decoded.length==0||decoded.length>64)throw new Core.Fault("PIN_REPLY_LENGTH");}
            catch(IOException e){if(decoded!=null)Arrays.fill(decoded,(byte)0);stage("PIN_INPUT","In attesa del codice di abbinamento del watch, oppure conferma di nuovo l'abbinamento sul watch",120000);if(listener!=null)listener.onPin(true,"Inserisci il codice di abbinamento mostrato sul watch per continuare.");return;}
            finally{if(magic!=null)Arrays.fill(magic,(byte)0);}
            pin=decoded;if(listener!=null)listener.onPin(false,"");
            // 只在配对码取得或解密失败时请求手动输入；取得有效配对码后，
            // 后继认证构造或发送失败必须关闭，不能假装仍在等待配对码。
            if(caps.iso())startIso(true,pin);else startLite();return;
        }
        if(service==1&&command==0x28&&phase.equals("ISO_AUTH")){handleIso(fields);return;}
        if(service==1&&command==0x13&&phase.equals("LITE_AUTH")){lite.validate(body);stage("BOND_PARAMS","Lettura dei parametri di associazione del protocollo dell'app",15000);String virtual=store.virtualMac(address);String compact=virtual.replace(":","");sendPlain(1,0x0f,concat(Core.tlv(1,new byte[0]),Core.tlv(3,compact.substring(compact.length()-6).getBytes(StandardCharsets.US_ASCII)),Core.tlv(4,new byte[]{2}),Core.tlv(5,new byte[0]),Core.tlv(7,virtual.getBytes(StandardCharsets.US_ASCII)),Core.tlv(9,new byte[0])));return;}
        if(service==1&&command==0x0f&&phase.equals("BOND_PARAMS")){TlvView.Part counter=Wire.unique(fields,9);if(counter==null||counter.value.length<4)throw new Core.Fault("BOND_COUNTER_MISSING");legacyCounter=Core.number(Arrays.copyOf(counter.value,4));byte[] iv=new byte[16];random.nextBytes(iv);long next=(legacyCounter+1)&0xffffffffL;if(next==0)next=1;System.arraycopy(num(next,4),0,iv,12,4);legacyCounter=next;byte[] key=lite.cipherKey(),active=lite.activeKey(),enc=(caps.encryptMethod==1||caps.deviceSupportType==4)?HuaweiCrypto.aesGcmEncrypt(key,iv,active,null):HuaweiCrypto.aesCbcEncrypt(key,iv,active);Arrays.fill(key,(byte)0);String virtual=store.virtualMac(address).replace(":","");stage("BOND_KEY","Completamento dell'associazione del protocollo",15000);sendPlain(1,0x0e,concat(Core.tlv(1,new byte[0]),Core.tlv(3,new byte[]{0}),Core.tlv(5,virtual.substring(virtual.length()-6).getBytes(StandardCharsets.US_ASCII)),Core.tlv(6,enc),Core.tlv(7,iv)));return;}
        if(service==1&&command==0x0e&&phase.equals("BOND_KEY")){Long error=Wire.number(fields,0x7f);if(error!=null&&error!=0&&error!=100000)throw new Core.Fault("BOND_KEY_REJECTED");byte[] key=lite.activeKey();store.saveAuthKey(address,key);activateCandidate(key);Arrays.fill(key,(byte)0);return;}
        if(service==1&&command==2&&phase.equals("VERIFY_SESSION")){
            if(!encrypted)throw new Core.Fault("SESSION_KEY_PROBE_REPLY_NOT_ENCRYPTED");checkInitHeader(fields,"SERVICE_CAPABILITIES_REJECTED");caps.parseServices(body);sessionVerified=true;
            log.record("SESSION_VERIFIED","encrypted_service_reply=true initialization_complete=false");stage("COMMAND_CAPS","Sessione corrente verificata, lettura delle funzionalità del dispositivo",15000);sendEncrypted(1,3,caps.commandsForSupportedServices());return;
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
            try{checkInitHeader(fields,"AUXILIARY_REPLY_ERROR");log.record("INIT_AUX_REPLY","comando="+command+" ricevuto=true non vincolante per la prontezza=true");}catch(IOException e){log.record("INIT_AUX_UNAVAILABLE","comando="+command+" codice="+Core.code(e)+" non vincolante per la prontezza=true");}return;
        }
        if(sessionVerified&&service==initService&&command==initCommand){handleInitReply(body,fields);return;}
        if(ready&&listener!=null)listener.onPacket(epoch,service,command,body,encrypted);
    }
    /** 名称、固件、序列号、型号独立校验；可早用名称初始化，其余信息只能已验证 READY 后公开。 */
    private void observeProductInfo(long own,List<TlvView.Part> fields){
        if(own!=epoch||telemetry==null)return;
        try{String firmware=WristInitialization.productFirmware(fields);if(firmware!=null)observedFirmware=firmware;}
        catch(IOException e){log.record("DEVICE_INFO_UNAVAILABLE","origine=01/07 campo=07 codice="+Core.code(e)+" mantieni sconosciuto=true");}
        if(observedModel==null&&!knownDeviceModelGuard.test(address)){
            try{observedModel=DeviceInfo.model(fields);}
            catch(IOException e){log.record("DEVICE_MODEL_UNAVAILABLE","origine=01/07 campo=0A codice="+Core.code(e)+" mantieni cache=true");}
        }
        try{telemetry.observe(own,DeviceTelemetry.parse(DeviceInfo.SERVICE,DeviceInfo.PRODUCT_COMMAND,fields));}
        catch(IOException e){log.record("DEVICE_STATS_UNAVAILABLE","service=1 command=7 field=9 code="+Core.code(e)+" ready_gate=false");}
        try{deviceName=WristInitialization.productName(fields,deviceName);}catch(IOException e){log.record("PRODUCT_NAME_UNAVAILABLE","origine=01/07 codice="+Core.code(e)+" mantieni nome originale=true");}
        publishDeviceInformation(own);
    }
    private void publishDeviceInformation(long own){
        if(own!=epoch||!sessionVerified||!ready||cipher==null||telemetry==null||listener==null)return;
        log.deviceContext(deviceName,observedFirmware);
        if(observedFirmware!=null&&!observedFirmware.equals(publishedFirmware)){
            publishedFirmware=observedFirmware; // 回调前标记，防止重入重复公开。
            log.record("DEVICE_INFO_OBSERVED","origine=01/07 campo=07 firmware valido=true");listener.onDeviceInfo(own,observedFirmware);
        }
        if(own!=epoch||!sessionVerified||!ready||cipher==null||telemetry==null||listener==null)return;
        if(observedModel!=null&&publishedModel==null&&!knownDeviceModelGuard.test(address)&&
                !store.removals().blocked(address)&&!pendingRemovalGuard.test(address)){
            publishedModel=observedModel;
            log.record("DEVICE_MODEL_OBSERVED","origine=01/07 campo=0A modello valido=true");listener.onDeviceModel(own,observedModel);
        }
        if(own!=epoch||!sessionVerified||!ready||cipher==null||telemetry==null||listener==null)return;
        DeviceTelemetry.Stats update=telemetry.publication(own,sessionVerified,ready);
        if(update!=null){
            log.record("DEVICE_STATS_OBSERVED","serial_valid="+(update.serialNumber!=null)+" battery_valid="+(update.batteryPercent!=null)+" music_storage_display_valid="+(update.freeMemoryBytes!=null));
            listener.onDeviceStats(own,update.serialNumber,update.batteryPercent,update.freeMemoryBytes);
        }
    }
    private void requestPin()throws IOException{stage("PIN_REQUEST","Richiesta delle informazioni di abbinamento del protocollo al watch",15000);sendPlain(1,0x2c,Core.tlv(1,new byte[0]));}
    public void submitPin(long expectedEpoch,String entered){actor.post(()->{if(epoch!=expectedEpoch||store.removals().blocked(address)||!phase.equals("PIN_INPUT"))return;try{byte[] input=entered==null?new byte[0]:entered.trim().getBytes(StandardCharsets.UTF_8);if(input.length<1||input.length>64)throw new Core.Fault("PIN_INPUT_LENGTH");pin=input;if(listener!=null)listener.onPin(false,"");if(caps.iso())startIso(true,pin);else startLite();}catch(IOException e){fail(Core.code(e));}});}
    private void startLite()throws IOException{if(lite!=null)lite.close();lite=new LiteAuth(caps,pin,stored,random);stage("LITE_AUTH","Verifica dell'attestazione Lite del dispositivo",15000);sendPlain(1,0x13,lite.requestBody(pin));}
    private void startIso(boolean pairing,byte[] secret)throws IOException{if(iso!=null)iso.close();usedSavedKey=!pairing;iso=new IsoAuth(pairing,selfId,secret,System.currentTimeMillis(),random);stage("ISO_AUTH",pairing?"Verifica della nuova prova di abbinamento del protocollo":"Negoziazione di una nuova chiave di sessione con le credenziali permanenti dell'app",15000);sendPlain(1,0x28,iso.requestTlv(iso.startRequest()));}
    private void handleIso(List<TlvView.Part> fields)throws IOException{
        if(iso==null)throw new Core.Fault("ISO_CONTEXT_MISSING");
        log.record("ISO_REPLY_SHAPE",IsoReplyHandler.describe(iso,fields));
        // 先识别错误字段和控制回执，再解析 01 字段正文。
        // 在认证封装层规范合法结束字节，认证证明校验仍由原状态机完成。
        try(IsoReplyHandler.Result reply=IsoReplyHandler.apply(iso,fields)) {
        if(reply.kind==IsoReplyHandler.Kind.PEER_REJECTED){peerRejected();return;}
        if(reply.kind==IsoReplyHandler.Kind.CONTROL_COMPLETION){log.record("ISO_CONTROL_COMPLETION","operation="+iso.operation()+" tag1_json_parsed=false initialized=false");byte[] candidate=reply.candidate();if(candidate==null){byte[] learned=store.authKey(address);if(learned==null)throw new Core.Fault("ISO_PAIR_SECRET_NOT_STORED");try{startIso(false,learned);}finally{Arrays.fill(learned,(byte)0);}}else{try{activateCandidate(candidate);}finally{Arrays.fill(candidate,(byte)0);}}return;}
        saveLearnedSecret();stage("ISO_AUTH","Verifica fase ISO"+iso.expectedStage(),15000);if(reply.next!=null)sendPlain(1,0x28,iso.requestTlv(reply.next));
        }catch(IsoAuth.PeerError e){saveLearnedSecret();peerRejected();}catch(IOException e){saveLearnedSecret();throw e;}
    }
    private void saveLearnedSecret()throws IOException{if(iso==null)return;byte[] learned=iso.learnedSecret();if(learned!=null)try{store.saveAuthKey(address,learned);}finally{Arrays.fill(learned,(byte)0);}}
    private void peerRejected()throws IOException{if(iso.operation()==2&&usedSavedKey&&rebindCount==0){stage("REBIND_CONFIRM","Il dispositivo ha rifiutato le credenziali di protocollo salvate; è possibile riassociare",120000);if(listener!=null)listener.onRebind("Il dispositivo ha rifiutato la chiave di protocollo salvata. La riassociazione potrebbe cambiare il modo in cui il watch si collega ad altre app. Continuare?");}else throw new Core.Fault("ISO_PEER_ERROR");}
    public void confirmRebind(long expectedEpoch){actor.post(()->{if(epoch!=expectedEpoch||!phase.equals("REBIND_CONFIRM")||rebindCount!=0)return;try{final String consentAddress=address;store.clearAuthKey(consentAddress);rebindCount=1;closeLocal();final long consentEpoch=++epoch;stage("REBINDING","Riassociazione del watch in corso",15000);actor.postDelayed(()->{if(epoch!=consentEpoch||!phase.equals("REBINDING")||!address.equals(consentAddress))return;try{open(true);}catch(SecurityException e){fail("BLUETOOTH_PERMISSION_REQUIRED");}catch(IOException e){fail(Core.code(e));}},800);}catch(IOException e){fail(Core.code(e));}});}
    private void activateCandidate(byte[] key)throws IOException{
        if(cipher!=null)cipher.close();cipher=new CryptoSession(key,caps.encryptMethod,caps.deviceSupportType,legacyCounter,random);sessionVerified=false;initUnconfirmed=false;initService=initCommand=nextInit=-1;
        auxiliaryPending.clear();auxiliaryPending.add(5);
        if(telemetry==null||telemetry.epoch()!=epoch)telemetry=new DeviceTelemetry.ProbeOwner(epoch);
        final boolean initialQueries=telemetry.markInitializationRequests(epoch); // 必须在 channel.send/任何回调之前认领。
        stage("VERIFY_SESSION","Attestazione verificata: lettura di prodotto e funzionalità e sincronizzazione dell'ora del telefono; in attesa della risposta di cifratura della sessione",15000);
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
            case 0:if(caps.hasCommand(1,0x37)){initRequest("EXPAND_CAPS","Lettura delle funzionalità estese",1,0x37,Core.tlv(1,new byte[0]),1,true);return;}skipped("EXPAND_CAPS");advanceInit(1);return;
            case 1:if(caps.accountSupported()&&!huaweiId.isEmpty()){initRequest("ACCOUNT","Invio dell'ID account interno",0x1a,5,caps.accountRequest(huaweiId),2,true);return;}skipped("ACCOUNT");advanceInit(2);return;
            case 2:if(caps.hasCommand(1,0x31)){initRequest("SETTINGS","Lettura delle impostazioni di inizializzazione",1,0x31,WristInitialization.settingsRequest(),3,true);return;}skipped("SETTINGS");advanceInit(3);return;
            case 3:if(caps.hasCommand(1,0x30)){stage("AGREEMENT_CONFIRM","Completamento dell'inizializzazione della connessione",120000);return;}skipped("AGREEMENTS");advanceInit(4);return;
            case 4:if(caps.hasCommand(1,0x3f)){initRequest("REVERSE_CAPS","Invio della dichiarazione di capacità inversa del protocollo originale",1,0x3f,WristInitialization.reverseRequest(),5,true);return;}skipped("REVERSE_CAPS");advanceInit(5);return;
            case 5:if(caps.bit(109)){initRequest("SETUP_DEVICE","Inizializzazione dello stato di connessione multi-dispositivo",1,0x3e,WristInitialization.setupRequest(deviceName),6,true);return;}skipped("SETUP_DEVICE");advanceInit(6);return;
            case 6:if(caps.hasCommand(1,0x12)){initRequest("ACTIVITY_TYPES","Lettura dei tipi di attività supportati",1,0x12,Core.tlv(1,new byte[0]),7,true);return;}skipped("ACTIVITY_TYPES");advanceInit(7);return;
            case 7:if(caps.bit(109)){initRequest("WEAR_STATUS","Lettura dello stato di indossamento",1,0x3d,Core.tlv(1,new byte[0]),8,true);return;}skipped("WEAR_STATUS");advanceInit(8);return;
            case 8:if(caps.hasCommand(1,0x35)){initRequest("CONNECT_NOTIFY","Notifica al watch del completamento dell'inizializzazione, in attesa della conferma",1,0x35,WristInitialization.connectedRequest(),9,false);return;}skipped("CONNECT_NOTIFY");finishReady();return;
            case 9:finishReady();return;
            default:throw new Core.Fault("INITIALIZATION_STEP_INVALID");
        }
    }
    /** 不记忆或默认同意，单次明确选择绑定当前通讯连接批次。 */
    public void respondAgreements(long expectedEpoch,boolean accept){actor.post(()->{
        if(epoch!=expectedEpoch||!phase.equals("AGREEMENT_CONFIRM")||!sessionVerified||cipher==null||ready)return;
        try{if(accept){log.record("AGREEMENT_CHOICE","explicit_accept=true");initRequest("AGREEMENTS","Invio delle tre dichiarazioni di accordo che hai accettato esplicitamente",1,0x30,WristInitialization.agreementsRequest(System.currentTimeMillis()),4,true);}
            else{log.record("AGREEMENT_CHOICE","explicit_accept=false sent=false");advanceInit(4);}}
        catch(IOException e){fail(Core.code(e));}
    });}
    private void finishReady()throws IOException{
        if(!sessionVerified||cipher==null)throw new Core.Fault("VERIFIED_SESSION_REQUIRED");if(ready)return;
        final long own=epoch;
        final boolean storageProbe=telemetry!=null&&telemetry.markReadyStorageRequest(own,sessionVerified,true,caps.hasService(DeviceTelemetry.MUSIC_SERVICE)&&caps.hasCommand(DeviceTelemetry.MUSIC_SERVICE,DeviceTelemetry.MUSIC_STORAGE_COMMAND));
        ready=true;initService=initCommand=nextInit=-1;autoRetry=0;savedKeyRejections=0;
        log.record("INITIALIZATION_COMPLETE","session_verified=true optional_unconfirmed="+initUnconfirmed+" coexistence_verified=false");
        stage("READY",initUnconfirmed?"Connessione pronta, alcune conferme di inizializzazione in attesa":"Inizializzazione della connessione completata",0);if(listener!=null)listener.onReady(own,caps);
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
        stage("REBIND_CONFIRM","Connessione fallita più volte di seguito, si consiglia di riassociare il protocollo",120000);
        if(listener!=null){listener.onPin(false,"");listener.onClosed(closingEpoch,code);
            listener.onRebind("Per"+savedKeyRejections+" volte di seguito la connessione è stata rifiutata dal dispositivo; il watch potrebbe non essere più nello stato di associazione del protocollo originale (ad es. dopo il passaggio tra modalità risparmio energetico e completa). La riassociazione sostituisce le chiavi di protocollo tra l'app e il watch e potrebbe influire sul collegamento del watch con altre app. Continuare?");}
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
                stage("REBINDING","Riassociazione del watch in corso",15000);
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
