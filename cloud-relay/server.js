const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 3000);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "nova2026";
const ROBOT_TOKEN = process.env.ROBOT_TOKEN || "change-me-robot-token";
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");

let robot = {
  online: false, lastSeen: 0, status: {}, detection: {}, people: [], points: [],
  care: { residents: [], reminders: [], alerts: [], logs: [] }, cameraJpegBase64: "",
};
const facility = { residents: [], reminders: [], alerts: [], logs: [], roundOrder: [], checkIns: {} };
const scheduledRounds = [];
const roundHistory = [];
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
  return safeEqual(passwordHash(password, salt).split(":")[1], hash);
}
users.set(ADMIN_USER, { username: ADMIN_USER, role: "admin", passwordHash: passwordHash(ADMIN_PASS), createdAt: Date.now() });

const DATA_FILE = path.join(__dirname, "nova_data.json");
(function loadPersistedData() {
  try {
    const raw = fs.readFileSync(DATA_FILE, "utf8");
    const saved = JSON.parse(raw);
    if (Array.isArray(saved.residents)) facility.residents = saved.residents;
    if (Array.isArray(saved.reminders)) facility.reminders = saved.reminders;
    if (Array.isArray(saved.alerts)) facility.alerts = saved.alerts;
    if (Array.isArray(saved.scheduledRounds)) saved.scheduledRounds.forEach(function(s) { scheduledRounds.push(s); });
    if (Array.isArray(saved.roundHistory)) saved.roundHistory.forEach(function(h) { roundHistory.push(h); });
    if (Array.isArray(saved.roundOrder)) facility.roundOrder = saved.roundOrder;
    if (saved.checkIns && typeof saved.checkIns === "object") facility.checkIns = saved.checkIns;
    if (typeof saved.brandLogoDataUrl === "string" && saved.brandLogoDataUrl) brandLogoDataUrl = saved.brandLogoDataUrl;
    console.log("[persist] loaded:", facility.residents.length, "residents,", scheduledRounds.length, "schedules");
  } catch(e) { if (e.code !== "ENOENT") console.error("[persist] load error:", e.message); }
})();
let _saveTimer = null;
let lastSaved = 0;
function persistData() {
  if (_saveTimer) clearTimeout(_saveTimer);
  _saveTimer = setTimeout(function() {
    const snap = JSON.stringify({ residents: facility.residents, reminders: facility.reminders, alerts: facility.alerts, scheduledRounds: scheduledRounds, roundHistory: roundHistory, roundOrder: facility.roundOrder, checkIns: facility.checkIns, brandLogoDataUrl: brandLogoDataUrl });
    fs.writeFile(DATA_FILE, snap, function(err) { if (err) console.error("[persist] save error:", err.message); else lastSaved = Date.now(); });
  }, 500);
}

const residentColumns = ["resident_id","full_name","room","map_point","wing","care_level","primary_contact_name","primary_contact_phone","medication_notes","mobility_notes","preferred_language","check_in_schedule","emergency_notes"];

function sendJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "content-length": data.length });
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
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}
function safeEqual(a, b) {
  const l = Buffer.from(a || ""), r = Buffer.from(b || "");
  return l.length === r.length && crypto.timingSafeEqual(l, r);
}
function isAdmin(req) {
  const h = req.headers.authorization || "";
  if (!h.startsWith("Basic ")) return false;
  return safeEqual(Buffer.from(h.slice(6), "base64").toString("utf8"), `${ADMIN_USER}:${ADMIN_PASS}`);
}
function parseCookies(req) {
  return Object.fromEntries(String(req.headers.cookie || "").split(";").map(p => p.trim()).filter(Boolean).map(p => {
    const i = p.indexOf("="); return i < 0 ? [p, ""] : [p.slice(0, i), decodeURIComponent(p.slice(i + 1))];
  }));
}
function currentUser(req) {
  const sid = parseCookies(req).nova_session;
  const session = sid ? sessions.get(sid) : null;
  if (!session || session.expiresAt < Date.now()) { if (sid) sessions.delete(sid); return null; }
  return users.get(session.username) || null;
}
function createSession(res, username) {
  const sid = crypto.createHmac("sha256", SESSION_SECRET).update(`${username}:${crypto.randomUUID()}`).digest("hex");
  sessions.set(sid, { username, createdAt: Date.now(), expiresAt: Date.now() + 43200000 });
  res.setHeader("set-cookie", `nova_session=${encodeURIComponent(sid)}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=43200`);
}
function isRobot(req) { return safeEqual(req.headers["x-robot-token"], ROBOT_TOKEN); }
function requireAdmin(req, res) {
  if (currentUser(req) || isAdmin(req)) return true;
  if (String(req.url || "").startsWith("/api/")) return sendJson(res, 401, { ok: false, error: "login required" });
  res.writeHead(302, { location: "/login", "cache-control": "no-store" }); res.end(); return false;
}
function requireRole(req, res, role = "admin") {
  const user = currentUser(req);
  if (user?.role === role || user?.role === "admin" || isAdmin(req)) return user || users.get(ADMIN_USER);
  sendJson(res, 403, { ok: false, error: "admin role required" }); return null;
}
function log(type, detail = {}) { events.push({ at: Date.now(), type, data: detail }); while (events.length > 200) events.shift(); }
function cleanText(v) { return String(v || "").trim(); }
function stableId(prefix, value) {
  const base = cleanText(value) || crypto.randomUUID();
  return `${prefix}-${crypto.createHash("sha1").update(base).digest("hex").slice(0, 10)}`;
}
function parseCsv(text) {
  const rows = []; let row = [], cell = "", quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i], next = text[i + 1];
    if (quoted && ch === '"' && next === '"') { cell += '"'; i++; }
    else if (ch === '"') { quoted = !quoted; }
    else if (!quoted && ch === ",") { row.push(cell); cell = ""; }
    else if (!quoted && (ch === "\n" || ch === "\r")) {
      if (ch === "\r" && next === "\n") i++;
      row.push(cell); if (row.some(v => cleanText(v))) rows.push(row); row = []; cell = "";
    } else { cell += ch; }
  }
  row.push(cell); if (row.some(v => cleanText(v))) rows.push(row); return rows;
}
function toResident(input) {
  const name = cleanText(input.full_name || input.name), room = cleanText(input.room);
  if (!name || !room) return null;
  return {
    id: cleanText(input.resident_id || input.id) || stableId("resident", `${name}:${room}`),
    name, room,
    mapPoint: cleanText(input.map_point || input.mapPoint || input.room),
    wing: cleanText(input.wing), careLevel: cleanText(input.care_level || input.careLevel),
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
  const rc = robot.care || {};
  return {
    residents: facility.residents,
    reminders: [...facility.reminders, ...(Array.isArray(rc.reminders) ? rc.reminders : [])],
    alerts: [...facility.alerts, ...(Array.isArray(rc.alerts) ? rc.alerts : [])],
    logs: [...facility.logs, ...(Array.isArray(rc.logs) ? rc.logs : [])],
  };
}

// Server-side schedule executor — fires every 60 seconds
setInterval(function () {
  const now = new Date();
  const dayNames = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
  const today = dayNames[now.getDay()];
  const hhmm = String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
  scheduledRounds.forEach(function (s) {
    if (!s.enabled) return;
    const days = s.days;
    const dayMatch = days === "daily"
      || (Array.isArray(days) && days.includes(today))
      || (days === "weekdays" && ["mon","tue","wed","thu","fri"].includes(today))
      || (days === "weekends" && ["sat","sun"].includes(today));
    if (!dayMatch || s.time !== hhmm) return;
    const runKey = now.toDateString() + hhmm;
    if (s.lastRun === runKey) return;
    s.lastRun = runKey;
    const base = facility.residents.slice().sort(function (a, b) {
      const ia = facility.roundOrder.indexOf(a.id), ib = facility.roundOrder.indexOf(b.id);
      if (ia >= 0 && ib >= 0) return ia - ib; if (ia >= 0) return -1; if (ib >= 0) return 1; return 0;
    });
    const residents = (Array.isArray(s.residentIds) && s.residentIds.length)
      ? base.filter(r => s.residentIds.includes(r.id)) : base;
    if (!residents.length) return;
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "start_rounds", params: {
      type: s.type || "checkin", scheduleName: s.name,
      residents: residents.map(r => ({ id: r.id, name: r.name, room: r.room, mapPoint: r.mapPoint || r.room, checkInPrompt: r.checkInSchedule || "" }))
    }});
    roundHistory.unshift({ id: crypto.randomUUID(), name: s.name, type: s.type || "checkin", trigger: "schedule", count: residents.length, at: Date.now() });
    if (roundHistory.length > 30) roundHistory.pop();
    persistData();
    log("scheduled_round", { schedule: s.name, residents: residents.length });
  });
}, 60000);

function loginPage(error = "") {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>ZOX Robotics — Sign In</title><style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0,#0d5790,#05152b 55%,#020814);font-family:Inter,system-ui,sans-serif;color:white}@keyframes si{from{opacity:0;transform:translateY(28px) scale(.97)}to{opacity:1;transform:none}}@keyframes logoSpin{0%{transform:scale(.6) rotate(-8deg);opacity:0}100%{transform:none;opacity:1}}.card{width:min(430px,92vw);background:#ffffff10;border:1px solid #ffffff26;border-radius:24px;padding:28px;box-shadow:0 24px 70px #0008;animation:si .45s cubic-bezier(.22,.68,0,1.2) both}.logo{width:96px;height:96px;border-radius:26px;margin:0 auto 16px;background:#06172e;border:2px solid #10c6e7;display:grid;place-items:center;overflow:hidden;animation:logoSpin .5s cubic-bezier(.22,.68,0,1.2) .08s both}.logo img{width:100%;height:100%;object-fit:cover}.logo span{color:#1bd6ee;font-size:30px;font-weight:950}.tag{text-align:center;color:#31d7ef;font-size:11px;letter-spacing:2.4px;font-weight:900}.field{width:100%;border:1px solid #ffffff2e;background:#ffffff14;color:white;border-radius:14px;padding:14px;margin:8px 0;font:inherit;transition:border-color .15s}.field:focus{outline:none;border-color:#18d0f0;box-shadow:0 0 0 3px rgba(24,208,240,.18)}.btn{width:100%;border:0;border-radius:14px;background:#12bee5;color:#03162d;padding:14px;font-weight:950;margin-top:12px;cursor:pointer;transition:transform .12s,box-shadow .12s}.btn:hover{box-shadow:0 4px 18px rgba(18,190,229,.45)}.btn:active{transform:scale(.96)}.err{background:#ff4d4d26;color:#ffd6d6;border:1px solid #ff8a8a55;padding:10px;border-radius:12px;margin:12px 0}.muted{color:#bed0e3;font-size:13px;text-align:center}</style></head><body><form class="card" method="post" action="/login">
<div class="logo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX">` : "<span>ZOX</span>"}</div><div class="tag">SMART ROBOTS. BETTER CARE.</div><h1 style="text-align:center;margin:14px 0 6px">Care Cloud Sign In</h1><p class="muted">Authorized clinic staff only.</p>${error ? `<div class="err">${cleanText(error)}</div>` : ""}
<input class="field" name="username" placeholder="Username" autocomplete="username" autofocus><input class="field" name="password" placeholder="Password" type="password" autocomplete="current-password"><button class="btn">Sign In</button></form></body></html>`;
}
function logoutPage() {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Signed Out</title><style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0,#0d5790,#05152b 55%,#020814);font-family:Inter,system-ui,sans-serif;color:white}.card{width:min(430px,92vw);background:#ffffff10;border:1px solid #ffffff26;border-radius:24px;padding:28px;text-align:center;box-shadow:0 24px 70px #0008}.logo{width:96px;height:96px;border-radius:26px;margin:0 auto 16px;background:#06172e;border:2px solid #10c6e7;display:grid;place-items:center;overflow:hidden}.logo img{width:100%;height:100%;object-fit:cover}.logo span{color:#1bd6ee;font-size:30px;font-weight:950}.btn{display:inline-block;border:0;border-radius:14px;background:#12bee5;color:#03162d;padding:14px 22px;font-weight:950;margin-top:12px;text-decoration:none}.muted{color:#bed0e3;font-size:13px}</style></head><body><section class="card">
<div class="logo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX">` : "<span>ZOX</span>"}</div><h1>Signed Out</h1><p class="muted">Your session has ended.</p><a class="btn" href="/login">Sign In Again</a></section></body></html>`;
}
function parseForm(body) {
  return Object.fromEntries(String(body || "").split("&").filter(Boolean).map(p => {
    const [k, v = ""] = p.split("=");
    return [decodeURIComponent(k.replace(/\+/g, " ")), decodeURIComponent(v.replace(/\+/g, " "))];
  }));
}

function page(user = users.get(ADMIN_USER)) {
  const u = user || users.get(ADMIN_USER) || { username: ADMIN_USER, role: "admin" };
  return `<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZOX Robotics — Care Cloud</title>
<style>
*{box-sizing:border-box;-webkit-font-smoothing:antialiased}
body{margin:0;background:#eef2f7;color:#111827;font-family:Inter,system-ui,-apple-system,sans-serif;font-size:14px}
.app{display:grid;grid-template-columns:252px 1fr;min-height:100vh}
.side{background:linear-gradient(175deg,#03132b 0%,#061f42 60%,#082a56 100%);color:white;padding:20px 14px;display:flex;flex-direction:column;position:sticky;top:0;height:100vh;overflow-y:auto}
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
.top{height:64px;background:white;border-bottom:1px solid #e5eaf3;display:flex;align-items:center;justify-content:space-between;padding:0 22px;position:sticky;top:0;z-index:10}
.top h1{margin:0;font-size:17px;font-weight:800;letter-spacing:-.3px}
.top .sub{font-size:12px;color:#8898b0;margin-top:2px}
.topRight{display:flex;gap:8px;align-items:center}
.content{padding:20px;flex:1}
.view{display:none}.view.active{display:block}
#view-command{display:block}
#view-command.hidden{display:none}
.g5{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}
.g3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:14px}
.g3t{display:grid;grid-template-columns:1.05fr 1fr 0.95fr;gap:14px}
.g2{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.card{background:white;border:1px solid #e5eaf3;border-radius:16px;padding:20px;box-shadow:0 1px 8px #1a2d4a08}
.ch{font-size:11px;font-weight:700;color:#95a8be;text-transform:uppercase;letter-spacing:.9px;margin:0 0 14px;display:flex;align-items:center;justify-content:space-between}
.tile{border:1px solid #e8edf5;border-radius:14px;min-height:114px;background:white;color:#111827;box-shadow:0 2px 14px #1e3a6808;font-weight:800;font-size:13px;cursor:pointer;padding:16px 10px;transition:transform .12s,box-shadow .12s,border-color .12s;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;text-align:center;line-height:1.2}
.tile:hover{transform:translateY(-3px);box-shadow:0 10px 28px #1e3a6820;border-color:#b8cef0}
.tile small{font-size:10px;font-weight:500;color:#8898b0;display:block;margin-top:1px;line-height:1.3}
.tile:hover small{color:#a0b4cc}
.tile .ti{width:42px;height:42px;border-radius:11px;display:grid;place-items:center;color:white;font-size:18px;flex-shrink:0}
.c-green{background:#1f9950}.c-blue{background:#1a68e0}.c-yellow{background:#d49600}.c-red{background:#d63b3b}.c-purple{background:#7848cc}.c-cyan{background:#0ab5cc}.c-orange{background:#d06a20}
.stats{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-top:14px}
.stat{border:1px solid #e8edf5;border-radius:12px;padding:13px 12px;background:white}
.stat .sl{font-size:10px;color:#95a8be;font-weight:700;text-transform:uppercase;letter-spacing:.6px;display:block}
.stat b{font-size:21px;display:block;margin-top:4px;font-weight:800;color:#111827}
.pill{border-radius:999px;padding:3px 9px;font-size:11px;font-weight:700;display:inline-flex;align-items:center;gap:4px;white-space:nowrap}
.ok{background:#dff5e9;color:#15773a}.bad{background:#fce8e8;color:#b82020}.warn{background:#fef3d0;color:#8f5c00}.low{background:#e4edff;color:#1448b8}.off{background:#eaeef5;color:#5a6a80}
.row{display:flex;gap:10px;align-items:center;border-bottom:1px solid #f0f4fa;padding:10px 0}
.row:last-child{border-bottom:0}
.dot{width:36px;height:36px;border-radius:9px;display:grid;place-items:center;color:white;font-weight:800;flex-shrink:0;font-size:14px}
.rb{flex:1;min-width:0}.rb b{display:block;font-size:13.5px;font-weight:700}.rb span{display:block;font-size:12px;color:#8898b0;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ra{display:flex;gap:4px;flex-shrink:0;align-items:center}
.btn{border:1px solid #d5dde8;background:white;color:#1a2840;border-radius:999px;padding:8px 16px;font-weight:700;font-size:13px;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:5px;transition:all .13s;white-space:nowrap;position:relative;overflow:hidden}
.btn:hover{background:#f0f5fc;border-color:#b8c8de}
.btn:active{transform:scale(.94)}
.btn.p{background:#1a68e0;color:white;border-color:#1a68e0}.btn.p:hover{background:#155ac4}
.btn.d{background:#d63b3b;color:white;border-color:#d63b3b}.btn.d:hover{background:#be3232}
.btn.s{padding:5px 12px;font-size:12px}
.btn:disabled{opacity:.4;cursor:not-allowed;pointer-events:none}
.field{width:100%;border:1px solid #d5dde8;border-radius:10px;padding:10px 13px;margin:4px 0;font:inherit;font-size:14px;color:#111827;outline:none;transition:border-color .15s,box-shadow .15s;background:white}
.field:focus{border-color:#1a68e0;box-shadow:0 0 0 3px #1a68e010}
select.field{cursor:pointer}
.map{border-radius:13px;min-height:60px}
.esbox{display:grid;place-items:center;color:#8898b0;text-align:center;min-height:80px;border:1.5px dashed #c0d0e0;border-radius:12px;background:#f5f8fd;padding:18px;font-size:13px}
.loc-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(110px,1fr));gap:8px;padding:2px 0}
.loc-btn{background:#f0f5ff;border:1.5px solid #d0ddf5;border-radius:10px;padding:12px 8px;cursor:pointer;text-align:center;transition:all .13s;font:inherit;color:#1a2840;display:flex;flex-direction:column;align-items:center;gap:5px;position:relative;overflow:hidden;word-break:break-word}
.loc-btn:hover{background:#1a68e0;color:white;border-color:#1a68e0;transform:translateY(-2px);box-shadow:0 6px 18px #1a68e030}
.loc-btn.curr{background:#1f9950;color:white;border-color:#1f9950}
.loc-btn .li{font-size:20px;line-height:1}
.loc-btn .ln{font-size:11px;font-weight:700;line-height:1.3}
.camera{display:none;margin-top:10px}.camera img{width:100%;max-height:300px;object-fit:contain;background:#060e1f;border-radius:10px}
.tbl{width:100%;border-collapse:collapse}
.tbl td,.tbl th{text-align:left;padding:9px 13px;border-bottom:1px solid #f0f4fa;font-size:13px;vertical-align:top}
.tbl th{font-size:11px;font-weight:700;color:#8898b0;text-transform:uppercase;letter-spacing:.5px;background:#fafbfd}
.tbl tr:last-child td{border-bottom:0}
.toast{position:fixed;right:20px;bottom:24px;background:#0d1f3c;color:white;padding:11px 16px;border-radius:12px;box-shadow:0 8px 30px #00000030;font-weight:600;font-size:13.5px;z-index:200;max-width:340px;opacity:0;transform:translateY(16px);transition:opacity .22s,transform .22s;pointer-events:none;border-left:3px solid #1a68e0;display:flex;align-items:center;gap:8px}
.toast.show{opacity:1;transform:none;pointer-events:auto}
.toast.t-ok{border-left-color:#1f9950}
.toast.t-err{border-left-color:#d63b3b;background:#1a0808}
.lp{width:90px;height:90px;border-radius:14px;border:2px solid #18d0f0;background:#06172e;object-fit:cover;display:block;margin:8px 0}
.fbox{background:#f5f8fd;border:1px solid #e5eaf3;border-radius:12px;padding:14px;margin-bottom:12px}
.fl{font-size:11px;font-weight:700;color:#6878a0;text-transform:uppercase;letter-spacing:.5px;display:block;margin:8px 0 3px}
.fl:first-child{margin-top:0}
.ir{display:flex;gap:8px}.ir>.iw{flex:1}
.ac{border:1px solid #f8c8c8;border-radius:12px;padding:13px;background:#fff5f5;margin-bottom:8px;display:flex;align-items:flex-start;gap:12px}
.ac.std{border-color:#fde5b0;background:#fffbf0}
.adot{width:36px;height:36px;border-radius:10px;background:#d63b3b;color:white;display:grid;place-items:center;font-weight:900;flex-shrink:0;font-size:17px}
.adot.std{background:#d49600}
.ab{flex:1;min-width:0}.ab b{font-weight:700;font-size:14px;display:block;color:#1a1a2e}.ab .as{font-size:12px;color:#8898b0;margin-top:3px;display:block}
.aa{flex-shrink:0;margin-top:2px}
.dchip{display:inline-flex;align-items:center;gap:5px;background:#f0f5ff;border:1px solid #d0ddf5;border-radius:8px;padding:6px 10px;font-size:12px;font-weight:600;color:#1448b8;margin:3px}
.dchip .dc{width:6px;height:6px;border-radius:50%;background:#1a68e0;flex-shrink:0}
.sch-item{display:flex;align-items:flex-start;gap:10px;padding:11px 0;border-bottom:1px solid #f0f4fa}
.sch-item:last-child{border-bottom:0}
.ord-btn{border:1px solid #d5dde8;background:white;color:#5a6a80;border-radius:5px;width:22px;height:20px;display:grid;place-items:center;cursor:pointer;font-size:9px;padding:0;line-height:1}
.ord-btn:hover:not(:disabled){background:#e8f0fc;color:#1a68e0;border-color:#b0c8ee}
.ord-btn:disabled{opacity:.28;cursor:not-allowed}
.res-row{display:flex;align-items:center;gap:8px;padding:9px 12px;border-bottom:1px solid #f0f4fa}
.res-row:last-child{border-bottom:0}
.round-num{width:20px;height:20px;border-radius:50%;background:#e8f0fc;color:#1a68e0;font-size:11px;font-weight:800;display:grid;place-items:center;flex-shrink:0}
@media(max-width:960px){.app{display:block}.side{display:none}.top{position:static;height:auto;padding:14px;flex-direction:column;gap:8px;align-items:flex-start}.g5,.g3,.g3t,.g2,.stats{grid-template-columns:1fr}.content{padding:12px}}
@keyframes fadeInUp{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}
@keyframes rippleAnim{to{transform:scale(5);opacity:0}}
@keyframes livePulse{0%,100%{box-shadow:0 0 0 0 rgba(229,62,62,.55)}70%{box-shadow:0 0 0 9px rgba(229,62,62,0)}}
@keyframes slideIn{from{opacity:0;transform:translateY(22px) scale(.97)}to{opacity:1;transform:none}}
.view.active{animation:fadeInUp .26s cubic-bezier(.22,.68,0,1) both}
.tile{position:relative;overflow:hidden}.tile:active{transform:translateY(-2px) scale(.97)}
.ripple-el{position:absolute;border-radius:50%;background:rgba(255,255,255,.28);transform:scale(0);animation:rippleAnim .52s linear;pointer-events:none}
.live-dot{width:8px;height:8px;border-radius:50%;background:#e53e3e;display:inline-block;flex-shrink:0;box-shadow:0 0 0 0 rgba(229,62,62,.55);animation:livePulse 1.4s ease-in-out infinite}
.cam-wrap{position:relative;width:100%;background:#060e1c;border-radius:12px;overflow:hidden;aspect-ratio:16/9}
.cam-wrap img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;transition:opacity .38s}
.cam-overlay{position:absolute;top:10px;left:10px;display:flex;align-items:center;gap:5px;pointer-events:none}
.expand-btn{width:100%;background:none;border:0;border-top:1px solid #eef2f8;color:#1a68e0;font-size:12px;font-weight:700;padding:10px 0;cursor:pointer;text-align:center;letter-spacing:.1px;display:block;margin-top:4px}
.expand-btn:hover{background:#f5f8ff;border-radius:0 0 10px 10px}
.edit-banner{background:#fff8e1;border:1px solid #f0d050;border-radius:10px;padding:10px 14px;margin-bottom:12px;font-size:13px;font-weight:600;color:#7a4500;display:none;align-items:center;gap:8px}
.sch-res-row{display:flex;align-items:center;gap:8px;padding:7px 10px;border-bottom:1px solid #f0f4fa;cursor:pointer;transition:background .1s}
.sch-res-row:last-child{border-bottom:0}
.sch-res-row:hover{background:#f5f8fd}
</style></head><body><div class="app">
<aside class="side">
<div class="brand">
<div class="logo" id="sideLogo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX">` : "ZOX"}</div>
<div class="brand-text"><b>ZOX Robotics</b><span class="tag">SMART ROBOTS. BETTER CARE.</span></div>
</div>
<nav class="nav">
<a data-view="command" class="active" onclick="sv('command',this)"><span class="ni">&#9632;</span>Command Center<span id="queueBadge" style="display:none;background:#d63b3b;color:white;border-radius:999px;font-size:10px;font-weight:700;padding:1px 7px;margin-left:auto;line-height:1.6">0</span></a>
<a data-view="robots" onclick="sv('robots',this)"><span class="ni">&#128247;</span>Robot Feeds</a>
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
<div id="sideBatBar" style="display:none;margin:5px 0 4px"><div style="display:flex;justify-content:space-between;margin-bottom:2px"><span style="font-size:10px;color:#7fa8cc;font-weight:600">Battery</span><span id="sideBatText" style="font-size:10px;font-weight:700;color:#7fa8cc"></span></div><div style="height:5px;background:#ffffff14;border-radius:3px;overflow:hidden"><div id="sideBatFill" style="height:100%;border-radius:3px;transition:width .6s,background .6s"></div></div></div>
<button class="btn p" style="width:100%;justify-content:center" onclick="cmd('camera_start')">Open Camera</button>
</div>
</aside>
<main style="display:flex;flex-direction:column;overflow:hidden;height:100vh">
<header class="top">
<div><h1 id="pageTitle">Command Center</h1><div class="sub" id="pageSubtitle">Live data from Nova and your facility registry.</div></div>
<div class="topRight">
<span class="pill low">${cleanText(u.username)} &middot; ${cleanText(u.role)}</span>
<span class="pill off" id="onlinePill">Offline</span>
<span class="pill bad" id="alertCount" style="display:none">0 alerts</span>
<a class="btn" href="/logout">Sign Out</a>
</div>
</header>
<div class="content" style="overflow-y:auto">

<section class="view active" id="view-command">
<div class="g5">
<button class="tile" onclick="goRounds()"><div class="ti c-green">&#8635;</div><span>Care Rounds</span><small>Build &amp; dispatch round</small></button>
<button class="tile" onclick="cmdCheckIn()"><div class="ti c-blue">&#10003;</div><span>Check-In</span><small>Use selector below</small></button>
<button class="tile" onclick="cmdMed()"><div class="ti c-yellow">&#9670;</div><span>Medication</span><small>Use selector below</small></button>
<button class="tile" onclick="goAlerts()"><div class="ti c-red">!</div><span>Staff Alert</span><small>Create &amp; notify staff</small></button>
<button class="tile" onclick="cmdGuide()"><div class="ti c-purple">&#8594;</div><span>Guide Visitor</span><small>Use selector below</small></button>
</div>
<div class="stats">
<div class="stat"><span class="sl">Robot</span><b id="statRobot">—</b></div>
<div class="stat"><span class="sl">Residents</span><b id="statResidents">—</b></div>
<div class="stat"><span class="sl">Map Points</span><b id="statPoints">—</b></div>
<div class="stat"><span class="sl">People Seen</span><b id="statPeople">—</b></div>
<div class="stat"><span class="sl">Camera</span><b id="statCamera">—</b></div>
</div>
<div id="quickActionsCard" class="card" style="margin-top:14px">
<div class="ch">Quick Dispatch <span class="pill low" style="font-size:10px;margin-left:4px">Select a target then act</span></div>
<div class="g2" style="gap:0">
<div style="padding-right:18px;border-right:1px solid #f0f4fa">
<label class="fl" style="margin-top:0">Resident</label>
<select class="field" id="cmdResidentSelect" style="margin-bottom:6px"><option value="" disabled>No residents yet — add in Residents section</option></select>
<div id="cmdResInfo" style="font-size:11px;color:#8898b0;margin-bottom:10px;min-height:14px"></div>
<div style="display:flex;gap:6px;flex-wrap:wrap">
<button class="btn p s" onclick="cmdCheckIn()">&#10003;&nbsp;Check-In</button>
<button class="btn s" style="background:#fffbe8;border-color:#e8cc5a;color:#7a4500" onclick="cmdMed()">&#9670;&nbsp;Med Reminder</button>
<button class="btn d s" onclick="cmdAlert()">!&nbsp;Alert Staff</button>
</div>
</div>
<div style="padding-left:18px">
<label class="fl" style="margin-top:0">Location</label>
<select class="field" id="cmdPointSelect" style="margin-bottom:6px"><option value="" disabled>Waiting for Nova to connect...</option></select>
<div style="font-size:11px;color:#8898b0;margin-bottom:10px">Send Nova to the selected map point.</div>
<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px">
<button class="btn p s" onclick="cmdGuide()">&#8594;&nbsp;Guide Visitor</button>
<button class="btn s" onclick="toggleCmdMsg()">Speak Message</button>
</div>
<div id="cmdMsgBox" style="display:none">
<textarea class="field" id="cmdMessageText" rows="2" placeholder="Nova will speak this at the destination..." style="margin-top:0"></textarea>
<button class="btn p s" style="margin-top:6px;width:100%;justify-content:center" onclick="cmdMsg()">&#9654;&nbsp;Send Message</button>
</div>
</div>
</div>
</div>
<div class="g3" style="margin-top:14px">
<div class="card"><div class="ch">Robot Status<div style="display:flex;gap:5px"><button class="btn s d" onclick="cmd('stop')">Stop</button><button class="btn s" onclick="cmd('charge')">Charge</button></div></div><div id="robotBox"></div></div>
<div class="card"><div class="ch">Residents</div><div id="residentBox"></div></div>
<div class="card"><div class="ch">Active Alerts</div><div id="alertBox"></div></div>
</div>
<div class="g3" style="margin-top:14px">
<div class="card"><div class="ch">People Detection</div><div id="peopleBox"></div></div>
<div class="card"><div class="ch">Locations<span id="locCount" class="pill low" style="display:none"></span></div><div id="mapBox" style="max-height:210px;overflow-y:auto"></div></div>
<div class="card"><div class="ch">Camera<div style="display:flex;gap:5px"><button class="btn s p" onclick="cmd(&#39;camera_start&#39;)">Live</button><button class="btn s" onclick="cmd(&#39;camera_stop&#39;)">Close</button></div></div><div id="cameraBox" style="display:none;margin-top:2px"><div class="cam-wrap"><img id="camera" alt="Nova" style="opacity:1"><img id="camera2" alt="" aria-hidden="true" style="opacity:0"><div class="cam-overlay"><span class="live-dot"></span><span style="color:white;font-size:10px;font-weight:800;letter-spacing:1.5px;text-shadow:0 1px 4px #000c">LIVE</span></div><div id="camTs" style="position:absolute;bottom:9px;right:10px;color:rgba(255,255,255,.78);font-size:11px;font-weight:600;text-shadow:0 1px 4px #000a;pointer-events:none"></div><button onclick="camFs()" title="Fullscreen" style="position:absolute;bottom:8px;left:10px;background:rgba(0,0,0,.48);border:0;color:white;border-radius:6px;padding:4px 8px;font-size:12px;cursor:pointer;line-height:1.4;transition:background .12s">&#x26F6;</button></div></div><div id="noCamera" class="esbox" style="min-height:70px">No camera feed from Nova.</div></div>
</div>
<div class="card" style="margin-top:14px"><div class="ch">Pending Command Queue<div style="display:flex;gap:6px;align-items:center"><span id="queueCount" class="pill off">0 pending</span><button class="btn s d" onclick="clearQueue()">Clear</button></div></div><div id="queueList"></div></div>
</section>

<section class="view" id="view-robots">
<div class="g2">
<div class="card"><div class="ch">Robot Telemetry<div style="display:flex;gap:5px"><button class="btn s d" onclick="cmd('stop')">Emergency Stop</button><button class="btn s" onclick="cmd('charge')">Go Charge</button></div></div><div id="robotsFleet"></div></div>
<div class="card"><div class="ch">Detection Feed</div><div id="robotDiagnostics"></div><div style="margin-top:14px"><div class="ch" style="margin-bottom:8px">People in Range</div><div id="detectionList"></div></div></div>
</div>
</section>

<section class="view" id="view-rounds">
<div class="g3t">
<div class="card">
<div class="ch">Round Builder<div style="display:flex;gap:4px"><button class="btn s" onclick="selectAllForRound(true)">All</button><button class="btn s" onclick="selectAllForRound(false)">None</button></div></div>
<label class="fl">Round Type</label>
<select class="field" id="roundType" style="margin-bottom:10px">
<option value="checkin">Check-in Round</option>
<option value="medication">Medication Round</option>
<option value="full">Full Care (Check-in + Medication)</option>
</select>
<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
<span style="font-size:11px;font-weight:700;color:#6878a0;text-transform:uppercase;letter-spacing:.5px">Visit Order <span id="roundSelCount" style="font-weight:500;color:#8898b0;text-transform:none;letter-spacing:0"></span></span>
<span style="font-size:11px;color:#8898b0">&#9650;&#9660; to reorder</span>
</div>
<div id="roundResidentPicker" style="border:1px solid #e5eaf3;border-radius:10px;overflow:hidden;max-height:360px;overflow-y:auto;margin-bottom:12px"></div>
<button class="btn p" style="width:100%;justify-content:center" onclick="startRound()">&#8635;&nbsp; Start This Round</button>
</div>
<div class="card">
<div class="ch">Scheduled Rounds<span id="schedBadge" class="pill ok" style="display:none">0 active</span></div>
<div id="scheduleList"></div>
<div style="margin-top:14px;padding-top:14px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">New Schedule</div>
<label class="fl">Name</label><input class="field" id="schName" placeholder="e.g. Morning Check-in">
<div class="ir"><div class="iw"><label class="fl">Type</label><select class="field" id="schType"><option value="checkin">Check-in</option><option value="medication">Medication</option><option value="full">Full Care</option></select></div><div class="iw"><label class="fl">Time</label><input class="field" id="schTime" type="time" value="09:00"></div></div>
<label class="fl">Repeats</label>
<select class="field" id="schDays" style="margin-bottom:10px">
<option value="daily">Every Day</option>
<option value="weekdays">Weekdays (Mon – Fri)</option>
<option value="weekends">Weekends</option>
<option value="mon">Mondays only</option>
<option value="tue">Tuesdays only</option>
<option value="wed">Wednesdays only</option>
<option value="thu">Thursdays only</option>
<option value="fri">Fridays only</option>
</select>
<label class="fl" style="margin-top:8px">Include in Round</label>
<div style="background:#f5f8fd;border:1px solid #e5eaf3;border-radius:10px;padding:10px;margin-bottom:10px">
<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:7px">
<span style="font-size:11px;color:#5a6a80;font-weight:600">Who Nova will visit</span>
<div style="display:flex;gap:4px"><button type="button" class="btn s" onclick="schSelAll(true)">All</button><button type="button" class="btn s" onclick="schSelAll(false)">None</button></div>
</div>
<div id="schResPicker" style="max-height:160px;overflow-y:auto"><div class="esbox" style="min-height:40px;font-size:12px">Add residents first.</div></div>
</div>
<button class="btn p s" onclick="saveSchedule()">&#43; Add Schedule</button>
</div>
</div>
<div class="card">
<div class="ch">Round History</div>
<div id="roundHistoryList"></div>
<div style="margin-top:16px;padding-top:14px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:8px">Quick Check-in</div>
<div id="roundResidents"></div>
</div>
</div>
</div>
</section>

<section class="view" id="view-residents">
<div class="g2">
<div class="card" id="resFormCard">
<div class="ch"><span id="resFormTitle">Add Resident</span></div>
<div id="editBanner" class="edit-banner">&#9998;&nbsp;<span id="editBannerName"></span><button class="btn s" onclick="cancelEdit()" style="margin-left:auto">&#10005; Cancel</button></div>
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
</div>
<div style="margin-top:18px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Import via CSV</div>
<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px"><a class="btn s" href="/templates/residents.csv">Download Template</a></div>
<input class="field" type="file" id="residentFile" accept=".csv,text/csv">
<button class="btn s" onclick="uploadResidents()" style="margin-top:6px">Upload CSV</button>
<p style="font-size:12px;color:#8898b0;margin:6px 0 0">Fill the template, save as CSV, then upload.</p>
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
<button class="btn d s" onclick="staffAlertForResident()">Alert Staff</button>
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
<div class="card"><div class="ch">Map Points<span id="locCountFull" class="pill low" style="display:none;margin-left:6px"></span></div><div id="fullMapBox"></div><p style="font-size:12px;color:#8898b0;margin:8px 0 0">Tap a location to send Nova there. Active destination highlighted in green.</p></div>
<div class="card">
<div class="ch">Destination Control</div>
<label class="fl">Map Point</label><select class="field" id="pointSelect"></select>
<button class="btn p" style="margin-top:10px;width:100%;justify-content:center" onclick="guideSelected()">Guide Visitor Here</button>
<div style="margin-top:18px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Send a Message</div>
<label class="fl">Message (Nova will speak this)</label><textarea class="field" id="messageText" rows="3" placeholder="Please proceed to the waiting area."></textarea>
<button class="btn" style="margin-top:8px;width:100%;justify-content:center" onclick="sendMessage()">Send Message to Point</button>
</div>
</div>
</div>
</section>

<section class="view" id="view-logs">
<div class="card">
<div class="ch">Operations Log<span id="logCount" class="pill off" style="font-size:10px;margin-left:6px;display:none"></span></div>
<div id="opsLog" style="max-height:600px;overflow-y:auto"></div>
</div>
</section>

<section class="view" id="view-settings">
<div class="g2">
<div class="card">
<div class="ch">Cloud Relay Status</div>
<div id="settingsRelay"></div>
<div style="margin-top:14px;display:flex;flex-wrap:wrap;gap:6px"><button class="btn p s" onclick="cmd(&#39;camera_start&#39;)">Test Camera</button><button class="btn s" onclick="cmd(&#39;security_start&#39;)">Test Detection</button><a class="btn s" href="/api/export" download>&#8681; Export Backup</a></div>
<div style="margin-top:20px;padding-top:16px;border-top:1px solid #eef2f8">
<div class="ch" style="margin-bottom:10px">Brand Logo</div>
<img class="lp" id="logoPreview" src="${brandLogoDataUrl || ""}" alt="Logo">
<input class="field" id="logoFile" type="file" accept="image/png,image/jpeg,image/webp" style="margin-top:8px">
<button class="btn p s" onclick="uploadLogo()" style="margin-top:6px">Upload Logo</button>
<p style="font-size:12px;color:#8898b0;margin:6px 0 0">PNG or JPG, max 5 MB.</p>
</div>
</div>
<div class="card">
<div class="ch">User Access</div>
<div class="fbox">
<label class="fl">Username</label><input class="field" id="newUser" placeholder="Username (3–40 chars)">
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
var T={command:["Command Center","Live data from Nova and your facility registry."],robots:["Robot Feeds","Telemetry and detection feed from Nova."],rounds:["Care Rounds","Build rounds, set schedules, and track check-ins."],residents:["Residents","Manage the resident registry."],alerts:["Alerts","Create urgent alerts and monitor facility events."],map:["Map & Messaging","Live map points from Nova."],logs:["Operations Log","Commands, state updates and facility actions."],settings:["Settings","Relay status, users, logo and CSV format."]};
function get(p){return fetch(p,{cache:"no-store"}).then(function(r){return r.ok?r.json():{};}).catch(function(){return{};})}
function post(p,b){return fetch(p,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(b)}).then(function(r){return r.json();}).catch(function(){return{ok:false,error:"Network error"};})}
function cmd(action,params){params=params||{};notice("Sending...",true);post("/api/command",{action:action,params:params}).then(function(out){notice(out.ok?"Sent: "+action:"Error: "+(out.error||"failed"),out.ok);refresh();});}
function esc(v){var map={"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"};return String(v||"").replace(/[&<>"']/g,function(c){return map[c];});}
function notice(t,ok){if(ok===undefined)ok=true;var el=document.getElementById("toast");if(!el)return;el.innerHTML=(ok?'<span style="color:#2ec47a;font-size:15px;flex-shrink:0">&#10003;</span>':'<span style="color:#ef5350;font-size:15px;flex-shrink:0">&#10005;</span>')+'<span>'+esc(String(t||""))+'</span>';el.className="toast show "+(ok?"t-ok":"t-err");clearTimeout(notice._t);notice._t=setTimeout(function(){el.className="toast";},3800);}
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
function esb(t){return '<div class="esbox">'+esc(t)+'</div>';}
function timeAgo(ms){var d=Date.now()-ms;if(d<60000)return"just now";if(d<3600000)return Math.floor(d/60000)+"m ago";if(d<86400000)return Math.floor(d/3600000)+"h ago";return Math.floor(d/86400000)+"d ago";}
function byId(id){var s=window._s;var res=(s&&s.care&&s.care.residents)||[];for(var i=0;i<res.length;i++){if(res[i].id===id)return res[i];}return null;}
function rParams(id){var el=document.getElementById("residentSelect");var rid=id||(el&&el.value)||"";var r=byId(rid);if(r){return{residentId:r.id,residentName:r.name,room:r.room,mapPoint:r.mapPoint||r.room,notes:[r.medicationNotes,r.mobilityNotes,r.emergencyNotes].filter(Boolean).join("; "),checkInPrompt:r.checkInSchedule||""};}return{residentId:rid};}
function checkInResident(id){if(!id)return notice("Select a resident first.",false);cmd("resident_checkin",rParams(id));}
function medResident(id){if(!id)return notice("Select a resident first.",false);cmd("med_reminder",rParams(id));}
function checkInSelected(){var el=document.getElementById("residentSelect");checkInResident(el&&el.value);}
function medSelected(){var el=document.getElementById("residentSelect");medResident(el&&el.value);}
function staffAlert(){cmd("staff_alert",{priority:"urgent",message:"Staff assistance requested."});}
function staffAlertForResident(){var el=document.getElementById("residentSelect");cmd("staff_alert",{priority:"urgent",residentId:(el&&el.value)||"",message:"Assistance requested for resident."});}
function guideSelected(){var el=document.getElementById("pointSelect");var p=el&&el.value;if(!p||p.indexOf("No ")==0)return notice("No map points received from Nova yet.",false);cmd("visitor_guide",{destination:p});}
function sendMessage(){var el=document.getElementById("pointSelect");var p=el&&el.value;if(!p||p.indexOf("No ")==0)return notice("No map points received from Nova yet.",false);var mt=document.getElementById("messageText");var msg=(mt&&mt.value.trim())||"Please meet Nova at this location.";cmd("message",{destination:p,message:msg});if(mt)mt.value="";}
var _eid=null;
function hlField(id,ok){var el=document.getElementById(id);if(!el)return;el.style.borderColor=ok?"":"#d63b3b";el.style.boxShadow=ok?"":"0 0 0 3px rgba(214,59,59,.12)";}
function editResident(id){
  var r=byId(id);if(!r)return notice("Resident not loaded yet. Try again.",false);
  _eid=id;
  var fields={manualName:r.name,manualRoom:r.room,manualMapPoint:r.mapPoint||r.room,manualWing:r.wing||"",manualCare:r.careLevel||"",manualPhone:r.contactPhone||"",manualNotes:[r.medicationNotes,r.mobilityNotes,r.emergencyNotes].filter(Boolean).join("; "),manualPrompt:r.checkInSchedule||""};
  Object.keys(fields).forEach(function(fid){var el=document.getElementById(fid);if(el)el.value=fields[fid];});
  document.getElementById("saveResBtn").textContent="Save Changes";
  document.getElementById("resFormTitle").textContent="Edit Resident";
  var eb=document.getElementById("editBanner");var ebn=document.getElementById("editBannerName");
  if(eb){eb.style.display="flex";}if(ebn)ebn.textContent=r.name;
  sv("residents",document.querySelector('[data-view="residents"]'));
  setTimeout(function(){var fc=document.getElementById("resFormCard");if(fc)fc.scrollIntoView({behavior:"smooth",block:"start"});},100);
  var mn=document.getElementById("manualName");if(mn)mn.focus();
}
function cancelEdit(){
  _eid=null;
  ["manualName","manualRoom","manualMapPoint","manualWing","manualCare","manualPhone","manualNotes","manualPrompt"].forEach(function(fid){var el=document.getElementById(fid);if(el)el.value="";});
  hlField("manualName",true);hlField("manualRoom",true);
  document.getElementById("saveResBtn").textContent="Add Resident";
  document.getElementById("resFormTitle").textContent="Add Resident";
  var eb=document.getElementById("editBanner");if(eb)eb.style.display="none";
}
function saveResident(){
  var nameEl=document.getElementById("manualName");var roomEl=document.getElementById("manualRoom");
  var name=nameEl&&nameEl.value.trim();var room=roomEl&&roomEl.value.trim();
  hlField("manualName",!!name);hlField("manualRoom",!!room);
  if(!name)return notice("Full name is required.",false);
  if(!room)return notice("Room is required.",false);
  var mpEl=document.getElementById("manualMapPoint");var mapPoint=(mpEl&&mpEl.value.trim())||room;
  function gv(id){var e=document.getElementById(id);return(e&&e.value.trim())||"";}
  var b={full_name:name,room:room,map_point:mapPoint,wing:gv("manualWing"),care_level:gv("manualCare"),primary_contact_phone:gv("manualPhone"),medication_notes:gv("manualNotes"),check_in_schedule:gv("manualPrompt")};
  if(_eid)b.resident_id=_eid;
  post("/api/residents",b).then(function(out){
    notice(out.ok?(_eid?"Resident updated":"Resident added"):(out.error||"Could not save"),out.ok);
    if(out.ok)cancelEdit();refresh();
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
    notice(out.ok?"Alert created":(out.error||"Could not create alert"),out.ok);
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
  if(!file)return notice("Choose a CSV file first.",false);
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
  if(!file)return notice("Choose a logo image first.",false);
  if(file.size>5*1024*1024)return notice("Logo too large. Use PNG/JPEG under 5 MB.",false);
  var reader=new FileReader();
  reader.onload=function(){
    post("/api/logo",{dataUrl:reader.result}).then(function(out){
      notice(out.ok?"Logo updated":(out.error||"Failed"),out.ok);
      if(out.logo){var lp=document.getElementById("logoPreview");var sl=document.getElementById("sideLogo");if(lp)lp.src=out.logo;if(sl)sl.innerHTML='<img src="'+out.logo+'" alt="ZOX" style="width:100%;height:100%;object-fit:cover">';}
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
  var lc=document.getElementById("locCount"),lcf=document.getElementById("locCountFull");
  if(!points.length){
    target.innerHTML=esb("No locations yet. Connect Nova to populate map points.");
    if(lc)lc.style.display="none";if(lcf)lcf.style.display="none";
    return;
  }
  var dest=(window._s&&window._s.status&&window._s.status.destination)||"";
  var label=points.length+" location"+(points.length!==1?"s":"");
  if(lc){lc.textContent=label;lc.style.display="inline-flex";}
  if(lcf){lcf.textContent=label;lcf.style.display="inline-flex";}
  target.innerHTML='<div class="loc-grid">'+points.map(function(p){
    var active=dest&&p.name.toLowerCase()===dest.toLowerCase();
    return '<button class="loc-btn'+(active?" curr":"")+'" onclick="cmd(&#39;visitor_guide&#39;,{destination:&#39;'+esc(p.name)+'&#39;})" title="Send Nova to '+esc(p.name)+'">'+
      '<span class="li">&#9685;</span>'+
      '<span class="ln">'+esc(p.name)+'</span>'+
      (active?'<span style="font-size:9px;font-weight:700;letter-spacing:.5px;opacity:.85">HERE</span>':'')+
    '</button>';
  }).join('')+'</div>';
}
function aCard(a){
  var u=a.priority!=="standard";
  return '<div class="ac'+(u?"":" std")+'"><div class="adot'+(u?"":" std")+'">'+(u?"!":"&#9650;")+'</div><div class="ab"><b>'+esc(a.message||"Alert")+'</b><span class="as">'+esc(a.room||"Facility")+" &middot; "+new Date(a.createdAt||Date.now()).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"})+'</span></div><div class="aa"><button class="btn s d" onclick="dismissAlert(&#39;'+esc(a.id)+'&#39;)">Dismiss</button></div></div>';
}
function _buildFullOrder(){
  var s=window._s;var all=(s&&s.care&&s.care.residents)||[];
  var existing=window._roundOrder||[];
  var full=existing.slice();
  all.forEach(function(r){if(full.indexOf(r.id)<0)full.push(r.id);});
  return{order:full,all:all};
}
function moveResidentUp(id){
  var ob=_buildFullOrder();var order=ob.order;
  var idx=order.indexOf(id);if(idx<=0)return;
  var tmp=order[idx-1];order[idx-1]=order[idx];order[idx]=tmp;
  window._roundOrder=order;
  fetch("/api/round-order",{method:"PUT",headers:{"content-type":"application/json"},body:JSON.stringify({order:order})});
  renderRoundPicker(ob.all);
}
function moveResidentDown(id){
  var ob=_buildFullOrder();var order=ob.order;
  var idx=order.indexOf(id);if(idx<0||idx>=order.length-1)return;
  var tmp=order[idx+1];order[idx+1]=order[idx];order[idx]=tmp;
  window._roundOrder=order;
  fetch("/api/round-order",{method:"PUT",headers:{"content-type":"application/json"},body:JSON.stringify({order:order})});
  renderRoundPicker(ob.all);
}
function sortedResidents(res){
  var order=window._roundOrder||[];
  return res.slice().sort(function(a,b){
    var ia=order.indexOf(a.id),ib=order.indexOf(b.id);
    if(ia>=0&&ib>=0)return ia-ib;if(ia>=0)return -1;if(ib>=0)return 1;return 0;
  });
}
function renderRoundPicker(res){
  var el=document.getElementById("roundResidentPicker");if(!el)return;
  if(!res.length){el.innerHTML='<div class="esbox">No residents registered. Add them in the Residents section.</div>';return;}
  var sorted=sortedResidents(res);
  var prev={};var cbs=document.querySelectorAll(".round-res-cb");
  for(var i=0;i<cbs.length;i++)prev[cbs[i].value]=cbs[i].checked;
  var checkIns=(window._s&&window._s.facility&&window._s.facility.checkIns)||{};
  el.innerHTML=sorted.map(function(r,idx){
    var chk=prev[r.id]===false?"":"checked";
    var lastCI=checkIns[r.id];
    var lastStr=lastCI?timeAgo(lastCI):"Never";
    return '<div class="res-row">'+
      '<span class="round-num">'+(idx+1)+'</span>'+
      '<input type="checkbox" class="round-res-cb" value="'+esc(r.id)+'" '+chk+' onchange="updateRoundSelCount()" style="width:15px;height:15px;accent-color:#1a68e0;flex-shrink:0">'+
      '<div style="flex:1;min-width:0">'+
        '<div style="font-weight:600;font-size:13px">'+esc(r.name)+'</div>'+
        '<div style="font-size:11px;color:#8898b0">Room '+esc(r.room)+(r.careLevel?' &middot; '+esc(r.careLevel):'')+' &middot; Last: '+lastStr+'</div>'+
      '</div>'+
      '<div style="display:flex;flex-direction:column;gap:2px;flex-shrink:0">'+
        '<button class="ord-btn" onclick="moveResidentUp(&#39;'+esc(r.id)+'&#39;)" '+(idx===0?'disabled':'')+'>&#9650;</button>'+
        '<button class="ord-btn" onclick="moveResidentDown(&#39;'+esc(r.id)+'&#39;)" '+(idx===sorted.length-1?'disabled':'')+'>&#9660;</button>'+
      '</div></div>';
  }).join('');
  updateRoundSelCount();
}
function selectAllForRound(v){if(v===undefined)v=true;var cbs=document.querySelectorAll(".round-res-cb");for(var i=0;i<cbs.length;i++)cbs[i].checked=v;updateRoundSelCount();}
function updateRoundSelCount(){var cbs=document.querySelectorAll(".round-res-cb");var total=cbs.length,checked=0;for(var i=0;i<cbs.length;i++){if(cbs[i].checked)checked++;}var el=document.getElementById("roundSelCount");if(el)el.textContent=total?"("+checked+" of "+total+")":"";}
function goRounds(){var a=document.querySelector('.nav a[data-view="rounds"]');sv("rounds",a);}
function goAlerts(){var a=document.querySelector('.nav a[data-view="alerts"]');sv("alerts",a);}
function _cmdResId(){var el=document.getElementById("cmdResidentSelect");return el&&el.value||"";}
function _cmdPt(){var el=document.getElementById("cmdPointSelect");return el&&el.value||"";}
function cmdCheckIn(){var rid=_cmdResId();if(!rid)return notice("Select a resident in the Quick Dispatch panel first.",false);checkInResident(rid);}
function cmdMed(){var rid=_cmdResId();if(!rid)return notice("Select a resident in the Quick Dispatch panel first.",false);medResident(rid);}
function cmdAlert(){var rid=_cmdResId();var r=byId(rid)||{};cmd("staff_alert",{priority:"urgent",residentId:rid,room:r.room||"",message:"Staff assistance requested"+(r.name?" for "+r.name:".")});}
function cmdGuide(){var p=_cmdPt();if(!p)return notice("Select a location in the Quick Dispatch panel first.",false);cmd("visitor_guide",{destination:p});}
function cmdMsg(){var p=_cmdPt();if(!p)return notice("Select a location in the Quick Dispatch panel first.",false);var mt=document.getElementById("cmdMessageText");var msg=(mt&&mt.value.trim())||"Please meet Nova here.";cmd("message",{destination:p,message:msg});if(mt)mt.value="";}
function toggleCmdMsg(){var b=document.getElementById("cmdMsgBox");if(b)b.style.display=b.style.display==="none"?"block":"none";}
function startRound(){
  var typeEl=document.getElementById("roundType");var roundType=typeEl?typeEl.value:"checkin";
  var cbs=document.querySelectorAll(".round-res-cb");var selected=[];
  for(var i=0;i<cbs.length;i++){if(cbs[i].checked)selected.push(cbs[i].value);}
  var s=window._s;var allRes=(s&&s.care&&s.care.residents)||[];
  var sorted=sortedResidents(allRes);
  var residents=selected.length?sorted.filter(function(r){return selected.indexOf(r.id)>=0;}):sorted;
  if(!residents.length)return notice("No residents selected. Add residents first.",false);
  var ids=sorted.map(function(r){return r.id;});
  fetch("/api/round-order",{method:"PUT",headers:{"content-type":"application/json"},body:JSON.stringify({order:ids})});
  cmd("start_rounds",{type:roundType,residents:residents.map(function(r){return{id:r.id,name:r.name,room:r.room,mapPoint:r.mapPoint||r.room,checkInPrompt:r.checkInSchedule||""};})});
}
function renderSchedules(schedules){
  var el=document.getElementById("scheduleList");if(!el)return;
  var sb=document.getElementById("schedBadge");
  var active=schedules.filter(function(s){return s.enabled;}).length;
  if(sb){if(active){sb.textContent=active+" active";sb.style.display="inline-flex";}else sb.style.display="none";}
  if(!schedules.length){el.innerHTML=esb("No scheduled rounds yet. Add one below.");return;}
  var allRes=(window._s&&window._s.care&&window._s.care.residents)||[];
  function resLabel(s){
    if(!s.residentIds||!s.residentIds.length)return"All residents"+(allRes.length?" ("+allRes.length+")":"");
    var names=[];
    for(var i=0;i<s.residentIds.length;i++){for(var j=0;j<allRes.length;j++){if(allRes[j].id===s.residentIds[i]){names.push(allRes[j].name.split(" ")[0]);break;}}}
    if(!names.length)return s.residentIds.length+" residents";
    var extra=names.length-3;
    return names.slice(0,3).join(", ")+(extra>0?" +"+extra+" more":"");
  }
  el.innerHTML=schedules.map(function(s){
    var dLabel=Array.isArray(s.days)?s.days.join(", "):String(s.days||"daily");
    var tc=s.type==="medication"?"#fef3d0":s.type==="full"?"#f0eaff":"#e4edff";
    var tcc=s.type==="medication"?"#8f5c00":s.type==="full"?"#5830a8":"#1448b8";
    var tl=s.type==="medication"?"Medication":s.type==="full"?"Full Care":"Check-in";
    return '<div class="sch-item">'+
      '<div style="flex:1;min-width:0">'+
        '<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-bottom:4px">'+
          '<b style="font-size:13px">'+esc(s.name)+'</b>'+
          '<span class="pill '+(s.enabled?"ok":"off")+'" style="font-size:10px">'+(s.enabled?"ON":"OFF")+'</span>'+
          '<span style="border-radius:5px;padding:2px 6px;font-size:10px;font-weight:800;background:'+tc+';color:'+tcc+'">'+tl+'</span>'+
        '</div>'+
        '<div style="font-size:12px;color:#5a6a80">'+esc(s.time)+' &middot; '+esc(dLabel)+'</div>'+
        '<div style="font-size:12px;color:#1a68e0;font-weight:600;margin-top:3px">&#9673;&nbsp;'+esc(resLabel(s))+'</div>'+
        (s.lastRun?'<div style="font-size:11px;color:#b0b8cc;margin-top:2px">Last: '+esc(s.lastRun.split(" ").slice(0,4).join(" "))+'</div>':'')+
      '</div>'+
      '<div style="display:flex;flex-direction:column;gap:5px;align-items:flex-end;flex-shrink:0;margin-left:8px">'+
        '<button class="btn s '+(s.enabled?"p":"")+'" onclick="toggleSchedule(&#39;'+esc(s.id)+'&#39;)">'+(s.enabled?"ON":"OFF")+'</button>'+
        '<button class="btn s d" onclick="deleteSchedule(&#39;'+esc(s.id)+'&#39;)">&#10005;</button>'+
      '</div>'+
    '</div>';
  }).join('');
}
function saveSchedule(){
  var n=document.getElementById("schName");var name=n&&n.value.trim();
  if(!name)return notice("Schedule name is required.",false);
  var typeEl=document.getElementById("schType");var timeEl=document.getElementById("schTime");var daysEl=document.getElementById("schDays");
  var time=(timeEl&&timeEl.value)||"09:00";
  var daysRaw=(daysEl&&daysEl.value)||"daily";
  var days=daysRaw==="weekdays"?["mon","tue","wed","thu","fri"]:daysRaw==="weekends"?["sat","sun"]:daysRaw;
  var cbs=document.querySelectorAll(".sch-res-cb");
  var residentIds=[],allChecked=true;
  for(var i=0;i<cbs.length;i++){if(cbs[i].checked)residentIds.push(cbs[i].value);else allChecked=false;}
  if(allChecked||!cbs.length)residentIds=[];
  post("/api/schedules",{name:name,type:(typeEl&&typeEl.value)||"checkin",time:time,days:days,residentIds:residentIds,enabled:true}).then(function(out){
    notice(out.ok?"Schedule saved":(out.error||"Could not save"),out.ok);
    if(out.ok&&n)n.value="";refresh();
  });
}
function deleteSchedule(id){
  if(!confirm("Delete this schedule?"))return;
  fetch("/api/schedules/"+encodeURIComponent(id),{method:"DELETE"}).then(function(r){return r.json();}).then(function(out){
    notice(out.ok?"Schedule deleted":(out.error||"Could not delete"),out.ok);refresh();
  });
}
function toggleSchedule(id){
  post("/api/schedules/"+encodeURIComponent(id)+"/toggle",{}).then(function(out){
    notice(out.ok?(out.enabled?"Schedule enabled":"Schedule paused"):(out.error||"Could not toggle"),out.ok);refresh();
  });
}
var _histExpanded=false;
function renderRoundHistory(history){
  var el=document.getElementById("roundHistoryList");if(!el)return;
  if(!history||!history.length){el.innerHTML=esb("No rounds have run yet.");return;}
  var limit=5;
  var show=_histExpanded?history:history.slice(0,limit);
  function tl(t){return t==="medication"?"Medication":t==="full"?"Full Care":"Check-in";}
  var html=show.map(function(h){
    var trig=h.trigger==="schedule"?'<span class="pill ok" style="font-size:10px">Sched</span>':'<span class="pill low" style="font-size:10px">Manual</span>';
    return '<div class="row"><div class="dot c-green" style="font-size:12px">&#8635;</div><div class="rb"><b>'+esc(h.name||"Round")+'</b><span>'+tl(h.type)+' &middot; '+h.count+' residents &middot; '+timeAgo(h.at)+'</span></div><div class="ra">'+trig+'</div></div>';
  }).join('');
  if(history.length>limit){
    html+='<button class="expand-btn" onclick="toggleHist()">'+(_histExpanded?"Show less &#8593;":"Show "+(history.length-limit)+" more &#8595;")+'</button>';
  }
  el.innerHTML=html;
}
function toggleHist(){_histExpanded=!_histExpanded;var s=window._s;renderRoundHistory((s&&s.roundHistory)||[]);}
function refreshQueue(){
  get("/api/queue").then(function(out){
    var count=(out.queue&&out.queue.length)||0;
    var qc=document.getElementById("queueCount");var ql=document.getElementById("queueList");var qb=document.getElementById("queueBadge");
    if(qc){qc.textContent=count+" pending";qc.className="pill "+(count?"warn":"off");}
    if(qb){if(count){qb.textContent=count;qb.style.display="inline-flex";}else qb.style.display="none";}
    if(ql){
      if(!count){ql.innerHTML=esb("No pending commands. Robot is up to date.");}
      else{ql.innerHTML=(out.queue||[]).slice(0,10).map(function(c){return rRow("blue",c.action,new Date(c.at||Date.now()).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit",second:"2-digit"}));}).join("")+(count>10?'<p style="font-size:12px;color:#8898b0;margin:8px 0 0">+'+(count-10)+" more</p>":'');}
    }
  });
}
function clearQueue(){
  fetch("/api/queue",{method:"DELETE"}).then(function(r){return r.json();}).then(function(out){
    notice(out.ok?"Command queue cleared":(out.error||"Could not clear"),out.ok);refreshQueue();
  });
}
function renderSchResidentPicker(res){
  var el=document.getElementById("schResPicker");if(!el)return;
  if(!res||!res.length){el.innerHTML='<div class="esbox" style="min-height:40px;font-size:12px">Add residents first.</div>';return;}
  var prev={};var cbs=document.querySelectorAll(".sch-res-cb");var hasState=cbs.length>0;
  for(var i=0;i<cbs.length;i++)prev[cbs[i].value]=cbs[i].checked;
  el.innerHTML=res.map(function(r){
    var chk=hasState?(prev[r.id]===false?"":"checked"):"checked";
    return '<label class="sch-res-row">'+
      '<input type="checkbox" class="sch-res-cb" value="'+esc(r.id)+'" '+chk+' style="width:14px;height:14px;accent-color:#1a68e0;flex-shrink:0">'+
      '<span style="font-weight:600;font-size:13px">'+esc(r.name)+'</span>'+
      '<span style="color:#8898b0;font-size:11px;margin-left:auto;flex-shrink:0">Room '+esc(r.room)+'</span>'+
    '</label>';
  }).join('');
}
function schSelAll(v){var cbs=document.querySelectorAll(".sch-res-cb");for(var i=0;i<cbs.length;i++)cbs[i].checked=v;}
var _resDirExpanded=false;
function renderResidentDirectory(res,checkIns){
  var el=document.getElementById("residentDirectory");if(!el)return;
  checkIns=checkIns||{};
  if(!res.length){el.innerHTML=esb("No residents. Add one using the form.");return;}
  var limit=6;
  var show=_resDirExpanded?res:res.slice(0,limit);
  var html=show.map(function(r){
    var lastCI=checkIns[r.id];
    var sub='Room '+esc(r.room)+(r.wing?' &middot; Wing '+esc(r.wing):'')+(r.careLevel?' &mdash; '+esc(r.careLevel):'')+(lastCI?' &middot; Seen '+timeAgo(lastCI):'');
    return '<div class="row"><div class="dot c-purple">'+esc(r.name[0]||"?")+'</div><div class="rb"><b>'+esc(r.name)+'</b><span>'+sub+'</span></div><div class="ra"><button class="btn s" onclick="editResident(&#39;'+esc(r.id)+'&#39;)">Edit</button><button class="btn s d" onclick="deleteResident(&#39;'+esc(r.id)+'&#39;)">Remove</button></div></div>';
  }).join('');
  if(res.length>limit){
    html+='<button class="expand-btn" onclick="toggleResDir()">'+(_resDirExpanded?"Show less &#8593;":"Show "+(res.length-limit)+" more &#8595;")+'</button>';
  }
  el.innerHTML=html;
}
function toggleResDir(){_resDirExpanded=!_resDirExpanded;var s=window._s;renderResidentDirectory((s&&s.care&&s.care.residents)||[],(s&&s.facility&&s.facility.checkIns)||{});}
var _alertsExpanded=false;
function renderAlertCenter(al){
  var el=document.getElementById("alertCenter");if(!el)return;
  if(!al.length){el.innerHTML=esb("No active alerts.");return;}
  var limit=4;
  var show=_alertsExpanded?al:al.slice(0,limit);
  var html=show.map(aCard).join('');
  if(al.length>limit){
    html+='<button class="expand-btn" onclick="toggleAlerts()">'+(_alertsExpanded?"Show less &#8593;":"Show "+(al.length-limit)+" more &#8595;")+'</button>';
  }
  el.innerHTML=html;
}
function toggleAlerts(){_alertsExpanded=!_alertsExpanded;var s=window._s;renderAlertCenter((s&&s.care&&s.care.alerts)||[]);}
function renderAll(s){
  if(!s||typeof s!=="object")return;
  var c=s.care||{};var res=c.residents||[];var rem=c.reminders||[];var al=c.alerts||[];var pts=s.points||[];var ppl=s.people||[];
  window._s=s;
  if(!window._roundOrder||!window._roundOrder.length){var fac=s.facility||{};window._roundOrder=fac.roundOrder||[];}
  function gi(id){return document.getElementById(id);}
  function st(id,v){var e=gi(id);if(e)e.textContent=v;}
  function ht(id,v){var e=gi(id);if(e)e.innerHTML=v;}
  st("statRobot",s.online?"Online":"Offline");st("statResidents",res.length);st("statPoints",pts.length);st("statPeople",ppl.length);st("statCamera",s.camera?"Active":"Off");
  var so=gi("sideOnline");if(so){so.textContent=(s.online?"1":"0")+" / 1";so.className="pill "+(s.online?"ok":"off");}
  var status=s.status||{};
  var bat=esc(status.battery||"");var dest=esc(status.destination||"");var stat=esc(status.status||"");
  var batPct=(function(){var m=String(status.battery||"").match(/(\d+)/);return m?parseInt(m[1],10):-1;})();
  var batColor=batPct>50?"#1f9950":batPct>20?"#d49600":"#d63b3b";
  var shParts=[];if(s.online){shParts.push("Seen "+new Date(s.lastSeen).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit",second:"2-digit"}));if(bat)shParts.push(bat);if(dest)shParts.push(dest);}
  ht("sideHealth",s.online?shParts.join(" &middot; "):"Waiting for robot connection");
  var sbb=gi("sideBatBar");var sbf=gi("sideBatFill");var sbtt=gi("sideBatText");
  if(s.online&&batPct>=0){if(sbb)sbb.style.display="block";if(sbtt)sbtt.textContent=batPct+"%";if(sbf){sbf.style.width=Math.min(100,batPct)+"%";sbf.style.background=batColor;}}else{if(sbb)sbb.style.display="none";}
  var op=gi("onlinePill");if(op){op.textContent=s.online?"Online":"Offline";op.className="pill "+(s.online?"ok":"off");}
  var ac=gi("alertCount");if(ac){if(al.length){ac.style.display="inline-flex";ac.textContent=al.length+" alert"+(al.length===1?"":"s");ac.className="pill bad";}else ac.style.display="none";}
  var batNum=batPct>=0?batPct:0;
  var robotHtml='<div style="display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #f0f4fa"><div class="dot c-'+(s.online?"blue":"red")+'" style="width:42px;height:42px;font-size:16px">N</div><div style="flex:1"><div style="font-weight:700;font-size:14px">Nova 01</div><div style="font-size:12px;color:#8898b0;margin-top:2px">'+(dest||"No active destination")+"</div></div>"+(s.online?'<span class="pill ok">Online</span>':'<span class="pill off">Offline</span>')+"</div>"+(bat?'<div style="margin-top:10px"><div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:3px"><span style="font-size:11px;color:#5a6a80;font-weight:600">Battery</span><span style="font-size:12px;font-weight:700;color:'+batColor+'">'+bat+'</span></div><div style="height:5px;background:#f0f4fa;border-radius:3px;overflow:hidden"><div style="height:100%;width:'+Math.min(100,batNum)+'%;background:'+batColor+';border-radius:3px;transition:width .6s"></div></div></div>':"")+(stat?'<div style="margin-top:6px;font-size:12px;color:#5a6a80"><b style="color:#374151">Status:</b> '+stat+"</div>":"");
  ht("robotBox",robotHtml);ht("robotsFleet",robotHtml);
  ht("residentBox",res.length?res.slice(0,4).map(function(r){return'<div class="row"><div class="dot c-purple">'+esc(r.name[0]||"?")+'</div><div class="rb"><b>'+esc(r.name)+'</b><span>'+esc(r.room)+'</span></div><div class="ra"><button class="btn s p" onclick="checkInResident(&#39;'+esc(r.id)+'&#39;)">Go</button></div></div>';}).join("")+(res.length>4?'<p style="font-size:12px;color:#8898b0;margin:8px 0 0">+'+(res.length-4)+" more</p>":""):esb("No residents yet."));
  ht("alertBox",al.length?al.slice(0,3).map(function(a){return'<div class="row"><div class="dot c-'+(a.priority!=="standard"?"red":"yellow")+'">!</div><div class="rb"><b>'+esc((a.message||"Alert").slice(0,60))+'</b><span>'+esc(a.room||"Facility")+'</span></div><div class="ra"><button class="btn s d" onclick="dismissAlert(&#39;'+esc(a.id)+'&#39;)">&#215;</button></div></div>';}).join("")+(al.length>3?'<p style="font-size:12px;color:#8898b0;margin:8px 0 0">+'+(al.length-3)+" more</p>":""):esb("No active alerts."));
  ht("peopleBox",ppl.length?ppl.map(function(p){return'<div class="dchip"><div class="dc"></div>Person&nbsp;'+(p.id||"?")+" &mdash; "+(p.distance!=null?p.distance+"m away":"detected")+"</div>";}).join(""):esb("No people detected by Nova."));
  renderMap(gi("mapBox"),pts);renderMap(gi("fullMapBox"),pts);
  var sdkOk=!!(status.robotSdk);
  ht("robotDiagnostics",rRow(sdkOk?"green":"yellow","Robot SDK",sdkOk?"AgentOS connected":"No SDK connection")+rRow("blue","Map Points",pts.length+" point"+(pts.length!==1?"s":"")+" known")+rRow(s.camera?"green":"red","Camera",s.camera?"Frame available":"No frame"));
  ht("detectionList",ppl.length?ppl.map(function(p){return'<div class="row"><div class="dot c-cyan" style="font-size:11px">&#9679;</div><div class="rb"><b>Person detected</b><span>'+(p.distance!=null?"Distance: "+p.distance+"m":"x="+(p.x||"-")+", y="+(p.y||"-"))+"</span></div></div>";}).join(""):esb("No people in Nova's detection range."));
  var checkIns=(s.facility&&s.facility.checkIns)||{};
  ht("roundResidents",res.length?res.map(function(r){var lastCI=checkIns[r.id];var lastStr=lastCI?timeAgo(lastCI):"Never";return'<div class="row"><div class="dot c-blue">'+esc(r.name[0]||"?")+'</div><div class="rb"><b>'+esc(r.name)+'</b><span>Room '+esc(r.room)+' &middot; Last check-in: '+lastStr+'</span></div><div class="ra"><button class="btn s p" onclick="checkInResident(&#39;'+esc(r.id)+'&#39;)">Check In</button><button class="btn s" onclick="medResident(&#39;'+esc(r.id)+'&#39;)">Med</button></div></div>';}).join(""):esb("Add residents to begin care rounds."));
  renderRoundPicker(res);
  renderSchResidentPicker(res);
  renderSchedules(s.scheduledRounds||[]);
  renderRoundHistory(s.roundHistory||[]);
  renderResidentDirectory(res,checkIns);
  var rs=gi("residentSelect");var rsV=rs&&rs.value;
  if(rs)rs.innerHTML=res.length?res.map(function(r){return'<option value="'+esc(r.id)+'">'+esc(r.name)+" — Room "+esc(r.room)+"</option>";}).join(""):"<option disabled>No residents registered yet</option>";
  if(rs&&rsV){rs.value=rsV;}
  renderAlertCenter(al);
  var ps2=gi("pointSelect");if(ps2)ps2.innerHTML=pts.length?pts.map(function(p){return'<option value="'+esc(p.name)+'">'+esc(p.name)+"</option>";}).join(""):"<option disabled>No map points from Nova yet</option>";
  var crs=gi("cmdResidentSelect");var crsV=crs&&crs.value;
  if(crs)crs.innerHTML=res.length?res.map(function(r){return'<option value="'+esc(r.id)+'">'+esc(r.name)+" — Room "+esc(r.room)+"</option>";}).join(""):"<option value='' disabled>No residents yet — add in Residents section</option>";
  if(crs&&crsV){crs.value=crsV;}
  var cri=gi("cmdResInfo");if(cri){var crId=crs&&crs.value;var cr=byId(crId);var lci2=cr&&checkIns[cr.id];cri.textContent=cr?("Room "+cr.room+(cr.careLevel?" · "+cr.careLevel:"")+(lci2?" · Last seen "+timeAgo(lci2):" · No check-in on record")):(res.length?"Select a resident above.":"");}
  var cps=gi("cmdPointSelect");var cpsV=cps&&cps.value;
  if(cps)cps.innerHTML=pts.length?pts.map(function(p){return'<option value="'+esc(p.name)+'">'+esc(p.name)+"</option>";}).join(""):"<option value='' disabled>Waiting for Nova to connect...</option>";
  if(cps&&cpsV){cps.value=cpsV;}
  var cLogs=c.logs||[];var eLogs=(s.events||[]).map(function(e){return{createdAt:e.at,title:e.type,detail:typeof e.data==="object"?Object.keys(e.data||{}).slice(0,4).map(function(k){return k+": "+String(e.data[k]).slice(0,40);}).join(", "):String(e.data||"")};});
  var logRows=cLogs.concat(eLogs).filter(function(l){return l.title!=="state";}).sort(function(a,b){return(b.createdAt||0)-(a.createdAt||0);});
  var lc2=gi("logCount");if(lc2){lc2.textContent=logRows.length+" entries";lc2.style.display=logRows.length?"inline-flex":"none";}
  var LTITLE={"command":"Command sent","result":"Result received","resident":"Resident saved","resident_import":"CSV import","resident_deleted":"Resident removed","alert":"Alert created","alert_dismissed":"Alert dismissed","login":"Staff signed in","login_failed":"Sign-in failed","scheduled_round":"Scheduled round fired","queue_cleared":"Queue cleared","logo_updated":"Logo updated","user_created":"User created","schedule_created":"Schedule created","upsert_resident":"Resident synced to Nova","delete_resident":"Resident removed from Nova"};
  var LMETA={"command":{ic:"&#9654;",cl:"blue"},"result":{ic:"&#10003;",cl:"green"},"resident":{ic:"&#9673;",cl:"purple"},"resident_import":{ic:"&#9673;",cl:"purple"},"resident_deleted":{ic:"&#9673;",cl:"red"},"alert":{ic:"!",cl:"red"},"alert_dismissed":{ic:"&#10003;",cl:"green"},"login":{ic:"&#9679;",cl:"cyan"},"login_failed":{ic:"&#10005;",cl:"red"},"scheduled_round":{ic:"&#8635;",cl:"green"},"queue_cleared":{ic:"&#9747;",cl:"yellow"},"logo_updated":{ic:"&#9998;",cl:"cyan"},"user_created":{ic:"&#9873;",cl:"blue"},"schedule_created":{ic:"&#8635;",cl:"blue"},"upsert_resident":{ic:"&#9673;",cl:"blue"},"delete_resident":{ic:"&#9673;",cl:"red"}};
  ht("opsLog",logRows.length?logRows.slice(0,120).map(function(l){var m=LMETA[l.title]||{ic:"&#9679;",cl:"blue"};var dt=new Date(l.createdAt||Date.now());var diff=Date.now()-(l.createdAt||0);var ts=diff<3600000?timeAgo(l.createdAt):(diff<86400000?dt.toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}):dt.toLocaleDateString([],{month:"short",day:"numeric"})+" "+dt.toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}));return'<div class="row"><div class="dot c-'+m.cl+'" style="font-size:12px;width:32px;height:32px;border-radius:8px">'+m.ic+'</div><div class="rb"><b>'+(LTITLE[l.title]||esc(l.title||"Event"))+'</b><span>'+esc(String(l.detail||"").slice(0,100))+'</span></div><div class="ra" style="font-size:11px;color:#a0b0c8;white-space:nowrap">'+ts+'</div></div>';}).join(""):esb("No activity logged yet. Connect Nova and start managing your facility."));
  var fh={resident_id:"Optional — auto-generated if blank.",full_name:"Required.",room:"Required.",map_point:"Exact Nova map point name.",wing:"Optional.",care_level:"Independent / Assisted / High.",primary_contact_name:"Optional.",primary_contact_phone:"Optional.",medication_notes:"Medication schedule.",mobility_notes:"Mobility aids and restrictions.",preferred_language:"Communication preference.",check_in_schedule:"e.g. daily 09:00",emergency_notes:"Critical staff notes."};
  ht("formatRows",columns.map(function(col){return'<tr><td style="font-weight:700;font-size:12px;white-space:nowrap">'+col+'</td><td style="font-size:12px;color:#5a6a80">'+esc(fh[col]||"")+"</td></tr>";}).join(""));
  ht("settingsRelay",rRow(s.online?"green":"red","Robot Connection",s.online?"Connected &middot; "+new Date(s.lastSeen).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}):"Not connected")+rRow(s.camera?"green":"red","Camera Feed",s.camera?"Active":"No frame")+rRow("blue","Residents",res.length+" registered")+rRow("purple","Schedules",(s.scheduledRounds||[]).length+" configured")+rRow(s.lastSaved?"green":"yellow","Data Persistence",s.lastSaved?"Saved to disk "+timeAgo(s.lastSaved)+" &middot; Survives restart":"Not yet saved — make a change to trigger save"));
  var cb=gi("cameraBox");var nc=gi("noCamera");
  if(s.camera){if(cb)cb.style.display="block";if(nc)nc.style.display="none";updateCamera("/api/camera.jpg?t="+Date.now());}
  else{if(cb)cb.style.display="none";if(nc)nc.style.display="grid";}
}
function refresh(){get("/api/state").then(function(s){if(s&&Object.keys(s).length)renderAll(s);});}
setInterval(refresh,2000);setInterval(refreshQueue,4000);refresh();refreshQueue();loadUsers();
var _camFlip=false;
function updateCamera(src){
  var a=document.getElementById("camera"),b=document.getElementById("camera2");
  if(!a||!b)return;
  var next=_camFlip?a:b,prev=_camFlip?b:a;
  next.onload=function(){next.style.opacity="1";prev.style.opacity="0";};
  next.onerror=function(){next.style.opacity="0.3";};
  next.src=src;
  _camFlip=!_camFlip;
  var ts=document.getElementById("camTs");
  if(ts)ts.textContent=new Date().toLocaleTimeString([],{hour:"2-digit",minute:"2-digit",second:"2-digit"});
}
function camFs(){
  var b=document.getElementById("cameraBox");
  if(!b)return;
  if(b.requestFullscreen)b.requestFullscreen();
  else if(b.webkitRequestFullscreen)b.webkitRequestFullscreen();
}
setInterval(function(){
  var cb=document.getElementById("cameraBox");
  if(cb&&cb.style.display!=="none")updateCamera("/api/camera.jpg?t="+Date.now());
},1500);
document.addEventListener("click",function(e){
  var el=e.target;
  while(el&&el!==document.body){
    if(el.classList&&(el.classList.contains("btn")||el.classList.contains("tile")||el.classList.contains("pin")))break;
    el=el.parentNode;
  }
  if(!el||el===document.body)return;
  var rect=el.getBoundingClientRect();
  var size=Math.max(rect.width,rect.height)*2.2;
  var x=e.clientX-rect.left-size/2,y=e.clientY-rect.top-size/2;
  var rip=document.createElement("span");
  rip.className="ripple-el";
  rip.style.cssText="width:"+size+"px;height:"+size+"px;left:"+x+"px;top:"+y+"px;";
  el.appendChild(rip);
  setTimeout(function(){if(rip.parentNode)rip.parentNode.removeChild(rip);},580);
});
window.onerror=function(msg,src,line){notice('JS Error: '+msg+' (line '+line+')',false);return false;};
</script></body></html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  if (url.pathname === "/health") return sendJson(res, 200, { ok: true });

  if (url.pathname === "/login" && req.method === "GET") {
    if (currentUser(req) || isAdmin(req)) { res.writeHead(302, { location: "/", "cache-control": "no-store" }); return res.end(); }
    return sendText(res, 200, loginPage(), "text/html; charset=utf-8");
  }
  if (url.pathname === "/login" && req.method === "POST") {
    const form = parseForm(await readBody(req));
    const user = users.get(cleanText(form.username));
    if (!user || !verifyPassword(form.password, user.passwordHash)) {
      log("login_failed", { username: cleanText(form.username) });
      return sendText(res, 401, loginPage("Invalid username or password."), "text/html; charset=utf-8");
    }
    createSession(res, user.username); log("login", { username: user.username });
    res.writeHead(302, { location: "/", "cache-control": "no-store" }); return res.end();
  }
  if (url.pathname === "/logout") {
    const sid = parseCookies(req).nova_session; if (sid) sessions.delete(sid);
    res.writeHead(200, { "set-cookie": "nova_session=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0", "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    return res.end(logoutPage());
  }

  if (url.pathname.startsWith("/robot/")) {
    if (!isRobot(req)) return sendJson(res, 403, { ok: false, error: "bad robot token" });
    robot.online = true; robot.lastSeen = Date.now();
    if (url.pathname === "/robot/state" && req.method === "POST") {
      const body = JSON.parse((await readBody(req)) || "{}");
      robot.status = body.status || robot.status;
      robot.detection = body.detection || robot.detection;
      robot.people = Array.isArray(body.people) ? body.people : robot.people;
      robot.points = Array.isArray(body.points) ? body.points : robot.points;
      robot.care = body.care || robot.care;
      if (body.cameraJpegBase64) robot.cameraJpegBase64 = body.cameraJpegBase64;
      log("state", { people: robot.people.length, points: robot.points.length });
      return sendJson(res, 200, { ok: true });
    }
    if (url.pathname === "/robot/poll") return sendJson(res, 200, { commands: commandQueue.splice(0, 20) });
    if (url.pathname === "/robot/result" && req.method === "POST") {
      const data = JSON.parse((await readBody(req)) || "{}");
      const p = data.params || {};
      if (data.action === "resident_checkin" && p.residentId) { facility.checkIns[p.residentId] = Date.now(); persistData(); }
      if (data.action === "start_rounds" && Array.isArray(p.residents)) {
        p.residents.forEach(function(r) { if (r.id) facility.checkIns[r.id] = Date.now(); });
        roundHistory.unshift({ id: crypto.randomUUID(), name: p.scheduleName || "Manual Round", type: p.type || "checkin", trigger: p.scheduleName ? "schedule" : "manual", count: p.residents.length, at: Date.now() });
        if (roundHistory.length > 30) roundHistory.pop();
        persistData();
      }
      log("result", data); return sendJson(res, 200, { ok: true });
    }
  }

  if (!requireAdmin(req, res)) return;

  if (url.pathname === "/") return sendText(res, 200, page(currentUser(req) || users.get(ADMIN_USER)), "text/html; charset=utf-8");
  if (url.pathname === "/api/me") {
    const user = currentUser(req) || users.get(ADMIN_USER);
    return sendJson(res, 200, { ok: true, user: { username: user.username, role: user.role } });
  }
  if (url.pathname === "/api/state") {
    const stale = Date.now() - robot.lastSeen > 15000;
    return sendJson(res, 200, { ...robot, online: robot.online && !stale, care: mergedCare(), camera: !!robot.cameraJpegBase64, events, scheduledRounds, roundHistory, facility: { roundOrder: facility.roundOrder, checkIns: facility.checkIns }, lastSaved });
  }
  if (url.pathname === "/api/camera.jpg") {
    if (!robot.cameraJpegBase64) return sendText(res, 404, "No camera snapshot");
    const data = Buffer.from(robot.cameraJpegBase64, "base64");
    res.writeHead(200, { "content-type": "image/jpeg", "cache-control": "no-store", "content-length": data.length }); return res.end(data);
  }
  if (url.pathname === "/api/users" && req.method === "GET") {
    if (!requireRole(req, res, "admin")) return;
    return sendJson(res, 200, { ok: true, users: Array.from(users.values()).map(u => ({ username: u.username, role: u.role, createdAt: u.createdAt })) });
  }
  if (url.pathname === "/api/users" && req.method === "POST") {
    const actor = requireRole(req, res, "admin"); if (!actor) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const username = cleanText(body.username).toLowerCase(), password = String(body.password || "");
    const role = ["admin","operator","viewer"].includes(cleanText(body.role)) ? cleanText(body.role) : "operator";
    if (!/^[a-z0-9._-]{3,40}$/.test(username)) return sendJson(res, 400, { ok: false, error: "username must be 3-40 letters, numbers, dot, dash, or underscore" });
    if (password.length < 8) return sendJson(res, 400, { ok: false, error: "password must be at least 8 characters" });
    users.set(username, { username, role, passwordHash: passwordHash(password), createdAt: Date.now() });
    log("user_created", { username, role, actor: actor.username }); return sendJson(res, 200, { ok: true, user: { username, role } });
  }
  if (url.pathname === "/api/logo" && req.method === "POST") {
    const actor = requireRole(req, res, "admin"); if (!actor) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const dataUrl = String(body.dataUrl || "");
    if (!/^data:image\/(png|jpg|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(dataUrl)) return sendJson(res, 400, { ok: false, error: "Upload PNG, JPG, or WEBP." });
    if (Buffer.byteLength(dataUrl) > 7_000_000) return sendJson(res, 413, { ok: false, error: "Logo too large." });
    brandLogoDataUrl = dataUrl; persistData(); log("logo_updated", { actor: actor.username });
    return sendJson(res, 200, { ok: true, logo: brandLogoDataUrl });
  }
  if (url.pathname === "/templates/residents.csv") {
    const example = [residentColumns.join(","), 'R-204,"Mary Collins",204,Room 204,A,Assisted,"Sarah Collins","+1 555 0100","Morning medication at 09:00","Walker; avoid stairs",English,"daily 09:00","Call nurse if no response"'].join("\n");
    res.writeHead(200, { "content-type": "text/csv; charset=utf-8", "content-disposition": 'attachment; filename="zox-resident-import-template.csv"', "cache-control": "no-store" });
    return res.end(example);
  }
  if (url.pathname === "/api/residents" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const resident = toResident(JSON.parse((await readBody(req)) || "{}"));
    if (!resident) return sendJson(res, 400, { ok: false, error: "full_name and room are required" });
    const idx = facility.residents.findIndex(r => r.id === resident.id);
    if (idx >= 0) facility.residents[idx] = resident; else facility.residents.push(resident);
    facility.logs.push({ createdAt: Date.now(), title: "Resident saved", detail: `${resident.name} - ${resident.room}` });
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "upsert_resident", params: { id: resident.id, name: resident.name, room: resident.room, mapPoint: resident.mapPoint || resident.room, notes: [resident.medicationNotes, resident.mobilityNotes].filter(Boolean).join("; "), checkInPrompt: resident.checkInSchedule || `Hello ${resident.name}. I am checking in. Do you need anything?` } });
    persistData(); log("resident", { id: resident.id, name: resident.name }); return sendJson(res, 200, { ok: true, resident });
  }
  if (url.pathname === "/api/residents/import" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const rows = parseCsv(await readBody(req));
    if (rows.length < 2) return sendJson(res, 400, { ok: false, error: "CSV must have a header row and at least one resident" });
    const header = rows[0].map(v => cleanText(v).toLowerCase());
    const imported = [];
    rows.slice(1).forEach(cells => { const item = {}; header.forEach((k, i) => { item[k] = cells[i] || ""; }); const r = toResident(item); if (r) imported.push(r); });
    imported.forEach(r => { const idx = facility.residents.findIndex(x => x.id === r.id); if (idx >= 0) facility.residents[idx] = r; else facility.residents.push(r); });
    facility.logs.push({ createdAt: Date.now(), title: "Resident import", detail: `${imported.length} residents imported` });
    persistData(); log("resident_import", { count: imported.length }); return sendJson(res, 200, { ok: true, count: imported.length });
  }
  const resDeleteMatch = url.pathname.match(/^\/api\/residents\/([^/]+)$/);
  if (resDeleteMatch && req.method === "DELETE") {
    if (!requireRole(req, res, "operator")) return;
    const id = decodeURIComponent(resDeleteMatch[1]);
    const idx = facility.residents.findIndex(r => r.id === id);
    if (idx < 0) return sendJson(res, 404, { ok: false, error: "resident not found" });
    const removed = facility.residents.splice(idx, 1)[0];
    facility.logs.push({ createdAt: Date.now(), title: "Resident removed", detail: `${removed.name} — ${removed.room}` });
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "delete_resident", params: { id: removed.id } });
    persistData(); log("resident_deleted", { id: removed.id, name: removed.name }); return sendJson(res, 200, { ok: true });
  }
  if (url.pathname === "/api/alerts" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const alert = { id: crypto.randomUUID(), createdAt: Date.now(), priority: cleanText(body.priority) || "urgent", room: cleanText(body.room), message: cleanText(body.message) || "Staff assistance requested." };
    facility.alerts.unshift(alert);
    facility.logs.push({ createdAt: Date.now(), title: "Alert created", detail: `${alert.room || "Facility"} - ${alert.message}` });
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "staff_alert", params: alert });
    persistData(); log("alert", alert); return sendJson(res, 200, { ok: true, alert });
  }
  const alertDismissMatch = url.pathname.match(/^\/api\/alerts\/([^/]+)\/dismiss$/);
  if (alertDismissMatch && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const alertId = decodeURIComponent(alertDismissMatch[1]);
    const idx = facility.alerts.findIndex(a => a.id === alertId);
    if (idx >= 0) facility.alerts.splice(idx, 1);
    facility.logs.push({ createdAt: Date.now(), title: "Alert dismissed", detail: alertId });
    persistData(); log("alert_dismissed", { id: alertId }); return sendJson(res, 200, { ok: true });
  }
  if (url.pathname === "/api/command" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const command = { id: crypto.randomUUID(), at: Date.now(), action: cleanText(body.action), params: body.params || {} };
    if (!command.action) return sendJson(res, 400, { ok: false, error: "action is required" });
    commandQueue.push(command);
    facility.logs.push({ createdAt: Date.now(), title: "Command queued", detail: `${command.action} ${JSON.stringify(command.params)}` });
    log("command", command); return sendJson(res, 200, { ok: true, command });
  }
  if (url.pathname === "/api/schedules" && req.method === "GET") {
    return sendJson(res, 200, { ok: true, schedules: scheduledRounds });
  }
  if (url.pathname === "/api/schedules" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const name = cleanText(body.name); if (!name) return sendJson(res, 400, { ok: false, error: "name required" });
    const schedule = { id: crypto.randomUUID(), name, type: ["checkin","medication","full"].includes(body.type) ? body.type : "checkin", time: /^\d{2}:\d{2}$/.test(body.time || "") ? body.time : "09:00", days: body.days || "daily", residentIds: Array.isArray(body.residentIds) ? body.residentIds : [], enabled: true, createdAt: Date.now(), lastRun: null };
    scheduledRounds.push(schedule); persistData(); log("schedule_created", { name }); return sendJson(res, 200, { ok: true, schedule });
  }
  const schedDeleteMatch = url.pathname.match(/^\/api\/schedules\/([^/]+)$/);
  if (schedDeleteMatch && req.method === "DELETE") {
    if (!requireRole(req, res, "operator")) return;
    const id = decodeURIComponent(schedDeleteMatch[1]);
    const idx = scheduledRounds.findIndex(s => s.id === id);
    if (idx < 0) return sendJson(res, 404, { ok: false, error: "schedule not found" });
    scheduledRounds.splice(idx, 1); persistData(); return sendJson(res, 200, { ok: true });
  }
  const schedToggleMatch = url.pathname.match(/^\/api\/schedules\/([^/]+)\/toggle$/);
  if (schedToggleMatch && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const id = decodeURIComponent(schedToggleMatch[1]);
    const s = scheduledRounds.find(s => s.id === id);
    if (!s) return sendJson(res, 404, { ok: false, error: "schedule not found" });
    s.enabled = !s.enabled; persistData(); return sendJson(res, 200, { ok: true, enabled: s.enabled });
  }
  if (url.pathname === "/api/round-order" && req.method === "PUT") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    if (Array.isArray(body.order)) { facility.roundOrder = body.order; persistData(); }
    return sendJson(res, 200, { ok: true });
  }
  if (url.pathname === "/api/queue" && req.method === "GET") {
    return sendJson(res, 200, { ok: true, queue: commandQueue.map(c => ({ id: c.id, action: c.action, at: c.at })), count: commandQueue.length });
  }
  if (url.pathname === "/api/queue" && req.method === "DELETE") {
    if (!requireRole(req, res, "admin")) return;
    commandQueue.splice(0, commandQueue.length); log("queue_cleared", {}); return sendJson(res, 200, { ok: true });
  }
  if (url.pathname === "/api/export" && req.method === "GET") {
    if (!requireRole(req, res, "admin")) return;
    const snap = JSON.stringify({ residents: facility.residents, reminders: facility.reminders, alerts: facility.alerts, scheduledRounds, roundHistory, roundOrder: facility.roundOrder, checkIns: facility.checkIns }, null, 2);
    const fname = "nova-backup-" + new Date().toISOString().slice(0, 10) + ".json";
    res.writeHead(200, { "content-type": "application/json; charset=utf-8", "content-disposition": `attachment; filename="${fname}"`, "cache-control": "no-store" });
    return res.end(snap);
  }
  sendJson(res, 404, { ok: false, error: "not found" });
});

server.listen(PORT, () => console.log(`Nova cloud relay listening on ${PORT}`));
