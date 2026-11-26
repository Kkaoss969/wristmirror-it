package io.github.escosis.hwtfaceswap.transport;


import io.github.escosis.hwtfaceswap.Tr;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用华为自定义 SDP UUID 的串行 RFCOMM 传输；不猜测通道 1，也不以 removeBond 作为回退。 */
public final class RfcommChannel implements LinkChannel {
    public static final UUID HUAWEI_SDP=UUID.fromString("82FF3820-8411-400C-B85A-55BDB32CF060");
    private final BluetoothAdapter adapter;private final BluetoothDevice device;private final long epoch;private final Listener listener;private final boolean secure;
    private final ExecutorService reader=Executors.newSingleThreadExecutor(),writer=Executors.newSingleThreadExecutor();private final AtomicBoolean closed=new AtomicBoolean(),reported=new AtomicBoolean();
    private volatile BluetoothSocket socket;private volatile OutputStream output;private final AtomicInteger queuedBytes=new AtomicInteger();
    public RfcommChannel(BluetoothAdapter adapter,BluetoothDevice device,long epoch,Listener listener,boolean secure){this.adapter=adapter;this.device=device;this.epoch=epoch;this.listener=listener;this.secure=secure;}

    @SuppressLint("MissingPermission") public void connect(){reader.execute(()->{
        String phase="CANCEL_DISCOVERY";
        try{
            adapter.cancelDiscovery();
            phase="CREATE_SOCKET";
            BluetoothSocket created=secure?device.createRfcommSocketToServiceRecord(HUAWEI_SDP):device.createInsecureRfcommSocketToServiceRecord(HUAWEI_SDP);
            socket=created;if(closed.get()){created.close();return;}
            phase="CONNECT";created.connect();if(closed.get()){created.close();return;}
            phase="GET_OUTPUT_STREAM";output=created.getOutputStream();
            phase="GET_INPUT_STREAM";InputStream input=created.getInputStream();
            phase="GET_DEVICE_NAME";String name=device.getName();
            phase="OPEN_CALLBACK";listener.onOpen(epoch,name==null?Tr.tr("华为手表", "Watch Huawei", "Huawei watch", "Reloj Huawei", "Montre Huawei", "Relógio Huawei", "Relógio Huawei", "Часовник Huawei", "Huawei saat", "Huawei-Uhr", "Часы Huawei"):name);
            phase="READ";byte[] b=new byte[4096];while(!closed.get()){
                int n=input.read(b);if(n<0){fail(eofDetail(secure));return;}
                if(n>0)try{listener.onBytes(epoch,Arrays.copyOf(b,n));}catch(RuntimeException e){fail(failureDetail("READ_CALLBACK",secure,e));return;}
            }
        }catch(SecurityException e){fail(failureDetail(phase,secure,e));}
        catch(IOException e){fail(failureDetail(phase,secure,e));}
        catch(RuntimeException e){fail(failureDetail(phase,secure,e));}
    });}

    /** Safe metadata only: never include the platform exception message or device identity. */
    static String failureDetail(String phase,boolean secure,Throwable error){
        String safePhase=safePhase(phase),exception=error==null?"UnknownException":error.getClass().getSimpleName();
        if(exception.isEmpty())exception="UnknownException";
        String common=" phase="+safePhase+" transport=rfcomm_"+(secure?"secure":"insecure")+" sdp_uuid="+HUAWEI_SDP;
        return error instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED"+common+" exception="+exception:
                "RFCOMM_"+safePhase+"_FAILED"+common+" exception="+exception;
    }
    static String eofDetail(boolean secure){return "RFCOMM_EOF phase=READ transport=rfcomm_"+(secure?"secure":"insecure")+" sdp_uuid="+HUAWEI_SDP;}
    private static String safePhase(String phase){
        if("CANCEL_DISCOVERY".equals(phase)||"CREATE_SOCKET".equals(phase)||"CONNECT".equals(phase)||
                "GET_OUTPUT_STREAM".equals(phase)||"GET_INPUT_STREAM".equals(phase)||"GET_DEVICE_NAME".equals(phase)||
                "OPEN_CALLBACK".equals(phase)||"READ".equals(phase)||"READ_CALLBACK".equals(phase)||"WRITE".equals(phase))return phase;
        return "UNKNOWN";
    }

    public void send(List<byte[]> frames,int peerMtu,int intervalMs)throws IOException{
        if(closed.get()||output==null)throw new IOException("TRANSPORT_NOT_READY");if(frames==null||frames.size()>256)throw new IOException("FRAME_QUEUE_LIMIT");
        final List<byte[]> owned=new ArrayList<>();int total=0;for(byte[] f:frames){if(f==null||(total+=f.length)>2*1024*1024)throw new IOException("FRAME_QUEUE_LIMIT");owned.add(f.clone());}
        final int mtu=peerMtu>=16&&peerMtu<=65540?peerMtu:1024,delay=Math.max(0,Math.min(intervalMs,1000));
        final int reservation=total;if(queuedBytes.addAndGet(reservation)>4*1024*1024){queuedBytes.addAndGet(-reservation);throw new IOException("RFCOMM_QUEUE_LIMIT");}
        try{writer.execute(()->{try{for(byte[] frame:owned){for(int p=0;p<frame.length;p+=mtu){if(closed.get())return;OutputStream out=output;if(out==null)throw new IOException();out.write(frame,p,Math.min(mtu,frame.length-p));out.flush();if(delay>0)Thread.sleep(delay);}}}catch(Exception e){fail(failureDetail("WRITE",secure,e));}finally{queuedBytes.addAndGet(-reservation);}});}catch(RejectedExecutionException e){queuedBytes.addAndGet(-reservation);throw new IOException("TRANSPORT_CLOSED");}
    }
    private void fail(String why){if(!closed.get()&&reported.compareAndSet(false,true))listener.onFailure(epoch,why);close();}
    public void close(){if(!closed.compareAndSet(false,true))return;BluetoothSocket s=socket;output=null;if(s!=null)try{s.close();}catch(IOException ignored){}reader.shutdownNow();writer.shutdownNow();}
}
