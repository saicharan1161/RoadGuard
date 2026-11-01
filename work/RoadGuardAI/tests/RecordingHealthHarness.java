import com.roadguard.ai.RecordingHealth;
public class RecordingHealthHarness {
    static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(!RecordingHealth.mustStop(-1));
        check(RecordingHealth.mustStop(0));
        check(RecordingHealth.mustStop(RecordingHealth.RESERVE_BYTES));
        check(!RecordingHealth.mustStop(RecordingHealth.RESERVE_BYTES+1));
        check(!RecordingHealth.mustStop(128L*1024*1024*1024));
        check(RecordingHealth.label(false,false,100,0).equals("NOT RECORDING"));
        check(RecordingHealth.label(true,true,100,0).startsWith("Saving"));
        check(RecordingHealth.label(true,false,0,0).startsWith("Waiting"));
        check(RecordingHealth.label(true,false,1,5000).equals("Video writes active"));
        check(RecordingHealth.label(true,false,1,5001).startsWith("WARNING"));
        check(RecordingHealth.label(true,false,1,15001).equals("WRITE STALLED"));
        System.out.println("PASS: reserve boundaries, unknown/large storage, saving/stopped/first-write/delayed/stalled health");
    }
}
