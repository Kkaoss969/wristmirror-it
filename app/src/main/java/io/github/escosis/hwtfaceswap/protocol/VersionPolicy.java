package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.List;

/** 仅在本地选择传输资源标识；不发送数据、不修改载荷，也不授予上传许可。 */
public final class VersionPolicy {
    private static final int MAX_VERSION_LENGTH = 10;

    private VersionPolicy() {}

    /**
     * 仅将 baseline 的最后一个十进制字段加一，绝不追加新字段。
     * 之前的字段及末字段已有的前导零逐字节保留。
     * 不向前缀进位，不回绕，不搜索冲突，也不回退到 baseline。
     *
     * 所选 ID 与 baseline 必须在传入的设备列表中恰好出现一次。
     * 冲突按 ID 与版本字符串精确匹配，不猜测语义等价。
     * 调用方另行负责将列表绑定当前设备、连接及观察。
     * 本方法不授予上传许可；仍需匹配的真实回包明确给出 error=0/need=1。
     */
    public static String next(String id, String baseline, List<TlvView.Face> listed) throws IOException {
        // 读取传入列表前先拒绝非法输入，不做空白裁剪或自动修复。
        Core.resource(id, baseline);
        String candidate = incrementTail(baseline);
        if (candidate.length() > MAX_VERSION_LENGTH) {
            throw new Core.Fault("VERSION_INCREMENT_OVERFLOW");
        }
        Core.resource(id, candidate);

        if (listed == null) throw new Core.Fault("VERSION_LIST_REQUIRED");
        int size = listed.size();
        if (size > TlvView.MAX_FIELDS) throw new Core.Fault("VERSION_LIST_LIMIT");
        int baselineMatches = 0;
        for (int i = 0; i < size; i++) {
            TlvView.Face face = listed.get(i);
            if (face == null || face.id == null || face.version == null) {
                throw new Core.Fault("VERSION_LIST_INVALID");
            }
            if (!id.equals(face.id)) continue;
            if (candidate.equals(face.version)) {
                throw new Core.Fault("VERSION_CANDIDATE_ALREADY_LISTED");
            }
            if (baseline.equals(face.version)) baselineMatches++;
        }
        if (baselineMatches == 0) throw new Core.Fault("VERSION_BASELINE_NOT_LISTED");
        if (baselineMatches != 1) throw new Core.Fault("VERSION_BASELINE_AMBIGUOUS");
        return candidate;
    }

    /** 十进制进位仅发生在这一字段内，不使用固定位宽的整数解析。 */
    private static String incrementTail(String version) {
        int start = version.lastIndexOf('.') + 1;
        String prefix = version.substring(0, start);
        char[] digits = version.substring(start).toCharArray();
        for (int i = digits.length - 1; i >= 0; i--) {
            if (digits[i] < '9') {
                digits[i]++;
                return prefix + new String(digits);
            }
            digits[i] = '0';
        }
        return prefix + "1" + new String(digits);
    }
}
