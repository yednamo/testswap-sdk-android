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
 * TestSwap SDK: confirms that a TestSwap tester installed the app from Google Play
 * and checks in test days automatically.
 *
 * <p>Usage: call {@link #init(Context, String)} once in {@code Application.onCreate()}
 * with the app key from your TestSwap dashboard.
 *
 * <p>Privacy: only the app key and the participation token from the install tag are sent.
 * Users who did not install the app through a TestSwap link send nothing.
 */
public final class TestSwap {
    public static final String VERSION = "0.1.0";

    private static final String TAG = "TestSwap";
    private static final String DEFAULT_ENDPOINT = "https://testswap.yednamo.com";
    private static final String PREFS = "com.yednamo.testswap";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DONE = "done";
    private static final String KEY_REFERRER_CHECKED = "referrer_checked";
    private static final String KEY_LAST_PING = "last_ping_ms";
    /** The server counts one check-in per local day; pinging every few hours covers day boundaries. */
    private static final long PING_INTERVAL_MS = 3L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 10_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "testswap");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean initialized;
    private static volatile boolean referrerInProgress;
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
        if (context == null || key == null || key.trim().isEmpty()) {
            Log.w(TAG, "init: context or app key is missing, SDK disabled");
            return;
        }
        appContext = context.getApplicationContext();
        appKey = key.trim();
        endpoint = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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

    /** On every app start: link the install once, then check in at most every few hours. */
    private static void tick() {
        if (!initialized) return;
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_DONE, false)) return;
        String token = p.getString(KEY_TOKEN, null);
        if (token != null) {
            long last = p.getLong(KEY_LAST_PING, 0);
            if (System.currentTimeMillis() - last >= PING_INTERVAL_MS) {
                EXECUTOR.execute(() -> ping(token));
            }
        } else if (!p.getBoolean(KEY_REFERRER_CHECKED, false) && !referrerInProgress) {
            readReferrer();
        }
    }

    private static void readReferrer() {
        referrerInProgress = true;
        final InstallReferrerClient client = InstallReferrerClient.newBuilder(appContext).build();
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
                        final String r = referrer;
                        EXECUTOR.execute(() -> link(r));
                    } else {
                        safeEnd(client);
                        // Not installed from Google Play or the API is unsupported: nothing to do on this device.
                        if (code == InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED
                                || code == InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR) {
                            prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
                        }
                        referrerInProgress = false;
                    }
                }

                @Override
                public void onInstallReferrerServiceDisconnected() {
                    referrerInProgress = false;
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "install referrer client failed", e);
            referrerInProgress = false;
        }
    }

    private static void safeEnd(InstallReferrerClient client) {
        try {
            client.endConnection();
        } catch (RuntimeException ignored) {
            // already disconnected
        }
    }

    /** Runs on the SDK thread. */
    private static void link(String referrer) {
        try {
            if (referrer == null || !referrer.contains("ts_")) {
                // Regular install, not from a TestSwap link: the SDK stays silent from now on.
                prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
                return;
            }
            JSONObject body = new JSONObject();
            body.put("appKey", appKey);
            body.put("referrer", referrer);
            body.put("sdkVersion", VERSION);
            Response res = post("/sdk/v1/install", body);
            if (res.code == 200) {
                String token = res.json.optString("token", null);
                if (token != null && !token.isEmpty()) {
                    prefs().edit().putString(KEY_TOKEN, token).putBoolean(KEY_REFERRER_CHECKED, true).apply();
                    ping(token);
                }
            } else if (res.code == 404) {
                // Unknown tag or another app's key: retrying will not help.
                prefs().edit().putBoolean(KEY_REFERRER_CHECKED, true).apply();
                Log.w(TAG, "install is not linked to TestSwap: " + res.json.optString("error"));
            }
            // Other codes (network, 5xx, 429): try again on the next app start.
        } catch (IOException | JSONException e) {
            Log.w(TAG, "link failed, will retry on next start", e);
        } finally {
            referrerInProgress = false;
        }
    }

    /** Runs on the SDK thread. */
    private static void ping(String token) {
        try {
            JSONObject body = new JSONObject();
            body.put("appKey", appKey);
            body.put("token", token);
            Response res = post("/sdk/v1/ping", body);
            SharedPreferences.Editor e = prefs().edit();
            if (res.code == 200) {
                e.putLong(KEY_LAST_PING, System.currentTimeMillis());
                String status = res.json.optString("status", "active");
                // The test is over (completed, left, removed): stop sending anything.
                if (!"active".equals(status) && !"pending_access".equals(status)) e.putBoolean(KEY_DONE, true);
            } else if (res.code == 404) {
                e.remove(KEY_TOKEN).putBoolean(KEY_DONE, true);
            }
            e.apply();
        } catch (IOException | JSONException e) {
            Log.w(TAG, "check-in failed, will retry", e);
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
            JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
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
