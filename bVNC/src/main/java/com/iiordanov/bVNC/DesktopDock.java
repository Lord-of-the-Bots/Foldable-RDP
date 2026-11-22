package com.iiordanov.bVNC;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import com.undatech.remoteClientUi.R;

/** Session controls that dock to an edge and retain a proportional position on resize. */
public final class DesktopDock extends LinearLayout {
    private final Rect bounds = new Rect();
    private final SharedPreferences preferences;
    private int edge;
    private float fraction;
    private float downX, downY, startX, startY;
    private boolean dragging;

    public DesktopDock(Context context, OnClickListener menu, OnClickListener keyboard,
                       OnClickListener computers) {
        super(context);
        preferences = context.getSharedPreferences("desktop_dock", Context.MODE_PRIVATE);
        edge = Math.max(0, Math.min(3, preferences.getInt("edge", 1)));
        fraction = Math.max(0, Math.min(1, preferences.getFloat("fraction", .5f)));
        setOrientation(edge < 2 ? VERTICAL : HORIZONTAL);
        setId(R.id.foldDock);
        setPadding(dp(3), dp(3), dp(3), dp(3));
        setElevation(dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6202630);
        background.setCornerRadius(dp(14));
        setBackground(background);
        if (computers != null) {
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            addButton(R.drawable.fold_computers, R.string.fold_computers, computers);
        }
        addButton(R.drawable.fold_menu, R.string.fold_menu, menu);
        addButton(R.drawable.fold_keyboard, R.string.fold_keyboard_picker, keyboard);
        setLayoutParams(new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void addButton(int icon, int description, OnClickListener click) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(icon);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setColorFilter(0xFFFFFFFF);
        button.setContentDescription(getContext().getString(description));
        android.util.TypedValue background = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        addView(button, new LayoutParams(dp(48), dp(48)));
        button.setOnClickListener(click);
        button.setOnTouchListener(this::drag);
    }

    private boolean drag(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY();
                startX = getX(); startY = getY(); dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    dragging = true;
                    view.cancelLongPress();
                    view.setPressed(false);
                }
                if (dragging) {
                    setX(clamp(startX + dx, bounds.left, bounds.right - getWidth()));
                    setY(clamp(startY + dy, bounds.top, bounds.bottom - getHeight()));
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    snap();
                    dragging = false;
                    return true;
                }
        }
        return false;
    }

    private float clamp(float v, float min, float max) { return Math.max(min, Math.min(Math.max(min, max), v)); }

    private void snap() {
        float[] distance = {getX() - bounds.left, bounds.right - getX() - getWidth(),
                getY() - bounds.top, bounds.bottom - getY() - getHeight()};
        edge = 0;
        for (int i = 1; i < distance.length; i++) if (distance[i] < distance[edge]) edge = i;
        float range = edge < 2 ? bounds.height() - getHeight() : bounds.width() - getWidth();
        fraction = range <= 0 ? .5f : clamp((edge < 2 ? getY() - bounds.top : getX() - bounds.left) / range, 0, 1);
        preferences.edit().putInt("edge", edge).putFloat("fraction", fraction).apply();
        arrange();
    }

    public void setBounds(Rect value) {
        if (!bounds.equals(value)) { bounds.set(value); arrange(); }
    }

    private void arrange() {
        setOrientation(edge < 2 ? VERTICAL : HORIZONTAL);
        requestLayout();
        post(this::place);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        place();
    }

    private void place() {
            float x = edge < 2 ? (edge == 0 ? bounds.left : bounds.right - getWidth())
                    : bounds.left + fraction * Math.max(0, bounds.width() - getWidth());
            float y = edge >= 2 ? (edge == 2 ? bounds.top : bounds.bottom - getHeight())
                    : bounds.top + fraction * Math.max(0, bounds.height() - getHeight());
            setX(clamp(x, bounds.left, bounds.right - getWidth()));
            setY(clamp(y, bounds.top, bounds.bottom - getHeight()));
    }
}
