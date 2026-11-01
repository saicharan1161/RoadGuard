package com.roadguard.ai
import java.io.File
import java.io.RandomAccessFile
import android.media.MediaMetadataRetriever
object ClipDuration {
    private val cache=android.util.LruCache<String,Long>(2048)
    fun read(f:File):Long {
        val key="${f.absolutePath}:${f.length()}:${f.lastModified()}"
        cache.get(key)?.let{return it}
        val ms=RandomAccessFile(f,"r").use { r ->
            var pos=0L
            while(pos+8<=r.length()){
                r.seek(pos);val n=r.readInt().toLong() and 0xffffffffL;val kind=r.readInt()
                if(n<8 || n>r.length()-pos || kind==0x6d6f6f66)break
                if(kind==0x73696478 && n in 32..32768){
                    if(r.readInt()!=0)break;r.readInt();val scale=r.readInt();if(scale<=0)break
                    r.skipBytes(10);val count=r.readUnsignedShort();if(32+12L*count!=n)break
                    var total=0L;repeat(count){r.readInt();total+=r.readInt().toLong() and 0xffffffffL;r.readInt()}
                    return@use total*1000/scale
                }
                pos+=n
            };0L
        }.takeIf {it>0} ?: MediaMetadataRetriever().let {m->try {m.setDataSource(f.absolutePath);m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L}finally{m.release()}}
        if(ms>0)cache.put(key,ms);return ms
    }
}
