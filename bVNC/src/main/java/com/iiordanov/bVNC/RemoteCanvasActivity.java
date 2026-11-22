/**
 * Copyright (C) 2012-2017 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

//
// CanvasView is the Activity for showing VNC Desktop.
//
package com.iiordanov.bVNC;

import static com.iiordanov.bVNC.Constants.EXTRA_KEYS_TOUR_SHOWN;
import static com.iiordanov.bVNC.dialogs.MetaKeyDialog.tryPopulateKeysInListWhereFieldMatchesValue;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.ScrollView;

import androidx.viewpager.widget.ViewPager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.graphics.Insets;
import android.widget.TextView;
import android.view.Gravity;
import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.snackbar.Snackbar;
import com.iiordanov.bVNC.dialogs.EnterTextDialog;
import com.iiordanov.bVNC.dialogs.MetaKeyDialog;
import com.iiordanov.bVNC.extrakeys.ExtraKeysView;
import com.iiordanov.bVNC.extrakeys.ExtraKeysPagerAdapter;
import com.iiordanov.bVNC.input.IgnoringMouseInputListener;
import com.iiordanov.bVNC.input.MetaKeyBean;
import com.iiordanov.bVNC.input.Panner;
import com.iiordanov.bVNC.input.RemoteCanvasHandler;
import com.iiordanov.bVNC.input.RemoteClientsInputListener;
import com.iiordanov.bVNC.input.RemoteKeyboard;
import com.iiordanov.bVNC.input.ScrollWheelButton;
import com.iiordanov.bVNC.input.TouchInputHandler;
import com.iiordanov.bVNC.input.TouchInputHandlerDirectDragPan;
import com.iiordanov.bVNC.input.TouchInputHandlerDirectSwipePan;
import com.iiordanov.bVNC.input.TouchInputHandlerSingleHanded;
import com.iiordanov.bVNC.input.TouchInputHandlerTouchpad;
import com.iiordanov.bVNC.input.TouchInputDelegate;
import com.iiordanov.bVNC.protocol.GettingConnectionSettingsException;
import com.iiordanov.bVNC.protocol.RemoteConnection;
import com.iiordanov.bVNC.protocol.RemoteConnectionFactory;
import com.iiordanov.bVNC.protocol.RemoteRdpConnection;
import com.iiordanov.util.SamsungDexUtils;
import com.undatech.opaque.Connection;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.dialogs.SelectTextElementFragment;
import com.undatech.opaque.util.GeneralUtils;
import com.undatech.opaque.util.OnTouchViewMover;
import com.undatech.opaque.util.RemoteToolbar;
import com.undatech.remoteClientUi.R;


import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

@SuppressLint("ClickableViewAccessibility")
public class RemoteCanvasActivity extends AppCompatActivity implements
        SelectTextElementFragment.OnFragmentDismissedListener, TouchInputDelegate {

    public static final int[] inputModeIds = {R.id.itemInputTouchpad,
            R.id.itemInputTouchPanZoomMouse,
            R.id.itemInputDragPanZoomMouse,
            R.id.itemInputSingleHanded};
    public static final Map<Integer, String> inputModeMap;
    private final static String TAG = "RemoteCanvasActivity";
    private static final int[] scalingModeIds = {R.id.itemZoomable, R.id.itemFitToScreen,
            R.id.itemOneToOne};

    static {
        inputModeMap = Map.of(
                R.id.itemInputTouchpad, TouchInputHandlerTouchpad.ID,
                R.id.itemInputDragPanZoomMouse, TouchInputHandlerDirectDragPan.ID,
                R.id.itemInputTouchPanZoomMouse, TouchInputHandlerDirectSwipePan.ID,
                R.id.itemInputSingleHanded, TouchInputHandlerSingleHanded.ID
        );
    }

    final long hideToolbarDelay = 2500;
    TouchInputHandler touchInputHandler;
    Panner panner;
    Handler handler;
    ViewPager extraKeysToolbar;
    LinearLayout extraKeysPageIndicator;
    View[] pageIndicatorDots;
    ExtraKeysPagerAdapter extraKeysPagerAdapter;
    boolean extraKeysHidden = false;
    volatile boolean softKeyboardUp;
    RemoteToolbar toolbar;
    View rootView;
    ActionBarHider actionBarHider = new ActionBarHider();
    ActionBarShower actionBarShower = new ActionBarShower();
    KeyboardIconShower keyboardIconShower = new KeyboardIconShower();
    private Vibrator myVibrator;
    private FrameLayout canvasLayout;
    private RemoteCanvas canvas;
    private RemoteConnection remoteConnection;
    private MenuItem[] inputModeMenuItems;
    private MenuItem[] scalingModeMenuItems;
    private TouchInputHandler[] inputModeHandlers;
    private Connection connection;
    public RemoteClientsInputListener inputListener;
    private ImageButton keyboardIconForAndroidTv;
    float keyboardIconForAndroidTvX = Float.MAX_VALUE;
    IgnoringMouseInputListener ignoringMouseInputListener = new IgnoringMouseInputListener();
    OnTouchViewMover toolbarMover;
    ActionBarPositionSaver toolbarPositionSaver = new ActionBarPositionSaver();
    int xPointerOffset = 0;
    int yPointerOffset = 0;
    private DesktopDock desktopDock;
    private DesktopKeyboardView desktopKeyboard;
    private FrameLayout desktopKeyboardHost;
    private TextView resizeCover;
    private int desktopKeyboardMode;
    static final int SYSTEM_KEYS = 1, FUNCTION_KEYS = 3;
    private int preferredDesktopKeyboardMode = SYSTEM_KEYS;
    private int imeHeight;
    private boolean waitingForSystemKeyboardHide;
    private boolean systemKeyboardSuspended;
    private boolean reopenSystemKeyboard;
    private boolean restoreKeyboardOnConnect;
    private static final String SYSTEM_KEYBOARD_OPEN = "desktop_system_keyboard_open";
    private int desktopTransientWindows;
    private Insets desktopInsets = Insets.NONE;
    private int lastDesktopWidth, lastDesktopHeight;
    private int lastDesktopRotation = -1;
    private String desktopProfile = "";
    private AlertDialog desktopInputDialog;
    private int desktopScalePercent = 100, sentDesktopScalePercent = -1;
    private int desktopResizeGeneration;
    private int requestedDesktopWidth, requestedDesktopHeight;
    private int lastIssuedWidth, lastIssuedHeight, lastIssuedScale;
    private boolean recordedDesktopSession;
    private AlertDialog displayDriverErrorDialog;
    private boolean desktopStopped;
    private boolean desktopGestureActive, desktopPinching, desktopWheel;
    private float wheelRemainder;
    private boolean horizontalWheel;
    private float gestureSpan, gestureFocusX, gestureFocusY;
    private float gestureStartX0, gestureStartY0, gestureStartX1, gestureStartY1;
    private final java.util.Set<Integer> desktopModifiers = new java.util.HashSet<>();
    private final Runnable desktopResize = () -> {
        if (!recordedDesktopSession || desktopStopped || remoteConnection == null || isFinishing() || canvas == null) return;
        // Use the window target; the child canvas may still have pre-rotation bounds.
        int width = lastDesktopWidth, height = lastDesktopHeight;
        if (!RdpViewport.validSize(width, height)) return;
        if (remoteConnection.getRfbConn() == null || !remoteConnection.getRfbConn().isInNormalProtocol()) {
            if (handler != null) handler.postDelayed(this.desktopResizeRetry, 250);
            return;
        }
        requestedDesktopWidth = width & ~1;
        requestedDesktopHeight = height;
        boolean samePixels = canvas.getImageWidth() == requestedDesktopWidth && canvas.getImageHeight() == height;
        boolean targetUnchanged = lastIssuedWidth == 0 || (lastIssuedWidth == requestedDesktopWidth
                && lastIssuedHeight == height && lastIssuedScale == desktopScalePercent);
        if (samePixels && desktopScalePercent == sentDesktopScalePercent && targetUnchanged) {
            if (canvas.hasDesktopFrame()) onDesktopFrame(requestedDesktopWidth, height);
            return;
        }
        canvas.absoluteXPosition = canvas.absoluteYPosition = 0;
        canvas.userPanned = false;
        canvas.resetScroll();
        int generation = desktopResizeGeneration;
        int requestedScale = desktopScalePercent;
        lastIssuedWidth = requestedDesktopWidth;
        lastIssuedHeight = height;
        lastIssuedScale = requestedScale;
        try {
            if (remoteConnection.getRfbConn() instanceof com.undatech.opaque.RdpCommunicator) {
                com.undatech.opaque.RdpCommunicator transport = (com.undatech.opaque.RdpCommunicator)remoteConnection.getRfbConn();
                transport.requestDesktopLayout(
                        requestedDesktopWidth, height, requestedScale, accepted -> {
                            if (generation != desktopResizeGeneration || isFinishing() || isDestroyed()
                                    || transport != remoteConnection.getRfbConn()) return;
                            handler.removeCallbacks(this.desktopResizeTimeout);
                            if (accepted) {
                                sentDesktopScalePercent = requestedScale;
                                if (canvas.getImageWidth() == requestedDesktopWidth && canvas.getImageHeight() == height
                                        && canvas.hasDesktopFrame()) onDesktopFrame(requestedDesktopWidth, height);
                                else handler.postDelayed(this.desktopResizeTimeout, 6000);
                            } else {
                                sentDesktopScalePercent = -1;
                                if (resizeCover != null) resizeCover.setVisibility(View.GONE);
                                int status = transport.getDisplayControlStatus();
                                int message = status == 0 ? R.string.fold_resolution_channel_missing :
                                        status == 1 ? R.string.fold_resolution_caps_pending : R.string.fold_resolution_send_failed;
                                Utils.showMessage(canvas, getString(message), Snackbar.LENGTH_LONG);
                            }
                        });
                return;
            } else {
                remoteConnection.getRfbConn().requestResolution(requestedDesktopWidth, height);
                sentDesktopScalePercent = requestedScale;
                if (samePixels && resizeCover != null) resizeCover.setVisibility(View.GONE);
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to request desktop resize", error);
            this.desktopResizeTimeout.run();
            return;
        }
        handler.removeCallbacks(this.desktopResizeTimeout);
        if (!samePixels) handler.postDelayed(this.desktopResizeTimeout, 6000);
    };
    private final Runnable desktopResizeRetry = this::scheduleDesktopResize;
    private final Runnable desktopResizeTimeout = () -> {
        if (resizeCover != null && resizeCover.getVisibility() == View.VISIBLE) {
            resizeCover.setVisibility(View.GONE);
            int message = R.string.fold_resolution_unavailable;
            if (remoteConnection != null && remoteConnection.getRfbConn() instanceof com.undatech.opaque.RdpCommunicator) {
                int status = ((com.undatech.opaque.RdpCommunicator)remoteConnection.getRfbConn()).getDisplayControlStatus();
                if (status == 0) message = R.string.fold_resolution_channel_missing;
                else if (status == 1) message = R.string.fold_resolution_caps_pending;
                else if (status < 0) message = R.string.fold_resolution_send_failed;
            }
            Utils.showMessage(canvas, getString(message), Snackbar.LENGTH_LONG);
        }
    };

    private void scheduleDesktopResize() {
        if (handler == null || desktopDock == null || desktopStopped) return;
        desktopResizeGeneration++;
        handler.removeCallbacks(desktopResize);
        handler.removeCallbacks(desktopResizeRetry);
        handler.removeCallbacks(desktopResizeTimeout);
        handler.postDelayed(desktopResize, 180);
    }

    private void rememberDesktopOptions() {
        if (Utils.isFoldable(this) && connection instanceof ConnectionBean) {
            ((ConnectionBean) connection).rememberSessionOptions(this);
        }
    }

    public void onDesktopFrame(int width, int height) {
        if (!recordedDesktopSession) {
            recordedDesktopSession = true;
            rememberDesktopOptions();
            scheduleDesktopResize();
        }
        if (resizeCover != null && width == requestedDesktopWidth && height == requestedDesktopHeight) {
            resizeCover.setVisibility(View.GONE);
            handler.removeCallbacks(desktopResizeTimeout);
        }
    }

    /**
     * This runnable enables immersive mode.
     */
    private final Runnable immersiveEnabler = new Runnable() {
        public void run() {
            try {
                if (Utils.querySharedPreferenceBoolean(RemoteCanvasActivity.this,
                        Constants.disableImmersiveTag, false)) {
                    Log.d(TAG, "immersiveEnabler - exiting because immersive mode disabled");
                    return;
                }

                Log.d(TAG, "immersiveEnabler - enabling immersive mode");
                if (Constants.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    canvas.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            } catch (Exception e) {
                Log.d(TAG, "Ignored Exception while enabling immersive mode");
            }
        }
    };
    /**
     * This runnable disables immersive mode.
     */
    private final Runnable immersiveDisabler = new Runnable() {
        public void run() {
            try {
                if (!Utils.querySharedPreferenceBoolean(RemoteCanvasActivity.this,
                        Constants.disableImmersiveTag, false)) {
                    Log.d(TAG, "immersiveDisabler - exiting because immersive mode enabled");
                    return;
                }

                Log.d(TAG, "immersiveDisabler - disabling immersive mode");
                if (Constants.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    canvasLayout.setFitsSystemWindows(desktopDock == null);
                    canvas.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    ViewCompat.requestApplyInsets(canvasLayout);
                }
            } catch (Exception e) {
                Log.d(TAG, "Ignored Exception while disabling immersive mode");
            }
        }
    };

    /**
     * This runnable fixes things up after a rotation.
     */
    private final Runnable rotationCorrector = () -> {
        try {
            correctAfterRotation();
        } catch (Exception e) {
            Log.d(TAG, "Ignoring Exception on rotationCorrector run.");
        }
    };

    private void correctAfterRotation() throws Exception {
        Log.d(TAG, "correctAfterRotation");
        if (desktopDock != null) { layoutDesktop(); return; }
        canvas.waitUntilInflated();
        // Its quite common to see NullPointerExceptions here when this function is called
        // at the point of disconnection. Hence, we catch and ignore the error.
        float oldScale = canvas.canvasZoomer.getZoomFactor();
        int x = canvas.absoluteXPosition;
        int y = canvas.absoluteYPosition;
        canvas.canvasZoomer.setScaleTypeForActivity(this);
        float newScale = canvas.canvasZoomer.getZoomFactor();
        canvas.canvasZoomer.changeZoom(this, oldScale / newScale, 0, 0);
        newScale = canvas.canvasZoomer.getZoomFactor();
        if (newScale <= oldScale &&
                canvas.canvasZoomer.getScaleType() != ImageView.ScaleType.FIT_CENTER) {
            canvas.absoluteXPosition = x;
            canvas.absoluteYPosition = y;
            canvas.resetScroll();
        }
        remoteConnection.correctAfterRotation();
    }

    /**
     * Controls sticky immersive mode (enables/disables as configured).
     */
    private void controlImmersive() {
        if (handler != null) {
            handler.removeCallbacks(immersiveEnabler);
            handler.postDelayed(immersiveEnabler, 200);
            handler.removeCallbacks(immersiveDisabler);
            handler.postDelayed(immersiveDisabler, 200);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG, "onWindowFocusChanged: " + hasFocus);
        if (canvas != null) canvas.setForegrounded(hasFocus);
        if (remoteConnection != null && remoteConnection.getRfbConn() instanceof com.undatech.opaque.RdpCommunicator rdp) {
            rdp.setClipboardFocus(hasFocus);
            if (hasFocus && rdp.isInNormalProtocol()) requestTransferNotifications();
        }
        if (hasFocus) {
            if (desktopDock != null) applyDesktopCursorSpeed();
            DesktopSessions.activated(this);
            if (Utils.isFoldable(this) && !waitingForWorkspaceUnlock) DesktopWorkspace.focused(this);
        }
        handleDesktopWindowFocus(hasFocus, Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode());
        if (hasFocus) {
            controlImmersive();
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private static final int PHONE_DRIVE_PERMISSION = 4073;
    private static final String WAITING_FOR_PHONE_DRIVE = "waiting_for_phone_drive";
    private boolean waitingForPhoneDrive;
    private static final int UNLOCK_WORKSPACE = 4074;
    private boolean waitingForWorkspaceUnlock;
    private DesktopReconnect reconnect = new DesktopReconnect();
    private final Runnable reconnectCheck = this::checkReconnect;

    /** Keep in-memory credentials in this document task across a transport retry. */
    private static final class RetrySession {
        final Connection connection;
        final DesktopReconnect budget;

        RetrySession(Connection connection, DesktopReconnect budget) {
            this.connection = connection;
            this.budget = budget;
        }
    }

    @Override public Object onRetainCustomNonConfigurationInstance() {
        if (remoteConnection == null || !remoteConnection.maintainConnection) return null;
        return reconnect.waiting || reconnect.retrying()
                ? new RetrySession(connection, reconnect) : null;
    }

    public void desktopDisplayDriverFailed() {
        if (displayDriverErrorDialog != null || isFinishing() || isDestroyed() || !remoteConnection.maintainConnection) return;
        handler.removeCallbacks(reconnectCheck);
        remoteConnection.pd.dismiss();
        displayDriverErrorDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.error_dialog_title)
                .setMessage(describeRdpFailure(R.string.fold_display_driver_error))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    public String describeRdpFailure(int message) {
        String error = getString(message);
        if (remoteConnection.getRfbConn() instanceof com.undatech.opaque.RdpCommunicator transport) {
            long layout = transport.getLastLayoutBeforeStop();
            if (transport.getInitialWidth() <= 0) return error;
            error += "\n\n" + getString(R.string.fold_initial_desktop, transport.getInitialWidth(), transport.getInitialHeight())
                    + " " + getString(transport.hadFirstFrame() ? R.string.fold_frame_seen : R.string.fold_frame_missing)
                    + " " + (layout == 0 ? getString(R.string.fold_no_layout)
                    : getString(R.string.fold_last_layout, (int)(layout >> 32), (int)(layout >> 16) & 0xffff, (int)layout & 0xffff));
            long codes = transport.getErrorCodesBeforeStop();
            if ((codes >>> 32) != 0 && message != R.string.fold_display_driver_error)
                error += String.format(java.util.Locale.ROOT, " RDP 0x%X.", codes >>> 32);
            if ((int)codes != 0)
                error += String.format(java.util.Locale.ROOT, " FreeRDP 0x%08X.", (int)codes);
        }
        return error;
    }

    public void desktopConnected() {
        handler.removeCallbacks(reconnectCheck);
        reconnect.connected(SystemClock.elapsedRealtime());
        if (hasWindowFocus()) requestTransferNotifications();
    }

    private void requestTransferNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !Utils.isFoldable(this) || waitingForPhoneDrive || isFinishing()
                || isDestroyed() || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences(Constants.generalSettingsTag, MODE_PRIVATE);
        String key = "asked_for_transfer_notifications";
        if (prefs.getBoolean(key, false) || getSupportFragmentManager().isStateSaved()) return;
        prefs.edit().putBoolean(key, true).apply();
        new FileProgressPermissionDialog().show(getSupportFragmentManager(), key);
    }

    public static final class FileProgressPermissionDialog extends androidx.fragment.app.DialogFragment {
        @NonNull @Override public Dialog onCreateDialog(Bundle state) {
            return new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fold_file_progress_question).setMessage(R.string.fold_file_progress_explanation)
                    .setNegativeButton(R.string.fold_file_progress_later, null)
                    .setPositiveButton(R.string.fold_file_progress_continue, (dialog, which) -> {
                        if (Build.VERSION.SDK_INT >= 33) requireActivity().requestPermissions(
                                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4074);
                    }).create();
        }
    }

    public void desktopNetworkLost() {
        if (isFinishing() || isDestroyed()) return;
        if (!reconnect.lost(SystemClock.elapsedRealtime())) {
            reconnectFailed();
            return;
        }
        updateReconnectMessage();
        remoteConnection.pd.show();
        handler.removeCallbacks(reconnectCheck);
        checkReconnect();
    }

    private void updateReconnectMessage() {
        remoteConnection.pd.setMessage(getString(R.string.fold_reconnecting,
                reconnect.attemptNumber(), reconnect.maxAttempts()));
    }

    private void checkReconnect() {
        if (isFinishing() || isDestroyed() || !remoteConnection.maintainConnection || !reconnect.waiting) return;
        long now = SystemClock.elapsedRealtime();
        if (reconnect.expired(now)) {
            reconnectFailed();
            return;
        }
        long delay = reconnect.delay(now);
        if (delay == 0) {
            ConnectivityManager networks = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            boolean available;
            if (Build.VERSION.SDK_INT >= 23) available = networks.getActiveNetwork() != null;
            else {
                android.net.NetworkInfo info = networks.getActiveNetworkInfo();
                available = info != null && info.isConnected();
            }
            if (available) {
                reconnect.startAttempt();
                recreate();
                return;
            }
            delay = 1000;
        }
        handler.postDelayed(reconnectCheck, delay);
    }

    private void reconnectFailed() {
        remoteConnection.pd.dismiss();
        remoteConnection.showFatalMessageAndQuit(describeRdpFailure(R.string.fold_reconnect_failed));
    }

    @Override
    public void onCreate(Bundle icicle) {
        Log.d(TAG, "OnCreate called");
        super.onCreate(icicle);
        restoreKeyboardOnConnect = icicle != null && icicle.getBoolean(SYSTEM_KEYBOARD_OPEN);
        if (Utils.isFoldable(this)) {
            if (!DesktopWorkspace.created(this, icicle)) return;
            if (icicle != null && isMasterPasswordEnabled() && Database.getPassword().isEmpty()) {
                waitingForWorkspaceUnlock = true;
                if (!icicle.getBoolean(DesktopWorkspace.UNLOCK)) {
                    startActivityForResult(new Intent(this, com.undatech.opaque.ConnectionGridActivity.class)
                            .setAction(DesktopWorkspace.UNLOCK), UNLOCK_WORKSPACE);
                }
                return;
            }
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Utils.showMenu(this);

        setContentView(R.layout.canvas);

        canvasLayout = findViewById(R.id.canvasLayout);
        canvas = findViewById(R.id.canvas);
        keyboardIconForAndroidTv = findViewById(R.id.keyboardIconForAndroidTv);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.setDefaultFocusHighlightEnabled(false);
        }
        if (Build.VERSION.SDK_INT >= 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        myVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener
                (visibility -> {
                    try {
                        remoteConnection.correctAfterRotation();
                    } catch (Exception e) {
                        Log.d(TAG, "Ignoring Exception during SystemUiVisibilityChangeListener execution");
                    }
                });

        Runnable setModes = () -> {
            try {
                setModes();
            } catch (NullPointerException e) {
                Log.d(TAG, "Ignored NullPointerException while running setModes: " + Log.getStackTraceString(e));
            }
        };
        Runnable hideKeyboardAndExtraKeys = () -> {
            try {
                if (desktopDock != null && restoreKeyboardOnConnect) {
                    restoreKeyboardOnConnect = false;
                    desktopKeyboardMode = SYSTEM_KEYS;
                    desktopKeyboard.setFunctionsOnly(false);
                    systemKeyboardSuspended = reopenSystemKeyboard = true;
                    if (hasWindowFocus()) resumeSystemKeyboard();
                    return;
                }
                hideKeyboardAndExtraKeys();
            } catch (NullPointerException e) {
                Log.d(TAG, "Ignoring NullPointerException while running hideKeyboardAndExtraKeys.");
            }
        };

        setApplicationSpecificSettings();

        RetrySession retry = (RetrySession) getLastCustomNonConfigurationInstance();
        try {
            if (retry == null) {
                connection = AbstractConnectionBean.getRemoteConnectionSettings(getIntent(), this, isMasterPasswordEnabled());
            } else {
                connection = retry.connection;
                reconnect = retry.budget;
            }
        } catch (GettingConnectionSettingsException e) {
            Utils.showFatalErrorMessage(this, getResources().getString(e.getErrorStringId()));
            return;
        }
        if (connection != null && Utils.isRdp(this)) {
            connection.setRdpResType(RemoteClientLibConstants.RDP_GEOM_SELECT_AUTO);
            connection.setFontSmoothing(true);
            String screenProfile = currentDesktopProfile();
            connection.setDesktopScalePercentage(new DesktopControlProfile.Screen(this, screenProfile).windowsScale);
            if (android.text.TextUtils.isEmpty(connection.getConnectionConfigFile())) {
                sentDesktopScalePercent = connection.getDesktopScalePercentage();
            }
            if (Utils.isFoldable(this)) {
                connection.setDesktopBackground(true);
                connection.setDesktopComposition(true);
                connection.setWindowContents(true);
                connection.setMenuAnimation(true);
                connection.setVisualStyles(true);
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) connection.setEnableRecording(false);
            }
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        if (Utils.isFoldable(this) && connection != null && DesktopSessions.resumeSaved(this, connection.getId())) {
            finish();
            return;
        }
        remoteConnection = new RemoteConnectionFactory(this, connection, canvas, hideKeyboardAndExtraKeys).build();
        if (connection != null && connection.isReadyForConnection()) {
            if (Utils.isFoldable(this)) {
                DesktopSessions.register(this, connection);
                long profile = connection instanceof ConnectionBean bean && getIntent().getData() == null ? bean.get_Id() : 0;
                DesktopWorkspace.registered(this, profile);
            }
            handler = new RemoteCanvasHandler(this, canvas, remoteConnection, connection, setModes);
            if (retry != null && remoteConnection instanceof RemoteRdpConnection && (reconnect.waiting || reconnect.retrying())) {
                updateReconnectMessage();
                if (reconnect.waiting) {
                    checkReconnect();
                    return;
                }
            }
            Log.d(TAG, "OnCreate - initializing session with a REINIT_SESSION message");
            waitingForPhoneDrive = icicle != null && icicle.getBoolean(WAITING_FOR_PHONE_DRIVE);
            if (!waitingForPhoneDrive && Utils.isFoldable(this))
                waitingForPhoneDrive = PhoneDrive.offerPermission(this, connection, PHONE_DRIVE_PERMISSION);
            if (!waitingForPhoneDrive) startDesktopConnection();
        } else {
            showConnectionScreenOrExitIfNotReadyForConnecting(connection);
        }

        Log.d(TAG, "OnCreate complete");
    }

    private void setApplicationSpecificSettings() {
        if (Utils.isOpaque(this)) {
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
        } else {
            if (Utils.querySharedPreferenceBoolean(this, Constants.keepScreenOnTag))
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            if (Utils.isRdp(this))
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            else if (Utils.querySharedPreferenceBoolean(this, Constants.forceLandscapeTag))
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private void startDesktopConnection() {
        handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
        continueConnecting();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UNLOCK_WORKSPACE && waitingForWorkspaceUnlock) {
            if (resultCode == RESULT_OK && !Database.getPassword().isEmpty()) {
                recreate();
            } else {
                DesktopWorkspace.dismiss(this);
            }
            return;
        }
        if (requestCode == PHONE_DRIVE_PERMISSION && waitingForPhoneDrive) {
            waitingForPhoneDrive = false;
            if (!isFinishing() && handler != null) startDesktopConnection();
        }
    }

    private void showConnectionScreenOrExitIfNotReadyForConnecting(Connection connection) {
        // we need to save the connection to display the loading screen, so otherwise we should exit
        if (connection == null || !connection.isReadyForConnection()) {
            Utils.showMessage(canvas, getString(R.string.error_uri_noinfo_nosave), Snackbar.LENGTH_LONG);
            if (connection != null && connection.isReadyToBeSaved()) {
                Log.i(TAG, "Exiting - Insufficient information to connect and connection was not saved.");
            } else {
                Log.i(TAG, "Insufficient information to connect, showing connection dialog.");
                // launch appropriate activity
                Class<?> cls = bVNC.class;
                if (Utils.isRdp(this)) {
                    cls = aRDP.class;
                } else if (Utils.isSpice(this)) {
                    cls = aSPICE.class;
                }
                Intent Intent = new Intent(this, cls);
                startActivity(Intent);
            }
            Utils.justFinish(this);
        }
    }

    @SuppressLint("RtlHardcoded")
    public void continueConnecting() {
        Log.d(TAG, "continueConnecting");
        // Initialize extra keys view and pager.
        initializeExtraKeysView();

        if (Utils.isRdp(this)) {
            initializeDesktopControls();
            return;
        }

        canvas.setFocusableInTouchMode(true);
        canvas.setDrawingCacheEnabled(false);

        // This code detects when the soft keyboard is up and sets an appropriate visibleHeight in vncCanvas.
        // When the keyboard is gone, it resets visibleHeight and pans zero distance to prevent us from being
        // below the desktop image (if we scrolled all the way down when the keyboard was up).
        // TODO: Move this into a separate thread, and post the visibility changes to the handler.
        //       to avoid occupying the UI thread with this.
        rootView = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> relayoutViews(rootView));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);

        if (Utils.querySharedPreferenceBoolean(this, Constants.leftHandedModeTag)) {
            params.gravity = Gravity.CENTER | Gravity.LEFT;
        } else {
            params.gravity = Gravity.CENTER | Gravity.RIGHT;
        }

        panner = new Panner(this, handler);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("");
        toolbar.setOnGenericMotionListener(ignoringMouseInputListener);
        toolbar.getBackground().setAlpha(64);
        toolbar.setLayoutParams(params);
        setSupportActionBar(toolbar);
        showActionBar();
    }

    void relayoutViews(View rootView) {
        if (desktopDock != null) { layoutDesktop(); return; }
        Log.d(TAG, "onGlobalLayout: start");
        if (canvas == null) {
            Log.d(TAG, "onGlobalLayout: canvas null, returning");
            return;
        }

        Rect r = new Rect();

        rootView.getWindowVisibleDisplayFrame(r);
        Log.d(TAG, "onGlobalLayout: getWindowVisibleDisplayFrame: " + r);

        // To avoid setting the visible height to a wrong value after an screen unlock event
        // (when r.bottom holds the width of the screen rather than the height due to a rotation)
        // we make sure r.top is zero (i.e. there is no notification bar and we are in full-screen mode)
        // It's a bit of a hack.
        // One additional situation that needed handling was that devices with notches / cutouts don't
        // ever have r.top equal to zero. so a special case for them.
        Rect re = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(re);
        if (r.top == 0 || re.top > 0) {
            if (canvas.getDrawable() != null) {
                int extraKeysHeight = (extraKeysToolbar != null) ? extraKeysToolbar.getHeight() : 0;
                int usableHeight = r.bottom - re.top - extraKeysHeight;
                Log.d(TAG, "onGlobalLayout: Setting VisibleDesktopHeight to: " + usableHeight +
                        ", extraKeysHeight: " + extraKeysHeight);
                canvas.setVisibleDesktopHeight(usableHeight);
                canvas.relativePan(0, 0);
            } else {
                Log.d(TAG, "onGlobalLayout: canvas.myDrawable is null");
            }
        } else {
            Log.d(TAG, "onGlobalLayout: Found r.top to be non-zero");
        }

        // Enable/show the toolbar if the keyboard is gone, and disable/hide otherwise.
        // We detect the keyboard if more than 19% of the screen is covered.
        // Use the visible display frame of the decor view to compute notch dimensions.
        int rootViewHeight = rootView.getHeight();

        if (keyboardIconForAndroidTvX == Float.MAX_VALUE) {
            keyboardIconForAndroidTvX = keyboardIconForAndroidTv.getX();
        }

        int[] rootViewScreenPos = new int[2];
        rootView.getLocationOnScreen(rootViewScreenPos);
        int rootViewTopInScreen = rootViewScreenPos[1];

        int layoutKeysBottom = extraKeysToolbar.getBottom();
        int toolbarBottom = toolbar.getBottom();
        int rootViewBottom = extraKeysToolbar.getRootView().getBottom();
        int diffLayoutKeysPosition = r.bottom - rootViewTopInScreen - layoutKeysBottom;
        int diffToolbarPosition = r.bottom - re.top - toolbarBottom - r.bottom / 2;
        int standardToolbarPositionX = r.right - toolbar.getWidth();
        int standardToolbarPositionY = r.bottom - re.top - toolbar.getHeight() - r.bottom / 2;
        Log.d(TAG, "onGlobalLayout: before: r.bottom: " + r.bottom +
                " rootViewHeight: " + rootViewHeight + " re.top: " + re.top + " re.bottom: " + re.bottom +
                " layoutKeysBottom: " + layoutKeysBottom + " rootViewBottom: " + rootViewBottom + " toolbarBottom: " + toolbarBottom +
                " diffLayoutKeysPosition: " + diffLayoutKeysPosition + " diffToolbarPosition: " + diffToolbarPosition);

        if (r.bottom > rootViewHeight * 0.81) {
            Log.d(TAG, "onGlobalLayout: Less than 19% of screen is covered");
            String direction = "down";
            // Soft Kbd gone, shift the extra keys toolbar down.
            if (extraKeysToolbar != null) {
                shiftToolbar(r, diffLayoutKeysPosition, diffToolbarPosition, standardToolbarPositionX, standardToolbarPositionY, direction);
                if (softKeyboardUp) {
                    Log.d(TAG, "onGlobalLayout: softKeyboardUp was true, but keyboard is now hidden. Hiding on-screen buttons");
                    setExtraKeysVisibility(View.GONE, false);
                    canvas.invalidate();
                }
            }
            softKeyboardUp = false;
        } else {
            Log.d(TAG, "onGlobalLayout: More than 19% of screen is covered");
            softKeyboardUp = true;
            String direction = "up";
            //  Soft Kbd up, shift the extra keys toolbar up.
            if (extraKeysToolbar != null) {
                shiftToolbar(r, diffLayoutKeysPosition, diffToolbarPosition, standardToolbarPositionX, standardToolbarPositionY, direction);
                if (extraKeysHidden) {
                    Log.d(TAG, "onGlobalLayout: on-screen buttons should be hidden");
                    setExtraKeysVisibility(View.GONE, false);
                } else {
                    Log.d(TAG, "onGlobalLayout: on-screen buttons should be showing");
                    setExtraKeysVisibility(View.VISIBLE, true);
                }
                canvas.invalidate();
            }
        }
        if (extraKeysToolbar != null) {
            layoutKeysBottom = extraKeysToolbar.getBottom();
            rootViewBottom = extraKeysToolbar.getRootView().getBottom();
        }
        Log.d(TAG, "onGlobalLayout: after: r.bottom: " + r.bottom +
                " rootViewHeight: " + rootViewHeight + " re.top: " + re.top + " re.bottom: " + re.bottom +
                " layoutKeysBottom: " + layoutKeysBottom + " rootViewBottom: " + rootViewBottom + " toolbarBottom: " + toolbarBottom +
                " diffLayoutKeysPosition: " + diffLayoutKeysPosition + " diffToolbarPosition: " + diffToolbarPosition);

        recalculateYPointerOffset();
    }

    private void recalculateYPointerOffset() {
        boolean disabled = Utils.querySharedPreferenceBoolean(getApplicationContext(), "disablePointerOffsetCalculation");
        if (disabled) {
            Log.d(TAG, "recalculateTitleBarHeight - pointer offset calculation disabled");
            xPointerOffset = 0;
            yPointerOffset = 0;
        } else {
            Rect frame = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            int[] location = new int[2];
            getWindow().getDecorView().getLocationOnScreen(location);

            if (isWidthFull()) {
                Log.d(TAG, "recalculateTitleBarHeight - app is occupying full width of screen, skipping x offset calculation");
                xPointerOffset = 0;
            } else {
                int displayLeft = location[0];
                xPointerOffset = frame.left - displayLeft;
                Log.d(TAG, "recalculateTitleBarHeight - frame.left - displayLeft = xPointerOffset: " +
                        frame.left + " - " + displayLeft + " = " + xPointerOffset);
            }

            if (isHeightFull()) {
                Log.d(TAG, "recalculateTitleBarHeight - app is occupying full height of screen, skipping y offset calculation");
                yPointerOffset = 0;
            } else {
                int displayTop = location[1];
                yPointerOffset = frame.top - displayTop;
                Log.d(TAG, "recalculateTitleBarHeight - frame.top - displayTop = yPointerOffset: " +
                        frame.top + " - " + displayTop + " = " + yPointerOffset);
            }
        }
    }

    public boolean isWidthFull() {
        int w = canvas.getWidth();
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int dW = displayMetrics.widthPixels;
        Log.d(TAG, "isWidthFull - view width: " + w + ", display width: " + dW);
        return w == dW;
    }

    public boolean isHeightFull() {
        int h = canvas.getHeight();
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int dH = displayMetrics.heightPixels;
        Log.d(TAG, "isHeightFull - view height: " + h + ", display height: " + dH);
        return h == dH;
    }

    private void shiftToolbar(Rect r, int diffLayoutKeysPosition, int diffToolbarPosition, int standardToolbarPositionX, int standardToolbarPositionY, String direction) {
        Log.d(TAG, String.format("onGlobalLayout: shifting on-screen buttons %s by: %d", direction, diffLayoutKeysPosition));
        // Use setTranslationY instead of offsetTopAndBottom: unlike offsetTopAndBottom, setTranslationY
        // is a render-layer transform that is not reset by layout passes, and does not change getBottom(),
        // so every onGlobalLayout call sets the same absolute translation regardless of intermediate layouts.
        extraKeysToolbar.setTranslationY(diffLayoutKeysPosition);
        if (extraKeysPageIndicator != null)
            extraKeysPageIndicator.setTranslationY(diffLayoutKeysPosition);
        offsetOrRestoreSavedToolbarPosition(r, diffToolbarPosition, standardToolbarPositionX, standardToolbarPositionY);
    }

    private void offsetOrRestoreSavedToolbarPosition(Rect r, int diffToolbarPosition, int standardPositionX, int standardPositionY) {
        boolean useLastPosition = connection.getUseLastPositionToolbar();
        boolean toolbarMoved = connection.getUseLastPositionToolbarMoved();
        if (!useLastPosition || !toolbarMoved) {
            toolbar.offsetTopAndBottom(diffToolbarPosition);
        } else {
            toolbar.setPositionToMakeVisible(
                    connection.getUseLastPositionToolbarX(),
                    connection.getUseLastPositionToolbarY(),
                    r.right,
                    r.bottom,
                    standardPositionX,
                    standardPositionY);
        }
    }

    public void extraKeysToggle(MenuItem m) {
        if (extraKeysToolbar.getVisibility() == View.VISIBLE) {
            extraKeysHidden = true;
            setExtraKeysVisibility(View.GONE, false);
        } else {
            extraKeysHidden = false;
            setExtraKeysVisibility(View.VISIBLE, true);
        }
        setKeyStowDrawableAndVisibility(m);
        relayoutViews(rootView);
    }

    private void setKeyStowDrawableAndVisibility(MenuItem m) {
        if (m == null) {
            return;
        }
        Drawable replacer;
        m.setVisible(connection.getExtraKeysToggleType() != Constants.EXTRA_KEYS_OFF);
        if (extraKeysToolbar.getVisibility() == View.GONE)
            replacer = ResourcesCompat.getDrawable(getResources(), R.drawable.showkeys, null);
        else
            replacer = ResourcesCompat.getDrawable(getResources(), R.drawable.hidekeys, null);

        m.setIcon(replacer);
    }

    public void sendShortVibration() {
        if (myVibrator != null) {
            myVibrator.vibrate(Constants.SHORT_VIBRATION);
        } else {
            Log.i(TAG, "Device cannot vibrate, not sending vibration");
        }
    }

    @Override
    public <T extends View> T findViewById(int id) {
        return super.findViewById(id);
    }

    @Override
    public boolean getUseDpadAsArrows() {
        return connection != null && connection.getUseDpadAsArrows();
    }

    @Override
    public boolean getRotateDpad() {
        return connection != null && connection.getRotateDpad();
    }

    @Override
    public int getxPointerOffset() {
        return xPointerOffset;
    }

    @Override
    public int getyPointerOffset() {
        return yPointerOffset;
    }

    private void initializeExtraKeysView() {
        extraKeysToolbar = findViewById(R.id.extraKeysToolbar);
        float extraKeysAlpha = Color.alpha(ContextCompat.getColor(this, R.color.extra_keys_background)) / 255f;
        extraKeysToolbar.setAlpha(extraKeysAlpha);

        extraKeysPagerAdapter = new ExtraKeysPagerAdapter(this, new ExtraKeysPagerAdapter.Callbacks() {
            @Override
            public void onSendText(String text) {
                if (inputListener != null) {
                    inputListener.sendText(text);
                }
            }
            @Override
            public RemoteKeyboard getKeyboard() {
                return remoteConnection != null ? remoteConnection.getKeyboard() : null;
            }
        });
        extraKeysToolbar.setAdapter(extraKeysPagerAdapter);

        extraKeysToolbar.setCurrentItem(1, false);

        extraKeysPageIndicator = findViewById(R.id.extraKeysPageIndicator);
        extraKeysPageIndicator.setAlpha(extraKeysAlpha);
        pageIndicatorDots = new View[]{
            findViewById(R.id.dotPage0),
            findViewById(R.id.dotPage1),
            findViewById(R.id.dotPage2)
        };
        updatePageDots(1);

        extraKeysToolbar.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePageDots(position);
                if (position == 0) {
                    if (extraKeysPagerAdapter != null && extraKeysPagerAdapter.getSendTextPanel() != null)
                        extraKeysPagerAdapter.getSendTextPanel().requestFocusOnText();
                } else {
                    canvas.requestFocus();
                }
            }
        });
    }

    private void initializeDesktopControls() {
        if (desktopDock != null) return;
        canvasLayout.setFitsSystemWindows(false);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setVisibility(View.GONE);
        extraKeysToolbar.setVisibility(View.GONE);
        extraKeysPageIndicator.setVisibility(View.GONE);
        rootView = canvasLayout;
        desktopKeyboard = new DesktopKeyboardView(this, new DesktopKeyboardView.Listener() {
            @Override public void key(int code, boolean down, int repeat) { sendDesktopKey(code, down, repeat); }
            @Override public boolean modifier(int code) {
                if (remoteConnection.getKeyboard() == null) return false;
                RemoteKeyboard keyboard = remoteConnection.getKeyboard();
                boolean on;
                if (code == KeyEvent.KEYCODE_CTRL_LEFT) on = keyboard.onScreenCtrlToggle();
                else if (code == KeyEvent.KEYCODE_ALT_LEFT) on = keyboard.onScreenAltToggle();
                else if (code == KeyEvent.KEYCODE_SHIFT_LEFT) on = keyboard.onScreenShiftToggle();
                else on = keyboard.onScreenSuperToggle();
                if (on) desktopModifiers.add(code); else desktopModifiers.remove(code);
                sendDesktopKey(code, on, 0);
                return on;
            }
            @Override public void clearModifiers() {
                for (int code : desktopModifiers) sendDesktopKey(code, false, 0);
                desktopModifiers.clear();
                if (remoteConnection.getKeyboard() != null) remoteConnection.getKeyboard().clearMetaState();
            }
            @Override public void hide() { hideKeyboard(); }
            @Override public void layoutChanged() { layoutDesktop(); }
            @Override public void switchKeyboard() {
                selectDesktopKeyboard(desktopKeyboardMode == FUNCTION_KEYS ? SYSTEM_KEYS : FUNCTION_KEYS);
            }
        });
        desktopKeyboardHost = new FrameLayout(this);
        desktopKeyboardHost.setId(R.id.foldKeyboard);
        desktopKeyboard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutDesktop());
        desktopKeyboardHost.addView(desktopKeyboard, new FrameLayout.LayoutParams(-1, -1));
        desktopKeyboardHost.setVisibility(View.GONE);
        canvasLayout.addView(desktopKeyboardHost, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        resizeCover = new TextView(this);
        resizeCover.setGravity(Gravity.CENTER);
        resizeCover.setTextColor(0xFFE1E7EF);
        resizeCover.setBackgroundColor(0xFF10141A);
        resizeCover.setText(R.string.fold_resizing);
        resizeCover.setClickable(true);
        resizeCover.setVisibility(View.GONE);
        canvasLayout.addView(resizeCover, new FrameLayout.LayoutParams(-1, -1));
        preferredDesktopKeyboardMode = savedKeyboardMode(getPreferences(MODE_PRIVATE));
        desktopDock = new DesktopDock(this, this::showDesktopMenu, v -> toggleDesktopKeyboard(),
                Utils.isFoldable(this) ? v -> startActivity(new Intent(this, com.undatech.opaque.ConnectionGridActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) : null);
        canvasLayout.addView(desktopDock);
        ViewCompat.setOnApplyWindowInsetsListener(canvasLayout, (view, insets) -> {
            int safeTypes = WindowInsetsCompat.Type.displayCutout();
            if (Utils.querySharedPreferenceBoolean(this, Constants.disableImmersiveTag, false))
                safeTypes |= WindowInsetsCompat.Type.systemBars();
            desktopInsets = insets.getInsetsIgnoringVisibility(safeTypes);
            updateDesktopIme(insets.isVisible(WindowInsetsCompat.Type.ime()),
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            layoutDesktop();
            return insets;
        });
        canvasLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutDesktop());
        canvas.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutDesktop());
        canvasLayout.getViewTreeObserver().addOnPreDrawListener(() -> {
            int width = canvasLayout.getWidth() - desktopInsets.left - desktopInsets.right;
            int height = canvasLayout.getHeight() - desktopInsets.top - desktopInsets.bottom;
            int rotation = canvasLayout.getDisplay() == null ? 0 : canvasLayout.getDisplay().getRotation();
            if (width != lastDesktopWidth || height != lastDesktopHeight || rotation != lastDesktopRotation
                    || !desktopProfile.equals(currentDesktopProfile()))
                layoutDesktop();
            canvas.setRdpWindowSize(width, height);
            return true;
        });
        ViewCompat.requestApplyInsets(canvasLayout);
        canvas.setFocusableInTouchMode(true);
        canvas.requestFocus();
        layoutDesktop();
    }

    private void layoutDesktop() {
        if (desktopDock == null || canvasLayout.getWidth() <= 0 || canvasLayout.getHeight() <= 0) return;
        int width = Math.max(0, canvasLayout.getWidth() - desktopInsets.left - desktopInsets.right);
        int height = Math.max(0, canvasLayout.getHeight() - desktopInsets.top - desktopInsets.bottom);
        String profile = currentDesktopProfile();
        boolean profileChanged = !profile.equals(desktopProfile);
        if (profileChanged) {
            desktopProfile = profile;
            if (desktopInputDialog != null) desktopInputDialog.dismiss();
            applyDesktopCursorSpeed();
            SharedPreferences keyboard = getSharedPreferences("desktop_keyboard", MODE_PRIVATE);
            desktopScalePercent = new DesktopControlProfile.Screen(this, profile).windowsScale;
            if (Utils.isFoldable(this) && !keyboard.contains("seen_" + profile)) {
                keyboard.edit().putBoolean("seen_" + profile, true).apply();
            }
            if (desktopKeyboard != null) desktopKeyboard.setScreenProfile(profile);
        }
        FrameLayout.LayoutParams canvasParams = (FrameLayout.LayoutParams) canvas.getLayoutParams();
        if (canvasParams.width != width || canvasParams.height != height
                || canvasParams.leftMargin != desktopInsets.left || canvasParams.topMargin != desktopInsets.top) {
            canvasParams.width = width; canvasParams.height = height;
            canvasParams.gravity = Gravity.TOP | Gravity.LEFT;
            canvasParams.leftMargin = desktopInsets.left; canvasParams.topMargin = desktopInsets.top;
            canvas.setLayoutParams(canvasParams);
        }
        int occlusion = Math.max(0, imeHeight - desktopInsets.bottom);
        if (desktopKeyboardHost.getVisibility() == View.VISIBLE) {
            desktopKeyboard.setAvailableHeight(Math.round(Math.max(0, height - occlusion)
                    * .80f));
            desktopKeyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int keyboardHeight = desktopKeyboard.getMeasuredHeight();
            FrameLayout.LayoutParams keyboardParams = (FrameLayout.LayoutParams) desktopKeyboardHost.getLayoutParams();
            int bottom = desktopInsets.bottom + occlusion;
            if (keyboardParams.height != keyboardHeight || keyboardParams.width != width
                    || keyboardParams.bottomMargin != bottom || keyboardParams.leftMargin != desktopInsets.left) {
                keyboardParams.width = width; keyboardParams.height = keyboardHeight;
                keyboardParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
                keyboardParams.bottomMargin = bottom; keyboardParams.leftMargin = desktopInsets.left;
                desktopKeyboardHost.setLayoutParams(keyboardParams);
            }
            occlusion += keyboardHeight;
        }
        canvas.updateDesktopViewport(height, occlusion);
        desktopDock.setBounds(new Rect(desktopInsets.left, desktopInsets.top,
                desktopInsets.left + width, desktopInsets.top + Math.max(1, height - occlusion)));
        xPointerOffset = desktopInsets.left;
        yPointerOffset = desktopInsets.top;
        int rotation = canvasLayout.getDisplay() == null ? 0 : canvasLayout.getDisplay().getRotation();
        if (width != lastDesktopWidth || height != lastDesktopHeight || rotation != lastDesktopRotation || profileChanged) {
            lastDesktopWidth = width; lastDesktopHeight = height;
            lastDesktopRotation = rotation;
            if (RdpViewport.validSize(width, height)) {
                requestedDesktopWidth = width & ~1;
                requestedDesktopHeight = height;
                resizeCover.setVisibility(View.VISIBLE);
                scheduleDesktopResize();
            }
        }
    }

    private void showDesktopMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, R.id.foldInputSettings, 0, R.string.fold_input_settings);
        onCreateOptionsMenu(popup.getMenu());
        if (Utils.isFoldable(this) && DesktopSessions.isOpen(connection.getId()))
            popup.getMenu().findItem(R.id.itemDisconnect).setVisible(false);
        for (int id : new int[]{R.id.moveToolbar, R.id.extraKeysToggle, R.id.actionShowKeyboard,
                R.id.actionScrollWheel, R.id.itemExtraKeys, R.id.itemScaling, R.id.itemEnterText,
                R.id.itemColorMode, R.id.itemCenterMouse, R.id.itemInfo, R.id.itemHelpInputMode,
                R.id.itemSendKeyAgain, R.id.itemSpecialKeys, R.id.itemCtrlAltDel}) popup.getMenu().findItem(id).setVisible(false);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.foldInputSettings) { showDesktopInputSettings(); return true; }
            return onOptionsItemSelected(item);
        });
        desktopTransientWindows++;
        popup.setOnDismissListener(ignored -> desktopTransientWindowClosed());
        popup.show();
    }

    static String desktopProfileKey(int width, int height) {
        return Math.min(width, height) + "x" + Math.max(width, height);
    }

    private String currentDesktopProfile() {
        return DesktopControlProfile.displayKey(this, canvasLayout, desktopProfile);
    }

    private void setDesktopScalePercent(int percent) {
        desktopScalePercent = Math.max(100, Math.min(300, percent));
        getSharedPreferences("desktop_keyboard", MODE_PRIVATE).edit()
                .putInt("scale_" + desktopProfile, desktopScalePercent).apply();
        scheduleDesktopResize();
    }

    private void setDesktopCursorSpeed(int slider) {
        int value = Math.max(0, Math.min(11, slider));
        if (Utils.isFoldable(this)) {
            getSharedPreferences("desktop_keyboard", MODE_PRIVATE).edit()
                    .putInt("cursorSpeed_" + currentDesktopProfile(), value).apply();
        } else {
            getSharedPreferences(Constants.generalSettingsTag, MODE_PRIVATE).edit()
                    .putInt(Constants.touchpadSensitivity, value).apply();
        }
        if (remoteConnection != null && remoteConnection.getPointer() != null)
            remoteConnection.getPointer().setSensitivity((value + 1) * .4f);
    }

    private void applyDesktopCursorSpeed() {
        if (remoteConnection != null && remoteConnection.getPointer() != null)
            remoteConnection.getPointer().setSensitivity(getTouchpadSensitivityMultiplier());
    }

    private void addDesktopSlider(LinearLayout panel, int max, int progress,
                                  java.util.function.IntFunction<String> label,
                                  java.util.function.IntConsumer changed) {
        TextView caption = new TextView(this);
        caption.setText(label.apply(progress)); caption.setTextSize(16);
        panel.addView(caption);
        SeekBar slider = new SeekBar(this);
        slider.setMax(max); slider.setProgress(progress); slider.setContentDescription(caption.getText());
        panel.addView(slider, new LinearLayout.LayoutParams(-1, Math.round(56 * getResources().getDisplayMetrics().density)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                caption.setText(label.apply(value)); bar.setContentDescription(caption.getText());
                if (fromUser) changed.accept(value);
            }
        });
    }

    private void showDesktopInputSettings() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        TextView screen = new TextView(this);
        screen.setText(getString(R.string.fold_screen_profile, desktopProfile.replace('x', '×')));
        panel.addView(screen);
        addDesktopSlider(panel, 8, (desktopScalePercent - 100) / 25,
                value -> getString(R.string.fold_windows_scale, 100 + value * 25),
                value -> setDesktopScalePercent(100 + value * 25));
        int heightPercent = Math.round(desktopKeyboard.getRowHeightDp() * 100f / DesktopKeyboardView.BASE_ROW_HEIGHT_DP);
        addDesktopSlider(panel, 110, Math.max(0, Math.min(110, heightPercent - 70)),
                value -> getString(R.string.fold_key_height, value + 70),
                value -> desktopKeyboard.setRowHeightDp(Math.round(DesktopKeyboardView.BASE_ROW_HEIGHT_DP * (value + 70) / 100f)));
        int speed = Utils.isFoldable(this) ? DesktopControlProfile.cursorSpeedStep(this, currentDesktopProfile())
                : com.iiordanov.bVNC.input.RemoteRdpPointer.savedSpeedStep(this);
        addDesktopSlider(panel, 11, speed,
                value -> getString(R.string.fold_cursor_speed, (value + 1) * 20), this::setDesktopCursorSpeed);
        TextView note = new TextView(this); note.setText(R.string.fold_input_settings_note); panel.addView(note);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.fold_input_settings).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).create();
        desktopInputDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (desktopInputDialog == dialog) desktopInputDialog = null;
            desktopTransientWindowClosed();
            if (canvas != null) canvas.requestFocus();
        });
        desktopTransientWindows++;
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    static int savedKeyboardMode(SharedPreferences preferences) {
        // In older versions 2 selected the built-in alphabetic keyboard.
        return preferences.getInt("desktopKeyboardMode", SYSTEM_KEYS) == FUNCTION_KEYS ? FUNCTION_KEYS : SYSTEM_KEYS;
    }

    private void selectDesktopKeyboard(int mode) {
        systemKeyboardSuspended = reopenSystemKeyboard = false;
        preferredDesktopKeyboardMode = mode;
        getPreferences(MODE_PRIVATE).edit().putInt("desktopKeyboardMode", mode).apply();
        desktopKeyboard.releaseKeys();
        desktopKeyboardMode = mode;
        desktopKeyboard.setFunctionsOnly(mode == FUNCTION_KEYS);
        canvas.requestFocus();
        waitingForSystemKeyboardHide = mode != SYSTEM_KEYS;
        desktopKeyboardHost.setVisibility(mode == SYSTEM_KEYS
                ? (softKeyboardUp ? View.VISIBLE : View.GONE)
                : (softKeyboardUp ? View.GONE : View.VISIBLE));
        if (mode == SYSTEM_KEYS) Utils.showKeyboard(this, canvas);
        else requestSystemKeyboardHide();
        layoutDesktop();
    }

    private void dismissDesktopKeyboardSurface() {
        desktopKeyboard.releaseKeys();
        desktopKeyboardMode = 0;
        systemKeyboardSuspended = reopenSystemKeyboard = false;
        waitingForSystemKeyboardHide = false;
        desktopKeyboardHost.setVisibility(View.GONE);
    }

    private void requestSystemKeyboardHide() {
        Utils.hideKeyboard(this, canvas);
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(canvas);
        if (controller != null) controller.hide(WindowInsetsCompat.Type.ime());
        ViewCompat.requestApplyInsets(canvasLayout);
    }

    private void updateDesktopIme(boolean visible, int height) {
        softKeyboardUp = visible;
        imeHeight = visible ? height : 0;
        if (desktopKeyboardMode == FUNCTION_KEYS) {
            if (visible && waitingForSystemKeyboardHide) desktopKeyboardHost.setVisibility(View.GONE);
            else if (visible) dismissDesktopKeyboardSurface();
            else if (!visible) {
                waitingForSystemKeyboardHide = false;
                desktopKeyboardHost.setVisibility(View.VISIBLE);
            }
        } else if (desktopKeyboardMode == SYSTEM_KEYS) {
            if (!visible && desktopKeyboardHost.getVisibility() == View.VISIBLE) desktopKeyboard.releaseKeys();
            desktopKeyboardHost.setVisibility(visible && !systemKeyboardSuspended ? View.VISIBLE : View.GONE);
        }
    }

    private void suspendSystemKeyboard() {
        if (systemKeyboardSuspended) return;
        systemKeyboardSuspended = true;
        reopenSystemKeyboard = softKeyboardUp;
        desktopKeyboard.releaseKeys();
        desktopKeyboardHost.setVisibility(View.GONE);
        layoutDesktop();
    }

    private void resumeSystemKeyboard() {
        if (!systemKeyboardSuspended) return;
        systemKeyboardSuspended = false;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(canvasLayout);
        if (insets != null) {
            updateDesktopIme(insets.isVisible(WindowInsetsCompat.Type.ime()),
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
        } else {
            desktopKeyboardHost.setVisibility(softKeyboardUp ? View.VISIBLE : View.GONE);
        }
        layoutDesktop();
        if (reopenSystemKeyboard) {
            canvas.requestFocus();
            Utils.showKeyboard(this, canvas);
            reopenSystemKeyboard = false;
        }
        ViewCompat.requestApplyInsets(canvasLayout);
    }

    private boolean desktopKeyboardOpen() {
        return desktopKeyboardMode == FUNCTION_KEYS || softKeyboardUp;
    }

    private void handleDesktopWindowFocus(boolean hasFocus, boolean multiWindow) {
        if (desktopDock == null || desktopKeyboard == null) return;
        if (desktopKeyboardMode == SYSTEM_KEYS) {
            if (hasFocus) resumeSystemKeyboard();
            else if (desktopTransientWindows == 0) suspendSystemKeyboard();
        } else if (!hasFocus && multiWindow && desktopTransientWindows == 0) {
            dismissDesktopKeyboardSurface();
            layoutDesktop();
        } else if (hasFocus && desktopKeyboardMode == FUNCTION_KEYS) {
            waitingForSystemKeyboardHide = true;
            // The IME owned by the previous app can only be dismissed after our window gains focus.
            canvas.post(() -> { if (desktopKeyboardMode == FUNCTION_KEYS && hasWindowFocus()) requestSystemKeyboardHide(); });
        }
    }

    private void desktopTransientWindowClosed() {
        desktopTransientWindows = Math.max(0, desktopTransientWindows - 1);
        canvasLayout.postDelayed(() -> handleDesktopWindowFocus(hasWindowFocus(),
                Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode()), 100);
    }

    private void toggleDesktopKeyboard() {
        if (!desktopKeyboardOpen() || desktopKeyboardMode == 0) selectDesktopKeyboard(preferredDesktopKeyboardMode);
        else hideKeyboard();
    }

    private void sendDesktopKey(int code, boolean down, int repeat) {
        if (remoteConnection.getKeyboard() == null) return;
        long now = SystemClock.uptimeMillis();
        remoteConnection.getKeyboard().keyEvent(code, new KeyEvent(now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, repeat));
    }

    public void sendDesktopText(String text) {
        if (remoteConnection.getKeyboard() == null) return;
        ((com.iiordanov.bVNC.input.RemoteRdpKeyboard)remoteConnection.getKeyboard()).sendCommittedText(text);
    }

    /**
     * Shows the extra-keys toolbar (if hidden) and navigates to the Send Text panel with an
     * animated slide from the modifier-keys page, hinting that a right swipe reaches it.
     */
    private void openSendTextPanel() {
        if (desktopDock != null) { showDialog(R.layout.entertext); return; }
        extraKeysHidden = false;
        setExtraKeysVisibility(View.VISIBLE, true);
        // Snap to the modifier-keys page (1) without animation so the subsequent animated
        // slide always travels one page to the right, demonstrating the swipe gesture.
        extraKeysToolbar.setCurrentItem(1, false);
        extraKeysToolbar.post(() -> extraKeysToolbar.setCurrentItem(0, true));
    }

    /** Updates the page indicator dots so the active page's dot is bright and the others are dim. */
    private void updatePageDots(int activePage) {
        if (pageIndicatorDots == null) return;
        for (int i = 0; i < pageIndicatorDots.length; i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(i == activePage ? 0xFFFFFFFF : 0x55FFFFFF);
            pageIndicatorDots[i].setBackground(dot);
        }
    }

    /**
     * On the very first launch, animates through all three pages so the user discovers the
     * swipe gestures. Runs only once, tracked in SharedPreferences.
     */
    private void maybeRunExtraKeysTour() {
        if (Utils.querySharedPreferenceBoolean(this, EXTRA_KEYS_TOUR_SHOWN, false)) {
            return;
        }
        Utils.setSharedPreferenceBoolean(this, EXTRA_KEYS_TOUR_SHOWN, true);
        // Sweep: modifier keys → F-keys → modifier keys → send text → modifier keys
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(2, true), 1000);
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(1, true), 2000);
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(0, true), 3000);
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(1, true), 4000);
    }

    /**
     * Resets the state of the on-screen special buttons.
     */
    private int resetOnScreenKeys(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return keyCode;
        }
        ExtraKeysView extraKeysView = extraKeysPagerAdapter != null ? extraKeysPagerAdapter.getExtraKeysView() : null;
        if (extraKeysView != null) {
            extraKeysView.resetSpecialButtons();
        }
        return keyCode;
    }

    /**
     * Sets the visibility of the extra keys appropriately.
     */
    private void setExtraKeysVisibility(int visibility, boolean forceVisible) {
        if (desktopDock != null) {
            extraKeysToolbar.setVisibility(View.GONE);
            extraKeysPageIndicator.setVisibility(View.GONE);
            return;
        }
        Configuration config = getResources().getConfiguration();

        boolean makeVisible = forceVisible;
        if (config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
            makeVisible = true;

        if (!extraKeysHidden && makeVisible &&
                connection.getExtraKeysToggleType() == Constants.EXTRA_KEYS_ON) {
            extraKeysToolbar.setVisibility(View.VISIBLE);
            if (extraKeysPageIndicator != null)
                extraKeysPageIndicator.setVisibility(View.VISIBLE);
            extraKeysToolbar.invalidate();
            maybeRunExtraKeysTour();
            return;
        }

        if (visibility == View.GONE) {
            extraKeysToolbar.setVisibility(View.GONE);
            if (extraKeysPageIndicator != null)
                extraKeysPageIndicator.setVisibility(View.GONE);
            extraKeysToolbar.invalidate();
        }
    }

    @Override
    protected void onPause() {
        DesktopWorkspace.paused(this);
        if (desktopKeyboard != null) desktopKeyboard.releaseKeys();
        if (desktopDock != null && remoteConnection != null && remoteConnection.getKeyboard() instanceof com.iiordanov.bVNC.input.RemoteRdpKeyboard)
            ((com.iiordanov.bVNC.input.RemoteRdpKeyboard)remoteConnection.getKeyboard()).releasePressedKeys();
        super.onPause();
        Log.i(TAG, "onPause called.");
        if (desktopDock != null) {
            if (desktopKeyboardMode == SYSTEM_KEYS) suspendSystemKeyboard();
            else if (Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode() && desktopTransientWindows == 0)
                dismissDesktopKeyboardSurface();
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(canvas.getWindowToken(), 0);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onPause");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Utils.isFoldable(this) && !waitingForWorkspaceUnlock) DesktopWorkspace.resumed(this);
        if (desktopKeyboardMode == SYSTEM_KEYS && hasWindowFocus()) resumeSystemKeyboard();
        Log.i(TAG, "onResume called.");
        try {
            canvas.postInvalidateDelayed(600);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onResume");
        }
    }

    /**
     * Set modes on start to match what is specified in the ConnectionBean;
     * color mode (already done) scaling, input mode
     */
    void setModes() {
        Log.d(TAG, "setModes");
        setInputHandler(getInputHandlerByName(connection.getInputMode()));
        if (desktopDock != null) {
            if (!(canvas.canvasZoomer instanceof PixelPerfectScaling)) {
                new PixelPerfectScaling().setScaleTypeForActivity(this);
            } else if (canvas.getZoomFactor() < 1f) {
                // The native framebuffer may have caught up with a smaller window.
                ((PixelPerfectScaling)canvas.canvasZoomer).transform(canvas, 1f, 0, 0, 0, 0);
            }
            layoutDesktop();
            scheduleDesktopResize();
        } else {
            AbstractScaling.getByScaleType(connection.getScaleMode()).setScaleTypeForActivity(this);
            initializeExtraKeysView();
        }
        try {
            COLORMODEL cm = COLORMODEL.valueOf(connection.getColorModel());
            remoteConnection.setColorModel(cm);
        } catch (NullPointerException | IllegalArgumentException e) {
            Log.w(TAG, "Could not set color model");
        }
        canvas.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.setFocusedByDefault(true);
        }
        canvas.requestFocus();
        canvas.setDrawingCacheEnabled(false);

        SamsungDexUtils.INSTANCE.dexMetaKeyCapture(this);
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onCreateDialog(int)
     */
    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == R.layout.entertext) {
            return new EnterTextDialog(this);
        } else if (id == R.id.itemHelpInputMode) {
            return createHelpDialog();
        }

        // Default to meta key dialog
        return new MetaKeyDialog(this);
    }

    /**
     * Creates the help dialog for this activity.
     */
    private Dialog createHelpDialog() {
        AlertDialog.Builder adb = new AlertDialog.Builder(this)
                .setMessage(R.string.input_mode_help_text)
                .setPositiveButton(R.string.close,
                        (dialog, whichButton) -> {
                            // We don't have to do anything.
                        });
        Dialog d = adb.setView(new ListView(this)).create();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        Window window = d.getWindow();
        if (window != null) {
            lp.copyFrom(window.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.show();
            window.setAttributes(lp);
        }
        return d;
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onPrepareDialog(int, android.app.Dialog)
     */
    @SuppressWarnings("deprecation")
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        if (dialog instanceof ConnectionSettable)
            ((ConnectionSettable) dialog).setConnection(connection);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        controlImmersive();
        if (desktopDock != null) {
            desktopKeyboard.releaseKeys();
            desktopKeyboard.setFunctionsOnly(desktopKeyboardMode == FUNCTION_KEYS);
            ViewCompat.requestApplyInsets(canvasLayout);
            canvasLayout.requestLayout();
            canvasLayout.post(this::layoutDesktop);
            return;
        }
        try {
            setExtraKeysVisibility(View.GONE, false);
            handler.postDelayed(rotationCorrector, 300);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onConfigurationChanged");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (desktopStopped) {
            desktopStopped = false;
            if (desktopDock != null) {
                layoutDesktop();
                scheduleDesktopResize();
            }
        }
        Log.i(TAG, "onStart called.");
        try {
            canvas.postInvalidateDelayed(800);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onStart");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (desktopDock != null) {
            desktopStopped = true;
            desktopResizeGeneration++;
            if (handler != null) {
                handler.removeCallbacks(desktopResize);
                handler.removeCallbacks(desktopResizeRetry);
                handler.removeCallbacks(desktopResizeTimeout);
            }
        }
        Log.i(TAG, "onStop called.");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.i(TAG, "onRestart called.");
        try {
            canvas.postInvalidateDelayed(1000);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onRestart");

        }
    }

    @Override
    public void onPanelClosed(int featureId, @NonNull Menu menu) {
        showActionBar();
        super.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (waitingForWorkspaceUnlock) return false;
        if (menu != null) {
            Log.i(TAG, "Menu opened, disabling hiding action bar");
            handler.removeCallbacks(actionBarHider);
            updateScalingMenu();
            updateInputMenu();
        }
        return super.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (waitingForWorkspaceUnlock) return false;
        // Make sure extra keys stow item is gone if extra keys are disabled and vice versa.
        setKeyStowDrawableAndVisibility(menu.findItem(R.id.extraKeysToggle));
        menu.findItem(R.id.itemColorMode).setVisible(remoteConnection.canUpdateColorModelConnected());
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (waitingForWorkspaceUnlock) return false;
        Log.d(TAG, "OnCreateOptionsMenu called");
        try {
            getMenuInflater().inflate(R.menu.canvasactivitymenu, menu);

            Menu inputMenu = menu.findItem(R.id.itemInputMode).getSubMenu();
            inputModeMenuItems = new MenuItem[inputModeIds.length];
            for (int i = 0; i < inputModeIds.length; i++) {
                if (inputMenu != null) {
                    inputModeMenuItems[i] = inputMenu.findItem(inputModeIds[i]);
                }
            }
            updateInputMenu();

            Menu scalingMenu = menu.findItem(R.id.itemScaling).getSubMenu();
            scalingModeMenuItems = new MenuItem[scalingModeIds.length];
            for (int i = 0; i < scalingModeIds.length; i++) {
                if (scalingMenu != null) {
                    scalingModeMenuItems[i] = scalingMenu.findItem(scalingModeIds[i]);
                }
            }
            updateScalingMenu();

            // Set the text of the Extra Keys menu item appropriately.
            // TODO: Implement for Opaque
            if (connection != null && connection.getExtraKeysToggleType() == Constants.EXTRA_KEYS_ON)
                menu.findItem(R.id.itemExtraKeys).setTitle(R.string.extra_keys_disable);
            else
                menu.findItem(R.id.itemExtraKeys).setTitle(R.string.extra_keys_enable);

            if (desktopDock != null) return true;
            toolbarMover = new OnTouchViewMover(toolbar, handler, toolbarPositionSaver, actionBarHider, hideToolbarDelay);
            ImageButton moveButton = new ImageButton(this);

            moveButton.setBackgroundResource(R.drawable.ic_all_out_gray_36dp);
            MenuItem moveToolbar = menu.findItem(R.id.moveToolbar);
            moveToolbar.setActionView(moveButton);
            moveButton.setOnTouchListener(toolbarMover);

            // Set up scroll wheel button
            MenuItem scrollWheelItem = menu.findItem(R.id.actionScrollWheel);
            if (scrollWheelItem != null) {
                ScrollWheelButton scrollWheelButton = new ScrollWheelButton(this);
                scrollWheelButton.setRemoteInput(remoteConnection);
                scrollWheelButton.setTouchInputDelegate(this);
                scrollWheelItem.setActionView(scrollWheelButton);
                Log.d(TAG, "ScrollWheelButton added to toolbar");
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "onCreateOptionsMenu - NullPointerException: " + Log.getStackTraceString(e));
        }
        Log.d(TAG, "OnCreateOptionsMenu complete");
        return true;
    }

    /**
     * Change the scaling mode sub-menu to reflect available scaling modes.
     */
    void updateScalingMenu() {
        try {
            for (MenuItem item : scalingModeMenuItems) {
                // If the entire framebuffer is NOT contained in the bitmap, fit-to-screen is meaningless.
                if (item.getItemId() == R.id.itemFitToScreen) {
                    item.setEnabled(canvas == null || canvas.myDrawable == null ||
                            (canvas.myDrawable.getBitmapHeight() == canvas.myDrawable.getFramebufferHeight() &&
                                    canvas.myDrawable.getBitmapWidth() == canvas.myDrawable.getFramebufferWidth()));
                } else {
                    item.setEnabled(true);
                }

                AbstractScaling scaling = AbstractScaling.getById(item.getItemId());
                if (scaling.scaleType == connection.getScaleMode()) {
                    item.setChecked(true);
                }
            }
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during updateScalingMenu");
        }
    }

    /**
     * Change the input mode sub-menu to reflect change in scaling
     */
    void updateInputMenu() {
        try {
            for (MenuItem item : inputModeMenuItems) {
                item.setEnabled(canvas.canvasZoomer.isValidInputMode(item.getItemId()));
                if (getInputHandlerById(item.getItemId()) == touchInputHandler)
                    item.setChecked(true);
            }
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during updateInputMenu");
        }
    }

    /**
     * If id represents an input handler, return that; otherwise return null
     */
    TouchInputHandler getInputHandlerById(int id) {
        myVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        if (inputModeHandlers == null) {
            inputModeHandlers = new TouchInputHandler[inputModeIds.length];
        }
        float scrollRate = getScrollRate();
        remoteConnection.getPointer().setSensitivity(getTouchpadSensitivityMultiplier());
        for (int i = 0; i < inputModeIds.length; ++i) {
            if (inputModeIds[i] == id) {
                if (inputModeHandlers[i] == null) {
                    if (id == R.id.itemInputTouchPanZoomMouse) {
                        inputModeHandlers[i] = new TouchInputHandlerDirectSwipePan(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else if (id == R.id.itemInputDragPanZoomMouse) {
                        inputModeHandlers[i] = new TouchInputHandlerDirectDragPan(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else if (id == R.id.itemInputTouchpad) {
                        inputModeHandlers[i] = Utils.isFoldable(this)
                                ? new com.iiordanov.bVNC.input.DesktopTouchpad(this, canvas, remoteConnection, App.debugLog, scrollRate)
                                : new TouchInputHandlerTouchpad(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else if (id == R.id.itemInputSingleHanded) {
                        inputModeHandlers[i] = new TouchInputHandlerSingleHanded(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else {
                        throw new IllegalStateException("Unexpected value: " + id);
                    }
                }
                return inputModeHandlers[i];
            }
        }
        return null;
    }

    TouchInputHandler getInputHandlerByName(String name) {
        TouchInputHandler result = null;
        for (int id : inputModeIds) {
            TouchInputHandler handler = getInputHandlerById(id);
            if (handler.getId().equals(name)) {
                result = handler;
                break;
            }
        }
        if (result == null) {
            result = getInputHandlerById(R.id.itemInputTouchPanZoomMouse);
        }
        return result;
    }

    int getModeIdFromHandler(TouchInputHandler handler) {
        for (int id : inputModeIds) {
            if (handler == getInputHandlerById(id))
                return id;
        }
        return R.id.itemInputTouchPanZoomMouse;
    }

    float getScrollRate() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.scrollSpeed, Constants.DEFAULT_SCROLL_SPEED);
        return (slider + 1) / 7f;
    }

    float getTouchpadSensitivityMultiplier() {
        if (Utils.isFoldable(this))
            return (DesktopControlProfile.cursorSpeedStep(this, currentDesktopProfile()) + 1) * .4f;
        if (desktopDock != null) return com.iiordanov.bVNC.input.RemoteRdpPointer.savedSensitivity(this);
        int slider = Utils.querySharedPreferencesInt(this, Constants.touchpadSensitivity, Constants.DEFAULT_TOUCHPAD_SENSITIVITY);
        return (slider + 1) * 0.4f;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        RemoteKeyboard k = remoteConnection.getKeyboard();
        if (k != null) {
            k.setAfterMenu(true);
        }
        int itemId = item.getItemId();
        if (itemId == R.id.itemInfo) {
            remoteConnection.showConnectionInfo();
            return true;
        } else if (itemId == R.id.itemSpecialKeys) {
            showDialog(R.layout.metakey);
            return true;
        } else if (itemId == R.id.itemColorMode) {
            selectColorModel();
            return true;
            // Following sets one of the scaling options
        } else if (itemId == R.id.itemZoomable || itemId == R.id.itemOneToOne || itemId == R.id.itemFitToScreen) {
            AbstractScaling.getById(item.getItemId()).setScaleTypeForActivity(this);
            item.setChecked(true);
            showPanningState(false);
            return true;
        } else if (itemId == R.id.itemCenterMouse) {
            remoteConnection.getPointer().movePointer(canvas.absoluteXPosition + canvas.getVisibleDesktopWidth() / 2,
                    canvas.absoluteYPosition + canvas.getVisibleDesktopHeight() / 2);
            return true;
        } else if (itemId == R.id.itemDisconnect) {
            remoteConnection.disconnectAndFinish();
            return true;
        } else if (itemId == R.id.itemEnterText) {
            openSendTextPanel();
            return true;
        } else if (itemId == R.id.itemCtrlAltDel) {
            remoteConnection.getKeyboard().sendMetaKey(MetaKeyBean.keyCtrlAltDel);
            return true;
        } else if (itemId == R.id.itemSendKeyAgain) {
            sendSpecialKeyAgain();
            return true;
        } else if (itemId == R.id.itemExtraKeys) {
            if (connection.getExtraKeysToggleType() == Constants.EXTRA_KEYS_ON) {
                connection.setExtraKeysToggleType(Constants.EXTRA_KEYS_OFF);
                item.setTitle(R.string.extra_keys_enable);
                setExtraKeysVisibility(View.GONE, false);
            } else {
                connection.setExtraKeysToggleType(Constants.EXTRA_KEYS_ON);
                item.setTitle(R.string.extra_keys_disable);
                setExtraKeysVisibility(View.VISIBLE, false);
                extraKeysHidden = false;
            }
            invalidateOptionsMenu();
            connection.save(this);
            return true;
        } else if (itemId == R.id.itemHelpInputMode) {
            showDialog(R.id.itemHelpInputMode);
            return true;
        } else {
            boolean inputModeSet = setInputMode(item.getItemId());
            item.setChecked(inputModeSet);
            if (inputModeSet) {
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    public boolean setInputMode(int id) {
        TouchInputHandler input = getInputHandlerById(id);
        if (input != null) {
            setInputHandler(input);
            connection.setInputMode(input.getId());
            if (input.getId().equals(TouchInputHandlerTouchpad.ID)) {
                connection.setFollowMouse(true);
                connection.setFollowPan(true);
            } else {
                connection.setFollowMouse(false);
                connection.setFollowPan(false);
                remoteConnection.getPointer().setRelativeEvents(false);
            }

            showPanningState(true);
            connection.save(this);
            if (recordedDesktopSession) rememberDesktopOptions();
            return true;
        }
        return false;
    }

    private void setInputHandler(TouchInputHandler input) {
        touchInputHandler = input;
        inputListener = new RemoteClientsInputListener(
                this,
                remoteConnection,
                remoteConnection,
                touchInputHandler,
                this::resetOnScreenKeys,
                connection.getUseDpadAsArrows()
        );
        canvas.setOnKeyListener(inputListener);
    }

    private void sendSpecialKeyAgain() {
        ArrayList<MetaKeyBean> keys = new ArrayList<>();
        Database.runReadable(this, db -> tryPopulateKeysInListWhereFieldMatchesValue(
                db, keys, MetaKeyBean.GEN_FIELD__ID, connection.getLastMetaKeyId(), false
        ));
        if (!keys.isEmpty()) {
            remoteConnection.getKeyboard().sendMetaKey(keys.get(0));
        }
    }

    @Override
    protected void onDestroy() {
        DesktopSessions.remove(this);
        if (Utils.isFoldable(this)) DesktopWorkspace.destroyed(this);
        if (displayDriverErrorDialog != null) displayDriverErrorDialog.dismiss();
        if (remoteConnection != null && remoteConnection.pd != null && remoteConnection.pd.isShowing())
            remoteConnection.pd.dismiss();
        if (desktopKeyboard != null) desktopKeyboard.releaseKeys();
        if (handler != null) {
            handler.removeCallbacks(reconnectCheck);
            handler.removeCallbacks(desktopResize);
            handler.removeCallbacks(desktopResizeRetry);
            handler.removeCallbacks(desktopResizeTimeout);
        }
        super.onDestroy();
        Log.i(TAG, "onDestroy called.");
        if (remoteConnection != null)
            remoteConnection.closeConnection();
        System.gc();
    }

    public void showPanningState(boolean showLonger) {
        if (showLonger) {
            final Snackbar t = Utils.makeMessage(canvas, touchInputHandler.getDescription(), Snackbar.LENGTH_LONG);
            TimerTask tt = new TimerTask() {
                @Override
                public void run() {
                    t.show();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Ignored InterruptedException during showPanningState");
                    }
                    t.show();
                }
            };
            new Timer().schedule(tt, 2000);
            t.show();
        } else {
            Utils.showMessage(canvas, touchInputHandler.getDescription(), Snackbar.LENGTH_SHORT);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onTrackballEvent(android.view.MotionEvent)
     */
    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        boolean consumed = false;
        if (inputListener != null) {
            consumed = inputListener.onTrackballEvent(event);
        }
        if (!consumed) {
            consumed = super.onTrackballEvent(event);
        }
        return consumed;
    }

    private boolean isControllerMotion(MotionEvent event) {
        if (desktopDock == null) return false;
        if (event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_DPAD)
                || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return true;
        InputDevice device = event.getDevice();
        if (device == null) return false;
        // A DualSense touch surface can have its own Android mouse or touch device ID.
        return device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)
                || (device.getVendorId() == 0x054c && (device.getProductId() == 0x0ce6 || device.getProductId() == 0x0df2));
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return isControllerMotion(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return isControllerMotion(event) || super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchTrackballEvent(MotionEvent event) {
        return isControllerMotion(event) || super.dispatchTrackballEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (desktopDock != null && handleDesktopGesture(event)) return true;
        boolean consumed = false;
        if (inputListener != null) {
            consumed = inputListener.onTouchEvent(event);
        }
        if (!consumed) {
            consumed = super.onTrackballEvent(event);
        }
        return consumed;
    }

    private boolean handleDesktopGesture(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean consumed = action != MotionEvent.ACTION_DOWN && desktopGestureActive;
            desktopGestureActive = desktopPinching = desktopWheel = false;
            gestureSpan = 0;
            return consumed;
        }
        if (!(canvas.canvasZoomer instanceof PixelPerfectScaling)) return false;
        if (event.getPointerCount() != 2 || action == MotionEvent.ACTION_POINTER_UP) {
            gestureSpan = 0;
            return desktopGestureActive;
        }
        float x = (event.getX(0) + event.getX(1)) / 2 - xPointerOffset;
        float y = (event.getY(0) + event.getY(1)) / 2 - yPointerOffset;
        float span = (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            gestureSpan = Math.max(1f, span);
            gestureStartX0 = event.getX(0);
            gestureStartY0 = event.getY(0);
            gestureStartX1 = event.getX(1);
            gestureStartY1 = event.getY(1);
            gestureFocusX = x;
            gestureFocusY = y;
            desktopPinching = false;
            desktopWheel = false;
            wheelRemainder = 0;
            return desktopGestureActive;
        }
        if (gestureSpan == 0 || action != MotionEvent.ACTION_MOVE) return desktopGestureActive;
        int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        double focusTravel = Math.hypot(x - gestureFocusX, y - gestureFocusY);
        if (!desktopWheel) {
            double spread = Math.abs(span - gestureSpan);
            boolean touchpad = touchInputHandler instanceof com.iiordanov.bVNC.input.DesktopTouchpad;
            // Two parallel fingers rarely keep an exact separation. Require a deliberate pinch.
            if (touchpad && !desktopGestureActive) {
                double first = Math.hypot(event.getX(0) - gestureStartX0, event.getY(0) - gestureStartY0);
                double second = Math.hypot(event.getX(1) - gestureStartX1, event.getY(1) - gestureStartY1);
                boolean anchored = Math.min(first, second) < slop;
                double minimumSpread = anchored ? Math.max(5 * slop, gestureSpan * .30)
                        : Math.max(3 * slop, gestureSpan * .16);
                desktopPinching = spread > Math.max(minimumSpread, (anchored ? 1.6 : 3) * focusTravel);
                if (!desktopPinching && (anchored || spread > 2 * focusTravel)) return true;
            } else if (!touchpad) {
                desktopPinching |= spread > Math.max(slop, focusTravel);
            }
        }
        if (!desktopGestureActive && !desktopPinching) {
            if (focusTravel <= slop) return true;
            desktopWheel = touchInputHandler instanceof com.iiordanov.bVNC.input.DesktopTouchpad;
            horizontalWheel = Math.abs(x - gestureFocusX) > Math.abs(y - gestureFocusY);
            if (!desktopWheel && canvas.getAbsY() == 0 && canvas.getImageHeight() <= canvas.getVisibleDesktopHeight()
                    && canvas.getImageWidth() <= canvas.getVisibleDesktopWidth()) {
                gestureSpan = 0;
                return false; // Parallel swipe on a fully visible desktop scrolls in Windows.
            }
        }
        if (!desktopGestureActive && inputListener != null) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            inputListener.onTouchEvent(cancel);
            cancel.recycle();
        }
        desktopGestureActive = true;
        if (desktopWheel) {
            wheelRemainder = ((com.iiordanov.bVNC.input.DesktopTouchpad)touchInputHandler)
                    .scrollWheel(wheelRemainder + (horizontalWheel ? x - gestureFocusX : y - gestureFocusY),
                            horizontalWheel, event.getMetaState());
        } else if (desktopPinching) {
            ((PixelPerfectScaling)canvas.canvasZoomer).transform(canvas, span / gestureSpan,
                    gestureFocusX, gestureFocusY, x, y);
            gestureSpan = Math.max(1f, span);
        } else {
            canvas.relativePan(gestureFocusX - x, gestureFocusY - y);
        }
        gestureFocusX = x;
        gestureFocusY = y;
        return true;
    }

    // Send e.g. mouse events like hover and scroll to be handled.
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean consumed = false;
        if (inputListener != null) {
            consumed = inputListener.onGenericMotionEvent(event);
        }
        if (!consumed) {
            consumed = super.onGenericMotionEvent(event);
        }
        return consumed;
    }

    private void selectColorModel() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ListView list = getColorsListView(dialog);
        dialog.setContentView(list);
        dialog.show();
    }

    @NonNull
    private ListView getColorsListView(Dialog dialog) {
        String[] choices = new String[COLORMODEL.values().length];
        int currentSelection = -1;
        for (int i = 0; i < choices.length; i++) {
            COLORMODEL cm = COLORMODEL.values()[i];
            choices[i] = cm.toString();
            if (remoteConnection.isColorModel(cm))
                currentSelection = i;
        }
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_checked, choices));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setItemChecked(currentSelection, true);
        list.setOnItemClickListener((arg0, arg1, arg2, arg3) -> {
            dialog.dismiss();
            COLORMODEL cm = COLORMODEL.values()[arg2];
            remoteConnection.setColorModel(cm);
            connection.setColorModel(cm.nameString());
            connection.save(RemoteCanvasActivity.this);
            Utils.showMessage(canvas, getString(R.string.info_update_color_model_to) + cm, Snackbar.LENGTH_LONG);
        });
        return list;
    }

    public void showActionBar() {
        if (desktopDock != null) return;
        handler.removeCallbacks(actionBarShower);
        handler.postAtTime(actionBarShower, SystemClock.uptimeMillis() + 50);
        handler.removeCallbacks(actionBarHider);
        handler.postAtTime(actionBarHider, SystemClock.uptimeMillis() + hideToolbarDelay);
    }

    public void showKeyboardIcon() {
        handler.removeCallbacks(keyboardIconShower);
        handler.postAtTime(keyboardIconShower, SystemClock.uptimeMillis() + 50);
        handler.removeCallbacks(actionBarHider);
        handler.postAtTime(actionBarHider, SystemClock.uptimeMillis() + hideToolbarDelay);
    }

    @Override
    public void onTextSelected(String selectedString) {
        Log.i(TAG, "onTextSelected called with selectedString: " + selectedString);
        remoteConnection.pd.show();
        connection.setVmname(remoteConnection.vmNameToId.get(selectedString));
        connection.save(this);
        synchronized (remoteConnection.getRfbConn()) {
            remoteConnection.getRfbConn().notify();
        }
    }

    public void toggleKeyboard(MenuItem menuItem) {
        if (softKeyboardUp) {
            hideKeyboard();
        } else {
            showKeyboard();
        }
    }

    public void showKeyboard() {
        if (desktopDock != null) { selectDesktopKeyboard(SYSTEM_KEYS); return; }
        Log.i(TAG, "Showing keyboard and hiding action bar");
        canvas.requestFocus();
        Utils.showKeyboard(this, canvas);
        softKeyboardUp = true;
        Objects.requireNonNull(getSupportActionBar()).hide();
    }

    public void hideKeyboard() {
        if (desktopDock != null) {
            dismissDesktopKeyboardSurface();
            requestSystemKeyboardHide();
            layoutDesktop();
            return;
        }
        Log.i(TAG, "Hiding keyboard and hiding action bar");
        canvas.requestFocus();
        Utils.hideKeyboard(this, getCurrentFocus());
        softKeyboardUp = false;
        Objects.requireNonNull(getSupportActionBar()).hide();
    }

    public void hideKeyboardAndExtraKeys() {
        hideKeyboard();
        if (extraKeysToolbar.getVisibility() == View.VISIBLE) {
            extraKeysHidden = true;
            setExtraKeysVisibility(View.GONE, false);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public RemoteCanvas getCanvas() {
        return canvas;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Utils.isFoldable(this)) {
            DesktopWorkspace.save(outState);
            outState.putBoolean(DesktopWorkspace.UNLOCK, waitingForWorkspaceUnlock);
        }
        outState.putBoolean(SYSTEM_KEYBOARD_OPEN, restoreKeyboardOnConnect || (desktopKeyboardMode == SYSTEM_KEYS
                && (systemKeyboardSuspended ? reopenSystemKeyboard : softKeyboardUp)));
        outState.putBoolean(WAITING_FOR_PHONE_DRIVE, waitingForPhoneDrive);
        outState.putString("WORKAROUND_FOR_BUG_19917_KEY", "WORKAROUND_FOR_BUG_19917_VALUE");
        super.onSaveInstanceState(outState);
    }

    private boolean isMasterPasswordEnabled() {
        SharedPreferences sp = getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
        return sp.getBoolean(Constants.masterPasswordEnabledTag, false);
    }

    @Override public void finish() {
        if (Utils.isFoldable(this)) DesktopWorkspace.closingDesktop(this);
        super.finish();
    }

    @Override public void finishAndRemoveTask() {
        if (Utils.isFoldable(this)) DesktopWorkspace.closingDesktop(this);
        super.finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        if (waitingForWorkspaceUnlock) {
            DesktopWorkspace.dismiss(this);
            return;
        }
        if (desktopDock != null && desktopKeyboardOpen()) {
            hideKeyboard();
            return;
        }
        if (GeneralUtils.isTv(this)) {
            remoteConnection.disconnectAndFinish();
            super.onBackPressed();
        }
        if (inputListener != null) {
            inputListener.onKey(canvas, KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        }
    }

    public RemoteConnection getRemoteConnection() {
        return remoteConnection;
    }

    public Handler getHandler() { return handler; }

    private class ActionBarPositionSaver implements Runnable {
        public void run() {
            connection.setUseLastPositionToolbarX(toolbarMover.getLastX());
            connection.setUseLastPositionToolbarY(toolbarMover.getLastY());
            connection.setUseLastPositionToolbarMoved(true);
            connection.save(RemoteCanvasActivity.this);
        }
    }

    private class ActionBarHider implements Runnable {
        public void run() {
            if (GeneralUtils.isTv(RemoteCanvasActivity.this)) {
                keyboardIconForAndroidTv.setVisibility(View.GONE);
            } else {
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null) {
                    Log.d(TAG, "ActionBarHider: Hiding ActionBar");
                    actionBar.hide();
                }
            }
        }
    }

    private class ActionBarShower implements Runnable {
        public void run() {
            showActionBar();
        }

        private void showActionBar() {
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                Log.d(TAG, "ActionBarShower: Showing ActionBar");
                actionBar.show();
            }
        }
    }

    private class KeyboardIconShower implements Runnable {
        public void run() {
            if (GeneralUtils.isTv(RemoteCanvasActivity.this)) {
                animateKeyboardIconForAndroidTv();
            }
        }

        private void animateKeyboardIconForAndroidTv() {
            keyboardIconForAndroidTv.setVisibility(View.VISIBLE);
            Log.d(TAG, "ActionBarHider: keyboardIconForAndroidTv X position to: " + keyboardIconForAndroidTvX);
            keyboardIconForAndroidTv.setX(keyboardIconForAndroidTvX);
            ObjectAnimator animation = ObjectAnimator.ofFloat(keyboardIconForAndroidTv, "translationX", -100f);
            animation.setDuration(1000);
            animation.start();
        }
    }
}
