package ua.iben.recorder;

import android.content.Context;
import android.os.Process;
import java.io.InterruptedIOException;

/** An explicit foreground UI operation. No periodic worker or automatic DELETE retry. */
final class CloudDeletionTask {
    interface Listener {
        void progress(String phase);
        void finished(boolean remoteDone, LocalDeletion.Result local, String error);
    }
    private final Context app;
    private final RecordingFiles.Item item;
    private final boolean removeLocal,allowImportant;
    private final Listener listener;
    private volatile boolean canceled;
    private volatile DavClient active;

    CloudDeletionTask(Context context, RecordingFiles.Item item, boolean removeLocal,boolean allowImportant, Listener listener) {
        app = context.getApplicationContext(); this.item = item;
        this.removeLocal = removeLocal;this.allowImportant=allowImportant; this.listener = listener;
    }
    void start() {
        new Thread(this::run, "iben-cloud-delete").start();
    }
    void cancel() {
        canceled = true;
        DavClient client = active;
        if (client != null) client.cancel();
    }
    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        boolean remoteDone = false;
        LocalDeletion.Result local = null;
        String error = null;
        CloudSettings cloud = new CloudSettings(app);
        try {
            if (canceled) throw new InterruptedIOException();
            CloudSettings.Connection connection = cloud.connection();
            try (RecordingFiles files = new RecordingFiles(app, new Config(app));
                    RecordingFiles.CloudDelete deletion = files.prepareCloudDelete(item, connection.target.key,allowImportant);
                    DavClient client = connection.client( (phase, done, total) -> {
                        if (cloud.revision() != connection.revision) cancel();
                        listener.progress(I18n.tr(phase));
                    })) {
                active = client;
                if (canceled) client.cancel();
                client.deleteRecording(deletion.remoteName, deletion.size,
                        () -> !canceled && cloud.revision() == connection.revision && files.cloudDeletionAllowed(deletion));
                remoteDone = true;
                // Record a confirmed response even if UI cancellation arrived just after it.
                // A canceled 'both' operation still retains the phone copy.
                local = files.cloudDeleted(deletion, item, removeLocal && !canceled);
                AppLog.write(app, I18n.s("cloud_delete_log", deletion.remoteName));
                if (removeLocal && canceled) error = I18n.s("cloud_delete_canceled");
            }
        } catch (Exception e) {
            if (canceled || e instanceof InterruptedIOException) error = I18n.s("cloud_delete_canceled");
            else if (e instanceof DavClient.Conflict) error = I18n.s("cloud_delete_conflict");
            else if (e instanceof java.io.IOException && e.getMessage() != null) error = I18n.tr(e.getMessage());
            else error = I18n.s("cloud_delete_failed");
            AppLog.write(app, "Cloud deletion: " + e.getClass().getSimpleName());
        } finally { active = null; }
        listener.finished(remoteDone, local, error);
    }
}
