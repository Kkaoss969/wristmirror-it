package io.github.escosis.hwtfaceswap.protocol;

/**
 * 设备在文件信息阶段给出的“容量/数量”类明确拒绝。
 *
 * 140004 / 100004：腕连 native w0.Z0 把这一组码归为同一类提示，Gadgetbridge 0.95.1
 * 的 onError 对 140004 打印 “Too many watchfaces installed or musics uploaded”；
 * 华为健康把 140004 命名为 DEVICE_REPORT_FILE_LIMITED 并映射 FILE_LIMITED，
 * 其上传前检查也在“表盘数量已满”时主动报同一个码。
 * 140009：华为健康 DEVICE_REPORT_NO_SPACE，只在剩余空间不足时使用。
 *
 * 这组码只说明设备拒绝了这次文件登记，不证明具体上限数值、也不证明
 * 是表盘槽位还是音乐等共享配额已满；未知码一律不归类。
 */
public final class DeviceRefusal {
    private DeviceRefusal() { }

    /** 文件/表盘数量受限。 */
    public static boolean limited(long code) { return code == 140004L || code == 100004L; }
    /** 设备剩余空间不足。 */
    public static boolean noSpace(long code) { return code == 140009L; }
    /** 属于本类可解释的明确拒绝。 */
    public static boolean known(long code) { return limited(code) || noSpace(code); }

    /** 从 “FILE_TRANSFER_INFO_ERROR_140004” 这类原因串尾部取码；不是码就返回 0。 */
    public static long codeOf(String reason) {
        if (reason == null) return 0L;
        int end = reason.length(), start = end;
        while (start > 0 && reason.charAt(start - 1) >= '0' && reason.charAt(start - 1) <= '9') start--;
        if (start == end || start == 0 || reason.charAt(start - 1) != '_') return 0L;
        if (end - start > 10) return 0L;
        try { return Long.parseLong(reason.substring(start, end)); } catch (NumberFormatException unsupported) { return 0L; }
    }

    /** 稳定的本地原因标签；未知码返回空串，不伪造设备语义。 */
    public static String reason(long code) {
        if (limited(code)) return "DEVICE_FILE_LIMITED_" + code;
        if (noSpace(code)) return "DEVICE_NO_SPACE_" + code;
        return "";
    }
}