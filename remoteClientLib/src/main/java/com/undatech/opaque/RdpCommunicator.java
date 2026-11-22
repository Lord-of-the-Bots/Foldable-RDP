package com.undatech.opaque;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ManualBookmark;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.undatech.opaque.input.RdpKeyboardMapper;
import com.undatech.opaque.input.RemoteKeyboard;
import com.undatech.opaque.input.RemotePointer;
import com.undatech.opaque.util.GeneralUtils;

import org.apache.commons.validator.routines.InetAddressValidator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RdpCommunicator extends RfbConnectable implements RdpKeyboardMapper.KeyProcessingListener,
        LibFreeRDP.UIEventListener, LibFreeRDP.EventListener {
    static final String TAG = "RdpCommunicator";

    // private final static int VK_CONTROL = 0x11;
    private final static int VK_LCONTROL = 0xA2;
    private final static int VK_RCONTROL = 0xA3;
    private final static int VK_LMENU = 0xA4;
    private final static int VK_RMENU = 0xA5;
    private final static int VK_LSHIFT = 0xA0;
    private final static int VK_RSHIFT = 0xA1;
    private final static int VK_LWIN = 0x5B;
    private final static int VK_RWIN = 0x5C;
    private final static int VK_EXT_KEY = 0x00000100;
    private final RdpCommunicator myself;
    private final Viewable viewable;
    private volatile SessionState session;
    private long handledDisconnect = -1;
    private final BookmarkBase bookmark;
    private final Context context;
    private volatile boolean isInNormalProtocol = false;
    // This variable indicates whether or not the user has accepted an untrusted
    // security certificate. Used to control progress while the dialog asking the user
    // to confirm the authenticity of a certificate is displayed.
    private boolean certificateAccepted = false;
    private boolean reattemptWithoutCredentials = true;
    private boolean authenticationAttempted = false;
    private boolean gatewayAuthenticationAttempted = false;
    private volatile boolean disconnectRequested = false;
    private boolean connectStarted;
    private boolean networkRecoveryEnabled;
    private volatile RdpClipboard clipboard;
    private boolean ending;
    private int disconnectReasonBeforeStop;
    private long lastLayoutBeforeStop, errorCodesBeforeStop, layoutRequest;
    private int initialWidth, initialHeight;
    private int certificateRequest;

    public void enableNetworkRecovery() {
        networkRecoveryEnabled = true;
    }

    public boolean enableSessionClipboard(android.content.SharedPreferences preferences, String copyKey, String pasteKey) {
        String computer = connection.getNickname();
        if (computer == null || computer.trim().isEmpty()) computer = connection.getAddress();
        clipboard = RdpClipboard.create(context, session.getInstance(), preferences, copyKey, pasteKey, computer);
        if (clipboard != null) clipboard.focus(viewable.isForegrounded());
        return clipboard != null;
    }

    public void setClipboardFocus(boolean focused) {
        RdpClipboard clip = clipboard;
        if (clip != null) clip.focus(focused);
    }
    private int desktopScaleFactor = 100;

    public void setDesktopScaleFactor(int percent) {
        desktopScaleFactor = Math.max(100, Math.min(500, percent));
    }
    private final String username, password, domain;
    public void setPhoneDriveEnabled(boolean enabled) {
        bookmark.getAdvancedSettings().setPhoneDrive(enabled);
    }

    private final Connection connection;

    ExecutorService inputExecutor = Executors.newSingleThreadExecutor(
            r -> {
                Thread thread = new Thread(r);
                thread.setName("SendRdpInputThread");
                thread.setPriority(Thread.MAX_PRIORITY);
                return thread;
            }
    );

    public RdpCommunicator(Connection connection, Context context, Handler handler, Viewable viewable,
                           String rdpFileName, String username, String domain, String password,
                           boolean debugLogging, boolean isRemoteToLocalClipboardIntegrationEnabled) {
        super(debugLogging, handler, isRemoteToLocalClipboardIntegrationEnabled);
        this.connection = connection;
        // Create a manual bookmark and populate it from settings.
        this.bookmark = new ManualBookmark();
        this.context = context;
        this.viewable = viewable;
        this.myself = this;
        this.username = username == null ? "" : username;
        this.domain = domain == null ? "" : domain;
        this.password = password == null ? "" : password;
        modifierMap.put(RemoteKeyboard.CTRL_MASK, VK_LCONTROL);
        modifierMap.put(RemoteKeyboard.RCTRL_MASK, (VK_RCONTROL | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.ALT_MASK, VK_LMENU);
        modifierMap.put(RemoteKeyboard.RALT_MASK, (VK_RMENU | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.SUPER_MASK, (VK_LWIN | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.RSUPER_MASK, (VK_RWIN | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.SHIFT_MASK, VK_LSHIFT);
        modifierMap.put(RemoteKeyboard.RSHIFT_MASK, VK_RSHIFT);
        initSession(rdpFileName, this.username, this.domain, this.password);
    }

    @Override
    public void setIsInNormalProtocol(boolean state) {
        Log.d(TAG, "setIsInNormalProtocol: " + state);
        isInNormalProtocol = state;
    }

    @Override
    public int framebufferWidth() {
        return session.getBookmark().getActiveScreenSettings().getWidth();
    }

    @Override
    public int framebufferHeight() {
        return session.getBookmark().getActiveScreenSettings().getHeight();
    }

    @Override
    public String desktopName() {
        return ((ManualBookmark) session.getBookmark()).getHostname();
    }

    @Override
    public void requestUpdate(boolean incremental) {
        // NOT USED for RDP.
    }

    @Override
    public synchronized void writeClientCutText(String text) {
        if (!isActiveSession(session.getInstance())) return;
        if (clipboard != null) clipboard.localText(text);
        else LibFreeRDP.sendClipboardData(session.getInstance(), text);
    }

    @Override
    public boolean isInNormalProtocol() {
        return isInNormalProtocol;
    }

    @Override
    public String getEncoding() {
        return "RDP";
    }

    @Override
    public synchronized void writePointerEvent(int x, int y, int metaState, int pointerMask, boolean rel) {
        if (!isActiveSession(session.getInstance())) return;
        this.metaState = metaState;
        boolean down = (pointerMask & RemotePointer.POINTER_DOWN_MASK) != 0;
        boolean wheel = (pointerMask & 0x0600) != 0;
        if ((pointerMask & 0x0400) != 0 && !RdpDisplayControl.hasHorizontalWheel(session.getInstance())) {
            // Browsers also accept Shift + vertical wheel. Wheel-down scrolls right.
            this.metaState |= RemoteKeyboard.SHIFT_MASK;
            pointerMask = (pointerMask & 0x0100) == 0 ? 0x0388 : 0x0278;
        }
        if (down || wheel) {
            sendModifierKeys(true);
        }
        sendRemoteMouseEventOnNewThread(x, y, pointerMask);
        if (!down) {
            sendModifierKeys(false);
        }
        this.metaState = metaState;
    }

    private void sendRemoteMouseEventOnNewThread(int x, int y, int pointerMask) {
        runMethodOnInputThread(() -> sendRemoteMouseEvent(x, y, pointerMask));
    }

    private synchronized void sendRemoteMouseEvent(int x, int y, int pointerMask) {
        sleepBetweenInputEvents(3);
        LibFreeRDP.sendCursorEvent(session.getInstance(), x, y, pointerMask);
    }


    @Override
    public synchronized void writeKeyEvent(int key, int metaState, boolean down) {
        // Not used for actually sending keyboard events, but rather to record the current metastate.
        // The key event is sent to the KeyboardMapper from RemoteRdpKeyboard, and
        // when processed through the keyboard mapper, it ends up in one of the KeyProcessingListener
        // methods defined here.
        GeneralUtils.debugLog(this.debugLogging, TAG, "writeKeyEvent: setting metaState to: " + metaState);
        this.metaState = metaState;
    }

    @Override
    public void writeSetPixelFormat(int bitsPerPixel, int depth,
                                    boolean bigEndian, boolean trueColour, int redMax, int greenMax,
                                    int blueMax, int redShift, int greenShift, int blueShift,
                                    boolean fGreyScale) {
        // NOT USED for RDP.
    }

    @Override
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h,
                                              boolean b) {
        // NOT USED for RDP.
    }

    @Override
    public synchronized void close() {
        if (disconnectRequested) return;
        setIsInNormalProtocol(false);
        disconnectRequested = true;
        inputExecutor.shutdownNow();
        notifyAll();
        long instance = session.getInstance();
        if (handledDisconnect == instance) return;
        if (!connectStarted) {
            freeUnstarted(instance);
            return;
        }
        // This JNI call queues an event and signals abort; the native thread does the teardown.
        LibFreeRDP.stopStartedInstance(instance);
    }

    @Override
    public synchronized boolean isCertificateAccepted() {
        return certificateAccepted;
    }

    @Override
    public synchronized void setCertificateAccepted(boolean certificateAccepted) {
        this.certificateAccepted = certificateAccepted;
        notifyAll();
    }

    public synchronized boolean awaitingCertificate(int request) {
        return request == certificateRequest && !disconnectRequested && !ending && !certificateAccepted;
    }

    public synchronized void acceptCertificate(int request) {
        if (awaitingCertificate(request)) setCertificateAccepted(true);
    }

    private void sendModifierKeys(boolean down) {
        for (int modifierMask : modifierMap.keySet()) {
            if (remoteKeyboardState.shouldSendModifier(metaState, modifierMask, down)) {
                Integer modifier = modifierMap.get(modifierMask);
                if (modifier != null) {
                    GeneralUtils.debugLog(this.debugLogging, TAG, "sendModifierKeys, modifierMask:" +
                            modifierMask + ", sending: " + modifier + ", down: " + down);
                    sendKeyEventOnNewThread(modifier, down);
                    remoteKeyboardState.updateRemoteMetaState(modifierMask, down);
                }
            }
        }
    }

    // ****************************************************************************
    // KeyboardMapper.KeyProcessingListener implementation
    @Override
    public void processVirtualKey(int virtualKeyCode, boolean down) {
        GeneralUtils.debugLog(this.debugLogging, TAG, "processVirtualKey: " +
                "Processing VK key: " + virtualKeyCode + ". Is it down: " + down);

        if (down) {
            sendModifierKeys(true);
        }
        sendKeyEventOnNewThread(virtualKeyCode, down);

        if (!down) {
            sendModifierKeys(false);
        }
    }

    private void sendKeyEventOnNewThread(int virtualKeyCode, boolean down) {
        RdpClipboard clip = clipboard;
        runMethodOnInputThread(() -> sendKeyEvent(virtualKeyCode, down),
                clip == null ? null : () -> clip.beforeKey(virtualKeyCode, down));
    }

    private synchronized void sendKeyEvent(int virtualKeyCode, boolean down) {
        sleepBetweenInputEvents(5);
        LibFreeRDP.sendKeyEvent(session.getInstance(), virtualKeyCode, down);
    }

    @Override
    public void processUnicodeKey(int unicodeKey, boolean down, boolean suppressMetaState) {
        GeneralUtils.debugLog(this.debugLogging, TAG, "processUnicodeKey: " +
                "Processing unicode key: " + unicodeKey + ", down: " + down +
                ", metaState: " + metaState + ", suppressMetaState: " + suppressMetaState);
        if (down && !suppressMetaState) {
            sendModifierKeys(true);
        }
        sendUnicodeKeyOnNewThread(unicodeKey, down);
        if (!down && !suppressMetaState) {
            sendModifierKeys(false);
        }
    }

    private void sendUnicodeKeyOnNewThread(int unicodeKey, boolean down) {
        runMethodOnInputThread(() -> sendUnicodeKey(unicodeKey, down));
    }

    private void runMethodOnInputThread(Runnable runnable) {
        runMethodOnInputThread(runnable, null);
    }

    private synchronized void runMethodOnInputThread(Runnable runnable, Runnable beforeSend) {
        if (disconnectRequested || session == null) return;
        long instance = session.getInstance();
        inputExecutor.submit(() -> {
            if (beforeSend != null) beforeSend.run();
            synchronized (this) {
                if (!Thread.currentThread().isInterrupted() && isActiveSession(instance)) runnable.run();
            }
        });
    }

    private synchronized void sendUnicodeKey(int unicodeKey, boolean down) {
        sleepBetweenInputEvents(5);
        LibFreeRDP.sendUnicodeKeyEvent(session.getInstance(), unicodeKey, down);
    }

    private static void sleepBetweenInputEvents(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void switchKeyboard(int keyboardType) {
        // This is functionality specific to aFreeRDP.
    }

    @Override
    public void modifiersChanged() {
        // This is functionality specific to aFreeRDP.
    }

    @Override
    public void requestResolution(int x, int y) {
        requestDesktopLayout(x, y, desktopScaleFactor, null);
    }

    public synchronized void requestDesktopLayout(int x, int y, int scale, java.util.function.Consumer<Boolean> completion) {
        if (session == null || disconnectRequested) {
            if (completion != null) handler.post(() -> completion.accept(false));
            return;
        }
        long instance = session.getInstance();
        long request = ++layoutRequest;
        long layout = ((long)(x & ~1) << 32) | ((long)y << 16) | scale;
        runMethodOnInputThread(() -> {
            if (request != layoutRequest) return;
            boolean accepted = RdpDisplayControl.resize(instance, x, y, scale);
            if (!accepted) Log.w(TAG, "Display Control did not accept requested size " + x + "x" + y);
            if (completion == null) return;
            if (!accepted) handler.post(() -> completion.accept(false));
            else {
                long deadline = SystemClock.uptimeMillis() + 6000;
                handler.post(() -> awaitLayoutWrite(instance, request, layout, deadline, completion));
            }
        });
    }

    private synchronized void awaitLayoutWrite(long instance, long request, long layout, long deadline,
                                               java.util.function.Consumer<Boolean> completion) {
        if (request != layoutRequest || !isActiveSession(instance)) return;
        int status = RdpDisplayControl.status(instance);
        if (status == 2 && RdpDisplayControl.lastSentLayout(instance) == layout) completion.accept(true);
        else if (status < 0 || SystemClock.uptimeMillis() >= deadline) completion.accept(false);
        else handler.postDelayed(() -> awaitLayoutWrite(instance, request, layout, deadline, completion), 180);
    }

    public synchronized int getDisplayControlStatus() {
        return session == null || !isActiveSession(session.getInstance()) ? -1 : RdpDisplayControl.status(session.getInstance());
    }

    private void initSession(String rdpFileName, String username, String domain, String password) {
        bookmark.setRdpFileName(rdpFileName);
        bookmark.setUsername(username);
        bookmark.setDomain(domain);
        bookmark.setPassword(password);
        session = GlobalApp.createSession(bookmark, context);
        connectStarted = false;
        handledDisconnect = -1;
        ending = false;
        disconnectReasonBeforeStop = 0;
        lastLayoutBeforeStop = 0;
        errorCodesBeforeStop = 0;
        receivedFirstGraphicsFrame = false;
        if (!RdpDisplayControl.attach(session.getInstance())) {
            GlobalApp.freeSession(session.getInstance());
            throw new IllegalStateException("Unable to initialize RDP Display Control");
        }
        session.setUIEventListener(this);
    }

    public void setConnectionParameters(
            String address, int rdpPort,
            boolean gatewayEnabled, String gatewayHostname, int gatewayPort,
            String gatewayUsername, String gatewayDomain, String gatewayPassword,
            String nickname, int remoteWidth,
            int remoteHeight, boolean wallpaper, boolean fontSmoothing,
            boolean desktopComposition, boolean fullWindowDrag,
            boolean menuAnimations, boolean theming, boolean redirectSdCard,
            boolean consoleMode, int redirectSound, boolean enableRecording,
            boolean enableRemoteFx, boolean enableGfx, boolean enableGfxH264,
            int colors, int desktopScalePercentage, boolean debugLog
    ) {
        // Set a writable data directory
        //LibFreeRDP.setDataDirectory(session.getInstance(), getContext().getFilesDir().toString());
        // Get the address and port (based on whether an SSH tunnel is being established or not).
        bookmark.<ManualBookmark>get().setLabel(nickname);
        bookmark.<ManualBookmark>get().setHostname(addBracketsToIpv6Address(address));
        bookmark.<ManualBookmark>get().setPort(rdpPort);

        bookmark.<ManualBookmark>get().setEnableGatewaySettings(gatewayEnabled);
        ManualBookmark.GatewaySettings gatewaySettings = bookmark.<ManualBookmark>get().getGatewaySettings();
        gatewaySettings.setUsername(gatewayUsername == null ? "" : gatewayUsername);
        gatewaySettings.setDomain(gatewayDomain == null ? "" : gatewayDomain);
        gatewaySettings.setPassword(gatewayPassword == null ? "" : gatewayPassword);
        gatewaySettings.setHostname(addBracketsToIpv6Address(gatewayHostname));
        gatewaySettings.setPort(gatewayPort);

        BookmarkBase.DebugSettings debugSettings = bookmark.getDebugSettings();
        if (debugLog) {
            debugSettings.setDebugLevel("DEBUG");
        } else {
            debugSettings.setDebugLevel("INFO");
        }
        // Android already queues input. Keep its native event consumer on the connection thread
        // so its lifetime cannot outlast the session on disconnect.
        if (networkRecoveryEnabled) debugSettings.setAsyncInput(false);

        // Set screen settings to native res if instructed to, or if height or width are too small.
        BookmarkBase.ScreenSettings screenSettings = bookmark.getActiveScreenSettings();
        screenSettings.setDesktopScalePercentage(desktopScalePercentage);
        initialWidth = remoteWidth;
        initialHeight = remoteHeight;
        updateScreenSettings(remoteWidth, remoteHeight, colors);

        // Set performance flags.
        BookmarkBase.PerformanceFlags performanceFlags = bookmark.getPerformanceFlags();
        performanceFlags.setRemoteFX(enableRemoteFx);
        performanceFlags.setWallpaper(wallpaper);
        performanceFlags.setFontSmoothing(fontSmoothing);
        performanceFlags.setDesktopComposition(desktopComposition);
        performanceFlags.setFullWindowDrag(fullWindowDrag);
        performanceFlags.setMenuAnimations(menuAnimations);
        performanceFlags.setTheming(theming);
        performanceFlags.setGfx(enableGfx);
        performanceFlags.setH264(enableGfxH264);
        performanceFlags.setGlyphCache(connection.getEnableGlyphCache());

        BookmarkBase.AdvancedSettings advancedSettings = bookmark.getAdvancedSettings();
        advancedSettings.setRedirectSDCard(redirectSdCard);
        advancedSettings.setConsoleMode(consoleMode);
        advancedSettings.setRedirectSound(redirectSound);
        advancedSettings.setRedirectMicrophone(enableRecording);
        advancedSettings.setSecurity(connection.getRdpSecurity());
    }

    /**
     * Adds brackets around a valid IPv6 address that does not already start with [.
     *
     * @param address the address to potentially ad brackets to
     * @return the address with brackets added if necessary
     */
    private String addBracketsToIpv6Address(String address) {
        if (address == null) return "";
        InetAddressValidator validator = InetAddressValidator.getInstance();
        if (validator.isValidInet6Address(address) && !address.startsWith("[")) {
            address = "[" + address + "]";
        }
        return address;
    }

    public synchronized void connect(String configPath) {
        bookmark.setRdpFileName(configPath);
        connect();
    }

    public synchronized void connect() {
        if (disconnectRequested || connectStarted || handledDisconnect == session.getInstance()) return;
        long instance = session.getInstance();
        if (!LibFreeRDP.setConnectionInfo(context, instance, bookmark)) {
            freeUnstarted(instance);
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_INVALID_SETTINGS);
        } else if (!(connectStarted = LibFreeRDP.connect(instance))) {
            freeUnstarted(instance);
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_UNABLE_TO_CONNECT);
        }
    }

    private void freeUnstarted(long instance) {
        handledDisconnect = instance;
        RdpDisplayControl.detach(instance);
        GlobalApp.freeSession(instance);
    }

    @Override
    public void OnPreConnect(long instance) {
        Log.v(TAG, "OnPreConnect");
    }

    //////////////////////////////////////////////////////////////////////////////////
    //  Implementation of LibFreeRDP.EventListener.  Through the functions implemented
    //  below, FreeRDP communicates connection state information.
    //////////////////////////////////////////////////////////////////////////////////

    @Override
    public synchronized void OnConnectionSuccess(long instance) {
        if (!isActiveSession(instance)) return;
        Log.v(TAG, "OnConnectionSuccess");
        reattemptWithoutCredentials = false;
        authenticationAttempted = false;
        myself.setIsInNormalProtocol(true);
        if (networkRecoveryEnabled) {
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_CONNECTED);
        }
    }

    @Override
    public synchronized void OnConnectionFailure(long instance) {
        if (networkRecoveryEnabled) {
            // Android FreeRDP delivers failure instead of OnDisconnected on an error exit.
            finishNativeSession(instance);
            return;
        }
        if (!isActiveSession(instance)) return;
        Log.v(TAG, "OnConnectionFailure");
        myself.setIsInNormalProtocol(false);
    }

    @Override
    public synchronized void OnDisconnecting(long instance) {
        if (networkRecoveryEnabled) {
            if (session != null && session.getInstance() == instance && !ending && handledDisconnect != instance) {
                ending = true;
                snapshotDisconnect(instance);
                // FreeRDP closes the channel interfaces after PostDisconnect returns.
                RdpDisplayControl.detach(instance);
                notifyAll();
            }
            return;
        }
        RdpDisplayControl.detach(instance);
        if (!isActiveSession(instance)) return;
        handledDisconnect = instance;
        Log.v(TAG, "OnDisconnecting, reattemptWithoutCredentials: " + reattemptWithoutCredentials +
                ", authenticationAttempted: " + authenticationAttempted +
                ", disconnectRequested: " + disconnectRequested +
                ", isInNormalProtocol: " + myself.isInNormalProtocol());
        if (reattemptWithoutCredentials && !myself.isInNormalProtocol()) {
            reattemptWithoutCredentials = false;
            // It could be bad credentials that caused the disconnection, so trying to connect
            // once again with no credentials before reporting the type of failure.
            initSession(null, "", "", "");
            connect();
        } else if (authenticationAttempted && !myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: GET_RDP_CREDENTIALS");
            handler.sendEmptyMessage(RemoteClientLibConstants.GET_RDP_CREDENTIALS);
        } else if (gatewayAuthenticationAttempted && !myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: GET_RDP_GATEWAY_CREDENTIALS");
            handler.sendEmptyMessage(RemoteClientLibConstants.GET_RDP_GATEWAY_CREDENTIALS);
        } else if (!disconnectRequested && !myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: RDP_UNABLE_TO_CONNECT");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_UNABLE_TO_CONNECT);
        } else if (!disconnectRequested) {
            myself.setIsInNormalProtocol(false);
            Log.v(TAG, "Sending message: RDP_CONNECT_FAILURE");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_CONNECT_FAILURE);
        }
    }

    @Override
    public synchronized void OnDisconnected(long instance) {
        finishNativeSession(instance);
    }

    private void finishNativeSession(long instance) {
        GlobalApp.forgetSession(instance);
        if (networkRecoveryEnabled) {
            finishRecoverableTransport(instance);
            return;
        }
        if (!isActiveSession(instance)) return;
        handledDisconnect = instance;
        Log.v(TAG, "OnDisconnected");
        if (!myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: RDP_UNABLE_TO_CONNECT");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_UNABLE_TO_CONNECT);
        } else {
            Log.v(TAG, "Sending message: RDP_CONNECT_FAILURE");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_CONNECT_FAILURE);
        }
        myself.setIsInNormalProtocol(false);
    }

    private void finishRecoverableTransport(long instance) {
        if (session == null || session.getInstance() != instance || handledDisconnect == instance) return;
        boolean wasConnected = isInNormalProtocol();
        if (!ending) {
            snapshotDisconnect(instance);
            RdpDisplayControl.detach(instance);
        }
        int reason = disconnectReasonBeforeStop;
        handledDisconnect = instance;
        LibFreeRDP.releaseAfterDisconnect(instance);
        myself.setIsInNormalProtocol(false);
        if (disconnectRequested) return;
        int message = wasConnected ? RemoteClientLibConstants.RDP_CONNECT_FAILURE
                : RemoteClientLibConstants.RDP_UNABLE_TO_CONNECT;
        if (reason == RdpDisplayControl.NETWORK_FAILURE) {
            message = RemoteClientLibConstants.RDP_NETWORK_LOST;
        } else if (reason == RdpDisplayControl.DISPLAY_DRIVER_FAILURE) {
            message = RemoteClientLibConstants.RDP_DISPLAY_DRIVER_FAILURE;
        } else if (reason == RdpDisplayControl.CREDENTIALS_REQUIRED && !wasConnected) {
            message = gatewayAuthenticationAttempted && !authenticationAttempted
                    ? RemoteClientLibConstants.GET_RDP_GATEWAY_CREDENTIALS
                    : RemoteClientLibConstants.GET_RDP_CREDENTIALS;
        }
        handler.sendEmptyMessage(message);
    }

    private void snapshotDisconnect(long instance) {
        lastLayoutBeforeStop = RdpDisplayControl.lastSentLayout(instance);
        errorCodesBeforeStop = RdpDisplayControl.errorCodes(instance);
        disconnectReasonBeforeStop = RdpDisplayControl.disconnectReason(instance, isInNormalProtocol());
    }

    public synchronized long getLastLayoutBeforeStop() { return lastLayoutBeforeStop; }
    public synchronized long getErrorCodesBeforeStop() { return errorCodesBeforeStop; }
    public synchronized boolean hadFirstFrame() { return receivedFirstGraphicsFrame; }
    public synchronized int getInitialWidth() { return initialWidth; }
    public synchronized int getInitialHeight() { return initialHeight; }

    private boolean isActiveSession(long instance) {
        return session != null && session.getInstance() == instance && !disconnectRequested
                && !ending && handledDisconnect != instance;
    }

    @Override
    public synchronized void OnSettingsChanged(int width, int height, int bpp) {
        if (!isActiveSession(session.getInstance())) return;
        Log.d(TAG, "OnSettingsChanged called, wxh: " + width + "x" + height);
        handler.sendEmptyMessage(RemoteClientLibConstants.GRAPHICS_SETTINGS_RECEIVED);
        updateScreenSettings(width, height, bpp);
        viewable.reallocateDrawable(width, height);
    }

    private void updateScreenSettings(int width, int height, int bpp) {
        BookmarkBase.ScreenSettings settings = session.getBookmark().getActiveScreenSettings();
        settings.setWidth(width);
        settings.setHeight(height);
        settings.setColors(bpp);
        settings.setDesktopScalePercentage(settings.getDesktopScalePercentage());
    }

    //////////////////////////////////////////////////////////////////////////////////
    //  Implementation of LibFreeRDP.UIEventListener. Through the functions implemented
    //  below, libspice and FreeRDP communicate remote desktop size and updates.
    //////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean OnAuthenticate(StringBuilder username, StringBuilder domain, StringBuilder password) {
        Log.d(TAG, "OnAuthenticate called.");
        authenticationAttempted = true;
        if (!TextUtils.isEmpty(this.username) || !TextUtils.isEmpty(this.domain) || !TextUtils.isEmpty(this.password))
            setCredentialsStringBuildersToValues(username, domain, password, this.username, this.domain, this.password);
        return true;
    }

    private void setCredentialsStringBuildersToValues(
            StringBuilder username, StringBuilder domain, StringBuilder password,
            String fromUsername, String fromDomain, String fromPassword) {
        username.setLength(0);
        domain.setLength(0);
        password.setLength(0);
        username.append(fromUsername);
        domain.append(fromDomain);
        password.append(fromPassword);
    }

    @Override
    public int OnVerifiyCertificate(String commonName, String subject,
                                    String issuer, String fingerprint, boolean mismatch) {
        return verifyCertificate(subject, issuer, fingerprint, mismatch, false);
    }

    private synchronized int verifyCertificate(String subject, String issuer, String fingerprint,
                                                boolean hostnameMismatch, boolean changed) {
        Log.d(TAG, "OnVerifiyCertificate called.");
        if (disconnectRequested || ending) return 0;
        certificateAccepted = false;
        int request = ++certificateRequest;
        notifyAll();

        // Send a message containing the certificate to our handler.
        Message m = new Message();
        m.setTarget(handler);
        m.what = RemoteClientLibConstants.DIALOG_RDP_CERT;
        Bundle strings = new Bundle();
        strings.putString("subject", subject);
        strings.putString("issuer", issuer);
        strings.putString("fingerprint", fingerprint);
        strings.putBoolean("mismatch", changed);
        strings.putBoolean("hostnameMismatch", hostnameMismatch);
        m.setData(strings);
        m.obj = this;
        m.arg1 = request;
        handler.sendMessage(m);

        while (awaitingCertificate(request)) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return certificateAccepted && !disconnectRequested && request == certificateRequest ? 1 : 0;
    }

    @Override
    public boolean OnGatewayAuthenticate(StringBuilder username,
                                         StringBuilder domain, StringBuilder password) {
        Log.d(TAG, "OnGatewayAuthenticate called.");
        gatewayAuthenticationAttempted = true;
        ManualBookmark.GatewaySettings gateway = bookmark.<ManualBookmark>get().getGatewaySettings();
        if (!TextUtils.isEmpty(gateway.getUsername()) || !TextUtils.isEmpty(gateway.getDomain()) ||
                !TextUtils.isEmpty(gateway.getPassword())) {
            setCredentialsStringBuildersToValues(username, domain, password,
                    gateway.getUsername(), gateway.getDomain(), gateway.getPassword());
        }
        return true;
    }

    @Override
    public int OnVerifyChangedCertificate(String commonName, String subject,
                                          String issuer, String fingerprint, String oldSubject,
                                          String oldIssuer, String oldFingerprint) {
        Log.d(TAG, "OnVerifyChangedCertificate called.");
        return verifyCertificate(subject, issuer, fingerprint, false, true);
    }

    @Override
    public synchronized void OnGraphicsUpdate(int x, int y, int width, int height) {
        if (viewable != null && session != null && isActiveSession(session.getInstance())) {
            Bitmap bitmap = viewable.getBitmap();
            if (bitmap != null && x >= 0 && y >= 0 && width > 0 && height > 0
                    && x <= bitmap.getWidth() - width && y <= bitmap.getHeight() - height) {
                if (LibFreeRDP.updateGraphics(session.getInstance(), bitmap, x, y, width, height)) {
                    if (!receivedFirstGraphicsFrame) {
                        receivedFirstGraphicsFrame = true;
                        handler.sendEmptyMessage(RemoteClientLibConstants.GRAPHICS_FIRST_FRAME_RECEIVED);
                    }
                    viewable.reDraw(x, y, width, height);
                    viewable.framebufferUpdated(bitmap.getWidth(), bitmap.getHeight());
                }
            }
        }
    }

    @Override
    public void OnGraphicsResize(int width, int height, int bpp) {
        Log.d(TAG, "OnGraphicsResize called " + width + "x" + height + ", bpp:" + bpp);
        OnSettingsChanged(width, height, bpp);
    }

    @Override
    public void OnRemoteClipboardChanged(String data) {
        Log.d(TAG, "OnRemoteClipboardChanged called.");
        if (clipboard == null && data != null && !data.isEmpty()) remoteClipboardChanged(data);
    }

}
