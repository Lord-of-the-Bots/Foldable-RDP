package com.undatech.opaque;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** A selection of Android content URIs. Only Windows file requests open the contents. */
final class PhoneClipboardFiles implements ClipboardBridge.LocalFiles {
    static final ExecutorService io = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "RdpClipboardFiles");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_FILES = 16384;
    private static final long WINDOWS_EPOCH = 11644473600000L;
    private static final Pattern DEVICE = Pattern.compile("(?i)(?:CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³])(?:\\..*)?");
    private static final String[] META = { Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_DOCUMENT_ID };
    private final Context app;
    private final ContentResolver resolver;
    private final List<Uri> selection;
    private final ClipboardDocuments.Selection documents;
    private final ClipboardBridge.RemoteFiles source;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Semaphore pinnedFiles = new Semaphore(8);
    private int references = 1;
    private byte[] descriptors;

    static PhoneClipboardFiles create(Context context, List<Uri> selection) {
        ClipboardDocuments.Selection documents = null;
        String authority = ClipboardDocuments.authority(context);
        try {
            for (Uri uri : selection) {
                if (!authority.equals(uri.getAuthority())) continue;
                if (documents == null) documents = ClipboardDocuments.selection();
                documents.resolve(DocumentsContract.getDocumentId(uri));
            }
            ClipboardBridge.RemoteFiles source = documents == null ? null : documents.bridge.openRemoteFiles(documents.revision);
            return new PhoneClipboardFiles(context, selection, documents, source);
        } catch (IOException | IllegalArgumentException unavailable) {
            // An expired document cannot be copied as part of a smaller successful selection.
            return null;
        }
    }

    private PhoneClipboardFiles(Context context, List<Uri> selection, ClipboardDocuments.Selection documents,
                               ClipboardBridge.RemoteFiles source) {
        app = context.getApplicationContext();
        resolver = app.getContentResolver();
        this.selection = new ArrayList<>(new LinkedHashSet<>(selection));
        this.documents = documents;
        this.source = source;
    }

    @Override public ClipboardBridge.RemoteFiles sourceFiles() { return source; }
    @Override public ClipboardBridge.PhoneFile phoneFile(int index) {
        // The bridge accepts reads only after prepare() has published the immutable entry list.
        Entry entry = entries.get(index);
        return entry.directory || entry.sourceIndex >= 0 ? null : entry;
    }
    @Override public synchronized void retain() { references++; }
    @Override public void release() {
        synchronized (this) { if (--references != 0) return; }
        io.execute(() -> {
            for (Entry entry : entries) entry.close();
            if (source != null) source.close();
        });
    }
    private interface Work { byte[] run() throws IOException; }
    private void submit(Work work, ClipboardBridge.Result reply) {
        retain();
        io.execute(() -> {
            byte[] data = null;
            try { data = work.run(); }
            catch (IOException | RuntimeException unavailable) { /* A grant or remote document can expire. */ }
            try { reply.finish(data); }
            finally { release(); }
        });
    }
    @Override public void describe(ClipboardBridge.Result reply) { submit(this::prepare, reply); }
    @Override public void read(int index, boolean size, long offset, int length, ClipboardBridge.Result reply) {
        submit(() -> {
            if (prepare() == null || index < 0 || index >= entries.size()) return null;
            Entry entry = entries.get(index);
            if (entry.directory) return null;
            if (size) return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(entry.length()).array();
            return entry.range(offset, length);
        }, reply);
    }
    private final Object metadataLock = new Object();
    private byte[] prepare() throws IOException {
        synchronized (metadataLock) {
            if (descriptors != null) return descriptors;
            // A document provider can recover between two paste attempts.
            entries.clear();
            Set<String> used = new HashSet<>();
            for (Uri uri : selection) {
                if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || uri.getAuthority() == null)
                    throw new IOException("Invalid clipboard URI");
                if (documents != null && ClipboardDocuments.authority(app).equals(uri.getAuthority())) {
                    appendRemote(documents.resolve(DocumentsContract.getDocumentId(uri)), "", used);
                    continue;
                }
                boolean doc = DocumentsContract.isDocumentUri(app, uri);
                String[] columns = doc ? META : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
                Cursor metadata;
                try { metadata = query(uri, columns); }
                catch (IllegalArgumentException | UnsupportedOperationException noColumns) { metadata = null; }
                try (Cursor cursor = metadata) {
                    append(uri, cursor != null && cursor.moveToFirst() ? cursor : null, "", used, new HashSet<>());
                }
            }
            ByteBuffer buf = ByteBuffer.allocate(4 + 592 * entries.size()).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                int pos = 4 + 592 * i;
                buf.putInt(pos, 4 | (entry.size >= 0 ? 0x40 : 0) | (entry.modified > 0 ? 0x20 : 0));
                buf.putInt(pos + 36, entry.directory ? 16 : 128);
                if (entry.modified > 0) buf.putLong(pos + 56, (entry.modified + WINDOWS_EPOCH) * 10000L);
                if (entry.size >= 0) {
                    buf.putInt(pos + 64, (int)(entry.size >>> 32));
                    buf.putInt(pos + 68, (int)entry.size);
                }
                buf.position(pos + 72);
                buf.put(entry.path.getBytes(StandardCharsets.UTF_16LE));
            }
            descriptors = buf.array();
            return descriptors;
        }
    }
    private Cursor query(Uri uri, String[] columns) {
        if (Build.VERSION.SDK_INT >= 26) return resolver.query(uri, columns, (Bundle)null, null);
        return resolver.query(uri, columns, null, null, null);
    }
    private static String string(Cursor cursor, String column) {
        int i = cursor == null ? -1 : cursor.getColumnIndex(column);
        return i < 0 || cursor.isNull(i) ? null : cursor.getString(i);
    }
    private static long number(Cursor cursor, String column) {
        int i = cursor == null ? -1 : cursor.getColumnIndex(column);
        return i < 0 || cursor.isNull(i) ? -1 : cursor.getLong(i);
    }
    private static String uniqueName(String value, Set<String> used, int limit) throws IOException {
        if (limit < 1) throw new IOException("Windows clipboard path too long");
        String base = value == null ? "File" : value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").replaceAll("[ .]+$", "");
        if (base.isEmpty()) base = "File";
        if (DEVICE.matcher(base).matches()) base = "_" + base;
        for (int n = 1; n <= MAX_FILES; n++) {
            String suffix = n == 1 ? "" : " (" + n + ")";
            int len = Math.min(base.length(), limit - suffix.length());
            if (len < 1) break;
            if (Character.isHighSurrogate(base.charAt(len - 1))) len--;
            String name = base.substring(0,len).replaceAll("[ .]+$", "") + suffix;
            if (!name.isEmpty() && !DEVICE.matcher(name).matches() && used.add(name.toLowerCase(Locale.ROOT))) return name;
        }
        throw new IOException("Too many duplicate clipboard names");
    }
    private Entry addEntry(Uri uri, String label, String parent, Set<String> used, boolean directory,
                           long size, long modified, int sourceIndex) throws IOException {
        if (entries.size() >= MAX_FILES) throw new IOException("Too many clipboard files");
        String name = uniqueName(label, used, 259 - parent.length());
        Entry entry = new Entry(uri, parent + name, directory, size, modified, sourceIndex);
        entries.add(entry);
        return entry;
    }
    private void appendRemote(ClipboardDocuments.Node node, String parent, Set<String> used) throws IOException {
        Entry entry = addEntry(null, node.name, parent, used, node.directory, node.size, node.modified, node.index);
        if (node.directory) {
            Set<String> names = new HashSet<>();
            for (ClipboardDocuments.Node child : node.children.values()) appendRemote(child, entry.path + "\\", names);
        }
    }
    private void append(Uri uri, Cursor row, String parent, Set<String> used, Set<Uri> ancestors) throws IOException {
        String label = string(row, Document.COLUMN_DISPLAY_NAME);
        if (label == null) {
            label = uri.getLastPathSegment();
            if (label != null) label = label.substring(label.lastIndexOf('/') + 1);
        }
        String mime = string(row, Document.COLUMN_MIME_TYPE);
        if (mime == null) mime = resolver.getType(uri);
        Entry entry = addEntry(uri, label, parent, used, Document.MIME_TYPE_DIR.equals(mime),
                number(row, Document.COLUMN_SIZE), number(row, Document.COLUMN_LAST_MODIFIED), -1);
        if (!entry.directory) return;
        if (!ancestors.add(uri)) throw new IOException("Cyclic document folder");
        boolean tree = !uri.getPathSegments().isEmpty() && "tree".equals(uri.getPathSegments().get(0));
        String id = DocumentsContract.getDocumentId(uri);
        Uri children = tree ? DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                : DocumentsContract.buildChildDocumentsUri(uri.getAuthority(), id);
        Set<String> names = new HashSet<>();
        try (Cursor rows = query(children, META)) {
            if (rows == null) throw new IOException("Folder listing unavailable");
            while (rows.moveToNext()) {
                String child = string(rows, Document.COLUMN_DOCUMENT_ID);
                if (child == null) throw new IOException("Missing child document ID");
                Uri item = tree ? DocumentsContract.buildDocumentUriUsingTree(uri, child)
                        : DocumentsContract.buildDocumentUri(uri.getAuthority(), child);
                append(item, rows, entry.path + "\\", names, ancestors);
            }
        } finally { ancestors.remove(uri); }
    }
    private static File spoolDirectory;
    private static synchronized File spoolDirectory(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "rdp-clipboard-streams");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Clipboard cache unavailable");
        if (!dir.equals(spoolDirectory)) {
            // A previous process may have stopped while a forward-only document was being copied.
            File[] old = dir.listFiles((ignored, name) -> name.startsWith("range-") && name.endsWith(".tmp"));
            if (old != null) for (File file : old) file.delete();
            spoolDirectory = dir;
        }
        return dir;
    }
    private final class Entry implements ClipboardBridge.PhoneFile {
        final Uri uri;
        final String path;
        final boolean directory;
        final long modified;
        final int sourceIndex;
        volatile long size;
        private final String name;
        @Override public String name() { return name; }
        @Override public long size() { return size; }
        File cache;
        AssetFileDescriptor pinned;
        Entry(Uri uri, String path, boolean dir, long size, long modified, int sourceIndex) {
            name = path.substring(path.lastIndexOf('\\') + 1);
            this.uri = uri; this.path = path; directory = dir; this.size = size; this.modified = modified;
            this.sourceIndex = sourceIndex;
        }
        private AssetFileDescriptor open() throws IOException {
            if (pinned != null) return pinned;
            AssetFileDescriptor asset = resolver.openAssetFileDescriptor(uri, "r");
            if (asset == null) throw new IOException("No file descriptor");
            if (pinnedFiles.tryAcquire()) pinned = asset;
            return asset;
        }
        private void done(AssetFileDescriptor asset) throws IOException {
            if (asset != pinned) asset.close();
        }
        synchronized void close() {
            if (pinned != null) {
                try { pinned.close(); } catch (IOException ignored) {}
                pinned = null;
                pinnedFiles.release();
            }
            if (cache != null) cache.delete();
        }
        synchronized long length() throws IOException {
            if (size >= 0) return size;
            if (sourceIndex >= 0) {
                long length = ByteBuffer.wrap(source.fetch(sourceIndex, true, 0, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
                if (length < 0) throw new IOException("Invalid file size");
                size = length;
                return size;
            }
            AssetFileDescriptor asset = open();
            try {
                size = asset.getLength();
                if (size < 0) spool(asset);
            } finally { done(asset); }
            return size;
        }
        synchronized byte[] range(long offset, int requested) throws IOException {
            long total = length();
            if (offset < 0 || offset > total) throw new IOException("Invalid file offset");
            int length = (int)Math.min(requested, total - offset);
            if (length == 0) return new byte[0];
            if (sourceIndex >= 0) {
                byte[] data = source.fetch(sourceIndex, false, offset, length);
                if (data.length == 0) throw new EOFException();
                return data;
            }
            byte[] bytes = new byte[length];
            if (cache == null) {
                AssetFileDescriptor asset = open();
                try {
                    if (offset > Long.MAX_VALUE - asset.getStartOffset()) throw new IOException("File offset too large");
                    long position = asset.getStartOffset() + offset;
                    try {
                        for (int n = 0; n < length;) {
                            int read = Os.pread(asset.getFileDescriptor(), bytes, n, length - n, position + n);
                            if (read <= 0) throw new EOFException();
                            n += read;
                        }
                        return bytes;
                    } catch (ErrnoException e) {
                        if (e.errno != OsConstants.ESPIPE && e.errno != OsConstants.EINVAL) throw new IOException(e);
                        spool(asset);
                    }
                } finally { done(asset); }
            }
            try (RandomAccessFile file = new RandomAccessFile(cache, "r")) {
                file.seek(offset);
                file.readFully(bytes);
            }
            return bytes;
        }
        // Some cloud providers only offer a forward-only pipe. Spool it once, on demand, for range reads.
        private void spool(AssetFileDescriptor asset) throws IOException {
            File tmp = File.createTempFile("range-", ".tmp", spoolDirectory(app));
            try {
                try (InputStream in = asset.createInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                if (size >= 0 && size != tmp.length()) throw new EOFException();
                size = tmp.length();
                cache = tmp;
            } finally {
                if (cache == null) tmp.delete();
                if (pinned == asset) {
                    pinned = null;
                    pinnedFiles.release();
                }
            }
        }
    }
}
