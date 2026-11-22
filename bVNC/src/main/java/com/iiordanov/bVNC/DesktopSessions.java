package com.iiordanov.bVNC;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import com.undatech.opaque.Connection;
import java.util.LinkedHashMap;

/** Accessed on the UI thread. Each desktop lives in a separate Android document task. */
public final class DesktopSessions {
    private static final LinkedHashMap<RemoteCanvasActivity, String> open = new LinkedHashMap<>();

    private DesktopSessions() {}

    static void register(RemoteCanvasActivity activity, Connection connection) {
        open.put(activity, connection.getId());
    }

    static void remove(RemoteCanvasActivity activity) { open.remove(activity); }

    static void activated(RemoteCanvasActivity activity) {
        if (open.containsKey(activity)) open.put(activity, open.remove(activity));
    }

    static boolean resumeLast(Context context) {
        RemoteCanvasActivity last = null;
        for (RemoteCanvasActivity activity : open.keySet()) {
            if (available(activity)) last = activity;
        }
        return last != null && show(context, last);
    }

    public static void closeSaved(String id) {
        RemoteCanvasActivity session = find(id);
        if (session != null) {
            remove(session);
            session.finishAndRemoveTask();
        }
    }

    private static boolean available(RemoteCanvasActivity activity) {
        return !activity.isFinishing() && !activity.isDestroyed() && activity.getRemoteConnection() != null
                && activity.getRemoteConnection().maintainConnection;
    }

    private static boolean show(Context context, RemoteCanvasActivity activity) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null) continue;
                int id = android.os.Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
                if (id == activity.getTaskId()) {
                    // Deliver the original launch Intent to the live task selected by the foreground UI.
                    Intent resume = new Intent(activity.getIntent());
                    resume.setClass(context, RemoteCanvasActivity.class);
                    resume.setFlags((resume.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    task.startActivity(context, resume, null);
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Android can remove a task from Recents after returning the list.
            }
        }
        return false;
    }

    private static RemoteCanvasActivity find(String id) {
        if (id == null || id.isEmpty() || id.equals("0")) return null;
        for (var entry : open.entrySet()) {
            if (id.equals(entry.getValue()) && available(entry.getKey())) return entry.getKey();
        }
        return null;
    }

    public static boolean isOpen(String id) { return find(id) != null; }

    static boolean hasTask(int id) {
        for (RemoteCanvasActivity activity : open.keySet()) {
            if (activity.getTaskId() == id && !activity.isFinishing() && !activity.isDestroyed()) return true;
        }
        return false;
    }

    public static boolean resumeSaved(Context context, String id) {
        RemoteCanvasActivity session = find(id);
        return session != null && show(context, session);
    }

}
