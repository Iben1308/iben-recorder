package ua.iben.recorder;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.ZoneId;

final class ScheduleManager {
    private static final Object LOCK = new Object();
    static WeeklySchedule.State state(Config config) {
        return WeeklySchedule.at(config.days(), System.currentTimeMillis(), ZoneId.systemDefault());
    }
    static boolean permissions(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    static void manualStart(Context context) {
        synchronized (LOCK) {
            Config c = new Config(context);
            c.prefs.edit().putString("origin", "manual").putLong("schedule_skip", 0).putBoolean("wanted", true).commit();
            wake(context);
        }
    }
    static void markManualStop(Context context) {
        synchronized (LOCK) {
            Config c = new Config(context);
            WeeklySchedule.State state = state(c);
            long skip = c.scheduleEnabled() ? WeeklySchedule.skipCurrent(state) : 0;
            c.prefs.edit().putBoolean("wanted", false).putString("origin", "none").putLong("schedule_skip", skip).commit();
            scheduleNext(context, c, state, false);
        }
    }
    static void manualStop(Context context) { markManualStop(context); wake(context); }
    static void wake(Context context) {
        try { context.startForegroundService(new Intent(context, RecorderService.class)); }
        catch (RuntimeException e) { new Config(context).status("Не вдалося запустити сервіс запису: " + e.getClass().getSimpleName(), 0); }
    }
    static void reconcile(Context context, boolean force) {
        if (Build.VERSION.SDK_INT != 27) return;
        synchronized (LOCK) {
            Config c = new Config(context);
            WeeklySchedule.State state = state(c);
            boolean wanted = c.wanted();
            boolean auto = "schedule".equals(c.origin());
            long now = System.currentTimeMillis();
            boolean due = c.scheduleEnabled() && WeeklySchedule.allowed(state, c.prefs.getLong("schedule_skip", 0), now);
            if (c.prefs.getLong("schedule_skip", 0) > 0 && now >= c.prefs.getLong("schedule_skip", 0))
                c.prefs.edit().putLong("schedule_skip", 0).apply();
            WeeklySchedule.Decision decision = WeeklySchedule.decide(wanted, auto, due, permissions(context));
            boolean changed = decision.wanted != wanted;
            if (changed || decision.scheduled != auto) {
                c.prefs.edit().putBoolean("wanted", decision.wanted).putString("origin", decision.scheduled ? "schedule" : decision.wanted ? "manual" : "none").commit();
            }
            scheduleNext(context, c, state, force);
            if (due && !permissions(context)) c.status("Для розкладу потрібні дозволи на мікрофон і файли", 0);
            if (changed || (force && c.wanted() && ("schedule".equals(c.origin()) || c.resumeAtBoot()))) wake(context);
        }
    }
    private static void scheduleNext(Context context, Config c, WeeklySchedule.State state, boolean force) {
        long next = c.scheduleEnabled() ? state.next : 0;
        if (!force && c.prefs.getLong("next_alarm", -1) == next) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent event = PendingIntent.getBroadcast(context, 8104, new Intent(context, ScheduleReceiver.class)
                .setAction("ua.iben.recorder.oreo.SCHEDULE"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(event);
        if (next > 0) {
            PendingIntent show = PendingIntent.getActivity(context, 8104, new Intent(context, MainActivity.class)
                    .putExtra("tab", 2), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // User-selected clock times: avoids the per-app idle-alarm rate limit on short intervals.
            alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(next, show), event);
        }
        c.prefs.edit().putLong("next_alarm", next).apply();
    }
}
