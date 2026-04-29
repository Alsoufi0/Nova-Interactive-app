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
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "content-length": data.length });
  res.end(data);
}

function text(res, status, body, type = "text/plain; charset=utf-8") {
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
<title>Nova Clinic Command</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f8fb;color:#111c3b;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}.app{display:grid;grid-template-columns:250px 1fr;min-height:100vh}.side{background:#071b34;color:white;padding:24px 16px;display:flex;flex-direction:column;gap:18px}.brand{display:flex;gap:12px;align-items:center}.mark{width:48px;height:48px;border-radius:16px;background:#1578ee;display:grid;place-items:center;font-size:24px}.brand b{font-size:21px}.nav{display:grid;gap:8px}.nav a{padding:13px;border-radius:10px;color:white;text-decoration:none}.nav a:first-child{background:#1c64c8}.sidebox{margin-top:auto;background:#ffffff10;border:1px solid #ffffff18;border-radius:14px;padding:16px}.top{height:76px;background:white;border-bottom:1px solid #dfe7f1;display:flex;align-items:center;justify-content:space-between;padding:0 26px}.top h1{margin:0;font-size:24px}.muted{color:#718198;font-size:13px}.content{padding:18px}.commandGrid{display:grid;grid-template-columns:repeat(5,1fr);gap:14px}.op{border:0;border-radius:18px;padding:22px 14px;min-height:150px;color:#08142f;background:white;box-shadow:0 12px 32px #2f4d7a18;font-weight:900;font-size:18px;cursor:pointer}.op span{display:block;margin:auto auto 14px;width:64px;height:64px;border-radius:50%;display:grid;place-items:center;color:white;font-size:22px}.green{background:#2f9e57}.blue{background:#2374e1}.yellow{background:#f2b51e}.red{background:#e95050}.purple{background:#8a55de}.panelGrid{display:grid;grid-template-columns:1.15fr 1fr 1fr;gap:14px;margin-top:14px}.card{background:white;border:1px solid #dfe7f1;border-radius:16px;padding:16px;box-shadow:0 10px 28px #3451a012}.card h2{font-size:16px;margin:0 0 12px}.status{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.stat{border:1px solid #e1e9f2;border-radius:14px;padding:14px}.stat b{font-size:26px;display:block}.pill{border-radius:999px;padding:5px 9px;font-size:12px;font-weight:800;display:inline-block}.ok{background:#e5f7e9;color:#238044}.badpill{background:#ffe6e6;color:#c93131}.mid{background:#fff2d9;color:#b06a00}.low{background:#e8f1ff;color:#1e67c9}.row{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;border-bottom:1px solid #edf2f7;padding:11px 0}.dot{width:42px;height:42px;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900}.map{height:255px;border-radius:14px;background:linear-gradient(135deg,#edf4ff,#f9fbff);border:1px solid #dce7f4;position:relative;overflow:hidden}.room{position:absolute;border:2px solid #cbd9ea;border-radius:9px;padding:13px;color:#52627a;background:#ffffffaa}.pin{position:absolute;width:34px;height:34px;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900}.controls{display:flex;gap:10px;flex-wrap:wrap}.cmd{border:1px solid #d7e1ee;background:white;color:#14213d;border-radius:999px;padding:12px 18px;font-weight:850;cursor:pointer}.danger{background:#e94d4d;color:white;border-color:#e94d4d}.primary{background:#2374e1;color:white;border-color:#2374e1}.camera{display:none;margin-top:12px}.camera img{width:100%;max-height:310px;object-fit:contain;background:#061426;border-radius:12px}@media(max-width:1000px){.app{grid-template-columns:1fr}.side{display:none}.top{height:auto;padding:16px;align-items:flex-start;flex-direction:column}.commandGrid,.panelGrid,.status{grid-template-columns:1fr}.content{padding:12px}}
</style></head><body><div class="app">
<aside class="side"><div class="brand"><div class="mark">+</div><div><b>Nova Care Cloud</b><br><span class="muted">Clinic Command</span></div></div><nav class="nav"><a>Command</a><a>Robots</a><a>Rounds</a><a>Residents</a><a>Alerts</a><a>Map</a><a>Logs</a><a>Settings</a></nav><div class="sidebox"><b>Nova Online</b><span id="sideOnline" style="float:right">0/1</span><p class="muted" id="sideHealth">Waiting for robot</p><button class="cmd primary" onclick="cmd('camera_start')">Live Camera</button></div></aside>
<main><header class="top"><div><h1>Clinic Command Center</h1><div class="muted">Operate Nova, rounds, reminders, alerts, messages, and live view</div></div><div><span class="pill" id="onlinePill">Offline</span> <span class="pill badpill" id="alertCount">0 alerts</span></div></header><section class="content">
<section class="commandGrid"><button class="op" onclick="cmd('start_rounds')"><span class="green">R</span>Rounds</button><button class="op" onclick="cmd('resident_checkin',{residentId:firstResidentId()})"><span class="blue">C</span>Check-In</button><button class="op" onclick="cmd('med_reminder',{residentId:firstResidentId()})"><span class="yellow">M</span>Meds</button><button class="op" onclick="staffAlert()"><span class="red">!</span>Alert</button><button class="op" onclick="cmd('visitor_guide',{destination:firstPoint()})"><span class="purple">G</span>Guide</button></section>
<section class="card" style="margin-top:14px"><div class="status"><div class="stat"><span class="muted">Robot</span><b id="statRobot">0</b></div><div class="stat"><span class="muted">Residents</span><b id="statResidents">0</b></div><div class="stat"><span class="muted">Tasks</span><b id="statTasks">0</b></div><div class="stat"><span class="muted">People Seen</span><b id="statPeople">0</b></div></div></section>
<section class="panelGrid"><div class="card"><h2>Robot</h2><div id="robotBox"></div><div class="controls"><button class="cmd danger" onclick="cmd('stop')">Stop</button><button class="cmd" onclick="cmd('charge')">Charge</button><button class="cmd" onclick="cmd('security_start')">Detect</button><button class="cmd" onclick="cmd('security_stop')">End Detect</button></div></div><div class="card"><h2>Residents</h2><div id="residentBox"></div></div><div class="card"><h2>Alerts</h2><div id="alerts"></div></div></section>
<section class="panelGrid"><div class="card"><h2>Schedule</h2><div id="schedule"></div></div><div class="card"><h2>Map</h2><div class="map" id="mapBox"></div></div><div class="card"><h2>Camera</h2><div class="controls"><button class="cmd primary" onclick="cmd('camera_start')">Open</button><button class="cmd" onclick="cmd('camera_stop')">Close</button></div><div class="camera" id="cameraBox"><img id="camera" alt="camera"><p class="muted" id="cameraNote"></p></div></div></section>
<section class="card" style="margin-top:14px"><h2>Care Log</h2><div id="history"></div></section>
</section></main></div><script>
async function get(p){return (await fetch(p,{cache:'no-store'})).json()}
async function cmd(action,params={}){await fetch('/api/command',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,params})});refresh()}
function esc(v){return String(v||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function firstResidentId(){return (window.lastCare?.residents||[])[0]?.id||''}
function firstPoint(){return (window.lastPoints||[])[0]?.name||'Reception'}
function staffAlert(){cmd('staff_alert',{priority:'urgent',room:firstPoint(),message:'Staff assistance requested.'})}
function row(color,title,detail,right=''){return '<div class="row"><div class="dot '+color+'">'+title[0]+'</div><div><b>'+esc(title)+'</b><br><span class="muted">'+esc(detail)+'</span></div><div>'+right+'</div></div>'}
function renderRobot(s){robotBox.innerHTML=row(s.online?'blue':'red','Nova 01',(s.status?.destination||'Hallway A')+' - '+(s.status?.battery||'Battery --'),'<span class="pill '+(s.online?'ok':'badpill')+'">'+(s.online?'Online':'Offline')+'</span>')+'<p class="muted">'+esc(s.status?.status||'Waiting')+'</p>'}
function renderResidents(care){const rs=care.residents||[];residentBox.innerHTML=(rs.length?rs:[{name:'Mary Collins',room:'Room 204',id:''},{name:'John Ahmed',room:'Room 207',id:''},{name:'Grace Lee',room:'Therapy',id:''}]).slice(0,5).map(r=>row('purple',r.name,r.room,'<button class="cmd" onclick="cmd(\\\'resident_checkin\\\',{residentId:\\\''+esc(r.id||'')+'\\\'})">Go</button>')).join('')}
function renderAlerts(care){const list=(care.alerts||[]);const sample=[['Staff assistance','Nurse station','mid'],['Medication missed','Room 210','badpill'],['Low battery','Nova 01','low']];const rows=list.length?list.map(a=>[a.message,a.room,a.priority==='urgent'?'badpill':'mid']):sample;alerts.innerHTML=rows.slice(0,5).map(a=>row(a[2]==='badpill'?'red':'yellow',a[0],a[1],'<span class="pill '+a[2]+'">'+(a[2]==='badpill'?'High':'Open')+'</span>')).join('');alertCount.textContent=rows.length+' alerts'}
function renderSchedule(care){const reminders=care.reminders||[];const base=reminders.length?reminders:[{timeLabel:'08:00',title:'Rounds'},{timeLabel:'09:00',title:'Check-In'},{timeLabel:'10:00',title:'Meds'}];schedule.innerHTML=base.slice(0,4).map(r=>row('green',r.timeLabel||'Now',r.title,'<button class="cmd" onclick="cmd(\\\'med_reminder\\\',{reminderId:\\\''+esc(r.id||'')+'\\\'})">Run</button>')).join('')}
function renderHistory(care){const logs=(care.logs||[]);history.innerHTML=(logs.length?logs:[{title:'Ready',detail:'No robot log yet',mapPoint:'Cloud'}]).slice(0,6).map(l=>row('blue',l.title,l.detail||l.mapPoint||'Logged','<span class="pill ok">OK</span>')).join('')}
function renderMap(points){window.lastPoints=points||[];mapBox.innerHTML='<div class="room" style="left:5%;top:16%">102</div><div class="room" style="left:24%;top:10%">Nurse</div><div class="room" style="left:48%;top:16%">204</div><div class="room" style="left:66%;top:45%">Wing B</div>'+window.lastPoints.slice(0,5).map((p,i)=>'<button class="pin '+(i%2?'green':'blue')+'" style="left:'+(12+i*16)+'%;top:'+(48-i*6)+'%" onclick="cmd(\\\'visitor_guide\\\',{destination:\\\''+esc(p.name)+'\\\'})">'+(i+1)+'</button>').join('')}
async function refresh(){const s=await get('/api/state');const care=s.care||{};window.lastCare=care;statRobot.textContent=s.online?'1':'0';sideOnline.textContent=(s.online?'1':'0')+'/1';onlinePill.textContent=s.online?'Online':'Offline';onlinePill.className='pill '+(s.online?'ok':'badpill');sideHealth.textContent=s.online?'Robot connected':'Robot offline';statResidents.textContent=(care.residents||[]).length||3;statTasks.textContent=((care.reminders||[]).length+(care.logs||[]).length)||5;statPeople.textContent=(s.people||[]).length||0;renderRobot(s);renderResidents(care);renderAlerts(care);renderSchedule(care);renderHistory(care);renderMap(s.points||[]);if(s.camera){cameraBox.style.display='block';camera.src='/api/camera.jpg?t='+Date.now();cameraNote.textContent='Live snapshot'}else{cameraBox.style.display='none'}}
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
    if (url.pathname === "/robot/poll") return json(res, 200, { commands: commandQueue.splice(0, 10) });
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
