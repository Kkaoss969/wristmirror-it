package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/** HiChain 字节串封装，与严格 JSON 解析器分离。
 * 仅允许末尾一个 C 风格 NUL；不接受 BOM、内部 NUL 或任意后缀。
 * 此处不验证认证证明，也绝不凭 JSON 结构判定认证完成。
 */
public final class AuthReplyJson {
    private AuthReplyJson() {}
    private static boolean ws(int c){return c==0x20||c==0x09||c==0x0a||c==0x0d;}
    public static byte[] normalize(byte[] input)throws IOException{
        if(input==null||input.length==0||input.length>Core.MAX_AUTH+1)throw new Core.Fault("ISO_JSON_BYTES_INVALID");
        int start=0;
        int end=input.length;
        // C 风格终止符必须是最后一个字节，位于所有 JSON 空白之后。
        if(end>start&&input[end-1]==0)end--;
        for(int i=start;i<end;i++)if(input[i]==0)throw new Core.Fault("ISO_JSON_EMBEDDED_OR_MULTIPLE_NUL");
        while(start<end&&ws(input[start]&255))start++;
        while(end>start&&ws(input[end-1]&255))end--;
        if(start==end)throw new Core.Fault("ISO_JSON_EMPTY_AFTER_ENVELOPE");
        if(end-start>Core.MAX_AUTH)throw new Core.Fault("ISO_JSON_SIZE_LIMIT");
        return Arrays.copyOfRange(input,start,end);
    }
    public static Map<String,Object> parseObject(byte[] input)throws IOException{return MiniJson.parseObject(normalize(input));}
    public static boolean startsObject(byte[] input)throws IOException{byte[] normalized=normalize(input);return normalized.length>0&&normalized[0]=='{';}
    /** 只描述不含内容的元数据；即使载荷带有 PIN、令牌或账号信息，也不会泄露其内容。 */
    public static String describe(byte[] input){
        if(input==null)return "json_present=false";
        int start=input.length>=3&&(input[0]&255)==0xef&&(input[1]&255)==0xbb&&(input[2]&255)==0xbf?3:0;
        boolean bom=start==3;int leading=0;while(start<input.length&&ws(input[start]&255)){start++;leading++;}
        String shape="EMPTY";
        if(start<input.length){int c=input[start]&255;shape=c=='{'?"OBJECT":c=='['?"ARRAY":c=='\"'?"STRING":(c=='-'||(c>='0'&&c<='9'))?"NUMBER":(c=='t'||c=='f')?"BOOLEAN":c=='n'?"NULL":"OTHER";}
        int trailingNul=0;for(int i=input.length-1;i>=0&&input[i]==0;i--)trailingNul++;
        int tail=input.length-trailingNul,trailingSpace=0;while(tail>0&&ws(input[tail-1]&255)){tail--;trailingSpace++;}
        return "json_present=true bytes="+input.length+" root_shape="+shape+" utf8_bom="+bom+" leading_ws="+leading+" trailing_ws="+trailingSpace+" terminal_nuls="+trailingNul;
    }
}
