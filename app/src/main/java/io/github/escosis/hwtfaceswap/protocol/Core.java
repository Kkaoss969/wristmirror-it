package io.github.escosis.hwtfaceswap.protocol;



import java.io.*;

import java.nio.ByteBuffer;

import java.nio.charset.*;

import java.security.MessageDigest;

import java.util.*;



/** 独立且可离线测试的字节工具；不调用厂商代码，不包含签名密钥。 */

public final class Core {

    private Core() {}

    public static final long MAX_SOURCE = 160L * 1024 * 1024;

    public static final long MAX_MEMBER = 128L * 1024 * 1024;

    public static final int MAX_AUTH = 32768;

    public static final int MAX_CHUNK = 4 * 1024 * 1024;



    public static final class Fault extends IOException {

        public final String code;

        public Fault(String code) { super(code); this.code = code; }

    }

    public static String code(Throwable t) {

        return t instanceof Fault ? ((Fault)t).code : t.getClass().getSimpleName();

    }

    public static byte[] digest(byte[] b) throws IOException {

        try { return MessageDigest.getInstance("SHA-256").digest(b); }

        catch (Exception e) { throw new Fault("SHA256_UNAVAILABLE"); }

    }

    public static byte[] digest(File f) throws IOException {

        try {

            MessageDigest md = MessageDigest.getInstance("SHA-256");

            try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {

                byte[] buf = new byte[65536]; int n;

                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);

            }

            return md.digest();

        } catch (java.security.GeneralSecurityException e) { throw new Fault("SHA256_UNAVAILABLE"); }

    }

    public static String hex(byte[] b) {

        char[] out = new char[b.length * 2]; char[] alphabet = "0123456789abcdef".toCharArray();

        for (int i = 0; i < b.length; i++) { out[i*2] = alphabet[(b[i]&255)>>>4]; out[i*2+1] = alphabet[b[i]&15]; }

        return new String(out);

    }

    public static byte[] unhex(String s) throws IOException {

        if (s == null || (s.length() & 1) != 0 || !s.matches("[0-9a-fA-F]*")) throw new Fault("HEX_INVALID");

        byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)((Character.digit(s.charAt(i*2),16)<<4)|Character.digit(s.charAt(i*2+1),16)); return b;

    }

    public static long copy(InputStream in, OutputStream out, long max) throws IOException {

        byte[] buf = new byte[65536]; long total = 0; int n;

        while ((n = in.read(buf)) != -1) {

            total += n;

            if (total > max) throw new Fault("FILE_LIMIT");

            out.write(buf, 0, n);

        }

        return total;

    }

    public static byte[] read(InputStream in, int max) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream(); copy(in, out, max); return out.toByteArray();

    }

    public static String utf8(byte[] b) throws IOException {

        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)

                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString(); }

        catch (CharacterCodingException e) { throw new Fault("INVALID_UTF8"); }

    }

    public static void resource(String id, String version) throws IOException {

        if (id == null || !id.matches("[0-9]{1,11}")) throw new Fault("RESOURCE_ID_REQUIRED");

        if (version == null || version.length() > 10 || !version.matches("[0-9]+(?:\\.[0-9]+){1,3}"))

            throw new Fault("RESOURCE_VERSION_INVALID");

    }

    public static long number(byte[] b) throws IOException {

        if (b == null || b.length < 1 || b.length > 4) throw new Fault("NUMERIC_TLV_LENGTH");

        long n = 0; for (byte x : b) n = (n << 8) | (x & 255); return n;

    }

    /** 华为使用的高位组优先 7 位变长整数，并非 protobuf 的 LEB128 编码。 */

    public static byte[] length(int n) throws IOException {

        if (n < 0) throw new Fault("NEGATIVE_LENGTH");

        int groups = 1; for (int x=n; (x >>>= 7) != 0;) groups++;

        byte[] b = new byte[groups];

        for (int i=groups-1; i>=0; i--) { b[i]=(byte)(n&127); if (i<groups-1) b[i]|=128; n >>>= 7; }

        return b;

    }

    public static byte[] tlv(int tag, byte[] value) throws IOException {

        ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(tag); b.write(length(value.length)); b.write(value); return b.toByteArray();

    }

    public static String clean(String s) { return s == null ? "unknown" : s.replace('\r',' ').replace('\n',' '); }

    public static String firmware(String s) {

        return s != null && s.length() <= 80 && s.matches("[0-9][0-9A-Za-z.() _-]*") ? s : "unknown";

    }

    public static boolean chunkValid(int offset, int count, int total, int unit) {

        return offset >= 0 && count > 0 && count <= MAX_CHUNK && offset <= total && count <= total-offset

                && unit > 0 && unit <= MAX_CHUNK && ((long)count + unit - 1) / unit <= 256;

    }

}

