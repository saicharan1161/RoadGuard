package com.jiangdg.ausbc.render;

/** Small offline fuzzy-rule engine. Abstain on nearly black, monochrome/IR, or bright scenes. */
public final class NightRules {
    public static volatile boolean cameraNightHardware, bypass;
    /** Online unsupervised scene metering: isolate bright headlights from the dominant road region. */
    public static float meter(java.nio.ByteBuffer rgba){return meter(rgba,true);}
    public static float meter(java.nio.ByteBuffer rgba,boolean night){
        java.util.List<org.apache.commons.math3.ml.clustering.DoublePoint> points=new java.util.ArrayList<>(64);
        for(int i=0;i<64;i++){
            int r=rgba.get(i*4)&255,g=rgba.get(i*4+1)&255,b=rgba.get(i*4+2)&255;
            points.add(new org.apache.commons.math3.ml.clustering.DoublePoint(new double[]{
                (.299*r+.587*g+.114*b)/255,(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)))/255.0}));
        }
        org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer<org.apache.commons.math3.ml.clustering.DoublePoint> model=
            new org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer<>(2,4,new org.apache.commons.math3.ml.distance.EuclideanDistance(),new org.apache.commons.math3.random.JDKRandomGenerator(7));
        java.util.List<org.apache.commons.math3.ml.clustering.CentroidCluster<org.apache.commons.math3.ml.clustering.DoublePoint>> groups=model.cluster(points);
        float result=0;float dark=1,bright=0;
        for(org.apache.commons.math3.ml.clustering.CentroidCluster<?> group:groups){
            if(group.getPoints().isEmpty())continue;
            double[] center=group.getCenter().getPoint();
            dark=Math.min(dark,(float)center[0]);bright=Math.max(bright,(float)center[0]);
            result+=group.getPoints().size()/64f*strength((float)center[0],(float)center[1],true,false);
        }
        // Daytime: intervene only on a high-contrast/backlit scene; no blanket brightening.
        return night?result:Math.min(.12f,result)*unit((bright-dark-.30f)/.25f);
    }
    static float unit(float x){return Math.max(0,Math.min(1,x));}
    public static float strength(float luma,float chroma,boolean night,boolean nativeNight){
        if(!night || nativeNight)return 0;
        float dark=unit((0.36f-luma)/0.18f), visible=unit((luma-0.015f)/0.055f);
        float color=unit((chroma-0.022f)/0.05f);
        return 0.32f*Math.min(dark,Math.min(visible,color));
    }
    private NightRules(){}
}
