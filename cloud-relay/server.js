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
<title>Nova Cloud</title>
<style>
body{margin:0;background:#071012;color:#eef8f7;font-family:system-ui,-apple-system,Segoe UI,sans-serif}
main{max-width:900px;margin:auto;padding:16px}.top{position:sticky;top:0;background:#071012ee;padding:12px 0;backdrop-filter:blur(12px)}
h1{margin:0;font-size:30px}.sub{color:#9db3b6;margin:4px 0 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.card{background:#111d21;border:1px solid #284147;border-radius:14px;padding:14px;margin-top:12px;box-shadow:0 10px 28px #0007}
button,input{width:100%;box-sizing:border-box;border:0;border-radius:12px;padding:13px;margin:5px 0;font-size:16px}
button{background:#2ee0bd;color:#04110f;font-weight:850}.stop{background:#e45757;color:white}.ghost{background:#223238;color:#eef8f7}
input{background:#eaf4f3;color:#102024}.small{white-space:pre-wrap;color:#a8bbbd;font-size:13px;max-height:220px;overflow:auto}
img{display:block;width:100%;max-height:430px;object-fit:contain;background:#020506;border-radius:12px;border:1px solid #284147}
@media(max-width:700px){.grid{grid-template-columns:1fr}main{padding:12px}}
</style></head><body><main>
<div class="top"><h1>Nova Cloud</h1><p class="sub">Internet control relay for Nova concierge.</p></div>
<div class="grid">
<div class="card"><b>Movement</b><button onclick="cmd('follow')">Follow</button><button onclick="cmd('door_follow')">Door Follow</button><button class="stop" onclick="cmd('stop')">Stop</button></div>
<div class="card"><b>Navigation</b><input id="dest" placeholder="Destination"><button onclick="cmd('guide',{destination:dest.value})">Guide</button><button class="ghost" onclick="cmd('charge')">Charge</button></div>
<div class="card"><b>Message</b><input id="msgDest" placeholder="Destination"><input id="msg" placeholder="Message"><button onclick="cmd('message',{destination:msgDest.value,message:msg.value})">Send Message</button></div>
<div class="card"><b>Camera / Detection</b><button onclick="cmd('camera_start')">Open Camera</button><button onclick="cmd('security_start')">Start Detection</button><button class="ghost" onclick="cmd('camera_stop')">Close Camera</button><button class="ghost" onclick="cmd('security_stop')">Stop Detection</button></div>
</div>
<div class="card"><b>Live Camera</b><img id="camera" alt="camera"><p id="cameraNote" class="sub"></p></div>
<div class="grid"><div class="card"><b>Status</b><pre id="status" class="small"></pre></div><div class="card"><b>People / Points</b><pre id="details" class="small"></pre></div></div>
</main><script>
async function get(p){return (await fetch(p,{cache:'no-store'})).json()}
async function cmd(action,params={}){await fetch('/api/command',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,params})});refresh()}
async function refresh(){const s=await get('/api/state');status.textContent=JSON.stringify({online:s.online,lastSeen:s.lastSeen,status:s.status,detection:s.detection},null,2);details.textContent=JSON.stringify({people:s.people,points:s.points},null,2);cameraNote.textContent=s.camera?'Camera snapshot online':'No camera snapshot yet';if(s.camera) camera.src='/api/camera.jpg?t='+Date.now()}
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
