package bin.mt.apksign.key;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class BksSignatureKey implements SignatureKey {
    private X509Certificate certificate;
    private PrivateKey privateKey;

    public BksSignatureKey(String path, String storePassword, String alias, String aliasPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("bks");
        keyStore.load(new FileInputStream(path), storePassword.toCharArray());
        certificate = (X509Certificate) keyStore.getCertificate(alias);
        privateKey = (PrivateKey) keyStore.getKey(alias, aliasPassword.toCharArray());
    }

    public BksSignatureKey(InputStream is, String storePassword, String alias, String aliasPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("bks");
        keyStore.load(is, storePassword.toCharArray());
        certificate = (X509Certificate) keyStore.getCertificate(alias);
        privateKey = (PrivateKey) keyStore.getKey(alias, aliasPassword.toCharArray());
    }

    @Override
    public X509Certificate getCertificate() {
        return certificate;
    }

    @Override
    public PrivateKey getPrivateKey() {
        return privateKey;
    }
}
