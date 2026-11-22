package com.undatech.opaque;

import android.content.ClipData;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only Android documents for the current RDP clipboard, granted individually by ClipboardManager. */
public final class ClipboardDocuments extends DocumentsProvider {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED};
    private static final AtomicReference<Selection> published = new AtomicReference<>();
    private static final AtomicLong lastFailure = new AtomicLong(-10000);
    private interface Failure { void report(IOException error); }
    static String authority(Context ctx) { return ctx.getPackageName() + ".clipboard.documents"; }
    static void publish(Selection selection) { published.set(selection); }
    static void discardStale() {
        Selection s = published.get();
        if (s != null && !s.bridge.hasDocuments(s.revision)) published.compareAndSet(s, null);
    }

    static final class Node {
        final String id, name;
        final Node parent;
        final boolean directory;
        final LinkedHashMap<String, Node> children = new LinkedHashMap<>();
        int index = -1;
        long size = -1, modified;
        Node(String id, String name, Node parent, boolean directory) {
            this.id = id; this.name = name; this.parent = parent; this.directory = directory;
        }
        String mime() {
            if (directory) return Document.MIME_TYPE_DIR;
            int dot = name.lastIndexOf('.');
            String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            return mime == null ? "application/octet-stream" : mime;
        }
    }
    static final class Selection {
        final ClipboardBridge bridge;
        final long revision;
        final String token = UUID.randomUUID().toString();
        final ArrayList<Node> nodes = new ArrayList<>();
        final Node root;
        Selection(ClipboardBridge bridge, long revision, byte[] descriptors) throws IOException {
            this.bridge = bridge;
            this.revision = revision;
            root = make("RDP", null, true);
            ByteBuffer buf = ByteBuffer.wrap(descriptors).order(ByteOrder.LITTLE_ENDIAN);
            int count = buf.remaining() < 4 ? -1 : buf.getInt();
            if (count <= 0 || count > 16384 || 4 + (long)count * 592 != descriptors.length) throw new IOException("Unsupported file list");
            for (int i = 0; i < count; i++) {
                int base = 4 + 592 * i, flags = buf.getInt(base);
                boolean dir = (flags & 4) != 0 && (buf.getInt(base + 36) & 16) != 0;
                int start = base + 72, end = start;
                while (end < base + 592 && (descriptors[end] != 0 || descriptors[end+1] != 0)) end += 2;
                if (end == start || end == base + 592) throw new IOException("Invalid file name");
                String path = new String(descriptors, start, end - start, StandardCharsets.UTF_16LE).replace('\\','/');
                Node parent = root;
                String[] parts = path.split("/", -1);
                for (int j = 0; j < parts.length; j++) {
                    String part = parts[j];
                    if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0) throw new IOException("Invalid file name");
                    boolean folder = j + 1 < parts.length || dir;
                    Node child = parent.children.get(part);
                    if (child == null) {
                        if (nodes.size() >= 32768) throw new IOException("Too many clipboard documents");
                        child = make(part, parent, folder);
                        parent.children.put(part, child);
                    }
                    if (child.directory != folder) throw new IOException("Conflicting file names");
                    parent = child;
                }
                if (parent.index >= 0) throw new IOException("Duplicate file name");
                parent.index = i;
                if (!dir && (flags & 0x40) != 0) {
                    parent.size = (long)buf.getInt(base + 64) << 32 | buf.getInt(base + 68) & 0xffffffffL;
                    if (parent.size < 0) throw new IOException("File too large");
                }
                if ((flags & 0x20) != 0) parent.modified = buf.getLong(base + 56) / 10000 - 11644473600000L;
            }
        }
        private Node make(String name, Node parent, boolean directory) {
            Node n = new Node(token + "-" + nodes.size(), name, parent, directory);
            nodes.add(n);
            return n;
        }
        ClipData clip(Context ctx, String label) {
            // Bound the Binder transaction for very large selections. The folder retains every item.
            boolean grouped = root.children.size() > 256;
            Iterable<Node> top = grouped ? Collections.singleton(root) : root.children.values();
            Uri tree = DocumentsContract.buildTreeDocumentUri(authority(ctx), root.id);
            LinkedHashSet<String> types = new LinkedHashSet<>();
            // Advertise the document types the same way Android Files does when copying locally.
            for (Node n : top) types.add(n.mime());
            ClipData clip = null;
            for (Node n : top) {
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, n.id);
                ClipData.Item item = new ClipData.Item(uri);
                if (clip == null) clip = new ClipData(label, types.toArray(new String[0]), item);
                else clip.addItem(item);
            }
            if (Build.VERSION.SDK_INT >= 24) {
                PersistableBundle extras = new PersistableBundle();
                extras.putInt("clipper:opType", 1);
                // File managers can identify the source before choosing or creating a destination.
                // A grouped selection contains the root itself, which has no source parent.
                if (!grouped) extras.putString("clipper:srcParent",
                        DocumentsContract.buildDocumentUriUsingTree(tree, root.id).toString());
                clip.getDescription().setExtras(extras);
            }
            return clip;
        }
        Node resolve(String id) throws FileNotFoundException {
            if (id == null || !id.startsWith(token + "-")) throw new FileNotFoundException();
            try {
                int index = Integer.parseInt(id.substring(token.length()+1));
                if (index >= 0 && index < nodes.size() && nodes.get(index).id.equals(id)) return nodes.get(index);
            } catch (NumberFormatException ignored) {}
            throw new FileNotFoundException();
        }
    }

    @Override public boolean onCreate() { return true; }
    // Clipboard grants expose just the selected documents. There is no browsable storage volume.
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE} : projection);
    }
    static Selection selection() throws FileNotFoundException {
        Selection s = published.get();
        if (s == null || !s.bridge.hasDocuments(s.revision)) throw new FileNotFoundException("Clipboard changed");
        return s;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        row(result, selection().resolve(documentId));
        return result;
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String order) throws FileNotFoundException {
        Node parent = selection().resolve(parentId);
        if (!parent.directory) throw new FileNotFoundException();
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        for (Node child : parent.children.values()) row(result, child);
        return result;
    }
    @Override public boolean isChildDocument(String parentId, String childId) {
        try {
            Selection s = selection();
            Node parent = s.resolve(parentId);
            for (Node n = s.resolve(childId).parent; n != null; n = n.parent) if (n == parent) return true;
        } catch (FileNotFoundException ignored) {}
        return false;
    }
    @android.annotation.TargetApi(26)
    @Override public DocumentsContract.Path findDocumentPath(String parentId, String documentId) throws FileNotFoundException {
        // Clipboard items belong to a selection tree with no browsable storage root.
        if (parentId == null) return super.findDocumentPath(null, documentId);
        Selection selection = selection();
        Node parent = selection.resolve(parentId);
        ArrayList<String> path = new ArrayList<>();
        for (Node node = selection.resolve(documentId); node != null; node = node.parent) {
            path.add(node.id);
            if (node == parent) {
                Collections.reverse(path);
                return new DocumentsContract.Path(null, path);
            }
        }
        throw new FileNotFoundException("Document outside clipboard tree");
    }
    private static void row(MatrixCursor cursor, Node n) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cursor.getColumnNames()) {
            Object value = null;
            if (Document.COLUMN_DOCUMENT_ID.equals(col)) value = n.id;
            else if (Document.COLUMN_DISPLAY_NAME.equals(col)) value = n.name;
            else if (Document.COLUMN_MIME_TYPE.equals(col)) value = n.mime();
            else if (Document.COLUMN_FLAGS.equals(col)) value = 0;
            else if (Document.COLUMN_SIZE.equals(col) && n.size >= 0) value = n.size;
            else if (Document.COLUMN_LAST_MODIFIED.equals(col) && n.modified > 0) value = n.modified;
            row.add(value);
        }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only clipboard");
        if (signal != null) signal.throwIfCanceled();
        Failure failure = error -> {
            if (signal == null || !signal.isCanceled()) showReadFailure(error);
        };
        try {
            Selection s = selection();
            Node n = s.resolve(id);
            if (n.directory) throw new FileNotFoundException("Clipboard item is a directory");
            ClipboardBridge.RemoteFile file = s.bridge.openRemoteFile(s.revision, n.index);
            file.observePhoneReads(n.name);
            try {
                if (signal != null) signal.setOnCancelListener(file::close);
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        ParcelFileDescriptor descriptor = Proxy.open(getContext(), file, n.size, failure);
                        if (descriptor != null) return descriptor;
                    } catch (IOException | UnsupportedOperationException | IllegalStateException unavailable) {
                        // A sequential reader also works on devices without a proxy mount.
                    }
                }
                return pipe(file, n.size, failure);
            } catch (IOException | RuntimeException e) { file.close(); throw e; }
        } catch (IOException e) {
            failure.report(e);
            FileNotFoundException unavailable = new FileNotFoundException("Clipboard file unavailable");
            unavailable.initCause(e);
            throw unavailable;
        }
    }
    private void showReadFailure(IOException error) {
        Context context = getContext();
        long time = android.os.SystemClock.elapsedRealtime(), previous = lastFailure.get();
        if (context == null || time - previous < 8000 || !lastFailure.compareAndSet(previous, time)) return;
        int message = com.undatech.remoteClientLib.R.string.clipboard_read_failed;
        if (error instanceof ClipboardBridge.LargeFileUnavailable)
            message = com.undatech.remoteClientLib.R.string.clipboard_large_unavailable;
        else if (error instanceof ClipboardBridge.ClipboardReplaced)
            message = com.undatech.remoteClientLib.R.string.clipboard_file_replaced;
        String detail = context.getString(message);
        new Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context, detail, android.widget.Toast.LENGTH_LONG).show());
    }
    private static long size(ClipboardBridge.RemoteFile file, long known) throws IOException {
        if (known >= 0) return known;
        byte[] raw = file.fetch(true, 0, 8);
        long length = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (length < 0) throw new IOException("Invalid file length");
        return length;
    }
    private static byte[] chunk(ClipboardBridge.RemoteFile file, long position, int count, long length) throws IOException {
        if (position < 0 || count < 0) throw new IOException("Invalid file range");
        if (position >= length || count == 0) return new byte[0];
        file.checkSize(length);
        int amount = (int)Math.min(Math.min(count, 256 * 1024), length - position);
        file.reportPhoneRange(length, position, 0);
        byte[] data = file.fetch(false, position, amount);
        if (data.length == 0 || data.length > amount) {
            file.stopPhoneProgress();
            throw new IOException("Incomplete clipboard file");
        }
        return data;
    }
    static ParcelFileDescriptor pipe(ClipboardBridge.RemoteFile file, long known) throws IOException {
        return pipe(file, known, ignored -> {});
    }
    private static ParcelFileDescriptor pipe(ClipboardBridge.RemoteFile file, long known, Failure failure) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        PhoneClipboardFiles.io.execute(() -> {
            try (ClipboardBridge.RemoteFile reader = file; OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                try {
                    long length;
                    try { length = size(reader, known); }
                    catch (IOException error) { failure.report(error); throw error; }
                    for (long offset = 0; offset < length;) {
                        byte[] buf;
                        try { buf = chunk(reader, offset, 256 * 1024, length); }
                        catch (IOException error) { failure.report(error); throw error; }
                        out.write(buf);
                        reader.reportPhoneRange(length, offset, buf.length);
                        offset += buf.length;
                    }
                } catch (IOException | RuntimeException error) { pipe[1].closeWithError("RDP file transfer interrupted"); }
            } catch (IOException ignored) {}
        });
        return pipe[0];
    }
    @android.annotation.TargetApi(26)
    private static final class Proxy extends ProxyFileDescriptorCallback {
        private static final Handler[] handlers = new Handler[2];
        private static int next;
        private final ClipboardBridge.RemoteFile file;
        private final Failure failure;
        private long length;
        Proxy(ClipboardBridge.RemoteFile file, long length, Failure failure) {
            this.file = file; this.length = length; this.failure = failure;
        }
        private static synchronized Handler worker() {
            int i = next++ & 1;
            if (handlers[i] == null) {
                HandlerThread thread = new HandlerThread("RdpDocument" + i);
                thread.start();
                handlers[i] = new Handler(thread.getLooper());
            }
            return handlers[i];
        }
        static ParcelFileDescriptor open(Context ctx, ClipboardBridge.RemoteFile file, long size, Failure failure) throws IOException {
            StorageManager storage = ctx.getSystemService(StorageManager.class);
            return storage == null ? null : storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    new Proxy(file, size, failure), worker());
        }
        @Override public long onGetSize() throws ErrnoException {
            try { return length = size(file, length); }
            catch (IOException error) { failure.report(error); throw new ErrnoException("getSize", OsConstants.EIO, error); }
        }
        @Override public int onRead(long offset, int count, byte[] out) throws ErrnoException {
            try {
                byte[] data = chunk(file, offset, Math.min(count, out.length), onGetSize());
                System.arraycopy(data, 0, out, 0, data.length);
                if (data.length > 0) file.reportPhoneRange(length, offset, data.length);
                return data.length;
            } catch (IOException error) { failure.report(error); throw new ErrnoException("read", OsConstants.EIO, error); }
        }
        @Override public void onRelease() { file.close(); }
    }
}
