package ua.iben.recorder;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import java.util.concurrent.atomic.AtomicLong;

final class SyncScheduler {
    static final int JOB = 8103;
    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());
    static void kick(Context context) { schedule(context, 0, false); }
    static void restart(Context context) {
        synchronized (RecordingFiles.LOCK) {
            context.getSystemService(JobScheduler.class).cancel(JOB);
            new CloudSettings(context).prefs.edit().putInt("failures", 0).putLong("retry_at", 0).apply();
            schedule(context, 0, true);
        }
    }
    static void cancel(Context context) {
        synchronized (RecordingFiles.LOCK) { context.getSystemService(JobScheduler.class).cancel(JOB); }
    }
    static void finished(Context context, long serial, long delay) {
        synchronized (RecordingFiles.LOCK) {
            JobInfo pending = context.getSystemService(JobScheduler.class).getPendingJob(JOB);
            // A settings change may have scheduled a newer job while this one was finishing.
            if (pending != null && pending.getExtras().getLong("serial") != serial) return;
            schedule(context, delay, true);
        }
    }
    static void schedule(Context context, long delay, boolean force) {
        synchronized (RecordingFiles.LOCK) { scheduleLocked(context, delay, force); }
    }
    private static void scheduleLocked(Context context, long delay, boolean force) {
        CloudSettings cloud = new CloudSettings(context);
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1 || !cloud.enabled() || !cloud.hasSecret()) return;
        if (!force && SyncJobService.busy()) return;
        try {
            long now = System.currentTimeMillis();
            long due = Math.max(now + delay, cloud.prefs.getLong("retry_at", 0));
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            JobInfo pending = scheduler.getPendingJob(JOB);
            if (!force && pending != null && pending.getExtras().getLong("due", 0) <= due) return;
            PersistableBundle extras = new PersistableBundle();
            extras.putLong("serial", SERIAL.incrementAndGet()); extras.putLong("due", due);
            extras.putLong("revision", cloud.revision());
            JobInfo job = new JobInfo.Builder(JOB, new ComponentName(context, SyncJobService.class))
                    .setPersisted(true).setMinimumLatency(Math.max(0, due - now))
                    .setRequiredNetworkType(cloud.unmetered() ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setExtras(extras).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) cloud.status("Android не прийняв завдання передачі; відкрийте застосунок повторно");
        } catch (RuntimeException e) {
            cloud.status("Не вдалося запланувати передачу; перевірте налаштування фонової роботи");
            AppLog.write(context, "Не вдалося запланувати WebDAV: " + e.getClass().getSimpleName());
        }
    }
}
