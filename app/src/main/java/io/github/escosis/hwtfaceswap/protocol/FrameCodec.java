package io.github.escosis.hwtfaceswap.protocol;

import java.io.*;
import java.util.*;

/** 带 CRC16-XMODEM 校验的华为 5A 帧编解码；根据协议证据独立实现。 */
public final class FrameCodec {
    public static final int MAX_FRAME=65540,MAX_BODY=65536;
    private FrameCodec() {}
    public static int crc16(byte[] data,int offset,int length){int crc=0;for(int i=offset;i<offset+length;i++){crc^=(data[i]&255)<<8;for(int bit=0;bit<8;bit++)crc=(crc&0x8000)!=0?((crc<<1)^0x1021)&0xffff:(crc<<1)&0xffff;}return crc;}
    public static final class Packet {
        public final int service,command;private final byte[] body;
        public Packet(int service,int command,byte[] body){this.service=service;this.command=command;this.body=body.clone();}
        public byte[] body(){return body.clone();}
    }
    private static byte[] framed(int control,int sequence,byte[] data)throws IOException{
        int overhead=control==0?1:2,len=data.length+overhead;
        if(len>65535)throw new Core.Fault("FRAME_LENGTH_LIMIT");
        byte[] out=new byte[len+5];out[0]=0x5a;out[1]=(byte)(len>>>8);out[2]=(byte)len;out[3]=(byte)control;int at=4;
        if(control!=0)out[at++]=(byte)sequence;
        System.arraycopy(data,0,out,at,data.length);int crc=crc16(out,0,out.length-2);out[out.length-2]=(byte)(crc>>>8);out[out.length-1]=(byte)crc;return out;
    }
    /** maxFrameBytes 包含 CRC 与帧头；按套接字 MTU 拆分写入属于独立的传输层职责。 */
    public static List<byte[]> encode(int service,int command,byte[] body,int maxFrameBytes)throws IOException{
        if(service<0||service>255||command<0||command>255||body==null||body.length>MAX_BODY||maxFrameBytes<16||maxFrameBytes>MAX_FRAME)throw new Core.Fault("FRAME_ARGUMENTS");
        byte[] logical=new byte[body.length+2];logical[0]=(byte)service;logical[1]=(byte)command;System.arraycopy(body,0,logical,2,body.length);
        // 原生分片条件：logical(service+command+body).length + 6 <= sliceSize，即 body + 8。
        if(body.length+8<=maxFrameBytes&&body.length+3<=65535)return Collections.singletonList(framed(0,0,logical));
        int unit=Math.min(maxFrameBytes-7,65533),count=(logical.length+unit-1)/unit;
        if(count<2||count>256)throw new Core.Fault("FRAME_SLICE_BUDGET");
        List<byte[]> result=new ArrayList<>();int pos=0;for(int seq=0;seq<count;seq++){int n=Math.min(unit,logical.length-pos);result.add(framed(seq==0?1:seq==count-1?3:2,seq,Arrays.copyOfRange(logical,pos,pos+n)));pos+=n;}return Collections.unmodifiableList(result);
    }
    public static final class Decoder {
        private byte[] pending=new byte[0];private ByteArrayOutputStream assembly;private int nextSeq;private long started;private int errors;private String lastError="";
        public int errors(){return errors;}public String lastError(){return lastError;}
        public void clear(){pending=new byte[0];assembly=null;nextSeq=0;started=0;errors=0;lastError="";}
        private void bad(String why){errors++;lastError=why;assembly=null;nextSeq=0;started=0;}
        public List<Packet> receive(byte[] input,long now)throws IOException{
            if(input==null||input.length>MAX_FRAME*2)throw new Core.Fault("FRAME_RX_LIMIT");
            if(assembly!=null&&now-started>15000)bad("FRAME_ASSEMBLY_TIMEOUT");
            if(pending.length+input.length>MAX_FRAME*3){bad("FRAME_BUFFER_LIMIT");pending=new byte[0];throw new Core.Fault("FRAME_BUFFER_LIMIT");}
            byte[] joined=new byte[pending.length+input.length];System.arraycopy(pending,0,joined,0,pending.length);System.arraycopy(input,0,joined,pending.length,input.length);int cursor=0;List<Packet> out=new ArrayList<>();
            while(cursor<joined.length){
                if((joined[cursor]&255)!=0x5a){cursor++;bad("FRAME_MAGIC_RESYNC");continue;}
                if(joined.length-cursor<3)break;
                int n=((joined[cursor+1]&255)<<8)|(joined[cursor+2]&255),total=n+5;
                if(n<2||total>MAX_FRAME){cursor++;bad("FRAME_INVALID_SIZE");continue;}
                if(joined.length-cursor<total)break;
                int expected=((joined[cursor+total-2]&255)<<8)|(joined[cursor+total-1]&255);
                if(crc16(joined,cursor,total-2)!=expected){cursor++;bad("FRAME_CRC");continue;}
                byte[] frame=Arrays.copyOfRange(joined,cursor,cursor+total);cursor+=total;
                int control=frame[3]&255;
                if(control==0){
                    if(total<8){bad("FRAME_UNSLICED_SHORT");continue;}
                    if(assembly!=null)bad("FRAME_UNSLICED_INTERRUPTED_SLICES");
                    out.add(new Packet(frame[4]&255,frame[5]&255,Arrays.copyOfRange(frame,6,total-2)));continue;
                }
                if(control<1||control>3||total<7){bad("FRAME_CONTROL");continue;}
                int seq=frame[4]&255;byte[] data=Arrays.copyOfRange(frame,5,total-2);
                if(control==1){if(seq!=0||data.length<2){bad("FRAME_FIRST_SLICE");continue;}if(assembly!=null)bad("FRAME_OVERLAPPING_SLICES");assembly=new ByteArrayOutputStream();started=now;nextSeq=0;}
                if(assembly==null||seq!=nextSeq){bad("FRAME_SLICE_SEQUENCE");continue;}
                if(assembly.size()+data.length>MAX_BODY+2){bad("FRAME_BODY_LIMIT");continue;}
                assembly.write(data,0,data.length);nextSeq++;
                if(control==3){byte[] logical=assembly.toByteArray();assembly=null;nextSeq=0;started=0;if(logical.length<2){bad("FRAME_SLICED_SHORT");continue;}out.add(new Packet(logical[0]&255,logical[1]&255,Arrays.copyOfRange(logical,2,logical.length)));}
            }
            pending=Arrays.copyOfRange(joined,cursor,joined.length);return Collections.unmodifiableList(out);
        }
    }
}
