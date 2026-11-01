package com.roadguard.ai;

/** Health reflects committed writes, not a guarantee that every frame is playable. */
public final class RecordingHealth {
    public static final long RESERVE_BYTES=256L*1024*1024;
    public static final long WARNING_BYTES=1024L*1024*1024;
    public static boolean mustStop(long available) { return available>=0 && available<=RESERVE_BYTES; }
    public static String label(boolean recording,boolean saving,long fragments,long ageMs) {
        if(saving) return "Saving — keep USB connected";
        if(!recording) return "NOT RECORDING";
        if(fragments<=0) return "Waiting for first saved video";
        if(ageMs>15000) return "WRITE STALLED";
        if(ageMs>5000) return "WARNING: saved video delayed";
        return "Video writes active";
    }
    private RecordingHealth() {}
}
