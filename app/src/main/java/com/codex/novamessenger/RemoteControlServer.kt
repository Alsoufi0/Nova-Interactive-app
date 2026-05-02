package com.codex.novamessenger

import android.util.Log
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RemoteControlServer(
    private val port: Int = 8787,
    private val username: String,
    private val password: String,
    private val statusProvider: () -> RemoteStatus,
    private val peopleProvider: () -> List<BodyTarget>,
    private val pointsProvider: () -> List<MapPoint>,
    private val detectionProvider: () -> DetectionStatus,
    private val cameraFrameProvider: () -> ByteArray?,
    private val commandHandler: (RemoteCommand) -> String
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val lastCameraRequestAt = AtomicLong(0L)
    private val CAMERA_MIN_INTERVAL_MS = 500L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            runCatching {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Remote console listening on $port")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        socket.use {
                            it.soTimeout = 15_000
                            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                            val request = reader.readLine().orEmpty()
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) break
                                val key = line.substringBefore(":", "").lowercase()
                                val value = line.substringAfter(":", "").trim()
                                if (key.isNotBlank()) headers[key] = value
                            }
                            val path = request.split(" ").getOrNull(1).orEmpty()
                            val response = if (isAuthorized(headers["authorization"])) handle(path) else unauthorized()
                            runCatching { writeResponse(it.getOutputStream(), response) }
                                .onFailure { error -> Log.w(TAG, "Remote client disconnected before response completed: ${error.message}") }
                        }
                    }.start()
                }
            }.onFailure {
                if (running.get()) Log.e(TAG, "Remote console stopped", it)
            }
        }.apply {
            name = "NovaRemoteControl"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
    }

    private fun handle(pathWithQuery: String): HttpResponse {
        val route = pathWithQuery.substringBefore("?").ifBlank { "/" }
        val query = parseQuery(pathWithQuery.substringAfter("?", ""))
        return when (route) {
            "/" -> HttpResponse("text/html; charset=utf-8", html())
            "/status" -> HttpResponse("application/json", statusProvider().toJson())
            "/people" -> HttpResponse("application/json", peopleToJson(peopleProvider()))
            "/points" -> HttpResponse("application/json", pointsToJson(pointsProvider()))
            "/detection" -> HttpResponse("application/json", detectionProvider().toJson())
            "/camera.jpg" -> {
                val now = System.currentTimeMillis()
                val last = lastCameraRequestAt.get()
                if (now - last < CAMERA_MIN_INTERVAL_MS) {
                    HttpResponse("text/plain; charset=utf-8", "Rate limited. Max 2 frames/sec.", 429)
                } else {
                    lastCameraRequestAt.set(now)
                    cameraFrameProvider()?.let {
                        HttpResponse("image/jpeg", body = "", headers = mapOf("Pragma" to "no-cache"), bodyBytes = it)
                    } ?: HttpResponse("text/plain; charset=utf-8", "Camera feed is not ready.", 503)
                }
            }
            "/control" -> {
                val result = commandHandler(RemoteCommand(query["action"].orEmpty(), query))
                HttpResponse("application/json", """{"ok":true,"message":"${esc(result)}"}""")
            }
            else -> HttpResponse("application/json", """{"ok":false,"message":"not found"}""", 404)
        }
    }

    private fun isAuthorized(header: String?): Boolean {
        if (header.isNullOrBlank() || !header.startsWith("Basic ")) return false
        val decoded = runCatching {
            String(Base64.decode(header.removePrefix("Basic ").trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
        return decoded == "$username:$password"
    }

    private fun unauthorized(): HttpResponse =
        HttpResponse(
            "text/plain; charset=utf-8",
            "Login required for Nova Control.",
            401,
            mapOf("WWW-Authenticate" to """Basic realm="Nova Control"""")
        )

    private fun writeResponse(out: OutputStream, response: HttpResponse) {
        val body = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 ${response.status} ${reasonPhrase(response.status)}\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
            "Content-Length: ${body.size}\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&")
            .filter { it.contains("=") }
            .associate {
                val key = URLDecoder.decode(it.substringBefore("="), "UTF-8")
                val value = URLDecoder.decode(it.substringAfter("="), "UTF-8")
                key to value
            }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        401 -> "Unauthorized"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun html(): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Nova Control</title>
          <style>
            :root{color-scheme:dark;--bg:#070b0d;--panel:#11191c;--line:#25343a;--text:#f2fbfa;--muted:#90a5a8;--accent:#2ee0bd;--blue:#66a6ff;--danger:#f06767}
            *{box-sizing:border-box} body{margin:0;background:radial-gradient(circle at top,#163036 0,#070b0d 42%);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,sans-serif}
            main{max-width:820px;margin:auto;padding:18px}
            .top{position:sticky;top:0;padding:12px 0 14px;background:linear-gradient(#081014 70%,transparent);backdrop-filter:blur(14px);z-index:2}
            h1{font-size:30px;margin:0;letter-spacing:.2px}.sub{color:var(--muted);margin:4px 0 0;font-size:14px}
            .hero{display:grid;grid-template-columns:1.15fr .85fr;gap:12px;align-items:stretch}
            .panel{background:color-mix(in srgb,var(--panel) 92%,transparent);border:1px solid var(--line);border-radius:14px;padding:14px;box-shadow:0 12px 32px #0008}
            .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}.title{font-weight:800;margin-bottom:8px}.muted{color:var(--muted);font-size:13px}
            .status{display:grid;grid-template-columns:1fr 1fr;gap:8px}.metric{border:1px solid #223138;border-radius:12px;padding:10px;background:#0c1417}.metric b{display:block;font-size:12px;color:var(--muted);margin-bottom:5px}.metric span{font-size:17px}
            button,input{width:100%;border:0;border-radius:12px;padding:13px;margin:5px 0;font-size:16px}
            button{background:linear-gradient(135deg,#2ee0bd,#208a7e);color:#04110f;font-weight:850} button.stop{background:linear-gradient(135deg,#ff8585,#ad3434);color:white}.ghost{background:#1e2a2f;color:var(--text)}.blue{background:linear-gradient(135deg,#83b7ff,#386ad8);color:#061021}
            input{background:#eaf4f3;color:#102024}.small{font-size:13px;color:#a7b9bb;white-space:pre-wrap;max-height:210px;overflow:auto}.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}.chip{width:auto;padding:9px 11px;margin:0;background:#1f3035;color:#dff8f5;font-size:14px}
            .camera{margin-top:12px}.camera img{display:block;width:100%;max-height:430px;object-fit:contain;background:#020506;border:1px solid var(--line);border-radius:12px}
            @media(max-width:640px){.hero,.grid{grid-template-columns:1fr}.status{grid-template-columns:1fr 1fr}main{padding:12px}h1{font-size:26px}}
          </style>
        </head>
        <body><main>
          <div class="top"><h1>Nova Control</h1><p class="sub">Private operator console for concierge, follow, map delivery, and detection.</p></div>
          <div class="hero">
            <div class="panel"><div class="title">Live Status</div><div id="summary" class="status"></div><div id="taskBar" style="margin-top:10px"></div></div>
            <div class="panel"><div class="title">Emergency</div><button class="stop" onclick="cmd('stop')">Stop Nova</button><p class="muted">Stops follow, navigation, and chassis movement.</p></div>
          </div>
          <div class="grid">
            <div class="panel"><div class="title">Movement</div>
              <button onclick="cmd('follow')">Follow</button>
              <button class="blue" onclick="cmd('door_follow')">Door Follow</button>
              <button class="ghost" onclick="cmd('stop')">Stop</button>
            </div>
            <div class="panel"><div class="title">Navigation</div>
              <input id="dest" placeholder="Destination, e.g. Reception">
              <button onclick="cmd('guide','dest')">Guide</button>
              <button class="ghost" onclick="cmd('return_home')">Return to Home Base</button>
              <button class="ghost" onclick="cmd('charge')">Return to Charge</button>
              <div id="pointChips" class="chips"></div>
            </div>
            <div class="panel"><div class="title">Message Delivery</div>
              <input id="msgDest" placeholder="Destination">
              <input id="msg" placeholder="Message content">
              <button onclick="sendMessage()">Send Message</button>
              <p class="muted">Nova navigates to the saved map point and speaks the message.</p>
            </div>
            <div class="panel"><div class="title">Care Rounds</div>
              <button onclick="cmd('start_rounds')">Start Care Round</button>
              <input id="alertRoom" placeholder="Room (e.g. Room 204)">
              <input id="alertMsg" placeholder="Alert message">
              <button class="stop" onclick="sendAlert('urgent')">Urgent Alert</button>
              <button class="ghost" onclick="sendAlert('normal')">Normal Alert</button>
              <p class="muted">Alert navigates Nova to the room and speaks the message on arrival.</p>
            </div>
            <div class="panel"><div class="title">Detection Watch</div>
              <button class="blue" onclick="cmd('camera_start')">Open Camera Feed</button>
              <button onclick="cmd('security_start')">Start Watch</button>
              <button class="ghost" onclick="cmd('security_stop')">Stop Watch</button>
              <button class="ghost" onclick="cmd('camera_stop')">Close Camera</button>
              <p class="muted">Use Detection Watch for RobotAPI person scanning. Camera Feed for live snapshots.</p>
            </div>
          </div>
          <div class="panel camera"><div class="title">Live Camera</div><img id="camera" alt="Nova camera feed"><p id="cameraNote" class="muted">Press Open Camera Feed to start.</p></div>
          <div class="grid">
            <div class="panel"><div class="title">Detected People</div><pre id="people" class="small">Loading...</pre></div>
            <div class="panel"><div class="title">Camera / Map</div><pre id="detection" class="small">Loading...</pre><pre id="points" class="small">Loading...</pre></div>
          </div>
        </main>
        <script>
          async function get(path){return (await fetch(path)).json()}
          async function cmd(action,inputId){let q='?action='+encodeURIComponent(action); if(inputId){q+='&destination='+encodeURIComponent(document.getElementById(inputId).value)} await get('/control'+q); refresh()}
          async function sendMessage(){await get('/control?action=message&destination='+encodeURIComponent(msgDest.value)+'&message='+encodeURIComponent(msg.value)+'&sender=operator'); refresh()}
          async function sendAlert(priority){await get('/control?action=staff_alert&priority='+encodeURIComponent(priority)+'&room='+encodeURIComponent(alertRoom.value)+'&message='+encodeURIComponent(alertMsg.value)); refresh()}
          function htmlEscape(value){return String(value ?? '').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))}
          function jsEscape(value){return String(value ?? '').replace(/\\/g,'\\\\').replace(/'/g,"\\'").replace(/\n/g,' ')}
          function metric(name,value){return '<div class="metric"><b>'+htmlEscape(name)+'</b><span>'+htmlEscape(value)+'</span></div>'}
          function bar(pct){return '<div style="margin:6px 0 2px;height:6px;border-radius:99px;background:#1e2d30"><div style="height:6px;border-radius:99px;background:var(--accent);width:'+Math.min(100,pct)+'%"></div></div>'}
          function choosePoint(name){dest.value=name;msgDest.value=name;alertRoom.value=name}
          async function refresh(){const s=await get('/status'), pe=await get('/people'), po=await get('/points'), de=await get('/detection');
            summary.innerHTML=metric('Robot',s.status)+metric('Battery',s.battery)+metric('Destination',s.destination||'Not set')+metric('Security',s.security?'On':'Off')+metric('Task',s.taskTitle||'Ready')+metric('Stage',s.taskStage||'--');
            taskBar.innerHTML='<span style="font-size:13px;color:var(--muted)">'+htmlEscape((s.taskTitle||'Nova Care Assistant')+' · '+htmlEscape(s.taskStage||'Ready'))+'</span>'+bar(s.taskProgress||0)+'<span style="font-size:11px;color:var(--muted)">Safety: '+htmlEscape(s.safetyStop||'--')+'</span>';
            people.textContent=pe.length?JSON.stringify(pe,null,2):'No people detected right now.';
            detection.textContent=JSON.stringify(de,null,2);
            cameraNote.textContent=de.cameraPreview?'Live snapshot feed is refreshing.':'Camera feed is closed or waiting for permission.';
            if(de.cameraPreview){camera.src='/camera.jpg?t='+Date.now()}
            points.textContent=po.length?JSON.stringify(po,null,2):'No map points loaded.';
            pointChips.innerHTML=po.slice(0,8).map(p=>'<button class="chip" onclick="choosePoint(\''+jsEscape(p.name)+'\')">'+htmlEscape(p.name)+'</button>').join('');
          }
          setInterval(refresh,2000); refresh()
        </script></body></html>
    """.trimIndent()

    private fun RemoteStatus.toJson(): String =
        """{"status":"${esc(status)}","battery":"${esc(battery)}","destination":"${esc(destination)}","points":$points,"queue":$queue,"security":$security,"url":"${esc(url)}","taskTitle":"${esc(taskTitle)}","taskStage":"${esc(taskStage)}","taskProgress":$taskProgress,"safetyStop":"${esc(safetyStop)}"}"""

    private fun DetectionStatus.toJson(): String =
        """{"cameraPermission":$cameraPermission,"cameraPreview":$cameraPreview,"securityWatch":$securityWatch,"events":$events,"people":$people,"note":"${esc(note)}"}"""

    private fun peopleToJson(items: List<BodyTarget>): String =
        items.joinToString(prefix = "[", postfix = "]") {
            """{"id":${it.id},"distance":${"%.2f".format(it.distanceMeters)},"angle":${"%.1f".format(it.angleDegrees)},"centerX":${"%.1f".format(it.centerX)}}"""
        }

    private fun pointsToJson(items: List<MapPoint>): String =
        items.joinToString(prefix = "[", postfix = "]") {
            """{"name":"${esc(it.name)}","status":${it.status},"x":${it.x ?: 0.0},"y":${it.y ?: 0.0}}"""
        }

    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    data class RemoteStatus(
        val status: String,
        val battery: String,
        val destination: String,
        val points: Int,
        val queue: Int,
        val security: Boolean,
        val url: String,
        val taskTitle: String,
        val taskStage: String,
        val taskProgress: Int,
        val safetyStop: String
    )

    data class DetectionStatus(
        val cameraPermission: Boolean,
        val cameraPreview: Boolean,
        val securityWatch: Boolean,
        val events: Int,
        val people: Int,
        val note: String
    )

    data class RemoteCommand(val action: String, val params: Map<String, String>)

    private data class HttpResponse(
        val contentType: String,
        val body: String,
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val bodyBytes: ByteArray? = null
    )

    companion object {
        private const val TAG = "NovaRemote"
    }
}
