import com.roadguard.ai.LocationPolicy;
public class LocationPolicyHarness {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        check(LocationPolicy.MIN_DISTANCE_METERS==0f);
        check(LocationPolicy.ageMillis(100_000_000_000L,99_000_000_000L)==1000);
        check(LocationPolicy.fresh(true,30000));check(!LocationPolicy.fresh(true,30001));
        check(!LocationPolicy.fresh(false,0));
        check(LocationPolicy.ageMillis(100,101)==Long.MAX_VALUE);
        check(LocationPolicy.ageMillis(100,0)==Long.MAX_VALUE);
        long old=LocationPolicy.ageMillis(60000_000_000_000L,210_000_000_000L);
        check(!LocationPolicy.fresh(true,old));
        System.out.println("PASS stationary updates, monotonic age, stale fixes, stopped service and invalid timestamps");
    }
}
