package com.codex.novamessenger

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VisitAuthorizationResult(
    val ok: Boolean,
    val residentName: String,
    val mapPoint: String,
    val message: String
)

class VisitAuthorizationClient(private val activity: MainActivity) {
    fun verify(
        visitorName: String,
        visitorIdNumber: String,
        resident: CareResident,
        callback: (VisitAuthorizationResult) -> Unit
    ) {
        Thread {
            val result = runCatching {
                val base = activity.cloudUrl().trim().trimEnd('/')
                val payload = JSONObject()
                    .put("visitorName", visitorName.trim())
                    .put("visitorIdNumber", visitorIdNumber.trim())
                    .put("residentId", resident.id)
                    .put("residentName", resident.name)
                val conn = (URL("$base/robot/verify-visit").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3500
                    readTimeout = 7000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Robot-Token", activity.cloudToken())
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = JSONObject(body.ifBlank { "{}" })
                VisitAuthorizationResult(
                    ok = json.optBoolean("ok", false),
                    residentName = json.optString("residentName", resident.name),
                    mapPoint = json.optString("mapPoint"),
                    message = json.optString("message").ifBlank {
                        if (json.optBoolean("ok", false)) "Visit verified." else "Visit could not be verified."
                    }
                )
            }.getOrElse {
                VisitAuthorizationResult(false, resident.name, "", "I could not verify the visit with the care cloud.")
            }
            callback(result)
        }.start()
    }
}
