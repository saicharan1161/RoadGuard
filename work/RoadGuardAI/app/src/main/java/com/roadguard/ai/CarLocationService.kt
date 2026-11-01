package com.roadguard.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** Explicitly enabled location service, separate from recording and its permissions. */
class CarLocationService:Service(),LocationListener {
    private lateinit var manager:LocationManager
    private val worker=Executors.newSingleThreadExecutor()
    private var geocodedAt= -30000L
    private val handler=Handler(Looper.getMainLooper())
    private var lastSubscribe= -60000L
    private var lastManual= -5000L
    private var singlePending=false
    private val singleListener=object:LocationListener {
        override fun onLocationChanged(location:Location) {this@CarLocationService.onLocationChanged(location);cancelSingle()}
        override fun onProviderEnabled(provider:String) {}
        override fun onProviderDisabled(provider:String) {}
        @Deprecated("Legacy callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
    }
    private val singleTimeout=Runnable {cancelSingle()}
    private fun cancelSingle() {
        if(::manager.isInitialized)try {manager.removeUpdates(singleListener)} catch(_:Exception) {}
        singlePending=false;handler.removeCallbacks(singleTimeout)
    }
    private var receiverRegistered=false
    @Volatile private var destroyed=false
    private val changed=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) { subscribe() }
    }
    private val watchdog=object:Runnable {
        override fun run() {
            if(destroyed)return
            if(!allowed(this@CarLocationService)){stopSelf();return}
            val age=fix?.let {LocationPolicy.ageMillis(SystemClock.elapsedRealtimeNanos(),it.elapsedRealtimeNanos)} ?: Long.MAX_VALUE
            if(age>LocationPolicy.FRESH_MS && SystemClock.elapsedRealtime()-lastSubscribe>=LocationPolicy.RETRY_MS)subscribe()
            handler.postDelayed(this,15000)
        }
    }
    private val geocoding=java.util.concurrent.atomic.AtomicBoolean(false)
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("location","Car location sharing",NotificationManager.IMPORTANCE_LOW))
        val notice=NotificationCompat.Builder(this,"location").setSmallIcon(R.drawable.ic_roadguard)
            .setContentTitle("RoadGuard location sharing").setContentText("Authorized phones and PCs can see your car location. Turn off in RoadGuard settings.")
            .setOngoing(true).build()
        try {
            startForeground(33,notice)
            if(!allowed(this)){stopSelf();return}
            manager=getSystemService(LOCATION_SERVICE) as LocationManager
            running=true;instance=this
            val filter=IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {addAction(LocationManager.MODE_CHANGED_ACTION)}
            if(Build.VERSION.SDK_INT>=33)registerReceiver(changed,filter,Context.RECEIVER_NOT_EXPORTED)
            else { @Suppress("DEPRECATION") registerReceiver(changed,filter) }
            receiverRegistered=true
            subscribe();handler.post(watchdog)
        } catch(e:Exception) { problem="Open RoadGuard on car to resume location (${e.javaClass.simpleName})";stopSelf() }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!allowed(this)){stopSelf();return START_NOT_STICKY}
        return START_STICKY
    }
    private fun subscribe() {
        if(destroyed || !::manager.isInitialized)return
        if(SystemClock.elapsedRealtime()-lastSubscribe<2000)return
        if(!allowed(this)){stopSelf();return}
        lastSubscribe=SystemClock.elapsedRealtime()
        try {manager.removeUpdates(this)} catch(_:Exception) {}
        var enabled=0;var subscriptions=0;var failure=""
        for(provider in arrayOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))try {
            if(provider !in manager.allProviders)continue
            if(manager.isProviderEnabled(provider))enabled++
            manager.getLastKnownLocation(provider)?.let {onLocationChanged(it)}
            manager.requestLocationUpdates(provider,LocationPolicy.UPDATE_MS,LocationPolicy.MIN_DISTANCE_METERS,this,Looper.getMainLooper())
            subscriptions++
        } catch(e:SecurityException){problem="Location permission denied; grant it on car";stopSelf();return}
        catch(e:Exception){failure=e.javaClass.simpleName}
        problem=when {
            enabled==0 -> "Android Location/GPS is off or unavailable; enable Location on car"
            subscriptions==0 -> "Cannot subscribe to location: $failure"
            else -> "Waiting for a fresh fix; GPS antenna needs a clear view of the sky"
        }
    }
    private fun refreshFix() {
        if(destroyed || !::manager.isInitialized || !allowed(this))return
        val now=SystemClock.elapsedRealtime()
        if(now-lastManual<5000 || singlePending)return
        lastManual=now
        if(now-lastSubscribe>=LocationPolicy.RETRY_MS)subscribe()
        singlePending=true;handler.postDelayed(singleTimeout,30000)
        for(provider in arrayOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))try {
            if(manager.isProviderEnabled(provider)) {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider,singleListener,Looper.getMainLooper())
            }
        } catch(_:SecurityException) {problem="Location permission denied; grant it on car";cancelSingle();stopSelf();return}
        catch(_:Exception) {}
    }
    override fun onLocationChanged(location:Location) {
        if(destroyed || !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy<0) return
        if(!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0)return
        val age=LocationPolicy.ageMillis(SystemClock.elapsedRealtimeNanos(),location.elapsedRealtimeNanos)
        if(age==Long.MAX_VALUE)return
        val old=fix
        if(old!=null && location.elapsedRealtimeNanos<old.elapsedRealtimeNanos) return
        fix=Location(location)
        if(age>LocationPolicy.FRESH_MS || SystemClock.elapsedRealtime()-geocodedAt<30000L) return
        if(!geocoding.compareAndSet(false,true)) return
        geocodedAt=SystemClock.elapsedRealtime()
        worker.execute {
            try {
                @Suppress("DEPRECATION")
                val address=Geocoder(this,Locale.getDefault()).getFromLocation(location.latitude,location.longitude,1)?.firstOrNull()
                if(address!=null && !destroyed) {
                    place=listOfNotNull(address.locality,address.subLocality,address.subAdminArea,address.adminArea).distinct().joinToString(", ")
                    placeLat=location.latitude; placeLon=location.longitude
                }
            } catch(_:Exception) { /* Coordinates remain available without a geocoder. */ }
            finally { geocoding.set(false) }
        }
    }
    override fun onProviderEnabled(provider:String) {handler.post {subscribe()}}
    override fun onProviderDisabled(provider:String) {handler.post {subscribe()}}
    @Deprecated("Legacy callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
    override fun onDestroy() {
        destroyed=true;handler.removeCallbacksAndMessages(null)
        cancelSingle()
        if(::manager.isInitialized)try {manager.removeUpdates(this)} catch(_:Exception) {}
        if(receiverRegistered)try {unregisterReceiver(changed)} catch(_:Exception) {}
        if(instance===this){instance=null;running=false}
        worker.shutdownNow();super.onDestroy()
    }
    companion object {
        @Volatile private var instance:CarLocationService?=null
        @Volatile private var problem="Open RoadGuard on car to start location sharing"
        private var lastStart= -60000L
        private fun allowed(context:Context)=context.getSharedPreferences("connection",MODE_PRIVATE).let {
            it.getString("mode","car")!="phone" && it.getBoolean("locationSharing",false)
        } && ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        @Synchronized fun ensureStarted(context:Context,visible:Boolean=false) {
            if(!allowed(context) || running)return
            val now=SystemClock.elapsedRealtime()
            if(!visible && now-lastStart<60000)return
            lastStart=now
            if(!visible && Build.VERSION.SDK_INT>=29 && ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                problem="Open RoadGuard on car: Android requires a visible app to start location sharing";return
            }
            try {ContextCompat.startForegroundService(context,Intent(context,CarLocationService::class.java))}
            catch(e:Exception){problem="Open RoadGuard on car to resume location (${e.javaClass.simpleName})"}
        }
        fun requestRefresh(context:Context) {
            ensureStarted(context)
            instance?.let {service->service.handler.post {service.refreshFix()}}
        }
        @Volatile private var fix:Location?=null
        @Volatile private var running=false
        @Volatile private var place=""
        @Volatile private var placeLat=0.0
        @Volatile private var placeLon=0.0
        fun snapshot(context:Context):JSONObject {
            val enabled=context.getSharedPreferences("connection",MODE_PRIVATE).getBoolean("locationSharing",false)
            val json=JSONObject().put("enabled",enabled).put("running",running)
            if(!enabled) return json.put("message","Location sharing is off")
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return json.put("message","Grant location permission on car")
            ensureStarted(context)
            val loc=fix ?: return json.put("message",problem)
            val age=LocationPolicy.ageMillis(SystemClock.elapsedRealtimeNanos(),loc.elapsedRealtimeNanos)
            val fresh=LocationPolicy.fresh(running,age)
            val distance=FloatArray(1); Location.distanceBetween(loc.latitude,loc.longitude,placeLat,placeLon,distance)
            return json.put("latitude",loc.latitude).put("longitude",loc.longitude).put("accuracyMeters",loc.accuracy)
                .put("ageMs",age).put("fresh",fresh).put("fixTime",loc.time).put("provider",loc.provider)
                .put("place",if(distance[0]<200) place else "").put("message",if(fresh) "Current location fix" else "Last known position — not live. $problem")
        }
    }
}
