package ua.iben.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(30));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("J7 Recorder", 28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text("Аудіореєстратор · прототип 0.1", 14);
        space(18);
        status = text("Запис вимкнено", 21);
        status.setTextColor(Color.rgb(19, 111, 99));
        details = text("", 14);
        space(12);
        start = button("Почати / відновити запис", this::startPressed);
        stop = button("Зупинити й зберегти фрагмент", () -> {
            config.wanted(false);
            startForegroundService(new Intent(this, RecorderService.class).setAction(RecorderService.STOP));
            refresh();
        });

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
        text("Між фрагментами можлива коротка пауза. Перед автономною роботою перевірте запис і синхронізацію протягом 72 годин.", 13);
        if (Build.VERSION.SDK_INT != 29)
            text("Цей прототип працює лише на Android 10. На цьому пристрої запис вимкнено.", 15).setTextColor(Color.RED);
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
        if (Build.VERSION.SDK_INT != 29) { toast("Потрібен Android 10"); return; }
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
        boolean stale = wanted && System.currentTimeMillis() - heartbeat > 90000L;
        status.setText(stale ? "Немає свіжого стану — перевірте запис" : config.prefs.getString("status", "Запис вимкнено"));
        long began = config.prefs.getLong("segment_start", 0);
        long seconds = began == 0 || stale || !wanted ? 0 : Math.max(0, (System.currentTimeMillis() - began) / 1000L);
        long used = config.prefs.getLong("used_bytes", 0);
        long free = config.prefs.getLong("free_bytes", 0);
        details.setText(String.format(Locale.ROOT,
                "Поточний фрагмент: %02d:%02d:%02d\nЗаписи: %.0f / %d МіБ · вільно: %.0f МіБ",
                seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L,
                used / (double) StoragePolicy.MIB, config.quotaMiB(), free / (double) StoragePolicy.MIB));
        for (View view : new View[]{minutes, quota, bitrate, sampling, cleanup, boot, save}) view.setEnabled(!wanted);
        start.setEnabled(Build.VERSION.SDK_INT == 29);
        stop.setEnabled(wanted);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(33, 47, 45));
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
