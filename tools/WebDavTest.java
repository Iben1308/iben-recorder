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
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicInteger gets = new AtomicInteger(), heads = new AtomicInteger();
    private final AtomicLong audioResponseBytes = new AtomicLong();
    private volatile int putStatus;
    private volatile boolean lostAck, dropPut, race, redirect, corrupt;
    private volatile boolean weakEtag, noEtag, deleteRace, lostDeleteAck;
    private volatile int deleteStatus;
    private volatile int headStatus;
    private volatile boolean noLength;
    private volatile String invalidEtag;
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
        check(TransferPolicy.confirmed(t.key,t.key,100,100,20,20,null,TransferPolicy.METADATA,"\"version\""),
                "Metadata receipt accepts matching identity and a strong ETag without inventing a hash");
        for(String etag:new String[]{null,"","W/\"weak\"","unquoted","\"bad\"quote\"","\"bad\nvalue\""})
            check(!TransferPolicy.confirmed(t.key,t.key,100,100,20,20,null,TransferPolicy.METADATA,etag),"Invalid ETag cannot authorize cleanup");
        check(!TransferPolicy.confirmed(t.key,"other",100,100,20,20,null,TransferPolicy.METADATA,"\"v\""),"Metadata receipt binds destination");
        check(!TransferPolicy.confirmed(t.key,t.key,101,100,20,20,null,TransferPolicy.METADATA,"\"v\""),"Metadata receipt binds size");
        check(!TransferPolicy.confirmed(t.key,t.key,100,100,21,20,null,TransferPolicy.METADATA,"\"v\""),"Metadata receipt binds local modification time");
        for(int kind:new int[]{TransferPolicy.NONE,99})
            check(!TransferPolicy.confirmed(t.key,t.key,100,100,20,20,hash,kind,"\"v\""),"Unknown/unconfirmed receipt kind protects local file");
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
            check(receipt.size==bytes.length && receipt.modified==source.lastModified()
                    && receipt.sha256==null && receipt.kind==TransferPolicy.METADATA && TransferPolicy.strongEtag(receipt.etag),
                    "HEAD confirms metadata and never claims full content verification");
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
            byte[] other=Arrays.copyOf(bytes,bytes.length+1);
            remote.put(ROOT+"collision.m4a",other);
            int before=puts.get();
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,"collision.m4a");}},DavClient.Conflict.class);
            check(puts.get()==before && Arrays.equals(remote.get(ROOT+"collision.m4a"),other), "Different-size collision is never overwritten");
            byte[] sameSize=bytes.clone();sameSize[12]^=1;remote.put(ROOT+"same-size.m4a",sameSize);
            try(DavClient c=client(QUIET)){receipt=c.upload(source,"same-size.m4a");}
            check(receipt.kind==TransferPolicy.METADATA && receipt.sha256==null && puts.get()==before,
                    "Same-name/same-size replacement is an explicit limitation of metadata verification");
            try(DavClient c=client(QUIET)){c.upload(source,"collision_unique-id.m4a");}
            check(Arrays.equals(remote.get(ROOT+"collision_unique-id.m4a"),bytes), "An alternate name preserves both files");
            race=true;
            try(DavClient c=client(QUIET)){c.upload(source,"race.m4a");}
            check(Arrays.equals(remote.get(ROOT+"race.m4a"),bytes), "412 race is resolved by metadata without overwriting");
            for(int code:new int[]{401,403,409,413,423,429,500,507}) {
                putStatus=code;
                expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,"rejected-"+code+".m4a");}},IOException.class);
                check(!remote.containsKey(ROOT+"rejected-"+code+".m4a") && source.isFile(), "Failed upload keeps local source");
            }
            putStatus=0;
            redirect=true;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,"redirect.m4a");}},IOException.class);
            check(leaks.get()==0, "Redirect was not followed and Authorization was not forwarded");
            redirect=false;
            uploadMetadataCases(source);
            try(DavClient c=client(QUIET)) { c.cancel(); expectFailure(() -> c.upload(source,name), InterruptedIOException.class); }
            final DavClient[] active=new DavClient[1];
            active[0]=client((phase,done,total)->{if(phase.equals(I18n.uk("upload_check_metadata"))) active[0].cancel();});
            try(DavClient c=active[0]) {expectFailure(() -> c.upload(source,name), IOException.class);}
            int count=remote.size();
            try(DavClient c=client(QUIET)) { c.test(directory); }
            check(remote.size()==count && deletes.get()==1, "Connection probe creates and removes only its own test file");
            deletionCases(source, bytes);
            check(gets.get()==0 && audioResponseBytes.get()==0,"All upload, retry, probe and delete paths download zero audio bytes");
            check(source.isFile() && Arrays.equals(Files.readAllBytes(source.toPath()),bytes), "All paths leave the local recording intact");
        } finally {
            server.stop(0); executor.shutdownNow();
            for(File f:directory.listFiles()) if(!f.delete()) throw new IOException("Test cleanup failed");
            if(!directory.delete()) throw new IOException("Test directory cleanup failed");
        }
    }
    private void uploadMetadataCases(File source) throws Exception {
        String name=source.getName();int before=puts.get();
        for(int mode=0;mode<4;mode++) {
            weakEtag=mode==0;noEtag=mode==1;noLength=mode==2;invalidEtag=mode==3 ? "unquoted" : null;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,name);}},IOException.class);
            check(puts.get()==before && source.isFile(),"Incomplete metadata produces no receipt and does not re-upload or delete");
        }
        weakEtag=false;noEtag=false;noLength=false;invalidEtag=null;
        for(int status:new int[]{401,403,405,429,500,501,507}) {
            headStatus=status;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.upload(source,name);}},IOException.class);
            check(puts.get()==before,"Failed HEAD never falls back to GET or unconditional PUT");
        }
        headStatus=0;
        File changing=new File(source.getParentFile(),"changing.m4a");Files.copy(source.toPath(),changing.toPath());
        long oldModified=changing.lastModified();AtomicInteger phaseCount=new AtomicInteger();
        try(DavClient c=client((phase,done,total) -> {
            if(phase.equals(I18n.uk("upload_check_metadata")) && phaseCount.incrementAndGet()==2)
                check(changing.setLastModified(oldModified+2000),"Fixture changes local modification time");
        })) {expectFailure(() -> c.upload(changing,changing.getName()),IOException.class);}
        check(changing.isFile(),"Mutation during transfer leaves local source without a receipt");
        check(changing.delete(),"Fixture cleanup");
        final DavClient[] cancel=new DavClient[1];AtomicInteger metadata=new AtomicInteger();
        cancel[0]=client((phase,done,total) -> {
            if(phase.equals(I18n.uk("upload_check_metadata")) && metadata.incrementAndGet()==2)cancel[0].cancel();
        });
        try(DavClient c=cancel[0]) {expectFailure(() -> c.upload(source,"cancel-after-put.m4a"),InterruptedIOException.class);}
        int afterCancel=puts.get();
        try(DavClient c=client(QUIET)){c.upload(source,"cancel-after-put.m4a");}
        check(puts.get()==afterCancel,"Retry after cancellation following PUT confirms with HEAD without uploading again");
    }
    private void deletionCases(File source, byte[] bytes) throws Exception {
        String name = "delete-one.m4a", path = ROOT + name;
        int getsBefore=gets.get(),headsBefore=heads.get();
        long bytesBefore=audioResponseBytes.get();
        int before = deletes.get(), retained = remote.size();
        remote.put(path, bytes);
        try (DavClient c=client(QUIET)) { c.deleteRecording(name, bytes.length); }
        check(!remote.containsKey(path) && remote.size()==retained && deletes.get()==before+1,
                "An explicit DELETE removes only the exact named and size-checked recording");
        check(heads.get()==headsBefore+1 && gets.get()==getsBefore && audioResponseBytes.get()==bytesBefore,
                "Successful deletion issues HEAD and downloads zero audio bytes");
        try (DavClient c=client(QUIET)) { c.deleteRecording(name, bytes.length); }
        check(deletes.get()==before+1, "An already absent remote recording is idempotent without another DELETE");

        byte[] other=Arrays.copyOf(bytes,bytes.length+1);
        remote.put(path, other); before=deletes.get();
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},DavClient.Conflict.class);
        check(Arrays.equals(remote.get(path),other) && deletes.get()==before,
                "A different-size recording at the expected name is never deleted");
        // Explicit scope of the simplified policy: identical name and size are accepted.
        byte[] sameSize=bytes.clone();sameSize[7]^=1;
        String replacement="same-size-replacement.m4a";
        remote.put(ROOT+replacement,sameSize);
        try(DavClient c=client(QUIET)) {c.deleteRecording(replacement,bytes.length);}
        check(!remote.containsKey(ROOT+replacement),"A prior same-size replacement is intentionally not SHA-256 checked by quick deletion");
        before=deletes.get();
        remote.put(path, bytes); deleteRace=true;
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},DavClient.Conflict.class);
        check(remote.containsKey(path) && !Arrays.equals(remote.get(path),bytes) && deletes.get()==before,
                "If-Match protects a replacement arriving between HEAD and DELETE");
        remote.put(path,bytes);
        for (int mode=0; mode<2; mode++) {
            weakEtag=mode==0; noEtag=mode==1;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
            check(Arrays.equals(remote.get(path),bytes) && deletes.get()==before,
                    "Weak or missing ETags never authorize unconditional deletion");
        }
        weakEtag=false; noEtag=false;
        invalidEtag="unquoted-version";
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
        check(remote.containsKey(path) && deletes.get()==before,"A malformed ETag cannot authorize deletion");
        invalidEtag=null;
        noLength=true;
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
        check(remote.containsKey(path) && deletes.get()==before,"Missing Content-Length preserves the recording without GET fallback");
        noLength=false;
        for(int code:new int[]{401,403,405,429,500,501,507}) {
            headStatus=code;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
            check(remote.containsKey(path) && deletes.get()==before,"Failed/unsupported HEAD does not issue DELETE or fall back to GET");
        }
        headStatus=0;
        for (int code:new int[]{401,403,423,500}) {
            deleteStatus=code;
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
            check(remote.containsKey(path) && source.isFile(), "Rejected deletion preserves both copies");
        }
        deleteStatus=0; redirect=true;
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
        check(leaks.get()==0 && remote.containsKey(path), "Deletion never follows redirects or leaks credentials");
        redirect=false;
        final DavClient[] early=new DavClient[1];
        int headsAtCancel=heads.get();
        early[0]=client((phase,done,total)-> {if(phase.equals(I18n.uk("cloud_delete_checking")))early[0].cancel();});
        try(DavClient c=early[0]) {expectFailure(() -> c.deleteRecording(name,bytes.length),InterruptedIOException.class);}
        check(heads.get()==headsAtCancel && remote.containsKey(path),"Cancellation before metadata sends no HEAD and keeps the remote file");
        try(DavClient c=client(QUIET)) {
            c.cancel(); expectFailure(() -> c.deleteRecording(name,bytes.length),InterruptedIOException.class);
        }
        AtomicInteger guard = new AtomicInteger();
        try(DavClient c=client(QUIET)) {
            expectFailure(() -> c.deleteRecording(name,bytes.length,() -> guard.getAndIncrement()==0),InterruptedIOException.class);
        }
        check(remote.containsKey(path) && deletes.get()==before, "A changed connection after HEAD prevents DELETE");
        final DavClient[] active=new DavClient[1];
        active[0]=client((phase,done,total)-> { if(phase.equals(I18n.uk("cloud_delete_removing"))) active[0].cancel(); });
        try(DavClient c=active[0]) {expectFailure(() -> c.deleteRecording(name,bytes.length),IOException.class);}
        check(remote.containsKey(path) && deletes.get()==before, "Cancel during verification leaves the remote recording intact");
        for(String invalid:new String[]{"../escape.m4a","folder/recording.m4a","*","folder/","note.txt"}) {
            expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(invalid,bytes.length);}},IOException.class);
        }
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,0);}},IOException.class);
        check(remote.containsKey(path) && deletes.get()==before, "Directories, traversal, wildcards and invalid expected size cannot delete");
        lostDeleteAck=true;
        expectFailure(() -> {try(DavClient c=client(QUIET)){c.deleteRecording(name,bytes.length);}},IOException.class);
        check(!remote.containsKey(path) && source.isFile(), "A lost DELETE acknowledgement still keeps the local source");
        int afterLost=deletes.get();
        try(DavClient c=client(QUIET)) {c.deleteRecording(name,bytes.length);}
        check(deletes.get()==afterLost && afterLost==before+1, "Retry after lost DELETE response confirms absence without a second deletion");
        check(Arrays.equals(Files.readAllBytes(source.toPath()),bytes), "Cloud-only paths leave local audio bytes unchanged");
        int batchHeads=heads.get(),batchDeletes=deletes.get();
        for(String batchName:new String[]{"Запис 1(01_00).m4a","record-2.m4a","record-3.m4a"}) {
            remote.put(ROOT+batchName,bytes);
            try(DavClient c=client(QUIET)){c.deleteRecording(batchName,bytes.length);}
            check(!remote.containsKey(ROOT+batchName),"Sequential cloud deletion uses the exact encoded file name");
        }
        check(heads.get()==batchHeads+3 && deletes.get()==batchDeletes+3,"Three selected files use three HEADs and three conditional DELETEs");
        check(gets.get()==getsBefore && audioResponseBytes.get()==bytesBefore,
                "All quick-deletion success/failure/cancel/retry/batch paths download zero audio bytes");
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/leak")) {leaks.incrementAndGet(); reply(exchange,200,new byte[]{1}); return;}
            if(!AUTH.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {reply(exchange,401,null);return;}
            if(redirect) {exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+port+"/leak");reply(exchange,302,null);return;}
            if(!path.startsWith(ROOT)) {reply(exchange,409,null);return;}
            switch(exchange.getRequestMethod()) {
                case "HEAD": {
                    heads.incrementAndGet();
                    if(headStatus!=0){reply(exchange,headStatus,null);return;}
                    byte[] data=remote.get(path);
                    if(data==null){reply(exchange,404,null);return;}
                    if(!noLength)exchange.getResponseHeaders().set("Content-Length",Integer.toString(data.length));
                    if(!noEtag)exchange.getResponseHeaders().set("ETag",invalidEtag!=null ? invalidEtag : (weakEtag ? "W/" : "")+"\""+hash(data)+"\"");
                    exchange.getResponseHeaders().set("Last-Modified","Sun, 01 Jan 2023 00:00:00 GMT");
                    reply(exchange,200,null);return;
                }
                case "GET": {
                    gets.incrementAndGet();
                    byte[] data=remote.get(path);
                    if(data==null) {reply(exchange,404,null);return;}
                    if (!noEtag) exchange.getResponseHeaders().set("ETag",(weakEtag ? "W/" : "")+"\""+hash(data)+"\"");
                    if(corrupt) {data=data.clone();data[0]^=1;}
                    audioResponseBytes.addAndGet(data.length);reply(exchange,200,data);return;
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
                    if(deleteStatus!=0) {reply(exchange,deleteStatus,null);return;}
                    byte[] current=remote.get(path);
                    if (deleteRace && current!=null) {
                        deleteRace=false; current=current.clone(); current[0]^=1; remote.put(path,current);
                    }
                    if(current!=null && !("\""+hash(current)+"\"").equals(exchange.getRequestHeaders().getFirst("If-Match"))) {reply(exchange,412,null);return;}
                    remote.remove(path);deletes.incrementAndGet();
                    if (lostDeleteAck) {lostDeleteAck=false;reply(exchange,500,null);return;}
                    reply(exchange,204,null);return;
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
