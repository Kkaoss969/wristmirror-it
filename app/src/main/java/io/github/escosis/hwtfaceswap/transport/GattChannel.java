package io.github.escosis.hwtfaceswap.transport;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** BLE 传输路径：回调绑定连接实例，CCCD 与写入操作按序执行，队列大小有界。 */
@SuppressLint("MissingPermission")
public final class GattChannel implements LinkChannel {
    private static final UUID SERVICE=UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb"),WRITE=UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb"),NOTIFY=UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;private final BluetoothDevice device;private final long epoch;private final Listener listener;private final HandlerThread thread=new HandlerThread("gt7-gatt");private final Handler work;
    private final AtomicBoolean closed=new AtomicBoolean();private BluetoothGatt gatt;private BluetoothGattCharacteristic write;private boolean discovering,ready,busy,noResponse;private int payload=20,delay=5,queuedBytes;private long writeSerial;private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    public GattChannel(Context c,BluetoothDevice d,long epoch,Listener l){context=c.getApplicationContext();device=d;this.epoch=epoch;listener=l;thread.start();work=new Handler(thread.getLooper());}
    public void connect(){work.post(()->{try{gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);if(gatt==null)fail("GATT_CONNECT_UNAVAILABLE");work.postDelayed(()->{if(!ready&&!closed.get())fail("GATT_SETUP_TIMEOUT");},20000);}catch(RuntimeException e){fail("GATT_CONNECT_FAILED");}});}
    private void discover(){if(closed.get()||discovering||gatt==null)return;discovering=true;if(!gatt.discoverServices())fail("GATT_DISCOVER_FAILED");}
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        public void onConnectionStateChange(BluetoothGatt current,int status,int state){work.post(()->{if(closed.get()||current!=gatt)return;if(status!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){fail("GATT_CONNECTION_CLOSED");return;}if(state==BluetoothProfile.STATE_CONNECTED){if(!current.requestMtu(247))discover();else work.postDelayed(()->{if(current==gatt&&!discovering)discover();},2500);}});}
        public void onMtuChanged(BluetoothGatt current,int mtu,int status){work.post(()->{if(closed.get()||current!=gatt)return;if(status==BluetoothGatt.GATT_SUCCESS&&mtu>=23)payload=Math.min(244,mtu-3);discover();});}
        public void onServicesDiscovered(BluetoothGatt current,int status){work.post(()->{if(closed.get()||current!=gatt)return;if(status!=BluetoothGatt.GATT_SUCCESS){fail("GATT_SERVICE_ERROR");return;}BluetoothGattService s=current.getService(SERVICE);if(s==null){fail("HUAWEI_BLE_SERVICE_MISSING");return;}write=s.getCharacteristic(WRITE);BluetoothGattCharacteristic notify=s.getCharacteristic(NOTIFY);if(write==null||notify==null){fail("HUAWEI_BLE_CHARACTERISTIC_MISSING");return;}noResponse=(write.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0;if(noResponse&&(write.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0){fail("GATT_WRITE_PROPERTY_MISSING");return;}BluetoothGattDescriptor d=notify.getDescriptor(CCCD);if(d==null||!current.setCharacteristicNotification(notify,true)){fail("GATT_CCCD_MISSING");return;}byte[] enable=(notify.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;boolean ok;if(Build.VERSION.SDK_INT>=33)ok=current.writeDescriptor(d,enable)==BluetoothStatusCodes.SUCCESS;else{d.setValue(enable);ok=current.writeDescriptor(d);}if(!ok)fail("GATT_CCCD_WRITE_FAILED");});}
        public void onDescriptorWrite(BluetoothGatt current,BluetoothGattDescriptor d,int status){work.post(()->{if(closed.get()||current!=gatt||!CCCD.equals(d.getUuid()))return;if(status!=BluetoothGatt.GATT_SUCCESS){fail("GATT_CCCD_REJECTED");return;}ready=true;String name;try{name=device.getName();}catch(SecurityException e){name=null;}listener.onOpen(epoch,name==null?"Dispositivo Huawei":name);pump();});}
        public void onCharacteristicChanged(BluetoothGatt current,BluetoothGattCharacteristic characteristic,byte[] value){if(current==gatt&&!closed.get()&&NOTIFY.equals(characteristic.getUuid()))listener.onBytes(epoch,value.clone());}
        @SuppressWarnings("deprecation") public void onCharacteristicChanged(BluetoothGatt current,BluetoothGattCharacteristic characteristic){byte[] value=characteristic.getValue();if(value!=null)onCharacteristicChanged(current,characteristic,value);}
        public void onCharacteristicWrite(BluetoothGatt current,BluetoothGattCharacteristic c,int status){work.post(()->{if(closed.get()||current!=gatt||!WRITE.equals(c.getUuid()))return;if(status!=BluetoothGatt.GATT_SUCCESS){fail("GATT_WRITE_REJECTED");return;}if(noResponse||!busy)return;completeWrite();});}
    };
    public void send(List<byte[]> frames,int peerMtu,int intervalMs)throws IOException{if(closed.get())throw new IOException("TRANSPORT_CLOSED");if(frames==null||frames.size()>256)throw new IOException("FRAME_QUEUE_LIMIT");List<byte[]> owned=new ArrayList<>();int bytes=0;for(byte[] b:frames){if(b==null||(bytes+=b.length)>2*1024*1024)throw new IOException("FRAME_QUEUE_LIMIT");owned.add(b.clone());}final int total=bytes;
        work.post(()->{if(closed.get())return;if(queuedBytes+total>4*1024*1024){fail("GATT_QUEUE_LIMIT");return;}int mtu=Math.min(payload,peerMtu>=16?peerMtu:payload);delay=Math.max(5,Math.min(intervalMs,1000));for(byte[] b:owned)for(int p=0;p<b.length;p+=mtu)queue.addLast(Arrays.copyOfRange(b,p,Math.min(b.length,p+mtu)));queuedBytes+=total;pump();});}
    private void pump(){if(closed.get()||!ready||busy||queue.isEmpty())return;byte[] b=queue.peekFirst();busy=true;long token=++writeSerial;int type=noResponse?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;boolean ok;
        try{if(Build.VERSION.SDK_INT>=33)ok=gatt.writeCharacteristic(write,b,type)==BluetoothStatusCodes.SUCCESS;else{write.setWriteType(type);write.setValue(b);ok=gatt.writeCharacteristic(write);}}catch(RuntimeException e){ok=false;}if(!ok){fail("GATT_WRITE_START_FAILED");return;}
        if(noResponse)work.postDelayed(()->{if(!closed.get()&&busy&&writeSerial==token)completeWrite();},delay);else work.postDelayed(()->{if(!closed.get()&&busy&&writeSerial==token)fail("GATT_WRITE_TIMEOUT");},5000);
    }
    private void completeWrite(){byte[] b=queue.pollFirst();if(b!=null)queuedBytes-=b.length;busy=false;work.postDelayed(this::pump,delay);}
    private void fail(String code){if(closed.get())return;listener.onFailure(epoch,code);close();}
    public void close(){if(!closed.compareAndSet(false,true))return;work.post(()->{queue.clear();queuedBytes=0;BluetoothGatt g=gatt;gatt=null;if(g!=null){try{g.disconnect();}catch(RuntimeException ignored){}g.close();}thread.quitSafely();});}
}
