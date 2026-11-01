import com.roadguard.ai.TlsServerContext;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

public class TlsServerHarness {
    public static void main(String[] args) throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(InputStream in=new FileInputStream(args[0])) { store.load(in,"test-only-password".toCharArray()); }
        PrivateKey key=(PrivateKey)store.getKey("test","test-only-password".toCharArray());
        X509Certificate cert=(X509Certificate)store.getCertificate("test");
        SSLContext server=TlsServerContext.create(key,cert);
        byte[] pin=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        for(boolean correct:new boolean[]{true,false}) {
            SSLServerSocket listener=(SSLServerSocket)server.getServerSocketFactory().createServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
            TlsServerContext.configure(listener); listener.setSoTimeout(8000);
            if(!Arrays.equals(listener.getEnabledProtocols(),new String[]{"TLSv1.2"})) throw new AssertionError();
            for(String cipher:listener.getEnabledCipherSuites()) if(!cipher.startsWith("TLS_ECDHE_RSA_WITH_AES_")) throw new AssertionError();
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread t=new Thread(()-> {
                try(SSLSocket socket=(SSLSocket)listener.accept()) {
                    socket.setSoTimeout(8000); socket.startHandshake();
                    int b=socket.getInputStream().read();
                    socket.getOutputStream().write(b); socket.getOutputStream().flush();
                } catch(Throwable e) { failure.set(e); }
            }); t.start();
            SSLContext client=SSLContext.getInstance("TLSv1.2");
            client.init(null,new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers(){ return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c,String a) throws CertificateException { throw new CertificateException(); }
                public void checkServerTrusted(X509Certificate[] c,String a) throws CertificateException {
                    try { if(!correct || !Arrays.equals(pin,MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()))) throw new CertificateException("Pin rejected"); }
                    catch(NoSuchAlgorithmException e){throw new CertificateException(e);}
                }
            }},new SecureRandom());
            boolean accepted=false;
            try(SSLSocket socket=(SSLSocket)client.getSocketFactory().createSocket(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort())) {
                socket.setSoTimeout(8000); socket.startHandshake(); accepted=true;
                socket.getOutputStream().write(73); socket.getOutputStream().flush();
                if(socket.getInputStream().read()!=73) throw new AssertionError();
                System.out.println("PASS pinned TLS loopback: "+socket.getSession().getProtocol()+" "+socket.getSession().getCipherSuite());
            } catch(SSLHandshakeException e) { if(correct) throw e; }
            finally { listener.close(); t.join(9000); }
            if(t.isAlive() || accepted!=correct || (correct && failure.get()!=null)) throw new AssertionError("Handshake expectation failed",failure.get());
            if(!correct) System.out.println("PASS mismatched certificate pin rejected");
        }
    }
}
