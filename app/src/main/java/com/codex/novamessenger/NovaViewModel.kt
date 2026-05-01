package com.codex.novamessenger

class NovaViewModel {
    @Volatile var lastStatus = "Starting Nova Concierge."
    @Volatile var lastBattery = "Battery --"
    var clientName = ""
    @Volatile var selectedDestination = "Reception"
    var messageDraft = ""
    var recordingPath: String? = null
    var isRecording = false
    var autoDeliverAfterRecording = false
    @Volatile var guestAssistEnabled = false
    @Volatile var securityEnabled = false
    @Volatile var securityEvents = 0
    var lastSecurityAlertAt = 0L
    var lastGuestGreetingAt = 0L
    @Volatile var lastMapPoints: List<MapPoint> = emptyList()
    @Volatile var currentTaskTitle = "Nova Care Assistant"
    @Volatile var currentTaskStage = "Ready"
    @Volatile var currentTaskNext = "Awaiting request"
    @Volatile var currentTaskProgress = 0
    @Volatile var lastDetectedPerson = "None"
    @Volatile var safetyStopStatus = "Armed"
    var activeRoundIds: List<String> = emptyList()
    var activeRoundIndex = -1
    var voiceListening = false

    fun setTask(title: String, stage: String, next: String, progress: Int) {
        currentTaskTitle = title
        currentTaskStage = stage
        currentTaskNext = next
        currentTaskProgress = progress.coerceIn(0, 100)
        if (progress > 0) safetyStopStatus = "Armed"
    }
}
