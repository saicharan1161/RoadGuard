import com.roadguard.ai.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RecordingLibraryHarness {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    static File clip(File dir,int n) throws Exception {
        dir.mkdirs();
        File f=new File(dir,String.format("2026-10-20_Tuesday_10-00-00-%03d.mp4",n));
        Files.write(f.toPath(),new byte[]{1,2,3}); check(f.setLastModified(n*1000L)); return f;
    }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("roadguard-library-").toFile().getCanonicalFile();
        File legacy=new File(root,"Recordings"),current=RecordingLibrary.folder(root);
        File old=clip(legacy,1),kept=clip(current,2),active=clip(current,3);
        File unrelated=clip(new File(root,"Diagnostics"),4);
        check(RecordingLibrary.list(root).equals(Arrays.asList(active,kept,old)));
        RecordingLibrary.protect(root,kept,true);
        RecordingLibrary.protect(root,kept,true);
        check(RecordingLibrary.protectedClip(kept));
        long[] space={0};
        check(LoopStorage.reclaimFolders(Arrays.asList(current,legacy),()->space[0],()->active,
            f->{check(f.equals(old));space[0]=RecordingHealth.WARNING_BYTES;})==1);
        check(kept.exists() && active.exists() && unrelated.exists());
        RecordingLibrary.protect(root,kept,false); check(!RecordingLibrary.protectedClip(kept));
        boolean rejected=false;
        try { RecordingLibrary.protect(root,unrelated,true); } catch(IOException e) { rejected=true; }
        check(rejected && !RecordingLibrary.protectedClip(unrelated));
        File missing=new File(current,"missing.mp4"); rejected=false;
        try { RecordingLibrary.protect(root,missing,true); } catch(IOException e) { rejected=true; }
        check(rejected);
        System.out.println("PASS dual-folder listing, order, protection/unprotection, cleanup exclusions and missing/outside rejection");
    }
}
