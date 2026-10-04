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
    private RecordingFailure.Reason waitingReason;
    private long retryAt,lastDeviceRetry,gapSince,healthSince;
    private boolean awaitingCorrection;
    private int previousHealth;
    private android.media.AudioDeviceCallback devices;
    private android.content.BroadcastReceiver bluetoothEvents;
    static boolean retryNow() {
        RecorderService current=instance;
        if(current==null || current.destroying || current.worker==null)return false;
        current.worker.post(() -> {
            if(current.recorder!=null || !current.config.wanted())return;
            current.awaitingCorrection=false;current.retryAt=0;current.retryCount=0;
            current.worker.removeCallbacks(current.retry);current.applyDesired();
        });return true;
    }

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
        gapSince=config.prefs.getLong("record_gap_since",0);
        config.prefs.edit().putBoolean("engine_active", false).putInt("capture_health",CaptureHealth.NORMAL).apply();
        try {
            if (!permissionsGranted()) throw new SecurityException("Recording permission missing");
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification("Підготовка…"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION, notification("Підготовка…"));
            foregroundStarted = true; instance = this;
            ScheduleManager.clearReminder(this);
            observeInputs();
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
            if (recorder != null) recorder.abort(RecordingFailure.Reason.PERMISSION);
            else finishStopped(I18n.uk("record_permission"));
            return;
        }
        if (!config.wanted()) {
            awaitingCorrection=false;retryAt=0;waitingReason=null;retryCount=0;
            worker.removeCallbacks(retry);
            if (recorder != null) { report("Завершення й збереження запису…", 0); recorder.stop(); }
            else finishStopped("Запис зупинено");
            return;
        }
        worker.removeCallbacks(standbyTick);
        if(awaitingCorrection && recorder==null)return;
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (recorder == null && !awaitingCorrection && SystemClock.elapsedRealtime()>=retryAt) { worker.removeCallbacks(retry); begin(); }
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
        if(error!=null && config.wanted() && gapSince==0)
            gapSince=ended.failureAt()>0 ? ended.failureAt() : System.currentTimeMillis();
        if(previousHealth!=CaptureHealth.NORMAL) {
            if(healthSince>0)AppLog.write(this,I18n.s("capture_interval",Math.max(0,(System.currentTimeMillis()-healthSince)/1000)));
            healthSince=0;previousHealth=CaptureHealth.NORMAL;
            ProblemNotifications.captureState(this,CaptureHealth.NORMAL);
        }
        worker.removeCallbacks(heartbeat);
        config.prefs.edit().putBoolean("engine_active", false).putInt("peak", 0).putInt("finishing", 0).apply();
        try { updateStats(); } catch (Exception ignored) { }
        if (error != null) { AppLog.write(this, "Помилка запису: " + message(error)); ProblemNotifications.recordingFailure(this,error); }
        if (destroying) { releaseResources(); thread.quitSafely(); }
        else if (config.wanted()) {
            if (error == null) begin(); else scheduleRetry(error);
        } else finishStopped(error == null ? "Запис зупинено; файли збережено" : "Запис зупинено з помилкою: " + message(error));
    }
    private final Runnable retry = () -> {retryAt=0;applyDesired();};
    private void scheduleRetry(Throwable error) {
        RecordingFailure.Reason reason=RecordingFailure.classify(error);
        if(reason!=waitingReason)retryCount=0;
        waitingReason=reason;
        ProblemNotifications.recordingFailure(this,error);
        if (destroying || !config.wanted()) { finishStopped(message(error)); return; }
        long delay=RecordingFailure.retryDelay(reason,retryCount);
        retryCount=Math.min(10,retryCount+1);awaitingCorrection=delay<0;
        if(gapSince==0)gapSince=System.currentTimeMillis();
        config.prefs.edit().putBoolean("engine_active",false).putInt("capture_health",CaptureHealth.NORMAL)
                .putString("record_failure",reason.name()).putLong("record_gap_since",gapSince)
                .putLong("record_retry_at",delay<0 ? 0 : System.currentTimeMillis()+delay).apply();
        String detail=I18n.uk(RecordingFailure.key(reason));
        AppLog.write(this,"Recording ["+reason.name()+"] "+message(error));
        report(detail+(delay<0 ? "\n"+I18n.uk("recovery_user_action") : ". Повтор через "+delay/1000+" с"),0);
        worker.removeCallbacks(retry);
        if(delay<0) {retryAt=Long.MAX_VALUE;releaseResources();}
        else {retryAt=SystemClock.elapsedRealtime()+delay;worker.postDelayed(retry,delay);}
    }
    private void observeInputs() {
        android.media.AudioManager manager=getSystemService(android.media.AudioManager.class);
        devices=new android.media.AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
                for(android.media.AudioDeviceInfo device:added) {
                    if(AudioInputPolicy.bluetooth(config.input()) ? BluetoothRoute.type(device.getType()) : AudioInputs.key(device).equals(config.input())) {
                        inputReturned();break;
                    }
                }
            }
        };
        manager.registerAudioDeviceCallback(devices,worker);
        bluetoothEvents=new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context,Intent intent) {
                if(!AudioInputPolicy.bluetooth(config.input()))return;
                boolean connected=android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())
                        && intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE,-1)==android.bluetooth.BluetoothProfile.STATE_CONNECTED;
                boolean enabled=android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                        && intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1)==android.bluetooth.BluetoothAdapter.STATE_ON;
                if(connected || enabled)worker.post(() -> inputReturned());
            }
        };
        android.content.IntentFilter filter=new android.content.IntentFilter(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(bluetoothEvents,filter,android.content.Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothEvents,filter);
    }
    private void inputReturned() {
        if(destroying || recorder!=null || !config.wanted() || (waitingReason!=RecordingFailure.Reason.INPUT_UNAVAILABLE && waitingReason!=RecordingFailure.Reason.INPUT_BUSY))return;
        long now=SystemClock.elapsedRealtime();if(now-lastDeviceRetry<1000)return;lastDeviceRetry=now;
        retryAt=0;worker.removeCallbacks(retry);applyDesired();
    }
    private void captureState(ContinuousRecorder current,int health) {
        if(health!=previousHealth) {
            if(previousHealth!=CaptureHealth.NORMAL && healthSince>0)
                AppLog.write(this,I18n.s("capture_interval",(System.currentTimeMillis()-healthSince)/1000));
            healthSince=health==CaptureHealth.NORMAL ? 0 : System.currentTimeMillis();
            AppLog.write(this,I18n.s(health==CaptureHealth.SYSTEM_SILENCED ? "capture_system_silenced" : health==CaptureHealth.ZERO_SIGNAL ? "capture_zero_signal" : "capture_restored"));
            previousHealth=health;ProblemNotifications.captureState(this,health);
        }
        if(current.capturing() && current.segmentStart()>0 && health==CaptureHealth.NORMAL && gapSince>0) {
            AppLog.write(this,I18n.s("recording_gap_ended",Math.max(0,(System.currentTimeMillis()-gapSince)/1000)));
            gapSince=0;config.prefs.edit().remove("record_gap_since").remove("record_failure").remove("record_retry_at").apply();
            // Keep the last failure kind until five healthy minutes have elapsed.
            // Brief recoveries must not reset backoff on a repeatedly failing input/codec.
            awaitingCorrection=false;retryAt=0;
        }
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
            int health=current.captureHealth();captureState(current,health);
            if(health!=CaptureHealth.NORMAL || !current.capturing())stableSince=now;
            if (current.recording() && config.wanted()
                    && (now - current.lastCapture() > 30000L || now - current.lastWrite() > 30000L))
                current.abort(RecordingFailure.Reason.STALLED);
            if (!healthReported && health==CaptureHealth.NORMAL && current.capturing() && now-stableSince>60000L && now-current.lastCapture()<5000L && now-current.lastWrite()<5000L) {
                ProblemNotifications.recordingHealthy(RecorderService.this); healthReported=true;
            }
            if (health==CaptureHealth.NORMAL && current.capturing() && now-stableSince>300000L) {
                retryCount=0;waitingReason=null;
            }
            try { if (now - lastStats >= 10000L) { updateStats(); lastStats = now; } }
            catch (Exception e) { current.abort(RecordingFailure.classify(e)==RecordingFailure.Reason.STORAGE_FULL ? RecordingFailure.Reason.STORAGE_FULL : RecordingFailure.Reason.STORAGE_IO); }
            config.prefs.edit().putLong("segment_ms", current.segmentMillis())
                    .putInt("peak", Math.round(current.peak() * 100))
                    .putFloat("peak_raw", current.peak())
                    .putBoolean("limiting", current.limitedFraction() > 0.01f)
                    .putInt("finishing", current.finishing()).putInt("capture_health",health).apply();
            String status = !config.wanted() ? "Завершення й збереження запису…"
                    : health==CaptureHealth.SYSTEM_SILENCED ? I18n.uk("capture_system_silenced")
                    : health==CaptureHealth.ZERO_SIGNAL ? I18n.uk("capture_zero_signal")
                    : current.capturing() ? "Триває запис" : "Підготовка або завершення аудіодвигуна…";
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
        config.prefs.edit().remove("record_failure").remove("record_retry_at").remove("record_gap_since").putInt("capture_health",CaptureHealth.NORMAL).apply();
        gapSince=0;waitingReason=null;awaitingCorrection=false;retryAt=0;previousHealth=CaptureHealth.NORMAL;
        ProblemNotifications.captureState(this,CaptureHealth.NORMAL);
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
        if(devices!=null)getSystemService(android.media.AudioManager.class).unregisterAudioDeviceCallback(devices);
        if(bluetoothEvents!=null)try{unregisterReceiver(bluetoothEvents);}catch(RuntimeException ignored){}
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
