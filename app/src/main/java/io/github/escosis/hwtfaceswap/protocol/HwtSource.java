package io.github.escosis.hwtfaceswap.protocol;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** 本地表盘来源：原始 HWT/BIN/资源内容不改写；显式去 BIN 模式只在私有副本中重新封装 ZIP。 */
public final class HwtSource {
    /**
     * BIN=内层 watchface.bin（经典表盘）；HWT=外层容器（仅校验，不直装）；
     * CONTAINER=内层资源容器原字节（Watch 全能模式）；CONTAINER_NO_BIN=由内层容器移除根 watchface.bin 重新封装的派生载荷。
     */
    public enum Mode { BIN, HWT, CONTAINER, CONTAINER_NO_BIN }
    public static final int MAX_PREVIEW_BYTES=2*1024*1024;
    public final File directory, original, resource, bin, preview, container;
    public final String sourceHash, binHash, resourceHash, containerHash, xmlVersion, screen, title, previewHash;
    public final boolean resourceIsZip;
    /** 内层容器含 watchface/ 资源树（全能模式），且不是只有 watchface.bin 的经典容器。 */
    public final boolean allModeResource;
    public final int allModeMembers;
    /** 本地结构/完整性判断，不是设备支持矩阵或固件许可结论。 */
    public final String containerDiagnostic;
    private volatile String strippedHash;
    private File strippedContainerFile;
    private HwtSource(File dir,File outer,File resource,File bin,File container,boolean zip,boolean allMode,int members,String diagnostic,String[] xml,File preview) throws IOException {
        this.directory=dir;this.original=outer;this.resource=resource;this.bin=bin;this.container=container;this.resourceIsZip=zip;
        this.allModeResource=allMode;this.allModeMembers=members;this.containerDiagnostic=diagnostic;
        this.sourceHash=Core.hex(Core.digest(outer));this.resourceHash=Core.hex(Core.digest(resource));
        this.containerHash=container==null?null:resourceHash;
        this.binHash=bin==null?null:Core.hex(Core.digest(bin));this.xmlVersion=xml[0];this.screen=xml[1];this.title=xml[2];
        File acceptedPreview=preview;String acceptedHash=null;
        if(preview!=null)try{acceptedHash=Core.hex(Core.digest(preview));}catch(IOException ignored){acceptedPreview=null;}
        this.preview=acceptedPreview;this.previewHash=acceptedHash;
    }
    public static HwtSource load(InputStream input,File privateFiles) throws IOException {
        File parent=new File(privateFiles,"local-hwt");
        if(!parent.isDirectory() && !parent.mkdirs()) throw new Core.Fault("PRIVATE_DIRECTORY_UNAVAILABLE");
        File dir=new File(parent,"source-"+UUID.randomUUID().toString());
        if(!dir.mkdir()) throw new Core.Fault("PRIVATE_DIRECTORY_UNAVAILABLE");
        File outer=new File(dir,"source.hwt");
        try {
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(outer))) { Core.copy(input,out,Core.MAX_SOURCE); }
            return inspect(outer,dir);
        } catch(IOException | RuntimeException e) { try{cleanup(privateFiles,dir);}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e; }
    }
    public static HwtSource inspect(File outer,File dir) throws IOException {
        if(outer.length()<=0 || outer.length()>Core.MAX_SOURCE) throw new Core.Fault("SOURCE_SIZE_LIMIT");
        File resource=new File(dir,"resource.data"), bin=null, preview=null;
        String[] xml={"unknown","unknown","HWT locale"};
        try(ZipFile zip=new ZipFile(outer)) {
            Map<String,ZipEntry> entries=index(zip);
            boolean root=entries.containsKey("com.huawei.watchface"), snapshot=entries.containsKey("watchFace/com.huawei.watchface");
            if(root==snapshot) throw new Core.Fault("HWT_RESOURCE_MISSING_OR_AMBIGUOUS");
            String prefix=root?"":"watchFace/";
            ZipEntry description=entries.get(prefix+"description.xml");
            if(description!=null) xml=description(readMember(zip,description,65536));
            extract(zip,entries.get(prefix+"com.huawei.watchface"),resource);
            preview=extractPreview(zip,entries,prefix,dir);
        } catch(ZipException e) { throw new Core.Fault("SOURCE_NOT_READABLE_HWT"); }
        boolean zip=zipMagic(resource), allMode=false;
        File container=null;
        int members=0;
        String diagnostic="RAW_BIN_RESOURCE";
        if(zip) {
            container=resource;
            try(ZipFile inner=new ZipFile(resource)) {
                Map<String,ZipEntry> innerEntries=index(inner);
                ZipEntry e=innerEntries.get("watchface.bin");
                if(e!=null) { bin=new File(dir,"watchface.bin");extract(inner,e,bin); }
                for(Map.Entry<String,ZipEntry> entry:innerEntries.entrySet())
                    if(!entry.getValue().isDirectory() && !"watchface.bin".equals(entry.getKey())) members++;
                // 经典 BIN 与全能资源分别判断：坏/未知资源树不影响已有有效 BIN。
                try {
                    validateAllModeContainer(inner,innerEntries);
                    allMode=true;diagnostic="WATCH_XML_RESOURCES_VALIDATED";
                } catch(Core.Fault unsupported) { diagnostic=unsupported.code; }
            } catch(ZipException e) { throw new Core.Fault("INNER_RESOURCE_ZIP_INVALID"); }
        } else bin=resource;
        return new HwtSource(dir,outer,resource,bin,container,zip,allMode,members,diagnostic,xml,preview);
    }
    /** 经典载荷：内层根 watchface.bin（或本身即非 ZIP 的原始 BIN）。 */
    public boolean payloadBin(){return bin!=null;}
    /** 全能模式载荷：内层资源容器原字节。 */
    public boolean payloadContainer(){return container!=null&&allModeResource;}
    /** 派生载荷：内层同时含 bin 与资源树时，移除根 bin 后的重新封装容器。 */
    public boolean payloadContainerWithoutBin(){return bin!=null&&bin!=container&&container!=null&&allModeResource;}
    /** 派生载荷哈希；只有真正生成过才可见，用于绑定冻结快照。 */
    public String strippedHash(){return strippedHash;}
    public String payloadKind(){return payloadBin()?"bin":payloadContainer()?"container":"none";}
    /** 唯一共用的载荷绑定门：调用者不能只改 Mode 把外 HWT/未知 ZIP 冒充解析结果。 */
    public boolean matchesPayload(Snapshot snapshot){
        if(snapshot==null||snapshot.source!=this||snapshot.hash==null||snapshot.mode==null)return false;
        switch(snapshot.mode){
            case BIN:return payloadBin()&&snapshot.hash.equals(binHash);
            case CONTAINER:return payloadContainer()&&snapshot.hash.equals(containerHash);
            case CONTAINER_NO_BIN:return payloadContainerWithoutBin()&&snapshot.hash.equals(strippedHash);
            default:return false;
        }
    }
    private static void validateAllModeContainer(ZipFile zip,Map<String,ZipEntry> entries)throws IOException{
        ZipEntry config=entries.get("watchface/watch_face_config.xml");
        if(config==null||config.isDirectory()||config.getSize()<=0)throw new Core.Fault("CONTAINER_CONFIG_MISSING");
        int assets=0;long expanded=0;
        for(ZipEntry entry:entries.values()){
            validateContainerEntry(entry);
            if(!entry.isDirectory()){
                expanded+=entry.getSize();
                if(expanded>Core.MAX_MEMBER)throw new Core.Fault("CONTAINER_EXPANDED_SIZE_LIMIT");
                if(entry.getName().startsWith("watchface/res/")&&entry.getSize()>0)assets++;
            }
        }
        if(assets==0)throw new Core.Fault("CONTAINER_RESOURCES_MISSING");
        byte[] xml=readMember(zip,config,1024*1024);
        String upper=Core.utf8(xml).toUpperCase(Locale.ROOT);
        if(upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY"))throw new Core.Fault("XML_DTD_FORBIDDEN");
        try{
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
            factory.setValidating(false);factory.setNamespaceAware(false);factory.setExpandEntityReferences(false);
            DocumentBuilder builder=factory.newDocumentBuilder();
            builder.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler(){public void warning(SAXParseException e)throws SAXException{throw e;}public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            Element root=builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            if(root==null||!"watchface".equals(root.getTagName()))throw new Core.Fault("CONTAINER_CONFIG_ROOT_UNSUPPORTED");
            if(root.getElementsByTagName("*").getLength()>4096)throw new Core.Fault("CONTAINER_CONFIG_NODE_LIMIT");
        }catch(Core.Fault e){throw e;}catch(Exception e){throw new Core.Fault("CONTAINER_CONFIG_XML_INVALID");}
        // ZipFile 的流不保证替调用者核对 CRC；所有将发给设备的成员都显式核验。
        for(ZipEntry entry:entries.values())if(!entry.isDirectory())checkContainerMember(zip,entry,null);
    }
    private static void validateContainerEntry(ZipEntry entry)throws IOException{
        String name=entry.getName();
        if(name==null||name.isEmpty()||name.length()>512||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0)
            throw new Core.Fault("CONTAINER_ENTRY_PATH_INVALID");
        for(int i=0;i<name.length();i++)if(name.charAt(i)<32||name.charAt(i)==127)throw new Core.Fault("CONTAINER_ENTRY_PATH_INVALID");
        String path=entry.isDirectory()?name.substring(0,name.length()-1):name;
        for(String segment:path.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))throw new Core.Fault("CONTAINER_ENTRY_PATH_INVALID");
        if(entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED)throw new Core.Fault("CONTAINER_ENTRY_METHOD_UNSUPPORTED");
        if(entry.getSize()<0||entry.getSize()>Core.MAX_MEMBER||entry.getCrc()<0)throw new Core.Fault("RESOURCE_SIZE_LIMIT");
        if(entry.getCompressedSize()>0&&entry.getSize()>entry.getCompressedSize()*500L)throw new Core.Fault("ZIP_EXPANSION_LIMIT");
    }
    private static void checkContainerMember(ZipFile zip,ZipEntry entry,OutputStream copy)throws IOException{
        CRC32 crc=new CRC32();long size=0;byte[] buffer=new byte[65536];int n;
        try(InputStream in=zip.getInputStream(entry)){
            while((n=in.read(buffer))!=-1){
                size+=n;if(size>entry.getSize()||size>Core.MAX_MEMBER)throw new Core.Fault("RESOURCE_SIZE_LIMIT");
                crc.update(buffer,0,n);if(copy!=null)copy.write(buffer,0,n);
            }
        }
        if(size!=entry.getSize()||crc.getValue()!=entry.getCrc())throw new Core.Fault("RESOURCE_CRC");
    }
    /** 预览是可选旁路；只读取固定候选成员，失败不改变资源提取或原始载荷。 */
    private static File extractPreview(ZipFile zip,Map<String,ZipEntry> entries,String prefix,File dir) {
        String[] candidates={"preview/cover.jpg","preview/cover.jpeg","preview/cover.png","preview/cover.webp","preview/cover.gif",
            "preview/preview.png","preview/preview.jpg","preview/preview.jpeg","preview/preview.webp","preview/preview.gif",
            "preview.png","preview.jpg","preview.jpeg","preview.webp","preview.gif","cover.png","cover.jpg","cover.jpeg","cover.webp",
            "preview/icon_small.png","preview/icon_small.jpg","preview/icon_small.jpeg","preview/icon_small.webp"};
        for(String candidate:candidates){
            ZipEntry found=null;boolean ambiguous=false;String wanted=(prefix+candidate).toLowerCase(Locale.ROOT);
            for(Map.Entry<String,ZipEntry> entry:entries.entrySet())if(entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)){
                if(found!=null){ambiguous=true;break;}found=entry.getValue();
            }
            if(found==null||ambiguous||found.isDirectory()||found.getSize()<=0||found.getSize()>MAX_PREVIEW_BYTES)continue;
            if(found.getCompressedSize()>0&&found.getSize()>found.getCompressedSize()*500L)continue;
            try{
                byte[] bytes;try(InputStream in=zip.getInputStream(found)){bytes=Core.read(in,MAX_PREVIEW_BYTES);}
                CRC32 crc=new CRC32();crc.update(bytes);
                if(bytes.length!=found.getSize()||crc.getValue()!=found.getCrc()||!previewMagic(bytes))continue;
                File output=new File(dir,"preview.image");
                try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){out.write(bytes);}
                return output;
            }catch(IOException ignored){ /* 缺失、坏图和私有预览写入失败均不阻止有效 HWT。 */ }
        }
        return null;
    }
    private static boolean previewMagic(byte[] b){
        if(b.length>=8&&(b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10)return true;
        if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return true;
        if(b.length>=12&&b[0]==82&&b[1]==73&&b[2]==70&&b[3]==70&&b[8]==87&&b[9]==69&&b[10]==66&&b[11]==80)return true;
        return b.length>=6&&b[0]==71&&b[1]==73&&b[2]==70&&b[3]==56&&(b[4]==55||b[4]==57)&&b[5]==97;
    }
    private static Map<String,ZipEntry> index(ZipFile zip) throws IOException {
        Map<String,ZipEntry> map=new HashMap<String,ZipEntry>();Enumeration<? extends ZipEntry> all=zip.entries();int count=0;
        while(all.hasMoreElements()) { ZipEntry e=all.nextElement();if(++count>12000)throw new Core.Fault("ZIP_ENTRY_LIMIT");
            if(map.put(e.getName(),e)!=null)throw new Core.Fault("ZIP_DUPLICATE_ENTRY"); }
        return map;
    }
    private static byte[] readMember(ZipFile zip,ZipEntry e,int max) throws IOException {
        if(e.isDirectory() || e.getSize()<=0 || e.getSize()>max)throw new Core.Fault("DESCRIPTION_SIZE_LIMIT");
        byte[] b;try(InputStream in=zip.getInputStream(e)){b=Core.read(in,max);}
        CRC32 crc=new CRC32();crc.update(b);if(b.length!=e.getSize() || crc.getValue()!=e.getCrc())throw new Core.Fault("DESCRIPTION_CRC");return b;
    }
    private static void extract(ZipFile zip,ZipEntry e,File output) throws IOException {
        if(e==null || e.isDirectory() || e.getSize()<=0 || e.getSize()>Core.MAX_MEMBER)throw new Core.Fault("RESOURCE_SIZE_LIMIT");
        if(e.getCompressedSize()>0 && e.getSize()>e.getCompressedSize()*500L)throw new Core.Fault("ZIP_EXPANSION_LIMIT");
        CRC32 crc=new CRC32();long size=0;byte[] buf=new byte[65536];int n;
        try(InputStream in=zip.getInputStream(e);OutputStream out=new BufferedOutputStream(new FileOutputStream(output))) {
            while((n=in.read(buf))!=-1) { size+=n;if(size>Core.MAX_MEMBER)throw new Core.Fault("RESOURCE_SIZE_LIMIT");crc.update(buf,0,n);out.write(buf,0,n); }
        }
        if(size!=e.getSize() || crc.getValue()!=e.getCrc())throw new Core.Fault("RESOURCE_CRC");
    }
    private static boolean zipMagic(File file) throws IOException {
        try(InputStream in=new FileInputStream(file)) {
            if(in.read()!=80 || in.read()!=75)return false;
            int a=in.read(),b=in.read();return (a==3&&b==4)||(a==5&&b==6)||(a==7&&b==8);
        }
    }
    private static String[] description(byte[] bytes) throws IOException {
        String text=Core.utf8(bytes),upper=text.toUpperCase(Locale.US);
        if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))throw new Core.Fault("XML_DTD_FORBIDDEN");
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setValidating(false);f.setNamespaceAware(false);f.setExpandEntityReferences(false);
            DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver(new EntityResolver(){public InputSource resolveEntity(String p,String s){return new InputSource(new StringReader(""));}});
            b.setErrorHandler(new DefaultHandler(){public void warning(SAXParseException e)throws SAXException{throw e;}public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            Element root=b.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if(root==null || !"HwTheme".equals(root.getTagName()))throw new Core.Fault("DESCRIPTION_ROOT_UNSUPPORTED");
            return new String[]{field(root,"version","unknown"),field(root,"screen","unknown"),field(root,"title","HWT locale")};
        } catch(Core.Fault e){throw e;}catch(Exception e){throw new Core.Fault("DESCRIPTION_XML_INVALID");}
    }
    private static String field(Element root,String name,String fallback) throws IOException {
        NodeList all=root.getChildNodes();String result=null;
        for(int i=0;i<all.getLength();i++){Node n=all.item(i);if(n.getNodeType()!=Node.ELEMENT_NODE || !name.equals(n.getNodeName()))continue;
            if(result!=null)throw new Core.Fault("DESCRIPTION_DUPLICATE_FIELD");
            for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())if(c.getNodeType()==Node.ELEMENT_NODE)throw new Core.Fault("DESCRIPTION_NESTED_FIELD");
            result=n.getTextContent().trim();if(result.length()>160)result=result.substring(0,160);
        }
        return result==null||result.isEmpty()?fallback:Core.clean(result);
    }
    public synchronized Snapshot freeze(Mode mode) throws IOException {
        if(mode!=Mode.BIN && mode!=Mode.HWT && mode!=Mode.CONTAINER && mode!=Mode.CONTAINER_NO_BIN)throw new Core.Fault("PAYLOAD_MODE_REQUIRED");
        if(mode==Mode.CONTAINER&&!payloadContainer())throw new Core.Fault("PAYLOAD_CONTAINER_UNAVAILABLE");
        File input;String extension,expected;
        switch(mode){
            case HWT: input=original;extension=".hwt";expected=sourceHash;break;
            case BIN: input=bin;extension=".bin";expected=binHash;break;
            case CONTAINER: input=container;extension=".wfc";expected=containerHash;break;
            default: input=strippedContainer();extension=".wfc";expected=strippedHash;break;
        }
        if(input==null||expected==null)throw new Core.Fault(mode==Mode.CONTAINER||mode==Mode.CONTAINER_NO_BIN?"PAYLOAD_CONTAINER_UNAVAILABLE":"ROOT_WATCHFACE_BIN_MISSING");
        File file=new File(directory,"payload-"+UUID.randomUUID().toString()+extension);
        try{
            long count;
            try(InputStream in=new BufferedInputStream(new FileInputStream(input));OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){count=Core.copy(in,out,Core.MAX_MEMBER);}
            String hash=Core.hex(Core.digest(file));
            if(count<=0 || count!=input.length() || !hash.equals(expected))throw new Core.Fault("SOURCE_CHANGED_DURING_FREEZE");
            if(!file.setReadOnly())throw new Core.Fault("PRIVATE_PAYLOAD_READONLY_FAILED");
            return new Snapshot(this,file,(int)count,hash,mode);
        }catch(IOException|RuntimeException failure){
            try{discardGeneratedFile(file);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    /**
     * 仅在“内层同时含 watchface.bin 与 watchface/ 资源树”时生成派生容器：
     * 逐成员复制原始内容，只去掉根 watchface.bin，不做任何内容改写。
     */
    private File strippedContainer() throws IOException {
        if(!payloadContainerWithoutBin())throw new Core.Fault("PAYLOAD_CONTAINER_WITHOUT_BIN_UNAVAILABLE");
        if(strippedContainerFile!=null){
            if(!strippedContainerFile.isFile()||!strippedHash.equals(Core.hex(Core.digest(strippedContainerFile))))throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
            return strippedContainerFile;
        }
        // 不能以改过的来源生成一份“新合法”派生包；必须仍是首次解析的同一内层字节。
        if(!containerHash.equals(Core.hex(Core.digest(container))))throw new Core.Fault("SOURCE_CHANGED_DURING_FREEZE");
        File output=new File(directory,"container-no-bin-"+UUID.randomUUID().toString()+".zip");
        try{
            long total=0;int count=0;
            try(ZipFile zip=new ZipFile(container)){
                Map<String,ZipEntry> originals=index(zip);
                validateAllModeContainer(zip,originals);
                try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))){
                    Enumeration<? extends ZipEntry> all=zip.entries();
                    while(all.hasMoreElements()){
                        ZipEntry entry=all.nextElement();
                        if("watchface.bin".equals(entry.getName()))continue;
                        validateContainerEntry(entry);
                        if(++count>12000)throw new Core.Fault("ZIP_ENTRY_LIMIT");
                        total+=entry.getSize();if(total>Core.MAX_MEMBER)throw new Core.Fault("CONTAINER_EXPANDED_SIZE_LIMIT");
                        ZipEntry copy=new ZipEntry(entry.getName());copy.setTime(0L);copy.setMethod(entry.getMethod());
                        if(entry.getMethod()==ZipEntry.STORED){copy.setSize(entry.getSize());copy.setCrc(entry.getCrc());}
                        out.putNextEntry(copy);
                        if(!entry.isDirectory())checkContainerMember(zip,entry,out);
                        out.closeEntry();
                    }
                }
                if(count==0||total<=0||output.length()>Core.MAX_MEMBER)throw new Core.Fault("PAYLOAD_CONTAINER_WITHOUT_BIN_UNAVAILABLE");
                try(ZipFile derived=new ZipFile(output)){
                    Map<String,ZipEntry> kept=index(derived);
                    if(kept.containsKey("watchface.bin")||kept.size()!=originals.size()-1)throw new Core.Fault("CONTAINER_PROJECTION_MISMATCH");
                    for(ZipEntry entry:kept.values()){
                        ZipEntry before=originals.get(entry.getName());
                        if(before==null||entry.getSize()!=before.getSize()||entry.getCrc()!=before.getCrc())throw new Core.Fault("CONTAINER_PROJECTION_MISMATCH");
                    }
                    validateAllModeContainer(derived,kept);
                }
            }
            if(!containerHash.equals(Core.hex(Core.digest(container))))throw new Core.Fault("SOURCE_CHANGED_DURING_FREEZE");
            String hash=Core.hex(Core.digest(output));
            if(!output.setReadOnly())throw new Core.Fault("PRIVATE_PAYLOAD_READONLY_FAILED");
            strippedHash=hash;strippedContainerFile=output;
            return output;
        }catch(IOException|RuntimeException failure){
            try{discardGeneratedFile(output);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    private void discardGeneratedFile(File file)throws IOException{
        File parent=directory.getCanonicalFile(),target=file.getCanonicalFile();
        if(!parent.equals(target.getParentFile())||!target.getName().matches("(?:payload|container-no-bin)-[0-9a-f-]{36}\\.(?:bin|hwt|wfc|zip)"))throw new Core.Fault("CLEANUP_PATH_REJECTED");
        if(target.exists()&&!target.delete())throw new Core.Fault("CLEANUP_FAILED");
    }
    public static final class Snapshot {
        public final HwtSource source;public final File file;public final int size;public final String hash;public final Mode mode;
        Snapshot(HwtSource source,File file,int size,String hash,Mode mode){this.source=source;this.file=file;this.size=size;this.hash=hash;this.mode=mode;}
        public void verify() throws IOException {if(file.length()!=size || !Core.hex(Core.digest(file)).equals(hash))throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");}
    }
    /** 只删除经验证、由本应用生成的直接子目录；绝不沿用外部目录路径。 */
    public static void cleanup(File privateFiles,File directory) throws IOException {
        File parent=new File(privateFiles,"local-hwt").getCanonicalFile(),target=directory.getCanonicalFile();
        if(!parent.equals(target.getParentFile()) || !target.getName().matches("source-[0-9a-f-]{36}"))throw new Core.Fault("CLEANUP_PATH_REJECTED");
        File[] children=target.listFiles();if(children!=null)for(File file:children){
            if(!target.equals(file.getCanonicalFile().getParentFile()) || file.isDirectory())throw new Core.Fault("CLEANUP_PATH_REJECTED");
            if(!file.delete())throw new Core.Fault("CLEANUP_FAILED");
        }
        if(target.exists() && !target.delete())throw new Core.Fault("CLEANUP_FAILED");
    }
}
