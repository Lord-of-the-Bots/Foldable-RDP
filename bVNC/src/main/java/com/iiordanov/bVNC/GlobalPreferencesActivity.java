package com.iiordanov.bVNC;

import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.undatech.remoteClientUi.R;
import androidx.appcompat.app.AppCompatActivity;

public class GlobalPreferencesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int preferencesContainer = android.R.id.content;
        if (Utils.isFoldable(this)) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            setContentView(R.layout.fold_settings_activity);
            android.view.View root = findViewById(R.id.fold_settings_window);
            ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
                Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(root);
            setSupportActionBar(findViewById(R.id.fold_settings_toolbar));
            ActionBar bar = getSupportActionBar();
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setTitle(R.string.fold_settings);
            preferencesContainer = R.id.fold_preferences_container;
        } else {
            ActionBar bar = getSupportActionBar();
            if (bar != null) bar.hide();
        }
        if (savedInstanceState == null) getSupportFragmentManager().beginTransaction()
                .replace(preferencesContainer, new GlobalPreferencesFragment())
                .commit();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
