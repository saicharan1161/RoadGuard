package com.roadguard.ai;

import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

/** TLS 1.2 with ephemeral RSA-authenticated key exchange; no RSA decryption key permission needed. */
public final class TlsServerContext {
    public static SSLContext create(final PrivateKey key,final X509Certificate cert) throws GeneralSecurityException {
        X509ExtendedKeyManager manager=new X509ExtendedKeyManager() {
            public PrivateKey getPrivateKey(String alias) { return "roadguard".equals(alias)?key:null; }
            public X509Certificate[] getCertificateChain(String alias) { return "roadguard".equals(alias)?new X509Certificate[]{cert}:null; }
            public String[] getServerAliases(String type,Principal[] issuers) { return "RSA".equals(type)?new String[]{"roadguard"}:null; }
            public String chooseServerAlias(String type,Principal[] issuers,Socket socket) { return "RSA".equals(type)?"roadguard":null; }
            public String[] getClientAliases(String type,Principal[] issuers) { return null; }
            public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket) { return null; }
        };
        SSLContext ssl=SSLContext.getInstance("TLSv1.2");
        ssl.init(new KeyManager[]{manager},null,new SecureRandom());
        return ssl;
    }
    public static void configure(SSLServerSocket socket) {
        List<String> supported=Arrays.asList(socket.getSupportedCipherSuites());
        List<String> enabled=new ArrayList<>();
        for(String cipher:new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256","TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"}) {
            if(supported.contains(cipher)) enabled.add(cipher);
        }
        if(enabled.isEmpty()) throw new IllegalStateException("Firmware does not support the required TLS 1.2 ECDHE-RSA/AES-GCM cipher");
        socket.setEnabledProtocols(new String[]{"TLSv1.2"});
        socket.setEnabledCipherSuites(enabled.toArray(new String[0]));
        socket.setUseClientMode(false);
        socket.setNeedClientAuth(false);
        socket.setWantClientAuth(false);
    }
    private TlsServerContext() {}
}
