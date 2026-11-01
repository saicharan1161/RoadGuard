package com.roadguard.ai;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Deletes only timestamp-named RoadGuard MP4s beneath the explicitly supplied recordings folder. */
public final class LoopStorage {
    public interface Space { long getAsLong(); }
    public interface Active { File get(); }
    public interface Deleted { void accept(File file); }
    public static final long SEGMENT_BYTES=32L*1024*1024;
    public static final long HEADROOM=2*SEGMENT_BYTES;
    /** Decimal GB as printed on the drive; includes common 32/64/128 GB drives. */
    public static long reserve(long capacity) {
        return capacity>=24000000000L ? 10000000000L : Math.min(3000000000L,Math.max(0L,capacity/2));
    }
    public static int reclaim(File root,Space available,Active active,Deleted deleted) throws IOException {
        return reclaimFolders(Collections.singletonList(root),available,active,deleted);
    }
    public static int reclaimFolders(List<File> roots,Space available,Active active,Deleted deleted) throws IOException {
        return reclaimFolders(roots,available,active,deleted,RecordingHealth.WARNING_BYTES,Integer.MAX_VALUE,Long.MAX_VALUE);
    }
    public static int reclaimFolders(List<File> roots,Space available,Active active,Deleted deleted,long target,int limit,long maxClip) throws IOException {
        if(available.getAsLong()>=target) return 0;
        List<File> bases=new ArrayList<>();
        List<File> candidates=new ArrayList<>();
        for(File root:roots) {
            File base=root.getCanonicalFile(); if(!base.equals(root.getAbsoluteFile()))continue;
            bases.add(base); collect(base,base,0,candidates);
        }
        Collections.sort(candidates,(a,b)-> {
            int order=Long.compare(a.lastModified(),b.lastModified());
            return order!=0?order:a.getName().compareTo(b.getName());
        });
        int count=0;
        for(File file:candidates) {
            if(count>=limit || available.getAsLong()>=target) break;
            File current=active.get();
            if(current!=null && file.getCanonicalFile().equals(current.getCanonicalFile())) continue;
            boolean contained=false;
            for(File base:bases) if(inside(base,file)) { contained=true; break; }
            if(!contained || !file.isFile() || file.length()>maxClip || new File(file.getPath()+".protect").exists()) continue;
            if(file.delete()) { count++; deleted.accept(file); }
        }
        return count;
    }
    private static boolean inside(File root,File file) throws IOException {
        return file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator);
    }
    private static void collect(File root,File dir,int depth,List<File> out) throws IOException {
        if(depth>3) return;
        File[] files=dir.listFiles(); if(files==null) return;
        for(File f:files) {
            if(!inside(root,f) || !f.getAbsolutePath().equals(f.getCanonicalPath())) continue;
            if(f.isDirectory()) collect(root,f,depth+1,out);
            else if(f.getName().matches("\\d{4}-\\d{2}-\\d{2}_[A-Za-z]+_\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.mp4")) out.add(f);
        }
    }
    private LoopStorage() {}
}
