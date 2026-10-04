package ua.iben.recorder;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Real TLS handshake, production DavClient connection path, and isolated temporary test certificates. */
public final class TlsCertificateTest {
    private static int checks;
    private static final DavClient.Progress QUIET=(p,d,t)->{};
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface Operation {void run() throws Exception;}
    private static void fails(Operation task,String why) throws Exception {
        try {task.run();throw new AssertionError(why);}
        catch(IOException | CertificateException | IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("iben-tls-tests-");
        try {
            KeyStore matching=identity(directory,"matching","SAN=ip:127.0.0.1,dns:localhost",false);
            KeyStore other=identity(directory,"other","SAN=ip:127.0.0.1",false);
            KeyStore wrongHost=identity(directory,"wrong-host","SAN=dns:wrong.invalid",false);
            KeyStore expired=identity(directory,"expired","SAN=ip:127.0.0.1",true);
            byte[] der=matching.getCertificate("server").getEncoded();
            String imported=TlsCertificate.importPublic(der);
            String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(der)+"\n-----END CERTIFICATE-----\n";
            check(imported.equals(TlsCertificate.importPublic(pem.getBytes(StandardCharsets.US_ASCII))),"DER and PEM import the same public certificate");
            check(TlsCertificate.fingerprint(imported).equals(DavTarget.hex(java.security.MessageDigest.getInstance("SHA-256").digest(der))),"Displayed SHA-256 fingerprint matches certificate bytes");
            fails(()->TlsCertificate.importPublic((pem+"-----BEGIN PRIVATE KEY-----").getBytes(StandardCharsets.US_ASCII)),"Private key material rejected");
            fails(()->TlsCertificate.importPublic((pem+pem).getBytes(StandardCharsets.US_ASCII)),"Ambiguous multiple-certificate files rejected");
            fails(()->TlsCertificate.importPublic(new byte[65537]),"Oversized certificate rejected");
            fails(()->TlsCertificate.importPublic(new byte[0]),"Empty certificate rejected");
            fails(()->TlsCertificate.importPublic(expired.getCertificate("server").getEncoded()),"Expired certificate rejected at import");
            String old=Base64.getEncoder().encodeToString(expired.getCertificate("server").getEncoded());
            fails(()->TlsCertificate.socketFactory(old),"Previously stored expired certificate rejected at use");
            try(Server server=new Server(matching)) {
                DavTarget target=server.target();File source=directory.resolve("recording.m4a").toFile();
                byte[] bytes=new byte[32769];new java.util.Random(7).nextBytes(bytes);Files.write(source.toPath(),bytes);
                DavClient.Receipt receipt;
                try(DavClient client=new DavClient(target,"password",QUIET,imported)) {receipt=client.upload(source,"recording.m4a");}
                check(Arrays.equals(server.files.get("/records/recording.m4a"),bytes),"Pinned self-signed HTTPS uploads through production client");
                check(receipt.kind==TransferPolicy.METADATA && receipt.sha256==null && TransferPolicy.strongEtag(receipt.etag),
                        "Production HTTPS returns an explicitly metadata-only receipt");
                int requests=server.requests.get();
                fails(()->{try(DavClient client=new DavClient(target,"password",QUIET)){client.upload(source,"no-pin.m4a");}},"Default trust rejects self-signed server");
                check(server.requests.get()==requests,"Failed default TLS sends no HTTP credentials");
                String otherPin=TlsCertificate.importPublic(other.getCertificate("server").getEncoded());
                fails(()->{try(DavClient client=new DavClient(target,"password",QUIET,otherPin)){client.upload(source,"wrong-pin.m4a");}},"Different certificate cannot use the imported trust");
                check(server.requests.get()==requests,"Failed pin sends no HTTP request");
                // A failed pin must not alter subsequent trust or global HTTPS defaults.
                try(DavClient client=new DavClient(target,"password",QUIET,imported)) {
                    client.deleteRecording("recording.m4a",receipt.size);
                }
                check(server.files.isEmpty(),"Metadata-checked conditional deletion also works over pinned HTTPS");
                check(server.requests.get()==requests+2,"Pinned HTTPS deletion uses only HEAD and DELETE");
                check(Files.exists(source.toPath()),"Cloud operations retain the local recording");
            }
            try(Server server=new Server(wrongHost)) {
                String pin=TlsCertificate.importPublic(wrongHost.getCertificate("server").getEncoded());
                File source=directory.resolve("recording.m4a").toFile();
                fails(()->{try(DavClient client=new DavClient(server.target(),"password",QUIET,pin)){client.upload(source,"bad-host.m4a");}},"An imported certificate does not disable hostname verification");
                check(server.requests.get()==0,"Wrong hostname sends no HTTP credentials");
            }
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                for(Path path:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: "+checks+" real HTTPS / exact certificate / hostname / expiry / credential isolation assertions");
    }
    private static KeyStore identity(Path dir,String name,String san,boolean expired) throws Exception {
        Path path=dir.resolve(name+".p12");
        java.util.List<String> args=new java.util.ArrayList<>(Arrays.asList(
                System.getProperty("java.home")+File.separator+"bin"+File.separator+"keytool",
                "-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-sigalg","SHA256withRSA",
                "-dname","CN=test.invalid","-ext",san,"-validity",expired?"1":"2",
                "-storetype","PKCS12","-keystore",path.toString(),"-storepass","test-only-password","-keypass","test-only-password","-noprompt"));
        if(expired){args.add("-startdate");args.add("2020/01/01 00:00:00");}
        java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        byte[] log=read(process.getInputStream());
        if(process.waitFor()!=0)throw new IOException("Temporary test certificate generation failed: "+new String(log,StandardCharsets.UTF_8));
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(InputStream in=Files.newInputStream(path)){store.load(in,"test-only-password".toCharArray());}
        return store;
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
    }
    private static final class Server implements AutoCloseable {
        final Map<String,byte[]> files=Collections.synchronizedMap(new HashMap<>());
        final AtomicInteger requests=new AtomicInteger();final HttpsServer server;
        final ExecutorService executor=Executors.newCachedThreadPool();
        Server(KeyStore store) throws Exception {
            KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store,"test-only-password".toCharArray());
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),null,null);
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));server.setExecutor(executor);
            server.createContext("/",exchange -> {
                try {
                    requests.incrementAndGet();
                    String auth="Basic "+Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8));
                    if(!auth.equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);return;}
                    String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                    if(method.equals("HEAD")) {
                        byte[] bytes=files.get(path);
                        if(bytes==null){exchange.sendResponseHeaders(404,-1);return;}
                        exchange.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));
                        exchange.getResponseHeaders().set("ETag","\"test-version\"");
                        exchange.sendResponseHeaders(200,-1);
                    } else if(method.equals("GET")) {
                        byte[] bytes=files.get(path);
                        if(bytes==null){exchange.sendResponseHeaders(404,-1);return;}
                        exchange.getResponseHeaders().set("ETag","\"test-version\"");
                        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                    } else if(method.equals("PUT")) {
                        if(!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match")) || files.containsKey(path)) {
                            exchange.sendResponseHeaders(412,-1);return;
                        }
                        files.put(path,read(exchange.getRequestBody()));exchange.sendResponseHeaders(201,-1);
                    } else if(method.equals("DELETE")) {
                        if(!"\"test-version\"".equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
                            exchange.sendResponseHeaders(412,-1);return;
                        }
                        files.remove(path);exchange.sendResponseHeaders(204,-1);
                    } else exchange.sendResponseHeaders(405,-1);
                } finally {exchange.close();}
            });server.start();
        }
        DavTarget target(){return new DavTarget("https://127.0.0.1:"+server.getAddress().getPort()+"/records/","user");}
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
}
