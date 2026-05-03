# Nova People Messenger

Android/Kotlin app for OrionStar GreetingBot Nova / AgentOS reception workflows.

## What It Does

- Follows a nearby person using Orion RobotAPI body/person-shape detection, without requiring face identity.
- Navigates to a named map point such as `Reception`, `Meeting Room A`, or `VIP Desk`.
- Runs a Guest Assist mode that greets nearby guests, listens for intent, and offers to take a message, follow, or guide.
- Asks: `What do you want <point name> to know?`
- Records the original spoken message, or uses local speech-to-text for a text-only message.
- At the destination, Nova can replay the original recording or speak the transcribed/typed text.
- Adds useful client features: branded wallpaper UI, map point lookup, save-current-location, message templates, battery display, stop controls, and auto-charge.
- Adds Healthcare / Elder Care mode with resident check-ins, staff alerts, medication/appointment reminders, family message delivery, visitor guidance, safety detection, and care logs.

## OrionStar/AgentOS Notes

The implementation follows the current OrionStar split:

- AgentOS SDK: speech/TTS/intelligent interaction layer.
- RobotAPI: local robot hardware layer, including person detection, body list, movement, navigation, map points, battery and charging.

The robot adapter uses reflection, so the app can open in Android Studio before Orion SDK artifacts are installed. On the robot, add the matching SDK/JAR/AAR files to:

```text
app/libs/
```

The Gradle project already includes:

```kotlin
implementation("com.orionstar.agent:sdk:0.4.5-SNAPSHOT")
```

The project is configured with:

- `vendor/maven`: a local mirror containing the downloaded OrionStar AgentOS SDK, base SDK, and robot core artifacts.
- OrionStar's public Maven repository from the AgentOS docs as a fallback.
- `app/libs/robotservice_11.3.jar`: the RobotAPI JAR from the downloaded SDK repo, kept beside the app for direct reference.

## Install On Nova

1. Open this folder in Android Studio:

   ```text
   D:\Codex Projects\nova-people-messenger
   ```

2. Copy the matching OrionStar RobotAPI / AgentOS SDK artifacts into:

   ```text
   D:\Codex Projects\nova-people-messenger\app\libs
   ```

3. Build an APK from Android Studio.

4. Install on Nova with Android Studio device deployment or `adb install`.

5. On Nova, set it as a boot/default app if desired. The manifest includes the Orion default-app intent filter:

   ```xml
   <action android:name="action.orionstar.default.app" />
   ```

## Robot Requirements

- Nova must already have a created map and be localized on that map.
- The destination must be a saved map point with the exact name typed in the app.
- LIDAR/sensors must be unblocked for navigation.
- For shape following, keep the target person roughly 1-3 meters in front of the robot.
- For Guest Assist, microphone permission must be granted and RobotAPI/AgentOS must be available on Nova.
- Do not open the camera directly in another app while using RobotAPI vision/person detection.

## Phone / Internet Control

The production internet console is now the Render cloud relay:

```text
https://your-relay.onrender.com
```

Login:

```text
Username: <your-admin-user>
Password: <your-password>
```

Nova connects outbound to this relay, so a phone can reach it from anywhere without keeping a laptop connected by ADB. The relay can show Nova online status, map points, camera snapshots, people detections, residents, reminders, alerts, and care logs. It can send commands for visitor guide, family message delivery, staff alert, check-in round, resident check-in, medication reminder, safety detection, camera open/close, charge, and emergency stop.

Healthcare workflow:

1. Open the Care tab on Nova or the cloud relay on your phone.
2. Use Start Check-In Round for resident rounds.
3. Use Staff Alert for urgent help requests.
4. Use Medication for the next reminder, or choose a resident/reminder from the cloud console.
5. Use Family Message to send text to a named point or resident area.
6. Use Visitor Guide to guide visitors to a mapped destination.
7. Use Safety Watch for person-shape detection events.
8. Review Alerts and Care Log for handoff notes.

Nova must still have correct map points and localization. The seeded resident rooms are editable in `CareRepository.kt`; for a client deployment, change each resident `mapPoint` to an exact Nova map-point name.

The app includes a protected phone console on Nova:

```text
http://<nova-ip-address>:8787
```

Default login:

```text
Username: <your-admin-user>
Password: <your-password>
```

From the same Wi-Fi network, open the URL shown on Nova's home screen. The console can start/stop follow, use Door Follow, guide to map points, send a typed message to a point, start detection watch, stop the robot, and view RobotAPI status, detected people, and loaded map points.

For internet access from your phone, use a private tunnel/VPN first, then open the same port through that private address.

Recommended setup:

1. Install Tailscale, ZeroTier, or WireGuard on your phone.
2. Put a matching VPN/tunnel node on the same network as Nova. This can be the router, a Windows mini PC, or another always-on device.
3. Confirm the phone can reach Nova's local IP through the private tunnel.
4. Open:

   ```text
   http://<nova-private-vpn-ip-or-routed-lan-ip>:8787
   ```

5. Log in with the credentials above.

Do not public-port-forward Nova directly to the internet unless you add HTTPS, rotate the password, and restrict source IPs. For a real client deployment, the safest next step is a cloud relay or VPN-managed device identity rather than an open inbound port.

The phone console includes:

- `/status`: robot/app status.
- `/points`: loaded named map points.
- `/people`: RobotAPI person-shape detections.
- `/detection`: camera permission, detection-watch state, event count, and current detected people count.
- `/camera.jpg`: latest secured camera snapshot when Camera Feed is open.
- `/control?action=camera_start`: opens the Camera page and starts the secured snapshot feed.
- `/control?action=camera_stop`: closes the camera feed.
- `/control?action=security_start`: starts person-detection watch.
- `/control?action=security_stop`: stops person-detection watch.

Camera Feed and Detection Watch are separate controls. Detection Watch uses Orion/Nova RobotAPI person-shape detection. Camera Feed uses Android Camera2 snapshots for remote viewing. On Nova hardware, raw camera access can compete with the robot vision stack, so use Detection Watch for people scanning and Camera Feed when you need to visually inspect the area.

Current verified Nova URL from the installed debug app:

```text
http://<nova-ip>:8787
```

To reach that URL from outside the building with the Windows machine as the tunnel bridge:

1. Log in to Tailscale on this Windows machine.
2. Log in to Tailscale on your phone with the same account.
3. Advertise and approve the LAN route that contains Nova:

   ```text
   <your-local-subnet>
   ```

4. From the phone, connect Tailscale and open:

   ```text
   http://<nova-ip>:8787
   ```

## Important Files

- `MainActivity.kt`: operator UI and message workflow.
- `CareRepository.kt`: resident profiles, reminders, staff alerts, and care logs.
- `CloudRelayClient.kt`: outbound Nova-to-cloud command and state sync.
- `ReflectionRobotAdapter.kt`: Orion RobotAPI/AgentOS integration layer.
- `VoiceMessageManager.kt`: local recording, playback, speech recognition, and TTS fallback.
- `MessageRepository.kt`: on-device saved message queue.
- `RemoteControlServer.kt`: protected phone console and JSON control endpoints.
- `cloud-relay/server.js`: Render-hosted internet dashboard and relay API.
