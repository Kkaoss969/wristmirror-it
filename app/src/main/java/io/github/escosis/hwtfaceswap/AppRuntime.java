package io.github.escosis.hwtfaceswap;

import android.content.Context;
import android.os.SystemClock;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalController;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal;
import io.github.escosis.hwtfaceswap.platform.AndroidSystemUnpair;
import io.github.escosis.hwtfaceswap.protocol.*;
import io.github.escosis.hwtfaceswap.store.*;
import io.github.escosis.hwtfaceswap.transport.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内运行状态，由界面与设备连接前台服务共享。 */
public final class AppRuntime implements WatchConnection.Listener,WatchfaceManager.Listener {
    private static volatile AppRuntime instance;
    public static AppRuntime get(Context c){if(instance==null)synchronized(AppRuntime.class){if(instance==null)instance=new AppRuntime(c.getApplicationContext());}return instance;}
    public interface Observer {void changed(Snapshot state);void rebindRequested(String message);void deletionPrepared(TlvView.Face face,long epoch);}
    public static final class Snapshot {
        public final String code,detail,deviceAddress,serialNumber,firmware,deviceModel,kind,stage,operationDetail,message,pinMessage;
        public final boolean ready,connecting,needsPin,listReady,listRefreshing,active,failed;
        public final int rebind;public final long epoch,generation,operationRevision,replacementDialogRevision,deletionDialogRevision,activationDialogRevision;
        public final WatchfaceManager.ReplacementIdentity replacementIdentity,replacementDialogIdentity;
        public final WatchfaceManager.DeletionIdentity deletionIdentity,deletionDialogIdentity;
        public final WatchfaceManager.ActivationIdentity activationIdentity,activationDialogIdentity;
        public final ReplacementDialogInfo replacementDialog,deletionDialog,activationDialog;
        public final Integer width,height,progress,batteryPercent;public final Long capability,freeMemoryBytes;
        public final List<TlvView.Face> faces;public final Map<String,String> names;
        Snapshot(AppRuntime r){
            code=r.code;detail=r.detail;deviceAddress=r.deviceAddress;serialNumber=r.serialNumber;firmware=r.firmware;deviceModel=r.deviceModel;kind=r.kind;stage=r.stage;
            operationDetail=r.operationDetail;message=r.message;pinMessage=r.pinMessage;
            ready=r.ready;connecting=r.connecting;needsPin=r.needsPin;listReady=r.listReady;
            listRefreshing=r.listRefreshing;active=r.active;failed=r.failed;rebind=r.rebind;
            epoch=r.epoch;generation=r.generation;operationRevision=r.operationRevision;replacementDialogRevision=r.replacementDialogRevision;deletionDialogRevision=r.deletionDialogRevision;activationDialogRevision=r.activationDialogRevision;
            replacementIdentity=r.replacementIdentity;replacementDialogIdentity=r.replacementRequest==null?null:r.replacementRequest.identity;replacementDialog=r.replacementDialog;
            deletionIdentity=r.deletionIdentity;deletionDialogIdentity=r.deletionRequest==null?null:r.deletionRequest.identity;deletionDialog=r.deletionDialog;
            activationIdentity=r.activationIdentity;activationDialogIdentity=r.activationRequest==null?null:r.activationRequest.identity;activationDialog=r.activationDialog;
            width=r.width;height=r.height;progress=r.progress;
            capability=r.capability;batteryPercent=r.batteryPercent;freeMemoryBytes=r.freeMemoryBytes;faces=Collections.unmodifiableList(new ArrayList<>(r.visible));
            names=Collections.unmodifiableMap(new HashMap<>(r.names));
        }
    }
    public final SecureStore secure;public final SafeLog logs;
    public final WatchConnection connection;public final WatchfaceManager watchfaces;
    public final DeviceRemovalController unpair;
    private final CopyOnWriteArrayList<Observer> observers=new CopyOnWriteArrayList<>();
    private String code="IDLE",detail="Non ancora connesso",deviceAddress="",serialNumber="Sconosciuto",firmware="Sconosciuto",kind="",stage="",operationDetail="",message="",pinMessage="";
    private String deviceModel;
    private boolean ready,connecting,needsPin,listReady,listRefreshing,active,failed;
    private int rebind;private long epoch,generation,operationRevision,replacementDialogRevision,deletionDialogRevision,activationDialogRevision;private Integer width,height,progress,batteryPercent;private Long capability,freeMemoryBytes;
    private WatchfaceManager.ReplacementIdentity replacementIdentity;
    private WatchfaceManager.DeletionIdentity deletionIdentity;
    private WatchfaceManager.ActivationIdentity activationIdentity;
    // 进程内会话只保存标题/文件名与不可变本地身份；不保存 URI、账户、密钥或可恢复页面数据。
    private WatchfaceManager.ReplacementRequest replacementRequest;
    private WatchfaceManager.DeletionRequest deletionRequest;
    private WatchfaceManager.ActivationRequest activationRequest;
    private ReplacementDialogInfo replacementDialog,deletionDialog,activationDialog;
    // UI 退休不清原生历史；即使旧活动摘要晚到，也不能重开已关闭或已让位的会话。
    private final Set<Long> retiredDialogTokens=new HashSet<>();
    private List<TlvView.Face> visible=Collections.emptyList();private Map<String,String> names=Collections.emptyMap();
    private AppRuntime(Context c){this(c,new SecureStore(c),new AndroidSystemUnpair(c));}
    /** 包内装配边界供真实运行时代码的普通 JVM/Android 边界测试；应用始终使用 get。 */
    AppRuntime(Context c,SecureStore secure,DeviceRemovalController.Platform platform){
        this.secure=secure;logs=new SafeLog(c);connection=new WatchConnection(c,secure,logs);watchfaces=new WatchfaceManager(connection,logs);connection.setListener(this);watchfaces.setListener(this);
        SavedWatchStore history=new SavedWatchStore(c);
        connection.setKnownDeviceModelGuard(address->{
            try{return history.devices().stream().anyMatch(watch->watch.getAddress().equalsIgnoreCase(address)&&watch.getModel()!=null);}
            catch(RuntimeException ignored){return false;} // 本地缓存不可读不能推进或中断认证。
        });
        unpair=new DeviceRemovalController(platform,new DeviceRemovalController.Queue(){
            public void post(Runnable task){connection.post(task);}public void delay(Runnable task,long ms){connection.delay(task,ms);}public long now(){return SystemClock.elapsedRealtime();}
        },new DeviceRemovalController.Transport(){
            public boolean busy(){Snapshot s=snapshot();return watchfaces.busy()||s.active||activeDialog(s.replacementDialog)||activeDialog(s.deletionDialog)||activeDialog(s.activationDialog);}
            public void stopTarget(String address){connection.stopForUnpair(address);}
        },new DeviceRemovalController.Records(){
            public List<DeviceRemovalController.Record> pending()throws Exception{List<DeviceRemovalController.Record> result=new ArrayList<>();for(DeviceRemovalJournal.Pending p:secure.removals().pending())result.add(removalRecord(p));return result;}
            public DeviceRemovalController.Record begin(String address,long token)throws Exception{return removalRecord(secure.removals().begin(address,token));}
            public DeviceRemovalController.Record markAttempted(String address,long token)throws Exception{return removalRecord(secure.removals().markAttempted(address,token));}
            public DeviceRemovalController.Record markCleanupStarted(String address,long token)throws Exception{return removalRecord(secure.removals().markCleanupStarted(address,token));}
            public void clearCredentials(String address)throws Exception{secure.clearDevice(address);}
            public void removeHistory(String address)throws Exception{history.removeDevice(address);}
            public void finish(String address,long token)throws Exception{secure.removals().finish(address,token);}
            public void abandon(String address,long token)throws Exception{secure.removals().abandon(address,token);}
        });
        connection.setPendingRemovalGuard(address->unpair.blocksAddress(address));
        unpair.addListener(s->{if(s.message!=null&&!s.message.isEmpty())logs.record("SYSTEM_UNPAIR",s.message);});
    }
    private static DeviceRemovalController.Record removalRecord(DeviceRemovalJournal.Pending p){return new DeviceRemovalController.Record(p.address,p.token,p.generation,p.attempted,p.cleanupStarted);}
    private boolean removalBlocksCurrent(){String address=connection.address();return secure.removals().blocked(address)||(unpair!=null&&unpair.blocksAddress(address));}
    public synchronized Snapshot snapshot(){return new Snapshot(this);}
    public void addObserver(Observer o){observers.addIfAbsent(o);o.changed(snapshot());}
    public void removeObserver(Observer o){observers.remove(o);}
    private static boolean activeDialog(ReplacementDialogInfo dialog){return dialog!=null&&dialog.getOperation().getActive();}
    private boolean activeOperationDialog(){return activeDialog(replacementDialog)||activeDialog(deletionDialog)||activeDialog(activationDialog);}
    private void publish(){Snapshot s=snapshot();for(Observer o:observers)o.changed(s);ConnectionService.runtimeState(s.connecting||s.ready,s.active||activeDialog(s.replacementDialog)||activeDialog(s.deletionDialog)||activeDialog(s.activationDialog),s.code);}
    /** 在同一次发布中退休旧终态，避免共享 getter 用旧替换结果遮住新删除。 */
    private void retireDialogSessions(){
        if(replacementDialog!=null){retiredDialogTokens.add(replacementDialog.getToken());replacementDialog=null;replacementRequest=null;replacementDialogRevision++;}
        if(deletionDialog!=null){retiredDialogTokens.add(deletionDialog.getToken());deletionDialog=null;deletionRequest=null;deletionDialogRevision++;}
        if(activationDialog!=null){retiredDialogTokens.add(activationDialog.getToken());activationDialog=null;activationRequest=null;activationDialogRevision++;}
    }
    private void installReplacementDialog(WatchfaceManager.ReplacementRequest request,String title,String fileName){
        retireDialogSessions();replacementRequest=request;WatchfaceManager.ReplacementIdentity identity=request.identity;
        replacementDialog=new ReplacementDialogInfo(identity.token,identity.epoch,identity.id,identity.version,title,fileName,new OperationInfo(request.directInstall?"direct_install":"replacement","WAIT_OPERATION",request.directInstall?"Avvio dell'installazione":"Avvio della sostituzione",null,true,false),false);
        replacementDialogRevision++;
    }
    private void installDeletionDialog(WatchfaceManager.DeletionRequest request,String title){
        retireDialogSessions();deletionRequest=request;WatchfaceManager.DeletionIdentity identity=request.identity;
        deletionDialog=new ReplacementDialogInfo(identity.token,identity.epoch,identity.id,identity.version,title,"",new OperationInfo("deletion","WAIT_OPERATION","Avvio dell'eliminazione",null,true,false),false);
        deletionDialogRevision++;
    }
    private void installActivationDialog(WatchfaceManager.ActivationRequest request,String title){
        retireDialogSessions();activationRequest=request;WatchfaceManager.ActivationIdentity identity=request.identity;
        activationDialog=new ReplacementDialogInfo(identity.token,identity.epoch,identity.id,identity.version,title,"",new OperationInfo("activation","WAIT_OPERATION","Attivazione del quadrante",null,true,false),false);
        activationDialogRevision++;
    }
    public synchronized boolean openActivationDialog(WatchfaceManager.ActivationRequest request,String title){
        if(request==null||request==activationRequest||retiredDialogTokens.contains(request.identity.token)||activeOperationDialog())return false;
        installActivationDialog(request,title);publish();return true;
    }
    /** 确认立即创建 WAIT_OPERATION，旧 LISTED 摘要不能被借给尚未进入 actor 的新请求。 */
    public synchronized boolean openReplacementDialog(WatchfaceManager.ReplacementRequest request,String title,String fileName){
        if(request==null||request==replacementRequest||retiredDialogTokens.contains(request.identity.token)||activeOperationDialog())return false;
        installReplacementDialog(request,title,fileName);publish();return true;
    }
    /** 删除前置刷新与确认仍走原路径；只有确认被消费后才创建此会话。 */
    public synchronized boolean openDeletionDialog(WatchfaceManager.DeletionRequest request,String title){
        if(request==null||request==deletionRequest||retiredDialogTokens.contains(request.identity.token)||activeOperationDialog())return false;
        installDeletionDialog(request,title);publish();return true;
    }
    private boolean ownsReplacementDialog(WatchfaceManager.ReplacementIdentity identity){
        return identity!=null&&replacementRequest!=null&&replacementRequest.identity==identity&&replacementDialog!=null&&(identity.directInstall?"direct_install":"replacement").equals(replacementDialog.getOperation().getKind())&&replacementDialog.getToken()==identity.token&&replacementDialog.getConnectionEpoch()==identity.epoch&&Objects.equals(replacementDialog.getTargetId(),identity.id)&&Objects.equals(replacementDialog.getTargetVersion(),identity.version);
    }
    private void updateReplacementDialog(WatchfaceManager.ReplacementIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){
        if(!ownsReplacementDialog(identity))return;
        ReplacementDialogInfo old=replacementDialog;
        replacementDialog=new ReplacementDialogInfo(old.getToken(),old.getConnectionEpoch(),old.getTargetId(),old.getTargetVersion(),old.getTitle(),old.getFileName(),new OperationInfo(identity.directInstall?"direct_install":"replacement",stage,detail,progress,active,failed),old.getInterruptRequested());
        replacementDialogRevision++;
    }
    private boolean ownsDeletionDialog(WatchfaceManager.DeletionIdentity identity){
        return identity!=null&&deletionRequest!=null&&deletionRequest.identity==identity&&deletionDialog!=null&&"deletion".equals(deletionDialog.getOperation().getKind())&&deletionDialog.getToken()==identity.token&&deletionDialog.getConnectionEpoch()==identity.epoch&&Objects.equals(deletionDialog.getTargetId(),identity.id)&&Objects.equals(deletionDialog.getTargetVersion(),identity.version);
    }
    private void updateDeletionDialog(WatchfaceManager.DeletionIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){
        if(!ownsDeletionDialog(identity))return;
        ReplacementDialogInfo old=deletionDialog;
        deletionDialog=new ReplacementDialogInfo(old.getToken(),old.getConnectionEpoch(),old.getTargetId(),old.getTargetVersion(),old.getTitle(),"",new OperationInfo("deletion",stage,detail,progress,active,failed),false);
        deletionDialogRevision++;
    }
    private boolean ownsActivationDialog(WatchfaceManager.ActivationIdentity identity){
        return identity!=null&&activationRequest!=null&&activationRequest.identity==identity&&activationDialog!=null&&"activation".equals(activationDialog.getOperation().getKind())&&activationDialog.getToken()==identity.token&&activationDialog.getConnectionEpoch()==identity.epoch&&Objects.equals(activationDialog.getTargetId(),identity.id)&&Objects.equals(activationDialog.getTargetVersion(),identity.version);
    }
    private void updateActivationDialog(WatchfaceManager.ActivationIdentity identity,String stage,String detail,boolean active,boolean failed){
        if(!ownsActivationDialog(identity))return;ReplacementDialogInfo old=activationDialog;
        activationDialog=new ReplacementDialogInfo(old.getToken(),old.getConnectionEpoch(),old.getTargetId(),old.getTargetVersion(),old.getTitle(),"",new OperationInfo("activation",stage,detail,null,active,failed),false);activationDialogRevision++;
    }
    public synchronized void rejectActivationDialog(WatchfaceManager.ActivationIdentity identity,String code){
        if(!ownsActivationDialog(identity)||!activationDialog.getOperation().getActive()||watchfaces.activationIdentity()==identity)return;
        updateActivationDialog(identity,"PRECHECK_FAILED",code,false,true);publish();
    }
    /** 仅界面廉价前检拒绝，不改变原生操作、错误或隔离。 */
    public synchronized void rejectReplacementDialog(WatchfaceManager.ReplacementIdentity identity,String code){
        if(!ownsReplacementDialog(identity)||!replacementDialog.getOperation().getActive()||watchfaces.replacementIdentity()==identity)return;
        updateReplacementDialog(identity,"PRECHECK_FAILED",code,null,false,true);publish();
    }
    public synchronized void rejectDeletionDialog(WatchfaceManager.DeletionIdentity identity,String code){
        if(!ownsDeletionDialog(identity)||!deletionDialog.getOperation().getActive()||watchfaces.deletionIdentity()==identity)return;
        updateDeletionDialog(identity,"PRECHECK_FAILED",code,null,false,true);publish();
    }
    /** main 已核对可见令牌；此处再核对同一请求对象与连接代次，actor 最后核对实际 Replace。 */
    public synchronized boolean interruptReplacement(WatchfaceManager.ReplacementIdentity identity){
        if(!ownsReplacementDialog(identity)||identity.epoch!=connection.epoch()||epoch!=identity.epoch||!replacementDialog.getOperation().getActive()||replacementDialog.getInterruptRequested())return false;
        // 即时门先置位，排队 begin 尚未执行时能做到零 TX；终态只能由 actor 真正停止后发布。
        WatchfaceManager.ReplacementRequest own=replacementRequest;
        watchfaces.cancelReplacement(own);
        // 即使边界同步重入发布了其他任务，也不能给新所有者标记中断请求。
        if(!ownsReplacementDialog(identity))return true;
        ReplacementDialogInfo old=replacementDialog;
        replacementDialog=new ReplacementDialogInfo(old.getToken(),old.getConnectionEpoch(),old.getTargetId(),old.getTargetVersion(),old.getTitle(),old.getFileName(),old.getOperation(),true);
        replacementDialogRevision++;publish();return true;
    }
    /** 关闭只是 UI 会话退休；终态晚到错误仍按原协议处理，不清错误或隔离。 */
    public synchronized boolean dismissReplacementResult(WatchfaceManager.ReplacementIdentity identity){
        if(!ownsReplacementDialog(identity)||replacementDialog.getOperation().getActive())return false;
        retiredDialogTokens.add(identity.token);replacementDialog=null;replacementRequest=null;replacementDialogRevision++;publish();return true;
    }
    public synchronized boolean dismissDeletionResult(WatchfaceManager.DeletionIdentity identity){
        if(!ownsDeletionDialog(identity)||deletionDialog.getOperation().getActive())return false;
        retiredDialogTokens.add(identity.token);deletionDialog=null;deletionRequest=null;deletionDialogRevision++;publish();return true;
    }
    public synchronized boolean dismissActivationResult(WatchfaceManager.ActivationIdentity identity){
        if(!ownsActivationDialog(identity)||activationDialog.getOperation().getActive())return false;
        retiredDialogTokens.add(identity.token);activationDialog=null;activationRequest=null;activationDialogRevision++;publish();return true;
    }
    public synchronized void onConnection(String code,String detail,boolean ready,boolean connecting,int rebindCount,long epoch){
        if(epoch!=connection.epoch()||((ready||connecting)&&removalBlocksCurrent()))return;
        if(this.epoch!=epoch){
            // 设备连接已变化，上一会话的参数与表盘不能用于新设备。
            deviceAddress="";serialNumber="Sconosciuto";firmware="Sconosciuto";deviceModel=null;batteryPercent=null;freeMemoryBytes=null;
            width=height=null;capability=null;visible=Collections.emptyList();
            names=Collections.emptyMap();listReady=listRefreshing=false;message="";
        }
        this.code=code;this.detail=detail;this.ready=ready;this.connecting=!ready&&connecting;
        deviceAddress=ready?connection.address():"";
        this.rebind=rebindCount;this.epoch=epoch;
        if(ready)message="";
        publish();
        // 跳过协议声明，界面关闭后仍由运行时完成同一分支。
        if("AGREEMENT_CONFIRM".equals(code))connection.respondAgreements(epoch,false);
    }
    public synchronized void onPin(boolean needed,String msg){if(needed&&removalBlocksCurrent())return;needsPin=needed;pinMessage=msg;publish();}
    public void onRebind(String message){if(removalBlocksCurrent())return;for(Observer o:observers)o.rebindRequested(message);}
    public synchronized void onDeviceInfo(long epoch,String version){
        if(this.epoch!=epoch||epoch!=connection.epoch()||removalBlocksCurrent()||!ready||!connection.ready()||version==null||version.isEmpty())return;
        firmware=version;publish();
    }
    public synchronized void onDeviceModel(long epoch,String model){
        if(this.epoch!=epoch||epoch!=connection.epoch()||removalBlocksCurrent()||!ready||!connection.ready()||
                !deviceAddress.equalsIgnoreCase(connection.address())||deviceModel!=null)return;
        try{String known=DeviceInfo.modelText(model);if(known==null)return;deviceModel=known;}
        catch(java.io.IOException ignored){return;}
        publish();
    }
    public synchronized void onDeviceStats(long epoch,String serial,Integer battery,Long freeBytes){
        if(this.epoch!=epoch||epoch!=connection.epoch()||removalBlocksCurrent()||!ready||!connection.ready())return;
        if(serial!=null&&!serial.isEmpty())serialNumber=serial;
        if(battery!=null&&battery>=0&&battery<=100)batteryPercent=battery;
        if(freeBytes!=null&&freeBytes>0)freeMemoryBytes=freeBytes;
        publish();
    }
    public synchronized void onReady(long epoch,LinkCapabilities caps){
        if(epoch!=connection.epoch()||this.epoch!=epoch||removalBlocksCurrent())return;
        kind="";stage="";operationDetail="";message="";active=false;failed=false;progress=null;replacementIdentity=null;deletionIdentity=null;activationIdentity=null;operationRevision++;
        publish();watchfaces.authenticated(epoch);
    }
    public void onPacket(long epoch,int service,int command,byte[] body,boolean encrypted){if(removalBlocksCurrent())return;watchfaces.packet(epoch,service,command,body,encrypted);}
    public void onClosed(long closedEpoch,String reason){watchfaces.closed(closedEpoch,reason);}
    public synchronized void onFaces(List<TlvView.Face> faces,Map<String,String> names,boolean fresh,long epoch,long gen){
        if(epoch!=connection.epoch())return;
        visible=faces;this.names=names;listReady=fresh;generation=gen;publish();
    }
    public synchronized void onRefreshing(boolean refreshing,long epoch){
        if(epoch!=connection.epoch())return;
        listRefreshing=refreshing;
        if(!refreshing&&listReady)message="";
        publish();
    }
    public synchronized void onParameters(Integer width,Integer height,Long capability){this.width=width;this.height=height;this.capability=capability;publish();}
    public synchronized void onOperation(String kind,String stage,String detail,Integer progress,boolean active,boolean failed){operationRevision++;replacementIdentity=null;deletionIdentity=null;activationIdentity=null;this.kind=kind;this.stage=stage;operationDetail=detail;this.progress=progress;this.active=active;this.failed=failed;publish();}
    @Override public synchronized void onReplacementOperation(WatchfaceManager.ReplacementIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){
        WatchfaceManager.ReplacementRequest current=watchfaces.currentReplacementRequest();
        if(identity==null||current==null||current.identity!=identity||watchfaces.replacementIdentity()!=identity||(active&&identity.epoch!=connection.epoch()))return;
        // 旧回调只能更新仍由 manager 发布的同一摘要；新会话的标题、文件名不从当前选择重建。
        if(!ownsReplacementDialog(identity)&&active&&!activeOperationDialog()&&!retiredDialogTokens.contains(identity.token))installReplacementDialog(current,identity.id,"HWT locale");
        if(replacementIdentity!=identity)message="";
        operationRevision++;replacementIdentity=identity;deletionIdentity=null;activationIdentity=null;kind=identity.directInstall?"direct_install":"replacement";this.stage=stage;operationDetail=detail;this.progress=progress;this.active=active;this.failed=failed;
        updateReplacementDialog(identity,stage,detail,progress,active,failed);publish();
    }
    @Override public synchronized void onReplacementRequestFinished(WatchfaceManager.ReplacementIdentity identity,String stage,String detail,boolean failed){
        if(!ownsReplacementDialog(identity)||!replacementDialog.getOperation().getActive()||watchfaces.replacementIdentity()==identity)return;
        updateReplacementDialog(identity,stage,detail,null,false,failed);publish();
    }
    @Override public synchronized void onReplacementError(WatchfaceManager.ReplacementIdentity identity,String error){
        WatchfaceManager.ReplacementRequest current=watchfaces.currentReplacementRequest();
        if(identity==null||current==null||current.identity!=identity||replacementIdentity!=identity||watchfaces.replacementIdentity()!=identity)return;
        message=error;publish();
    }
    @Override public synchronized void onDeletionOperation(WatchfaceManager.DeletionIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){
        WatchfaceManager.DeletionRequest current=watchfaces.currentDeletionRequest();
        if(identity==null||current==null||current.identity!=identity||watchfaces.deletionIdentity()!=identity||(active&&identity.epoch!=connection.epoch()))return;
        if(!ownsDeletionDialog(identity)&&active&&!activeOperationDialog()&&!retiredDialogTokens.contains(identity.token))installDeletionDialog(current,identity.id);
        if(deletionIdentity!=identity)message="";
        operationRevision++;deletionIdentity=identity;replacementIdentity=null;activationIdentity=null;kind="deletion";this.stage=stage;operationDetail=detail;this.progress=progress;this.active=active;this.failed=failed;
        updateDeletionDialog(identity,stage,detail,progress,active,failed);publish();
    }
    @Override public synchronized void onDeletionRequestFinished(WatchfaceManager.DeletionIdentity identity,String stage,String detail,boolean failed){
        if(!ownsDeletionDialog(identity)||!deletionDialog.getOperation().getActive()||watchfaces.deletionIdentity()==identity)return;
        updateDeletionDialog(identity,stage,detail,null,false,failed);publish();
    }
    @Override public synchronized void onDeletionError(WatchfaceManager.DeletionIdentity identity,String error){
        WatchfaceManager.DeletionRequest current=watchfaces.currentDeletionRequest();
        if(identity==null||current==null||current.identity!=identity||deletionIdentity!=identity||watchfaces.deletionIdentity()!=identity)return;
        message=error;publish();
    }
    @Override public synchronized void onActivationOperation(WatchfaceManager.ActivationIdentity identity,String stage,String detail,boolean active,boolean failed){
        WatchfaceManager.ActivationRequest current=watchfaces.currentActivationRequest();
        if(identity==null||current==null||current.identity!=identity||watchfaces.activationIdentity()!=identity||(active&&identity.epoch!=connection.epoch()))return;
        if(!ownsActivationDialog(identity)&&active&&!activeOperationDialog()&&!retiredDialogTokens.contains(identity.token))installActivationDialog(current,identity.id);
        if(activationIdentity!=identity)message="";
        operationRevision++;activationIdentity=identity;replacementIdentity=null;deletionIdentity=null;kind="activation";this.stage=stage;operationDetail=detail;progress=null;this.active=active;this.failed=failed;
        updateActivationDialog(identity,stage,detail,active,failed);publish();
    }
    @Override public synchronized void onActivationRequestFinished(WatchfaceManager.ActivationIdentity identity,String stage,String detail,boolean failed){
        if(!ownsActivationDialog(identity)||!activationDialog.getOperation().getActive()||watchfaces.activationIdentity()==identity)return;
        updateActivationDialog(identity,stage,detail,false,failed);publish();
    }
    @Override public synchronized void onActivationError(WatchfaceManager.ActivationIdentity identity,String error){
        WatchfaceManager.ActivationRequest current=watchfaces.currentActivationRequest();
        if(identity==null||current==null||current.identity!=identity||activationIdentity!=identity||watchfaces.activationIdentity()!=identity)return;
        message=error;publish();
    }
    public void onPreparedDeletion(TlvView.Face face,long epoch){if(epoch!=connection.epoch()||!connection.ready())return;for(Observer o:observers)o.deletionPrepared(face,epoch);}
    public synchronized void onError(String msg){
        // 含缺失条目的列表不再作为错误上报（完整行照常可用）；保留旧码的兜底文案，避免任何历史路径把连接问题写错。
        message="WIRE_FACE_LIST_INCOMPLETE".equals(msg)
                ? "Nell'elenco del watch alcune voci non hanno campi obbligatori come la versione; queste voci non verranno usate come destinazione, gli altri quadranti restano utilizzabili"
                : msg;
        publish();
    }
}
