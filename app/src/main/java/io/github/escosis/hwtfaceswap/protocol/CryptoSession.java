package io.github.escosis.hwtfaceswap.protocol;

import java.io.*;
import java.security.SecureRandom;
import java.util.*;

/** 单次连接的业务加密密钥与 IV 计数器；绝不将其作为持久化认证密钥写入存储。 */
public final class CryptoSession implements AutoCloseable {
    private final SecureRandom random;private final int method,supportType;private byte[] key;private long counter;
    public CryptoSession(byte[] sessionKey,int method,int supportType,long counter,SecureRandom random)throws IOException{if(sessionKey==null||(sessionKey.length!=16&&sessionKey.length!=24&&sessionKey.length!=32))throw new Core.Fault("SESSION_KEY_LENGTH");key=sessionKey.clone();this.method=method;this.supportType=supportType;this.counter=counter&0xffffffffL;this.random=random;}
    public synchronized byte[] nextIv()throws IOException{if(key==null)throw new Core.Fault("SESSION_CLOSED");byte[] iv=new byte[16];random.nextBytes(iv);if(supportType!=4){counter=(counter+1)&0xffffffffL;if(counter==0)counter=1;for(int i=0;i<4;i++)iv[12+i]=(byte)(counter>>>(24-8*i));}return iv;}
    public synchronized byte[] seal(byte[] plain)throws IOException{if(key==null)throw new Core.Fault("SESSION_CLOSED");byte[] iv=nextIv(),cipher=(method==1||supportType==4)?HuaweiCrypto.aesGcmEncrypt(key,iv,plain,null):HuaweiCrypto.aesCbcEncrypt(key,iv,plain);ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(Core.tlv(0x7c,new byte[]{1}));out.write(Core.tlv(0x7d,iv));out.write(Core.tlv(0x7e,cipher));return out.toByteArray();}
    public static boolean wrapped(byte[] body)throws IOException{List<TlvView.Part> p=TlvView.decode(body);TlvView.Part flag=Wire.unique(p,0x7c),iv=Wire.unique(p,0x7d),ct=Wire.unique(p,0x7e);if(flag==null&&iv==null&&ct==null)return false;if(p.size()!=3||flag==null||flag.value.length!=1||flag.value[0]!=1||iv==null||iv.value.length!=16||ct==null||ct.value.length<16)throw new Core.Fault("ENCRYPTED_ENVELOPE_INVALID");return true;}
    public synchronized byte[] open(byte[] body)throws IOException{if(key==null)throw new Core.Fault("SESSION_CLOSED");List<TlvView.Part> p=TlvView.decode(body);TlvView.Part flag=Wire.unique(p,0x7c),iv=Wire.unique(p,0x7d),cipher=Wire.unique(p,0x7e);if(p.size()!=3||flag==null||flag.value.length!=1||flag.value[0]!=1||iv==null||iv.value.length!=16||cipher==null||cipher.value.length<16)throw new Core.Fault("ENCRYPTED_ENVELOPE_INVALID");return(method==1||supportType==4)?HuaweiCrypto.aesGcmDecrypt(key,iv.value,cipher.value,null):HuaweiCrypto.aesCbcDecrypt(key,iv.value,cipher.value);}
    public synchronized void close(){if(key!=null)Arrays.fill(key,(byte)0);key=null;}
}
