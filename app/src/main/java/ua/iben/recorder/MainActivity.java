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
    private final Button[] tabs = new Button[3];
    private ListenPanel listen;
    private SettingsPanel settings;
    private TextView status, duration, details, gainLabel, levelLabel, cloud, input;
    private Button record;
    private ProgressBar level;
    private int currentTab = 1;
    private Runnable afterPermission;
    private String[] requestedPermissions;
    private boolean resumed;
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
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(6)); root.addView(bar);
        String[] labels = {I18n.s("listen"), I18n.s("record"), I18n.s("settings")};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tabs[i] = ui.button(null, labels[i], () -> tab(index), false); tabs[i].setTextSize(13);
            bar.addView(tabs[i], new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        }
        setContentView(root);
        int selected = state != null ? state.getInt("tab", 1) : getIntent().getIntExtra("tab", config.prefs.getInt("last_tab", 1));
        tab(Math.max(0, Math.min(2, selected))); refresh();
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
                if (Build.VERSION.SDK_INT != 27) { ui.toast(I18n.s("android81")); return; }
                listen.pause(); ScheduleManager.manualStart(this); refresh();
            });
        }, true);
        details = ui.text(main, "", 13, ui.muted);
        LinearLayout sound = ui.card(page);
        ui.title(sound, I18n.s("sound"));
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
        ui.text(sound, I18n.s("gain_hint"), 12, ui.muted);
        input = ui.text(sound, "", 12, ui.muted);
        LinearLayout storage = ui.card(page);
        ui.title(storage, "Nextcloud"); cloud = ui.text(storage, "", 13, ui.muted);
        ui.text(page, I18n.s("record_manual_hint"), 12, ui.muted);
        if (Build.VERSION.SDK_INT != 27) ui.text(page, I18n.s("android81"), 15, ui.red);
        return scroll;
    }
    private void tab(int index) {
        listen.visible(index == 0 && resumed);
        currentTab = index;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(i == index ? ui.accent : ui.pale));
            tabs[i].setTextColor(i == index ? ui.background : ui.accent);
            tabs[i].setSelected(i == index);
        }
        config.prefs.edit().putInt("last_tab", index).apply();
        if (index == 0 && resumed) listen.load();
        if (index == 2) settings.refresh();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (intent.hasExtra("tab")) tab(intent.getIntExtra("tab", 1)); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("tab", currentTab); settings.saveDraft(state);
    }
    void recordingPermission(Runnable action) { permission(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, action); }
    void storagePermission(Runnable action) { permission(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, action); }
    private void permission(String[] permissions, Runnable action) {
        for (String p : permissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            if (afterPermission != null) return;
            afterPermission = action; requestedPermissions = permissions; requestPermissions(permissions, 100); return;
        }
        action.run();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 100 || afterPermission == null) return;
        Runnable action = afterPermission; afterPermission = null;
        for (String p : requestedPermissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            ui.toast(I18n.s("permission_required")); return;
        }
        action.run();
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
        status.setText(stale ? I18n.s("stale") : I18n.tr(config.prefs.getString("status", "Запис вимкнено")));
        duration.setText(Ui.clock(active ? config.prefs.getLong("segment_ms", 0) : 0));
        record.setText(I18n.s(wanted ? "stop_save" : engine && !stale ? "saving" : "start_recording"));
        record.setEnabled(Build.VERSION.SDK_INT == 27 && (wanted || !active));
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
