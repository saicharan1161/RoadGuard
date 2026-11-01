package com.roadguard.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Binder
import android.os.SystemClock
import android.view.Surface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.usb.USBMonitor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RecorderService : Service(), ICameraStateCallBack {
    private var client: MultiCameraClient? = null
    private val cameras = ConcurrentHashMap<Int, MultiCameraClient.ICamera>()
    @Volatile private var activeCamera: MultiCameraClient.ICamera? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var userPaused = false
    @Volatile private var recordingRequested = false
    private var recoveryFinished = false
    private var recoveryGeneration = 0
    private var recovering=false
    private var nextShareRetry=0L
    private val restoringLink=java.util.concurrent.atomic.AtomicBoolean(false)
    private var nextWifiRetry=0L
    @Volatile private var destroyed = false
    @Volatile private var recordGeneration = 0
    private var lastSavedBytes = 0L
    private var lastProgressAt = 0L
    private val storageWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val storageChecking=java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastStorageCheck=0L
    private var loopRotating=false
    private var segmentSince=0L
    private var rotateAt=0L
    private var stallRecovery=false
    private var recoveryNotBefore=0L
    private var spacePaused=false
    private var mountObserved=false
    private var scanAfter=0L
    private var scanCount=0
    private var lastMonitorReset= -300000L
    private var usbEpoch=0
    private val asked=mutableSetOf<Int>()
    inner class LocalBinder : Binder() { fun service() = this@RecorderService }
    @Volatile private var currentOutput: File? = null
    @Volatile var carLink: CarLink? = null
        private set
    @Volatile var linkError = ""
    private var frameAt = 0L
    private var frameBytes: ByteArray? = null
    val liveVideo:LiveVideoHub=LiveVideoHub { mainHandler.post {
        val camera=activeCamera
        if(camera!=null && camera.isCameraOpened()) {
            if(liveVideoCount()>0) { camera.setEncodeDataCallBack(liveCallback); camera.captureStreamStart() }
            else camera.captureStreamStop()
        }
    } }
    private fun liveVideoCount():Int=liveVideo.count()
    private val liveCallback:com.jiangdg.ausbc.callback.IEncodeDataCallBack=object:com.jiangdg.ausbc.callback.IEncodeDataCallBack {
        override fun onVideoFormat(format:android.media.MediaFormat) {
            try {
                fun bytes(key:String)=format.getByteBuffer(key)?.duplicate()?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                liveVideo.format(format.getInteger(android.media.MediaFormat.KEY_WIDTH),format.getInteger(android.media.MediaFormat.KEY_HEIGHT),bytes("csd-0"),bytes("csd-1"))
            } catch(e:Exception) { StorageHelper.logEvent(this@RecorderService,"Live format unavailable: ${e.message}") }
        }
        override fun onEncodeData(type:com.jiangdg.ausbc.callback.IEncodeDataCallBack.DataType, buffer:java.nio.ByteBuffer, offset:Int, size:Int, timestamp:Long) {
            if(liveVideo.count()==0 || type==com.jiangdg.ausbc.callback.IEncodeDataCallBack.DataType.AAC || type==com.jiangdg.ausbc.callback.IEncodeDataCallBack.DataType.H264_SPS || size<=0) return
            try {
                val data=ByteArray(size)
                buffer.duplicate().apply { position(offset); limit(offset+size); get(data) }
                liveVideo.frame(data,timestamp*1000,type==com.jiangdg.ausbc.callback.IEncodeDataCallBack.DataType.H264_KEY)
            } catch(_:Exception) { /* A viewer must never interrupt recording. */ }
        }
    }
    @Synchronized fun enableSharing(): CarLink {
        check(!destroyed) { "Recorder service stopped" }
        carLink?.let { return it }
        return CarLink(this, { remoteFrame() }, {
            if(recordingRequested || RecorderState.saving) currentOutput?.absolutePath?.plus(".mp4") else null
        }, { remoteInterval() },liveVideo).also {
            try { it.start(10000, true); it.verifyLocalConnection(); it.advertise(); carLink=it }
            catch(e:Exception) { it.close(); throw IllegalStateException("Car secure-server self-test failed: ${e.javaClass.simpleName}: ${e.message}",e) }
            getSharedPreferences("connection", MODE_PRIVATE).edit().putBoolean("sharing",true).apply()
        }
    }
    @Synchronized fun disableSharing() {
        liveVideo.closeAll()
        carLink?.revoke(); carLink?.close(); carLink=null
        getSharedPreferences("connection", MODE_PRIVATE).edit().putBoolean("sharing",false).remove("tokens").apply()
    }
    private fun remoteInterval():Int = when {
        RecorderState.previewFps<20 || QualityPlanner.hot(this) -> 1000
        RecorderState.videoWidth>=1920 -> 250
        RecorderState.videoWidth>=1280 -> 500
        else -> 1000
    }
    @Synchronized private fun remoteFrame(): ByteArray? {
        val interval=remoteInterval()
        if(SystemClock.elapsedRealtime()-frameAt<interval*3/4) return frameBytes
        val camera=activeCamera ?: return null
        val latch=java.util.concurrent.CountDownLatch(1)
        val result=java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>()
        val waiting=java.util.concurrent.atomic.AtomicBoolean(true)
        camera.requestRemoteFrame(if(interval==250) 960 else if(interval==500) 640 else 480) { bitmap ->
            synchronized(result) { if(waiting.get()) result.set(bitmap) else bitmap?.recycle() }
            latch.countDown()
        }
        latch.await(1500,java.util.concurrent.TimeUnit.MILLISECONDS)
        val bitmap=synchronized(result) { waiting.set(false); result.getAndSet(null) } ?: return null
        return try {
            val draw=if(bitmap.isMutable)bitmap else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,true)
            val bytes=try {
                val canvas=android.graphics.Canvas(draw)
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=(draw.width*.031f).coerceIn(11f,24f);typeface=android.graphics.Typeface.MONOSPACE}
                val stamp=java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm:ss Z",java.util.Locale.ENGLISH).format(java.util.Date())
                paint.color=0xb0000000.toInt();canvas.drawRect(4f,draw.height-paint.textSize-12f,12f+paint.measureText(stamp),draw.height-4f,paint)
                paint.color=android.graphics.Color.WHITE;canvas.drawText(stamp,8f,draw.height-9f,paint)
                java.io.ByteArrayOutputStream().also {draw.compress(android.graphics.Bitmap.CompressFormat.JPEG,65,it)}.toByteArray()
            }finally{if(draw!==bitmap)draw.recycle()}
            frameBytes=bytes; frameAt=SystemClock.elapsedRealtime(); bytes
        } finally { bitmap.recycle() }
    }
    private var cameraProfile = 0
    private var qualityPlan:QualityPlanner.Plan?=null
    private var qualityKey=""
    private var profileSince=0L
    private var unhealthySeconds=0
    private var remembered=false
    private var reopening=false
    private var retryPending = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Waiting for USB webcam and storage"))
        RecorderState.status = "Sleeping"
        RecorderState.detail = "Connect a UVC webcam and removable USB storage"
        refreshStorageStatus()
        CarLocationService.ensureStarted(this)
        updateCompatibilityState()
        registerStorageReceiver()
        registerUsbMonitor()
        recoverStorageAndResume()
        mainHandler.post(progressMonitor)
        restoreSavedLinks()
    }

    private fun restoreSavedLinks(){
        val now=SystemClock.elapsedRealtime()
        val prefs=getSharedPreferences("connection",MODE_PRIVATE)
        if(now>=nextWifiRetry && prefs.getBoolean("vpnAutoConnect",false) && Build.VERSION.SDK_INT<29){
            nextWifiRetry=now+60000
            try{
                val cm=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val wifi=applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                if(wifi.isWifiEnabled && cm.activeNetwork==null)wifi.reconnect()
            }catch(_:Exception){}
        }
        if(carLink!=null || now<nextShareRetry || !prefs.getBoolean("sharing",false) || !restoringLink.compareAndSet(false,true))return
        nextShareRetry=now+60000
        Thread({try{synchronized(this){if(!destroyed && prefs.getBoolean("sharing",false)){enableSharing();linkError=""}}}
            catch(e:Exception){linkError=e.message?:"Sharing unavailable"}finally{restoringLink.set(false)}},"roadguard-link-restore").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (client == null && recoveryFinished) registerUsbMonitor()
        when (intent?.action) {
            ACTION_STOP -> stopAndSave()
            ACTION_RESUME -> {
                userPaused = false
                if(activeCamera==null){asked.clear();scanAfter=0}
                activeCamera?.takeIf { it.isCameraOpened() }?.let { startContinuousRecording(it) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    fun hasRecordingInProgress() = recordingRequested || RecorderState.saving

    private fun recoverStorageAndResume() {
        if(recovering || hasRecordingInProgress())return
        recovering=true
        mountObserved=StorageHelper.removableRoot(this)!=null
        recoveryFinished = false
        val generation = ++recoveryGeneration
        Thread({
            try { StorageHelper.recoverInterruptedFiles(applicationContext) }
            finally {
                mainHandler.post {
                    if (!destroyed && generation == recoveryGeneration) {
                        recovering=false
                        recoveryFinished = true
                        registerUsbMonitor()
                        activeCamera?.takeIf { it.isCameraOpened() }?.let { startContinuousRecording(it) }
                    }
                }
            }
        }, "roadguard-recovery").start()
    }

    fun showPreview(surface: Surface?, width: Int, height: Int, applied: () -> Unit = {}) {
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        val camera = activeCamera
        if (camera == null) applied() else camera.setPreviewSurface(surface, width, height, applied)
    }

    fun stopAndSave() {
        userPaused = true
        stallRecovery=false
        spacePaused=false
        loopRotating=false
        if (recordingRequested || activeCamera?.isRecording() == true) {
            RecorderState.saving = true
            RecorderState.status = "Saving"
            RecorderState.detail = "Keep the USB connected until Saved appears"
            activeCamera?.captureVideoStop()
        } else {
            RecorderState.status = "Paused"
            RecorderState.detail = "Recording stopped"
        }
    }

    private val progressMonitor = object : Runnable {
        override fun run() {
            checkStorageHealth()
            if(!destroyed && recoveryFinished && !userPaused && !hasRecordingInProgress() && !retryPending &&
                activeCamera?.isCameraOpened()!=true && SystemClock.elapsedRealtime()>=scanAfter) scanExistingCamera()
            RecorderState.previewFps = activeCamera?.previewFrameRate() ?: 0
            val (bytes, fragments) = activeCamera?.recordingProgress() ?: (0L to 0)
            if (recordingRequested) {
                RecorderState.committedBytes = bytes
                RecorderState.committedFragments = fragments
                if((bytes>=LoopStorage.SEGMENT_BYTES || SystemClock.elapsedRealtime()-segmentSince>=300000L) && !RecorderState.saving && !loopRotating) {
                    val g=recordGeneration
                    rotateAt=SystemClock.elapsedRealtime()
                    val rotatingCamera=activeCamera
                    loopRotating=rotatingCamera?.rotateRecording {file->
                        if(recordGeneration==g && activeCamera===rotatingCamera) currentOutput=File(file.parentFile,file.nameWithoutExtension)
                        mainHandler.post {if(!destroyed && recordGeneration==g){segmentSince=SystemClock.elapsedRealtime();loopRotating=false}}
                    }==true
                } else if(loopRotating && !RecorderState.saving && SystemClock.elapsedRealtime()-rotateAt>20000){
                    // Broken encoders that never send another keyframe still have the save/reopen fallback.
                    RecorderState.saving=true;activeCamera?.captureVideoStop()
                }
                if (bytes != lastSavedBytes) {
                    lastSavedBytes = bytes
                    lastProgressAt = SystemClock.elapsedRealtime()
                    RecorderState.lastCommitAt=lastProgressAt
                } else if (!RecorderState.saving && SystemClock.elapsedRealtime() - lastProgressAt > 15000L) {
                    recordingRequested = false
                    RecorderState.saving = true
                    stallRecovery=true;loopRotating=false
                    RecorderState.recording = false
                    RecorderState.status = "Recording stalled"
                    RecorderState.detail = "No saved writes for 15 seconds. Closing this clip before retrying the camera."
                    activeCamera?.captureVideoStop()
                    updateNotification(RecorderState.detail)
                    StorageHelper.logEvent(this@RecorderService, RecorderState.detail)
                }
            }
            if(RecorderState.recording && !retryPending && !RecorderState.saving) {
                val age=SystemClock.elapsedRealtime()-profileSince
                val memory=ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
                val pressure=memory.lowMemory || QualityPlanner.hot(this@RecorderService) || RecorderState.previewFps<12
                unhealthySeconds=if(age>=30000 && pressure) unhealthySeconds+1 else 0
                val higherResolution=RecorderState.videoWidth.toLong()*RecorderState.videoHeight > 640L*480
                if(AdaptiveQuality.shouldLower(age,unhealthySeconds,higherResolution && cameraProfile<(qualityPlan?.profiles?.lastIndex ?: 0))) {
                    activeCamera?.let { lowerQuality(it,"20 seconds of low FPS, low memory or severe thermal pressure") }
                } else if(!remembered && age>=60000 && !pressure && RecorderState.committedFragments>0 && qualityKey.isNotEmpty()) {
                    getSharedPreferences("quality",MODE_PRIVATE).edit().putInt(qualityKey,cameraProfile).apply()
                    remembered=true
                    StorageHelper.logEvent(this@RecorderService,"Remembered stable auto profile $cameraProfile; ${RecorderState.videoWidth}x${RecorderState.videoHeight}, ${RecorderState.previewFps}fps")
                }
            }
            VpnAssist.tick(this@RecorderService)
            restoreSavedLinks()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun checkStorageHealth() {
        val now=SystemClock.elapsedRealtime()
        if(destroyed || now-lastStorageCheck<5000 || !storageChecking.compareAndSet(false,true)) return
        lastStorageCheck=now
        val session=currentOutput
        storageWorker.execute {
            try {
                val root=StorageHelper.removableRoot(this)
                if(root!=null && (recordingRequested || spacePaused)) StorageHelper.reclaimLoopSpace(this,root) { if(recordingRequested || RecorderState.saving) currentOutput?.let { File(it.absolutePath+".mp4") } else null }
                val free=root?.usableSpace ?: -1L
                val reserve=LoopStorage.reserve(root?.totalSpace ?: 0L)
                mainHandler.post {
                    if(destroyed || currentOutput!=session) return@post
                    if(!mountObserved && root!=null && !hasRecordingInProgress()) {
                        mountObserved=true;refreshStorageStatus();recoverStorageAndResume();return@post
                    }
                    mountObserved=root!=null
                    RecorderState.freeBytes=free
                    if(free<0) RecorderState.storageAlert="USB storage unavailable"
                    else if(free<reserve+LoopStorage.SEGMENT_BYTES) {
                        RecorderState.storageAlert="USB reserve: ${reserve/1000000000.0} GB for music and other files. Waiting for space."
                        if(hasRecordingInProgress() && !RecorderState.saving) {
                            stopAndSave()
                            userPaused=false;spacePaused=true
                            updateNotification(RecorderState.storageAlert)
                            StorageHelper.logEvent(this,RecorderState.storageAlert)
                        }
                    } else {
                        RecorderState.storageAlert=if(spacePaused && free<reserve+LoopStorage.HEADROOM)"Waiting for the USB reserve and write margin" else ""
                        if(spacePaused && !hasRecordingInProgress() && !userPaused && free>=reserve+LoopStorage.HEADROOM) {
                            spacePaused=false;activeCamera?.takeIf { it.isCameraOpened() }?.let { startContinuousRecording(it) }
                        }
                    }
                }
            } catch(_:Exception) { mainHandler.post { if(!destroyed) RecorderState.storageAlert="Cannot check USB free space" } }
            finally { storageChecking.set(false) }
        }
    }

    private fun scanExistingCamera() {
        val now=SystemClock.elapsedRealtime()
        scanAfter=now+listOf(3000L,7000L,15000L,30000L,60000L)[scanCount.coerceAtMost(4)]
        scanCount=(scanCount+1).coerceAtMost(5)
        try {
            val usb=getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
            val devices=usb.deviceList.values.filter { isVideoDevice(it) }.sortedBy {it.deviceId}
            if(scanCount>=5 && now-lastMonitorReset>=300000L && !hasRecordingInProgress() && (devices.isEmpty()||devices.any {usb.hasPermission(it)})) {
                lastMonitorReset=now
                // Reset only our camera monitor. Never unmount or reset the shared storage bus.
                usbEpoch++;stopActiveCamera("Retrying RoadGuard camera connection")
                client?.unRegister();client?.destroy();client=null;cameras.clear();registerUsbMonitor()
            }
            if(devices.isEmpty()||activeCamera!=null)return
            val device=devices.firstOrNull {usb.hasPermission(it)} ?: devices.first()
            if(usb.hasPermission(device) || asked.add(device.deviceId))client?.requestPermission(device)
        } catch(e:Exception){RecorderState.detail="USB scan will retry: ${e.javaClass.simpleName}"}
    }
    private fun registerUsbMonitor() {
        if (client != null) return
        val epoch=usbEpoch
        client = MultiCameraClient(applicationContext, object : com.jiangdg.ausbc.callback.IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                if (destroyed || epoch!=usbEpoch || device == null || !isVideoDevice(device) || activeCamera!=null || !asked.add(device.deviceId)) return
                RecorderState.status = "Camera detected"
                RecorderState.cameraName = "USB camera ${device.vendorId}:${device.productId}"
                val camera = CameraUVC(applicationContext, device)
                cameras[device.deviceId] = camera
                updateNotification("Camera detected; requesting USB access")
                client?.requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                if (epoch!=usbEpoch || device == null) return
                asked.remove(device.deviceId)
                cameras.remove(device.deviceId)?.let { detached ->
                    if (detached == activeCamera) stopActiveCamera("Webcam disconnected")
                    detached.setUsbControlBlock(null)
                }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (destroyed || epoch!=usbEpoch || device == null || !isVideoDevice(device) || ctrlBlock == null || activeCamera != null) return
                val camera = cameras[device.deviceId] ?: CameraUVC(applicationContext, device).also {
                    cameras[device.deviceId] = it
                }
                camera.setUsbControlBlock(ctrlBlock)
                camera.setCameraStateCallBack(this@RecorderService)
                activeCamera = camera
                qualityPlan=QualityPlanner.plan(this@RecorderService)
                qualityKey=LinkSecurity.hash((Build.FINGERPRINT+device.vendorId+":"+device.productId+qualityPlan!!.profiles.joinToString()).toByteArray())
                cameraProfile=getSharedPreferences("quality",MODE_PRIVATE).getInt(qualityKey,0).coerceIn(0,qualityPlan!!.profiles.lastIndex)
                StorageHelper.logEvent(this@RecorderService,qualityPlan!!.explanation)
                retryPending = false
                RecorderState.cameraName = device.productName ?: "USB camera ${device.vendorId}:${device.productId}"
                com.jiangdg.ausbc.render.NightRules.cameraNightHardware=Regex("(?i)(night.?vision|infra.?red|\\bIR\\b|starlight)").containsMatchIn(RecorderState.cameraName)
                com.jiangdg.ausbc.render.NightRules.bypass=getSharedPreferences("quality",MODE_PRIVATE).getBoolean("nativeNightOnly",false)
                RecorderState.status = "Opening camera"
                camera.openCamera<Any>(null, buildCameraRequest(cameraProfile))
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if(epoch==usbEpoch && device!=null && cameras[device.deviceId]===activeCamera)stopActiveCamera("Camera access ended")
            }

            override fun onCancelDev(device: UsbDevice?) {
                if(epoch!=usbEpoch)return
                RecorderState.status = "USB permission required"
                RecorderState.detail = "Open RoadGuard AI and approve this webcam"
                updateNotification("USB camera permission is required")
            }
        }).also { it.register() }
    }

    private fun buildCameraRequest(profile: Int): CameraRequest {
        val plan=qualityPlan ?: QualityPlanner.plan(this).also { qualityPlan=it }
        val requested=plan.profiles[profile.coerceIn(0,plan.profiles.lastIndex)]
        RecorderState.quality="${plan.explanation}\nSelected profile ${profile+1}/${plan.profiles.size}: $requested"
        RecorderState.detail = "Trying auto profile ${profile+1}: $requested"
        return CameraRequest.Builder()
            .setPreviewWidth(requested.width)
            .setPreviewHeight(requested.height)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setPreviewFormat(requested.format)
            .setAspectRatioShow(true)
            .setRawPreviewData(false)
            .setCaptureRawImage(false)
            .create()
    }

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        if(destroyed || activeCamera!==self)return
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                scanCount=0;scanAfter=SystemClock.elapsedRealtime()+15000
                liveVideo.clear()
                self.setEncodeDataCallBack(liveCallback)
                profileSince=SystemClock.elapsedRealtime(); unhealthySeconds=0; remembered=false
                self.getCameraRequest()?.let {
                    RecorderState.videoWidth = it.previewWidth
                    RecorderState.videoHeight = it.previewHeight
                    StorageHelper.logEvent(this, "Camera opened ${it.previewWidth}x${it.previewHeight}")
                    RecorderState.quality+="\nActual camera: ${it.previewWidth}x${it.previewHeight} ${it.previewFormat}"
                }
                self.setPreviewSurface(previewSurface, previewWidth, previewHeight)
                if (!userPaused) startContinuousRecording(self)
                if(liveVideo.count()>0) self.captureStreamStart()
            }
            ICameraStateCallBack.State.CLOSED -> {
                RecorderState.recording = false
                if (retryPending) {
                    RecorderState.status = "Retrying camera"
                } else {
                    RecorderState.status = "Sleeping"
                    RecorderState.detail = "Waiting for a compatible USB webcam"
                }
                releaseWakeLock()
                updateNotification("Waiting for USB webcam")
            }
            ICameraStateCallBack.State.ERROR -> {
                RecorderState.recording = false
                releaseWakeLock()
                if(!retryPending && !lowerQuality(self,msg ?: "Camera profile failed")) {
                    RecorderState.status = "Camera incompatible"
                    RecorderState.detail = msg ?: "All automatic camera profiles failed"
                    updateNotification("Camera incompatible: ${msg ?: "all profiles failed"}")
                }
            }
        }
    }

    private fun lowerQuality(camera:MultiCameraClient.ICamera, reason:String):Boolean {
        if(destroyed || activeCamera!==camera || retryPending || cameraProfile >= (qualityPlan?.profiles?.lastIndex ?: 0)) return false
        cameraProfile++; retryPending=true; unhealthySeconds=0
        RecorderState.status="Adjusting auto quality"
        RecorderState.detail="$reason; saving before changing profile"
        StorageHelper.logEvent(this,"Auto quality step-down: $reason -> ${qualityPlan!!.profiles[cameraProfile]}")
        if(recordingRequested || camera.isRecording()) {
            val requestedProfile=cameraProfile
            RecorderState.saving=true
            camera.captureVideoStop()
            mainHandler.postDelayed({
                if(retryPending && !reopening && activeCamera===camera && cameraProfile==requestedProfile) {
                    retryPending=false; RecorderState.status="Quality change paused"
                    RecorderState.detail="Encoder did not confirm save. Reconnect camera; not forcing a new recording."
                }
            },10000)
        } else reopenLower(camera)
        return true
    }

    private fun retryAfterStall(camera:MultiCameraClient.ICamera){
        if(!stallRecovery)return
        stallRecovery=false
        if(destroyed||userPaused||spacePaused||activeCamera!==camera)return
        val now=SystemClock.elapsedRealtime();val wait=maxOf(2000L,recoveryNotBefore-now)
        recoveryNotBefore=now+wait+60000L
        mainHandler.postDelayed({
            if(!destroyed && !userPaused && !spacePaused && !hasRecordingInProgress() && activeCamera===camera){
                usbEpoch++;stopActiveCamera("Reopening camera after write stall");client?.unRegister();client?.destroy();client=null
                cameras.clear();scanAfter=0;registerUsbMonitor()
            }
        },wait)
    }

    private fun reopenLower(camera:MultiCameraClient.ICamera) {
        if(reopening) return
        if(userPaused) { retryPending=false; return }
        reopening=true
        try { camera.closeCamera() } catch(_:Exception) {}
        mainHandler.postDelayed({
            if(activeCamera!==camera) return@postDelayed
            reopening=false; retryPending=false
            if(!destroyed && activeCamera===camera && !userPaused) {
                try { camera.openCamera<Any>(null,buildCameraRequest(cameraProfile)) }
                catch(e:Exception) { RecorderState.status="Camera retry failed"; RecorderState.detail=e.message ?: "Unknown error" }
            }
        },1500)
    }

    fun resetAutoQuality() {
        if(hasRecordingInProgress()) return
        getSharedPreferences("quality",MODE_PRIVATE).edit().clear().apply()
        qualityKey=""
        qualityPlan=QualityPlanner.plan(this); cameraProfile=0; retryPending=true
        // Reopen preview only while paused; the user presses START after reassessment.
        val camera=activeCamera ?: return
        camera.closeCamera()
        mainHandler.postDelayed({ if(!destroyed && activeCamera===camera) { retryPending=false; camera.openCamera<Any>(null,buildCameraRequest(0)) } },1500)
    }

    private fun startContinuousRecording(camera: MultiCameraClient.ICamera) {
        if (!recoveryFinished || userPaused || recordingRequested || RecorderState.saving || camera.isRecording()) return
        val target = try{StorageHelper.createSession(this)}catch(_:Exception){null}
        if (target == null) {
            RecorderState.status = "Storage required"
            RecorderState.detail = "Connect a writable USB drive. If multiple removable drives are present and Android cannot identify USB, disconnect the other drive."
            updateNotification("USB storage is required; recording has not started")
            return
        }
        RecorderState.storagePath = target.first.absolutePath
        val free=target.first.usableSpace
        RecorderState.freeBytes=free
        if(target.first.totalSpace<=0 || free<LoopStorage.reserve(target.first.totalSpace)+LoopStorage.SEGMENT_BYTES) {
            spacePaused=true
            RecorderState.status="USB space low"
            RecorderState.storageAlert="Reserve protected. Waiting for incremental cleanup or more free USB space."
            updateNotification(RecorderState.storageAlert)
            return
        }
        RecorderState.storageAlert=""
        currentOutput = target.second
        recordingRequested = true
        val generation = ++recordGeneration
        lastSavedBytes = 0
        lastProgressAt = SystemClock.elapsedRealtime()
        RecorderState.recordingStartedAt=lastProgressAt
        segmentSince=lastProgressAt
        RecorderState.lastCommitAt=0
        RecorderState.committedBytes = 0
        RecorderState.committedFragments = 0
        RecorderState.status = "Starting recording"
        RecorderState.detail = "Waiting for the first video section to be saved"
        StorageHelper.logEvent(this, "Recording started: ${target.second.name}.mp4")
        acquireWakeLock()
        camera.captureVideoStart(object : ICaptureCallBack {
            override fun onBegin() {
                if (generation != recordGeneration || RecorderState.saving) return
                RecorderState.recording = true
                RecorderState.status = "Recording"
                RecorderState.detail = target.second.name
                updateNotification("Recording ${target.second.name}")
            }

            override fun onError(error: String?) {
                if (generation != recordGeneration) return
                recordingRequested = false
                loopRotating=false
                RecorderState.saving = false
                RecorderState.recording = false
                RecorderState.status = "Recording error"
                RecorderState.detail = error ?: "Unknown encoder error"
                updateNotification("Recording error: ${error ?: "unknown"}")
                StorageHelper.logEvent(this@RecorderService, RecorderState.detail)
                camera.captureVideoStop()
                releaseWakeLock()
                retryAfterStall(camera)
                // Encoder startup failure may mean the selected resolution is too costly.
                // Do not retry USB/file errors as though they were camera capability failures.
                if(error?.startsWith("H.264 startup failed:")==true) lowerQuality(camera,RecorderState.detail)
            }

            override fun onComplete(path: String?) {
                if (generation != recordGeneration) return
                recordingRequested = false
                RecorderState.saving = false
                RecorderState.recording = false
                RecorderState.status = "Saved"
                if (path != null) RecorderState.committedBytes = File(path).length()
                RecorderState.detail = "Recording saved. You may remove the USB after Android ejects it."
                StorageHelper.logEvent(this@RecorderService, "Saved: $path")
                updateNotification("Recording saved")
                releaseWakeLock()
                if(stallRecovery)retryAfterStall(camera)
                else if(loopRotating && !userPaused && !destroyed) {
                    loopRotating=false
                    mainHandler.post { if(!destroyed && !userPaused && activeCamera===camera) startContinuousRecording(camera) }
                } else if(retryPending) reopenLower(camera)
            }
        }, target.second.absolutePath, 0L)
    }

    private fun stopActiveCamera(reason: String) {
        loopRotating=false
        liveVideo.clear()
        val camera = activeCamera
        activeCamera = null
        retryPending=false; reopening=false
        try { camera?.captureVideoStop() } catch (_: Throwable) {}
        try { camera?.closeCamera() } catch (_: Throwable) {}
        RecorderState.recording = false
        recordingRequested = false
        RecorderState.status = "Sleeping"
        RecorderState.detail = reason
        RecorderState.cameraName = "No camera"
        releaseWakeLock()
        updateNotification(reason)
    }

    private fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_VIDEO) return true
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun refreshStorageStatus() {
        RecorderState.storagePath = StorageHelper.removableRoot(this)?.absolutePath ?: "No removable storage"
    }

    private fun updateCompatibilityState() {
        val result = CompatibilityProbe.run(this)
        RecorderState.compatibility = result.summary
        StorageHelper.saveCompatibilityReport(this, result.report)?.let {
            RecorderState.compatibilityReportPath = it.absolutePath
        }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_MEDIA_MOUNTED -> {
                    refreshStorageStatus()
                    updateCompatibilityState()
                    if (!hasRecordingInProgress()) recoverStorageAndResume()
                }
                Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED -> {
                    val camera = activeCamera
                    try { if (camera?.isRecording() == true) camera.captureVideoStop() } catch (_: Throwable) {}
                    RecorderState.recording = false
                    recordingRequested = false
                    RecorderState.storagePath = "No removable storage"
                    RecorderState.status = "Storage disconnected"
                    RecorderState.detail = "Recording paused; reconnect removable USB storage"
                    updateNotification("Recording paused: storage disconnected")
                }
            }
        }
    }

    private fun registerStorageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(storageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(storageReceiver, filter)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadGuardAI:Recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("RoadGuard AI")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        destroyed = true
        storageWorker.shutdownNow()
        liveVideo.closeAll()
        synchronized(this) { carLink?.close(); carLink=null }
        stopActiveCamera("Service stopped")
        cameras.values.forEach { try { it.closeCamera() } catch (_: Throwable) {} }
        cameras.clear()
        client?.unRegister()
        client?.destroy()
        client = null
        try { unregisterReceiver(storageReceiver) } catch (_: Throwable) {}
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.roadguard.ai.STOP_AND_SAVE"
        const val ACTION_RESUME = "com.roadguard.ai.RESUME"
        private const val CHANNEL_ID = "roadguard_recording"
        private const val NOTIFICATION_ID = 4101
        private const val USB_CLASS_VIDEO = 14
    }
}
