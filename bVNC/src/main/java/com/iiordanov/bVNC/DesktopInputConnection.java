package com.iiordanov.bVNC;

import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import java.util.function.Consumer;

/** Keeps IME composition local and sends committed text exactly once. */
final class DesktopInputConnection extends BaseInputConnection {
    private final Consumer<String> text;
    private final Consumer<KeyEvent> key;

    DesktopInputConnection(View target, Consumer<String> text, Consumer<KeyEvent> key) {
        super(target, true);
        this.text = text;
        this.key = key;
    }

    @Override public boolean commitText(CharSequence value, int cursor) {
        text.accept(value.toString());
        clear();
        return true;
    }

    @Override public boolean finishComposingText() {
        Editable editable = getEditable();
        if (editable.length() > 0) text.accept(editable.toString());
        clear();
        return true;
    }

    private void clear() {
        removeComposingSpans(getEditable());
        getEditable().clear();
        android.text.Selection.setSelection(getEditable(), 0);
    }

    @Override public boolean deleteSurroundingText(int before, int after) {
        if (getEditable().length() > 0) return super.deleteSurroundingText(before, after);
        for (int i = 0; i < before; i++) press(KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < after; i++) press(KeyEvent.KEYCODE_FORWARD_DEL);
        return true;
    }

    @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
        if (getEditable().length() > 0) return super.deleteSurroundingTextInCodePoints(before, after);
        return deleteSurroundingText(before, after);
    }

    @Override public boolean sendKeyEvent(KeyEvent event) { key.accept(event); return true; }

    private void press(int code) {
        key.accept(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        key.accept(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
}
