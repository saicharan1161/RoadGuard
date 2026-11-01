package com.roadguard.ai;
import java.io.*;
/** Pace only explicitly tagged background backups, on their HTTP worker, never on the recorder. */
public final class BackupReader extends FilterInputStream {
    private long due;
    public BackupReader(InputStream source){super(source);due=System.nanoTime();}
    @Override public int read(byte[] data,int off,int len)throws IOException{
        if(len==0)return 0;
        long now=System.nanoTime(),delay=due-now;
        if(delay>0)try {Thread.sleep(delay/1000000L,(int)(delay%1000000L));}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Backup cancelled");}
        int n=in.read(data,off,Math.min(32768,len));
        if(n>0)due=Math.max(due,System.nanoTime())+n*1000000000L/1048576L;
        return n;
    }
}
