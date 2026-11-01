import com.roadguard.ai.AdaptiveQuality;
public class AdaptiveQualityHarness {
    static void check(boolean v) { if(!v) throw new AssertionError(); }
    public static void main(String[] args) {
        check(AdaptiveQuality.tier(1024,400,4,false,false,true,true)==0);
        check(AdaptiveQuality.tier(2048,700,4,false,false,true,true)==1);
        check(AdaptiveQuality.tier(4096,1500,8,false,false,true,true)==2);
        check(AdaptiveQuality.tier(8192,2500,8,false,false,true,false)==1);
        check(AdaptiveQuality.tier(8192,2500,8,false,false,false,false)==0);
        check(AdaptiveQuality.tier(8192,200,8,false,false,true,true)==0);
        check(AdaptiveQuality.tier(8192,2500,8,true,false,true,true)==0);
        check(AdaptiveQuality.tier(8192,2500,8,false,true,true,true)==0);
        check(!AdaptiveQuality.shouldLower(29000,40,true));
        check(!AdaptiveQuality.shouldLower(60000,19,true));
        check(!AdaptiveQuality.shouldLower(60000,20,false));
        check(AdaptiveQuality.shouldLower(60000,20,true));
        System.out.println("PASS adaptive tiers: low/mid/high hardware, encoder restrictions, memory/thermal pressure, warmup and sustained-only downgrade");
    }
}
