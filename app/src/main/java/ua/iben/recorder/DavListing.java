package ua.iben.recorder;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Untrusted WebDAV XML: a bounded, one-folder catalogue; no DTD, external entities or foreign paths. */
final class DavListing {
    static final int MAX_BYTES=8*1024*1024,MAX_FILES=20000;
    static final class Remote {
        final String name,etag;final long size,modified;
        Remote(String name,long size,String etag,long modified){this.name=name;this.size=size;this.etag=etag;this.modified=modified;}
    }
    static List<Remote> parse(byte[] bytes,DavTarget target) throws IOException {
        if(bytes.length>MAX_BYTES)throw new IOException(I18n.s("cloud_list_failed"));
        String xml=new String(bytes,StandardCharsets.UTF_8),upper=xml.toUpperCase(Locale.ROOT);
        if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))throw new IOException("Unsafe WebDAV XML");
        try {
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
            // Some Android XML providers lack these features; the UTF-8 Reader and DTD rejection above remain mandatory.
            for(String feature:new String[]{"http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities"})
                try{factory.setFeature(feature,false);}catch(javax.xml.parsers.ParserConfigurationException ignored){}
            javax.xml.parsers.DocumentBuilder builder=factory.newDocumentBuilder();
            builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("External entity");});
            Document document=builder.parse(new InputSource(new StringReader(xml)));
            Element root=document.getDocumentElement();
            if(!"DAV:".equals(root.getNamespaceURI()) || !"multistatus".equals(root.getLocalName()))throw new IOException("Invalid WebDAV listing");
            Map<String,Remote> found=new LinkedHashMap<>();URI folder=URI.create(target.folder);
            NodeList responses=root.getChildNodes();
            for(int i=0;i<responses.getLength();i++){
                if(!(responses.item(i) instanceof Element))continue;Element response=(Element)responses.item(i);
                if(!is(response,"response"))continue;
                Element href=child(response,"href");if(href==null)continue;
                String name=directChild(folder,href.getTextContent().trim());if(name==null || !name.toLowerCase(Locale.ROOT).endsWith(".m4a"))continue;
                target.file(name);long size=-1,modified=0;String etag=null;boolean collection=false;
                NodeList blocks=response.getChildNodes();
                for(int b=0;b<blocks.getLength();b++)if(blocks.item(b) instanceof Element && is((Element)blocks.item(b),"propstat")){
                    Element block=(Element)blocks.item(b),status=child(block,"status"),prop=child(block,"prop");
                    if(status==null || !status.getTextContent().trim().matches("HTTP/[^ ]+ 200(?: .*)?") || prop==null)continue;
                    Element length=child(prop,"getcontentlength"),tag=child(prop,"getetag"),time=child(prop,"getlastmodified"),type=child(prop,"resourcetype");
                    if(length!=null)try{size=Long.parseLong(length.getTextContent().trim());}catch(NumberFormatException ignored){}
                    if(tag!=null)etag=tag.getTextContent().trim();
                    if(time!=null)try{modified=ZonedDateTime.parse(time.getTextContent().trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(RuntimeException ignored){}
                    collection|=type!=null && child(type,"collection")!=null;
                }
                if(collection)continue;
                if(size<=0)throw new IOException(I18n.s("cloud_list_failed")); // An incomplete listing must not hide existing cloud copies.
                if(found.put(name,new Remote(name,size,etag,modified))!=null)throw new IOException("Duplicate WebDAV resource");
                if(found.size()>MAX_FILES)throw new IOException(I18n.s("cloud_list_failed"));
            }
            return new ArrayList<>(found.values());
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException(I18n.s("cloud_list_failed"),e);}
    }
    private static boolean is(Element e,String local){return "DAV:".equals(e.getNamespaceURI()) && local.equals(e.getLocalName());}
    private static Element child(Element e,String name){NodeList nodes=e.getChildNodes();for(int i=0;i<nodes.getLength();i++)if(nodes.item(i) instanceof Element && is((Element)nodes.item(i),name))return (Element)nodes.item(i);return null;}
    static String directChild(URI base,String href){
        try{
            URI raw=new URI(href),u=base.resolve(raw);
            if(u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null || !base.getScheme().equalsIgnoreCase(u.getScheme())
                    || !base.getHost().equalsIgnoreCase(u.getHost()) || port(base)!=port(u))return null;
            String path=u.getPath(),prefix=base.getPath();if(!path.startsWith(prefix))return null;
            String name=path.substring(prefix.length());if(name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))return null;
            for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))return null;
            return name;
        }catch(Exception ignored){return null;}
    }
    private static int port(URI uri){return uri.getPort()<0 ? "https".equalsIgnoreCase(uri.getScheme())?443:80 : uri.getPort();}
    static long[] timing(String name,long modified){
        long start=modified>0?modified:System.currentTimeMillis(),duration=0;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})\\((\\d{2,})_(\\d{2})\\)").matcher(name);
        if(match.find())try{
            start=LocalDateTime.parse(match.group(1),DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss",Locale.ROOT)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long minutes=Long.parseLong(match.group(2)),seconds=Long.parseLong(match.group(3));
            if(minutes<=100000 && seconds<60)duration=(minutes*60+seconds)*1000;
        }catch(RuntimeException ignored){}
        return new long[]{start,duration};
    }
}
