package ua.iben.recorder;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class CloudActivity extends Activity {
    private CloudSettings cloud;
    private LinearLayout root;
    private EditText address, user, password;
    private Switch enabled, unmetered;
    private Button save, test, now, pause;
    private TextView status, testStatus;
    private int foreground;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private volatile DavClient testClient;
    private volatile boolean gone;
    private boolean working;

    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(LocaleContext.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        Config config = new Config(this);
        boolean dark = config.theme() == 2 || (config.theme() == 0
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(state);
        foreground = Color.parseColor(dark ? "#E5ECE9" : "#212F2D");
        cloud = new CloudSettings(this);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(30)); scroll.addView(root); setContentView(scroll);
        text("Nextcloud · WebDAV", 26);
        text("Пряме завантаження готових записів. Застосунок Nextcloud на телефоні не потрібний.", 14);
        status = text("", 15);
        address = input("Повна HTTPS WebDAV-адреса папки", cloud.folder(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://cloud.example.com/remote.php/dav/files/LOGIN/IbenRecorder81/");
        user = input("Ім’я користувача Nextcloud", cloud.username(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = input("Пароль застосунку Nextcloud", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        password.setHint(I18n.tr(cloud.hasSecret() ? "Збережено — порожнє поле залишає пароль" : "Створи в Nextcloud → Особисті налаштування → Безпека"));
        text("Заздалегідь створи папку IbenRecorder81 у вебінтерфейсі Nextcloud. Скопіюй свою WebDAV-адресу з налаштувань файлів і додай до неї назву папки. Публічне посилання «Поділитися» не підходить.", 13);
        enabled = toggle("Автоматично передавати готові записи", cloud.enabled());
        unmetered = toggle("Лише мережа без тарифікації (зазвичай Wi-Fi)", cloud.unmetered());
        text("Після передачі файл читається назад і звіряється SHA-256. Це додає вхідний трафік приблизно в розмір запису. Непередані файли захищені від очищення навіть після вимкнення передачі.", 13);
        save = button("Зберегти підключення", this::save);
        test = button("Перевірити підключення", this::test);
        text("Перевірка створює, читає й видаляє маленький тестовий файл у вибраній папці.", 13);
        testStatus = text("", 14);
        now = button("Синхронізувати зараз", () -> {
            if (!cloud.enabled()) { testStatus.setText(I18n.tr("Увімкни автопередачу й збережи підключення")); return; }
            cloud.status("Очікування дозволеної мережі та запуску Android");
            SyncScheduler.restart(this); refresh();
        });
        pause = button("Призупинити передачу", () -> {
            cloud.disable(); SyncScheduler.cancel(this); enabled.setChecked(false); refresh();
        });
        button("Повернутися до запису", this::finish);
        refresh();
    }
    private void save() {
        if (working) return;
        String folder = address.getText().toString(); String login = user.getText().toString();
        String secret = password.getText().toString(); boolean active = enabled.isChecked(); boolean wifi = unmetered.isChecked();
        busy(true); testStatus.setText(I18n.tr("Збереження…"));
        new Thread(() -> {
            String result;
            boolean success = false;
            try {
                cloud.save(folder, login, secret, active, wifi);
                SyncScheduler.restart(getApplicationContext());
                result = "Підключення збережено"; success = true;
            } catch (Exception e) { result = CloudSettings.error(e); }
            final String message = result; final boolean saved = success;
            runOnUiThread(() -> {
                if (gone) return;
                busy(false); testStatus.setText(I18n.tr(message));
                if (saved) {
                    address.setText(cloud.folder()); user.setText(cloud.username()); password.setText("");
                    password.setHint(I18n.tr("Збережено — порожнє поле залишає пароль"));
                }
                refresh();
            });
        }, "iben-cloud-settings").start();
    }
    private void test() {
        if (working) return;
        String folder = address.getText().toString(); String login = user.getText().toString();
        String typedPassword = password.getText().toString();
        busy(true); testStatus.setText(I18n.tr("Перевірка читання й запису…"));
        new Thread(() -> {
            String result;
            try {
                DavTarget target = new DavTarget(folder, login);
                String secret = typedPassword;
                if (secret.isEmpty()) {
                    CloudSettings.Connection stored = cloud.connection();
                    if (!stored.target.key.equals(target.key)) throw new IllegalArgumentException("Введіть пароль застосунку для нової адреси або користувача");
                    secret = stored.password;
                }
                DavClient client = new DavClient(target, secret, (phase, done, total) -> { });
                testClient = client;
                try {
                    if (gone) return;
                    client.test(getCacheDir());
                } finally { client.close(); testClient = null; }
                result = "Підключення працює: запис, читання, SHA-256 і видалення тестового файла перевірено. Збережи налаштування, якщо змінював їх.";
            } catch (Exception e) { result = CloudSettings.error(e); }
            final String message = result;
            runOnUiThread(() -> { if (!gone) { busy(false); testStatus.setText(I18n.tr(message)); } });
        }, "iben-webdav-test").start();
    }
    private void busy(boolean value) {
        working = value;
        for (View view : new View[]{address, user, password, enabled, unmetered, save, test, now, pause}) view.setEnabled(!value);
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() { refresh(); timer.postDelayed(this, 1000L); }
    };
    private void refresh() {
        String text = cloud.prefs.getString("status", "Підключення ще не налаштоване");
        int pending = cloud.prefs.getInt("pending", 0);
        String last = cloud.prefs.getString("last_file", "");
        status.setText(I18n.tr(text + "\nЗа останньою перевіркою в черзі: " + pending
                + (last.isEmpty() ? "" : "\nОстанній підтверджений: " + last)));
    }
    @Override public void onResume() { super.onResume(); timer.post(poll); }
    @Override public void onPause() { timer.removeCallbacks(poll); super.onPause(); }
    @Override public void onDestroy() {
        gone = true;
        DavClient client = testClient; if (client != null) client.cancel();
        super.onDestroy();
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(I18n.tr(value)); view.setTextSize(size); view.setTextColor(foreground);
        view.setPadding(0, dp(6), 0, dp(8)); root.addView(view); return view;
    }
    private EditText input(String label, String value, int type) {
        text(label, 14); EditText view = new EditText(this); view.setSingleLine(true); view.setInputType(type);
        view.setText(value); root.addView(view); return view;
    }
    private Switch toggle(String label, boolean value) {
        Switch view = new Switch(this); view.setText(I18n.tr(label)); view.setChecked(value); view.setTextSize(14);
        view.setPadding(0, dp(12), 0, dp(12)); root.addView(view); return view;
    }
    private Button button(String label, Runnable action) {
        Button view = new Button(this); view.setAllCaps(false); view.setText(I18n.tr(label)); view.setOnClickListener(v -> action.run());
        root.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
