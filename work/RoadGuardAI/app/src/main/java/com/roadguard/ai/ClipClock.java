package com.roadguard.ai;
import java.text.*;
import java.util.*;
/** Compare the wall-clock labels written by the car, not the phone's time zone. */
public final class ClipClock {
    public static long start(String file) {
        if(!file.matches("\\d{4}-\\d{2}-\\d{2}_[A-Za-z]+_\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.mp4"))return -1;
        return parse(file.substring(0,10)+" "+file.substring(file.lastIndexOf('_')+1,file.length()-4),"yyyy-MM-dd HH-mm-ss-SSS");
    }
    public static long day(String date){return parse(date,"yyyy-MM-dd");}
    private static long parse(String s,String pattern){
        SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.ENGLISH);f.setTimeZone(TimeZone.getTimeZone("UTC"));f.setLenient(false);
        ParsePosition p=new ParsePosition(0);Date d=f.parse(s,p);return d!=null&&p.getIndex()==s.length()?d.getTime():-1;
    }
    public static long[] overlap(long recording,long length,long from,long until){
        if(recording<0||length<=0||from>=until)return null;
        long a=Math.max(0,from-recording),b=Math.min(length,until-recording);
        return a<b?new long[]{a,b}:null;
    }
}
