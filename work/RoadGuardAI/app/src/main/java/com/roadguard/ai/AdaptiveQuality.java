package com.roadguard.ai;

/** Deterministic, conservative starting ceiling, not a hardware benchmark. */
public final class AdaptiveQuality {
    private AdaptiveQuality() {}
    public static int tier(long ramMb, long availableMb, int cores, boolean lowMemory,
                           boolean hot, boolean supports720, boolean supports1080) {
        if(lowMemory || hot || availableMb<256) return 0;
        if(ramMb>=3500 && availableMb>=768 && cores>=4 && supports1080) return 2;
        if(ramMb>=1500 && availableMb>=384 && cores>=2 && supports720) return 1;
        return 0;
    }
    public static boolean shouldLower(long ageMs, int badSeconds, boolean canLower) {
        return canLower && ageMs>=30000 && badSeconds>=20;
    }
}
