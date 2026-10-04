package ua.iben.recorder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** WebDAV with metadata-confirmed uploads and explicitly requested, metadata-checked deletion.
 * Production connections retain system certificate/hostname checks and reject redirects.
 */
public final class DavClient implements AutoCloseable {
    public interface Progress { void update(String phase, long done, long total); }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    public static final class Conflict extends IOException {
        Conflict() { super("На сервері є інший файл із такою назвою; його не перезаписано"); }
    }
    public static final class Receipt {
        public final long size;
        public final long modified;
        public final String sha256;
        public final String etag;
        public final int kind;
        Receipt(long size, long modified, String sha256, String etag) {
            this(size,modified,sha256,etag,TransferPolicy.CONTENT);
        }
        Receipt(long size,long modified,String sha256,String etag,int kind) {
            this.size=size;this.modified=modified;this.sha256=sha256;this.etag=etag;this.kind=kind;
        }
    }
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "iben-network-deadline"); t.setDaemon(true); return t;
    });
    private final DavTarget target;
    private final String authorization;
    private final Connections connections;
    private final Progress progress;
    private volatile boolean canceled;
    private volatile HttpURLConnection active;
    private ScheduledFuture<?> deadline;

    public DavClient(DavTarget target, String password, Progress progress) {
        this(target,password,progress,"");
    }
    public DavClient(DavTarget target,String password,Progress progress,String certificate) {
        this(target,password,progress,new Connections() {
            private javax.net.ssl.SSLSocketFactory trusted;
            @Override public HttpURLConnection open(URL url) throws IOException {
                HttpURLConnection connection=(HttpURLConnection)(url.getProtocol().equals("http")
                        ? url.openConnection(java.net.Proxy.NO_PROXY) : url.openConnection());
                if(!certificate.isEmpty()) {
                    if(!(connection instanceof javax.net.ssl.HttpsURLConnection))throw new IOException(I18n.s("certificate_https_only"));
                    if(trusted==null)trusted=TlsCertificate.socketFactory(certificate);
                    ((javax.net.ssl.HttpsURLConnection)connection).setSSLSocketFactory(trusted);
                }
                return connection;
            }
        });
    }
    DavClient(DavTarget target, String password, Progress progress, Connections connections) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Потрібен пароль застосунку WebDAV");
        this.target = target; this.progress = progress; this.connections = connections;
        authorization = "Basic " + Base64.getEncoder().encodeToString((target.username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    public void cancel() {
        canceled = true;
        HttpURLConnection c = active;
        if (c != null) c.disconnect();
    }
    private void check() throws InterruptedIOException {
        if (canceled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Передачу призупинено");
    }
    private HttpURLConnection begin(String method, String name) throws IOException {
        check();
        HttpURLConnection c = connections.open(target.file(name));
        active = c;
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", authorization);
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Cache-Control", "no-cache, no-store");
        c.setRequestProperty("User-Agent", "IbenRecorder/0.7.3");
        // HttpsURLConnection lacks a write timeout. Bound the whole request as well.
        deadline = DEADLINES.schedule(c::disconnect, 9, TimeUnit.MINUTES);
        check();
        return c;
    }
    private void end(HttpURLConnection c) {
        if (deadline != null) { deadline.cancel(false); deadline = null; }
        HttpURLConnection closing = c == null ? active : c;
        if (closing != null) closing.disconnect();
        active = null;
    }
    private static IOException response(int code) {
        String why;
        switch (code) {
            case 401: why = "Неправильний логін або пароль застосунку"; break;
            case 403: why = "Немає доступу до папки; перевірте дозволи WebDAV"; break;
            case 404: case 409: why = "Папку не знайдено. Створіть її в WebDAV і перевірте WebDAV-адресу"; break;
            case 413: why = "Сервер відхилив розмір файла; зменште інтервал запису або збільште ліміт запиту на сервері"; break;
            case 423: why = "Файл заблокований сервером"; break;
            case 429: why = "Сервер обмежив частоту запитів"; break;
            case 507: why = "На сервері закінчилося місце або квота"; break;
            default: why = code >= 300 && code < 400
                    ? "Сервер перенаправляє запит. Вкажіть кінцеву WebDAV-адресу"
                    : "Сервер не підтвердив операцію";
        }
        return new IOException(why + " (HTTP " + code + ")");
    }
    /** The caller owns the saved name. No audio body is downloaded or hashed.
     * Same-name/same-size replacements cannot be distinguished by this policy. */
    private Receipt verify(String name, long size, long modified) throws IOException {
        HttpURLConnection c=null;
        try {
            progress.update(I18n.uk("upload_check_metadata"),0,0);
            c=begin("HEAD",name);int code=c.getResponseCode();
            if(code==404)return null;
            if(code==405 || code==501)throw new IOException(I18n.s("upload_head_required"));
            if(code!=200)throw response(code);
            long length=c.getContentLengthLong();
            if(length<0)throw new IOException(I18n.s("upload_metadata_missing"));
            if(length!=size)throw new Conflict();
            String etag=c.getHeaderField("ETag");
            if(!TransferPolicy.strongEtag(etag))throw new IOException(I18n.s("upload_metadata_missing"));
            return new Receipt(size,modified,null,etag,TransferPolicy.METADATA);
        } finally {end(c);}
    }
    public Receipt upload(File file, String remoteName) throws IOException {
        check();
        long size = file.length(); long modified = file.lastModified();
        if (!file.isFile() || size <= 0) throw new IOException("Готовий локальний файл недоступний");
        unchanged(file, size, modified);
        // Covers a lost PUT response or a crash before recording the receipt: no second PUT.
        Receipt receipt = verify(remoteName, size, modified);
        if (receipt == null) {
            HttpURLConnection c = null;
            try {
                c = begin("PUT", remoteName);
                c.setRequestProperty("If-None-Match", "*");
                c.setRequestProperty("Content-Type", remoteName.endsWith(".m4a") ? "audio/mp4" : "text/plain; charset=utf-8");
                c.setDoOutput(true); c.setFixedLengthStreamingMode(size);
                long sent = 0;
                byte[] buffer = new byte[65536];
                try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = c.getOutputStream()) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        check(); out.write(buffer, 0, n); sent += n;
                        progress.update("Завантаження", sent, size);
                    }
                }
                int code = c.getResponseCode();
                // Another attempt may have completed after the initial HEAD: verify instead of overwrite.
                if (code != 201 && code != 204 && code != 412) throw response(code);
            } finally { end(c); }
            receipt = verify(remoteName, size, modified);
            if (receipt == null) throw new IOException("Після передачі файл відсутній на сервері");
        }
        check(); unchanged(file, size, modified);
        return receipt;
    }
    /** The caller authorizes the exact saved path and size; HEAD never downloads audio.
     * A strong current ETag protects against changes between this check and DELETE.
     * Same-name, same-size replacements made before HEAD are intentionally not content-verified.
     */
    public void deleteRecording(String remoteName, long expectedSize) throws IOException {
        deleteRecording(remoteName, expectedSize, () -> true);
    }
    void deleteRecording(String remoteName, long expectedSize, BooleanSupplier allowed) throws IOException {
        if (remoteName == null || !remoteName.endsWith(".m4a") || expectedSize <= 0)
            throw new IOException(I18n.s("cloud_delete_no_receipt"));
        if (!allowed.getAsBoolean()) throw new InterruptedIOException();
        progress.update(I18n.uk("cloud_delete_checking"), 0, 0);
        String etag = deletionEtag(remoteName, expectedSize);
        check();
        if (!allowed.getAsBoolean()) throw new InterruptedIOException();
        if (etag == null) return; // A previous DELETE may have succeeded before its response was lost.
        progress.update(I18n.uk("cloud_delete_removing"), 0, 0);
        check();
        if (!allowed.getAsBoolean()) throw new InterruptedIOException();
        HttpURLConnection c = null;
        try {
            c = begin("DELETE", remoteName);
            c.setRequestProperty("If-Match", etag);
            int code = c.getResponseCode();
            if (code == 412) throw new Conflict();
            if (code != 200 && code != 204 && code != 404) throw response(code);
        } finally { end(c); }
    }
    /** Reads response headers only. No GET fallback: missing metadata cannot authorize deletion. */
    private String deletionEtag(String name, long expectedSize) throws IOException {
        HttpURLConnection c = null;
        try {
            c = begin("HEAD", name);
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code == 405 || code == 501) throw new IOException(I18n.s("cloud_delete_head"));
            if (code != 200) throw response(code);
            long size = c.getContentLengthLong();
            if (size < 0) throw new IOException(I18n.s("cloud_delete_size"));
            if (size != expectedSize) throw new Conflict();
            String etag = c.getHeaderField("ETag");
            if (!strongEtag(etag)) throw new IOException(I18n.s("cloud_delete_etag"));
            return etag;
        } finally { end(c); }
    }
    private static boolean strongEtag(String etag) { return TransferPolicy.strongEtag(etag); }
    private static void unchanged(File file, long size, long modified) throws IOException {
        if (!file.isFile() || file.length() != size || file.lastModified() != modified)
            throw new IOException("Локальний файл змінився; підтвердження не збережено");
    }
    /** Connection test removes only its own tiny, uniquely named probe. */
    public void test(File cacheDirectory) throws IOException {
        File probe = File.createTempFile("iben-webdav-", ".txt", cacheDirectory);
        String remote = ".iben-connection-test-" + UUID.randomUUID() + ".txt";
        try {
            try (OutputStream out = new java.io.FileOutputStream(probe)) {
                out.write(("Iben Recorder WebDAV test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            }
            Receipt receipt = upload(probe, remote);
            if (receipt.etag == null || receipt.etag.startsWith("W/"))
                throw new IOException("Передача працює, але сервер не надав ETag: тестовий файл залишено у папці");
            HttpURLConnection c = null;
            try {
                c = begin("DELETE", remote);
                c.setRequestProperty("If-Match", receipt.etag);
                int code = c.getResponseCode();
                if (code != 200 && code != 204 && code != 404)
                    throw new IOException("Передача працює, але тестовий файл не видалено (HTTP " + code + ")");
            } finally { end(c); }
        } finally { if (!probe.delete()) probe.deleteOnExit(); }
    }
    @Override public void close() { cancel(); }
}
