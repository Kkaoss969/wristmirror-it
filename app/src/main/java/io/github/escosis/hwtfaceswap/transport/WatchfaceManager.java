package io.github.escosis.hwtfaceswap.transport;

import android.os.SystemClock;
import io.github.escosis.hwtfaceswap.protocol.*;
import io.github.escosis.hwtfaceswap.store.SafeLog;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 在本应用串行连接执行器中实现既有表盘内容替换流程。 */
public final class WatchfaceManager {
    public interface Listener {
        void onFaces(List<TlvView.Face> faces,Map<String,String> names,boolean fresh,long epoch,long generation);
        void onParameters(Integer width,Integer height,Long capability);
        void onOperation(String kind,String stage,String detail,Integer progress,boolean active,boolean failed);
        void onPreparedDeletion(TlvView.Face face,long epoch);
        void onError(String error);
        default void onRefreshing(boolean refreshing,long epoch) {}
        /** 身份与操作快照在同一回调发布；旧监听者仍走既有操作边界。 */
        default void onReplacementOperation(ReplacementIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){onOperation("replacement",stage,detail,progress,active,failed);}
        /** 未取得原生操作所有权的排队请求，只结束自己的界面会话。 */
        default void onReplacementRequestFinished(ReplacementIdentity identity,String stage,String detail,boolean failed) {}
        default void onReplacementError(ReplacementIdentity identity,String error){onError(error);}
        /** 删除共用展示 DTO，但始终发布自己的不可变请求身份。 */
        default void onDeletionOperation(DeletionIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){onOperation("deletion",stage,detail,progress,active,failed);}
        default void onDeletionRequestFinished(DeletionIdentity identity,String stage,String detail,boolean failed) {}
        default void onDeletionError(DeletionIdentity identity,String error){onError(error);}
        default void onActivationOperation(ActivationIdentity identity,String stage,String detail,boolean active,boolean failed){onOperation("activation",stage,detail,null,active,failed);}
        default void onActivationRequestFinished(ActivationIdentity identity,String stage,String detail,boolean failed) {}
        default void onActivationError(ActivationIdentity identity,String error){onError(error);}
    }
    /** 本地身份不是线上的 nonce；一项任务（包括排队/前检结果）终身使用同一对象。 */
    public static final class ReplacementIdentity {
        public final long token,epoch;
        public final String id,version;
        public final boolean directInstall;
        private ReplacementIdentity(String id,String version,long epoch){this(id,version,epoch,false);}
        private ReplacementIdentity(String id,String version,long epoch,boolean directInstall){this.token=replacementTokens.incrementAndGet();this.id=id;this.version=version;this.epoch=epoch;this.directInstall=directInstall;}
    }
    /** 确认后、进入 actor 前的取消门；不能借此访问另一项原生操作。 */
    public static final class ReplacementRequest {
        public final ReplacementIdentity identity;
        private final AtomicBoolean submitted=new AtomicBoolean(),interruptRequested=new AtomicBoolean();
        public final boolean directInstall;
        private final boolean legacy;
        private final Object previousOwner;
        private ReplacementRequest(String id,String version,long epoch,boolean legacy,Object previousOwner){this(id,version,epoch,legacy,previousOwner,false);}
        private ReplacementRequest(String id,String version,long epoch,boolean legacy,Object previousOwner,boolean directInstall){identity=new ReplacementIdentity(id,version,epoch,directInstall);this.legacy=legacy;this.previousOwner=previousOwner;this.directInstall=directInstall;}
    }
    /** 删除没有用户取消门；一次确认只允许提交同一对象一次。 */
    public static final class DeletionIdentity {
        public final long token,epoch;
        public final String id,version;
        private DeletionIdentity(String id,String version,long epoch){this.token=replacementTokens.incrementAndGet();this.id=id;this.version=version;this.epoch=epoch;}
    }
    public static final class DeletionRequest {
        public final DeletionIdentity identity;
        private final AtomicBoolean submitted=new AtomicBoolean();
        private final Object previousOwner;
        private DeletionRequest(String id,String version,long epoch,Object previousOwner){identity=new DeletionIdentity(id,version,epoch);this.previousOwner=previousOwner;}
    }
    public static final class ActivationIdentity {
        public final long token,epoch;public final String id,version;
        private ActivationIdentity(String id,String version,long epoch){token=replacementTokens.incrementAndGet();this.id=id;this.version=version;this.epoch=epoch;}
    }
    public static final class ActivationRequest {
        public final ActivationIdentity identity;
        private final AtomicBoolean submitted=new AtomicBoolean();private final Object previousOwner;
        private ActivationRequest(String id,String version,long epoch,Object previousOwner){identity=new ActivationIdentity(id,version,epoch);this.previousOwner=previousOwner;}
    }
    /** 删除前置查询只回报创建它的调用者；回调不是全局选中行，也不是线上 nonce。 */
    public interface DeletionPreparationListener {
        void onPrepared(TlvView.Face face,long epoch);
        void onFailure(String code);
    }
    // 三种操作共用进程令牌计数器，旧结果不能碰巧成为另一种操作的关闭令牌。
    private static final AtomicLong replacementTokens=new AtomicLong(System.nanoTime());
    private volatile Object summaryOwner;
    private volatile ReplacementRequest shownReplacementRequest;
    private volatile DeletionRequest shownDeletionRequest;
    private volatile ActivationRequest shownActivationRequest;
    /** 仅返回已经一致发布的身份，界面不能读 actor 的可变 Replace。 */
    public ReplacementIdentity replacementIdentity(){ReplacementRequest r=shownReplacementRequest;return r==null?null:r.identity;}
    public ReplacementRequest currentReplacementRequest(){return shownReplacementRequest;}
    public ReplacementRequest newReplacementRequest(TlvView.Face face,String version,long epoch){return new ReplacementRequest(face==null?"":face.id,version,epoch,false,summaryOwner);}
    public ReplacementRequest newDirectInstallRequest(String id,String version,long epoch){if(id==null||version==null)throw new IllegalArgumentException("DIRECT_REQUEST_ID_VERSION_REQUIRED");return new ReplacementRequest(id,version,epoch,false,summaryOwner,true);}
     public DeletionIdentity deletionIdentity(){DeletionRequest r=shownDeletionRequest;return r==null?null:r.identity;}
    public DeletionRequest currentDeletionRequest(){return shownDeletionRequest;}
    public DeletionRequest newDeletionRequest(TlvView.Face face,long epoch){return new DeletionRequest(face==null?"":face.id,face==null?"":face.version,epoch,summaryOwner);}
    public ActivationIdentity activationIdentity(){ActivationRequest r=shownActivationRequest;return r==null?null:r.identity;}
    public ActivationRequest currentActivationRequest(){return shownActivationRequest;}
    public ActivationRequest newActivationRequest(TlvView.Face face,long epoch){return new ActivationRequest(face==null?"":face.id,face==null?"":face.version,epoch,summaryOwner);}
    // 替换列表有效期为五分钟；删除的最终确认使用独立三十秒有效期。
    private static final long REPLACEMENT_LIST_AGE=300000L,DELETE_LIST_AGE=30000L,POST_TIMEOUT=90000L,TOTAL_TIMEOUT=900000L,DELETE_OPERATION_TIMEOUT=15000L;
    private static final long REPLACEMENT_OPERATION_TIMEOUT=30000L;
    private static final long ACTIVATION_OPERATION_TIMEOUT=30000L,ACTIVATION_LIST_TIMEOUT=30000L,ACTIVATION_TOTAL_TIMEOUT=60000L;
    /** 只限制初始接受回执等待；已进入传输/报告/后置核验后仍使用原有期限。 */
    static boolean replacementResponseTimedOut(ReplacementState.Phase phase,long elapsed){
        return phase==ReplacementState.Phase.WAIT_OPERATION&&elapsed>=REPLACEMENT_OPERATION_TIMEOUT;
    }
    private static final int POST_QUERY_BUDGET=40;
    private final WatchConnection connection;
    private final SafeLog log;
    private Listener listener;
    private volatile boolean busy;
    private volatile List<TlvView.Face> latest=Collections.emptyList();
    private final Map<String,String> names=new HashMap<>();
    private long listAt,listEpoch,serial;
    private long parametersRequestedEpoch = -1;
    private Query query;
    private Op active,last;
    private final ArrayDeque<Op> history=new ArrayDeque<>();
    private final ArrayDeque<Activate> activationHistory=new ArrayDeque<>();
    private NamesRun namesRun;
    private PreDelete preDelete;
    private boolean precheckFailure;
    private volatile long reconnectEpoch=-1;
    public boolean reconnectRequired(long expectedEpoch){return reconnectEpoch==expectedEpoch;}
    private static final class ListObservation {
        final long at,epoch; final boolean usable;
        ListObservation(long at,long epoch,boolean usable){this.at=at;this.epoch=epoch;this.usable=usable;}
    }
    private volatile ListObservation observation=new ListObservation(0,0,false);
    public WatchfaceManager(WatchConnection connection,SafeLog log){this.connection=connection;this.log=log;}
    public void setListener(Listener listener){this.listener=listener;}
    public boolean busy(){return busy||preDelete!=null;}
    public List<TlvView.Face> faces(){return latest;}
    /** 界面读取已发布的不可变观察值，不直接访问串行执行器的可变查询状态。 */
    public boolean replacementListFresh(long expectedEpoch){
        ListObservation o=observation;long age=SystemClock.elapsedRealtime()-o.at;
        return o.usable&&o.at!=0&&o.epoch==expectedEpoch&&connection.epoch()==expectedEpoch&&connection.ready()&&age>=0&&age<=REPLACEMENT_LIST_AGE;
    }
    /** 删除确认仍使用独立三十秒观察值；不因共用展示会话扩大原协议前检窗口。 */
    public boolean deletionListFresh(long expectedEpoch){
        ListObservation o=observation;long age=SystemClock.elapsedRealtime()-o.at;
        return o.usable&&o.at!=0&&o.epoch==expectedEpoch&&connection.epoch()==expectedEpoch&&connection.ready()&&age>=0&&age<=DELETE_LIST_AGE;
    }
    private abstract static class Op {
        final String id,version; final long epoch,started=SystemClock.elapsedRealtime();long finished;int listQueries;
        Op(String i,String v,long e){id=i;version=v;epoch=e;}
        boolean matches(String i,String v){return id.equals(i)&&version.equals(v);}
    }
    private static class Replace extends Op {
        final ReplacementState state;final DirectInstallState directState;final HwtSource.Snapshot source;final ReplacementRequest request;final ConfirmationWait confirmationWait=new ConfirmationWait();FileTransfer file;long postAt;boolean fileResultSeen;
        Replace(TlvView.Face face,String version,HwtSource.Snapshot source,long epoch,ReplacementRequest request)throws IOException{
            super(face.id,version,epoch);this.request=request;this.source=source;
            state=request.directInstall?null:new ReplacementState(face.id,face.version,version);
            directState=request.directInstall?new DirectInstallState(face.id,version):null;
        }
        Replace(String id,String version,HwtSource.Snapshot source,long epoch,ReplacementRequest request)throws IOException{
            super(id,version,epoch);this.request=request;this.source=source;
            state=null;directState=new DirectInstallState(id,version);
        }
        boolean direct(){return directState!=null;}
    }
    private static final class Delete extends Op {
        final DeletionState state;final Set<String> otherIds;final DeletionRequest request;
        Delete(TlvView.Face f,List<TlvView.Face> all,long epoch,DeletionRequest request)throws IOException{
            super(f.id,f.version,epoch);state=new DeletionState(f.id,f.version,FacePolicy.current(f));this.request=request;
            Set<String> ids=new HashSet<>();for(TlvView.Face x:all)if(!f.id.equals(x.id))ids.add(x.id);otherIds=ids;
        }
    }
    private static final class Activate extends Op {
        final ActivationState state;final ActivationRequest request;long acceptedAt=-1;
        Activate(TlvView.Face f,long epoch,ActivationRequest request)throws IOException{super(f.id,f.version,epoch);state=new ActivationState(f.id,f.version);this.request=request;}
    }
    private static final class NamesRun {
        final long epoch;final List<TlvView.Face> sourceFaces;final NameQueryState state;final int language;
        NamesRun(long epoch,List<TlvView.Face> faces,Set<String> known,int language)throws IOException{this.epoch=epoch;sourceFaces=faces;state=new NameQueryState(faces,known);this.language=language;}
    }
    private static final class Query {
        final long serial,epoch;final Op op;final int generation;final PreDelete preparation;
        Query(long serial,long epoch,Op op,PreDelete preparation){this.serial=serial;this.epoch=epoch;this.op=op;this.preparation=preparation;generation=op instanceof Replace?(((Replace)op).direct()?((Replace)op).directState.postGeneration:((Replace)op).state.postGeneration):op instanceof Delete?((Delete)op).state.signalGeneration:op instanceof Activate?((Activate)op).state.signalGeneration:0;}
    }
    private static final class PreDelete {
        final String id,version;final long epoch;final TlvView.Face requested;final DeletionPreparationListener callback;boolean completed;
        PreDelete(String i,String v,long e,TlvView.Face requested,DeletionPreparationListener callback){id=i;version=v;epoch=e;this.requested=requested;this.callback=callback;}
    }
    private void preparationFailed(PreDelete p,String code,boolean publishError){
        if(p==null||p.completed)return;
        if(preDelete==p)preDelete=null;
        p.completed=true;
        if(p.callback!=null)p.callback.onFailure("DEVICE_FACE_LIST_INCOMPLETE_RECONNECT");
        if(publishError)error(code);
    }
    private void preparationReady(PreDelete p,TlvView.Face face){
        if(p.completed)return;p.completed=true;
        if(p.callback!=null)p.callback.onPrepared(face,p.epoch);
        else if(listener!=null)listener.onPreparedDeletion(face,p.epoch);
    }
    public void authenticated(long epoch){connection.execute(()->{
        if(epoch!=connection.epoch())return;
        if(active!=null){if(active.epoch==epoch)return;retire(active,"CONNECTION_REPLACED");}
        preparationFailed(preDelete,"DELETE_CONNECTION_CHANGED",false);
        cancelNames();summaryOwner=null;shownReplacementRequest=null;shownDeletionRequest=null;shownActivationRequest=null;reconnectEpoch=-1;latest=Collections.emptyList();names.clear();listAt=0;listEpoch=epoch;query=null;preDelete=null;busy=false;refreshActor();
    });}
    public void closed(long closedEpoch,String reason){connection.execute(()->{
        if(namesRun!=null&&namesRun.epoch==closedEpoch)cancelNames();
        if(query!=null&&query.epoch==closedEpoch)query=null;
        if(preDelete!=null&&preDelete.epoch==closedEpoch)preparationFailed(preDelete,"DELETE_CONNECTION_CHANGED",false);
        if(listEpoch==closedEpoch)listAt=0;
        if(active!=null&&active.epoch==closedEpoch)retire(active,"CONNECTION_CLOSED_"+reason);
        publishFaces();
    });}
    private void retire(Op old,String reason){
        if(old instanceof Replace){Replace r=(Replace)old;if(r.direct())r.directState.fail(reason,null);else r.state.fail(reason,null);if(r.file!=null)try{r.file.close();}catch(Exception ignored){}}
        else if(old instanceof Delete)((Delete)old).state.fail(reason,null);
        else((Activate)old).state.fail(reason,null);
        old.finished=SystemClock.elapsedRealtime();if(active==old){active=null;busy=false;}if(last==old)show(old);
        log.record("OLD_OPERATION_RETIRED","epoch_bound=true reason="+reason+" no_new_connection_failure=true");
    }
    public void refresh(){connection.post(()->{if(busy())error("WATCHFACE_OPERATION_BUSY");else if(reconnectRequired(connection.epoch()))error("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");else refreshActor();});}
    private void refreshActor(){try{
        if(!connection.ready())throw new Core.Fault("CONNECT_AND_AUTHENTICATE_FIRST");
        if(listener!=null){listener.onError("");if(precheckFailure){shownReplacementRequest=null;shownDeletionRequest=null;shownActivationRequest=null;listener.onOperation("","","",null,false,false);}}
        if(query!=null){log.record("REFRESH_MERGED","pending_query=true rows_retained=true");return;}
        // 刷新先使操作资格失效，保留已显示的列表和名称，直到收到完整的新列表。
        precheckFailure=false;listAt=0;
        // 屏幕/能力信息每个连接批次只读取一次，刷新表盘不重复探测设备参数。
        if(parametersRequestedEpoch != connection.epoch()) {
            parametersRequestedEpoch = connection.epoch();
            connection.sendControl(0x27,1,Wire.params());
        }
        requestList(null);
    }catch(IOException e){publishFaces();error(Core.code(e));}}
    private void publishFaces(){
        long own=connection.epoch(),age=SystemClock.elapsedRealtime()-listAt;
        boolean fresh=listAt!=0&&listEpoch==own&&age>=0&&age<=REPLACEMENT_LIST_AGE&&query==null&&connection.ready()&&!reconnectRequired(own);
        observation=new ListObservation(listAt,listEpoch,fresh);
        if(listener!=null){
            listener.onRefreshing(query!=null&&query.epoch==own,own);
            boolean sameSession=listEpoch==own;
            listener.onFaces(sameSession?latest:Collections.emptyList(),sameSession?Collections.unmodifiableMap(new HashMap<>(names)):Collections.emptyMap(),fresh,own,serial);
        }
    }
    private void expireObservation(List<TlvView.Face> faces,long at,long epoch){
        connection.delay(()->{if(latest==faces&&listAt==at&&listEpoch==epoch&&connection.epoch()==epoch){
            log.record("LIST_OBSERVATION_EXPIRED","replacement_limit_ms="+REPLACEMENT_LIST_AGE+" rows_retained=true no_operation_sent=true");publishFaces();
        }},REPLACEMENT_LIST_AGE+1L);
    }
    private void error(String code){log.record("WATCHFACE_ERROR","code="+code);if(listener!=null)listener.onError(code);}
    private void replacementRequestFinished(ReplacementRequest request,String stage,String code){
        boolean failed=!"CANCELLED".equals(stage);
        log.record(failed?"REPLACE_PRECHECK_FAILED":"REPLACE_QUEUED_CANCELLED","code="+code+" command_sent=false upload_sent=false reconnect_required=false");
        // 连接已变化、或另一个操作/删除已持有 actor 时，不覆盖其状态或错误。
        boolean ownsSummary=!busy()&&request.identity.epoch==connection.epoch()&&summaryOwner==request.previousOwner;
        if(ownsSummary){
            summaryOwner=request;shownReplacementRequest=request;shownDeletionRequest=null;shownActivationRequest=null;precheckFailure=failed;
            if(listener!=null){listener.onReplacementOperation(request.identity,stage,code,null,false,failed);listener.onReplacementError(request.identity,failed?code:"");}
        }else if(listener!=null)listener.onReplacementRequestFinished(request.identity,stage,code,failed);
        if(failed&&request.legacy&&!ownsSummary)error(code);
    }
    private void deletionRequestFinished(DeletionRequest request,String code){
        log.record("DELETE_PRECHECK_FAILED","code="+code+" command_sent=false upload_sent=false reconnect_required=false");
        // 拒绝只能取得仍属创建请求时的空闲摘要，绝不失败或覆盖另一个原生操作。
        boolean ownsSummary=!busy()&&request.identity.epoch==connection.epoch()&&summaryOwner==request.previousOwner;
        if(ownsSummary){
            summaryOwner=request;shownDeletionRequest=request;shownReplacementRequest=null;shownActivationRequest=null;precheckFailure=true;
            if(listener!=null){listener.onDeletionOperation(request.identity,"PRECHECK_FAILED",code,null,false,true);listener.onDeletionError(request.identity,code);}
        }else if(listener!=null)listener.onDeletionRequestFinished(request.identity,"PRECHECK_FAILED",code,true);
    }
    private void activationRequestFinished(ActivationRequest request,String code){
        log.record("ACTIVATE_PRECHECK_FAILED","code="+code+" command_sent=false file_budget=0");
        boolean ownsSummary=!busy()&&request.identity.epoch==connection.epoch()&&summaryOwner==request.previousOwner;
        if(ownsSummary){
            summaryOwner=request;shownActivationRequest=request;shownReplacementRequest=null;shownDeletionRequest=null;precheckFailure=true;
            if(listener!=null){listener.onActivationOperation(request.identity,"PRECHECK_FAILED",code,false,true);listener.onActivationError(request.identity,code);}
        }else if(listener!=null)listener.onActivationRequestFinished(request.identity,"PRECHECK_FAILED",code,true);
    }
    private void validate(TlvView.Face face,long epoch,long maxAge,boolean strictObservation)throws IOException{
        validate(face,epoch,maxAge,strictObservation,false);
    }
    /** activation=true 只供 beginActivation；覆盖、删除与删除前检仍使用 eligible。 */
    private void validate(TlvView.Face face,long epoch,long maxAge,boolean strictObservation,boolean activation)throws IOException{
        if(!connection.ready()||connection.epoch()!=epoch)throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
        if(reconnectRequired(epoch))throw new Core.Fault("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");
        if(!(activation?FacePolicy.canActivate(face):FacePolicy.eligible(face)))throw new Core.Fault(activation?"SELECT_EXISTING_ACTIVATABLE_FACE":"SELECT_EXISTING_ORDINARY_FACE");
        long age=SystemClock.elapsedRealtime()-listAt;
        if(listAt==0||listEpoch!=epoch||age<0||age>maxAge||query!=null)throw new Core.Fault("FRESH_DEVICE_LIST_REQUIRED");
        int count=0;
        for(TlvView.Face x:latest)if(face.id.equals(x.id)){
            count++;
            // 普通表盘的正在使用位变化（4 与 5 互换）不改变旧替换入口的目标身份。
            if(!face.version.equals(x.version)||!(activation?FacePolicy.canActivate(x):FacePolicy.eligible(x))||(strictObservation&&(!Objects.equals(face.type,x.type)||!Objects.equals(face.expandType,x.expandType))))throw new Core.Fault("SELECTED_FACE_CHANGED");
        }
        if(count!=1)throw new Core.Fault("SELECTED_ID_NOT_UNIQUE");
    }
    /** 保留旧四参数入口；新界面先分配身份，再用带取消门的重载入队。 */
    public void beginReplacement(TlvView.Face face,String version,HwtSource.Snapshot source,long epoch){beginReplacement(face,version,source,epoch,new ReplacementRequest(face==null?"":face.id,version,epoch,true,summaryOwner));}
    public void beginReplacement(TlvView.Face face,String version,HwtSource.Snapshot source,long epoch,ReplacementRequest request){
        Objects.requireNonNull(request);
        if(!request.submitted.compareAndSet(false,true))return;
        connection.post(()->{
            if(request.directInstall){replacementRequestFinished(request,"PRECHECK_FAILED","DIRECT_REQUEST_ROUTE_MISMATCH");return;}
            if(request.interruptRequested.get()){replacementRequestFinished(request,"CANCELLED","USER_CANCELLED_BEFORE_SEND");return;}
            if(busy()){replacementRequestFinished(request,"PRECHECK_FAILED","WATCHFACE_OPERATION_BUSY");return;}
            if(reconnectRequired(connection.epoch())){replacementRequestFinished(request,"PRECHECK_FAILED","RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");return;}
            log.record("REPLACE_CONFIRM_ACCEPTED","id="+(face==null?"absent":Core.clean(face.id))+" baseline="+(face==null?"absent":Core.clean(face.version))+" version="+Core.clean(version)+" list_age_ms="+(SystemClock.elapsedRealtime()-listAt)+" replacement_limit_ms="+REPLACEMENT_LIST_AGE);
            try{
                if(face==null||request.identity.epoch!=epoch||!Objects.equals(request.identity.id,face.id)||!Objects.equals(request.identity.version,version))throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
                validate(face,epoch,REPLACEMENT_LIST_AGE,false);
                if(!Objects.equals(version,VersionPolicy.next(face.id,face.version,latest)))throw new Core.Fault("VERSION_CHANGED");
                // 确认前已在文件线程校验并冻结，串行连接执行器不重复计算全文件摘要。
                if(source==null||source.mode!=HwtSource.Mode.BIN||source.source==null||!source.source.matchesPayload(source)||source.file==null||!source.file.isFile()||source.file.length()!=source.size)throw new Core.Fault("PAYLOAD_SNAPSHOT_UNAVAILABLE");
                if(request.interruptRequested.get()){replacementRequestFinished(request,"CANCELLED","USER_CANCELLED_BEFORE_SEND");return;}
                Replace r=new Replace(face,version,source,epoch,request);start(r);
                log.record("REPLACE_SEND_OPERATION","id="+r.id+" version="+r.version+" op=1 source_bytes="+source.size+" source_sha256="+source.hash+" pre_operation_budget=1 upload_budget=1");
                connection.sendControl(0x27,3,Wire.operation(r.id,r.version));show(r);timer(r);
            }catch(Exception e){
                String code=e instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED":Core.code(e);
                if(active instanceof Replace&&((Replace)active).request==request)failActive(code,null);
                else replacementRequestFinished(request,"PRECHECK_FAILED",code);
            }
        });
    }
    /** Starts one parsed-content install with a new ID, without an existing-resource replacement preflight. */
    public void beginDirectInstall(HwtSource.Snapshot source,long epoch,ReplacementRequest request){
        Objects.requireNonNull(request);
        if(!request.submitted.compareAndSet(false,true))return;
        connection.post(()->{
            if(!request.directInstall){replacementRequestFinished(request,"PRECHECK_FAILED","DIRECT_REQUEST_ROUTE_MISMATCH");return;}
            if(request.interruptRequested.get()){replacementRequestFinished(request,"CANCELLED","USER_CANCELLED_BEFORE_SEND");return;}
            if(busy()){replacementRequestFinished(request,"PRECHECK_FAILED","WATCHFACE_OPERATION_BUSY");return;}
            if(reconnectRequired(connection.epoch())){replacementRequestFinished(request,"PRECHECK_FAILED","RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");return;}
            try{
                ReplacementIdentity identity=request.identity;
                if(identity.epoch!=epoch||epoch!=connection.epoch()||!connection.ready())throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
                long listAge=SystemClock.elapsedRealtime()-listAt;
                if(listAt==0||listEpoch!=epoch||listAge<0||listAge>REPLACEMENT_LIST_AGE||query!=null)throw new Core.Fault("FRESH_DEVICE_LIST_REQUIRED");
                if(!identity.id.matches("[0-9]{9}")||!"1.0.0".equals(identity.version))throw new Core.Fault("DIRECT_HWT_FILENAME_CONTRACT_INVALID");
                if(source==null||source.file==null||!source.file.isFile()||source.file.length()!=source.size||
                        source.source==null||!source.source.matchesPayload(source))throw new Core.Fault("DIRECT_PAYLOAD_SNAPSHOT_REQUIRED");
                for(TlvView.Face f:latest)if(identity.id.replaceFirst("^0+(?!$)","").equals(f.id.replaceFirst("^0+(?!$)","")))throw new Core.Fault("DIRECT_HWT_ID_ALREADY_INSTALLED");
                Replace op=new Replace(identity.id,identity.version,source,epoch,request);start(op);
                op.file=new FileTransfer(op.id,op.version,source,FileTransfer.Route.DIRECT_INSTALL);
                connection.sendFile(2,op.file.metadataBody(),true);
                log.record("DIRECT_HWT_UPLOAD_STARTED","id="+op.id+" version="+op.version+" bytes="+source.size+" sha256="+source.hash+
                        " source_bytes="+source.source.original.length()+" source_sha256="+source.source.sourceHash+
                        " payload="+payloadKind(source)+" repacked="+(source.mode==HwtSource.Mode.CONTAINER_NO_BIN)+
                        " container_members="+source.source.allModeMembers+" outer_hwt_sent=false file_type=1 upload_budget=1");
                show(op);timer(op);
            }catch(Exception e){String code=e instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED":Core.code(e);if(active instanceof Replace&&((Replace)active).request==request)failActive(code,null);else replacementRequestFinished(request,"PRECHECK_FAILED",code);}
        });
    }
    /** 即时置位仅属于此请求的取消门；真正原生停止必须在 actor 再验对象、代次与终态。 */
    public void cancelReplacement(ReplacementRequest request){
        if(request==null)return;request.interruptRequested.set(true);
        connection.post(()->{
            Op op=active;
            if(!(op instanceof Replace))return;
            Replace r=(Replace)op;ReplacementIdentity identity=request.identity;
            if(r.request!=request||r.request.identity!=identity||r.epoch!=identity.epoch||connection.epoch()!=identity.epoch||terminal(r))return;
            if(r.direct())r.directState.cancel();else r.state.cancel();finish(r);
        });
    }
    /** 旧调用者保留原监听路径；逐卡调用者必须使用携带请求行与私有回调的重载。 */
    public void prepareDeletion(String id,String version,long epoch){prepareDeletion(new PreDelete(id,version,epoch,null,null));}
    public void prepareDeletion(TlvView.Face face,long epoch,DeletionPreparationListener callback){
        Objects.requireNonNull(callback);
        prepareDeletion(new PreDelete(face==null?"":face.id,face==null?"":face.version,epoch,face,callback));
    }
    private void prepareDeletion(PreDelete p){connection.post(()->{
        if(busy()||query!=null){preparationFailed(p,"WAIT_FOR_CURRENT_OPERATION_OR_QUERY",p.callback==null);return;}
        try{
            if(!connection.ready()||connection.epoch()!=p.epoch)throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
            if(reconnectRequired(p.epoch))throw new Core.Fault("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");
            if(p.callback!=null)validate(p.requested,p.epoch,REPLACEMENT_LIST_AGE,true);
            preDelete=p;listAt=0;requestList(null);
        }catch(Exception e){
            String code=e instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED":Core.code(e);
            Query failedQuery=query;
            if(failedQuery!=null&&failedQuery.preparation==p){query=null;listAt=0;if(preDelete==p)preDelete=null;publishFaces();}
            preparationFailed(p,code,p.callback==null);
        }
    });}
    /** 旧删除入口仍走相同的对象/代次门，不提供删除中断或文件路径。 */
    public void beginDeletion(TlvView.Face face,long epoch){beginDeletion(face,epoch,newDeletionRequest(face,epoch));}
    public void beginDeletion(TlvView.Face face,long epoch,DeletionRequest request){
        Objects.requireNonNull(request);
        if(!request.submitted.compareAndSet(false,true))return;
        connection.post(()->{
            if(busy()){deletionRequestFinished(request,"WATCHFACE_OPERATION_BUSY");return;}
            if(reconnectRequired(connection.epoch())){deletionRequestFinished(request,"RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");return;}
            try{
                DeletionIdentity identity=request.identity;
                if(face==null||identity.epoch!=epoch||!Objects.equals(identity.id,face.id)||!Objects.equals(identity.version,face.version))throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
                validate(face,epoch,DELETE_LIST_AGE,true);
                for(Op old:history)if(old instanceof Delete&&old.epoch==epoch&&old.matches(face.id,face.version)&&old.finished!=0&&SystemClock.elapsedRealtime()-old.finished<=POST_TIMEOUT)throw new Core.Fault("RECENT_SAME_DELETE_WAIT_OR_RECONNECT");
                Delete d=new Delete(face,latest,epoch,request);start(d);connection.sendControl(0x27,3,Wire.deletion(d.id,d.version));show(d);timer(d);
            }catch(Exception e){
                String code=e instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED":Core.code(e);
                Op op=active;
                if(op instanceof Delete&&((Delete)op).request==request&&op.epoch==request.identity.epoch)failActive(code,null);
                else deletionRequestFinished(request,code);
            }
        });
    }
    /** 启用仅使用实际已有 ID 和原版本，一次 op1；没有文件发送或版本候选。 */
    public void beginActivation(TlvView.Face face,long epoch,ActivationRequest request){
        Objects.requireNonNull(request);if(!request.submitted.compareAndSet(false,true))return;
        connection.post(()->{
            if(busy()){activationRequestFinished(request,"WATCHFACE_OPERATION_BUSY");return;}
            if(reconnectRequired(connection.epoch())){activationRequestFinished(request,"RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");return;}
            try{
                ActivationIdentity identity=request.identity;
                if(face==null||identity.epoch!=epoch||!Objects.equals(identity.id,face.id)||!Objects.equals(identity.version,face.version))throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
                validate(face,epoch,REPLACEMENT_LIST_AGE,true,true);
                Activate a=new Activate(face,epoch,request);start(a);
                log.record("ACTIVATE_SEND_OPERATION","id="+a.id+" version="+a.version+" op=1 original_version=true file_budget=0");
                connection.sendControl(0x27,3,Wire.operation(a.id,a.version));show(a);timer(a);
            }catch(Exception e){
                String code=e instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED":Core.code(e);
                Op op=active;if(op instanceof Activate&&((Activate)op).request==request)failActive(code,null);else activationRequestFinished(request,code);
            }
        });
    }
    private static Object owner(Op op){return op instanceof Replace?((Replace)op).request:op instanceof Delete?((Delete)op).request:((Activate)op).request;}
    private void start(Op op){
        precheckFailure=false;active=op;last=op;busy=true;summaryOwner=owner(op);
        shownReplacementRequest=op instanceof Replace?((Replace)op).request:null;
        shownDeletionRequest=op instanceof Delete?((Delete)op).request:null;
        shownActivationRequest=op instanceof Activate?((Activate)op).request:null;
        cancelNames();
        if(listener!=null){if(op instanceof Replace)listener.onReplacementError(((Replace)op).request.identity,"");else if(op instanceof Delete)listener.onDeletionError(((Delete)op).request.identity,"");else listener.onActivationError(((Activate)op).request.identity,"");}
        if(op instanceof Activate){activationHistory.addFirst((Activate)op);while(activationHistory.size()>4)activationHistory.removeLast();}
        else{history.addFirst(op);while(history.size()>4)history.removeLast();}
        log.record(op instanceof Delete?"DELETE_START":op instanceof Activate?"ACTIVATE_START":"REPLACE_START","id="+op.id+" version="+op.version+" file_budget="+(op instanceof Replace?1:0)+" auth08_09_not_fabricated=true");
    }
    private void requestList(Op op)throws IOException{
        if(op!=null&&op.epoch!=connection.epoch())throw new Core.Fault("OPERATION_EPOCH_CHANGED");
        if(query!=null){log.record("LIST_QUERY_MERGED","pending_generation="+query.generation);return;}
        if(op!=null&&++op.listQueries>POST_QUERY_BUDGET){
            log.record("LIST_QUERY_BUDGET_EXHAUSTED","id="+op.id+" version="+op.version+" query_budget="+POST_QUERY_BUDGET+" no_more_query=true");
            if(op instanceof Delete)throw new Core.Fault("DELETE_LIST_QUERY_BUDGET_EXHAUSTED");
            if(op instanceof Activate)throw new Core.Fault("ACTIVATION_LIST_QUERY_BUDGET_EXHAUSTED");
            return; // 等待既有的后置或总超时，不自行推断最终结果。
        }
        Query q=new Query(++serial,connection.epoch(),op,op==null?preDelete:null);query=q;publishFaces();
        try{connection.sendControl(0x27,2,Wire.list(2));}catch(IOException e){if(query==q)query=null;listAt=0;publishFaces();throw e;}
        log.record("LIST_QUERY","serial="+q.serial+" generation="+q.generation+" local_not_wire_nonce=true");
        connection.delay(()->{if(query==q&&q.epoch==connection.epoch()){
            query=null;listAt=0;if(active==op&&op!=null)failActive("LIST_QUERY_TIMEOUT",null);
            else{preparationFailed(q.preparation,"LIST_QUERY_TIMEOUT_RECONNECT",false);error("LIST_QUERY_TIMEOUT_RECONNECT");connection.protocolFailure("LIST_QUERY_TIMEOUT");}publishFaces();
        }},15000);
    }
    public void packet(long epoch,int service,int command,byte[] body,boolean encrypted){
        if(epoch!=connection.epoch()||!connection.ready())return;Op op=active;if(op!=null&&op.epoch!=epoch)return;boolean owned=false;List<TlvView.Part> observedFields=null;String fileStage=null;
        try{
            if(service==0x28){
                if(!(op instanceof Replace)||command<2||command>8)return;Replace r=(Replace)op;if(r.file==null)return;
                if(command==6&&!r.direct()){log.record("RAW_FILE_DATA_OBSERVED","bytes="+body.length+" no_ack_or_completion_inference=true");return;}owned=true;fileStage=r.file.stateLabel();
                if(command==2)log.record("FILE_INFO_RESPONSE","stage="+fileStage+" bytes="+(body==null?0:body.length)+" "+r.file.infoReplyObservation(body));
                for(FileTransfer.Outgoing out:r.file.onPacket(command,body,true))connection.sendFile(out.command,out.body(),out.encrypt);
                if(command==2&&r.direct()&&r.file.existingResource()){
                    log.record("DIRECT_METADATA_EXISTS","skip_data_upload=true reference=wrist_native one_post_only=true");
                    startConfirmationWait(r);actDirect(r,r.directState.existingResource());
                }
                if(command==3){List<TlvView.Part> hashFields=TlvView.decode(body);log.record("FILE_HASH_REQUEST","file_id="+r.file.fileId()+" check_mode="+(r.direct()?"observed_not_gated":Wire.number(hashFields,2))+" full_hash_for_wrist_direct="+r.direct());}
                if(command==7&&r.file.resultObserved()){
                    r.fileResultSeen=true;
                    Integer raw=r.file.peerResultRaw();String failure=r.file.completionFailure();
                    log.record("FILE_RESULT_OBSERVED","file_id="+r.file.fileId()+" raw="+raw+" completion_failure="+failure+" ack_dispatched_first=true transfer_only=true");
                    if(r.direct()){
                        if(failure!=null)throw new Core.Fault(failure);
                        startConfirmationWait(r);actDirect(r,r.directState.nativeResult(raw));
                    }else if(raw!=null&&raw==0){r.state.nativeResult(0);}
                    else if(failure!=null)throw new Core.Fault(failure);
                    else if(raw!=null){startConfirmationWait(r);r.state.nativeResult(raw);}
                }
                show(r);finish(r);return;
            }
            if(service!=0x27||(command!=1&&command!=2&&command!=3&&command!=5&&command!=6))return;
            if(command==2)owned=query!=null&&query.epoch==epoch&&query.op==op&&op!=null;
            List<TlvView.Part> fields=TlvView.decode(body);observedFields=fields;
            if(command==1){Long w=Wire.number(fields,2),h=Wire.number(fields,3),cap=Wire.number(fields,0x11);if(listener!=null)listener.onParameters(w==null?null:w.intValue(),h==null?null:h.intValue(),cap);return;}
            if(command==2){
                Query pendingQuery=query;
                log.record("LIST_RESPONSE_OBSERVED","bytes="+(body==null?0:body.length)+" tags="+TlvView.tags(fields)+
                        " owned_query="+(pendingQuery!=null&&pendingQuery.epoch==epoch));
                handleList(fields,epoch);return;
            }
            if(command==6){handleNames(WireNames.parseResponse(fields),epoch,body.length);return;}
            String id=Wire.text(fields,1),version=Wire.text(fields,2);owned=op!=null&&op.matches(id,version);
            boolean directReply=op instanceof Replace&&((Replace)op).direct();
            if(directReply&&command==3&&((Replace)op).directState.postIssued&&(id==null||id.equals(op.id))&&(version==null||version.equals(op.version)))owned=true;
            Long error=directReply?wristOperationError(fields):Wire.number(fields,0x7f);
            Long value=directReply?(command==3?directNeedObservation(fields):wristReportType(fields)):Wire.number(fields,command==3?4:3);
            if(!owned){late(id,version,command,error,value,fields,epoch);return;}
            if(command==5&&!directReply&&value!=null&&value>Integer.MAX_VALUE)throw new Core.Fault("REPORT_VALUE_OVERFLOW");
            if(command==5&&(directReply||error==null||error==100000))connection.sendControl(0x27,5,concat(Core.tlv(1,id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Core.tlv(2,version.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Core.tlv(0x7f,num(100000,4))));
            if(op instanceof Replace){
                Replace r=(Replace)op;
                if(r.direct()){
                    if(command==3){
                        Long echo=Wire.number(fields,3);
                        log.record("DIRECT_OPERATION_RESPONSE","bytes="+(body==null?0:body.length)+" tags="+TlvView.tags(fields)+
                                " echo="+echo+" error="+error+" need="+value+" post_issued="+r.directState.postIssued+
                                " missing_error_uses_official_zero="+(error==null));
                        if(echo!=null&&echo!=1)return;
                    }
                    DirectInstallState.Action action=command==3?r.directState.operation(error,value):r.directState.report(value==null?null:value.intValue(),error);
                    actDirect(r,action);
                }else{
                    ReplacementState.Action action=command==3?r.state.operation(error,value):r.state.report(value==null?null:value.intValue(),error);
                    actReplace(r,action);
                }
                log.record(command==3?"OPERATION_RESPONSE":"WATCH_REPORT","id="+id+" version="+version+" error="+error+" value="+value+" "+(r.direct()?r.directState.summary():r.state.summary()));
                show(r);finish(r);
            }else if(op instanceof Delete){
                Delete d=(Delete)op;DeletionState.Action action;
                if(command==3){Long echo=Wire.number(fields,3);if(echo!=null&&echo!=2)return;action=d.state.operation(error,value,echo);}
                else action=d.state.report(value==null?null:value.intValue(),error);
                if(action==DeletionState.Action.QUERY)requestList(d);show(d);finish(d);
            }else{
                Activate a=(Activate)op;ActivationState.Action action;
                if(command==3)action=a.state.operation(error,value,Wire.number(fields,3));
                else action=a.state.report(value==null?null:value.intValue(),error);
                log.record(command==3?"ACTIVATE_RESPONSE":"ACTIVATE_REPORT","id="+a.id+" version="+a.version+" error="+error+" value="+value+" "+a.state.summary());
                if(action==ActivationState.Action.QUERY){if(a.acceptedAt<0)a.acceptedAt=SystemClock.elapsedRealtime();requestList(a);}
                show(a);finish(a);
            }
        }catch(IOException e){
            if(service==0x28)log.record("FILE_TRANSFER_ERROR","command="+command+" stage="+fileStage+" code="+Core.code(e)+" bytes="+(body==null?0:body.length)+" owned="+owned+" no_retry=true");
            if(service==0x27&&command==6){
                // 名称仅用于显示；格式/设备错误不清列表、不终止当前操作，也不冒充替换失败。
                log.record("NAMES_UNAVAILABLE","code="+Core.code(e)+" bytes="+(body==null?0:body.length)+" tags="+(observedFields==null?"unparsed":TlvView.tags(observedFields)));
                NamesRun run=namesRun;if(run!=null&&run.epoch==epoch&&run.sourceFaces==latest){run.state.failCurrent();advanceNames(run);}
                return;
            }
            if(owned&&active==op)failActive(Core.code(e),null);else{
                error(Core.code(e));if(service==0x27&&command==2&&query!=null&&query.epoch==epoch){Query failedQuery=query;query=null;preparationFailed(failedQuery.preparation,Core.code(e),false);listAt=0;publishFaces();}
            }
        }
    }
    private void handleList(List<TlvView.Part> fields,long epoch)throws IOException{
        Query q=query;if(q==null||q.epoch!=epoch)return;Long err=Wire.number(fields,0x7f);
        if(err!=null&&err!=0&&err!=100000)throw new Core.Fault("DEVICE_LIST_ERROR");
        Wire.FaceListObservation parsed=Wire.observeFaces(fields);List<TlvView.Face> faces=parsed.faces;
        cancelNames();query=null;latest=faces;listAt=SystemClock.elapsedRealtime();listEpoch=epoch;
        Set<String> listedKeys=new HashSet<>();for(TlvView.Face f:faces)listedKeys.add(f.id+"_"+f.version);
        names.keySet().retainAll(listedKeys);
        publishFaces();expireObservation(faces,listAt,epoch);
        log.record("RESOURCE_LIST","entries="+faces.size()+" serial="+q.serial+" generation="+q.generation+
                " complete="+parsed.complete()+" incomplete_entries="+parsed.incompleteEntries.size());
        for(TlvView.Face f:faces)log.record("LIST_ENTRY","id="+f.id+" version="+f.version+" type="+f.type+" expand="+f.expandType);
        if(!parsed.complete()){
            // 官方两条客户端路径都容忍单条缺字段：腕连跳过空 ID、缺版本按 ID 索引；Health 逐标签赋值不设必需门。
            // 因此缺失行只被排除在操作目标之外，不再让整包列表失效、也不再反复弹错误提示。
            for(Wire.IncompleteFace entry:parsed.incompleteEntries)log.record("LIST_ENTRY_INCOMPLETE",entry.summary()+" excluded_from_targets=true no_guessed_key=true");
            log.record("LIST_INCOMPLETE","valid_entries="+faces.size()+" incomplete_entries="+parsed.incompleteEntries.size()+
                    " known_rows_usable=true isolated_entries_never_targets=true connection_preserved=true");
        }
        observeCompletedDirectList(parsed,epoch);
        if(q.preparation!=null&&preDelete==q.preparation){
            PreDelete p=q.preparation;preDelete=null;
            try{
                if(p.epoch!=epoch)throw new Core.Fault("DELETE_CONNECTION_CHANGED");
                TlvView.Face requested=null;int count=0;for(TlvView.Face f:faces)if(p.id.equals(f.id)){requested=f;count++;}
                if(count!=1||requested==null||!p.version.equals(requested.version)||!FacePolicy.eligible(requested)||
                    (p.requested!=null&&(!Objects.equals(p.requested.type,requested.type)||!Objects.equals(p.requested.expandType,requested.expandType))))throw new Core.Fault("DELETE_PREPARATION_CHANGED");
                preparationReady(p,requested);
            }catch(IOException e){preparationFailed(p,Core.code(e),false);throw e;}
        }
        if(q.op!=null&&q.op==active){Op op=q.op;if(op instanceof Replace){
            Replace r=(Replace)op;if(r.direct())r.directState.list(faces,q.generation,true);else r.state.list(faces,q.generation,true);show(r);finish(r);
            boolean postObserved=r.direct()?r.directState.postObserved:r.state.postObserved;
            if(active==r&&postObserved)connection.delay(()->{if(active==r)try{requestList(r);}catch(IOException e){failActive(Core.code(e),null);}},q.generation<(r.direct()?r.directState.postGeneration:r.state.postGeneration)?1:1500);
        }else if(op instanceof Delete){
            Delete d=(Delete)op;Set<String> ids=new HashSet<>();for(TlvView.Face f:faces)ids.add(f.id);
            if(ids.containsAll(d.otherIds))d.state.list(faces,q.generation,true,q.serial);else{d.state.consecutiveAbsences=0;d.state.reason="DELETE_NON_TARGET_LIST_INCOMPLETE";}
            show(d);finish(d);
            if(active==d)connection.delay(()->{if(active==d)try{requestList(d);}catch(IOException e){failActive(Core.code(e),null);}},q.generation<d.state.signalGeneration?1:d.state.consecutiveAbsences==1?500:1500);
        }else{
            Activate a=(Activate)op;a.state.list(faces,q.generation,true,q.serial);show(a);finish(a);
            if(active==a)connection.delay(()->{if(active==a)try{requestList(a);}catch(IOException e){failActive(Core.code(e),null);}},1500);
        }}
        if(active==null)requestNames();
    }
    private void observeCompletedDirectList(Wire.FaceListObservation parsed,long epoch){
        if(!(last instanceof Replace))return;Replace r=(Replace)last;
        if(!r.direct()||r.finished==0||r.directState.phase!=DirectInstallState.Phase.COMPLETED)return;
        if(r.epoch!=epoch){log.record("DIRECT_LIST_OBSERVATION_SKIPPED","reason=epoch_changed no_cross_connection_verdict=true");return;}
        int exact=0,idOnly=0,incompleteId=0;boolean current=false;
        for(TlvView.Face f:parsed.faces)if(r.id.equals(f.id)){idOnly++;if(r.version.equals(f.version)){exact++;current|=FacePolicy.current(f);}}
        for(Wire.IncompleteFace f:parsed.incompleteEntries)if(r.id.equals(f.observedId))incompleteId++;
        log.record("DIRECT_LIST_OBSERVATION","id="+r.id+" version="+r.version+" complete="+parsed.complete()+" exact_rows="+exact+" complete_id_rows="+idOnly+" incomplete_id_rows="+incompleteId+" current_bit_observed="+(exact==1&&current)+" snapshot_only=true wire_nonce_verified=false application_unverified=true no_query_issued=true no_reupload=true");
    }
    private static String payloadKind(HwtSource.Snapshot source){
        return source.mode==HwtSource.Mode.CONTAINER?"watchface_container":
                source.mode==HwtSource.Mode.CONTAINER_NO_BIN?"watchface_container_without_bin":"parsed_bin";
    }
    private void cancelNames(){NamesRun run=namesRun;namesRun=null;if(run!=null)run.state.cancel();}
    private void requestNames(){try{
        if(latest.isEmpty()||!connection.ready()||!connection.capabilities().hasCommand(0x27,6))return;
        if(namesRun==null||namesRun.epoch!=connection.epoch()||namesRun.sourceFaces!=latest){
            cancelNames();namesRun=new NamesRun(connection.epoch(),latest,names.keySet(),"zh".equals(Locale.getDefault().getLanguage())?2:1);
        }
        advanceNames(namesRun);
    }catch(IOException e){log.record("NAMES_UNAVAILABLE","code="+Core.code(e));}}
    private void advanceNames(NamesRun run){
        if(run==null||namesRun!=run||run.epoch!=connection.epoch()||run.sourceFaces!=latest||!connection.ready()||busy()||query!=null)return;
        NameQueryState.Batch batch=run.state.next(SystemClock.elapsedRealtime());if(batch==null)return;
        try{
            connection.sendControl(0x27,6,WireNames.request(batch.faces,run.language));
            log.record("NAMES_BATCH_QUERY","serial="+batch.serial+" ids="+batch.ids.size()+" list_entries="+run.sourceFaces.size()+" local_not_wire_nonce=true");
            connection.delay(()->{
                if(namesRun==run&&run.epoch==connection.epoch()&&run.sourceFaces==latest&&run.state.current()==batch&&run.state.expire(SystemClock.elapsedRealtime())){
                    // 整条查询完全没有回包才走到这里；有回包（哪怕只含部分名字）已在 accept 里结束批次。
                    log.record("NAMES_BATCH_TIMEOUT","serial="+batch.serial+" ids="+batch.ids.size()+" names_retained=true no_requery=true");
                    advanceNames(run);
                }
            },NameQueryState.REPLY_TIMEOUT_MS);
        }catch(IOException e){
            log.record("NAMES_UNAVAILABLE","code="+Core.code(e)+" serial="+batch.serial);run.state.failCurrent();advanceNames(run);
        }
    }
    private void handleNames(WireNames.Response response,long epoch,int bytes){
        NamesRun run=namesRun;
        if(run==null||run.epoch!=epoch||run.sourceFaces!=latest){log.record("NAMES_REPLY_IGNORED","bytes="+bytes+" current_owner=false");return;}
        Map<String,String> found=run.state.accept(response);int added=0;
        for(TlvView.Face face:latest){String name=found.get(face.id);if(name!=null&&names.putIfAbsent(face.id+"_"+face.version,Core.clean(name))==null)added++;}
        log.record("NAMES_BATCH_RESPONSE","received_ids="+response.ids.size()+" matched_names="+added+" unanswered_ids="+run.state.unanswered()+" malformed_names_skipped="+response.malformedNameCount+" bytes="+bytes+" names_only=true no_requery=true");
        publishFaces();advanceNames(run);
    }
    /** Wrist n.f: absent or shorter than four bytes means default error 0. */
    private static Long wristOperationError(List<TlvView.Part> fields)throws IOException{
        TlvView.Part p=Wire.unique(fields,0x7f);
        return p==null||p.value.length<4?null:Long.valueOf(wristInt(p.value));
    }
    /** Wrist n.d(3,0): absent/empty means 0, 1..3 bytes unsigned, >=4 signed first four. */
    private static Long wristReportType(List<TlvView.Part> fields)throws IOException{
        TlvView.Part p=Wire.unique(fields,3);
        return Long.valueOf(p==null||p.value.length==0?0:wristInt(p.value));
    }
    private static long wristInt(byte[] bytes){
        int value=0;for(int i=0;i<Math.min(bytes.length,4);i++)value=(value<<8)|(bytes[i]&255);
        return value;
    }
    private static Long directNeedObservation(List<TlvView.Part> fields)throws IOException{
        TlvView.Part p=Wire.unique(fields,4);
        return p==null||p.value.length==0||p.value.length>8?null:Core.number(p.value);
    }
    private void startConfirmationWait(Replace r){
        if(r.confirmationWait.started())return;
        r.confirmationWait.start(SystemClock.elapsedRealtime());
        log.record("CONFIRMATION_WAIT_STARTED","kind="+kind(r)+" timeout_ms="+ConfirmationWait.TIMEOUT_MS+" report_and_application_shared_deadline=true no_reset_on_reply=true");
    }
    private void actDirect(Replace r,DirectInstallState.Action action)throws IOException{
        if(active!=r||r.epoch!=connection.epoch())return;
        if(action==DirectInstallState.Action.ACTIVATE){startConfirmationWait(r);r.postAt=SystemClock.elapsedRealtime();connection.sendControl(0x27,3,Wire.operation(r.id,r.version));}
        else if(action==DirectInstallState.Action.QUERY)requestList(r);
    }
    private void actReplace(Replace r,ReplacementState.Action action)throws IOException{
        if(active!=r||r.epoch!=connection.epoch())return;
        if(action==ReplacementState.Action.UPLOAD){
            if(r.file!=null)throw new Core.Fault("UPLOAD_BUDGET_EXHAUSTED");r.file=new FileTransfer(r.id,r.version,r.source);
            log.record("UPLOAD_START","id="+r.id+" version="+r.version+" bytes="+r.source.size+" sha256="+r.source.hash+" file_type=1 upload_budget=1");
            connection.sendFile(2,r.file.metadataBody(),true);
        }else if(action==ReplacementState.Action.ACTIVATE){
            startConfirmationWait(r);r.postAt=SystemClock.elapsedRealtime();log.record("POST_UPLOAD_ACTIVATION","id="+r.id+" version="+r.version+" report_two_drives_post=true file_result_is_separate=true post_timeout_ms="+ConfirmationWait.TIMEOUT_MS);
            connection.sendControl(0x27,3,Wire.operation(r.id,r.version));
        }else if(action==ReplacementState.Action.QUERY)requestList(r);
    }
    private static String kind(Op op){return op instanceof Replace?(((Replace)op).direct()?"direct_install":"replacement"):op instanceof Delete?"deletion":"activation";}
    private static String phase(Op op){return op instanceof Replace?(((Replace)op).direct()?((Replace)op).directState.phase.name():((Replace)op).state.phase.name()):op instanceof Delete?((Delete)op).state.phase.name():((Activate)op).state.phase.name();}
    private static boolean terminal(Op op){return op instanceof Replace?(((Replace)op).direct()?((Replace)op).directState.terminal():((Replace)op).state.terminal()):op instanceof Delete?((Delete)op).state.terminal():((Activate)op).state.terminal();}
    private void timer(Op op){connection.delay(()->{
        if(active!=op||op.epoch!=connection.epoch())return;long now=SystemClock.elapsedRealtime(),age=now-op.started;
        boolean initialExpired=op instanceof Replace&&!((Replace)op).direct()&&replacementResponseTimedOut(((Replace)op).state.phase,age);
        boolean activationExpired=op instanceof Activate&&(age>=ACTIVATION_TOTAL_TIMEOUT||
            (((Activate)op).state.phase==ActivationState.Phase.WAIT_OPERATION&&age>=ACTIVATION_OPERATION_TIMEOUT)||
            (((Activate)op).acceptedAt>=0&&now-((Activate)op).acceptedAt>=ACTIVATION_LIST_TIMEOUT));
        boolean confirmationExpired=op instanceof Replace&&((Replace)op).confirmationWait.expired(now);
        boolean timed=initialExpired||activationExpired||(!(op instanceof Activate)&&age>(op instanceof Delete?POST_TIMEOUT:TOTAL_TIMEOUT))||confirmationExpired||(op instanceof Delete&&((Delete)op).state.phase==DeletionState.Phase.WAIT_DEVICE&&age>DELETE_OPERATION_TIMEOUT);
        if(timed){
            log.record("WATCHFACE_TIMEOUT","kind="+kind(op)+" phase="+phase(op)+" age_ms="+age+" initial_acceptance_timeout="+initialExpired+" initial_limit_ms="+REPLACEMENT_OPERATION_TIMEOUT+" confirmation_timeout="+confirmationExpired+" confirmation_limit_ms="+ConfirmationWait.TIMEOUT_MS+" confirmation_elapsed_ms="+(op instanceof Replace?((Replace)op).confirmationWait.elapsed(now):0));
            if(op instanceof Delete)((Delete)op).state.timeout();else if(op instanceof Replace){if(((Replace)op).direct())((Replace)op).directState.timeout();else((Replace)op).state.timeout();}else((Activate)op).state.timeout();finish(op);return;
        }timer(op);
    },1000);}
    private void failActive(String reason,Long err){Op op=active;if(op==null)return;if(op instanceof Replace){if(((Replace)op).direct())((Replace)op).directState.fail(reason,err);else((Replace)op).state.fail(reason,err);}else if(op instanceof Delete)((Delete)op).state.fail(reason,err);else((Activate)op).state.fail(reason,err);finish(op);}
    private void finish(Op op){
        if(active!=op||!terminal(op))return;
        boolean resultSettled=op instanceof Replace&&(((Replace)op).fileResultSeen||(((Replace)op).direct()&&((Replace)op).file!=null&&((Replace)op).file.existingResource()));
        show(op);op.finished=SystemClock.elapsedRealtime();active=null;busy=false;query=null;
        if(op instanceof Replace&&((Replace)op).file!=null)try{((Replace)op).file.close();}catch(Exception ignored){}
        boolean ok=op instanceof Replace?(((Replace)op).direct()?((Replace)op).directState.phase==DirectInstallState.Phase.COMPLETED:(((Replace)op).state.phase==ReplacementState.Phase.LISTED||((Replace)op).state.phase==ReplacementState.Phase.NO_TRANSFER)):op instanceof Delete?((Delete)op).state.phase==DeletionState.Phase.DELETED:((Activate)op).state.phase==ActivationState.Phase.ACTIVATED;
        log.record(op instanceof Replace?"REPLACE_SUMMARY":op instanceof Delete?"DELETE_SUMMARY":"ACTIVATE_SUMMARY","id="+op.id+" version="+op.version+" reliable_protocol_end="+ok+" visual_application_unverified=true"+(op instanceof Replace?" "+(((Replace)op).direct()?((Replace)op).directState.summary():((Replace)op).state.summary()):""));
        Replace refused=op instanceof Replace?(Replace)op:null;
        long refusal=refused!=null&&refused.file!=null?refused.file.infoRefusal():0;
        if(!ok&&op.epoch==connection.epoch()){
            listAt=0;
            if(DeviceRefusal.known(refusal)){
                // 设备在文件登记阶段就给出明确容量/数量拒绝，且一个数据字节都没发：这是终局失败，
                // 不是协议歧义。保留已认证链路，只让旧列表失效，用户清出空间后可直接再试。
                log.record("DEVICE_FILE_REFUSED","code="+refusal+" reason="+DeviceRefusal.reason(refusal)+" stage=file_info link_preserved=true reconnect_required=false fresh_list_required=true retry_after_refresh=true no_data_sent=true no_retry_sent=true no_reupload=true");
            }else{
                reconnectEpoch=op.epoch;
                if(!resultSettled)connection.protocolFailure("WATCHFACE_OPERATION_UNCERTAIN_RECONNECT");
                else log.record("FILE_RESULT_RECONNECT_REQUIRED","ack_queue_preserved=true no_more_operation_until_new_authenticated_epoch=true");
            }
        }
        boolean directEnd=op instanceof Replace&&((Replace)op).direct();
        if(directEnd&&ok){
            listAt=0;
            Replace r=(Replace)op;
            log.record("DIRECT_NATIVE_END","id="+r.id+" version="+r.version+" uploaded_payload_bytes="+r.source.size+" uploaded_payload_sha256="+r.source.hash+" payload_kind="+payloadKind(r.source)+" bin_removed="+(r.source.mode==HwtSource.Mode.CONTAINER_NO_BIN)+
                    (r.source.mode==HwtSource.Mode.BIN?" uploaded_bin_bytes="+r.source.size+" uploaded_bin_sha256="+r.source.hash:"")+" payload_mode="+r.source.mode+" post_need="+r.directState.postNeed+" reference=wrist_native client_flow_completed=true device_application_unverified=true need_not_a_gate=true fresh_list_not_required=true no_reupload=true");
        }
        publishFaces();if(ok&&!directEnd)requestNames();
    }
    private void show(Op op){if(listener==null||summaryOwner!=owner(op))return;if(op instanceof Replace){
        Replace r=(Replace)op;shownReplacementRequest=r.request;shownDeletionRequest=null;shownActivationRequest=null;Integer progress=r.file==null?null:(int)Math.min(100,r.file.bytesSentOrPrepared()*100/r.file.totalSize());
        String detail=r.direct()?r.directState.summary():r.state.summary();String currentPhase=r.direct()?r.directState.phase.name():r.state.phase.name();boolean done=r.direct()?r.directState.terminal():r.state.terminal();boolean failed=r.direct()?(r.directState.phase==DirectInstallState.Phase.FAILED||r.directState.phase==DirectInstallState.Phase.TIMED_OUT||r.directState.phase==DirectInstallState.Phase.CANCELLED):(r.state.phase==ReplacementState.Phase.FAILED||r.state.phase==ReplacementState.Phase.TIMED_OUT||r.state.phase==ReplacementState.Phase.CANCELLED);
        listener.onReplacementOperation(r.request.identity,currentPhase,detail,progress,!done,failed);
    }else if(op instanceof Delete){shownReplacementRequest=null;shownActivationRequest=null;Delete d=(Delete)op;shownDeletionRequest=d.request;listener.onDeletionOperation(d.request.identity,d.state.phase.name(),d.state.summary(),null,!d.state.terminal(),d.state.phase==DeletionState.Phase.FAILED||d.state.phase==DeletionState.Phase.TIMED_OUT);
    }else{shownReplacementRequest=null;shownDeletionRequest=null;Activate a=(Activate)op;shownActivationRequest=a.request;listener.onActivationOperation(a.request.identity,a.state.phase.name(),a.state.summary(),!a.state.terminal(),a.state.phase==ActivationState.Phase.FAILED||a.state.phase==ActivationState.Phase.TIMED_OUT);}}
    private void late(String id,String version,int command,Long error,Long value,List<TlvView.Part> fields,long epoch)throws IOException{
        for(Op prior:history)if(prior instanceof Replace&&((Replace)prior).direct()&&prior!=active&&prior.epoch==epoch&&prior.matches(id,version)&&prior.finished!=0&&SystemClock.elapsedRealtime()-prior.finished<POST_TIMEOUT){
            if(command==5){
                connection.sendControl(0x27,5,concat(Core.tlv(1,id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Core.tlv(2,version.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Core.tlv(0x7f,num(100000,4))));
                log.record("DIRECT_LATE_REPORT","ack_only=true no_new_post=true no_reupload=true reference=wrist_native");return;
            }
            if(command==3){error=wristOperationError(fields);if(error==null||error==0||error==100000)return;}
            break;
        }
        boolean badError=error!=null&&error!=0&&(command!=5||error!=100000),reportThree=command==5&&value!=null&&value==3;
        boolean invalidActivationReceipt=command==3&&(error==null||value==null||value.longValue()!=0);
        if(!badError&&!reportThree&&!invalidActivationReceipt)return;
        List<Op> recent=new ArrayList<>(history);recent.addAll(activationHistory);
        recent.sort((a,b)->a==b?0:a==last?-1:b==last?1:Long.compare(b.finished,a.finished));
        for(Op old:recent)if(old!=active&&old.epoch==epoch&&old.matches(id,version)&&old.finished!=0&&SystemClock.elapsedRealtime()-old.finished<POST_TIMEOUT){
            if(old instanceof Delete){
                if(!badError)continue;if(command==5&&!reportThree)return;
                if(command==3){Long echo=Wire.number(fields,3);if(echo!=null&&echo!=2)continue;}
                ((Delete)old).state.fail("LATE_DEVICE_ERROR",error);
            }else if(old instanceof Replace){
                if(!badError&&!reportThree)continue;
                Replace lateReplace=(Replace)old;
                if(lateReplace.direct()){
                    if(command==3){Long echo=Wire.number(fields,3);if(echo!=null&&echo!=1)continue;}
                    lateReplace.directState.fail(reportThree?"LATE_WATCH_REPORT_FAILURE":"LATE_DEVICE_ERROR",error);
                }else lateReplace.state.fail(reportThree?"LATE_WATCH_REPORT_FAILURE":"LATE_DEVICE_ERROR",error);
            }else{
                Activate oldActivation=(Activate)old;
                if(command==3){Long echo=Wire.number(fields,3);if(echo!=null&&echo!=1)continue;oldActivation.state.operation(error,value,echo);}
                else oldActivation.state.report(value==null?null:value.intValue(),error);
            }
            log.record("LATE_ERROR","id="+id+" version="+version+" report_three="+reportThree+" no_retry_no_ack_no_stop=true");
            if(active==null&&last==old&&summaryOwner==owner(old)){
                show(old);
                if(old instanceof Replace&&((Replace)old).direct()){
                    listAt=0;reconnectEpoch=old.epoch;publishFaces();
                    log.record("DIRECT_LATE_RESULT_INVALIDATED","reconnect_required=true no_tx_no_retry_no_delete=true");
                }else if(!(old instanceof Replace))connection.protocolFailure("LATE_OPERATION_ERROR_RECONNECT");
            }
            return;
        }
    }
    private static byte[] num(long n,int len){byte[] b=new byte[len];for(int i=len-1;i>=0;i--){b[i]=(byte)n;n>>>=8;}return b;}
    private static byte[] concat(byte[]... p)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] x:p)b.write(x);return b.toByteArray();}
}
