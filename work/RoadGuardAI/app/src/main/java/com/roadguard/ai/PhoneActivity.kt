package com.roadguard.ai

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhoneActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("connection",MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var address: EditText
    private lateinit var list: LinearLayout
    private lateinit var found: LinearLayout
    private lateinit var image: ImageView
    private val worker=Executors.newFixedThreadPool(2)
    private val handler=Handler(Looper.getMainLooper())
    private val live=AtomicBoolean(false)
    private val downloading=AtomicBoolean(false)
    private val cancelDownload=AtomicBoolean(false)
    private var pending: JSONObject?=null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation?=null
    private var discovery: NsdManager.DiscoveryListener?=null
    @Volatile private var resumed=false
    private var listing=false
    private var autoLiveOpened=false
    @Volatile private var recoveryBlocked=false
    private var presence:LocalDiscovery?=null
    private var networkCallback:android.net.ConnectivityManager.NetworkCallback?=null
    private val networkReconnect=Runnable {
        if(resumed && !recoveryBlocked && prefs.getString("token",null)!=null) {
            refresh()
        }
    }
    private val discovered=mutableSetOf<String>()
    private val probeTimes=mutableMapOf<String,Long>()
    private lateinit var liveButton:Button
    private var clips=emptyList<JSONObject>()
    private var query=""
    private var keptOnly=false
    private var visibleCount=30
    private lateinit var downloadStatus:TextView
    private val version by lazy { packageManager.getPackageInfo(packageName,0).versionName }
    private val scan=registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents -> try {
            val data=if(contents.startsWith("RG1|")) {
                val fields=contents.split('|'); require(fields.size==5)
                fun hex(value:String)=android.util.Base64.decode(value,android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).joinToString("") { "%02x".format(it) }
                JSONObject().put("v",1).put("app",fields[1]).put("host",fields[2]).put("pin",hex(fields[3])).put("secret",hex(fields[4]))
            } else JSONObject(contents)
            require(data.optString("app")==version) { "Install the same latest APK on car and phone" }
            require(data.getInt("v")==1 && data.getString("pin").matches(Regex("[a-f0-9]{64}")) && data.getString("secret").matches(Regex("[a-f0-9]{64}")))
            address.setText(data.getString("host"))
            pair(data.getString("host"),data.getString("pin"),data.getString("secret"))
        } catch(e:Exception) { message(e.message ?: "Not a valid RoadGuard QR. Generate a new code on the car.") } }
    }
    private val create=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri=result.data?.data; val entry=pending; pending=null
        if(result.resultCode==RESULT_OK && uri!=null && entry!=null) download(entry,uri)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs.edit().putString("mode","phone").apply()
        stopService(Intent(this,RecorderService::class.java))
        stopService(Intent(this,CarLocationService::class.java))
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,24,20,20); setBackgroundColor(LibraryStyle.background) }
        body.addView(LibraryStyle.text(this,"RoadGuard",28f))
        body.addView(LibraryStyle.text(this,"Your car. Your recordings.",16f,true))
        status=LibraryStyle.text(this,if(prefs.getString("token",null)!=null) "Reconnecting to your paired car / PC automatically…" else "Pair once to access your recordings. Saved devices reconnect automatically while this screen is open.")
        body.addView(LibraryStyle.panel(this).apply { addView(status) })
        val pairing=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        body.addView(pairing)
        pairing.addView(button("Scan car QR") { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the entire square on the car's Connect phone screen").setBeepEnabled(false).setOrientationLocked(false)) },LinearLayout.LayoutParams(0,-2,1f))
        address=EditText(this).apply { hint="Car IPv4 address (shown on car)"; isSingleLine=true; setText(prefs.getString("host","")) }
        pairing.addView(button("Enter code") { codePair() },LinearLayout.LayoutParams(0,-2,1f))
        found=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; body.addView(found)
        liveButton=button("Watch live  •  Full screen") { autoLiveOpened=true;live.set(false); startActivity(Intent(this,LivePlayerActivity::class.java)) }.apply { isEnabled=false }
        body.addView(liveButton)
        body.addView(CheckBox(this).apply {
            text="Open live automatically after connecting (uses data)";setTextColor(LibraryStyle.ink)
            isChecked=prefs.getBoolean("autoLive",true)
            setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean("autoLive",checked).apply();if(checked){autoLiveOpened=false;refresh()} }
        })
        body.addView(button("Refresh connection & recordings") { recoveryBlocked=false; refresh() })
        body.addView(button("Recording timeline · play or export") {startActivity(Intent(this,TimelineActivity::class.java).putExtra("phone",true))})
        body.addView(button("Car location / coordinates") { showLocation() })
        body.addView(button("Live car map") { startActivity(Intent(this,LiveMapActivity::class.java)) })
        body.addView(button("Resume interrupted download") {
            val saved=prefs.getString("downloadEntry",null);val uri=prefs.getString("downloadUri",null)
            if(saved==null || uri==null) message("No interrupted download saved")
            else download(JSONObject(saved),Uri.parse(uri),true)
        })
        image=ImageView(this)
        body.addView(button("SETTINGS / CONNECTION HELP") {
            AlertDialog.Builder(this).setTitle("RoadGuard $version").setItems(arrayOf("Hotspot settings","Enter car address manually","Forget pairing","Cancel download","Change device mode")) { _,which -> when(which) {
                0 -> hotspotOptions()
                1 -> { val edit=EditText(this).apply { hint="Car address"; setText(address.text) }; AlertDialog.Builder(this).setTitle("Car address").setView(edit).setPositiveButton("USE") { _,_ -> address.setText(edit.text); codePair() }.setNegativeButton("CANCEL",null).show() }
                2 -> { prefs.edit().remove("token").remove("pin").remove("routes").remove("carPin").apply(); liveButton.isEnabled=false; clips=emptyList(); renderClips(); message("All remembered car/server pairings forgotten. Pair again to connect.") }
                3 -> cancelDownload.set(true)
                4 -> { startActivity(Intent(this,ModeActivity::class.java).putExtra("choose",true)); finish() }
            } }.show()
        })
        body.addView(LibraryStyle.text(this,"Recordings",24f))
        body.addView(LibraryStyle.text(this,"Newest first · dates use the car clock\nKeep protects a clip from automatic loop deletion.",14f,true))
        val search=EditText(this).apply { hint="Find date or filename · e.g. 2026-10-20"; setTextColor(LibraryStyle.ink); setHintTextColor(LibraryStyle.muted); isSingleLine=true }
        body.addView(search)
        body.addView(CheckBox(this).apply { text="Show kept videos only"; setTextColor(LibraryStyle.ink); setOnCheckedChangeListener { _,checked -> keptOnly=checked; visibleCount=30; renderClips() } })
        downloadStatus=LibraryStyle.text(this,"Downloads: choose a folder when you tap Save to phone.",14f,true)
        body.addView(downloadStatus)
        list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; body.addView(list)
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s.toString().trim(); visibleCount=30; renderClips() }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        renderClips()
        val scroll=ScrollView(this).apply { addView(body) }
        setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
    }
    private fun button(label:String,click:()->Unit)=LibraryStyle.button(this,label,click)
    private fun message(text:String) { runOnUiThread { if(!isDestroyed) status.text=text } }
    private fun task(block:()->Unit) { worker.execute { try { block() } catch(e:Exception) {
        android.util.Log.e("RoadGuardLink","Connection operation failed",e)
        val reasons=generateSequence(e as Throwable?) { it.cause }.take(5).joinToString(" → ") { "${it.javaClass.simpleName}: ${it.message ?: "no detail"}" }
        message("Connection failed: $reasons\nCar: ${addressTextForError()}\nKeep Connect phone open on the car. Send a screenshot of this message if it persists.")
    } } }
    private fun addressTextForError()=prefs.getString("lastAttemptHost",prefs.getString("host","not found"))
    private fun client(path:String)=LinkSecurity.connect(prefs.getString("host","")!!,path,prefs.getString("pin",null) ?: error("Pair the car first"),prefs.getString("token",null) ?: error("Pair the car first")).apply { setRequestProperty("X-RoadGuard-App",version) }
    private fun readCar(path:String):JSONObject {
        var attempts=0
        while(true) {
            check(resumed && !isDestroyed) { "Viewer is not active" }
            var c:javax.net.ssl.HttpsURLConnection?=null
            try {
                c=PhoneRoutes.get(this,path)
                val status=c.responseCode
                if(status==401) error("This endpoint rejected the pairing. Check the car/server and pair again if access was revoked.")
                if(status==503 || status==502 || status==504) throw java.io.IOException("Car temporarily busy ($status)")
                check(status==200) { "Car returned $status. Open Connect phone and check matching APK versions" }
                return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch(e:Exception) {
                val delay=ConnectionRecovery.delayMillis(++attempts)
                if(!ConnectionRecovery.temporary(e) || delay<0 || !resumed) throw e
                message("Recovering connection — retry $attempts/3 in ${delay/1000}s. Recording on the car is not restarted.")
                c?.disconnect()
                Thread.sleep(delay)
            } finally { c?.disconnect() }
        }
    }
    private fun codePair() {
        val fields=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val hostEntry=EditText(this).apply { hint="Car IP, or Windows PC IP:8944"; isSingleLine=true; setText(address.text) }
        val entry=EditText(this).apply { hint="8-digit code"; inputType=android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine=true; filters=arrayOf(android.text.InputFilter.LengthFilter(8)) }
        fields.addView(hostEntry); fields.addView(entry)
        val dialog=AlertDialog.Builder(this).setTitle("Connect car or Windows PC").setMessage("Car: enter its IP and code. Windows: generate Pair phone code in the dashboard and enter PC IPv4:8944. Remote access needs a working private network.")
            .setView(fields).setNegativeButton("CANCEL",null).setPositiveButton("CONNECT",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val host=hostEntry.text.toString().trim(); val code=entry.text.toString().trim()
            if(!host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}(:894[34])?"))) { hostEntry.error="Enter IPv4 or PC IPv4:8944"; return@setOnClickListener }
            if(!code.matches(Regex("[0-9]{8}"))) { entry.error="Enter all 8 digits"; return@setOnClickListener }
            address.setText(host); dialog.dismiss(); verifyCode(host,code)
        } }
        dialog.show()
    }
    private fun verifyCode(host:String,code:String) {
        prefs.edit().putString("lastAttemptHost",host).apply()
        message("Checking secure connection to $host…")
        task {
            // This unauthenticated probe ONLY retrieves the certificate. Never send a code/token until the user checks it.
            val connection=LinkSecurity.connect(host,"/identity",null)
            val pin=try { check(connection.responseCode==200); val data=JSONObject(connection.inputStream.bufferedReader().use { it.readText() }); check(data.optString("app")==version) { "Install RoadGuard $version on both devices" }; LinkSecurity.hash((connection.serverCertificates[0] as X509Certificate).encoded) } finally { connection.disconnect() }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Check identity: ${pin.take(12)}")
                    .setMessage("Does this security check exactly match the car's Connect phone screen or Windows Pair phone dialog? Only continue if it matches.")
                    .setNegativeButton("CANCEL",null).setPositiveButton("MATCHES — PAIR") { _,_ -> pair(host,pin,code) }.show()
            }
        }
    }
    private fun pair(host:String,pin:String,code:String) { task {
        prefs.edit().putString("lastAttemptHost",host).apply()
        message("Pairing securely with $host…")
        val c=LinkSecurity.connect(host,"/pair",pin)
        val result=try { c.requestMethod="POST"; c.setRequestProperty("X-Pair-Code",code)
            c.setRequestProperty("X-RoadGuard-App",version)
            check(c.responseCode==200) { c.errorStream?.bufferedReader()?.readText() ?: "Pairing expired" }
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
        prefs.edit().putString("host",host).putString("pin",pin).putString("token",result.getString("token")).apply()
        PhoneRoutes.remember(this,host,pin,result.getString("token"),result.optString("carPin",pin))
        recoveryBlocked=false
        message("Paired securely. Connection remembered.")
        runOnUiThread { refresh() }
    } }
    private fun refresh() {
        if(listing || recoveryBlocked || prefs.getString("token",null)==null) return
        listing=true
        task {
            try {
                val data=try {
                    check(readCar("/identity").optString("app")==version) { "Install RoadGuard $version on both car and phone" }
                    readCar("/recordings")
                } catch(e:Exception) { if(!ConnectionRecovery.temporary(e) && resumed) recoveryBlocked=true; runOnUiThread { liveButton.isEnabled=false }; throw e }
                runOnUiThread {
                    if(isDestroyed) return@runOnUiThread
                    liveButton.isEnabled=data.optBoolean("liveAvailable",true)
                    val items=data.getJSONArray("recordings")
                    status.text="● Connected · ${data.getString("status")}\n${data.getString("camera")} · ${items.length()} clips\nLast response ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}"
                    clips=(0 until items.length()).map { items.getJSONObject(it) }
                    renderClips()
                    if(resumed && !autoLiveOpened && liveButton.isEnabled && prefs.getBoolean("autoLive",true)) {
                        autoLiveOpened=true
                        startActivity(Intent(this,LivePlayerActivity::class.java))
                    }
                }
            } finally { runOnUiThread { listing=false } }
        }
    }
    private fun renderClips() {
        if(!::list.isInitialized) return
        list.removeAllViews()
        val filtered=clips.filter { it.getString("name").contains(query,true) && (!keptOnly || it.optBoolean("protected")) }
        if(filtered.isEmpty()) {
            list.addView(LibraryStyle.text(this,if(clips.isEmpty()) "No recordings loaded. Connect to the car and tap Refresh." else "No matching recordings. Try another date or turn off the kept-only filter.",16f,true))
            return
        }
        var day=""
        filtered.take(visibleCount).forEach { entry ->
            val name=entry.getString("name")
            val nextDay=LibraryStyle.day(name)
            if(nextDay!=day) { day=nextDay; list.addView(LibraryStyle.text(this,day,20f)) }
            val active=entry.getBoolean("active")
            val card=LibraryStyle.panel(this)
            card.addView(LibraryStyle.text(this,LibraryStyle.title(name),20f))
            card.addView(LibraryStyle.text(this,"${"%.1f".format(entry.getLong("bytes")/1048576.0)} MB  ·  ${if(active) "Recording now" else "Saved"}${if(entry.optBoolean("protected")) "  ·  KEPT" else ""}",14f,true))
            card.addView(button("Save to phone") {
                if(downloading.get()) { message("Wait for the current download"); return@button }
                pending=entry
                create.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="video/mp4"; putExtra(Intent.EXTRA_TITLE,name) })
            }.apply { isEnabled=!active })
            val actions=LinearLayout(this)
            actions.addView(button("Play") { startActivity(Intent(this,RemotePlayerActivity::class.java).putExtra("id",entry.getString("id")).putExtra("size",entry.getLong("bytes"))) }.apply { isEnabled=!active },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(button(if(entry.optBoolean("backup")) "PC copy" else if(entry.optBoolean("protected")) "Kept ✓" else "Keep") { toggleKeep(entry) }.apply { isEnabled=!entry.optBoolean("backup") },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(button("Details") { showDetails(entry) }.apply { isEnabled=!active && !entry.optBoolean("backup") },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(actions)
            if(active) card.addView(LibraryStyle.text(this,"Still recording. Playback and download become available after this clip is saved.",14f,true))
            list.addView(card)
        }
        if(filtered.size>visibleCount) list.addView(button("Show more (${filtered.size-visibleCount} remaining)") { visibleCount+=30; renderClips() })
    }
    private fun toggleKeep(entry:JSONObject) {
        val keep=!entry.optBoolean("protected")
        val update={ task {
            val c=client("/protect?id=${entry.getString("id")}")
            try {
                c.requestMethod="POST"; c.setRequestProperty("X-Keep-Recording",keep.toString())
                check(c.responseCode==200) { "Could not change protection; refresh and try again" }
                val confirmed=JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getBoolean("protected")
                runOnUiThread { entry.put("protected",confirmed); renderClips(); message(if(confirmed) "Video kept: automatic cleanup will skip it." else "Protection removed. This clip can be deleted by loop recording.") }
            } finally { c.disconnect() }
        } }
        if(keep) update() else AlertDialog.Builder(this).setTitle("Remove protection?").setMessage("Automatic loop recording may delete this video when space is low.").setPositiveButton("Remove") { _,_ -> update() }.setNegativeButton("Cancel",null).show()
    }
    private fun showDetails(entry:JSONObject) { task {
        val details=readCar("/details?id=${entry.getString("id")}")
        runOnUiThread { if(!isDestroyed) AlertDialog.Builder(this).setTitle("Recording details")
            .setMessage("${entry.getString("name")}\n\nDuration: ${details.optLong("durationMs",0)/1000}s\nResolution: ${details.optString("width","?")} × ${details.optString("height","?")}\nSize: ${entry.getLong("bytes")} bytes\nLast written: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.getLong("modified")))}\nFilename uses the car's local clock.\n\nKept: ${entry.optBoolean("protected")}")
            .setPositiveButton("Close",null).show() }
    } }
    private fun startLive() {
        if(!live.compareAndSet(false,true)) return
        task {
            try {
                while(live.get() && !isDestroyed) {
                    val c=client("/live.jpg")
                    var interval=1000L
                    val bytes=try { check(c.responseCode==200) { "Live camera frame unavailable" }; interval=(c.getHeaderField("X-Preview-Interval")?.toLongOrNull() ?: 1000L).coerceIn(250L,2000L); c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Invalid preview image")
                    runOnUiThread { if(live.get() && !isDestroyed) { image.setImageBitmap(bitmap); status.text="LIVE · adaptive preview (up to ${1000/interval} FPS) · ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}" } else bitmap.recycle() }
                    Thread.sleep(interval)
                }
            } finally { live.set(false) }
        }
    }
    private fun locationDetail(loc:JSONObject):String = if(loc.has("latitude"))
        "${loc.optString("message")}\n\nLatitude: ${loc.getDouble("latitude")}\nLongitude: ${loc.getDouble("longitude")}\nAccuracy: ±${loc.optDouble("accuracyMeters")} m\nFix age: ${loc.optLong("ageMs")/1000}s\n${loc.optString("place").ifBlank { "Place name unavailable" }}\n\nUpdates automatically every 15 seconds while open."
        else loc.optString("message","Location unavailable")
    private fun showLocation() { task {
        val loc=readCar("/location?refresh=1")
        runOnUiThread {
            if(isDestroyed)return@runOnUiThread
            val dialog=AlertDialog.Builder(this).setTitle("Car location").setMessage(locationDetail(loc))
                .setPositiveButton("Refresh") { _,_ -> showLocation() }.setNegativeButton("Close",null)
            dialog.setNeutralButton("Live map") { _,_ ->
                startActivity(Intent(this,LiveMapActivity::class.java))
            }
            val shown=dialog.show()
            val update=object:Runnable {
                override fun run() {
                    if(!resumed || isDestroyed || !shown.isShowing)return
                    worker.execute {
                        val next=try {readCar("/location")} catch(_:Exception) {JSONObject().put("message","Location connection interrupted — retrying automatically")}
                        runOnUiThread {
                            if(!resumed || isDestroyed || !shown.isShowing)return@runOnUiThread
                            shown.setMessage(locationDetail(next))
                            handler.postDelayed(this,15000)
                        }
                    }
                }
            }
            shown.setOnDismissListener {handler.removeCallbacks(update)}
            handler.postDelayed(update,15000)
        }
    } }
    private fun download(entry:JSONObject,uri:Uri,resume:Boolean=false) {
        if(!downloading.compareAndSet(false,true)) return
        live.set(false); cancelDownload.set(false)
        try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch(_:Exception) {}
        if(resume && prefs.getString("downloadPin",null)!=prefs.getString("pin",null)) { downloading.set(false); message("Reconnect to the original car/server before resuming this download."); return }
        prefs.edit().putString("downloadEntry",entry.toString()).putString("downloadUri",uri.toString()).putString("downloadPin",prefs.getString("pin",null)).apply()
        task {
            var success=false
            try {
                val expected=entry.getLong("bytes")
                // Seekable document FD gives an exact offset. Never truncate a resumed download.
                val fd=contentResolver.openFileDescriptor(uri,if(resume) "rw" else "rwt") ?: error("Cannot open destination")
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                        var count=if(resume) output.channel.size() else 0L
                        require(count<=expected) { "Partial file is larger than the original video" }
                        output.channel.position(count)
                        var attempts=0
                        while(count<expected && !cancelDownload.get()) {
                            var c:javax.net.ssl.HttpsURLConnection?=null
                            try {
                                c=PhoneRoutes.get(this,"/video?id=${entry.getString("id")}",if(count>0) mapOf("Range" to "bytes=$count-") else emptyMap())
                                if(c.responseCode in listOf(502,503,504))throw java.io.IOException("Car temporarily unavailable")
                                check(c.responseCode==if(count>0) 206 else 200) { "Video unavailable or resume unsupported" }
                                if(count>0)check(c.getHeaderField("Content-Range")=="bytes $count-${expected-1}/$expected") { "Resume offset rejected; original file may have changed" }
                                var updated=0L
                                c.inputStream.use { input ->
                                    val buffer=ByteArray(64*1024)
                                    while(true) {
                                        check(!cancelDownload.get()) { "Download paused; use Resume interrupted download" }
                                        val n=input.read(buffer)
                                        if(n<0) {
                                            if(count!=expected)throw java.io.EOFException("Incomplete transfer")
                                            break
                                        }
                                        check(count+n<=expected) { "Source size changed" }
                                        try {output.write(buffer,0,n)} catch(e:java.io.IOException) {throw IllegalStateException("Cannot write to the download destination",e)}
                                        count+=n
                                        if(SystemClock.elapsedRealtime()-updated>1000) {
                                            val progress="Saving ${entry.getString("name")}\n${count*100/expected.coerceAtLeast(1)}% · Keep both devices connected"
                                            runOnUiThread { downloadStatus.text=progress }
                                            updated=SystemClock.elapsedRealtime()
                                        }
                                    }
                                }
                            } catch(e:java.io.IOException) {
                                val delay=ConnectionRecovery.delayMillis(++attempts)
                                if(cancelDownload.get()||isDestroyed||delay<0||!ConnectionRecovery.temporary(e))throw e
                                c?.disconnect()
                                runOnUiThread { downloadStatus.text="Connection interrupted. Resuming at $count bytes (attempt $attempts/3)…" }
                                Thread.sleep(delay)
                            } finally { c?.disconnect() }
                        }
                        output.channel.force(true)
                        check(count==expected) { "Download interrupted; tap Resume interrupted download" }
                }
                success=true;prefs.edit().remove("downloadEntry").remove("downloadUri").remove("downloadPin").apply()
                message("Video saved to your chosen location.")
                runOnUiThread { downloadStatus.text="Saved to phone: ${entry.getString("name")}" }
                runOnUiThread { if(!isDestroyed) AlertDialog.Builder(this).setTitle("Download complete").setPositiveButton("SHARE") { _,_ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="video/mp4"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"Share recording"))
                }.setNegativeButton("DONE",null).show() }
            } finally { downloading.set(false); if(!success) runOnUiThread { downloadStatus.text="Download interrupted. Tap Resume interrupted download on this phone. Choose a local folder that supports seeking." } }
        }
    }
    private fun hotspotOptions() {
        AlertDialog.Builder(this).setTitle("Phone hotspot setup").setMessage("Recommended: enable your usual 2.4 GHz WPA2 hotspot in Android Settings. Connect the car once; it can remember that network.\n\nTemporary app hotspot can be requested automatically, but its name/password may change and it has no internet. Keep this app open.")
            .setPositiveButton("HOTSPOT SETTINGS") { _,_ ->
                try { startActivity(Intent("android.settings.TETHER_SETTINGS")) } catch(_:Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }.setNeutralButton("TEMPORARY AUTO HOTSPOT") { _,_ -> autoHotspot() }.setNegativeButton("ALREADY CONNECTED",null).show()
    }
    private fun autoHotspot() {
        if(Build.VERSION.SDK_INT<26) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)); return }
        if(hotspot!=null) { message("Temporary hotspot already running. Keep the app open."); return }
        val permission=if(Build.VERSION.SDK_INT>=33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if(ContextCompat.checkSelfPermission(this,permission)!=PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,if(Build.VERSION.SDK_INT>=33) arrayOf(permission) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),442); return
        }
        try {
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).startLocalOnlyHotspot(object:WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r:WifiManager.LocalOnlyHotspotReservation) {
                    if(isDestroyed) { r.close(); return }; hotspot=r
                    @Suppress("DEPRECATION") val ssid=if(Build.VERSION.SDK_INT>=30) r.softApConfiguration.ssid else r.wifiConfiguration?.SSID
                    @Suppress("DEPRECATION") val password=if(Build.VERSION.SDK_INT>=30) r.softApConfiguration.passphrase else r.wifiConfiguration?.preSharedKey
                    AlertDialog.Builder(this@PhoneActivity).setTitle("Connect car to this hotspot")
                        .setMessage("Name: $ssid\nPassword: $password\n\nOn car: SETTINGS → PAIR PHONE → CONNECT CAR TO PHONE HOTSPOT. Then refresh its pairing QR.\nTemporary hotspot has no internet; keep phone app open.")
                        .setPositiveButton("OK",null).show()
                }
                override fun onFailed(reason:Int) { message("Android refused automatic hotspot ($reason). Use Hotspot Settings; older Android may also require Location enabled.") }
                override fun onStopped() { hotspot=null; message("Temporary hotspot stopped; reconnect before viewing") }
            },handler)
        } catch(e:Exception) { message("Cannot start hotspot: ${e.message}. Use Hotspot Settings.") }
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray) { super.onRequestPermissionsResult(r,p,g); if(r==442 && g.isNotEmpty() && g[0]==PackageManager.PERMISSION_GRANTED) autoHotspot() }
    @Suppress("DEPRECATION")
    private fun discover() {
        if(discovery!=null) return
        found.removeAllViews()
        val manager=getSystemService(NSD_SERVICE) as NsdManager
        val resolving=AtomicBoolean(false)
        val seen=mutableSetOf<String>()
        discovery=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t:String?) {}
            override fun onDiscoveryStopped(t:String?) {}
            override fun onStartDiscoveryFailed(t:String?,e:Int) { discovery=null }
            override fun onStopDiscoveryFailed(t:String?,e:Int) {}
            override fun onServiceLost(i:NsdServiceInfo?) {}
            override fun onServiceFound(info:NsdServiceInfo) {
                if(!resolving.compareAndSet(false,true)) return
                manager.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(i:NsdServiceInfo?,e:Int) { resolving.set(false) }
                    override fun onServiceResolved(i:NsdServiceInfo) {
                        resolving.set(false)
                        val ip=i.host?.hostAddress ?: return
                        if(!ip.matches(Regex("[0-9.]+"))) return
                        runOnUiThread { carFound(ip) }
                    }
                })
            }
        }
        try { manager.discoverServices("_roadguard._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery) } catch(_:Exception) { discovery=null }
        handler.postDelayed({ stopDiscovery() },15000)
    }
    private fun carFound(ip:String) {
        if(prefs.getString("host","")!!.endsWith(":8944")) return // Do not replace an explicitly paired server with a LAN car.
        if(!resumed || isDestroyed || (discovered.size>=8 && ip !in discovered)) return
        val first=discovered.add(ip)
        if(first && discovered.size==1 && prefs.getString("token",null)==null) {
            address.setText(ip); message("Car found. Scan its QR or enter the code shown on its Connect phone screen.")
        }
        if(first && discovered.size>1) found.addView(button("USE CAR $ip") { address.setText(ip); codePair() })
        val pin=prefs.getString("pin",null)
        val now=SystemClock.elapsedRealtime()
        if(now-(probeTimes[ip] ?: -10000L)<10000L) return
        probeTimes[ip]=now
        if(pin!=null && prefs.getString("host",null)!=ip && prefs.getString("token",null)!=null) task {
            if(try {PhoneRoutes.recoverCarAddress(this,ip)} catch(_:Exception) {false}) {
                recoveryBlocked=false
                runOnUiThread { address.setText(ip); refresh() }
            }
        }
    }
    private fun stopDiscovery() { discovery?.let { try { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } catch(_:Exception) {} }; discovery=null }
    private val refreshTimer=object:Runnable { override fun run() { if(resumed) { if(!live.get() && !downloading.get()) { refresh(); if(discovery==null) discover() }; handler.postDelayed(this,30000) } } }
    override fun onResume() {
        super.onResume(); resumed=true; discovered.clear(); probeTimes.clear()
        presence=LocalDiscovery(this,false) { ip -> runOnUiThread { carFound(ip) } }.also { it.start() }
        // Observe internet and VPN transports, without requiring validated public internet.
        // A local hotspot may legitimately have no internet validation.
        val manager=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val callback=object:android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:android.net.Network) {
                handler.removeCallbacks(networkReconnect); handler.postDelayed(networkReconnect,1000)
            }
        }
        try {
            manager.registerNetworkCallback(android.net.NetworkRequest.Builder()
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build(),callback)
            networkCallback=callback
        } catch(_:Exception) { /* Periodic reconnect remains available. */ }
        handler.post(refreshTimer); discover()
    }
    override fun onPause() {
        resumed=false; presence?.close(); presence=null; live.set(false)
        networkCallback?.let { try { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } catch(_:Exception) {} }
        networkCallback=null; handler.removeCallbacks(networkReconnect); handler.removeCallbacks(refreshTimer)
        stopDiscovery(); super.onPause()
    }
    override fun onDestroy() { cancelDownload.set(true); live.set(false); handler.removeCallbacksAndMessages(null); if(Build.VERSION.SDK_INT>=26) hotspot?.close(); worker.shutdownNow(); super.onDestroy() }
}
