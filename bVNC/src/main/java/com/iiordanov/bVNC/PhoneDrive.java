package com.iiordanov.bVNC;

import android.content.Context;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import com.undatech.opaque.Connection;
import java.util.Arrays;
import org.json.JSONArray;

/** The full-storage grant belongs to a saved computer and its connection route. */
public final class PhoneDrive {
    private static final String PROFILES = "phone_drives";
    private static final String PERMISSION_OFFERED = "permission_offered";
    private PhoneDrive() {}

    static boolean permitted() {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            return Environment.isExternalStorageManager();
        } catch (IllegalStateException | IndexOutOfBoundsException unavailable) {
            // The framework may have no primary storage volume during a user or volume transition.
            return false;
        }
    }

    private static String route(Connection c) {
        return new JSONArray(Arrays.asList(c.getAddress(), c.getPort(), c.getUserName(), c.getRdpDomain(),
                c.getConnectionType(), c.getSshServer(), c.getSshPort(), c.getSshUser(),
                c.getRdpGatewayEnabled(), c.getRdpGatewayHostname(), c.getRdpGatewayPort())).toString();
    }

    // fold.12 stored the route when sharing was on. An empty string records an explicit opt-out.
    static boolean selectedInEditor(Context context, Connection c) {
        String saved = context.getSharedPreferences(PROFILES, 0).getString(c.getId(), null);
        return saved == null || !saved.isEmpty();
    }

    /** Bind defaults only from a saved profile selected inside the app or loaded by its shortcut. */
    public static void prepareSaved(Context context, Connection c) {
        if ("0".equals(c.getId()) || !selectedInEditor(context, c)) return;
        // Use the same host and ports as the transport, including saved host:port addresses.
        c.load(context);
        if (!requested(context, c)) save(context, c, true);
    }

    private static boolean requested(Context context, Connection c) {
        if (!TextUtils.isEmpty(c.getConnectionConfigFile()) || "0".equals(c.getId())) return false;
        String saved = context.getSharedPreferences(PROFILES, 0).getString(c.getId(), null);
        return saved != null && saved.equals(route(c));
    }

    public static boolean enabled(Context context, Connection c) {
        return requested(context, c) && permitted();
    }

    static boolean offerPermission(Activity activity, Connection c, int requestCode) {
        return Build.VERSION.SDK_INT >= 30 && requested(activity, c) && !permitted()
                && !activity.getSharedPreferences(PROFILES, 0).getBoolean(PERMISSION_OFFERED, false)
                && openPermissionSettings(activity, requestCode);
    }

    static boolean openPermissionSettings(Activity activity, int requestCode) {
        Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", activity.getPackageName(), null));
        try {
            activity.startActivityForResult(settings, requestCode);
        } catch (ActivityNotFoundException missingAppSettings) {
            try {
                activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), requestCode);
            } catch (ActivityNotFoundException missingStorageSettings) {
                return false;
            }
        }
        activity.getSharedPreferences(PROFILES, 0).edit().putBoolean(PERMISSION_OFFERED, true).apply();
        return true;
    }

    static void save(Context context, Connection c, boolean enabled) {
        if ("0".equals(c.getId())) return;
        context.getSharedPreferences(PROFILES, 0).edit().putString(c.getId(), enabled ? route(c) : "").apply();
    }
}
