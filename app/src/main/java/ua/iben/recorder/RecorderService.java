package ua.iben.recorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

public final class RecorderService extends Service {
    static final String START = "ua.iben.recorder.oreo.START";
    static final String STOP = "ua.iben.recorder.oreo.STOP";
    static final String PAUSE_ALL = "ua.iben.recorder.oreo.PAUSE_ALL";
    private static volatile RecorderService instance;
    static boolean alive() { RecorderService s = instance; return s != null && !s.destroying && s.foregroundStarted; }
    static boolean dispatch() {
        RecorderService s = instance;
        if (s == null || s.destroying || !s.foregroundStarted || s.worker == null) return false;
        PowerManager.WakeLock command = s.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "IbenRecorder:command");
        command.acquire(15000L);
        boolean posted = s.worker.post(() -> {
            try { s.applyDesired(); }
            finally { if (command.isHeld()) command.release(); }
        });
        if (!posted && command.isHeld()) command.release();
        return posted;
    }
    static RecordingPosition.Moment bookmarkPosition() {
        RecorderService service=instance;
        ContinuousRecorder engine=service==null ? null : service.recorder;
        return engine==null ? null : engine.bookmarkPosition();
    }
    private boolean foregroundStarted;
    private static final String CHANNEL = "recording";
    private static final int NOTIFICATION = 1;
    private HandlerThread thread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private Config config;
    private RecordingFiles files;
    private volatile ContinuousRecorder recorder;
    private volatile boolean destroying;
    private int retryCount;
    private int latestStartId;
    private long stableSince;
    private long lastStats;
    private long lastScheduleCheck;
    private boolean healthReported;
    static final String PAUSE_SCHEDULE="ua.iben.recorder.oreo.PAUSE_SCHEDULE";

    @Override public void onCreate() {
        super.onCreate();
        config = new Config(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, I18n.tr("Аудіозапис"), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(I18n.tr("Стан безперервного запису та кнопка зупинки"));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        thread = new HandlerThread("iben-control"); thread.start();
        worker = new Handler(thread.getLooper());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IbenRecorder:recording");
        wakeLock.setReferenceCounted(false);
        config.prefs.edit().putBoolean("engine_active", false).apply();
        try {
            if (!permissionsGranted()) throw new SecurityException("Recording permission missing");
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification("Підготовка…"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION, notification("Підготовка…"));
            foregroundStarted = true; instance = this;
            ScheduleManager.clearReminder(this);
        } catch (RuntimeException e) {
            AppLog.write(this, "Foreground service: " + e.getClass().getSimpleName());
            ScheduleManager.awaitingUser(this);
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) { stopSelf(startId); return START_NOT_STICKY; }
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) ScheduleManager.markManualStop(this);
        if (PAUSE_ALL.equals(action)) ScheduleManager.pauseAll(this);
        if (PAUSE_SCHEDULE.equals(action)) ScheduleManager.pauseSchedule(this);
        if (START.equals(action)) config.prefs.edit().putString("origin", "manual").putBoolean("wanted", true).commit();
        worker.post(() -> { latestStartId = startId; applyDesired(); });
        return START_STICKY;
    }
    private void applyDesired() {
        if (destroying) return;
        if (!permissionsGranted()) {
            config.wanted(false);
            if (recorder != null) recorder.abort(I18n.uk("record_permission"));
            else finishStopped(I18n.uk("record_permission"));
            return;
        }
        if (!config.wanted()) {
            worker.removeCallbacks(retry);
            if (recorder != null) { report("Завершення й збереження запису…", 0); recorder.stop(); }
            else finishStopped("Запис зупинено");
            return;
        }
        worker.removeCallbacks(standbyTick);
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (recorder == null) { worker.removeCallbacks(retry); begin(); }
    }
    private boolean permissionsGranted() { return Platform.recordingGranted(this); }
    private final Runnable standbyTick = new Runnable() {
        @Override public void run() {
            if (destroying || recorder != null) return;
            ScheduleManager.reconcile(RecorderService.this, false);
            if (config.wanted()) applyDesired();
            else if (ScheduleManager.standby(config)) {
                report(I18n.uk("standby_status"), 0);
                worker.postDelayed(this, 30000L);
            } else finishStopped("Запис зупинено");
        }
    };
    private void begin() {
        if (destroying || !config.wanted() || recorder != null) return;
        try {
            if (files == null) files = new RecordingFiles(this, config);
            recorder = new ContinuousRecorder(config, files, (ended, error) -> worker.post(() -> ended(ended, error)));
            config.prefs.edit().putBoolean("engine_active", true).putLong("segment_ms", 0).apply();
            stableSince = SystemClock.elapsedRealtime(); lastStats = 0; healthReported = false;
            report("Підготовка запису…", 0);
            AppLog.write(this, "Запуск безперервного аудіодвигуна");
            recorder.start();
            worker.removeCallbacks(heartbeat); worker.post(heartbeat);
        } catch (Exception e) { recorder = null; scheduleRetry(e); }
    }
    private void ended(ContinuousRecorder ended, Throwable error) {
        if (recorder != ended) return;
        recorder = null;
        worker.removeCallbacks(heartbeat);
        config.prefs.edit().putBoolean("engine_active", false).putInt("peak", 0).putInt("finishing", 0).apply();
        try { updateStats(); } catch (Exception ignored) { }
        if (error != null) { AppLog.write(this, "Помилка запису: " + message(error)); ProblemNotifications.recordingFailure(this,error); }
        if (destroying) { releaseResources(); thread.quitSafely(); }
        else if (config.wanted()) {
            if (error == null) begin(); else scheduleRetry(error);
        } else finishStopped(error == null ? "Запис зупинено; файли збережено" : "Запис зупинено з помилкою: " + message(error));
    }
    private final Runnable retry = this::begin;
    private void scheduleRetry(Throwable error) {
        ProblemNotifications.recordingFailure(this,error);
        config.prefs.edit().putBoolean("engine_active", false).apply();
        if (destroying || !config.wanted()) { finishStopped(message(error)); return; }
        long delay = retryCount == 0 ? 5000L : retryCount == 1 ? 15000L : 60000L;
        retryCount = Math.min(2, retryCount + 1);
        AppLog.write(this, message(error) + "; повтор через " + delay / 1000L + " с");
        report(message(error) + ". Повтор через " + delay / 1000L + " с", 0);
        worker.removeCallbacks(retry); worker.postDelayed(retry, delay);
    }
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (destroying || recorder == null) return;
            if (SystemClock.elapsedRealtime() - lastScheduleCheck >= 10000) {
                lastScheduleCheck = SystemClock.elapsedRealtime(); ScheduleManager.reconcile(RecorderService.this, false);
            }
            ContinuousRecorder current = recorder;
            if (!config.wanted()) current.stop();
            long now = SystemClock.elapsedRealtime();
            if (current.recording() && config.wanted()
                    && (now - current.lastCapture() > 30000L || now - current.lastWrite() > 30000L))
                current.abort("Немає нового аудіо понад 30 секунд");
            if (!healthReported && current.recording() && now-stableSince>60000L && now-current.lastCapture()<5000L && now-current.lastWrite()<5000L) {
                ProblemNotifications.recordingHealthy(RecorderService.this); healthReported=true;
            }
            if (now - stableSince > 300000L) retryCount = 0;
            try { if (now - lastStats >= 10000L) { updateStats(); lastStats = now; } }
            catch (Exception e) { current.abort("Контроль пам’яті: " + message(e)); }
            config.prefs.edit().putLong("segment_ms", current.segmentMillis())
                    .putInt("peak", Math.round(current.peak() * 100))
                    .putFloat("peak_raw", current.peak())
                    .putBoolean("limiting", current.limitedFraction() > 0.01f)
                    .putInt("finishing", current.finishing()).apply();
            String status = !config.wanted() ? "Завершення й збереження запису…"
                    : current.recording() ? "Триває запис" : "Підготовка або завершення аудіодвигуна…";
            report(status, current.segmentStart());
            worker.postDelayed(this, 1000L);
        }
    };
    private void updateStats() throws Exception {
        if (files == null) return;
        long[] stats = files.stats();
        config.prefs.edit().putLong("used_bytes", stats[0]).putLong("free_bytes", stats[1]).putLong("closed_count", stats[2]).apply();
    }
    private void finishStopped(String text) {
        worker.removeCallbacks(retry); worker.removeCallbacks(heartbeat); worker.removeCallbacks(standbyTick);
        config.prefs.edit().putBoolean("engine_active", false).putInt("peak", 0).putInt("finishing", 0).apply();
        config.status(text, 0); AppLog.write(this, text);
        releaseResources();
        if (!destroying && ScheduleManager.standby(config)) {
            report(I18n.uk("standby_status"), 0);
            worker.postDelayed(standbyTick, 30000L);
            return;
        }
        final int completedStartId = latestStartId;
        new Handler(getMainLooper()).post(() -> {
            if (config.wanted() || ScheduleManager.standby(config)) { dispatch(); return; }
            stopSelfResult(completedStartId);
        });
    }
    private void releaseResources() {
        if (files != null) { files.close(); files = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    private void report(String text, long start) {
        config.status(text, start);
        if (Platform.notifications(this)) {
            try { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text)); }
            catch (SecurityException ignored) { }
        }
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RecorderService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 3, new Intent(this, RecorderService.class).setAction(PAUSE_SCHEDULE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle("Iben Recorder")
                .setContentText(I18n.tr(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, I18n.s("stop"), stop).build());
        if (config.scheduleEnabled() && !config.prefs.getBoolean("schedule_paused",false)) builder.addAction(new Notification.Action.Builder(null, I18n.s("schedule_pause_only"), pause).build());
        return builder.build();
    }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        if (worker != null) worker.post(() -> {
            destroying = true;
            worker.removeCallbacks(retry); worker.removeCallbacks(heartbeat); worker.removeCallbacks(standbyTick);
            if (recorder != null) recorder.stop();
            else { releaseResources(); thread.quitSafely(); }
        });
        stopForeground(true);
        super.onDestroy();
    }
    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
