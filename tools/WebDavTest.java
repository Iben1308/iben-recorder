package ua.iben.recorder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Real local HTTP exchange exercises the production streaming client.
 * Test-only connection injection routes a logical HTTPS URL to loopback;
 * production connections use platform HTTPS and its certificate verification.
 */
public final class WebDavTest {
    private static int checks;
    private static final String ROOT = "/remote.php/dav/files/ivan/IbenRecorder81/";
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("ivan:test-app-password".getBytes(StandardCharsets.UTF_8));
    private static final DavClient.Progress QUIET = (phase, done, total) -> { };
    private final Map<String, byte[]> remote = Collections.synchronizedMap(new HashMap<>());
    private final AtomicInteger puts = new AtomicInteger(), deletes = new AtomicInteger(), leaks = new AtomicInteger();
    private volatile int putStatus;
    private volatile boolean lostAck, dropPut, race, redirect, corrupt;
    private HttpServer server;
    private ExecutorService executor;
    private int port;
    public static void main(String[] args) throws Exception {
        targetAndReceipts();
        WebDavTest test = new WebDavTest(); test.run();
        System.out.println("PASS: " + checks + " WebDAV/receipt assertions (real loopback HTTP, no Android or external server)");
    }
    private static void targetAndReceipts() throws Exception {
        DavTarget t = new DavTarget("https://CLOUD.example.com:443" + ROOT, "ivan");
        check(t.folder.equals("https://cloud.example.com" + ROOT), "Canonical HTTPS origin");
        check(t.key.equals(new DavTarget(t.folder.substring(0, t.folder.length()-1), "ivan").key), "Trailing slash keeps the same destination identity");
        check(!t.key.equals(new DavTarget(t.folder, "other").key), "Receipts bind to account as well as folder");
        check(t.file("Мій запис(01_00).m4a").toString().contains("%20"), "Filename path encoding includes spaces");
        check(t.file("a+b.m4a").toString().endsWith("a%2Bb.m4a"), "Plus is not treated as a space");
        for (String url : new String[]{"http://cloud.example.com"+ROOT,"https://u:p@cloud.example.com"+ROOT,
                "https://cloud.example.com"+ROOT+"?token=x","https://cloud.example.com"+ROOT+"#fragment",
                "https://cloud.example.com/a/%2e%2e/b/","https://cloud.example.com/"}) {
            try { new DavTarget(url,"ivan"); throw new AssertionError("Unsafe URL accepted: " + url); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (String name : new String[]{"../record.m4a","a/b.m4a","a\\b.m4a","x\r\nHeader: value"}) {
            try { t.file(name); throw new AssertionError("Unsafe path accepted"); }
            catch (IOException expected) { checks++; }
        }
        String hash = String.join("", Collections.nCopies(64, "a"));
        check(TransferPolicy.verified(t.key,t.key,100,100,20,20,hash), "A matching content receipt authorizes cleanup");
        check(!TransferPolicy.verified("","",100,100,20,20,hash), "Unconfigured cloud cannot authorize deletion");
        check(!TransferPolicy.verified(t.key,null,100,100,20,20,hash), "Migrated records without receipts are protected");
        check(!TransferPolicy.verified(t.key,"old",100,100,20,20,hash), "Changed destination protects old receipts");
        check(!TransferPolicy.verified(t.key,t.key,101,100,20,20,hash), "Changed length invalidates receipt");
        check(!TransferPolicy.verified(t.key,t.key,100,100,21,20,hash), "Changed local timestamp invalidates receipt");
        check(!TransferPolicy.verified(t.key,t.key,100,100,20,20,null), "A size-only response is insufficient");
        check(TransferPolicy.retryDelay(1)==30000L && TransferPolicy.retryDelay(2)==120000L
                && TransferPolicy.retryDelay(3)==600000L && TransferPolicy.retryDelay(20)==1800000L, "Retry backoff is bounded");
    }
    private DavClient client(DavClient.Progress progress) {
        return new DavClient(new DavTarget("https://cloud.test"+ROOT, "ivan"), "test-app-password", progress,
                url -> (HttpURLConnection) new URL("http://127.0.0.1:"+port+url.getFile()).openConnection());
    }
    private void run() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", this::handle); server.start(); port=server.getAddress().getPort();
        File directory=Files.createTempDirectory("iben-webdav-tests-").toFile();
        File source=new File(directory,"2026-09-27_23-29-21(01_00).m4a");
        byte[] bytes=new byte[300123];
        new java.util.Random(27).nextBytes(bytes); Files.write(source.toPath(),bytes);
        try {
            String name=source.getName();
            DavClient.Receipt receipt;
            try (DavClient c=client(QUIET)) { receipt=c.upload(source,name); }
            check(receipt.size==bytes.length && receipt.sha256.equals(hash(bytes)), "Full remote SHA-256 matches local bytes");
            check(Arrays.equals(remote.get(ROOT+name),bytes), "Complete source uploaded");
            check(puts.get()==1 && deletes.get()==0, "First upload creates once and deletes no recording");
            try (DavClient c=client(QUIET)) { c.upload(source,name); }
            check(puts.get()==1, "Already verified remote data is reused without a second PUT");
            dropPut=true;
            expectFailure(() -> { try(DavClient c=client(QUIET)){c.upload(source,"interrupted.m4a");} }, IOException.class);
            check(!remote.containsKey(ROOT+"interrupted.m4a") && source.isFile(), "A closed connection mid-PUT preserves the local source");
            try(DavClient c=client(QUIET)){c.upload(source,"interrupted.m4a");}
            check(Arrays.equals(remote.get(ROOT+"interrupted.m4a"),bytes), "Retry after a real connection break uploads the complete file");
            String uncertain="uncertain.m4a"; lostAck=true;
            expectFailure(() -> { try(DavClient c=client(QUIET)){c.upload(source,uncertain);} }, IOException.class);
            int afterLost=puts.get();
            try(DavClient c=client(QUIET)){c.upload(source,uncertain);}
            check(puts.get()==afterLost, "Retry after lost successful PUT response verifies instead of duplicating");
            byte[] other=bytes.clone(); other[12]^=1;
            remote.put(ROOT+"collision.m4a",other);
            int before=puts.get();
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,"collision.m4a");}},DavClient.Conflict.class);
            check(puts.get()==before && Arrays.equals(remote.get(ROOT+"collision.m4a"),other), "Same-size different-content collision is never overwritten");
            try(DavClient c=client(QUIET)){c.upload(source,"collision_unique-id.m4a");}
            check(Arrays.equals(remote.get(ROOT+"collision_unique-id.m4a"),bytes), "An alternate name preserves both files");
            race=true;
            try(DavClient c=client(QUIET)){c.upload(source,"race.m4a");}
            check(Arrays.equals(remote.get(ROOT+"race.m4a"),bytes), "412 race is resolved by content verification");
            for(int code:new int[]{401,403,409,413,423,429,500,507}) {
                putStatus=code;
                expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,"rejected-"+code+".m4a");}},IOException.class);
                check(!remote.containsKey(ROOT+"rejected-"+code+".m4a") && source.isFile(), "Failed upload keeps local source");
            }
            putStatus=0;
            redirect=true;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,"redirect.m4a");}},IOException.class);
            check(leaks.get()==0, "Redirect was not followed and Authorization was not forwarded");
            redirect=false; corrupt=true;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,name);}},DavClient.Conflict.class);
            corrupt=false;
            try(DavClient c=client(QUIET)) { c.cancel(); expectFailure(() -> c.upload(source,name), InterruptedIOException.class); }
            final DavClient[] active=new DavClient[1];
            active[0]=client((phase,done,total)->{if(phase.equals("Перевірка SHA-256")&&done>0) active[0].cancel();});
            try(DavClient c=active[0]) {expectFailure(() -> c.upload(source,name), IOException.class);}
            int count=remote.size();
            try(DavClient c=client(QUIET)) { c.test(directory); }
            check(remote.size()==count && deletes.get()==1, "Connection probe creates and removes only its own test file");
            check(source.isFile() && Arrays.equals(Files.readAllBytes(source.toPath()),bytes), "All paths leave the local recording intact");
        } finally {
            server.stop(0); executor.shutdownNow();
            for(File f:directory.listFiles()) if(!f.delete()) throw new IOException("Test cleanup failed");
            if(!directory.delete()) throw new IOException("Test directory cleanup failed");
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/leak")) {leaks.incrementAndGet(); reply(exchange,200,new byte[]{1}); return;}
            if(!AUTH.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {reply(exchange,401,null);return;}
            if(redirect) {exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+port+"/leak");reply(exchange,302,null);return;}
            if(!path.startsWith(ROOT)) {reply(exchange,409,null);return;}
            switch(exchange.getRequestMethod()) {
                case "GET": {
                    byte[] data=remote.get(path);
                    if(data==null) {reply(exchange,404,null);return;}
                    exchange.getResponseHeaders().set("ETag","\""+hash(data)+"\"");
                    if(corrupt) {data=data.clone();data[0]^=1;}
                    reply(exchange,200,data);return;
                }
                case "PUT": {
                    if(!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {reply(exchange,400,null);return;}
                    if(dropPut) {
                        dropPut=false;
                        // Close the socket before consuming the body or returning any HTTP status.
                        exchange.getRequestBody().read(new byte[8192]);
                        return;
                    }
                    byte[] body=read(exchange.getRequestBody());
                    puts.incrementAndGet();
                    if(putStatus!=0) {reply(exchange,putStatus,null);return;}
                    if(race) {race=false;remote.put(path,body);reply(exchange,412,null);return;}
                    if(remote.containsKey(path)) {reply(exchange,412,null);return;}
                    remote.put(path,body);
                    if(lostAck) {lostAck=false;reply(exchange,500,null);return;}
                    reply(exchange,201,null);return;
                }
                case "DELETE": {
                    if(!path.contains("/.iben-connection-test-")) {reply(exchange,403,null);return;}
                    byte[] current=remote.get(path);
                    if(current!=null && !("\""+hash(current)+"\"").equals(exchange.getRequestHeaders().getFirst("If-Match"))) {reply(exchange,412,null);return;}
                    remote.remove(path);deletes.incrementAndGet();reply(exchange,204,null);return;
                }
                default:reply(exchange,405,null);
            }
        } finally {exchange.close();}
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1) out.write(b,0,n);return out.toByteArray();
    }
    private static void reply(HttpExchange e,int code,byte[] body) throws IOException {
        e.sendResponseHeaders(code,body==null?-1:body.length);
        if(body!=null) e.getResponseBody().write(body);
    }
    private static String hash(byte[] data) {
        try{return DavTarget.hex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(Exception e){throw new AssertionError(e);}
    }
    private interface Action {void run() throws Exception;}
    private static void expectFailure(Action action,Class<? extends Exception> type) throws Exception {
        try{action.run();throw new AssertionError("Failure expected: "+type.getSimpleName());}
        catch(Exception e){check(type.isInstance(e),"Expected "+type.getSimpleName()+", got "+e);}
    }
    private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
}
