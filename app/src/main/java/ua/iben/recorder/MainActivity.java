package ua.iben.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private Config config;
    private Ui ui;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final View[] pages = new View[3];
    private final ImageButton[] tabs = new ImageButton[3];
    private ListenPanel listen;
    private SettingsPanel settings;
    private TextView status, duration, details, gainLabel, levelLabel, cloud, input;
    private Button record, addBookmark;
    private ProgressBar level;
    private int currentTab = 1;
    private Runnable afterPermission;
    private String[] requestedPermissions;
    private boolean resumed;
    private Runnable afterNotification;
    private String exportId;
    private java.util.ArrayList<String> exportIds;
    private static final int EXPORT = 8106, RESTORE = 8107, EXPORT_MANY=8108;
    private long refreshed;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(LocaleContext.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        config = new Config(this); ui = new Ui(this, config);
        setTheme(ui.dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(state);
        getWindow().setStatusBarColor(ui.background); getWindow().setNavigationBarColor(ui.background);
        LinearLayout root = ui.column(); root.setBackgroundColor(ui.background);
        TextView brand = ui.text(root, "Iben Recorder", 23, ui.ink);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD); brand.setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(10));
        FrameLayout body = new FrameLayout(this); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        listen = new ListenPanel(this, ui, config); pages[0] = listen.view;
        pages[1] = recordPage();
        settings = new SettingsPanel(this, ui, config, state); pages[2] = settings.view;
        for (View page : pages) body.addView(page, new FrameLayout.LayoutParams(-1, -1));
        View divider = new View(this); divider.setBackgroundColor(ui.pale);
        root.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout bar = ui.row(); bar.setBackgroundColor(ui.card);
        bar.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); root.addView(bar);
        String[] labels = {I18n.s("listen"), I18n.s("record"), I18n.s("settings")};
        int[] icons = {R.drawable.ic_speaker, R.drawable.ic_mic, R.drawable.ic_settings};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tabs[i] = ui.icon(icons[i], labels[i], () -> tab(index));
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, ui.dp(52), 1);
            slot.setMargins(ui.dp(6), 0, ui.dp(6), 0); bar.addView(tabs[i], slot);
        }
        setContentView(root); Platform.insets(this, root, ui.dark);
        exportId = state == null ? null : state.getString("export_id");
        exportIds=state==null ? null : state.getStringArrayList("export_ids");
        int selected = state != null ? state.getInt("tab", 1) : getIntent().getIntExtra("tab", config.prefs.getInt("last_tab", 1));
        tab(Math.max(0, Math.min(2, selected)));
        if(state==null && getIntent().hasExtra("settings_section"))settings.showSection(getIntent().getIntExtra("settings_section",0));
        refresh();
    }
    private View recordPage() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = ui.column(); page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); scroll.addView(page);
        LinearLayout main = ui.card(page);
        status = ui.text(main, "", 15, ui.accent); status.setGravity(Gravity.CENTER);
        duration = ui.text(main, "00:00:00", 43, ui.ink); duration.setGravity(Gravity.CENTER);
        duration.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        ui.text(main, I18n.s("current_segment"), 12, ui.muted).setGravity(Gravity.CENTER);
        record = ui.button(main, I18n.s("start_recording"), () -> {
            if (config.wanted()) { ScheduleManager.manualStop(this); refresh(); }
            else recordingPermission(() -> {
                if (DocumentTransfers.restoring()) { ui.toast(I18n.s("restore_busy")); return; }
                listen.pause(); ScheduleManager.manualStart(this); refresh();
            });
        }, true);
        addBookmark=ui.button(main,I18n.s("bookmark_add"),() -> {
            RecordingPosition.Moment moment=RecorderService.bookmarkPosition();
            if(moment==null)ui.toast(I18n.s("bookmark_unavailable"));
            else Bookmarks.add(this,ui,moment.id,moment.millis);
        },false);
        details = ui.text(main, "", 13, ui.muted);
        LinearLayout sound = ui.card(page);
        ui.helpTitle(sound, I18n.s("sound"), I18n.s("gain_hint"));
        levelLabel = ui.text(sound, "", 14, ui.ink);
        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100); sound.addView(level, new LinearLayout.LayoutParams(-1, ui.dp(12)));
        level.setContentDescription(I18n.s("level"));
        gainLabel = ui.text(sound, "", 16, ui.ink);
        SeekBar gain = new SeekBar(this); gain.setMax(24); gain.setProgress(config.gainDb()); sound.addView(gain);
        gain.setContentDescription(I18n.s("gain_accessibility"));
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) {
                if (fromUser) config.gainDb(value); gainLabel.setText(I18n.s("gain", value));
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        input = ui.text(sound, "", 12, ui.muted);
        LinearLayout storage = ui.card(page);
        ui.title(storage, I18n.s("webdav_title")); cloud = ui.text(storage, "", 13, ui.muted);
        ui.text(page, I18n.s("record_manual_hint"), 12, ui.muted);
        return scroll;
    }
    private void tab(int index) {
        listen.visible(index == 0 && resumed);
        currentTab = index;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            ui.navigationState(tabs[i], i == index);
        }
        config.prefs.edit().putInt("last_tab", index).apply();
        if (index == 0 && resumed) listen.load();
        if (index == 2) settings.refresh();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (intent.hasExtra("tab")) tab(Math.max(0,Math.min(2,intent.getIntExtra("tab", 1)))); if(intent.hasExtra("settings_section"))settings.showSection(intent.getIntExtra("settings_section",0)); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("export_id", exportId);state.putStringArrayList("export_ids",exportIds); state.putInt("tab", currentTab); settings.saveDraft(state);
    }
    @Override public void onBackPressed() {
        if(currentTab==0 && listen.finishSelection())return;
        super.onBackPressed();
    }
    void recordingPermission(Runnable action) { permission(Platform.permissions(true), () -> notificationPermission(action)); }
    void storagePermission(Runnable action) { permission(Platform.permissions(false), action); }
    private void permission(String[] permissions, Runnable action) {
        for (String p : permissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            if (afterPermission != null) return;
            afterPermission = action; requestedPermissions = permissions; requestPermissions(permissions, 100); return;
        }
        action.run();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 101) {
            Runnable next = afterNotification; afterNotification = null;
            if (next != null) next.run();
            return;
        }
        if (request != 100 || afterPermission == null) return;
        Runnable action = afterPermission; afterPermission = null;
        for (String p : requestedPermissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            ui.toast(I18n.s("permission_required")); return;
        }
        action.run();
    }
    void notificationPermission(Runnable next) {
        if (Build.VERSION.SDK_INT >= 33 && !Platform.granted(this, Manifest.permission.POST_NOTIFICATIONS)
                && !config.prefs.getBoolean("notification_asked", false)) {
            config.prefs.edit().putBoolean("notification_asked", true).apply();
            afterNotification = next; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        } else next.run();
    }
    void exportRecording(RecordingFiles.Item item) {
        exportId = item.id;
        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/mp4").putExtra(Intent.EXTRA_TITLE, item.name), EXPORT); }
        catch (RuntimeException e) { ui.toast(I18n.s("document_error")); }
    }
    void exportRecordings(java.util.List<RecordingFiles.Item> items) {
        exportIds=new java.util.ArrayList<>();for(RecordingFiles.Item item:items)exportIds.add(item.id);
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION),EXPORT_MANY); }
        catch(RuntimeException e) { exportIds=null;ui.toast(I18n.s("document_error")); }
    }
    void restoreFolder() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), RESTORE); }
        catch (RuntimeException e) { ui.toast(I18n.s("document_error")); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if(request==EXPORT_MANY) {
            android.net.Uri destination=data.getData();
            java.util.ArrayList<String> requested=exportIds;exportIds=null;
            if(requested==null || requested.isEmpty())return;
            android.content.Context app=getApplicationContext();
            RecordEdits.worker.execute(() -> {
                java.util.List<RecordingFiles.Item> found=new java.util.ArrayList<>();
                try(RecordingFiles files=new RecordingFiles(app,new Config(app))) {
                    java.util.Set<String> ids=new java.util.HashSet<>(requested);
                    for(RecordingFiles.Item item:files.recordings())if(ids.contains(item.id))found.add(item);
                } catch(Exception ignored) { }
                runOnUiThread(() -> {
                    if(isDestroyed() || isFinishing())return;
                    if(found.isEmpty())ui.toast(I18n.s("batch_result",0,requested.size(),0,0));
                    else listen.runBatch(found,BatchWork.Action.EXPORT,destination,requested.size()-found.size());
                });
            });return;
        }
        if (request != EXPORT && request != RESTORE) return;
        android.net.Uri uri = data.getData(); String id = exportId;
        android.content.Context app = getApplicationContext();
        ui.toast(I18n.s("copying"));
        new Thread(() -> {
            String message;
            try {
                if (request == EXPORT) { DocumentTransfers.export(app, id, uri); message = I18n.s("export_done"); }
                else message = I18n.s("restore_done", DocumentTransfers.restore(app, uri));
            } catch (Exception e) { message = I18n.s("copy_failed") + " " + I18n.tr(e.getMessage()); }
            String text = message;
            runOnUiThread(() -> { if (!isDestroyed()) { ui.toast(text); if (currentTab == 0) listen.load(); } });
        }, "iben-documents").start();
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - refreshed >= 1000) { refreshed = now; refresh(); }
            if (currentTab == 0) listen.tick(); timer.postDelayed(this, 250);
        }
    };
    @Override public void onResume() {
        super.onResume(); resumed = true; listen.visible(currentTab == 0); timer.post(tick); SyncScheduler.kick(this); ScheduleManager.reconcile(this, true);
        if (currentTab == 0) listen.load();
    }
    @Override public void onPause() { resumed = false; timer.removeCallbacks(tick); listen.suspend(); super.onPause(); }
    @Override public void onDestroy() { listen.destroy(); super.onDestroy(); }
    void silenceChanged() { listen.thresholdChanged(); }
    private void refresh() {
        boolean wanted = config.wanted(), engine = config.prefs.getBoolean("engine_active", false);
        boolean stale = (wanted || engine) && System.currentTimeMillis() - config.prefs.getLong("heartbeat", 0) > 90000;
        boolean active = engine && !stale;
        status.setText(config.prefs.getBoolean("awaiting_user", false) ? I18n.s("resume_required") : stale ? I18n.s("stale") : I18n.tr(config.prefs.getString("status", "Запис вимкнено")));
        duration.setText(Ui.clock(active ? config.prefs.getLong("segment_ms", 0) : 0));
        record.setText(I18n.s(wanted ? "stop_save" : engine && !stale ? "saving" : "start_recording"));
        record.setEnabled(wanted || !active);
        addBookmark.setEnabled(RecorderService.bookmarkPosition()!=null);
        long used = config.prefs.getLong("used_bytes", 0), free = config.prefs.getLong("free_bytes", 0);
        details.setText(I18n.s("storage_info", used / 1048576d, config.quotaMiB(), free / 1048576d)
                + "\n" + I18n.s("format_info", config.minutes(), config.bitrate(), config.sampleRate())
                + (active && config.prefs.getInt("finishing", 0) > 0 ? "\n" + I18n.s("finishing", config.prefs.getInt("finishing", 0)) : ""));
        int peak = active ? config.prefs.getInt("peak", 0) : 0; level.setProgress(peak);
        level.setProgressTintList(android.content.res.ColorStateList.valueOf(peak >= 95 ? ui.red : ui.accent));
        float raw = active ? config.prefs.getFloat("peak_raw", peak / 100f) : 0;
        String db = raw > 0 ? String.format(Locale.ROOT, "%.0f dBFS", 20 * Math.log10(raw)) : "−∞ dBFS";
        levelLabel.setText(I18n.s("level_value", peak, db) + (active && config.prefs.getBoolean("limiting", false) ? " · " + I18n.s("limiter") : ""));
        gainLabel.setText(I18n.s("gain", config.gainDb()));
        String route = config.prefs.getString("input_route", "");
        input.setText(I18n.s("actual_input") + ": " + (active && !route.isEmpty() ? AudioInputs.labelKey(route) : "—"));
        cloud.setText(I18n.tr(new CloudSettings(this).prefs.getString("status", "Підключення ще не налаштоване")));
        if (currentTab == 2) settings.refresh();
    }
}
