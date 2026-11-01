package com.roadguard.ai;
/** Monotonic freshness: never re-date cached fixes, including stationary samples. */
public final class LocationPolicy {
    public static final long UPDATE_MS=5000, FRESH_MS=30000, RETRY_MS=60000;
    public static final float MIN_DISTANCE_METERS=0f;
    public static long ageMillis(long now,long fix) {
        return fix<=0 || fix>now ? Long.MAX_VALUE : (now-fix)/1000000L;
    }
    public static boolean fresh(boolean running,long age) {return running && age>=0 && age<=FRESH_MS;}
    private LocationPolicy() {}
}
