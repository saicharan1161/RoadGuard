package com.roadguard.ai

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

/** Local USB library; no network pairing needed. Playback is deliberately disabled while recording. */
class RecordingsActivity:AppCompatActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var list:LinearLayout
    private lateinit var summary:TextView
    private var files=emptyList<File>()
    private var query=""
    private var keptOnly=false
    private var limit=30
    private var player:VideoView?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16); setBackgroundColor(LibraryStyle.background)
        }
        body.addView(LibraryStyle.text(this,"Car recordings",26f))
        body.addView(LibraryStyle.button(this,"Browse by date · play or export"){startActivity(android.content.Intent(this,TimelineActivity::class.java))})
        body.addView(LibraryStyle.button(this,"Back to live camera") { finish() })
        summary=LibraryStyle.text(this,"Loading USB recordings…",14f,true); body.addView(summary)
        body.addView(LibraryStyle.button(this,"Refresh recordings") { load() })
        body.addView(EditText(this).apply {
            hint="Search date or filename"; setTextColor(LibraryStyle.ink); setHintTextColor(LibraryStyle.muted); isSingleLine=true
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s.toString().trim(); limit=30; render() }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
        })
        body.addView(CheckBox(this).apply {
            text="Kept videos only"; setTextColor(LibraryStyle.ink)
            setOnCheckedChangeListener { _,checked -> keptOnly=checked; limit=30; render() }
        })
        list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; body.addView(list)
        val scroll=ScrollView(this).apply { addView(body) }; setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        load()
    }
    private fun load() { worker.execute {
        try {
            val data=StorageHelper.recordings(this)
            runOnUiThread { if(!isDestroyed) { files=data; summary.text="${files.size} clips · newest first · dates use car clock\nNew videos: RoadGuardAI/Car Dash Cam Recordings\nOlder videos remain available in their original folder.\nUse STOP & SAVE before local playback. Download videos from the phone's Recordings screen."; render() } }
        } catch(e:Exception) { runOnUiThread { if(!isDestroyed) summary.text="USB unavailable: ${e.message}" } }
    } }
    private fun render() {
        if(!::list.isInitialized) return
        list.removeAllViews()
        val selected=files.filter { it.name.contains(query,true) && (!keptOnly || RecordingLibrary.protectedClip(it)) }
        if(selected.isEmpty()) list.addView(LibraryStyle.text(this,"No matching videos. Connect USB storage or change the filter."))
        var day=""
        selected.take(limit).forEach { file ->
            val next=LibraryStyle.day(file.name)
            if(next!=day) { day=next; list.addView(LibraryStyle.text(this,day,20f)) }
            val kept=RecordingLibrary.protectedClip(file)
            val card=LibraryStyle.panel(this)
            card.addView(LibraryStyle.text(this,LibraryStyle.title(file.name),20f))
            card.addView(LibraryStyle.text(this,"${"%.1f".format(file.length()/1048576.0)} MB${if(kept) "  ·  KEPT" else ""}",14f,true))
            val row=LinearLayout(this)
            row.addView(LibraryStyle.button(this,"Play") { play(file) },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(LibraryStyle.button(this,if(kept) "Kept ✓" else "Keep video") {
                val change={ worker.execute {
                    try { StorageHelper.protect(this,file,!kept); runOnUiThread { if(!isDestroyed) render() } }
                    catch(e:Exception) { runOnUiThread { if(!isDestroyed) Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() } }
                } }
                if(!kept) change() else AlertDialog.Builder(this).setTitle("Remove protection?")
                    .setMessage("Loop recording may delete this clip when space is low.").setPositiveButton("Remove") { _,_ -> change() }.setNegativeButton("Cancel",null).show()
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            card.addView(LibraryStyle.text(this,file.name,12f,true)); list.addView(card)
        }
        if(selected.size>limit) list.addView(LibraryStyle.button(this,"Show more (${selected.size-limit} remaining)") { limit+=30; render() })
    }
    private fun play(file:File) {
        if(RecorderState.recording || RecorderState.saving) {
            Toast.makeText(this,"Return to live and tap STOP & SAVE before local playback.",Toast.LENGTH_LONG).show(); return
        }
        val video=VideoView(this); player=video
        val dialog=AlertDialog.Builder(this).setTitle(LibraryStyle.title(file.name)).setView(video)
            .setPositiveButton("Close",null).create()
        video.setMediaController(MediaController(this).apply { setAnchorView(video) })
        video.setOnErrorListener { _,_,_ -> Toast.makeText(this,"This clip could not be played. Keep it and check it on your phone or PC.",Toast.LENGTH_LONG).show(); dialog.dismiss(); true }
        dialog.setOnDismissListener { video.stopPlayback(); player=null }
        dialog.show(); video.layoutParams=video.layoutParams.apply { height=LibraryStyle.dp(this@RecordingsActivity,240) }
        video.setVideoPath(file.absolutePath); video.start()
    }
    override fun onPause() { player?.pause(); super.onPause() }
    override fun onDestroy() { player?.stopPlayback(); worker.shutdownNow(); super.onDestroy() }
}
