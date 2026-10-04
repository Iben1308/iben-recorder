package ua.iben.recorder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** One bounded HTTP/1.1 PROPFIND. Android's HttpURLConnection rejects this DAV verb.
 * Reuses the client's per-connection TLS factory, validates hostnames, never follows redirects.
 * A fresh socket and Connection: close avoid connection/pipeline ambiguity. */
final class DavFolderTransport implements AutoCloseable {
    private volatile Socket socket;private volatile boolean canceled;
    byte[] fetch(HttpURLConnection configured,String authorization) throws IOException {
        URL url=configured.getURL();boolean tls=configured instanceof HttpsURLConnection;
        SSLSocketFactory factory=tls ? ((HttpsURLConnection)configured).getSSLSocketFactory():null;
        if(!tls && !url.getProtocol().equals("http"))throw new IOException("Invalid DAV transport");
        int port=url.getPort()>0?url.getPort():tls?443:80;
        Socket raw=new Socket(Proxy.NO_PROXY);socket=raw;
        try{
            check();raw.connect(new InetSocketAddress(url.getHost(),port),15000);raw.setSoTimeout(30000);check();
            if(tls){
                SSLSocket secure=(SSLSocket)factory.createSocket(raw,url.getHost(),port,true);socket=secure;
                SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);
                check();secure.startHandshake();
            }
            String body="<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            String host=url.getHost()+(port==(tls?443:80)?"":":"+port);
            String path=url.getFile().isEmpty()?"/":url.getFile();
            String headers="PROPFIND "+path+" HTTP/1.1\r\nHost: "+host+"\r\nAuthorization: "+authorization
                    +"\r\nDepth: 1\r\nContent-Type: application/xml; charset=utf-8\r\nAccept-Encoding: identity\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n";
            OutputStream output=socket.getOutputStream();output.write(headers.getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
            return response(new BufferedInputStream(socket.getInputStream()));
        }finally{close();configured.disconnect();}
    }
    private void check() throws InterruptedIOException{if(canceled || Thread.currentThread().isInterrupted())throw new InterruptedIOException();}
    @Override public void close(){canceled=true;Socket active=socket;if(active!=null)try{active.close();}catch(IOException ignored){}}
    static byte[] response(InputStream input) throws IOException {
        String status=line(input);if(!status.matches("HTTP/1\\.[01] 207(?: .*)?"))throw new IOException(I18n.s("cloud_list_failed"));
        Map<String,String> headers=new HashMap<>();int total=0;
        while(true){String line=line(input);total+=line.length()+2;if(total>65536)throw new IOException("DAV headers too large");if(line.isEmpty())break;
            int colon=line.indexOf(':');if(colon<=0)throw new IOException("Invalid DAV header");
            String key=line.substring(0,colon).toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
            if(headers.put(key,value)!=null && (key.equals("content-length") || key.equals("transfer-encoding")))throw new IOException("Ambiguous DAV length");
        }
        String encoding=headers.get("content-encoding");if(encoding!=null && !encoding.equalsIgnoreCase("identity"))throw new IOException("Unexpected DAV encoding");
        String transfer=headers.get("transfer-encoding"),length=headers.get("content-length");
        if(transfer!=null && length!=null)throw new IOException("Ambiguous DAV framing");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(transfer!=null){
            if(!transfer.equalsIgnoreCase("chunked"))throw new IOException("Unsupported DAV framing");
            while(true){
                String chunk=line(input).split(";",2)[0].trim();long count;
                try{count=Long.parseLong(chunk,16);}catch(NumberFormatException e){throw new IOException("Invalid DAV chunk",e);}
                if(count<0 || count>DavListing.MAX_BYTES-out.size())throw new IOException("DAV listing too large");
                if(count==0){int trailers=0;String trailer;do{trailer=line(input);trailers+=trailer.length()+2;if(trailers>65536)throw new IOException("DAV trailers too large");}while(!trailer.isEmpty());break;}
                copy(input,out,count);if(!line(input).isEmpty())throw new IOException("Invalid DAV chunk ending");
            }
        }else if(length!=null){
            long count;try{count=Long.parseLong(length);}catch(NumberFormatException e){throw new IOException("Invalid DAV length",e);}
            if(count<0 || count>DavListing.MAX_BYTES)throw new IOException("DAV listing too large");copy(input,out,count);
        }else{
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(out.size()+count>DavListing.MAX_BYTES)throw new IOException("DAV listing too large");out.write(buffer,0,count);}
        }
        return out.toByteArray();
    }
    private static void copy(InputStream in,OutputStream out,long left)throws IOException{
        byte[] buffer=new byte[8192];while(left>0){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new EOFException();out.write(buffer,0,n);left-=n;}
    }
    private static String line(InputStream in)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;
        while(bytes.size()<8192){int value=in.read();if(value<0)throw new EOFException();if(previous==13 && value==10){byte[] data=bytes.toByteArray();return new String(data,0,data.length-1,StandardCharsets.ISO_8859_1);}bytes.write(value);previous=value;}
        throw new IOException("DAV line too large");
    }
}
