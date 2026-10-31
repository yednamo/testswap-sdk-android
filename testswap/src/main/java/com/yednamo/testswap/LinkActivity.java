package com.yednamo.testswap;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Receives the participation token from a TestSwap link ({@code testswap://<package>/link?token=…})
 * opened from the tester's test page, then opens the app's launcher activity.
 *
 * <p>Second way to link an install, after the Install Referrer: it works however the app was installed
 * (Google Play search, the "Become a tester" page, a web install), because the tester taps the link on
 * the device where the app is. Play Integrity still verifies the install itself. Declared by the library
 * manifest; the app does nothing besides calling {@link TestSwap#init(android.content.Context, String)}.
 */
public final class LinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        String token = null;
        if (data != null) {
            try {
                token = Policy.tokenFromLink(data.getQueryParameter("token"));
            } catch (RuntimeException e) {
                // Opaque or malformed URI: nothing to link.
            }
        }
        if (token != null) TestSwap.linkInstall(token);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
        }
        finish();
    }
}
