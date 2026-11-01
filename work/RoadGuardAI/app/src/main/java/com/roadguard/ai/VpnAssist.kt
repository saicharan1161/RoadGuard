package com.roadguard.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.*

/** Uses Tailscale's exported CONNECT_VPN integration, never hidden APIs or shell/root. */
object VpnAssist {
    private const val PACKAGE="com.tailscale.ipn"
    private var lastAttempt= -60000L
    private var lastCheck= -15000L
    @Volatile private var status="Enable the connection helper below, then complete Tailscale setup once."
    @Synchronized fun tick(context:Context,force:Boolean=false) {
        val prefs=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
        if(prefs.getString("mode","car")=="phone" || !prefs.getBoolean("vpnAutoConnect",false))return
        val now=SystemClock.elapsedRealtime()
        if(!force && now-lastCheck<15000)return
        lastCheck=now
        try {
            context.packageManager.getPackageInfo(PACKAGE,0)
            val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps=manager.allNetworks.mapNotNull {manager.getNetworkCapabilities(it)}
            if(caps.any {it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}) {
                status="Android reports an active VPN. This alone does not prove Tailscale or the remote PC is reachable.";return
            }
            if(caps.none {it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}) {status="Waiting for Wi-Fi/mobile network before requesting VPN connection.";return}
            if(now-lastAttempt<60000)return
            lastAttempt=now
            context.sendBroadcast(Intent("com.tailscale.ipn.CONNECT_VPN").setClassName(PACKAGE,"com.tailscale.ipn.IPNReceiver"))
            status="Tailscale connection requested. If it stays disconnected, open Tailscale to approve/login. Retries are limited to once per minute."
        } catch(_:android.content.pm.PackageManager.NameNotFoundException) {status="Install Tailscale first."}
        catch(e:Exception) {status="Android did not allow the request. Open Tailscale: ${e.javaClass.simpleName}"}
    }
    fun show(activity:Activity) {
        val prefs=activity.getSharedPreferences("connection",Context.MODE_PRIVATE)
        val panel=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12)}
        val detail=TextView(activity).apply {textSize=15f}
        fun update(){detail.text="$status\n\nOne-time setup:\n1. Open Tailscale, sign in and approve its VPN prompt.\n2. In Android VPN settings, select Tailscale and enable Always-on VPN if available.\n3. Allow Tailscale and RoadGuard background activity / unrestricted battery and OEM auto-start.\n\nRoadGuard requests reconnection while its car service runs. It cannot bypass Android permission or manufacturer restrictions. Another active VPN is never replaced automatically. This does not change exit-node, DNS, or block-without-VPN settings."}
        val toggle=CheckBox(activity).apply {text="Auto-connect Tailscale with RoadGuard (car only)";isChecked=prefs.getBoolean("vpnAutoConnect",false)}
        toggle.setOnCheckedChangeListener {_,enabled->prefs.edit().putBoolean("vpnAutoConnect",enabled).apply();tick(activity,true);update()}
        panel.addView(toggle);panel.addView(detail)
        fun button(label:String,action:()->Unit){panel.addView(Button(activity).apply {text=label;setOnClickListener {try {action()}catch(_:Exception){Toast.makeText(activity,"This setting is unavailable on this Android. Open system Settings manually.",Toast.LENGTH_LONG).show()}}})}
        button("OPEN TAILSCALE") {val intent=activity.packageManager.getLaunchIntentForPackage(PACKAGE);if(intent!=null)activity.startActivity(intent)else Toast.makeText(activity,"Install Tailscale first",Toast.LENGTH_LONG).show()}
        button("ANDROID VPN / ALWAYS-ON SETTINGS") {activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}
        button("TAILSCALE BACKGROUND / BATTERY SETTINGS") {activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$PACKAGE")))}
        button("ROADGUARD BACKGROUND / BATTERY SETTINGS") {activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${activity.packageName}")))}
        tick(activity,true);update()
        AlertDialog.Builder(activity).setTitle("Tailscale auto-connect setup").setView(ScrollView(activity).apply {addView(panel)}).setPositiveButton("Close",null).show()
    }
}
