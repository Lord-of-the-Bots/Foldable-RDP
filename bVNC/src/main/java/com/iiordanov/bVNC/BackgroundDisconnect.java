package com.iiordanov.bVNC;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.TimeUnit;

/** One deadline for the whole background workspace, measured from when its last window stopped. */
final class BackgroundDisconnect implements Application.ActivityLifecycleCallbacks {
    private static final String WIFI = "foldIdleWifi";
    private static final String MOBILE = "foldIdleMobile";
    private static final String START = "foldIdleSince";
    private static final String NETWORK = "foldIdleNetwork";
    private static final String GENERATION = "foldIdleGeneration";
    private static BackgroundDisconnect instance;

    private final Application app;
    private final SharedPreferences prefs;
    private final ConnectivityManager connectivity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::evaluate;
    // A non-wakeup elapsed alarm accounts for sleep if the phone wakes with its screen still off.
    // The Handler provides the normal (unbatched) deadline while the process is running.
    private static final class SleepDeadline {
        final AlarmManager alarms;
        final AlarmManager.OnAlarmListener alarm;
        long scheduledAt = -1;
        SleepDeadline(Application app, Runnable tick) {
            alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            alarm = () -> { scheduledAt = -1; tick.run(); };
        }
        void schedule(long deadline, Handler handler) {
            if (scheduledAt == deadline) return;
            alarms.set(AlarmManager.ELAPSED_REALTIME, deadline, "RDP background timeout", alarm, handler);
            scheduledAt = deadline;
        }
        void cancel() {
            if (scheduledAt >= 0) alarms.cancel(alarm);
            scheduledAt = -1;
        }
    }
    private final SleepDeadline sleepDeadline;
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { evaluate(); }
    };
    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network active, NetworkCapabilities caps) { handler.post(tick); }
        @Override public void onLost(Network lost) { handler.post(tick); }
    };
    private int visible;
    private boolean listening;
    private long since;
    private String network;

    private BackgroundDisconnect(Application app) {
        this.app = app;
        connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        sleepDeadline = Build.VERSION.SDK_INT >= 24 ? new SleepDeadline(app, tick) : null;
        prefs = app.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
        since = prefs.getLong(START, -1);
        network = prefs.getString(NETWORK, "");
    }

    static void install(Application app) {
        instance = new BackgroundDisconnect(app);
        app.registerActivityLifecycleCallbacks(instance);
        if (instance.since >= 0) instance.monitor();
    }

    static long generation() {
        return instance == null ? 0 : instance.prefs.getLong(GENERATION, 0);
    }

    static void checkDeadline() {
        if (instance != null && instance.since >= 0) instance.evaluate();
    }

    private long limit(String key) {
        if (!WIFI.equals(key) && !MOBILE.equals(key)) return 0;
        return TimeUnit.MINUTES.toMillis(Long.parseLong(prefs.getString(key, "0")));
    }

    @SuppressWarnings("deprecation")
    private String currentNetwork() {
        ConnectivityManager cm = connectivity;
        if (Build.VERSION.SDK_INT >= 23) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) return network;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WIFI;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return MOBILE;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "";
        }
        NetworkInfo active = cm.getActiveNetworkInfo();
        if (active == null) return network;
        if (active.getType() == ConnectivityManager.TYPE_VPN) {
            NetworkInfo wifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (wifi != null && wifi.isConnected()) return WIFI;
            NetworkInfo cell = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            return cell != null && cell.isConnected() ? MOBILE : network;
        }
        if (active.getType() == ConnectivityManager.TYPE_WIFI) return WIFI;
        return active.getType() == ConnectivityManager.TYPE_MOBILE ? MOBILE : "";
    }

    private void monitor() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(networkChanges);
        else filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        app.registerReceiver(changes, filter);
        listening = true;
        evaluate();
    }

    private void evaluate() {
        if (since < 0) return;
        handler.removeCallbacks(tick);
        long elapsed = SystemClock.elapsedRealtime() - since;
        // A killed process has no connection to preserve. After a reboot discard its old workspace.
        long previousLimit = limit(network);
        if (elapsed < 0 || (previousLimit > 0 && elapsed >= previousLimit)) {
            expire();
            return;
        }
        String current = currentNetwork();
        if (!current.equals(network)) {
            network = current;
            prefs.edit().putString(NETWORK, network).apply();
        }
        long timeout = limit(network);
        if (timeout == 0) {
            if (sleepDeadline != null) sleepDeadline.cancel();
            return;
        }
        if (elapsed >= timeout) expire();
        else {
            handler.postDelayed(tick, timeout - elapsed);
            if (sleepDeadline != null) sleepDeadline.schedule(since + timeout, handler);
        }
    }

    private void expire() {
        reset(prefs.edit().putLong(GENERATION, generation() + 1));
        DesktopWorkspace.disconnectInBackground(app);
    }

    private void reset(SharedPreferences.Editor editor) {
        handler.removeCallbacks(tick);
        if (sleepDeadline != null) sleepDeadline.cancel();
        if (listening) {
            app.unregisterReceiver(changes);
            if (Build.VERSION.SDK_INT >= 24) connectivity.unregisterNetworkCallback(networkChanges);
        }
        listening = false;
        since = -1;
        network = "";
        editor.remove(START).remove(NETWORK).apply();
    }

    @Override public void onActivityStarted(Activity activity) {
        if (++visible == 1 && since >= 0) {
            evaluate();
            if (since >= 0) reset(prefs.edit());
        }
    }

    @Override public void onActivityStopped(Activity activity) {
        if (--visible != 0 || activity.isChangingConfigurations() || !DesktopWorkspace.hasDesktops()) return;
        if (limit(WIFI) == 0 && limit(MOBILE) == 0) return;
        since = SystemClock.elapsedRealtime();
        network = currentNetwork();
        prefs.edit().putLong(START, since).putString(NETWORK, network).apply();
        monitor();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
