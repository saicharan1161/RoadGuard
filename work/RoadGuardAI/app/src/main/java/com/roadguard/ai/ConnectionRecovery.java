package com.roadguard.ai;

/** Retry only temporary transport failures. Never replay a one-use pairing request. */
public final class ConnectionRecovery {
    /** Background waits stay bounded; callers must stop at their visible-session boundary. */
    public static long liveDelayMillis(int failures) {
        return Math.min(30000L,1000L << Math.min(5,Math.max(0,failures-1)));
    }
    public static long delayMillis(int failedAttempts) {
        if(failedAttempts<1 || failedAttempts>3) return -1;
        return 1000L << (failedAttempts-1);
    }
    public static boolean temporary(Throwable error) {
        boolean io=false;
        for(int i=0;error!=null && i<8;i++,error=error.getCause()) {
            if(error instanceof javax.net.ssl.SSLException || error instanceof java.security.GeneralSecurityException
                || error instanceof SecurityException || error instanceof InterruptedException) return false;
            if(error instanceof java.io.IOException) io=true;
        }
        return io;
    }
    private ConnectionRecovery() {}
}
