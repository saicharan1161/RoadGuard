/*
 * Copyright 2017-2023 Jiangdg
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
package com.jiangdg.ausbc.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.natives.YUVUtils
import java.lang.Exception
import java.nio.ByteBuffer

/**
 * Encode h264 by MediaCodec
 *
 * @property width yuv width
 * @property height yuv height
 * @property gLESRender rendered by opengl flag
 * @property isPortrait phone direction capture
 * @author Created by jiangdg on 2022/2/10
 */
class H264EncodeProcessor(
    val width: Int,
    val height: Int,
    private val gLESRender: Boolean = false,
    private val isPortrait: Boolean = true
) : AbstractProcessor(true) {
    private var mReadyListener: OnEncodeReadyListener? = null

    override fun getThreadName(): String = TAG

    override fun handleStartEncode() {
        // Do not force an AVC profile on legacy vendor encoders. Some reject it
        // even though their default H.264 configuration works.
        val names = mutableListOf<String?>(null)
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, true) }
            }.forEach { info ->
                try {
                    if (info.getCapabilitiesForType(MIME).colorFormats.contains(getSupportColorFormat())) {
                        names.add(info.name)
                    }
                } catch (_: Exception) { /* A broken vendor capability query is not fatal. */ }
            }
        } catch (e: Exception) { reportEncoderDiagnostic("Codec enumeration: ${e.message}") }

        var readySurface: Surface? = null
        var lastFailure = "No usable H.264 encoder"
        var started = false
        val triedNames = mutableSetOf<String>()
        attempt@ for (name in names) {
            if (name != null && name in triedNames) continue
            for (fps in listOf(FRAME_RATE, 15)) {
                if (isExit) break@attempt
                var stage = "create"
                var label = name ?: "default"
                var surface: Surface? = null
                try {
                    val codec = if (name == null) MediaCodec.createEncoderByType(MIME)
                        else MediaCodec.createByCodecName(name)
                    mMediaCodec = codec
                    label = codec.name
                    triedNames.add(label)
                    val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        val bitrate = mBitRate ?: getEncodeBitrate(width, height)
                        setInteger(MediaFormat.KEY_BIT_RATE, if (fps == 15) bitrate / 2 else bitrate)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, getSupportColorFormat())
                        if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
                    reportEncoderDiagnostic("Attempt $label: $format")
                    stage = "configure"
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    stage = "createInputSurface"
                    surface = if (gLESRender) codec.createInputSurface() else null
                    stage = "start"
                    codec.start()
                    readySurface = surface
                    started = true
                    reportEncoderDiagnostic("Started $label at ${fps}fps")
                    break@attempt
                } catch (e: Exception) {
                    val diagnostic = (e as? MediaCodec.CodecException)?.diagnosticInfo ?: e.javaClass.simpleName
                    lastFailure = "$label / $stage / ${fps}fps: ${e.message} ($diagnostic)"
                    reportEncoderDiagnostic(lastFailure)
                    handleStopEncode()
                    surface?.release()
                }
            }
        }
        if (!started) {
            if (!isExit) reportEncoderError("H.264 startup failed: $lastFailure")
            return
        }
        try {
            mEncodeState.set(true)
            if (readySurface != null) mReadyListener?.onReady(readySurface)
            doEncodeData()
        } catch (e: Exception) {
            reportEncoderError("H.264 encoder failed: ${e.localizedMessage}")
            Logger.e(TAG, "start h264 media codec failed, err = ${e.localizedMessage}", e)
        } finally {
            handleStopEncode()
        }
    }

    override fun handleStopEncode() {
        mEncodeState.set(false)
        val codec = mMediaCodec
        mMediaCodec = null
        try {
            codec?.stop()
        } catch (e: Exception) {
            Logger.e(TAG, "Stop mediaCodec failed, err = ${e.localizedMessage}", e)
        } finally {
            // stop() is invalid if configure/start failed; release must still run.
            try { codec?.release() } catch (e: Exception) {
                Logger.e(TAG, "Release mediaCodec failed", e)
            }
            mRawDataQueue.clear()
        }
    }

    override fun getPTSUs(bufferSize: Int): Long = System.nanoTime() / 1000L

    override fun processOutputData(
        encodeData: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ): Pair<IEncodeDataCallBack.DataType, ByteBuffer> {
        val type = when {
            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                IEncodeDataCallBack.DataType.H264_SPS
            }
            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> {
                IEncodeDataCallBack.DataType.H264_KEY
            }
            else -> {
                IEncodeDataCallBack.DataType.H264
            }
        }
        return Pair(type, encodeData)
    }

    override fun processInputData(data: ByteArray): ByteArray? {
        return if (gLESRender) {
            null
        } else {
            data.apply {
                if (size != width * height * 3 /2) {
                    return null
                }
                if (isPortrait) {
                    YUVUtils.nativeRotateNV21(data,width, height, 90)
                }
                YUVUtils.nv21ToYuv420sp(data, width, height)
            }
        }
    }

    /**
     * Set on encode ready listener
     *
     * @param listener input surface ready listener
     */
    fun setOnEncodeReadyListener(listener: OnEncodeReadyListener) {
        this.mReadyListener = listener
    }

    private fun getSupportColorFormat(): Int {
        if (gLESRender) {
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private fun getEncodeBitrate(width: Int, height: Int): Int {
        var bitRate = width * height * 20 * 3 * 0.07F
        if (width >= 1920 || height >= 1920) {
            bitRate *= 0.75F
        } else if (width >= 1280 || height >= 1280) {
            bitRate *= 1.2F
        } else if (width >= 640 || height >= 640) {
            bitRate *= 1.4F
        }
        return bitRate.toInt()
    }

    /**
     * Get encode width
     */
    fun getEncodeWidth() = width


    /**
     * Get encode height
     */
    fun getEncodeHeight() = height

    /**
     * On encode ready listener
     */
    interface OnEncodeReadyListener {
        /**
         * On ready
         *
         * @param surface mediacodec input surface for getting raw data
         */
        fun onReady(surface: Surface?)
    }

    companion object {
        private const val TAG = "H264EncodeProcessor"
        private const val MIME = "video/avc"
        private const val FRAME_RATE = 30
        private const val KEY_FRAME_INTERVAL = 1
    }
}
