package ua.iben.recorder;

import android.app.Application;

public final class RecorderApp extends Application {
    @Override public void onCreate() { super.onCreate(); I18n.use(new Config(this).language()); }
}
