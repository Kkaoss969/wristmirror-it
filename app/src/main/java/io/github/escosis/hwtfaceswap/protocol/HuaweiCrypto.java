package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 针对已观察到的运动健康 / HiChain ISO 协议，独立实现 JCA 密码原语。
 * 不含 Android、SDK、账号身份、网络、凭据查找或日志逻辑。
 * 所有字节数组参数均创建快照；结果绝不与调用方输入共享可变引用。
 */
public final class HuaweiCrypto {
    /** 防御性内存分配限制，不代表任何特定手表固件的能力。 */
    public static final int MAX_CRYPTO_BYTES = Core.MAX_CHUNK;
    public static final int MAX_PIN_BYTES = 1024;
    public static final int GCM_TAG_BYTES = 16;
    private static final int MAX_GCM_IV_BYTES = 64;

    // 从静态验证的协议取值方法中取得的公开、固定协议常量。
    // 它们不是任何个人的 PIN、授权凭据或持久化密钥。
    private static final byte[] PIN_VERSION_1_4 = literal("70FB6C24035FDB552F38898AEEDE3F69");
    private static final byte[] PIN_VERSION_2 = literal("93ACDEF76ACB09857DBFE5261AABCD78");
    private static final byte[] PIN_VERSION_OTHER = literal("9C2763A9CCE134766DE3FF6118200553");

    private HuaweiCrypto() {}

    public static byte[] sha256(byte[] data) throws IOException {
        byte[] input = snapshot(data, 0, MAX_CRYPTO_BYTES, "CRYPTO_DATA_LENGTH");
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new Core.Fault("SHA256_UNAVAILABLE");
        } catch (RuntimeException e) {
            throw new Core.Fault("SHA256_FAILED");
        } finally {
            wipe(input);
        }
    }

    /** HMAC-SHA256(key, data)；空密钥按标准使用全零填充。 */
    public static byte[] hmac(byte[] key, byte[] data) throws IOException {
        byte[] k = null;
        byte[] input = null;
        try {
            k = snapshot(key, 0, MAX_CRYPTO_BYTES, "HMAC_KEY_LENGTH");
            input = snapshot(data, 0, MAX_CRYPTO_BYTES, "CRYPTO_DATA_LENGTH");
            // SecretKeySpec 拒绝零长度密钥；单个零字节与空密钥
            // 具有完全相同的 SHA-256 HMAC 分块填充，不涉及算法回退。
            if (k.length == 0) k = new byte[1];
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (NoSuchAlgorithmException e) {
            throw new Core.Fault("HMAC_SHA256_UNAVAILABLE");
        } catch (GeneralSecurityException e) {
            throw new Core.Fault("HMAC_SHA256_FAILED");
        } catch (RuntimeException e) {
            throw new Core.Fault("HMAC_SHA256_FAILED");
        } finally {
            wipe(k);
            wipe(input);
        }
    }

    /**
     * 按 RFC 5869 执行 HKDF-SHA256，恰好输出 32 字节：先提取，再扩展一个分块。
     * 参数顺序为 IKM、salt、info；null 或空 salt 以及 null info 使用 RFC 默认值。
     */
    public static byte[] hkdf32(byte[] ikm, byte[] salt, byte[] info) throws IOException {
        byte[] input = null;
        byte[] s = null;
        byte[] context = null;
        byte[] prk = null;
        byte[] block = null;
        try {
            input = snapshot(ikm, 0, MAX_CRYPTO_BYTES, "HKDF_IKM_LENGTH");
            s = salt == null || salt.length == 0
                    ? new byte[32] : snapshot(salt, 0, MAX_CRYPTO_BYTES, "HKDF_SALT_LENGTH");
            context = info == null ? new byte[0]
                    : snapshot(info, 0, MAX_CRYPTO_BYTES - 1, "HKDF_INFO_LENGTH");
            prk = hmac(s, input);
            block = Arrays.copyOf(context, context.length + 1);
            block[block.length - 1] = 1;
            return hmac(prk, block);
        } finally {
            wipe(input);
            wipe(s);
            wipe(context);
            wipe(prk);
            wipe(block);
        }
    }

    /** 不因数据内容提前退出，处理量只取决于公开的数组长度；null 绝不是有效证明。 */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        byte[] left = a.clone();
        byte[] right = b.clone();
        try {
            int difference = left.length ^ right.length;
            int length = Math.max(left.length, right.length);
            for (int i = 0; i < length; i++) {
                int x = i < left.length ? left[i] & 255 : 0;
                int y = i < right.length ? right[i] & 255 : 0;
                difference |= x ^ y;
            }
            return difference == 0;
        } finally {
            wipe(left);
            wipe(right);
        }
    }

    /** SHA256(UTF8(HEX_UPPER(PIN 原始字节)))；不是直接对原始 PIN 求 SHA256，也不是华为账号散列。 */
    public static byte[] pinBase(byte[] pinRaw) throws IOException {
        byte[] pin = snapshot(pinRaw, 1, MAX_PIN_BYTES, "PIN_LENGTH_INVALID");
        byte[] upperHex = new byte[pin.length * 2];
        final String alphabet = "0123456789ABCDEF";
        try {
            for (int i = 0; i < pin.length; i++) {
                int value = pin[i] & 255;
                upperHex[i * 2] = (byte) alphabet.charAt(value >>> 4);
                upperHex[i * 2 + 1] = (byte) alphabet.charAt(value & 15);
            }
            // ASCII 十六进制文本在 UTF-8 中具有相同字节序列，无需创建无法擦除的 PIN 字符串。
            return sha256(upperHex);
        } finally {
            wipe(pin);
            wipe(upperHex);
        }
    }

    /** 公开、固定的 16 字节 PIN 传输密钥，每次调用均返回新副本。 */
    public static byte[] pinMagic(int authVersion) {
        if (authVersion == 1 || authVersion == 4) return PIN_VERSION_1_4.clone();
        if (authVersion == 2) return PIN_VERSION_2.clone();
        return PIN_VERSION_OTHER.clone();
    }

    /** 保守限定所支持的 AES 密钥长度，不表示设备 Secret 长度经过实测。 */
    public static boolean isAesKeyLength(int length) {
        return length == 16 || length == 24 || length == 32;
    }

    public static void validateKeyLength(int length) throws IOException {
        if (!isAesKeyLength(length)) throw new Core.Fault("AES_KEY_LENGTH_INVALID");
    }

    public static void validateKeyLength(byte[] key) throws IOException {
        if (key == null) throw new Core.Fault("AES_KEY_REQUIRED");
        validateKeyLength(key.length);
    }

    /** 密文包含末尾追加的 128 位认证标签；不在开头附加 IV。 */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] data, byte[] aad)
            throws IOException {
        return gcm(Cipher.ENCRYPT_MODE, key, iv, data, aad);
    }

    /** 仅在 doFinal 验证完整的 128 位 GCM 标签后返回明文。 */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] data, byte[] aad)
            throws IOException {
        return gcm(Cipher.DECRYPT_MODE, key, iv, data, aad);
    }

    public static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] data) throws IOException {
        return cbc(Cipher.ENCRYPT_MODE, key, iv, data);
    }

    /** CBC 填充不等于认证；调用方不得将加解密往返成功视为 MAC 验证。 */
    public static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] data) throws IOException {
        return cbc(Cipher.DECRYPT_MODE, key, iv, data);
    }

    /** 根据实际观察选择业务加密；ISO 始终使用独立的 GCM 与 12 字节 nonce 路径。 */
    public static boolean businessUsesGcm(int encryptMethod, int supportType) {
        return encryptMethod == 1 || supportType == 4;
    }

    public static byte[] businessEncrypt(byte[] key, byte[] iv, byte[] data,
                                         int encryptMethod, int supportType) throws IOException {
        requireLength(iv, 16, 16, "BUSINESS_IV_LENGTH_INVALID");
        return businessUsesGcm(encryptMethod, supportType)
                ? aesGcmEncrypt(key, iv, data, null) : aesCbcEncrypt(key, iv, data);
    }

    public static byte[] businessDecrypt(byte[] key, byte[] iv, byte[] data,
                                         int encryptMethod, int supportType) throws IOException {
        requireLength(iv, 16, 16, "BUSINESS_IV_LENGTH_INVALID");
        return businessUsesGcm(encryptMethod, supportType)
                ? aesGcmDecrypt(key, iv, data, null) : aesCbcDecrypt(key, iv, data);
    }

    /**
     * 处理已观察到的 01/2C PIN 解密；业务层 supportType=4 的覆盖规则不适用于此处。
     * 原生 PIN 调用方未固定 IV 长度：GCM 原语接受 1..64 字节，
     * CBC 必须为 16 字节；不记录 PIN 字节、散列值或含密文的错误信息。
     */
    public static byte[] decryptPin(int authVersion, int encryptMethod, byte[] iv, byte[] encrypted)
            throws IOException {
        byte[] magic = pinMagic(authVersion);
        byte[] result = null;
        try {
            requireLength(encrypted, 1, MAX_PIN_BYTES + 16, "PIN_CIPHERTEXT_LENGTH_INVALID");
            result = encryptMethod == 1
                    ? aesGcmDecrypt(magic, iv, encrypted, null)
                    : aesCbcDecrypt(magic, iv, encrypted);
            requireLength(result, 1, MAX_PIN_BYTES, "PIN_LENGTH_INVALID");
            byte[] copy = result.clone();
            return copy;
        } finally {
            wipe(magic);
            wipe(result);
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] data, byte[] aad)
            throws IOException {
        byte[] k = null;
        byte[] nonce = null;
        byte[] input = null;
        byte[] additional = null;
        try {
            validateKeyLength(key);
            k = key.clone();
            nonce = snapshot(iv, 1, MAX_GCM_IV_BYTES, "GCM_IV_LENGTH_INVALID");
            input = snapshot(data, mode == Cipher.DECRYPT_MODE ? GCM_TAG_BYTES : 0,
                    mode == Cipher.DECRYPT_MODE ? MAX_CRYPTO_BYTES + GCM_TAG_BYTES : MAX_CRYPTO_BYTES,
                    "GCM_DATA_LENGTH_INVALID");
            additional = aad == null ? null
                    : snapshot(aad, 0, MAX_CRYPTO_BYTES, "GCM_AAD_LENGTH_INVALID");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, nonce));
            if (additional != null) cipher.updateAAD(additional);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new Core.Fault("GCM_AUTHENTICATION_FAILED");
        } catch (BadPaddingException e) {
            // 部分 Android/JCA 实现会将 AEAD 标签错误报告为 BadPaddingException。
            throw new Core.Fault(mode == Cipher.DECRYPT_MODE
                    ? "GCM_AUTHENTICATION_FAILED" : "AES_GCM_FAILED");
        } catch (GeneralSecurityException e) {
            throw new Core.Fault("AES_GCM_FAILED");
        } catch (RuntimeException e) {
            throw new Core.Fault("AES_GCM_FAILED");
        } finally {
            wipe(k);
            wipe(nonce);
            wipe(input);
            wipe(additional);
        }
    }

    private static byte[] cbc(int mode, byte[] key, byte[] iv, byte[] data) throws IOException {
        byte[] k = null;
        byte[] vector = null;
        byte[] input = null;
        try {
            validateKeyLength(key);
            k = key.clone();
            vector = snapshot(iv, 16, 16, "CBC_IV_LENGTH_INVALID");
            input = snapshot(data, mode == Cipher.DECRYPT_MODE ? 16 : 0,
                    mode == Cipher.DECRYPT_MODE ? MAX_CRYPTO_BYTES + 16 : MAX_CRYPTO_BYTES,
                    "CBC_DATA_LENGTH_INVALID");
            if (mode == Cipher.DECRYPT_MODE && (input.length & 15) != 0) {
                throw new Core.Fault("CBC_CIPHERTEXT_ALIGNMENT_INVALID");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(k, "AES"), new IvParameterSpec(vector));
            return cipher.doFinal(input);
        } catch (BadPaddingException e) {
            throw new Core.Fault("CBC_PADDING_INVALID");
        } catch (GeneralSecurityException e) {
            throw new Core.Fault("AES_CBC_FAILED");
        } catch (RuntimeException e) {
            throw new Core.Fault("AES_CBC_FAILED");
        } finally {
            wipe(k);
            wipe(vector);
            wipe(input);
        }
    }

    private static byte[] snapshot(byte[] value, int min, int max, String code) throws IOException {
        requireLength(value, min, max, code);
        return value.clone();
    }

    private static void requireLength(byte[] value, int min, int max, String code) throws IOException {
        if (value == null || value.length < min || value.length > max) throw new Core.Fault(code);
    }

    private static byte[] literal(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return bytes;
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
}
