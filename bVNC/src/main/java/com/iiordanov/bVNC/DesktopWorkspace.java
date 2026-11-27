package com.iiordanov.bVNC;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.AppTask;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import com.undatech.opaque.ConnectionGridActivity;
import com.undatech.opaque.IntentHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single Recents card owns the lifetime of the open desktops. Main thread only. */
public final class DesktopWorkspace {
    private static final String OPEN_TASKS = "com.foldable.rdp.OPEN_DESKTOP_TASKS";
    private static final String SAVED_PROFILES = "com.foldable.rdp.OPEN_PROFILES";
    private static final String SAVED_GENERATION = "com.foldable.rdp.WORKSPACE_GENERATION";
    public static final String UNLOCK = "com.foldable.rdp.UNLOCK_WORKSPACE";
    private static final int NONE = -1;
    private static final LinkedHashMap<Integer, Long> desktops = new LinkedHashMap<>();
    private static final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private static int card = NONE;
    private static int destination = NONE;
    private static int replacing = NONE;
    private static int resumeGeneration;
    private static boolean initialized;
    private static boolean checkedForceStop;
    private static boolean wasHidden;
    private static Activity resumed;

    private DesktopWorkspace() {}

    private static final class Window {
        final AppTask task;
        final boolean desktop;
        final boolean excluded;

        Window(AppTask task, boolean desktop, boolean excluded) {
            this.task = task;
            this.desktop = desktop;
            this.excluded = excluded;
        }

        void exclude(boolean value) {
            if (value == excluded) return;
            try {
                task.setExcludeFromRecents(value);
            } catch (IllegalArgumentException ignored) {
                // The user can remove a task between enumeration and use.
            }
        }

        boolean bringForward(Activity caller) {
            try {
                // Android retains the original Intent, URI grants and Activity instance state.
                task.moveToFront();
                return true;
            } catch (IllegalArgumentException | SecurityException unavailable) {
                return startInTask(caller);
            }
        }

        boolean startInTask(Activity caller) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || info.baseIntent == null) return false;
                Intent original = info.baseIntent;
                Intent intent = new Intent(original).setFlags((original.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                task.startActivity(caller, intent, null);
                return true;
            } catch (IllegalArgumentException | SecurityException | android.content.ActivityNotFoundException ignored) {
                return false;
            }
        }
    }

    private static Map<Integer, Window> windows(Context context) {
        Map<Integer, Window> result = new LinkedHashMap<>();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (AppTask task : manager.getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || info.baseIntent == null || (Build.VERSION.SDK_INT >= 23 && info.numActivities == 0)) continue;
                ComponentName root = info.baseIntent.getComponent();
                if (root == null || !context.getPackageName().equals(root.getPackageName())) continue;
                boolean desktop = RemoteCanvasActivity.class.getName().equals(root.getClassName());
                if (!desktop && !ConnectionGridActivity.class.getName().equals(root.getClassName())) continue;
                int id = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
                if (id >= 0) result.put(id, new Window(task, desktop,
                        (info.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0));
            } catch (IllegalArgumentException ignored) {
                // A removed task no longer belongs to the workspace.
            }
        }
        return result;
    }

    static boolean openFromLauncher(Activity launcher) {
        BackgroundDisconnect.checkDeadline();
        Map<Integer, Window> windows = windows(launcher);
        if (firstLaunchAfterForceStop(launcher)) {
            discard(windows, NONE);
            return false;
        }
        for (Window window : windows.values()) {
            if (window.excluded) continue;
            if (initialized) queueMissing(card);
            if (initialized && destination == NONE && DesktopSessions.resumeLast(launcher)) return true;
            if (window.bringForward(launcher)) return true;
        }
        // Swiping away the app card can leave hidden tasks alive even after a process restart.
        if (!hasCard(windows(launcher))) discard(windows, NONE);
        return false;
    }

    static boolean hasCard(Context context) {
        return hasCard(windows(context));
    }

    static boolean retryCard(Activity launcher) {
        for (Window window : windows(launcher).values()) {
            if (!window.excluded && window.startInTask(launcher)) return true;
        }
        return false;
    }

    private static boolean hasCard(Map<Integer, Window> windows) {
        for (Window window : windows.values()) if (!window.excluded) return true;
        return false;
    }

    private static boolean firstLaunchAfterForceStop(Context context) {
        if (checkedForceStop || Build.VERSION.SDK_INT < 35) return false;
        checkedForceStop = true;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            for (android.app.ApplicationStartInfo start : manager.getHistoricalProcessStartReasons(1)) {
                if (start.getPid() == android.os.Process.myPid()) return start.wasForceStopped();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Retained tasks and their saved state also distinguish a clean launch on older systems.
        }
        return false;
    }

    public static boolean created(Activity activity, Bundle state) {
        BackgroundDisconnect.checkDeadline();
        if (activity.isFinishing()) return false;
        if (state != null && state.getLong(SAVED_GENERATION) != BackgroundDisconnect.generation()) {
            if (activity instanceof RemoteCanvasActivity) {
                activity.finishAndRemoveTask();
                activity.startActivity(new Intent(activity, ConnectionGridActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                return false;
            }
            state = null;
        }
        if (initialized) return checkCard(activity, state != null);
        boolean stopped = firstLaunchAfterForceStop(activity);
        boolean history = (activity.getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        Map<Integer, Window> windows = windows(activity);
        Window own = windows.get(activity.getTaskId());
        boolean orphan = state != null && own != null && own.excluded && !hasCard(windows);
        boolean discardedHistory = history && (state == null || stopped);
        if (activity instanceof RemoteCanvasActivity && (orphan || discardedHistory || stopped && state != null)) {
            returnToComputers(activity);
            return false;
        }
        List<Integer> saved = state == null ? null : state.getIntegerArrayList(OPEN_TASKS);
        if (stopped || orphan) discard(windows, activity.getTaskId());
        initialized = true;
        if (stopped || orphan || saved == null || saved.isEmpty()) return true;
        long[] profiles = state.getLongArray(SAVED_PROFILES);
        for (int i = 0; i < saved.size(); i++) {
            int id = saved.get(i);
            long profile = profiles != null && i < profiles.length ? profiles[i] : 0;
            Window w = windows.get(id);
            if ((w != null && w.desktop) || (w == null && profile > 0)) {
                desktops.put(id, profile);
                pending.add(id);
                destination = id;
            }
        }
        card = activity.getTaskId();
        if (desktops.containsKey(card)) destination = card;
        return true;
    }

    static void registered(Activity desktop, long profile) {
        int id = desktop.getTaskId();
        if (replacing != NONE && Long.valueOf(profile).equals(desktops.get(replacing))) {
            desktops.remove(replacing);
            pending.remove(replacing);
            if (destination == replacing) destination = id;
            replacing = NONE;
        }
        desktops.put(id, profile);
    }

    public static void save(Bundle state) {
        state.putLong(SAVED_GENERATION, BackgroundDisconnect.generation());
        state.putIntegerArrayList(OPEN_TASKS, new ArrayList<>(desktops.keySet()));
        long[] profiles = new long[desktops.size()];
        int i = 0;
        for (long profile : desktops.values()) profiles[i++] = profile;
        state.putLongArray(SAVED_PROFILES, profiles);
    }

    private static void useCard(Map<Integer, Window> windows, int id) {
        Window chosen = windows.get(id);
        if (chosen == null) return;
        chosen.exclude(false);
        for (Map.Entry<Integer, Window> entry : windows.entrySet()) {
            if (entry.getKey() != id) entry.getValue().exclude(true);
        }
        card = id;
    }

    public static void focused(Activity activity) {
        if (destination != NONE || activity.isFinishing() || !checkCard(activity, true)) return;
        int id = activity.getTaskId();
        Long profile = desktops.remove(id);
        if (profile != null) desktops.put(id, profile);
        if (card != id) useCard(windows(activity), id);
    }

    public static void resumed(Activity activity) {
        if (activity.isFinishing() || !checkCard(activity, true)) return;
        resumed = activity;
        int generation = ++resumeGeneration;
        if (wasHidden) {
            wasHidden = false;
            queueMissing(activity.getTaskId());
        }
        if (destination == NONE) {
            if (activity instanceof RemoteCanvasActivity desktop) DesktopSessions.activated(desktop);
            focused(activity);
            return;
        }
        // Let this window measure its own framebuffer before starting another restored desktop.
        View decor = activity.getWindow().getDecorView();
        if (decor.isLaidOut()) {
            decor.post(() -> advance(activity, generation));
        } else {
            decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    decor.getViewTreeObserver().removeOnPreDrawListener(this);
                    decor.post(() -> advance(activity, generation));
                    return true;
                }
            });
        }
    }

    public static void paused(Activity activity) {
        if (resumed == activity) {
            resumed = null;
            resumeGeneration++;
        }
    }

    static void backgrounded() {
        wasHidden = true;
    }

    static boolean hasDesktops() { return !desktops.isEmpty(); }

    static void disconnectInBackground(Context context) {
        Map<Integer, Window> tasks = windows(context);
        desktops.clear();
        pending.clear();
        card = destination = replacing = NONE;
        resumed = null;
        resumeGeneration++;
        wasHidden = false;
        for (Map.Entry<Integer, Window> entry : tasks.entrySet()) {
            if (!entry.getValue().desktop) {
                useCard(tasks, entry.getKey());
                break;
            }
        }
        java.util.Set<Integer> closed = DesktopSessions.closeAllForTimeout();
        for (Map.Entry<Integer, Window> entry : tasks.entrySet()) {
            Window window = entry.getValue();
            if (!window.desktop || closed.contains(entry.getKey())) continue;
            try {
                window.task.finishAndRemoveTask();
            } catch (IllegalArgumentException ignored) {
                // Android already removed this desktop.
            }
        }
    }

    private static void queueMissing(int preferred) {
        if (destination != NONE) return;
        int last = NONE;
        for (int id : desktops.keySet()) {
            if (!DesktopSessions.hasTask(id)) pending.add(id);
            last = id;
        }
        if (!pending.isEmpty()) destination = desktops.containsKey(preferred) ? preferred : last;
    }

    private static boolean restoreTask(Activity activity, Map<Integer, Window> windows, int id) {
        Window window = windows.get(id);
        if (window != null && window.desktop && window.bringForward(activity)) return true;
        // Android may have trimmed an older desktop task while retaining the workspace card.
        Long profile = desktops.get(id);
        if (profile == null || profile <= 0) return false;
        try {
            ConnectionBean bean = new ConnectionBean(activity);
            if (!Database.withReadable(activity, db -> bean.Gen_read(db, profile))) return false;
            Intent launch = new IntentHelper().getIntent(bean, activity.getApplicationContext(), activity);
            if (launch.getComponent() == null) {
                // Let the profile's VPN client establish the tunnel using its normal entry point.
                activity.startActivity(launch);
                pending.remove(id);
                desktops.remove(id);
                if (destination == id) destination = NONE;
                return true;
            }
            replacing = id;
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            return true;
        } catch (RuntimeException unavailable) {
            replacing = NONE;
            Log.w("DesktopWorkspace", "Could not reopen a saved desktop", unavailable);
            return false;
        }
    }

    private static void advance(Activity activity, int generation) {
        if (generation != resumeGeneration || resumed != activity || activity.isFinishing() || destination == NONE) return;
        int current = activity.getTaskId();
        Map<Integer, Window> windows = windows(activity);
        while (!pending.isEmpty()) {
            int id = pending.getFirst();
            if (id == current || id == destination || DesktopSessions.hasTask(id)) {
                pending.removeFirst();
                continue;
            }
            if (restoreTask(activity, windows, id)) {
                retryIfStillVisible(activity, generation, id, true);
                return;
            }
            pending.removeFirst();
            desktops.remove(id);
        }
        if (current != destination) {
            if (restoreTask(activity, windows, destination)) {
                retryIfStillVisible(activity, generation, destination, true);
                return;
            }
        }
        destination = NONE;
        useCard(windows, current);
        if (activity instanceof RemoteCanvasActivity desktop) {
            DesktopSessions.activated(desktop);
            focused(desktop);
        }
    }

    private static void retryIfStillVisible(Activity activity, int generation, int requested, boolean tryExplicitIntent) {
        // Some window managers decline a foreground request without throwing. Continue only
        // if the same app window stayed resumed throughout the transition.
        activity.getWindow().getDecorView().postDelayed(() -> {
            if (resumed != activity || resumeGeneration != generation || destination == NONE) return;
            Window target = windows(activity).get(requested);
            if (tryExplicitIntent && target != null && target.startInTask(activity)) {
                retryIfStillVisible(activity, generation, requested, false);
                return;
            }
            replacing = NONE;
            pending.remove(requested);
            if (requested == destination) destination = activity.getTaskId();
            advance(activity, generation);
        }, 2500);
    }

    /** An in-app disconnect closes one desktop. Hand the card to a surviving window first. */
    static void closingDesktop(Activity activity) {
        int id = activity.getTaskId();
        if (desktops.remove(id) == null) return;
        pending.remove(id);
        if (destination == id) {
            destination = NONE;
            for (int remaining : desktops.keySet()) destination = remaining;
        }
        if (card != id) return;
        card = NONE;
        Map<Integer, Window> windows = windows(activity);
        for (Map.Entry<Integer, Window> entry : windows.entrySet()) {
            if (entry.getKey() != id) {
                useCard(windows, entry.getKey());
                break;
            }
        }
    }

    public static void destroyed(Activity activity) {
        paused(activity);
        if (!activity.isFinishing()) return;
        int id = activity.getTaskId();
        if (id == card) {
            // Android removed the app's visible card, without an in-app disconnect.
            discard(windows(activity), id);
        } else {
            desktops.remove(id);
            pending.remove(id);
        }
    }

    public static void dismiss(Activity activity) {
        discard(windows(activity), activity.getTaskId());
        activity.finishAndRemoveTask();
    }

    private static void returnToComputers(Activity activity) {
        dismiss(activity);
        activity.startActivity(new Intent(activity, ConnectionGridActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static boolean checkCard(Activity activity, boolean returning) {
        int id = activity.getTaskId();
        if (card == NONE || card == id) return true;
        Map<Integer, Window> windows = windows(activity);
        if (windows.containsKey(card)) return true;
        // A swipe can remove the card before Android delivers onDestroy to its Activity.
        if (returning && activity instanceof RemoteCanvasActivity && desktops.containsKey(id)) {
            returnToComputers(activity);
            return false;
        }
        // An explicit new window starts a fresh workspace even if old callbacks are still pending.
        discard(windows, id);
        initialized = true;
        return true;
    }

    private static void discard(Map<Integer, Window> windows, int except) {
        desktops.clear();
        pending.clear();
        destination = card = replacing = NONE;
        resumed = null;
        initialized = false;
        wasHidden = false;
        for (Map.Entry<Integer, Window> entry : windows.entrySet()) {
            if (entry.getKey() == except) continue;
            try {
                entry.getValue().task.finishAndRemoveTask();
            } catch (IllegalArgumentException ignored) {
                // Already dismissed.
            }
        }
    }
}
