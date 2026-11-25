package io.github.escosis.hwtfaceswap.platform;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalController;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 普通 Android 应用边界：只有原本可访问的隐藏方法，不能突破隐藏 API 或权限限制。 */
public final class AndroidSystemUnpair implements DeviceRemovalController.Platform {
    private final Context context;
    private DeviceRemovalController.Event listener;
    private boolean registered;
    public AndroidSystemUnpair(Context context){this.context=context.getApplicationContext();}
    private boolean permission() {
        String permission=Build.VERSION.SDK_INT>=31?Manifest.permission.BLUETOOTH_CONNECT:Manifest.permission.BLUETOOTH;
        return context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }
    private BluetoothManager manager(){return (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);}
    @SuppressLint("MissingPermission")
    public DeviceRemovalController.Bond readBond(String address) {
        String mac=DeviceRemovalJournal.canonical(address);
        if(mac==null)return DeviceRemovalController.Bond.UNKNOWN;
        if(!permission())return DeviceRemovalController.Bond.PERMISSION_REQUIRED;
        try {
            BluetoothManager manager=manager();BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null)return DeviceRemovalController.Bond.UNKNOWN;
            if(!adapter.isEnabled())return DeviceRemovalController.Bond.ADAPTER_OFF;
            BluetoothDevice device=adapter.getRemoteDevice(mac);
            if(device==null||!mac.equalsIgnoreCase(device.getAddress()))return DeviceRemovalController.Bond.UNKNOWN;
            int state=device.getBondState();
            // adapter off/权限撤销时系统可能返回默认 NONE，绝不能把它当作解除配对。
            if(!permission())return DeviceRemovalController.Bond.PERMISSION_REQUIRED;
            if(manager.getAdapter()!=adapter)return DeviceRemovalController.Bond.UNKNOWN;
            if(!adapter.isEnabled())return DeviceRemovalController.Bond.ADAPTER_OFF;
            if(!mac.equalsIgnoreCase(device.getAddress()))return DeviceRemovalController.Bond.UNKNOWN;
            if(state==BluetoothDevice.BOND_NONE){
                // 普通 API 服务异常也可能返回默认 NONE；可见已配对列表若矛盾则保持未知。
                java.util.Set<BluetoothDevice> bonded=adapter.getBondedDevices();
                if(bonded==null)return DeviceRemovalController.Bond.UNKNOWN;
                for(BluetoothDevice other:bonded)if(other!=null&&mac.equalsIgnoreCase(other.getAddress()))return DeviceRemovalController.Bond.UNKNOWN;
                if(!permission())return DeviceRemovalController.Bond.PERMISSION_REQUIRED;
                if(manager.getAdapter()!=adapter)return DeviceRemovalController.Bond.UNKNOWN;
                if(!adapter.isEnabled())return DeviceRemovalController.Bond.ADAPTER_OFF;
                return DeviceRemovalController.Bond.NONE;
            }
            if(state==BluetoothDevice.BOND_BONDED)return DeviceRemovalController.Bond.BONDED;
            if(state==BluetoothDevice.BOND_BONDING)return DeviceRemovalController.Bond.BONDING;
            return DeviceRemovalController.Bond.UNKNOWN;
        } catch(SecurityException e){return DeviceRemovalController.Bond.PERMISSION_REQUIRED;}
        catch(RuntimeException e){return DeviceRemovalController.Bond.UNKNOWN;}
    }
    @SuppressLint("MissingPermission")
    public DeviceRemovalController.Request requestRemoval(String address) {
        String mac=DeviceRemovalJournal.canonical(address);
        if(mac==null)return DeviceRemovalController.Request.UNKNOWN;
        if(!permission())return DeviceRemovalController.Request.PERMISSION_REQUIRED;
        try {
            BluetoothManager manager=manager();BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null)return DeviceRemovalController.Request.UNKNOWN;
            if(!adapter.isEnabled())return DeviceRemovalController.Request.ADAPTER_OFF;
            BluetoothDevice device=adapter.getRemoteDevice(mac);
            if(device==null||!mac.equalsIgnoreCase(device.getAddress()))return DeviceRemovalController.Request.UNKNOWN;
            // 没有 setAccessible、隐藏 API 豁免、特权权限或替代后门。生命周期只授权调用一次。
            Method method=BluetoothDevice.class.getMethod("removeBond");
            if(!permission())return DeviceRemovalController.Request.PERMISSION_REQUIRED;
            if(!adapter.isEnabled())return DeviceRemovalController.Request.ADAPTER_OFF;
            Object result=method.invoke(device);
            return Boolean.TRUE.equals(result)?DeviceRemovalController.Request.ACCEPTED:DeviceRemovalController.Request.REJECTED;
        } catch(NoSuchMethodException|IllegalAccessException e){return DeviceRemovalController.Request.UNSUPPORTED;}
        catch(InvocationTargetException e){
            return !permission()?DeviceRemovalController.Request.PERMISSION_REQUIRED:DeviceRemovalController.Request.REJECTED;
        } catch(SecurityException e){
            return !permission()?DeviceRemovalController.Request.PERMISSION_REQUIRED:DeviceRemovalController.Request.UNSUPPORTED;
        } catch(RuntimeException e){return DeviceRemovalController.Request.UNKNOWN;}
    }
    public synchronized void observe(DeviceRemovalController.Event event) {
        listener=event;if(registered)return;
        IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter);
        registered=true;
    }
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent) {
            DeviceRemovalController.Event callback;
            synchronized(AndroidSystemUnpair.this){callback=listener;}
            if(callback==null||intent==null)return;
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())){callback.changed(null);return;}
            if(!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()))return;
            try {
                BluetoothDevice device=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class):intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String mac=device==null?null:DeviceRemovalJournal.canonical(device.getAddress());
                if(mac!=null)callback.changed(mac);
            } catch(SecurityException e){callback.changed(null);}
            catch(RuntimeException ignoredError){/* 畸形/非设备广播不提供证据。 */}
        }
    };
    public boolean openSettings() {
        try {context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}
        catch(ActivityNotFoundException|SecurityException e){return false;}
        catch(RuntimeException e){return false;}
    }
}
