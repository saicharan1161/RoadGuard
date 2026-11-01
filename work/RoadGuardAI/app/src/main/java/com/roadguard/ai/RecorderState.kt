package com.roadguard.ai

object RecorderState {
    @Volatile var recordingStartedAt: Long = 0
    @Volatile var lastCommitAt: Long = 0
    @Volatile var freeBytes: Long = -1
    @Volatile var storageAlert: String = ""
    @Volatile var quality: String = "Auto quality not evaluated"
    @Volatile var status: String = "Starting"
    @Volatile var detail: String = "Waiting for service"
    @Volatile var cameraName: String = "No camera"
    @Volatile var storagePath: String = "No removable storage"
    @Volatile var recording: Boolean = false
    @Volatile var videoWidth: Int = 640
    @Volatile var videoHeight: Int = 480
    @Volatile var previewFps: Int = 0
    @Volatile var committedBytes: Long = 0
    @Volatile var committedFragments: Int = 0
    @Volatile var saving: Boolean = false
    @Volatile var compatibility: String = "Compatibility scan not run"
    @Volatile var compatibilityReportPath: String = ""
}
