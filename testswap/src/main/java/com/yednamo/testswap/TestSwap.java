package com.yednamo.testswap;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TestSwap SDK: confirms through Google Play Integrity that a TestSwap tester installed the app
 * from Google Play on a certified device, and checks in test days automatically.
 *
 * <p>Usage: call {@link #init(Context, String)} once in {@code Application.onCreate()}
 * with the app key from your TestSwap dashboard. The dashboard also needs your app's
 * Play Integrity response keys (classic requests, self-managed encryption keys).
 *
 * <p>Flow, at most once per local day of the tester:
 * <ol>
 *   <li>TestSwap issues a one-time nonce ({@code /sdk/v2/challenge}).</li>
 *   <li>The SDK requests a classic Play Integrity token with that nonce.</li>
 *   <li>TestSwap decrypts and verifies the token and checks in the day ({@code /sdk/v2/checkin}).</li>
 * </ol>
 *
 * <p>Privacy: only the app key, the participation token from the install tag and the
 * Play Integrity token are sent. Users who did not install the app through a TestSwap link send nothing.
 */
public final class TestSwap {
    public static final String VERSION = "0.2.0";

    private static final String TAG = "TestSwap";
    private static final String DEFAULT_ENDPOINT = "https://testswap.yednamo.com";
    private static final String PREFS = "com.yednamo.testswap";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DONE = "done";
    private static final String KEY_REFERRER_CHECKED = "referrer_checked";
    /** Do nothing until this time (ms since epoch): next local day, or a back-off after an error. */
    private static final String KEY_NEXT_AT = "next_at_ms";

    private static final long MINUTE_MS = 60L * 1000;
    private static final long RETRY_NETWORK_MS = 30 * MINUTE_MS;
    private static final long RETRY_INTEGRITY_MS = 60 * MINUTE_MS;
    private static final long RETRY_LATER_MS = 6 * 60 * MINUTE_MS;
    private static final int TIMEOUT_MS = 15_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "testswap");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean initialized;
    /** One flow (referrer, challenge, Integrity, check-in) at a time. */
    private static volatile boolean inProgress;
    private static Context appContext;
    private static String appKey;
    private static String endpoint;

    private TestSwap() {
    }

    /** Initializes the SDK with the default TestSwap endpoint. Safe to call more than once. */
    public static void init(Context context, String key) {
        init(context, key, DEFAULT_ENDPOINT);
    }

    /** Initializes the SDK with a custom endpoint (for testing). */
    public static synchronized void init(Context context, String key, String baseUrl) {
        if (initialized) return;
        if (context == null || key == null || key.trim().isEmpty() || baseUrl == null || baseUrl.trim().isEmpty()) {
            Log.w(TAG, "init: context, app key or endpoint is missing, SDK disabled");
            return;
        }
        appContext = context.getApplicationContext();
        appKey = key.trim();
        String url = baseUrl.trim();
        endpoint = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        initialized = true;

        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityStarted(Activity activity) { tick(); }
                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityResumed(Activity activity) { }
                @Override public void onActivityPaused(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            });
        }
        tick();
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** On every app start: start the flow if it is due. */
    private static void tick() {
        if (!initialized || inProgress) return;
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_DONE, false)) return;
        if (System.currentTimeMillis() < p.getLong(KEY_NEXT_AT, 0)) return;
        final String token = p.getString(KEY_TOKEN, null);
        if (token != null) {
            inProgress = true;
            EXECUTOR.execute(() -> challenge(token, null));
        } else if (!p.getBoolean(KEY_REFERRER_CHECKED, false)) {
            inProgress = true;
            readReferrer();
        }
    }

    private static void finish() {
        inProgress = false;
    }

    private static void retryIn(long delayMs) {
        prefs().edit().putLong(KEY_NEXT_AT, System.currentTimeMillis() + delayMs).apply();
    }

    private static void markDone() {
        prefs().edit().putBoolean(KEY_DONE, true).apply();
    }

    private static boolean isOver(String status) {
        return !"active".equals(status) && !"pending_access".equals(status);
    }

    private static void readReferrer() {
        final InstallReferrerClient client;
        try {
            client = InstallReferrerClient.newBuilder(appContext).build();
        } catch (RuntimeException e) {
            Log.w(TAG, "install referrer client failed", e);
            finish();
            return;
        }
        try {
            client.startConnection(new InstallReferrerStateListener() {
                @Override
                public void onInstallReferrerSetupFinished(int code) {
                    if (code == InstallReferrerClient.InstallReferrerResponse.OK) {
                        String referrer = null;
                        try {
                            ReferrerDetails details = client.getInstallReferrer();
                            referrer = details.getInstallReferrer();
                        } catch (RemoteException e) {
                            Log.w(TAG, "install referrer unavailable", e);
                        } finally {
                            safeEnd(client);
                        }
                        if (referrer == null || !referrer.contains("ts_")) {
                            // Regular install, not from a TestSwap link: the SDK stays silent from now on.
                            prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
                            finish();
                            return;
                        }
                        final String r = referrer;
                        EXECUTOR.execute(() -> challenge(null, r));
                    } else {
                        safeEnd(client);
                        // Not installed from Google Play or the API is unsupported: nothing to do on this device.
                        if (code == InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED
                                || code == InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR) {
                            prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
                        }
                        finish();
                    }
                }

                @Override
                public void onInstallReferrerServiceDisconnected() {
                    finish();
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "install referrer connection failed", e);
            finish();
        }
    }

    private static void safeEnd(InstallReferrerClient client) {
        try {
            client.endConnection();
        } catch (RuntimeException ignored) {
            // already disconnected
        }
    }

    /** Step 1, on the SDK thread: get a one-time nonce. Exactly one of token / referrer is set. */
    private static void challenge(String token, String referrer) {
        boolean handedOver = false;
        try {
            JSONObject body = new JSONObject();
            body.put("appKey", appKey);
            if (token != null) body.put("token", token);
            else body.put("referrer", referrer);
            body.put("sdkVersion", VERSION);
            Response res = post("/sdk/v2/challenge", body);
            if (res.code == 200) {
                String t = res.json.optString("token", "");
                if (t.isEmpty()) {
                    retryIn(RETRY_NETWORK_MS);
                    return;
                }
                prefs().edit().putString(KEY_TOKEN, t).putBoolean(KEY_REFERRER_CHECKED, true).apply();
                String status = res.json.optString("status", "active");
                String nonce = res.json.isNull("nonce") ? null : res.json.optString("nonce", null);
                if (isOver(status)) {
                    markDone();
                } else if (nonce == null) {
                    // Waiting for access (email mode): try again later.
                    retryIn(RETRY_LATER_MS);
                } else {
                    handedOver = true;
                    requestIntegrity(t, nonce);
                }
            } else if (res.code == 404) {
                // Unknown tag or another app's key: retrying will not help.
                if (token == null) prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
                else markDone();
                Log.w(TAG, "install is not linked to TestSwap: " + res.json.optString("error"));
            } else if (res.code == 409 || res.code == 410) {
                markDone();
                Log.w(TAG, "TestSwap SDK disabled for this app: " + res.json.optString("error"));
            } else if (res.code == 503) {
                // The developer has not added Play Integrity keys yet.
                retryIn(RETRY_LATER_MS);
                Log.w(TAG, "TestSwap verification is not configured: " + res.json.optString("error"));
            } else {
                retryIn(RETRY_NETWORK_MS);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "challenge failed, will retry", e);
            retryIn(RETRY_NETWORK_MS);
        } finally {
            if (!handedOver) finish();
        }
    }

    /** Step 2: classic Play Integrity request with the server nonce. Callbacks run on the SDK thread. */
    private static void requestIntegrity(final String token, String nonce) {
        try {
            IntegrityManager manager = IntegrityManagerFactory.create(appContext);
            manager.requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce).build())
                    .addOnSuccessListener(EXECUTOR, response -> checkin(token, response.token()))
                    .addOnFailureListener(EXECUTOR, e -> {
                        // No Play Store / Play services, outdated, too many requests, network: try again later.
                        Log.w(TAG, "Play Integrity request failed, will retry", e);
                        retryIn(RETRY_INTEGRITY_MS);
                        finish();
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "Play Integrity is unavailable", e);
            retryIn(RETRY_INTEGRITY_MS);
            finish();
        }
    }

    /** Step 3, on the SDK thread: send the token, TestSwap verifies it and checks in today. */
    private static void checkin(String token, String integrityToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("appKey", appKey);
            body.put("token", token);
            body.put("integrityToken", integrityToken);
            body.put("sdkVersion", VERSION);
            Response res = post("/sdk/v2/checkin", body);
            if (res.code == 200) {
                if (isOver(res.json.optString("status", "active"))) {
                    markDone();
                } else {
                    long sec = res.json.optLong("nextCheckinInSec", 0);
                    retryIn(sec > 0 ? sec * 1000 : RETRY_LATER_MS);
                }
            } else if (res.code == 403) {
                // Google Play did not confirm the install or the device: no point retrying right away.
                Log.w(TAG, "TestSwap rejected the Play Integrity verdict: " + res.json.optString("reason"));
                retryIn(RETRY_LATER_MS);
            } else if (res.code == 404 || res.code == 409) {
                markDone();
            } else {
                retryIn(RETRY_NETWORK_MS);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "check-in failed, will retry", e);
            retryIn(RETRY_NETWORK_MS);
        } finally {
            finish();
        }
    }

    private static final class Response {
        final int code;
        final JSONObject json;

        Response(int code, JSONObject json) {
            this.code = code;
            this.json = json;
        }
    }

    private static Response post(String path, JSONObject body) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "TestSwap-Android/" + VERSION);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = is == null ? "" : readAll(is);
            JSONObject json;
            try {
                json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                // Proxy or gateway error page instead of JSON.
                json = new JSONObject();
            }
            return new Response(code, json);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
