package ua.iben.recorder;

import android.Manifest;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** OS-persisted jobs keep retries working even after recording has been stopped. */
public final class SyncJobService extends JobService {
    private static final Semaphore TRANSFER = new Semaphore(1);
    private static final AtomicInteger RUNNING = new AtomicInteger();
    private final ConcurrentHashMap<Long, Runner> runners = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    static boolean busy() { return RUNNING.get() > 0; }
    @Override public boolean onStartJob(JobParameters parameters) {
        synchronized (RecordingFiles.LOCK) {
            CloudSettings cloud = new CloudSettings(this);
            if (!cloud.enabled() || cloud.revision() != parameters.getExtras().getLong("revision")) return false;
            Runner runner = new Runner(parameters);
            runners.put(parameters.getExtras().getLong("serial"), runner);
            RUNNING.incrementAndGet(); runner.thread.start();
            return true;
        }
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if (android.os.Build.VERSION.SDK_INT >= 31) AppLog.write(this, "WebDAV JobScheduler stopReason=" + parameters.getStopReason());
        Runner runner = runners.remove(parameters.getExtras().getLong("serial"));
        if (runner != null) {
            if (runner.current()) runner.cloud.status("Передачу призупинено Android; очікування дозволеної мережі та фонової роботи");
            runner.cancel();
        }
        CloudSettings cloud = new CloudSettings(this);
        return cloud.enabled() && cloud.revision() == parameters.getExtras().getLong("revision");
    }
    @Override public void onDestroy() {
        for (Runner runner : runners.values()) runner.cancel();
        runners.clear(); super.onDestroy();
    }
    private final class Runner implements Runnable {
        final JobParameters parameters;
        final Thread thread = new Thread(this, "iben-webdav");
        volatile boolean stopped;
        volatile DavClient client;
        long lastProgress;
        volatile long revision;
        boolean pendingFiles;
        final CloudSettings cloud = new CloudSettings(SyncJobService.this);
        Runner(JobParameters parameters) { this.parameters = parameters; }
        void cancel() {
            stopped = true;
            DavClient c = client; if (c != null) c.cancel();
            thread.interrupt();
        }
        boolean current() { return !stopped && cloud.enabled() && revision == cloud.revision(); }
        @Override public void run() {
            boolean acquired = false;
            long next = 15 * 60000L;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                TRANSFER.acquire(); acquired = true;
                if (stopped || !cloud.enabled()) return;
                revision=cloud.revision();
                if(revision!=parameters.getExtras().getLong("revision"))return;
                try(RecordingFiles files=new RecordingFiles(SyncJobService.this,new Config(SyncJobService.this))) {
                    int pending=files.uploads(cloud.targetKey()).size();pendingFiles=pending>0;cloud.prefs.edit().putInt("pending",pending).apply();
                    if(!pendingFiles){cloud.prefs.edit().putInt("failures",0).putLong("retry_at",0).apply();cloud.status(I18n.uk("cloud_queue_empty"));ProblemNotifications.cloudHealthy(SyncJobService.this);return;}
                }
                CloudSettings.Connection connection = cloud.connection();
                revision = connection.revision;
                // A canceled job must not use new credentials with its old network constraints.
                if (revision != parameters.getExtras().getLong("revision")) return;
                if (!Platform.storageGranted(SyncJobService.this))
                    throw new IOException("Надайте дозвіл на сховище, запустивши запис у застосунку");
                client = connection.client( (phase, done, total) -> {
                    long now = SystemClock.elapsedRealtime();
                    if (current() && now - lastProgress > 1000L) {
                        lastProgress = now;
                        cloud.status(phase + ": " + (total == 0 ? 0 : done * 100L / total) + "%");
                    }
                });
                long started = SystemClock.elapsedRealtime();
                try (RecordingFiles files = new RecordingFiles(SyncJobService.this, new Config(SyncJobService.this))) {
                    while (current()) {
                        List<RecordingFiles.Upload> queue = files.uploads(connection.target.key);
                        cloud.prefs.edit().putInt("pending", queue.size()).apply();
                        if (queue.isEmpty()) {
                            ProblemNotifications.cloudHealthy(SyncJobService.this);
                            cloud.prefs.edit().putInt("failures", 0).putLong("retry_at", 0).apply();
                            cloud.status(I18n.uk("cloud_queue_empty"));
                            break;
                        }
                        RecordingFiles.Upload file = queue.get(0);
                        try (RecordingFiles.Lease lease = files.leaseUpload(file, connection.target.key)) {
                            if (lease == null) continue; // Deleted before this upload started; re-read the queue.
                            String remoteName = file.remoteName;
                            cloud.status("Передача: " + file.name);
                            DavClient.Receipt receipt;
                            try { receipt = client.upload(file.file, remoteName); }
                            catch (DavClient.Conflict collision) {
                                String alternate = file.collisionName();
                                if (remoteName.equals(alternate)) throw collision;
                                files.uploadName(file, alternate); // Persist BEFORE network I/O for crash-safe retry.
                                remoteName = alternate;
                                receipt = client.upload(file.file, remoteName);
                            }
                            if (!current()) return;
                            files.uploaded(file, connection.target.key, remoteName, receipt);
                            ProblemNotifications.cloudHealthy(SyncJobService.this);
                            cloud.prefs.edit().putString("last_file", file.name).putLong("last_success", System.currentTimeMillis())
                                    .putInt("failures", 0).putLong("retry_at", 0).putInt("pending", queue.size() - 1).apply();
                            AppLog.write(SyncJobService.this, "WebDAV: передано й перевірено SHA-256: " + file.name);
                        }
                        // Let the OS reschedule before its execution window is exhausted.
                        if (SystemClock.elapsedRealtime() - started > 4 * 60000L) { next = 1000L; break; }
                    }
                }
            } catch (Exception e) {
                if (!stopped && cloud.enabled() && (revision == 0 || revision == cloud.revision())) {
                    int failures = Math.min(10, cloud.prefs.getInt("failures", 0) + 1);
                    next = TransferPolicy.retryDelay(failures);
                    cloud.prefs.edit().putInt("failures", failures).putLong("retry_at", System.currentTimeMillis() + next).apply();
                    String reason = CloudSettings.error(e);
                    cloud.status(reason + ". Повтор не раніше ніж через " + next / 1000L + " с за наявності мережі");
                    AppLog.write(SyncJobService.this, "WebDAV: " + reason);
                    if (pendingFiles) ProblemNotifications.cloudFailure(SyncJobService.this,revision);
                }
            } finally {
                if (client != null) client.close();
                if (acquired) TRANSFER.release();
                RUNNING.decrementAndGet();
                final long delay = next;
                main.post(() -> {
                    // Keep the runner visible to onStopJob until this main-thread callback.
                    runners.remove(parameters.getExtras().getLong("serial"), this);
                    if (!stopped) {
                        jobFinished(parameters, false);
                        SyncScheduler.finished(SyncJobService.this, parameters.getExtras().getLong("serial"), delay);
                    }
                });
            }
        }
    }
}
