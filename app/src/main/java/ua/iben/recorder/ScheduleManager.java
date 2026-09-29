package ua.iben.recorder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.time.ZoneId;

final class ScheduleManager {
    private static final Object LOCK = new Object();
    private static final int RESUME_NOTIFICATION = 8105;
    static WeeklySchedule.State state(Config config) {
        return WeeklySchedule.at(config.days(), System.currentTimeMillis(), ZoneId.systemDefault());
    }
    static boolean permissions(Context c) { return Platform.recordingGranted(c); }
    static boolean ready(Context context) {
        return permissions(context) && Platform.exactAlarms(context) && Platform.notifications(context);
    }
    static boolean standby(Config c) {
        return PlatformPolicy.keepStandby(Build.VERSION.SDK_INT, c.scheduleEnabled(), ready(c.context),
                c.prefs.getBoolean("schedule_paused", false));
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
    static void pauseAll(Context context) {
        synchronized (LOCK) {
            Config c = new Config(context);
            c.prefs.edit().putBoolean("wanted", false).putString("origin", "none").putBoolean("schedule_paused", true).commit();
            scheduleNext(context, c, state(c), true);
            context.getSystemService(NotificationManager.class).cancel(RESUME_NOTIFICATION);
        }
    }
    static void manualStop(Context context) { markManualStop(context); wake(context); }
    static void wake(Context context) {
        // Dispatch to the existing service without attempting a prohibited background launch.
        if (RecorderService.dispatch()) return;
        Config c = new Config(context);
        if (!c.wanted() && !standby(c)) return;
        if (!PlatformPolicy.mayStartMicrophone(Build.VERSION.SDK_INT, Platform.visible(), false)) {
            awaitingUser(context); return;
        }
        if (!permissions(context)) { c.status(I18n.uk("record_permission"), 0); return; }
        try { context.startForegroundService(new Intent(context, RecorderService.class)); }
        catch (RuntimeException e) {
            AppLog.write(context, "Foreground service: " + e.getClass().getSimpleName());
            awaitingUser(context);
        }
    }
    static void awaitingUser(Context context) {
        Config c = new Config(context);
        c.prefs.edit().putBoolean("awaiting_user", true).putBoolean("engine_active", false).apply();
        c.status(I18n.uk("resume_required"), 0);
        if (!Platform.notifications(context)) return;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("resume", I18n.s("schedule_notice"), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, RESUME_NOTIFICATION, new Intent(context, MainActivity.class)
                .putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, "resume").setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Iben Recorder").setContentText(I18n.s("resume_required"))
                .setStyle(new Notification.BigTextStyle().bigText(I18n.s("resume_required")))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
        try { notifications.notify(RESUME_NOTIFICATION, n); } catch (SecurityException ignored) { }
    }
    static void clearReminder(Context c) {
        new Config(c).prefs.edit().putBoolean("awaiting_user", false).apply();
        c.getSystemService(NotificationManager.class).cancel(RESUME_NOTIFICATION);
    }
    static void reconcile(Context context, boolean force) {
        synchronized (LOCK) {
            Config c = new Config(context);
            WeeklySchedule.State state = state(c);
            boolean wanted = c.wanted();
            boolean auto = "schedule".equals(c.origin());
            long now = System.currentTimeMillis();
            boolean due = c.scheduleEnabled() && !c.prefs.getBoolean("schedule_paused", false) && ready(context)
                    && WeeklySchedule.allowed(state, c.prefs.getLong("schedule_skip", 0), now);
            if (c.prefs.getLong("schedule_skip", 0) > 0 && now >= c.prefs.getLong("schedule_skip", 0))
                c.prefs.edit().putLong("schedule_skip", 0).apply();
            WeeklySchedule.Decision decision = WeeklySchedule.decide(wanted, auto, due, permissions(context));
            boolean changed = decision.wanted != wanted;
            if (changed || decision.scheduled != auto) {
                c.prefs.edit().putBoolean("wanted", decision.wanted).putString("origin", decision.scheduled ? "schedule" : decision.wanted ? "manual" : "none").commit();
            }
            scheduleNext(context, c, state, force);
            if (changed || (force && (c.wanted() || standby(c) || RecorderService.alive()))) wake(context);
        }
    }
    private static void scheduleNext(Context context, Config c, WeeklySchedule.State state, boolean force) {
        long next = c.scheduleEnabled() && !c.prefs.getBoolean("schedule_paused", false) && ready(context) ? state.next : 0;
        if (!force && c.prefs.getLong("next_alarm", -1) == next) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent event = PendingIntent.getBroadcast(context, 8104, new Intent(context, ScheduleReceiver.class)
                .setAction("ua.iben.recorder.oreo.SCHEDULE"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(event);
        if (next > 0) {
            PendingIntent show = PendingIntent.getActivity(context, 8104, new Intent(context, MainActivity.class)
                    .putExtra("tab", 2), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try { alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(next, show), event); }
            catch (SecurityException e) { next = 0; c.status(I18n.uk("alarm_required"), 0); }
        }
        c.prefs.edit().putLong("next_alarm", next).apply();
    }
}
