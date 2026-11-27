package com.iiordanov.bVNC;

import android.graphics.Point;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Routes a whole gesture to the backend that accepted its first finger. */
final class DesktopTouchContacts {
    interface Sender {
        boolean send(int action, int id, int x, int y);
    }

    private static final class Contact extends Point {
        final int id;
        Contact(int id, int x, int y) { super(x, y); this.id = id; }
    }

    private final SparseArray<Contact> contacts = new SparseArray<>();
    private Sender owner;
    private boolean captured;
    private int nextId;

    void cancel() {
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            owner.send(MotionEvent.ACTION_CANCEL, c.id, c.x, c.y);
        }
        contacts.clear();
        owner = null;
    }

    boolean handle(MotionEvent e, Sender sender, RemoteCanvas canvas, int offsetX, int offsetY) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            captured = false;
            if (!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER)
                return false;
        } else if (owner == null) {
            boolean consumed = captured;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) captured = false;
            return consumed;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
            captured = false;
            return true;
        }
        int width = canvas.getImageWidth(), height = canvas.getImageHeight();
        float scale = canvas.getZoomFactor();
        if (width <= 0 || height <= 0 || scale <= 0) {
            boolean active = owner != null;
            if (active) cancel();
            return active;
        }
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (action != MotionEvent.ACTION_MOVE && i != e.getActionIndex()) continue;
            int key = e.getPointerId(i);
            int x = Math.max(0, Math.min(width - 1, (int)(canvas.getAbsX() + (e.getX(i) - offsetX) / scale)));
            int y = Math.max(0, Math.min(height - 1, (int)(canvas.getAbsY() + (e.getY(i) - offsetY) / scale)));
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (e.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue;
                Contact c = new Contact(nextId++, x, y);
                Sender wire = owner == null ? sender : owner;
                if (wire.send(MotionEvent.ACTION_DOWN, c.id, x, y)) {
                    owner = wire;
                    captured = true;
                    contacts.put(key, c);
                } else if (action == MotionEvent.ACTION_DOWN) return false;
            } else {
                Contact c = contacts.get(key);
                if (c == null) continue;
                if (action == MotionEvent.ACTION_MOVE) {
                    if (c.x != x || c.y != y) owner.send(action, c.id, x, y);
                    c.set(x, y);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                    owner.send(MotionEvent.ACTION_UP, c.id, x, y);
                    contacts.remove(key);
                }
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            cancel();
            captured = false;
        }
        return true;
    }
}
