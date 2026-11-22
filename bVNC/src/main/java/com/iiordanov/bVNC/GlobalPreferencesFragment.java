package com.iiordanov.bVNC;

import android.os.Bundle;
import android.content.Context;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.undatech.remoteClientUi.R;

public class GlobalPreferencesFragment extends PreferenceFragmentCompat {
    private final ActivityResultLauncher<String> saveProfile = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null) return;
                Context context = requireContext().getApplicationContext();
                new Thread(() -> {
                    try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException("No output stream");
                        output.write(DesktopControlProfile.export(context).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException | org.json.JSONException | SecurityException error) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, R.string.fold_profile_save_failed, Toast.LENGTH_LONG).show());
                    }
                }, "Save control profile").start();
            });
    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        getPreferenceManager().setSharedPreferencesName(Constants.generalSettingsTag);
        if (Utils.isFoldable(requireContext())) {
            showFoldPreferences();
            return;
        }
        setPreferencesFromResource(R.xml.global_preferences, s);
        if (Utils.isVnc(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_vnc);
        } else if (Utils.isRdp(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_rdp);
        } else if (Utils.isSpice(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_spice);
        }
    }

    private void showFoldPreferences() {
        setPreferencesFromResource(R.xml.fold_preferences, null);
        androidx.preference.SwitchPreferenceCompat clipboard = findPreference("foldClipboard");
        android.content.SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        clipboard.setChecked(prefs.getBoolean("localToRemoteClipboardIntegration", true)
                && prefs.getBoolean("remoteToLocalClipboardIntegration", true));
        clipboard.setOnPreferenceChangeListener((preference, value) -> {
            prefs.edit().putBoolean("localToRemoteClipboardIntegration", (Boolean)value)
                    .putBoolean("remoteToLocalClipboardIntegration", (Boolean)value).apply();
            return true;
        });
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("foldFileProgress".equals(preference.getKey())) {
            Context context = requireContext();
            try {
                startActivity(com.undatech.opaque.TransferNotificationChannel.settings(context));
            } catch (android.content.ActivityNotFoundException unavailable) {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.getPackageName(), null)));
            }
            return true;
        }
        if ("foldExportControlProfile".equals(preference.getKey())) {
            saveProfile.launch("Foldable-RDP-profile.json");
            return true;
        }
        if ("openDefaultConnectionSettings".equals(preference.getKey())) {
            Utils.openDefaultConnectionSettings(requireContext());
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }
}
