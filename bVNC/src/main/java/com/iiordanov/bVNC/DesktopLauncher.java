package com.iiordanov.bVNC;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.undatech.opaque.ConnectionGridActivity;

/** Return from the home screen to the workspace retained by Android. */
public final class DesktopLauncher extends Activity {
    private boolean routed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!DesktopWorkspace.hasCard(this)) route();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android permits a visible caller to bring a retained task to the foreground.
        if (hasFocus) route();
    }

    private void route() {
        if (routed) return;
        routed = true;
        if (DesktopWorkspace.openFromLauncher(this)) {
            // This noHistory window goes away when the desktop appears. If the system silently
            // declines moveToFront, retry from the still-visible launcher using an explicit Intent.
            getWindow().getDecorView().postDelayed(() -> {
                if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
                if (!DesktopWorkspace.retryCard(this)) openComputers();
                finish();
            }, 2500);
            return;
        }
        openComputers();
        finish();
    }

    private void openComputers() {
        startActivity(new Intent(this, ConnectionGridActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
}
