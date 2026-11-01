package com.roadguard.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.MediaCodecList
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CompatibilityProbe {
    data class Result(val summary: String, val report: String)

    fun run(context: Context): Result {
        val packageManager = context.packageManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val hasUsbHost = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val devices = usbManager.deviceList.values.toList()
        val videoDevices = devices.filter(::isVideoDevice)
        val avcEncoders = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
                .map { it.name }
        } catch (_: Throwable) { emptyList() }
        val internalCameras = try { cameraManager.cameraIdList.toList() } catch (_: Throwable) { emptyList() }
        val removable = StorageHelper.removableRoot(context)
        val ramMb = memoryInfo.totalMem / (1024L * 1024L)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ENGLISH).format(Date())

        val blockers = mutableListOf<String>()
        if (!hasUsbHost) blockers += "Android firmware does not advertise USB-host support"
        if (videoDevices.isEmpty()) blockers += "No UVC/video-class webcam is currently visible"
        if (removable == null) blockers += "No writable removable recording storage is mounted"
        if (avcEncoders.isEmpty()) blockers += "No Android H.264/AVC encoder is advertised"

        val profile = when {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && ramMb >= 3500 -> "HIGH: try 1080p MJPEG, then fall back"
            ramMb >= 1500 -> "BALANCED: try 720p MJPEG, then fall back"
            else -> "LOW: start at 640x480 MJPEG/YUYV"
        }
        val summary = if (blockers.isEmpty()) "Capabilities detected; recording needs a successful test" else blockers.joinToString("; ")
        val report = buildString {
            appendLine("RoadGuard AI compatibility report")
            appendLine("Generated: $timestamp")
            appendLine("App version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
            appendLine()
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("RAM: $ramMb MB")
            appendLine("Selected profile: $profile")
            appendLine()
            appendLine("USB host advertised: $hasUsbHost")
            appendLine("Connected USB devices: ${devices.size}")
            devices.forEach { device ->
                appendLine("- ${device.productName ?: device.deviceName}; VID=${device.vendorId}; PID=${device.productId}; class=${device.deviceClass}; interfaces=${device.interfaceCount}; video=${isVideoDevice(device)}")
            }
            appendLine("Detected UVC/video devices: ${videoDevices.size}")
            appendLine("Internal camera IDs: ${internalCameras.joinToString().ifBlank { "none visible" }}")
            appendLine("H.264 encoders: ${avcEncoders.joinToString().ifBlank { "none" }}")
            appendLine("Removable storage: ${removable?.absolutePath ?: "not mounted"}")
            appendLine()
            appendLine("Result: $summary")
            appendLine("Software can retry formats and resolutions, but cannot add missing USB-host hardware, kernel USB support, electrical power, or proprietary manufacturer drivers.")
        }
        return Result(summary, report)
    }

    private fun isVideoDevice(device: android.hardware.usb.UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_VIDEO || device.deviceClass == UsbConstants.USB_CLASS_MISC) return true
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == USB_CLASS_VIDEO) return true
        }
        return false
    }

    private const val USB_CLASS_VIDEO = 14
}
