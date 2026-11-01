package com.roadguard.ai

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** One authenticated H.264 connection, decoded on the phone. No JPEG loop or USB reads. */
class LivePlayerActivity:Activity(),SurfaceHolder.Callback {
    private lateinit var root:FrameLayout
    private lateinit var view:SurfaceView
    private lateinit var label:TextView
    private lateinit var gpsLabel:TextView
    private val gpsWorker=Executors.newSingleThreadExecutor()
    private val gpsBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var gps:org.json.JSONObject?=null
    @Volatile private var gpsReceived=0L
    private var gpsRequested= -15000L
    private lateinit var controls:LinearLayout
    private val generation=AtomicInteger()
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var connection:HttpsURLConnection?=null
    private var showing=true
    private var fill=false
    private var width=640
    private var height=480
    private var resumed=false
    private var readySurface:Surface?=null
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var lastFrameAt=0L
    @Volatile private var streamConnected=false
    private val freshness=object:Runnable { override fun run() {
        if(resumed) {
            val now=android.os.SystemClock.elapsedRealtime()
            if(streamConnected && now-lastFrameAt>3000) { label.visibility=View.VISIBLE; label.text="Connected to car — waiting for camera video" }
            updateLocation(now)
            handler.postDelayed(this,1000)
        }
    } }
    private fun updateLocation(now:Long) {
        val loc=gps
        gpsLabel.text=if(loc!=null && loc.has("latitude")) {
            val age=(loc.optLong("ageMs")+now-gpsReceived).coerceAtLeast(0)
            "${if(loc.optBoolean("fresh") && age<=30000) "CURRENT LOCATION" else "LAST KNOWN — NOT LIVE"} · ${age/1000}s old\n${"%.6f".format(loc.optDouble("latitude"))}, ${"%.6f".format(loc.optDouble("longitude"))} · ±${loc.optDouble("accuracyMeters")}m"
        } else loc?.optString("message","Location unavailable") ?: "Checking car location…"
        if(now-gpsRequested<15000 || !gpsBusy.compareAndSet(false,true))return
        gpsRequested=now
        gpsWorker.execute {
            var c:HttpsURLConnection?=null
            try {
                c=PhoneRoutes.get(this,"/location")
                check(c.responseCode==200)
                val next=org.json.JSONObject(c.inputStream.bufferedReader().use {it.readText()})
                gpsReceived=android.os.SystemClock.elapsedRealtime();gps=next
            } catch(_:Exception) {gps=org.json.JSONObject().put("message","Location unavailable — retrying automatically")}
            finally {c?.disconnect();gpsBusy.set(false)}
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view=SurfaceView(this).apply { holder.addCallback(this@LivePlayerActivity); setOnClickListener { showing=!showing; controls.visibility=if(showing) View.VISIBLE else View.GONE; label.visibility=if(showing) View.VISIBLE else View.GONE; immersive() } }
        root.addView(view,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        label=TextView(this).apply { setTextColor(Color.WHITE); textSize=15f; setPadding(14,12,14,12); setBackgroundColor(0xAA000000.toInt()); text="Connecting to car video…" }
        root.addView(label,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
        gpsLabel=TextView(this).apply {setTextColor(Color.WHITE);textSize=12f;setPadding(10,6,10,6);setBackgroundColor(0xAA000000.toInt())}
        gpsLabel.contentDescription="Car GPS. Tap to open live map"
        gpsLabel.setOnClickListener {startActivity(android.content.Intent(this,LiveMapActivity::class.java))}
        root.addView(gpsLabel,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply {bottomMargin=72})
        controls=LinearLayout(this).apply { setBackgroundColor(0xAA000000.toInt()) }
        controls.addView(Button(this).apply { text="FULL SCREEN"; setOnClickListener { showing=false; controls.visibility=View.GONE; label.visibility=View.GONE; immersive() } })
        controls.addView(Button(this).apply { text="FIT / FILL"; setOnClickListener { fill=!fill; layoutVideo() } })
        controls.addView(Button(this).apply { text="BACK"; setOnClickListener { finish() } })
        root.addView(controls,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutVideo() }
        setContentView(root); immersive()
    }
    private fun immersive() { WindowInsetsControllerCompat(window,window.decorView).apply { systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars()) } }
    private fun layoutVideo() {
        if(root.width==0 || root.height==0) return
        val ratio=width.toDouble()/height
        val byWidth=if(fill) root.width.toDouble()/root.height>ratio else root.width.toDouble()/root.height<ratio
        val w=if(byWidth) root.width else (root.height*ratio).toInt()
        val h=if(byWidth) (root.width/ratio).toInt() else root.height
        if(view.layoutParams.width!=w || view.layoutParams.height!=h) view.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
    }
    override fun surfaceCreated(holder:SurfaceHolder) { readySurface=holder.surface; if(resumed) startVideo(holder.surface) }
    override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,height:Int) {}
    override fun surfaceDestroyed(h:SurfaceHolder) { readySurface=null; stopVideo() }
    private fun status(text:String) { runOnUiThread { if(!isDestroyed) { label.text=text; label.visibility=if(text.startsWith("LIVE") && !showing) View.GONE else View.VISIBLE } } }
    private fun startVideo(surface:Surface) {
        val run=generation.incrementAndGet()
        executor.execute {
            var failures=0
            while(generation.get()==run && !isDestroyed) {
                var codec:MediaCodec?=null
                var c:HttpsURLConnection?=null
                val codecLock=Any()
                val drainStopped=java.util.concurrent.atomic.AtomicBoolean(false)
                val drainError=java.util.concurrent.atomic.AtomicReference<Exception?>(null)
                var outputThread:Thread?=null
                try {
                    val prefs=getSharedPreferences("connection",MODE_PRIVATE)
                    if(prefs.getString("pin",null)==null || prefs.getString("token",null)==null) {
                        status("Not paired. Tap BACK, then scan the car QR or enter its code."); break
                    }
                    c=PhoneRoutes.get(this,"/live.avc")
                    connection=c
                    if(c.responseCode==401) {
                        prefs.edit().remove("pin").remove("token").apply()
                        status("Pairing expired or revoked. Tap BACK and pair again."); break
                    }
                    if(c.responseCode in arrayOf(429,502,503,504))throw java.io.IOException("Car video temporarily unavailable (${c.responseCode})")
                    check(c.responseCode==200) { "Live connection refused (${c.responseCode}). Check matching versions and pairing." }
                    streamConnected=true
                    check(c.getHeaderField("X-RoadGuard-Stream-Version")=="1") { "Update the APK on the car too" }
                    val input=DataInputStream(c.inputStream.buffered())
                    var waitingKey=true
                    var originPts=-1L; var originNs=0L
                    var rendered=0; var measuredAt=android.os.SystemClock.elapsedRealtime()
                    fun drain() = synchronized(codecLock) {
                        val decoder=codec ?: return
                        val info=MediaCodec.BufferInfo()
                        while(true) {
                            val index=decoder.dequeueOutputBuffer(info,0)
                            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                            if(index<0) break
                            if(originPts<0 || kotlin.math.abs((System.nanoTime()-originNs)/1000-(info.presentationTimeUs-originPts))>300000) {
                                originPts=info.presentationTimeUs; originNs=System.nanoTime()+30000000L
                            }
                            decoder.releaseOutputBuffer(index,originNs+(info.presentationTimeUs-originPts)*1000)
                            rendered++
                            failures=0
                            val now=android.os.SystemClock.elapsedRealtime()
                            lastFrameAt=now
                            if(now-measuredAt>=1000) { status("LIVE · ${width}×${height} · ${rendered*1000/(now-measuredAt)} decoded FPS · tap video for controls"); rendered=0; measuredAt=now }
                        }
                    }
                    // Output must keep draining even while the network reader is
                    // waiting for the next packet. Otherwise decoded frames sit idle.
                    outputThread=Thread({
                        try {
                            while(!drainStopped.get() && generation.get()==run) {
                                drain();Thread.sleep(4)
                            }
                        } catch(e:Exception) {if(!drainStopped.get())drainError.set(e)}
                    },"RoadGuard-live-output").also {it.start()}
                    while(generation.get()==run) {
                        drainError.get()?.let {throw it}
                        drain()
                        val type=input.readInt(); val size=input.readInt()
                        require(size in 0..LiveVideoHub.MAX_PACKET) { "Invalid live packet" }
                        val bytes=ByteArray(size); input.readFully(bytes)
                        val packet=DataInputStream(ByteArrayInputStream(bytes))
                        when(type) {
                            1 -> {
                                val w=packet.readInt(); val h=packet.readInt()
                                require(w in 1..4096 && h in 1..4096)
                                fun csd():ByteArray { val n=packet.readInt(); require(n in 0..65536 && n<=packet.available()); return ByteArray(n).also { packet.readFully(it) } }
                                val sps=csd(); val pps=csd(); require(sps.isNotEmpty() && packet.available()==0)
                                synchronized(codecLock) {
                                codec?.release(); codec=null
                                val format=MediaFormat.createVideoFormat("video/avc",w,h).apply { setByteBuffer("csd-0",ByteBuffer.wrap(sps)); if(pps.isNotEmpty()) setByteBuffer("csd-1",ByteBuffer.wrap(pps)) }
                                codec=MediaCodec.createDecoderByType("video/avc").also { it.configure(format,surface,null,0); it.start() }
                                waitingKey=true; originPts=-1
                                }
                                runOnUiThread { width=w; height=h; layoutVideo() }
                                status("Waiting for camera keyframe…")
                            }
                            2 -> {
                                require(size>=13)
                                val pts=packet.readLong(); val key=packet.readInt()==1
                                synchronized(codecLock) {
                                val decoder=codec ?: return@synchronized
                                if(waitingKey && !key) return@synchronized
                                drain()
                                var index=decoder.dequeueInputBuffer(10000)
                                if(index<0) { drain(); index=decoder.dequeueInputBuffer(10000) }
                                if(index<0) { decoder.flush(); waitingKey=true; originPts=-1; return@synchronized }
                                val buffer=decoder.getInputBuffer(index) ?: error("Decoder input unavailable")
                                buffer.clear(); require(buffer.remaining()>=size-12) { "Decoder input too small" }
                                buffer.put(bytes,12,size-12); decoder.queueInputBuffer(index,0,size-12,pts,0); waitingKey=false
                                drain()
                                }
                            }
                            3 -> { require(size==0); drain() }
                            else -> error("Unknown live packet")
                        }
                    }
                } catch(e:Exception) {
                    failures++
                    if(generation.get()==run) {
                        if(!ConnectionRecovery.temporary(e)) {
                            status("Live recovery stopped: ${e.message}. Tap BACK to reconnect or pair again."); break
                        }
                        status("Car/video unavailable — reconnecting automatically in ${ConnectionRecovery.liveDelayMillis(failures)/1000}s. Tap BACK to stop.")
                    }
                }
                finally {
                    drainStopped.set(true);outputThread?.interrupt()
                    try {outputThread?.join(1000)} catch(_:InterruptedException) {Thread.currentThread().interrupt()}
                    streamConnected=false
                    synchronized(codecLock) {try {codec?.release()} catch(_:Exception) {};codec=null}
                    c?.disconnect(); if(connection===c) connection=null
                }
                var remaining=ConnectionRecovery.liveDelayMillis(failures)
                while(generation.get()==run && remaining>0) {
                    try {Thread.sleep(minOf(remaining,250))} catch(_:InterruptedException) {return@execute}
                    remaining-=250
                }
            }
        }
    }
    private fun stopVideo() { generation.incrementAndGet(); val old=connection; connection=null; if(old!=null) Thread { old.disconnect() }.start() }
    override fun onResume() { super.onResume(); resumed=true; readySurface?.let { startVideo(it) }; handler.post(freshness); immersive() }
    override fun onPause() { resumed=false; stopVideo(); handler.removeCallbacks(freshness); super.onPause() }
    override fun onDestroy() { stopVideo(); handler.removeCallbacksAndMessages(null); executor.shutdownNow();gpsWorker.shutdownNow(); super.onDestroy() }
}
