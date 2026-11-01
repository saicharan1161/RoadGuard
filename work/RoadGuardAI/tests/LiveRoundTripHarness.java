import com.roadguard.ai.LiveVideoHub;
import com.jiangdg.ausbc.encode.muxer.FragmentedMp4Writer;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class LiveRoundTripHarness {
    static byte[] annex(byte[] nal) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); b.write(new byte[]{0,0,0,1}); b.write(nal); return b.toByteArray(); }
    public static void main(String[] args) throws Exception {
        ArrayList<byte[]> nals=FragmentedMp4Writer.nalUnits(Files.readAllBytes(Paths.get(args[0])));
        byte[] sps=null,pps=null;
        for(byte[] n:nals) { if((n[0]&31)==7)sps=annex(n); if((n[0]&31)==8)pps=annex(n); }
        LiveVideoHub hub=new LiveVideoHub(()->{}); hub.format(640,480,sps,pps);
        LiveVideoHub.Viewer viewer=hub.subscribe(); DataInputStream input=new DataInputStream(viewer);
        byte[] header=LiveVideoHarness.packet(input,1);
        DataInputStream config=new DataInputStream(new ByteArrayInputStream(header)); config.readInt(); config.readInt();
        try(OutputStream output=Files.newOutputStream(Paths.get(args[1]))) {
            for(int i=0;i<2;i++) { int size=config.readInt(); byte[] nal=new byte[size]; config.readFully(nal); output.write(nal); }
            ArrayList<byte[]> frame=new ArrayList<>(); int count=0;
            for(byte[] n:nals) {
                int type=n[0]&31;
                if(type==9 && !frame.isEmpty()) { send(hub,input,output,frame,count++); frame.clear(); }
                if(type!=9 && type!=7 && type!=8)frame.add(n);
            }
            if(!frame.isEmpty())send(hub,input,output,frame,count++);
            if(count!=150)throw new AssertionError("Frame count "+count);
        } finally { viewer.close(); }
        System.out.println("PASS 150 real encoded frames through production live fan-out protocol, alternating Annex-B/AVCC inputs");
    }
    static void send(LiveVideoHub hub,DataInputStream input,OutputStream output,List<byte[]> nals,int index) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream data=new DataOutputStream(bytes); boolean key=false;
        for(byte[] nal:nals) { if((nal[0]&31)==5)key=true; data.writeInt(index%2==0?1:nal.length); data.write(nal); }
        long pts=index*1000000L/30;
        hub.frame(bytes.toByteArray(),pts,key);
        byte[] packet=LiveVideoHarness.packet(input,2); DataInputStream body=new DataInputStream(new ByteArrayInputStream(packet));
        if(body.readLong()!=pts || body.readInt()!=(key?1:0))throw new AssertionError("Timing/keyframe changed");
        output.write(packet,12,packet.length-12);
    }
}
