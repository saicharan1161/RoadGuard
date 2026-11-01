using System;
using System.IO;
using System.Collections.Generic;
public static class LiveTransportHarness {
    static byte[] Annex(byte[] n) {byte[] b=new byte[n.Length+4];b[3]=1;Buffer.BlockCopy(n,0,b,4,n.Length);return b;}
    static List<byte[]> Nals(byte[] b) {
        var list=new List<byte[]>();int begin=-1;
        for(int i=0;i<b.Length-2;i++) {
            int n=b[i]==0&&b[i+1]==0?(b[i+2]==1?3:i+3<b.Length&&b[i+2]==0&&b[i+3]==1?4:0):0;
            if(n==0)continue;
            if(begin>=0){byte[] nal=new byte[i-begin];Buffer.BlockCopy(b,begin,nal,0,nal.Length);list.Add(nal);}
            begin=i+n;i+=n-1;
        }
        if(begin>=0){byte[] nal=new byte[b.Length-begin];Buffer.BlockCopy(b,begin,nal,0,nal.Length);list.Add(nal);}
        return list;
    }
    static void Send(LiveTransport mux,List<byte[]> frame,int index,int fps) {
        using(var bytes=new MemoryStream()) {
            bool key=false;
            foreach(byte[] n in frame){if((n[0]&31)==5)key=true;byte[] a=Annex(n);bytes.Write(a,0,a.Length);}
            byte[] data=bytes.ToArray();mux.Frame(data,0,data.Length,index*1000000L/fps,key);
        }
    }
    public static void Main(string[] args) {
        var nals=Nals(File.ReadAllBytes(args[0]));byte[] sps=null,pps=null;
        foreach(byte[] n in nals){if((n[0]&31)==7)sps=Annex(n);if((n[0]&31)==8)pps=Annex(n);}
        using(var test=new MemoryStream()) {
            var mux=new LiveTransport(test);mux.Configure(sps,pps);mux.Frame(new byte[]{0,0,0,1,0x41},0,5,0,false);
            if(test.Length!=0)throw new Exception("Delta frame leaked before keyframe");
            try{mux.Frame(new byte[]{1},0,1,-1,true);throw new Exception("Invalid timestamp accepted");}catch(InvalidDataException){}
        }
        using(var file=File.Create(args[1])) {
            var mux=new LiveTransport(file);mux.Configure(sps,pps);var frame=new List<byte[]>();int count=0,fps=int.Parse(args[2]);
            foreach(byte[] n in nals) {
                int type=n[0]&31;
                if(type==9&&frame.Count>0){Send(mux,frame,count++,fps);frame.Clear();}
                if(type!=9&&type!=7&&type!=8)frame.Add(n);
            }
            if(frame.Count>0)Send(mux,frame,count++,fps);
            if(count!=150)throw new Exception("Wrong frame count "+count);
        }
        byte[] result=File.ReadAllBytes(args[1]);if(result.Length%188!=0)throw new Exception("TS size");
        for(int i=0;i<result.Length;i+=188)if(result[i]!=0x47)throw new Exception("TS sync");
        Console.WriteLine("PASS 150 timestamped MPEG-TS frames, keyframe join and packet structure at "+args[2]+" FPS");
    }
}
