package com.roadguard.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.net.URL
import java.security.*
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

object LinkSecurity {
    private val pinnedFactories=linkedMapOf<String,SSLSocketFactory>()
    @Synchronized private fun factory(pin:String?,create:()->SSLSocketFactory):SSLSocketFactory {
        if(pin==null)return create() // Unpaired identity probes never share a trusted pool.
        pinnedFactories[pin]?.let {return it}
        val next=create()
        if(pinnedFactories.size>=8)pinnedFactories.remove(pinnedFactories.keys.first())
        pinnedFactories[pin]=next;return next
    }
    fun hash(bytes: ByteArray): String {
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex="0123456789abcdef"
        val result=CharArray(digest.size*2)
        for(i in digest.indices) {
            val value=digest[i].toInt() and 255
            result[2*i]=hex[value ushr 4]
            result[2*i+1]=hex[value and 15]
        }
        return String(result)
    }
    fun secret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Synchronized fun identity(): Pair<SSLContext, String> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Existing keys cannot have their authorizations changed. Preserve v1 but do not reuse it.
        val alias = "roadguard-local-link-v2-tls"
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    // TLS computes its own DigestInfo; Conscrypt needs raw RSA signing permission.
                    // DIGEST_NONE does not disable TLS hashing, encryption or peer authentication.
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=RoadGuard Local Camera"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(1577836800000L)).setCertificateNotAfter(Date(2208988800000L)).build())
                generateKeyPair()
            }
        }
        val cert = store.getCertificate(alias) as X509Certificate
        val key = store.getKey(alias, null) as PrivateKey
        // Fail with the actual keystore exception before a generic peer-closed TLS error.
        val challenge=ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature=Signature.getInstance("NONEwithRSA").run { initSign(key); update(challenge); sign() }
        check(Signature.getInstance("NONEwithRSA").run { initVerify(cert.publicKey); update(challenge); verify(signature) }) {
            "Car keystore raw-RSA signing self-test failed"
        }
        return TlsServerContext.create(key,cert) to hash(cert.encoded)
    }
    // The certificate pin replaces DNS hostname trust for the car's changing LAN IP.
    fun connect(host: String, path: String, pin: String?, token: String? = null): HttpsURLConnection {
        val parts=host.split(':')
        require(parts.size<=2 && parts[0].matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && parts[0].split('.').all { it.toInt() in 0..255 }) { "Enter IPv4, or PC IPv4:8944" }
        val port=if(parts.size==2) parts[1].toIntOrNull() else 8943
        require(port==8943 || port==8944) { "RoadGuard uses port 8943 (car) or 8944 (PC)" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {
                require(!c.isNullOrEmpty())
                if(pin != null && hash(c!![0].encoded) != pin) throw java.security.cert.CertificateException("Car identity changed. Pair again on the car screen.")
            }
        }
        val sockets=factory(pin) {SSLContext.getInstance("TLSv1.2").apply {init(null,arrayOf(trust),SecureRandom())}.socketFactory}
        return (URL("https://${parts[0]}:$port$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sockets
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            connectTimeout = 5000; readTimeout = 10000; instanceFollowRedirects = false
            if(token != null) setRequestProperty("Authorization", "Bearer $token")
        }
    }
}
