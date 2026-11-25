package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** 纯粹记录删除观察的状态机；绝不请求文件或激活其他表盘。
 * 原生 27/03 的 operation=2 与 27/05 的 reportType=3 是不同字段。
 * 兼容的成功信号仅允许发起新的列表查询；仍必须确认目标 ID 已不在列表中。
 */
public final class DeletionState {
    public enum Phase { WAIT_DEVICE, WAIT_LIST, DELETED, FAILED, CANCELLED, TIMED_OUT }
    public enum Action { NONE, QUERY }
    public final String id,version;
    public final boolean wasCurrent;
    public Phase phase=Phase.WAIT_DEVICE;
    public String reason="WAITING_DELETE_RESPONSE_OR_REPORT";
    public Long operationError,need,operationEcho,reportError;
    public Integer reportType;
    public Long firstError;
    public boolean operationObserved,deleteReportObserved,idAbsent,versionAbsent;
    public int signalGeneration,listObservations,consecutiveAbsences,currentCount;
    public String newCurrentId;
    private long lastObservedQuery=-1L;
    public DeletionState(String id,String version) throws IOException {this(id,version,false);}
    public DeletionState(String id,String version,boolean current) throws IOException {Core.resource(id,version);this.id=id;this.version=version;wasCurrent=current;}
    public boolean matches(String id,String version){return this.id.equals(id)&&this.version.equals(version);}
    public boolean terminal(){return phase==Phase.DELETED||phase==Phase.FAILED||phase==Phase.CANCELLED||phase==Phase.TIMED_OUT;}
    public Action operation(Long error,Long transferNeed,Long echo) {
        // 可选的回显值 1 代表其他操作，绝不视为删除响应。
        if(echo!=null&&echo.longValue()!=2)return Action.NONE;
        operationError=error;need=transferNeed;operationEcho=echo;
        if(error!=null&&error.longValue()!=0){fail("DELETE_OPERATION_ERROR",error);return Action.NONE;}
        if(terminal())return Action.NONE;
        // 0105 日志证明本机 27/03 回包缺 7F；原厂 Health/腕连 都把缺失的 7F 初始化成 0。原始观测保留 null，
        // 但删除判定仍要求独立的后续列表确认，不会仅凭这一帧就认定删除成功。
        if(transferNeed!=null&&transferNeed.longValue()!=0){fail("DELETE_UNEXPECTED_FILE_REQUEST_NO_TRANSFER",null);return Action.NONE;}
        if(operationObserved)return Action.NONE;
        operationObserved=true;signalGeneration++;consecutiveAbsences=0;phase=Phase.WAIT_LIST;
        reason=echo==null?"DELETE_RESPONSE_NO_ECHO_WAIT_NEW_LIST":"DELETE_RESPONSE_WAIT_NEW_LIST";
        return Action.QUERY;
    }
    public Action report(Integer type,Long error) {
        reportType=type;reportError=error;
        // 报告类型 2 表示文件传输完成，不表示删除；类型 1 表示当前表盘变更。
        if(type==null||type.intValue()!=3)return Action.NONE;
        if(error!=null&&error.longValue()!=100000&&error.longValue()!=0){fail("DELETE_REPORT_ERROR",error);return Action.NONE;}
        if(terminal())return Action.NONE;
        // 原生 reportAck 把缺失的 7F 默认为 100000；明确的 0 不能证明报告成功。
        if(error!=null&&error.longValue()==0){reason="DELETE_REPORT_ZERO_WAIT_OPERATION_OR_NORMAL_REPORT";return Action.NONE;}
        if(deleteReportObserved)return Action.NONE;
        deleteReportObserved=true;signalGeneration++;consecutiveAbsences=0;phase=Phase.WAIT_LIST;reason="DELETE_REPORT_THREE_WAIT_NEW_LIST";
        return Action.QUERY;
    }
    private static boolean observedText(String text) {
        if(text==null||text.isEmpty())return false;
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        try{return bytes.length<=32&&text.equals(Core.utf8(bytes));}catch(IOException e){return false;}
    }
    private static boolean observedFace(TlvView.Face face) {
        return face!=null&&observedText(face.id)&&observedText(face.version)&&face.type!=null&&face.type.longValue()>=0&&face.type.longValue()<=0xffffffffL&&(face.expandType==null||(face.expandType.longValue()>=0&&face.expandType.longValue()<=0xffffffffL));
    }
    public void list(List<TlvView.Face> faces,int generation,boolean ownQuery,long querySerial) {
        listObservations++;
        if(terminal()||phase!=Phase.WAIT_LIST||!ownQuery||signalGeneration==0||generation!=signalGeneration||querySerial<=0||querySerial<=lastObservedQuery)return;
        lastObservedQuery=querySerial;
        if(faces==null){fail("DELETE_LIST_MISSING",null);return;}
        if(faces.size()>TlvView.MAX_FIELDS){fail("DELETE_LIST_LIMIT",null);return;}
        idAbsent=true;versionAbsent=true;currentCount=0;newCurrentId=null;boolean validCurrent=true;
        for(TlvView.Face face:faces){if(!observedFace(face)){fail("DELETE_LIST_INVALID",null);return;}if(id.equals(face.id)){idAbsent=false;if(version.equals(face.version))versionAbsent=false;}if(FacePolicy.current(face)){currentCount++;newCurrentId=face.id;if(!face.validResource())validCurrent=false;}}
        if(!idAbsent){consecutiveAbsences=0;reason=versionAbsent?"DELETE_VERSION_ABSENT_BUT_ID_STILL_PRESENT":"DELETE_TARGET_STILL_LISTED";return;}
        if(wasCurrent&&(currentCount!=1||!validCurrent)){consecutiveAbsences=0;reason="DELETE_TARGET_ABSENT_WAIT_UNIQUE_VALID_CURRENT_MARKER";return;}
        consecutiveAbsences++;
        if(consecutiveAbsences>=2){phase=Phase.DELETED;reason="DELETE_TARGET_ID_ABSENT_IN_TWO_NEW_LISTS_VISUAL_CHECK_REQUIRED";}
        else reason="DELETE_TARGET_ABSENT_WAIT_SECOND_INDEPENDENT_QUERY";
    }
    public void fail(String why,Long error) {
        if(firstError==null&&error!=null)firstError=error;
        if(!terminal()||phase==Phase.DELETED){phase=Phase.FAILED;reason=why;}
    }
    public void cancel(){if(!terminal()){phase=Phase.CANCELLED;reason="DELETE_CANCELLED_QUEUED_COMMAND_NOT_RETRACTABLE";}}
    public void timeout(){if(!terminal()){phase=Phase.TIMED_OUT;reason="DELETE_TIMEOUT_NOT_A_REMOVAL_VERDICT";}}
    public String summary(){return "phase="+phase+" reason="+reason+" id="+id+" version="+version+" operation_error="+operationError+" need="+need+" operation_echo="+operationEcho+" delete_report="+reportType+" report_error="+reportError+" operation_observed="+operationObserved+" report_three_observed="+deleteReportObserved+" signal_generation="+signalGeneration+" list_observations="+listObservations+" independent_absences="+consecutiveAbsences+" id_absent="+idAbsent+" version_absent="+versionAbsent+" was_current="+wasCurrent+" new_current_count="+currentCount+" new_current_id="+newCurrentId+" first_error="+firstError;}
}
