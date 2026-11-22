package com.undatech.opaque;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.annotation.Keep;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Android clipboard integration and the cliprdr endpoint for one live desktop. */
@Keep
final class RdpClipboard implements ClipboardBridge.Wire, ClipboardManager.OnPrimaryClipChangedListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final ClipboardBridge bridge = new ClipboardBridge();
    private static final AtomicLong ids = new AtomicLong();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean ticking = new AtomicBoolean();
    private static final String MARKER = "com.foldable.rdp.clipboard";
    private static final String ownToken = UUID.randomUUID().toString();
    // Android has one clipboard. Keep its observation across activity changes so an older plain
    // text value cannot replace a richer clipboard offer when the user switches computers.
    private static RdpClipboard activePhone;
    private static boolean observed;
    private static long observedTime;
    private static String observedText;
    private static List<Uri> observedFiles = Collections.emptyList();
    private static String lastPublishedText;
    private static List<Uri> lastPublishedFiles = Collections.emptyList();
    private static boolean partialSelection;

    private final long token = ids.incrementAndGet();
    private final ClipboardBridge.Peer peer;
    private final ClipboardManager phone;
    private final PhoneTransferNotifications transfers;
    private final Context app;
    private final SharedPreferences preferences;
    private final String copyPreference, pastePreference;
    private boolean listening;
    private volatile boolean closed;
    private int modifiers;

    private RdpClipboard(Context context, SharedPreferences preferences, String copy, String paste, String computer) {
        this.preferences = preferences;
        app = context.getApplicationContext();
        transfers = new PhoneTransferNotifications(app, computer);
        copyPreference = copy;
        pastePreference = paste;
        peer = new ClipboardBridge.Peer(this, preferences.getBoolean(copy, true), preferences.getBoolean(paste, true));
        phone = (ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    static RdpClipboard create(Context ctx, long instance, SharedPreferences preferences, String copy, String paste) {
        return create(ctx, instance, preferences, copy, paste, null);
    }

    static RdpClipboard create(Context ctx, long instance, SharedPreferences preferences, String copy, String paste, String computer) {
        RdpClipboard clip = new RdpClipboard(ctx, preferences, copy, paste, computer);
        if (!clip.bind(instance, clip.token)) return null;
        preferences.registerOnSharedPreferenceChangeListener(clip);
        clip.onSharedPreferenceChanged(preferences, null);
        return clip;
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (closed || key != null && !copyPreference.equals(key) && !pastePreference.equals(key)) return;
        bridge.permissions(peer, prefs.getBoolean(copyPreference, true), prefs.getBoolean(pastePreference, true));
        ClipboardDocuments.discardStale();
        if (activePhone == this) focus(true);
    }

    void focus(boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(() -> focus(active)); return; }
        if (!active || closed) {
            if (activePhone == this) activePhone = null;
            if (listening) { phone.removePrimaryClipChangedListener(this); listening = false; }
            bridge.focus(peer, false);
            return;
        }
        if (activePhone != null && activePhone != this) activePhone.focus(false);
        activePhone = this;
        if (peer.canPaste && phone != null && !listening) {
            phone.addPrimaryClipChangedListener(this);
            listening = true;
        } else if (!peer.canPaste && listening) {
            phone.removePrimaryClipChangedListener(this);
            listening = false;
        }
        boolean changed = listening && sample(false);
        bridge.focus(peer, true, changed, observedText, changed ? localFiles() : null);
        if (changed) ClipboardDocuments.discardStale();
    }

    private ClipboardBridge.LocalFiles localFiles() {
        return observedFiles.isEmpty() ? null : PhoneClipboardFiles.create(app, observedFiles);
    }

    private void applyObserved() {
        bridge.phoneChanged(peer, observedText, localFiles());
        ClipboardDocuments.discardStale();
    }

    private static List<Uri> contentUris(ClipData clip) {
        if (clip == null) return Collections.emptyList();
        List<Uri> files = new ArrayList<>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null && "content".equals(uri.getScheme()) && uri.getAuthority() != null) files.add(uri);
        }
        return files;
    }

    private static boolean samePublishedFiles(List<Uri> files) {
        if (files.size() != lastPublishedFiles.size()) return false;
        for (int i = 0; i < files.size(); i++) {
            Uri published = lastPublishedFiles.get(i);
            Uri found = files.get(i);
            if (published.equals(found)) continue;
            // Some managers re-copy a document using its individual URI outside the tree.
            if (!DocumentsContract.buildDocumentUri(published.getAuthority(),
                    DocumentsContract.getDocumentId(published)).equals(found)) return false;
        }
        return true;
    }

    private boolean sample(boolean notification) {
        try {
            ClipDescription description = phone.getPrimaryClipDescription();
            if (description == null) {
                if (notification && bridge.withdrawPhoneOffer(peer)) {
                    resetObservation();
                    ClipboardDocuments.discardStale();
                }
                observed = true;
                return false;
            }
            long stamp = Build.VERSION.SDK_INT >= 26 ? description.getTimestamp() : 0;
            boolean hasOffer = bridge.hasOffer();
            if (!notification && hasOffer && stamp != 0 && observed && stamp == observedTime
                    && observedFiles.isEmpty() && lastPublishedFiles.isEmpty()) return false;
            ClipData clip = phone.getPrimaryClip();
            if (clip == null) return false;
            description = clip.getDescription();
            if (Build.VERSION.SDK_INT >= 26) stamp = description.getTimestamp();
            List<Uri> files = contentUris(clip);
            CharSequence item = clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
            String text = item == null || !files.isEmpty() ? null : item.toString();
            // DocumentsUI keeps the remainder of a jumbo selection in its own private store.
            // Never present the public prefix to Explorer as if it were the complete selection.
            boolean partial = Build.VERSION.SDK_INT >= 24 && !files.isEmpty() && description.getExtras() != null
                    && description.getExtras().getInt("jumboSelection-size", 0) > clip.getItemCount();
            // File managers can republish our documents with a fresh description, or retain the
            // marker while selecting only some of them. Compare the actual selection in either case.
            if (hasOffer && !partial && (files.isEmpty() && (text != null ? text.equals(lastPublishedText) : isOwn(description))
                    || !files.isEmpty() && samePublishedFiles(files))) {
                observed = true; observedTime = stamp; observedText = text; observedFiles = files;
                partialSelection = false;
                return false;
            }
            if (partial) {
                files.clear();
                text = null;
                if (!partialSelection || observedTime != stamp)
                    android.widget.Toast.makeText(app, com.undatech.remoteClientLib.R.string.clipboard_partial_selection,
                            android.widget.Toast.LENGTH_LONG).show();
            }
            boolean changed = !hasOffer && (text != null || !files.isEmpty()) || observed && (!Objects.equals(observedText, text) || !files.equals(observedFiles)
                    || stamp != observedTime || partialSelection != partial || notification && stamp == 0)
                    || partial && !observed;
            observed = true; observedTime = stamp; observedText = text;
            observedFiles = files;
            partialSelection = partial;
            if (changed) {
                lastPublishedText = null;
                lastPublishedFiles = Collections.emptyList();
            }
            return changed;
        } catch (SecurityException | IllegalStateException unavailable) {
            return false;
        }
    }

    private static boolean isOwn(ClipDescription description) {
        if (Build.VERSION.SDK_INT >= 24) return description.getExtras() != null
                && ownToken.equals(description.getExtras().getString(MARKER));
        CharSequence label = description.getLabel();
        return label != null && ownToken.contentEquals(label);
    }

    private static void resetObservation() {
        observed = false;
        observedTime = 0;
        observedText = null;
        observedFiles = Collections.emptyList();
        lastPublishedText = null;
        lastPublishedFiles = Collections.emptyList();
        partialSelection = false;
    }

    @Override public void onPrimaryClipChanged() {
        if (activePhone == this && listening && sample(true)) applyObserved();
    }

    @Override public void phoneText(long revision, String text) {
        ui.post(() -> publish(revision, text, null));
    }

    @Override public boolean acceptsDocuments() { return phone != null; }
    @Override public ClipboardBridge.PhoneProgress phoneProgress() { return transfers; }

    @Override public void phoneDocuments(long revision, byte[] descriptors) {
        PhoneClipboardFiles.io.execute(() -> {
            if (!bridge.isCurrent(revision)) return;
            try {
                ClipboardDocuments.Selection selection = new ClipboardDocuments.Selection(bridge, revision, descriptors);
                ui.post(() -> publish(revision, null, selection));
            } catch (IOException unavailable) { /* Ignore an invalid remote file list. */ }
        });
    }

    private void publish(long revision, String text, ClipboardDocuments.Selection selection) {
        if (!bridge.isCurrent(revision) || phone == null) return;
        if (activePhone != null && activePhone.listening && activePhone.sample(false)) activePhone.applyObserved();
        if (!bridge.isCurrent(revision)) return;
        String label = Build.VERSION.SDK_INT < 24 ? ownToken : "RDP";
        ClipData clip = selection == null ? ClipData.newPlainText(label, text) : selection.clip(app, label);
        if (Build.VERSION.SDK_INT >= 24) {
            PersistableBundle mark = clip.getDescription().getExtras();
            if (mark == null) mark = new PersistableBundle();
            mark.putString(MARKER, ownToken);
            clip.getDescription().setExtras(mark);
        }
        String previousText = lastPublishedText;
        List<Uri> previousFiles = lastPublishedFiles;
        try {
            ClipboardDocuments.publish(selection);
            lastPublishedText = text;
            lastPublishedFiles = contentUris(clip);
            phone.setPrimaryClip(clip);
            observed = true; observedText = text;
            observedFiles = lastPublishedFiles;
            partialSelection = false;
        } catch (SecurityException | IllegalStateException unavailable) {
            lastPublishedText = previousText;
            lastPublishedFiles = previousFiles;
            // Inter-desktop paste still uses the original offer if Android denies clipboard access.
        }
    }

    void localText(String text) { bridge.phoneChanged(peer, text); }

    void beforeKey(int virtualKey, boolean down) {
        // This runs on the same ordered worker that delivers physical and on-screen Windows keys.
        int key = virtualKey & 0xff;
        int bit = key == 0xa2 ? 1 : key == 0xa3 ? 2 : key == 0xa0 ? 4 : key == 0xa1 ? 8 : 0;
        if (bit != 0) { modifiers = down ? modifiers | bit : modifiers & ~bit; return; }
        if (!down) return;
        boolean ctrl = (modifiers & 3) != 0, shift = (modifiers & 12) != 0;
        if (ctrl && (key == 'C' || key == 'X' || key == 0x2d)) bridge.copying(peer);
        else if (ctrl && key == 'V' || shift && key == 0x2d) {
            try { bridge.beforePaste(peer); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
    }

    private static void scheduleExpiry() {
        if (ticking.compareAndSet(false, true)) ui.postDelayed(() -> {
            ticking.set(false);
            bridge.expire();
            if (bridge.hasRequests()) scheduleExpiry();
        }, 5000);
    }

    private void onReady(int flags) { bridge.ready(peer, flags); }
    private void onFormats(int[] ids, String[] names) {
        bridge.remoteFormats(peer, ids, names);
        ClipboardDocuments.discardStale();
        scheduleExpiry();
    }
    private void onAcknowledged(boolean ok) { bridge.acknowledged(peer, ok); }
    private void onFormatRequest(int id) { bridge.requestFormat(peer, id); scheduleExpiry(); }
    private void onFormatData(byte[] data) { bridge.formatData(peer, data); }
    private void onFileRequest(int stream,int index,int flags,int low,int high,int count,boolean locked,int id) {
        bridge.requestFile(peer, stream, index, flags, low, high, count, locked, id);
        scheduleExpiry();
    }
    private void onFileData(int stream, byte[] data) { bridge.fileData(peer, stream, data); }
    private void onLock(int id, boolean acquire) { bridge.lock(peer, id, acquire); }
    private void onOffline(boolean permanent) {
        if (permanent) closed = true;
        bridge.offline(peer, permanent);
        ClipboardDocuments.discardStale();
        if (permanent) {
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            ui.post(() -> {
                focus(false);
                if (activePhone == null && !bridge.hasOffer()) resetObservation();
            });
        }
    }

    @Override public boolean formats(int[] ids, String[] names) { return sendFormats(token, ids, names); }
    @Override public boolean readFormat(int id) { return getFormat(token, id); }
    @Override public void formatResult(byte[] data) { sendData(token, false, 0, data); }
    @Override public boolean readFile(int stream,int index,int flags,int low,int high,int count,boolean locked,int id) {
        return getFile(token, stream, index, flags, low, high, count, locked, id);
    }
    @Override public void fileResult(int stream, byte[] data) { sendData(token, true, stream, data); }
    @Override public boolean lock(int id, boolean acquire) { return sendLock(token, id, acquire); }

    private native boolean bind(long instance, long token);
    private static native boolean sendFormats(long token,int[] ids,String[] names);
    private static native boolean getFormat(long token,int id);
    private static native void sendData(long token,boolean file,int stream,byte[] data);
    private static native boolean getFile(long token,int stream,int index,int flags,int low,int high,int count,boolean locked,int id);
    private static native boolean sendLock(long token,int id,boolean acquire);
}
