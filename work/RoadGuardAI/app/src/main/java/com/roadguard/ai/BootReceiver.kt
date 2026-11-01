package com.roadguard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("mode", "car") == "phone") return
        val allowed = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
            "android.hardware.usb.action.USB_DEVICE_DETACHED"
        )
        if (intent?.action !in allowed) return
        val service = Intent(context, RecorderService::class.java)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        } } catch(e:IllegalStateException){android.util.Log.w("RoadGuard","Android requires the car app to be opened before service startup",e)}
        catch(e:SecurityException){android.util.Log.w("RoadGuard","Android denied foreground service startup",e)}
    }
}
