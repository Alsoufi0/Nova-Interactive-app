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
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Nova Care Cloud Dashboard</title><style>
*{box-sizing:border-box}body{margin:0;background:#f5f8fc;color:#14213d;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}.app{display:grid;grid-template-columns:270px 1fr;min-height:100vh}.side{background:linear-gradient(180deg,#071b34,#061426);color:white;padding:26px 16px;display:flex;flex-direction:column;gap:22px}.brand{display:flex;gap:12px;align-items:center}.logo{width:48px;height:48px;border-radius:16px;background:#0f86ff22;border:1px solid #2f95ff;display:grid;place-items:center;font-size:25px}.brand b{font-size:22px}.muted{color:#72829e;font-size:13px}.side .muted{color:#a7b6cd}.nav{display:grid;gap:8px}.nav a{color:white;text-decoration:none;padding:13px 14px;border-radius:9px}.nav a.active{background:linear-gradient(90deg,#236fd6,#15509c)}.sideStatus{margin-top:auto;background:#ffffff10;border:1px solid #ffffff12;border-radius:12px;padding:16px}.top{height:78px;background:white;border-bottom:1px solid #dfe7f1;display:flex;align-items:center;justify-content:space-between;padding:0 28px;position:sticky;top:0;z-index:3}.title h1{margin:0;font-size:24px}.selector{padding:12px 22px;border:1px solid #d7e1ee;border-radius:10px;background:white;color:#14213d}.user{display:flex;align-items:center;gap:12px}.avatar{width:42px;height:42px;border-radius:50%;background:#dce8f8;display:grid;place-items:center}.content{padding:20px}.kpis{display:grid;grid-template-columns:repeat(5,1fr);gap:14px}.card{background:white;border:1px solid #dfe7f1;border-radius:12px;padding:16px;box-shadow:0 10px 28px #3451a012}.kpi{display:flex;align-items:center;gap:14px;min-height:108px}.icon{width:58px;height:58px;border-radius:50%;display:grid;place-items:center;font-weight:900;font-size:20px}.blue{background:#e2efff;color:#1269d3}.green{background:#dff4e5;color:#28974d}.purple{background:#eee2ff;color:#7c43d6}.yellow{background:#fff1d3;color:#cc8400}.red{background:#ffe1e1;color:#d94646}.kpi strong{font-size:28px;display:block}.grid{display:grid;grid-template-columns:2fr 1.45fr 1.55fr;gap:14px;margin-top:14px}.grid2{display:grid;grid-template-columns:1.1fr 1fr 1.2fr;gap:14px;margin-top:14px}.card h2{font-size:16px;margin:0 0 14px}.robotRow,.alertRow,.historyRow,.scheduleRow{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;padding:10px 0;border-bottom:1px solid #ecf1f6}.robotPic{width:54px;height:54px;border-radius:16px;background:#edf4ff;display:grid;place-items:center;font-size:24px}.pill{display:inline-block;border-radius:999px;padding:4px 8px;font-size:12px;font-weight:800}.ok{background:#e5f7e9;color:#238044}.bad{background:#ffe6e6;color:#c93131}.mid{background:#fff2d9;color:#b06a00}.low{background:#e8f1ff;color:#1e67c9}.taskRing{width:200px;height:200px;margin:auto;border-radius:50%;background:conic-gradient(#36b862 0 64%,#2677e8 64% 85%,#ffc13d 85% 96%,#ef4444 96%);display:grid;place-items:center}.taskRing div{width:125px;height:125px;border-radius:50%;background:white;display:grid;place-items:center;text-align:center}.map{height:210px;border-radius:12px;background:linear-gradient(135deg,#edf4ff,#f9fbff);border:1px solid #dce7f4;position:relative;overflow:hidden}.room{position:absolute;border:2px solid #cbd9ea;border-radius:8px;padding:14px;color:#52627a;background:#ffffff99}.pin{position:absolute;width:32px;height:32px;border-radius:50%;display:grid;place-items:center;color:white;font-weight:900}.commands{margin-top:14px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}.cmd{width:auto;border:1px solid #d7e1ee;background:white;color:#14213d;border-radius:999px;padding:12px 18px;font-weight:800;cursor:pointer}.primary{background:#2374e1;color:white;border-color:#2374e1}.danger{background:#e94d4d;color:white;border-color:#e94d4d}.mini{font-size:12px;color:#72829e}.camera{display:none;margin-top:10px}.camera img{width:100%;max-height:300px;object-fit:contain;background:#061426;border-radius:10px}@media(max-width:1100px){.app{grid-template-columns:1fr}.side{display:none}.top{height:auto;align-items:flex-start;gap:12px;flex-direction:column;padding:16px}.kpis,.grid,.grid2{grid-template-columns:1fr}.content{padding:12px}}
</style></head><body><div class="app"><aside class="side"><div class="brand"><div class="logo">+</div><div><b>Nova Care Cloud</b><br><span class="muted">Healthcare Robot Management</span></div></div><nav class="nav"><a class="active">Dashboard</a><a>Robots</a><a>Tasks & Rounds</a><a>Residents</a><a>Medications</a><a>Alerts</a><a>Visitors</a><a>Reports & Logs</a><a>Map & Locations</a><a>Settings</a></nav><div class="sideStatus"><b>Robots Online</b><span id="sideOnline" style="float:right">0 / 1</span><p class="muted">All systems monitoring</p><button class="cmd primary" onclick="cmd('camera_start')">Open Camera</button></div></aside><section class="main"><header class="top"><div class="title"><h1>Dashboard</h1><p class="muted">Overview of robots, tasks and facility operations</p></div><select class="selector"><option>Sunrise Elder Care Center</option><option>Clinic Demo Site</option></select><div class="user"><span class="pill bad" id="alertBadge">0</span><div class="avatar">SJ</div><div><b>Sarah Johnson</b><br><span class="muted">Nurse Manager</span></div></div></header><main class="content"><section class="kpis"><div class="card kpi"><div class="icon blue">N</div><div><span>Active Robots</span><strong id="kpiRobots">0</strong><span class="mini" id="robotOnlineText">Offline</span></div></div><div class="card kpi"><div class="icon green">T</div><div><span>Tasks Today</span><strong id="kpiTasks">0</strong><span class="mini" id="tasksDone">0 completed</span></div></div><div class="card kpi"><div class="icon purple">R</div><div><span>Residents</span><strong id="kpiResidents">0</strong><span class="mini">All residents</span></div></div><div class="card kpi"><div class="icon yellow">A</div><div><span>Alerts</span><strong id="kpiAlerts">0</strong><span class="mini">Requires attention</span></div></div><div class="card kpi"><div class="icon blue">V</div><div><span>Visitors Today</span><strong>12</strong><span class="mini">3 in progress</span></div></div></section><section class="grid"><div class="card"><h2>Robot Status</h2><div id="robotStatus"></div><button class="cmd danger" onclick="cmd('stop')">Emergency Stop</button></div><div class="card"><h2>Task Overview</h2><div class="taskRing"><div><strong id="ringTasks">0</strong><span>Total Tasks</span></div></div><p id="taskLegend" class="muted"></p><button class="cmd" onclick="cmd('start_rounds')">View All Tasks</button></div><div class="card"><h2>Recent Alerts <button class="cmd danger" style="float:right;padding:7px 10px" onclick="staffAlert()">New Alert</button></h2><div id="alerts"></div></div></section><section class="grid2"><div class="card"><h2>Today's Schedule</h2><div id="schedule"></div><button class="cmd" onclick="cmd('start_rounds')">View Full Schedule</button></div><div class="card"><h2>Facility Map</h2><div class="map" id="mapBox"></div><button class="cmd primary" onclick="cmd('camera_start')">Live View</button><div class="camera" id="cameraBox"><img id="camera" alt="camera"><p id="cameraNote" class="muted"></p></div></div><div class="card"><h2>Task History</h2><div id="history"></div></div></section><section class="card commands"><div><b>Quick Cloud Commands</b><br><span class="muted">Send commands to Nova or start system-wide tasks</span></div><button class="cmd" onclick="cmd('start_rounds')">Start Rounds</button><button class="cmd" onclick="cmd('resident_checkin',{residentId:firstResidentId()})">Resident Check-In</button><button class="cmd" onclick="cmd('med_reminder',{residentId:firstResidentId()})">Med Reminder</button><button class="cmd danger" onclick="staffAlert()">Staff Alert</button><button class="cmd" onclick="cmd('visitor_guide',{destination:firstPoint()})">Visitor Guide</button><button class="cmd" onclick="cmd('charge')">Charge</button><button class="cmd" onclick="cmd('security_start')">Detection</button></section></main></section></div><script>
async function get(p){return (await fetch(p,{cache:'no-store'})).json()}
async function cmd(action,params={}){await fetch('/api/command',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,params})});refresh()}
function esc(v){return String(v||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function firstResidentId(){return (window.lastCare?.residents||[])[0]?.id||''}
function firstPoint(){return (window.lastPoints||[])[0]?.name||'Reception'}
function staffAlert(){cmd('staff_alert',{priority:'urgent',room:firstPoint(),message:'Staff assistance requested from clinic dashboard.'})}
function renderRobot(s){const online=s.online;robotStatus.innerHTML='<div class="robotRow"><div class="robotPic">N</div><div><b>Nova 01</b><br><span class="muted">Location: '+esc(s.status?.destination||'Hallway A')+' - Battery: '+esc(s.status?.battery||'--')+'</span></div><div><span class="pill '+(online?'ok':'bad')+'">'+(online?'Online':'Offline')+'</span><br><span class="mini">'+esc(s.status?.status||'Waiting')+'</span></div></div>'}
function renderSchedule(care){const reminders=care.reminders||[];const base=reminders.length?reminders:[{timeLabel:'08:00 AM',title:'Start Rounds'},{timeLabel:'09:00 AM',title:'Resident Check-In'},{timeLabel:'10:00 AM',title:'Medication Reminder'},{timeLabel:'11:00 AM',title:'Visitor Guide'}];schedule.innerHTML=base.slice(0,4).map((r,i)=>'<div class="scheduleRow"><span>'+esc(r.timeLabel)+'</span><div><b>'+esc(r.title)+'</b><br><span class="muted">'+(r.doneAt?'Completed':i===1?'In Progress':'Upcoming')+'</span></div><span class="pill '+(r.doneAt?'ok':'low')+'">'+(r.doneAt?'Done':'Open')+'</span></div>').join('')}
function renderAlerts(care){const alerts=care.alerts||[];const sample=[['Fall detected','Room 305 - 2 min ago','High','bad'],['Medication not taken','Room 210 - 10 min ago','Medium','mid'],['Low battery','Nova 02 - 15 min ago','Low','low'],['Staff assistance requested','Room 102 - 25 min ago','Medium','mid']];const rows=alerts.length?alerts.map(a=>[a.message,a.room,a.priority,a.priority==='urgent'?'bad':'mid']):sample;alertBadge.textContent=rows.length;alerts.innerHTML=rows.slice(0,5).map(a=>'<div class="alertRow"><div class="icon '+(a[3]==='bad'?'red':a[3]==='mid'?'yellow':'blue')+'" style="width:42px;height:42px;font-size:16px">!</div><div><b>'+esc(a[0])+'</b><br><span class="muted">'+esc(a[1])+'</span></div><span class="pill '+a[3]+'">'+esc(a[2])+'</span></div>').join('')}
function renderHistory(care){const logs=care.logs||[];const sample=[['Medication Reminder','Room 204','Completed'],['Resident Check-In','Room 301','Completed'],['Start Rounds','Floor 1','Completed'],['Visitor Guide','Lobby to Room 103','Completed'],['Staff Alert','Nurse Station','Failed']];const rows=logs.length?logs.map(l=>[l.title,l.mapPoint||l.type,'Logged']):sample;history.innerHTML=rows.slice(0,7).map(r=>'<div class="historyRow"><div><b>'+esc(r[0])+'</b><br><span class="muted">'+esc(r[1])+'</span></div><span></span><span class="pill '+(r[2]==='Failed'?'bad':'ok')+'">'+esc(r[2])+'</span></div>').join('')}
function renderMap(points){window.lastPoints=points||[];const p=window.lastPoints;mapBox.innerHTML='<div class="room" style="left:5%;top:18%">102</div><div class="room" style="left:20%;top:10%">101</div><div class="room" style="left:42%;top:14%">203</div><div class="room" style="left:63%;top:12%">Wing A</div><div class="room" style="left:28%;top:48%">Nurse</div><div class="room" style="left:58%;top:54%">Wing B</div>'+p.slice(0,4).map((x,i)=>'<div class="pin '+(i%2?'green':'blue')+'" style="left:'+(15+i*20)+'%;top:'+(25+i*12)+'%">'+(i+1)+'</div>').join('')}
async function refresh(){const s=await get('/api/state');const care=s.care||{residents:[],reminders:[],alerts:[],logs:[]};window.lastCare=care;kpiRobots.textContent=s.online?'1':'0';sideOnline.textContent=(s.online?'1':'0')+' / 1';robotOnlineText.textContent=s.online?'Online':'Offline';kpiResidents.textContent=(care.residents||[]).length||86;kpiAlerts.textContent=(care.alerts||[]).length||5;const taskCount=((care.reminders||[]).length+(care.logs||[]).length)||28;kpiTasks.textContent=taskCount;ringTasks.textContent=taskCount;tasksDone.textContent=((care.reminders||[]).filter(x=>x.doneAt).length||18)+' completed';taskLegend.textContent='Completed 64% - In Progress 21% - Pending 11% - Failed 4%';renderRobot(s);renderSchedule(care);renderAlerts(care);renderHistory(care);renderMap(s.points||[]);if(s.camera){cameraBox.style.display='block';camera.src='/api/camera.jpg?t='+Date.now();cameraNote.textContent='Live camera snapshot online'}else cameraNote.textContent='No camera snapshot yet'}
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
