package com.undatech.opaque;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Routes MS-RDPECLIP between the desktops the user visits. Wire sends only enqueue a channel PDU. */
final class ClipboardBridge {
    static final int FILES = 4, LOCKS = 16, HUGE_FILES = 32;
    private static final int TEXT = 1, UNICODE = 13;
    private static final int SIZE = 1, RANGE = 2;
    private static final long HANDOFF_MS = 5000, RESPONSE_MS = 30000;
    private static final int MAX_PENDING = 256;
    private static final int MAX_CACHED_TEXT = 4 * 1024 * 1024;
    private static final String DESCRIPTORS = "FileGroupDescriptorW", CONTENTS = "FileContents", EFFECT = "Preferred DropEffect";
    private static final int FILE_DESCRIPTOR_SIZE = 592, FILENAME_OFFSET = 72;
    private static final Pattern DEVICE_NAME = Pattern.compile("(?i)(?:CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³])(?:\\..*)?");

    interface Wire {
        boolean formats(int[] ids, String[] names);
        boolean readFormat(int id);
        void formatResult(byte[] data);
        boolean readFile(int stream, int index, int flags, int low, int high, int count, boolean locked, int lock);
        void fileResult(int stream, byte[] data);
        boolean lock(int id, boolean acquire);
        void phoneText(long revision, String text);
        default boolean acceptsDocuments() { return false; }
        default void phoneDocuments(long revision, byte[] descriptors) {}
        default PhoneProgress phoneProgress() { return null; }
    }

    interface Result { void finish(byte[] data); }

    interface PhoneProgress {
        void range(Object group, int index, String name, boolean toPhone, long total, long offset, int bytes);
        void endFile(Object group, int index);
        void endGroup(Object group);
        void stop(boolean toPhone, boolean toComputer);
    }

    interface PhoneFile {
        String name();
        long size();
    }

    /** The initial reference belongs to the clipboard; a Windows file lock can keep it alive. */
    interface LocalFiles {
        void describe(Result result);
        void read(int index, boolean size, long offset, int length, Result result);
        void retain();
        void release();
        default RemoteFiles sourceFiles() { return null; }
        default PhoneFile phoneFile(int index) { return null; }
    }

    static final class Peer {
        final Wire wire;
        volatile boolean canCopy, canPaste;
        boolean ready, closed;
        int capabilities, nextId;
        Offer owned, advertised;
        long pendingAcks, initialListUntil, handoffUntil;
        final ArrayDeque<Reply> replies = new ArrayDeque<>();
        final ArrayDeque<Read> reads = new ArrayDeque<>();
        Read reading;
        final Map<Integer, Transfer> transfers = new HashMap<>();
        final Map<Integer, Lock> locks = new HashMap<>();
        final Map<Integer, LocalRead> localReads = new HashMap<>();

        Peer(Wire wire, boolean canCopy, boolean canPaste) {
            this.wire = wire;
            this.canCopy = canCopy;
            this.canPaste = canPaste;
        }
    }

    private static final class Offer {
        final long revision;
        final Peer source;
        final LinkedHashMap<Integer, String> formats = new LinkedHashMap<>();
        byte[] text;
        byte[] descriptors;
        LocalFiles local;
        int textId, fileCount = -1;
        boolean containsHugeFile;

        Offer(long revision, Peer source) { this.revision = revision; this.source = source; }

        RemoteFiles backing() { return local == null ? null : local.sourceFiles(); }

        boolean dependsOn(Peer p) {
            RemoteFiles files = backing();
            return source == p || files != null && files.origin.source == p;
        }

        boolean visible(int id, Peer to) {
            if (!formats.containsKey(id)) return false;
            String name = formats.get(id);
            return !fileFormat(name) || (to.capabilities & FILES) != 0;
        }
    }

    /** Both directions of Format Data are untagged. Answer a destination in its request order. */
    private static final class Reply {
        final Peer peer;
        final long deadline = now() + RESPONSE_MS;
        boolean complete;
        byte[] data;
        Reply(Peer peer) { this.peer = peer; peer.replies.addLast(this); }
        void finish(byte[] bytes) {
            if (complete || !peer.ready) return;
            data = bytes;
            complete = true;
            while (!peer.replies.isEmpty() && peer.replies.peekFirst().complete)
                peer.wire.formatResult(peer.replies.removeFirst().data);
        }
    }

    private static final class LocalRead {
        final long deadline = now() + RESPONSE_MS;
        final PhoneProgress progress;
        final Object group;
        final int index;
        LocalRead(PhoneProgress progress, Object group, int index) {
            this.progress = progress; this.group = group; this.index = index;
        }
    }

    private static final class Read {
        final Offer offer;
        final int id;
        final Reply reply;
        long deadline;
        boolean finished;
        Read(Offer offer, int id, Reply reply) { this.offer = offer; this.id = id; this.reply = reply; }
        void finish(byte[] data) {
            if (!finished && reply != null) reply.finish(data);
            finished = true;
        }
    }

    private static final class Lock {
        final Offer offer;
        final int id;
        final boolean forwarded;
        PhoneProgress phoneProgress;
        Lock(Offer offer, int id, boolean forwarded) {
            this.offer = offer; this.id = id; this.forwarded = forwarded;
        }
    }

    private static final class Transfer {
        final Peer to;
        final int stream, flags, count;
        final Offer offer;
        final boolean locked;
        final long deadline = now() + RESPONSE_MS;
        Transfer(Peer to, int stream, int flags, int count, Offer offer, boolean locked) {
            this.to = to; this.stream = stream; this.flags = flags; this.count = count; this.offer = offer; this.locked = locked;
        }
    }

    private final Set<Peer> peers = new HashSet<>();
    private Peer focused, recent, copying;
    private boolean firstListAfterFocus;
    private Offer current;
    private long revision, recentRevision, recentUntil, copyUntil;
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    synchronized void focus(Peer p, boolean active) {
        focus(p, active, false, null);
    }

    synchronized void focus(Peer p, boolean active, boolean phoneChanged, String text) {
        focus(p, active, phoneChanged, text, null);
    }

    synchronized void focus(Peer p, boolean active, boolean phoneChanged, String text, LocalFiles files) {
        if (files != null && (p.closed || !active || !phoneChanged)) files.release();
        if (p.closed) return;
        if (active) {
            if (focused != p) {
                if (focused != null) rememberFocusLoss(focused);
                focused = p;
                firstListAfterFocus = true;
            }
            if (phoneChanged) phoneChanged(p, text, files);
            else publishTo(p, false);
        } else if (focused == p) {
            rememberFocusLoss(p);
            focused = null;
        }
        notifyAll();
    }

    private void rememberFocusLoss(Peer p) {
        recent = p;
        recentRevision = revision;
        recentUntil = now() + HANDOFF_MS;
    }

    synchronized void ready(Peer p, int flags) {
        if (p.closed) return;
        // A new Monitor Ready starts a fresh clipboard protocol epoch on this transport.
        if (p.ready) offline(p, false);
        p.capabilities = flags;
        p.ready = true;
        p.initialListUntil = now() + HANDOFF_MS;
        peers.add(p);
        publishTo(p, true);
    }

    synchronized void permissions(Peer p, boolean copy, boolean paste) {
        if (p.closed || p.canCopy == copy && p.canPaste == paste) return;
        p.canCopy = copy;
        p.canPaste = paste;
        if (p.wire.phoneProgress() != null) p.wire.phoneProgress().stop(!copy, !paste);
        if (!copy) {
            if (copying == p) copying = null;
            if (current != null && current.dependsOn(p)) replaceCurrent(new Offer(++revision, null));
            p.owned = null;
            if (p.reading != null) p.reading.finish(null);
            while (!p.reads.isEmpty()) p.reads.removeFirst().finish(null);
        }
        if (!paste) {
            for (int stream : p.localReads.keySet()) p.wire.fileResult(stream, null);
            p.localReads.clear();
            if (current != null && current.local != null) {
                boolean shared = false;
                for (Peer other : peers) if (other.canPaste && !(other.wire instanceof RemoteFiles)) { shared = true; break; }
                if (!shared) replaceCurrent(null);
            }
        }
        for (Peer other : peers) {
            Iterator<Lock> locks = other.locks.values().iterator();
            while (locks.hasNext()) {
                Lock l = locks.next();
                if ((!paste && other == p) || (!copy && l.offer.dependsOn(p))) {
                    release(l);
                    locks.remove();
                }
            }
            Iterator<Transfer> transfers = other.transfers.values().iterator();
            while (transfers.hasNext()) {
                Transfer t = transfers.next();
                if ((!copy && other == p) || (!paste && t.to == p)) {
                    transfers.remove();
                    t.to.wire.fileResult(t.stream, null);
                }
            }
            if ((!copy && other.advertised != null && other.advertised.dependsOn(p)) || (!paste && other == p))
                publishTo(other, false);
        }
        publishTo(focused, false);
        notifyAll();
    }

    private void publishTo(Peer p, boolean initial) {
        if (p == null || !p.ready) return;
        Offer offer = p == focused && p.canPaste ? current : null;
        if (offer != null && offer.source == p) return;
        // A source without clipboard locking must keep the original files available for readers.
        RemoteFiles backing = offer == null ? null : offer.backing();
        if (backing != null && backing.origin.source == p && (p.capabilities & LOCKS) == 0) return;
        if (offer != null && !initial && copying == p && now() < copyUntil) return;
        if (!initial && p.advertised == offer) return;
        Map<Integer, String> list = new LinkedHashMap<>();
        if (offer != null) for (Map.Entry<Integer, String> e : offer.formats.entrySet())
            if (offer.visible(e.getKey(), p)) list.put(e.getKey(), e.getValue());
        int[] ids = new int[list.size()];
        String[] names = new String[list.size()];
        int i = 0;
        for (Map.Entry<Integer, String> e : list.entrySet()) {
            ids[i] = e.getKey();
            names[i++] = e.getValue();
        }
        if (p.wire.formats(ids, names)) {
            p.advertised = offer;
            p.owned = null;
            p.pendingAcks++;
            p.handoffUntil = offer != null && !offer.formats.isEmpty() ? now() + HANDOFF_MS : 0;
        }
    }

    synchronized void acknowledged(Peer p, boolean ok) {
        if (p.pendingAcks > 0 && --p.pendingAcks == 0 && !ok) p.advertised = null;
        notifyAll();
    }

    synchronized void copying(Peer p) {
        long time = now();
        if (p.canCopy && (p == focused || p == recent && time < recentUntil)) {
            if (p == focused && recent != p) recent = null;
            copying = p;
            copyUntil = time + HANDOFF_MS;
            notifyAll();
        }
    }

    /** Called by the destination's serial input worker, never by the UI or the channel reader. */
    synchronized void beforePaste(Peer p) throws InterruptedException {
        while (p.ready && p == focused && copying != null && copying != p) {
            long remaining = copyUntil - now();
            if (remaining <= 0) break;
            wait(remaining);
        }
        long end = now() + 1000;
        while (p.ready && p == focused && p.advertised != null && p.pendingAcks > 0) {
            long remaining = end - now();
            if (remaining <= 0) return;
            wait(remaining);
        }
    }

    synchronized void remoteFormats(Peer p, int[] ids, String[] names) {
        if (!p.ready) return;
        long time = now();
        boolean pendingCopy = copying != null && time < copyUntil;
        boolean requestedCopy = pendingCopy && copying == p;
        boolean initialEmpty = ids.length == 0 && time <= p.initialListUntil;
        boolean firstDestinationList = p == focused && firstListAfterFocus;
        if (p == focused) firstListAfterFocus = false;
        p.initialListUntil = 0;
        Offer old = p.owned;
        p.owned = null;
        // A server announcing its own clipboard now owns that endpoint, even when it is backgrounded.
        Offer previousAdvertisement = p.advertised;
        p.advertised = null;
        boolean recentCanCopy = recent != null && time <= recentUntil && recentRevision == revision;
        if (!p.canCopy || !(requestedCopy || p == focused || !pendingCopy && p == recent && recentCanCopy)) {
            RemoteFiles backing = current == null ? null : current.backing();
            if (old != null && (current == old || backing != null && backing.origin == old && !backing.available()))
                sourceLost(current);
            return;
        }
        Offer offer = new Offer(revision + 1, p);
        boolean files = (p.capabilities & FILES) != 0;
        for (int i = 0; i < ids.length; i++) {
            String name = names[i];
            if (portable(ids[i], name) || files && fileFormat(name)) offer.formats.put(ids[i], name);
        }
        if (!offer.formats.containsValue(DESCRIPTORS)) {
            Iterator<String> it = offer.formats.values().iterator();
            while (it.hasNext()) if (fileFormat(it.next())) it.remove();
        } else if (!offer.formats.containsValue(CONTENTS)) {
            // Peers may imply streaming by advertising only the file list. Explorer's OLE
            // data object also needs the named FileContents format for a destination paste.
            for (int id = 0xc000; id <= 0xffff; id++) {
                if (!offer.formats.containsKey(id)) { offer.formats.put(id, CONTENTS); break; }
            }
        }
        if (initialEmpty && (old == null || old != current) && !requestedCopy) {
            // A newly connected host often announces an empty clipboard during initialization.
            if (p == focused && current != null && current.source != p) {
                p.advertised = previousAdvertisement;
                publishTo(p, false);
            }
            return;
        }
        // A destination can announce its old clipboard while switching desktops. In an already
        // focused session, a menu Copy can also supersede a phone offer before its ACK arrives.
        if (!offer.formats.isEmpty() && !requestedCopy && p == focused && previousAdvertisement != null
                && previousAdvertisement == current && (firstDestinationList || previousAdvertisement.source != null)
                && p.handoffUntil > time && p.pendingAcks > 0) {
            long deadline = p.handoffUntil;
            publishTo(p, false);
            p.handoffUntil = deadline;
            return;
        }
        // A context-menu Copy has no keyboard signal. The first passive update after switching
        // can also race the previous desktop. Subsequent copies on this desktop supersede it.
        if (firstDestinationList && !offer.formats.isEmpty() && !requestedCopy && p != recent && recentCanCopy)
            recentRevision = offer.revision;
        revision = offer.revision;
        replaceCurrent(offer);
        p.owned = offer;
        // A passive update on the new desktop is a fallback if the user's Copy produces no data.
        // Keep waiting for the explicit source even if that update advances the shared revision.
        if (requestedCopy || !pendingCopy) copying = null;
        publishTo(focused, false);
        if (p.wire.acceptsDocuments() && offer.formats.containsValue(DESCRIPTORS)) {
            for (Map.Entry<Integer, String> e : offer.formats.entrySet()) {
                if (DESCRIPTORS.equals(e.getValue())) {
                    enqueueRead(p, new Read(offer, e.getKey(), null));
                    break;
                }
            }
        } else readPhoneText(offer);
        notifyAll();
    }

    private void readPhoneText(Offer offer) {
        int id = offer.formats.containsKey(UNICODE) ? UNICODE : offer.formats.containsKey(TEXT) ? TEXT : 0;
        if (id != 0) enqueueRead(offer.source, new Read(offer, id, null));
    }

    private static boolean portable(int id, String name) {
        if (name == null || name.isEmpty())
            return id == TEXT || id == 3 || id == 4 || id == 5 || id == 6 || id == 7 || id == 8
                    || id == 9 || id == 11 || id == UNICODE || id == 14 || id == 16 || id == 17;
        return name.equalsIgnoreCase("HTML Format") || name.equalsIgnoreCase("Rich Text Format")
                || name.equalsIgnoreCase("PNG") || name.equalsIgnoreCase("JFIF") || name.equalsIgnoreCase("GIF")
                || name.equalsIgnoreCase("image/png") || name.equalsIgnoreCase("image/jpeg") || name.equalsIgnoreCase("text/html")
                || name.equalsIgnoreCase("Biff8") || name.equalsIgnoreCase("Biff12") || name.equalsIgnoreCase("XML Spreadsheet")
                || name.equalsIgnoreCase("Csv") || name.equalsIgnoreCase("image/webp");
    }

    private static boolean fileFormat(String name) {
        return DESCRIPTORS.equals(name) || CONTENTS.equals(name) || EFFECT.equals(name);
    }

    synchronized boolean isCurrent(long version) { return current != null && current.revision == version; }
    synchronized boolean hasOffer() { return current != null; }

    synchronized boolean hasDocuments(long version) {
        if (isCurrent(version)) return true;
        RemoteFiles backing = current == null ? null : current.backing();
        return backing != null && backing.origin.revision == version && backing.available();
    }

    synchronized void phoneChanged(Peer p, String text) {
        phoneChanged(p, text, null);
    }

    synchronized boolean withdrawPhoneOffer(Peer p) {
        // Android reports a missing clip both on clear and when clipboard access is unavailable.
        // This can withdraw data supplied by the phone, but cannot erase a desktop's native copy.
        if (p != focused || !p.canPaste || p.closed || current == null || current.source != null
                || current.formats.isEmpty()) return false;
        phoneChanged(p, null);
        return true;
    }

    private void replaceCurrent(Offer next) {
        if (current != null && current.local != null) {
            for (Peer peer : peers) {
                PhoneProgress progress = peer.wire.phoneProgress();
                if (progress != null) progress.endGroup(current);
            }
            current.local.release();
        }
        current = next;
    }

    synchronized void phoneChanged(Peer p, String text, LocalFiles files) {
        if (p != focused || !p.canPaste || p.closed) {
            if (files != null) files.release();
            return;
        }
        Offer offer = new Offer(++revision, null);
        if (files != null) {
            offer.local = files;
            offer.formats.put(0xc001, DESCRIPTORS);
            offer.formats.put(0xc002, EFFECT);
            offer.formats.put(0xc003, CONTENTS);
        } else if (text != null && !text.isEmpty()) {
            offer.formats.put(UNICODE, null);
            offer.textId = UNICODE;
            offer.text = (text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n") + '\0')
                    .getBytes(StandardCharsets.UTF_16LE);
        }
        copying = null;
        recent = null;
        replaceCurrent(offer);
        publishTo(p, false);
        notifyAll();
    }

    synchronized void requestFormat(Peer p, int id) {
        if (!p.ready) return;
        if (p.replies.size() >= MAX_PENDING) {
            while (!p.replies.isEmpty()) {
                p.replies.removeFirst().complete = true;
                p.wire.formatResult(null);
            }
            p.wire.formatResult(null);
            return;
        }
        Reply reply = new Reply(p);
        Offer offer = p.advertised;
        if (offer == null || !offer.visible(id, p)) { reply.finish(null); return; }
        // Requesting a format also proves the destination has observed this advertisement.
        p.handoffUntil = 0;
        // Indexed file data travels in FILECONTENTS_REQUEST/RESPONSE, never in FORMAT_DATA.
        if (CONTENTS.equals(offer.formats.get(id))) { reply.finish(null); return; }
        if (offer.local != null && current != offer) { reply.finish(null); return; }
        if (EFFECT.equals(offer.formats.get(id))) { reply.finish(new byte[]{1,0,0,0}); return; }
        if (DESCRIPTORS.equals(offer.formats.get(id))) {
            if (offer.descriptors != null) { reply.finish(descriptorsFor(offer, p)); return; }
            if (offer.local != null) {
                offer.local.describe(bytes -> {
                    synchronized (ClipboardBridge.this) {
                        if (bytes != null && p.advertised == offer && current == offer && p.canPaste
                                && !reply.complete && p.ready) saveDescriptors(offer, bytes);
                        reply.finish(current == offer && p.advertised == offer ? descriptorsFor(offer, p) : null);
                    }
                });
                return;
            }
        }
        if (offer.text != null && offer.textId == id) { reply.finish(offer.text); return; }
        if (offer.source == null || offer.source.owned != offer) { reply.finish(null); return; }
        enqueueRead(offer.source, new Read(offer, id, reply));
    }

    private void enqueueRead(Peer source, Read read) {
        discardObsoleteReads(source, now());
        if (source.reads.size() >= MAX_PENDING) { read.finish(null); return; }
        source.reads.addLast(read);
        pump(source);
    }

    private void discardObsoleteReads(Peer p, long time) {
        Iterator<Read> it = p.reads.iterator();
        while (it.hasNext()) {
            Read read = it.next();
            if (read.offer == p.owned && (read.reply == null || !read.reply.complete && time < read.reply.deadline)) continue;
            it.remove();
            read.finish(null);
        }
    }

    private void pump(Peer p) {
        // Format responses have no ID. Only one format request may be outstanding per source.
        while (p.reading == null && !p.reads.isEmpty()) {
            Read read = p.reads.removeFirst();
            Peer to = read.reply == null ? null : read.reply.peer;
            if (read.offer != p.owned || to != null && (read.reply.complete || !to.ready || to.advertised != read.offer))
                read.finish(null);
            else if (read.offer.text != null && read.offer.textId == read.id) {
                if (to == null && current == read.offer) p.wire.phoneText(read.offer.revision, plainText(read.id, read.offer.text));
                read.finish(read.offer.text);
            } else if (read.offer.descriptors != null && DESCRIPTORS.equals(read.offer.formats.get(read.id))) {
                if (to == null && current == read.offer) p.wire.phoneDocuments(read.offer.revision, read.offer.descriptors);
                read.finish(to == null ? read.offer.descriptors : descriptorsFor(read.offer, to));
            } else if (p.wire.readFormat(read.id)) {
                read.deadline = now() + RESPONSE_MS;
                p.reading = read;
            }
            else read.finish(null);
        }
    }

    synchronized void formatData(Peer source, byte[] data) {
        Read read = source.reading;
        if (read == null) return;
        source.reading = null;
        Offer offer = read.offer;
        if (read.finished || source.owned != offer || read.reply != null && read.reply.peer.advertised != offer) data = null;
        if (data != null && DESCRIPTORS.equals(offer.formats.get(read.id))) {
            saveDescriptors(offer, data);
            data = read.reply == null ? offer.descriptors : descriptorsFor(offer, read.reply.peer);
            if (data != null && read.reply == null && current == offer) source.wire.phoneDocuments(offer.revision, data);
            if (!source.wire.acceptsDocuments()) offer.descriptors = null;
        }
        if (data != null && (read.id == TEXT || read.id == UNICODE) && data.length <= MAX_CACHED_TEXT) {
            if (offer.text == null || read.id == UNICODE) {
                offer.text = data; offer.textId = read.id;
            }
            if (read.reply == null && current == offer && offer.descriptors == null)
                source.wire.phoneText(offer.revision, plainText(read.id, data));
        }
        read.finish(data);
        if (read.reply == null && current == offer && offer.descriptors == null
                && DESCRIPTORS.equals(offer.formats.get(read.id))) readPhoneText(offer);
        pump(source);
    }

    private static void saveDescriptors(Offer offer, byte[] bytes) {
        offer.fileCount = inspectDescriptors(bytes, offer);
        // Remote lists cached for the phone are also useful for a simultaneous paste on another PC.
        if (offer.fileCount >= 0) offer.descriptors = bytes;
        else offer.descriptors = null;
    }

    private static byte[] descriptorsFor(Offer offer, Peer to) {
        if (offer.containsHugeFile && (offer.source != null && (offer.source.capabilities & HUGE_FILES) == 0
                || to != null && (to.capabilities & HUGE_FILES) == 0)) return null;
        return offer.descriptors;
    }

    private static boolean hugeEntry(Offer offer, int index) {
        if (!offer.containsHugeFile || offer.descriptors == null) return offer.containsHugeFile;
        int start = 4 + index * FILE_DESCRIPTOR_SIZE;
        byte[] data = offer.descriptors;
        return (data[start] & 0x40) != 0 && (data[start+64] | data[start+65] | data[start+66] | data[start+67]) != 0;
    }

    static String plainText(int id, byte[] data) {
        int end = 0;
        if (id == UNICODE) {
            while (end + 1 < data.length && (data[end] != 0 || data[end+1] != 0)) end += 2;
            return new String(data, 0, end, StandardCharsets.UTF_16LE);
        }
        while (end < data.length && data[end] != 0) end++;
        return new String(data, 0, end, Charset.forName("windows-1252"));
    }

    /** Wire FILEDESCRIPTORW records contain a 260-WCHAR relative name starting at byte 72. */
    private static int inspectDescriptors(byte[] data, Offer offer) {
        offer.containsHugeFile = false;
        if (data.length < 4) return -1;
        long count = (data[0] & 255L) | (data[1] & 255L) << 8 | (data[2] & 255L) << 16 | (data[3] & 255L) << 24;
        if (4 + count * FILE_DESCRIPTOR_SIZE != data.length) return -1;
        for (int i = 0; i < count; i++) {
            int offset = 4 + FILE_DESCRIPTOR_SIZE * i;
            // FD_FILESIZE makes nFileSizeHigh (offset 64) meaningful for this entry.
            if ((data[offset] & 0x40) != 0 &&
                    (data[offset + 64] | data[offset + 65] | data[offset + 66] | data[offset + 67]) != 0)
                offer.containsHugeFile = true;
            int start = offset + FILENAME_OFFSET, end = start;
            while (end < start + 520 && (data[end] != 0 || data[end+1] != 0)) end += 2;
            if (end == start || end == start + 520) return -1;
            String path = new String(data, start, end - start, StandardCharsets.UTF_16LE).replace('\\', '/');
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.indexOf(':') >= 0 || part.endsWith(".") || part.endsWith(" ")
                        || DEVICE_NAME.matcher(part).matches()) return -1;
            }
        }
        return (int)count;
    }

    synchronized void lock(Peer p, int key, boolean acquire) {
        if (!acquire) { release(p.locks.remove(key)); return; }
        Offer offer = p.advertised;
        if (offer == null || (p.capabilities & LOCKS) == 0 || !offer.formats.containsValue(DESCRIPTORS)
                || p.locks.containsKey(key) || p.locks.size() >= MAX_PENDING) return;
        if (offer.local != null && offer == current) {
            offer.local.retain();
            Lock lock = new Lock(offer, 0, false);
            lock.phoneProgress = p.wire.phoneProgress();
            p.locks.put(key, lock);
            return;
        }
        Peer src = offer.source;
        if (src == null || src.owned != offer) return;
        int id = ++src.nextId;
        boolean forward = (src.capabilities & LOCKS) != 0;
        if (!forward || src.wire.lock(id, true)) p.locks.put(key, new Lock(offer, id, forward));
    }

    private void release(Lock lock) {
        if (lock != null && lock.phoneProgress != null) lock.phoneProgress.endGroup(lock);
        if (lock != null && lock.offer.local != null) lock.offer.local.release();
        if (lock != null && lock.forwarded && lock.offer.source.ready) lock.offer.source.wire.lock(lock.id, false);
    }

    synchronized void requestFile(Peer p, int stream, int index, int flags, int low, int high, int count,
                                  boolean locked, int key) {
        if (!p.ready) return;
        Lock lock = locked ? p.locks.get(key) : null;
        Offer offer = locked ? (lock == null ? null : lock.offer) : p.advertised;
        Peer src = offer == null ? null : offer.source;
        boolean forwardLock = lock != null && lock.forwarded;
        if (offer == null || !p.canPaste || (p.capabilities & FILES) == 0 || index < 0 || index >= offer.fileCount
                || count < 0 || count > 64 * 1024 * 1024 || (flags != RANGE && flags != SIZE) || flags == SIZE && count != 8
                || (high != 0 || hugeEntry(offer, index)) && (p.capabilities & HUGE_FILES) == 0) {
            p.wire.fileResult(stream, null);
            return;
        }
        if (offer.local != null) {
            if (high < 0 || lock == null && current != offer || p.localReads.size() >= MAX_PENDING || p.localReads.containsKey(stream)) {
                p.wire.fileResult(stream, null);
                return;
            }
            long offset = (long)high << 32 | low & 0xffffffffL;
            PhoneProgress progress = p.wire.phoneProgress();
            PhoneFile phoneFile = flags == RANGE && count > 0 && progress != null ? offer.local.phoneFile(index) : null;
            Object group = lock != null ? (Object)lock : offer;
            boolean track = phoneFile != null && (phoneFile.size() < 0 || offset < phoneFile.size());
            LocalRead ticket = new LocalRead(track ? progress : null, group, index);
            p.localReads.put(stream, ticket);
            if (track)
                progress.range(group, index, phoneFile.name(), false, phoneFile.size(), offset, 0);
            offer.local.read(index, flags == SIZE, offset, count, bytes -> {
                synchronized (ClipboardBridge.this) {
                    if (p.localReads.get(stream) != ticket) return;
                    p.localReads.remove(stream);
                    boolean readable = offer.backing() == null || offer.backing().available();
                    byte[] result = readable && p.canPaste && (lock != null || current == offer) ? bytes : null;
                    if (p.ready) p.wire.fileResult(stream, result);
                    if (track && p.ready && (lock == null ? current == offer : p.locks.get(key) == lock)) {
                        if (result == null || result.length == 0) progress.endFile(group, index);
                        else progress.range(group, index, phoneFile.name(), false, phoneFile.size(), offset, result.length);
                    }
                }
            });
            return;
        }
        if (src == null || !src.ready || !forwardLock && src.owned != offer || src.transfers.size() >= MAX_PENDING
                || (high != 0 || hugeEntry(offer, index)) && (src.capabilities & HUGE_FILES) == 0) {
            p.wire.fileResult(stream, null);
            return;
        }
        int id;
        do { id = ++src.nextId; } while (src.transfers.containsKey(id));
        if (src.wire.readFile(id, index, flags, low, high, count, forwardLock, forwardLock ? lock.id : 0))
            src.transfers.put(id, new Transfer(p, stream, flags, count, offer, forwardLock));
        else p.wire.fileResult(stream, null);
    }

    synchronized void fileData(Peer source, int id, byte[] data) {
        Transfer transfer = source.transfers.remove(id);
        if (transfer == null || !transfer.to.ready) return;
        if (!transfer.locked && source.owned != transfer.offer || data != null && (data.length > transfer.count
                || transfer.flags == SIZE && data.length != 8)) data = null;
        transfer.to.wire.fileResult(transfer.stream, data);
    }

    private void sourceLost(Offer old) {
        Offer copy = new Offer(++revision, null);
        if (old.text != null) {
            copy.text = old.text; copy.textId = old.textId; copy.formats.put(old.textId, null);
        }
        replaceCurrent(copy);
        publishTo(focused, false);
    }

    /** One source clipboard lock covers all the file indices in its descriptor list. */
    synchronized RemoteFiles openRemoteFiles(long version) throws IOException {
        RemoteFiles backing = current == null ? null : current.backing();
        if (backing != null && backing.origin.revision == version && backing.available()) {
            backing.references++;
            return backing;
        }
        if (!isCurrent(version) || current.source == null || !current.source.ready || !current.source.canCopy
                || current.source.owned != current || peers.size() >= MAX_PENDING)
            throw new IOException("Clipboard files are unavailable");
        RemoteFiles files = new RemoteFiles(current);
        files.reader.advertised = current;
        files.reader.capabilities = FILES | LOCKS | HUGE_FILES;
        files.reader.ready = true;
        peers.add(files.reader);
        lock(files.reader, 1, true);
        if (!files.available()) {
            files.close();
            throw new IOException("Clipboard files are unavailable");
        }
        return files;
    }

    synchronized RemoteFile openRemoteFile(long version, int index) throws IOException {
        RemoteFiles files = openRemoteFiles(version);
        if (index < 0 || index >= files.origin.fileCount) {
            files.close();
            throw new IOException("Clipboard file is unavailable");
        }
        return new RemoteFile(files, index);
    }

    static final class LargeFileUnavailable extends IOException {
        LargeFileUnavailable() { super("The source did not negotiate 64-bit clipboard file transfers"); }
    }

    static final class ClipboardReplaced extends IOException {
        ClipboardReplaced() { super("The source no longer supplies the previous clipboard file"); }
    }

    static final class RemoteFile implements java.io.Closeable {
        private final RemoteFiles files;
        private final int index;
        private volatile boolean closed;
        private String phoneName;
        RemoteFile(RemoteFiles files, int index) { this.files = files; this.index = index; }
        void observePhoneReads(String name) { phoneName = name; }
        synchronized void reportPhoneRange(long total, long offset, int bytes) {
            if (!closed && phoneName != null) files.reportToPhone(this, index, phoneName, total, offset, bytes);
        }
        void stopPhoneProgress() {
            PhoneProgress progress = files.origin.source.wire.phoneProgress();
            if (progress != null) progress.endGroup(this);
        }
        void checkSize(long size) throws IOException {
            if (size > 0xffffffffL) files.requireLargeFiles();
        }
        byte[] fetch(boolean size, long offset, int length) throws IOException {
            if (closed) throw new IOException("Closed clipboard file");
            try { return files.fetch(index, size, offset, length); }
            catch (IOException error) { close(); throw error; }
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            stopPhoneProgress();
            files.close();
        }
    }

    final class RemoteFiles implements Wire, java.io.Closeable {
        private final Offer origin;
        private final Peer reader = new Peer(this, false, true);
        private int references = 1;
        private final AtomicInteger streams = new AtomicInteger();
        private final Map<Integer, Result> waiting = new ConcurrentHashMap<>();
        private boolean preferUnlocked;
        RemoteFiles(Offer origin) { this.origin = origin; }
        private void reportToPhone(RemoteFile handle, int index, String name, long total, long offset, int bytes) {
            synchronized (ClipboardBridge.this) {
                PhoneProgress progress = origin.source.wire.phoneProgress();
                if (progress != null && available()) progress.range(handle, index, name, true, total, offset, bytes);
            }
        }
        private boolean available() {
            Lock pin = reader.locks.get(1);
            return reader.ready && origin.source.ready && origin.source.canCopy && pin != null
                    && (pin.forwarded || origin.source.owned == origin);
        }

        // Called with the bridge lock held so a disconnect or permission change stays distinguishable.
        private IOException readFailure() {
            if (reader.ready && reader.locks.containsKey(1) && origin.source.ready && origin.source.canCopy && origin.source.owned != origin)
                return new ClipboardReplaced();
            return new IOException("Clipboard file read failed");
        }

        private void requireLargeFiles() throws IOException {
            synchronized (ClipboardBridge.this) {
                if ((origin.source.capabilities & HUGE_FILES) == 0) throw new LargeFileUnavailable();
            }
        }

        byte[] fetch(int index, boolean size, long offset, int length) throws IOException {
            boolean locked;
            synchronized (ClipboardBridge.this) {
                if (!available()) throw readFailure();
                if (offset > 0xffffffffL || hugeEntry(origin, index)) requireLargeFiles();
                locked = !preferUnlocked || origin.source.owned != origin;
            }
            if (locked) {
                byte[] bytes = request(index, size, offset, length, true);
                if (bytes != null) return bytes;
                synchronized (ClipboardBridge.this) {
                    // A server may accept only untagged reads while this is still its current clipboard.
                    if (!available() || origin.source.owned != origin || !reader.locks.get(1).forwarded)
                        throw readFailure();
                }
            }
            byte[] bytes = request(index, size, offset, length, false);
            synchronized (ClipboardBridge.this) {
                if (bytes != null) { preferUnlocked = true; return bytes; }
                if (!available() || origin.source.owned == origin) throw readFailure();
            }
            // A new Copy crossed an untagged request. fileData discarded its ambiguous reply.
            // Retry this exact range with the retained ID of the original file list.
            bytes = request(index, size, offset, length, true);
            if (bytes != null) return bytes;
            synchronized (ClipboardBridge.this) { throw readFailure(); }
        }

        private byte[] request(int index, boolean size, long offset, int length, boolean locked) throws IOException {
            CountDownLatch latch = new CountDownLatch(1);
            byte[][] result = new byte[1][];
            int stream = streams.incrementAndGet();
            synchronized (ClipboardBridge.this) {
                if (!available()) throw readFailure();
                if (!locked && origin.source.owned != origin) return null;
                waiting.put(stream, bytes -> { result[0] = bytes; latch.countDown(); });
                requestFile(reader, stream, index, size ? SIZE : RANGE, (int)offset, (int)(offset >>> 32),
                        length, locked, 1);
            }
            try {
                return latch.await(RESPONSE_MS, TimeUnit.MILLISECONDS) ? result[0] : null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                synchronized (ClipboardBridge.this) {
                    if (waiting.remove(stream) != null) {
                        Iterator<Transfer> it = origin.source.transfers.values().iterator();
                        while (it.hasNext()) {
                            Transfer transfer = it.next();
                            if (transfer.to == reader && transfer.stream == stream) { it.remove(); break; }
                        }
                    }
                }
            }
        }
        @Override public void close() {
            synchronized (ClipboardBridge.this) {
                if (--references != 0) return;
                offline(reader, true);
                for (Result result : waiting.values()) result.finish(null);
                waiting.clear();
            }
        }
        @Override public void fileResult(int stream, byte[] bytes) {
            Result result = waiting.remove(stream);
            if (result != null) result.finish(bytes);
        }
        // A document handle can receive only replies to its own range and size requests.
        @Override public boolean formats(int[] ids, String[] names) { return false; }
        @Override public boolean readFormat(int id) { return false; }
        @Override public void formatResult(byte[] data) {}
        @Override public boolean readFile(int s, int i, int f, int lo, int hi, int n, boolean l, int id) { return false; }
        @Override public boolean lock(int id, boolean acquire) { return false; }
        @Override public void phoneText(long revision, String text) {}
    }

    synchronized void offline(Peer p, boolean closed) {
        p.closed |= closed;
        p.ready = false;
        if (p.wire.phoneProgress() != null) p.wire.phoneProgress().stop(true, true);
        peers.remove(p);
        if (closed && focused == p) focused = null;
        if (recent == p) recent = null;
        if (copying == p) copying = null;
        p.pendingAcks = 0;
        p.replies.clear();
        p.localReads.clear();
        if (p.reading != null) { p.reading.finish(null); p.reading = null; }
        for (Read read : p.reads) read.finish(null);
        p.reads.clear();
        for (Transfer t : p.transfers.values()) if (t.to.ready) t.to.wire.fileResult(t.stream, null);
        p.transfers.clear();
        for (Lock l : p.locks.values()) release(l);
        p.locks.clear();
        for (Peer other : peers) {
            Iterator<Lock> locks = other.locks.values().iterator();
            while (locks.hasNext()) {
                Lock lock = locks.next();
                if (lock.offer.dependsOn(p)) { release(lock); locks.remove(); }
            }
            Iterator<Transfer> transfers = other.transfers.values().iterator();
            while (transfers.hasNext()) if (transfers.next().to == p) transfers.remove();
            Iterator<Read> reads = other.reads.iterator();
            while (reads.hasNext()) {
                Read r = reads.next();
                if (r.reply != null && r.reply.peer == p) reads.remove();
            }
            if (other.reading != null && other.reading.reply != null && other.reading.reply.peer == p) other.reading.finished = true;
            if (other.advertised != null && other.advertised.dependsOn(p)) other.advertised = null;
        }
        if (current != null && current.dependsOn(p)) sourceLost(current);
        if (closed && peers.isEmpty() && focused == null) replaceCurrent(null);
        p.advertised = p.owned = null;
        notifyAll();
    }

    synchronized void expire() { expire(now()); }

    synchronized void expire(long time) {
        for (Peer p : peers) {
            // Keep the expired in-flight slot until its untagged response arrives or this channel closes.
            if (p.reading != null && time >= p.reading.deadline) p.reading.finish(null);
            // A queued read for the latest Android copy starts its timeout only once sent.
            // A late untagged reply must be drained before sending the next request.
            discardObsoleteReads(p, time);
            while (!p.replies.isEmpty() && time >= p.replies.peekFirst().deadline) p.replies.peekFirst().finish(null);
            Iterator<Map.Entry<Integer, LocalRead>> local = p.localReads.entrySet().iterator();
            while (local.hasNext()) {
                Map.Entry<Integer, LocalRead> read = local.next();
                LocalRead ticket = read.getValue();
                if (time >= ticket.deadline) {
                    p.wire.fileResult(read.getKey(), null);
                    if (ticket.progress != null) ticket.progress.endFile(ticket.group, ticket.index);
                    local.remove();
                }
            }
            Iterator<Transfer> it = p.transfers.values().iterator();
            while (it.hasNext()) {
                Transfer t = it.next();
                if (time >= t.deadline) { it.remove(); if (t.to.ready) t.to.wire.fileResult(t.stream, null); }
            }
        }
    }

    synchronized boolean hasRequests() {
        for (Peer p : peers) if (p.reading != null && !p.reading.finished || !p.transfers.isEmpty()
                || !p.replies.isEmpty() || !p.localReads.isEmpty())
            return true;
        return false;
    }
}
