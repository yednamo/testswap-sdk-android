package com.yednamo.testswap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PolicyTest {
    /** 24 random bytes in base64url: 32 characters. */
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123-_";

    // Token extraction: same cases as the server's parseReferrer tests.

    @Test
    public void tokenFromPlainReferrer() {
        assertEquals(TOKEN, Policy.tokenFromReferrer("ts_" + TOKEN));
        assertEquals(TOKEN, Policy.tokenFromReferrer("  ts_" + TOKEN + "\n"));
    }

    @Test
    public void tokenFromEncodedReferrer() {
        assertEquals(TOKEN, Policy.tokenFromReferrer("ts_" + TOKEN.replace("-", "%2D").replace("_", "%5F")));
        assertEquals(TOKEN, Policy.tokenFromReferrer("utm_source%3Dx%26ts_" + TOKEN + "%26utm_medium%3Dy"));
    }

    @Test
    public void tokenAmongUtmParameters() {
        assertEquals(TOKEN, Policy.tokenFromReferrer("utm_source=google&ts_" + TOKEN + "&utm_medium=cpc"));
        assertEquals(TOKEN, Policy.tokenFromReferrer("?ts_" + TOKEN));
    }

    @Test
    public void noTokenForRegularInstall() {
        assertNull(Policy.tokenFromReferrer(null));
        assertNull(Policy.tokenFromReferrer(""));
        assertNull(Policy.tokenFromReferrer("utm_source=google-play&utm_medium=organic"));
        assertNull(Policy.tokenFromReferrer("ts_"));
        assertNull(Policy.tokenFromReferrer("ts_tooshort"));
        assertNull(Policy.tokenFromReferrer("ts_" + TOKEN + "!"));
    }

    @Test
    public void tokenLengthLimits() {
        assertEquals(letters(20), Policy.tokenFromReferrer("ts_" + letters(20)));
        assertEquals(letters(64), Policy.tokenFromReferrer("ts_" + letters(64)));
        assertNull(Policy.tokenFromReferrer("ts_" + letters(19)));
        assertNull(Policy.tokenFromReferrer("ts_" + letters(65)));
    }

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('a');
        return sb.toString();
    }

    @Test
    public void malformedPercentEncodingIsUsedAsIs() {
        assertEquals(TOKEN, Policy.tokenFromReferrer("ts_" + TOKEN + "&bad=%zz"));
    }

    // next_at guard and delay cap.

    @Test
    public void dueWhenNextAtPassed() {
        assertTrue(Policy.isDue(1000, 1000));
        assertTrue(Policy.isDue(1000, 2000));
        assertTrue(Policy.isDue(0, 5));
        assertFalse(Policy.isDue(2000, 1000));
    }

    @Test
    public void dueWhenClockJumpedBack() {
        long now = 1_700_000_000_000L;
        assertFalse(Policy.isDue(now + Policy.MAX_DELAY_MS, now));
        assertTrue(Policy.isDue(now + Policy.MAX_DELAY_MS + 1, now));
        assertTrue(Policy.isDue(now + 365 * 24 * Policy.HOUR_MS, now));
    }

    @Test
    public void delayIsCapped() {
        assertEquals(0, Policy.capDelay(-5));
        assertEquals(Policy.RETRY_LATER_MS, Policy.capDelay(Policy.RETRY_LATER_MS));
        assertEquals(Policy.MAX_DELAY_MS, Policy.capDelay(Policy.MAX_DELAY_MS + 1));
        assertEquals(Policy.MAX_DELAY_MS, Policy.capDelay(Long.MAX_VALUE));
    }

    @Test
    public void nextCheckinDelayFromServer() {
        assertEquals(3600 * 1000L, Policy.nextCheckinDelay(3600));
        assertEquals(Policy.RETRY_LATER_MS, Policy.nextCheckinDelay(0));
        assertEquals(Policy.RETRY_LATER_MS, Policy.nextCheckinDelay(-1));
        assertEquals(Policy.MAX_DELAY_MS, Policy.nextCheckinDelay(10 * 24 * 3600));
    }

    // Back-off.

    @Test
    public void backoffDoubles() {
        assertEquals(Policy.RETRY_INTEGRITY_MS, Policy.backoffMs(Policy.RETRY_INTEGRITY_MS, 0));
        assertEquals(2 * Policy.RETRY_INTEGRITY_MS, Policy.backoffMs(Policy.RETRY_INTEGRITY_MS, 1));
        assertEquals(8 * Policy.RETRY_INTEGRITY_MS, Policy.backoffMs(Policy.RETRY_INTEGRITY_MS, 3));
        assertEquals(16 * Policy.HOUR_MS, Policy.backoffMs(Policy.RETRY_INTEGRITY_MS, 4));
    }

    @Test
    public void backoffIsCappedAt24h() {
        assertEquals(Policy.BACKOFF_MAX_MS, Policy.backoffMs(Policy.RETRY_INTEGRITY_MS, 5));
        assertEquals(Policy.BACKOFF_MAX_MS, Policy.backoffMs(Policy.RETRY_INTEGRITY_MS, 100));
        assertEquals(Policy.BACKOFF_MAX_MS, Policy.backoffMs(Policy.RETRY_LATER_MS, 2));
        assertEquals(Policy.BACKOFF_MAX_MS, Policy.backoffMs(Policy.RETRY_LATER_MS, Integer.MAX_VALUE));
        assertEquals(Policy.RETRY_LATER_MS, Policy.backoffMs(Policy.RETRY_LATER_MS, -3));
    }

    @Test
    public void integrityFailureBacksOff() {
        Policy.Decision d = Policy.onIntegrityFailure(2);
        assertEquals(Policy.Kind.RETRY, d.kind);
        assertEquals(4 * Policy.RETRY_INTEGRITY_MS, d.delayMs);
        assertTrue(d.integrityFailure);
    }

    // Watchdog.

    @Test
    public void flowWatchdog() {
        assertTrue(Policy.canStart(0, 123));
        assertFalse(Policy.canStart(1000, 1000 + Policy.FLOW_TIMEOUT_MS));
        assertTrue(Policy.canStart(1000, 1000 + Policy.FLOW_TIMEOUT_MS + 1));
    }

    // Challenge replies.

    @Test
    public void challengeTerminalCodesStopForever() {
        for (String e : new String[] {"unknown_install", "sdk_not_enabled", "not_testswap_install"}) {
            assertEquals(Policy.Kind.DONE, Policy.onChallengeError(404, e, false).kind);
            assertEquals(Policy.Kind.DONE, Policy.onChallengeError(409, e, false).kind);
            assertEquals(Policy.Kind.SILENT, Policy.onChallengeError(404, e, true).kind);
        }
        assertEquals(Policy.Kind.DONE, Policy.onChallengeError(410, "sdk_upgrade_required", false).kind);
        assertEquals(Policy.Kind.SILENT, Policy.onChallengeError(410, "sdk_upgrade_required", true).kind);
    }

    @Test
    public void challengeUnknownCodeOn404IsRetried() {
        Policy.Decision d = Policy.onChallengeError(404, "", false);
        assertEquals(Policy.Kind.RETRY, d.kind);
        assertEquals(Policy.RETRY_NETWORK_MS, d.delayMs);
        assertFalse(d.integrityFailure);
        assertEquals(Policy.Kind.RETRY, Policy.onChallengeError(404, "not_found", true).kind);
        assertEquals(Policy.Kind.RETRY, Policy.onChallengeError(409, "conflict", false).kind);
    }

    @Test
    public void challengeBadRequest() {
        assertEquals(Policy.Kind.SILENT, Policy.onChallengeError(400, "bad_request", true).kind);
        Policy.Decision d = Policy.onChallengeError(400, "bad_request", false);
        assertEquals(Policy.Kind.RETRY, d.kind);
        assertEquals(Policy.RETRY_LATER_MS, d.delayMs);
    }

    @Test
    public void challengeTransientErrors() {
        assertEquals(Policy.RETRY_LATER_MS, Policy.onChallengeError(503, "integrity_not_configured", false).delayMs);
        assertEquals(Policy.RETRY_NETWORK_MS, Policy.onChallengeError(429, "rate_limited", false).delayMs);
        assertEquals(Policy.RETRY_NETWORK_MS, Policy.onChallengeError(500, "", false).delayMs);
        assertEquals(Policy.RETRY_NETWORK_MS, Policy.onChallengeError(502, "", true).delayMs);
        assertEquals(Policy.Kind.RETRY, Policy.onChallengeError(503, "integrity_not_configured", true).kind);
    }

    // Check-in replies.

    @Test
    public void checkinTerminalCodesStopForever() {
        assertEquals(Policy.Kind.DONE, Policy.onCheckinError(404, "unknown_install", "", false, 0).kind);
        assertEquals(Policy.Kind.DONE, Policy.onCheckinError(409, "sdk_not_enabled", "", false, 0).kind);
        assertEquals(Policy.Kind.DONE, Policy.onCheckinError(410, "sdk_upgrade_required", "", false, 0).kind);
        assertEquals(Policy.Kind.RETRY, Policy.onCheckinError(404, "", "", false, 0).kind);
        assertEquals(Policy.RETRY_NETWORK_MS, Policy.onCheckinError(409, "other", "", false, 0).delayMs);
    }

    @Test
    public void nonceErrorRetriesOnceThenBacksOff() {
        for (String reason : new String[] {"nonce_mismatch", "stale"}) {
            assertEquals(Policy.Kind.RETRY_NOW, Policy.onCheckinError(403, "integrity_failed", reason, false, 0).kind);
            Policy.Decision d = Policy.onCheckinError(403, "integrity_failed", reason, true, 0);
            assertEquals(Policy.Kind.RETRY, d.kind);
            assertEquals(Policy.RETRY_NETWORK_MS, d.delayMs);
            assertTrue(d.integrityFailure);
            assertEquals(2 * Policy.RETRY_NETWORK_MS, Policy.onCheckinError(403, "integrity_failed", reason, true, 1).delayMs);
        }
    }

    @Test
    public void verdictRejectedWaitsLonger() {
        String[] reasons = {"device_integrity", "not_licensed", "app_unrecognized", "test_response",
                "decrypt_failed", "bad_signature", "malformed", "wrong_package", ""};
        for (String reason : reasons) {
            Policy.Decision d = Policy.onCheckinError(403, "integrity_failed", reason, false, 0);
            assertEquals(reason, Policy.Kind.RETRY, d.kind);
            assertEquals(reason, Policy.RETRY_LATER_MS, d.delayMs);
            assertTrue(d.integrityFailure);
            assertEquals(reason, 2 * Policy.RETRY_LATER_MS, Policy.onCheckinError(403, "integrity_failed", reason, false, 1).delayMs);
            assertEquals(reason, Policy.BACKOFF_MAX_MS, Policy.onCheckinError(403, "integrity_failed", reason, false, 2).delayMs);
        }
    }

    @Test
    public void checkinTransientErrors() {
        assertEquals(Policy.RETRY_LATER_MS, Policy.onCheckinError(503, "integrity_not_configured", "", false, 0).delayMs);
        assertEquals(Policy.RETRY_LATER_MS, Policy.onCheckinError(400, "bad_request", "", false, 0).delayMs);
        assertEquals(Policy.RETRY_NETWORK_MS, Policy.onCheckinError(429, "rate_limited", "", false, 0).delayMs);
        assertEquals(Policy.RETRY_NETWORK_MS, Policy.onCheckinError(500, "", "", false, 0).delayMs);
        assertFalse(Policy.onCheckinError(500, "", "", false, 3).integrityFailure);
    }

    @Test
    public void statusIsOver() {
        assertFalse(Policy.isOver("active"));
        assertFalse(Policy.isOver("pending_access"));
        assertTrue(Policy.isOver("completed"));
        assertTrue(Policy.isOver("removed"));
        assertTrue(Policy.isOver(null));
    }
}
