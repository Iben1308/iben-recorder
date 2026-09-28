package ua.iben.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;

final class CloudSettings {
    final SharedPreferences prefs;
    CloudSettings(Context context) { prefs = context.getSharedPreferences("webdav", Context.MODE_PRIVATE); }
    String folder() { return prefs.getString("folder", ""); }
    String username() { return prefs.getString("username", ""); }
    String targetKey() { return prefs.getString("target", ""); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    boolean unmetered() { return prefs.getBoolean("unmetered", true); }
    boolean hasSecret() { return !prefs.getString("password_cipher", "").isEmpty(); }
    long revision() { return prefs.getLong("revision", 0); }
    DavTarget target() { return new DavTarget(folder(), username()); }
    static final class Connection {
        final DavTarget target;
        final String password;
        final long revision;
        Connection(DavTarget target, String password, long revision) {
            this.target = target; this.password = password; this.revision = revision;
        }
    }
    Connection connection() throws IOException {
        synchronized (RecordingFiles.LOCK) { return new Connection(target(), password(), revision()); }
    }
    String password() throws IOException {
        try { return SecretStore.decrypt(prefs.getString("password_cipher", "")); }
        catch (Exception e) { throw new IOException("Не вдалося прочитати збережений пароль. Введіть пароль застосунку повторно"); }
    }
    void save(String folder, String username, String password, boolean enabled, boolean unmetered) throws Exception {
        DavTarget target = new DavTarget(folder, username);
        String encrypted = password.isEmpty() ? null : SecretStore.encrypt(password);
        synchronized (RecordingFiles.LOCK) {
            String cipher = encrypted;
            // Read the old target and its secret atomically, including during overlapping UI saves.
            if (cipher == null) {
                if (!target.key.equals(targetKey()) || !hasSecret()) throw new IllegalArgumentException("Введіть пароль застосунку для цього підключення");
                password();
                cipher = prefs.getString("password_cipher", "");
            }
            if (!prefs.edit().putString("folder", target.folder).putString("username", target.username)
                    .putString("target", target.key).putString("password_cipher", cipher)
                    .putBoolean("enabled", enabled).putBoolean("unmetered", unmetered)
                    .putLong("revision", revision() + 1).putInt("failures", 0).putLong("retry_at", 0)
                    .putString("status", enabled ? "Очікування передачі" : "Передачу вимкнено; непередані файли захищені").commit())
                throw new IOException("Не вдалося зберегти підключення");
        }
    }
    void disable() {
        synchronized (RecordingFiles.LOCK) {
            prefs.edit().putBoolean("enabled", false).putLong("revision", revision() + 1)
                    .putString("status", "Передачу вимкнено; непередані файли захищені").commit();
        }
    }
    void status(String text) { prefs.edit().putString("status", text).putLong("status_at", System.currentTimeMillis()).apply(); }
    static String error(Throwable e) {
        if (e instanceof javax.net.ssl.SSLException) return "Помилка HTTPS: перевірте сертифікат сервера й дату телефона";
        if (e instanceof java.net.SocketTimeoutException) return "Сервер не відповів вчасно; передачу буде повторено";
        if (e instanceof java.net.UnknownHostException) return "Не вдалося знайти сервер; перевірте мережу й адресу";
        if (e.getClass() == IOException.class || e instanceof DavClient.Conflict || e instanceof IllegalArgumentException)
            return e.getMessage() == null ? "Помилка передачі" : e.getMessage();
        return "Передача не завершена (" + e.getClass().getSimpleName() + ")";
    }
}
