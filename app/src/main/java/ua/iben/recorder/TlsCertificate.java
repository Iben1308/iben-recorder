package ua.iben.recorder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Trust one user-imported public server certificate on this connection only.
 * HTTPS still uses its default hostname verifier. No global trust or hostname overrides.
 */
final class TlsCertificate {
    static String importPublic(byte[] bytes) throws Exception {
        if(bytes.length==0 || bytes.length>65536 || new String(bytes,StandardCharsets.US_ASCII).contains("PRIVATE KEY"))
            throw new CertificateException(I18n.s("certificate_invalid"));
        java.util.Collection<? extends java.security.cert.Certificate> parsed=CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(bytes));
        if(parsed.size()!=1)throw new CertificateException(I18n.s("certificate_invalid"));
        X509Certificate certificate=(X509Certificate)parsed.iterator().next();certificate.checkValidity();
        return Base64.getEncoder().encodeToString(certificate.getEncoded());
    }
    static X509Certificate decode(String encoded) throws CertificateException {
        try {
            return (X509Certificate)CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        } catch(IllegalArgumentException e) { throw new CertificateException(e); }
    }
    static String fingerprint(String encoded) throws Exception {
        return DavTarget.hex(MessageDigest.getInstance("SHA-256").digest(decode(encoded).getEncoded()));
    }
    static SSLSocketFactory socketFactory(String encoded) throws IOException {
        try {
            X509Certificate certificate=decode(encoded);certificate.checkValidity();
            byte[] expected=certificate.getEncoded();
            KeyStore anchors=KeyStore.getInstance(KeyStore.getDefaultType());
            anchors.load(null,null);anchors.setCertificateEntry("selected-server",certificate);
            TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(anchors);
            X509TrustManager validator=null;
            for(TrustManager candidate:factory.getTrustManagers())
                if(candidate instanceof X509TrustManager)validator=(X509TrustManager)candidate;
            if(validator==null)throw new CertificateException(I18n.s("certificate_invalid"));
            final X509TrustManager platform=validator;
            X509TrustManager manager=new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain,String authType) throws CertificateException { throw new CertificateException(); }
                @Override public void checkServerTrusted(X509Certificate[] chain,String authType) throws CertificateException {
                    if(chain==null || chain.length==0 || !Arrays.equals(expected,chain[0].getEncoded()))
                        throw new CertificateException(I18n.s("certificate_changed"));
                    chain[0].checkValidity();
                    // Retain platform certificate/key-usage validation with only the imported trust anchor.
                    platform.checkServerTrusted(chain,authType);
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{certificate}; }
            };
            SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{manager},null);
            return context.getSocketFactory();
        } catch(Exception e) { throw new IOException(I18n.s("certificate_invalid"),e); }
    }
}
