package io.github.escosis.hwtfaceswap.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 基于已观察协议的 Mode2 Lite 路径；未知的 mode0 不会被默认为已认证。 */
public final class LiteAuth implements AutoCloseable {
    private byte[] activeKey,bondCipherKey,expected,clientNonce;private final LinkCapabilities caps;
    private static byte[] concat(byte[]... parts)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p);return b.toByteArray();}
    public LiteAuth(LinkCapabilities caps,byte[] pin,byte[] saved,SecureRandom random)throws IOException{
        this.caps=caps;if(caps.authMode!=2||caps.serverNonce==null||caps.serverNonce.length!=16)throw new Core.Fault("LITE_AUTH_MODE_OR_NONCE_UNSUPPORTED");clientNonce=new byte[16];random.nextBytes(clientNonce);byte[] nonces=concat(caps.serverNonce,clientNonce);
        activeKey=saved==null?new byte[16]:saved.clone();if(saved==null)random.nextBytes(activeKey);if(activeKey.length!=16&&caps.authVersion==2)throw new Core.Fault("LITE_AUTH_SAVED_KEY_LENGTH");
        if(caps.authVersion==2){bondCipherKey=activeKey.clone();expected=HuaweiCrypto.hmac(concat(activeKey,new byte[]{1,16}),nonces);}
        else{if(pin==null||pin.length==0)throw new Core.Fault("PIN_REQUIRED");byte[] p=HuaweiCrypto.pinBase(pin),magic=HuaweiCrypto.pinMagic(caps.authVersion),root=new byte[16];for(int i=0;i<16;i++)root[i]=(byte)(p[i]^magic[i]);byte[] a=derive(concat(root,new byte[]{1,0}),nonces),b=derive(concat(root,new byte[]{1,16}),nonces);bondCipherKey=Arrays.copyOf(a,16);expected=HuaweiCrypto.hmac(b,nonces);Arrays.fill(p,(byte)0);Arrays.fill(root,(byte)0);Arrays.fill(a,(byte)0);Arrays.fill(b,(byte)0);}
    }
    private byte[] derive(byte[] root,byte[] nonces)throws IOException{if(caps.authAlgorithm!=1)return HuaweiCrypto.hmac(root,nonces);char[] password=Core.hex(root).toUpperCase(Locale.ROOT).toCharArray();byte[] salt=Core.hex(nonces).toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);PBEKeySpec spec=new PBEKeySpec(password,salt,1000,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(Exception e){throw new Core.Fault("PBKDF2_UNAVAILABLE");}finally{Arrays.fill(password,'\0');spec.clearPassword();}}
    public byte[] requestBody(byte[] pin)throws IOException{byte[] n=concat(caps.serverNonce,clientNonce),challenge;if(caps.authVersion==2)challenge=HuaweiCrypto.hmac(concat(activeKey,new byte[]{1,0}),n);else{byte[] p=HuaweiCrypto.pinBase(pin),magic=HuaweiCrypto.pinMagic(caps.authVersion),root=new byte[16];for(int i=0;i<16;i++)root[i]=(byte)(p[i]^magic[i]);byte[] d=derive(concat(root,new byte[]{1,0}),n);challenge=HuaweiCrypto.hmac(d,n);Arrays.fill(d,(byte)0);Arrays.fill(p,(byte)0);Arrays.fill(root,(byte)0);}ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(Core.tlv(1,challenge));out.write(Core.tlv(2,concat(new byte[]{(byte)(caps.authVersion>>>8),(byte)caps.authVersion},clientNonce)));out.write(Core.tlv(3,new byte[]{(byte)caps.authAlgorithm}));return out.toByteArray();}
    public void validate(byte[] body)throws IOException{TlvView.Part f=Wire.unique(TlvView.decode(body),1);if(f==null||!HuaweiCrypto.constantTimeEquals(expected,f.value))throw new Core.Fault("LITE_AUTH_TOKEN_MISMATCH");}
    public byte[] activeKey(){return activeKey.clone();}public byte[] cipherKey(){return bondCipherKey.clone();}
    public void close(){for(byte[] b:new byte[][]{activeKey,bondCipherKey,expected,clientNonce})if(b!=null)Arrays.fill(b,(byte)0);activeKey=null;bondCipherKey=null;expected=null;clientNonce=null;}
}
