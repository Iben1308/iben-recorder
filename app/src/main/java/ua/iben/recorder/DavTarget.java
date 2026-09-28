package ua.iben.recorder;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** A user-selected HTTPS folder. No discovery, redirects or credentials in URLs. */
public final class DavTarget {
    public final String folder;
    public final String username;
    public final String key;
    public DavTarget(String address, String username) {
        try {
            URI uri = new URI(address.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || address.length() > 2048 || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            for (String part : uri.getPath().split("/")) if (part.equals("..") || part.equals(".")
                    || part.indexOf('\\') >= 0 || hasControl(part)) throw new IllegalArgumentException();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || path.equals("/")) throw new IllegalArgumentException();
            if (!path.endsWith("/")) path += "/";
            String authority = uri.getHost().toLowerCase(Locale.ROOT);
            if (uri.getPort() > 0 && uri.getPort() != 443) authority += ":" + uri.getPort();
            folder = new URI("https://" + authority + path).toASCIIString();
            this.username = username.trim();
            if (this.username.isEmpty() || this.username.length() > 256 || this.username.indexOf(':') >= 0
                    || hasControl(this.username)) throw new IllegalArgumentException();
            key = hex(MessageDigest.getInstance("SHA-256").digest((folder + "\n" + this.username).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Вкажіть повну HTTPS WebDAV-адресу папки та ім’я користувача");
        }
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
