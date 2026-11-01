package com.roadguard.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Explicitly paired routes only. Fallback never crosses car identities or retries mutations. */
object PhoneRoutes {
    fun remember(context:Context,host:String,pin:String,token:String,carPin:String) {
        val prefs=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
        val old=JSONArray(prefs.getString("routes","[]"))
        val next=JSONArray().put(JSONObject().put("host",host).put("pin",pin).put("token",token).put("carPin",carPin))
        for(i in 0 until old.length()) if(next.length()<8 && old.getJSONObject(i).getString("host")!=host)next.put(old.getJSONObject(i))
        prefs.edit().putString("routes",next.toString()).putString("carPin",carPin).apply()
    }
    fun get(context:Context,path:String,headers:Map<String,String> = emptyMap()):HttpsURLConnection {
        val prefs=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
        val current=JSONObject().put("host",prefs.getString("host","")).put("pin",prefs.getString("pin","")).put("token",prefs.getString("token",""))
        val routes=mutableListOf(current)
        val saved=JSONArray(prefs.getString("routes","[]"));val carPin=prefs.getString("carPin","")
        if(!carPin.isNullOrBlank()) for(i in 0 until saved.length()) {
            val route=saved.getJSONObject(i)
            if(route.optString("carPin")==carPin && route.getString("host")!=current.getString("host"))routes.add(route)
        }
        var error:Exception?=null
        routes.forEachIndexed { index,route ->
            val c=LinkSecurity.connect(route.getString("host"),path,route.getString("pin"),route.getString("token"))
            try {
                headers.forEach { (k,v)->c.setRequestProperty(k,v) }
                c.setRequestProperty("X-RoadGuard-App",context.packageManager.getPackageInfo(context.packageName,0).versionName)
                val code=c.responseCode
                if(code in arrayOf(502,503,504) && index<routes.lastIndex) {c.disconnect();return@forEachIndexed}
                return c
            } catch(e:Exception) {
                c.disconnect()
                if(e is SSLException || !ConnectionRecovery.temporary(e))throw e
                error=e
            }
        }
        throw error ?: java.io.IOException("No registered server is reachable")
    }
}
