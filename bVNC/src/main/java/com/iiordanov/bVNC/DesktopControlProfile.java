package com.iiordanov.bVNC;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import com.iiordanov.bVNC.input.RemoteRdpPointer;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Set;
import java.util.TreeSet;

public final class DesktopControlProfile {
    private DesktopControlProfile() {}

    static final String RIGHT_CLICK_HAPTIC = "touchpadRightClickHaptic";

    public static boolean rightClickHaptic(Context context) {
        return context.getSharedPreferences("desktop_keyboard", Context.MODE_PRIVATE)
                .getBoolean(RIGHT_CLICK_HAPTIC, true);
    }

    public static String supportedInputMode(Context context, String saved) {
        if (!Utils.isFoldable(context)) return saved;
        if (saved == null || saved.isEmpty() || com.iiordanov.bVNC.input.TouchInputHandlerTouchpad.ID.equals(saved))
            return com.iiordanov.bVNC.input.TouchInputHandlerTouchpad.ID;
        return com.iiordanov.bVNC.input.TouchInputHandlerDirectSwipePan.ID;
    }

    static String displayKey(Context context, View view, String fallback) {
        android.view.Display display = view == null ? null : view.getDisplay();
        if (display == null) {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (manager != null) display = manager.getDefaultDisplay();
        }
        if (display != null && Build.VERSION.SDK_INT >= 23) {
            android.view.Display.Mode mode = display.getMode();
            return RemoteCanvasActivity.desktopProfileKey(mode.getPhysicalWidth(), mode.getPhysicalHeight());
        }
        android.graphics.Point size = new android.graphics.Point();
        if (display != null) display.getRealSize(size);
        if (size.x > 0 && size.y > 0) return RemoteCanvasActivity.desktopProfileKey(size.x, size.y);
        // A temporarily detached window must not create a profile from its own bounds.
        return fallback.isEmpty() ? "default" : fallback;
    }

    static int cursorSpeedStep(Context context, String display) {
        int fallback = RemoteRdpPointer.savedSpeedStep(context);
        int value = context.getSharedPreferences("desktop_keyboard", Context.MODE_PRIVATE)
                .getInt("cursorSpeed_" + display, fallback);
        return Math.max(0, Math.min(11, value));
    }

    public static float initialCursorSensitivity(Context context, View view) {
        if (!Utils.isFoldable(context)) return RemoteRdpPointer.savedSensitivity(context);
        String display = displayKey(context, view, "");
        return (cursorSpeedStep(context, display) + 1) * .4f;
    }

    static final class Screen {
        final int windowsScale, rowHeight;

        Screen(Context context, String display) {
            SharedPreferences preferences = context.getSharedPreferences("desktop_keyboard", Context.MODE_PRIVATE);
            boolean adaptive = Utils.isFoldable(context);
            int scale = 100, height = adaptive ? 32 : DesktopKeyboardView.BASE_ROW_HEIGHT_DP;
            if (adaptive && display.matches("[0-9]{1,5}x[0-9]{1,5}")) {
                String[] edges = display.split("x");
                int width = Integer.parseInt(edges[0]), length = Integer.parseInt(edges[1]);
                int shortEdge = Math.min(width, length), longEdge = Math.max(width, length);
                if (shortEdge > 0) {
                    // Start at 100% on a 720px phone, then add 25% per 384 extra pixels.
                    scale = Math.max(100, Math.min(200, 100 + 25 * Math.round((shortEdge - 720) / 384f)));
                    height = shortEdge >= longEdge * 0.65f ? DesktopKeyboardView.BASE_ROW_HEIGHT_DP : 41;
                }
            }
            windowsScale = Math.max(100, Math.min(300, preferences.getInt("scale_" + display, scale)));
            rowHeight = Math.max(32, Math.min(84, preferences.getInt("rowHeight_" + display,
                    preferences.getInt("rowHeight", height))));
        }
    }

    static String export(Context context) throws JSONException {
        SharedPreferences keyboard = context.getSharedPreferences("desktop_keyboard", Context.MODE_PRIVATE);
        SharedPreferences session = context.getSharedPreferences(ConnectionBean.SESSION_DEFAULTS, Context.MODE_PRIVATE);
        // In the Fold package getLocalClassName() returns the fully qualified activity name.
        SharedPreferences canvas = context.getSharedPreferences(RemoteCanvasActivity.class.getName(), Context.MODE_PRIVATE);
        int mode = RemoteCanvasActivity.savedKeyboardMode(canvas);
        Set<String> displays = new TreeSet<>();
        displays.add("default");
        for (String key : keyboard.getAll().keySet()) {
            if (key.matches("(seen|scale|rowHeight|cursorSpeed)_[0-9]+x[0-9]+")) displays.add(key.substring(key.indexOf('_') + 1));
        }
        JSONObject screens = new JSONObject();
        for (String display : displays) {
            Screen screen = new Screen(context, display);
            screens.put(display, new JSONObject()
                    .put("windowsScalePercent", screen.windowsScale)
                    .put("rdpKeyboardRowHeightDp", screen.rowHeight)
                    .put("touchpadSpeedPercent", 20 * (cursorSpeedStep(context, display) + 1)));
        }
        return new JSONObject().put("formatVersion", 1).put("displays", screens)
                .put("keyboardMode", mode == RemoteCanvasActivity.FUNCTION_KEYS ? "functionKeys" : "system")
                .put("systemKeyStripPercent", 70)
                .put("touchpadRightClickHaptic", rightClickHaptic(context))
                .put("touchpadSpeedPercent", 20 * (RemoteRdpPointer.savedSpeedStep(context) + 1))
                .put("inputMode", supportedInputMode(context, session.getString(ConnectionBean.GEN_FIELD_INPUTMODE,
                        ConnectionBean.getDefaultInputMode(context))))
                .toString(2);
    }
}
