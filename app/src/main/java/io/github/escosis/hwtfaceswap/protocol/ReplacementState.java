package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.List;

/** 不依赖 Android 或厂商组件；每次表盘替换只允许一次前置操作与一次上传。 */
public final class ReplacementState {
    public enum Phase { WAIT_OPERATION, TRANSFERRING, WAIT_REPORT, WAIT_POST, WAIT_LIST, LISTED, NO_TRANSFER, FAILED, CANCELLED, TIMED_OUT }
    public enum Action { NONE, UPLOAD, ACTIVATE, QUERY }
    public final String id,baseline,version;
    public Phase phase=Phase.WAIT_OPERATION;
    public String reason="WAITING_EXPLICIT_27_03";
    public boolean uploadIssued,reportReceived,postIssued,postObserved,listed,current;
    public boolean nativeRespondObserved;
    private boolean freshList;
    public Integer nativeRespond,progress,report;
    public Long firstError,initialError,initialNeed,postError,postNeed;
    public int postGeneration,listObservations;
    public ReplacementState(String id,String baseline,String version) throws IOException {Core.resource(id,baseline);Core.resource(id,version);this.id=id;this.baseline=baseline;this.version=version;}
    public boolean matches(String id,String version){return this.id.equals(id)&&this.version.equals(version);}
    public boolean terminal(){return phase==Phase.LISTED||phase==Phase.NO_TRANSFER||phase==Phase.FAILED||phase==Phase.CANCELLED||phase==Phase.TIMED_OUT;}
    public Action operation(Long error,Long need) {
        if(phase==Phase.WAIT_OPERATION){initialError=error;initialNeed=need;}
        else if(postIssued){postError=error;postNeed=need;}
        if(error!=null && error.longValue()!=0){fail("DEVICE_OPERATION_ERROR",error);return Action.NONE;}
        if(terminal())return Action.NONE;
        if(phase==Phase.WAIT_OPERATION) {
            initialError=error;initialNeed=need;
            // 0105 日志：本机 27/03 回包 30 B 只带 1/2/3 与 need=1，缺 7F；Health handleApply 与腕连 w0.H 都把缺失的 7F 初始化成 0。
            // 原始观测仍保留 null（initial_error=null、initial_error_defaulted=true），缺 04 need 仍然拒绝。
            if(need==null){fail("OPERATION_FIELDS_MISSING",null);return Action.NONE;}
            if(need.longValue()==0){phase=Phase.NO_TRANSFER;reason="INCREMENTED_VERSION_NEED_ZERO_NO_MORE_RETRY";return Action.NONE;}
            if(need.longValue()!=1){fail("OPERATION_NEED_INVALID",null);return Action.NONE;}
            uploadIssued=true;phase=Phase.TRANSFERRING;reason="DEVICE_REQUESTED_FILE";return Action.UPLOAD;
        }
        if(postIssued) {
            postError=error;postNeed=need;
            // 同一条原厂默认：缺 7F 视为 0，但缺 04 need 仍拒绝（不推断文件已被接受）。
            if(need==null){fail("POST_OPERATION_FIELDS_MISSING",null);return Action.NONE;}
            if(need.longValue()!=0){fail("POST_OPERATION_REQUESTED_MORE_FILE_NO_RETRY",null);return Action.NONE;}
            if(!postObserved){postObserved=true;postGeneration++;}
            phase=Phase.WAIT_LIST;reason="POST_OPERATION_CONFIRMED_WAIT_FRESH_LIST";return Action.QUERY;
        }
        return Action.NONE;
    }
    public Action report(Integer type,Long error) {
        if(error!=null && error.longValue()!=100000 && error.longValue()!=0){fail("WATCH_REPORT_ERROR",error);return Action.NONE;}
        report=type;
        if(type!=null && type.intValue()==3){fail("WATCH_REPORT_FAILURE",null);return Action.NONE;}
        if(terminal())return Action.NONE;
        if(type!=null && type.intValue()==2 && uploadIssued) {
            reportReceived=true;
            if(!postIssued){postIssued=true;phase=Phase.WAIT_POST;reason="REPORT_TWO_SEND_ONE_POST_ACTIVATION";return Action.ACTIVATE;}
        }
        return Action.NONE;
    }
    public void nativeResult(int raw) {
        nativeRespondObserved=true;nativeRespond=Integer.valueOf(raw);
        if(raw==0){fail("NATIVE_FILE_RESPOND_ZERO",null);return;}
        if(terminal())return;
        if(raw==1 && phase==Phase.TRANSFERRING){phase=Phase.WAIT_REPORT;reason="NATIVE_TRANSFER_CALLBACK_WAIT_REPORT_TWO";}
        maybeListed();
    }
    public void nativeProgress(int raw){if(raw>=0 && raw<=100)progress=Integer.valueOf(raw);}
    public void fail(String why,Long error){if(firstError==null&&error!=null)firstError=error;reason=why;phase=Phase.FAILED;}
    public void cancel(){if(!terminal()){phase=Phase.CANCELLED;reason="USER_CANCELLED";}}
    public void timeout(){if(!terminal()){phase=Phase.TIMED_OUT;reason="TIMEOUT_NOT_INSTALL_VERDICT";}}
    public void list(List<TlvView.Face> faces,int queryGeneration,boolean ownQuery) {
        listObservations++;listed=false;current=false;
        for(TlvView.Face face:faces)if(matches(face.id,face.version)){listed=true;if(face.type!=null)current=(face.type.longValue()&1)!=0;}
        if(terminal())return;
        freshList=ownQuery && postObserved && postGeneration>0 && queryGeneration==postGeneration;
        maybeListed();
    }
    private void maybeListed() {
        if(terminal())return;
        if(uploadIssued && reportReceived && postIssued && postObserved && listed && freshList && nativeRespondObserved && nativeRespond!=null && nativeRespond.intValue()==1) {
            phase=Phase.LISTED;reason="REPLACEMENT_LISTED_APPLICATION_VISUAL_CHECK_REQUIRED";
        }
    }
    public String summary(){return "phase="+phase+" reason="+reason+" initial_error="+initialError+" initial_need="+initialNeed
        +" initial_error_defaulted="+(initialNeed!=null&&initialError==null)
        +" upload_issued="+uploadIssued+" native_result="+nativeRespond+" native_percent="+progress+" report="+report
        +" report_two="+reportReceived+" post_issued="+postIssued+" post_error="+postError+" post_need="+postNeed
        +" post_error_defaulted="+(postObserved&&postError==null)
        +" listed="+listed+" current="+current+" generation="+postGeneration+" list_observations="+listObservations+" first_error="+firstError;}
}
