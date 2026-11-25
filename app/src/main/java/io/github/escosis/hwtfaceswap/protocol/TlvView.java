package io.github.escosis.hwtfaceswap.protocol;



import java.io.IOException;

import java.util.*;



/** 用于诊断的有界、只读华为 TLV 解码；绝不改写协议或认证流程。 */

public final class TlvView {

    private TlvView() {}

    public static final int MAX_BYTES=65536, MAX_FIELDS=512, MAX_DEPTH=4;

    public static final class Part {

        public final int tag;

        public final byte[] value;

        Part(int tag,byte[] value) { this.tag=tag; this.value=value; }

        public Long number() { try { return Long.valueOf(Core.number(value)); } catch(IOException e) { return null; } }

        public String resourceText() {

            try { return value.length<=32?Core.utf8(value):null; } catch(IOException e) { return null; }

        }

    }

    public static List<Part> decode(byte[] bytes) throws IOException {

        if(bytes==null || bytes.length>MAX_BYTES) throw new Core.Fault("TLV_VIEW_BYTES_LIMIT");

        List<Part> out=new ArrayList<Part>(); int p=0;

        while(p<bytes.length) {

            if(out.size()>=MAX_FIELDS) throw new Core.Fault("TLV_VIEW_FIELDS_LIMIT");

            int tag=bytes[p++]&255, groups=0; long length=0;

            boolean ended=false;

            while(p<bytes.length) {

                int b=bytes[p++]&255;

                if(++groups>5 || length>(Integer.MAX_VALUE>>>7)) throw new Core.Fault("TLV_VIEW_LENGTH_OVERFLOW");

                length=(length<<7)|(b&127);

                if(length>MAX_BYTES) throw new Core.Fault("TLV_VIEW_BYTES_LIMIT");

                if((b&128)==0) { ended=true; break; }

            }

            if(!ended) throw new Core.Fault("TLV_VIEW_TRUNCATED_LENGTH");

            if(length>bytes.length-p) throw new Core.Fault("TLV_VIEW_TRUNCATED_VALUE");

            out.add(new Part(tag,Arrays.copyOfRange(bytes,p,p+(int)length)));p+=(int)length;

        }

        return Collections.unmodifiableList(out);

    }

    public static Part first(List<Part> fields,int tag) {

        for(Part p:fields) if(p.tag==tag) return p; return null;

    }

    public static String tags(List<Part> fields) {

        StringBuilder b=new StringBuilder();int count=0;

        for(Part p:fields) {

            if(++count>64) { b.append(",...");break; }

            if(b.length()>0)b.append(',');b.append(Integer.toHexString(p.tag)).append(':').append(p.value.length);

        }

        return b.toString();

    }

    public static final class Face {

        public final String id,version;

        public final Long type,expandType;

        public Face(String id,String version,Long type,Long expand) { this.id=id;this.version=version;this.type=type;this.expandType=expand; }

        public boolean validResource() { try { Core.resource(id,version);return true; } catch(IOException e) { return false; } }

        public String toString() { return Core.clean(id)+"_"+Core.clean(version)+"  type(raw)="+type+" expand(raw)="+expandType; }

    }

    /** 实际观察到的列表结构：root81 数据体 → 重复的 82 条目 → ID03、version04、type05 与可选 07。 */

    public static List<Face> faces(byte[] root81) throws IOException {

        List<Face> out=new ArrayList<Face>();

        for(Part entry:decode(root81)) {

            if(entry.tag!=0x82) continue;

            List<Part> data=decode(entry.value);Part id=first(data,3),version=first(data,4),type=first(data,5),expand=first(data,7);

            if(id==null || version==null) throw new Core.Fault("TLV_VIEW_FACE_ID_VERSION_MISSING");

            String i=id.resourceText(),v=version.resourceText();

            if(i==null || v==null) throw new Core.Fault("TLV_VIEW_FACE_TEXT_INVALID");

            out.add(new Face(i,v,type==null?null:type.number(),expand==null?null:expand.number()));

        }

        return Collections.unmodifiableList(out);

    }

}

