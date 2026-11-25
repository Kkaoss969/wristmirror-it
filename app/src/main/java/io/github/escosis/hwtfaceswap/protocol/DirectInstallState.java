package io.github.escosis.hwtfaceswap.protocol;

import java.io.IOException;
import java.util.List;

/**
 * Wrist Assistant native direct-install completion model (original 3.5 DEX).
 * An acknowledged 28/07 creates a pending confirmation, without inspecting its raw result.
 * A matching non-2 report or accepted post operation completes the protocol task;
 * list/current observations remain separate and never prove rendering success.
 */
public final class DirectInstallState {
    // WAIT_LIST/LISTED and QUERY remain source-compatible names, unused by this route.
    public enum Phase { TRANSFERRING, WAIT_REPORT, WAIT_POST, WAIT_LIST, LISTED, COMPLETED, FAILED, CANCELLED, TIMED_OUT }
    public enum Action { NONE, ACTIVATE, QUERY }

    public final String id, version;
    public Phase phase = Phase.TRANSFERRING;
    public String reason = "DIRECT_HWT_UPLOAD_STARTED";
    public boolean uploadIssued = true, reportReceived, postIssued, postObserved, listed, current;
    public boolean nativeRespondObserved, existingResourceObserved;
    public Integer nativeRespond, report;
    public Long reportError, postError, postNeed, firstError;
    public int postGeneration, listObservations;

    public DirectInstallState(String id, String version) throws IOException {
        Core.resource(id, version);
        this.id = id;
        this.version = version;
    }

    public boolean matches(String id, String version) { return this.id.equals(id) && this.version.equals(version); }
    public boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.LISTED || phase == Phase.FAILED || phase == Phase.CANCELLED || phase == Phase.TIMED_OUT; }

    /** Called only for an owned 28/07 after its ACK has been dispatched by the caller. */
    public Action nativeResult(Integer raw) {
        nativeRespondObserved = true;
        nativeRespond = raw;
        if (!terminal() && phase == Phase.TRANSFERRING) {
            phase = Phase.WAIT_REPORT;
            reason = "DIRECT_ACKED_FILE_RESULT_WAIT_NATIVE_CONFIRMATION";
        }
        // Reports arriving before the pending confirmation exists are not deferred.
        return Action.NONE;
    }

    /** Owned 28/02 returned native already-exists (140008 or 100008), without an upload result. */
    public Action existingResource() {
        existingResourceObserved = true;
        if (terminal() || postIssued) return Action.NONE;
        postIssued = true;
        phase = Phase.WAIT_POST;
        reason = "DIRECT_NATIVE_EXISTING_RESOURCE_SEND_ONE_POST_ACTIVATION";
        return Action.ACTIVATE;
    }

    /** The caller matches the task and ACKs the report before executing any returned action. */
    public Action report(Integer type, Long error) {
        report = type;
        reportError = error; // Native w0.H does not consume report 7F.
        if (terminal() || !nativeRespondObserved) return Action.NONE;
        reportReceived = true;
        if (type != null && type.intValue() == 2) {
            if (!postIssued) {
                postIssued = true;
                phase = Phase.WAIT_POST;
                reason = "DIRECT_NATIVE_REPORT_TWO_SEND_ONE_POST_ACTIVATION";
                return Action.ACTIVATE;
            }
        } else {
            // Native d(3, 0) defaults absent/empty tag3 to 0; every non-2 type ends pending.
            phase = Phase.COMPLETED;
            reason = "DIRECT_NATIVE_REPORT_COMPLETED_VISUAL_CHECK_REQUIRED";
        }
        return Action.NONE;
    }

    public Action operation(Long error, Long need) {
        if (!postIssued) return Action.NONE;
        postError = error;
        postNeed = need; // Original native 27/03 never reads tag4.
        if (error != null && error.longValue() != 0 && error.longValue() != 100000) {
            fail("DIRECT_POST_OPERATION_ERROR", error);
            return Action.NONE;
        }
        if (terminal()) return Action.NONE;
        postObserved = true;
        postGeneration++;
        phase = Phase.COMPLETED;
        reason = "DIRECT_NATIVE_POST_COMPLETED_VISUAL_CHECK_REQUIRED";
        return Action.NONE;
    }

    /** Query identity is retained in the API, but freshness is not an installation gate. */
    public void list(List<TlvView.Face> faces, int queryGeneration, boolean ownQuery) {
        listObservations++;
        listed = false;
        current = false;
        int count = 0;
        for (TlvView.Face face : faces) if (matches(face.id, face.version)) {
            count++;
            if (face.type != null) current = (face.type.longValue() & 1) != 0;
        }
        listed = count == 1;
        if (!listed) current = false;
    }

    /** Explicit caller/local failures may invalidate a verdict; they never restart an upload. */
    public void fail(String why, Long error) {
        if (firstError == null && error != null) firstError = error;
        reason = why;
        phase = Phase.FAILED;
    }
    public void cancel() { if (!terminal()) { phase = Phase.CANCELLED; reason = "USER_CANCELLED"; } }
    public void timeout() { if (!terminal()) { phase = Phase.TIMED_OUT; reason = "DIRECT_HWT_TIMEOUT_NOT_INSTALL_VERDICT"; } }

    /** UI notice only: does not change the native completion contract or send any command. */
    public static String completionNotice(String summary) {
        boolean needOne = summary != null && java.util.regex.Pattern.compile("(?:^|\\s)post_need=1(?:\\s|$)").matcher(summary).find();
        if (needOne) return "Procedura di installazione diretta lato app terminata; il watch ha risposto need=1, ricezione o applicazione del quadrante non ancora confermate. Il file non verrà ricaricato: salva il log e controlla l'elenco.";
        return "Procedura di installazione diretta lato app terminata, ma ricezione o applicazione del quadrante non ancora confermate. Controlla il watch e aggiorna l'elenco.";
    }

    public String summary() {
        return "phase=" + phase + " reason=" + reason + " upload_issued=" + uploadIssued +
                " native_result=" + nativeRespond + " native_result_observed=" + nativeRespondObserved +
                " metadata_existing=" + existingResourceObserved +
                " report=" + report + " report_error=" + reportError + " report_two=" + (reportReceived && report != null && report.intValue() == 2) +
                " post_issued=" + postIssued + " post_error=" + postError +
                " post_error_defaulted=" + (postObserved && postError == null) + " post_need=" + postNeed +
                " listed=" + listed + " current=" + current + " generation=" + postGeneration +
                " list_observations=" + listObservations + " first_error=" + firstError +
                " completion_is_client_only=true application_unverified=true post_need_one_observed=" + (postObserved && postNeed != null && postNeed.longValue() == 1) +
                " reference=wrist_native raw_not_gate=true need_not_gate=true list_not_gate=true";
    }
}
