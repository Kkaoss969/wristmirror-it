package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, bounded sequencing of optional 27/06 name queries for one observed resource list.
 * There is no version or nonce on that command: the owner must serialize access, guard
 * the actual epoch/source-list reference and operation priority, and cancel obsolete states.
 * This class neither sends commands nor schedules timeouts; all time is supplied by the owner.
 */
public final class NameQueryState {
    public static final int MAX_BATCH_SIZE=64;
    public static final long REPLY_TIMEOUT_MS=8000L;

    /** One immutable request snapshot; ids are the full batch, not mutable remaining IDs. */
    public static final class Batch {
        public final long serial,startedAt;
        public final List<TlvView.Face> faces;
        public final Set<String> ids;
        private Batch(long serial,long startedAt,List<TlvView.Face> faces,Set<String> ids) {
            this.serial=serial;this.startedAt=startedAt;
            this.faces=Collections.unmodifiableList(new ArrayList<TlvView.Face>(faces));
            this.ids=Collections.unmodifiableSet(new LinkedHashSet<String>(ids));
        }
    }

    // Every retained queue/set is bounded by the <=512 input faces; remainingIds by 64.
    private final ArrayDeque<TlvView.Face> pending=new ArrayDeque<TlvView.Face>();
    private final Set<String> sentIds=new LinkedHashSet<String>();
    private final Set<String> remainingIds=new LinkedHashSet<String>();
    private final Set<String> answeredIds=new LinkedHashSet<String>();
    private long serial;
    private Batch current;
    private boolean cancelled;
    private int unanswered;

    /**
     * knownKeys contains cached ID_version names. Only valid resources count: an ID is
     * skipped iff every valid listed version is known. Query order and representatives
     * follow each ID's first valid occurrence, retaining the original immutable Face
     * and its raw ID/version/type/expandType fields. Inputs are not retained or changed;
     * a null knownKeys is empty, and unrelated cache entries consume no state capacity.
     */
    public NameQueryState(List<TlvView.Face> faces,Set<String> knownKeys) throws IOException {
        if(faces==null)throw new Core.Fault("NAMES_QUERY_FACES_REQUIRED");
        if(faces.size()>TlvView.MAX_FIELDS)throw new Core.Fault("NAMES_QUERY_FIELDS_LIMIT");
        LinkedHashMap<String,TlvView.Face> reps=new LinkedHashMap<String,TlvView.Face>();
        Set<String> unknownIds=new LinkedHashSet<String>();
        for(TlvView.Face face:faces){
            if(face==null || !face.validResource())continue;
            if(!reps.containsKey(face.id))reps.put(face.id,face);
            if(knownKeys==null || !knownKeys.contains(face.id+"_"+face.version))unknownIds.add(face.id);
        }
        for(Map.Entry<String,TlvView.Face> entry:reps.entrySet())
            if(unknownIds.contains(entry.getKey()))pending.addLast(entry.getValue());
    }

    /**
     * Returns a new batch only when idle, never retries removed IDs, and records these
     * IDs as sent by this state. The owner must actually dispatch it or failCurrent().
     * An overdue current batch still blocks next until the owner explicitly expires it.
     */
    public Batch next(long now) {
        if(cancelled || current!=null || pending.isEmpty())return null;
        List<TlvView.Face> faces=new ArrayList<TlvView.Face>(MAX_BATCH_SIZE);
        Set<String> ids=new LinkedHashSet<String>();
        while(faces.size()<MAX_BATCH_SIZE && !pending.isEmpty()){
            TlvView.Face face=pending.removeFirst();faces.add(face);ids.add(face.id);
        }
        current=new Batch(++serial,now,faces,ids);
        sentIds.addAll(ids);remainingIds.addAll(ids);
        return current;
    }

    public Batch current() { return current; }

    /**
     * Matches names only for this state's actually sent IDs. Valid reported IDs without
     * names also count as answered. 0622/0627 日志：手表对每条 27/06 只回一次（60–110 ms），
     * 且只回它确实保存了名字的 ID（16 个 ID 回 3 个）。因此任何回包都结束当前批次：
     * 继续等待未回包的 ID 只是把可选查询拖成 8 s 超时，再从子集补查一次更不被回答。
     * A null response or any response after cancel has no effect and yields an empty map.
     */
    public Map<String,String> accept(WireNames.Response response) {
        if(cancelled || response==null)return Collections.emptyMap();
        LinkedHashMap<String,String> matched=new LinkedHashMap<String,String>();
        for(Map.Entry<String,String> entry:response.names.entrySet())
            if(sentIds.contains(entry.getKey()))matched.put(entry.getKey(),entry.getValue());
        for(String id:response.ids)answeredIds.add(id);
        if(current!=null){
            boolean mentioned=false;
            for(String id:response.ids)if(remainingIds.contains(id)){mentioned=true;break;}
            if(mentioned){
                remainingIds.removeAll(response.ids);
                unanswered=remainingIds.size();
                current=null;remainingIds.clear();
            }
        }
        return Collections.unmodifiableMap(matched);
    }

    /** 最近一次回包中没有名字的已发送 ID 数；只用于诊断，绝不据此再发查询。 */
    public int unanswered(){return unanswered;}

    /** Abandons only the current batch; the next unsent IDs can continue, without retry. */
    public void failCurrent() { current=null;remainingIds.clear();unanswered=0; }

    /** Clears a current batch iff at least 8s elapsed; backward timestamps do not expire. */
    public boolean expire(long now) {
        if(current==null || now<current.startedAt)return false;
        long elapsed=now-current.startedAt;
        // A negative delta here is forward subtraction overflow, necessarily beyond 8s.
        if(elapsed>=0 && elapsed<REPLY_TIMEOUT_MS)return false;
        failCurrent();return true;
    }

    /** True once no queued/current work remains, including a cancelled state. */
    public boolean done() { return pending.isEmpty() && current==null; }

    /** Terminal: releases retained work and refuses both names and new batches. */
    public void cancel() {
        cancelled=true;pending.clear();sentIds.clear();answeredIds.clear();failCurrent();
    }
}
