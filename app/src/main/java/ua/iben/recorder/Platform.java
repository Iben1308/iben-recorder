package ua.iben.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import java.util.ArrayList;
import java.util.List;

final class Platform {
    private static volatile int visibleActivities;
    static void visible(boolean value) { visibleActivities = Math.max(0, visibleActivities + (value ? 1 : -1)); }
    static boolean visible() { return visibleActivities > 0; }
    static boolean publicStorage() { return PlatformPolicy.publicStorage(Build.VERSION.SDK_INT); }
    static boolean armedService() { return PlatformPolicy.needsArmedService(Build.VERSION.SDK_INT); }
    static boolean granted(Context c, String permission) { return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    static boolean storageGranted(Context c) {
        return !publicStorage() || (granted(c, Manifest.permission.READ_EXTERNAL_STORAGE) && granted(c, Manifest.permission.WRITE_EXTERNAL_STORAGE));
    }
    static boolean recordingGranted(Context c) { return granted(c, Manifest.permission.RECORD_AUDIO) && storageGranted(c); }
    static String[] permissions(boolean microphone) {
        List<String> values = new ArrayList<>();
        if (microphone) values.add(Manifest.permission.RECORD_AUDIO);
        if (publicStorage()) { values.add(Manifest.permission.READ_EXTERNAL_STORAGE); values.add(Manifest.permission.WRITE_EXTERNAL_STORAGE); }
        return values.toArray(new String[0]);
    }
    static boolean exactAlarms(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    static boolean notifications(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if ((Build.VERSION.SDK_INT >= 33 && !granted(c, Manifest.permission.POST_NOTIFICATIONS)) || !manager.areNotificationsEnabled()) return false;
        for (String id : new String[]{"recording", "resume"}) {
            android.app.NotificationChannel channel = manager.getNotificationChannel(id);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }
    static void requestAlarms(Activity a) {
        if (Build.VERSION.SDK_INT >= 31) a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + a.getPackageName())));
    }
    static void notificationSettings(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
    }
    /** Consume system/IME insets on API 35+, where target 35 enforces edge-to-edge. */
    static void insets(Activity activity, View root, boolean dark) {
        if (Build.VERSION.SDK_INT >= 35) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                android.graphics.Insets inset = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(l + inset.left, t + inset.top, r + inset.right, b + inset.bottom);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
