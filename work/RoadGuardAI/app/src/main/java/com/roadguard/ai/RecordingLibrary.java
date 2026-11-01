package com.roadguard.ai;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** App-owned USB folders only. Existing footage is never moved or renamed. */
public final class RecordingLibrary {
    public static File folder(File appRoot) { return new File(appRoot,"Car Dash Cam Recordings"); }
    public static List<File> list(File appRoot) throws IOException {
        List<File> result=new ArrayList<>();
        collect(folder(appRoot),folder(appRoot),0,result);
        File legacy=new File(appRoot,"Recordings");
        collect(legacy,legacy,0,result);
        Collections.sort(result,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        return result;
    }
    private static void collect(File root,File dir,int depth,List<File> result) throws IOException {
        if(depth>3 || !dir.exists()) return;
        File[] entries=dir.listFiles(); if(entries==null) return;
        for(File file:entries) {
            if(!file.getCanonicalPath().equals(file.getAbsolutePath()) ||
                !file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)) continue;
            if(file.isDirectory()) collect(root,file,depth+1,result);
            else if(file.getName().endsWith(".mp4") && file.length()>0) result.add(file);
        }
    }
    public static boolean protectedClip(File file) { return new File(file.getPath()+".protect").exists(); }
    public static void protect(File appRoot,File file,boolean keep) throws IOException {
        if(!list(appRoot).contains(file.getCanonicalFile())) throw new IOException("Recording no longer available");
        File marker=new File(file.getPath()+".protect");
        if(keep) {
            if(!marker.exists() && !marker.createNewFile()) throw new IOException("USB could not protect recording");
        } else if(marker.exists() && !marker.delete()) throw new IOException("USB could not remove protection");
    }
    private RecordingLibrary() {}
}
