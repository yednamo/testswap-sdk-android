package com.yednamo.testswap;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
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
import java.io.FileInputStream;
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
 * <p>Flow, at most once per local day of the tester, started only when an Activity starts
 * and only in the app's main process (a push or WorkManager wake-up is not the tester using the app):
 * <ol>
 *   <li>TestSwap issues a one-time nonce ({@code /sdk/v2/challenge}).</li>
 *   <li>The SDK requests a classic Play Integrity token with that nonce.</li>
 *   <li>TestSwap decrypts and verifies the token and checks in the day ({@code /sdk/v2/checkin}).</li>
 * </ol>
 *
 * <p>The participation token comes either from the Install Referrer of the personal install link or from a
 * TestSwap link the tester opens on the device ({@link LinkActivity}), for installs made any other way.
 *
 * <p>Privacy: only the app key, the participation token and the Play Integrity token are sent. Users who
 * neither installed the app through a TestSwap link nor opened one send nothing.
 * The decision rules (what to do after each reply) live in {@link Policy}.
 */
public final class TestSwap {
    public static final String VERSION = "0.3.0";

    private static final String TAG = "TestSwap";
    private static final String DEFAULT_ENDPOINT = "https://testswap.yednamo.com";
    private static final String PREFS = "com.yednamo.testswap";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DONE = "done";
    private static final String KEY_REFERRER_CHECKED = "referrer_checked";
    /** Do nothing until this time (ms since epoch): next local day, or a back-off after an error. */
    private static final String KEY_NEXT_AT = "next_at_ms";
    /** Play Integrity failures in a row (request failed or verdict rejected): drives the back-off. */
    private static final String KEY_FAILURES = "integrity_failures";
    /** The one immediate re-run after a nonce error has been used in the current attempt. */
    private static final String KEY_RETRIED_NOW = "retried_now";
    /**
     * {@code PackageInfo.firstInstallTime} the stored state belongs to. Android Auto Backup restores
     * SharedPreferences on reinstall, so without this a "not a TestSwap install" verdict from an earlier
     * install (an APK, a Play search install) would silence the SDK forever on that device.
     */
    private static final String KEY_INSTALL_TIME = "install_time_ms";

    private static final int TIMEOUT_MS = 15_000;
    /** A real reply is a few hundred bytes; anything longer is not ours. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "testswap");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean initialized;
    private static volatile boolean debugEnabled;
    /**
     * One flow (referrer, challenge, Integrity, check-in) at a time: {@code SystemClock.elapsedRealtime()}
     * when it started, 0 when idle. A flow older than {@link Policy#FLOW_TIMEOUT_MS} is considered dead.
     */
    private static volatile long flowStartedAt;
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
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        if (!isMainProcess(app)) {
            // Helper processes (push, WorkManager, widgets, WebView) must not check in days.
            return;
        }
        appContext = app;
        appKey = key.trim();
        String url = baseUrl.trim();
        endpoint = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        resetStateIfReinstalled();
        initialized = true;

        // The flow starts only from an Activity: a background process start is not the tester using the app.
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
    }

    /**
     * The tester opened a TestSwap link on this device ({@link LinkActivity}): the install is linked to that
     * participation whatever the Install Referrer said. Any earlier terminal state (silent, done, back-off)
     * is dropped; the flow starts on the next Activity start.
     */
    static synchronized void linkInstall(String token) {
        if (!initialized) {
            Log.w(TAG, "link ignored: TestSwap.init() was not called in Application.onCreate()");
            return;
        }
        prefs().edit()
                .putString(KEY_TOKEN, token)
                .putBoolean(KEY_REFERRER_CHECKED, true)
                .remove(KEY_DONE)
                .remove(KEY_NEXT_AT)
                .remove(KEY_FAILURES)
                .remove(KEY_RETRIED_NOW)
                .apply();
        flowStartedAt = 0;
        Log.i(TAG, "install linked through a TestSwap link");
    }

    /** Logs every retry decision under the "TestSwap" tag. Off by default: only terminal states are logged. */
    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    private static boolean isMainProcess(Context context) {
        String name = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? Application.getProcessName() : readProcessName();
        // Unknown name: assume the main process rather than silently disabling the SDK.
        return name == null || name.isEmpty() || name.equals(context.getPackageName());
    }

    /** Process name from /proc/self/cmdline (NUL-terminated), for API 26-27. */
    private static String readProcessName() {
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return null;
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            return new String(buf, 0, end, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * A fresh install (uninstall + install again) gets a new {@code firstInstallTime} and a new Install
     * Referrer; state restored by Android Auto Backup from the previous install must not survive it.
     * Updates keep {@code firstInstallTime}, so the stored token and schedule stay.
     */
    private static void resetStateIfReinstalled() {
        long installed;
        try {
            installed = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return;
        }
        SharedPreferences p = prefs();
        long known = p.getLong(KEY_INSTALL_TIME, 0);
        if (known == installed) return;
        SharedPreferences.Editor e = p.edit();
        if (known != 0) {
            e.clear();
            debug("app reinstalled since the stored state, starting over", null);
        }
        e.putLong(KEY_INSTALL_TIME, installed).apply();
    }

    /** On every Activity start (main thread): start the flow if it is due. */
    private static void tick() {
        if (!initialized) return;
        long uptime = SystemClock.elapsedRealtime();
        if (!Policy.canStart(flowStartedAt, uptime)) return;
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_DONE, false)) return;
        if (!Policy.isDue(p.getLong(KEY_NEXT_AT, 0), System.currentTimeMillis())) return;
        final String token = p.getString(KEY_TOKEN, null);
        if (token != null) {
            flowStartedAt = Math.max(1, uptime);
            EXECUTOR.execute(() -> challenge(token, false));
        } else if (!p.getBoolean(KEY_REFERRER_CHECKED, false)) {
            flowStartedAt = Math.max(1, uptime);
            readReferrer();
        }
    }

    private static void finish() {
        flowStartedAt = 0;
    }

    private static void retryIn(long delayMs) {
        prefs().edit()
                .putLong(KEY_NEXT_AT, System.currentTimeMillis() + Policy.capDelay(delayMs))
                .putBoolean(KEY_RETRIED_NOW, false)
                .apply();
    }

    private static void markDone(String why) {
        prefs().edit().putBoolean(KEY_DONE, true).apply();
        Log.w(TAG, "TestSwap SDK stops on this device: " + why);
    }

    private static void referrerChecked(String why) {
        prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
        debug("not a TestSwap install, SDK stays silent: " + why, null);
    }

    private static void debug(String msg, Throwable t) {
        if (!debugEnabled) return;
        if (t == null) Log.w(TAG, msg);
        else Log.w(TAG, msg, t);
    }

    /**
     * Applies a decision on the SDK thread. Returns true when the flow goes on (immediate re-run)
     * and the caller must not finish it.
     */
    private static boolean apply(Policy.Decision d, String token, String what) {
        switch (d.kind) {
            case DONE:
                markDone(what);
                return false;
            case SILENT:
                referrerChecked(what);
                return false;
            case RETRY_NOW:
                debug(what + ", retrying once with a fresh nonce", null);
                prefs().edit().putBoolean(KEY_RETRIED_NOW, true).apply();
                flowStartedAt = Math.max(1, SystemClock.elapsedRealtime());
                challenge(token, false);
                return true;
            default:
                if (d.integrityFailure) {
                    SharedPreferences p = prefs();
                    p.edit().putInt(KEY_FAILURES, p.getInt(KEY_FAILURES, 0) + 1).apply();
                }
                retryIn(d.delayMs);
                debug(what + ", retry in " + d.delayMs / Policy.MINUTE_MS + " min", null);
                return false;
        }
    }

    private static void readReferrer() {
        final InstallReferrerClient client;
        try {
            client = InstallReferrerClient.newBuilder(appContext).build();
        } catch (RuntimeException e) {
            debug("install referrer client failed", e);
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
                            debug("install referrer unavailable", e);
                        } finally {
                            safeEnd(client);
                        }
                        // Only the token leaves the device, never the raw referrer.
                        final String token = Policy.tokenFromReferrer(referrer);
                        if (token == null) {
                            // Regular install, not from a TestSwap link: the SDK stays silent from now on.
                            referrerChecked("no ts_ tag in the install referrer");
                            finish();
                            return;
                        }
                        EXECUTOR.execute(() -> challenge(token, true));
                    } else {
                        safeEnd(client);
                        // Not installed from Google Play or the API is unsupported: nothing to do on this device.
                        if (code == InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED
                                || code == InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR) {
                            referrerChecked("install referrer response " + code);
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
            debug("install referrer connection failed", e);
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

    /**
     * Step 1, on the SDK thread: get a one-time nonce.
     * fromReferrer: the token was just extracted from the Install Referrer and is not stored yet.
     */
    private static void challenge(String token, boolean fromReferrer) {
        boolean handedOver = false;
        try {
            JSONObject body = new JSONObject();
            body.put("appKey", appKey);
            body.put("token", token);
            body.put("sdkVersion", VERSION);
            Response res = post("/sdk/v2/challenge", body);
            if (res.code != 200) {
                String error = res.json.optString("error");
                apply(Policy.onChallengeError(res.code, error, fromReferrer), token, "challenge " + res.code + " " + error);
                return;
            }
            String t = res.json.optString("token", "");
            if (t.isEmpty()) {
                retryIn(Policy.RETRY_NETWORK_MS);
                debug("challenge reply without token, will retry", null);
                return;
            }
            prefs().edit().putString(KEY_TOKEN, t).putBoolean(KEY_REFERRER_CHECKED, true).apply();
            String status = res.json.optString("status", "active");
            String nonce = res.json.isNull("nonce") ? null : res.json.optString("nonce", null);
            if (Policy.isOver(status)) {
                markDone("test is over: " + status);
            } else if (nonce == null) {
                // Waiting for access (email mode): try again later.
                retryIn(Policy.RETRY_LATER_MS);
                debug("waiting for access: " + status, null);
            } else {
                handedOver = true;
                requestIntegrity(t, nonce);
            }
        } catch (IOException | JSONException e) {
            debug("challenge failed, will retry", e);
            retryIn(Policy.RETRY_NETWORK_MS);
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
                    .addOnFailureListener(EXECUTOR, e -> integrityFailed("Play Integrity request failed", e));
        } catch (RuntimeException e) {
            integrityFailed("Play Integrity is unavailable", e);
        }
    }

    /** No Play Store / Play services, outdated, too many requests, network: back off and try again later. */
    private static void integrityFailed(String what, Exception e) {
        debug(what, e);
        apply(Policy.onIntegrityFailure(prefs().getInt(KEY_FAILURES, 0)), null, what);
        finish();
    }

    /**
     * "Samsung SM-A525F, Android 14": shown to the developer next to the tester (which devices tested the app).
     * Model and OS version only, no identifiers.
     */
    static String deviceLabel() {
        String make = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (!make.isEmpty()) make = Character.toUpperCase(make.charAt(0)) + make.substring(1);
        String name = model.toLowerCase().startsWith(make.toLowerCase()) ? model : (make + " " + model).trim();
        String label = name + ", Android " + Build.VERSION.RELEASE;
        return label.length() > 120 ? label.substring(0, 120) : label;
    }

    /** "0.6.1 (13)": the app's versionName and versionCode, so the developer sees who runs an outdated build. */
    static String appVersionLabel() {
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            String name = info.versionName == null ? "" : info.versionName.trim();
            String label = (name.isEmpty() ? "" : name + " ") + "(" + code + ")";
            return label.length() > 40 ? label.substring(0, 40) : label;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Step 3, on the SDK thread: send the token, TestSwap verifies it and checks in today. */
    private static void checkin(String token, String integrityToken) {
        boolean handedOver = false;
        try {
            JSONObject body = new JSONObject();
            body.put("appKey", appKey);
            body.put("token", token);
            body.put("integrityToken", integrityToken);
            body.put("sdkVersion", VERSION);
            body.put("device", deviceLabel());
            String appVersion = appVersionLabel();
            if (appVersion != null) body.put("appVersion", appVersion);
            Response res = post("/sdk/v2/checkin", body);
            if (res.code == 200) {
                String status = res.json.optString("status", "active");
                if (Policy.isOver(status)) {
                    markDone("test is over: " + status);
                } else {
                    prefs().edit().putInt(KEY_FAILURES, 0).apply();
                    retryIn(Policy.nextCheckinDelay(res.json.optLong("nextCheckinInSec", 0)));
                    debug("checked in: " + res.json.optBoolean("checkedIn", false), null);
                }
            } else {
                SharedPreferences p = prefs();
                String error = res.json.optString("error");
                String reason = res.json.optString("reason");
                Policy.Decision d = Policy.onCheckinError(res.code, error, reason,
                        p.getBoolean(KEY_RETRIED_NOW, false), p.getInt(KEY_FAILURES, 0));
                handedOver = apply(d, token, "check-in " + res.code + " " + error + " " + reason);
            }
        } catch (IOException | JSONException e) {
            debug("check-in failed, will retry", e);
            retryIn(Policy.RETRY_NETWORK_MS);
        } finally {
            if (!handedOver) finish();
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

    /** Reads at most {@link #MAX_BODY_BYTES}; a longer body is cut off (and then fails to parse as JSON). */
    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (out.size() < MAX_BODY_BYTES && (n = in.read(buf)) != -1) {
                out.write(buf, 0, Math.min(n, MAX_BODY_BYTES - out.size()));
            }
            return out.toString("UTF-8");
        }
    }
}
