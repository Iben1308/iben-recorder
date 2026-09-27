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
    static final String START = "ua.iben.recorder.START";
    static final String STOP = "ua.iben.recorder.STOP";
    private static final String CHANNEL = "recording";
    private static final int NOTIFICATION = 1;
    private HandlerThread thread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private Config config;
    private RecordingFiles files;
    private ContinuousRecorder recorder;
    private boolean destroying;
    private int retryCount;
    private int latestStartId;
    private long stableSince;
    private long lastStats;

    @Override public void onCreate() {
        super.onCreate();
        config = new Config(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Аудіозапис", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Стан безперервного запису та кнопка зупинки");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION, notification("Підготовка…"));
        thread = new HandlerThread("iben-control"); thread.start();
        worker = new Handler(thread.getLooper());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IbenRecorder:recording");
        wakeLock.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        worker.post(() -> {
            latestStartId = startId;
            String action = intent == null ? null : intent.getAction();
            if (STOP.equals(action)) {
                config.wanted(false); worker.removeCallbacks(retry);
                if (recorder != null) { report("Завершення й збереження запису…", 0); recorder.stop(); }
                else finishStopped("Запис зупинено");
                return;
            }
            if (START.equals(action)) config.wanted(true);
            if (!config.wanted()) { if (recorder == null) finishStopped("Запис вимкнено"); return; }
            if (Build.VERSION.SDK_INT != 29 || !permissionsGranted()) {
                config.wanted(false);
                String reason = Build.VERSION.SDK_INT != 29 ? "Потрібен Android 10" : "Потрібні дозволи на мікрофон і файли";
                if (recorder != null) recorder.abort(reason); else finishStopped(reason);
                return;
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (recorder == null) { worker.removeCallbacks(retry); begin(); }
        });
        return START_STICKY;
    }
    private boolean permissionsGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void begin() {
        if (destroying || !config.wanted() || recorder != null) return;
        try {
            if (files == null) files = new RecordingFiles(this, config);
            recorder = new ContinuousRecorder(config, files, (ended, error) -> worker.post(() -> ended(ended, error)));
            config.prefs.edit().putBoolean("engine_active", true).putLong("segment_ms", 0).apply();
            stableSince = SystemClock.elapsedRealtime(); lastStats = 0;
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
        if (error != null) AppLog.write(this, "Помилка запису: " + message(error));
        if (destroying) { releaseResources(); thread.quitSafely(); }
        else if (config.wanted()) {
            if (error == null) begin(); else scheduleRetry(error);
        } else finishStopped(error == null ? "Запис зупинено; файли збережено" : "Запис зупинено з помилкою: " + message(error));
    }
    private final Runnable retry = this::begin;
    private void scheduleRetry(Throwable error) {
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
            ContinuousRecorder current = recorder;
            long now = SystemClock.elapsedRealtime();
            if (current.recording() && config.wanted()
                    && (now - current.lastCapture() > 30000L || now - current.lastWrite() > 30000L))
                current.abort("Немає нового аудіо понад 30 секунд");
            if (now - stableSince > 300000L) retryCount = 0;
            try { if (now - lastStats >= 10000L) { updateStats(); lastStats = now; } }
            catch (Exception e) { current.abort("Контроль пам’яті: " + message(e)); }
            config.prefs.edit().putLong("segment_ms", current.segmentMillis())
                    .putInt("peak", Math.round(current.peak() * 100))
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
        worker.removeCallbacks(retry); worker.removeCallbacks(heartbeat);
        config.prefs.edit().putBoolean("engine_active", false).putInt("peak", 0).putInt("finishing", 0).apply();
        config.status(text, 0); AppLog.write(this, text);
        releaseResources();
        final int completedStartId = latestStartId;
        new Handler(getMainLooper()).post(() -> stopSelfResult(completedStartId));
    }
    private void releaseResources() {
        if (files != null) { files.close(); files = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    private void report(String text, long start) {
        config.status(text, start);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RecorderService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle("Iben Recorder")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Зупинити", stop).build()).build();
    }
    @Override public void onDestroy() {
        if (worker != null) worker.post(() -> {
            destroying = true;
            worker.removeCallbacks(retry); worker.removeCallbacks(heartbeat);
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
