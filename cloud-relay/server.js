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
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>ZOX Robotics Sign In v3</title><style>
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
  return `<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZOX Robotics — Care Cloud v3</title>
<style>
*{box-sizing:border-box;-webkit-font-smoothing:antialiased}
body{margin:0;background:#eef2f7;color:#111827;font-family:Inter,system-ui,-apple-system,sans-serif;font-size:14px}
.app{display:grid;grid-template-columns:252px 1fr;min-height:100vh}
.side{background:linear-gradient(175deg,#03132b 0%,#061f42 60%,#082a56 100%);color:white;padding:20px 14px;display:flex;flex-direction:column;gap:0;position:sticky;top:0;height:100vh;overflow-y:auto}
.brand{display:flex;gap:12px;align-items:center;padding-bottom:20px;border-bottom:1px solid #ffffff12;margin-bottom:16px}
.logo{width:56px;height:56px;border-radius:13px;background:radial-gradient(circle at 40% 35%,#0e4a8a,#041428 70%);border:1.5px solid #18d0f0;box-shadow:0 0 18px #10c6e730;display:grid;place-items:center;color:#1bd6ee;font-weight:900;font-size:19px;overflow:hidden;flex-shrink:0}
.logo img{width:100%;height:100%;object-fit:cover}
.brand-text b{font-size:16px;font-weight:800;display:block;letter-spacing:-.2px}.brand-text .tag{color:#40d8f0;font-size:9px;letter-spacing:2.4px;font-weight:700;margin-top:2px;display:block}
.nav{display:flex;flex-direction:column;gap:2px;flex:1}
.nav a{padding:10px 13px;border-radius:9px;color:#94aec8;text-decoration:none;cursor:pointer;font-weight:600;font-size:13.5px;transition:background .15s,color .15s;display:flex;align-items:center;gap:9px;letter-spacing:-.1px}
.nav a .ni{width:18px;text-align:center;opacity:.7;flex-shrink:0}
.nav a.active{background:#1960c8;color:white;box-shadow:0 2px 10px #1960c840}.nav a.active .ni{opacity:1}
.nav a:hover:not(.active){background:#ffffff10;color:#d4e6f8}
.navdiv{height:1px;background:#ffffff10;margin:8px 0}
.sidebox{background:#ffffff08;border:1px solid #ffffff12;border-radius:12px;padding:13px;margin-top:12px}
.sidebox .sbt{font-size:13px;font-weight:700;margin-bottom:3px;display:flex;justify-content:space-between;align-items:center}
.sblabel{font-size:11px;color:#7fa8cc;margin-bottom:8px}
.top{height:68px;background:white;border-bottom:1px solid #e5eaf3;display:flex;align-items:center;justify-content:space-between;padding:0 22px;position:sticky;top:0;z-index:10}
.top h1{margin:0;font-size:18px;font-weight:800;letter-spacing:-.3px}
.top .sub{font-size:12px;color:#8898b0;margin-top:2px}
.topRight{display:flex;gap:8px;align-items:center}
.content{padding:20px;flex:1}
.view{display:none}.view.active{display:block}
#view-command{display:block}
#view-command.hidden{display:none}
.g5{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}
.g3{display:grid;grid-template-columns:1.1fr 1fr 1fr;gap:14px;margin-top:14px}
.g2{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.card{background:white;border:1px solid #e5eaf3;border-radius:16px;padding:20px;box-shadow:0 1px 8px #1a2d4a08}
.ch{font-size:11px;font-weight:700;color:#95a8be;text-transform:uppercase;letter-spacing:.9px;margin:0 0 16px;display:flex;align-items:center;justify-content:space-between}
.tile{border:1px solid #e8edf5;border-radius:14px;min-height:120px;background:white;color:#111827;box-shadow:0 2px 14px #1e3a6808;font-weight:800;font-size:15px;cursor:pointer;padding:18px 10px 16px;transition:transform .12s,box-shadow .12s,border-color .12s;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px}
.tile:hover{transform:translateY(-2px);box-shadow:0 8px 24px #1e3a6818;border-color:#c8d8f0}
.tile .ti{width:48px;height:48px;border-radius:12px;display:grid;place-items:center;color:white;font-size:19px;flex-shrink:0}
.c-green{background:#1f9950}.c-blue{background:#1a68e0}.c-yellow{background:#d49600}.c-red{background:#d63b3b}.c-purple{background:#7848cc}.c-cyan{background:#0ab5cc}.c-orange{background:#d06a20}
.stats{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-top:14px}
.stat{border:1px solid #e8edf5;border-radius:12px;padding:14px 12px;background:white}
.stat .sl{font-size:10px;color:#95a8be;font-weight:700;text-transform:uppercase;letter-spacing:.6px;display:block}
.stat b{font-size:22px;display:block;margin-top:5px;font-weight:800;color:#111827}
.pill{border-radius:999px;padding:3px 9px;font-size:11px;font-weight:700;display:inline-flex;align-items:center;gap:4px;white-space:nowrap}
.ok{background:#dff5e9;color:#15773a}.bad{background:#fce8e8;color:#b82020}.warn{background:#fef3d0;color:#8f5c00}.low{background:#e4edff;color:#1448b8}.off{background:#eaeef5;color:#5a6a80}
.row{display:flex;gap:10px;align-items:center;border-bottom:1px solid #f0f4fa;padding:11px 0}
.row:last-child{border-bottom:0}
.dot{width:36px;height:36px;border-radius:9px;display:grid;place-items:center;color:white;font-weight:800;flex-shrink:0;font-size:14px}
.rb{flex:1;min-width:0}.rb b{display:block;font-size:13.5px;font-weight:700}.rb span{display:block;font-size:12px;color:#8898b0;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ra{display:flex;gap:4px;flex-shrink:0;align-items:center}
.sdot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:5px}
.sdot.on{background:#1f9950}.sdot.off{background:#d63b3b}.sdot.warn{background:#d49600}
.btn{border:1px solid #d5dde8;background:white;color:#1a2840;border-radius:999px;padding:8px 16px;font-weight:700;font-size:13px;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:5px;transition:all .12s;white-space:nowrap}
.btn:hover{background:#f0f5fc;border-color:#b8c8de}
.btn.p{background:#1a68e0;color:white;border-color:#1a68e0}.btn.p:hover{background:#155ac4}
.btn.d{background:#d63b3b;color:white;border-color:#d63b3b}.btn.d:hover{background:#be3232}
.btn.s{padding:5px 12px;font-size:12px}
.btn:disabled{opacity:.4;cursor:not-allowed;pointer-events:none}
.field{width:100%;border:1px solid #d5dde8;border-radius:10px;padding:10px 13px;margin:4px 0;font:inherit;font-size:14px;color:#111827;outline:none;transition:border-color .15s,box-shadow .15s;background:white}
.field:focus{border-color:#1a68e0;box-shadow:0 0 0 3px #1a68e010}
select.field{cursor:pointer}
.map{height:260px;border-radius:13px;background:linear-gradient(145deg,#e6f0ff,#d0e3f5);border:1px solid #c8d8ee;position:relative;overflow:hidden}
.map.empty,.esbox{display:grid;place-items:center;color:#8898b0;text-align:center;min-height:90px;border:1.5px dashed #c0d0e0;border-radius:12px;background:#f5f8fd;padding:20px;font-size:13px}
.pin{position:absolute;width:28px;height:28px;border:0;border-radius:7px;display:grid;place-items:center;color:white;font-weight:900;cursor:pointer;font-size:11px;transition:transform .1s,box-shadow .1s}
.pin:hover{transform:scale(1.18);box-shadow:0 4px 12px #0004}
.camera{display:none;margin-top:10px}.camera img{width:100%;max-height:320px;object-fit:contain;background:#060e1f;border-radius:10px}
.tbl{width:100%;border-collapse:collapse}
.tbl td,.tbl th{text-align:left;padding:10px 14px;border-bottom:1px solid #f0f4fa;font-size:13px;vertical-align:top}
.tbl th{font-size:11px;font-weight:700;color:#8898b0;text-transform:uppercase;letter-spacing:.5px;background:#fafbfd}
.tbl tr:last-child td{border-bottom:0}
.toast{position:fixed;right:20px;bottom:24px;background:#0d1f3c;color:white;padding:13px 18px;border-radius:12px;box-shadow:0 8px 30px #00000030;display:none;font-weight:600;font-size:14px;z-index:200;max-width:320px}
.lp{width:100px;height:100px;border-radius:16px;border:2px solid #18d0f0;background:#06172e;object-fit:cover;display:block;margin:8px 0}
.fbox{background:#f5f8fd;border:1px solid #e5eaf3;border-radius:12px;padding:14px;margin-bottom:12px}
.fl{font-size:11px;font-weight:700;color:#6878a0;text-transform:uppercase;letter-spacing:.5px;display:block;margin:8px 0 3px}
.fl:first-child{margin-top:0}
.ir{display:flex;gap:8px}.ir>.iw{flex:1}
.ac{border:1px solid #f8c8c8;border-radius:12px;padding:14px;background:#fff5f5;margin-bottom:8px;display:flex;align-items:flex-start;gap:12px}
.ac.std{border-color:#fde5b0;background:#fffbf0}
.adot{width:36px;height:36px;border-radius:10px;background:#d63b3b;color:white;display:grid;place-items:center;font-weight:900;flex-shrink:0;font-size:17px}
.adot.std{background:#d49600}
.ab{flex:1;min-width:0}.ab b{font-weight:700;font-size:14px;display:block;color:#1a1a2e}.ab .as{font-size:12px;color:#8898b0;margin-top:3px;display:block}
.aa{flex-shrink:0;margin-top:2px}
.dchip{display:inline-flex;align-items:center;gap:5px;background:#f0f5ff;border:1px solid #d0ddf5;border-radius:8px;padding:6px 10px;font-size:12px;font-weight:600;color:#1448b8;margin:3px}
.dchip .dc{width:6px;height:6px;border-radius:50%;background:#1a68e0;flex-shrink:0}
@media(max-width:960px){.app{display:block}.side{display:none}.top{position:static;height:auto;padding:14px;flex-direction:column;gap:8px;align-items:flex-start}.g5,.g3,.g2,.stats{grid-template-columns:1fr}.content{padding:12px}}
</style></head><body><div class="app">
<aside class="side">
<div class="brand">
<div class="logo" id="sideLogo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX">` : "ZOX"}</div>
<div class="brand-text"><b>ZOX Robotics</b><span class="tag">SMART ROBOTS. BETTER CARE.</span></div>
</div>
<nav class="nav">
<a data-view="command" class="active" onclick="sv('command',this)"><span class="ni">&#9632;</span>Command Center</a>
<a data-view="robots" onclick="sv('robots',this)"><span class="ni">&#9685;</span>Robot Feeds</a>
<div class="navdiv"></div>
<a data-view="rounds" onclick="sv('rounds',this)"><span class="ni">&#8635;</span>Care Rounds</a>
<a data-view="residents" onclick="sv('residents',this)"><span class="ni">&#9673;</span>Residents</a>
<a data-view="alerts" onclick="sv('alerts',this)"><span class="ni">&#9888;</span>Alerts</a>
<div class="navdiv"></div>
<a data-view="map" onclick="sv('map',this)"><span class="ni">&#9685;</span>Map &amp; Messaging</a>
<a data-view="logs" onclick="sv('logs',this)"><span class="ni">&#9776;</span>Operations Log</a>
<a data-view="settings" onclick="sv('settings',this)"><span class="ni">&#9881;</span>Settings</a>
</nav>
<div class="sidebox">
<div class="sbt"><span>Nova Robot</span><span id="sideOnline" class="pill off" style="font-size:10px">0 / 1</span></div>
<div class="sblabel" id="sideHealth">Waiting for robot connection</div>
<button class="btn p" style="width:100%;justify-content:center" onclick="cmd('camera_start')">Open Camera</button>
</div>
</aside>
<main style="display:flex;flex-direction:column;overflow:hidden;height:100vh">
<header class="top">
<div><h1 id="pageTitle">Command Center</h1><div class="sub" id="pageSubtitle">Live data from Nova and your facility registry.</div></div>
<div class="topRight">
<span class="pill low">${cleanText(user?.username || ADMIN_USER)} &middot; ${cleanText(user?.role || "admin")}</span>
<span class="pill off" id="onlinePill">Offline</span>
<span class="pill bad" id="alertCount" style="display:none">0 alerts</span>
<a class="btn" href="/logout">Sign Out</a>
</div>
</header>
<div class="content" style="overflow-y:auto">

<section class="view active" id="view-command">
<div class="g5">
<button class="tile" onclick="cmd('start_rounds')"><div class="ti c-green">&#8635;</div>Start Rounds</button>
<button class="tile" onclick="checkInSelected()"><div class="ti c-blue">&#10003;</div>Check-In</button>
<button class="tile" onclick="medSelected()"><div class="ti c-yellow">&#9670;</div>Medication</button>
<button class="tile" onclick="staffAlert()"><div class="ti c-red">!</div>Staff Alert</button>
<button class="tile" onclick="guideSelected()"><div class="ti c-purple">&#8594;</div>Guide Visitor</button>
</div>
<div class="stats">
<div class="stat"><span class="sl">Robot</span><b id="statRobot">—</b></div>
<div class="stat"><span class="sl">Residents</span><b id="statResidents">—</b></div>
<div class="stat"><span class="sl">Map Points</span><b id="statPoints">—</b></div>
<div class="stat"><span class="sl">People Seen</span><b id="statPeople">—</b></div>
<div class="stat"><span class="sl">Camera</span><b id="statCamera">—</b></div>
</div>
<div class="g3">
<div class="card"><div class="ch">Robot Status<div style="display:flex;gap:5px"><button class="btn s d" onclick="cmd('stop')">Stop</button><button class="btn s" onclick="cmd('charge')">Charge</button></div></div><div id="robotBox"></div></div>
<div class="card"><div class="ch">Residents</div><div id="residentBox"></div></div>
<div class="card"><div class="ch">Active Alerts</div><div id="alertBox"></div></div>
</div>
<div class="g3">
<div class="card"><div class="ch">People Detection</div><div id="peopleBox"></div></div>
<div class="card"><div class="ch">Map</div><div class="map" id="mapBox"></div></div>
<div class="card"><div class="ch">Camera<div style="display:flex;gap:5px"><button class="btn s p" onclick="cmd('camera_start')">Open</button><button class="btn s" onclick="cmd('camera_stop')">Close</button></div></div><div class="camera" id="cameraBox"><img id="camera" alt="Nova camera"><p style="font-size:12px;color:#8898b0;margin:6px 0 0" id="cameraNote"></p></div><div id="noCamera" class="esbox" style="min-height:80px">No camera feed from Nova yet.</div></div>
</div>
</section>

<section class="view" id="view-robots">
<div class="g2">
<div class="card">
<div class="ch">Robot Telemetry<div style="display:flex;gap:5px"><button class="btn s d" onclick="cmd('stop')">Emergency Stop</button><button class="btn s" onclick="cmd('charge')">Go Charge</button></div></div>
<div id="robotsFleet"></div>
</div>
<div class="card">
<div class="ch">Detection Feed</div>
<div id="robotDiagnostics"></div>
<div style="margin-top:14px"><div class="ch" style="margin-bottom:10px">People in Range</div><div id="detectionList"></div></div>
</div>
</div>
</section>

<section class="view" id="view-rounds">
<div class="g2">
<div class="card">
<div class="ch">Launch Care Round</div>
<button class="tile" style="width:100%;min-height:80px;margin-bottom:16px;flex-direction:row;gap:14px;justify-content:flex-start;padding:16px 20px" onclick="cmd('start_rounds')">
<div class="ti c-green" style="flex-shrink:0">&#8635;</div><div style="text-align:left"><div>Start Care Round</div><div style="font-size:12px;font-weight:500;color:#5a6a8a;margin-top:3px">Send Nova to check in with all residents</div></div>
</button>
<div class="ch" style="margin-top:4px">Residents</div>
<div id="roundResidents"></div>
</div>
<div class="card"><div class="ch">Scheduled Reminders</div><div id="roundSchedule"></div></div>
</div>
</section>

<section class="view" id="view-residents">
<div class="g2">
<div class="card" id="resFormCard">
<div class="ch"><span id="resFormTitle">Add Resident</span></div>
<div class="fbox">
<label class="fl">Full Name *</label><input class="field" id="manualName" placeholder="e.g. Mary Collins">
<div class="ir"><div class="iw"><label class="fl">Room *</label><input class="field" id="manualRoom" placeholder="204"></div><div class="iw"><label class="fl">Map Point</label><input class="field" id="manualMapPoint" placeholder="Room 204"></div></div>
<div class="ir"><div class="iw"><label class="fl">Wing</label><input class="field" id="manualWing" placeholder="A"></div><div class="iw"><label class="fl">Care Level</label><input class="field" id="manualCare" placeholder="Assisted"></div></div>
<label class="fl">Contact Phone</label><input class="field" id="manualPhone" placeholder="+1 555 0100">
<label class="fl">Care Notes (medication, mobility, emergency)</label><textarea class="field" id="manualNotes" rows="3" placeholder="Morning medication at 09:00. Allergic to penicillin."></textarea>
<label class="fl">Check-in Prompt (what Nova says on arrival)</label><input class="field" id="manualPrompt" placeholder="Hello, I am checking in. Do you need anything?">
</div>
<div style="display:flex;gap:8px;align-items:center">
<button class="btn p" id="saveResBtn" onclick="saveResident()">Add Resident</button>
<button class="btn" id="cancelEditBtn" style="display:none" onclick="cancelEdit()">Cancel</button>
</div>
<div style="margin-top:18px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Import via CSV</div>
<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px"><a class="btn s" href="/templates/residents.csv">Download Template</a></div>
<input class="field" type="file" id="residentFile" accept=".csv,text/csv">
<button class="btn s" onclick="uploadResidents()" style="margin-top:6px">Upload CSV</button>
<p style="font-size:12px;color:#8898b0;margin:6px 0 0">Fill the template in Excel, save as CSV, then upload.</p>
</div>
</div>
<div class="card">
<div class="ch">Resident Directory</div>
<div id="residentDirectory"></div>
<div style="margin-top:18px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Quick Command</div>
<label class="fl">Select Resident</label><select class="field" id="residentSelect" style="margin-bottom:10px"></select>
<div style="display:flex;flex-wrap:wrap;gap:6px">
<button class="btn p s" onclick="checkInSelected()">Check In</button>
<button class="btn s" onclick="medSelected()">Medication</button>
<button class="btn d s" onclick="cmd('staff_alert',{priority:'urgent',residentId:residentSelect.value,message:'Assistance requested for resident.'})">Alert Staff</button>
</div>
</div>
</div>
</div>
</section>

<section class="view" id="view-alerts">
<div class="g2">
<div class="card"><div class="ch">Active Alerts</div><div id="alertCenter"></div></div>
<div class="card">
<div class="ch">Create Alert</div>
<label class="fl">Room or Location</label><input class="field" id="alertRoom" placeholder="e.g. Room 204, Lobby, Corridor B">
<label class="fl">Description</label><textarea class="field" id="alertMessage" rows="4" placeholder="Describe the situation clearly..."></textarea>
<div style="display:flex;gap:8px;margin-top:10px">
<button class="btn d" onclick="createAlert('urgent')">Send Urgent Alert</button>
<button class="btn" onclick="createAlert('standard')">Standard Alert</button>
</div>
</div>
</div>
</section>

<section class="view" id="view-map">
<div class="g2">
<div class="card"><div class="ch">Live Map</div><div class="map" id="fullMapBox"></div><p style="font-size:12px;color:#8898b0;margin:8px 0 0">Click any pin to send Nova to that location.</p></div>
<div class="card">
<div class="ch">Destination Control</div>
<label class="fl">Map Point</label><select class="field" id="pointSelect"></select>
<button class="btn p" style="margin-top:10px" onclick="guideSelected()">Guide Visitor Here</button>
<div style="margin-top:18px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Send a Message</div>
<label class="fl">Message (Nova will speak this)</label><textarea class="field" id="messageText" rows="3" placeholder="Please proceed to the waiting area. Your appointment is ready."></textarea>
<button class="btn" style="margin-top:8px" onclick="sendMessage()">Send Message to Point</button>
</div>
</div>
</div>
</section>

<section class="view" id="view-logs">
<div class="card">
<div class="ch">Operations Log</div>
<table class="tbl"><thead><tr><th>Time</th><th>Event</th><th>Detail</th></tr></thead><tbody id="opsLog"></tbody></table>
</div>
</section>

<section class="view" id="view-settings">
<div class="g2">
<div class="card">
<div class="ch">Cloud Relay Status</div>
<div id="settingsRelay"></div>
<div style="margin-top:14px;display:flex;flex-wrap:wrap;gap:6px"><button class="btn p s" onclick="cmd('camera_start')">Test Camera</button><button class="btn s" onclick="cmd('security_start')">Test Detection</button></div>
<div style="margin-top:20px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Brand Logo</div>
<img class="lp" id="logoPreview" src="${brandLogoDataUrl || ""}" alt="ZOX logo">
<input class="field" id="logoFile" type="file" accept="image/png,image/jpeg,image/webp" style="margin-top:8px">
<button class="btn p s" onclick="uploadLogo()" style="margin-top:6px">Upload Logo</button>
<p style="font-size:12px;color:#8898b0;margin:6px 0 0">PNG or JPG, max 5 MB.</p>
</div>
</div>
<div class="card">
<div class="ch">User Access</div>
<div class="fbox">
<label class="fl">Username</label><input class="field" id="newUser" placeholder="Username (3–40 characters)">
<label class="fl">Password</label><input class="field" id="newPass" type="password" placeholder="Minimum 8 characters">
<label class="fl">Role</label><select class="field" id="newRole"><option value="operator">Operator</option><option value="viewer">Viewer</option><option value="admin">Admin</option></select>
<button class="btn p s" style="margin-top:8px" onclick="addUser()">Add User</button>
</div>
<div id="userList"></div>
<div style="margin-top:18px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">CSV Import Format</div>
<table class="tbl" style="font-size:12px"><tbody id="formatRows"></tbody></table>
<a class="btn s" href="/templates/residents.csv" style="margin-top:10px;display:inline-flex">Download Template</a>
</div>
</div>
</div>
</section>

</div></main></div>
<div class="toast" id="toast"></div>
<script>
var columns=${JSON.stringify(residentColumns)};
var T={command:["Command Center","Live data from Nova and your facility registry."],robots:["Robot Feeds","Telemetry and people detection feed from Nova."],rounds:["Care Rounds","Launch rounds and check in with registered residents."],residents:["Residents","Manage the resident registry and send Nova on check-ins."],alerts:["Alerts","Create urgent staff alerts and monitor facility events."],map:["Map &amp; Messaging","Live map points from Nova."],logs:["Operations Log","Commands, state updates and facility actions."],settings:["Settings","Relay status, users, logo and CSV format."]};
function get(p){return fetch(p,{cache:"no-store"}).then(function(r){return r.ok?r.json():{};}).catch(function(){return{};})}
function post(p,b){return fetch(p,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(b)}).then(function(r){return r.json();}).catch(function(){return{ok:false,error:"Network error"};})}
function cmd(action,params){params=params||{};post("/api/command",{action:action,params:params}).then(function(out){notice(out.ok?"Command sent to Nova":"Error: "+(out.error||"failed"),out.ok);refresh();});}
function esc(v){var map={"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"};return String(v||"").replace(/[&<>"']/g,function(c){return map[c];});}
function notice(t,ok){if(ok===undefined)ok=true;var el=document.getElementById("toast");if(!el)return;el.textContent=t;el.style.background=ok?"#0d1f3c":"#7a1c1c";el.style.display="block";clearTimeout(notice._t);notice._t=setTimeout(function(){el.style.display="none";},3000);}
function sv(name,el){
  var views=document.querySelectorAll(".view");
  for(var i=0;i<views.length;i++){views[i].classList.remove("active");views[i].classList.add("hidden");}
  var view=document.getElementById("view-"+name);if(view){view.classList.add("active");view.classList.remove("hidden");}
  var navLinks=document.querySelectorAll(".nav a");
  for(var j=0;j<navLinks.length;j++)navLinks[j].classList.remove("active");
  var target=el||document.querySelector('.nav a[data-view="'+name+'"]');
  if(target)target.classList.add("active");
  var pt=document.getElementById("pageTitle");var ps=document.getElementById("pageSubtitle");
  if(pt&&T[name])pt.innerHTML=T[name][0]||name;
  if(ps&&T[name])ps.innerHTML=T[name][1]||"";
}
function rRow(col,title,sub,right){if(right===undefined)right="";return '<div class="row"><div class="dot c-'+col+'">'+esc(String(title||"?")[0])+'</div><div class="rb"><b>'+esc(title)+'</b><span>'+esc(sub)+'</span></div><div class="ra">'+right+'</div></div>';}
function esb(t){return '<div class="esbox" style="min-height:70px">'+esc(t)+'</div>'}
function byId(id){var s=window._s;var res=(s&&s.care&&s.care.residents)||[];for(var i=0;i<res.length;i++){if(res[i].id===id)return res[i];}return null;}
function rParams(id){var el=document.getElementById("residentSelect");var rid=id||(el&&el.value)||"";var r=byId(rid);if(r){return{residentId:r.id,residentName:r.name,room:r.room,mapPoint:r.mapPoint||r.room,notes:[r.medicationNotes,r.mobilityNotes,r.emergencyNotes].filter(Boolean).join("; "),checkInPrompt:r.checkInSchedule||""};}return{residentId:rid};}
function checkInResident(id){if(!id)return notice("Select a resident first.",false);cmd("resident_checkin",rParams(id));}
function medResident(id){if(!id)return notice("Select a resident first.",false);cmd("med_reminder",rParams(id));}
function checkInSelected(){var el=document.getElementById("residentSelect");checkInResident(el&&el.value);}
function medSelected(){var el=document.getElementById("residentSelect");medResident(el&&el.value);}
function guideSelected(){var el=document.getElementById("pointSelect");var p=el&&el.value;if(!p||p.indexOf("No ")==0)return notice("No map points received from Nova yet.",false);cmd("visitor_guide",{destination:p});}
function staffAlert(){cmd("staff_alert",{priority:"urgent",message:"Staff assistance requested."});}
function sendMessage(){var el=document.getElementById("pointSelect");var p=el&&el.value;if(!p||p.indexOf("No ")==0)return notice("No map points received from Nova yet.",false);var mt=document.getElementById("messageText");cmd("message",{destination:p,message:(mt&&mt.value)||"Please meet Nova at this location."});}
var _eid=null;
function editResident(id){
  var r=byId(id);if(!r)return notice("Resident not loaded yet. Try again in a moment.",false);
  _eid=id;
  var fields={manualName:r.name,manualRoom:r.room,manualMapPoint:r.mapPoint||r.room,manualWing:r.wing||"",manualCare:r.careLevel||"",manualPhone:r.contactPhone||"",manualNotes:[r.medicationNotes,r.mobilityNotes,r.emergencyNotes].filter(Boolean).join("; "),manualPrompt:r.checkInSchedule||""};
  Object.keys(fields).forEach(function(fid){var el=document.getElementById(fid);if(el)el.value=fields[fid];});
  document.getElementById("saveResBtn").textContent="Save Changes";
  document.getElementById("cancelEditBtn").style.display="inline-flex";
  document.getElementById("resFormTitle").textContent="Edit Resident";
  sv("residents",document.querySelector('[data-view="residents"]'));
  setTimeout(function(){var fc=document.getElementById("resFormCard");if(fc)fc.scrollIntoView({behavior:"smooth",block:"start"});},100);
  var mn=document.getElementById("manualName");if(mn)mn.focus();
}
function cancelEdit(){
  _eid=null;
  ["manualName","manualRoom","manualMapPoint","manualWing","manualCare","manualPhone","manualNotes","manualPrompt"].forEach(function(fid){var el=document.getElementById(fid);if(el)el.value="";});
  document.getElementById("saveResBtn").textContent="Add Resident";
  document.getElementById("cancelEditBtn").style.display="none";
  document.getElementById("resFormTitle").textContent="Add Resident";
}
function saveResident(){
  var nameEl=document.getElementById("manualName");var roomEl=document.getElementById("manualRoom");
  var name=nameEl&&nameEl.value.trim();var room=roomEl&&roomEl.value.trim();
  if(!name)return notice("Full name is required.",false);
  if(!room)return notice("Room is required.",false);
  var mpEl=document.getElementById("manualMapPoint");var mapPoint=(mpEl&&mpEl.value.trim())||room;
  function gv(id){var e=document.getElementById(id);return(e&&e.value.trim())||"";}
  var b={full_name:name,room:room,map_point:mapPoint,wing:gv("manualWing"),care_level:gv("manualCare"),primary_contact_phone:gv("manualPhone"),medication_notes:gv("manualNotes"),check_in_schedule:gv("manualPrompt")};
  if(_eid)b.resident_id=_eid;
  post("/api/residents",b).then(function(out){
    notice(out.ok?(_eid?"Resident updated successfully":"Resident added"):(out.error||"Could not save"),out.ok);
    if(out.ok)cancelEdit();
    refresh();
  });
}
function deleteResident(id){
  if(!confirm("Remove this resident? This cannot be undone."))return;
  fetch("/api/residents/"+encodeURIComponent(id),{method:"DELETE"}).then(function(r){return r.json();}).then(function(out){
    notice(out.ok?"Resident removed":(out.error||"Could not remove"),out.ok);refresh();
  });
}
function createAlert(priority){
  var msgEl=document.getElementById("alertMessage");var msg=msgEl&&msgEl.value.trim();
  if(!msg)return notice("Please describe the situation.",false);
  var roomEl=document.getElementById("alertRoom");var room=(roomEl&&roomEl.value.trim())||"";
  post("/api/alerts",{priority:priority||"urgent",room:room,message:msg}).then(function(out){
    notice(out.ok?"Alert created and sent to Nova":(out.error||"Could not create alert"),out.ok);
    if(out.ok){var ar=document.getElementById("alertRoom");var am=document.getElementById("alertMessage");if(ar)ar.value="";if(am)am.value="";}
    refresh();
  });
}
function dismissAlert(id){
  post("/api/alerts/"+encodeURIComponent(id)+"/dismiss",{}).then(function(out){
    notice(out.ok?"Alert dismissed":(out.error||"Could not dismiss"),out.ok);refresh();
  });
}
function uploadResidents(){
  var fi=document.getElementById("residentFile");var file=fi&&fi.files&&fi.files[0];
  if(!file)return notice("Choose the completed CSV first.",false);
  var reader=new FileReader();
  reader.onload=function(){
    fetch("/api/residents/import",{method:"POST",headers:{"content-type":"text/csv"},body:reader.result}).then(function(r){return r.json();}).then(function(out){
      notice(out.ok?"Imported "+out.count+" residents":(out.error||"Import failed"),out.ok);
      var rf=document.getElementById("residentFile");if(rf)rf.value="";refresh();
    });
  };
  reader.readAsText(file);
}
function uploadLogo(){
  var fi=document.getElementById("logoFile");var file=fi&&fi.files&&fi.files[0];
  if(!file)return notice("Choose the logo image first.",false);
  if(file.size>5*1024*1024)return notice("Logo too large. Use PNG/JPEG under 5 MB.",false);
  var reader=new FileReader();
  reader.onload=function(){
    post("/api/logo",{dataUrl:reader.result}).then(function(out){
      notice(out.ok?"Logo updated":(out.error||"Failed"),out.ok);
      if(out.logo){var lp=document.getElementById("logoPreview");var sl=document.getElementById("sideLogo");if(lp)lp.src=out.logo;if(sl)sl.innerHTML='<img src="'+out.logo+'" alt="ZOX">';}
      var lf=document.getElementById("logoFile");if(lf)lf.value="";
    });
  };
  reader.readAsDataURL(file);
}
function addUser(){
  var u=document.getElementById("newUser");var p=document.getElementById("newPass");var r=document.getElementById("newRole");
  post("/api/users",{username:u&&u.value,password:p&&p.value,role:r&&r.value}).then(function(out){
    notice(out.ok?"User added":(out.error||"Could not add user"),out.ok);
    if(u)u.value="";if(p)p.value="";loadUsers();
  });
}
function loadUsers(){
  get("/api/users").then(function(out){
    var el=document.getElementById("userList");if(!el)return;
    el.innerHTML=out.users?out.users.map(function(u){return rRow("blue",u.username,u.role+" &middot; Added "+new Date(u.createdAt).toLocaleDateString());}).join(""):esb(out.error||"Could not load users");
  });
}
function renderMap(target,points){
  if(!target)return;
  if(!points.length){target.className="map empty";target.innerHTML='<div style="font-size:13px;color:#8898b0;text-align:center">No map points from Nova yet.</div>';return}
  target.className="map";
  var cols=["c-blue","c-green","c-purple","c-cyan","c-orange"];
  target.innerHTML=points.slice(0,10).map(function(p,i){
    var l=(8+(i*19)%72)+"%";var t=(12+(i*27)%66)+"%";
    return '<button class="pin '+cols[i%cols.length]+'" title="'+esc(p.name)+'" style="left:'+l+';top:'+t+'" onclick="cmd(\'visitor_guide\',{destination:\''+esc(p.name)+'\'})">'+esc(String(i+1))+'</button>'+
           '<div style="position:absolute;left:calc('+l+' + 32px);top:calc('+t+' + 4px);font-size:10px;font-weight:700;color:#1a3058;background:white;border-radius:4px;padding:2px 5px;pointer-events:none;white-space:nowrap;max-width:72px;overflow:hidden;text-overflow:ellipsis">'+esc(p.name)+'</div>';
  }).join("");
}
function aCard(a){
  var u=a.priority!=="standard";
  return '<div class="ac'+(u?"":" std")+'"><div class="adot'+(u?"":" std")+'">'+(u?"!":"&#9650;")+'</div><div class="ab"><b>'+esc(a.message||"Alert")+'</b><span class="as">'+esc(a.room||"Facility")+" &middot; "+new Date(a.createdAt||Date.now()).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"})+'</span></div><div class="aa"><button class="btn s d" onclick="dismissAlert(\''+esc(a.id)+'\')">Dismiss</button></div></div>';
}
function renderAll(s){
  if(!s||typeof s!=="object")return;
  var c=s.care||{};var res=c.residents||[];var rem=c.reminders||[];var al=c.alerts||[];var pts=s.points||[];var ppl=s.people||[];
  window._s=s;
  function gi(id){return document.getElementById(id);}
  function st(id,v){var e=gi(id);if(e)e.textContent=v;}
  function ht(id,v){var e=gi(id);if(e)e.innerHTML=v;}
  st("statRobot",s.online?"Online":"Offline");st("statResidents",res.length);st("statPoints",pts.length);st("statPeople",ppl.length);st("statCamera",s.camera?"Active":"Off");
  var so=gi("sideOnline");if(so){so.textContent=(s.online?"1":"0")+" / 1";so.className="pill "+(s.online?"ok":"off");}
  st("sideHealth",s.online?"Connected &middot; "+new Date(s.lastSeen).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit",second:"2-digit"}):"Waiting for robot connection");
  var op=gi("onlinePill");if(op){op.textContent=s.online?"Online":"Offline";op.className="pill "+(s.online?"ok":"off");}
  var ac=gi("alertCount");if(ac){if(al.length){ac.style.display="inline-flex";ac.textContent=al.length+" alert"+(al.length===1?"":"s");ac.className="pill bad";}else ac.style.display="none";}
  var status=s.status||{};
  var bat=esc(status.battery||"");var dest=esc(status.destination||"");var stat=esc(status.status||"");
  var robotHtml='<div style="display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #f0f4fa"><div class="dot c-'+(s.online?"blue":"red")+'" style="width:42px;height:42px;font-size:16px">N</div><div style="flex:1"><div style="font-weight:700;font-size:14px">Nova 01</div><div style="font-size:12px;color:#8898b0;margin-top:2px">'+(dest||"No active destination")+"</div></div>"+(s.online?'<span class="pill ok" style="white-space:nowrap">Online</span>':'<span class="pill off">Offline</span>')+"</div>"+(bat?'<div style="margin-top:8px;font-size:12px;color:#5a6a80"><b style="color:#374151">Battery:</b> '+bat+"</div>":"")+(stat?'<div style="margin-top:4px;font-size:12px;color:#5a6a80"><b style="color:#374151">Status:</b> '+stat+"</div>":"");
  ht("robotBox",robotHtml);ht("robotsFleet",robotHtml);
  ht("residentBox",res.length?res.slice(0,4).map(function(r){return'<div class="row"><div class="dot c-purple">'+esc(r.name[0]||"?")+'</div><div class="rb"><b>'+esc(r.name)+'</b><span>'+esc(r.room)+'</span></div><div class="ra"><button class="btn s p" onclick="checkInResident(\''+esc(r.id)+'\')">Go</button></div></div>';}).join("")+(res.length>4?'<p style="font-size:12px;color:#8898b0;margin:8px 0 0">+'+(res.length-4)+" more</p>":""):esb("No residents yet. Add them in the Residents section."));
  ht("alertBox",al.length?al.slice(0,3).map(function(a){return'<div class="row"><div class="dot c-'+(a.priority!=="standard"?"red":"yellow")+'">!</div><div class="rb"><b>'+esc((a.message||"Alert").slice(0,60))+'</b><span>'+esc(a.room||"Facility")+'</span></div><div class="ra"><button class="btn s d" onclick="dismissAlert(\''+esc(a.id)+'\')">&#215;</button></div></div>';}).join("")+(al.length>3?'<p style="font-size:12px;color:#8898b0;margin:8px 0 0">+'+(al.length-3)+" more</p>":""):esb("No active alerts."));
  ht("peopleBox",ppl.length?ppl.map(function(p){return'<div class="dchip"><div class="dc"></div>Person&nbsp;'+(p.id||"?")+" &mdash; "+(p.distance!=null?p.distance+"m away":"detected")+"</div>";}).join(""):esb("No people detected by Nova."));
  renderMap(gi("mapBox"),pts);renderMap(gi("fullMapBox"),pts);
  var sdkOk=!!(status.robotSdk);
  ht("robotDiagnostics",rRow(sdkOk?"green":"yellow","Robot SDK",sdkOk?"AgentOS connected":"No SDK connection detected")+rRow("blue","Map Points",pts.length+" point"+(pts.length!==1?"s":"")+" known")+rRow(s.camera?"green":"red","Camera Feed",s.camera?"Frame available":"No frame received"));
  ht("detectionList",ppl.length?ppl.map(function(p){return'<div class="row"><div class="dot c-cyan" style="font-size:11px">&#9679;</div><div class="rb"><b>Person detected</b><span>'+(p.distance!=null?"Distance: "+p.distance+"m":"Position: x="+(p.x||"-")+", y="+(p.y||"-"))+"</span></div></div>";}).join(""):esb("No people currently in Nova's detection range."));
  ht("roundResidents",res.length?res.map(function(r){return'<div class="row"><div class="dot c-blue">'+esc(r.name[0]||"?")+'</div><div class="rb"><b>'+esc(r.name)+'</b><span>'+esc(r.room)+'</span></div><div class="ra"><button class="btn s p" onclick="checkInResident(\''+esc(r.id)+'\')">Check In</button><button class="btn s" onclick="medResident(\''+esc(r.id)+'\')">Med</button></div></div>';}).join(""):esb("Register residents to use care rounds."));
  ht("roundSchedule",rem.length?rem.map(function(r){return rRow("green",r.timeLabel||"Scheduled",r.title||"Reminder");}).join(""):esb("No reminders from Nova."));
  ht("residentDirectory",res.length?res.map(function(r){return'<div class="row"><div class="dot c-purple">'+esc(r.name[0]||"?")+'</div><div class="rb"><b>'+esc(r.name)+'</b><span>'+esc(r.room+(r.wing?" &middot; "+r.wing:"")+(r.careLevel?" &mdash; "+r.careLevel:""))+'</span></div><div class="ra"><button class="btn s" onclick="editResident(\''+esc(r.id)+'\')">Edit</button><button class="btn s d" onclick="deleteResident(\''+esc(r.id)+'\')">Remove</button></div></div>';}).join(""):esb("No residents. Add one using the form."));
  var rs=gi("residentSelect");if(rs)rs.innerHTML=res.length?res.map(function(r){return'<option value="'+esc(r.id)+'">'+esc(r.name)+" &mdash; Room "+esc(r.room)+"</option>";}).join(""):"<option disabled>No residents registered yet</option>";
  ht("alertCenter",al.length?al.map(aCard).join(""):esb("No active alerts."));
  var ps2=gi("pointSelect");if(ps2)ps2.innerHTML=pts.length?pts.map(function(p){return'<option value="'+esc(p.name)+'">'+esc(p.name)+"</option>";}).join(""):"<option disabled>No map points from Nova yet</option>";
  var cLogs=c.logs||[];var eLogs=(s.events||[]).slice().reverse().map(function(e){return{createdAt:e.at,title:e.type,detail:typeof e.data==="object"?Object.keys(e.data||{}).slice(0,4).map(function(k){return k+": "+String(e.data[k]).slice(0,40);}).join(", "):String(e.data||"")};});
  var logRows=cLogs.concat(eLogs);
  ht("opsLog",(logRows.length?logRows:[{title:"Ready",detail:"Waiting for robot and facility activity."}]).slice(0,80).map(function(l){return'<tr><td style="width:75px;white-space:nowrap;color:#8898b0;font-size:12px">'+new Date(l.createdAt||Date.now()).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"})+"</td><td style=\"font-weight:600;font-size:13px\">"+esc(l.title||"Event")+"</td><td style=\"font-size:12px;color:#5a6a80\">"+esc(String(l.detail||"").slice(0,140))+"</td></tr>";}).join(""));
  var fh={resident_id:"Optional. Auto-generated if blank.",full_name:"Required.",room:"Required.",map_point:"Exact Nova map point name. Falls back to room.",wing:"Optional.",care_level:"Independent / Assisted / High.",primary_contact_name:"Optional.",primary_contact_phone:"Optional.",medication_notes:"Medication schedule.",mobility_notes:"Mobility aids and restrictions.",preferred_language:"Communication preference.",check_in_schedule:"e.g. daily 09:00",emergency_notes:"Critical staff notes."};
  ht("formatRows",columns.map(function(col){return'<tr><td style="font-weight:700;font-size:12px;white-space:nowrap">'+col+'</td><td style="font-size:12px;color:#5a6a80">'+esc(fh[col]||"")+"</td></tr>";}).join(""));
  ht("settingsRelay",rRow(s.online?"green":"red","Robot Connection",s.online?"Connected &middot; "+new Date(s.lastSeen).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}):"Not connected")+rRow(s.camera?"green":"red","Camera Feed",s.camera?"Frame available":"No frame")+rRow("blue","Resident Registry",res.length+" registered"));
  var cb=gi("cameraBox");var nc=gi("noCamera");var cam=gi("camera");var cn=gi("cameraNote");
  if(s.camera){if(cb)cb.style.display="block";if(nc)nc.style.display="none";if(cam)cam.src="/api/camera.jpg?t="+Date.now();if(cn)cn.textContent="Live snapshot — updates every 2s";}
  else{if(cb)cb.style.display="none";if(nc)nc.style.display="grid";}
}
function refresh(){get("/api/state").then(function(s){if(s&&Object.keys(s).length)renderAll(s);});}
setInterval(refresh,2000);refresh();loadUsers();
document.querySelectorAll('.nav a[data-view]').forEach(function(a){
  a.addEventListener('click',function(e){e.preventDefault();sv(this.getAttribute('data-view'),this);});
});
window.onerror=function(msg,src,line){notice('JS Error: '+msg+' (line '+line+')',false);return false;};
</script></body></html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  if (url.pathname === "/health") return sendJson(res, 200, { ok: true, version: "v3" });

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
