package io.github.escosis.hwtfaceswap.lifecycle;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.escosis.hwtfaceswap.protocol.Core;
import java.io.IOException;
import java.util.*;

/**
 * 本应用每 MAC 的待解除配对标记与回调代次。标记必须先持久保存，才可提交系统请求。
 * 不含账号/密钥，也不修改系统、其他应用或全局偏好。
 * SharedPreferences.commit(false) 可能已经改变内存视图：共享注册表仍保留阻断标记，
 * 重试会重新持久保存它；进程重启从磁盘上此前成功的标记恢复。
 */
public final class DeviceRemovalJournal {
    public static final Object LOCK = new Object();
    private static final String PENDING = "pending_", GENERATION = "generation_";
    private static final Map<SharedPreferences, Registry> REGISTRIES = new WeakHashMap<>();
    private final SharedPreferences prefs;
    private final Registry registry;

    public static final class Pending {
        public final String address;
        public final long token, generation;
        public final boolean attempted, cleanupStarted;
        private boolean durable;
        private Pending(String address, long token, long generation, boolean attempted, boolean cleanupStarted, boolean durable) {
            this.address=address;this.token=token;this.generation=generation;
            this.attempted=attempted;this.cleanupStarted=cleanupStarted;this.durable=durable;
        }
        private String encoded() { return token+","+generation+","+(attempted?"1":"0")+","+(cleanupStarted?"1":"0"); }
    }
    private static final class Registry {
        final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
        final Map<String, Long> generations = new HashMap<>();
        boolean unreadable;
    }

    public DeviceRemovalJournal(Context context) {
        prefs=context.getApplicationContext().getSharedPreferences("device-removal-state",Context.MODE_PRIVATE);
        synchronized(LOCK) {
            Registry existing=REGISTRIES.get(prefs);
            if(existing==null) {
                existing=new Registry();
                try {
                    for(Map.Entry<String,?> entry:prefs.getAll().entrySet()) {
                        String key=entry.getKey();Object value=entry.getValue();
                        if(key.startsWith(GENERATION)) {
                            String address=canonical(key.substring(GENERATION.length()));
                            if(address!=null&&value instanceof String) {
                                try { long generation=Long.parseLong((String)value);if(generation<0)throw new NumberFormatException();existing.generations.put(address,Math.max(generation,existing.generations.getOrDefault(address,0L))); }
                                catch(NumberFormatException ignored) { existing.unreadable=true; }
                            }
                        } else if(key.startsWith(PENDING)) {
                            String address=canonical(key.substring(PENDING.length()));
                            if(address==null||!(value instanceof String)) { existing.unreadable=true;continue; }
                            String[] parts=((String)value).split(",",-1);
                            try {
                                if(parts.length!=4||!(parts[2].equals("0")||parts[2].equals("1"))||!(parts[3].equals("0")||parts[3].equals("1"))) throw new NumberFormatException();
                                long token=Long.parseLong(parts[0]),generation=Long.parseLong(parts[1]);
                                if(generation<1)throw new NumberFormatException();
                                Pending record=new Pending(address,token,generation,parts[2].equals("1"),parts[3].equals("1"),true);
                                existing.pending.put(address,record);
                                existing.generations.put(address,Math.max(generation,existing.generations.getOrDefault(address,0L)));
                            } catch(NumberFormatException ignored) { existing.unreadable=true; }
                        }
                    }
                } catch(RuntimeException e) { existing.unreadable=true; }
                REGISTRIES.put(prefs,existing);
            }
            registry=existing;
        }
    }

    public static String canonical(String address) {
        return address!=null&&address.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")?address.toUpperCase(Locale.ROOT):null;
    }
    private String valid(String address)throws IOException {
        String mac=canonical(address);if(mac==null)throw new Core.Fault("DEVICE_ADDRESS_INVALID");return mac;
    }
    private void readable()throws IOException { if(registry.unreadable)throw new Core.Fault("LOCAL_UNPAIR_MARKER_UNREADABLE"); }
    public boolean blocked(String address) {
        String mac=canonical(address);
        synchronized(LOCK) { return registry.unreadable||(mac!=null&&registry.pending.containsKey(mac)); }
    }
    public void requireUsable(String address)throws IOException {
        synchronized(LOCK) { String mac=valid(address);readable();if(registry.pending.containsKey(mac))throw new Core.Fault("DEVICE_UNPAIR_PENDING"); }
    }
    public long generation(String address)throws IOException {
        synchronized(LOCK) { String mac=valid(address);readable();return registry.generations.getOrDefault(mac,0L); }
    }
    /** 入队时捕获代次，即使清理后移除阻断标记，旧连接任务也不能重建资料。 */
    public boolean usable(String address,long expectedGeneration) {
        synchronized(LOCK) {
            String mac=canonical(address);
            return mac!=null&&!registry.unreadable&&!registry.pending.containsKey(mac)&&registry.generations.getOrDefault(mac,0L)==expectedGeneration;
        }
    }
    public List<Pending> pending()throws IOException {
        synchronized(LOCK) { readable();return Collections.unmodifiableList(new ArrayList<>(registry.pending.values())); }
    }
    public Pending begin(String address,long token)throws IOException {
        synchronized(LOCK) {
            String mac=valid(address);readable();Pending record=registry.pending.get(mac);
            if(record==null) {
                if(!registry.pending.isEmpty())throw new Core.Fault("ANOTHER_DEVICE_UNPAIR_PENDING");
                long old=registry.generations.getOrDefault(mac,0L);
                if(old==Long.MAX_VALUE)throw new Core.Fault("DEVICE_GENERATION_EXHAUSTED");
                record=new Pending(mac,token,old+1,false,false,false);
                registry.pending.put(mac,record);registry.generations.put(mac,record.generation);
            } else if(record.token!=token)throw new Core.Fault("UNPAIR_CONFIRMATION_CHANGED");
            persist(record);
            return record;
        }
    }
    public Pending ensureDurable(String address,long token)throws IOException {
        synchronized(LOCK) { Pending record=owned(address,token);if(!record.durable)persist(record);return record; }
    }
    public Pending markAttempted(String address,long token)throws IOException {
        synchronized(LOCK) {
            Pending old=owned(address,token);
            if(old.attempted) { if(!old.durable)persist(old);return old; }
            Pending record=new Pending(old.address,old.token,old.generation,true,old.cleanupStarted,false);
            registry.pending.put(record.address,record);persist(record);return record;
        }
    }
    public Pending markCleanupStarted(String address,long token)throws IOException {
        synchronized(LOCK) {
            Pending old=owned(address,token);
            Pending record=new Pending(old.address,old.token,old.generation,old.attempted,true,false);
            registry.pending.put(record.address,record);persist(record);return record;
        }
    }
    private Pending owned(String address,long token)throws IOException {
        readable();Pending record=registry.pending.get(valid(address));
        if(record==null||record.token!=token)throw new Core.Fault("UNPAIR_CONFIRMATION_CHANGED");return record;
    }
    private void persist(Pending record)throws IOException {
        // 一次提交同时写入标记与代次；在 commit 返回之前就阻断同 MAC 的写入。
        record.durable=false;
        try {
            if(!prefs.edit().putString(PENDING+record.address,record.encoded()).putString(GENERATION+record.address,Long.toString(record.generation)).commit())
                throw new Core.Fault("LOCAL_UNPAIR_MARKER_WRITE_FAILED");
            record.durable=true;
        } catch(RuntimeException e) { throw new Core.Fault("LOCAL_UNPAIR_MARKER_WRITE_FAILED"); }
    }
    /** 只有尚未开始资料清理时才允许撤回标记：此时凭据/历史一条都没有删除。 */
    public void abandon(String address,long token)throws IOException {
        synchronized(LOCK) {
            Pending record=owned(address,token);
            if(record.cleanupStarted)throw new Core.Fault("UNPAIR_CLEANUP_STARTED");
            try {
                if(!prefs.edit().remove(PENDING+record.address).commit()) {
                    record.durable=false;throw new Core.Fault("LOCAL_UNPAIR_MARKER_WRITE_FAILED");
                }
            } catch(RuntimeException e) { record.durable=false;throw new Core.Fault("LOCAL_UNPAIR_MARKER_WRITE_FAILED"); }
            registry.pending.remove(record.address);
            // 保留代次：撤回后旧连接任务的回调仍不能复活资料。
        }
    }
    public void finish(String address,long token)throws IOException {
        synchronized(LOCK) {
            Pending record=owned(address,token);
            try {
                if(!prefs.edit().remove(PENDING+record.address).commit()) {
                    record.durable=false;throw new Core.Fault("LOCAL_UNPAIR_COMPLETION_WRITE_FAILED");
                }
            } catch(RuntimeException e) { record.durable=false;throw new Core.Fault("LOCAL_UNPAIR_COMPLETION_WRITE_FAILED"); }
            registry.pending.remove(record.address);
            // 保留每 MAC 的回调代次，不触及其他 MAC 或共享资料。
        }
    }
}
