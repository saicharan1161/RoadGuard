import com.roadguard.ai.LiveVideoHub;
import java.io.*;
import java.util.Arrays;

public class LiveVideoHarness {
    static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    static byte[] packet(DataInputStream in,int type) throws Exception {
        check(in.readInt()==type); int length=in.readInt(); check(length>=0 && length<=LiveVideoHub.MAX_PACKET);
        byte[] data=new byte[length]; in.readFully(data); return data;
    }
    public static void main(String[] args) throws Exception {
        LiveVideoHub hub=new LiveVideoHub(()->{});
        byte[] sps={0,0,0,1,0x67,0x42,0,0x1e}; byte[] pps={0,0,0,1,0x68,1};
        byte[] key={0,0,0,1,0x65,1}; byte[] delta={0,0,0,1,0x41,2};
        hub.format(640,480,sps,pps);
        LiveVideoHub.Viewer first=hub.subscribe(); DataInputStream a=new DataInputStream(first);
        DataInputStream format=new DataInputStream(new ByteArrayInputStream(packet(a,1)));
        check(format.readInt()==640 && format.readInt()==480);
        hub.frame(delta,1,false); hub.frame(key,2,true);
        DataInputStream sample=new DataInputStream(new ByteArrayInputStream(packet(a,2)));
        check(sample.readLong()==2 && sample.readInt()==1);
        byte[] nal=new byte[6]; sample.readFully(nal); check(Arrays.equals(nal,key));
        LiveVideoHub.Viewer second=hub.subscribe();
        try { hub.subscribe(); throw new AssertionError(); } catch(IOException expected) {}
        for(int i=0;i<10000;i++) hub.frame(delta,i+3,false); // slow viewers must not grow unbounded
        hub.frame(key,10004,true);
        packet(a,1); packet(a,2); // resynchronization format then fresh keyframe, no delta backlog
        hub.format(1280,720,sps,pps); format=new DataInputStream(new ByteArrayInputStream(packet(a,1)));
        check(format.readInt()==1280 && format.readInt()==720);
        check(Arrays.equals(LiveVideoHub.annexB(new byte[]{0,0,0,2,0x65,1}),key));
        // A low-FPS source must be bounded by time, not just queue frame count.
        hub.frame(key,1000000,true);hub.frame(delta,1100000,false);hub.frame(delta,1300000,false);
        hub.frame(key,1400000,true);packet(a,1);
        sample=new DataInputStream(new ByteArrayInputStream(packet(a,2)));check(sample.readLong()==1400000);
        try { LiveVideoHub.annexB(new byte[]{0,0,0,9,0x65}); throw new AssertionError(); } catch(IOException expected) {}
        first.close(); check(first.read()==-1); second.close(); check(hub.count()==0);
        hub.subscribe(); hub.closeAll(); check(hub.count()==0);
        System.out.println("PASS live framing, codec format, keyframe-only join, 10,000-frame slow-consumer recovery, two-viewer limit, format changes, AVCC conversion and disconnect cleanup");
    }
}
