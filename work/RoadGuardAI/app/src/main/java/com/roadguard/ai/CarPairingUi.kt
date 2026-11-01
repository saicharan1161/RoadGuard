package com.roadguard.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

object CarPairingUi {
    fun show(activity: Activity, service: RecorderService) {
        Toast.makeText(activity,"Preparing secure local connection…",Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val link=service.enableSharing()
                val (code,payload)=link.pairing()
                val metrics=activity.resources.displayMetrics
                val landscape=metrics.widthPixels>metrics.heightPixels
                val size=minOf((metrics.widthPixels*if(landscape) 0.40 else 0.80).toInt(),(metrics.heightPixels*if(landscape) 0.64 else 0.40).toInt())
                val bitmap=try { Bitmap.createBitmap(PairingQr.pixels(payload,size),size,size,Bitmap.Config.ARGB_8888) } catch(_:Exception) { null }
                activity.runOnUiThread {
                    if(activity.isFinishing) { bitmap?.recycle(); return@runOnUiThread }
                    val body=LinearLayout(activity).apply { orientation=if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setPadding(12,4,12,4); gravity=android.view.Gravity.CENTER }
                    // Explicit square bounds: never squash or clip the QR on short head-unit displays.
                    if(bitmap!=null) body.addView(ImageView(activity).apply { setImageBitmap(bitmap); scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(android.graphics.Color.WHITE) },LinearLayout.LayoutParams(size,size))
                    val details=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(16,0,0,0) }
                    details.addView(TextView(activity).apply { text="Code: $code"; textSize=26f; setTextColor(android.graphics.Color.BLACK) })
                    details.addView(TextView(activity).apply { text="Open PHONE mode on your phone.\nScan QR or enter this code.\nBoth devices must use your phone's hotspot.\n\nValid for 2 minutes.\nSecurity check: ${link.fingerprint.take(12)}\nCar: ${CarLink.addresses().joinToString()}\nApp: ${activity.packageManager.getPackageInfo(activity.packageName,0).versionName}"; textSize=15f })
                    body.addView(ScrollView(activity).apply { addView(details) },if(landscape) LinearLayout.LayoutParams(0,size,1f) else LinearLayout.LayoutParams(-1,-2))
                    val dialog=AlertDialog.Builder(activity).setTitle("Connect phone").setView(body)
                        .setPositiveButton("CLOSE",null).setNeutralButton("NEW CODE") { _,_ -> show(activity,service) }.create()
                    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    dialog.show()
                    dialog.window?.setLayout((metrics.widthPixels*0.96).toInt(),-2)
                }
            } catch(e:Exception) {
                val details=generateSequence(e as Throwable?) { it.cause }.take(5).joinToString("\n") { "${it.javaClass.simpleName}: ${it.message}" }
                StorageHelper.logEvent(activity,"Sharing startup failed: $details")
                activity.runOnUiThread { if(!activity.isFinishing) AlertDialog.Builder(activity).setTitle("Car connection test failed")
                    .setMessage("$details\n\nSend a photo of this message. This test runs inside the car; changing hotspot settings will not fix a local server failure.").setPositiveButton("CLOSE",null).show() }
            }
        }.start()
    }
    @Suppress("DEPRECATION")
    private fun wifi(a: Activity) {
        if(Build.VERSION.SDK_INT>=29) { a.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); return }
        val layout=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(20,10,20,10) }
        val name=EditText(a).apply { hint="Phone hotspot name (SSID)"; isSingleLine=true }
        val password=EditText(a).apply { hint="Hotspot password"; inputType=129; isSingleLine=true }
        layout.addView(name); layout.addView(password)
        AlertDialog.Builder(a).setTitle("Connect to phone hotspot").setMessage("Enter the name and password shown on the phone. Use a 2.4 GHz WPA2 hotspot for this old stereo.")
            .setView(layout).setNegativeButton("CANCEL",null).setNeutralButton("WI-FI SETTINGS") { _,_ -> a.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .setPositiveButton("CONNECT") { _,_ ->
                try {
                    val ssid=name.text.toString(); val pass=password.text.toString()
                    require(ssid.isNotBlank() && pass.length in 8..63) { "Enter hotspot name and 8–63 character password" }
                    fun quoted(s:String)="\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""
                    val manager=a.applicationContext.getSystemService(Activity.WIFI_SERVICE) as WifiManager
                    if(!manager.isWifiEnabled) { manager.isWifiEnabled=true; throw IllegalStateException("Wi-Fi enabled. Wait a moment, then connect again.") }
                    val config=WifiConfiguration().apply { SSID=quoted(ssid); preSharedKey=quoted(pass); allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK) }
                    val id=manager.addNetwork(config)
                    check(id>=0 && manager.enableNetwork(id,true)) { "Android refused connection; use Wi-Fi Settings" }
                    manager.saveConfiguration(); manager.reconnect()
                    Toast.makeText(a,"Connecting. Refresh the pairing screen after Wi-Fi connects.",Toast.LENGTH_LONG).show()
                } catch(e:Exception) { Toast.makeText(a,e.message,Toast.LENGTH_LONG).show() }
            }.show()
    }
}
