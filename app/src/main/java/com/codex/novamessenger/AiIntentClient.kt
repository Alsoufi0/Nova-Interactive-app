package com.codex.novamessenger

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiIntentResult(
    val ok: Boolean,
    val action: String,
    val confidence: Double,
    val destination: String?,
    val residentId: String?,
    val residentName: String?,
    val message: String?,
    val priority: String?,
    val reply: String?
)

class AiIntentClient(private val activity: MainActivity) {
    fun classify(phrase: String, callback: (AiIntentResult?) -> Unit) {
        if (!activity.aiUnderstandingEnabled || phrase.isBlank()) {
            callback(null)
            return
        }

        Thread {
            val result = runCatching {
                val base = activity.cloudUrl().trim().trimEnd('/')
                if (base.isBlank()) return@runCatching null
                val payload = JSONObject()
                    .put("phrase", phrase.take(500))
                    .put("currentDestination", activity.destination())
                    .put("mapPoints", JSONArray().also { array ->
                        activity.lastMapPoints.take(80).forEach { point ->
                            array.put(
                                JSONObject()
                                    .put("name", point.name)
                                    .put("status", point.status)
                            )
                        }
                    })
                    .put("residents", JSONArray().also { array ->
                        activity.careRepo.residents().take(120).forEach { resident ->
                            array.put(
                                JSONObject()
                                    .put("id", resident.id)
                                    .put("name", resident.name)
                                    .put("room", resident.room)
                                    .put("mapPoint", resident.mapPoint)
                            )
                        }
                    })

                val conn = (URL("$base/ai/intent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2500
                    readTimeout = 4500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Robot-Token", activity.cloudToken())
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299 || body.isBlank()) return@runCatching null
                parseIntent(JSONObject(body))
            }.getOrNull()

            callback(result)
        }.start()
    }

    private fun parseIntent(json: JSONObject): AiIntentResult? {
        if (!json.optBoolean("ok", false) || json.optBoolean("fallback", false)) return null
        val action = json.optString("action").trim().lowercase()
        if (action.isBlank() || action == "unknown") return null
        return AiIntentResult(
            ok = true,
            action = action,
            confidence = json.optDouble("confidence", 0.0),
            destination = json.optString("destination").takeIf { it.isNotBlank() },
            residentId = json.optString("residentId").takeIf { it.isNotBlank() },
            residentName = json.optString("residentName").takeIf { it.isNotBlank() },
            message = json.optString("message").takeIf { it.isNotBlank() },
            priority = json.optString("priority").takeIf { it.isNotBlank() },
            reply = json.optString("reply").takeIf { it.isNotBlank() }
        )
    }
}
