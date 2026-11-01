package com.roadguard.ai

import android.app.Activity
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.*
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Uses certificate-pinned HTTP range reads; no temporary movie copy in phone storage. */
class RemotePlayerActivity:Activity(),SurfaceHolder.Callback {
    private var player:MediaPlayer?=null
    private lateinit var status:TextView
    private var prepared=false
    private lateinit var layout:LinearLayout
    private var full=false
    private fun fullScreen() {
        full=!full
        for(i in 0 until layout.childCount) {
            val child=layout.getChildAt(i)
            if(child !is SurfaceView) child.visibility=if(full) View.GONE else View.VISIBLE
        }
        WindowInsetsControllerCompat(window,window.decorView).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(full) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        status=TextView(this).apply { text="Loading saved recording from car…" }; layout.addView(status)
        layout.addView(SurfaceView(this).apply { holder.addCallback(this@RemotePlayerActivity); setOnClickListener { fullScreen() } },LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(Button(this).apply { text="FULL SCREEN (TAP VIDEO TO EXIT)"; setOnClickListener { fullScreen() } })
        layout.addView(Button(this).apply { text="PLAY / PAUSE"; setOnClickListener { if(prepared) player?.let { if(it.isPlaying) it.pause() else it.start() } } })
        layout.addView(Button(this).apply { text="BACK 10 SECONDS"; setOnClickListener { if(prepared) player?.let { it.seekTo((it.currentPosition-10000).coerceAtLeast(0)) } } })
        layout.addView(Button(this).apply { text="FORWARD 10 SECONDS"; setOnClickListener { if(prepared) player?.let { it.seekTo((it.currentPosition+10000).coerceAtMost(it.duration)) } } })
        setContentView(layout)
    }
    override fun surfaceCreated(holder:SurfaceHolder) {
        val prefs=getSharedPreferences("connection",MODE_PRIVATE)
        val host=prefs.getString("host","")!!; val pin=prefs.getString("pin","")!!; val token=prefs.getString("token","")!!
        val id=intent.getStringExtra("id") ?: return
        val length=intent.getLongExtra("size",0)
        try {
            player=MediaPlayer().apply {
                setDisplay(holder)
                setDataSource(object:MediaDataSource() {
                    private var offset=-1L
                    private var cached=ByteArray(0)
                    @Volatile private var closed=false
                    override fun getSize()=length
                    override fun close() { closed=true; cached=ByteArray(0) }
                    @Synchronized override fun readAt(position:Long,buffer:ByteArray,dest:Int,size:Int):Int {
                        if(closed || position>=length) return -1
                        if(size==0) return 0
                        if(position<0) return -1
                        if(position<offset || position>=offset+cached.size) {
                            val c=PhoneRoutes.get(this@RemotePlayerActivity,"/video?id=$id",mapOf("Range" to "bytes=$position-${minOf(length-1,position+262143)}"))
                            try {
                                if(c.responseCode!=206) throw java.io.IOException("Car no longer provides this recording")
                                cached=c.inputStream.use { it.readBytes() }; offset=position
                            } finally { c.disconnect() }
                        }
                        val at=(position-offset).toInt(); val count=minOf(size,cached.size-at)
                        if(count<=0) return -1
                        System.arraycopy(cached,at,buffer,dest,count); return count
                    }
                })
                setOnPreparedListener { prepared=true; it.start(); status.text="Saved recording · ${it.videoWidth}×${it.videoHeight} · ${it.duration/1000}s" }
                setOnErrorListener { _,_,_ -> status.text="Playback failed. Check connection, or download the clip and try a compatible player."; true }
                prepareAsync()
            }
        } catch(e:Exception) { status.text="Cannot open recording: ${e.message}" }
    }
    override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,height:Int) {}
    override fun surfaceDestroyed(h:SurfaceHolder) { prepared=false; player?.release(); player=null }
    override fun onPause() { if(prepared) player?.pause(); super.onPause() }
}
