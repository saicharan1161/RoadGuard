/*
 * Copyright 2017-2022 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.encode.muxer

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.MediaStoreUtils
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.utils.Utils
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * MediaMuxer for Mp4
 *
 * @property path mp4 saving path
 * @property durationInSec mp4 file auto divided in seconds
 *
 * @constructor
 * @param context context
 * @param callBack mp4 capture status, see [ICaptureCallBack]
 *
 * @author Created by jiangdg on 2022/2/10
 */
class Mp4Muxer(
    context: Context?,
    callBack: ICaptureCallBack,
    private var path: String? = null,
    private val durationInSec: Long = 0,
    private val isVideoOnly: Boolean = false
) {
    private var mContext: Context? = null
    private var mMediaMuxer: MediaMuxer? = null
    @Volatile private var fragmentedWriter: FragmentedMp4Writer? = null
    private val rotate=java.util.concurrent.atomic.AtomicReference<((File)->Unit)?>(null)
    fun rotateAtKeyframe(done:(File)->Unit):Boolean = fragmentedWriter!=null && !released && !failed && rotate.compareAndSet(null,done)
    private fun openWriter(f:File,fmt:MediaFormat):FragmentedMp4Writer {
        fun bytes(key:String)=fmt.getByteBuffer(key)?.duplicate()?.let {ByteArray(it.remaining()).also {data->it.get(data)}}
        if(!f.createNewFile())throw java.io.IOException("A recording already exists with this time; check the car clock")
        return FragmentedMp4Writer(f,fmt.getInteger(MediaFormat.KEY_WIDTH),fmt.getInteger(MediaFormat.KEY_HEIGHT),bytes("csd-0"),bytes("csd-1"))
    }
    @Volatile private var released = false
    @Volatile private var failed = false
    private var notifiedBegin = false
    private var mFileSubIndex: Int = 0
    @Volatile
    private var mVideoTrackerIndex = -1
    @Volatile
    private var mAudioTrackerIndex = -1
    private var mVideoFormat: MediaFormat? = null
    private var mAudioFormat: MediaFormat? = null
    private var mBeginMillis: Long = 0
    private var mCaptureCallBack: ICaptureCallBack? = null
    private var mMainHandler: Handler = Handler(Looper.getMainLooper())
    private var mOriginalPath: String? = null
    private var mPathEmpty: Boolean = path.isNullOrEmpty()
    private var mVideoPts: Long = 0L
    private var mAudioPts: Long = 0L
    private val mDateFormat by lazy {
        SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
    }
    private val mCameraDir by lazy {
        "${mContext!!.getExternalFilesDir(Environment.DIRECTORY_DCIM)}/Camera"
    }
    private val mCameraAudioDir by lazy {
        "${mContext!!.getExternalFilesDir(Environment.DIRECTORY_MUSIC)}/Camera"
    }

    init {
        this.mCaptureCallBack = callBack
        this.mContext= context
        try {
            if (path.isNullOrEmpty()) {
                val date = mDateFormat.format(System.currentTimeMillis())
                path = "$mCameraDir/VID_AUSBC_$date"
            }
            mOriginalPath = path
            path = "${path}.mp4"
            if (!isVideoOnly) mMediaMuxer = MediaMuxer(path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            mCaptureCallBack?.onError(e.localizedMessage)
            Logger.e(TAG, "init media muxer failed, err = ${e.localizedMessage}", e)
        }
    }

    /**
     * Add tracker
     *
     * @param mediaFormat media format, see [MediaFormat]
     * @param isVideo media type, audio or video
     */
    @Synchronized
    fun addTracker(mediaFormat: MediaFormat?, isVideo: Boolean) {
        if (released || failed || isMuxerStarter() || mediaFormat == null) {
            return
        }
        if (isVideoOnly) {
            if (!isVideo) return
            try {
                mVideoFormat=mediaFormat
                fragmentedWriter=openWriter(File(path!!),mediaFormat)
            } catch (e: Exception) { fail(e.localizedMessage ?: "Cannot create recording") }
            return
        }
        try {
            mMediaMuxer?.apply {
                val tracker = addTrack(mediaFormat)
                if (Utils.debugCamera) {
                    Logger.i(TAG, "addTracker index = $tracker isVideo = $isVideo")
                }
                if (isVideo) {
                    mVideoFormat = mediaFormat
                    mVideoTrackerIndex = tracker
                    if (mAudioTrackerIndex != -1 || isVideoOnly) {
                        start()
                        mMainHandler.post {
                            mCaptureCallBack?.onBegin()
                        }
                        mBeginMillis = System.currentTimeMillis()
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "start media muxer")
                        }
                    }
                } else {
                    mAudioFormat = mediaFormat
                    mAudioTrackerIndex = tracker
                    if (mVideoTrackerIndex != -1) {
                        start()
                        mMainHandler.post {
                            mCaptureCallBack?.onBegin()
                        }
                        mBeginMillis = System.currentTimeMillis()
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "start media muxer")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            release()
            mMainHandler.post {
                mCaptureCallBack?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "addTracker failed, err = ${e.localizedMessage}", e)
        }
    }

    /**
     * write audio(aac) or video(h264) data to media muxer
     *
     * @param outputBuffer encode output buffer, see [MediaCodec]
     * @param bufferInfo encode output buffer info, see [MediaCodec.BufferInfo]
     * @param isVideo media data type, audio or video
     */
    @Synchronized
    fun pumpStream(outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, isVideo: Boolean) {
        if (released || failed || bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (isVideoOnly) {
            if (!isVideo || bufferInfo.size <= 0) return
            try {
                var writer = fragmentedWriter ?: return
                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0)rotate.getAndSet(null)?.let {done->
                    val next=File(File(path!!).parentFile,SimpleDateFormat("yyyy-MM-dd_EEEE_HH-mm-ss-SSS",Locale.ENGLISH).format(Date())+".mp4")
                    writer.close()
                    writer=openWriter(next,mVideoFormat!!)
                    fragmentedWriter=writer;path=next.absolutePath
                    done(next)
                }
                val data = outputBuffer.duplicate().apply {
                    position(bufferInfo.offset)
                    limit(bufferInfo.offset + bufferInfo.size)
                }.slice()
                writer.write(data, bufferInfo.presentationTimeUs,
                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                if (!notifiedBegin && writer.committedFragments > 0) {
                    notifiedBegin = true
                    mMainHandler.post { mCaptureCallBack?.onBegin() }
                }
            } catch (e: Exception) { fail(e.localizedMessage ?: "USB write failed") }
            return
        }
        try {
            if (!isMuxerStarter()) {
                return
            }
            if (bufferInfo.size <= 0) {
                return
            }
            val index = if (isVideo) {
                if (mVideoPts == 0L) {
                    mVideoPts = bufferInfo.presentationTimeUs
                }
                bufferInfo.presentationTimeUs = bufferInfo.presentationTimeUs - mVideoPts
                mVideoTrackerIndex
            } else {
                if (mAudioPts == 0L) {
                    mAudioPts = bufferInfo.presentationTimeUs
                }
                bufferInfo.presentationTimeUs = bufferInfo.presentationTimeUs - mAudioPts
                mAudioTrackerIndex
            }
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
            mMediaMuxer?.writeSampleData(index, outputBuffer, bufferInfo)
            saveNewFileIfNeed()
        } catch (e: Exception) {
            Logger.e(TAG, "pumpStream failed, err = ${e.localizedMessage}", e)
        }
    }

    private fun saveNewFileIfNeed() {
        try {
            val endMillis = System.currentTimeMillis()
            if (durationInSec == 0L) {
                return
            }
            if (endMillis - mBeginMillis <= durationInSec * 1000) {
                return
            }

            mMediaMuxer?.stop()
            mMediaMuxer?.release()
            mMediaMuxer = null
            mAudioTrackerIndex = -1
            mVideoTrackerIndex = -1
            mAudioPts = 0L
            mVideoPts = 0L
            insertDCIM(mContext, path)

            path = "${mOriginalPath}_${++mFileSubIndex}.mp4"
            mMediaMuxer = MediaMuxer(path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            addTracker(mVideoFormat, true)
            addTracker(mAudioFormat, false)
        } catch (e: Exception) {
            mMainHandler.post {
                mCaptureCallBack?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "release media muxer failed, err = ${e.localizedMessage}", e)
        }
    }

    /**
     * Release mp4 muxer resource
     */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        if (isVideoOnly) {
            val writer = fragmentedWriter
            try {
                writer?.close()
                if (!failed && writer != null && writer.committedFragments > 0) {
                    val saved = path
                    mMainHandler.post { mCaptureCallBack?.onComplete(saved) }
                } else if (!failed) fail("No encoded video frames were saved")
            } catch (e: Exception) { fail(e.localizedMessage ?: "USB final save failed") }
            finally { fragmentedWriter = null }
            return
        }
        try {
            mMediaMuxer?.stop()
            mMediaMuxer?.release()
            insertDCIM(mContext, path, true)
            Logger.i(TAG, "stop media muxer")
        } catch (e: Exception) {
            mMainHandler.post {
                mCaptureCallBack?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "release media muxer failed, err = ${e.localizedMessage}", e)
        } finally {
            mMediaMuxer = null
            mAudioTrackerIndex = -1
            mVideoTrackerIndex = -1
            mAudioPts = 0L
            mVideoPts = 0L
        }
    }

    fun getSavePath() = path

    @Synchronized
    fun logEncoder(message: String) {
        // Use the same removable volume as the recording; never internal storage.
        try {
            val destination = path ?: return
            val video = File(destination)
            val root = if(video.parentFile?.name in listOf("Recordings","Car Dash Cam Recordings")) video.parentFile!!.parentFile else video.parentFile?.parentFile?.parentFile
            if(root != null) {
                val diagnostics=File(root,"Diagnostics").apply { mkdirs() }
                val log=File(diagnostics,"EncoderEvents.txt")
                if(log.length()>512*1024) log.renameTo(File(diagnostics,"EncoderEvents.previous.txt"))
                log.appendText("${Date()} ${video.name}: $message\n")
            }
        } catch (e: Exception) { Logger.e(TAG, "Cannot write encoder diagnostics", e) }
    }

    private fun insertDCIM(context: Context?, videoPath: String?, notifyOut: Boolean = false) {
        context?.let { ctx ->
            if (videoPath.isNullOrEmpty()) {
                return
            }
                mMainHandler.post {
                    mCaptureCallBack?.onComplete(this.path)
                }
        }
    }

    @Synchronized
    fun fail(message: String) {
        if (failed) return
        failed = true
        mMainHandler.post { mCaptureCallBack?.onError(message) }
    }

    fun isMuxerStarter() = !released && !failed &&
        (if (isVideoOnly) fragmentedWriter != null else mVideoTrackerIndex != -1 && mAudioTrackerIndex != -1)

    fun recordingProgress(): Pair<Long, Int> = fragmentedWriter?.let {
        it.committedBytes to it.committedFragments
    } ?: (0L to 0)

    private fun getLocalVideoDuration(filePath: String?): Long {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(filePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?:0L
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    companion object {
        private const val TAG = "Mp4Muxer"
    }
}
