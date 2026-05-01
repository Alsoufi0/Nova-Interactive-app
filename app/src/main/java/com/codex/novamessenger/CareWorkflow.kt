package com.codex.novamessenger

import android.os.Handler
import android.os.Looper
import android.util.Log

class CareWorkflow(private val activity: MainActivity) {

    fun runRobotCheck() {
        Log.i("NovaConcierge", "runRobotCheck")
        activity.setStatus("Running Nova check...")
        Thread {
            val targets = activity.robot.getBodyTargets()
            val points = activity.robot.getMapPoints()
            val battery = activity.robot.batteryInfo()
            val tts = activity.robot.speak("Nova Concierge check. Voice is working.")
            activity.runOnUiThread {
                activity.renderPoints(points, updateStatus = false)
                activity.setStatus(
                    "Robot check: sdk=${activity.robot.isRobotSdkAvailable}, voice=${tts.ok}, points=${points.size}, people=${targets.size}, battery=$battery"
                )
            }
        }.start()
    }

    fun startCareRound() {
        val residents = activity.careRepo.residents()
        val first = residents.firstOrNull() ?: return activity.setStatus("No residents are configured.")
        activity.activeRoundIds = residents.map { it.id }
        activity.activeRoundIndex = 0
        activity.setTask("Starting care round", "Starting", "Navigate to ${first.room}", 18)
        activity.careRepo.log("round", "Check-in round started", "Starting with ${first.name}. ${residents.size} residents queued.", first.id, first.mapPoint)
        activity.speakReply("Starting care round. I have ${residents.size} check-ins queued. I will check on ${first.name} first.")
        runResidentCheckIn(first.id, continueRound = true)
    }

    fun runResidentCheckIn(residentId: String?, continueRound: Boolean = false) {
        val resident = activity.careRepo.resident(residentId) ?: return activity.setStatus("Resident not found.")
        activity.setDestinationText(resolveMapPoint(resident.mapPoint))
        activity.setTask("Checking ${resident.name}", "Navigating", "Speak check-in prompt", if (continueRound) 38 else 45)
        activity.careRepo.log("check_in", "Check-in started", "Going to ${resident.name} at ${resident.room}.", resident.id, resident.mapPoint)
        activity.follow.stop()
        activity.setStatus("Going to ${resident.name} for check-in...")
        var spoken = false
        val result = activity.robot.startNavigation(activity.destination()) { status ->
            activity.setStatus(status)
            if (!spoken && isArrivalStatus(status)) {
                spoken = true
                activity.setTask("Checking ${resident.name}", "Speaking", "Complete care log", 85)
                activity.speakReply(resident.checkInPrompt)
                activity.careRepo.log("check_in", "Check-in delivered", resident.checkInPrompt, resident.id, resident.mapPoint)
                if (continueRound) {
                    activity.setTask("Round response window", "Listening", "Continue to next resident", 88)
                    scheduleNextRoundStop(resident)
                } else {
                    activity.setTask("Check-in completed", "Completed", resident.room, 100)
                }
                if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
            }
        }
        if (!result.ok) {
            activity.setTask("Check-in fallback", "Speaking", "Spoken in place", 85)
            activity.speakReply(resident.checkInPrompt)
            activity.careRepo.log("check_in", "Check-in spoken in place", result.message, resident.id, resident.mapPoint)
            if (continueRound) scheduleNextRoundStop(resident) else activity.setTask("Check-in completed", "Completed", resident.room, 100)
        }
        if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
    }

    fun scheduleNextRoundStop(resident: CareResident) {
        val current = activity.activeRoundIds.indexOf(resident.id).takeIf { it >= 0 } ?: activity.activeRoundIndex
        activity.activeRoundIndex = current
        activity.setStatus("Waiting briefly for ${resident.name}'s response before continuing the round.")
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.activeRoundIds.isEmpty() || activity.activeRoundIndex != current) return@postDelayed
            activity.careRepo.log("check_in", "Response window completed", "Nova waited for ${resident.name}'s response before continuing.", resident.id, resident.mapPoint)
            val nextIndex = current + 1
            if (nextIndex >= activity.activeRoundIds.size) {
                activity.activeRoundIds = emptyList()
                activity.activeRoundIndex = -1
                activity.setTask("Care round completed", "Completed", "All residents checked", 100)
                activity.careRepo.log("round", "Care round completed", "All queued residents were checked.", resident.id, resident.mapPoint)
                activity.speakReply("Care round completed. All queued residents have been checked.")
                if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            } else {
                activity.activeRoundIndex = nextIndex
                val next = activity.careRepo.resident(activity.activeRoundIds[nextIndex])
                if (next != null) {
                    activity.setTask("Care round", "Navigating", "Next: ${next.room}", 55)
                    activity.careRepo.log("round", "Continuing care round", "Next check-in: ${next.name}.", next.id, next.mapPoint)
                    activity.speakReply("Thank you. I will continue to ${next.name}.")
                    runResidentCheckIn(next.id, continueRound = true)
                }
            }
        }, 8_000)
    }

    fun runExternalResidentCheckIn(residentId: String, name: String, room: String, mapPoint: String, notes: String) {
        val destinationPoint = resolveMapPoint(mapPoint.ifBlank { room.ifBlank { activity.destination() } })
        val displayName = name.ifBlank { "the resident" }
        val displayRoom = room.ifBlank { destinationPoint }
        val prompt = buildString {
            append("Hello $displayName. This is Nova checking in at $displayRoom. ")
            append("Do you need water, medication help, or staff assistance?")
            if (notes.isNotBlank()) append(" Care note: $notes")
        }
        activity.setDestinationText(destinationPoint)
        activity.setTask("Checking $displayName", "Navigating", "Speak check-in prompt", 45)
        activity.careRepo.log("check_in", "Cloud resident check-in", "Going to $displayName at $displayRoom.", residentId.ifBlank { null }, destinationPoint)
        activity.follow.stop()
        activity.setStatus("Going to $displayName for cloud check-in...")
        var spoken = false
        val result = activity.robot.startNavigation(activity.destination()) { status ->
            activity.setStatus(status)
            if (!spoken && isArrivalStatus(status)) {
                spoken = true
                activity.setTask("Checking $displayName", "Speaking", "Complete care log", 85)
                activity.speakReply(prompt)
                activity.careRepo.log("check_in", "Cloud check-in delivered", prompt, residentId.ifBlank { null }, destinationPoint)
                activity.setTask("Check-in completed", "Completed", displayRoom, 100)
                if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
            }
        }
        if (!result.ok) {
            activity.setTask("Check-in fallback", "Speaking", "Spoken in place", 85)
            activity.speakReply(prompt)
            activity.careRepo.log("check_in", "Cloud check-in spoken in place", result.message, residentId.ifBlank { null }, destinationPoint)
            activity.setTask("Check-in completed", "Completed", displayRoom, 100)
        }
        if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
    }

    fun runNextReminder() {
        val reminder = activity.careRepo.reminders().firstOrNull { it.doneAt == null }
        if (reminder == null) return activity.setStatus("No pending reminders.")
        runReminder(reminder.id)
    }

    fun runReminderForResident(residentId: String) {
        val reminder = activity.careRepo.reminders().firstOrNull { it.residentId == residentId && it.doneAt == null }
            ?: activity.careRepo.reminders().firstOrNull { it.residentId == residentId }
        if (reminder == null) return runResidentCheckIn(residentId)
        runReminder(reminder.id)
    }

    fun runReminder(reminderId: String) {
        val reminder = activity.careRepo.reminders().firstOrNull { it.id == reminderId }
            ?: return activity.setStatus("Reminder not found.")
        val resident = activity.careRepo.resident(reminder.residentId)
        activity.setDestinationText(resolveMapPoint(resident?.mapPoint ?: activity.destination()))
        activity.setTask("Medication reminder", "Navigating", "Speak reminder", 45)
        activity.careRepo.log("reminder", reminder.title, "Going to ${resident?.name ?: "resident"} for ${reminder.timeLabel}.", reminder.residentId, activity.destination())
        activity.follow.stop()
        activity.setStatus("Taking reminder to ${resident?.name ?: "resident"}...")
        var spoken = false
        val result = activity.robot.startNavigation(activity.destination()) { status ->
            activity.setStatus(status)
            if (!spoken && isArrivalStatus(status)) {
                spoken = true
                activity.setTask("Medication reminder", "Speaking", "Mark complete", 85)
                activity.speakReply(reminder.message)
                activity.careRepo.completeReminder(reminder.id)
                activity.careRepo.log("reminder", "Reminder delivered", reminder.message, reminder.residentId, activity.destination())
                activity.setTask("Reminder completed", "Completed", resident?.room ?: activity.destination(), 100)
                if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
            }
        }
        if (!result.ok) {
            activity.setTask("Medication fallback", "Speaking", "Mark complete", 85)
            activity.speakReply(reminder.message)
            activity.careRepo.completeReminder(reminder.id)
            activity.careRepo.log("reminder", "Reminder spoken in place", result.message, reminder.residentId, activity.destination())
            activity.setTask("Reminder completed", "Completed", resident?.room ?: activity.destination(), 100)
        }
        if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
    }

    fun createStaffAlert(priority: String, room: String, message: String) {
        val alert = activity.careRepo.createAlert(priority, room.ifBlank { activity.destination() }, message)
        activity.setTask("Staff alert", "Waiting for staff", alert.room, 80)
        activity.speakReply("I alerted staff. Please wait here while help is requested.")
        activity.setStatus("Staff alert ${alert.priority}: ${alert.message}")
        if (activity.currentPage == "care") activity.runOnUiThread { activity.setContentView(activity.buildUi()) }
    }

    fun runVisitorGuide(destinationName: String) {
        val target = destinationName.ifBlank { activity.destination() }
        activity.setDestinationText(resolveMapPoint(target))
        activity.setTask("Visitor guide", "Navigating", "Arrive at ${activity.destination()}", 45)
        activity.careRepo.log("visitor", "Visitor guide", "Guiding visitor to ${activity.destination()}.", null, activity.destination())
        activity.speakReply("I can guide you to ${activity.destination()}. Please follow me.")
        activity.goToDestination()
    }

    fun resolveMapPoint(preferred: String): String {
        if (activity.lastMapPoints.isEmpty()) return preferred.ifBlank { "Reception" }
        return activity.lastMapPoints.firstOrNull { it.name.equals(preferred, ignoreCase = true) }?.name
            ?: activity.lastMapPoints.firstOrNull { preferred.contains(it.name, ignoreCase = true) || it.name.contains(preferred, ignoreCase = true) }?.name
            ?: activity.lastMapPoints.firstOrNull { it.name.equals(activity.selectedDestination, ignoreCase = true) }?.name
            ?: activity.lastMapPoints.first().name
    }

    fun isArrivalStatus(status: String): Boolean {
        val lower = status.lowercase()
        return listOf("arrive", "complete", "success", "finish", "in range", "in_destination", "到达", "完成").any { lower.contains(it) } ||
            Regex("""navigation result\s+(102|104)\b""").containsMatchIn(lower) ||
            Regex("""navigation result\s+1\s+true""").containsMatchIn(lower)
    }
}
