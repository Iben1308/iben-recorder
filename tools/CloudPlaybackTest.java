package ua.iben.recorder;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real HTTP for catalogue/range requests; the cache test uses only MediaDataSource's abstract API stub.
 * Does not pretend to exercise Android MediaPlayer or foreground-service lifecycle. */
public final class CloudPlaybackTest {
    private static int checks;
    private static final DavTarget TARGET=new DavTarget("https://cloud.example/records/","user");
    private static final String AUTH="Basic "+Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8));
    interface Task{void run()throws Exception;}
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void fails(Task work,String why)throws Exception{try{work.run();throw new AssertionError(why);}catch(IOException expected){checks++;}}
    static byte[] xml(String body){return ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"+body+"</d:multistatus>").getBytes(StandardCharsets.UTF_8);}
    static String entry(String href,String extra,String status){return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop><d:getcontentlength>700001</d:getcontentlength><d:getetag>&quot;v1&quot;</d:getetag>"+extra+"</d:prop><d:status>HTTP/1.1 "+status+"</d:status></d:propstat></d:response>";}
    public static void main(String[] args)throws Exception{
        parser();framing();registry();
        try(Server server=new Server()){
            try(DavClient client=server.client()){
                List<DavListing.Remote> list=client.list();check(list.size()==1 && list.get(0).name.equals("audio.m4a"),"One-folder catalogue through real PROPFIND");
                check(server.propfind.get()==1 && server.gets.get()==0,"Catalogue never downloads audio");
                DavClient.RemoteInfo info=client.inspect("audio.m4a");
                check(info.size==server.bytes.length && info.etag.equals("\"v1\""),"HEAD supplies audio length/version");
                check(Arrays.equals(client.range("audio.m4a",500000,100,info),Arrays.copyOfRange(server.bytes,500000,500100)),"Seeking fetches the requested byte range");
                check(Arrays.equals(client.range("audio.m4a",700000,100,info),new byte[]{server.bytes[700000]}),"Last range clamps to EOF");
                check(server.sent.get()==101,"Only requested bytes downloaded");
                fails(()->client.range("audio.m4a",-1,100,info),"Negative range rejected");
                fails(()->client.range("audio.m4a",0,1048577,info),"Oversized range rejected");
                server.mode="wrong-range";fails(()->client.range("audio.m4a",0,100,info),"Wrong Content-Range rejected");
                server.mode="short";fails(()->client.range("audio.m4a",0,100,info),"Truncated range rejected");
                server.mode="changed";fails(()->client.range("audio.m4a",0,100,info),"Changed ETag rejected even if server ignores If-Match");
                server.mode="compressed";fails(()->client.range("audio.m4a",0,100,info),"Unexpected content encoding rejected");
                server.mode="redirect";fails(()->client.range("audio.m4a",0,100,info),"Redirect rejected");check(server.leaks.get()==0,"Credentials never forwarded");
                server.mode="replace";fails(()->client.range("audio.m4a",0,100,info),"412 fails playback rather than mixing file versions");
                server.mode="whole";boolean fallback=false;
                try{client.range("audio.m4a",0,100,info);}catch(DavClient.RangeUnavailable expected){fallback=true;}
                check(fallback,"Servers ignoring Range request an explicit fallback");
                ByteArrayOutputStream copy=new ByteArrayOutputStream();client.download("audio.m4a",info,copy);check(Arrays.equals(copy.toByteArray(),server.bytes),"Fallback downloads exact bytes");
                server.mode="changed";fails(()->client.download("audio.m4a",info,new ByteArrayOutputStream()),"Full download also rejects changed version");
                server.mode="normal";
            }
            AtomicBoolean allowed=new AtomicBoolean(true);
            try(DavClient client=server.client();CloudAudioSource source=new CloudAudioSource(client,"audio.m4a",client.inspect("audio.m4a"),allowed::get)){
                source.prepare();int before=server.gets.get();byte[] output=new byte[500];
                check(source.readAt(25,output,20,100)==100,"Cache read count");
                check(Arrays.equals(Arrays.copyOfRange(output,20,120),Arrays.copyOfRange(server.bytes,25,125)),"Cache respects source and destination offsets");
                check(server.gets.get()==before,"Repeated bytes use memory cache");
                check(source.readAt(262100,output,0,200)==200 && Arrays.equals(Arrays.copyOf(output,200),Arrays.copyOfRange(server.bytes,262100,262300)),"Read crosses a cache block boundary");
                check(source.readAt(server.bytes.length,output,0,1)==-1,"EOF is signaled to decoder");
                check(source.readAt(0,output,0,0)==0,"Zero-size read");
                fails(()->source.readAt(0,output,499,2),"Destination bounds checked");
                allowed.set(false);fails(()->source.readAt(0,output,0,1),"Connection switch invalidates cached and new reads");
            }
            try(DavClient client=server.client()){
                server.mode="blocked";AtomicReference<Throwable> problem=new AtomicReference<>();
                Thread reader=new Thread(()->{try{client.list();}catch(Throwable e){problem.set(e);}});reader.start();
                check(server.blocked.await(3,TimeUnit.SECONDS),"Blocked PROPFIND reached server");client.cancel();reader.join(3000);
                check(!reader.isAlive() && problem.get() instanceof IOException,"Cancellation interrupts blocked catalogue I/O");server.release.countDown();
            }
        }
        System.out.println("PASS: "+checks+" cloud catalogue / HTTP range / bounded cache / targeted cancellation assertions");
    }
    static void parser()throws Exception{
        String good=entry("/records/%D0%97%D0%B0%D0%BF%D0%B8%D1%81%2B1.m4a","<d:getlastmodified>Sun, 04 Oct 2026 11:00:00 GMT</d:getlastmodified>","200 OK");
        List<DavListing.Remote> list=DavListing.parse(xml(good),TARGET);
        check(list.size()==1 && list.get(0).name.equals("\u0417\u0430\u043f\u0438\u0441+1.m4a") && list.get(0).modified>0,"UTF-8, percent encoding, plus and dates preserved");
        for(String href:new String[]{"https://evil.invalid/records/audio.m4a","//evil.invalid/records/audio.m4a","/other/audio.m4a","/records/sub/audio.m4a","/records/a%2Fb.m4a","/records/a%5Cb.m4a","/records/%0aa.m4a","/records/a.m4a?q=x","/records/a.m4a#x","/records/../a.m4a"})
            check(DavListing.parse(xml(entry(href,"","200 OK")),TARGET).isEmpty(),"Foreign or ambiguous path excluded: "+href);
        check(DavListing.parse(xml(entry("/records/folder.m4a","<d:resourcetype><d:collection/></d:resourcetype>","200 OK")),TARGET).isEmpty(),"Collections are not recordings");
        fails(()->DavListing.parse(xml(good+good),TARGET),"Duplicate href fails whole catalogue");
        fails(()->DavListing.parse(xml(entry("/records/audio.m4a","","403 Forbidden")),TARGET),"Incomplete properties cannot erase a known cloud entry");
        fails(()->DavListing.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".getBytes(StandardCharsets.UTF_8),TARGET),"External entities rejected before XML parsing");
        fails(()->DavListing.parse(new byte[DavListing.MAX_BYTES+1],TARGET),"Oversized XML rejected");
        fails(()->DavListing.parse("<html/>".getBytes(StandardCharsets.UTF_8),TARGET),"Only DAV multistatus accepted");
        check(DavListing.timing("2026-10-04_11-22-33(60_05).m4a",0)[1]==3605000,"Recorder duration read from filename");
        check(DavListing.timing("audio.m4a",123)[0]==123,"Other audio uses server time until decoded");
    }
    static void framing()throws Exception{
        check(new String(frame("HTTP/1.1 207 Multi-Status\r\nContent-Length: 3\r\n\r\nabc"),StandardCharsets.UTF_8).equals("abc"),"Fixed-length PROPFIND");
        check(new String(frame("HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n1\r\nc\r\n0\r\n\r\n"),StandardCharsets.UTF_8).equals("abc"),"Chunked PROPFIND");
        for(String bad:new String[]{"HTTP/1.1 302 Found\r\n\r\n","HTTP/1.1 207 OK\r\nContent-Length: 4\r\n\r\nabc","HTTP/1.1 207 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx","HTTP/1.1 207 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\nx","HTTP/1.1 207 OK\r\nContent-Length: 9000000\r\n\r\n","HTTP/1.1 207 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n"})fails(()->frame(bad),"Unsafe HTTP framing rejected");
    }
    static byte[] frame(String input)throws IOException{return DavFolderTransport.response(new ByteArrayInputStream(input.getBytes(StandardCharsets.ISO_8859_1)));}
    static void registry()throws Exception{
        FileUseRegistry uses=new FileUseRegistry();AtomicBoolean canceled=new AtomicBoolean(),otherCanceled=new AtomicBoolean();CountDownLatch acquired=new CountDownLatch(1),finish=new CountDownLatch(1);
        Thread processor=new Thread(()->{try(FileUseRegistry.Use ignored=uses.acquire("selected",()->{canceled.set(true);finish.countDown();})){acquired.countDown();finish.await();}catch(Exception e){throw new RuntimeException(e);}});processor.start();check(acquired.await(2,TimeUnit.SECONDS),"Processing holds its lease");
        try(FileUseRegistry.Use other=uses.acquire("other",()->otherCanceled.set(true));FileUseRegistry.Reservation deletion=uses.reserve("selected")){
            fails(()->uses.acquire("selected",null),"Reserved recording rejects new readers");
            deletion.stopAndAwait(2000);check(canceled.get() && !uses.busy("selected") && !otherCanceled.get(),"Only selected recording is canceled and fully released");
            check(uses.owns("selected"),"Reservation persists until unlink completes");
        }processor.join(2000);
        try(FileUseRegistry.Use stuck=uses.acquire("stuck",null);FileUseRegistry.Reservation deletion=uses.reserve("stuck")){
            fails(()->deletion.stopAndAwait(20),"Unresponsive reader times out without granting deletion");check(uses.busy("stuck") && uses.reserved("stuck"),"Live reader stays protected");
        }
        check(!uses.busy("stuck") && !uses.reserved("stuck"),"Resources released after failed deletion");
        try(FileUseRegistry.Use read=uses.acquire("interrupted",null);FileUseRegistry.Reservation deletion=uses.reserve("interrupted")){
            Thread.currentThread().interrupt();fails(()->deletion.stopAndAwait(5000),"Interrupted wait cannot authorize deletion");check(Thread.interrupted(),"Interrupt status is preserved");
        }
    }
    static final class Server implements AutoCloseable{
        final byte[] bytes=new byte[700001];final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicInteger gets=new AtomicInteger(),propfind=new AtomicInteger(),leaks=new AtomicInteger();final AtomicLong sent=new AtomicLong();
        final CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);volatile String mode="normal";
        Server()throws IOException{
            new Random(52).nextBytes(bytes);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{try{
                if(exchange.getRequestURI().getPath().equals("/leak")){leaks.incrementAndGet();exchange.sendResponseHeaders(403,-1);return;}
                if(!AUTH.equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);return;}
                String method=exchange.getRequestMethod();
                if(method.equals("PROPFIND")){
                    propfind.incrementAndGet();if(!"1".equals(exchange.getRequestHeaders().getFirst("Depth"))){exchange.sendResponseHeaders(400,-1);return;}
                    if(mode.equals("blocked")){blocked.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                    byte[] data=xml(entry("/records/audio.m4a","","200 OK"));exchange.sendResponseHeaders(207,data.length);exchange.getResponseBody().write(data);return;
                }
                exchange.getResponseHeaders().set("ETag",mode.equals("changed")?"\"v2\"":"\"v1\"");
                if(method.equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));exchange.sendResponseHeaders(200,-1);return;}
                if(!method.equals("GET")){exchange.sendResponseHeaders(405,-1);return;}gets.incrementAndGet();
                if(!"\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-Match"))){exchange.sendResponseHeaders(412,-1);return;}
                if(mode.equals("redirect")){exchange.getResponseHeaders().set("Location","/leak");exchange.sendResponseHeaders(302,-1);return;}
                if(mode.equals("replace")){exchange.sendResponseHeaders(412,-1);return;}
                String range=exchange.getRequestHeaders().getFirst("Range");
                if(range==null || mode.equals("whole")){exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);sent.addAndGet(bytes.length);return;}
                String[] bounds=range.substring(6).split("-");int start=Integer.parseInt(bounds[0]),end=Integer.parseInt(bounds[1]);int count=end-start+1;
                exchange.getResponseHeaders().set("Content-Range","bytes "+(mode.equals("wrong-range")?start+1:start)+"-"+end+"/"+bytes.length);
                if(mode.equals("compressed"))exchange.getResponseHeaders().set("Content-Encoding","gzip");
                exchange.sendResponseHeaders(206,mode.equals("short")?count-1:count);exchange.getResponseBody().write(bytes,start,mode.equals("short")?count-1:count);sent.addAndGet(count);
            }catch(IOException ignored){}finally{exchange.close();}});server.start();
        }
        DavClient client(){return new DavClient(TARGET,"password",(phase,done,total)->{},url->(HttpURLConnection)new URL("http://127.0.0.1:"+server.getAddress().getPort()+url.getFile()).openConnection());}
        public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
    }
}
