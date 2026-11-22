package com.undatech.opaque;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;

import androidx.core.app.NotificationCompat;

import com.undatech.remoteClientLib.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One set of clipboard transfers per RDP connection. Android document handles and Windows
 * clipboard locks identify independent copies, even when names, offsets or stream IDs coincide. */
final class PhoneTransferNotifications implements ClipboardBridge.PhoneProgress {
    private static final AtomicInteger ids = new AtomicInteger(0x52440000);
    private static final long REFRESH_MS = 1000, IDLE_MS = 65000;

    private final Context app;
    private final String computer;
    private final NotificationManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IdentityHashMap<Object, Map<Integer, Item>> groups = new IdentityHashMap<>();
    private final ArrayList<Integer> cancellations = new ArrayList<>();
    private boolean scheduled;
    private String channel;

    PhoneTransferNotifications(Context context, String computer) {
        app = context.getApplicationContext();
        this.computer = computer == null || computer.trim().isEmpty() ? app.getString(R.string.clipboard_computer) : computer;
        manager = (NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    // Count unique bytes. A reader can seek to the end of a video, retry a range, or read out of order.
    static final class Coverage {
        long bytes;
        private long prefix;
        private final TreeMap<Long, Long> spans = new TreeMap<>();

        void add(long start, int count, long size) {
            if (start < 0 || count <= 0 || start > Long.MAX_VALUE - count) return;
            long end = start + count;
            if (size >= 0) end = Math.min(end, size);
            start = Math.max(start, prefix);
            if (end <= start) return;
            Map.Entry<Long, Long> span = spans.floorEntry(start);
            if (span == null || span.getValue() < start) span = spans.ceilingEntry(start);
            while (span != null && span.getKey() <= end) {
                start = Math.min(start, span.getKey());
                end = Math.max(end, span.getValue());
                bytes -= span.getValue() - span.getKey();
                spans.remove(span.getKey());
                span = spans.ceilingEntry(start);
            }
            if (start == prefix) prefix = end;
            else if (spans.size() < 1024) spans.put(start, end);
            else return; // Bound memory for scattered preview reads. A later sequential pass counts them.
            bytes += end - start;
        }
    }

    private static final class Item {
        final int id = ids.incrementAndGet();
        final String name;
        final boolean toPhone;
        final Coverage coverage = new Coverage();
        final long started = SystemClock.elapsedRealtime();
        long active = started, total = -1, shownBytes = -1, shownTotal = -1;
        Item(String name, boolean toPhone) { this.name = name; this.toPhone = toPhone; }
    }

    @Override public synchronized void range(Object group, int index, String name, boolean toPhone,
                                              long total, long offset, int bytes) {
        if (offset < 0 || total >= 0 && offset >= total) return;
        Map<Integer, Item> files = groups.get(group);
        if (files == null) {
            files = new HashMap<>();
            groups.put(group, files);
        }
        Item item = files.get(index);
        if (item == null) {
            item = new Item(name, toPhone);
            files.put(index, item);
        }
        item.active = SystemClock.elapsedRealtime();
        if (total >= 0) item.total = total;
        item.coverage.add(offset, bytes, item.total);
        if (item.total >= 0 && item.coverage.bytes >= item.total) endFile(group, index);
        schedule();
    }

    @Override public synchronized void endFile(Object group, int index) {
        Map<Integer, Item> files = groups.get(group);
        if (files == null) return;
        cancel(files.remove(index));
        if (files.isEmpty()) groups.remove(group);
    }

    @Override public synchronized void endGroup(Object group) {
        Map<Integer, Item> files = groups.remove(group);
        if (files != null) for (Item item : files.values()) cancel(item);
    }

    @Override public synchronized void stop(boolean toPhone, boolean toComputer) {
        Iterator<Map<Integer, Item>> groupsIterator = groups.values().iterator();
        while (groupsIterator.hasNext()) {
            Map<Integer, Item> files = groupsIterator.next();
            Iterator<Item> items = files.values().iterator();
            while (items.hasNext()) {
                Item item = items.next();
                if (item.toPhone ? toPhone : toComputer) {
                    items.remove();
                    cancel(item);
                }
            }
            if (files.isEmpty()) groupsIterator.remove();
        }
    }

    private void cancel(Item item) {
        if (item != null && item.shownBytes >= 0) {
            cancellations.add(item.id);
            schedule();
        }
    }

    private void schedule() {
        if (!scheduled && (!groups.isEmpty() || !cancellations.isEmpty())) {
            scheduled = true;
            handler.postDelayed(this::refresh, REFRESH_MS);
        }
    }

    private void refresh() {
        ArrayList<Runnable> updates = new ArrayList<>();
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            Iterator<Map<Integer, Item>> all = groups.values().iterator();
            while (all.hasNext()) {
                Map<Integer, Item> files = all.next();
                Iterator<Item> items = files.values().iterator();
                while (items.hasNext()) {
                    Item item = items.next();
                    long bytes = item.coverage.bytes, total = item.total;
                    if (now - item.active >= IDLE_MS) {
                        items.remove();
                        cancel(item);
                    } else if (now - item.started >= REFRESH_MS && (item.shownBytes != bytes || item.shownTotal != total)) {
                        item.shownBytes = bytes;
                        item.shownTotal = total;
                        updates.add(() -> show(item, bytes, total));
                    }
                }
                if (files.isEmpty()) all.remove();
            }
            for (int id : cancellations) updates.add(() -> ClipboardTransferService.remove(app, id));
            cancellations.clear();
            scheduled = false;
            schedule();
        }
        // Binder calls stay off the protocol and file-reader locks.
        if (manager != null) for (Runnable update : updates) {
            try { update.run(); }
            catch (SecurityException | IllegalStateException ignored) { /* Notifications are optional. */ }
        }
    }

    private void show(Item item, long bytes, long total) {
        channel = TransferNotificationChannel.id(app, channel);
        String phone = app.getString(R.string.clipboard_phone);
        String route = app.getString(R.string.clipboard_transfer_route, item.toPhone ? computer : phone, item.toPhone ? phone : computer);
        String amount = Formatter.formatShortFileSize(app, bytes);
        int percent = total > 0 ? (int)Math.min(99, 100.0 * bytes / total) : 0;
        if (total > 0) amount = app.getString(R.string.clipboard_transfer_amount, percent, amount, Formatter.formatShortFileSize(app, total));
        NotificationCompat.Builder notice = new NotificationCompat.Builder(app, channel)
                .setSmallIcon(item.toPhone ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_upload)
                .setContentTitle(item.name).setSubText(route).setContentText(amount)
                .setProgress(100, percent, total < 0)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true)
                .setSilent(true).setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setShowWhen(false).setTimeoutAfter(IDLE_MS + 15000);
        ClipboardTransferService.show(app, item.id, notice.build());
    }
}
