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
    private Ui ui;
    private LinearLayout root;
    private EditText address, user, password;
    private Switch enabled, unmetered, localHttp;
    private Button save, test, now, pause, certificateImport, certificateClear;
    private TextView status, testStatus, certificateStatus;
    private String trustedCertificate="";
    private boolean settingFields,httpConfirmed;
    private static final int IMPORT_CERTIFICATE=8201;
    private int foreground;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private volatile DavClient testClient;
    private volatile boolean gone;
    private boolean working;

    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(LocaleContext.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        Config config = new Config(this);
        ui = new Ui(this, config);
        boolean dark = config.theme() == 2 || (config.theme() == 0
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(state);
        foreground = ui.ink;
        cloud = new CloudSettings(this);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.background);
        root.setPadding(dp(22), dp(22), dp(22), dp(30)); scroll.addView(root); setContentView(scroll); Platform.insets(this, scroll, dark);
        ui.helpTitle(root, I18n.s("webdav_title"), I18n.s("webdav_intro") + "\n\n" + I18n.s("webdav_nextcloud_recommend"));
        status = text("", 15);
        address = input(I18n.s("webdav_address"), state==null ? cloud.folder() : state.getString("draft_folder",cloud.folder()), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://server.example.com/recordings/");
        user = input(I18n.s("webdav_user"), state==null ? cloud.username() : state.getString("draft_user",cloud.username()), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = input(I18n.s("webdav_password"), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        password.setHint(cloud.hasSecret() ? I18n.tr("Збережено — порожнє поле залишає пароль") : I18n.s("webdav_password_hint"));
        localHttp=toggle(I18n.s("http_allow"),state==null ? cloud.localHttp() : state.getBoolean("draft_http",false));
        explain(localHttp, I18n.s("http_allow"), I18n.s("http_warning"));
        httpConfirmed=localHttp.isChecked();
        localHttp.setOnCheckedChangeListener((button,checked) -> {
            if(!checked)httpConfirmed=false;
            if(checked && !settingFields) { httpConfirmed=false;new android.app.AlertDialog.Builder(this).setTitle(I18n.s("http_allow"))
                    .setMessage(I18n.s("http_warning"))
                    .setNegativeButton(I18n.s("cancel"),(d,w) -> localHttp.setChecked(false))
                    .setOnCancelListener(d -> localHttp.setChecked(false))
                    .setPositiveButton(I18n.s("http_confirm"),(d,w) -> httpConfirmed=true).show(); }
        });
        trustedCertificate=state==null ? cloud.certificate() : state.getString("draft_certificate","");
        certificateStatus=text("",12);certificateStatus.setTextIsSelectable(true);
        certificateImport=button(I18n.s("certificate_import"),this::importCertificate);
        certificateClear=button(I18n.s("certificate_clear"),() -> {trustedCertificate="";certificateSummary();});
        certificateSummary();
        android.text.TextWatcher changed=new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                if(!settingFields) {localHttp.setChecked(false);trustedCertificate="";certificateSummary();}
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        };
        address.addTextChangedListener(changed);user.addTextChangedListener(changed);
        enabled = toggle("Автоматично передавати готові записи", state==null ? cloud.enabled() : state.getBoolean("draft_enabled",cloud.enabled()));
        explain(enabled, I18n.s("cloud_upload_help"), I18n.s("cloud_upload_hint"));
        unmetered = toggle("Лише мережа без тарифікації (зазвичай Wi-Fi)", state==null ? cloud.unmetered() : state.getBoolean("draft_wifi",cloud.unmetered()));
        save = button("Зберегти підключення", this::save);
        test = button("Перевірити підключення", this::test);
        explain(test, I18n.s("cloud_test_help"), I18n.s("cloud_test_hint"));
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
        boolean http=localHttp.isChecked() && httpConfirmed;String certificate=trustedCertificate;
        busy(true); testStatus.setText(I18n.tr("Збереження…"));
        new Thread(() -> {
            String result;
            boolean success = false;
            try {
                cloud.save(folder, login, secret, active, wifi,http,certificate);
                SyncScheduler.restart(getApplicationContext());
                result = "Підключення збережено"; success = true;
            } catch (Exception e) { result = CloudSettings.error(e); }
            final String message = result; final boolean saved = success;
            runOnUiThread(() -> {
                if (gone) return;
                busy(false); testStatus.setText(I18n.tr(message));
                if (saved) {
                    settingFields=true;
                    address.setText(cloud.folder()); user.setText(cloud.username()); password.setText("");
                    localHttp.setChecked(cloud.localHttp());httpConfirmed=cloud.localHttp();trustedCertificate=cloud.certificate();certificateSummary();
                    settingFields=false;
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
        boolean http=localHttp.isChecked() && httpConfirmed;String certificate=trustedCertificate;
        busy(true); testStatus.setText(I18n.tr("Перевірка читання й запису…"));
        new Thread(() -> {
            String result;
            try {
                DavTarget target = new DavTarget(folder, login,http);
                String secret = typedPassword;
                if (secret.isEmpty()) {
                    CloudSettings.Connection stored = cloud.connection();
                    if (!stored.target.key.equals(target.key)) throw new IllegalArgumentException("Введіть пароль застосунку для нової адреси або користувача");
                    secret = stored.password;
                }
                DavClient client = new DavClient(target, secret, (phase, done, total) -> { },certificate);
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
    private void certificateSummary() {
        try { certificateStatus.setText(trustedCertificate.isEmpty() ? I18n.s("certificate_system")
                : I18n.s("certificate_details",TlsCertificate.decode(trustedCertificate).getSubjectX500Principal().getName(),
                    TlsCertificate.fingerprint(trustedCertificate),java.text.DateFormat.getDateInstance().format(TlsCertificate.decode(trustedCertificate).getNotAfter()))); }
        catch(Exception e) { trustedCertificate="";certificateStatus.setText(I18n.s("certificate_invalid")); }
    }
    private void importCertificate() {
        if(working)return;
        if(!address.getText().toString().trim().startsWith("https://")) {testStatus.setText(I18n.s("certificate_https_only"));return;}
        try {startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT_CERTIFICATE);}
        catch(RuntimeException e) {testStatus.setText(I18n.s("document_error"));}
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("draft_folder",address.getText().toString());state.putString("draft_user",user.getText().toString());
        state.putBoolean("draft_http",localHttp.isChecked() && httpConfirmed);state.putString("draft_certificate",trustedCertificate);
        state.putBoolean("draft_enabled",enabled.isChecked());state.putBoolean("draft_wifi",unmetered.isChecked());
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_CERTIFICATE || result!=RESULT_OK || data==null || data.getData()==null)return;
        android.net.Uri source=data.getData();busy(true);
        new Thread(() -> {
            String certificate=null,error=null;
            try(java.io.InputStream input=getContentResolver().openInputStream(source);
                    java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
                BoundedCopy.copy(input,output,65536);certificate=TlsCertificate.importPublic(output.toByteArray());
            } catch(Exception e) {error=I18n.s("certificate_invalid");}
            String imported=certificate,problem=error;
            runOnUiThread(() -> {
                if(gone)return;busy(false);
                if(problem!=null) {testStatus.setText(problem);return;}
                try {
                    new android.app.AlertDialog.Builder(this).setTitle(I18n.s("certificate_import"))
                            .setMessage(I18n.s("certificate_confirm",TlsCertificate.fingerprint(imported)))
                            .setNegativeButton(I18n.s("cancel"),null)
                            .setPositiveButton(I18n.s("certificate_trust"),(d,w) -> {trustedCertificate=imported;certificateSummary();}).show();
                } catch(Exception e) {testStatus.setText(I18n.s("certificate_invalid"));}
            });
        },"iben-certificate").start();
    }
    private void busy(boolean value) {
        working = value;
        for (View view : new View[]{address, user, password, enabled, unmetered, localHttp,certificateImport,certificateClear,save, test, now, pause}) view.setEnabled(!value);
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
        if (label.equals(I18n.s("webdav_address"))) ui.helpLabel(root, label, I18n.s("webdav_folder_hint"));
        else text(label, 14);
        EditText view = new EditText(this); view.setSingleLine(true); view.setInputType(type);
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
    private void explain(View control, String title, String description) {
        root.removeView(control); ui.help(root, control, title, description);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
