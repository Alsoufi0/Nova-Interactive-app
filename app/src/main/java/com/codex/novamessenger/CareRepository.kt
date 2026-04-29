package com.codex.novamessenger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CareResident(
    val id: String,
    val name: String,
    val room: String,
    val mapPoint: String,
    val notes: String,
    val checkInPrompt: String
)

data class CareReminder(
    val id: String,
    val residentId: String,
    val title: String,
    val timeLabel: String,
    val message: String,
    val doneAt: Long? = null
)

data class CareAlert(
    val id: Long,
    val priority: String,
    val room: String,
    val message: String,
    val status: String,
    val createdAt: Long
)

data class CareLog(
    val id: Long,
    val type: String,
    val title: String,
    val detail: String,
    val residentId: String?,
    val mapPoint: String?,
    val createdAt: Long
)

class CareRepository(context: Context) {
    private val prefs = context.getSharedPreferences("nova_care", Context.MODE_PRIVATE)

    fun residents(): List<CareResident> {
        val raw = prefs.getString("residents", null)
        if (raw.isNullOrBlank()) {
            val seeded = defaultResidents()
            saveResidents(seeded)
            return seeded
        }
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            CareResident(
                id = item.getString("id"),
                name = item.getString("name"),
                room = item.optString("room"),
                mapPoint = item.optString("mapPoint", "Reception"),
                notes = item.optString("notes"),
                checkInPrompt = item.optString("checkInPrompt", "Hello. I am checking in. Do you need anything from staff?")
            )
        }
    }

    fun reminders(): List<CareReminder> {
        val raw = prefs.getString("reminders", null)
        if (raw.isNullOrBlank()) {
            val seeded = defaultReminders()
            saveReminders(seeded)
            return seeded
        }
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            CareReminder(
                id = item.getString("id"),
                residentId = item.getString("residentId"),
                title = item.optString("title"),
                timeLabel = item.optString("timeLabel"),
                message = item.optString("message"),
                doneAt = item.optLong("doneAt").takeIf { it > 0L }
            )
        }
    }

    fun alerts(): List<CareAlert> {
        val array = JSONArray(prefs.getString("alerts", "[]") ?: "[]")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            CareAlert(
                id = item.getLong("id"),
                priority = item.optString("priority", "normal"),
                room = item.optString("room"),
                message = item.optString("message"),
                status = item.optString("status", "open"),
                createdAt = item.optLong("createdAt")
            )
        }.sortedByDescending { it.createdAt }
    }

    fun logs(): List<CareLog> {
        val array = JSONArray(prefs.getString("logs", "[]") ?: "[]")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            CareLog(
                id = item.getLong("id"),
                type = item.optString("type"),
                title = item.optString("title"),
                detail = item.optString("detail"),
                residentId = item.optString("residentId").takeIf { it.isNotBlank() },
                mapPoint = item.optString("mapPoint").takeIf { it.isNotBlank() },
                createdAt = item.optLong("createdAt")
            )
        }.sortedByDescending { it.createdAt }
    }

    fun resident(id: String?): CareResident? =
        residents().firstOrNull { it.id == id } ?: residents().firstOrNull()

    fun createAlert(priority: String, room: String, message: String): CareAlert {
        val alert = CareAlert(
            id = System.currentTimeMillis(),
            priority = priority.ifBlank { "normal" },
            room = room,
            message = message.ifBlank { "Resident requested staff assistance." },
            status = "open",
            createdAt = System.currentTimeMillis()
        )
        saveAlerts((listOf(alert) + alerts()).take(30))
        log("alert", "Staff alert", "${alert.priority}: ${alert.message}", null, room)
        return alert
    }

    fun completeReminder(reminderId: String) {
        saveReminders(reminders().map {
            if (it.id == reminderId) it.copy(doneAt = System.currentTimeMillis()) else it
        })
    }

    fun log(type: String, title: String, detail: String, residentId: String? = null, mapPoint: String? = null): CareLog {
        val log = CareLog(
            id = System.currentTimeMillis(),
            type = type,
            title = title,
            detail = detail,
            residentId = residentId,
            mapPoint = mapPoint,
            createdAt = System.currentTimeMillis()
        )
        saveLogs((listOf(log) + logs()).take(80))
        return log
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put("residents", JSONArray().also { array -> residents().forEach { array.put(it.toJson()) } })
            .put("reminders", JSONArray().also { array -> reminders().forEach { array.put(it.toJson()) } })
            .put("alerts", JSONArray().also { array -> alerts().forEach { array.put(it.toJson()) } })
            .put("logs", JSONArray().also { array -> logs().take(20).forEach { array.put(it.toJson()) } })

    private fun saveResidents(items: List<CareResident>) =
        prefs.edit().putString("residents", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString()).apply()

    private fun saveReminders(items: List<CareReminder>) =
        prefs.edit().putString("reminders", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString()).apply()

    private fun saveAlerts(items: List<CareAlert>) =
        prefs.edit().putString("alerts", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString()).apply()

    private fun saveLogs(items: List<CareLog>) =
        prefs.edit().putString("logs", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString()).apply()

    private fun defaultResidents(): List<CareResident> = listOf(
        CareResident("mary", "Mary Collins", "Room 204", "Reception", "Prefers gentle reminders and short visits.", "Hello Mary. This is Nova checking in. Do you need water, medication help, or staff assistance?"),
        CareResident("john", "John Ahmed", "Room 207", "Reception", "Family often sends voice messages.", "Hello John. I am here for your check-in. Are you comfortable and do you need anything?"),
        CareResident("grace", "Grace Lee", "Therapy Lounge", "Reception", "Escort to therapy when requested.", "Hello Grace. It is Nova. Do you need help going to therapy or contacting staff?")
    )

    private fun defaultReminders(): List<CareReminder> = listOf(
        CareReminder("med_mary", "mary", "Medication reminder", "2:00 PM", "Mary, it is time for your medication. Please wait for staff before taking anything."),
        CareReminder("therapy_grace", "grace", "Therapy appointment", "3:30 PM", "Grace, your therapy appointment is coming up. I can guide you when staff is ready."),
        CareReminder("family_john", "john", "Family message check", "5:00 PM", "John, your family may send a message today. I will let you know when it arrives.")
    )
}

private fun CareResident.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("room", room)
        .put("mapPoint", mapPoint)
        .put("notes", notes)
        .put("checkInPrompt", checkInPrompt)

private fun CareReminder.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("residentId", residentId)
        .put("title", title)
        .put("timeLabel", timeLabel)
        .put("message", message)
        .put("doneAt", doneAt ?: 0L)

private fun CareAlert.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("priority", priority)
        .put("room", room)
        .put("message", message)
        .put("status", status)
        .put("createdAt", createdAt)

private fun CareLog.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("type", type)
        .put("title", title)
        .put("detail", detail)
        .put("residentId", residentId ?: "")
        .put("mapPoint", mapPoint ?: "")
        .put("createdAt", createdAt)
