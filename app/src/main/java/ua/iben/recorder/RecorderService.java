package ua.iben.recorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;

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
    private MediaRecorder recorder;
    private File part;
    private boolean running;
    private boolean stopping;
    private long segmentElapsed;
    private long segmentWall;
    private long lastBytes;
    private long lastGrowth;
    private long segmentLength;
    private long lastStorageCheck;
    private int retryCount;
    private int latestStartId;

    @Override public void onCreate() {
        super.onCreate();
        config = new Config(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Аудіозапис",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Стан безперервного запису та кнопка зупинки");
        manager.createNotificationChannel(channel);
        startForeground(NOTIFICATION, notification("Підготовка…"));
        thread = new HandlerThread("recorder-control");
        thread.start();
        worker = new Handler(thread.getLooper());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "J7Recorder:recording");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        worker.post(() -> {
            latestStartId = startId;
            String action = intent == null ? null : intent.getAction();
            if (STOP.equals(action)) {
                config.wanted(false);
                stopRequested("Запис зупинено");
                return;
            }
            if (START.equals(action)) config.wanted(true);
            if (!config.wanted()) {
                stopRequested("Запис вимкнено");
                return;
            }
            if (Build.VERSION.SDK_INT != 29 || !permissionsGranted()) {
                config.wanted(false);
                stopRequested(Build.VERSION.SDK_INT != 29
                        ? "Цей прототип призначений для Android 10"
                        : "Потрібні дозволи на мікрофон і файли");
                return;
            }
            if (running) return;
            stopping = false;
            running = true;
            // A dedicated recorder must continue while the screen is off.
            // Released on every explicit stop / service destruction; the OS releases on process death.
            if (!wakeLock.isHeld()) wakeLock.acquire();
            AppLog.write(this, "Сервіс запущено");
            beginSegment();
        });
        return START_STICKY;
    }

    private boolean permissionsGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void beginSegment() {
        if (!running || stopping) return;
        worker.removeCallbacks(retry);
        try {
            if (files == null) files = new RecordingFiles(this, config);
            files.recover();
            long budget = StoragePolicy.segmentBudget(config.minutes(), config.bitrate());
            if (!files.ensureRoom(budget))
                throw new IOException("Недостатньо місця в межах ліміту; очікування вільної пам’яті");
            part = files.newPart();
            final MediaRecorder current = new MediaRecorder();
            recorder = current;
            current.setAudioSource(MediaRecorder.AudioSource.MIC);
            current.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            current.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            current.setAudioChannels(1);
            current.setAudioSamplingRate(config.sampleRate());
            current.setAudioEncodingBitRate(config.bitrate() * 1000);
            current.setOutputFile(part.getAbsolutePath());
            current.setOnErrorListener((source, what, extra) -> worker.post(() -> {
                if (recorder == current && running && !stopping)
                    recordingFailed("Помилка аудіокодека: " + what + "/" + extra);
            }));
            // Use our serialized timer, not setMaxDuration's asynchronously stopped callback.
            current.prepare();
            current.start();
            segmentElapsed = SystemClock.elapsedRealtime();
            segmentWall = System.currentTimeMillis();
            segmentLength = config.minutes() * 60000L;
            lastBytes = part.length();
            lastGrowth = segmentElapsed;
            lastStorageCheck = segmentElapsed;
            retryCount = 0;
            worker.postDelayed(rotate, segmentLength);
            worker.postDelayed(heartbeat, 5000);
            report("Триває запис", segmentWall);
            AppLog.write(this, "Запис: " + config.minutes() + " хв, AAC " + config.bitrate()
                    + " кбіт/с, " + config.sampleRate() + " Гц, моно");
        } catch (Exception e) {
            recordingFailed("Не вдалося почати запис: " + message(e));
        }
    }

    private final Runnable rotate = () -> {
        if (!running || stopping) return;
        try {
            finishSegment();
            beginSegment();
        } catch (Exception e) { recordingFailed("Помилка завершення: " + message(e)); }
    };

    private final Runnable retry = () -> {
        if (running && !stopping) beginSegment();
    };

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running || stopping || recorder == null || part == null) return;
            try {
                long now = SystemClock.elapsedRealtime();
                long bytes = part.length();
                if (bytes > lastBytes) { lastBytes = bytes; lastGrowth = now; }
                if (now - lastGrowth > 120000L) {
                    recordingFailed("Файл не збільшується понад 2 хвилини; повторний запуск");
                    return;
                }
                if (now - lastStorageCheck >= 60000L) {
                    lastStorageCheck = now;
                    long remaining = Math.max(0, segmentLength - (now - segmentElapsed));
                    long expectedRemaining = remaining * config.bitrate() / 8L;
                    if (!files.ensureRoom(expectedRemaining + 2 * StoragePolicy.MIB)) {
                        recordingFailed("Мало вільної пам’яті; поточний фрагмент завершено");
                        return;
                    }
                }
                long[] stats = files.stats();
                config.prefs.edit().putLong("used_bytes", stats[0]).putLong("free_bytes", stats[1])
                        .putLong("closed_count", stats[2]).apply();
                report("Триває запис", segmentWall);
                worker.postDelayed(this, 5000L);
            } catch (Exception e) { recordingFailed("Контроль запису: " + message(e)); }
        }
    };

    private void finishSegment() throws IOException {
        worker.removeCallbacks(rotate);
        worker.removeCallbacks(heartbeat);
        MediaRecorder current = recorder;
        File completed = part;
        recorder = null;
        part = null;
        segmentWall = 0;
        if (current != null) {
            try {
                current.setOnErrorListener(null);
                current.stop();
            } catch (RuntimeException e) {
                AppLog.write(this, "Кодек не завершив файл штатно: " + message(e));
            } finally {
                try { current.release(); } catch (RuntimeException ignored) { }
            }
        }
        if (files != null && completed != null) files.finish(completed);
    }

    private void recordingFailed(String text) {
        try { finishSegment(); }
        catch (Exception e) { AppLog.write(this, "Залишено файл для відновлення: " + message(e)); }
        AppLog.write(this, text);
        if (!running || stopping) return;
        long delay = retryCount == 0 ? 5000L : retryCount == 1 ? 15000L : 60000L;
        retryCount = Math.min(2, retryCount + 1);
        report(text + ". Повтор через " + delay / 1000L + " с", 0);
        worker.removeCallbacks(retry);
        worker.postDelayed(retry, delay);
    }

    private void stopRequested(String text) {
        stopping = true;
        running = false;
        worker.removeCallbacks(rotate);
        worker.removeCallbacks(heartbeat);
        worker.removeCallbacks(retry);
        try { finishSegment(); }
        catch (Exception e) { AppLog.write(this, "Файл залишено для відновлення: " + message(e)); }
        config.status(text, 0);
        AppLog.write(this, text);
        releaseWakeLock();
        // Queue stop on the main thread; stopSelfResult protects a more recent start request.
        final int completedStartId = latestStartId;
        new Handler(getMainLooper()).post(() -> stopSelfResult(completedStartId));
    }

    private void report(String text, long start) {
        config.status(text, start);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, RecorderService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic).setContentTitle("J7 Recorder")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Зупинити", stop).build()).build();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public void onDestroy() {
        if (worker != null) {
            // Let the worker serialize cleanup with any in-progress prepare/stop operation.
            worker.post(() -> {
                stopping = true;
                running = false;
                worker.removeCallbacksAndMessages(null);
                try { finishSegment(); }
                catch (Exception e) { AppLog.write(this, "Завершення сервісу: " + message(e)); }
                releaseWakeLock();
                thread.quitSafely();
            });
        } else releaseWakeLock();
        stopForeground(true);
        super.onDestroy();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
