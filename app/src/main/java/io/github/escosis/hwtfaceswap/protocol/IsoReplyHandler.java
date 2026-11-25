package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 有界处理收到的 01/28 回包路由，与 JSON 内容及密码学证明分离。
 * 调用方必须绑定当前套接字连接批次，并在发送前保存 learnedSecret。
 */
public final class IsoReplyHandler {
    private IsoReplyHandler() {}
    public enum Kind { NEXT_REQUEST, CONTROL_COMPLETION, PEER_REJECTED }
    public static final class Result implements AutoCloseable {
        public final Kind kind;
        public final Map<String,Object> next;
        public final Long peerCode;
        private byte[] candidate;
        private Result(Kind kind,Map<String,Object> next,byte[] candidate,Long code){this.kind=kind;this.next=next;this.candidate=candidate==null?null:candidate.clone();this.peerCode=code;}
        public byte[] candidate(){return candidate==null?null:candidate.clone();}
        public void close(){if(candidate!=null)Arrays.fill(candidate,(byte)0);candidate=null;}
    }
    public static Result apply(IsoAuth auth,List<TlvView.Part> fields)throws IOException{
        if(auth==null)throw new Core.Fault("ISO_CONTEXT_MISSING");
        // 先读取全部语义字段，防止重复标签伪装成错误或 ACK。
        TlvView.Part json=Wire.unique(fields,1),completion=Wire.unique(fields,4);
        Long headerError=Wire.number(fields,0x7f);
        if(headerError!=null&&headerError!=0&&headerError!=100000)return new Result(Kind.PEER_REJECTED,null,null,headerError);
        if(completion!=null&&completion.value.length!=1)throw new Core.Fault("ISO_COMPLETION_MARKER_LENGTH");
        if(completion!=null&&(completion.value[0]&255)!=0){
            if(!auth.awaitingCompletion())throw new Core.Fault("ISO_COMPLETION_OUT_OF_STAGE");
            // 原生腕上助手 w0.F pc002b..003e：非零 tag4 表示完成；tag1 不是 JSON。
            // 其字节是不可解释的控制元数据，不是认证证明或错误结构。
            if(json!=null&&json.value.length>Core.MAX_AUTH+1)throw new Core.Fault("ISO_CONTROL_PAYLOAD_SIZE");
            byte[] candidate=auth.acceptCompletion();
            try{return new Result(Kind.CONTROL_COMPLETION,null,candidate,null);}finally{if(candidate!=null)Arrays.fill(candidate,(byte)0);}
        }
        if(json==null||json.value.length==0)throw new Core.Fault("ISO_JSON_MISSING");
        Map<String,Object> peer=AuthReplyJson.parseObject(json.value);
        return new Result(Kind.NEXT_REQUEST,auth.handlePeer(peer),null,null);
    }
    /** 仅描述不含敏感内容的结构与状态元数据；不包含账号、PIN、nonce、令牌或 JSON 正文。 */
    public static String describe(IsoAuth auth,List<TlvView.Part> fields){
        try{TlvView.Part json=Wire.unique(fields,1),completion=Wire.unique(fields,4);Long error=Wire.number(fields,0x7f);
            String marker=completion==null?"absent":completion.value.length==1?Integer.toString(completion.value[0]&255):"invalid_length";
            return "operation="+(auth==null?-1:auth.operation())+" expected_stage="+(auth==null?-1:auth.expectedStage())+" completion_marker="+marker+" header_error="+(error==null?"absent":error)+" "+AuthReplyJson.describe(json==null?null:json.value);
        }catch(IOException e){return "reply_metadata_invalid=true code="+Core.code(e);}
    }
}
