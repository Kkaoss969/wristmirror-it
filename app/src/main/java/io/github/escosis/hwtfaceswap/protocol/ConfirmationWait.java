package io.github.escosis.hwtfaceswap.protocol;

/** Upload completion starts one bounded confirmation window; later reports never extend it. */
public final class ConfirmationWait {
    public static final long TIMEOUT_MS = 30000L;
    private long startedAt = -1L;

    public void start(long now) {
        if (startedAt < 0 && now >= 0) startedAt = now;
    }
    public long elapsed(long now) {
        return startedAt < 0 || now < startedAt ? 0 : now - startedAt;
    }
    public boolean expired(long now) {
        return startedAt >= 0 && now >= startedAt && elapsed(now) >= TIMEOUT_MS;
    }
    public boolean started() { return startedAt >= 0; }
}
