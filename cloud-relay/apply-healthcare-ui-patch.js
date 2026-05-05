const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, 'server.js');
let s = fs.readFileSync(file, 'utf8');
let changed = false;

function replaceOnce(find, repl, label) {
  if (!s.includes(find)) {
    console.log(`[skip] ${label} not found`);
    return;
  }
  s = s.replace(find, repl);
  changed = true;
  console.log(`[ok] ${label}`);
}

// 1) Rename staff-facing navigation labels without changing data-view IDs or JS logic.
const navReplacements = [
  ['Command Center', 'Care Dashboard'],
  ['Robot Feeds', 'Monitoring'],
  ['Care Rounds', 'Resident Rounds'],
  ['Map &amp; Messaging', 'Facility Map'],
  ['Operations Log', 'Activity Log']
];
for (const [a,b] of navReplacements) {
  if (s.includes(a)) { s = s.split(a).join(b); changed = true; console.log(`[ok] label ${a} -> ${b}`); }
}

// 2) Improve page title/subtitle defaults.
replaceOnce('id="pageTitle">Command Center', 'id="pageTitle">Care Dashboard', 'default page title');
replaceOnce('id="pageSubtitle">Live data from Nova and your facility registry.', 'id="pageSubtitle">Today’s resident care, alerts, rounds, and Nova readiness at a glance.', 'default page subtitle');

// 3) Add care-first CSS helpers. Keep existing styles intact.
const cssMarker = '.res-row:last-child{border-bottom:0}';
const careCss = `.res-row:last-child{border-bottom:0}\n.care-hero{display:grid;grid-template-columns:1.25fr .75fr;gap:14px;margin:0 0 14px}.care-focus{background:linear-gradient(135deg,#ffffff,#f3f8ff);border:1px solid #dfe9f6;border-radius:18px;padding:20px;box-shadow:0 2px 14px #1e3a6808}.care-focus h2{margin:0 0 6px;font-size:22px;letter-spacing:-.4px}.care-focus p{margin:0;color:#718198;font-size:13px}.care-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}.care-side-status{background:#071b31;color:white;border-radius:18px;padding:18px;box-shadow:0 8px 24px #071b3122}.care-side-status b{display:block;font-size:18px;margin-bottom:8px}.care-side-status span{display:block;color:#b8c9de;font-size:12px;margin-top:4px}.care-section-title{font-size:12px;font-weight:900;text-transform:uppercase;letter-spacing:.8px;color:#7c8da3;margin:18px 0 10px}.care-dashboard-grid{display:grid;grid-template-columns:1.15fr .85fr;gap:14px}.care-mini{border:1px solid #e8edf5;border-radius:14px;padding:13px;background:#fff}.care-mini b{display:block;font-size:14px}.care-mini span{display:block;font-size:12px;color:#8898b0;margin-top:3px}.care-status-rail{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:14px}.care-stat{border:1px solid #e8edf5;border-radius:14px;padding:14px;background:white}.care-stat span{font-size:10px;color:#95a8be;font-weight:800;text-transform:uppercase;letter-spacing:.6px}.care-stat b{font-size:24px;display:block;margin-top:5px}@media(max-width:960px){.care-hero,.care-dashboard-grid,.care-status-rail{grid-template-columns:1fr}}`;
replaceOnce(cssMarker, careCss, 'healthcare dashboard CSS');

// 4) Replace the first dashboard quick-action strip with a care-first hero + move actions under a clear heading.
const oldTiles = `<div class="g5">\n<button class="tile" onclick="cmd('start_rounds')"><div class="ti c-green">&#8635;</div>Start Rounds</button>\n<button class="tile" onclick="checkInSelected()"><div class="ti c-blue">&#10003;</div>Check-In</button>\n<button class="tile" onclick="medSelected()"><div class="ti c-yellow">&#9670;</div>Medication</button>\n<button class="tile" onclick="staffAlert()"><div class="ti c-red">!</div>Staff Alert</button>\n<button class="tile" onclick="guideSelected()"><div class="ti c-purple">&#8594;</div>Guide Visitor</button>\n</div>`;
const newTiles = `<div class="care-hero">\n  <section class="care-focus">\n    <h2>Today’s Care Overview</h2>\n    <p>Start with resident needs first: scheduled rounds, active alerts, next care tasks, and Nova readiness.</p>\n    <div class="care-status-rail">\n      <div class="care-stat"><span>Residents</span><b id="statResidents">—</b></div>\n      <div class="care-stat"><span>Rounds</span><b id="statRounds">—</b></div>\n      <div class="care-stat"><span>Alerts</span><b id="statAlertsMini">—</b></div>\n      <div class="care-stat"><span>Next Task</span><b id="statNextTask">—</b></div>\n    </div>\n  </section>\n  <aside class="care-side-status">\n    <b>Nova Readiness</b>\n    <span id="careRobotState">Robot connection pending</span>\n    <span id="careBatteryState">Battery: —</span>\n    <span id="careTaskState">Current task: —</span>\n    <div class="care-actions">\n      <button class="btn d" onclick="cmd('stop')">Emergency Stop</button>\n      <button class="btn p" onclick="cmd('return_home')">Return Home</button>\n      <button class="btn" onclick="cmd('charge')">Charge</button>\n    </div>\n  </aside>\n</div>\n<div class="care-section-title">Primary Care Actions</div>\n<div class="g5">\n<button class="tile" onclick="cmd('start_rounds')"><div class="ti c-green">&#8635;</div>Start Rounds<small>Begin scheduled resident visits</small></button>\n<button class="tile" onclick="checkInSelected()"><div class="ti c-blue">&#10003;</div>Check-In<small>Visit selected resident</small></button>\n<button class="tile" onclick="medSelected()"><div class="ti c-yellow">&#9670;</div>Medication<small>Deliver reminder</small></button>\n<button class="tile" onclick="staffAlert()"><div class="ti c-red">!</div>Staff Alert<small>Escalate assistance</small></button>\n<button class="tile" onclick="guideSelected()"><div class="ti c-purple">&#8594;</div>Guide Visitor<small>Escort to destination</small></button>\n</div>`;
replaceOnce(oldTiles, newTiles, 'care-first dashboard hero');

// 5) Add fallback runtime updater for the new care stats, without touching existing JS functions.
const scriptHook = '</script></body></html>`;';
const careJs = `\n<script>\n(function(){\n  function txt(id,v){var e=document.getElementById(id); if(e) e.textContent=v;}\n  function syncCareLabels(){\n    try{\n      var robot = window.lastState || window.state || window.data || {};\n      var status = robot.status || {};\n      txt('careRobotState', (robot.online || status.robotSdk) ? 'Online and ready' : 'Waiting for Nova connection');\n      txt('careBatteryState', 'Battery: ' + (status.battery || '—'));\n      txt('careTaskState', 'Current task: ' + (status.taskTitle || status.status || 'Ready'));\n      var residents = (robot.care && robot.care.residents) || [];\n      var alerts = (robot.care && robot.care.alerts) || [];\n      txt('statResidents', residents.length || '—');\n      txt('statAlertsMini', alerts.length || '0');\n      txt('statRounds', 'Today');\n      txt('statNextTask', status.taskTitle || 'Ready');\n    }catch(e){}\n  }\n  setInterval(syncCareLabels,1500);\n  document.addEventListener('DOMContentLoaded',syncCareLabels);\n})();\n</script>\n</script></body></html>`;
if (s.includes(scriptHook) && !s.includes('syncCareLabels')) {
  s = s.replace(scriptHook, careJs);
  changed = true;
  console.log('[ok] care dashboard runtime labels');
}

if (!changed) {
  console.log('No changes applied. server.js may already be patched or structure changed.');
  process.exit(0);
}

fs.writeFileSync(file, s);
console.log('\nDone. Review with: git diff cloud-relay/server.js');
console.log('Then test locally with: cd cloud-relay && node server.js');
