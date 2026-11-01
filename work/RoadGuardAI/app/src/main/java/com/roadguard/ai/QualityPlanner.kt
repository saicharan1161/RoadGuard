package com.roadguard.ai

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import com.jiangdg.ausbc.camera.bean.CameraRequest

object QualityPlanner {
    data class Profile(val width:Int,val height:Int,val format:CameraRequest.PreviewFormat) {
        override fun toString()="${width}x${height} ${format.name.removePrefix("FORMAT_")}"
    }
    data class Plan(val profiles:List<Profile>,val explanation:String)
    fun hot(context:Context)=Build.VERSION.SDK_INT>=29 &&
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
    fun plan(context:Context):Plan {
        val memory=ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val ram=memory.totalMem/1048576; val available=memory.availMem/1048576
        val cores=Runtime.getRuntime().availableProcessors()
        val encoders=try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc",true) } &&
                (if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."))
        } } catch(_:Exception) { emptyList() }
        fun supports(w:Int,h:Int)=encoders.any { info -> try {
            val caps=info.getCapabilitiesForType("video/avc")
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && caps.videoCapabilities?.areSizeAndRateSupported(w,h,30.0)==true
        } catch(_:Exception) { false } }
        val hd=supports(1280,720); val full=supports(1920,1080)
        val tier=AdaptiveQuality.tier(ram,available,cores,memory.lowMemory,hot(context),hd,full)
        val mjpeg=CameraRequest.PreviewFormat.FORMAT_MJPEG
        val profiles=mutableListOf<Profile>()
        if(tier>=2) profiles+=Profile(1920,1080,mjpeg)
        if(tier>=1 && hd) profiles+=Profile(1280,720,mjpeg)
        profiles+=Profile(640,480,mjpeg)
        profiles+=Profile(640,480,CameraRequest.PreviewFormat.FORMAT_YUYV)
        return Plan(profiles,"Auto: ${ram}MB RAM, ${available}MB available, $cores cores, API ${Build.VERSION.SDK_INT}; hardware AVC30: 720p=$hd, 1080p=$full. Ceiling ${profiles.first()}. Camera negotiation and sustained performance may lower it.")
    }
}
