package com.iiordanov.bVNC;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import com.undatech.opaque.ClipboardTransferService;

public final class RdpSessionService extends ClipboardTransferService {
    static void removeLegacySessionNotice(Context context) {
        NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(1);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("rdp_session") != null) {
            try { manager.deleteNotificationChannel("rdp_session"); }
            catch (SecurityException inUse) { /* Android is still retiring the service from the previous version. */ }
        }
    }

    /** Each open connection retains a binding, including while its window is hidden. */
    public static final class Binding implements ServiceConnection {
        private boolean bound;

        public synchronized void acquire(Context context) {
            if (!bound) {
                bound = context.bindService(new Intent(context, RdpSessionService.class), this, Context.BIND_AUTO_CREATE);
            }
        }

        public synchronized void release(Context context) {
            if (bound) {
                bound = false;
                context.unbindService(this);
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {}
        @Override public void onServiceDisconnected(ComponentName name) {}
    }
}
