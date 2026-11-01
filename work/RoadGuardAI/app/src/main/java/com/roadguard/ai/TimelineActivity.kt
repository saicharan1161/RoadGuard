package com.roadguard.ai

import android.app.*
import android.os.*
import android.content.Intent
import android.net.Uri
import android.widget.*
import androidx.media3.common.*
import androidx.media3.common.util.Clock
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.Executors

/** One wall-clock selection feeds both playback and a non-destructive MP4 export. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TimelineActivity:Activity(){
    private data class Entry(val start:Long,val file:File?,val id:String,val size:Long)
    private data class Part(val media:MediaItem,val ms:Long,val bytes:Long)
    private val remote by lazy {intent.getBooleanExtra("phone",false)}
    private val worker=Executors.newSingleThreadExecutor()
    private val ui=Handler(Looper.getMainLooper())
    private lateinit var body:LinearLayout;private lateinit var summary:TextView
    private lateinit var select:Button;private lateinit var play:Button;private lateinit var export:Button;private lateinit var speed:Button
    private lateinit var screen:PlayerView
    private var player:ExoPlayer?=null;private var edit:Transformer?=null
    private var parts=listOf<Part>();@Volatile private var generation=0;private var caption="";private var rate=1
    private var temp:File?=null;private var copying=false
    private var preparedFile:File?=null
    private val sources by lazy { DefaultMediaSourceFactory(if(remote) DataSource.Factory {PairedVideoSource(this)} else DefaultDataSource.Factory(this)) }
    override fun onCreate(state:Bundle?){
        super.onCreate(state);body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(18,16,18,16);setBackgroundColor(LibraryStyle.background)}
        body.addView(LibraryStyle.text(this,"Recording timeline",26f))
        body.addView(LibraryStyle.text(this,"CAR CLOCK   /   SAVED FOOTAGE",13f,true))
        select=LibraryStyle.button(this,"Choose date and time"){chooseDate()};body.addView(select)
        summary=LibraryStyle.text(this,"Choose a day to watch everything recorded, or select a start and end minute.",15f,true);body.addView(summary)
        screen=PlayerView(this);body.addView(screen,LinearLayout.LayoutParams(-1,LibraryStyle.dp(this,210)))
        val row=LinearLayout(this);body.addView(row)
        play=LibraryStyle.button(this,"Play selection"){startPlayer()};speed=LibraryStyle.button(this,"Speed: 1×"){rate=rate%3+1;speed.text="Speed: ${rate}×";player?.setPlaybackSpeed(rate.toFloat())}
        for(b in listOf(play,speed))row.addView(b,LinearLayout.LayoutParams(0,-2,1f))
        export=LibraryStyle.button(this,"Download selected MP4"){beginExport()};body.addView(export)
        body.addView(LibraryStyle.button(this,"Back"){finish()})
        enable(false)
        val scroll=ScrollView(this).apply{addView(body)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,i->val b=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);i}
    }
    private fun enable(ok:Boolean){play.isEnabled=ok;speed.isEnabled=ok;export.isEnabled=ok}
    private fun chooseDate(){
        val c=Calendar.getInstance()
        DatePickerDialog(this,{_,y,m,d->
            val day=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)
            AlertDialog.Builder(this).setTitle(day).setItems(arrayOf("Full day · all saved footage","Time range · choose start and end")){_,which->
                if(which==0)load(day,0,1440) else pick("Start time",9,0){a->pick("End time (exclusive)",minOf(23,a/60+1),a%60){b->
                    if(b>a)load(day,a,b) else summary.text="End time must be later than start time on the same day."
                }}
            }.show()
        },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun pick(label:String,h:Int,m:Int,done:(Int)->Unit){TimePickerDialog(this,{_,hour,min->done(hour*60+min)},h,m,true).apply{setTitle(label)}.show()}
    private fun json(path:String):JSONObject {val c=PhoneRoutes.get(this,path);return try {check(c.responseCode==200){"Car unavailable (HTTP ${c.responseCode})"};JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}}
    private fun localBusy()=!remote && (RecorderState.recording||RecorderState.saving||RecorderState.status=="Starting recording")
    private fun load(day:String,a:Int,b:Int){
        if(localBusy()){summary.text="Use the phone while the car is recording, or STOP & SAVE first for local playback/export.";return}
        val run=++generation;player?.release();player=null;screen.player=null;preparedFile=null;enable(false);parts=emptyList()
        summary.text="Finding saved footage…";caption=day+if(a==0&&b==1440)"_full-day" else String.format(Locale.US,"_%02d-%02d_to_%02d-%02d",a/60,a%60,b/60,b%60)
        worker.execute {try{
            val begin=ClipClock.day(day)+a*60000L;val end=ClipClock.day(day)+b*60000L
            val entries=if(remote){val list=json("/recordings").getJSONArray("recordings");(0 until list.length()).mapNotNull {i->
                val e=list.getJSONObject(i);if(e.optBoolean("active"))null else Entry(ClipClock.start(e.getString("name")),null,e.getString("id"),e.getLong("bytes"))
            }}else StorageHelper.recordings(this).map{Entry(ClipClock.start(it.name),it,"",it.length())}
            val sorted=entries.filter {it.start>=0 && it.start<end}.sortedBy {it.start}
            // Consecutive recordings on one car do not overlap normally. Include the last one that started before the range for cross-midnight trips.
            val candidates=listOfNotNull(sorted.lastOrNull {it.start<begin})+sorted.filter {it.start>=begin}
            val result=ArrayList<Part>();var missing=0
            for(e in candidates){if(isDestroyed || run!=generation)return@execute
                val duration=if(e.file!=null)ClipDuration.read(e.file) else json("/details?timeline=1&id=${e.id}").optLong("durationMs",0)
                if(duration<=0){missing++;continue}
                val span=ClipClock.overlap(e.start,duration,begin,end) ?: continue
                val uri=e.file?.let {Uri.fromFile(it)} ?: Uri.parse("roadguard://clip/${e.id}")
                val clip=MediaItem.ClippingConfiguration.Builder().setStartPositionMs(span[0])
                if(span[1]<duration)clip.setEndPositionMs(span[1])
                val item=MediaItem.Builder().setUri(uri).setMimeType("video/mp4").setClippingConfiguration(clip.build()).build()
                result.add(Part(item,span[1]-span[0],(e.size.toDouble()*(span[1]-span[0])/duration).toLong()))
            }
            runOnUiThread {if(run==generation&&!isDestroyed){parts=result
                summary.text=if(result.isEmpty())"No completed footage in this range." else "${caption.replace('_',' ')}\n${result.size} segment(s) · ${result.sumOf{it.ms}/1000}s recorded. Gaps are skipped; original timestamps remain visible on new footage."
                if(missing>0)summary.append("\n$missing file(s) lacked duration and could not be included.")
                enable(result.isNotEmpty() && missing==0);if(result.isNotEmpty())play.isEnabled=true
            }}
        }catch(e:Exception){runOnUiThread {if(run==generation&&!isDestroyed)summary.text="Could not load this selection: ${e.message}"}}}
    }
    private fun startPlayer(){
        if(localBusy()){summary.text="Stop and save on the car before local playback.";return}
        try {
            player?.release()
            val saved=preparedFile?.takeIf{it.isFile}
            val p=ExoPlayer.Builder(this).setMediaSourceFactory(if(saved==null)sources else DefaultMediaSourceFactory(this)).build()
            player=p;screen.player=p;p.addListener(object:Player.Listener{override fun onPlayerError(error:PlaybackException){summary.text="Playback unavailable: ${error.errorCodeName}. Source may be offline or no longer on USB."}})
            p.setMediaItems(if(saved==null)parts.map{it.media} else listOf(MediaItem.fromUri(Uri.fromFile(saved))))
            p.prepare();p.setPlaybackSpeed(rate.toFloat());p.play()
        }catch(e:Exception){summary.text="Playback unavailable: ${e.message}"}
    }
    private fun beginExport(){
        if(parts.isEmpty()||copying||edit!=null)return
        if(localBusy()){summary.text="Stop and save before exporting on the car, or export on the phone.";return}
        if(remote && temp?.isFile==true && preparedFile==temp){savePicker();return}
        val root=if(remote) externalCacheDir else StorageHelper.removableRoot(this)
        if(root==null){summary.text="Writable storage unavailable.";return}
        val reserve=if(remote)128L*1024*1024 else LoopStorage.reserve(root.totalSpace)
        val bytes=parts.sumOf {it.bytes};if(root.usableSpace-reserve<bytes+32L*1024*1024){summary.text="Not enough free space for this export while keeping the reserve. Choose a shorter range.";return}
            val folder=File(root,if(remote)"RoadGuardTimeline" else "RoadGuardAI/Exports")
        try{
            check(folder.isDirectory||folder.mkdirs());File(folder,".nomedia").createNewFile()
            val cutoff=System.currentTimeMillis()-86400000L
            folder.listFiles()?.filter { it.isFile && it.canonicalFile==it.absoluteFile && it.name.matches(Regex("range-[0-9-]+\\.partial\\.mp4")) && it.lastModified() in 1 until cutoff }?.forEach { it.delete() }
            temp?.delete();temp=File.createTempFile("range-",".partial.mp4",folder)
            val dst=temp!!;player?.release();player=null;screen.player=null;select.isEnabled=false;enable(false)
            summary.text="Preparing MP4 from ${parts.size} segment(s). Keep this screen open. Back cancels."
            val seq=EditedMediaItemSequence.Builder(parts.map{EditedMediaItem.Builder(it.media).setRemoveAudio(true).build()}).build()
            val loader=DefaultAssetLoaderFactory(this,DefaultDecoderFactory.Builder(this).build(),Clock.DEFAULT,sources,DataSourceBitmapLoader(this))
            edit=Transformer.Builder(this).setAssetLoaderFactory(loader).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object:Transformer.Listener{
                override fun onError(composition:Composition,result:ExportResult,exception:ExportException){failed("Export failed: ${exception.errorCodeName}")}
                override fun onCompleted(composition:Composition,result:ExportResult){
                    edit=null;ui.removeCallbacksAndMessages(null);select.isEnabled=true;enable(true)
                    if(remote){preparedFile=dst;summary.text="Clip ready. Play selection previews the export; Download saves a copy.";savePicker()}
                    else {val final=File(folder,caption+"_"+System.currentTimeMillis()+".mp4");if(dst.renameTo(final)){temp=null;preparedFile=final;summary.text="Saved on USB:\n${final.absolutePath}"}else failed("Could not finish the USB export.")}
                }
            }).build().also{it.start(Composition.Builder(seq).build(),dst.absolutePath)}
            val monitor=object:Runnable{override fun run(){if(edit==null)return
                if(localBusy()||root.usableSpace<reserve+8L*1024*1024){edit?.cancel();failed("Export stopped to protect recording/storage space.");return}
                val p=ProgressHolder();if(edit?.getProgress(p)==Transformer.PROGRESS_STATE_AVAILABLE)summary.text="Preparing MP4 · ${p.progress}%"
                ui.postDelayed(this,500)
            }};ui.post(monitor)
        }catch(e:Exception){edit?.cancel();failed("Cannot export: ${e.message}")}
    }
    private fun failed(msg:String){edit=null;temp?.delete();temp=null;preparedFile=null;select.isEnabled=true;enable(parts.isNotEmpty());summary.text=msg}
    private fun savePicker(){try{startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"$caption.mp4"),701)}catch(e:Exception){summary.text="No file picker available: ${e.message}"}}
    @Deprecated("Legacy activity result for old head units")
    override fun onActivityResult(req:Int,code:Int,data:Intent?){super.onActivityResult(req,code,data);if(req!=701||code!=RESULT_OK)return
        val uri=data?.data?:return;val f=temp?:return;copying=true;select.isEnabled=false;enable(false)
        worker.execute {val msg=try{val out=contentResolver.openOutputStream(uri,"wt")?:error("Destination unavailable");out.use{o->f.inputStream().use{i->check(i.copyTo(o)==f.length())}};"Export saved to the selected location."}catch(e:Exception){"Copy failed; destination may be incomplete: ${e.message}"}
            finally{if(isDestroyed)f.delete()}
            runOnUiThread{copying=false;if(!isDestroyed){select.isEnabled=true;enable(true);summary.text=msg}}
        }
    }
    override fun onPause(){player?.pause();super.onPause()}
    override fun onDestroy(){generation++;ui.removeCallbacksAndMessages(null);edit?.cancel();player?.release();if(!copying)temp?.delete();worker.shutdown();super.onDestroy()}
}
