package com.roadguard.ai;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java protocol boundaries, exercised by the host regression harness. */
public final class LinkPolicy {
    private LinkPolicy() {}
    public static final class PairingGate {
        private String code = "", secret = "";
        private long expires;
        private int failures;
        public synchronized void open(String code, String secret, long now) {
            this.code=code; this.secret=secret; expires=now+120000L; failures=0;
        }
        public synchronized void close() { code=""; secret=""; expires=0; }
        public synchronized boolean consume(String supplied, long now) {
            if(code.isEmpty() || now>=expires || failures>=5 || supplied==null || supplied.length()>128) return false;
            byte[] bytes=supplied.getBytes(StandardCharsets.UTF_8);
            boolean match=MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),bytes)
                | MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),bytes);
            if(!match) { failures++; return false; }
            close(); return true;
        }
    }
    public static long[] range(String header, long size) {
        if(size<=0) throw new IllegalArgumentException("Empty video");
        if(header==null) return new long[]{0,size-1};
        Matcher m=Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(header);
        if(!m.matches()) throw new IllegalArgumentException("Invalid range");
        long start=Long.parseLong(m.group(1));
        long end=m.group(2).isEmpty()?size-1:Math.min(size-1,Long.parseLong(m.group(2)));
        if(start<0 || start>end || start>=size) throw new IllegalArgumentException("Invalid range");
        return new long[]{start,end};
    }
}
