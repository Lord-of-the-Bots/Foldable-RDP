package com.iiordanov.bVNC;

/** Retry budget for one desktop, using elapsed realtime. */
final class DesktopReconnect {
    private static final long[] DELAYS_MS = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
    private static final long WINDOW_MS = 120000;
    private int attempts;
    private long deadline;
    private long connectedAt = -1;
    private long nextAttempt;
    boolean waiting;

    void connected(long now) {
        connectedAt = now;
        waiting = false;
    }

    boolean lost(long now) {
        if (waiting) return !expired(now);
        if (deadline == 0 || (connectedAt >= 0 && now - connectedAt >= 15000)) {
            deadline = now + WINDOW_MS;
            attempts = 0;
        }
        connectedAt = -1;
        if (expired(now)) return false;
        nextAttempt = now + DELAYS_MS[attempts];
        waiting = true;
        return true;
    }

    boolean expired(long now) {
        return attempts >= DELAYS_MS.length || now >= deadline;
    }

    long delay(long now) {
        return Math.max(0, Math.min(deadline, nextAttempt) - now);
    }

    void startAttempt() {
        waiting = false;
        attempts++;
    }

    int attemptNumber() {
        return waiting ? attempts + 1 : attempts;
    }

    int maxAttempts() {
        return DELAYS_MS.length;
    }

    boolean retrying() {
        return attempts > 0 && connectedAt < 0;
    }
}
