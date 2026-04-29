# Nova Healthcare / Elder Care Demo Runbook

This checklist is for a live demo of Nova as a care-center assistant: greeting,
resident check-ins, medication reminders, message delivery, visitor guidance,
staff alerts, people detection, camera feed, cloud control, and safety stop.

## Current Software Readiness

- Android app builds successfully.
- APK path:

```text
D:\Codex Projects\nova-people-messenger\app\build\outputs\apk\debug\app-debug.apk
```

- Cloud relay health is live:

```text
https://nova-cloud-relay.onrender.com/health
```

- Cloud dashboard:

```text
https://nova-cloud-relay.onrender.com
```

- Default admin login:

```text
admin / nova2026
```

- The cloud dashboard uses live robot feed only for robot status, people,
camera, and map points. If Nova is offline, those panels should show waiting or
offline states instead of pretending data exists.
- The cloud map now visualizes real RobotAPI map-point coordinates when Nova
reports them, plus Nova's current RobotAPI position when available.
- The operations log records cloud commands, staff alerts, resident imports, and
robot command results so a manager can prove what happened during the demo.

## Honest Demo Status

Ready for a controlled demo once installed on Nova, with these limits:

- Robot movement, map navigation, follow behavior, camera access, and AgentOS
voice action must be confirmed on the physical Nova after install.
- The current shell does not show a connected ADB device, so hardware behavior
has not been certified in this check.
- Audio message recording is ready.
- AgentOS natural voice trigger for sending messages is registered.
- In-app speech-to-text dictation is not a finished feature; use recorded audio
or typed text for tomorrow's demo.

## Before Installing Tomorrow

1. Fully charge Nova or keep the dock nearby.
2. Confirm Nova is localized on its facility map.
3. Confirm there are saved map points for the demo, for example:

```text
Reception
Room 204
Nurse Station
Lobby
Charging Station
```

4. The map point names in the app and cloud must match Nova's saved point names.
5. Test in an open hallway first, not near stairs, glass, tight furniture, or a
wall behind the robot.
6. Keep one person ready to tap `Stop` on Nova or the phone dashboard.

## Install On Nova

From the project folder:

```powershell
cd "D:\Codex Projects\nova-people-messenger"
C:\Users\Alaa` Saleh` Alsoufi\.android\platform-tools\adb.exe devices
C:\Users\Alaa` Saleh` Alsoufi\.android\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

If Android asks for permissions on first launch, allow:

- Microphone
- Camera
- Network access if prompted

Expected package for the debug install:

```text
com.codex.novamessenger.debug
```

## First Launch Verification

1. Open the app on Nova.
2. Confirm the top badge says `Robot mode`.
3. If it says `Preview mode`, RobotAPI did not connect. Reopen the app, then
restart Nova's RobotAPI/core service if needed.
4. Nova should greet automatically after a few seconds:

```text
Hello, I am Nova. I can take a message, guide guests, or follow a person...
```

5. Go to `Map` and tap `Refresh Points`.
6. Confirm real Nova map points appear.
7. Go to `Robot` and tap `Robot Check`.
8. Expected status includes:

```text
sdk=true
voice=true
points>0
battery=...
```

## Cloud / Phone Setup

1. Open this URL from the phone:

```text
https://nova-cloud-relay.onrender.com
```

2. Sign in:

```text
admin / nova2026
```

3. In the dashboard, confirm:

- `Online` badge appears after Nova has internet and the app is open.
- `Robot Feeds` shows real battery/status.
- `Map` shows real map points only after Nova reports them.
- `Camera` shows a frame only after `Open Camera` is triggered.
- `People Detection` changes only when Nova detects a person shape.

4. Upload the exact ZOX Robotics logo in `Settings > Brand Logo`.
5. Add a safer demo user in `Settings > User Access`, then use that account for
staff demo access instead of the default admin.

## Resident Registration

Use the cloud dashboard:

- `Residents > Add Resident` for manual entry.
- `Residents > Download Excel Template` for bulk import.

CSV / Excel columns:

```text
resident_id
full_name
room
map_point
wing
care_level
primary_contact_name
primary_contact_phone
medication_notes
mobility_notes
preferred_language
check_in_schedule
emergency_notes
```

Required columns:

```text
full_name, room
```

Important:

- `map_point` should be the exact Nova map point to navigate to.
- If a resident's room is not a saved map point, use the nearest valid map
point, such as `Room 204` or `Hallway A`.

## Main Demo Script

### 1. Healthcare Command Screen

On Nova, show the clean command screen:

- `Start Rounds`
- `Check-In`
- `Meds`
- `Alert`
- `Visitor Guide`
- Quick actions for charging, reception, stop, and manual control

Say:

```text
Nova is built for care-center front desk and hallway support. It can greet,
guide, take messages, run check-ins, remind residents, and alert staff.
```

### 2. Proactive Greeting

1. Stand 1 to 3 meters in front of Nova.
2. Wait for the greeting.
3. Nova should offer message, guide, or follow.

If it does not greet:

- Tap `Robot > Start Assist`.
- Confirm `Robot Check` detects people.
- Make sure camera feed is closed, because RobotAPI person detection should not
compete with raw camera preview.

### 3. Send A Message By Voice Intent

Say one of these:

```text
Can you send a message to reception?
I want to send a message.
Please tell reception I need help.
Send a message to the nurse station that room 204 needs water.
```

Expected:

- AgentOS launches the message flow.
- If content is missing, Nova asks what the destination should know.
- Nova records the message.
- `Stop + Save` stores it.
- `Deliver` or the cloud `message` command sends Nova to the destination.
- On arrival, Nova plays the recording or speaks the text.

Fallback:

- Use `Message > Record Message`.
- Tap `Stop + Save`.
- Tap `Deliver`.

### 4. Resident Check-In

From Nova:

1. Tap `Care`.
2. Select a resident card.
3. Tap `Check In`.

From cloud:

1. Open `Residents`.
2. Choose a resident.
3. Click the check-in action.

Expected:

- Nova navigates to the resident's `map_point`.
- On arrival, Nova speaks the resident check-in prompt.
- The action is added to the care log.

### 5. Medication Reminder

1. Tap `Meds` on Nova or `Medication` in cloud.
2. Pick the selected resident/reminder.

Expected:

- Nova navigates to the resident point.
- Nova speaks the medication reminder.
- Reminder is logged.

Important demo wording:

```text
Nova does not dispense medicine. It reminds and calls staff.
```

### 6. Staff Alert

1. Tap `Alert` on Nova or `Staff Alert` in cloud.
2. Nova should speak that staff has been alerted.
3. Cloud `Alerts` should show the alert.

### 7. Visitor Guide

1. Select a valid destination from `Map`.
2. Tap `Guide`.

Expected:

- Nova speaks that it will guide the visitor.
- RobotAPI navigation starts to the map point.
- The cloud map uses the real Nova map point coordinates where RobotAPI provides
them.
- If Nova reports its current pose, the dashboard shows an `N` marker for Nova.

### 8. Follow Demo

Use this only in an open hallway.

1. Stand centered 1.5 to 2 meters in front of Nova.
2. Tap `Robot > Follow`.
3. Walk slowly in a straight line first.
4. Then test a gentle turn.
5. Tap `Stop`.

For a narrow doorway, use:

```text
Robot > Door Follow
```

Expected:

- Nova should move forward only.
- It should not intentionally reverse.
- It uses obstacle-aware movement commands.

Fallback if follow loses the person:

- Stop.
- Stand centered again.
- Close raw camera feed if open.
- Restart `Follow`.
- Use `Door Follow` for tighter spaces.

### 9. Camera And Detection

From Nova:

1. Open `Camera`.
2. Tap `Open Camera` to preview a camera frame.
3. Tap `Close Camera`.
4. Tap `Start Watch` to use RobotAPI people detection.

From cloud:

1. Open `Robot Feeds`.
2. Click `Open Camera`.
3. Wait for the live frame.
4. Click `Detect`.

Important:

- Raw camera preview and RobotAPI people detection can compete for vision
resources. For follow/detection demos, close the raw camera first.

### 10. Manager Proof View

Open `Logs` in the cloud dashboard.

Expected proof entries:

- Resident registered/imported
- Message delivery requested
- Resident check-in requested
- Staff alert queued
- Detection/camera commands queued
- Robot command result
- Robot state updates showing people, points, and camera availability

## Safety Stop Procedure

Use any of these:

- Nova screen: `Stop`
- Cloud dashboard: `Emergency Stop`
- ADB if connected:

```powershell
C:\Users\Alaa` Saleh` Alsoufi\.android\platform-tools\adb.exe shell am force-stop com.codex.novamessenger.debug
```

After emergency stop:

1. Move Nova to a clear position.
2. Reopen the app.
3. Run `Robot Check`.

## Demo Pass Criteria

The demo passes if these are true:

- App opens in `Robot mode`.
- Nova greets or `Start Assist` makes it greet.
- `Robot Check` confirms voice, battery, people, and map points.
- Cloud dashboard changes from offline to online.
- Cloud map/people/camera panels show real robot feed when triggered.
- A resident check-in or visitor guide starts navigation.
- Message delivery records/saves and delivers to a saved map point.
- Staff alert appears in cloud.
- Follow moves forward in open space and stops immediately.
- `Stop` works from Nova and cloud.

## Known Demo Limits

- In-app dictation-to-text is not finished; demo recorded voice and typed text.
- Follow quality depends heavily on lighting, localization, and people staying
centered in view.
- A 65 cm doorway can be too narrow for a comfortable live demo depending on
Nova's chassis, local obstacle margins, and map quality. Use `Door Follow` only
with a spotter.
- Cloud data is in-memory on the free relay unless a persistent database is
added later. For tomorrow, upload/register residents before the demo.
