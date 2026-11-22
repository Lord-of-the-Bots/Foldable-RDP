package com.iiordanov.bVNC;

/** Geometry in framebuffer pixels. */
public final class RdpViewport {
    private RdpViewport() {}

    public static int panX(int current, int delta, int remote, int visible) {
        return clampOffset(current + delta, remote, visible);
    }

    static int clampOffset(int offset, int remote, int visible) {
        return Math.max(0, Math.min(remote - visible, offset));
    }

    public static int panY(int current, int delta, int remote, int visible) {
        return clampOffset(current + delta, remote, visible);
    }

    public static boolean validSize(int width, int height) {
        return width >= 200 && height >= 200 && width <= 8192 && height <= 8192;
    }
}
