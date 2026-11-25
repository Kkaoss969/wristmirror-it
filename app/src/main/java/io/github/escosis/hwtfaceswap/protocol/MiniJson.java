package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 用于 HiChain 消息的小型、有界 JSON 编解码器；拒绝重复键与非整数数字。 */
public final class MiniJson {
    private MiniJson() {}
    public static final int MAX_BYTES=65536,MAX_DEPTH=8,MAX_NODES=2048;
    public static Map<String,Object> map(Object... pairs){if((pairs.length&1)!=0)throw new IllegalArgumentException();Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2){String k=(String)pairs[i];if(m.containsKey(k))throw new IllegalArgumentException("Duplicate JSON key");m.put(k,pairs[i+1]);}return m;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Map<String,Object> m,String key)throws IOException{Object v=m.get(key);if(!(v instanceof Map))throw new Core.Fault("AUTH_JSON_OBJECT_REQUIRED_"+key);return(Map<String,Object>)v;}
    public static String string(Map<String,Object> m,String key)throws IOException{Object v=m.get(key);if(!(v instanceof String))throw new Core.Fault("AUTH_JSON_STRING_REQUIRED_"+key);return(String)v;}
    public static String optionalString(Map<String,Object> m,String key){Object v=m.get(key);return v instanceof String?(String)v:null;}
    public static Long optionalNumber(Map<String,Object> m,String key)throws IOException{Object v=m.get(key);if(v==null)return null;if(!(v instanceof Byte||v instanceof Short||v instanceof Integer||v instanceof Long))throw new Core.Fault("AUTH_JSON_NUMBER_REQUIRED_"+key);return((Number)v).longValue();}
    public static byte[] hex(Map<String,Object> m,String key,int min,int max)throws IOException{byte[] b=Core.unhex(string(m,key));if(b.length<min||b.length>max)throw new Core.Fault("AUTH_JSON_HEX_LENGTH_"+key);return b;}
    public static Map<String,Object> parseObject(byte[] utf8)throws IOException{return parseObject(Core.utf8(utf8));}
    @SuppressWarnings("unchecked") public static Map<String,Object> parseObject(String text)throws IOException{if(text==null||text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new Core.Fault("AUTH_JSON_SIZE");Parser p=new Parser(text);Object o=p.value(0);p.space();if(p.i!=text.length()||!(o instanceof Map))throw new Core.Fault("AUTH_JSON_ROOT");return(Map<String,Object>)o;}
    public static String encode(Map<String,Object> value)throws IOException{StringBuilder b=new StringBuilder();write(b,value,0,new int[]{0});if(b.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new Core.Fault("AUTH_JSON_SIZE");return b.toString();}
    private static void write(StringBuilder b,Object o,int depth,int[] nodes)throws IOException{
        if(depth>MAX_DEPTH||++nodes[0]>MAX_NODES)throw new Core.Fault("AUTH_JSON_BUDGET");
        if(o==null){b.append("null");return;}if(o instanceof String){quote(b,(String)o);return;}if(o instanceof Boolean){b.append(o);return;}
        if(o instanceof Byte||o instanceof Short||o instanceof Integer||o instanceof Long){b.append(o);return;}
        if(o instanceof Map){b.append('{');boolean first=true;for(Map.Entry<?,?> e:((Map<?,?>)o).entrySet()){if(!(e.getKey() instanceof String))throw new Core.Fault("AUTH_JSON_KEY_TYPE");if(!first)b.append(',');first=false;quote(b,(String)e.getKey());b.append(':');write(b,e.getValue(),depth+1,nodes);}b.append('}');return;}
        if(o instanceof List){b.append('[');boolean first=true;for(Object v:(List<?>)o){if(!first)b.append(',');first=false;write(b,v,depth+1,nodes);}b.append(']');return;}
        throw new Core.Fault("AUTH_JSON_VALUE_TYPE");
    }
    private static void quote(StringBuilder b,String s)throws IOException{
        if(!s.equals(Core.utf8(s.getBytes(StandardCharsets.UTF_8))))throw new Core.Fault("AUTH_JSON_UNICODE");b.append('"');
        for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32){b.append("\\u");String h=Integer.toHexString(c);for(int j=h.length();j<4;j++)b.append('0');b.append(h);}else b.append(c);}}b.append('"');
    }
    private static final class Parser {
        final String s;int i,nodes;Parser(String s){this.s=s;}
        void space(){while(i<s.length()&&(s.charAt(i)==' '||s.charAt(i)=='\n'||s.charAt(i)=='\r'||s.charAt(i)=='\t'))i++;}
        Object value(int depth)throws IOException{space();if(depth>MAX_DEPTH||++nodes>MAX_NODES||i>=s.length())throw new Core.Fault("AUTH_JSON_BUDGET_OR_TRUNCATED");char c=s.charAt(i);
            if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<>();space();if(eat('}'))return m;while(true){space();if(i>=s.length()||s.charAt(i)!='"')throw bad();String k=str();space();if(!eat(':')||m.containsKey(k))throw new Core.Fault("AUTH_JSON_DUPLICATE_OR_KEY");m.put(k,value(depth+1));space();if(eat('}'))return m;if(!eat(','))throw bad();}}
            if(c=='['){i++;List<Object> a=new ArrayList<>();space();if(eat(']'))return a;while(true){a.add(value(depth+1));space();if(eat(']'))return a;if(!eat(','))throw bad();}}
            if(c=='"')return str();if(s.startsWith("true",i)){i+=4;return true;}if(s.startsWith("false",i)){i+=5;return false;}if(s.startsWith("null",i)){i+=4;return null;}
            int start=i;if(c=='-')i++;if(i>=s.length()||!digit(s.charAt(i)))throw bad();if(s.charAt(i)=='0')i++;else while(i<s.length()&&digit(s.charAt(i)))i++;
            if(i<s.length()&&(s.charAt(i)=='.'||s.charAt(i)=='e'||s.charAt(i)=='E'))throw new Core.Fault("AUTH_JSON_NON_INTEGER");try{return Long.valueOf(s.substring(start,i));}catch(NumberFormatException e){throw bad();}
        }
        boolean eat(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        String str()throws IOException{if(!eat('"'))throw bad();StringBuilder b=new StringBuilder();boolean closed=false;while(i<s.length()){char c=s.charAt(i++);if(c=='"'){closed=true;break;}if(c<32)throw bad();if(c=='\\'){if(i>=s.length())throw bad();c=s.charAt(i++);switch(c){case '"':case '\\':case '/':b.append(c);break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'u':if(i+4>s.length())throw bad();for(int p=0;p<4;p++){char h=s.charAt(i+p);if(!((h>='0'&&h<='9')||(h>='a'&&h<='f')||(h>='A'&&h<='F')))throw bad();}try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException e){throw bad();}i+=4;break;default:throw bad();}}else b.append(c);if(b.length()>MAX_BYTES)throw new Core.Fault("AUTH_JSON_STRING_SIZE");}String out=b.toString();if(!closed||!out.equals(Core.utf8(out.getBytes(StandardCharsets.UTF_8))))throw bad();return out;}
        boolean digit(char c){return c>='0'&&c<='9';}Core.Fault bad(){return new Core.Fault("AUTH_JSON_INVALID");}
    }
}
