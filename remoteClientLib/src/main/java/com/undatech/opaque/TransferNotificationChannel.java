package com.undatech.opaque;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.undatech.remoteClientLib.R;

public final class TransferNotificationChannel {
    private static final String OLD_ID = "rdp_phone_file_transfers", ID = "rdp_file_progress";
    private TransferNotificationChannel() {}

    static String id(Context context) { return id(context, null); }

    static synchronized String id(Context context, String cached) {
        if (Build.VERSION.SDK_INT < 26 || ID.equals(cached)) return ID;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel old = manager.getNotificationChannel(OLD_ID);
        NotificationChannel channel = manager.getNotificationChannel(ID);
        // Android fixes badging at channel creation. Never replace a channel the user
        // has muted. Recheck a legacy ID if its notification settings subsequently change.
        if (channel == null && old != null && (old.getImportance() == NotificationManager.IMPORTANCE_NONE || !old.canShowBadge())) {
            channel = old;
        }
        String name = context.getString(R.string.clipboard_transfers);
        String detail = context.getString(R.string.clipboard_transfers_note);
        if (channel == null) {
            channel = new NotificationChannel(ID, name, old == null ? NotificationManager.IMPORTANCE_LOW : old.getImportance());
            channel.setShowBadge(false);
            channel.setSound(null, null);
            if (old != null) {
                channel.setSound(old.getSound(), old.getAudioAttributes());
                channel.setVibrationPattern(old.getVibrationPattern());
                if (Build.VERSION.SDK_INT >= 35 && old.getVibrationEffect() != null)
                    channel.setVibrationEffect(old.getVibrationEffect());
                channel.enableVibration(old.shouldVibrate());
                channel.setLightColor(old.getLightColor());
                channel.enableLights(old.shouldShowLights());
                channel.setLockscreenVisibility(old.getLockscreenVisibility());
                channel.setGroup(old.getGroup());
                if (old.canBypassDnd() && manager.isNotificationPolicyAccessGranted()) channel.setBypassDnd(true);
            }
            channel.setDescription(detail);
            manager.createNotificationChannel(channel);
        } else if (!name.contentEquals(channel.getName()) || !detail.equals(channel.getDescription())) {
            channel.setName(name);
            channel.setDescription(detail);
            manager.createNotificationChannel(channel);
        }
        if (old != null && ID.equals(channel.getId())) {
            try { manager.deleteNotificationChannel(OLD_ID); }
            catch (SecurityException inUse) { /* Android still has a foreground notification on the old channel. */ }
        }
        return channel.getId();
    }

    public static Intent settings(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager.areNotificationsEnabled())
                return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, id(context));
            return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.getPackageName(), null));
    }
}
