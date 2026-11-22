package com.undatech.opaque;

/** Display Control virtual channel for the FreeRDP 2.x Android client. */
final class RdpDisplayControl {
    static { System.loadLibrary("fold_display"); }
    private RdpDisplayControl() {}
    static native boolean attach(long instance);
    static native boolean resize(long instance, int width, int height, int desktopScale);
    static native int status(long instance);
    static native long lastSentLayout(long instance);
    static native boolean hasHorizontalWheel(long instance);
    static final int NETWORK_FAILURE = 1;
    static final int CREDENTIALS_REQUIRED = 2;
    static final int DISPLAY_DRIVER_FAILURE = 3;
    static native int disconnectReason(long instance, boolean wasConnected);
    static native long errorCodes(long instance);
    static native void detach(long instance);
}
