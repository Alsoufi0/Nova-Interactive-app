package com.codex.novamessenger

import java.util.concurrent.atomic.AtomicBoolean

class MessageDelivery(private val activity: MainActivity) {

    fun template(text: String) {
        activity.setMessageText(text)
        activity.setStatus("Template loaded.")
    }

    fun templateCard() = activity.card().also { card ->
        card.addView(activity.buttonRow(
            activity.actionButton("Visitor", Neutral) { template("Your visitor has arrived and is waiting at ${activity.destination()}.") },
            activity.actionButton("Delivery", Neutral) { template("A delivery has arrived. Please come to ${activity.destination()} when available.") },
            activity.actionButton("Meeting", Neutral) { template("Your meeting guest is here. Nova can guide them when you are ready.") }
        ))
        card.addView(activity.buttonRow(
            activity.actionButton("VIP", Neutral) { template("A VIP guest has arrived. Please send someone to ${activity.destination()} for a personal welcome.") },
            activity.actionButton("Support", Neutral) { template("A guest needs assistance at ${activity.destination()}. Please send a team member.") },
            activity.actionButton("Pickup", Neutral) { template("Someone is waiting at ${activity.destination()} for pickup or escort.") }
        ))
    }

    fun askAndRecord() {
        if (!activity.hasAudioPermission()) return activity.setStatus("Microphone permission is required to record a message.")
        if (activity.isRecording) return activity.setStatus("Already recording. Press Stop + Save when finished.")
        activity.saveSettings()
        activity.hideKeyboard()
        val destination = activity.destination()
        val prompt = "What do you want $destination to know?"
        activity.setTask("Recording message", "Starting", "Record visitor message", 20)
        activity.voice.speak(prompt)
        val result = runCatching { activity.voice.startRecording(destination) }
        if (result.isSuccess) {
            activity.recordingPath = result.getOrThrow().absolutePath
            activity.isRecording = true
            activity.recordingBadge.text = "Recording"
            activity.recordingBadge.background = activity.rounded(Danger, activity.dp(99))
            activity.setStatus(prompt)
        } else {
            activity.setStatus("Could not start recording: ${result.exceptionOrNull()?.message ?: "unknown error"}")
        }
    }

    fun dictateText() {
        if (!activity.hasAudioPermission()) return activity.setStatus("Microphone permission is required for dictation.")
        activity.saveSettings()
        activity.hideKeyboard()
        val destination = activity.destination()
        val prompt = "What do you want $destination to know?"
        activity.voice.speak(prompt)
        activity.setStatus(prompt)
        activity.voice.transcribeOnce(prompt) { text ->
            if (text.isBlank()) activity.setStatus("No speech text captured. You can type the message instead.")
            else {
                activity.setMessageText(text)
                activity.setStatus("Captured text for $destination.")
            }
        }
    }

    fun stopRecordingAndSave() {
        if (!activity.isRecording) return activity.setStatus("No recording is active.")
        val file = activity.voice.stopRecording()
        activity.isRecording = false
        activity.recordingBadge.text = "Ready"
        activity.recordingBadge.background = activity.rounded(Good, activity.dp(99))
        val sender = activity.currentClient().ifBlank { "a guest" }
        val saved = saveMessage(file?.absolutePath ?: activity.recordingPath, allowEmptyText = true, prompt = "Voice message from $sender")
        if (saved != null) activity.setTask("Message saved", "Ready to deliver", saved.destination, 35)
        if (activity.autoDeliverAfterRecording && saved != null) {
            activity.autoDeliverAfterRecording = false
            activity.speakReply("I will deliver this message to ${saved.destination}.")
            deliverMessage(saved)
        }
    }

    fun saveTextOnlyMessage(): NovaMessage? {
        val sender = activity.currentClient().ifBlank { "a guest" }
        return saveMessage(null, allowEmptyText = false, prompt = "Text message from $sender")
    }

    fun saveMessage(audioPath: String?, allowEmptyText: Boolean, prompt: String): NovaMessage? {
        activity.saveSettings()
        val text = activity.currentMessage()
        if (!allowEmptyText && text.isBlank()) {
            activity.setStatus("Type or dictate a message first.")
            return null
        }
        val message = NovaMessage(
            id = System.currentTimeMillis(),
            destination = activity.destination(),
            prompt = prompt,
            text = text,
            audioPath = audioPath,
            createdAt = System.currentTimeMillis()
        )
        activity.repo.save(message)
        activity.setMessageText("")
        refreshMessages()
        activity.setStatus("Saved message for ${message.destination}.")
        return message
    }

    fun speakCurrentMessage() {
        val text = activity.currentMessage()
        if (text.isBlank()) return activity.setStatus("Type, dictate, or select a message first.")
        val robotTts = activity.robot.speak(text)
        if (!robotTts.ok) activity.voice.speak(text)
        activity.setStatus("Speaking message for ${activity.destination()}.")
    }

    fun sendCurrentMessageToPoint() {
        activity.saveSettings()
        val text = activity.currentMessage()
        val created = if (text.isNotBlank()) saveTextOnlyMessage() else null
        val message = created
            ?: activity.repo.all().firstOrNull { it.deliveredAt == null && it.destination.equals(activity.destination(), ignoreCase = true) }
            ?: activity.repo.all().firstOrNull { it.deliveredAt == null }
        if (message == null) return activity.setStatus("Create or select a message first.")
        deliverMessage(message)
    }

    fun deliverMessage(message: NovaMessage) {
        activity.setDestinationText(message.destination)
        activity.follow.stop()
        activity.setTask("Delivering message", "Navigating", "Play message from visitor", 55)
        activity.setStatus("Taking message to ${message.destination}...")
        val played = AtomicBoolean(false)
        val result = activity.robot.startNavigation(message.destination) { status ->
            activity.setStatus(status)
            val lower = status.lowercase()
            val arrived = listOf("arrive", "complete", "success", "finish", "到达", "完成").any { lower.contains(it) } ||
                Regex("""navigation result\s+(102|104)\b""").containsMatchIn(lower) ||
                Regex("""navigation result\s+1\s+true""").containsMatchIn(lower) ||
                lower.contains("in range") || lower.contains("in_destination")
            if (arrived && played.compareAndSet(false, true)) {
                activity.runOnUiThread { playDeliveredMessage(message) }
            }
        }
        if (!result.ok) activity.setStatus(result.message)
    }

    fun refreshMessages() {
        if (!activity.messageListReady) return
        activity.queueBadge.text = "Queue ${activity.repo.pendingCount()}"
        activity.messageList.removeAllViews()
        val messages = activity.repo.all()
        if (messages.isEmpty()) {
            activity.messageList.addView(activity.emptyState("No saved messages yet. Record or save a text message to start a delivery queue."))
            return
        }
        messages.forEach { message -> activity.messageList.addView(messageCard(message)) }
    }

    fun messageCard(message: NovaMessage) = activity.card().also { card ->
        val delivered = message.deliveredAt != null
        card.addView(android.widget.TextView(activity).apply {
            text = "${message.destination}${if (delivered) " - delivered" else " - pending"}"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (delivered) Muted else Text)
        })
        card.addView(android.widget.TextView(activity).apply {
            text = message.text.ifBlank { message.prompt.ifBlank { "Voice recording saved" } }
            textSize = 15f
            setTextColor(Text)
            setPadding(0, activity.dp(6), 0, activity.dp(8))
        })
        card.addView(activity.buttonGrid(
            2,
            activity.actionButton("Navigate") {
                activity.setDestinationText(message.destination)
                activity.goToDestination()
            },
            activity.actionButton("Play") {
                playDeliveredMessage(message)
            },
            activity.actionButton("Load") {
                activity.setDestinationText(message.destination)
                activity.setMessageText(message.text)
                activity.setStatus("Loaded message for editing.")
            },
            activity.actionButton("Delete", Danger) {
                activity.repo.delete(message.id)
                refreshMessages()
                activity.setStatus("Deleted saved message.")
            }
        ))
    }

    fun playDeliveredMessage(message: NovaMessage) {
        activity.setTask("Delivering message", "Speaking", "Mark delivered", 85)
        val played = runCatching {
            if (message.audioPath != null) {
                val sender = senderFromPrompt(message.prompt)
                val spoken = activity.robot.speak("Voice message from $sender.")
                if (!spoken.ok) activity.voice.speak("Voice message from $sender.")
                activity.voice.playAudio(message.audioPath)
            } else {
                val sender = senderFromPrompt(message.prompt)
                val spoken = activity.robot.speak("Message from $sender: ${message.text}")
                if (!spoken.ok) activity.voice.speak(message.text)
            }
        }.isSuccess
        if (played) {
            activity.repo.markDelivered(message.id)
            refreshMessages()
            activity.setTask("Message delivered", "Completed", message.destination, 100)
            activity.setStatus("Delivered message at ${message.destination}.")
            activity.handleAfterMission("Message delivery")
        } else activity.setStatus("Could not play the saved message.")
    }

    fun handleVoiceSendAction(message: String, destinationName: String, senderName: String) {
        activity.currentPage = "message"
        activity.setContentView(activity.buildUi())
        val destination = destinationName.ifBlank { inferDestination(message.lowercase()) ?: activity.destination() }
        val sender = senderName.ifBlank { activity.currentClient().ifBlank { "a guest" } }
        activity.setClientText(sender)
        activity.setDestinationText(destination)
        if (message.isBlank()) {
            activity.speakReply("I can send that. Tell me the message for $destination after the tone.")
            activity.autoDeliverAfterRecording = true
            askAndRecord()
            return
        }
        val cleanMessage = message.trim()
        activity.setMessageText(cleanMessage)
        val novaMessage = NovaMessage(
            id = System.currentTimeMillis(),
            destination = destination,
            prompt = "Text message from $sender",
            text = cleanMessage,
            audioPath = null,
            createdAt = System.currentTimeMillis()
        )
        activity.repo.save(novaMessage)
        refreshMessages()
        activity.setMessageText("")
        activity.speakReply("I will deliver your message to $destination.")
        deliverMessage(novaMessage)
    }

    private fun inferDestination(lowerPhrase: String): String? {
        val known = activity.lastMapPoints.map { it.name }.ifEmpty {
            listOf("Reception", "Lobby", "Meeting Room", "Office", "Entrance")
        }
        return known.firstOrNull { lowerPhrase.contains(it.lowercase()) }
    }

    private fun senderFromPrompt(prompt: String): String =
        prompt.substringAfter("from ", "a guest").ifBlank { "a guest" }
}
