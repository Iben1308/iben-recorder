package ua.iben.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 100;
    private static final String[] PERMISSIONS = {Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private final Handler timer = new Handler();
    private Config config;
    private TextView status;
    private TextView details;
    private EditText minutes;
    private EditText quota;
    private Spinner bitrate;
    private Spinner sampling;
    private Switch cleanup;
    private Switch boot;
    private Button save;
    private Button start;
    private Button stop;
    private LinearLayout root;
    private int foreground;
    private boolean dark;
    private TextView gainLabel;
    private TextView levelLabel;
    private ProgressBar level;

    @Override public void onCreate(Bundle state) {
        config = new Config(this);
        dark = config.theme() == 2 || (config.theme() == 0
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(state);
        foreground = Color.parseColor(dark ? "#E5ECE9" : "#212F2D");
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(30));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("Iben Recorder 8.1", 28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text("Прототип для Android 8.1 · 0.2-oreo.1", 14);
        space(18);
        status = text("Запис вимкнено", 21);
        status.setTextColor(Color.parseColor(dark ? "#76D8C7" : "#136F63"));
        details = text("", 14);
        space(12);
        start = button("Почати / відновити запис", this::startPressed);
        stop = button("Зупинити й зберегти фрагмент", () -> {
            config.wanted(false);
            config.status("Завершення й збереження запису…", 0);
            startForegroundService(new Intent(this, RecorderService.class).setAction(RecorderService.STOP));
            refresh();
        });

        space(16);
        gainLabel = text("Підсилення: +" + config.gainDb() + " дБ", 19);
        SeekBar gain = new SeekBar(this);
        gain.setMax(24); gain.setProgress(config.gainDb());
        gain.setContentDescription("Підсилення запису від 0 до 24 децибелів");
        root.addView(gain);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                gainLabel.setText("Підсилення: +" + value + " дБ");
                if (fromUser) config.gainDb(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        text("Можна змінювати під час запису. Почніть із +6 дБ. Підсилюється також фоновий шум; обмежувач пом’якшує надто гучні піки.", 13);
        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100); level.setContentDescription("Рівень звуку після підсилення"); root.addView(level);
        levelLabel = text("Рівень звуку: —", 13);

        space(20);
        text("Налаштування запису", 19).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        minutes = number("Тривалість файла, хвилини (1–180)", config.minutes());
        quota = number("Ліміт локальних записів, МіБ (128–32768)", config.quotaMiB());
        text("Якість AAC, кбіт/с · моно", 14);
        bitrate = spinner(new String[]{"64", "96", "128", "192", "256"}, String.valueOf(config.bitrate()));
        text("Частота дискретизації, Гц", 14);
        sampling = spinner(new String[]{"44100", "48000"}, String.valueOf(config.sampleRate()));
        cleanup = toggle("Видаляти найстаріші власні записи при ліміті", config.deleteOldest());
        text("Застосунок не знає, чи Nextcloud уже завантажив файл. Автовидалення може стерти ще не завантажений запис.", 13);
        boot = toggle("Відновлювати активний запис після перезавантаження", config.resumeAtBoot());
        text("На зашифрованому телефоні може знадобитися перше розблокування після запуску системи.", 13);
        save = button("Зберегти налаштування", () -> { if (saveSettings()) toast("Налаштування збережено"); });

        space(20);
        text("Папка для Nextcloud", 19).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text(RecordingFiles.publicDirectory().getAbsolutePath(), 14).setTextIsSelectable(true);
        text("Додайте цю папку в автозавантаження Nextcloud. Тут з’являються лише завершені файли .m4a.", 13);
        button("Скопіювати шлях", () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Папка записів",
                    RecordingFiles.publicDirectory().getAbsolutePath()));
            toast("Шлях скопійовано");
        });
        button("Налаштування економії батареї", () -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException e) { toast("Відкрийте системні налаштування батареї вручну"); }
        });
        button("Журнал роботи", () -> {
            TextView log = new TextView(this);
            log.setPadding(dp(16), dp(12), dp(16), dp(12));
            log.setText(AppLog.read(this));
            log.setTextIsSelectable(true);
            log.setTextSize(12);
            ScrollView box = new ScrollView(this);
            box.addView(log);
            new AlertDialog.Builder(this).setTitle("Журнал").setView(box).setPositiveButton("Закрити", null).show();
        });
        space(12);
        text("Тема", 19).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        String[] themeOptions = {"Як у системі", "Світла", "Темна"};
        Spinner theme = spinner(themeOptions, themeOptions[Math.max(0, Math.min(2, config.theme()))]);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != config.theme()) { config.theme(position); recreate(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        text("Тему можна змінювати під час запису.", 13);
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
            text("Цей прототип працює лише на Android 8.1. На цьому пристрої запис вимкнено.", 15).setTextColor(Color.RED);
    }

    private boolean saveSettings() {
        try {
            config.save(Integer.parseInt(minutes.getText().toString().trim()),
                    Integer.parseInt(bitrate.getSelectedItem().toString()),
                    Integer.parseInt(sampling.getSelectedItem().toString()),
                    Integer.parseInt(quota.getText().toString().trim()), cleanup.isChecked(), boot.isChecked());
            return true;
        } catch (Exception e) { toast(e.getMessage() == null ? "Перевірте числові значення" : e.getMessage()); return false; }
    }

    private void startPressed() {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1) { toast("Потрібен Android 8.1"); return; }
        if (!config.wanted() && !saveSettings()) return;
        for (String permission : PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(PERMISSIONS, PERMISSION_REQUEST);
                return;
            }
        }
        startForegroundService(new Intent(this, RecorderService.class).setAction(RecorderService.START));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != PERMISSION_REQUEST) return;
        for (String permission : PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                toast("Для запису потрібні дозволи на мікрофон і файли");
                return;
            }
        }
        startPressed();
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() { refresh(); timer.postDelayed(this, 1000L); }
    };

    @Override public void onResume() { super.onResume(); timer.post(refreshLoop); }
    @Override public void onPause() { timer.removeCallbacksAndMessages(null); super.onPause(); }

    private void refresh() {
        boolean wanted = config.wanted();
        long heartbeat = config.prefs.getLong("heartbeat", 0);
        boolean active = config.prefs.getBoolean("engine_active", false);
        boolean stale = (wanted || active) && System.currentTimeMillis() - heartbeat > 90000L;
        status.setText(stale ? "Немає свіжого стану — перевірте запис" : config.prefs.getString("status", "Запис вимкнено"));
        long seconds = !active || stale ? 0 : config.prefs.getLong("segment_ms", 0) / 1000L;
        long used = config.prefs.getLong("used_bytes", 0);
        long free = config.prefs.getLong("free_bytes", 0);
        details.setText(String.format(Locale.ROOT,
                "Поточний фрагмент: %02d:%02d:%02d\nЗаписи: %.0f / %d МіБ · вільно: %.0f МіБ\nЗавершується файлів: %d",
                seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L,
                used / (double) StoragePolicy.MIB, config.quotaMiB(), free / (double) StoragePolicy.MIB,
                active ? config.prefs.getInt("finishing", 0) : 0));
        int peak = active && !stale ? config.prefs.getInt("peak", 0) : 0;
        level.setProgress(peak);
        levelLabel.setText(active && !stale ? "Рівень звуку: " + peak + "%"
                + (config.prefs.getBoolean("limiting", false) ? " · обмеження піків" : "") : "Рівень звуку: —");
        for (View view : new View[]{minutes, quota, bitrate, sampling, cleanup, boot, save}) view.setEnabled(!wanted && (!active || stale));
        start.setEnabled(Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1 && (!active || stale));
        stop.setEnabled(wanted);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(foreground);
        view.setPadding(0, dp(5), 0, dp(7)); root.addView(view);
        return view;
    }
    private EditText number(String label, int value) {
        text(label, 14);
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(value)); root.addView(input);
        return input;
    }
    private Spinner spinner(String[] options, String selected) {
        Spinner view = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter);
        for (int i = 0; i < options.length; i++) if (options[i].equals(selected)) view.setSelection(i);
        root.addView(view); return view;
    }
    private Switch toggle(String label, boolean checked) {
        Switch view = new Switch(this);
        view.setText(label); view.setTextSize(14); view.setChecked(checked);
        view.setPadding(0, dp(12), 0, dp(12)); root.addView(view); return view;
    }
    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false); view.setOnClickListener(v -> action.run());
        root.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void space(int size) { View view = new View(this); root.addView(view, new LinearLayout.LayoutParams(1, dp(size))); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
