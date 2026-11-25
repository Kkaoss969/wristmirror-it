package io.github.escosis.hwtfaceswap.protocol;

/**
 * 连接连续失败后的重新协议绑定升级判定。
 *
 * 依据 1124 20:00 日志：手表切换模式后，持久协议凭据会被设备拒绝，且失败形状可能是
 * ISO 阶段 1 的令牌失配（{@code ISO_S1_TOKEN_MISMATCH}），而不是阶段 2 的对端拒绝。
 * 参考实现只在阶段 2 提供重新绑定入口时，用户会连续重试十几次仍无法连上。
 *
 * 本类只做计数/分类，不解释失败原因：达到阈值后由界面弹出一次需要用户确认的
 * 重新协议绑定对话框（清空本应用持久协议密钥后重新配对），不自动重试、不写设备数据。
 */
public final class RebindPolicy {
    /** 连续第几次使用已存密钥被拒后，主动提供重新绑定入口。 */
    public static final int THRESHOLD = 3;

    private RebindPolicy() { }

    /** 只统计“设备拒绝已存协议凭据”这一族失败；传输层失败不触发重新绑定。 */
    public static boolean savedKeyRejection(String code) {
        return "ISO_S1_TOKEN_MISMATCH".equals(code) || "ISO_PEER_ERROR".equals(code);
    }

    public static boolean reached(int rejections) { return rejections >= THRESHOLD; }
}