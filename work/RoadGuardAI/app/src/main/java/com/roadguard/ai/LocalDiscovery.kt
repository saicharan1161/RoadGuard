package com.roadguard.ai

import android.content.Context
import android.net.ConnectivityManager
import java.net.*

/** Advertises only public presence. Every discovered peer still needs pinned TLS pairing. */
class LocalDiscovery(private val context: Context, private val car: Boolean, private val found: (String)->Unit = {}) {
    @Volatile private var running=false
    @Volatile private var socket: DatagramSocket?=null
    fun start() {
        if(running) return
        running=true
        Thread {
            try {
                val s=DatagramSocket(null)
                socket=s
                s.reuseAddress=true; s.broadcast=true; s.soTimeout=2000
                s.bind(InetSocketAddress(if(car) 0 else 8944))
                while(running) {
                    if(car) {
                        val targets=mutableSetOf("255.255.255.255")
                        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        cm.allNetworks.forEach { n -> cm.getLinkProperties(n)?.routes?.forEach { r ->
                            val g=r.gateway
                            if(g is Inet4Address && g.isSiteLocalAddress) targets.add(g.hostAddress!!)
                        } }
                        NetworkInterface.getNetworkInterfaces().toList().forEach { n -> n.interfaceAddresses.forEach { a -> a.broadcast?.hostAddress?.let { targets.add(it) } } }
                        val bytes="ROADGUARD-CAR-1".toByteArray(Charsets.US_ASCII)
                        targets.forEach { ip -> try { s.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName(ip),8944)) } catch(_:Exception) {} }
                        Thread.sleep(2000)
                    } else {
                        val p=DatagramPacket(ByteArray(128),128)
                        try {
                            s.receive(p)
                            if(p.address is Inet4Address && p.address.isSiteLocalAddress && String(p.data,0,p.length,Charsets.US_ASCII)=="ROADGUARD-CAR-1") found(p.address.hostAddress!!)
                        } catch(_:SocketTimeoutException) {}
                    }
                }
            } catch(_:Exception) { /* mDNS and QR remain available if UDP is blocked. */ }
            finally { socket?.close(); socket=null; running=false }
        }.apply { name="RoadGuard-discovery"; isDaemon=true }.start()
    }
    fun close() { running=false; socket?.close() }
}
