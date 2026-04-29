# Free Cloud Relay Setup

This adds real internet access without keeping the laptop connected to Nova.

## Recommended Free Host

Use Render Free Web Service for the first prototype. Render currently offers free web services for preview/hobby projects, with limitations. It is good enough for testing the Nova relay, but not a production SLA.

## Deploy On Render

1. Push this project to GitHub.
2. Go to Render and create a new **Web Service**.
3. Select the repo.
4. Use these settings:

   ```text
   Root Directory: cloud-relay
   Runtime: Node
   Build Command: npm install
   Start Command: npm start
   ```

5. Add environment variables:

   ```text
   ADMIN_USER=admin
   ADMIN_PASS=nova2026
   ROBOT_TOKEN=make-a-long-random-token
   ```

6. Deploy. Render will give you a URL like:

   ```text
   https://nova-cloud-relay.onrender.com
   ```

## Configure Nova

For the debug app, set the cloud URL and robot token with ADB:

```powershell
adb shell run-as com.codex.novamessenger.debug sh -c "mkdir -p shared_prefs && cat > shared_prefs/nova_app_settings.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name='destination'>Reception Point</string>
    <string name='client'></string>
    <string name='cloud_url'>https://YOUR-RENDER-URL.onrender.com</string>
    <string name='cloud_token'>YOUR_ROBOT_TOKEN</string>
</map>
EOF"
```

Then restart the app:

```powershell
adb shell am force-stop com.codex.novamessenger.debug
adb shell am start -n com.codex.novamessenger.debug/com.codex.novamessenger.MainActivity
```

## Use From Phone

Open the Render URL on your phone:

```text
https://YOUR-RENDER-URL.onrender.com
```

Login:

```text
admin
nova2026
```

The phone dashboard can send commands to Nova through the cloud relay. Nova polls the relay every 2 seconds, so commands may take a moment.

## Notes

- Camera snapshots upload only when Nova's Camera Feed is open.
- Detection Watch and Camera Feed are separate because Nova's RobotAPI vision can conflict with raw Camera2 access.
- Render free services may sleep. The first phone load can take time while the server wakes.
