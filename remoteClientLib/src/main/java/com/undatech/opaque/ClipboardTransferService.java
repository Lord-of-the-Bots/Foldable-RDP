package com.undatech.opaque;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Map;

/** The session bindings keep this service available. Only a file transfer promotes it,
 * using that file's own notification. All callbacks and notification updates run on the main thread. */
public class ClipboardTransferService extends Service {
    private static ClipboardTransferService bound;
    private final Map<Integer, Notification> active = new LinkedHashMap<>();
    private int foregroundId;
    private long nextPromotion;

    @Override public IBinder onBind(Intent intent) {
        bound = this;
        return new Binder();
    }

    static void show(Context context, int id, Notification notice) {
        if (bound != null && bound.getApplicationContext() == context.getApplicationContext()) {
            bound.active.put(id, notice);
            if (bound.foregroundId == 0 && bound.promote(id, notice)) return;
        }
        NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled()) manager.notify(id, notice);
    }

    static void remove(Context context, int id) {
        if (bound != null && bound.getApplicationContext() == context.getApplicationContext()) {
            bound.active.remove(id);
            if (bound.foregroundId == id && !bound.promoteNext()) {
                bound.stopForeground(true);
                bound.foregroundId = 0;
            }
        }
        ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(id);
    }

    private boolean promoteNext() {
        if (active.isEmpty()) return false;
        Map.Entry<Integer, Notification> next = active.entrySet().iterator().next();
        return promote(next.getKey(), next.getValue());
    }

    private boolean promote(int id, Notification notice) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextPromotion) return false;
        try {
            startForeground(id, notice);
            foregroundId = id;
            return true;
        } catch (IllegalStateException | SecurityException unavailable) {
            // A paste can originate in another app while Android restricts foreground service starts.
            nextPromotion = now + 30000;
            return false;
        }
    }

    private void detachProgress() {
        if (foregroundId == 0) return;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        foregroundId = 0;
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        detachProgress();
        nextPromotion = SystemClock.elapsedRealtime() + 30000;
        stopSelf();
    }

    @Override public void onDestroy() {
        if (bound == this) bound = null;
        detachProgress();
        active.clear();
        super.onDestroy();
    }
}
