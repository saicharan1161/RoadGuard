package com.roadguard.ai

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageHelper {
    @Synchronized fun reclaimLoopSpace(context:Context,root:File,active:()->File?) {
        val appRoot=File(root,"RoadGuardAI")
        if(root.totalSpace<=0)return
        LoopStorage.reclaimFolders(listOf(RecordingLibrary.folder(appRoot),File(appRoot,"Recordings")),{ root.usableSpace },active,
            { _ -> },LoopStorage.reserve(root.totalSpace)+LoopStorage.HEADROOM,1,2*LoopStorage.SEGMENT_BYTES)
    }
    @Synchronized
    fun recoverInterruptedFiles(context: Context) {
        val root = removableRoot(context) ?: return
        // Only the newest session can have been open at the last power loss.
        // Avoid walking every fragment of the entire archive at every ignition.
        RecordingLibrary.list(File(root,"RoadGuardAI")).take(1).forEach { file ->
            try {
                val removed = com.jiangdg.ausbc.encode.muxer.FragmentedMp4Writer.recover(file)
                if (removed > 0) logEvent(context, "Recovered ${file.name}: discarded $removed incomplete trailing bytes")
            } catch (e: Exception) { logEvent(context, "Recovery check ${file.name}: ${e.message}") }
        }
    }
    private val sessionFormat = SimpleDateFormat("yyyy-MM-dd_EEEE_HH-mm-ss-SSS", Locale.ENGLISH)

    fun removableRoot(context: Context): File? {
        val writable=context.getExternalFilesDirs(null)
            .filterNotNull()
            .filter { dir ->
                try {
                    Environment.isExternalStorageRemovable(dir) && dir.canWrite()
                } catch (_: Throwable) {
                    false
                }
            }
        if(writable.size<=1)return writable.firstOrNull()
        // Don't silently choose the first SD card when the stereo has two removable volumes.
        if(android.os.Build.VERSION.SDK_INT>=24){
            val sm=context.getSystemService(android.os.storage.StorageManager::class.java)
            writable.singleOrNull{file->try{sm.getStorageVolume(file)?.getDescription(context)?.contains("USB",true)==true}catch(_:Exception){false}}?.let{return it}
        }
        return writable.singleOrNull { File(it,"RoadGuardAI/Car Dash Cam Recordings").isDirectory }
    }

    fun createSession(context: Context): Pair<File, File>? {
        val root = removableRoot(context) ?: return null
        val recordings = RecordingLibrary.folder(File(root,"RoadGuardAI"))
        if (!recordings.exists() && !recordings.mkdirs()) return null
        // Prevent cooperative Android media scanners from indexing dashcam segments as songs.
        File(recordings.parentFile,".nomedia").let { if(!it.exists()) it.createNewFile() }
        val stamp = synchronized(sessionFormat) { sessionFormat.format(Date()) }
        // One MP4 directly in Recordings per continuous session; no failed-attempt folders.
        return recordings to File(recordings, stamp)
    }

    fun recordings(context:Context):List<File> = removableRoot(context)?.let {
        RecordingLibrary.list(File(it,"RoadGuardAI"))
    } ?: emptyList()

    // Shares the cleanup lock, so a confirmed Keep operation cannot race loop deletion.
    @Synchronized fun protect(context:Context,file:File,keep:Boolean) {
        val root=removableRoot(context) ?: error("Connect USB storage first")
        RecordingLibrary.protect(File(root,"RoadGuardAI"),file.canonicalFile,keep)
        logEvent(context,"Recording protection ${if(keep) "enabled" else "removed"}: ${file.name}")
    }

    fun deleteAppFiles(context: Context): Boolean {
        var ok = true
        context.getExternalFilesDirs(null).filterNotNull().forEach { root ->
            val target = File(root, "RoadGuardAI")
            if (target.exists()) ok = target.deleteRecursively() && ok
        }
        return ok
    }

    fun saveCompatibilityReport(context: Context, report: String): File? {
        val root = removableRoot(context) ?: return null
        val diagnostics = File(root, "RoadGuardAI/Diagnostics")
        if (!diagnostics.exists() && !diagnostics.mkdirs()) return null
        val target = File(diagnostics, "CompatibilityReport.txt")
        return try { target.writeText(report); target } catch (_: Exception) { null }
    }

    @Synchronized
    fun logEvent(context: Context, message: String) {
        try {
            val root = removableRoot(context) ?: return
            val dir = File(root, "RoadGuardAI/Diagnostics").apply { mkdirs() }
            val file = File(dir, "RecorderEvents.txt")
            if (file.length() > 512 * 1024) file.renameTo(File(dir, "RecorderEvents.previous.txt"))
            file.appendText("${Date()} | $message\n")
        } catch (_: Exception) { /* A missing drive must never crash the camera service. */ }
    }
}
