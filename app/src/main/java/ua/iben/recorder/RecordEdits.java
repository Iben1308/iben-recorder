package ua.iben.recorder;

import android.os.Process;
import java.util.concurrent.*;

/** Ordered metadata writes across screens and Activity recreation; thread retires when idle. */
final class RecordEdits {
    static final ExecutorService worker = new ThreadPoolExecutor(0,1,30L,TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);r.run();
            },"iben-record-metadata"));
    private RecordEdits() { }
}
