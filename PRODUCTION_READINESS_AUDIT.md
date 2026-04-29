# Nova Care Assistant Production Readiness Audit

Last updated: 2026-04-29

## Demo-Critical Flows

| Flow | Current status | Current risk | Verification method |
| --- | --- | --- | --- |
| Nova boots and greets | Implemented with guest assist auto-start | Needs real-room noise testing | Open app on Nova and wait 3 seconds |
| "What can you do?" voice intent | Implemented | Speech recognizer may miss noisy phrases | Say multiple capability phrases |
| Message to reception | Implemented | Voice-only flow records audio when message body is omitted | Say "Can you send a message to reception?" |
| Message delivery | Implemented | Arrival callback wording differs by RobotAPI version | Send message to known map point |
| Visitor guide | Implemented | Depends on exact RobotAPI map-point names | Say "Take me to reception" |
| Staff alert | Implemented | Cloud needs Nova polling and token alignment | Say "I need help" and watch cloud alerts |
| Resident check-in from cloud | Implemented | Cloud resident data must include mapPoint/room | Register resident, press Check-In |
| Rounds continuation | Improved | Still needs real navigation validation across multiple rooms | Start rounds with 2+ residents |
| Camera feed | Implemented | Camera2 may conflict with RobotAPI detection | Open camera from cloud and Nova screen |
| Detection/security watch | Implemented | Depends on RobotAPI body target feed | Start Detect and stand in view |
| Cloud online/feed | Implemented | Render free service may sleep; robot token must match | Open dashboard, wait for heartbeat |
| Stop command | Implemented | Must be tested from voice, Nova UI, phone, and cloud | Start follow/navigation, issue stop |

## Fixes Completed In This Pass

- Added background retry for Nova map-point loading, instead of relying only on first connect or manual refresh.
- Fixed generic care voice commands so "check in" or "medication reminder" do not crash/fail when no resident name is spoken.
- Expanded message voice intent coverage for phrases like "message to", "can you tell", and "inform reception".
- Tuned follow profiles to remain forward-only but respond faster, especially in door-follow mode.
- Persisted cloud facility data in the cloud relay store: residents, reminders, alerts, and logs now survive server restarts alongside users/sessions/logo.
- Verified cloud relay syntax with `node --check`.
- Verified Android build with `assembleDebug`.

## Remaining Production Gaps

- `MainActivity.kt` is still a large UI/business-logic class. Production refactor should split UI, ViewModel/state, use cases, robot controller, and repositories.
- Cloud relay still uses a JSON file store. Production should use PostgreSQL or another managed database.
- Robot task execution needs a formal state machine with explicit `created`, `navigating`, `arrived`, `speaking`, `completed`, `failed`, and `cancelled` states.
- Camera and RobotAPI detection should be coordinated with one shared capability manager to avoid camera/detection conflicts.
- Follow mode still requires real hallway/door testing on Nova and site-specific safety tuning.
- Authentication is demo-safe, not production-grade. Add password reset, per-user roles, rate limiting, and session revocation before paid deployment.
