package com.roadguard.ai

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** Opt-in authenticated sharing. Only clip-protection metadata can be changed remotely. */
class CarLink(private val context: Context, private val frame: () -> ByteArray?, private val active: () -> String?, private val previewInterval:()->Int = {1000}, private val liveVideo:LiveVideoHub?=null) : NanoHTTPD(8943) {
    val fingerprint: String
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val gate=LinkPolicy.PairingGate()
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var cachedFiles = emptyList<File>()
    private var listedAt = 0L
    private val presence=LocalDiscovery(context,true)
    private val version=context.packageManager.getPackageInfo(context.packageName,0).versionName
    private val serverLogger=java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name)
    private val serverLogHandler=object:java.util.logging.Handler() {
        override fun publish(record:java.util.logging.LogRecord?) {
            val failure=record?.thrown ?: return
            val details=generateSequence(failure) { it.cause }.take(5).joinToString(" → ") { "${it.javaClass.simpleName}: ${it.message}" }
            StorageHelper.logEvent(context,"Local sharing transport: $details")
        }
        override fun flush() {}
        override fun close() {}
    }
    init {
        val identity = LinkSecurity.identity()
        fingerprint = identity.second
        // Do not re-enable every supported (including obsolete) protocol on old Android.
        setServerSocketFactory {
            (identity.first.serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket).also { socket ->
                try { TlsServerContext.configure(socket) } catch(e:Exception) { socket.close(); throw e }
            }
        }
        // A changed identity requires explicit QR/code pairing. Do not silently retain old authorizations.
        if(prefs.getString("serverIdentity",null)!=fingerprint) {
            prefs.edit().remove("tokens").putString("serverIdentity",fingerprint).apply()
        }
        serverLogger.addHandler(serverLogHandler)
        // Bound workers/sockets on the 1 GB head unit, including unauthenticated requests.
        setAsyncRunner(object : AsyncRunner {
            private val clients=java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<ClientHandler,Boolean>())
            private val pool=java.util.concurrent.ThreadPoolExecutor(4,4,0L,java.util.concurrent.TimeUnit.MILLISECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(4))
            override fun exec(client:ClientHandler) {
                clients.add(client)
                try { pool.execute(client) } catch(_:java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
            }
            override fun closed(client:ClientHandler) { clients.remove(client) }
            override fun closeAll() { clients.forEach { it.close() }; clients.clear(); pool.shutdownNow() }
        })
    }
    @Synchronized fun pairing(): Pair<String, String> {
        val code = "%08d".format(java.util.Locale.US, SecureRandom().nextInt(100000000))
        val qrSecret = LinkSecurity.secret(); gate.open(code,qrSecret,SystemClock.elapsedRealtime())
        fun compact(hex:String)=android.util.Base64.encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE)
        return code to "RG1|$version|${addresses().firstOrNull() ?: ""}|${compact(fingerprint)}|${compact(qrSecret)}"
    }
    fun verifyLocalConnection() {
        var failures=0
        while(true) {
            val c=LinkSecurity.connect("127.0.0.1","/identity",fingerprint)
            try { check(c.responseCode==200) { "Local secure server returned ${c.responseCode}" }; c.inputStream.close(); return }
            catch(e:Exception) {
                val delay=ConnectionRecovery.delayMillis(++failures)
                if(!ConnectionRecovery.temporary(e) || delay<0) throw e
                StorageHelper.logEvent(context,"Sharing self-test recovery $failures/3: ${e.javaClass.simpleName}")
                c.disconnect(); Thread.sleep(delay)
            } finally { c.disconnect() }
        }
    }
    @Synchronized fun revoke() {
        prefs.edit().remove("tokens").apply(); gate.close()
    }
    fun advertise() {
        presence.start()
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo?) {}
            override fun onServiceUnregistered(i: NsdServiceInfo?) {}
        }
        try { manager.registerService(NsdServiceInfo().apply { serviceName="RoadGuard-Car"; serviceType="_roadguard._tcp."; port=8943 }, NsdManager.PROTOCOL_DNS_SD, nsdListener) } catch (_: Exception) {}
    }
    fun close() {
        presence.close()
        serverLogger.removeHandler(serverLogHandler)
        liveVideo?.closeAll()
        stop()
        nsdListener?.let { try { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it) } catch (_: Exception) {} }
        nsdListener=null
    }
    @Synchronized private fun files(): List<File> {
        if(SystemClock.elapsedRealtime()-listedAt > 5000) {
            cachedFiles = StorageHelper.recordings(context)
            listedAt=SystemClock.elapsedRealtime()
        }
        return cachedFiles
    }
    private fun id(file: File) = LinkSecurity.hash(file.absolutePath.toByteArray())
    @Synchronized private fun pair(value: String): Response {
        val saved=prefs.getStringSet("tokens",emptySet())!!.toMutableSet()
        if(saved.size>=8) return error(Response.Status.FORBIDDEN,"Revoke old pairings on the car first")
        if(!gate.consume(value,SystemClock.elapsedRealtime())) return error(Response.Status.FORBIDDEN,"Wrong, used or expired code. Generate a fresh code on the car.")
        val token=LinkSecurity.secret()
        saved.add(LinkSecurity.hash(token.toByteArray())); prefs.edit().putStringSet("tokens",saved).apply()
        return json(JSONObject().put("token",token))
    }
    override fun serve(s: IHTTPSession): Response {
        try {
            val peer=java.net.InetAddress.getByName(s.remoteIpAddress)
            val bytes=peer.address
            val privateVpn=bytes.size==4 && (bytes[0].toInt() and 255)==100 && (bytes[1].toInt() and 255) in 64..127 &&
                prefs.getBoolean("privateNetwork",false)
            if(!peer.isSiteLocalAddress && !peer.isLinkLocalAddress && !peer.isLoopbackAddress && !privateVpn) return error(Response.Status.FORBIDDEN,"Enable private-network access on car for VPN clients")
            if(s.uri=="/identity" && s.method==Method.GET) return json(JSONObject().put("name","RoadGuard Car").put("app",version).put("protocol",1).put("kind","car"))
            if(s.uri=="/pair" && s.method==Method.POST) {
                if(s.headers["x-roadguard-app"]!=version) return error(Response.Status.CONFLICT,"Install the same latest APK on both car and phone")
                return pair(s.headers["x-pair-code"] ?: "")
            }
            val token=s.headers["authorization"]?.removePrefix("Bearer ") ?: ""
            if(token.isEmpty() || LinkSecurity.hash(token.toByteArray()) !in prefs.getStringSet("tokens",emptySet())!!) return error(Response.Status.UNAUTHORIZED,"Pair this phone first")
            if(s.headers["x-roadguard-app"]!=null && s.headers["x-roadguard-app"]!=version)
                return error(Response.Status.CONFLICT,"Update car, phone and Windows to $version")
            if(s.uri=="/protect" && s.method==Method.POST) {
                val f=files().find { id(it)==s.parameters["id"]?.firstOrNull() } ?: return error(Response.Status.NOT_FOUND,"Recording unavailable")
                val keep=s.headers["x-keep-recording"]
                if(keep!="true" && keep!="false") return error(Response.Status.BAD_REQUEST,"Specify protection")
                StorageHelper.protect(context,f,keep=="true")
                return json(JSONObject().put("protected",RecordingLibrary.protectedClip(f)))
            }
            if(s.method != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED,"Read-only")
            return when(s.uri) {
                "/location" -> {
                    if(s.parameters["refresh"]?.firstOrNull()=="1")CarLocationService.requestRefresh(context)
                    json(CarLocationService.snapshot(context))
                }
                "/live.avc" -> {
                    val hub=liveVideo ?: return error(Response.Status.SERVICE_UNAVAILABLE,"Live video unavailable")
                    val stream=try { hub.subscribe() } catch(_:Exception) { return error(Response.Status.SERVICE_UNAVAILABLE,"Two live viewers already connected") }
                    newChunkedResponse(Response.Status.OK,"application/x-roadguard-avc",stream).apply {
                        addHeader("Cache-Control","no-store"); addHeader("X-RoadGuard-Stream-Version","1")
                    }
                }
                "/recordings" -> {
                    val entries=JSONArray()
                    files().forEach { f -> entries.put(JSONObject().put("id",id(f)).put("name",f.name)
                        .put("bytes",f.length()).put("modified",f.lastModified()).put("protected",RecordingLibrary.protectedClip(f)).put("active",f.absolutePath==active())) }
                    json(JSONObject().put("recordings",entries).put("status",RecorderState.status).put("camera",RecorderState.cameraName))
                }
                "/live.jpg" -> frame()?.let { newFixedLengthResponse(Response.Status.OK,"image/jpeg",it.inputStream(),it.size.toLong()).apply { addHeader("Cache-Control","no-store"); addHeader("X-Preview-Interval",previewInterval().toString()) } }
                    ?: error(Response.Status.SERVICE_UNAVAILABLE,"No fresh camera frame; camera may be disconnected")
                "/details" -> {
                    val f=files().find { id(it)==s.parameters["id"]?.firstOrNull() } ?: return error(Response.Status.NOT_FOUND,"Recording unavailable")
                    if(f.absolutePath==active()) return error(Response.Status.CONFLICT,"Save this recording first")
                    if(s.parameters["timeline"]?.firstOrNull()=="1")return json(JSONObject().put("durationMs",ClipDuration.read(f)))
                    val metadata=android.media.MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(f.absolutePath)
                        json(JSONObject().put("durationMs",ClipDuration.read(f))
                            .put("width",metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                            .put("height",metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)))
                    } finally { metadata.release() }
                }
                "/video" -> {
                    val f=files().find { id(it)==s.parameters["id"]?.firstOrNull() } ?: return error(Response.Status.NOT_FOUND,"Recording unavailable")
                    if(f.absolutePath==active()) return error(Response.Status.CONFLICT,"Recording is still active. Stop and save first.")
                    val size=f.length()
                    val range=s.headers["range"]
                    val bounds=try { LinkPolicy.range(range,size) } catch(_:IllegalArgumentException) { return error(Response.Status.RANGE_NOT_SATISFIABLE,"Invalid range") }
                    val start=bounds[0]; val end=bounds[1]
                    val input=FileInputStream(f); input.channel.position(start)
                    val source=if(RecorderState.recording && s.headers["x-roadguard-background-backup"]=="1")BackupReader(input) else input
                    newFixedLengthResponse(if(range==null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,"video/mp4",source,end-start+1).apply {
                        addHeader("Accept-Ranges","bytes"); addHeader("Cache-Control","no-store")
                        if(range!=null) addHeader("Content-Range","bytes $start-$end/$size")
                    }
                }
                else -> error(Response.Status.NOT_FOUND,"Not found")
            }
        } catch (_: Exception) { return error(Response.Status.SERVICE_UNAVAILABLE,"USB/network unavailable; retry after reconnecting") }
    }
    private fun json(value: JSONObject)=newFixedLengthResponse(Response.Status.OK,"application/json",value.toString()).apply { addHeader("Cache-Control","no-store") }
    private fun error(status: Response.Status, message: String)=newFixedLengthResponse(status,"text/plain",message)
    companion object {
        fun addresses(): List<String> = try { NetworkInterface.getNetworkInterfaces().toList().sortedBy { if(it.name.startsWith("wlan")) 0 else 1 }.flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress } } catch (_: Exception) { emptyList() }
    }
}
