import com.roadguard.ai.ClipClock;
import com.jiangdg.ausbc.render.NightRules;
import java.nio.ByteBuffer;
public class CctvHarness {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static float scene(int r,int g,int b){
        ByteBuffer data=ByteBuffer.allocateDirect(256);
        for(int i=0;i<64;i++)data.put((byte)r).put((byte)g).put((byte)b).put((byte)255);
        return NightRules.meter(data);
    }
    public static void main(String[] a){
        long day=ClipClock.day("2026-11-01"),start=ClipClock.start("2026-10-31_Saturday_23-59-58-000.mp4");
        long[] r=ClipClock.overlap(start,5000,day,day+60000);
        check(r[0]==2000 && r[1]==5000);
        check(ClipClock.day("2026-02-30")==-1 && ClipClock.start("song.mp4")==-1);
        check(ClipClock.overlap(day,60000,day+60000,day+120000)==null);
        check(ClipClock.overlap(day,1000,day,day)==null);
        check(scene(48,27,21)>0 && scene(48,27,21)<=0.32f && scene(80,80,80)==0 && scene(230,200,170)==0 && scene(0,0,0)==0);
        check(NightRules.strength(.12f,.21f,false,false)==0 && NightRules.strength(.12f,.21f,true,true)==0);
        System.out.println("PASS local car clock, midnight overlap, empty and invalid ranges; bounded offline clustering, neutral/IR, black and daylight bypass");
    }
}
