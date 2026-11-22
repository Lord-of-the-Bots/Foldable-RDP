package com.iiordanov.bVNC;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import com.undatech.remoteClientUi.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** A bounded keyboard surface. Every layer fits the available window without scrolling. */
public final class DesktopKeyboardView extends LinearLayout {
    static final int BASE_ROW_HEIGHT_DP = 46;
    public interface Listener {
        void key(int code, boolean down, int repeat);
        boolean modifier(int code);
        void clearModifiers();
        void hide();
        default void layoutChanged() {}
        void switchKeyboard();
    }
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<View, Runnable> repeats = new HashMap<>();
    private final Map<View, Integer> pressed = new HashMap<>();
    private final Map<Integer, KeyLabel> modifiers = new HashMap<>();
    private final java.util.Set<Integer> activeModifiers = new java.util.HashSet<>();
    private final Map<KeyLabel, ModifierPress> heldModifiers = new HashMap<>();
    private static final class ModifierPress {
        int code;
        boolean wasActive, used;
        long started;
    }
    private boolean shifted, functionsOnly;
    private boolean shiftLocked;
    private long lastShiftTap = -1;
    private int availableHeight = Integer.MAX_VALUE;
    private int rowHeightDp;
    private String screenProfile = "default";

    public DesktopKeyboardView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        loadRowHeight();
        setOrientation(VERTICAL);
        // Own the gesture even when it starts on padding or between rows.
        setClickable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setOnGenericMotionListener((view, event) -> event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER));
        setBackgroundColor(0xFF202124);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setPadding(dp(4), dp(5), dp(4), dp(5));
        setFocusable(false);
        setContentDescription(context.getString(R.string.fold_desktop_keyboard));
        rebuild();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    public int getRowHeightDp() { return rowHeightDp; }
    private void loadRowHeight() {
        rowHeightDp = new DesktopControlProfile.Screen(getContext(), screenProfile).rowHeight;
    }
    public void setScreenProfile(String profile) {
        if (screenProfile.equals(profile)) return;
        screenProfile = profile;
        loadRowHeight();
        requestLayout();
    }
    public void setRowHeightDp(int height) {
        rowHeightDp = Math.max(32, Math.min(84, height));
        getContext().getSharedPreferences("desktop_keyboard", Context.MODE_PRIVATE).edit().putInt("rowHeight_" + screenProfile, rowHeightDp).apply();
        requestLayout();
        listener.layoutChanged();
    }
    public void setFunctionsOnly(boolean value) {
        if (functionsOnly == value) return;
        releaseKeys(); functionsOnly = value; rebuild();
    }
    public void setAvailableHeight(int pixels) {
        int value = Math.max(0, pixels);
        if (availableHeight != value) { availableHeight = value; requestLayout(); }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int limit = availableHeight;
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
            limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
        int preferredRow = dp(functionsOnly ? rowHeightDp : BASE_ROW_HEIGHT_DP * 0.70f);
        int padding = getPaddingTop() + getPaddingBottom();
        int height = Math.min(limit, preferredRow * getChildCount() + padding);
        height = Math.max(0, height);
        int space = Math.max(0, height - padding);
        for (int i = 0; i < getChildCount(); i++) {
            LayoutParams params = (LayoutParams)getChildAt(i).getLayoutParams();
            params.height = space / getChildCount() + (i < space % getChildCount() ? 1 : 0);
        }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    public final class KeyLabel extends View {
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final int maximumSize;
        private String label = "";
        KeyLabel(boolean special) {
            super(DesktopKeyboardView.this.getContext());
            maximumSize = dp(special ? 17 : 21);
            labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            labelPaint.setColor(0xFFF3F4F6);
            setWillNotDraw(false);
        }
        public String getText() { return label; }
        public Paint getPaint() { return labelPaint; }
        public float getTextSize() { return labelPaint.getTextSize(); }
        public void setText(String text) { label = text; fitLabel(); invalidate(); }
        void fitLabel() {
            int size = label.length() == 1 && label.charAt(0) > 127 && !Character.isLetterOrDigit(label.charAt(0))
                    ? dp(24) : maximumSize;
            labelPaint.setTextSize(size);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            float width = Math.max(1, getWidth() - dp(2));
            float height = Math.max(1, getHeight() - dp(4));
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            float scale = Math.min(1f, Math.min(width / Math.max(1, labelPaint.measureText(label)),
                    height / (fm.descent - fm.ascent)));
            labelPaint.setTextSize(size * scale);
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh); fitLabel();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            fitLabel();
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            canvas.drawText(label, (getWidth() - labelPaint.measureText(label)) / 2f,
                    (getHeight() - fm.ascent - fm.descent) / 2f, labelPaint);
        }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setText(label);
            info.setSelected(isSelected());
        }
    }
    private KeyLabel button(String label, boolean special) {
        KeyLabel view = new KeyLabel(special);
        view.setText(label);
        view.setFocusable(false); view.setClickable(true);
        view.setTag(special);
        view.setContentDescription(label);
        paint(view, false);
        return view;
    }
    private void paint(KeyLabel view, boolean down) {
        GradientDrawable bg = new GradientDrawable();
        boolean special = Boolean.TRUE.equals(view.getTag());
        bg.setColor(down || view.isSelected() ? 0xFF2979C8 : special ? 0xFF383A3F : 0xFF55575C);
        bg.setCornerRadius(dp(7));
        view.setBackground(new android.graphics.drawable.InsetDrawable(bg, dp(2), dp(3), dp(2), dp(3)));
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setMotionEventSplittingEnabled(true);
        row.setClickable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(row, new LayoutParams(-1, dp(BASE_ROW_HEIGHT_DP)));
        return row;
    }
    private void add(LinearLayout row, KeyLabel button, float weight) {
        LayoutParams lp = new LayoutParams(0, -1, weight);
        row.addView(button, lp);
    }
    private void action(LinearLayout row, String label, float weight, Runnable action) {
        KeyLabel b = button(label, true);
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action.run(); });
        add(row, b, weight);
    }
    private void bindPress(KeyLabel b, int code) {
        boolean[] touchClick = {false};
        b.setOnClickListener(v -> {
            if (touchClick[0]) return;
            for (ModifierPress modifier : heldModifiers.values()) modifier.used = true;
            listener.key(code, true, 0); listener.key(code, false, 0);
            consumeShift();
        });
        b.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    paint(b, true);
                    for (ModifierPress modifier : heldModifiers.values()) modifier.used = true;
                    pressed.put(v, code); listener.key(code, true, 0);
                    Runnable repeat = new Runnable() {
                        int count;
                        @Override public void run() {
                            listener.key(code, true, ++count);
                            handler.postDelayed(this, 55);
                        }
                    };
                    repeats.put(v, repeat); handler.postDelayed(repeat, 450);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getX() < -dp(12) || event.getX() > v.getWidth() + dp(12)
                            || event.getY() < -dp(12) || event.getY() > v.getHeight() + dp(12)) release(v);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    release(v);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        consumeShift();
                        touchClick[0] = true;
                        try { v.performClick(); } finally { touchClick[0] = false; }
                    }
                    return true;
                default: return true;
            }
        });
    }
    private void key(LinearLayout row, String label, int code) { key(row, label, code, 1); }
    private void key(LinearLayout row, String label, int code, float weight) {
        KeyLabel b = button(label, true);
        bindPress(b, code); add(row, b, weight);
    }
    private void setModifier(int code, boolean on) {
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT && !on) {
            shiftLocked = false;
            lastShiftTap = -1;
        }
        if (activeModifiers.contains(code) != on) {
            boolean actual = listener.modifier(code);
            if (actual) activeModifiers.add(code); else activeModifiers.remove(code);
        }
        KeyLabel view = modifiers.get(code);
        if (view != null) { view.setSelected(activeModifiers.contains(code)); paint(view, false); }
        shifted = activeModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT);
        if (view != null && code == KeyEvent.KEYCODE_SHIFT_LEFT) updateShiftLabel(view);
    }
    private void consumeShift() {
        int shift = KeyEvent.KEYCODE_SHIFT_LEFT;
        if (shifted && !shiftLocked && !heldModifiers.containsKey(modifiers.get(shift))) setModifier(shift, false);
    }
    private void updateShiftLabel(KeyLabel key) {
        key.setText(shiftLocked ? "SHIFT" : "Shift");
        key.setContentDescription(shiftLocked ? "Shift Lock" : "Shift");
    }
    private void tapShift(boolean wasActive, long time) {
        boolean doubleTap = wasActive && !shiftLocked && lastShiftTap >= 0
                && time - lastShiftTap <= android.view.ViewConfiguration.getDoubleTapTimeout();
        shiftLocked = doubleTap;
        setModifier(KeyEvent.KEYCODE_SHIFT_LEFT, !wasActive || doubleTap);
        lastShiftTap = shifted ? time : -1;
    }
    private void modifier(LinearLayout row, String label, int code) {
        KeyLabel b = button(label, true);
        modifiers.put(code, b);
        b.setSelected(activeModifiers.contains(code)); paint(b, false);
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT) updateShiftLabel(b);
        b.setOnClickListener(v -> {
            if (code == KeyEvent.KEYCODE_SHIFT_LEFT) tapShift(shifted, android.os.SystemClock.uptimeMillis());
            else setModifier(code, !activeModifiers.contains(code));
        });
        b.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                ModifierPress press = new ModifierPress();
                press.code = code; press.wasActive = activeModifiers.contains(code); press.started = event.getEventTime();
                heldModifiers.put(b, press); setModifier(code, true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                ModifierPress press = heldModifiers.remove(b);
                if (press != null) {
                    if (press.used || event.getEventTime() - press.started >= 350 || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        setModifier(code, false);
                    else if (code == KeyEvent.KEYCODE_SHIFT_LEFT) tapShift(press.wasActive, event.getEventTime());
                    else if (press.wasActive) setModifier(code, false);
                }
            }
            return true;
        });
        add(row, b, 1.2f);
    }
    private void arrows(LinearLayout row) {
        key(row, "←", KeyEvent.KEYCODE_DPAD_LEFT);
        key(row, "↑", KeyEvent.KEYCODE_DPAD_UP);
        key(row, "↓", KeyEvent.KEYCODE_DPAD_DOWN);
        key(row, "→", KeyEvent.KEYCODE_DPAD_RIGHT);
    }
    private void rebuild() {
        post(listener::layoutChanged);
        releasePhysicalKeys(); removeAllViews(); modifiers.clear();
        LinearLayout selector = row();
        KeyLabel type = button(getContext().getString(functionsOnly ? R.string.fold_switch_to_text : R.string.fold_switch_to_functions), true);
        type.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            listener.switchKeyboard();
        });
        type.setSelected(true);
        paint(type, false);
        add(selector, type, 4);
        action(selector, "⌄", 1, listener::hide);
        selector.getChildAt(1).setContentDescription(getContext().getString(R.string.fold_keyboard_hide));
        LinearLayout top = row();
        key(top, "Esc", KeyEvent.KEYCODE_ESCAPE);
        key(top, "Tab", KeyEvent.KEYCODE_TAB);
        modifier(top, "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT);
        modifier(top, "Alt", KeyEvent.KEYCODE_ALT_LEFT);
        modifier(top, "Win", KeyEvent.KEYCODE_META_LEFT);
        modifier(top, "Shift", KeyEvent.KEYCODE_SHIFT_LEFT);
        if (functionsOnly) {
            for (int start = 0; start < 12; start += 6) {
                LinearLayout f = row();
                for (int i = start; i < start + 6; i++) key(f, "F"+(i+1), KeyEvent.KEYCODE_F1+i);
            }
            LinearLayout nav = row();
            key(nav, "Home", KeyEvent.KEYCODE_MOVE_HOME); key(nav, "End", KeyEvent.KEYCODE_MOVE_END);
            key(nav, "PgUp", KeyEvent.KEYCODE_PAGE_UP); key(nav, "PgDn", KeyEvent.KEYCODE_PAGE_DOWN);
            key(nav, "Ins", KeyEvent.KEYCODE_INSERT); key(nav, "Del", KeyEvent.KEYCODE_FORWARD_DEL);
            LinearLayout commands = row();
            key(commands, "PrtSc", KeyEvent.KEYCODE_SYSRQ); key(commands, "Pause", KeyEvent.KEYCODE_BREAK);
            key(commands, "Caps", KeyEvent.KEYCODE_CAPS_LOCK); key(commands, "Num", KeyEvent.KEYCODE_NUM_LOCK);
            key(commands, "ScrLk", KeyEvent.KEYCODE_SCROLL_LOCK);
            LinearLayout bottom = row(); arrows(bottom);
            key(bottom, "⌫", KeyEvent.KEYCODE_DEL); key(bottom, "↵", KeyEvent.KEYCODE_ENTER);
            return;
        }
        LinearLayout nav = row(); arrows(nav);
        key(nav, "Home", KeyEvent.KEYCODE_MOVE_HOME); key(nav, "End", KeyEvent.KEYCODE_MOVE_END);
        key(nav, "Del", KeyEvent.KEYCODE_FORWARD_DEL);
    }
    private void release(View view) {
        Runnable repeat = repeats.remove(view);
        if (repeat != null) handler.removeCallbacks(repeat);
        Integer code = pressed.remove(view);
        if (code != null) listener.key(code, false, 0);
        paint((KeyLabel)view, false);
    }
    private void releasePhysicalKeys() {
        for (View view : new ArrayList<>(repeats.keySet())) release(view);
        for (View view : new ArrayList<>(pressed.keySet())) release(view);
        for (ModifierPress press : new ArrayList<>(heldModifiers.values())) setModifier(press.code, false);
        heldModifiers.clear();
    }
    public void releaseKeys() {
        releasePhysicalKeys();
        listener.clearModifiers(); activeModifiers.clear(); shifted = shiftLocked = false; lastShiftTap = -1;
        KeyLabel shift = modifiers.get(KeyEvent.KEYCODE_SHIFT_LEFT);
        if (shift != null) updateShiftLabel(shift);
        for (KeyLabel b : modifiers.values()) { b.setSelected(false); paint(b, false); }
    }
    @Override protected void onDetachedFromWindow() { releaseKeys(); super.onDetachedFromWindow(); }
}
