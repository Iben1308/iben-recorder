package ua.iben.recorder;

import android.app.Application;

public final class RecorderApp extends Application {
    @Override public void onCreate() { super.onCreate(); I18n.use(new Config(this).language());
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityStarted(android.app.Activity a) { Platform.visible(true); }
            public void onActivityStopped(android.app.Activity a) { Platform.visible(false); }
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
            public void onActivityResumed(android.app.Activity a) { }
            public void onActivityPaused(android.app.Activity a) { }
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
            public void onActivityDestroyed(android.app.Activity a) { }
        }); }
}
