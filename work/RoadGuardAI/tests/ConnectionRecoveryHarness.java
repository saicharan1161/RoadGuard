import com.roadguard.ai.ConnectionRecovery;
public class ConnectionRecoveryHarness {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    public static void main(String[] args) {
        check(ConnectionRecovery.delayMillis(1)==1000);
        check(ConnectionRecovery.delayMillis(2)==2000);
        check(ConnectionRecovery.delayMillis(3)==4000);
        check(ConnectionRecovery.delayMillis(4)==-1);
        check(ConnectionRecovery.delayMillis(0)==-1);
        check(ConnectionRecovery.delayMillis(Integer.MAX_VALUE)==-1);
        check(ConnectionRecovery.liveDelayMillis(1)==1000);
        check(ConnectionRecovery.liveDelayMillis(3)==4000);
        check(ConnectionRecovery.liveDelayMillis(6)==30000);
        check(ConnectionRecovery.liveDelayMillis(Integer.MAX_VALUE)==30000);
        check(ConnectionRecovery.temporary(new java.net.SocketTimeoutException()));
        check(ConnectionRecovery.temporary(new java.net.ConnectException()));
        check(ConnectionRecovery.temporary(new java.io.EOFException()));
        check(!ConnectionRecovery.temporary(new javax.net.ssl.SSLHandshakeException("bad certificate")));
        check(!ConnectionRecovery.temporary(new java.io.IOException(new java.security.cert.CertificateException())));
        check(!ConnectionRecovery.temporary(new SecurityException()));
        check(!ConnectionRecovery.temporary(new IllegalStateException("wrong version or revoked token")));
        check(!ConnectionRecovery.temporary(new InterruptedException()));
        System.out.println("PASS recovery: bounded backoff, temporary transport failures, security/version/auth stop, cancellation");
    }
}
