package com.roadguard.ai;

import java.io.*;
import java.util.*;

/** Encoded AVC fan-out: no second encoder, no network writes on the camera thread. */
public final class LiveVideoHub {
    public static final int MAX_PACKET=8*1024*1024;
    private final Set<Viewer> viewers=new HashSet<>();
    private final Runnable changed;
    private byte[] format;
    public LiveVideoHub(Runnable changed) { this.changed=changed; }
    public synchronized int count() { return viewers.size(); }
    public synchronized Viewer subscribe() throws IOException {
        if(viewers.size()>=2) throw new IOException("Maximum two live viewers");
        Viewer v=new Viewer(); if(format!=null) v.reset(format); viewers.add(v); changed.run(); return v;
    }
    public synchronized void clear() { format=null; for(Viewer v:viewers) v.reset(null); }
    public synchronized void closeAll() { for(Viewer v:new ArrayList<>(viewers)) v.close(); }
    public synchronized void format(int width,int height,byte[] sps,byte[] pps) throws IOException {
        if(width<=0 || height<=0 || sps==null || sps.length==0) throw new IOException("Missing AVC format");
        byte[] a=annexB(sps), b=pps==null?new byte[0]:annexB(pps);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(width); out.writeInt(height); out.writeInt(a.length); out.write(a); out.writeInt(b.length); out.write(b);
        byte[] next=packet(1,bytes.toByteArray());
        if(Arrays.equals(format,next)) return;
        format=next; for(Viewer v:viewers) v.reset(format);
    }
    public synchronized void frame(byte[] data,long ptsUs,boolean key) throws IOException {
        if(viewers.isEmpty() || format==null) return;
        byte[] nal=annexB(data);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(nal.length+12); DataOutputStream out=new DataOutputStream(bytes);
        out.writeLong(ptsUs); out.writeInt(key?1:0); out.write(nal);
        byte[] packet=packet(2,bytes.toByteArray());
        for(Viewer v:viewers) v.offer(packet,key,format,ptsUs);
    }
    public static byte[] annexB(byte[] data) throws IOException {
        if(data.length>=4 && data[0]==0 && data[1]==0 && (data[2]==1 || (data[2]==0 && data[3]==1))) return data;
        ByteArrayOutputStream out=new ByteArrayOutputStream(); int i=0;
        while(i<data.length) {
            if(data.length-i<4) throw new IOException("Truncated AVC length");
            long n=((data[i]&255L)<<24)|((data[i+1]&255L)<<16)|((data[i+2]&255L)<<8)|(data[i+3]&255L); i+=4;
            if(n<=0 || n>data.length-i) throw new IOException("Invalid AVC length");
            out.write(new byte[]{0,0,0,1}); out.write(data,i,(int)n); i+=(int)n;
        }
        return out.toByteArray();
    }
    private static byte[] packet(int type,byte[] payload) throws IOException {
        if(payload.length>MAX_PACKET) throw new IOException("AVC packet too large");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(payload.length+8); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(type); out.writeInt(payload.length); out.write(payload); return bytes.toByteArray();
    }
    public final class Viewer extends InputStream {
        private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
        private boolean waitingKey=true,closed;
        private int queuedBytes;
        private byte[] current=new byte[0]; private int position;
        private synchronized void reset(byte[] config) { queue.clear(); queuedBytes=0; waitingKey=true; if(config!=null) { queue.add(config); queuedBytes=config.length; } notifyAll(); }
        private synchronized void offer(byte[] data,boolean key,byte[] config,long ptsUs) {
            if(closed) return;
            long oldest=-1;
            for(byte[] packet:queue)if(packet.length>=20 && packet[3]==2) {
                oldest=0;for(int i=8;i<16;i++)oldest=(oldest<<8)|(packet[i]&255L);break;
            }
            // Keep a short live queue, not a growing replay. Never drop dependent
            // H.264 frames individually: rejoin at the next keyframe with config.
            if(queue.size()>=8 || queuedBytes+data.length>1024*1024 ||
                oldest>=0 && (ptsUs-oldest>250000 || ptsUs<oldest)) reset(config);
            if(waitingKey && !key) return;
            waitingKey=false; queue.add(data); queuedBytes+=data.length; notifyAll();
        }
        @Override public int read() throws IOException { byte[] one=new byte[1]; return read(one,0,1)<0?-1:one[0]&255; }
        @Override public synchronized int read(byte[] output,int offset,int length) throws IOException {
            if(length==0) return 0;
            if(closed) return -1;
            if(position==current.length) {
                if(queue.isEmpty()) try { wait(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Viewer stopped",e); }
                if(closed) return -1;
                current=queue.poll(); position=0;
                if(current==null) current=new byte[]{0,0,0,3,0,0,0,0}; // idle heartbeat, not a video frame
                else queuedBytes-=current.length;
            }
            int n=Math.min(length,current.length-position); System.arraycopy(current,position,output,offset,n); position+=n; return n;
        }
        @Override public void close() {
            synchronized(LiveVideoHub.this) { synchronized(this) { if(closed) return; closed=true; queue.clear(); queuedBytes=0; notifyAll(); } viewers.remove(this); }
            changed.run();
        }
    }
}
