package com.roadguard.ai
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import java.io.IOException
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection

/** Range reads only from an existing authenticated pairing. No token in media URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PairedVideoSource(private val context:Context):BaseDataSource(true){
    private var connection:HttpsURLConnection?=null;private var stream:InputStream?=null
    private var uri:Uri?=null;private var remaining=0L
    override fun getUri()=uri
    override fun open(spec:DataSpec):Long{
        transferInitializing(spec)
        val id=spec.uri.lastPathSegment ?: throw IOException("Missing recording")
        if(!id.matches(Regex("[a-f0-9]{64}")))throw IOException("Invalid recording")
        val end=if(spec.length==C.LENGTH_UNSET.toLong())"" else (spec.position+spec.length-1).toString()
        try{
            val c=PhoneRoutes.get(context,"/video?id=$id",mapOf("Range" to "bytes=${spec.position}-$end"));connection=c
            if(c.responseCode!=206)throw IOException("Recording unavailable (HTTP ${c.responseCode})")
            val bounds=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(c.getHeaderField("Content-Range")?:"") ?: throw IOException("Missing range")
            val a=bounds.groupValues[1].toLong();val b=bounds.groupValues[2].toLong()
            if(a!=spec.position || b<a || b>=bounds.groupValues[3].toLong())throw IOException("Invalid range")
            stream=c.inputStream;remaining=b-a+1;uri=spec.uri;transferStarted(spec);return remaining
        }catch(e:Exception){close();throw IOException("Cannot read paired recording: ${e.message}",e)}
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int{
        if(length==0)return 0;if(remaining==0L)return C.RESULT_END_OF_INPUT
        val n=stream!!.read(buffer,offset,minOf(length.toLong(),remaining).toInt())
        if(n<0)throw IOException("Interrupted recording read");remaining-=n;bytesTransferred(n);return n
    }
    override fun close(){try{stream?.close()}finally{stream=null;connection?.disconnect();connection=null;if(uri!=null){uri=null;transferEnded()}}}
}
