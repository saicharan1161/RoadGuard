package com.jiangdg.ausbc.encode.muxer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Video-only AVC/ISO-BMFF writer. Each committed fragment has its own sample table.
 * No Android dependencies, so the exact production writer can be tested on a host JVM.
 * Supports monotonic presentation timestamps (AVC without B frames).
 */
public final class FragmentedMp4Writer implements Closeable {
    private final FileOutputStream output;
    private final BufferedOutputStream burst;
    private final ArrayList<Sample> samples = new ArrayList<>();
    private long origin = -1, lastPts = -1;
    private volatile int sequence = 1;
    private int pendingBytes;
    private boolean closed, failed;
    private volatile long committedBytes;
    private final long indexOffset;
    private static final int INDEX_SIZE=32768;
    private final ArrayList<long[]> groups=new ArrayList<>();
    private static final long SECOND = 1_000_000L;
    private static final int MAX_FRAGMENT_BYTES = 8 * 1024 * 1024;
    private static final class Sample {
        final byte[] data;
        final long pts;
        final boolean key;
        Sample(byte[] data, long pts, boolean key) { this.data=data; this.pts=pts; this.key=key; }
    }

    public FragmentedMp4Writer(File file, int width, int height, byte[] csd0, byte[] csd1) throws IOException {
        byte[] sps = null, pps = null;
        for (byte[] csd : new byte[][]{csd0, csd1}) {
            if (csd == null) continue;
            for (byte[] nal : nalUnits(csd)) {
                if (nal.length == 0) continue;
                if ((nal[0] & 31) == 7) sps = nal;
                if ((nal[0] & 31) == 8) pps = nal;
            }
        }
        if (sps == null || sps.length < 4 || pps == null) throw new IOException("Encoder did not supply AVC SPS/PPS");
        byte[] header = join(box("ftyp", ascii("iso6"), ints(1), ascii("iso6isomavc1mp41rgai")),
                movie(width, height, sps, pps));
        output = new FileOutputStream(file);
        burst=new BufferedOutputStream(output,128*1024);
        indexOffset=header.length;
        try { output.write(header);output.write(box("free",new byte[INDEX_SIZE-8])); output.getFD().sync(); committedBytes=header.length+INDEX_SIZE; }
        catch (IOException e) { output.close(); throw e; }
    }

    public synchronized void write(ByteBuffer buffer, long presentationUs, boolean key) throws IOException {
        if (closed || failed) throw new IOException("Recording writer is closed");
        byte[] raw = new byte[buffer.remaining()];
        buffer.duplicate().get(raw);
        ArrayList<byte[]> nals = nalUnits(raw);
        ByteArrayOutputStream packed = new ByteArrayOutputStream(raw.length + 16);
        DataOutputStream data = new DataOutputStream(packed);
        boolean hasSlice = false;
        for (byte[] nal : nals) {
            if (nal.length == 0) continue;
            int type = nal[0] & 31;
            if (type == 7 || type == 8 || type == 9) continue;
            if (type == 5) key = true;
            if (type == 1 || type == 5) hasSlice = true;
            data.writeInt(nal.length); data.write(nal);
        }
        if (!hasSlice) return;
        if (origin < 0) { if (!key) return; origin = presentationUs; }
        long pts = presentationUs - origin;
        if (pts < 0 || (lastPts >= 0 && pts <= lastPts)) throw new IOException("Non-monotonic AVC timestamp");
        if (!samples.isEmpty() && ((key && !samples.get(samples.size()-1).key) || pts - samples.get(0).pts >= SECOND || pendingBytes + packed.size() > MAX_FRAGMENT_BYTES)) {
            commit(pts);
        }
        byte[] bytes = packed.toByteArray();
        if (bytes.length > MAX_FRAGMENT_BYTES) throw new IOException("Encoded frame exceeds safety limit");
        samples.add(new Sample(bytes, pts, key)); pendingBytes += bytes.length; lastPts = pts;
    }

    private void commit(long nextPts) throws IOException {
        if (samples.isEmpty()) return;
        try {
            byte[] moof = fragment(nextPts, 0);
            moof = fragment(nextPts, moof.length + 8);
            burst.write(moof);
            burst.write(ints(pendingBytes + 8)); burst.write(ascii("mdat"));
            for (Sample sample : samples) burst.write(sample.data);
            burst.flush();
            output.getFD().sync();
            committedBytes += moof.length + 8L + pendingBytes;
            if(groups.isEmpty() || (samples.get(0).key && groups.size()<2000))groups.add(new long[2]);
            long[] group=groups.get(groups.size()-1);
            group[0]+=moof.length+8L+pendingBytes;group[1]+=nextPts-samples.get(0).pts;
            samples.clear(); pendingBytes = 0; sequence++;
        } catch (IOException e) { failed = true; throw e; }
    }

    private byte[] fragment(long nextPts, int dataOffset) throws IOException {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(table);
        out.writeInt(samples.size()); out.writeInt(dataOffset);
        for (int i=0;i<samples.size();i++) {
            Sample s = samples.get(i);
            long end = i+1 < samples.size() ? samples.get(i+1).pts : nextPts;
            long duration = end - s.pts;
            if (duration <= 0 || duration > Integer.MAX_VALUE) throw new IOException("Invalid sample duration");
            out.writeInt((int)duration); out.writeInt(s.data.length);
            out.writeInt(s.key ? 0x02000000 : 0x01010000);
        }
        return box("moof", full("mfhd", 0, 0, ints(sequence)),
                box("traf", full("tfhd", 0, 0x020000, ints(1)),
                        full("tfdt", 1, 0, longs(samples.get(0).pts)),
                        full("trun", 0, 0x000701, table.toByteArray())));
    }

    public long getCommittedBytes() { return committedBytes; }
    public int getCommittedFragments() { return sequence - 1; }

    /** Discard only an incomplete final transaction in a file created by this writer.
     * Foreign MP4 files and already indexed complete files are never changed.
     * An interrupted file with our reserved index slot receives an index for its surviving fragments.
     * Call before recording starts, never on a currently open recording.
     * @return number of incomplete trailing bytes removed
     */
    public static long recover(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file,"r")) {
            if(f.length()<36)return 0;
            byte[] first=new byte[36]; f.readFully(first);
            if(ByteBuffer.wrap(first).getInt()!=36 ||
                    !"ftyp".equals(new String(first,4,4,StandardCharsets.US_ASCII)) ||
                    !"rgai".equals(new String(first,32,4,StandardCharsets.US_ASCII)))return 0;
        }
        try (RandomAccessFile f = new RandomAccessFile(file,"rw")) {
            long length=f.length(), pos=36, good=36;
            boolean movie=false, fragment=false,indexed=false;long slot=-1,fragAt=0,firstFrag=-1;
            while(pos+8<=length) {
                f.seek(pos); long size=((long)f.readInt())&0xffffffffL;
                byte[] kind=new byte[4]; f.readFully(kind);
                String type=new String(kind,StandardCharsets.US_ASCII);
                if(size<8 || size>length-pos)break;
                if(!movie && type.equals("moov")) {movie=true; good=pos+size;}
                else if(movie && !fragment && (type.equals("free") || type.equals("sidx"))){
                    if(slot<0 && firstFrag<0 && (type.equals("sidx") || size==INDEX_SIZE)){slot=pos;indexed=type.equals("sidx");}
                    good=pos+size;
                }
                else if(movie && !fragment && type.equals("moof")){fragment=true;fragAt=pos;if(firstFrag<0)firstFrag=fragAt;}
                else if(fragment && type.equals("mdat")) {fragment=false;good=pos+size;}
                else return 0; // Do not modify unfamiliar structures.
                pos+=size;
            }
            if(!movie)return 0;
            if(good<length){f.setLength(good);f.getFD().sync();}
            if(slot>=0 && firstFrag==slot+INDEX_SIZE && (!indexed || good!=length)){
                // Rebuild the seek index from complete metadata boxes only. Never read/modify video payloads.
                ArrayList<long[]> rebuilt=new ArrayList<>();long at=firstFrag;
                while(at<good){
                    f.seek(at);int moof=f.readInt();if(moof<100||moof>256*1024||at+moof+8>good)return length-good;
                    f.seek(at+72);if(f.readInt()!=0x7472756e || f.readInt()!=0x701)return length-good;
                    int count=f.readInt();if(count<=0||88L+count*12L!=moof)return length-good;
                    f.readInt();long duration=0;boolean sync=false;
                    for(int i=0;i<count;i++){duration+=f.readInt()&0xffffffffL;f.readInt();int flags=f.readInt();if(i==0)sync=flags==0x02000000;}
                    int dataSize=f.readInt();if(dataSize<8||f.readInt()!=0x6d646174||at+moof+dataSize>good)return length-good;
                    if(rebuilt.isEmpty() || sync&&rebuilt.size()<2000)rebuilt.add(new long[2]);
                    long[] g=rebuilt.get(rebuilt.size()-1);g[0]+=moof+(long)dataSize;g[1]+=duration;at+=moof+(long)dataSize;
                }
                if(!rebuilt.isEmpty())publishIndex(f.getChannel(),slot,rebuilt);
            }
            return length-good;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        try {
            if (!failed && !samples.isEmpty()) {
                long step = samples.size() > 1 ? lastPts - samples.get(samples.size()-2).pts : 33333L;
                commit(lastPts + Math.max(1L, Math.min(SECOND, step)));
            }
            if(!failed && !groups.isEmpty())publishIndex(output.getChannel(),indexOffset,groups);
        } finally { closed = true; output.close(); }
    }
    private static void publishIndex(java.nio.channels.FileChannel channel,long indexOffset,List<long[]> groups)throws IOException{
                int size=32+12*groups.size(),padding=INDEX_SIZE-size;
                ByteBuffer body=ByteBuffer.allocate(INDEX_SIZE);
                body.putInt(size).put(ascii("sidx")).putInt(0).putInt(1).putInt(1000000).putInt(0).putInt(padding).putShort((short)0).putShort((short)groups.size());
                for(long[] g:groups) {
                    if(g[0]>Integer.MAX_VALUE||g[1]>0xffffffffL)return; // leave playable unindexed file
                    body.putInt((int)g[0]).putInt((int)g[1]).putInt(0x90000000);
                }
                body.putInt(padding).put(ascii("free"));
                // Publish the new box header last; power loss while writing the body leaves the original free box valid.
                body.position(8);body.limit(INDEX_SIZE);channel.position(indexOffset+8);
                while(body.hasRemaining())channel.write(body);channel.force(false);
                body.position(0);body.limit(8);channel.position(indexOffset);
                while(body.hasRemaining())channel.write(body);channel.force(false);
    }

    // Accept MediaCodec's Annex-B or four-byte length-prefixed AVC output.
    public static ArrayList<byte[]> nalUnits(byte[] input) throws IOException {
        ArrayList<byte[]> result = new ArrayList<>();
        if (input.length == 0) return result;
        int first = startCode(input, 0);
        if (first == 0) {
            int at = first;
            while (at >= 0) {
                int len = input[at+2] == 1 ? 3 : 4;
                int end = startCode(input, at + len);
                int limit = end < 0 ? input.length : end;
                if (limit > at+len) result.add(Arrays.copyOfRange(input, at+len, limit));
                at = end;
            }
            return result;
        }
        int p=0;
        while (p+4 <= input.length) {
            int n = ByteBuffer.wrap(input,p,4).getInt();
            if (n<=0 || n>input.length-p-4) break;
            result.add(Arrays.copyOfRange(input,p+4,p+4+n)); p+=4+n;
        }
        if (p == input.length) return result;
        // Codec-specific data may contain a single NAL without framing.
        if ((input[0]&31)==7 || (input[0]&31)==8) {
            result.clear(); result.add(input); return result;
        }
        throw new IOException("Malformed AVC NAL framing");
    }

    private static int startCode(byte[] b, int from) {
        for (int i=from;i+2<b.length;i++) {
            if (b[i]==0 && b[i+1]==0 && (b[i+2]==1 || (i+3<b.length && b[i+2]==0 && b[i+3]==1))) return i;
        }
        return -1;
    }

    private static byte[] movie(int w, int h, byte[] sps, byte[] pps) throws IOException {
        byte[] matrix = ints(0x10000,0,0,0,0x10000,0,0,0,0x40000000);
        byte[] mvhd = full("mvhd",1,0, longs(0,0),ints(1000000),longs(0),ints(0x10000),shorts(0x100,0),
                ints(0,0),matrix,new byte[24],ints(2));
        byte[] tkhd = full("tkhd",1,7,longs(0,0),ints(1,0),longs(0),ints(0,0),shorts(0,0,0,0),matrix,ints(w<<16,h<<16));
        byte[] mdhd = full("mdhd",1,0,longs(0,0),ints(1000000),longs(0),shorts(0x55c4,0));
        byte[] hdlr = full("hdlr",0,0,ints(0),ascii("vide"),ints(0,0,0),ascii("RoadGuard video\0"));
        byte[] avcc = box("avcC",new byte[]{1,sps[1],sps[2],sps[3],(byte)255,(byte)225},
                shorts(sps.length),sps,new byte[]{1},shorts(pps.length),pps);
        byte[] avc1 = box("avc1",new byte[6],shorts(1),new byte[16],shorts(w,h),
                ints(0x00480000,0x00480000,0),shorts(1),new byte[32],shorts(24,65535),avcc);
        byte[] stbl = box("stbl",full("stsd",0,0,ints(1),avc1),full("stts",0,0,ints(0)),
                full("stsc",0,0,ints(0)),full("stsz",0,0,ints(0,0)),full("stco",0,0,ints(0)));
        byte[] dinf = box("dinf",full("dref",0,0,ints(1),full("url ",0,1)));
        byte[] minf = box("minf",full("vmhd",0,1,shorts(0,0,0,0)),dinf,stbl);
        byte[] trak = box("trak",tkhd,box("mdia",mdhd,hdlr,minf));
        return box("moov",mvhd,trak,box("mvex",full("trex",0,0,ints(1,1,0,0,0))));
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] ints(int... values) { ByteBuffer b=ByteBuffer.allocate(values.length*4); for(int v:values)b.putInt(v); return b.array(); }
    private static byte[] longs(long... values) { ByteBuffer b=ByteBuffer.allocate(values.length*8); for(long v:values)b.putLong(v); return b.array(); }
    private static byte[] shorts(int... values) { ByteBuffer b=ByteBuffer.allocate(values.length*2); for(int v:values)b.putShort((short)v); return b.array(); }
    private static byte[] join(byte[]... values) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); for(byte[] v:values)b.write(v); return b.toByteArray(); }
    private static byte[] box(String name, byte[]... payload) throws IOException { byte[] body=join(payload); return join(ints(body.length+8),ascii(name),body); }
    private static byte[] full(String name,int version,int flags,byte[]... payload) throws IOException { return box(name,ints((version<<24)|flags),join(payload)); }
}
