package com.undatech.opaque;

import android.content.Context;
import android.net.Uri;
import com.undatech.opaque.util.FileUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/** A document is copied on the connection thread and retained only while FreeRDP parses it. */
public final class RdpConfigFile {
    private RdpConfigFile() {}

    public static boolean accepts(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        return "content".equals(scheme) || "file".equals(scheme) || "https".equals(scheme) || "http".equals(scheme);
    }

    public static File copy(Context context, Uri source) throws IOException {
        File copy = File.createTempFile("desktop-", ".rdp", context.getCacheDir());
        boolean complete = false;
        try {
            try (InputStream in = open(context, source)) {
                if (in == null || !FileUtils.outputToFile(in, copy, RemoteClientLibConstants.MAX_CONFIG_FILE_SIZE_BYTES))
                    throw new IOException("Could not read the RDP document within the size limit");
                if (copy.length() == 0) throw new IOException("Empty RDP document");
            }
            complete = true;
            return copy;
        } finally {
            if (!complete) copy.delete();
        }
    }

    private static InputStream open(Context context, Uri source) throws IOException {
        if ("https".equals(source.getScheme()) || "http".equals(source.getScheme())) {
            URLConnection connection = new URL(source.toString()).openConnection();
            connection.setConnectTimeout(RemoteClientLibConstants.GET_FILE_TIMEOUT);
            connection.setReadTimeout(RemoteClientLibConstants.GET_FILE_TIMEOUT);
            return connection.getInputStream();
        }
        if ("file".equals(source.getScheme()) && (source.getPath() == null || !new File(source.getPath()).isFile()))
            throw new IOException("The selected RDP document is not a regular file");
        return context.getContentResolver().openInputStream(source);
    }
}
