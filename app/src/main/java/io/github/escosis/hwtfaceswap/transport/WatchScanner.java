package io.github.escosis.hwtfaceswap.transport;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;

/** 用户主动触发的有界经典蓝牙与 BLE 扫描，同时列出已配对设备；不修改配对关系。 */
public final class WatchScanner {
    public static final class Item {public final String address,name;public final boolean bonded,bleOnly;public final Integer rssi;Item(String a,String n,boolean b,boolean le,Integer r){address=a;name=n;bonded=b;bleOnly=le;rssi=r;}}
    public interface Listener {void onItems(List<Item> items);void onStopped(String reason);}
    private final Context context;private final BluetoothAdapter adapter;private final Handler main=new Handler(Looper.getMainLooper());private final LinkedHashMap<String,Item> items=new LinkedHashMap<>();
    private Listener listener;private boolean active,registered;private long serial;
    public WatchScanner(Context c){context=c.getApplicationContext();BluetoothManager m=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);adapter=m==null?null:m.getAdapter();}
    public static String[] permissions(){return Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};}
    public boolean hasPermissions(){for(String p:permissions())if(context.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;return true;}
    public BluetoothAdapter adapter(){return adapter;}
    @SuppressLint("MissingPermission") public void start(Listener callback){stop();listener=callback;if(!hasPermissions()){callback.onStopped("BLUETOOTH_PERMISSION_REQUIRED");return;}if(adapter==null||!adapter.isEnabled()){callback.onStopped("BLUETOOTH_DISABLED");return;}active=true;long token=++serial;items.clear();
        try{for(BluetoothDevice d:adapter.getBondedDevices())add(d,null);IntentFilter f=new IntentFilter();f.addAction(BluetoothDevice.ACTION_FOUND);f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);else context.registerReceiver(receiver,f);registered=true;adapter.startDiscovery();BluetoothLeScanner le=adapter.getBluetoothLeScanner();if(le!=null)le.startScan(Collections.emptyList(),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan);main.postDelayed(()->{if(active&&serial==token){stop();callback.onStopped("Scansione completata");}},12000L);}catch(SecurityException e){stop();callback.onStopped("BLUETOOTH_PERMISSION_REQUIRED");}catch(RuntimeException e){stop();callback.onStopped("SCAN_FAILED");}}
    @SuppressLint("MissingPermission") private void add(BluetoothDevice d,Integer rssi){if(!active||d==null)return;try{String n=d.getName();if(n==null)n="Dispositivo senza nome";String low=n.toLowerCase(Locale.ROOT);if(!low.contains("huawei")&&!low.contains("honor")&&!low.contains("watch")&&!low.contains("华为")&&!low.contains("荣耀"))return;String a=d.getAddress();items.put(a,new Item(a,n.length()>80?n.substring(0,80):n,d.getBondState()==BluetoothDevice.BOND_BONDED,d.getType()==BluetoothDevice.DEVICE_TYPE_LE,rssi));if(listener!=null)listener.onItems(Collections.unmodifiableList(new ArrayList<>(items.values())));}catch(SecurityException ignored){}}
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(!active)return;if(BluetoothDevice.ACTION_FOUND.equals(i.getAction())){BluetoothDevice d=Build.VERSION.SDK_INT>=33?i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class):i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);short r=i.getShortExtra(BluetoothDevice.EXTRA_RSSI,Short.MIN_VALUE);add(d,r==Short.MIN_VALUE?null:(int)r);}}};
    private final ScanCallback scan=new ScanCallback(){public void onScanResult(int type,ScanResult result){main.post(()->add(result.getDevice(),result.getRssi()));}public void onScanFailed(int error){if(listener!=null)main.post(()->listener.onStopped("BLE_SCAN_ERROR_"+error));}};
    @SuppressLint("MissingPermission") public void stop(){active=false;serial++;if(registered){try{context.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}registered=false;}if(adapter!=null)try{adapter.cancelDiscovery();BluetoothLeScanner le=adapter.getBluetoothLeScanner();if(le!=null)le.stopScan(scan);}catch(SecurityException ignored){}catch(RuntimeException ignored){}}
}
