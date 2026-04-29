const http = require("http");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 3000);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "nova2026";
const ROBOT_TOKEN = process.env.ROBOT_TOKEN || "change-me-robot-token";

let robot = {
  online: false,
  lastSeen: 0,
  status: {},
  detection: {},
  people: [],
  points: [],
  care: { residents: [], reminders: [], alerts: [], logs: [] },
  cameraJpegBase64: "",
};
const commandQueue = [];
const events = [];

function json(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "content-length": data.length,
  });
  res.end(data);
}

function text(res, status, body, type = "text/plain; charset=utf-8") {
  const data = Buffer.from(body);
  res.writeHead(status, {
    "content-type": type,
    "cache-control": "no-store",
    "content-length": data.length,
  });
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

function safeEqual(a, b) {
  const left = Buffer.from(a || "");
  const right = Buffer.from(b || "");
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function isAdmin(req) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Basic ")) return false;
  const decoded = Buffer.from(header.slice(6), "base64").toString("utf8");
  return safeEqual(decoded, `${ADMIN_USER}:${ADMIN_PASS}`);
}

function isRobot(req) {
  return safeEqual(req.headers["x-robot-token"], ROBOT_TOKEN);
}

function requireAdmin(req, res) {
  if (isAdmin(req)) return true;
  res.writeHead(401, { "www-authenticate": 'Basic realm="Nova Cloud"', "cache-control": "no-store" });
  res.end("Login required");
  return false;
}

function page() {
  return `<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nova Care Cloud</title>
<style>
body{margin:0;background:#f4f7f7;color:#102024;font-family:system-ui,-apple-system,Segoe UI,sans-serif}
main{max-width:1120px;margin:auto;padding:16px}.top{position:sticky;top:0;background:#f4f7f7ee;padding:12px 0;backdrop-filter:blur(12px);z-index:2}
h1{margin:0;font-size:30px}.sub{color:#617579;margin:4px 0 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.tri{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.card{background:white;border:1px solid #d8e3e4;border-radius:14px;padding:14px;margin-top:12px;box-shadow:0 10px 28px #1232}
.dark{background:#0d191d;color:#eef8f7;border-color:#29444a}.pill{display:inline-block;border-radius:999px;background:#e9f3f2;padding:7px 10px;margin:4px;color:#163034;font-size:13px}
button,input,select,textarea{width:100%;box-sizing:border-box;border:0;border-radius:12px;padding:13px;margin:5px 0;font-size:16px}
button{background:#1e8f7d;color:white;font-weight:850}.stop{background:#d85151;color:white}.ghost{background:#e8eff0;color:#102024}.nav{background:#183139;color:white}.warn{background:#b76b2b;color:white}
input,select,textarea{background:#edf4f4;color:#102024;border:1px solid #d8e3e4}.small{white-space:pre-wrap;color:#617579;font-size:13px;max-height:220px;overflow:auto}
img{display:block;width:100%;max-height:430px;object-fit:contain;background:#020506;border-radius:12px;border:1px solid #284147}.row{display:flex;gap:8px;align-items:center}.row>*{flex:1}
@media(max-width:760px){.grid,.tri{grid-template-columns:1fr}main{padding:12px}.row{display:block}}
</style></head><body><main>
<div class="top"><h1>Nova Care Cloud</h1><p class="sub">Secure internet control for elder care, messages, wayfinding, detection, and care logs.</p></div>
<div class="tri">
<div class="card dark"><b>Robot</b><p id="robotSummary" class="sub"></p><button class="stop" onclick="cmd('stop')">Stop Now</button></div>
<div class="card"><b>Care Ops</b><button onclick="cmd('start_rounds')">Start Check-In Round</button><button class="warn" onclick="staffAlert()">Staff Alert</button></div>
<div class="card"><b>Safety</b><button onclick="cmd('security_start')">Start Detection</button><button class="ghost" onclick="cmd('security_stop')">Stop Detection</button></div>
</div>
<div class="grid">
<div class="card"><b>Family Message</b><input id="msgDest" placeholder="Destination or room"><textarea id="msg" rows="3" placeholder="Message to deliver"></textarea><button onclick="cmd('message',{destination:msgDest.value,message:msg.value})">Send Message</button></div>
<div class="card"><b>Visitor Guide</b><select id="dest"></select><button class="nav" onclick="cmd('visitor_guide',{destination:dest.value})">Guide Visitor</button><button class="ghost" onclick="cmd('charge')">Send To Charger</button></div>
</div>
<div class="grid">
<div class="card"><b>Residents</b><div id="residents"></div></div>
<div class="card"><b>Reminders</b><div id="reminders"></div></div>
</div>
<div class="card"><b>Live Camera</b><div class="row"><button onclick="cmd('camera_start')">Open Camera</button><button class="ghost" onclick="cmd('camera_stop')">Close Camera</button></div><img id="camera" alt="camera"><p id="cameraNote" class="sub"></p></div>
<div class="grid"><div class="card"><b>Alerts</b><div id="alerts" class="small"></div></div><div class="card"><b>Care Log</b><div id="logs" class="small"></div></div></div>
<div class="grid"><div class="card"><b>Status</b><pre id="status" class="small"></pre></div><div class="card"><b>People / Points</b><pre id="details" class="small"></pre></div></div>
</main><script>
async function get(p){return (await fetch(p,{cache:'no-store'})).json()}
async function cmd(action,params={}){await fetch('/api/command',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,params})});refresh()}
function esc(v){return String(v||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function staffAlert(){cmd('staff_alert',{priority:'urgent',room:dest.value||msgDest.value,message:msg.value||'Resident or visitor requested assistance.'})}
function fillSelect(points){const old=dest.value;dest.innerHTML=(points||[]).map(p=>'<option>'+esc(p.name)+'</option>').join('');if(old)dest.value=old}
function renderCare(care){
  const rs=care.residents||[], rem=care.reminders||[], al=care.alerts||[], lg=care.logs||[];
  residents.innerHTML=rs.map(r=>'<div class="pill"><b>'+esc(r.name)+'</b><br>'+esc(r.room)+'<br><button onclick="cmd(\'resident_checkin\',{residentId:\''+esc(r.id)+'\'})">Check In</button><button class="ghost" onclick="cmd(\'med_reminder\',{residentId:\''+esc(r.id)+'\'})">Reminder</button></div>').join('')||'<p class="sub">No residents from Nova yet.</p>';
  reminders.innerHTML=rem.map(r=>'<div class="pill"><b>'+esc(r.timeLabel)+'</b> '+esc(r.title)+'<br><button onclick="cmd(\'med_reminder\',{reminderId:\''+esc(r.id)+'\'})">Deliver</button></div>').join('')||'<p class="sub">No reminders.</p>';
  alerts.innerHTML=al.map(a=>new Date(a.createdAt).toLocaleTimeString()+'  '+esc(a.priority)+'  '+esc(a.room)+'  '+esc(a.message)).join('\n')||'No open alerts.';
  logs.innerHTML=lg.map(l=>new Date(l.createdAt).toLocaleTimeString()+'  '+esc(l.title)+' - '+esc(l.detail)).join('\n')||'No care log yet.';
}
async function refresh(){const s=await get('/api/state');robotSummary.textContent=(s.online?'Online':'Offline')+' - '+(s.status?.battery||'battery --')+' - '+(s.status?.status||'waiting');status.textContent=JSON.stringify({online:s.online,lastSeen:s.lastSeen,status:s.status,detection:s.detection},null,2);details.textContent=JSON.stringify({people:s.people,points:s.points},null,2);fillSelect(s.points||[]);renderCare(s.care||{});cameraNote.textContent=s.camera?'Camera snapshot online':'No camera snapshot yet';if(s.camera) camera.src='/api/camera.jpg?t='+Date.now()}
setInterval(refresh,2000);refresh()
</script></body></html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  if (url.pathname === "/health") return json(res, 200, { ok: true });

  if (url.pathname.startsWith("/robot/")) {
    if (!isRobot(req)) return json(res, 403, { ok: false, error: "bad robot token" });
    robot.online = true;
    robot.lastSeen = Date.now();
    if (url.pathname === "/robot/state" && req.method === "POST") {
      const body = JSON.parse((await readBody(req)) || "{}");
      robot.status = body.status || robot.status;
      robot.detection = body.detection || robot.detection;
      robot.people = body.people || robot.people;
      robot.points = body.points || robot.points;
      robot.care = body.care || robot.care;
      if (body.cameraJpegBase64) robot.cameraJpegBase64 = body.cameraJpegBase64;
      events.push({ at: Date.now(), type: "state" });
      while (events.length > 100) events.shift();
      return json(res, 200, { ok: true });
    }
    if (url.pathname === "/robot/poll") {
      return json(res, 200, { commands: commandQueue.splice(0, 10) });
    }
    if (url.pathname === "/robot/result" && req.method === "POST") {
      events.push({ at: Date.now(), type: "result", data: JSON.parse((await readBody(req)) || "{}") });
      while (events.length > 100) events.shift();
      return json(res, 200, { ok: true });
    }
  }

  if (!requireAdmin(req, res)) return;
  if (url.pathname === "/") return text(res, 200, page(), "text/html; charset=utf-8");
  if (url.pathname === "/api/state") {
    const stale = Date.now() - robot.lastSeen > 15000;
    return json(res, 200, { ...robot, online: robot.online && !stale, camera: !!robot.cameraJpegBase64, events });
  }
  if (url.pathname === "/api/camera.jpg") {
    if (!robot.cameraJpegBase64) return text(res, 404, "No camera snapshot");
    const data = Buffer.from(robot.cameraJpegBase64, "base64");
    res.writeHead(200, { "content-type": "image/jpeg", "cache-control": "no-store", "content-length": data.length });
    return res.end(data);
  }
  if (url.pathname === "/api/command" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)) || "{}");
    const command = { id: crypto.randomUUID(), at: Date.now(), action: String(body.action || ""), params: body.params || {} };
    commandQueue.push(command);
    return json(res, 200, { ok: true, command });
  }
  json(res, 404, { ok: false, error: "not found" });
});

server.listen(PORT, () => console.log(`Nova cloud relay listening on ${PORT}`));
