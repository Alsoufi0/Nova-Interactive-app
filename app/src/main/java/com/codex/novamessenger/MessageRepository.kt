package com.codex.novamessenger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MessageRepository(context: Context) {
    private val prefs = context.getSharedPreferences("nova_messages", Context.MODE_PRIVATE)

    fun all(): List<NovaMessage> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            NovaMessage(
                id = item.getLong("id"),
                destination = item.getString("destination"),
                prompt = item.optString("prompt"),
                text = item.optString("text"),
                audioPath = item.optString("audioPath").takeIf { it.isNotBlank() },
                createdAt = item.optLong("createdAt"),
                deliveredAt = item.optLong("deliveredAt").takeIf { it > 0L }
            )
        }.sortedByDescending { it.createdAt }
    }

    fun save(message: NovaMessage) {
        val next = all().filterNot { it.id == message.id }.toMutableList()
        next.add(message)
        persist(next.sortedByDescending { it.createdAt }.take(50))
    }

    fun markDelivered(id: Long) {
        val next = all().map {
            if (it.id == id) it.copy(deliveredAt = System.currentTimeMillis()) else it
        }
        persist(next)
    }

    fun delete(id: Long) {
        persist(all().filterNot { it.id == id })
    }

    fun pendingCount(): Int = all().count { it.deliveredAt == null }

    private fun persist(messages: List<NovaMessage>) {
        val array = JSONArray()
        messages.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("destination", it.destination)
                put("prompt", it.prompt)
                put("text", it.text)
                put("audioPath", it.audioPath ?: "")
                put("createdAt", it.createdAt)
                put("deliveredAt", it.deliveredAt ?: 0L)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
