const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 3000);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = (() => {
  if (process.env.ADMIN_PASS) return process.env.ADMIN_PASS;
  const generated = crypto.randomBytes(16).toString("hex");
  console.warn(`[SECURITY] ADMIN_PASS env var not set. Generated one-time password: ${generated}`);
  console.warn("[SECURITY] Set ADMIN_PASS environment variable to avoid regeneration on each restart.");
  return generated;
})();
const ROBOT_TOKEN = (() => {
  if (process.env.ROBOT_TOKEN) return process.env.ROBOT_TOKEN;
  const generated = crypto.randomBytes(16).toString("hex");
  console.warn(`[SECURITY] ROBOT_TOKEN env var not set. Generated one-time token: ${generated}`);
  console.warn("[SECURITY] Set ROBOT_TOKEN environment variable and update the Android app to match.");
  return generated;
})();
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");
const SESSION_DAYS = Math.max(1, Number(process.env.SESSION_DAYS || 180));
const SESSION_MAX_AGE = Math.floor(SESSION_DAYS * 24 * 60 * 60);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, ".data");
const AUTH_STORE_FILE = process.env.AUTH_STORE_FILE || path.join(DATA_DIR, "auth-store.json");

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
const loginAttempts = new Map();
const detectionHistory = [];
let lastDetectionLogAt = 0;
let brandLogoDataUrl = process.env.BRAND_LOGO_DATA_URL || "";

const LOGIN_MAX_ATTEMPTS = 5;
const LOGIN_WINDOW_MS = 5 * 60 * 1000;
const LOGIN_LOCKOUT_MS = 15 * 60 * 1000;

function isLoginRateLimited(ip) {
  const now = Date.now();
  const record = loginAttempts.get(ip) || { count: 0, firstAt: now, lockedUntil: 0 };
  if (record.lockedUntil > now) return true;
  return false;
}

function recordLoginFailure(ip) {
  const now = Date.now();
  const record = loginAttempts.get(ip) || { count: 0, firstAt: now, lockedUntil: 0 };
  if (now - record.firstAt > LOGIN_WINDOW_MS) {
    record.count = 1;
    record.firstAt = now;
  } else {
    record.count += 1;
  }
  if (record.count >= LOGIN_MAX_ATTEMPTS) record.lockedUntil = now + LOGIN_LOCKOUT_MS;
  loginAttempts.set(ip, record);
}

function clearLoginFailures(ip) {
  loginAttempts.delete(ip);
}

function escapeHtml(value) {
  return String(value || "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[ch]));
}

function ensureDataDir() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

function loadAuthStore() {
  try {
    if (!fs.existsSync(AUTH_STORE_FILE)) return;
    const store = JSON.parse(fs.readFileSync(AUTH_STORE_FILE, "utf8"));
    if (Array.isArray(store.users)) {
      users.clear();
      for (const user of store.users) {
        if (!user?.username || !user?.passwordHash) continue;
        const role = ["admin", "operator", "viewer"].includes(user.role) ? user.role : "operator";
        users.set(user.username, { username: user.username, role, passwordHash: user.passwordHash, createdAt: user.createdAt || Date.now() });
      }
    }
    if (Array.isArray(store.sessions)) {
      sessions.clear();
      for (const [sid, session] of store.sessions) {
        if (!sid || !session?.username || session.expiresAt < Date.now()) continue;
        sessions.set(sid, session);
      }
    }
    if (!process.env.BRAND_LOGO_DATA_URL && store.brandLogoDataUrl) brandLogoDataUrl = store.brandLogoDataUrl;
    if (store.facility && typeof store.facility === "object") {
      facility.residents = Array.isArray(store.facility.residents) ? store.facility.residents : [];
      facility.reminders = Array.isArray(store.facility.reminders) ? store.facility.reminders : [];
      facility.alerts = Array.isArray(store.facility.alerts) ? store.facility.alerts : [];
      facility.logs = Array.isArray(store.facility.logs) ? store.facility.logs : [];
    }
  } catch (error) {
    console.error("Could not load auth store", error.message);
  }
}

function saveAuthStore() {
  try {
    ensureDataDir();
    const store = {
      users: Array.from(users.values()).map((user) => ({
        username: user.username,
        role: user.role,
        passwordHash: user.passwordHash,
        createdAt: user.createdAt,
      })),
      sessions: Array.from(sessions.entries()).filter(([, session]) => session.expiresAt >= Date.now()),
      brandLogoDataUrl,
      facility,
      savedAt: Date.now(),
    };
    fs.writeFileSync(AUTH_STORE_FILE, JSON.stringify(store, null, 2));
  } catch (error) {
    console.error("Could not save auth store", error.message);
  }
}

function ensureDefaultAdmin() {
  if (users.has(ADMIN_USER)) return;
  users.set(ADMIN_USER, {
    username: ADMIN_USER,
    role: "admin",
    passwordHash: passwordHash(ADMIN_PASS),
    createdAt: Date.now(),
  });
}

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
loadAuthStore();
ensureDefaultAdmin();
saveAuthStore();

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
    if (sid) {
      sessions.delete(sid);
      saveAuthStore();
    }
    return null;
  }
  return users.get(session.username) || null;
}

function createSession(res, username) {
  const sid = crypto.createHmac("sha256", SESSION_SECRET).update(`${username}:${crypto.randomUUID()}`).digest("hex");
  sessions.set(sid, { username, createdAt: Date.now(), expiresAt: Date.now() + SESSION_MAX_AGE * 1000 });
  saveAuthStore();
  res.setHeader("set-cookie", [
    `nova_session=${encodeURIComponent(sid)}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=${SESSION_MAX_AGE}`,
    `nova_last_user=${encodeURIComponent(username)}; Secure; SameSite=Strict; Path=/; Max-Age=${SESSION_MAX_AGE}`,
  ]);
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

function loginPage(error = "", username = "") {
  const safeUser = escapeHtml(username || ADMIN_USER);
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>ZOX Robotics Sign In</title><style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0,#0d5790,#05152b 55%,#020814);font-family:Inter,system-ui,Segoe UI,sans-serif;color:white}.card{width:min(430px,92vw);background:#ffffff10;border:1px solid #ffffff26;border-radius:24px;padding:28px;box-shadow:0 24px 70px #0008}.logo{width:96px;height:96px;border-radius:26px;margin:0 auto 16px;background:#06172e;border:2px solid #10c6e7;display:grid;place-items:center;overflow:hidden}.logo img{width:100%;height:100%;object-fit:cover}.logo span{color:#1bd6ee;font-size:30px;font-weight:950}.tag{text-align:center;color:#31d7ef;font-size:11px;letter-spacing:2.4px;font-weight:900}.field{width:100%;border:1px solid #ffffff2e;background:#ffffff14;color:white;border-radius:14px;padding:14px;margin:8px 0;font:inherit}.btn{width:100%;border:0;border-radius:14px;background:#12bee5;color:#03162d;padding:14px;font-weight:950;margin-top:12px;cursor:pointer}.err{background:#ff4d4d26;color:#ffd6d6;border:1px solid #ff8a8a55;padding:10px;border-radius:12px;margin:12px 0}.muted{color:#bed0e3;font-size:13px;text-align:center}</style></head><body><form class="card" method="post" action="/login">
<div class="logo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX Robotics">` : "<span>ZOX</span>"}</div><div class="tag">SMART ROBOTS. BETTER CARE.</div><h1 style="text-align:center;margin:14px 0 6px">Care Cloud Sign In</h1><p class="muted">Authorized clinic staff only.</p>${error ? `<div class="err">${escapeHtml(error)}</div>` : ""}
<input class="field" name="username" placeholder="Username" autocomplete="username" value="${safeUser}" autofocus><input class="field" name="password" placeholder="Password" type="password" autocomplete="current-password"><button class="btn">Sign In</button><p class="muted">This trusted device stays signed in for ${SESSION_DAYS} days. Passwords are stored only as secure hashes.</p></form></body></html>`;
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

function facilityLog(title, detail, status = "OK") {
  facility.logs.unshift({ createdAt: Date.now(), title, detail, status });
  while (facility.logs.length > 250) facility.logs.pop();
}

function titleForAction(action) {
  return {
    start_rounds: "Care round requested",
    resident_checkin: "Resident check-in requested",
    med_reminder: "Medication reminder requested",
    staff_alert: "Staff alert requested",
    visitor_guide: "Visitor guide requested",
    message: "Message delivery requested",
    camera_start: "Camera opened from cloud",
    camera_stop: "Camera closed from cloud",
    security_start: "Detection watch requested",
    security_stop: "Detection watch stopped",
    follow: "Follow requested",
    door_follow: "Door follow requested",
    charge: "Charge requested",
    stop: "Emergency stop requested",
  }[action] || `Command requested: ${action}`;
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

function careMetrics(care = mergedCare()) {
  const logs = care.logs || [];
  const today = new Date().toDateString();
  const todaysLogs = logs.filter((l) => new Date(l.createdAt || Date.now()).toDateString() === today);
  return {
    tasksToday: todaysLogs.length,
    residentsChecked: todaysLogs.filter((l) => /check/i.test(l.title || l.type || "")).length,
    remindersDelivered: todaysLogs.filter((l) => /reminder|medication/i.test(l.title || l.type || "")).length,
    alertsOpened: (care.alerts || []).length,
    averageResponseTime: todaysLogs.length ? "Live" : "--",
    currentActivity: robot.status?.status || "Waiting for Nova",
  };
}

function page(user = users.get(ADMIN_USER)) {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZOX Robotics Care Cloud</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f8fb;color:#101a33;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}.app{display:grid;grid-template-columns:260px 1fr;min-height:100vh}.side{background:linear-gradient(180deg,#051b34,#092544);color:white;padding:22px 16px;display:flex;flex-direction:column;gap:18px}.brand{display:flex;gap:12px;align-items:center}.logo{width:64px;height:64px;border-radius:16px;background:radial-gradient(circle at 50% 40%,#123963,#06172e 62%);border:2px solid #10c6e7;box-shadow:0 0 22px #10c6e755;display:grid;place-items:center;color:#1bd6ee;font-weight:950;font-size:22px;letter-spacing:-1px;overflow:hidden}.logo img{width:100%;height:100%;object-fit:cover}.brand b{font-size:20px}.tag{color:#36d7ee;font-size:10px;letter-spacing:2.2px;font-weight:900}.muted{color:#728198;font-size:13px}.side .muted{color:#b7c6d8}.nav{display:grid;gap:7px}.nav a{padding:13px 14px;border-radius:11px;color:white;text-decoration:none;cursor:pointer;font-weight:760}.nav a.active,.nav a:hover{background:#1d66ca}.sidebox{margin-top:auto;background:#ffffff10;border:1px solid #ffffff1f;border-radius:16px;padding:16px}.top{height:78px;background:white;border-bottom:1px solid #dfe7f1;display:flex;align-items:center;justify-content:space-between;padding:0 26px}.top h1{margin:0;font-size:24px}.topRight{display:flex;gap:10px;align-items:center;flex-wrap:wrap;justify-content:flex-end}.content{padding:18px}.view{display:none}.view.active{display:block}.grid5{display:grid;grid-template-columns:repeat(5,1fr);gap:14px}.grid3{display:grid;grid-template-columns:1.2fr 1fr 1fr;gap:14px;margin-top:14px}.two{display:grid;grid-template-columns:1fr 1fr;gap:14px}.card{background:white;border:1px solid #dfe7f1;border-radius:16px;padding:16px;box-shadow:0 10px 28px #3451a012}.card h2{font-size:16px;margin:0 0 12px}.tile{border:0;border-radius:18px;min-height:145px;background:white;color:#08142f;box-shadow:0 12px 32px #2f4d7a18;font-weight:900;font-size:18px;cursor:pointer;padding:20px 12px}.tile span{display:grid;place-items:center;width:64px;height:64px;border-radius:50%;margin:0 auto 14px;color:white;font-size:22px}.green{background:#2f9e57}.blue{background:#2374e1}.yellow{background:#f2b51e}.red{background:#e95050}.purple{background:#8a55de}.cyan{background:#10bcd7}.status{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}.stat{border:1px solid #e1e9f2;border-radius:14px;padding:14px}.stat b{font-size:26px;display:block}.pill{border-radius:999px;padding:5px 9px;font-size:12px;font-weight:850;display:inline-block}.ok{background:#e5f7e9;color:#238044}.bad{background:#ffe6e6;color:#c93131}.warn{background:#fff2d9;color:#b06a00}.low{background:#e8f1ff;color:#1e67c9}.row{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;border-bottom:1px solid #edf2f7;padding:11px 0}.dot{width:42px;height:42px;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900}.cmd{border:1px solid #d7e1ee;background:white;color:#14213d;border-radius:999px;padding:11px 16px;font-weight:850;cursor:pointer;margin:4px;text-decoration:none;display:inline-block}.cmd.primary{background:#2374e1;color:white;border-color:#2374e1}.cmd.danger{background:#e94d4d;color:white;border-color:#e94d4d}.cmd:disabled{opacity:.45;cursor:not-allowed}.field{width:100%;border:1px solid #d7e1ee;border-radius:12px;padding:12px;margin:6px 0;font:inherit}.map{height:280px;border-radius:14px;background:linear-gradient(90deg,#dbe8f8 1px,transparent 1px),linear-gradient(#dbe8f8 1px,transparent 1px),#f1f6fd;background-size:42px 42px;border:1px solid #dce7f4;position:relative;overflow:hidden}.map.empty,.empty{display:grid;place-items:center;color:#667895;text-align:center;min-height:110px;border:1px dashed #cbd8e8;border-radius:14px;background:#f9fbfe;padding:18px}.room{position:absolute;border:2px solid #cbd9ea;border-radius:9px;padding:13px;color:#52627a;background:#ffffffbb}.pin{position:absolute;width:34px;height:34px;border:0;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900;cursor:pointer;box-shadow:0 8px 20px #1f4f8c35;z-index:3}.robotpin{position:absolute;width:42px;height:42px;border-radius:50%;display:grid;place-items:center;background:#061b34;color:#19d7ef;border:3px solid white;box-shadow:0 0 0 5px #18c9ec3d,0 10px 24px #05224155;font-weight:950;z-index:5}.residentpin,.alertpin{position:absolute;transform:translate(14px,22px);font-size:11px;font-weight:900;border-radius:999px;padding:4px 8px;background:white;border:1px solid #d7e1ee;box-shadow:0 5px 14px #2342}.alertpin{background:#ffe6e6;color:#b82121;border-color:#ffb3b3}.pathsvg{position:absolute;inset:0;z-index:1;pointer-events:none}.mapnote{position:absolute;left:10px;bottom:10px;background:#ffffffd9;border:1px solid #d7e1ee;border-radius:999px;padding:6px 10px;color:#52627a;font-size:12px;font-weight:800;z-index:6}.camera{display:none;margin-top:12px}.camera img{width:100%;max-height:360px;object-fit:contain;background:#071426;border-radius:12px}.table{width:100%;border-collapse:collapse}.table td,.table th{text-align:left;padding:12px;border-bottom:1px solid #edf2f7;vertical-align:top}.small{font-size:12px}.toast{position:fixed;right:18px;bottom:18px;background:#071b34;color:white;padding:12px 16px;border-radius:12px;box-shadow:0 12px 28px #0003;display:none}.logoPreview{width:132px;height:132px;border-radius:24px;border:2px solid #10c6e7;background:#06172e;object-fit:cover;display:block;margin:8px 0}@media(max-width:1000px){.app{grid-template-columns:1fr}.side{display:none}.top{height:auto;padding:16px;align-items:flex-start;flex-direction:column}.grid5,.grid3,.status,.two{grid-template-columns:1fr}.content{padding:12px}}
</style></head><body><div class="app">
<aside class="side"><div class="brand"><div class="logo" id="sideLogo">${brandLogoDataUrl ? `<img src="${brandLogoDataUrl}" alt="ZOX Robotics">` : "ZOX"}</div><div><b>ZOX Robotics</b><br><span class="tag">SMART ROBOTS. BETTER CARE.</span></div></div><nav class="nav">
<a class="active" onclick="switchView('command',this)">Command</a><a onclick="switchView('robots',this)">Robot Feeds</a><a onclick="switchView('rounds',this)">Rounds</a><a onclick="switchView('residents',this)">Residents</a><a onclick="switchView('alerts',this)">Alerts</a><a onclick="switchView('map',this)">Map</a><a onclick="switchView('logs',this)">Logs</a><a onclick="switchView('settings',this)">Settings</a>
</nav><div class="sidebox"><b>Nova Online</b><span id="sideOnline" style="float:right">0/1</span><p class="muted" id="sideHealth">Waiting for real robot feed</p><button class="cmd primary" onclick="cmd('camera_start')">Open Camera</button></div></aside>
<main><header class="top"><div><h1 id="pageTitle">Clinic Command Center</h1><div class="muted" id="pageSubtitle">Every number here is live from Nova or your registered facility data.</div></div><div class="topRight"><span class="pill low">${cleanText(user?.username || ADMIN_USER)} · ${cleanText(user?.role || "admin")}</span><span class="pill" id="onlinePill">Offline</span> <span class="pill bad" id="alertCount">0 alerts</span><a class="cmd" href="/logout">Logout</a></div></header>
<section class="content view active" id="view-command">
<div class="grid5"><button class="tile" onclick="cmd('start_rounds')"><span class="green">R</span>Start Rounds</button><button class="tile" onclick="checkInSelected()"><span class="blue">C</span>Check-In</button><button class="tile" onclick="medSelected()"><span class="yellow">M</span>Medication</button><button class="tile" onclick="staffAlert()"><span class="red">!</span>Staff Alert</button><button class="tile" onclick="guideSelected()"><span class="purple">G</span>Guide</button></div>
<div class="card" style="margin-top:14px"><div class="status"><div class="stat"><span class="muted">Robot</span><b id="statRobot">0</b></div><div class="stat"><span class="muted">Residents</span><b id="statResidents">0</b></div><div class="stat"><span class="muted">Map Points</span><b id="statPoints">0</b></div><div class="stat"><span class="muted">People Seen</span><b id="statPeople">0</b></div><div class="stat"><span class="muted">Camera</span><b id="statCamera">Off</b></div></div><div class="status" style="margin-top:10px"><div class="stat"><span class="muted">Tasks Today</span><b id="statTasks">0</b></div><div class="stat"><span class="muted">Checked</span><b id="statChecked">0</b></div><div class="stat"><span class="muted">Reminders</span><b id="statReminders">0</b></div><div class="stat"><span class="muted">Avg Response</span><b id="statResponse">--</b></div><div class="stat"><span class="muted">Task</span><b id="statTaskStage">Ready</b></div></div></div>
<div class="grid3"><div class="card"><h2>Robot</h2><div id="robotBox"></div><button class="cmd danger" onclick="cmd('stop')">Stop</button><button class="cmd" onclick="cmd('charge')">Charge</button><button class="cmd" onclick="cmd('security_start')">Detect</button><button class="cmd" onclick="cmd('security_stop')">End Detect</button></div><div class="card"><h2>Residents</h2><div id="residentBox"></div></div><div class="card"><h2>Alerts</h2><div id="alertBox"></div></div></div>
<div class="grid3"><div class="card"><h2>People Detection</h2><div id="peopleBox"></div></div><div class="card"><h2>Map</h2><div class="map" id="mapBox"></div></div><div class="card"><h2>Camera</h2><button class="cmd primary" onclick="cmd('camera_start')">Open</button><button class="cmd" onclick="cmd('camera_stop')">Close</button><div class="camera" id="cameraBox"><img id="camera" alt="Nova camera feed"><p class="muted" id="cameraNote"></p></div><div id="noCamera" class="empty">No live camera frame from Nova yet.</div></div></div>
</section>
<section class="content view" id="view-robots"><div class="two"><div class="card"><h2>Real Robot Telemetry</h2><div id="robotsFleet"></div><button class="cmd danger" onclick="cmd('stop')">Emergency Stop</button><button class="cmd" onclick="cmd('charge')">Go Charge</button><button class="cmd primary" onclick="cmd('camera_start')">Open Camera</button></div><div class="card"><h2>Detection Feed</h2><div id="robotDiagnostics"></div><pre class="small" id="rawDetection"></pre></div></div><div class="two" style="margin-top:14px"><div class="card"><h2>Detection Heatmap</h2><div class="map" id="heatmapBox"></div></div><div class="card"><h2>Robot Health Panel</h2><div id="healthPanel"></div></div></div></section>
<section class="content view" id="view-rounds"><div class="two"><div class="card"><h2>Round Launcher</h2><button class="tile" onclick="cmd('start_rounds')"><span class="green">R</span>Start Care Round</button><div id="roundResidents"></div></div><div class="card"><h2>Round Schedule</h2><div id="roundSchedule"></div></div></div></section>
<section class="content view" id="view-residents"><div class="two"><div class="card"><h2>Register Residents</h2><input class="field" id="manualName" placeholder="Full name"><input class="field" id="manualRoom" placeholder="Room"><input class="field" id="manualWing" placeholder="Wing"><input class="field" id="manualCare" placeholder="Care level"><input class="field" id="manualPhone" placeholder="Primary contact phone"><textarea class="field" id="manualNotes" rows="3" placeholder="Medication, mobility, emergency notes"></textarea><button class="cmd primary" onclick="addResident()">Add Resident</button><a class="cmd" href="/templates/residents.csv">Download Excel Template</a><input class="field" type="file" id="residentFile" accept=".csv,text/csv"><button class="cmd" onclick="uploadResidents()">Upload Excel CSV</button><p class="muted">Open the downloaded CSV in Excel, fill one resident per row, save as CSV, then upload here.</p></div><div class="card"><h2>Resident Directory</h2><div id="residentDirectory"></div><h2>Resident Actions</h2><select class="field" id="residentSelect"></select><button class="cmd primary" onclick="checkInSelected()">Check In</button><button class="cmd" onclick="medSelected()">Medication</button><button class="cmd danger" onclick="cmd('staff_alert',{priority:'urgent',residentId:residentSelect.value,message:'Assistance requested for resident.'})">Alert Staff</button></div></div></section>
<section class="content view" id="view-alerts"><div class="two"><div class="card"><h2>Alert Center</h2><div id="alertCenter"></div></div><div class="card"><h2>Create Alert</h2><input class="field" id="alertRoom" placeholder="Room or location"><textarea class="field" id="alertMessage" rows="4" placeholder="What happened?"></textarea><button class="cmd danger" onclick="createAlert()">Send Urgent Alert</button></div></div></section>
<section class="content view" id="view-map"><div class="two"><div class="card"><h2>Nova Map Points</h2><div class="map" id="fullMapBox"></div></div><div class="card"><h2>Destination Control</h2><select class="field" id="pointSelect"></select><button class="cmd primary" onclick="guideSelected()">Guide Visitor</button><textarea class="field" id="messageText" rows="4" placeholder="Message Nova should deliver"></textarea><button class="cmd" onclick="sendMessage()">Send Message To Point</button></div></div></section>
<section class="content view" id="view-logs"><div class="card"><h2>Operations Log</h2><table class="table"><thead><tr><th>Time</th><th>Event</th><th>Detail</th><th>Status</th></tr></thead><tbody id="opsLog"></tbody></table></div></section>
<section class="content view" id="view-settings"><div class="two"><div class="card"><h2>Cloud Relay</h2><div id="settingsRelay"></div><button class="cmd primary" onclick="cmd('camera_start')">Test Camera Command</button><button class="cmd" onclick="cmd('security_start')">Test Detection Command</button><h2>Brand Logo</h2><img class="logoPreview" id="logoPreview" src="${brandLogoDataUrl || ""}" alt="ZOX Robotics logo"><input class="field" id="logoFile" type="file" accept="image/png,image/jpeg,image/webp"><button class="cmd primary" onclick="uploadLogo()">Use Uploaded Logo</button><p class="muted">Upload the exact ZOX logo image here. It will replace the cloud logo immediately.</p></div><div class="card"><h2>User Access</h2><input class="field" id="newUser" placeholder="Username"><input class="field" id="newPass" type="password" placeholder="Temporary password"><select class="field" id="newRole"><option value="operator">operator</option><option value="viewer">viewer</option><option value="admin">admin</option></select><button class="cmd primary" onclick="addUser()">Add User</button><div id="userList"></div><h2>Excel Format</h2><table class="table small"><tbody id="formatRows"></tbody></table><a class="cmd" href="/templates/residents.csv">Download Template</a></div></div></section>
</main></div><div class="toast" id="toast"></div><script>
const titles={command:["Clinic Command Center","Every number here is live from Nova or your registered facility data."],robots:["Robot Feeds","Telemetry, camera, people detection, SDK and map feed from Nova."],rounds:["Rounds","Launch care rounds and check registered residents."],residents:["Residents","Register by form or Excel CSV upload, then command Nova by resident."],alerts:["Alerts","Create urgent staff alerts and monitor real robot/facility events."],map:["Map","Uses only real map points reported by Nova."],logs:["Logs","Cloud commands, robot state pushes, robot results and facility actions."],settings:["Settings","Relay health, safety controls and resident import format."]};
const columns=${JSON.stringify(residentColumns)};
const currentUser=${JSON.stringify({ username: user?.username || ADMIN_USER, role: user?.role || "admin" })};
async function get(p){const r=await fetch(p,{cache:"no-store"});return r.json()}
async function post(p,body){const r=await fetch(p,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});return r.json()}
async function cmd(action,params={}){const out=await post("/api/command",{action,params});notice(out.ok?"Command queued":"Command failed");refresh()}
function esc(v){return String(v||"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]))}
function notice(t){toast.textContent=t;toast.style.display="block";setTimeout(()=>toast.style.display="none",2200)}
function switchView(name,el){document.querySelectorAll(".view").forEach(v=>v.classList.remove("active"));document.getElementById("view-"+name).classList.add("active");document.querySelectorAll(".nav a").forEach(a=>a.classList.remove("active"));if(el)el.classList.add("active");pageTitle.textContent=titles[name][0];pageSubtitle.textContent=titles[name][1]}
function row(color,title,detail,right=""){return '<div class="row"><div class="dot '+color+'">'+(esc(title)[0]||"?")+'</div><div><b>'+esc(title)+'</b><br><span class="muted">'+esc(detail)+'</span></div><div>'+right+'</div></div>'}
function empty(text){return '<div class="empty">'+esc(text)+'</div>'}
function residentById(id){return (window.state?.care?.residents||[]).find(r=>r.id===id)||null}
function residentParams(id){const r=residentById(id||residentSelect.value);return r?{residentId:r.id,residentName:r.name,room:r.room,mapPoint:r.mapPoint||r.room,notes:r.medicationNotes||r.mobilityNotes||r.emergencyNotes||""}:{residentId:id||""}}
function selectedResident(){return residentSelect.value||""}
function selectedPoint(){return pointSelect.value||""}
function checkInResident(id){if(!id)return notice("Register/select a resident first."); cmd("resident_checkin",residentParams(id))}
function medResident(id){if(!id)return notice("Register/select a resident first."); cmd("med_reminder",residentParams(id))}
function checkInSelected(){checkInResident(selectedResident())}
function medSelected(){medResident(selectedResident())}
function guideSelected(){const p=selectedPoint(); if(!p)return notice("Nova has not sent map points yet."); cmd("visitor_guide",{destination:p})}
function staffAlert(){cmd("staff_alert",{priority:"urgent",message:"Staff assistance requested."})}
function sendMessage(){const p=selectedPoint(); if(!p)return notice("Nova has not sent map points yet."); cmd("message",{destination:p,message:messageText.value||"Please meet Nova at this location."})}
async function addResident(){const resident={full_name:manualName.value,room:manualRoom.value,wing:manualWing.value,care_level:manualCare.value,primary_contact_phone:manualPhone.value,medication_notes:manualNotes.value};const out=await post("/api/residents",resident);notice(out.ok?"Resident registered":out.error||"Could not add resident");refresh()}
async function createAlert(){const out=await post("/api/alerts",{priority:"urgent",room:alertRoom.value,message:alertMessage.value||"Staff assistance requested."});notice(out.ok?"Alert created":out.error||"Could not create alert");refresh()}
async function uploadResidents(){const file=residentFile.files[0];if(!file)return notice("Choose the completed CSV first.");const text=await file.text();const r=await fetch("/api/residents/import",{method:"POST",headers:{"content-type":"text/csv"},body:text});const out=await r.json();notice(out.ok?("Imported "+out.count+" residents"):(out.error||"Import failed"));residentFile.value="";refresh()}
async function uploadLogo(){const file=logoFile.files[0];if(!file)return notice("Choose the exact ZOX logo image first.");if(file.size>5*1024*1024)return notice("Logo is too large. Use a PNG/JPEG under 5 MB.");const reader=new FileReader();reader.onload=async()=>{const out=await post("/api/logo",{dataUrl:reader.result});notice(out.ok?"Logo updated":(out.error||"Logo update failed"));if(out.logo){logoPreview.src=out.logo;sideLogo.innerHTML='<img src="'+out.logo+'" alt="ZOX Robotics">'}logoFile.value=""};reader.onerror=()=>notice("Could not read that image file.");reader.readAsDataURL(file)}
async function addUser(){const out=await post("/api/users",{username:newUser.value,password:newPass.value,role:newRole.value});notice(out.ok?"User added":out.error||"Could not add user");newUser.value="";newPass.value="";loadUsers()}
async function loadUsers(){const out=await get("/api/users");userList.innerHTML=out.users?out.users.map(u=>row(u.role==="admin"?"blue":"green",u.username,u.role+" · created "+new Date(u.createdAt).toLocaleDateString())).join(""):empty(out.error||"User list unavailable")}
function renderMap(target,points){const pose=window.state?.status?.robotPose;const care=window.state?.care||{};const residents=care.residents||[];const alerts=care.alerts||[];const real=points.filter(p=>p.hasCoordinates&&Number.isFinite(Number(p.x))&&Number.isFinite(Number(p.y)));if(!points.length){target.className="map empty";target.innerHTML="No real Nova map points received yet.";return}target.className="map";const coordItems=[...real];if(pose&&Number.isFinite(Number(pose.x))&&Number.isFinite(Number(pose.y)))coordItems.push({x:pose.x,y:pose.y});const xs=coordItems.map(p=>Number(p.x));const ys=coordItems.map(p=>Number(p.y));const minX=xs.length?Math.min(...xs):0,maxX=xs.length?Math.max(...xs):1,minY=ys.length?Math.min(...ys):0,maxY=ys.length?Math.max(...ys):1;const spanX=Math.max(maxX-minX,0.001),spanY=Math.max(maxY-minY,0.001);const place=(x,y)=>({left:8+((Number(x)-minX)/spanX)*84,top:84-((Number(y)-minY)/spanY)*76});const posForPoint=(name)=>{const p=real.find(x=>String(x.name).toLowerCase()===String(name||"").toLowerCase())||real.find(x=>String(name||"").toLowerCase().includes(String(x.name).toLowerCase())||String(x.name).toLowerCase().includes(String(name||"").toLowerCase()));return p?place(p.x,p.y):null};const routeTo=posForPoint(window.state?.status?.destination);const robotPos=pose?place(pose.x,pose.y):null;const route=(robotPos&&routeTo)?'<svg class="pathsvg" viewBox="0 0 100 100" preserveAspectRatio="none"><line x1="'+robotPos.left+'" y1="'+robotPos.top+'" x2="'+routeTo.left+'" y2="'+routeTo.top+'" stroke="#2374e1" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="4 3"/></svg>':"";const pins=(real.length?points.slice(0,24):points.slice(0,10)).map((p,i)=>{const pos=real.length&&p.hasCoordinates?place(p.x,p.y):{left:8+(i*17)%78,top:16+(i*23)%62};return '<button class="pin '+(i%2?"green":"blue")+'" title="'+esc(p.name)+' x='+esc(p.x)+' y='+esc(p.y)+'" style="left:'+pos.left+'%;top:'+pos.top+'%" onclick="cmd(\\'visitor_guide\\',{destination:\\''+esc(p.name)+'\\'})">'+(i+1)+'</button>'}).join("");const residentPins=residents.slice(0,12).map(r=>{const pos=posForPoint(r.mapPoint||r.room);return pos?'<div class="residentpin" style="left:'+pos.left+'%;top:'+pos.top+'%" title="'+esc(r.name)+'">'+esc(r.room||r.name)+'</div>':""}).join("");const alertPins=alerts.slice(0,6).map(a=>{const pos=posForPoint(a.room||a.mapPoint||window.state?.status?.destination);return pos?'<div class="alertpin" style="left:'+pos.left+'%;top:'+pos.top+'%" title="'+esc(a.message)+'">Alert</div>':""}).join("");const bot=pose?(()=>{const pos=place(pose.x,pose.y);return '<div class="robotpin" title="Nova position from RobotAPI" style="left:'+pos.left+'%;top:'+pos.top+'%">N</div>'})():"";target.innerHTML=route+pins+residentPins+alertPins+bot+'<div class="mapnote">'+(real.length?"RobotAPI coordinates + active route":"Waiting for point coordinates")+'</div>'}
function renderHeatmap(target,history){if(!history.length){target.className="map empty";target.innerHTML="No detections recorded yet.";return}target.className="map";const xs=history.map(h=>Number(h.x||0)),ys=history.map(h=>Number(h.y||0));const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys);const spanX=Math.max(maxX-minX,0.001),spanY=Math.max(maxY-minY,0.001);target.innerHTML=history.slice(-30).map((h,i)=>{const left=8+((Number(h.x||0)-minX)/spanX)*84,top=84-((Number(h.y||0)-minY)/spanY)*76;return '<div class="pin red" style="left:'+left+'%;top:'+top+'%;opacity:'+(0.35+i/40)+'" title="'+new Date(h.at).toLocaleTimeString()+'">'+(h.count||1)+'</div>'}).join("")+'<div class="mapnote">Detection frequency / last seen areas</div>'}
function renderAll(s){const care=s.care||{};const residents=care.residents||[];const reminders=care.reminders||[];const alerts=care.alerts||[];const pts=s.points||[];const people=s.people||[];const m=s.metrics||{};window.state=s;statRobot.textContent=s.online?"1":"0";statResidents.textContent=residents.length;statPoints.textContent=pts.length;statPeople.textContent=people.length;statCamera.textContent=s.camera?"On":"Off";statTasks.textContent=m.tasksToday||0;statChecked.textContent=m.residentsChecked||0;statReminders.textContent=m.remindersDelivered||0;statResponse.textContent=m.averageResponseTime||"--";statTaskStage.textContent=s.status?.taskStage||"Ready";sideOnline.textContent=(s.online?"1":"0")+"/1";sideHealth.textContent=s.online?"Robot connected: "+new Date(s.lastSeen).toLocaleTimeString():"Waiting for real robot feed";onlinePill.textContent=s.online?"Online":"Offline";onlinePill.className="pill "+(s.online?"ok":"bad");alertCount.textContent=alerts.length+" alerts";
robotBox.innerHTML=row(s.online?"blue":"red","Nova 01",(s.status?.destination||"No destination")+" - "+(s.status?.battery||"Battery unknown"),'<span class="pill '+(s.online?"ok":"bad")+'">'+(s.online?"Online":"Offline")+"</span>")+'<p class="muted">'+esc(s.status?.status||"No status from Nova yet.")+"</p>";
residentBox.innerHTML=residents.length?residents.slice(0,5).map(r=>row("purple",r.name,r.room,'<button class="cmd" onclick="checkInResident(\\''+esc(r.id)+'\\')">Go</button>')).join(""):empty("No residents registered. Use Residents > Download Excel Template.");
alertBox.innerHTML=alerts.length?alerts.slice(0,5).map(a=>row(a.priority==="urgent"?"red":"yellow",a.message||"Alert",a.room||a.residentId||"Facility",'<span class="pill '+(a.priority==="urgent"?"bad":"warn")+'">'+esc(a.priority||"open")+"</span>")).join(""):empty("No active alerts.");
peopleBox.innerHTML=people.length?people.map(p=>row("cyan","Target "+(p.id??"?"),"x="+(p.x??"-")+" y="+(p.y??"-")+" distance="+(p.distance??"-"))).join(""):empty("No people detected in the real Nova feed.");
renderMap(mapBox,pts);renderMap(fullMapBox,pts);renderHeatmap(heatmapBox,s.detectionHistory||[]);
robotsFleet.innerHTML=robotBox.innerHTML;robotDiagnostics.innerHTML=row(s.status?.robotSdk?"green":"yellow","RobotAPI / AgentOS",s.status?.robotSdk?"Connected":"No SDK connected flag received")+row("blue","Real Map Points",String(pts.length))+row("cyan","People Detected",String(people.length))+row(s.camera?"green":"red","Camera Feed",s.camera?"Frame available":"No frame");rawDetection.textContent=JSON.stringify({detection:s.detection||{},people},null,2);
healthPanel.innerHTML=row(s.status?.robotSdk?"green":"red","RobotAPI",s.status?.robotSdk?"Connected":"Offline")+row(s.camera?"green":"yellow","Camera",s.camera?"Live frame available":"Closed")+row("blue","Map Status",pts.length+" saved destinations")+row("purple","Current Activity",m.currentActivity||"Waiting")+row("yellow","Tasks Today",String(m.tasksToday||0))+row("red","Alerts Opened",String(m.alertsOpened||0))+row("green","Safety Stop",s.status?.safetyStop||"Armed")+row("cyan","Last Detected",s.status?.lastDetectedPerson||"None");
roundResidents.innerHTML=residents.length?residents.map(r=>row("blue",r.name,r.room,'<button class="cmd" onclick="checkInResident(\\''+esc(r.id)+'\\')">Check</button>')).join(""):empty("Register residents before starting care rounds.");roundSchedule.innerHTML=reminders.length?reminders.map(r=>row("green",r.timeLabel||"Scheduled",r.title||"Reminder")).join(""):empty("No reminders from Nova or cloud yet.");
residentDirectory.innerHTML=residents.length?residents.map(r=>row("purple",r.name,r.room+" "+(r.wing||""),'<span class="pill low">'+esc(r.careLevel||"care")+"</span>")).join(""):empty("No residents registered.");residentSelect.innerHTML=residents.map(r=>'<option value="'+esc(r.id)+'">'+esc(r.name)+" - "+esc(r.room)+"</option>").join("");
alertCenter.innerHTML=alertBox.innerHTML;pointSelect.innerHTML=pts.map(p=>"<option>"+esc(p.name)+"</option>").join("");if(!pts.length)pointSelect.innerHTML="";
const logRows=[...(care.logs||[]),...(s.events||[]).slice().reverse().map(e=>({createdAt:e.at,title:e.type,detail:JSON.stringify(e.data||{}),status:"EVENT"}))];opsLog.innerHTML=(logRows.length?logRows:[{title:"Ready",detail:"Waiting for robot and facility activity",status:"READY"}]).slice(0,50).map(l=>"<tr><td>"+new Date(l.createdAt||Date.now()).toLocaleTimeString()+"</td><td>"+esc(l.title||"Event")+"</td><td>"+esc(l.detail||l.mapPoint||"")+'</td><td><span class="pill '+(l.status==="ALERT"?"bad":l.status==="QUEUED"?"warn":"ok")+'">'+esc(l.status||"OK")+"</span></td></tr>").join("");
settingsRelay.innerHTML=row(s.online?"green":"red","Robot Cloud",s.online?"Online":"Offline")+row(s.camera?"green":"red","Camera",s.camera?"Real frame ready":"No real frame")+row("blue","Resident Registry",residents.length+" registered");
formatRows.innerHTML=columns.map(c=>"<tr><td><b>"+c+"</b></td><td>"+({resident_id:"Optional stable ID. Leave blank to auto-generate.",full_name:"Required.",room:"Required.",map_point:"Nova map point to navigate to. Use the exact point name from Nova when possible.",wing:"Optional location group.",care_level:"Independent / Assisted / High.",primary_contact_name:"Optional.",primary_contact_phone:"Optional.",medication_notes:"Optional care note.",mobility_notes:"Optional mobility/safety note.",preferred_language:"Optional.",check_in_schedule:"Example: daily 09:00.",emergency_notes:"Optional urgent note."}[c]||"")+"</td></tr>").join("");
if(s.camera){cameraBox.style.display="block";noCamera.style.display="none";camera.src="/api/camera.jpg?t="+Date.now();const nearest=people[0]||{};cameraNote.textContent=(people.length?"Person detected · distance "+(nearest.distance||"--")+"m · ":"No person detected · ")+(s.detection?.securityWatch?"security":"normal")+" · "+new Date().toLocaleTimeString()}else{cameraBox.style.display="none";noCamera.style.display="grid"}
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
    return sendText(res, 200, loginPage("", parseCookies(req).nova_last_user || ADMIN_USER), "text/html; charset=utf-8");
  }
  if (url.pathname === "/login" && req.method === "POST") {
    const clientIp = req.socket.remoteAddress || "unknown";
    if (isLoginRateLimited(clientIp)) {
      log("login_rate_limited", { ip: clientIp });
      return sendText(res, 429, loginPage("Too many failed attempts. Please wait 15 minutes.", ""), "text/html; charset=utf-8");
    }
    const form = parseForm(await readBody(req));
    const username = cleanText(form.username).toLowerCase();
    const user = users.get(username);
    if (!user || !verifyPassword(form.password, user.passwordHash)) {
      recordLoginFailure(clientIp);
      log("login_failed", { username, ip: clientIp });
      return sendText(res, 401, loginPage("Invalid username or password.", username || parseCookies(req).nova_last_user), "text/html; charset=utf-8");
    }
    clearLoginFailures(clientIp);
    createSession(res, user.username);
    log("login", { username: user.username });
    res.writeHead(302, { location: "/", "cache-control": "no-store" });
    return res.end();
  }
  if (url.pathname === "/logout") {
    const sid = parseCookies(req).nova_session;
    if (sid) sessions.delete(sid);
    saveAuthStore();
    res.writeHead(200, {
      "set-cookie": "nova_session=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0",
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
      if (robot.people.length && Date.now() - lastDetectionLogAt > 5000) {
        lastDetectionLogAt = Date.now();
        const pose = robot.status?.robotPose || {};
        detectionHistory.push({
          at: Date.now(),
          count: robot.people.length,
          x: Number.isFinite(Number(pose.x)) ? Number(pose.x) : detectionHistory.length,
          y: Number.isFinite(Number(pose.y)) ? Number(pose.y) : robot.people.length,
          destination: robot.status?.destination || "",
        });
        while (detectionHistory.length > 200) detectionHistory.shift();
      }
      log("state", { people: robot.people.length, points: robot.points.length, camera: !!robot.cameraJpegBase64 });
      return sendJson(res, 200, { ok: true });
    }
    if (url.pathname === "/robot/poll") return sendJson(res, 200, { commands: commandQueue.splice(0, 20) });
    if (url.pathname === "/robot/result" && req.method === "POST") {
      const resultBody = JSON.parse((await readBody(req)) || "{}");
      facilityLog("Robot command result", JSON.stringify(resultBody), "RESULT");
      log("result", resultBody);
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
    saveAuthStore();
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
    saveAuthStore();
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
    const care = mergedCare();
    return sendJson(res, 200, { ...robot, online: robot.online && !stale, care, camera: !!robot.cameraJpegBase64, events, detectionHistory, metrics: careMetrics(care) });
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
    facilityLog("Resident registered", `${resident.name} - ${resident.room}`);
    saveAuthStore();
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
    facilityLog("Resident import", `${imported.length} residents imported`);
    saveAuthStore();
    log("resident_import", { count: imported.length });
    return sendJson(res, 200, { ok: true, count: imported.length, residents: facility.residents });
  }
  if (url.pathname === "/api/alerts" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const alert = { id: crypto.randomUUID(), createdAt: Date.now(), priority: cleanText(body.priority) || "urgent", room: cleanText(body.room), message: cleanText(body.message) || "Staff assistance requested." };
    facility.alerts.unshift(alert);
    facilityLog("Alert created", `${alert.room || "Facility"} - ${alert.message}`, "ALERT");
    commandQueue.push({ id: crypto.randomUUID(), at: Date.now(), action: "staff_alert", params: alert });
    saveAuthStore();
    log("alert", alert);
    return sendJson(res, 200, { ok: true, alert });
  }
  if (url.pathname === "/api/command" && req.method === "POST") {
    if (!requireRole(req, res, "operator")) return;
    const body = JSON.parse((await readBody(req)) || "{}");
    const command = { id: crypto.randomUUID(), at: Date.now(), action: cleanText(body.action), params: body.params || {} };
    if (!command.action) return sendJson(res, 400, { ok: false, error: "action is required" });
    commandQueue.push(command);
    if (command.action === "staff_alert") {
      const alert = {
        id: crypto.randomUUID(),
        createdAt: Date.now(),
        priority: cleanText(command.params.priority) || "urgent",
        room: cleanText(command.params.room || command.params.residentId || command.params.destination),
        message: cleanText(command.params.message) || "Staff assistance requested.",
      };
      facility.alerts.unshift(alert);
      facilityLog("Staff alert queued", `${alert.room || "Facility"} - ${alert.message}`, "ALERT");
      saveAuthStore();
    } else {
      facilityLog(titleForAction(command.action), JSON.stringify(command.params), "QUEUED");
      saveAuthStore();
    }
    log("command", command);
    return sendJson(res, 200, { ok: true, command });
  }
  sendJson(res, 404, { ok: false, error: "not found" });
});

server.listen(PORT, () => console.log(`Nova cloud relay listening on ${PORT}`));
