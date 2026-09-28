package ua.iben.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** BOOT_COMPLETED arrives after first unlock on credential-encrypted devices. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Config config = new Config(context);
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) SyncScheduler.kick(context);
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1 && config.wanted() && config.resumeAtBoot()) {
            try {
                context.startForegroundService(new Intent(context, RecorderService.class));
                AppLog.write(context, "Запит відновлення після запуску системи / оновлення");
            } catch (RuntimeException e) {
                config.status("Не вдалося відновити запис: " + e.getMessage(), 0);
                AppLog.write(context, "Автозапуск: " + e.getMessage());
            }
        }
    }
}
