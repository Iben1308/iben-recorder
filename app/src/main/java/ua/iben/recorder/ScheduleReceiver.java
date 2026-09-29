package ua.iben.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if ("ua.iben.recorder.oreo.SCHEDULE".equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) ScheduleManager.reconcile(context, true);
    }
}
