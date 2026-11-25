package io.github.escosis.hwtfaceswap.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅记录协议观察；不按型号名称猜测能力，也不据此判定账号授权。 */
public final class LinkCapabilities {
    public int protocolVersion,sliceSize=1024,mtu=1024,intervalMs,authVersion,deviceSupportType,authAlgorithm,bondState,encryptMethod,authMode,authType;
    public byte[] serverNonce;
    // 原生腕上助手 w0.A / <clinit>：请求顺序决定对应响应布尔值的顺序。
    // 服务 01 隐含存在，始终请求其命令行；它不属于 A。
    private static final byte[] SERVICES=ids("02030405060708090A0B0C0D0E0F101112131415161718191A1B1D202223242526272A2B2D2E3032333435");
    private static final Map<Integer,byte[]> COMMANDS=new LinkedHashMap<>();
    static {
        add(0x01,"040708090A0D0E10111213141B1A1D21222324292A2B322E31303536372F");
        add(0x02,"010405060708");add(0x03,"010304");add(0x04,"01");add(0x05,"01");add(0x06,"01");
        add(0x07,"0103050708090A0E1013161517181B1C1D1E21222324252829061F");
        add(0x08,"010203");add(0x09,"010B0C0D0E0F");add(0x0a,"01090A");add(0x0b,"0103");add(0x0c,"01");add(0x0d,"01");
        add(0x0f,"0103050607080A0B0C");add(0x10,"01");add(0x11,"01");add(0x12,"01");add(0x13,"01");add(0x14,"01");add(0x15,"01");
        add(0x16,"010307");add(0x17,"010406070B0C10121517");add(0x18,"010204050609");add(0x19,"0104");
        add(0x1a,"0103070506");add(0x1b,"010F191A");add(0x1d,"0102030405060708090A");add(0x20,"01020304090A");
        add(0x22,"01");add(0x23,"020B");add(0x24,"0102030405060708090A0B0C");add(0x25,"02040E");add(0x26,"0203");
        // 原始 27 命令行为 01,0E；追加本应用的列表、操作、报告和名称能力查询。
        // 这是明确的只读能力查询扩展，不会执行对应操作。
        add(0x27,"010E02030506");add(0x2a,"0106");add(0x2b,"12");add(0x2d,"01");add(0x2e,"010203");add(0x30,"01");
        add(0x32,"01");add(0x33,"0102");add(0x34,"01");add(0x35,"0304");
        // 不要添加 01/3F：原生 Y(01,3F) 无法在原始命令行中找到它。
        // 存在尚未启用的发送方法，不能证明设备实际声明了该能力。
    }
    private static byte[] ids(String hex){byte[] values=new byte[hex.length()/2];for(int i=0;i<values.length;i++)values[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return values;}
    private static void add(int service,String hex){COMMANDS.put(service,ids(hex));}
    private final Map<Integer,Boolean> services=new HashMap<>();private final Map<String,Boolean> commands=new HashMap<>();
    private final Set<Integer> requestedCommandServices=new HashSet<>();private byte[] expand;
    private static int byteValue(List<TlvView.Part> p,int tag,int fallback)throws IOException{TlvView.Part f=Wire.unique(p,tag);if(f==null||f.value.length==0)return fallback;return f.value[0]&255;}
    private static int shortValue(List<TlvView.Part> p,int tag)throws IOException{TlvView.Part f=Wire.unique(p,tag);if(f==null||f.value.length!=2)throw new Core.Fault("LINK_PARAM_MISSING_OR_LENGTH");return(int)Core.number(f.value);}
    public static byte[] linkRequest()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int t:new int[]{1,2,3,4})b.write(Core.tlv(t,new byte[0]));return b.toByteArray();}
    public void parseLink(byte[] body)throws IOException{
        List<TlvView.Part> p=TlvView.decode(body);protocolVersion=byteValue(p,1,-1);sliceSize=shortValue(p,2);mtu=shortValue(p,3);intervalMs=shortValue(p,4);
        if(protocolVersion<0||sliceSize<16||sliceSize>32767||mtu<16||mtu>32767||intervalMs>1000)throw new Core.Fault("LINK_PARAM_RANGE_UNSUPPORTED");
        TlvView.Part v=Wire.unique(p,5);authVersion=v!=null&&v.value.length>=2?v.value[1]&255:0;serverNonce=v!=null&&v.value.length>=18?Arrays.copyOfRange(v.value,2,18):null;
        deviceSupportType=byteValue(p,7,0);authAlgorithm=byteValue(p,8,0);bondState=byteValue(p,9,0);encryptMethod=byteValue(p,0x0c,0);
        authMode=deviceSupportType==4?4:(deviceSupportType==1||deviceSupportType==2||deviceSupportType==3||deviceSupportType==8)?2:0;
    }
    public byte[] securityRequest(byte[] selfId,String model)throws IOException{
        if(selfId==null||selfId.length!=32)throw new Core.Fault("CLIENT_IDENTITY_REQUIRED");ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write(Core.tlv(1,new byte[]{(byte)authMode}));b.write(Core.tlv(5,selfId));b.write(Core.tlv(3,new byte[]{1}));b.write(Core.tlv(4,new byte[]{0}));
        if(authMode==2||authMode==4)b.write(Core.tlv(2,new byte[]{1}));if(authMode==4){b.write(Core.tlv(6,new byte[0]));String m=model==null?"Android":model;if(m.length()>80)m=m.substring(0,80);b.write(Core.tlv(7,m.getBytes(StandardCharsets.UTF_8)));}if(encryptMethod==1)b.write(Core.tlv(0x0d,new byte[]{1}));return b.toByteArray();
    }
    /** 01/33 专门使用首字节选择值，不能按通用 uint32 成功码解释。 */
    public void parseSecurity(byte[] body)throws IOException{List<TlvView.Part> p=TlvView.decode(body);int first=byteValue(p,1,0),type=first==1?100000:0;TlvView.Part t=Wire.unique(p,2);if(t!=null&&t.value.length>0){type=t.value[0]&255;if(first==4)type^=4;}type=byteValue(p,0x7f,type);authType=type;}
    public boolean iso(){return authType==100000||authType==5||authType==6;}
    public boolean lite(){return authType==1||authType==2;}
    public static byte[] servicesRequest()throws IOException{return Core.tlv(1,SERVICES);}
    public void parseServices(byte[] body)throws IOException{
        TlvView.Part f=Wire.unique(TlvView.decode(body),2);if(f==null)throw new Core.Fault("SERVICE_CAPABILITIES_MISSING");
        services.clear();commands.clear();requestedCommandServices.clear();services.put(1,true);
        // 原生实现读取 min(reply.length,A.length)；缺失的后缀保持未知，
        // 绝不编造为 true，也不使用可选 tag1 重排 tag2 的布尔值。
        for(int i=0;i<Math.min(SERVICES.length,f.value.length);i++)services.put(SERVICES[i]&255,f.value[i]!=0);
    }
    private static void row(ByteArrayOutputStream out,Map.Entry<Integer,byte[]> e)throws IOException{out.write(Core.tlv(2,new byte[]{e.getKey().byteValue()}));out.write(Core.tlv(3,e.getValue()));}
    public static byte[] commandsRequest()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(Map.Entry<Integer,byte[]> e:COMMANDS.entrySet())row(b,e);return Core.tlv(0x81,b.toByteArray());}
    public byte[] commandsForSupportedServices()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();requestedCommandServices.clear();
        for(Map.Entry<Integer,byte[]> e:COMMANDS.entrySet()){if(e.getKey()!=1&&!hasService(e.getKey()))continue;requestedCommandServices.add(e.getKey());row(b,e);}return Core.tlv(0x81,b.toByteArray());
    }
    public void parseCommands(byte[] body)throws IOException{
        TlvView.Part root=Wire.unique(TlvView.decode(body),0x81);if(root==null)throw new Core.Fault("COMMAND_CAPABILITIES_ROOT_MISSING");
        Map<String,Boolean> observed=new HashMap<>();Set<Integer> seen=new HashSet<>();
        List<Integer> rowServices=new ArrayList<>();List<byte[]> rowFlags=new ArrayList<>();
        // w0.Y 将第 i 个 tag02 与第 i 个 tag04 配对，而非按相邻字段配对。
        // 服务与标志整对可重排，标志仍按 B 中的位置解释。
        for(TlvView.Part f:TlvView.decode(root.value)){
            if(f.tag==2){if(f.value.length!=1)throw new Core.Fault("COMMAND_CAPABILITY_SERVICE_LENGTH");int service=f.value[0]&255;if(!seen.add(service))throw new Core.Fault("COMMAND_CAPABILITY_DUPLICATE_SERVICE");rowServices.add(service);}
            else if(f.tag==4)rowFlags.add(f.value);
        }
        if(rowServices.size()!=rowFlags.size())throw new Core.Fault("COMMAND_CAPABILITY_ROW_INCOMPLETE");
        for(int row=0;row<rowServices.size();row++){
            int service=rowServices.get(row);byte[] flags=rowFlags.get(row),requested=COMMANDS.get(service);
            if(requested!=null&&(requestedCommandServices.isEmpty()||requestedCommandServices.contains(service)))for(int i=0;i<Math.min(requested.length,flags.length);i++)observed.put(service+":"+(requested[i]&255),flags[i]!=0);
        }
        commands.clear();commands.putAll(observed);
    }
    public void parseExpand(byte[] body)throws IOException{TlvView.Part p=Wire.unique(TlvView.decode(body),1);if(p==null||p.value.length>512)throw new Core.Fault("EXPAND_CAPABILITY_INVALID");expand=p.value.clone();}
    public boolean hasService(int id){return Boolean.TRUE.equals(services.get(id));}
    public boolean hasCommand(int service,int command){return Boolean.TRUE.equals(commands.get(service+":"+command));}
    public boolean bit(int index){return expand!=null&&index>=0&&index/8<expand.length&&(expand[index/8]&(1<<(index%8)))!=0;}
    public boolean accountSupported(){return hasCommand(0x1a,5)&&hasCommand(0x1a,6);}
    public byte[] accountRequest(String id)throws IOException{String s=id==null?"":id.trim();if(s.getBytes(StandardCharsets.UTF_8).length>128)throw new Core.Fault("ACCOUNT_IDENTIFIER_TOO_LONG");ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(Core.tlv(1,s.isEmpty()?new byte[]{0}:s.getBytes(StandardCharsets.UTF_8)));if(bit(172))b.write(Core.tlv(3,new byte[]{1}));return b.toByteArray();}
}
