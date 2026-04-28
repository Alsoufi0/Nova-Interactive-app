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
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nova Care Cloud</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f6f9fc;color:#101a33;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}.app{display:grid;grid-template-columns:248px 1fr;min-height:100vh}.side{background:linear-gradient(180deg,#071b34,#0b2647);color:white;padding:24px 16px;display:flex;flex-direction:column;gap:18px}.brand{display:flex;gap:12px;align-items:center}.mark{width:46px;height:46px;border-radius:15px;background:#1677ee;display:grid;place-items:center;font-size:24px;font-weight:900}.brand b{font-size:21px}.muted{color:#728198;font-size:13px}.side .muted{color:#b7c6d8}.nav{display:grid;gap:7px}.nav a{padding:13px 14px;border-radius:11px;color:white;text-decoration:none;cursor:pointer;font-weight:750}.nav a.active,.nav a:hover{background:#1d66ca}.sidebox{margin-top:auto;background:#ffffff10;border:1px solid #ffffff1f;border-radius:16px;padding:16px}.top{height:78px;background:white;border-bottom:1px solid #dfe7f1;display:flex;align-items:center;justify-content:space-between;padding:0 26px}.top h1{margin:0;font-size:24px}.content{padding:18px}.view{display:none}.view.active{display:block}.grid5{display:grid;grid-template-columns:repeat(5,1fr);gap:14px}.grid3{display:grid;grid-template-columns:1.2fr 1fr 1fr;gap:14px;margin-top:14px}.two{display:grid;grid-template-columns:1fr 1fr;gap:14px}.card{background:white;border:1px solid #dfe7f1;border-radius:16px;padding:16px;box-shadow:0 10px 28px #3451a012}.card h2{font-size:16px;margin:0 0 12px}.tile{border:0;border-radius:18px;min-height:148px;background:white;color:#08142f;box-shadow:0 12px 32px #2f4d7a18;font-weight:900;font-size:18px;cursor:pointer;padding:20px 12px}.tile span{display:grid;place-items:center;width:64px;height:64px;border-radius:50%;margin:0 auto 14px;color:white;font-size:22px}.green{background:#2f9e57}.blue{background:#2374e1}.yellow{background:#f2b51e}.red{background:#e95050}.purple{background:#8a55de}.status{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.stat{border:1px solid #e1e9f2;border-radius:14px;padding:14px}.stat b{font-size:26px;display:block}.pill{border-radius:999px;padding:5px 9px;font-size:12px;font-weight:850;display:inline-block}.ok{background:#e5f7e9;color:#238044}.bad{background:#ffe6e6;color:#c93131}.warn{background:#fff2d9;color:#b06a00}.low{background:#e8f1ff;color:#1e67c9}.row{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;border-bottom:1px solid #edf2f7;padding:11px 0}.dot{width:42px;height:42px;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900}.cmd{border:1px solid #d7e1ee;background:white;color:#14213d;border-radius:999px;padding:11px 16px;font-weight:850;cursor:pointer;margin:4px}.cmd.primary{background:#2374e1;color:white;border-color:#2374e1}.cmd.danger{background:#e94d4d;color:white;border-color:#e94d4d}.field{width:100%;border:1px solid #d7e1ee;border-radius:12px;padding:12px;margin:6px 0;font:inherit}.map{height:255px;border-radius:14px;background:linear-gradient(135deg,#edf4ff,#fbfdff);border:1px solid #dce7f4;position:relative;overflow:hidden}.room{position:absolute;border:2px solid #cbd9ea;border-radius:9px;padding:13px;color:#52627a;background:#ffffffbb}.pin{position:absolute;width:34px;height:34px;border:0;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900;cursor:pointer}.camera{display:none;margin-top:12px}.camera img{width:100%;max-height:320px;object-fit:contain;background:#071426;border-radius:12px}.table{width:100%;border-collapse:collapse}.table td,.table th{text-align:left;padding:12px;border-bottom:1px solid #edf2f7}@media(max-width:1000px){.app{grid-template-columns:1fr}.side{display:none}.top{height:auto;padding:16px;align-items:flex-start;flex-direction:column}.grid5,.grid3,.status,.two{grid-template-columns:1fr}.content{padding:12px}}
</style></head><body><div class="app">
<aside class="side"><div class="brand"><div class="mark">+</div><div><b>Nova Care Cloud</b><br><span class="muted">Healthcare robot control</span></div></div><nav class="nav">
<a class="active" onclick="switchView('command',this)">Command</a><a onclick="switchView('robots',this)">Robots</a><a onclick="switchView('rounds',this)">Rounds</a><a onclick="switchView('residents',this)">Residents</a><a onclick="switchView('alerts',this)">Alerts</a><a onclick="switchView('map',this)">Map</a><a onclick="switchView('logs',this)">Logs</a><a onclick="switchView('settings',this)">Settings</a>
</nav><div class="sidebox"><b>Nova Online</b><span id="sideOnline" style="float:right">0/1</span><p class="muted" id="sideHealth">Waiting for robot</p><button class="cmd primary" onclick="cmd('camera_start')">Live Camera</button></div></aside>
<main><header class="top"><div><h1 id="pageTitle">Clinic Command Center</h1><div class="muted" id="pageSubtitle">Operate rounds, reminders, alerts, messages, map points, and live view.</div></div><div><span class="pill" id="onlinePill">Offline</span> <span class="pill bad" id="alertCount">0 alerts</span></div></header>
<section class="content view active" id="view-command">
<div class="grid5"><button class="tile" onclick="cmd('start_rounds')"><span class="green">R</span>Start Rounds</button><button class="tile" onclick="cmd('resident_checkin',{residentId:firstResidentId()})"><span class="blue">C</span>Check-In</button><button class="tile" onclick="cmd('med_reminder',{residentId:firstResidentId()})"><span class="yellow">M</span>Medication</button><button class="tile" onclick="staffAlert()"><span class="red">!</span>Staff Alert</button><button class="tile" onclick="cmd('visitor_guide',{destination:firstPoint()})"><span class="purple">G</span>Visitor Guide</button></div>
<div class="card" style="margin-top:14px"><div class="status"><div class="stat"><span class="muted">Robot</span><b id="statRobot">0</b></div><div class="stat"><span class="muted">Residents</span><b id="statResidents">0</b></div><div class="stat"><span class="muted">Tasks</span><b id="statTasks">0</b></div><div class="stat"><span class="muted">People Seen</span><b id="statPeople">0</b></div></div></div>
<div class="grid3"><div class="card"><h2>Robot</h2><div id="robotBox"></div><button class="cmd danger" onclick="cmd('stop')">Stop</button><button class="cmd" onclick="cmd('charge')">Charge</button><button class="cmd" onclick="cmd('security_start')">Detect</button><button class="cmd" onclick="cmd('security_stop')">End Detect</button></div><div class="card"><h2>Residents</h2><div id="residentBox"></div></div><div class="card"><h2>Alerts</h2><div id="alertBox"></div></div></div>
<div class="grid3"><div class="card"><h2>Schedule</h2><div id="scheduleBox"></div></div><div class="card"><h2>Map</h2><div class="map" id="mapBox"></div></div><div class="card"><h2>Camera</h2><button class="cmd primary" onclick="cmd('camera_start')">Open</button><button class="cmd" onclick="cmd('camera_stop')">Close</button><div class="camera" id="cameraBox"><img id="camera" alt="camera"><p class="muted" id="cameraNote"></p></div></div></div>
</section>
<section class="content view" id="view-robots"><div class="two"><div class="card"><h2>Fleet Control</h2><div id="robotsFleet"></div><button class="cmd danger" onclick="cmd('stop')">Emergency Stop</button><button class="cmd" onclick="cmd('charge')">Go Charge</button><button class="cmd primary" onclick="cmd('camera_start')">Open Camera</button></div><div class="card"><h2>Diagnostics</h2><div id="robotDiagnostics"></div></div></div></section>
<section class="content view" id="view-rounds"><div class="two"><div class="card"><h2>Round Launcher</h2><button class="tile" onclick="cmd('start_rounds')"><span class="green">R</span>Start Care Round</button><div id="roundResidents"></div></div><div class="card"><h2>Round Schedule</h2><div id="roundSchedule"></div></div></div></section>
<section class="content view" id="view-residents"><div class="two"><div class="card"><h2>Resident Directory</h2><div id="residentDirectory"></div></div><div class="card"><h2>Resident Actions</h2><select class="field" id="residentSelect"></select><button class="cmd primary" onclick="cmd('resident_checkin',{residentId:residentSelect.value})">Check In</button><button class="cmd" onclick="cmd('med_reminder',{residentId:residentSelect.value})">Medication</button><button class="cmd danger" onclick="cmd('staff_alert',{priority:'urgent',room:residentSelect.value,message:'Assistance requested for resident.'})">Alert Staff</button></div></div></section>
<section class="content view" id="view-alerts"><div class="two"><div class="card"><h2>Alert Center</h2><div id="alertCenter"></div></div><div class="card"><h2>Create Alert</h2><input class="field" id="alertRoom" placeholder="Room or location"><textarea class="field" id="alertMessage" rows="4" placeholder="What happened?"></textarea><button class="cmd danger" onclick="cmd('staff_alert',{priority:'urgent',room:alertRoom.value,message:alertMessage.value||'Staff assistance requested.'})">Send Urgent Alert</button></div></div></section>
<section class="content view" id="view-map"><div class="two"><div class="card"><h2>Facility Map</h2><div class="map" id="fullMapBox"></div></div><div class="card"><h2>Destination Control</h2><select class="field" id="pointSelect"></select><button class="cmd primary" onclick="cmd('visitor_guide',{destination:pointSelect.value})">Guide Visitor</button><button class="cmd" onclick="cmd('message',{destination:pointSelect.value,message:'Please meet Nova at this location.'})">Send Message</button></div></div></section>
<section class="content view" id="view-logs"><div class="card"><h2>Operations Log</h2><table class="table"><thead><tr><th>Time</th><th>Event</th><th>Detail</th><th>Status</th></tr></thead><tbody id="opsLog"></tbody></table></div></section>
<section class="content view" id="view-settings"><div class="two"><div class="card"><h2>Cloud Relay</h2><div id="settingsRelay"></div><button class="cmd primary" onclick="cmd('camera_start')">Test Camera</button><button class="cmd" onclick="cmd('security_start')">Test Detection</button></div><div class="card"><h2>Safety</h2><button class="cmd danger" onclick="cmd('stop')">Emergency Stop</button><button class="cmd" onclick="cmd('security_stop')">Stop Detection</button><p class="muted">Rotate cloud credentials before client deployment.</p></div></div></section>
</main></div><script>
const titles={command:["Clinic Command Center","Operate rounds, reminders, alerts, messages, map points, and live view."],robots:["Robots","Fleet control, diagnostics, camera and safety state."],rounds:["Rounds","Launch care rounds and run resident check-ins."],residents:["Residents","Directory, check-ins, reminders and resident alerts."],alerts:["Alerts","Create urgent alerts and monitor open issues."],map:["Map","Select Nova map points and send navigation commands."],logs:["Logs","Care history, relay events and command results."],settings:["Settings","Relay health and safety controls."]};
async function get(p){return (await fetch(p,{cache:"no-store"})).json()}
async function cmd(action,params={}){await fetch("/api/command",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({action,params})});refresh()}
function esc(v){return String(v||"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]))}
function switchView(name,el){document.querySelectorAll(".view").forEach(v=>v.classList.remove("active"));document.getElementById("view-"+name).classList.add("active");document.querySelectorAll(".nav a").forEach(a=>a.classList.remove("active"));if(el)el.classList.add("active");pageTitle.textContent=titles[name][0];pageSubtitle.textContent=titles[name][1]}
function firstResidentId(){return (window.lastCare?.residents||[])[0]?.id||"resident-1"}
function firstPoint(){return (window.lastPoints||[])[0]?.name||"Reception"}
function staffAlert(){cmd("staff_alert",{priority:"urgent",room:firstPoint(),message:"Staff assistance requested."})}
function row(color,title,detail,right=""){return '<div class="row"><div class="dot '+color+'">'+esc(title)[0]+'</div><div><b>'+esc(title)+'</b><br><span class="muted">'+esc(detail)+'</span></div><div>'+right+'</div></div>'}
function renderMap(target,points){target.innerHTML='<div class="room" style="left:5%;top:16%">102</div><div class="room" style="left:24%;top:10%">Nurse</div><div class="room" style="left:48%;top:16%">204</div><div class="room" style="left:66%;top:45%">Wing B</div>'+points.slice(0,6).map((p,i)=>'<button class="pin '+(i%2?"green":"blue")+'" style="left:'+(12+i*13)+'%;top:'+(50-i*5)+'%" onclick="cmd(\\'visitor_guide\\',{destination:\\''+esc(p.name)+'\\'})">'+(i+1)+'</button>').join("")}
function sampleResidents(){return [{id:"room-204",name:"Mary Collins",room:"Room 204"},{id:"room-207",name:"John Ahmed",room:"Room 207"},{id:"therapy",name:"Grace Lee",room:"Therapy"}]}
function sampleReminders(){return [{id:"r1",timeLabel:"08:00",title:"Rounds"},{id:"r2",timeLabel:"09:00",title:"Check-In"},{id:"r3",timeLabel:"10:00",title:"Meds"}]}
function renderAll(s){const care=s.care||{};const residents=(care.residents||[]).length?care.residents:sampleResidents();const reminders=(care.reminders||[]).length?care.reminders:sampleReminders();const alerts=(care.alerts||[]);const pts=(s.points||[]).length?s.points:[{name:"Reception"},{name:"Nurse Station"},{name:"Room 204"}];window.lastCare=care;window.lastPoints=pts;statRobot.textContent=s.online?"1":"0";statResidents.textContent=residents.length;statTasks.textContent=reminders.length+(care.logs||[]).length;statPeople.textContent=(s.people||[]).length;sideOnline.textContent=(s.online?"1":"0")+"/1";sideHealth.textContent=s.online?"Robot connected":"Robot offline";onlinePill.textContent=s.online?"Online":"Offline";onlinePill.className="pill "+(s.online?"ok":"bad");alertCount.textContent=(alerts.length||3)+" alerts";
robotBox.innerHTML=row(s.online?"blue":"red","Nova 01",(s.status?.destination||"Hallway A")+" - "+(s.status?.battery||"Battery --"),'<span class="pill '+(s.online?"ok":"bad")+'">'+(s.online?"Online":"Offline")+"</span>")+'<p class="muted">'+esc(s.status?.status||"Waiting for Nova telemetry")+"</p>";
residentBox.innerHTML=residents.slice(0,5).map(r=>row("purple",r.name,r.room,'<button class="cmd" onclick="cmd(\\'resident_checkin\\',{residentId:\\''+esc(r.id)+'\\'})">Go</button>')).join("");
alertBox.innerHTML=(alerts.length?alerts:[{message:"Staff assistance",room:"Nurse station",priority:"open"},{message:"Medication missed",room:"Room 210",priority:"urgent"},{message:"Low battery",room:"Nova 01",priority:"low"}]).slice(0,5).map(a=>row(a.priority==="urgent"?"red":"yellow",a.message,a.room,'<span class="pill '+(a.priority==="urgent"?"bad":"warn")+'">'+esc(a.priority||"open")+"</span>")).join("");
scheduleBox.innerHTML=reminders.slice(0,5).map(r=>row("green",r.timeLabel||"Now",r.title,'<button class="cmd" onclick="cmd(\\'med_reminder\\',{reminderId:\\''+esc(r.id)+'\\'})">Run</button>')).join("");
renderMap(mapBox,pts);renderMap(fullMapBox,pts);
robotsFleet.innerHTML=robotBox.innerHTML;robotDiagnostics.innerHTML=row("blue","RobotAPI",s.status?.robotSdk?"Connected":"Waiting for SDK telemetry")+row("green","Map Points",String(pts.length))+row("purple","People Detected",String((s.people||[]).length));
roundResidents.innerHTML=residents.map(r=>row("blue",r.name,r.room,'<button class="cmd" onclick="cmd(\\'resident_checkin\\',{residentId:\\''+esc(r.id)+'\\'})">Check</button>')).join("");roundSchedule.innerHTML=scheduleBox.innerHTML;
residentDirectory.innerHTML=residentBox.innerHTML;residentSelect.innerHTML=residents.map(r=>'<option value="'+esc(r.id)+'">'+esc(r.name)+" - "+esc(r.room)+"</option>").join("");
alertCenter.innerHTML=alertBox.innerHTML;pointSelect.innerHTML=pts.map(p=>"<option>"+esc(p.name)+"</option>").join("");
const logRows=[...(care.logs||[]),...(s.events||[]).slice(-12).reverse().map(e=>({createdAt:e.at,title:e.type,detail:JSON.stringify(e.data||{})}))];opsLog.innerHTML=(logRows.length?logRows:[{title:"Ready",detail:"No robot log yet"}]).slice(0,30).map(l=>"<tr><td>"+new Date(l.createdAt||Date.now()).toLocaleTimeString()+"</td><td>"+esc(l.title||"Event")+"</td><td>"+esc(l.detail||l.mapPoint||"")+'</td><td><span class="pill ok">OK</span></td></tr>').join("");
settingsRelay.innerHTML=row(s.online?"green":"red","Robot Cloud",s.online?"Online":"Offline")+row("blue","Camera",s.camera?"Snapshot ready":"Closed")+row("purple","Command Queue","Relay active");
if(s.camera){cameraBox.style.display="block";camera.src="/api/camera.jpg?t="+Date.now();cameraNote.textContent="Live snapshot"}else{cameraBox.style.display="none"}
}
async function refresh(){renderAll(await get("/api/state"))}
setInterval(refresh,2000);refresh();
</script></body></html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  if (url.pathname === "/health") return sendJson(res, 200, { ok: true });
  if (url.pathname.startsWith("/robot/")) {
    if (!isRobot(req)) return sendJson(res, 403, { ok: false, error: "bad robot token" });
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
      return sendJson(res, 200, { ok: true });
    }
    if (url.pathname === "/robot/poll") return sendJson(res, 200, { commands: commandQueue.splice(0, 10) });
    if (url.pathname === "/robot/result" && req.method === "POST") {
      events.push({ at: Date.now(), type: "result", data: JSON.parse((await readBody(req)) || "{}") });
      while (events.length > 100) events.shift();
      return sendJson(res, 200, { ok: true });
    }
  }
  if (!requireAdmin(req, res)) return;
  if (url.pathname === "/") return sendText(res, 200, page(), "text/html; charset=utf-8");
  if (url.pathname === "/api/state") {
    const stale = Date.now() - robot.lastSeen > 15000;
    return sendJson(res, 200, { ...robot, online: robot.online && !stale, camera: !!robot.cameraJpegBase64, events });
  }
  if (url.pathname === "/api/camera.jpg") {
    if (!robot.cameraJpegBase64) return sendText(res, 404, "No camera snapshot");
    const data = Buffer.from(robot.cameraJpegBase64, "base64");
    res.writeHead(200, { "content-type": "image/jpeg", "cache-control": "no-store", "content-length": data.length });
    return res.end(data);
  }
  if (url.pathname === "/api/command" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)) || "{}");
    const command = { id: crypto.randomUUID(), at: Date.now(), action: String(body.action || ""), params: body.params || {} };
    commandQueue.push(command);
    events.push({ at: Date.now(), type: "command", data: command });
    while (events.length > 100) events.shift();
    return sendJson(res, 200, { ok: true, command });
  }
  sendJson(res, 404, { ok: false, error: "not found" });
});

server.listen(PORT, () => console.log(`Nova cloud relay listening on ${PORT}`));
