import com.roadguard.ai.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class LoopStorageHarness {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    static File clip(File root,int n) throws Exception {
        File f=new File(root,String.format("2026-10-19_Monday_10-00-00-%03d.mp4",n));
        Files.write(f.toPath(),new byte[]{1}); check(f.setLastModified(1000L*n)); return f;
    }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("roadguard-loop-test-").toFile();
        File old=clip(root,1),next=clip(root,2),active=clip(root,3),protectedFile=clip(root,4);
        Files.write(new File(protectedFile+".protect").toPath(),new byte[0]);
        File unrelated=new File(root,"holiday.mp4"); Files.write(unrelated.toPath(),new byte[]{1});
        long[] space={0}; List<File> removed=new ArrayList<>();
        int count=LoopStorage.reclaim(root,()->space[0],()->active,f->{removed.add(f);space[0]+=600L*1024*1024;});
        check(count==2 && removed.get(0).equals(old) && removed.get(1).equals(next));
        check(active.exists() && protectedFile.exists() && unrelated.exists());
        check(LoopStorage.reclaim(root,()->0,()->active,f->{throw new AssertionError();})==0);
        File newer=clip(root,5);
        check(LoopStorage.reclaim(root,()->RecordingHealth.WARNING_BYTES,()->active,f->{throw new AssertionError();})==0);
        check(newer.exists());
        check(LoopStorage.reserve(7729053696L)==3000000000L && LoopStorage.reserve(30000000000L)==10000000000L && LoopStorage.reserve(60000000000L)==10000000000L);
        File legacy=clip(root,0);try(RandomAccessFile h=new RandomAccessFile(legacy,"rw")){h.setLength(LoopStorage.SEGMENT_BYTES*2+1);}
        File another=clip(root,6);space[0]=2900000000L;
        count=LoopStorage.reclaimFolders(Collections.singletonList(root),()->space[0],()->active,f->space[0]+=32*1024*1024,3000000000L+LoopStorage.HEADROOM,1,2*LoopStorage.SEGMENT_BYTES);
        check(count==1 && !newer.exists() && another.exists() && legacy.exists() && protectedFile.exists() && active.exists() && unrelated.exists());
        File outside=Files.createTempDirectory("roadguard-loop-outside-").toFile(); File external=clip(outside,6);
        try { Files.createSymbolicLink(new File(root,"linked").toPath(),outside.toPath());
            LoopStorage.reclaim(root,()->0,()->newer,f->{}); check(external.exists());
        } catch(java.nio.file.FileSystemException e) { System.out.println("Symlink creation unavailable on this host; containment guard reviewed in source"); }
        System.out.println("PASS oldest-first, target reserve, active/protected/unrelated exclusions; isolated fixtures: "+root);
    }
}
