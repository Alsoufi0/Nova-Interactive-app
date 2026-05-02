package com.codex.novamessenger

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

class CloudRelayClient(
    private val cloudUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val stateProvider: () -> CloudState,
    private val commandHandler: (CloudCommand) -> String
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var consecutiveFailures = 0
    private var nextRetryAfter = 0L

    private val pendingResults = ConcurrentLinkedQueue<Pair<String, String>>()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val delayMs = if (consecutiveFailures == 0) 2_000L else {
                minOf(60_000L, 2_000L * (1L shl minOf(consecutiveFailures - 1, 5)))
            }
            if (now >= nextRetryAfter) {
                Thread { syncOnce() }.start()
            }
            handler.postDelayed(this, delayMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun syncOnce() {
        val base = cloudUrlProvider().trim().trimEnd('/')
        val token = tokenProvider().trim()
        if (base.isBlank() || token.isBlank()) return
        runCatching {
            flushPendingResults(base, token)
            postJson("$base/robot/state", stateProvider().toJson(), token)
            val response = getJson("$base/robot/poll", token)
            val commands = response.optJSONArray("commands") ?: JSONArray()
            for (i in 0 until commands.length()) {
                val item = commands.optJSONObject(i) ?: continue
                val command = CloudCommand(
                    id = item.optString("id"),
                    action = item.optString("action"),
                    params = jsonToMap(item.optJSONObject("params") ?: JSONObject())
                )
                val result = commandHandler(command)
                runCatching {
                    postJson("$base/robot/result", JSONObject().put("id", command.id).put("result", result), token)
                }.onFailure {
                    pendingResults.offer(command.id to result)
                    Log.w(TAG, "Result for command ${command.id} queued for retry: ${it.message}")
                }
            }
            consecutiveFailures = 0
        }.onFailure {
            consecutiveFailures++
            val backoffMs = minOf(60_000L, 2_000L * (1L shl minOf(consecutiveFailures - 1, 5)))
            nextRetryAfter = System.currentTimeMillis() + backoffMs
            Log.e(TAG, "Cloud sync failed (attempt $consecutiveFailures, backoff ${backoffMs}ms): ${it.message}")
        }
    }

    private fun flushPendingResults(base: String, token: String) {
        val iterator = pendingResults.iterator()
        while (iterator.hasNext()) {
            val (id, result) = iterator.next()
            val sent = runCatching {
                postJson("$base/robot/result", JSONObject().put("id", id).put("result", result), token)
                iterator.remove()
            }.isSuccess
            if (!sent) break
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("X-Robot-Token", token)
        }
        val code = connection.responseCode
        if (code !in 200..299) error("GET $url failed HTTP $code")
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun postJson(url: String, body: JSONObject, token: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Robot-Token", token)
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        val code = connection.responseCode
        if (code !in 200..299) error("POST $url failed HTTP $code")
        connection.inputStream.close()
    }

    private fun CloudState.toJson(): JSONObject =
        JSONObject()
            .put("status", status)
            .put("detection", detection)
            .put("people", JSONArray().also { array ->
                people.forEach {
                    array.put(
                        JSONObject()
                            .put("id", it.id)
                            .put("distance", it.distanceMeters)
                            .put("angle", it.angleDegrees)
                            .put("centerX", it.centerX)
                    )
                }
            })
            .put("points", JSONArray().also { array ->
                points.forEach {
                    val point = JSONObject()
                        .put("name", it.name)
                        .put("status", it.status)
                        .put("hasCoordinates", it.x != null && it.y != null)
                    it.x?.let { x -> point.put("x", x) }
                    it.y?.let { y -> point.put("y", y) }
                    array.put(point)
                }
            })
            .put("care", care)
            .apply {
                cameraJpeg?.let { put("cameraJpegBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
            }

    private fun jsonToMap(json: JSONObject): Map<String, String> =
        json.keys().asSequence().associateWith { json.optString(it) }

    data class CloudState(
        val status: JSONObject,
        val detection: JSONObject,
        val people: List<BodyTarget>,
        val points: List<MapPoint>,
        val care: JSONObject,
        val cameraJpeg: ByteArray?
    )

    data class CloudCommand(
        val id: String,
        val action: String,
        val params: Map<String, String>
    )

    companion object {
        private const val TAG = "NovaCloudRelay"
    }
}
