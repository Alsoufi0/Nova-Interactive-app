package com.codex.novamessenger

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class GuestAssist(private val activity: MainActivity) {

    fun startGuestAssist(auto: Boolean) {
        activity.guestAssistEnabled = true
        activity.assistBadge.text = "Assist on"
        activity.assistBadge.background = activity.rounded(Good, activity.dp(99))
        activity.setStatus("Guest Assist is watching for nearby body shapes.")
        val greeting = if (auto) {
            "Hello, I am Nova. I can take a message, guide you, or follow with staff approval."
        } else {
            "Guest Assist is on. I can take a message, guide you, or follow with staff approval."
        }
        activity.speakReply(greeting)
        activity.assistHandler.removeCallbacks(activity.guestAssistTick)
        activity.assistHandler.post(activity.guestAssistTick)
    }

    fun stopGuestAssist() {
        activity.guestAssistEnabled = false
        activity.assistHandler.removeCallbacks(activity.guestAssistTick)
        activity.assistBadge.text = "Assist off"
        activity.assistBadge.background = activity.rounded(Neutral, activity.dp(99))
        activity.setStatus("Guest Assist stopped.")
    }

    fun observeGuestPresence() {
        val target = activity.robot.getBodyTargets().firstOrNull()
        if (target == null) {
            activity.assistBadge.text = "Watching"
            return
        }
        activity.assistBadge.text = "${"%.1f".format(target.distanceMeters)}m guest"
        val now = System.currentTimeMillis()
        if (now - activity.lastGuestGreetingAt > activity.vm.guestCooldownSeconds * 1_000L && target.distanceMeters in 0.8..3.0) {
            activity.lastGuestGreetingAt = now
            val greeting = "Hello. I can take a message, guide you to ${activity.destination()}, or follow you. What would you like?"
            activity.robot.speak(greeting).takeIf { it.ok } ?: activity.voice.speak(greeting)
            activity.setStatus("Guest detected. Offered help.")
        }
    }

    fun listenToGuest() {
        startConversationalListen("I am listening. How can I help?")
    }

    fun startConversationalListen(prompt: String?) {
        if (!activity.hasAudioPermission()) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            activity.setStatus("Microphone permission is required for voice commands.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            activity.speakReply("AgentOS voice is active. Ask me to send a message, guide you, alert staff, check on a resident, follow, or open camera detection.")
            return
        }
        if (activity.voiceListening) return
        prompt?.let { activity.speakReply(it) }
        activity.voiceListening = true
        activity.assistBadge.text = "Listening"
        activity.speechRecognizer?.destroy()
        activity.speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    activity.setStatus("Listening for a care concierge command...")
                }

                override fun onBeginningOfSpeech() {
                    activity.setStatus("I hear you.")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    activity.setStatus("Understanding request...")
                }

                override fun onError(error: Int) {
                    activity.voiceListening = false
                    val retryable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    activity.setStatus(if (retryable) "No clear voice command heard." else "Voice command error $error.")
                    if (activity.guestAssistEnabled && retryable) {
                        activity.assistHandler.postDelayed({ if (activity.guestAssistEnabled) startConversationalListen(null) }, 4_000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    activity.voiceListening = false
                    val phrase = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (phrase.isBlank()) {
                        activity.setStatus("No speech result.")
                        return
                    }
                    handleGuestIntent(phrase)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
        }
        runCatching { activity.speechRecognizer?.startListening(intent) }
            .onFailure {
                activity.voiceListening = false
                activity.setStatus("Could not start voice command: ${it.message ?: "unknown error"}")
            }
    }

    fun handleGuestIntent(phrase: String) {
        val lower = phrase.lowercase()
        activity.setStatus("Heard: $phrase")
        when {
            isStopIntent(lower) -> {
                activity.stopAll()
                stopGuestAssist()
                activity.speakReply("Okay, I stopped.")
            }
            isCapabilitiesIntent(lower) -> {
                activity.speakReply(novaCapabilitiesText())
            }
            isMessageIntent(lower) -> {
                val destination = inferDestination(lower) ?: inferDestinationFromWords(lower) ?: activity.destination()
                val body = extractMessageBody(phrase, destination)
                activity.messageDelivery.handleVoiceSendAction(body, destination, "visitor")
            }
            isCareIntent(lower) -> {
                val resident = findResidentFromSpeech(lower)
                val fallbackResident = activity.careRepo.residents().firstOrNull()
                when {
                    lower.contains("medication") || lower.contains("medicine") || lower.contains("appointment") -> {
                        val targetId = resident?.id ?: fallbackResident?.id
                        if (targetId == null) activity.speakReply("I do not have a resident registered yet. Please add residents in the care screen or cloud dashboard.")
                        else activity.careWorkflow.runReminderForResident(targetId)
                    }
                    lower.contains("round") -> activity.careWorkflow.startCareRound()
                    else -> {
                        val targetId = resident?.id ?: fallbackResident?.id
                        if (targetId == null) activity.speakReply("I do not have a resident registered yet. Please add residents before starting check-ins.")
                        else activity.careWorkflow.runResidentCheckIn(targetId)
                    }
                }
            }
            isAlertIntent(lower) -> {
                val priority = if (listOf("urgent", "emergency", "fall", "fell", "pain", "can't breathe", "cannot breathe").any { lower.contains(it) }) "urgent" else "normal"
                activity.careWorkflow.createStaffAlert(priority, inferDestination(lower) ?: inferRoom(lower).orEmpty(), phrase)
            }
            isFollowIntent(lower) -> {
                activity.speakReply("I will follow slowly. Please stay in front of me.")
                activity.startFollowMode()
            }
            isGuideIntent(lower) -> {
                val point = inferDestination(lower) ?: inferDestinationFromWords(lower)
                if (point != null) activity.setDestinationText(point) else inferRoom(lower)?.let { activity.setDestinationText(it) }
                activity.speakReply("I will guide you to ${activity.destination()}.")
                activity.goToDestination()
            }
            isCameraIntent(lower) -> {
                activity.currentPage = "camera"
                activity.setContentView(activity.buildUi())
                if (lower.contains("security") || lower.contains("watch") || lower.contains("scan")) activity.startSecurityWatch() else activity.startCameraFeed()
            }
            else -> {
                activity.speakReply("I can help with messages, visitor guide, resident check-ins, medication reminders, staff alerts, following, and camera detection. Please tell me which one you need.")
                if (activity.guestAssistEnabled) activity.assistHandler.postDelayed({ startConversationalListen(null) }, 1_800)
            }
        }
    }

    private fun inferDestination(lowerPhrase: String): String? {
        val known = activity.lastMapPoints.map { it.name }.ifEmpty {
            listOf("Reception", "Lobby", "Meeting Room", "Office", "Entrance")
        }
        return known.firstOrNull { lowerPhrase.contains(it.lowercase()) }
    }

    private fun inferDestinationFromWords(lowerPhrase: String): String? {
        val aliases = mapOf(
            "front desk" to "Reception",
            "reception" to "Reception",
            "nurse station" to "Reception",
            "nurses station" to "Reception",
            "lobby" to "Lobby",
            "entrance" to "Gate Entrance",
            "gate" to "Gate Entrance",
            "charging" to "Charging Point",
            "charger" to "Charging Point",
            "office" to "Zox Robotics office",
            "conference" to "conference room C",
            "meeting" to "conference room C",
            "printer" to "Printer"
        )
        val target = aliases.entries.firstOrNull { lowerPhrase.contains(it.key) }?.value ?: return null
        return activity.careWorkflow.resolveMapPoint(target)
    }

    private fun inferRoom(lowerPhrase: String): String? =
        Regex("""\b(room|suite|bed)\s*([a-z]?\d{1,4}[a-z]?)\b""").find(lowerPhrase)
            ?.let { "Room ${it.groupValues[2].uppercase()}" }

    private fun findResidentFromSpeech(lowerPhrase: String): CareResident? =
        activity.careRepo.residents().firstOrNull {
            lowerPhrase.contains(it.name.lowercase()) ||
                lowerPhrase.contains(it.name.lowercase().substringBefore(" ")) ||
                lowerPhrase.contains(it.room.lowercase())
        }

    private fun isStopIntent(lower: String): Boolean =
        listOf("stop", "cancel", "wait", "pause", "never mind", "nevermind", "that's enough").any { lower.contains(it) }

    private fun isCapabilitiesIntent(lower: String): Boolean =
        listOf(
            "what can you do",
            "what do you do",
            "what are you",
            "who are you",
            "help me",
            "options",
            "functions",
            "capabilities",
            "what do you offer",
            "how can you help"
        ).any { lower.contains(it) } && !isGuideIntent(lower) && !isMessageIntent(lower) && !isAlertIntent(lower)

    private fun isMessageIntent(lower: String): Boolean =
        listOf(
            "send a message",
            "send message",
            "take a message",
            "record a message",
            "leave a message",
            "deliver a message",
            "can you send",
            "could you send",
            "i want to send",
            "message to",
            "message for",
            "please tell",
            "can you tell",
            "could you tell",
            "i need you to tell",
            "tell reception",
            "tell the front desk",
            "tell nurse",
            "tell the nurse",
            "let reception know",
            "let the nurse know",
            "inform reception",
            "inform the nurse",
            "notify reception"
        ).any { lower.contains(it) }

    private fun isCareIntent(lower: String): Boolean =
        listOf("check on", "check in", "check-in", "care round", "rounds", "resident", "medication", "medicine", "appointment", "reminder").any { lower.contains(it) }

    private fun isAlertIntent(lower: String): Boolean {
        if (isGuideIntent(lower) || isMessageIntent(lower)) return false
        return listOf(
            "alert",
            "call nurse",
            "call a nurse",
            "need a nurse",
            "staff",
            "caregiver",
            "water",
            "thirsty",
            "medication help",
            "medicine help",
            "urgent",
            "emergency",
            "i need help",
            "help now",
            "fell",
            "fall detected",
            "pain"
        ).any { lower.contains(it) }
    }

    private fun isFollowIntent(lower: String): Boolean =
        listOf("follow me", "follow", "come with me", "walk with me", "stay with me").any { lower.contains(it) }

    private fun isGuideIntent(lower: String): Boolean =
        listOf("guide", "take me", "go to", "show me", "navigate", "where is", "how do i get", "help me go", "help me get").any { lower.contains(it) }

    private fun isCameraIntent(lower: String): Boolean =
        listOf("camera", "security", "detect", "detection", "scan", "watch hallway", "watch area", "surveillance").any { lower.contains(it) }

    private fun extractMessageBody(phrase: String, destination: String): String {
        val cleanup = phrase
            .replace(Regex("(?i)^(nova|hey nova|please|can you|could you|i want to|i need to|would you)\\s+"), "")
            .replace(Regex("(?i)\\b(send|take|record|leave|deliver)\\s+(a\\s+)?message\\b"), "")
            .replace(Regex("(?i)\\b(to|for)\\s+${Regex.escape(destination)}\\b"), "")
            .replace(Regex("(?i)\\b(to|for)\\s+(reception|front desk|nurse station|nurses station|lobby|office|conference room|meeting room)\\b"), "")
            .replace(Regex("(?i)^\\s*(that|saying|says|tell|let .* know)\\s+"), "")
            .trim(' ', ',', '.', ':', ';')
        val afterThat = Regex("(?i)\\b(that|saying|says)\\b\\s+(.+)").find(phrase)?.groupValues?.getOrNull(2)
        return (afterThat ?: cleanup).trim().takeIf { it.length >= 4 }.orEmpty()
    }

    private fun novaCapabilitiesText(): String =
        "I am Nova, the care concierge. I can take and deliver messages, guide visitors to saved map points, alert staff, check on residents, deliver medication reminders, follow with staff approval, open camera detection, and report activity to the cloud dashboard."
}
