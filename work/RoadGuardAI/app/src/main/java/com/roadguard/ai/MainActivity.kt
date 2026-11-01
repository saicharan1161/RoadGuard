package com.roadguard.ai

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private lateinit var root: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var stateText: TextView
    private lateinit var clockText: TextView
    private lateinit var controls: LinearLayout
    private lateinit var controlsButton: Button
    private var recorder: RecorderService? = null
    private var bound = false
    private var surface: Surface? = null
    private var controlsVisible = false
    private var fillScreen = false
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recorder = (binder as? RecorderService.LocalBinder)?.service()
            publishPreview()
        }
        override fun onServiceDisconnected(name: ComponentName?) { recorder = null }
    }
    private val updater = object : Runnable {
        override fun run() {
            val mb = RecorderState.committedBytes / 1048576.0
            stateText.text = "${if (RecorderState.recording) "● REC" else RecorderState.status}  |  " +
                "${RecorderState.videoWidth}×${RecorderState.videoHeight} · ${RecorderState.previewFps} FPS  |  ${String.format(Locale.US, "%.1f", mb)} MB saved"
            stateText.setTextColor(if (RecorderState.recording) Color.rgb(255,100,100) else Color.WHITE)
            val now=SystemClock.elapsedRealtime()
            val health=RecordingHealth.label(RecorderState.recording,RecorderState.saving,RecorderState.committedFragments.toLong(),if(RecorderState.lastCommitAt==0L) 0L else now-RecorderState.lastCommitAt)
            val elapsed=if(RecorderState.recordingStartedAt>0 && (RecorderState.recording || RecorderState.saving)) (now-RecorderState.recordingStartedAt)/1000 else 0
            val free=if(RecorderState.freeBytes<0) "USB space unknown" else String.format(Locale.US,"%.1f GB free",RecorderState.freeBytes/1073741824.0)
            stateText.append("\n$health · ${elapsed/60}m ${elapsed%60}s · $free")
            if(RecorderState.storageAlert.isNotEmpty()) stateText.append("\n${RecorderState.storageAlert}")
            clockText.text = java.text.SimpleDateFormat("EEEE · dd MMM yyyy · HH:mm:ss z", Locale.getDefault()).format(java.util.Date())
            fitPreview()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(getSharedPreferences("connection",MODE_PRIVATE).getString("mode", "car")=="phone") {
            startActivity(Intent(this,PhoneActivity::class.java)); finish(); return
        }
        getSharedPreferences("connection",MODE_PRIVATE).edit().putString("mode","car").apply()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        immersive()
        requestMissingPermissions()
        startRecorderService()
        bound = bindService(Intent(this, RecorderService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onResume() { super.onResume(); if(::clockText.isInitialized) handler.post(updater); immersive(); startLocationIfAllowed() }
    override fun onPause() { handler.removeCallbacks(updater); super.onPause() }
    override fun onDestroy() {
        if (bound) unbindService(connection)
        // TextureView destroys its SurfaceTexture after Activity teardown. Keep the
        // local binder until that callback detaches EGL and releases the Surface.
        super.onDestroy()
    }
    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun buildUi(): FrameLayout {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this).apply {
            surfaceTextureListener = this@MainActivity
            contentDescription = "Live camera video. Tap to show controls."
            setOnClickListener { toggleControls() }
        }
        root.addView(preview, FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> fitPreview() }
        stateText = TextView(this).apply {
            textSize=16f; setTextColor(Color.WHITE); setPadding(dp(12),dp(10),dp(12),dp(10))
            setBackgroundColor(0x99000000.toInt())
        }
        root.addView(stateText, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
        clockText=TextView(this).apply { textSize=12f; setTextColor(Color.WHITE); setPadding(dp(8),dp(4),dp(8),dp(4)); setBackgroundColor(0x99000000.toInt()) }
        root.addView(clockText,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START))
        controlsButton=button("CONTROLS") { toggleControls() }
        root.addView(controlsButton,FrameLayout.LayoutParams(dp(130),dp(50),Gravity.TOP or Gravity.END))
        controls=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; setPadding(dp(6),dp(6),dp(6),dp(6))
            setBackgroundColor(0xCC07111F.toInt()); visibility=View.GONE
        }
        controls.addView(button("START") { startRecorderService(RecorderService.ACTION_RESUME) }, controlParams())
        controls.addView(button("STOP & SAVE") { startRecorderService(RecorderService.ACTION_STOP) }, controlParams())
        controls.addView(button("FIT / FILL") { fillScreen=!fillScreen; fitPreview() }, controlParams())
        controls.addView(button("VIDEOS") { startActivity(Intent(this,RecordingsActivity::class.java)) }, controlParams())
        controls.addView(button("SETTINGS") { showSettings() }, controlParams())
        root.addView(controls,FrameLayout.LayoutParams(-1,dp(66),Gravity.BOTTOM))
        return root
    }
    private fun toggleControls() {
        controlsVisible=!controlsVisible
        controls.visibility=if(controlsVisible) View.VISIBLE else View.GONE
        controlsButton.text=if(controlsVisible) "FULL SCREEN" else "CONTROLS"
        (clockText.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin=if(controlsVisible) dp(66) else 0; clockText.layoutParams=this }
        immersive()
    }
    private fun fitPreview() {
        if (!::root.isInitialized || root.width<=0 || root.height<=0) return
        val ratio=RecorderState.videoWidth.toDouble()/RecorderState.videoHeight.coerceAtLeast(1)
        val screenRatio=root.width.toDouble()/root.height
        val useWidth=if(fillScreen) screenRatio>ratio else screenRatio<ratio
        val w=if(useWidth) root.width else (root.height*ratio).toInt()
        val h=if(useWidth) (root.width/ratio).toInt() else root.height
        if(preview.layoutParams.width!=w || preview.layoutParams.height!=h) {
            preview.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
        }
    }
    private fun publishPreview() { surface?.let { recorder?.showPreview(it,preview.width,preview.height) } }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width:Int,height:Int) {
        surface=Surface(texture); publishPreview()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,width:Int,height:Int) { publishPreview() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { detachPreview(texture); return false }
    private fun detachPreview(texture: SurfaceTexture?) {
        val old=surface; surface=null
        val done = { old?.release(); texture?.release(); Unit }
        val service=recorder
        if(service==null) done() else service.showPreview(null,0,0,done)
    }
    private fun showSettings() {
        val text=TextView(this).apply {
            textSize=16f; setPadding(dp(16),dp(12),dp(16),dp(12))
            text="RoadGuard AI ${packageManager.getPackageInfo(packageName,0).versionName}\n\n${RecorderState.status}\n${RecorderState.detail}\n\n"+
                "Camera: ${RecorderState.cameraName}\nStorage: ${RecorderState.storagePath}\n\n${RecorderState.compatibility}\n\n"+
                "${RecorderState.quality}\n\n"+
                "FIT keeps every edge visible; FILL crops the edges to fill the display.\n\n"+
                "Tap STOP & SAVE before ejecting USB storage."
        }
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(text) }
        panel.addView(button("CONNECT PHONE") { recorder?.let { CarPairingUi.show(this,it) } })
        panel.addView(button("TAILSCALE AUTO-CONNECT SETUP") { VpnAssist.show(this) })
        panel.addView(button("RECORDINGS / KEPT VIDEOS") { startActivity(Intent(this,RecordingsActivity::class.java)) })
        panel.addView(button("LOCATION / PRIVATE NETWORK") {
            val prefs=getSharedPreferences("connection",MODE_PRIVATE)
            val enabled=booleanArrayOf(prefs.getBoolean("locationSharing",false),prefs.getBoolean("privateNetwork",false))
            AlertDialog.Builder(this).setTitle("Remote access options")
                .setMultiChoiceItems(arrayOf("Share GPS with paired phones / PCs (place lookup uses Android's geocoder)","Allow paired private VPN clients (Tailscale 100.64–100.127)"),enabled) { _,i,value -> enabled[i]=value }
                .setPositiveButton("Save") { _,_ ->
                    prefs.edit().putBoolean("locationSharing",enabled[0]).putBoolean("privateNetwork",enabled[1]).apply()
                    if(enabled[0]) {
                        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
                            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),9002)
                        else startLocationIfAllowed()
                    } else stopService(Intent(this,CarLocationService::class.java))
                }.setNegativeButton("Cancel",null).show()
        })
        panel.addView(button("REAR CAMERA AVAILABILITY") { showRearAvailability() })
        panel.addView(android.widget.CheckBox(this).apply {
            this.text="Use camera's own night processing only"
            val pref=getSharedPreferences("quality",MODE_PRIVATE)
            isChecked=pref.getBoolean("nativeNightOnly",false)
            setOnCheckedChangeListener {_,v->pref.edit().putBoolean("nativeNightOnly",v).apply();com.jiangdg.ausbc.render.NightRules.bypass=v}
        })
        panel.addView(button("RECORDING HEALTH / STORAGE PROTECTION") {
            AlertDialog.Builder(this).setTitle("Recording health and storage")
                .setMessage("${stateText.text}\n\nRecording keeps a 3 GB reserve on an 8 GB drive and 10 GB on drives of 24 GB or more. New recordings rotate at about 32 MiB. Near the reserve, one oldest eligible finished segment is removed per check. Kept videos, large legacy clips over 64 MiB, music and unrelated files are never pruned. If no eligible space remains, recording waits. Segment boundaries may have a short gap.")
                .setPositiveButton("CLOSE",null).show()
        })
        panel.addView(button("RETEST AUTO QUALITY (STOP FIRST)") {
            if(recorder?.hasRecordingInProgress()==true) Toast.makeText(this,"STOP & SAVE before retesting quality",Toast.LENGTH_LONG).show()
            else { recorder?.resetAutoQuality(); Toast.makeText(this,"Reassessing camera. Press START to record.",Toast.LENGTH_LONG).show() }
        })
        panel.addView(button("CHANGE DEVICE MODE") {
            if(recorder?.hasRecordingInProgress()==true) Toast.makeText(this,"STOP & SAVE before changing modes",Toast.LENGTH_LONG).show()
            else { stopService(Intent(this,RecorderService::class.java)); startActivity(Intent(this,ModeActivity::class.java).putExtra("choose",true)); finish() }
        })
        panel.addView(button("COMPATIBILITY SCAN") {
            val scan=CompatibilityProbe.run(this)
            RecorderState.compatibility=scan.summary
            StorageHelper.saveCompatibilityReport(this,scan.report)
            text.text=scan.report
        })
        panel.addView(button("ANDROID APP SETTINGS") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        })
        panel.addView(button("CLEAN UNINSTALL") { showUninstallDialog() })
        AlertDialog.Builder(this).setTitle("Status and settings")
            .setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("CLOSE",null).show()
    }
    private fun requestMissingPermissions() {
        val wanted=mutableListOf(Manifest.permission.CAMERA)
        if(Build.VERSION.SDK_INT>=33) wanted+=Manifest.permission.POST_NOTIFICATIONS
        val missing=wanted.filter { ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) ActivityCompat.requestPermissions(this,missing.toTypedArray(),9001)
    }
    private fun startLocationIfAllowed() {
        if(getSharedPreferences("connection",MODE_PRIVATE).getString("mode","car")!="car") return
        if(getSharedPreferences("connection",MODE_PRIVATE).getBoolean("locationSharing",false) &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            CarLocationService.ensureStarted(this,visible=true)
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==9002) startLocationIfAllowed()
    }
    private fun showRearAvailability() {
        val manager=getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val ids=try { manager.cameraIdList.toList() } catch(_:Exception) { emptyList() }
        val report=CompatibilityProbe.run(this)
        StorageHelper.saveCompatibilityReport(this,report.report)
        AlertDialog.Builder(this).setTitle("Factory RCA reverse camera")
            .setMessage("Android camera IDs: ${ids.joinToString().ifBlank { "none exposed" }}\n\n"+
                if(ids.isEmpty()) "The factory RCA feed is not exposed through Android's camera API in this scan. RoadGuard cannot show or record it yet. The factory reverse display is left untouched.\n\nA camera powered only in reverse cannot supply continuous rear video. Manufacturer integration or suitable capture hardware and safe power wiring are required."
                else "Android exposes camera devices, but their IDs do not prove that any is the RCA reverse feed. Manufacturer mapping and testing are needed before enabling rear preview. No camera is opened by this scan; the factory reverse display is left untouched.")
            .setPositiveButton("CLOSE",null).show()
    }
    private fun startRecorderService(action:String?=null) {
        val intent=Intent(this,RecorderService::class.java).apply { this.action=action }
        if(Build.VERSION.SDK_INT>=26) startForegroundService(intent) else startService(intent)
    }
    private fun showUninstallDialog() {
        AlertDialog.Builder(this).setTitle("Delete RoadGuard AI data and uninstall?")
            .setMessage("First stop and save recording. This deletes recordings and reports from connected storage. Android will ask you to confirm uninstalling.")
            .setNegativeButton("CANCEL",null).setPositiveButton("DELETE & UNINSTALL") { _,_ ->
                if(recorder?.hasRecordingInProgress() == true || RecorderState.recording || RecorderState.saving) {
                    Toast.makeText(this,"Use STOP & SAVE first, then uninstall",Toast.LENGTH_LONG).show()
                } else {
                    stopService(Intent(this,RecorderService::class.java))
                    if(StorageHelper.deleteAppFiles(this)) {
                        startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:$packageName")))
                    } else Toast.makeText(this,"Some USB files could not be deleted",Toast.LENGTH_LONG).show()
                }
            }.show()
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply {
        text=label; textSize=13f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(22,69,90))
        setOnClickListener { action() }
    }
    private fun controlParams()=LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(3),0,dp(3),0) }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
