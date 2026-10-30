package com.yednamo.testswap;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure decision rules of the SDK: no Android classes, no I/O, so plain JUnit tests cover them.
 * {@link TestSwap} only moves data between the server, Play Integrity and SharedPreferences.
 */
final class Policy {
    static final long MINUTE_MS = 60L * 1000;
    static final long HOUR_MS = 60 * MINUTE_MS;
    /** Transient trouble: network, 5xx, rate limit. */
    static final long RETRY_NETWORK_MS = 30 * MINUTE_MS;
    /** Base delay after a failed Play Integrity request (no Play Store, outdated, throttled). */
    static final long RETRY_INTEGRITY_MS = 60 * MINUTE_MS;
    /** Nothing will change soon: waiting for access, missing keys, rejected verdict. */
    static final long RETRY_LATER_MS = 6 * HOUR_MS;
    /** Longest back-off for repeated Play Integrity failures. */
    static final long BACKOFF_MAX_MS = 24 * HOUR_MS;
    /** No stored delay may exceed this; a next_at further away means the device clock jumped. */
    static final long MAX_DELAY_MS = 36 * HOUR_MS;
    /** A flow that has not finished within this time is considered dead (lost callback). */
    static final long FLOW_TIMEOUT_MS = 2 * MINUTE_MS;

    /** Same pattern as the server (parseReferrer in api/src/domain/rules.ts). */
    private static final Pattern TOKEN_IN_REFERRER =
            Pattern.compile("(?:^|[&?])?ts_([A-Za-z0-9_-]{20,64})(?:$|&)");

    private Policy() {
    }

    /** What to do after a server reply or a Play Integrity failure. */
    enum Kind {
        /** Try again after {@link Decision#delayMs}. */
        RETRY,
        /** Re-run challenge + Integrity right away, once. */
        RETRY_NOW,
        /** The test is over or the install is not linked to TestSwap: stop forever. */
        DONE,
        /** Not a TestSwap install: forget the referrer and stay silent forever. */
        SILENT
    }

    static final class Decision {
        static final Decision DONE = new Decision(Kind.DONE, 0, false);
        static final Decision SILENT = new Decision(Kind.SILENT, 0, false);
        static final Decision RETRY_NOW = new Decision(Kind.RETRY_NOW, 0, false);

        final Kind kind;
        final long delayMs;
        /** The delay is a Play Integrity back-off: the caller bumps the failure counter. */
        final boolean integrityFailure;

        private Decision(Kind kind, long delayMs, boolean integrityFailure) {
            this.kind = kind;
            this.delayMs = delayMs;
            this.integrityFailure = integrityFailure;
        }

        static Decision retry(long delayMs) {
            return new Decision(Kind.RETRY, delayMs, false);
        }

        static Decision backoff(long baseMs, int failures) {
            return new Decision(Kind.RETRY, backoffMs(baseMs, failures), true);
        }
    }

    /** Participation token from the Install Referrer string, or null for a regular install. */
    static String tokenFromReferrer(String referrer) {
        if (referrer == null) return null;
        // Google Play may return the referrer as is or URL-encoded, sometimes with utm parameters.
        String s = referrer.trim();
        try {
            s = URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            // Not URL-encoded: use as is.
        }
        Matcher m = TOKEN_IN_REFERRER.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static boolean isOver(String status) {
        return !"active".equals(status) && !"pending_access".equals(status);
    }

    /** Error codes after which retrying will never help. */
    static boolean isTerminal(String error) {
        return "unknown_install".equals(error) || "sdk_not_enabled".equals(error) || "not_testswap_install".equals(error);
    }

    /** Due when next_at has passed, or lies further ahead than any delay the SDK stores (clock jumped). */
    static boolean isDue(long nextAtMs, long nowMs) {
        return nowMs >= nextAtMs || nextAtMs - nowMs > MAX_DELAY_MS;
    }

    static long capDelay(long delayMs) {
        return Math.max(0, Math.min(delayMs, MAX_DELAY_MS));
    }

    /** base × 2^failures, at most {@link #BACKOFF_MAX_MS}. */
    static long backoffMs(long baseMs, int failures) {
        long d = Math.max(0, baseMs);
        for (int i = 0; i < failures && d < BACKOFF_MAX_MS; i++) d *= 2;
        return Math.min(d, BACKOFF_MAX_MS);
    }

    /** A new flow may start when none is running or the running one is older than {@link #FLOW_TIMEOUT_MS}. */
    static boolean canStart(long flowStartedAt, long nowMs) {
        return flowStartedAt == 0 || nowMs - flowStartedAt > FLOW_TIMEOUT_MS;
    }

    /** Delay until the next check-in from the server's nextCheckinInSec. */
    static long nextCheckinDelay(long nextCheckinInSec) {
        return nextCheckinInSec > 0 ? capDelay(nextCheckinInSec * 1000) : RETRY_LATER_MS;
    }

    /**
     * Non-200 reply to /sdk/v2/challenge.
     * fromReferrer: the token was just read from the Install Referrer and is not stored yet.
     */
    static Decision onChallengeError(int code, String error, boolean fromReferrer) {
        if (code == 410 || ((code == 404 || code == 409) && isTerminal(error))) {
            return fromReferrer ? Decision.SILENT : Decision.DONE;
        }
        if (code == 400) {
            // Our request is malformed: the same one will not pass next time either.
            return fromReferrer ? Decision.SILENT : Decision.retry(RETRY_LATER_MS);
        }
        if (code == 503) return Decision.retry(RETRY_LATER_MS);
        return Decision.retry(RETRY_NETWORK_MS);
    }

    /**
     * Non-200 reply to /sdk/v2/checkin.
     * retriedNow: the one immediate re-run after a nonce error has already been used.
     * failures: Play Integrity failures in a row so far.
     */
    static Decision onCheckinError(int code, String error, String reason, boolean retriedNow, int failures) {
        if (code == 410 || ((code == 404 || code == 409) && isTerminal(error))) return Decision.DONE;
        if (code == 403) {
            // The nonce expired or was used up (slow Play services, duplicate flow): a fresh one usually works.
            boolean nonce = "nonce_mismatch".equals(reason) || "stale".equals(reason);
            if (nonce && !retriedNow) return Decision.RETRY_NOW;
            return Decision.backoff(nonce ? RETRY_NETWORK_MS : RETRY_LATER_MS, failures);
        }
        if (code == 400 || code == 503) return Decision.retry(RETRY_LATER_MS);
        return Decision.retry(RETRY_NETWORK_MS);
    }

    /** Play Integrity request failed (no Play Store, outdated Play services, throttled, network). */
    static Decision onIntegrityFailure(int failures) {
        return Decision.backoff(RETRY_INTEGRITY_MS, failures);
    }
}
