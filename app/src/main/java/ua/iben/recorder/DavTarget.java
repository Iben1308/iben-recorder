package ua.iben.recorder;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** A user-selected secure folder, or an explicitly allowed private-IP HTTP folder. No discovery, redirects or credentials in URLs. */
public final class DavTarget {
    public final String folder;
    public final String username;
    public final String key;
    public DavTarget(String address, String username) { this(address,username,false); }
    public DavTarget(String address, String username,boolean allowLocalHttp) {
        try {
            URI uri = new URI(address.trim());
            String scheme=uri.getScheme()==null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http") && allowLocalHttp && privateIpv4(uri.getHost())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || address.length() > 2048 || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            for (String part : uri.getPath().split("/")) if (part.equals("..") || part.equals(".")
                    || part.indexOf('\\') >= 0 || hasControl(part)) throw new IllegalArgumentException();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || path.equals("/")) throw new IllegalArgumentException();
            if (!path.endsWith("/")) path += "/";
            String authority = uri.getHost().toLowerCase(Locale.ROOT);
            if (uri.getPort() > 0 && uri.getPort() != (scheme.equals("https") ? 443 : 80)) authority += ":" + uri.getPort();
            folder = new URI(scheme+"://" + authority + path).toASCIIString();
            this.username = username.trim();
            if (this.username.isEmpty() || this.username.length() > 256 || this.username.indexOf(':') >= 0
                    || hasControl(this.username)) throw new IllegalArgumentException();
            key = hex(MessageDigest.getInstance("SHA-256").digest((folder + "\n" + this.username).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException(I18n.s("webdav_address_error"));
        }
    }
    /** Numeric RFC1918 only: no DNS, public IPs, alternate numeric forms or proxy routing. */
    static boolean privateIpv4(String host) {
        if(host==null || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))return false;
        String[] parts=host.split("\\.");int[] bytes=new int[4];
        for(int i=0;i<4;i++) {
            if(parts[i].length()>3 || parts[i].length()>1 && parts[i].charAt(0)=='0')return false;
            try {bytes[i]=Integer.parseInt(parts[i]);}catch(NumberFormatException e){return false;}
            if(bytes[i]>255)return false;
        }
        return bytes[0]==10 || bytes[0]==172 && bytes[1]>=16 && bytes[1]<=31 || bytes[0]==192 && bytes[1]==168;
    }
    public URL file(String name) throws java.io.IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || hasControl(name))
            throw new java.io.IOException("Некоректне ім’я віддаленого файла");
        return new URL(folder + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
    }
    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return true;
        return false;
    }
    static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte v : bytes) b.append(String.format(Locale.ROOT, "%02x", v & 255));
        return b.toString();
    }
}
