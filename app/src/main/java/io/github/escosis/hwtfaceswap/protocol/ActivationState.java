package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pure observations for one existing supported face's same-ID, installed-version op1.
 * The owner validates the requested source row and scopes replies to its actual identity;
 * this state never chooses another resource, sends a command, transfers a file or retries.
 * Only an explicit successful 27/03 permits one QUERY. The owner must attach the generation
 * when creating that query, supply increasing positive local query serials, and bound polls
 * and timeouts itself. ACTIVATED confirms a fresh list's unique target-current marker,
 * not independently observed physical display, persistence or a newly installed package.
 */
public final class ActivationState {
    public enum Phase { WAIT_OPERATION, WAIT_LIST, ACTIVATED, FAILED, TIMED_OUT }
    public enum Action { NONE, QUERY }

    public final String id, version;
    public Phase phase = Phase.WAIT_OPERATION;
    public String reason = "WAITING_EXPLICIT_ACTIVATION_27_03";
    public Long operationError, need, operationEcho, reportError, firstError;
    public Integer reportType;
    public boolean operationObserved, reportOneObserved, listed, current;
    public int signalGeneration, listObservations, targetCount, currentCount;
    private long lastObservedQuery;

    /** Captures the original installed resource key; no version increment is performed. */
    public ActivationState(String id, String version) throws IOException {
        Core.resource(id, version);
        this.id = id;
        this.version = version;
    }

    public boolean matches(String id, String version) {
        return this.id.equals(id) && this.version.equals(version);
    }

    public boolean terminal() {
        return phase == Phase.ACTIVATED || phase == Phase.FAILED || phase == Phase.TIMED_OUT;
    }

    /** A missing op echo is compatible; an explicit echo for any other op is unrelated. */
    public Action operation(Long error, Long transferNeed, Long echo) {
        if (echo != null && echo.longValue() != 1) return Action.NONE;
        operationError = error;
        need = transferNeed;
        operationEcho = echo;
        if (error != null && error.longValue() != 0) {
            fail("ACTIVATION_OPERATION_ERROR", error);
            return Action.NONE;
        }
        // 0105 日志：本机 27/03 回包缺 7F。原厂 Health handleApply / 腕连 w0.H 都按 0 初始化；
        // 原始观测仍为 null，且缺 04 need 仍然拒绝。
        if (transferNeed == null) {
            fail("ACTIVATION_OPERATION_FIELDS_MISSING", null);
            return Action.NONE;
        }
        if (transferNeed.longValue() != 0) {
            fail(transferNeed.longValue() == 1
                    ? "ACTIVATION_UNEXPECTED_FILE_REQUEST_NO_TRANSFER"
                    : "ACTIVATION_OPERATION_NEED_INVALID", null);
            return Action.NONE;
        }
        if (terminal() || operationObserved) return Action.NONE;
        operationObserved = true;
        signalGeneration++;
        phase = Phase.WAIT_LIST;
        reason = "ACTIVATION_OPERATION_CONFIRMED_WAIT_NEW_CURRENT_LIST";
        return Action.QUERY;
    }

    /** Report 1 is optional observation only; report 2 is never an activation/file grant. */
    public Action report(Integer type, Long error) {
        reportType = type;
        reportError = error;
        if (error != null && error.longValue() != 0 && error.longValue() != 100000) {
            fail("ACTIVATION_REPORT_ERROR", error);
            return Action.NONE;
        }
        if (type != null && type.intValue() == 3) {
            fail("ACTIVATION_REPORT_THREE_FAILURE", null);
            return Action.NONE;
        }
        if (type != null && type.intValue() == 1) {
            reportOneObserved = true;
            if (!terminal() && !operationObserved)
                reason = "ACTIVATION_REPORT_ONE_WAIT_EXPLICIT_OPERATION";
        }
        return Action.NONE;
    }

    // Match Wire's observed text contract, not Core.resource's stricter target syntax.
    private static boolean observedText(String text) {
        if (text == null || text.isEmpty() || text.length() > 32) return false;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            return bytes.length <= 32 && text.equals(Core.utf8(bytes));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean observedNumber(Long value) {
        return value != null && value.longValue() >= 0 && value.longValue() <= 0xffffffffL;
    }

    private static boolean observedFace(TlvView.Face face) {
        return face != null && observedText(face.id) && observedText(face.version)
                && observedNumber(face.type)
                && (face.expandType == null || observedNumber(face.expandType));
    }

    /**
     * Only a new locally owned query created after the successful operation can confirm.
     * Unsupported numeric resource text on unrelated entries remains displayable. Every
     * structurally observed current bit counts, even on protected or unsupported faces.
     * A still-noncurrent/ambiguous list waits for the owner's bounded polling, with no action.
     */
    public void list(List<TlvView.Face> faces, int generation, boolean ownQuery, long serial) {
        if (terminal() || phase != Phase.WAIT_LIST || !operationObserved
                || signalGeneration <= 0 || !ownQuery || generation != signalGeneration
                || serial <= 0 || serial <= lastObservedQuery) return;
        lastObservedQuery = serial;
        listObservations++;
        listed = false;
        current = false;
        targetCount = 0;
        currentCount = 0;
        if (faces == null) {
            fail("ACTIVATION_LIST_MISSING", null);
            return;
        }
        if (faces.size() > TlvView.MAX_FIELDS) {
            fail("ACTIVATION_LIST_LIMIT", null);
            return;
        }
        TlvView.Face target = null;
        for (TlvView.Face face : faces) {
            if (!observedFace(face)) {
                fail("ACTIVATION_LIST_INVALID", null);
                return;
            }
            if (id.equals(face.id)) {
                targetCount++;
                target = face;
            }
            if (FacePolicy.current(face)) currentCount++;
        }
        if (targetCount == 0) {
            fail("ACTIVATION_TARGET_ID_ABSENT", null);
            return;
        }
        if (targetCount != 1) {
            fail("ACTIVATION_TARGET_ID_DUPLICATE", null);
            return;
        }
        listed = version.equals(target.version);
        current = FacePolicy.current(target);
        if (!listed) {
            fail("ACTIVATION_TARGET_INSTALLED_VERSION_CHANGED", null);
            return;
        }
        if (!FacePolicy.canActivate(target)) {
            fail("ACTIVATION_TARGET_NO_LONGER_ACTIVATABLE", null);
            return;
        }
        if (current && currentCount == 1) {
            phase = Phase.ACTIVATED;
            reason = "ACTIVATION_FRESH_LIST_TARGET_CURRENT_VISUAL_CHECK_REQUIRED";
        } else {
            reason = currentCount > 1 ? "ACTIVATION_WAIT_UNIQUE_CURRENT_MARKER"
                    : "ACTIVATION_TARGET_NOT_CURRENT_WAIT_NEW_LIST";
        }
    }

    /** Genuine nonzero errors are retained once; a late failure may downgrade ACTIVATED. */
    public void fail(String why, Long error) {
        if (firstError == null && error != null && error.longValue() != 0) firstError = error;
        if (!terminal() || phase == Phase.ACTIVATED) {
            phase = Phase.FAILED;
            reason = Core.clean(why);
        }
    }

    public void timeout() {
        if (!terminal()) {
            phase = Phase.TIMED_OUT;
            reason = "ACTIVATION_TIMEOUT_NOT_A_VISUAL_VERDICT";
        }
    }

    /** Resource/response metadata only; no credentials, HWT content or unrelated face text. */
    public String summary() {
        return "phase=" + phase + " reason=" + Core.clean(reason) + " id=" + id + " version=" + version
                + " operation_error=" + operationError + " need=" + need + " operation_echo=" + operationEcho
                + " report_type=" + reportType + " report_error=" + reportError
                + " operation_observed=" + operationObserved + " report_one_observed=" + reportOneObserved
                + " signal_generation=" + signalGeneration + " list_observations=" + listObservations
                + " last_query_serial=" + lastObservedQuery + " target_count=" + targetCount
                + " listed=" + listed + " current=" + current + " current_count=" + currentCount
                + " first_error=" + firstError;
    }
}
