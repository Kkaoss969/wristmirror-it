package io.github.escosis.hwtfaceswap.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import io.github.escosis.hwtfaceswap.protocol.Core;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 使用 Android Keystore 加密连接资料，凭据按设备蓝牙地址分别保存。 */
public final class SecureStore {
    private static final String ALIAS="gt7faces.private.preferences.v1";
    private final SharedPreferences prefs;
    private final SecureRandom random;
    private final Encryption encryption;
    private final DeviceRemovalJournal removals;
    /** 明确的加解密平台边界；生产构造器始终使用 Android Keystore，普通 JVM 测试不执行 Keystore。 */
    public interface Encryption { byte[] seal(String name,byte[] value)throws Exception; byte[] open(String name,byte[] value)throws Exception; }
    public SecureStore(Context context){this(context,null,new SecureRandom());}
    public SecureStore(Context context,Encryption encryption,SecureRandom random){
        prefs=context.getApplicationContext().getSharedPreferences("private-connection-state",Context.MODE_PRIVATE);
        this.encryption=encryption;this.random=java.util.Objects.requireNonNull(random);removals=new DeviceRemovalJournal(context);
    }
    public DeviceRemovalJournal removals(){return removals;}
    private SecretKey key()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());gen.generateKey();
        }
        return(SecretKey)ks.getKey(ALIAS,null);
    }
    private synchronized byte[] get(String name)throws IOException{
        String value=prefs.getString(name,null);if(value==null)return null;
        try{byte[] packed=Base64.decode(value,Base64.NO_WRAP);if(encryption!=null)return encryption.open(name,packed);if(packed.length<30||packed[0]!=1)throw new IllegalArgumentException();int n=packed[1]&255;if(n<12||n>32||packed.length<2+n+16)throw new IllegalArgumentException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(packed,2,2+n)));c.updateAAD(name.getBytes(StandardCharsets.UTF_8));return c.doFinal(Arrays.copyOfRange(packed,2+n,packed.length));
        }catch(Exception e){throw new Core.Fault("LOCAL_SECURE_STORAGE_UNAVAILABLE");}
    }
    private synchronized void put(String name,byte[] value)throws IOException{
        try{byte[] packed;
            if(encryption!=null)packed=encryption.seal(name,value.clone());
            else{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(name.getBytes(StandardCharsets.UTF_8));byte[] iv=c.getIV(),data=c.doFinal(value);packed=new byte[2+iv.length+data.length];packed[0]=1;packed[1]=(byte)iv.length;System.arraycopy(iv,0,packed,2,iv.length);System.arraycopy(data,0,packed,2+iv.length,data.length);}
            if(!prefs.edit().putString(name,Base64.encodeToString(packed,Base64.NO_WRAP)).commit())throw new IOException();
        }catch(Exception e){throw new Core.Fault("LOCAL_SECURE_STORAGE_WRITE_FAILED");}
    }
    private String suffix(String mac)throws IOException{if(mac==null||!mac.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"))throw new Core.Fault("DEVICE_ADDRESS_INVALID");return Core.hex(Core.digest(mac.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)));}
    public byte[] identity(String mac)throws IOException{
        synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);String name="identity_"+suffix(mac);byte[] id=get(name);
            if(id==null){byte[] seed=new byte[16];random.nextBytes(seed);id=Core.hex(seed).getBytes(StandardCharsets.US_ASCII);put(name,id);Arrays.fill(seed,(byte)0);}
            if(id.length!=32||!new String(id,StandardCharsets.US_ASCII).matches("[0-9a-f]{32}"))throw new Core.Fault("LOCAL_IDENTITY_INVALID");return id.clone();}
    }
    public String virtualMac()throws IOException{
        synchronized(DeviceRemovalJournal.LOCK){byte[] stored=get("virtual_mac");if(stored==null){byte[] b=new byte[3];random.nextBytes(b);String v=String.format(Locale.ROOT,"FF:FF:FF:%02X:%02X:%02X",b[0]&255,b[1]&255,b[2]&255);stored=v.getBytes(StandardCharsets.US_ASCII);put("virtual_mac",stored);}return new String(stored,StandardCharsets.US_ASCII);}
    }
    public String virtualMac(String mac)throws IOException{synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);String name="virtual_mac_"+suffix(mac);byte[] stored=get(name);if(stored==null){byte[] b=new byte[3];random.nextBytes(b);stored=String.format(Locale.ROOT,"FF:FF:FF:%02X:%02X:%02X",b[0]&255,b[1]&255,b[2]&255).getBytes(StandardCharsets.US_ASCII);put(name,stored);}return new String(stored,StandardCharsets.US_ASCII);}}
    public String huaweiId(String mac)throws IOException{synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);byte[] b=get("huawei_id_"+suffix(mac));return b==null?"":Core.utf8(b);}}
    /** 每个设备独立保存华为 ID，账户值不写入共享的最近使用槽。 */
    public void saveHuaweiId(String mac,String id)throws IOException{synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);String s=id==null?"":id.trim();if(s.getBytes(StandardCharsets.UTF_8).length>128)throw new Core.Fault("HUAWEI_ID_TOO_LONG");put("huawei_id_"+suffix(mac),s.getBytes(StandardCharsets.UTF_8));}}
    public byte[] authKey(String mac)throws IOException{synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);byte[] key=get("auth_"+suffix(mac));return key==null?null:key.clone();}}
    public void saveAuthKey(String mac,byte[] bytes)throws IOException{synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);if(bytes==null||(bytes.length!=16&&bytes.length!=24&&bytes.length!=32))throw new Core.Fault("AUTH_KEY_LENGTH_UNSUPPORTED");put("auth_"+suffix(mac),bytes.clone());}}
    public void clearAuthKey(String mac)throws IOException{synchronized(DeviceRemovalJournal.LOCK){removals.requireUsable(mac);if(!prefs.edit().remove("auth_"+suffix(mac)).commit())throw new Core.Fault("LOCAL_AUTH_CLEAR_FAILED");}}
    /**
     * 只移除指定 MAC 的四个加密槽，一次同步 commit；不访问或删除共享 Keystore ALIAS。
     * false/异常表示持久结果未知，调用方必须保留待处理标记且不得宣称资料已清除。
     * commit(false) 的内存删除不代表磁盘删除，重试此操作是幂等的。
     */
    public void clearDevice(String mac)throws IOException{
        synchronized(DeviceRemovalJournal.LOCK){String id=suffix(mac);
            try{if(!prefs.edit().remove("huawei_id_"+id).remove("auth_"+id).remove("identity_"+id).remove("virtual_mac_"+id).commit())throw new Core.Fault("LOCAL_DEVICE_CLEAR_FAILED");}
            catch(RuntimeException e){throw new Core.Fault("LOCAL_DEVICE_CLEAR_FAILED");}}
    }
}
