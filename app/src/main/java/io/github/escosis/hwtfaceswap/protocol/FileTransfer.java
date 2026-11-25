package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 固定的单一 BIN 载荷，仅使用原生服务 0x28 的数据体。
 *
 * 替换沿用Health/Unite已验证合同；DIRECT_INSTALL走腕连native合同。
 * 调用方负责操作入口、会话/任务归属、解密、CryptoSession.seal、帧封装、
 * 套接字写入和ACK派发与截止时间。准备字节不等于套接字写入或安装成功。
 * peerComplete在替换表示明确关联的raw1及本地完整性；在直装仅表示
 * 收到07并准备ACK、完成本地快照核验，raw只是观察，不是安装判值。
 * 直装接受metadata默认0/100000，已存在码转应用；03总回全SHA，03/04取得ID，
 * 06校验错误码，07不以raw判结果。替换03只check_mode3并支持suffix SHA。
 * 文件归属、字段去重、阶段/范围/内存预算及冻结校验是本地额外防护，
 * 不是腕连必需合同或设备能力实测；不把数值推定为设备能力上限。
 * 此处不含 Android、源文件改写、重试计时器、授权或加密实现。
 */
public final class FileTransfer implements Closeable {
    public static final int SERVICE = 0x28;
    private static final long SUCCESS = 100000L;
    // 本地有限处理上限；有意不将其解释为对端字段。
    static final int WINDOW_REQUEST_BUDGET = 65536;
    static final int PACKET_REQUEST_BUDGET = 65536;
    private static final int INTEGRITY_BLOCK_BYTES = 65536;
    private static final int PREPARED_BYTE_MULTIPLIER = 4;

    /** 命令 06 的数据体为原始分块字节；控制命令的数据体为 TLV 字节。 */
    public static final class Outgoing {
        public final int command;
        public final boolean encrypt;
        private final byte[] bytes;

        private Outgoing(int command, byte[] body, boolean encrypt) {
            this.command = command;
            this.bytes = body.clone();
            this.encrypt = encrypt;
        }

        public byte[] body() { return bytes.clone(); }
    }

    private enum Stage {
        NEW, WAIT_INFO, WAIT_CONSULT, WAIT_WINDOW,
        TRANSFERRING, WAIT_PEER_RESULT, RESULT_OBSERVED, PEER_COMPLETE, FAILED, CLOSED
    }

    public enum Route { REPLACEMENT, DIRECT_INSTALL }

    private final boolean directInstall;
    private final HwtSource.Snapshot frozen;
    private final File frozenFile;
    private final FileIdentity frozenIdentity;
    private final int size;
    private final byte[] sha256;
    private final byte[] filename;
    private final byte[] metadata;
    private final byte[][] blockHashes;
    private final long preparedByteBudget;
    private RandomAccessFile input;
    private Stage stage = Stage.NEW;
    private int currentFileId = -1;
    private int negotiatedUnit;
    private int noEncrypt = -1;
    private int packetRequests;
    private int windowRequests;
    private long preparedBytes;
    private long fullSnapshotChecks;
    private long fullHandleChecks;
    private long handleBytesRead;
    private long windowHandleBytesRead;
    private boolean complete;
    // 只保留标量观察值，绝不保留对端数据体或无界异常信息。
    private Integer peerResultRaw;
    private boolean resultObserved;
    private String completionFailure;
    private boolean resultAckAfterFailure;
    private boolean existingResource;
    public synchronized boolean existingResource() { return existingResource; }
    // 设备在文件信息阶段给出的明确容量/数量拒绝；0 表示没有观察到这类码。
    private long infoRefusal;
    public synchronized long infoRefusal() { return infoRefusal; }
    private static final int FAILURE_CODE_LIMIT = 96;

    public FileTransfer(String id, String installedProtocolVersion, HwtSource.Snapshot frozen)
            throws IOException {
        this(id, installedProtocolVersion, frozen, Route.REPLACEMENT);
    }

    public FileTransfer(String id, String installedProtocolVersion, HwtSource.Snapshot frozen, Route route)
            throws IOException {
        Core.resource(id, installedProtocolVersion);
        if (route == null) throw new Core.Fault("FILE_TRANSFER_ROUTE_REQUIRED");
        this.directInstall = route == Route.DIRECT_INSTALL;
        // 两条路线都传输解析出的表盘内容，而不是外层 HWT ZIP 容器。
        if (frozen == null || frozen.source == null || frozen.file == null
                || !supportedPayloadMode(frozen.mode)) {
            throw new Core.Fault("FILE_TRANSFER_FROZEN_PAYLOAD_REQUIRED");
        }
        if (!directInstall && frozen.mode != HwtSource.Mode.BIN) {
            throw new Core.Fault("FILE_TRANSFER_FROZEN_BIN_REQUIRED");
        }
        if (frozen.size <= 0 || frozen.size > Core.MAX_MEMBER) {
            throw new Core.Fault("FILE_TRANSFER_SOURCE_SIZE_LIMIT");
        }
        if (frozen.hash == null || frozen.hash.length() != 64) {
            throw new Core.Fault("FILE_TRANSFER_SNAPSHOT_HASH_INVALID");
        }
        if (!frozen.source.matchesPayload(frozen)) {
            throw new Core.Fault(directInstall ? "FILE_TRANSFER_PARSED_PAYLOAD_REQUIRED" : "FILE_TRANSFER_FROZEN_BIN_REQUIRED");
        }
        this.sha256 = Core.unhex(frozen.hash);
        this.frozen = frozen;
        this.size = frozen.size;
        this.frozenFile = frozen.file.getCanonicalFile();
        if (!frozen.source.directory.getCanonicalFile().equals(frozenFile.getParentFile())
                || !frozenFile.isFile()) {
            throw new Core.Fault("FILE_TRANSFER_PRIVATE_SNAPSHOT_REQUIRED");
        }
        this.frozenIdentity = readFileIdentity();
        if (directInstall && (!id.matches("[0-9]{9}") || !"1.0.0".equals(installedProtocolVersion))) {
            throw new Core.Fault("DIRECT_HWT_FILENAME_CONTRACT_INVALID");
        }
        this.filename = (id + "_" + installedProtocolVersion).getBytes(StandardCharsets.US_ASCII);
        this.metadata = makeMetadata(id, installedProtocolVersion);
        this.preparedByteBudget = PREPARED_BYTE_MULTIPLIER * (long) size + Core.MAX_CHUNK;
        this.blockHashes = new byte[(size + INTEGRITY_BLOCK_BYTES - 1) / INTEGRITY_BLOCK_BYTES][];
        // HwtSource.freeze 负责私有只读快照；此处绝不以写入方式打开来源或 BIN，
        // 不保留调用方字节缓冲区，也不创建不同的传输载荷。
        try {
            input = new RandomAccessFile(frozenFile, "r");
            checkSize();
            MessageDigest whole = newDigest();
            fullHandleChecks++;
            for (int block = 0; block < blockHashes.length; block++) {
                byte[] data = readBlock(block);
                blockHashes[block] = Core.digest(data);
                whole.update(data);
            }
            if (!Arrays.equals(whole.digest(), sha256)) {
                throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
            }
            verifyWholeSnapshot();
        } catch (IOException failure) {
            closeInput(failure);
            throw failure;
        }
    }

    private static boolean supportedPayloadMode(HwtSource.Mode mode) {
        return mode == HwtSource.Mode.BIN || mode == HwtSource.Mode.CONTAINER
                || mode == HwtSource.Mode.CONTAINER_NO_BIN;
    }

    /**
     * 元数据只准备一次；调用方将其作为 28/02 按常规控制加密发送
     * （seal=true），不受后续协商 no_encrypt 值影响。
     */
    public synchronized byte[] metadataBody() throws IOException {
        requireOpen();
        try {
            if (stage != Stage.NEW) {
                throw new Core.Fault("FILE_TRANSFER_METADATA_ALREADY_PREPARED");
            }
            verifyFrozen();
            byte[] result = metadata.clone();
            stage = Stage.WAIT_INFO;
            return result;
        } catch (IOException failure) {
            fail(failure);
            throw failure;
        }
    }

    /**
     * 接收已解码与解密的服务 0x28 数据体；此处不处理或生成服务字节、
     * 命令字节、帧、CRC 或加密封装。
     * 有效的重复窗口是对端请求的重读，不是自动重试。
     * 明确关联且格式正确的 07 会先返回 ACK，再由调用方读取原始结果或失败信息；
     * 即使结果为 0 或最终完整性检查失败也如此。身份或 TLV 存在歧义时
     * 仍直接抛出错误而不返回 ACK；resultObserved 仅表示本次调用的观察。
     */
    public synchronized List<Outgoing> onPacket(int command, byte[] body,
                                                boolean transactionsCrypted) throws IOException {
        resultObserved = false;
        if (!(command == 0x07 && stage == Stage.FAILED && resultAckAfterFailure)) requireOpen();
        try {
            if (command < 0 || command > 255) {
                throw new Core.Fault("FILE_TRANSFER_COMMAND_INVALID");
            }
            if (packetRequests >= PACKET_REQUEST_BUDGET) {
                throw new Core.Fault("FILE_TRANSFER_PACKET_REQUEST_BUDGET");
            }
            packetRequests++;
            // 错误通知只有在证实归属后，才能终止本次传输。
            if (command == 0x08) return deviceError(body);
            checkBody(body);
            switch (command) {
                case 0x02: return infoReply(fields(body));
                case 0x03: return hashRequest(fields(body));
                case 0x04: return consult(fields(body));
                case 0x05: return window(fields(body), transactionsCrypted);
                // Replacement keeps the previously verified Health/Unite dispatch contract.
                case 0x06: return directInstall ? directChunkAck(fields(body)) : Collections.emptyList();
                case 0x07: return result(fields(body));
                default: return Collections.emptyList(); // 未知命令绝不视为 ACK 或完成。
            }
        } catch (IOException failure) {
            fail(failure);
            throw failure;
        }
    }

    private List<Outgoing> infoReply(List<TlvView.Part> fields) throws IOException {
        if (directInstall) {
            TlvView.Part name = Wire.unique(fields, 1), type = Wire.unique(fields, 3);
            // Explicit foreign identity remains a local single-task ownership guard.
            if ((name != null && !Arrays.equals(name.value, filename)) ||
                    (type != null && metadataByteNumber(type) != 1)) return Collections.emptyList();
            requireStage(Stage.WAIT_INFO);
            long code = wristError(fields);
            if (code == 140008 || code == 100008) {
                verifyFrozen(); existingResource = true; stage = Stage.RESULT_OBSERVED;
                return Collections.emptyList();
            }
            if (code != 0 && code != SUCCESS) {
                // 设备明确说“文件受限/空间不足”时先记下码：这是终局拒绝而非协议歧义。
                if (DeviceRefusal.known(code)) infoRefusal = code;
                throw new Core.Fault("FILE_TRANSFER_INFO_ERROR_" + code);
            }
            TlvView.Part id = Wire.unique(fields, 4);
            currentFileId = id == null ? 0 : metadataByteNumber(id);
            stage = Stage.WAIT_CONSULT;
            return Collections.emptyList();
        }
        TlvView.Part name = required(fields, 0x01);
        int type = metadataByteNumber(required(fields, 0x03));
        // 原生任务标识是文件名加文件类型，不是后续的 protocolVersion。
        // 明确属于其他任务的回包，不得影响本任务或在此分配其 ID。
        if (!Arrays.equals(name.value, filename) || type != 1) return Collections.emptyList();
        requireStage(Stage.WAIT_INFO);
        int assignedId = metadataByteNumber(required(fields, 0x04));
        long code = fixedNumber(required(fields, 0x7f), 4);
        // Health 为100000，腕连原metadata分支也接受明确0；不采用其缺失/短header默认0。
        if (code != SUCCESS && code != 0) {
            // 替换路线同样可能被设备以容量/数量拒绝；记录码供管理器区分终局拒绝与协议歧义。
            if (DeviceRefusal.known(code)) infoRefusal = code;
            throw new Core.Fault("FILE_TRANSFER_INFO_ERROR_" + code);
        }
        // Unite 将 ID 默认为 -1，Classic 默认为 0；两者都不能证明缺失的 04 表示 ID 0。
        currentFileId = assignedId;
        stage = Stage.WAIT_CONSULT;
        return Collections.emptyList();
    }

    private List<Outgoing> hashRequest(List<TlvView.Part> fields) throws IOException {
        requireRegistered();
        if (directInstall) {
            if (stage == Stage.PEER_COMPLETE || stage == Stage.RESULT_OBSERVED) return Collections.emptyList();
            TlvView.Part id = Wire.unique(fields, 1);
            currentFileId = id == null ? 0 : metadataByteNumber(id);
            verifyFrozen();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            append(response, 1, new byte[]{(byte)currentFileId}); append(response, 3, sha256);
            return Collections.singletonList(new Outgoing(3, response.toByteArray(), true));
        }
        if (!associatedId(fields)) return Collections.emptyList();
        // 原生任务在协商及数据阶段保持存在，正常 07 完成后才清理。
        if (peerResultRaw != null || stage == Stage.PEER_COMPLETE || stage == Stage.RESULT_OBSERVED) return Collections.emptyList();
        TlvView.Part mode = Wire.unique(fields, 0x02);
        int checkMode = mode == null ? 0 : byteNumber(mode);
        if (checkMode != 3) return Collections.emptyList();
        long position = hashPosition(Wire.unique(fields, 0x04));
        verifyFrozen();
        byte[] hash = position == 0 ? sha256 : suffixHash((int) position);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 0x01, new byte[] { (byte) currentFileId });
        append(out, 0x03, hash);
        // Unite 请求 [position, EOF)；Classic 忽略 request04。明确的非零
        // 协议范围必须遵从，不得默默替换为 Classic 的全文件回退范围。
        // Lynn.c 在位置为零时省略 response04，否则写入位置的低 32 位大端值。
        if (position != 0) append(out, 0x04, number32(position));
        return Collections.singletonList(new Outgoing(0x03, out.toByteArray(), true));
    }

    private List<Outgoing> consult(List<TlvView.Part> fields) throws IOException {
        requireRegistered();
        if (directInstall) {
            TlvView.Part id = Wire.unique(fields, 1);
            currentFileId = id == null ? 0 : metadataByteNumber(id);
        } else if (!associatedId(fields)) return Collections.emptyList();
        // 原生协商映射在每次有效的同 ID 04 回包时替换参数，
        // 包括重传；原生实现没有 WAIT_CONSULT 或首次发送的阶段条件。
        // 原生 04/02 是 protocolVersion（默认空），仅设置，分块与加密处理不读取它；
        // 此处拒绝重复封装，但不解释或存储该字段。
        Wire.unique(fields, 0x02);
        long unit = Core.number(required(fields, 0x05).value);
        if (unit <= 0 || unit > Core.MAX_CHUNK) {
            throw new Core.Fault("FILE_TRANSFER_UNIT_SIZE_INVALID");
        }
        TlvView.Part rawNoEncrypt = Wire.unique(fields, 0x09);
        int value = rawNoEncrypt == null ? 0 : byteNumber(rawNoEncrypt);
        for (int tag : new int[] { 0x03, 0x04, 0x06, 0x07 }) {
            TlvView.Part observed = Wire.unique(fields, tag);
            if (observed != null) Core.number(observed.value);
        }
        TlvView.Part observed08 = Wire.unique(fields, 0x08);
        if (observed08 != null) fixedNumber(observed08, 4);
        Outgoing reply = new Outgoing(0x04, ackBody(value == 1), true);
        negotiatedUnit = (int) unit;
        noEncrypt = value;
        if (stage == Stage.WAIT_CONSULT) stage = Stage.WAIT_WINDOW;
        // 观察到 07 后，不重置字节或请求预算，也不重新开放数据发送。
        return Collections.singletonList(reply);
    }

    private List<Outgoing> window(List<TlvView.Part> fields, boolean transactionsCrypted)
            throws IOException {
        requireRegistered();
        if (!optionalAssociatedId(fields)) return Collections.emptyList();
        requireDataStage();
        long offset = fixedNumber(required(fields, 0x02), 4);
        long count = fixedNumber(required(fields, 0x03), 4);
        // 数值字段是 32 位大端值；TLV 长度使用的变长整数不是这些数值的编码。
        if (offset > size || count <= 0 || count > Core.MAX_CHUNK || count > size - offset
                || negotiatedUnit <= 0 || negotiatedUnit > Core.MAX_CHUNK
                || (count + negotiatedUnit - 1L) / negotiatedUnit > 256L) {
            throw new Core.Fault("FILE_TRANSFER_WINDOW_INVALID");
        }
        if (windowRequests >= WINDOW_REQUEST_BUDGET) {
            throw new Core.Fault("FILE_TRANSFER_WINDOW_REQUEST_BUDGET");
        }
        if (count > preparedByteBudget - preparedBytes) {
            throw new Core.Fault("FILE_TRANSFER_PREPARED_BYTE_BUDGET");
        }
        windowRequests++;
        // 不要在每个对端窗口请求时散列无关文件块；规范路径、大小及
        // 当前可用的本地身份检查共同保护只读句柄。返回数据之前，所有读出的分块
        // 均与构造时的散列比较；尚未读取或请求的分块若有变更，
        // 则在该分块被请求或最终完整检查时发现。
        checkSize();
        long beforeRead = handleBytesRead;
        byte[] data;
        try { data = readWindow((int) offset, (int) count); }
        finally { windowHandleBytesRead += handleBytesRead - beforeRead; }
        int units = (int) ((count + negotiatedUnit - 1L) / negotiatedUnit);
        List<Outgoing> outgoing = new ArrayList<Outgoing>(units);
        boolean encryptUnits = noEncrypt != 1 && transactionsCrypted;
        int used = 0;
        for (int sequence = 0; sequence < units; sequence++) {
            int actualCount = Math.min(negotiatedUnit, data.length - used);
            byte[] raw = new byte[actualCount + 6];
            raw[0] = (byte) currentFileId;
            raw[1] = (byte) sequence; // 00..FF，每个窗口重新计数。
            put32(raw, 2, offset + used);
            System.arraycopy(data, used, raw, 6, actualCount);
            outgoing.add(new Outgoing(0x06, raw, encryptUnits));
            used += actualCount; // 不能按 negotiatedUnit 累加，末分块可能更短。
        }
        checkSize();
        preparedBytes += used; // 仅统计文件字节；合法的重传同样消耗预算。
        if (offset + used == size || stage == Stage.WAIT_PEER_RESULT) {
            stage = Stage.WAIT_PEER_RESULT;
        } else {
            stage = Stage.TRANSFERRING;
        }
        return Collections.unmodifiableList(outgoing);
    }

    private static long wristError(List<TlvView.Part> fields) throws IOException {
        TlvView.Part p = Wire.unique(fields, 0x7f);
        if (p == null || p.value.length < 4) return 0;
        int value = 0; for (int i = 0; i < 4; i++) value = (value << 8) | (p.value[i] & 255);
        return value;
    }
    private List<Outgoing> directChunkAck(List<TlvView.Part> fields) throws IOException {
        requireRegistered();
        if (!optionalAssociatedId(fields)) return Collections.emptyList();
        long error = wristError(fields);
        if (error != 0 && error != SUCCESS) throw new Core.Fault("FILE_TRANSFER_DATA_ERROR_" + error);
        return Collections.emptyList();
    }

    private List<Outgoing> result(List<TlvView.Part> fields) throws IOException {
        requireRegistered();
        if (!associatedId(fields)) return Collections.emptyList();
        // 记录观察或生成 ACK 前，完整验证身份及明确给出的原始结果。
        // 原始结果缺失与 TLV 封装非法或重复是不同情况。
        TlvView.Part raw = Wire.unique(fields, 0x02);
        Integer value = raw == null ? null : Integer.valueOf(byteNumber(raw));
        Outgoing reply = new Outgoing(0x07, ackBody(false), true);
        resultObserved = true;
        peerResultRaw = value;
        if (completionFailure == null) { complete = false; stage = Stage.RESULT_OBSERVED; }
        if (directInstall) {
            // Wrist native w0.D does not branch on 28/07 tag2. Receipt + ACK ends
            // its file task; retain the raw value for diagnostics, not as an install verdict.
            if (completionFailure == null) {
                try { verifyFrozen(); complete = true; stage = Stage.PEER_COMPLETE; }
                catch (IOException integrityFailure) { resultFailure(integrityFailure); }
            }
            return Collections.singletonList(reply);
        }
        if (value == null) {
            resultFailure(new Core.Fault("FILE_TRANSFER_REQUIRED_TAG_2"));
        } else if (value.intValue() == 0) {
            // 先返回 ACK，再将真实零值保留给调用方的 State.nativeResult(0)。
            // 保持该失败不可覆盖，也可防止后续原始结果 1 将其抹去。
            resultFailure(new Core.Fault("FILE_TRANSFER_PEER_RESULT_0"));
        } else if (value.intValue() == 1 && completionFailure == null) {
            try {
                verifyFrozen();
                complete = true;
                stage = Stage.PEER_COMPLETE;
            } catch (IOException localIntegrityFailure) {
                // 明确关联的原生结果仍需 ACK；应单独报告本地完整性失败，
                // 而不是在发送 ACK 之前抛出错误。
                resultFailure(localIntegrityFailure);
            }
        }
        // 其他明确的 u8 值只作为原始观察，绝不据此创建成功状态、
        // 数据字节计数或安装与列表确认。
        return Collections.singletonList(reply);
    }

    private List<Outgoing> deviceError(byte[] body) throws IOException {
        if (currentFileId < 0) return Collections.emptyList();
        List<TlvView.Part> decoded;
        TlvView.Part id;
        try {
            decoded = fields(body);
            id = Wire.unique(decoded, 0x01);
            // 身份缺失或存在歧义时，无法证明归属，即使本任务 ID 为 0 也不例外。
            if (id == null || byteNumber(id) != currentFileId) return Collections.emptyList();
        } catch (IOException unownedOrAmbiguous) {
            // 身份格式非法或重复时，无法证明通知属于哪个文件。
            return Collections.emptyList();
        }
        TlvView.Part rawCode = Wire.unique(decoded, 0x7f);
        if (rawCode == null) throw new Core.Fault("FILE_TRANSFER_DEVICE_ERROR_MISSING");
        long code = Core.number(rawCode.value);
        // 归属确定时，命令 08 本身即表示终止错误，即使对端给出错误码 0/100000。
        throw new Core.Fault("FILE_TRANSFER_DEVICE_ERROR_" + code);
    }

    private byte[] makeMetadata(String id, String version) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 0x01, filename);
        append(out, 0x02, number32(size));
        append(out, 0x03, new byte[] { 1 });
        append(out, 0x05, id.getBytes(StandardCharsets.US_ASCII));
        append(out, 0x06, version.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private byte[] ackBody(boolean acknowledgeNoEncrypt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 0x7f, number32(SUCCESS));
        append(out, 0x01, new byte[] { (byte) currentFileId });
        if (acknowledgeNoEncrypt) append(out, 0x09, new byte[] { 1 });
        return out.toByteArray();
    }

    /** 本地范围校验有意比原生 cor.e 未检查的有符号 long 更严格。 */
    private long hashPosition(TlvView.Part field) throws IOException {
        if (field == null) return 0;
        if (field.value.length == 0 || field.value.length > 8) throw new Core.Fault("FILE_TRANSFER_HASH_POSITION_INVALID");
        long value = 0;
        for (byte b : field.value) value = (value << 8) | (b & 255L);
        if (value < 0 || value > size) throw new Core.Fault("FILE_TRANSFER_HASH_POSITION_INVALID");
        return value;
    }
    private byte[] suffixHash(int position) throws IOException {
        MessageDigest digest = newDigest();
        for (int block = position / INTEGRITY_BLOCK_BYTES; block < blockHashes.length; block++) {
            byte[] data = verifiedBlock(block);
            int start = Math.max(0, position - block * INTEGRITY_BLOCK_BYTES);
            digest.update(data, start, data.length - start);
        }
        checkSize();
        return digest.digest();
    }

    private void verifyFrozen() throws IOException {
        verifyWholeSnapshot();
        fullHandleChecks++;
        for (int block = 0; block < blockHashes.length; block++) verifiedBlock(block);
        checkSize();
    }

    private void verifyWholeSnapshot() throws IOException {
        checkSize();
        fullSnapshotChecks++;
        frozen.verify();
        checkSize();
    }

    private byte[] readWindow(int offset, int count) throws IOException {
        byte[] result = new byte[count];
        int end = offset + count;
        int written = 0;
        for (int block = offset / INTEGRITY_BLOCK_BYTES;
             block <= (end - 1) / INTEGRITY_BLOCK_BYTES; block++) {
            byte[] data = verifiedBlock(block);
            int blockStart = block * INTEGRITY_BLOCK_BYTES;
            int start = Math.max(offset, blockStart);
            int until = Math.min(end, blockStart + data.length);
            int length = until - start;
            System.arraycopy(data, start - blockStart, result, written, length);
            written += length;
        }
        return result;
    }

    private byte[] verifiedBlock(int block) throws IOException {
        byte[] data = readBlock(block);
        if (!Arrays.equals(Core.digest(data), blockHashes[block])) {
            throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
        }
        return data;
    }

    private byte[] readBlock(int block) throws IOException {
        checkSize();
        int offset = block * INTEGRITY_BLOCK_BYTES;
        byte[] data = new byte[Math.min(INTEGRITY_BLOCK_BYTES, size - offset)];
        input.seek(offset);
        int read = 0;
        while (read < data.length) {
            int count = input.read(data, read, data.length - read);
            if (count < 0) throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
            if (count == 0) throw new Core.Fault("PAYLOAD_SNAPSHOT_READ_STALLED");
            read += count;
            handleBytesRead += count;
        }
        checkSize();
        return data;
    }

    private static final class FileIdentity {
        final Object key;
        final FileTime created;
        FileIdentity(BasicFileAttributes attributes) {
            key = attributes.fileKey();
            created = attributes.creationTime();
        }
        boolean sameAs(FileIdentity other) {
            if (key != null || other.key != null) return key != null && key.equals(other.key);
            // Windows 的 BasicFileAttributes 可能没有 fileKey；此处仅尽力检查本地
            // 路径身份，无法用 inode 证明抵御能够伪造时间的文件拥有者。
            // 请求字节始终另行与原始分块 SHA 比较。
            return created.equals(other.created);
        }
    }

    private FileIdentity readFileIdentity() throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(frozenFile.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException missingOrUnreadable) {
            throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
        }
        if (!attributes.isRegularFile() || attributes.size() != size) {
            throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
        }
        return new FileIdentity(attributes);
    }

    private void checkSize() throws IOException {
        if (!frozen.file.getCanonicalFile().equals(frozenFile)
                || !frozenIdentity.sameAs(readFileIdentity())) {
            throw new Core.Fault("PAYLOAD_SNAPSHOT_IDENTITY_CHANGED");
        }
        if (input == null || !input.getFD().valid() || input.length() != size) {
            throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");
        }
    }

    private static MessageDigest newDigest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new Core.Fault("SHA256_UNAVAILABLE"); }
    }

    private static void checkBody(byte[] body) throws IOException {
        if (body == null || body.length > TlvView.MAX_BYTES) {
            throw new Core.Fault("FILE_TRANSFER_BODY_INVALID");
        }
    }

    private static List<TlvView.Part> fields(byte[] body) throws IOException {
        checkBody(body);
        return TlvView.decode(body.clone());
    }

    private static TlvView.Part required(List<TlvView.Part> fields, int tag) throws IOException {
        TlvView.Part value = Wire.unique(fields, tag);
        if (value == null) throw new Core.Fault("FILE_TRANSFER_REQUIRED_TAG_" + Integer.toHexString(tag));
        return value;
    }

    private static long fixedNumber(TlvView.Part value, int bytes) throws IOException {
        if (value.value.length != bytes) {
            throw new Core.Fault("FILE_TRANSFER_FIELD_WIDTH_" + Integer.toHexString(value.tag));
        }
        return Core.number(value.value);
    }

    private static int byteNumber(TlvView.Part value) throws IOException {
        return (int) fixedNumber(value, 1);
    }

    /** Health metadata03/04读取完整非空BE1..4；文件单元仍只能使用合法u8 ID。 */
    private static int metadataByteNumber(TlvView.Part value) throws IOException {
        long number = Core.number(value.value);
        if (number > 255) throw new Core.Fault("FILE_TRANSFER_METADATA_U8_RANGE_" + Integer.toHexString(value.tag));
        return (int) number;
    }

    /** 只读文件登记回包形状。只输出标签/长度/数值/匹配布尔，不记录对端文件名或正文。 */
    public synchronized String infoReplyObservation(byte[] body) {
        try {
            List<TlvView.Part> values = fields(body);
            TlvView.Part name = Wire.unique(values, 0x01);
            return "tags=" + TlvView.tags(values) + " filename_match=" +
                (name == null ? "missing" : Boolean.toString(Arrays.equals(name.value, filename))) +
                " type=" + scalarObservation(values, 0x03) +
                " assigned_file_id=" + scalarObservation(values, 0x04) +
                " result=" + scalarObservation(values, 0x7f);
        } catch (IOException malformed) {
            return "tags=unparsed diagnostic_code=" + Core.code(malformed);
        }
    }

    private static String scalarObservation(List<TlvView.Part> values, int tag) {
        try {
            TlvView.Part value = Wire.unique(values, tag);
            if (value == null) return "missing";
            return Long.toString(Core.number(value.value));
        } catch (IOException malformed) { return "invalid"; }
    }

    private void requireRegistered() throws IOException {
        if (currentFileId < 0) throw new Core.Fault("FILE_TRANSFER_STAGE_" + stage.name());
    }

    /** 原生 ID 查找：有效的其他 ID 不属于本任务；ID 缺失或有歧义则报错。 */
    private boolean associatedId(List<TlvView.Part> fields) throws IOException {
        return byteNumber(required(fields, 0x01)) == currentFileId;
    }

    private boolean optionalAssociatedId(List<TlvView.Part> fields) throws IOException {
        TlvView.Part id = Wire.unique(fields, 0x01);
        return id == null || byteNumber(id) == currentFileId;
    }

    private void requireStage(Stage expected) throws IOException {
        if (stage != expected) throw new Core.Fault("FILE_TRANSFER_STAGE_" + stage.name());
    }

    private void requireDataStage() throws IOException {
        boolean allowed = stage == Stage.TRANSFERRING || stage == Stage.WAIT_PEER_RESULT
                || stage == Stage.WAIT_WINDOW;
        if (!allowed || currentFileId < 0 || negotiatedUnit <= 0 || noEncrypt < 0) {
            throw new Core.Fault("FILE_TRANSFER_STAGE_" + stage.name());
        }
    }

    private void requireOpen() throws IOException {
        if (stage == Stage.CLOSED) throw new Core.Fault("FILE_TRANSFER_CLOSED");
        if (stage == Stage.FAILED) throw new Core.Fault("FILE_TRANSFER_FAILED");
    }

    private static byte[] number32(long value) {
        byte[] out = new byte[4];
        put32(out, 0, value);
        return out;
    }

    private static void put32(byte[] out, int offset, long value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    private static void append(ByteArrayOutputStream out, int tag, byte[] value) throws IOException {
        byte[] field = Core.tlv(tag, value);
        out.write(field, 0, field.length);
    }

    private void fail(IOException failure) { terminalFailure(failure, false); }

    private void resultFailure(IOException failure) { terminalFailure(failure, true); }

    private void terminalFailure(IOException failure, boolean mayAcknowledgeRepeatedResults) {
        if (completionFailure == null) completionFailure = boundedFailureCode(failure);
        complete = false;
        stage = Stage.FAILED;
        resultAckAfterFailure = mayAcknowledgeRepeatedResults;
        closeInput(failure);
    }

    private static String boundedFailureCode(IOException failure) {
        String code = Core.code(failure);
        if (code == null || code.length() == 0) return "FILE_TRANSFER_LOCAL_FAILURE";
        int length = Math.min(code.length(), FAILURE_CODE_LIMIT);
        StringBuilder bounded = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char ch = code.charAt(i);
            bounded.append((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '_' ? ch : '_');
        }
        return bounded.toString();
    }

    private void closeInput(IOException failure) {
        if (input == null) return;
        try { input.close(); }
        catch (IOException closing) { failure.addSuppressed(closing); }
        finally { input = null; }
    }

    public synchronized String stateLabel() { return stage.name(); }
    public synchronized int fileId() { return currentFileId; }
    /** 累计准备的文件数据，包含对端请求的重传；绝不统计套接字 ACK。 */
    public synchronized long bytesSentOrPrepared() { return preparedBytes; }
    public int totalSize() { return size; }
    public synchronized boolean peerComplete() { return complete; }
    /** 最近一次明确关联的 07 所含原始 u8；初始值或该 07 缺失原始结果时为 null。 */
    public synchronized Integer peerResultRaw() { return peerResultRaw; }
    /** 仅当最近一次 onPacket 调用观察到已关联且有效的 07 时为 true。 */
    public synchronized boolean resultObserved() { return resultObserved; }
    /** 首次终止失败码，最多 96 个安全字符；绝不使用对端控制的信息正文。 */
    public synchronized String completionFailure() { return completionFailure; }
    /** 协商前为 -1，之后为其原始 u8（缺失 09 默认为 0）；只有值恰好为 1 才禁用加密。 */
    public synchronized int lastNoEncrypt() { return noEncrypt; }
    /** 接受有效协商前为 0。 */
    public synchronized int unitSize() { return negotiatedUnit; }

    /** 完整 Snapshot.verify 的尝试次数；mode3 正常流程为四次，不会每个窗口都执行。 */
    public synchronized long fullSnapshotChecks() { return fullSnapshotChecks; }
    /** 对原始句柄执行完整性遍历的次数，包含构造时建立初始分块索引的遍历。 */
    public synchronized long fullHandleChecks() { return fullHandleChecks; }
    /** 本类只读 RAF 实际读出的字节数，包含检查点读取。 */
    public synchronized long bytesReadFromFrozenHandle() { return handleBytesRead; }
    /** 为对端窗口执行的 RAF 读取字节数，包含失败或重复请求。 */
    public synchronized long windowBytesReadFromFrozenHandle() { return windowHandleBytesRead; }
    /** 本地文件系统身份检查的强度，不是对端的 u8 fileId；分块 SHA 检查始终执行。 */
    public String fileIdentityMode() { return frozenIdentity.key == null ? "creationTimeFallback" : "fileKey"; }

    /** 幂等操作；仅关闭本类只读句柄，绝不删除或修改固定载荷。 */
    @Override public synchronized void close() throws IOException {
        if (stage == Stage.CLOSED) return;
        stage = Stage.CLOSED;
        RandomAccessFile owned = input;
        input = null;
        if (owned != null) owned.close();
    }
}
