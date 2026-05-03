const http = require("http");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 3000);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "nova2026";
const ROBOT_TOKEN = process.env.ROBOT_TOKEN || "change-me-robot-token";
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");

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

const facility = {
  residents: [],
  reminders: [],
  alerts: [],
  logs: [],
};
const commandQueue = [];
const events = [];
const sessions = new Map();
const users = new Map();
let brandLogoDataUrl = process.env.BRAND_LOGO_DATA_URL || "";

function passwordHash(password, salt = crypto.randomBytes(16).toString("hex")) {
  const hash = crypto.pbkdf2Sync(String(password || ""), salt, 120000, 32, "sha256").toString("hex");
  return `${salt}:${hash}`;
}

function verifyPassword(password, stored) {
  const [salt, hash] = String(stored || "").split(":");
  if (!salt || !hash) return false;
  const candidate = passwordHash(password, salt).split(":")[1];
  return safeEqual(candidate, hash);
}

users.set(ADMIN_USER, {
  username: ADMIN_USER,
  role: "admin",
  passwordHash: passwordHash(ADMIN_PASS),
  createdAt: Date.now(),
});

const residentColumns = [
  "resident_id",
  "full_name",
  "room",
  "map_point",
  "wing",
  "care_level",
  "primary_contact_name",
  "primary_contact_phone",
  "medication_notes",
  "mobility_notes",
  "preferred_language",
  "check_in_schedule",
  "emergency_notes",
];

function sendJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "content-length": data.length,
  });
  res.end(data);
}

function sendText(res, status, body, type = "text/plain; charset=utf-8") {
  const data = Buffer.from(body);
  res.writeHead(status, { "content-type": type, "cache-control": "no-store", "content-length": data.length });
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

function parseCookies(req) {
  return Object.fromEntries(
    String(req.headers.cookie || "")
      .split(";")
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => {
        const idx = part.indexOf("=");
        return idx < 0 ? [part, ""] : [part.slice(0, idx), decodeURIComponent(part.slice(idx + 1))];
      })
  );
}

function currentUser(req) {
  const sid = parseCookies(req).nova_session;
  const session = sid ? sessions.get(sid) : null;
  if (!session || session.expiresAt < Date.now()) {
    if (sid) sessions.delete(sid);
    return null;
  }
  return users.get(session.username) || null;
}

function createSession(res, username) {
  const sid = crypto.createHmac("sha256", SESSION_SECRET).update(`${username}:${crypto.randomUUID()}`).digest("hex");
  sessions.set(sid, { username, createdAt: Date.now(), expiresAt: Date.now() + 1000 * 60 * 60 * 12 });
  res.setHeader("set-cookie", `nova_session=${encodeURIComponent(sid)}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=43200`);
}

function isRobot(req) {
  return safeEqual(req.headers["x-robot-token"], ROBOT_TOKEN);
}

function requireAdmin(req, res) {
  if (currentUser(req) || isAdmin(req)) return true;
  if (String(req.url || "").startsWith("/api/")) return sendJson(res, 401, { ok: false, error: "login required" });
  res.writeHead(302, { location: "/login", "cache-control": "no-store" });
  res.end();
  return false;
}

function requireRole(req, res, role = "admin") {
  const user = currentUser(req);
  if (user?.role === role || user?.role === "admin" || isAdmin(req)) return user || users.get(ADMIN_USER);
  sendJson(res, 403, { ok: false, error: "admin role required" });
  return null;
}

function loginPage(error = "") {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>ZOX Robotics Sign In</title><style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0,#0d5790,#05152b 55%,#020814);font-family:Inter,system-ui,Segoe UI,sans-serif;color:white}.card{width:min(430px,92vw);background:#ffffff10;border:1px solid #ffffff26;border-radius:24px;padding:28px;box-shadow:0 24px 70px #0008}.logo{width:96px;height:96px;border-radius:26px;margin:0 auto 16px;background:#06172e;border:2px solid #10c6e7;display:grid;place-items:center;overflow:hidden}.logo img{width:100%;height:100%;object-fit:cover}.logo span{color:#1bd6ee;font-size:30px;font-weight:950}.tag{text-align:center;color:#31d7ef;font-size:11px;letter-spacing:2.4px;font-weight:900}.field{width:100%;border:1px solid #ffffff2e;background:#ffffff14;color:white;border-radius:14px;padding:14px;margin:8px 0;font:inherit}.btn{width:100%;border:0;border-radius:14px;background:#12bee5;color:#03162d;padding:14px;font-weight:950;margin-top:12px;cursor:pointer}.err{background:#ff4d4d26;color:#ffd6d6;border:1px solid #ff8a8a55;padding:10px;border-radius:12px;margin:12px 0}.muted{color:#bed0e3;font-size:13px;text-align:center}</style></head><body><form class="card" method="post" action="/login">
<div class="logo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX Robotics">` : "<span>ZOX</span>"}</div><div class="tag">SMART ROBOTS. BETTER CARE.</div><h1 style="text-align:center;margin:14px 0 6px">Care Cloud Sign In</h1><p class="muted">Authorized clinic staff only.</p>${error ? `<div class="err">${cleanText(error)}</div>` : ""}
<input class="field" name="username" placeholder="Username" autocomplete="username" autofocus><input class="field" name="password" placeholder="Password" type="password" autocomplete="current-password"><button class="btn">Sign In</button></form></body></html>`;
}

function logoutPage() {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Signed Out</title><style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0,#0d5790,#05152b 55%,#020814);font-family:Inter,system-ui,Segoe UI,sans-serif;color:white}.card{width:min(430px,92vw);background:#ffffff10;border:1px solid #ffffff26;border-radius:24px;padding:28px;text-align:center;box-shadow:0 24px 70px #0008}.logo{width:96px;height:96px;border-radius:26px;margin:0 auto 16px;background:#06172e;border:2px solid #10c6e7;display:grid;place-items:center;overflow:hidden}.logo img{width:100%;height:100%;object-fit:cover}.logo span{color:#1bd6ee;font-size:30px;font-weight:950}.btn{display:inline-block;border:0;border-radius:14px;background:#12bee5;color:#03162d;padding:14px 22px;font-weight:950;margin-top:12px;text-decoration:none}.muted{color:#bed0e3;font-size:13px}</style></head><body><section class="card">
<div class="logo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX Robotics">` : "<span>ZOX</span>"}</div><h1>Signed Out</h1><p class="muted">Your Care Cloud session has ended on this device.</p><a class="btn" href="/login">Sign In Again</a></section></body></html>`;
}

function parseForm(body) {
  return Object.fromEntries(
    String(body || "").split("&").filter(Boolean).map((part) => {
      const [key, value = ""] = part.split("=");
      return [decodeURIComponent(key.replace(/\+/g, " ")), decodeURIComponent(value.replace(/\+/g, " "))];
    })
  );
}

function legacyAuthChallenge(res) {
  res.writeHead(401, { "www-authenticate": 'Basic realm="Nova Cloud"', "cache-control": "no-store" });
  res.end("Login required");
}

function log(type, detail = {}) {
  events.push({ at: Date.now(), type, data: detail });
  while (events.length > 200) events.shift();
}

function cleanText(value) {
  return String(value || "").trim();
}

function stableId(prefix, value) {
  const base = cleanText(value) || crypto.randomUUID();
  return `${prefix}-${crypto.createHash("sha1").update(base).digest("hex").slice(0, 10)}`;
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    const next = text[i + 1];
    if (quoted && ch === '"' && next === '"') {
      cell += '"';
      i += 1;
    } else if (ch === '"') {
      quoted = !quoted;
    } else if (!quoted && ch === ",") {
      row.push(cell);
      cell = "";
    } else if (!quoted && (ch === "\n" || ch === "\r")) {
      if (ch === "\r" && next === "\n") i += 1;
      row.push(cell);
      if (row.some((v) => cleanText(v))) rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += ch;
    }
  }
  row.push(cell);
  if (row.some((v) => cleanText(v))) rows.push(row);
  return rows;
}

function toResident(input) {
  const name = cleanText(input.full_name || input.name);
  const room = cleanText(input.room);
  if (!name || !room) return null;
  return {
    id: cleanText(input.resident_id || input.id) || stableId("resident", `${name}:${room}`),
    name,
    room,
    mapPoint: cleanText(input.map_point || input.mapPoint || input.room),
    wing: cleanText(input.wing),
    careLevel: cleanText(input.care_level || input.careLevel),
    contactName: cleanText(input.primary_contact_name || input.contactName),
    contactPhone: cleanText(input.primary_contact_phone || input.contactPhone),
    medicationNotes: cleanText(input.medication_notes || input.medicationNotes),
    mobilityNotes: cleanText(input.mobility_notes || input.mobilityNotes),
    preferredLanguage: cleanText(input.preferred_language || input.preferredLanguage),
    checkInSchedule: cleanText(input.check_in_schedule || input.checkInSchedule),
    emergencyNotes: cleanText(input.emergency_notes || input.emergencyNotes),
    updatedAt: Date.now(),
  };
}

function mergedCare() {
  const robotCare = robot.care || {};
  return {
    residents: facility.residents,
    reminders: [...facility.reminders, ...(Array.isArray(robotCare.reminders) ? robotCare.reminders : [])],
    alerts: [...facility.alerts, ...(Array.isArray(robotCare.alerts) ? robotCare.alerts : [])],
    logs: [...facility.logs, ...(Array.isArray(robotCare.logs) ? robotCare.logs : [])],
  };
}

function page(user = users.get(ADMIN_USER)) {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZOX Robotics Care Cloud</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f0f4f8;color:#101a33;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}
.app{display:grid;grid-template-columns:260px 1fr;min-height:100vh}
.side{background:linear-gradient(180deg,#051b34,#092544);color:white;padding:22px 16px;display:flex;flex-direction:column;gap:18px;position:sticky;top:0;height:100vh;overflow-y:auto}
.brand{display:flex;gap:12px;align-items:center}
.logo{width:60px;height:60px;border-radius:14px;background:radial-gradient(circle at 50% 40%,#123963,#06172e 62%);border:2px solid #10c6e7;box-shadow:0 0 20px #10c6e744;display:grid;place-items:center;color:#1bd6ee;font-weight:950;font-size:20px;letter-spacing:-1px;overflow:hidden;flex-shrink:0}
.logo img{width:100%;height:100%;object-fit:cover}
.brand-text b{font-size:18px;font-weight:800;display:block}.brand-text .tag{color:#36d7ee;font-size:10px;letter-spacing:2.2px;font-weight:700}
.muted{color:#728198;font-size:13px}.side .muted{color:#a8bcd4}
.nav{display:grid;gap:4px}
.nav a{padding:11px 14px;border-radius:10px;color:#c8d8ec;text-decoration:none;cursor:pointer;font-weight:600;font-size:14px;transition:background .15s,color .15s}
.nav a.active{background:#1d66ca;color:white}.nav a:hover:not(.active){background:#ffffff14;color:white}
.sidebox{margin-top:auto;background:#ffffff0e;border:1px solid #ffffff1a;border-radius:14px;padding:14px}
.sidebox b{font-size:14px;font-weight:700}
.top{height:72px;background:white;border-bottom:1px solid #e4eaf2;display:flex;align-items:center;justify-content:space-between;padding:0 24px;position:sticky;top:0;z-index:10;box-shadow:0 1px 0 #e4eaf2}
.top h1{margin:0;font-size:20px;font-weight:800}
.topRight{display:flex;gap:8px;align-items:center;flex-wrap:wrap;justify-content:flex-end}
.content{padding:20px}
.view{display:none}.view.active{display:block}
.grid5{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}
.grid3{display:grid;grid-template-columns:1.15fr 1fr 1fr;gap:14px;margin-top:14px}
.two{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.card{background:white;border:1px solid #e4eaf2;border-radius:16px;padding:18px;box-shadow:0 2px 12px #3451a008}
.card h2{font-size:11px;font-weight:700;margin:0 0 14px;color:#8a9db8;text-transform:uppercase;letter-spacing:.8px}
.tile{border:0;border-radius:16px;min-height:130px;background:white;color:#08142f;box-shadow:0 4px 20px #2f4d7a10;font-weight:800;font-size:16px;cursor:pointer;padding:18px 10px;transition:transform .12s,box-shadow .12s;border:1px solid #e8eef6}
.tile:hover{transform:translateY(-2px);box-shadow:0 8px 28px #2f4d7a1c}
.tile span{display:grid;place-items:center;width:52px;height:52px;border-radius:12px;margin:0 auto 12px;color:white;font-size:20px}
.green{background:#28a155}.blue{background:#1f6fe8}.yellow{background:#e0a816}.red{background:#e04646}.purple{background:#8050d8}.cyan{background:#0ebad0}
.status{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}
.stat{border:1px solid #e4eaf2;border-radius:12px;padding:12px 14px;background:white}
.stat .sl{font-size:11px;color:#8a9db8;font-weight:600;text-transform:uppercase;letter-spacing:.5px;display:block}
.stat b{font-size:24px;display:block;margin-top:4px;font-weight:800}
.pill{border-radius:999px;padding:4px 10px;font-size:11px;font-weight:700;display:inline-block}
.ok{background:#e4f7ec;color:#1a7a3c}.bad{background:#fde8e8;color:#c02828}.warn{background:#fff3d6;color:#9a5c00}.low{background:#e4eeff;color:#1855c4}
.row{display:flex;gap:10px;align-items:center;border-bottom:1px solid #f2f5f9;padding:10px 0}
.row:last-child{border-bottom:0}
.dot{width:38px;height:38px;border-radius:10px;display:grid;place-items:center;color:white;font-weight:800;flex-shrink:0;font-size:15px}
.rb{flex:1;min-width:0}.rb b{display:block;font-size:14px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.rb span{display:block;font-size:12px;color:#8a9db8;margin-top:1px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ra{display:flex;gap:4px;flex-shrink:0;align-items:center}
.cmd{border:1px solid #d8e2ee;background:white;color:#14213d;border-radius:999px;padding:8px 14px;font-weight:700;font-size:13px;cursor:pointer;margin:3px;text-decoration:none;display:inline-block;transition:background .12s,border-color .12s}
.cmd:hover{background:#f0f4fa}.cmd.primary{background:#1f6fe8;color:white;border-color:#1f6fe8}.cmd.primary:hover{background:#1860d0}
.cmd.danger{background:#e04646;color:white;border-color:#e04646}.cmd.danger:hover{background:#c83c3c}
.cmd.sm{padding:5px 11px;font-size:12px}
.cmd:disabled{opacity:.4;cursor:not-allowed}
.field{width:100%;border:1px solid #d8e2ee;border-radius:10px;padding:10px 13px;margin:4px 0;font:inherit;font-size:14px;color:#101a33;outline:none;transition:border-color .15s,box-shadow .15s}
.field:focus{border-color:#1f6fe8;box-shadow:0 0 0 3px #1f6fe812}
.map{height:260px;border-radius:14px;background:linear-gradient(135deg,#eaf2ff,#d8e8f6);border:1px solid #ccdaee;position:relative;overflow:hidden}
.map.empty,.es{display:grid;place-items:center;color:#8a9db8;text-align:center;min-height:100px;border:1.5px dashed #c8d8ea;border-radius:12px;background:#f7fafd;padding:20px;font-size:13px}
.pin{position:absolute;width:30px;height:30px;border:0;border-radius:8px;display:grid;place-items:center;color:white;font-weight:900;cursor:pointer;font-size:12px;transition:transform .1s}
.pin:hover{transform:scale(1.2)}
.camera{display:none;margin-top:10px}.camera img{width:100%;max-height:340px;object-fit:contain;background:#071426;border-radius:10px}
.table{width:100%;border-collapse:collapse}
.table td,.table th{text-align:left;padding:10px 12px;border-bottom:1px solid #f0f4f8;vertical-align:top;font-size:13px}
.table th{font-size:11px;font-weight:700;color:#8a9db8;text-transform:uppercase;letter-spacing:.5px}
.small{font-size:12px}
.toast{position:fixed;right:18px;bottom:22px;background:#0d1f38;color:white;padding:12px 18px;border-radius:12px;box-shadow:0 8px 28px #0004;display:none;font-weight:600;font-size:14px;z-index:100}
.logoPreview{width:110px;height:110px;border-radius:18px;border:2px solid #10c6e7;background:#06172e;object-fit:cover;display:block;margin:8px 0}
.fs{background:#f7fafd;border-radius:12px;padding:14px;margin-bottom:10px}
.fl{font-size:11px;font-weight:700;color:#6880a0;text-transform:uppercase;letter-spacing:.5px;display:block;margin:8px 0 2px}
.inrow{display:flex;gap:8px}.inrow .inw{flex:1}
.ac{border:1px solid #fdc8c8;border-radius:12px;padding:12px 14px;background:#fff6f6;margin-bottom:8px;display:flex;align-items:flex-start;gap:10px}
.ac.std{border-color:#fde8b8;background:#fffbf0}
.adot{width:34px;height:34px;border-radius:9px;background:#e04646;color:white;display:grid;place-items:center;font-weight:900;flex-shrink:0;font-size:16px}
.adot.std{background:#e0a816}
.ab{flex:1;min-width:0}.ab b{font-weight:700;font-size:14px;display:block}.ab span{font-size:12px;color:#8a9db8;display:block;margin-top:2px}
.aa{display:flex;gap:4px;flex-shrink:0}
@media(max-width:1000px){.app{grid-template-columns:1fr}.side{display:none;position:static;height:auto}.top{height:auto;padding:14px;flex-direction:column;align-items:flex-start;position:static}.grid5,.grid3,.status,.two{grid-template-columns:1fr}.content{padding:12px}}
</style></head><body><div class="app">
<aside class="side">
<div class="brand"><div class="logo" id="sideLogo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX Robotics">` : "ZOX"}</div><div class="brand-text"><b>ZOX Robotics</b><span class="tag">SMART ROBOTS. BETTER CARE.</span></div></div>
<nav class="nav">
<a class="active" onclick="sv('command',this)">Command Center</a>
<a onclick="sv('robots',this)">Robot Feeds</a>
<a onclick="sv('rounds',this)">Care Rounds</a>
<a onclick="sv('residents',this)">Residents</a>
<a onclick="sv('alerts',this)">Alerts</a>
<a onclick="sv('map',this)">Map &amp; Messaging</a>
<a onclick="sv('logs',this)">Operations Log</a>
<a onclick="sv('settings',this)">Settings</a>
</nav>
<div class="sidebox">
<b>Nova Robot</b><span id="sideOnline" style="float:right;font-size:13px">0/1</span>
<p class="muted" id="sideHealth" style="margin:6px 0 10px">Waiting for robot</p>
<button class="cmd primary" style="width:100%;margin:0" onclick="cmd('camera_start')">Open Camera</button>
</div>
</aside>
<main style="display:flex;flex-direction:column;min-height:100vh;overflow:hidden">
<header class="top">
<div><h1 id="pageTitle">Command Center</h1><div class="muted" id="pageSubtitle">Live data from Nova and your facility registry.</div></div>
<div class="topRight"><span class="pill low">${cleanText(user?.username || ADMIN_USER)} &middot; ${cleanText(user?.role || "admin")}</span><span class="pill" id="onlinePill">Offline</span><span class="pill bad" id="alertCount">0 alerts</span><a class="cmd" href="/logout">Sign Out</a></div>
</header>
<div class="content" style="flex:1;overflow-y:auto">
<section class="view active" id="view-command">
<div class="grid5">
<button class="tile" onclick="cmd('start_rounds')"><span class="green">&#8635;</span>Start Rounds</button>
<button class="tile" onclick="checkInSelected()"><span class="blue">&#10003;</span>Check-In</button>
<button class="tile" onclick="medSelected()"><span class="yellow">&#9670;</span>Medication</button>
<button class="tile" onclick="staffAlert()"><span class="red">!</span>Staff Alert</button>
<button class="tile" onclick="guideSelected()"><span class="purple">&#8594;</span>Guide Visitor</button>
</div>
<div class="card" style="margin-top:14px"><div class="status">
<div class="stat"><span class="sl">Robot</span><b id="statRobot">0</b></div>
<div class="stat"><span class="sl">Residents</span><b id="statResidents">0</b></div>
<div class="stat"><span class="sl">Map Points</span><b id="statPoints">0</b></div>
<div class="stat"><span class="sl">People Seen</span><b id="statPeople">0</b></div>
<div class="stat"><span class="sl">Camera</span><b id="statCamera">Off</b></div>
</div></div>
<div class="grid3">
<div class="card"><h2>Robot Status</h2><div id="robotBox"></div><div style="margin-top:10px;display:flex;flex-wrap:wrap"><button class="cmd danger sm" onclick="cmd('stop')">Stop</button><button class="cmd sm" onclick="cmd('charge')">Charge</button><button class="cmd sm" onclick="cmd('security_start')">Detect</button><button class="cmd sm" onclick="cmd('security_stop')">End Detect</button></div></div>
<div class="card"><h2>Residents</h2><div id="residentBox"></div></div>
<div class="card"><h2>Active Alerts</h2><div id="alertBox"></div></div>
</div>
<div class="grid3">
<div class="card"><h2>People Detection</h2><div id="peopleBox"></div></div>
<div class="card"><h2>Map</h2><div class="map" id="mapBox"></div></div>
<div class="card"><h2>Camera</h2><div style="display:flex;gap:6px;margin-bottom:8px"><button class="cmd primary sm" onclick="cmd('camera_start')">Open</button><button class="cmd sm" onclick="cmd('camera_stop')">Close</button></div><div class="camera" id="cameraBox"><img id="camera" alt="Nova camera"><p class="muted" id="cameraNote"></p></div><div id="noCamera" class="es">No camera frame from Nova yet.</div></div>
</div>
</section>
<section class="view" id="view-robots"><div class="two">
<div class="card"><h2>Robot Telemetry</h2><div id="robotsFleet"></div><div style="margin-top:12px;display:flex;flex-wrap:wrap"><button class="cmd danger sm" onclick="cmd('stop')">Emergency Stop</button><button class="cmd sm" onclick="cmd('charge')">Go Charge</button><button class="cmd primary sm" onclick="cmd('camera_start')">Camera</button></div></div>
<div class="card"><h2>Detection Feed</h2><div id="robotDiagnostics"></div><pre class="small" id="rawDetection" style="background:#f7fafd;border-radius:8px;padding:10px;margin-top:10px;overflow:auto;max-height:240px;font-size:11px"></pre></div>
</div></section>
<section class="view" id="view-rounds"><div class="two">
<div class="card"><h2>Launch Rounds</h2><button class="tile" style="width:100%;min-height:90px;margin-bottom:14px" onclick="cmd('start_rounds')"><span class="green" style="display:inline-flex;width:38px;height:38px;margin-right:8px">&#8635;</span>Start Care Round</button><h2>Residents</h2><div id="roundResidents"></div></div>
<div class="card"><h2>Reminders</h2><div id="roundSchedule"></div></div>
</div></section>
<section class="view" id="view-residents"><div class="two">
<div class="card">
<h2 id="resFormTitle">Add Resident</h2>
<div class="fs">
<label class="fl">Full Name *</label><input class="field" id="manualName" placeholder="e.g. Mary Collins">
<div class="inrow"><div class="inw"><label class="fl">Room *</label><input class="field" id="manualRoom" placeholder="204"></div><div class="inw"><label class="fl">Map Point</label><input class="field" id="manualMapPoint" placeholder="Room 204"></div></div>
<div class="inrow"><div class="inw"><label class="fl">Wing</label><input class="field" id="manualWing" placeholder="A"></div><div class="inw"><label class="fl">Care Level</label><input class="field" id="manualCare" placeholder="Assisted"></div></div>
<label class="fl">Contact Phone</label><input class="field" id="manualPhone" placeholder="+1 555 0100">
<label class="fl">Care Notes (medication, mobility, emergency)</label><textarea class="field" id="manualNotes" rows="3" placeholder="Morning medication at 09:00..."></textarea>
<label class="fl">Check-in Prompt (what Nova says on arrival)</label><input class="field" id="manualPrompt" placeholder="Hello, I am checking in. Do you need anything?">
</div>
<div style="display:flex;gap:6px;flex-wrap:wrap"><button class="cmd primary" id="saveResBtn" onclick="saveResident()">Add Resident</button><button class="cmd danger sm" id="cancelEditBtn" style="display:none" onclick="cancelEdit()">Cancel</button></div>
<p class="muted" style="margin:12px 0 4px;font-weight:700">Import via CSV</p>
<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:6px"><a class="cmd sm" href="/templates/residents.csv">Download Template</a></div>
<input class="field" type="file" id="residentFile" accept=".csv,text/csv">
<button class="cmd sm" onclick="uploadResidents()" style="margin-top:4px">Upload CSV</button>
<p class="muted" style="margin-top:6px">Fill the template in Excel, save as CSV, then upload here.</p>
</div>
<div class="card">
<h2>Resident Directory</h2>
<div id="residentDirectory"></div>
<p class="muted" style="margin:12px 0 6px;font-weight:700">Quick Command</p>
<select class="field" id="residentSelect" style="margin-bottom:8px"></select>
<div style="display:flex;flex-wrap:wrap;gap:6px"><button class="cmd primary sm" onclick="checkInSelected()">Check In</button><button class="cmd sm" onclick="medSelected()">Medication</button><button class="cmd danger sm" onclick="cmd('staff_alert',{priority:'urgent',residentId:residentSelect.value,message:'Assistance requested for resident.'})">Alert Staff</button></div>
</div>
</div></section>
<section class="view" id="view-alerts"><div class="two">
<div class="card"><h2>Active Alerts</h2><div id="alertCenter"></div></div>
<div class="card"><h2>Create Alert</h2>
<label class="fl">Room or Location</label><input class="field" id="alertRoom" placeholder="Room 204, Lobby, Corridor B...">
<label class="fl">What happened?</label><textarea class="field" id="alertMessage" rows="4" placeholder="Describe the situation..."></textarea>
<div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px">
<button class="cmd danger" onclick="createAlert('urgent')">Urgent Alert</button>
<button class="cmd" onclick="createAlert('standard')">Standard Alert</button>
</div>
</div>
</div></section>
<section class="view" id="view-map"><div class="two">
<div class="card"><h2>Nova Map Points</h2><div class="map" id="fullMapBox"></div></div>
<div class="card"><h2>Destination Control</h2>
<label class="fl">Select Map Point</label><select class="field" id="pointSelect"></select>
<div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap"><button class="cmd primary" onclick="guideSelected()">Guide Visitor Here</button></div>
<label class="fl" style="margin-top:14px">Message to Deliver</label><textarea class="field" id="messageText" rows="3" placeholder="Message Nova should speak at this location"></textarea>
<button class="cmd" style="margin-top:4px" onclick="sendMessage()">Send Message to Point</button>
</div>
</div></section>
<section class="view" id="view-logs">
<div class="card"><h2>Operations Log</h2>
<table class="table"><thead><tr><th>Time</th><th>Event</th><th>Detail</th><th>Status</th></tr></thead><tbody id="opsLog"></tbody></table>
</div>
</section>
<section class="view" id="view-settings"><div class="two">
<div class="card"><h2>Cloud Relay</h2><div id="settingsRelay"></div>
<div style="margin-top:12px;display:flex;flex-wrap:wrap"><button class="cmd primary sm" onclick="cmd('camera_start')">Test Camera</button><button class="cmd sm" onclick="cmd('security_start')">Test Detection</button></div>
<p class="muted" style="margin:14px 0 4px;font-weight:700">Brand Logo</p>
<img class="logoPreview" id="logoPreview" src="${brandLogoDataUrl || ""}" alt="ZOX Robotics logo">
<input class="field" id="logoFile" type="file" accept="image/png,image/jpeg,image/webp" style="margin-top:6px">
<button class="cmd primary sm" onclick="uploadLogo()" style="margin-top:4px">Upload Logo</button>
<p class="muted">PNG/JPG under 5 MB.</p>
</div>
<div class="card"><h2>User Access</h2>
<div class="fs">
<label class="fl">Username</label><input class="field" id="newUser" placeholder="Username (3-40 chars)">
<label class="fl">Password</label><input class="field" id="newPass" type="password" placeholder="Min 8 characters">
<label class="fl">Role</label><select class="field" id="newRole"><option value="operator">operator</option><option value="viewer">viewer</option><option value="admin">admin</option></select>
<button class="cmd primary sm" style="margin-top:6px" onclick="addUser()">Add User</button>
</div>
<div id="userList"></div>
<p class="muted" style="margin:14px 0 4px;font-weight:700">CSV Column Format</p>
<table class="table small"><tbody id="formatRows"></tbody></table>
<a class="cmd sm" href="/templates/residents.csv" style="margin-top:8px;display:inline-block">Download Template</a>
</div>
</div></section>
</div>
</main>
</div>
<div class="toast" id="toast"></div>
<script>
const titles={command:["Command Center","Live data from Nova and your facility registry."],robots:["Robot Feeds","Telemetry, camera, people detection and SDK feed from Nova."],rounds:["Care Rounds","Launch rounds and check-in with registered residents."],residents:["Residents","Register residents by form or CSV, then send Nova on check-ins."],alerts:["Alerts","Create urgent staff alerts and monitor real-time facility events."],map:["Map &amp; Messaging","Live map points from Nova. Click a pin or select a point to guide a visitor or send a message."],logs:["Operations Log","Cloud commands, robot state pushes, results and facility actions."],settings:["Settings","Relay health, user management, brand logo and CSV import format."]};
const columns=${JSON.stringify(residentColumns)};
async function get(p){const r=await fetch(p,{cache:"no-store"});return r.json()}
async function post(p,body){const r=await fetch(p,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});return r.json()}
async function cmd(action,params={}){const out=await post("/api/command",{action,params});notice(out.ok?"Command queued":"Error: "+(out.error||"failed"));refresh()}
function esc(v){return String(v||"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]))}
function notice(t){toast.textContent=t;toast.style.display="block";setTimeout(()=>toast.style.display="none",2600)}
function sv(name,el){document.querySelectorAll(".view").forEach(v=>v.classList.remove("active"));document.getElementById("view-"+name).classList.add("active");document.querySelectorAll(".nav a").forEach(a=>a.classList.remove("active"));if(el)el.classList.add("active");pageTitle.innerHTML=titles[name][0];pageSubtitle.innerHTML=titles[name][1]}
function rr(color,title,detail,right=""){return '<div class="row"><div class="dot '+color+'">'+esc(String(title||"?")[0].toUpperCase())+'</div><div class="rb"><b>'+esc(title)+'</b><span>'+esc(detail)+'</span></div><div class="ra">'+right+'</div></div>'}
function es(text){return '<div class="es">'+esc(text)+'</div>'}
function residentById(id){return (window._s?.care?.residents||[]).find(r=>r.id===id)||null}
function residentParams(id){const r=residentById(id||residentSelect.value);return r?{residentId:r.id,residentName:r.name,room:r.room,mapPoint:r.mapPoint||r.room,notes:[r.medicationNotes,r.mobilityNotes,r.emergencyNotes].filter(Boolean).join("; "),checkInPrompt:r.checkInSchedule||""}:{residentId:id||""}}
function checkInResident(id){if(!id)return notice("Select a resident first.");cmd("resident_checkin",residentParams(id))}
function medResident(id){if(!id)return notice("Select a resident first.");cmd("med_reminder",residentParams(id))}
function checkInSelected(){checkInResident(residentSelect.value)}
function medSelected(){medResident(residentSelect.value)}
function guideSelected(){const p=pointSelect.value;if(!p||p.startsWith("No "))return notice("Nova has not sent map points yet.");cmd("visitor_guide",{destination:p})}
function staffAlert(){cmd("staff_alert",{priority:"urgent",message:"Staff assistance requested."})}
function sendMessage(){const p=pointSelect.value;if(!p||p.startsWith("No "))return notice("Nova has not sent map points yet.");cmd("message",{destination:p,message:messageText.value||"Please meet Nova at this location."})}
let _editId=null;
function editResident(id){const r=residentById(id);if(!r)return notice("Resident not found.");_editId=id;manualName.value=r.name||"";manualRoom.value=r.room||"";manualMapPoint.value=r.mapPoint||r.room||"";manualWing.value=r.wing||"";manualCare.value=r.careLevel||"";manualPhone.value=r.contactPhone||"";manualNotes.value=[r.medicationNotes,r.mobilityNotes,r.emergencyNotes].filter(Boolean).join("; ");manualPrompt.value=r.checkInSchedule||"";saveResBtn.textContent="Save Changes";cancelEditBtn.style.display="inline-block";resFormTitle.textContent="Edit Resident";sv("residents",document.querySelector('.nav a[onclick*=\\'residents\\']'));manualName.focus()}
function cancelEdit(){_editId=null;[manualName,manualRoom,manualMapPoint,manualWing,manualCare,manualPhone,manualNotes,manualPrompt].forEach(f=>f.value="");saveResBtn.textContent="Add Resident";cancelEditBtn.style.display="none";resFormTitle.textContent="Add Resident"}
async function saveResident(){const b={full_name:manualName.value,room:manualRoom.value,map_point:manualMapPoint.value||manualRoom.value,wing:manualWing.value,care_level:manualCare.value,primary_contact_phone:manualPhone.value,medication_notes:manualNotes.value,check_in_schedule:manualPrompt.value};if(_editId)b.resident_id=_editId;const out=await post("/api/residents",b);notice(out.ok?(_editId?"Resident updated":"Resident added"):(out.error||"Could not save resident"));if(out.ok)cancelEdit();refresh()}
async function deleteResident(id){if(!confirm("Remove this resident from the registry?"))return;const r=await fetch("/api/residents/"+encodeURIComponent(id),{method:"DELETE"});const out=await r.json();notice(out.ok?"Resident removed":(out.error||"Could not remove"));refresh()}
async function createAlert(priority){const out=await post("/api/alerts",{priority:priority||"urgent",room:alertRoom.value,message:alertMessage.value||"Staff assistance requested."});notice(out.ok?"Alert created":(out.error||"Could not create alert"));if(out.ok){alertRoom.value="";alertMessage.value=""}refresh()}
async function dismissAlert(id){const out=await post("/api/alerts/"+encodeURIComponent(id)+"/dismiss",{});notice(out.ok?"Alert dismissed":(out.error||"Could not dismiss"));refresh()}
async function uploadResidents(){const file=residentFile.files[0];if(!file)return notice("Choose the completed CSV first.");const text=await file.text();const r=await fetch("/api/residents/import",{method:"POST",headers:{"content-type":"text/csv"},body:text});const out=await r.json();notice(out.ok?("Imported "+out.count+" residents"):(out.error||"Import failed"));residentFile.value="";refresh()}
async function uploadLogo(){const file=logoFile.files[0];if(!file)return notice("Choose the ZOX logo image first.");if(file.size>5*1024*1024)return notice("Logo too large. Use PNG/JPEG under 5 MB.");const reader=new FileReader();reader.onload=async()=>{const out=await post("/api/logo",{dataUrl:reader.result});notice(out.ok?"Logo updated":(out.error||"Logo update failed"));if(out.logo){logoPreview.src=out.logo;sideLogo.innerHTML='<img src="'+out.logo+'" alt="ZOX Robotics">'}logoFile.value=""};reader.onerror=()=>notice("Could not read image file.");reader.readAsDataURL(file)}
async function addUser(){const out=await post("/api/users",{username:newUser.value,password:newPass.value,role:newRole.value});notice(out.ok?"User added":(out.error||"Could not add user"));newUser.value="";newPass.value="";loadUsers()}
async function loadUsers(){const out=await get("/api/users");userList.innerHTML=out.users?out.users.map(u=>rr(u.role==="admin"?"blue":"green",u.username,u.role+" &middot; "+new Date(u.createdAt).toLocaleDateString())).join(""):es(out.error||"User list unavailable")}
function renderMap(target,points){if(!points.length){target.className="map es";target.innerHTML="No map points from Nova yet.";return}target.className="map";target.innerHTML=points.slice(0,10).map((p,i)=>'<button class="pin '+(i%2?"green":"blue")+'" title="'+esc(p.name)+'" style="left:'+(8+(i*17)%78)+'%;top:'+(16+(i*23)%62)+'%" onclick="cmd(\\'visitor_guide\\',{destination:\\''+esc(p.name)+'\\'})">'+(i+1)+'</button>').join("")}
function alertCard(a){const u=a.priority!=="standard";return '<div class="ac'+(u?"":" std")+'"><div class="adot'+(u?"":" std")+'">'+(u?"!":"▲")+'</div><div class="ab"><b>'+esc(a.message||"Alert")+'</b><span>'+esc(a.room||a.residentId||"Facility")+" &middot; "+new Date(a.createdAt||Date.now()).toLocaleTimeString()+'</span></div><div class="aa"><button class="cmd sm danger" onclick="dismissAlert(\\''+esc(a.id)+'\\')">Dismiss</button></div></div>'}
function renderAll(s){const c=s.care||{};const res=c.residents||[];const rem=c.reminders||[];const al=c.alerts||[];const pts=s.points||[];const ppl=s.people||[];window._s=s;
statRobot.textContent=s.online?"1":"0";statResidents.textContent=res.length;statPoints.textContent=pts.length;statPeople.textContent=ppl.length;statCamera.textContent=s.camera?"On":"Off";
sideOnline.textContent=(s.online?"1":"0")+"/1";sideHealth.textContent=s.online?"Connected &middot; "+new Date(s.lastSeen).toLocaleTimeString():"Waiting for robot";
onlinePill.textContent=s.online?"Online":"Offline";onlinePill.className="pill "+(s.online?"ok":"bad");
alertCount.textContent=al.length+" alert"+(al.length===1?"":"s");alertCount.className="pill "+(al.length?"bad":"ok");
robotBox.innerHTML=rr(s.online?"blue":"red","Nova 01",(s.status?.destination||"No destination")+" &mdash; "+(s.status?.battery||"Battery unknown"),'<span class="pill '+(s.online?"ok":"bad")+'">'+(s.online?"Online":"Offline")+"</span>")+'<p class="muted" style="margin:8px 0 0;font-size:12px">'+esc(s.status?.status||"No status from Nova yet.")+"</p>";
residentBox.innerHTML=res.length?res.slice(0,4).map(r=>rr("purple",r.name,r.room,'<button class="cmd sm" onclick="checkInResident(\\''+esc(r.id)+'\\')">Go</button>')).join("")+(res.length>4?'<p class="muted" style="margin:8px 0 0;font-size:12px">+'+(res.length-4)+" more</p>":""):es("No residents registered.");
alertBox.innerHTML=al.length?al.slice(0,4).map(a=>rr(a.priority!=="standard"?"red":"yellow",a.message||"Alert",a.room||"Facility",'<button class="cmd sm danger" onclick="dismissAlert(\\''+esc(a.id)+'\\')">&#215;</button>')).join("")+(al.length>4?'<p class="muted" style="margin:8px 0 0;font-size:12px">+'+(al.length-4)+" more</p>":""):es("No active alerts.");
peopleBox.innerHTML=ppl.length?ppl.map(p=>rr("cyan","Target "+(p.id??"?"),"x="+(p.x??"-")+" y="+(p.y??"-")+" d="+(p.distance??"-"))).join(""):es("No people detected by Nova.");
renderMap(mapBox,pts);renderMap(fullMapBox,pts);
robotsFleet.innerHTML=robotBox.innerHTML;
robotDiagnostics.innerHTML=rr(s.status?.robotSdk?"green":"yellow","RobotAPI","AgentOS: "+(s.status?.robotSdk?"connected":"not detected"))+rr("blue","Map Points",pts.length+" known")+rr("cyan","People",ppl.length+" detected")+rr(s.camera?"green":"red","Camera",s.camera?"Frame available":"No frame");
rawDetection.textContent=JSON.stringify({detection:s.detection||{},people:ppl},null,2);
roundResidents.innerHTML=res.length?res.map(r=>rr("blue",r.name,r.room,'<button class="cmd sm" onclick="checkInResident(\\''+esc(r.id)+'\\')">Check In</button><button class="cmd sm" onclick="medResident(\\''+esc(r.id)+'\\')">Med</button>')).join(""):es("Register residents to use care rounds.");
roundSchedule.innerHTML=rem.length?rem.map(r=>rr("green",r.timeLabel||"Scheduled",r.title||"Reminder")).join(""):es("No reminders scheduled.");
residentDirectory.innerHTML=res.length?res.map(r=>rr("purple",r.name,r.room+(r.wing?" &middot; "+r.wing:"")+(r.careLevel?" &mdash; "+r.careLevel:""),'<button class="cmd sm" onclick="editResident(\\''+esc(r.id)+'\\')">Edit</button><button class="cmd sm danger" onclick="deleteResident(\\''+esc(r.id)+'\\')">Del</button>')).join(""):es("No residents. Add one using the form.");
residentSelect.innerHTML=res.length?res.map(r=>'<option value="'+esc(r.id)+'">'+esc(r.name)+" &mdash; "+esc(r.room)+"</option>").join(""):"<option disabled>No residents registered</option>";
alertCenter.innerHTML=al.length?al.map(alertCard).join(""):es("No active alerts.");
pointSelect.innerHTML=pts.length?pts.map(p=>"<option>"+esc(p.name)+"</option>"):"<option disabled>No points from Nova yet</option>";
const logRows=[...(c.logs||[]),...(s.events||[]).slice().reverse().map(e=>({createdAt:e.at,title:e.type,detail:JSON.stringify(e.data||{})}))];
opsLog.innerHTML=(logRows.length?logRows:[{title:"Ready",detail:"Waiting for robot and facility activity"}]).slice(0,80).map(l=>"<tr><td class='small'>"+new Date(l.createdAt||Date.now()).toLocaleTimeString()+"</td><td>"+esc(l.title||"Event")+"</td><td class='small muted'>"+esc(String(l.detail||l.mapPoint||"").slice(0,120))+"</td><td><span class='pill ok'>ok</span></td></tr>").join("");
settingsRelay.innerHTML=rr(s.online?"green":"red","Robot Cloud",s.online?"Connected &middot; "+new Date(s.lastSeen).toLocaleTimeString():"Offline")+rr(s.camera?"green":"red","Camera",s.camera?"Frame ready":"No frame")+rr("blue","Residents",res.length+" in registry");
const fmtHelp={resident_id:"Optional. Leave blank to auto-generate.",full_name:"Required.",room:"Required.",map_point:"Nova map point name (exact). Falls back to room.",wing:"Optional location group.",care_level:"e.g. Independent / Assisted / High.",primary_contact_name:"Optional.",primary_contact_phone:"Optional.",medication_notes:"Care note.",mobility_notes:"Mobility/safety note.",preferred_language:"Optional.",check_in_schedule:"e.g. daily 09:00.",emergency_notes:"Optional urgent note."};
formatRows.innerHTML=columns.map(c=>"<tr><td><b>"+c+"</b></td><td class='muted'>"+esc(fmtHelp[c]||"")+"</td></tr>").join("");
if(s.camera){cameraBox.style.display="block";noCamera.style.display="none";camera.src="/api/camera.jpg?t="+Date.now();cameraNote.textContent="Live snapshot from Nova."}else{cameraBox.style.display="none";noCamera.style.display="grid"}
}
async function refresh(){renderAll(await get("/api/state"))}
setInterval(refresh,2000);refresh();loadUsers();
</script></body></html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  if (url.pathname === "/health") return sendJson(res, 200, { ok: true });

  if (url.pathname === "/login" && req.method === "GET") {
    if (currentUser(req) || isAdmin(req)) {
      res.writeHead(302, { location: "/", "cache-control": "no-store" });
      return res.end();
    }
    return sendText(res, 200, loginPage(), "text/html; charset=utf-8");
  }
  if (url.pathname === "/login" && req.method === "POST") {
    const form = parseForm(await readBody(req));
    const user = users.get(cleanText(form.username));
    if (!user || !verifyPassword(form.password, user.passwordHash)) {
      log("login_failed", { username: cleanText(form.username) });
      return sendText(res, 401, loginPage("Invalid username or password."), "text/html; charset=utf-8");
    }
    createSession(res, user.username);
    log("login", { username: user.username });
    res.writeHead(302, { location: "/", "cache-control": "no-store" });
    return res.end();
  }
  if (url.pathname === "/logout") {
    const sid = parseCookies(req).nova_session;
    if (sid) sessions.delete(sid);
    res.writeHead(200, {
      "set-cookie": "nova_session=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0",
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
    });
    return res.end(logoutPage());
  }

  if (url.pathname.startsWith("/robot/")) {
    if (!isRobot(req)) return sendJson(res, 403, { ok: false, error: "bad robot token" });
    robot.online = true;
    robot.lastSeen = Date.now();
    if (url.pathname === "/robot/state" && req.method === "POST") {
      const body = JSON.parse((await readBody(req)) || "{}");
      robot.status = body.status || robot.status;
      robot.detection = body.detection || robot.detection;
      robot.people = Array.isArray(body.people) ? body.people : robot.people;
      robot.points = Array.isArray(body.points) ? body.points : robot.points;
      robot.care = body.care || robot.care;
      if (body.cameraJpegBase64) robot.cameraJpegBase64 = body.cameraJpegBase64;
      log("state", { people: robot.people.length, points: robot.points.length, camera: !!robot.cameraJpegBase64 });
      return sendJson(res, 200, { ok: true });
    }
    if (url.pathname === "/robot/poll") return sendJson(res, 200, { commands: commandQueue.splice(0, 20) });
    if (url.pathname === "/robot/result" && req.method === "POST") {
      log("result", JSON.parse((await readBody(req)) || "{}"));
      return sendJson(res, 200, { ok: true });
    }
  }

  if (!requireAdmin(req, res)) return;

  if (url.pathname === "/") return sendText(res, 200, page(currentUser(req) || users.get(ADMIN_USER)), "text/html; charset=utf-8");
  if (url.pathname === "/api/me") {
    const user = currentUser(req) || users.get(ADMIN_USER);
    return sendJson(res, 200, { ok: true, user: { username: user.username, role: user.role } });
  }
  if (url.pathname === "/api/users" && req.method === "GET") {
    if (!requireRole(req, res, "admin")) return;
    return sendJson(res, 200, {
      ok: true,
      users: Array.from(users.values()).map((u) => ({ username: u.username, role: u.role, createdAt: u.createdAt })),
    });
  }
  if (url.pathname === "/api/users" && req.method === "POST") {
    const actor = requireRole(req, res, "admin");
    if (!actor) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const username = cleanText(body.username).toLowerCase();
    const password = String(body.password || "");
    const role = ["admin", "operator", "viewer"].includes(cleanText(body.role)) ? cleanText(body.role) : "operator";
    if (!/^[a-z0-9._-]{3,40}$/.test(username)) return sendJson(res, 400, { ok: false, error: "username must be 3-40 letters, numbers, dot, dash, or underscore" });
    if (password.length < 8) return sendJson(res, 400, { ok: false, error: "password must be at least 8 characters" });
    users.set(username, { username, role, passwordHash: passwordHash(password), createdAt: Date.now() });
    log("user_created", { username, role, actor: actor.username });
    return sendJson(res, 200, { ok: true, user: { username, role } });
  }
  if (url.pathname === "/api/logo" && req.method === "POST") {
    const actor = requireRole(req, res, "admin");
    if (!actor) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const dataUrl = String(body.dataUrl || "");
    if (!/^data:image\/(png|jpg|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(dataUrl)) return sendJson(res, 400, { ok: false, error: "Upload a PNG, JPG, JPEG, or WEBP image." });
    if (Buffer.byteLength(dataUrl) > 7_000_000) return sendJson(res, 413, { ok: false, error: "Logo is too large. Use an image under 5 MB." });
    brandLogoDataUrl = dataUrl;
    log("logo_updated", { actor: actor.username });
    return sendJson(res, 200, { ok: true, logo: brandLogoDataUrl });
  }
  if (url.pathname === "/templates/residents.csv") {
    const example = [
      residentColumns.join(","),
      'R-204,"Mary Collins",204,Room 204,A,Assisted,"Sarah Collins","+1 555 0100","Morning medication at 09:00","Walker; avoid stairs",English,"daily 09:00","Call nurse if no response"',
    ].join("\n");
    res.writeHead(200, {
      "content-type": "text/csv; charset=utf-8",
      "content-disposition": 'attachment; filename="zox-resident-import-template.csv"',
      "cache-control": "no-store",
    });
    return res.end(example);
  }
  if (url.pathname === "/api/state") {
    const stale = Date.now() - robot.lastSeen > 15000;
    return sendJson(res, 200, { ...robot, online: robot.online && !stale, care: mergedCare(), camera: !!robot.cameraJpegBase64, events });
  }
  if (url.pathname === "/api/camera.jpg") {
    if (!robot.cameraJpegBase64) return sendText(res, 404, "No real camera snapshot from Nova");
    const data = Buffer.from(robot.cameraJpegBase64, "base64");
    res.writeHead(200, { "content-type": "image/jpeg", "cache-control": "no-store", "content-length": data.length });
    return res.end(data);
  }
  if (url.pathname === "/api/residents" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const resident = toResident(JSON.parse((await readBody(req)) || "{}"));
    if (!resident) return sendJson(res, 400, { ok: false, error: "full_name and room are required" });
    const idx = facility.residents.findIndex((r) => r.id === resident.id);
    if (idx >= 0) facility.residents[idx] = resident;
    else facility.residents.push(resident);
    facility.logs.push({ createdAt: Date.now(), title: "Resident registered", detail: `${resident.name} - ${resident.room}` });
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "upsert_resident", params: {
      id: resident.id, name: resident.name, room: resident.room, mapPoint: resident.mapPoint || resident.room,
      notes: [resident.medicationNotes, resident.mobilityNotes].filter(Boolean).join("; "),
      checkInPrompt: resident.checkInSchedule || `Hello ${resident.name}. I am checking in. Do you need anything?`
    }});
    log("resident", { id: resident.id, name: resident.name });
    return sendJson(res, 200, { ok: true, resident });
  }
  if (url.pathname === "/api/residents/import" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const rows = parseCsv(await readBody(req));
    if (rows.length < 2) return sendJson(res, 400, { ok: false, error: "CSV must include a header row and at least one resident" });
    const header = rows[0].map((v) => cleanText(v).toLowerCase());
    const imported = [];
    rows.slice(1).forEach((cells) => {
      const item = {};
      header.forEach((key, i) => { item[key] = cells[i] || ""; });
      const resident = toResident(item);
      if (resident) imported.push(resident);
    });
    imported.forEach((resident) => {
      const idx = facility.residents.findIndex((r) => r.id === resident.id);
      if (idx >= 0) facility.residents[idx] = resident;
      else facility.residents.push(resident);
    });
    facility.logs.push({ createdAt: Date.now(), title: "Resident import", detail: `${imported.length} residents imported` });
    log("resident_import", { count: imported.length });
    return sendJson(res, 200, { ok: true, count: imported.length, residents: facility.residents });
  }
  if (url.pathname === "/api/alerts" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const alert = { id: crypto.randomUUID(), createdAt: Date.now(), priority: cleanText(body.priority) || "urgent", room: cleanText(body.room), message: cleanText(body.message) || "Staff assistance requested." };
    facility.alerts.unshift(alert);
    facility.logs.push({ createdAt: Date.now(), title: "Alert created", detail: `${alert.room || "Facility"} - ${alert.message}` });
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "staff_alert", params: alert });
    log("alert", alert);
    return sendJson(res, 200, { ok: true, alert });
  }
  if (url.pathname === "/api/command" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const command = { id: crypto.randomUUID(), at: Date.now(), action: cleanText(body.action), params: body.params || {} };
    if (!command.action) return sendJson(res, 400, { ok: false, error: "action is required" });
    commandQueue.push(command);
    facility.logs.push({ createdAt: Date.now(), title: "Command queued", detail: `${command.action} ${JSON.stringify(command.params)}` });
    log("command", command);
    return sendJson(res, 200, { ok: true, command });
  }
  const residentDeleteMatch = url.pathname.match(/^\/api\/residents\/([^/]+)$/);
  if (residentDeleteMatch && req.method === "DELETE") {
    if (!requireRole(req, res, "operator")) return;
    const id = decodeURIComponent(residentDeleteMatch[1]);
    const idx = facility.residents.findIndex((r) => r.id === id);
    if (idx < 0) return sendJson(res, 404, { ok: false, error: "resident not found" });
    const removed = facility.residents.splice(idx, 1)[0];
    facility.logs.push({ createdAt: Date.now(), title: "Resident removed", detail: `${removed.name} — ${removed.room}` });
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "delete_resident", params: { id: removed.id } });
    log("resident_deleted", { id: removed.id, name: removed.name });
    return sendJson(res, 200, { ok: true });
  }
  const alertDismissMatch = url.pathname.match(/^\/api\/alerts\/([^/]+)\/dismiss$/);
  if (alertDismissMatch && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const alertId = decodeURIComponent(alertDismissMatch[1]);
    const idx = facility.alerts.findIndex((a) => a.id === alertId);
    if (idx >= 0) facility.alerts.splice(idx, 1);
    facility.logs.push({ createdAt: Date.now(), title: "Alert dismissed", detail: alertId });
    log("alert_dismissed", { id: alertId });
    return sendJson(res, 200, { ok: true });
  }
  sendJson(res, 404, { ok: false, error: "not found" });
});

server.listen(PORT, () => console.log(`Nova cloud relay listening on ${PORT}`));
