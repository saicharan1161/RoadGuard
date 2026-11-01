using System;
using System.IO;

// Small MPEG-TS packetizer: preserve car PTS instead of making ffplay guess raw-AVC FPS.
// No transcoding, no saved-video changes. MPEG-TS video PID 256, PMT PID 4096.
public sealed class LiveTransport {
    readonly Stream output;
    readonly byte[] packet=new byte[188];
    readonly int[] continuity=new int[8192];
    byte[] config=new byte[0];
    bool waitingKey=true,discontinuity=true;
    public LiveTransport(Stream target) {output=new BufferedStream(target,65536);}
    public void Configure(byte[] sps,byte[] pps) {
        if(sps==null||sps.Length==0||sps.Length>65536||pps==null||pps.Length>65536)throw new InvalidDataException("Invalid AVC configuration");
        config=new byte[sps.Length+pps.Length];Buffer.BlockCopy(sps,0,config,0,sps.Length);Buffer.BlockCopy(pps,0,config,sps.Length,pps.Length);
        waitingKey=true;discontinuity=true;
    }
    public void Frame(byte[] data,int offset,int length,long ptsUs,bool key) {
        if(offset<0||length<=0||length>8*1024*1024||offset>data.Length-length||ptsUs<0)throw new InvalidDataException("Invalid AVC frame");
        if(config.Length==0||waitingKey&&!key)return;
        waitingKey=false;
        long pts=((ptsUs/1000)*90+(ptsUs%1000)*90/1000)&0x1ffffffffL;
        if(key)Tables();
        using(var pes=new MemoryStream()) {
            byte[] header={0,0,1,0xe0,0,0,0x80,0x80,5,
                (byte)(0x21|((pts>>29)&14)),(byte)(pts>>22),(byte)(((pts>>14)&254)|1),(byte)(pts>>7),(byte)(((pts<<1)&254)|1)};
            pes.Write(header,0,header.Length);
            if(key)pes.Write(config,0,config.Length);
            pes.Write(data,offset,length);
            Pack(256,pes.ToArray(),true,pts,key);
            output.Flush();discontinuity=false;
        }
    }
    void Tables() {
        Table(0,new byte[]{0x00,0xb0,0x0d,0,1,0xc1,0,0,0,1,0xf0,0});
        Table(4096,new byte[]{0x02,0xb0,0x12,0,1,0xc1,0,0,0xe1,0,0xf0,0,0x1b,0xe1,0,0xf0,0});
    }
    void Table(int pid,byte[] section) {
        uint crc=0xffffffff;
        foreach(byte value in section) {
            crc^=(uint)value<<24;
            for(int bit=0;bit<8;bit++)crc=(crc&0x80000000)!=0?(crc<<1)^0x04c11db7:crc<<1;
        }
        byte[] payload=new byte[section.Length+5];Buffer.BlockCopy(section,0,payload,1,section.Length);
        for(int i=0;i<4;i++)payload[section.Length+1+i]=(byte)(crc>>(24-8*i));
        Pack(pid,payload,true,-1,false);
    }
    void Pack(int pid,byte[] data,bool start,long clock,bool key) {
        int pos=0;bool first=true;
        while(pos<data.Length) {
            for(int i=0;i<packet.Length;i++)packet[i]=255;
            packet[0]=0x47;packet[1]=(byte)((pid>>8)|(first&&start?0x40:0));packet[2]=(byte)pid;
            bool pcr=first&&clock>=0;int count=Math.Min(pcr?176:184,data.Length-pos);
            bool adapt=pcr||count<184;
            packet[3]=(byte)((adapt?0x30:0x10)|(continuity[pid]++&15));int begin=4;
            if(adapt) {
                int size=183-count;packet[4]=(byte)size;begin+=1+size;
                if(size>0)packet[5]=(byte)((pcr?0x10:0)|(key&&first?0x40:0)|(pcr&&discontinuity?0x80:0));
                if(pcr){packet[6]=(byte)(clock>>25);packet[7]=(byte)(clock>>17);packet[8]=(byte)(clock>>9);packet[9]=(byte)(clock>>1);packet[10]=(byte)(((clock&1)<<7)|0x7e);packet[11]=0;}
            }
            Buffer.BlockCopy(data,pos,packet,begin,count);output.Write(packet,0,packet.Length);pos+=count;first=false;
        }
    }
}
