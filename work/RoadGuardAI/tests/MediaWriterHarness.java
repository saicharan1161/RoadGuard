import com.jiangdg.ausbc.encode.muxer.FragmentedMp4Writer;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class MediaWriterHarness {
    public static void main(String[] args) throws Exception {
        byte[] input=Files.readAllBytes(Paths.get(args[0]));
        ArrayList<byte[]> nals=FragmentedMp4Writer.nalUnits(input);
        byte[] sps=null,pps=null;
        for(byte[] n:nals){ if((n[0]&31)==7)sps=n; if((n[0]&31)==8)pps=n; }
        if(sps==null || pps==null)throw new AssertionError("Missing test configuration");
        FragmentedMp4Writer writer=new FragmentedMp4Writer(new File(args[1]),640,480,sps,pps);
        ArrayList<byte[]> frame=new ArrayList<>();
        int count=0;
        for(byte[] n:nals) {
            if((n[0]&31)==9 && !frame.isEmpty()) {
                write(writer,frame,count++); frame.clear();
            }
            if((n[0]&31)!=9 && (n[0]&31)!=7 && (n[0]&31)!=8) frame.add(n);
        }
        if(!frame.isEmpty())write(writer,frame,count++);
        if(count!=150)throw new AssertionError("Expected 150 frames, got "+count);
        if(writer.getCommittedFragments()!=4)throw new AssertionError("Expected four committed seconds before close");
        if(args.length>2 && args[2].equals("crash")) {
            System.out.println("Abrupt exit with "+writer.getCommittedBytes()+" committed bytes");
            Runtime.getRuntime().halt(0);
        }
        writer.close(); writer.close();
        if(writer.getCommittedFragments()!=5)throw new AssertionError("Final fragment missing");
        if(FragmentedMp4Writer.recover(new File(args[1]))!=0)throw new AssertionError("Recovery changed a complete recording");
        byte[] complete=Files.readAllBytes(Paths.get(args[1]));
        int cursor=0,lastMoof=-1,lastMdat=-1;
        while(cursor+8<=complete.length) {
            int size=ByteBuffer.wrap(complete,cursor,4).getInt();
            String type=new String(complete,cursor+4,4,"US-ASCII");
            if(type.equals("moof"))lastMoof=cursor;
            if(type.equals("mdat"))lastMdat=cursor;
            cursor+=size;
        }
        // Simulate a cut halfway through the final video payload, and through its index.
        for(int cut:new int[]{lastMdat+(complete.length-lastMdat)/2,lastMoof+3,lastMdat+4}) {
            File broken=new File(args[1]+".recovered.mp4");
            Files.write(broken.toPath(),Arrays.copyOf(complete,cut));
            long removed=FragmentedMp4Writer.recover(broken);
            if(removed!=cut-lastMoof || broken.length()!=lastMoof)throw new AssertionError("Wrong recovery boundary");
        }
        System.out.println("PASS 150 frames; Annex-B and AVCC inputs; nonzero buffer offsets; five fragments; idempotent close");
        System.out.println("PASS recovery of cuts in moof header, mdat header and video payload; complete file unchanged");
        // Verify malformed media is rejected and cannot be presented as a successful recording.
        try { FragmentedMp4Writer.nalUnits(new byte[]{0,0,0,8,0x65}); throw new AssertionError("Accepted truncated NAL"); }
        catch(IOException expected) {}
    }
    static void write(FragmentedMp4Writer writer,ArrayList<byte[]> nals,int index)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        boolean key=false;
        for(byte[] nal:nals) {
            if((nal[0]&31)==5)key=true;
            if(index%2==0)out.writeInt(1); else out.writeInt(nal.length);
            out.write(nal);
        }
        byte[] data=bytes.toByteArray();
        ByteBuffer buffer=ByteBuffer.allocate(data.length+13);
        buffer.position(7); buffer.put(data); buffer.limit(7+data.length); buffer.position(7);
        writer.write(buffer,123456789L+index*1000000L/30,key);
        if(buffer.position()!=7)throw new AssertionError("Writer mutated caller buffer");
    }
}
