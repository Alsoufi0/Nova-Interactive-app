package com.codex.novamessenger

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class CareWorkflow(private val activity: MainActivity) {

    fun runRobotCheck() {
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
        val dest = resolveMapPoint(resident.mapPoint)
        activity.runOnUiThread { activity.setDestinationText(dest) }
        activity.setTask("Checking ${resident.name}", "Navigating", "Speak check-in prompt", if (continueRound) 38 else 45)
        activity.careRepo.log("check_in", "Check-in started", "Going to ${resident.name} at ${resident.room}.", resident.id, dest)
        activity.follow.stop()
        activity.setStatus("Going to ${resident.name} for check-in...")
        val handled = AtomicBoolean(false)
        fun onArrival() {
            if (!handled.compareAndSet(false, true)) return
            activity.runOnUiThread {
                activity.setTask("Checking ${resident.name}", "Speaking", "Listening for response", 85)
                activity.speakReply(resident.checkInPrompt)
                activity.careRepo.log("check_in", "Check-in delivered", resident.checkInPrompt, resident.id, dest)
                if (continueRound) {
                    activity.setTask("Round response window", "Listening", "Continue to next resident", 88)
                    scheduleNextRoundStop(resident)
                } else {
                    activity.setTask("Check-in completed", "Completed", resident.room, 100)
                    activity.handleAfterMission("Check-in for ${resident.name}")
                }
                if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            }
        }
        val result = activity.robot.startNavigation(dest) { status ->
            activity.setStatus(status)
            val lower = status.lowercase()
            when {
                isArrivalStatus(status) -> onArrival()
                lower.contains("error") || lower.contains("fail") -> onArrival()
            }
        }
        if (!result.ok) onArrival()
        activity.runOnUiThread { if (activity.currentPage == "care") activity.setContentView(activity.buildUi()) }
    }

    fun scheduleNextRoundStop(resident: CareResident) {
        val current = activity.activeRoundIds.indexOf(resident.id).takeIf { it >= 0 } ?: activity.activeRoundIndex
        activity.activeRoundIndex = current
        val waitMs = (activity.vm.roundWaitSeconds * 1_000L).coerceAtLeast(6_000L)
        val promptMs = (activity.vm.roundPromptSeconds * 1_000L).coerceIn(3_000L, waitMs - 2_000L)
        activity.setStatus("Waiting ${activity.vm.roundWaitSeconds}s for ${resident.name}'s response.")
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            if (activity.activeRoundIds.isEmpty() || activity.activeRoundIndex != current) return@postDelayed
            activity.speakReply("Is there anything else you need before I continue?")
            activity.setStatus("Checking if ${resident.name} needs anything else...")
        }, promptMs)
        mainHandler.postDelayed({
            if (activity.activeRoundIds.isEmpty() || activity.activeRoundIndex != current) return@postDelayed
            activity.careRepo.log("check_in", "Response window completed", "Nova waited for ${resident.name}'s response.", resident.id, resident.mapPoint)
            val nextIndex = current + 1
            if (nextIndex >= activity.activeRoundIds.size) {
                activity.activeRoundIds = emptyList()
                activity.activeRoundIndex = -1
                activity.setTask("Care round completed", "Completed", "All residents checked", 100)
                activity.careRepo.log("round", "Care round completed", "All queued residents checked.", resident.id, resident.mapPoint)
                activity.speakReply("Care round completed. All residents have been checked. Have a wonderful day.")
                activity.handleAfterMission("Care round")
                if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            } else {
                activity.activeRoundIndex = nextIndex
                val next = activity.careRepo.resident(activity.activeRoundIds[nextIndex])
                if (next != null) {
                    activity.setTask("Care round", "Navigating", "Next: ${next.room}", 55)
                    activity.careRepo.log("round", "Continuing care round", "Next: ${next.name}.", next.id, next.mapPoint)
                    activity.speakReply("Thank you. I will continue to ${next.name}.")
                    runResidentCheckIn(next.id, continueRound = true)
                }
            }
        }, waitMs)
    }

    fun runExternalResidentCheckIn(residentId: String, name: String, room: String, mapPoint: String, notes: String) {
        val dest = resolveMapPoint(mapPoint.ifBlank { room.ifBlank { activity.destination() } })
        val displayName = name.ifBlank { "the resident" }
        val displayRoom = room.ifBlank { dest }
        val prompt = buildString {
            append("Hello $displayName. This is Nova checking in at $displayRoom. ")
            append("Do you need water, medication help, or staff assistance?")
            if (notes.isNotBlank()) append(" Care note: $notes")
        }
        activity.runOnUiThread { activity.setDestinationText(dest) }
        activity.setTask("Checking $displayName", "Navigating", "Speak check-in prompt", 45)
        activity.careRepo.log("check_in", "Cloud resident check-in", "Going to $displayName at $displayRoom.", residentId.ifBlank { null }, dest)
        activity.follow.stop()
        activity.setStatus("Going to $displayName for cloud check-in...")
        val handled = AtomicBoolean(false)
        fun onArrival() {
            if (!handled.compareAndSet(false, true)) return
            activity.runOnUiThread {
                activity.setTask("Checking $displayName", "Speaking", "Complete care log", 85)
                activity.speakReply(prompt)
                activity.careRepo.log("check_in", "Cloud check-in delivered", prompt, residentId.ifBlank { null }, dest)
                activity.setTask("Check-in completed", "Completed", displayRoom, 100)
                activity.handleAfterMission("Check-in for $displayName")
                if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            }
        }
        val result = activity.robot.startNavigation(dest) { status ->
            activity.setStatus(status)
            val lower = status.lowercase()
            when {
                isArrivalStatus(status) -> onArrival()
                lower.contains("error") || lower.contains("fail") -> onArrival()
            }
        }
        if (!result.ok) onArrival()
        activity.runOnUiThread { if (activity.currentPage == "care") activity.setContentView(activity.buildUi()) }
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
        val dest = resolveMapPoint(resident?.mapPoint ?: activity.destination())
        activity.runOnUiThread { activity.setDestinationText(dest) }
        activity.setTask("Medication reminder", "Navigating", "Speak reminder", 45)
        activity.careRepo.log("reminder", reminder.title, "Going to ${resident?.name ?: "resident"} for ${reminder.timeLabel}.", reminder.residentId, dest)
        activity.follow.stop()
        activity.setStatus("Taking reminder to ${resident?.name ?: "resident"}...")
        val handled = AtomicBoolean(false)
        fun onArrival() {
            if (!handled.compareAndSet(false, true)) return
            activity.runOnUiThread {
                activity.setTask("Medication reminder", "Speaking", "Mark complete", 85)
                activity.speakReply(reminder.message)
                activity.careRepo.completeReminder(reminder.id)
                activity.careRepo.log("reminder", "Reminder delivered", reminder.message, reminder.residentId, dest)
                activity.setTask("Reminder completed", "Completed", resident?.room ?: dest, 100)
                activity.handleAfterMission("Medication reminder")
                if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            }
        }
        val result = activity.robot.startNavigation(dest) { status ->
            activity.setStatus(status)
            val lower = status.lowercase()
            when {
                isArrivalStatus(status) -> onArrival()
                lower.contains("error") || lower.contains("fail") -> onArrival()
            }
        }
        if (!result.ok) onArrival()
        activity.runOnUiThread { if (activity.currentPage == "care") activity.setContentView(activity.buildUi()) }
    }

    fun createStaffAlert(priority: String, room: String, message: String) {
        val resolvedRoom = room.ifBlank { activity.destination() }
        val alert = activity.careRepo.createAlert(priority, resolvedRoom, message)
        val speakMessage = alert.message

        // After delivering the alert the robot waits 5 minutes at the location,
        // announces if staff has not yet arrived, then returns via handleAfterMission.
        fun scheduleAlertReturn() {
            activity.setTask("Staff alert", "Waiting for staff", resolvedRoom, 100)
            if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            Handler(Looper.getMainLooper()).postDelayed({
                activity.speakReply("Staff has not yet arrived. I will return to my station.")
                activity.careRepo.log("alert", "Alert timeout", "Waited 5 minutes at $resolvedRoom. Returning.", null, resolvedRoom)
                activity.handleAfterMission("Staff alert")
                if (activity.currentPage == "care") activity.setContentView(activity.buildUi())
            }, ALERT_WAIT_MS)
        }

        if (room.isNotBlank()) {
            val dest = resolveMapPoint(resolvedRoom)
            activity.runOnUiThread { activity.setDestinationText(dest) }
            activity.follow.stop()
            activity.setTask("Staff alert", "Navigating", "Speak alert at $resolvedRoom", 55)
            activity.setStatus("Alert: navigating to $resolvedRoom...")
            val handled = AtomicBoolean(false)
            fun onArrival() {
                if (!handled.compareAndSet(false, true)) return
                activity.runOnUiThread {
                    activity.setTask("Staff alert", "Speaking", "Waiting for staff", 85)
                    activity.speakReply(speakMessage)
                    scheduleAlertReturn()
                }
            }
            val result = activity.robot.startNavigation(dest) { status ->
                activity.setStatus(status)
                val lower = status.lowercase()
                when {
                    isArrivalStatus(status) -> onArrival()
                    lower.contains("error") || lower.contains("fail") -> onArrival()
                }
            }
            if (!result.ok) {
                activity.runOnUiThread {
                    activity.speakReply(speakMessage)
                    scheduleAlertReturn()
                }
            }
        } else {
            activity.runOnUiThread {
                activity.speakReply(speakMessage)
                scheduleAlertReturn()
            }
        }
        activity.runOnUiThread { if (activity.currentPage == "care") activity.setContentView(activity.buildUi()) }
    }

    companion object {
        private const val ALERT_WAIT_MS = 5 * 60 * 1000L
    }

    fun runVisitorGuide(destinationName: String) {
        val target = destinationName.ifBlank { activity.destination() }
        activity.setDestinationText(resolveMapPoint(target))
        val dest = activity.destination()
        activity.setTask("Visitor guide", "Navigating", "Arrive at $dest", 45)
        activity.careRepo.log("visitor", "Visitor guide", "Guiding visitor to $dest.", null, dest)
        activity.speakReply("I can guide you to $dest. Please follow me.")
        activity.follow.stop()
        val handled = AtomicBoolean(false)
        val result = activity.robot.startNavigation(dest) { status ->
            activity.setStatus(status)
            val lower = status.lowercase()
            when {
                isArrivalStatus(status) -> {
                    if (!handled.compareAndSet(false, true)) return@startNavigation
                    activity.runOnUiThread {
                        activity.setTask("Visitor guide", "Arrived", dest, 90)
                        activity.speakReply("We have arrived at $dest. Have a great day.")
                        activity.handleAfterMission("Visitor guide")
                    }
                }
                lower.contains("error") || lower.contains("fail") -> {
                    if (!handled.compareAndSet(false, true)) return@startNavigation
                    activity.runOnUiThread {
                        activity.speakReply("I was unable to navigate to $dest. I apologize for the inconvenience.")
                        activity.handleAfterMission("Visitor guide")
                    }
                }
            }
        }
        if (!result.ok) {
            activity.speakReply("Navigation is not available. $dest is this way.")
            activity.handleAfterMission("Visitor guide")
        }
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
