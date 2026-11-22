package com.iiordanov.bVNC.input;

import android.view.MotionEvent;
import android.view.ViewConfiguration;
import com.undatech.opaque.InputCarriable;
import com.undatech.opaque.Viewable;
import com.undatech.remoteClientUi.R;

public final class DesktopTouchpad extends TouchInputHandlerTouchpad {
    private final int touchSlop;
    private boolean movedDuringDrag;

    public float scrollWheel(float pixels, boolean horizontal, int meta) {
        float step = Math.max(1f, baseSwipeDist * 2);
        int steps = (int)(pixels / step);
        var pointer = remoteInput.getPointer();
        for (int n = 0; n < Math.min(12, Math.abs(steps)); n++) {
            if (horizontal && steps > 0) pointer.scrollLeft(pointer.getX(), pointer.getY(), meta);
            else if (horizontal) pointer.scrollRight(pointer.getX(), pointer.getY(), meta);
            else if (steps > 0) pointer.scrollUp(pointer.getX(), pointer.getY(), meta);
            else pointer.scrollDown(pointer.getX(), pointer.getY(), meta);
        }
        return pixels - steps * step;
    }

    public DesktopTouchpad(TouchInputDelegate delegate, Viewable canvas, InputCarriable input,
                           boolean debugLogging, float scrollRate) {
        super(delegate, canvas, input, debugLogging, scrollRate);
        touchSlop = ViewConfiguration.get(canvas.getContext()).getScaledTouchSlop();
    }

    @Override public String getDescription() {
        return viewable.getResources().getString(R.string.fold_touchpad_gestures);
    }

    @Override public void onLongPress(MotionEvent event) {
        if (dragMode || secondPointerWasDown || thirdPointerWasDown) return;
        var pointer = remoteInput.getPointer();
        int x = pointer.getX(), y = pointer.getY();
        touchInputDelegate.sendShortVibration();
        pointer.rightButtonDown(x, y, event.getMetaState());
        pointer.releaseButton(x, y, event.getMetaState());
    }

    @Override public boolean onDoubleTap(MotionEvent firstTap) {
        return true;
    }

    @Override public boolean onDoubleTapEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragX = event.getX();
            dragY = event.getY();
            immersiveSwipe = inSwiping = false;
            dragMode = true;
            movedDuringDrag = false;
            var pointer = remoteInput.getPointer();
            pointer.leftButtonDown(pointer.getX(), pointer.getY(), event.getMetaState());
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!dragMode) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !movedDuringDrag) {
            if (Math.hypot(event.getX() - dragX, event.getY() - dragY) <= touchSlop) return true;
            movedDuringDrag = true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            var pointer = remoteInput.getPointer();
            pointer.releaseButton(pointer.getX(), pointer.getY(), event.getMetaState());
        }
        boolean quickLift = action == MotionEvent.ACTION_UP && !movedDuringDrag
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()
                && Math.hypot(event.getX() - dragX, event.getY() - dragY) <= touchSlop;
        boolean consumed = super.onTouchEvent(event);
        // The second touch already supplied one press and release. Complete a quick double-click.
        if (quickLift) return super.onSingleTapConfirmed(event);
        return consumed;
    }
}
