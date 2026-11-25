package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次、独立的 HiChain ISO 操作，仅使用有静态证据的协议字段，
 * 以及标准 HMAC / HKDF / AES-GCM；不含传输、存储、Android、
 * 华为账号、厂商 SDK、自动重新绑定、日志或认证成功界面。
 *
 * 操作 1 获取持久化 Secret；完成该操作不会生成业务密钥。
 * 引擎必须在发送 S4 前保存 learnedSecret()，
 * 配对完成后，再用该 Secret 开始新的操作 2。
 * 操作 2 完成时仅返回候选的临时业务密钥。
 * 引擎仍需成功解密符合预期的业务或能力探测回包，
 * 才能认定当前通道与密钥可用。
 *
 * 实例同步且仅使用一次；对端验证出错时默认拒绝继续。
 * close() 尽力清理本实例拥有的数组；Java/JCA/JSON 字符串无法保证取证级擦除。
 * 返回的数组与 JSON 对象图绝不与内部可变状态共享引用。
 */
public final class IsoAuth implements AutoCloseable {
    public static final String GROUP_ID =
            "7B0BC0CBCE474F6C238D9661C63400B797B166EA7849B3A370FC73A9A236E989";
    public static final int MAX_JSON_BYTES = Core.MAX_AUTH;
    public static final int MAX_SERVER_SALT_BYTES = 64;
    public static final int MAX_PEER_ID_BYTES = 128;
    public static final int ISO_NONCE_BYTES = 12;
    public static final int CHALLENGE_BYTES = 16;

    private static final byte[] EXCHANGE_AAD = utf8("hichain_iso_exchange");
    private static final byte[] RESULT_AAD = utf8("hichain_iso_result");
    private static final byte[] SESSION_INFO = utf8("hichain_iso_session_key");
    private static final byte[] RETURN_INFO = utf8("hichain_return_key");
    private static final byte[] SUCCESS_RESULT = new byte[4];

    private enum Stage { NEW, PEER_SALT, PEER_MAC, PEER_SECRET, COMPLETION, FINISHED, FAILED, CLOSED }

    /** 结构化的对端错误与 JSON 格式错误、密码学验证失败分别处理。 */
    public static final class PeerError extends IOException {
        private static final long serialVersionUID = 1L;
        public final long errorCode;
        private PeerError(long errorCode) {
            super("ISO_PEER_ERROR");
            this.errorCode = errorCode;
        }
    }

    private final int operation;
    private final long requestId;
    private SecureRandom random;
    private Stage stage = Stage.NEW;
    private boolean completed;
    private byte[] selfId;
    private byte[] base;
    private byte[] clientSalt;
    private byte[] serverSalt;
    private byte[] peerId;
    private byte[] psk;
    private byte[] isoKey;
    private byte[] challenge;
    private byte[] exchangeNonce;
    private byte[] learnedSecret;
    private byte[] issuedJson;

    /**
     * self32 是 16 个随机字节的小写十六进制 UTF-8/ASCII 文本，共 32 字节，
     * 由调用方按手表与 MAC 持久化；它不是 16 个原始随机字节，也不是 HUID。
     * 配对使用非空 PIN 原始字节；重连使用本应用保存的 Secret。
     * 保存或获取的 Secret 长度限定为 16/24/32 属于防御性实现边界，
     * 不是经过验证的固件约定；requestId 保留调用方传入的精确 Java long 值。
     */
    public IsoAuth(boolean pairing, byte[] self32, byte[] pinRawOrSavedSecret,
                   long requestId, SecureRandom rng) throws IOException {
        if (rng == null) throw new Core.Fault("ISO_SECURE_RANDOM_REQUIRED");
        byte[] identity = snapshot(self32, 32, 32, "ISO_SELF_ID_LENGTH_INVALID");
        byte[] input = null;
        byte[] derived = null;
        try {
            for (byte value : identity) {
                if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                    throw new Core.Fault("ISO_SELF_ID_FORMAT_INVALID");
                }
            }
            if (pairing) {
                input = snapshot(pinRawOrSavedSecret, 1, HuaweiCrypto.MAX_PIN_BYTES,
                        "ISO_PIN_LENGTH_INVALID");
                derived = HuaweiCrypto.pinBase(input);
            } else {
                HuaweiCrypto.validateKeyLength(pinRawOrSavedSecret);
                derived = pinRawOrSavedSecret.clone();
            }
            operation = pairing ? 1 : 2;
            this.requestId = requestId;
            random = rng;
            selfId = identity;
            identity = null;
            base = derived;
            derived = null;
        } finally {
            wipe(identity);
            wipe(input);
            wipe(derived);
        }
    }

    /** 使用 SecureRandom 生成新的 16 字节 clientSalt 与 32 字节 seed；仅允许启动一次。 */
    public synchronized Map<String, Object> startRequest() throws IOException {
        require(Stage.NEW);
        byte[] salt = null;
        byte[] seed = null;
        byte[] key = null;
        try {
            salt = fresh(16);
            seed = fresh(32);
            key = HuaweiCrypto.hmac(base, seed);
            Map<String, Object> payload = versionPayload();
            payload.put("isoSalt", Core.hex(salt));
            payload.put("peerAuthId", Core.hex(selfId));
            payload.put("operationCode", operation);
            payload.put("seed", Core.hex(seed));
            payload.put("peerUserType", 0);
            if (operation == 2) {
                payload.put("pkgName", "com.huawei.devicegroupmanage");
                payload.put("serviceType", GROUP_ID);
                payload.put("keyLength", 32);
            }
            Map<String, Object> next = issue(payload, 1);
            clientSalt = salt;
            salt = null;
            psk = key;
            key = null;
            wipe(base);
            base = null;
            stage = Stage.PEER_SALT;
            return next;
        } catch (IOException e) {
            abort();
            throw e;
        } catch (RuntimeException e) {
            abort();
            throw new Core.Fault("ISO_START_FAILED");
        } finally {
            wipe(salt);
            wipe(seed);
            wipe(key);
        }
    }

    /**
     * 验证预期的对端证明并返回下一请求；完成状态
     * 必须通过独立的外层 TLV 观察调用 acceptCompletion() 确认，绝不以
     * 任意 JSON 字段或 MAC/GCM 错误作为成功捷径。
     */
    public synchronized Map<String, Object> handlePeer(Map<String, Object> peer) throws IOException {
        requirePeerProof();
        try {
            Map<String, Object> root = snapshotJson(peer);
            checkPeerError(root);
            Map<String, Object> payload = MiniJson.object(root, "payload");
            checkPeerError(payload);
            validateEchoes(root, payload);
            checkProofShape(payload);
            if (stage == Stage.PEER_SALT) return peerSalt(payload);
            if (stage == Stage.PEER_MAC) return peerMac(payload);
            return peerSecret(payload);
        } catch (IOException e) {
            abort();
            throw e;
        } catch (RuntimeException e) {
            abort();
            throw new Core.Fault("ISO_PEER_INPUT_INVALID");
        }
    }

    /** 严格检查 UTF-8 与重复键的 JSON 入口，沿用出错时默认拒绝继续的生命周期。 */
    public synchronized Map<String, Object> handlePeer(byte[] peerJsonUtf8) throws IOException {
        requirePeerProof();
        byte[] bytes = null;
        try {
            bytes = snapshot(peerJsonUtf8, 1, MAX_JSON_BYTES, "ISO_JSON_BYTES_INVALID");
            return handlePeer(MiniJson.parseObject(bytes));
        } catch (IOException e) {
            abort();
            throw e;
        } catch (RuntimeException e) {
            abort();
            throw new Core.Fault("ISO_PEER_INPUT_INVALID");
        } finally {
            wipe(bytes);
        }
    }

    /**
     * 仅生成服务 01 / 命令 28 的数据体：tag1 为紧凑 JSON UTF-8，tag2 为一字节操作码，
     * tag3 为八字节大端 requestId；服务、命令及帧封装由传输层另行提供。
     * 对象图必须是本实例当前生成且未修改的请求；
     * 拒绝重放已过期请求、调用方修改或非预期状态。
     */
    public synchronized byte[] requestTlv(Map<String, Object> request) throws IOException {
        if (issuedJson == null || !active()) throw new Core.Fault("ISO_REQUEST_STATE_INVALID");
        byte[] json = null;
        try {
            json = encodeJson(snapshotJson(request));
            if (!HuaweiCrypto.constantTimeEquals(json, issuedJson)) {
                throw new Core.Fault("ISO_REQUEST_MODIFIED_OR_STALE");
            }
            byte[] length = Core.length(json.length);
            byte[] body = new byte[1 + length.length + json.length + 3 + 10];
            int p = 0;
            body[p++] = 1;
            System.arraycopy(length, 0, body, p, length.length);
            p += length.length;
            System.arraycopy(json, 0, body, p, json.length);
            p += json.length;
            body[p++] = 2;
            body[p++] = 1;
            body[p++] = (byte) operation;
            body[p++] = 3;
            body[p++] = 8;
            for (int shift = 56; shift >= 0; shift -= 8) body[p++] = (byte) (requestId >>> shift);
            return body;
        } finally {
            wipe(json);
        }
    }

    public int operation() { return operation; }
    public long requestId() { return requestId; }

    /** 0 表示新建；1/2/3 表示等待相应对端证明；4 仅等待完成；-1 表示终态。 */
    public synchronized int expectedStage() {
        switch (stage) {
            case NEW: return 0;
            case PEER_SALT: return 1;
            case PEER_MAC: return 2;
            case PEER_SECRET: return 3;
            case COMPLETION: return 4;
            default: return -1;
        }
    }

    public synchronized boolean awaitingCompletion() { return stage == Stage.COMPLETION; }

    /** 单次操作完成，不代表已成功建立经过认证的业务通信。 */
    public synchronized boolean finished() { return completed; }

    /**
     * 有效的配对 S3 解密后立即可取得副本，必须在发送 S4 之前保存。
     * 后续步骤即使失败，也应作为待确认材料保留，因为手表
     * 可能已更换密钥；重连过程不会在此暴露已保存的 Secret。
     */
    public synchronized byte[] learnedSecret() {
        return learnedSecret == null ? null : learnedSecret.clone();
    }

    /**
     * 仅在当前连接与请求处于等待完成阶段时接受完成。
     * 适配层必须验证当前 SocketEpoch、预期 ACK 以及外层 TLV 唯一性。
     * 无独立验证证据时，不将外层 tags2/3 推定为请求回显。
     * 非零 tag4 不是密码学证明；操作 2 返回候选密钥，操作 1 返回 null。
     */
    public synchronized byte[] acceptCompletion() throws IOException {
        return acceptCompletion((Map<String, Object>) null);
    }

    /**
     * 完成回包包含 JSON 时使用此重载；存在可选回显字段时进行检查，
     * 不忽略错误，也不将证明数据包
     * 误认成完成；尚无证据要求的回显字段可以缺失。
     */
    public synchronized byte[] acceptCompletion(Map<String, Object> peer) throws IOException {
        require(Stage.COMPLETION);
        byte[] candidate = null;
        try {
            if (peer != null) {
                Map<String, Object> root = snapshotJson(peer);
                checkPeerError(root);
                Map<String, Object> payload = root.containsKey("payload")
                        ? MiniJson.object(root, "payload") : MiniJson.map();
                checkPeerError(payload);
                validateEchoes(root, payload);
                if (payload.containsKey("isoSalt") || payload.containsKey("returnCodeMac")
                        || payload.containsKey("encAuthToken")) {
                    throw new Core.Fault("ISO_COMPLETION_PROOF_UNEXPECTED");
                }
            }
            if (operation == 1) {
                if (learnedSecret == null) throw new Core.Fault("ISO_SECRET_NOT_AVAILABLE");
            } else {
                candidate = derive(isoKey, RETURN_INFO);
            }
            byte[] result = candidate == null ? null : candidate.clone();
            completed = true;
            stage = Stage.FINISHED;
            clearTransient();
            return result;
        } catch (IOException e) {
            abort();
            throw e;
        } catch (RuntimeException e) {
            abort();
            throw new Core.Fault("ISO_COMPLETION_INPUT_INVALID");
        } finally {
            wipe(candidate);
        }
    }

    /** 完成回包的严格原始 JSON 重载，避免容错替换 UTF-8 字节或接受重复键。 */
    public synchronized byte[] acceptCompletionJson(byte[] peerJsonUtf8) throws IOException {
        require(Stage.COMPLETION);
        byte[] bytes = null;
        try {
            bytes = snapshot(peerJsonUtf8, 1, MAX_JSON_BYTES, "ISO_JSON_BYTES_INVALID");
            return acceptCompletion(MiniJson.parseObject(bytes));
        } catch (IOException e) {
            abort();
            throw e;
        } catch (RuntimeException e) {
            abort();
            throw new Core.Fault("ISO_COMPLETION_INPUT_INVALID");
        } finally {
            wipe(bytes);
        }
    }

    /** 幂等并尽力擦除；关闭后仍可观察此前是否完成。 */
    @Override public synchronized void close() {
        clearTransient();
        wipe(learnedSecret);
        learnedSecret = null;
        stage = Stage.CLOSED;
    }

    private Map<String, Object> peerSalt(Map<String, Object> payload) throws IOException {
        byte[] salt = null;
        byte[] identity = null;
        byte[] token = null;
        byte[] expected = null;
        byte[] replyToken = null;
        try {
            salt = boundedHex(payload, "isoSalt", 1, MAX_SERVER_SALT_BYTES);
            identity = boundedHex(payload, "peerAuthId", 1, MAX_PEER_ID_BYTES);
            token = boundedHex(payload, "token", 32, 32);
            expected = hmacJoined(psk, salt, clientSalt, selfId, identity);
            if (!HuaweiCrypto.constantTimeEquals(token, expected)) {
                throw new Core.Fault("ISO_S1_TOKEN_MISMATCH");
            }
            replyToken = hmacJoined(psk, clientSalt, salt, identity, selfId);
            Map<String, Object> nextPayload = versionPayload();
            nextPayload.put("peerAuthId", Core.hex(selfId));
            nextPayload.put("token", Core.hex(replyToken));
            Map<String, Object> next = issue(nextPayload, 2);
            serverSalt = salt;
            salt = null;
            peerId = identity;
            identity = null;
            stage = Stage.PEER_MAC;
            return next;
        } finally {
            wipe(salt);
            wipe(identity);
            wipe(token);
            wipe(expected);
            wipe(replyToken);
        }
    }

    private Map<String, Object> peerMac(Map<String, Object> payload) throws IOException {
        byte[] mac = null;
        byte[] expected = null;
        byte[] key = null;
        byte[] nonce = null;
        byte[] nextChallenge = null;
        byte[] encrypted = null;
        try {
            mac = boundedHex(payload, "returnCodeMac", 32, 32);
            expected = HuaweiCrypto.hmac(psk, SUCCESS_RESULT);
            if (!HuaweiCrypto.constantTimeEquals(mac, expected)) {
                throw new Core.Fault("ISO_S2_MAC_MISMATCH");
            }
            key = derive(psk, SESSION_INFO);
            nonce = fresh(ISO_NONCE_BYTES);
            Map<String, Object> nextPayload = versionPayload();
            nextPayload.put("nonce", Core.hex(nonce));
            if (operation == 1) {
                nextChallenge = fresh(CHALLENGE_BYTES);
                encrypted = HuaweiCrypto.aesGcmEncrypt(key, nonce, nextChallenge, EXCHANGE_AAD);
                nextPayload.put("encData", Core.hex(encrypted));
            } else {
                encrypted = HuaweiCrypto.aesGcmEncrypt(key, nonce, SUCCESS_RESULT, RESULT_AAD);
                nextPayload.put("encResult", Core.hex(encrypted));
                nextPayload.put("operationCode", operation);
            }
            Map<String, Object> next = issue(nextPayload, 3);
            isoKey = key;
            key = null;
            if (operation == 1) {
                challenge = nextChallenge;
                nextChallenge = null;
                exchangeNonce = nonce;
                nonce = null;
                stage = Stage.PEER_SECRET;
            } else {
                stage = Stage.COMPLETION;
            }
            wipe(psk);
            psk = null;
            wipe(peerId);
            peerId = null;
            return next;
        } finally {
            wipe(mac);
            wipe(expected);
            wipe(key);
            wipe(nonce);
            wipe(nextChallenge);
            wipe(encrypted);
        }
    }

    private Map<String, Object> peerSecret(Map<String, Object> payload) throws IOException {
        byte[] nonce = null;
        byte[] encrypted = null;
        byte[] secret = null;
        byte[] resultNonce = null;
        byte[] result = null;
        try {
            nonce = boundedHex(payload, "nonce", ISO_NONCE_BYTES, ISO_NONCE_BYTES);
            // 按保守限定的 Secret 长度，密文加认证标签共 32..48 字节。
            encrypted = boundedHex(payload, "encAuthToken", 32, 48);
            secret = HuaweiCrypto.aesGcmDecrypt(isoKey, nonce, encrypted, challenge);
            if (!HuaweiCrypto.isAesKeyLength(secret.length)) {
                throw new Core.Fault("ISO_SECRET_LENGTH_NOT_SUPPORTED");
            }
            // 有意在生成结果之前保存；绝不仅因后续 S4 或完成步骤失败，
            // 就丢弃已经通过认证而获取的 Secret。
            learnedSecret = secret;
            secret = null;
            wipe(challenge);
            challenge = null;
            resultNonce = fresh(ISO_NONCE_BYTES);
            if (HuaweiCrypto.constantTimeEquals(resultNonce, exchangeNonce)
                    || HuaweiCrypto.constantTimeEquals(resultNonce, nonce)) {
                throw new Core.Fault("ISO_LOCAL_NONCE_REUSE");
            }
            result = HuaweiCrypto.aesGcmEncrypt(isoKey, resultNonce, SUCCESS_RESULT, RESULT_AAD);
            Map<String, Object> nextPayload = versionPayload();
            nextPayload.put("nonce", Core.hex(resultNonce));
            nextPayload.put("encResult", Core.hex(result));
            nextPayload.put("operationCode", operation);
            Map<String, Object> next = issue(nextPayload, 4);
            wipe(exchangeNonce);
            exchangeNonce = null;
            stage = Stage.COMPLETION;
            return next;
        } finally {
            wipe(nonce);
            wipe(encrypted);
            wipe(secret);
            wipe(resultNonce);
            wipe(result);
        }
    }

    private Map<String, Object> issue(Map<String, Object> payload, int step) throws IOException {
        Map<String, Object> root = MiniJson.map("authForm", 0, "payload", payload,
                "groupAndModuleVersion", "2.0.1", "message", step | (operation == 2 ? 0x10 : 0));
        if (operation == 1) {
            String identity = Core.utf8(selfId);
            root.put("requestId", Long.toString(requestId));
            root.put("groupId", GROUP_ID);
            root.put("groupName", "health_group_name");
            root.put("groupOp", 2);
            root.put("groupType", 256);
            root.put("peerDeviceId", identity);
            root.put("connDeviceId", identity);
            root.put("appId", "com.huawei.health");
            root.put("ownerName", "");
        } else if (step == 1 || step == 2) {
            root.put("isDeviceLevel", false);
        }
        byte[] encoded = encodeJson(root);
        wipe(issuedJson);
        issuedJson = encoded;
        return root;
    }

    private static Map<String, Object> versionPayload() {
        return MiniJson.map("version", MiniJson.map("minVersion", "1.0.0", "currentVersion", "2.0.16"));
    }

    private void validateEchoes(Map<String, Object> root, Map<String, Object> payload)
            throws IOException {
        validateEchoScope(root);
        validateEchoScope(payload);
    }

    private void validateEchoScope(Map<String, Object> scope) throws IOException {
        if (scope.containsKey("operationCode")) {
            long code = number(scope, "operationCode");
            if (code < Integer.MIN_VALUE || code > 0xffffffffL) {
                throw new Core.Fault("ISO_OPERATION_METADATA_INVALID");
            }
            // 只有明确的 op1/op2 值可关联到本次操作；
            // 其他有界对端码没有已验证语义，不授予任何权限。
            if ((code == 1 || code == 2) && code != operation) {
                throw new Core.Fault("ISO_OPERATION_MISMATCH");
            }
        }
        if (scope.containsKey("requestId")) {
            Object value = scope.get("requestId");
            if (value instanceof Long) {
                if (((Long) value).longValue() != requestId) {
                    throw new Core.Fault("ISO_REQUEST_ID_MISMATCH");
                }
            } else if (value instanceof String) {
                String text = (String) value;
                if (text.matches("-?[0-9]+") && !Long.toString(requestId).equals(text)) {
                    throw new Core.Fault("ISO_REQUEST_ID_MISMATCH");
                }
                // 非十进制的对端 ID 仅为不可解释的元数据，不猜测为回显 ID。
            } else {
                throw new Core.Fault("ISO_REQUEST_ID_METADATA_INVALID");
            }
        }
        if (scope.containsKey("message")) {
            long message = number(scope, "message");
            // ISO 响应的 message 常量及其低半字节
            // 尚无经过验证的证据；有界整数 message 仅视为不可解释的元数据，
            // 绝不用于选择阶段、操作或证明。
            // 进度由当前状态、必需证明字段及 MAC/GCM 验证共同决定。
            if (message < Integer.MIN_VALUE || message > 0xffffffffL) {
                throw new Core.Fault("ISO_MESSAGE_METADATA_INVALID");
            }
        }
    }

    private static long number(Map<String, Object> scope, String field) throws IOException {
        Long value = MiniJson.optionalNumber(scope, field);
        if (value == null) throw new Core.Fault("ISO_ECHO_NUMBER_INVALID");
        return value.longValue();
    }

    private static void checkPeerError(Map<String, Object> scope) throws IOException {
        if (!scope.containsKey("errorCode")) return;
        long code = number(scope, "errorCode");
        if (code < Integer.MIN_VALUE || code > 0xffffffffL) {
            throw new Core.Fault("ISO_PEER_ERROR_CODE_INVALID");
        }
        if (code != 0) throw new PeerError(code);
    }

    private void checkProofShape(Map<String, Object> payload) throws IOException {
        boolean salt = payload.containsKey("isoSalt");
        boolean mac = payload.containsKey("returnCodeMac");
        boolean secret = payload.containsKey("encAuthToken");
        int count = (salt ? 1 : 0) + (mac ? 1 : 0) + (secret ? 1 : 0);
        if (count != 1) throw new Core.Fault("ISO_PROOF_MISSING_OR_AMBIGUOUS");
        if ((stage == Stage.PEER_SALT && !salt) || (stage == Stage.PEER_MAC && !mac)
                || (stage == Stage.PEER_SECRET && !secret)) {
            throw new Core.Fault("ISO_PROOF_OUT_OF_ORDER");
        }
    }

    private byte[] derive(byte[] key, byte[] info) throws IOException {
        byte[] salts = joined(clientSalt, serverSalt);
        try {
            return HuaweiCrypto.hkdf32(key, salts, info);
        } finally {
            wipe(salts);
        }
    }

    private static byte[] hmacJoined(byte[] key, byte[]... pieces) throws IOException {
        byte[] data = joined(pieces);
        try {
            return HuaweiCrypto.hmac(key, data);
        } finally {
            wipe(data);
        }
    }

    private static byte[] joined(byte[]... pieces) throws IOException {
        int length = 0;
        for (byte[] piece : pieces) {
            if (piece == null || piece.length > MAX_JSON_BYTES - length) {
                throw new Core.Fault("ISO_KEY_STATE_INVALID");
            }
            length += piece.length;
        }
        byte[] result = new byte[length];
        int p = 0;
        for (byte[] piece : pieces) {
            System.arraycopy(piece, 0, result, p, piece.length);
            p += piece.length;
        }
        return result;
    }

    private byte[] fresh(int length) throws IOException {
        byte[] generated = new byte[length];
        try {
            random.nextBytes(generated);
            // 即使注入测试随机数生成器，也不能保留会话状态的共享引用。
            return generated.clone();
        } catch (RuntimeException e) {
            throw new Core.Fault("ISO_RANDOM_FAILED");
        } finally {
            wipe(generated);
        }
    }

    private static byte[] boundedHex(Map<String, Object> payload, String field, int min, int max)
            throws IOException {
        String text = MiniJson.string(payload, field);
        if (text.length() < min * 2 || text.length() > max * 2) {
            throw new Core.Fault("ISO_HEX_LENGTH_INVALID_" + field);
        }
        // 在 Core.unhex 分配内存前先检查文本长度；十六进制解码输出为新建的独立数组。
        return MiniJson.hex(payload, field, min, max);
    }

    private static byte[] encodeJson(Map<String, Object> json) throws IOException {
        byte[] bytes = MiniJson.encode(json).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            wipe(bytes);
            throw new Core.Fault("ISO_JSON_SIZE_LIMIT");
        }
        return bytes;
    }

    /** 对直接传入、并非来自严格协议 JSON 解析的 Map 执行有界深拷贝。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotJson(Map<String, Object> input) throws IOException {
        if (input == null) throw new Core.Fault("ISO_JSON_REQUIRED");
        Map<String, Object> copy = (Map<String, Object>) copyJson(input, 0, new JsonBudget());
        byte[] encoded = encodeJson(copy);
        wipe(encoded);
        return copy;
    }

    private static Object copyJson(Object input, int depth, JsonBudget budget) throws IOException {
        if (depth > MiniJson.MAX_DEPTH || ++budget.nodes > MiniJson.MAX_NODES) {
            throw new Core.Fault("ISO_JSON_BUDGET");
        }
        if (input == null || input instanceof Boolean) return input;
        if (input instanceof String) {
            budget.text((String) input);
            return input;
        }
        if (input instanceof Byte || input instanceof Short || input instanceof Integer || input instanceof Long) {
            return Long.valueOf(((Number) input).longValue());
        }
        if (input instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) input).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new Core.Fault("ISO_JSON_KEY_INVALID");
                String key = (String) entry.getKey();
                budget.text(key);
                if (copy.containsKey(key)) throw new Core.Fault("ISO_JSON_DUPLICATE_KEY");
                copy.put(key, copyJson(entry.getValue(), depth + 1, budget));
            }
            return copy;
        }
        if (input instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object value : (List<?>) input) copy.add(copyJson(value, depth + 1, budget));
            return copy;
        }
        throw new Core.Fault("ISO_JSON_VALUE_TYPE_INVALID");
    }

    private static final class JsonBudget {
        int nodes;
        int bytes;
        void text(String text) throws IOException {
            if (text.length() > MAX_JSON_BYTES - bytes) throw new Core.Fault("ISO_JSON_SIZE_LIMIT");
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            try {
                if (encoded.length > MAX_JSON_BYTES - bytes) throw new Core.Fault("ISO_JSON_SIZE_LIMIT");
                if (!text.equals(Core.utf8(encoded))) throw new Core.Fault("ISO_JSON_UNICODE_INVALID");
                bytes += encoded.length;
            } finally {
                wipe(encoded);
            }
        }
    }

    private static byte[] snapshot(byte[] value, int min, int max, String code) throws IOException {
        if (value == null || value.length < min || value.length > max) throw new Core.Fault(code);
        return value.clone();
    }

    private void require(Stage expected) throws IOException {
        if (stage != expected) throw new Core.Fault("ISO_STATE_UNEXPECTED");
    }

    private void requirePeerProof() throws IOException {
        if (stage != Stage.PEER_SALT && stage != Stage.PEER_MAC && stage != Stage.PEER_SECRET) {
            throw new Core.Fault("ISO_STATE_UNEXPECTED");
        }
    }

    private boolean active() {
        return stage == Stage.PEER_SALT || stage == Stage.PEER_MAC
                || stage == Stage.PEER_SECRET || stage == Stage.COMPLETION;
    }

    private void abort() {
        clearTransient();
        stage = Stage.FAILED;
    }

    private void clearTransient() {
        wipe(selfId); selfId = null;
        wipe(base); base = null;
        wipe(clientSalt); clientSalt = null;
        wipe(serverSalt); serverSalt = null;
        wipe(peerId); peerId = null;
        wipe(psk); psk = null;
        wipe(isoKey); isoKey = null;
        wipe(challenge); challenge = null;
        wipe(exchangeNonce); exchangeNonce = null;
        wipe(issuedJson); issuedJson = null;
        random = null;
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
