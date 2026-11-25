package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 独立处理原生运动健康 TLV 数据体与观察；不含 Android、厂商调用、帧封装、加密或 app-ID 猜测。 */
public final class Wire {
    /** TLV 最多 64 KiB，加上原生服务与命令字节后仍小于 65 KiB。 */
    public static final int MAX_NATIVE_PACKET_BYTES = TlvView.MAX_BYTES + 2;
    private static final int MAX_TEXT_BYTES = 32;
    // 单容器的字段上限不等于多条目列表的累计上限；累计节点仍有独立硬预算。
    private static final int MAX_TOTAL_FACE_FIELDS = 4096;

    private Wire() {}

    /** Exact business dispatch policy used before the existing session-envelope verification. */
    public static boolean businessPacket(int service, int command) {
        return service == 0x27 && (command == 1 || command == 2 || command == 3 || command == 5 || command == 6) ||
                service == 0x28 && (command == 2 || command == 3 || command == 4 || command == 5 || command == 6 || command == 7 || command == 8);
    }

    /** Bounded P2P control metadata only; payload tag7 is represented solely by its length. */
    public static String p2pMetadata(byte[] body) throws IOException {
        List<TlvView.Part> fields = TlvView.decode(body);
        TlvView.Part payload = unique(fields, 7);
        return "inner_command=" + numberObservation(fields, 1) + " sequence=" + numberObservation(fields, 2) +
                " source_package=" + packageObservation(unique(fields, 3)) +
                " target_package=" + packageObservation(unique(fields, 4)) +
                " data_bytes=" + (payload == null ? "absent" : Integer.toString(payload.value.length)) +
                " response_code=" + numberObservation(fields, 8) + " payload_not_logged=true handler=unregistered_no_ack";
    }

    private static String numberObservation(List<TlvView.Part> fields, int tag) {
        try { return String.valueOf(number(fields, tag)); } catch (IOException ignored) { return "invalid"; }
    }

    private static String packageObservation(TlvView.Part part) {
        if (part == null) return "absent";
        if (part.value.length == 0) return "empty";
        if (part.value.length > 80) return "overlong";
        for (byte b : part.value) if ((b & 255) > 127) return "non_ascii";
        String value = new String(part.value, StandardCharsets.US_ASCII);
        return value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+") ? value : "invalid";
    }

    /** 仅生成 TLV 数据体；原生命令与传输层另行提供 0x27/0x03 及原有帧封装。 */
    public static byte[] operation(String id, String ver) throws IOException {
        Core.resource(id, ver);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 1, id.getBytes(StandardCharsets.US_ASCII));
        append(out, 2, ver.getBytes(StandardCharsets.US_ASCII));
        append(out, 3, new byte[] { 1 });
        return out.toByteArray();
    }

    /** 原生删除使用实际已安装版本与 op2，绝不上传文件或追加授权。 */
    public static byte[] deletion(String id, String ver) throws IOException {
        Core.resource(id, ver);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 1, id.getBytes(StandardCharsets.US_ASCII));
        append(out, 2, ver.getBytes(StandardCharsets.US_ASCII));
        append(out, 3, new byte[] { 2 });
        return out.toByteArray();
    }

    /** 原生 0x27/0x01 数据体；不请求或编造未知的 mode/sync 字段。 */
    public static byte[] params() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int tag : new int[] { 1, 2, 3, 4, 5, 0x14, 0x15 }) {
            append(out, tag, new byte[0]);
        }
        return out.toByteArray();
    }

    /** 原生 0x27/0x02 数据体；保留调用方真实的 serverMode，包括模式 2。 */
    public static byte[] list(int serverMode) throws IOException {
        if (serverMode < 1 || serverMode > 3) throw new Core.Fault("LIST_SERVER_MODE_INVALID");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 1, new byte[0]);
        append(out, 6, new byte[] { (byte) serverMode });
        return out.toByteArray();
    }

    private static void append(ByteArrayOutputStream out, int tag, byte[] value) throws IOException {
        byte[] field = Core.tlv(tag, value);
        out.write(field, 0, field.length);
    }

    /**
     * 解码恰好以服务与命令两个字节开头的原生数据包。
     * 仅在 Environment.sIsAarInTheme 为 false 时使用，该检查由适配层负责；
     * 本工具绝不猜测或跳过 appId、加密封装或其他帧格式。
     */
    public static List<TlvView.Part> fields(byte[] nativePacket, int service, int command) throws IOException {
        if (service < 0 || service > 255 || command < 0 || command > 255) {
            throw new Core.Fault("WIRE_SERVICE_COMMAND_INVALID");
        }
        if (nativePacket == null || nativePacket.length < 2) {
            throw new Core.Fault("WIRE_PACKET_TRUNCATED");
        }
        if (nativePacket.length > MAX_NATIVE_PACKET_BYTES) {
            throw new Core.Fault("WIRE_PACKET_BYTES_LIMIT");
        }
        byte[] snapshot = nativePacket.clone();
        if ((snapshot[0] & 255) != service || (snapshot[1] & 255) != command) {
            throw new Core.Fault("WIRE_PACKET_HEADER_MISMATCH");
        }
        return TlvView.decode(Arrays.copyOfRange(snapshot, 2, snapshot.length));
    }

    /** 缺失字段返回 null；请求标签重复或公开输入格式非法时明确报错。 */
    public static TlvView.Part unique(List<TlvView.Part> fields, int tag) throws IOException {
        if (tag < 0 || tag > 255) throw new Core.Fault("WIRE_TAG_INVALID");
        validateParts(fields);
        TlvView.Part found = null;
        for (TlvView.Part field : fields) {
            if (field.tag != tag) continue;
            if (found != null) throw new Core.Fault("WIRE_DUPLICATE_TAG");
            found = field;
        }
        return found;
    }

    /** 非空且长度有界的 UTF-8 文本；不静默截断、不强制转换数字，也不替换非法字节。 */
    public static String text(List<TlvView.Part> fields, int tag) throws IOException {
        TlvView.Part field = unique(fields, tag);
        if (field == null) return null;
        if (field.value.length == 0 || field.value.length > MAX_TEXT_BYTES) {
            throw new Core.Fault("WIRE_TEXT_LENGTH_INVALID");
        }
        return Core.utf8(field.value);
    }

    /** 1..4 字节大端无符号数值；缺失时保持 null，绝不默认成零。 */
    public static Long number(List<TlvView.Part> fields, int tag) throws IOException {
        TlvView.Part field = unique(fields, tag);
        return field == null ? null : Long.valueOf(Core.number(field.value));
    }

    /**
     * 已观察到的列表结构为唯一 root81 → 重复 82 → 唯一 03/04/05 与可选 07。
     * 缺失 root81 不等于空列表；存在但为空的 root81 才是有效的空列表。
     * 每个条目需要非空 ID、版本文本及类型数值，可选 07 可以缺失。
     * 其他标签仅保留为观察，不猜测容器或资源身份。
     * 共享节点预算覆盖根字段、82 条目及条目字段，而非只限制每一层。
     * 不符合 Core.resource 的有效文本仍可显示为不支持的条目；
     * 所选替换目标另由 VersionPolicy/Core.resource 校验。
     */
    public static List<TlvView.Face> faces(List<TlvView.Part> fields) throws IOException {
        return readFaces(fields, false).faces;
    }

    /** Partial rows are display-only observations, never a complete device inventory. */
    public static final class FaceListObservation {
        public final List<TlvView.Face> faces;
        public final List<IncompleteFace> incompleteEntries;
        private FaceListObservation(List<TlvView.Face> faces, List<IncompleteFace> incomplete) {
            this.faces = Collections.unmodifiableList(new ArrayList<TlvView.Face>(faces));
            this.incompleteEntries = Collections.unmodifiableList(new ArrayList<IncompleteFace>(incomplete));
        }
        public boolean complete() { return incompleteEntries.isEmpty(); }
    }

    /** Incomplete entries remain non-targets; only bounded decimal resource IDs are retained for diagnostics. */
    public static final class IncompleteFace {
        public final int index;
        public final boolean missingId, missingVersion, missingType;
        public final String tags, observedId;
        public final Long observedType;
        private IncompleteFace(int index, String id, String version, Long type, String tags) {
            this.index = index; missingId = id == null; missingVersion = version == null; missingType = type == null; this.tags = tags;
            observedId = id != null && id.matches("[0-9]{1,11}") ? id : null;
            observedType = type;
        }
        public String summary() {
            return "entry_index=" + index + " missing_id=" + missingId + " missing_version=" + missingVersion +
                    " missing_type=" + missingType + " observed_id=" + observedId + " observed_type=" + observedType +
                    " id_only_not_resource_key=true tags=" + tags;
        }
    }

    /** Absent/empty required row fields can be isolated for display; no default identity or type is invented. */
    public static FaceListObservation observeFaces(List<TlvView.Part> fields) throws IOException {
        return readFaces(fields, true);
    }

    private static String observedText(List<TlvView.Part> data, int tag) throws IOException {
        TlvView.Part field = unique(data, tag);
        return field == null || field.value.length == 0 ? null : text(data, tag);
    }

    private static Long observedNumber(List<TlvView.Part> data, int tag) throws IOException {
        TlvView.Part field = unique(data, tag);
        return field == null || field.value.length == 0 ? null : number(data, tag);
    }

    private static FaceListObservation readFaces(List<TlvView.Part> fields, boolean allowIncomplete) throws IOException {
        TlvView.Part root = unique(fields, 0x81);
        if (root == null) throw new Core.Fault("WIRE_FACE_LIST_ROOT_MISSING");
        Budget budget = new Budget();
        budget.initial(fields);
        List<TlvView.Part> entries = budget.decode(root.value);
        List<TlvView.Face> out = new ArrayList<TlvView.Face>();
        List<IncompleteFace> incomplete = new ArrayList<IncompleteFace>();
        int index = 0;
        for (TlvView.Part entry : entries) {
            if (entry.tag != 0x82) continue;
            List<TlvView.Part> data = budget.decode(entry.value);
            String id = allowIncomplete ? observedText(data, 3) : text(data, 3);
            String ver = allowIncomplete ? observedText(data, 4) : text(data, 4);
            Long type = allowIncomplete ? observedNumber(data, 5) : number(data, 5);
            Long expand = number(data, 7);
            if (id == null || ver == null || type == null) {
                if (!allowIncomplete) throw new Core.Fault("WIRE_FACE_REQUIRED_FIELD_MISSING");
                incomplete.add(new IncompleteFace(index, id, ver, type, TlvView.tags(data)));
            } else {
                out.add(new TlvView.Face(id, ver, type, expand));
            }
            index++;
        }
        return new FaceListObservation(out, incomplete);
    }

    private static void validateParts(List<TlvView.Part> fields) throws IOException {
        if (fields == null) throw new Core.Fault("WIRE_FIELDS_REQUIRED");
        if (fields.size() > TlvView.MAX_FIELDS) throw new Core.Fault("WIRE_FIELDS_LIMIT");
        long bytes = 0;
        for (TlvView.Part field : fields) {
            if (field == null || field.tag < 0 || field.tag > 255 || field.value == null) {
                throw new Core.Fault("WIRE_FIELD_INVALID");
            }
            bytes += field.value.length;
            if (bytes > TlvView.MAX_BYTES) throw new Core.Fault("WIRE_FIELDS_BYTES_LIMIT");
        }
    }

    private static final class Budget {
        private int nodesLeft = MAX_TOTAL_FACE_FIELDS;
        // 只处理已知的三层；此总处理上限也限制所有解码值副本的规模。
        private int bytesLeft = TlvView.MAX_BYTES * TlvView.MAX_DEPTH;

        void initial(List<TlvView.Part> fields) throws IOException {
            takeNodes(fields.size());
            for (TlvView.Part field : fields) takeBytes(field.value.length);
        }

        List<TlvView.Part> decode(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length > TlvView.MAX_BYTES) {
                throw new Core.Fault("TLV_VIEW_BYTES_LIMIT");
            }
            takeBytes(bytes.length);
            List<TlvView.Part> decoded = TlvView.decode(bytes);
            takeNodes(decoded.size());
            return decoded;
        }

        private void takeNodes(int count) throws IOException {
            if (count > nodesLeft) throw new Core.Fault("WIRE_TOTAL_FIELDS_LIMIT");
            nodesLeft -= count;
        }

        private void takeBytes(int count) throws IOException {
            if (count > bytesLeft) throw new Core.Fault("WIRE_TOTAL_BYTES_LIMIT");
            bytesLeft -= count;
        }
    }
}
