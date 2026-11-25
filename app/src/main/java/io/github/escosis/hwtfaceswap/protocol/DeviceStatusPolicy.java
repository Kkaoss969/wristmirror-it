package io.github.escosis.hwtfaceswap.protocol;

/**
 * 预认证 01/16（设备状态）的兼容判定。
 *
 * 参考实现都不把 7F 当门槛，只看 tag1 的设备状态：
 * · Gadgetbridge 0.95.1 `DeviceConfig$DeviceStatus$Response.parseTlv` 仅在
 *   contains(1) 时读取状态，缺 tag1 时字段保持默认 -1；其回调只在 -1/0/1 时继续
 *   初始化认证（initializeDeviceDealHiChain），其它值直接返回，不判协议错误。
 * · 腕连 3.5 的 01/16 分发读取 tag1 默认 -1 并继续，同样没有 7F 判错分支。
 *
 * 因此这里的 7F 只作为观察值记录（{@link #advisory(Long)}），是否继续完全取决于
 * 状态字节。本类不解释 7F 的具体数值，也不据此推断配对/占用/不支持等原因。
 */
public final class DeviceStatusPolicy {
    /** 缺 tag1 时的观察值，等于参考实现的字段默认值。 */
    public static final long ABSENT = -1L;

    private DeviceStatusPolicy() { }

    public static long status(Long observed) { return observed == null ? ABSENT : observed; }

    /** 与参考实现一致：仅 -1/0/1 可以继续认证初始化。 */
    public static boolean mayContinue(long status) { return status == ABSENT || status == 0L || status == 1L; }

    /** 7F 存在且不是成功码：只记录，不在本阶段中止。 */
    public static boolean advisory(Long error) { return error != null && error != 0 && error != 100000; }

    /** 状态值不在参考实现可继续的范围时使用；原因里保留真实数值。 */
    public static String stopReason(long status) { return "DEVICE_STATUS_UNUSUAL_STATUS_" + status; }
}