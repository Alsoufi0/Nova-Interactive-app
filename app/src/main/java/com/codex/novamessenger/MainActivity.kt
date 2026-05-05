package com.codex.novamessenger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MainActivity : Activity() {
    internal lateinit var robot: RobotAdapter
    internal lateinit var follow: ShapeFollowController
    internal lateinit var voice: VoiceMessageManager
    internal lateinit var repo: MessageRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var remoteServer: RemoteControlServer
    internal lateinit var cameraFeed: CameraFeedManager
    private lateinit var cloudRelay: CloudRelayClient
    internal lateinit var careRepo: CareRepository

    private val visionManager = VisionManager()
    private val pendingCloudCommands = ConcurrentHashMap<String, CloudRelayClient.CloudCommand>()

    internal lateinit var statusView: TextView
    internal lateinit var sdkBadge: TextView
    internal lateinit var queueBadge: TextView
    internal lateinit var clientInput: EditText
    internal lateinit var pointInput: Spinner
    internal lateinit var messageInput: EditText
    internal lateinit var messageList: LinearLayout
    internal lateinit var pointsList: LinearLayout
    internal lateinit var recordingBadge: TextView
    internal lateinit var assistBadge: TextView
    private lateinit var scrollView: ScrollView

    val vm = NovaViewModel()

    internal var currentPage = "home"
    internal var speechRecognizer: SpeechRecognizer? = null
    internal lateinit var careWorkflow: CareWorkflow
    internal lateinit var messageDelivery: MessageDelivery
    internal lateinit var guestAssist: GuestAssist

    // State delegated to ViewModel
    internal var lastStatus: String get() = vm.lastStatus; set(v) { vm.lastStatus = v }
    internal var lastBattery: String get() = vm.lastBattery; set(v) { vm.lastBattery = v }
    internal var clientName: String get() = vm.clientName; set(v) { vm.clientName = v }
    internal var selectedDestination: String get() = vm.selectedDestination; set(v) { vm.selectedDestination = v }
    internal var messageDraft: String get() = vm.messageDraft; set(v) { vm.messageDraft = v }
    internal var recordingPath: String? get() = vm.recordingPath; set(v) { vm.recordingPath = v }
    internal var isRecording: Boolean get() = vm.isRecording; set(v) { vm.isRecording = v }
    internal var autoDeliverAfterRecording: Boolean get() = vm.autoDeliverAfterRecording; set(v) { vm.autoDeliverAfterRecording = v }
    internal var guestAssistEnabled: Boolean get() = vm.guestAssistEnabled; set(v) { vm.guestAssistEnabled = v }
    internal var securityEnabled: Boolean get() = vm.securityEnabled; set(v) { vm.securityEnabled = v }
    internal var securityEvents: Int get() = vm.securityEvents; set(v) { vm.securityEvents = v }
    private var lastSecurityAlertAt: Long get() = vm.lastSecurityAlertAt; set(v) { vm.lastSecurityAlertAt = v }
    internal var lastGuestGreetingAt: Long get() = vm.lastGuestGreetingAt; set(v) { vm.lastGuestGreetingAt = v }
    internal var lastMapPoints: List<MapPoint> get() = vm.lastMapPoints; set(v) { vm.lastMapPoints = v }
    internal var currentTaskTitle: String get() = vm.currentTaskTitle; set(v) { vm.currentTaskTitle = v }
    internal var currentTaskStage: String get() = vm.currentTaskStage; set(v) { vm.currentTaskStage = v }
    internal var currentTaskNext: String get() = vm.currentTaskNext; set(v) { vm.currentTaskNext = v }
    internal var currentTaskProgress: Int get() = vm.currentTaskProgress; set(v) { vm.currentTaskProgress = v }
    internal var lastDetectedPerson: String get() = vm.lastDetectedPerson; set(v) { vm.lastDetectedPerson = v }
    internal var safetyStopStatus: String get() = vm.safetyStopStatus; set(v) { vm.safetyStopStatus = v }
    internal var activeRoundIds: List<String> get() = vm.activeRoundIds; set(v) { vm.activeRoundIds = v }
    internal var activeRoundIndex: Int get() = vm.activeRoundIndex; set(v) { vm.activeRoundIndex = v }
    internal var voiceListening: Boolean get() = vm.voiceListening; set(v) { vm.voiceListening = v }
    internal val assistHandler = Handler(Looper.getMainLooper())
    private val securityHandler = Handler(Looper.getMainLooper())
    private val mapHandler = Handler(Looper.getMainLooper())
    private val batteryHandler = Handler(Looper.getMainLooper())
    internal val guestAssistTick = object : Runnable {
        override fun run() {
            if (!guestAssistEnabled) return
            guestAssist.observeGuestPresence()
            assistHandler.postDelayed(this, 1_500)
        }
    }
    private val securityTick = object : Runnable {
        override fun run() {
            if (!securityEnabled) return
            observeSecurity()
            securityHandler.postDelayed(this, 2_000)
        }
    }
    private val mapRefreshTick = object : Runnable {
        override fun run() {
            if (!isFinishing && ::robot.isInitialized && robot.isRobotSdkAvailable) {
                val stale = System.currentTimeMillis() - lastMapLoadAt > 60_000
                if (lastMapPoints.isEmpty() || stale) loadMapPoints(silent = lastMapPoints.isNotEmpty())
            }
            mapHandler.postDelayed(this, 15_000)
        }
    }
    private val batteryTick = object : Runnable {
        override fun run() {
            if (!isFinishing && ::robot.isInitialized) {
                Thread {
                    val info = runCatching { robot.batteryInfo() }.getOrDefault(lastBattery)
                    lastBattery = info
                    val pct = Regex("\\d+").find(info)?.value?.toIntOrNull()
                    val charging = info.contains("charging", ignoreCase = true)
                    if (charging) vm.batteryChargeTriggered = false
                    if (pct != null && pct <= vm.batteryLowPercent && !charging
                        && !vm.batteryChargeTriggered && !safetyStopStatus.contains("Stopped")) {
                        vm.batteryChargeTriggered = true
                        runOnUiThread {
                            speakReply("Battery is low at $pct percent. Going to charge now.")
                            setTask("Going to charge", "Low battery $pct%", "Reach charging station", 50)
                            setStatus("Low battery ($pct%). Auto-charging...")
                        }
                        careRepo.log("system", "Battery low", "Auto-charge triggered at $pct%.", null, null)
                        val result = robot.goCharge()
                        if (!result.ok) runOnUiThread { setStatus("Battery low. Charge failed: ${result.message}") }
                        runOnUiThread { setContentView(buildUi()) }
                    }
                }.start()
            }
            batteryHandler.postDelayed(this, 30_000)
        }
    }
    private var mapLoadInFlight = false
    private var lastMapLoadAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("nova_app_settings", Context.MODE_PRIVATE)
        repairCloudSettings()
        clientName = prefs.getString("client", "") ?: ""
        selectedDestination = prefs.getString("destination", "Reception") ?: "Reception"
        vm.roundWaitSeconds = prefs.getInt("round_wait_seconds", 22)
        vm.roundPromptSeconds = prefs.getInt("round_prompt_seconds", 12)
        vm.securityCooldownSeconds = prefs.getInt("security_cooldown_seconds", 30)
        vm.guestCooldownSeconds = prefs.getInt("guest_cooldown_seconds", 45)
        vm.returnToChargeAfterRound = prefs.getBoolean("return_to_charge_after_round", false)
        vm.homeBase = prefs.getString("home_base", "Reception") ?: "Reception"
        vm.afterMissionBehavior = prefs.getString("after_mission_behavior", "home_base") ?: "home_base"
        vm.batteryLowPercent = prefs.getInt("battery_low_percent", 20)
        robot = DirectNovaRobotAdapter(this)
        cameraFeed = CameraFeedManager(this)
        voice = VoiceMessageManager(this)
        repo = MessageRepository(this)
        careRepo = CareRepository(this)
        follow = ShapeFollowController(robot) { setStatus(it) }
        careWorkflow = CareWorkflow(this)
        messageDelivery = MessageDelivery(this)
        guestAssist = GuestAssist(this)
        remoteServer = RemoteControlServer(
            username = prefs.getString("remote_username", "admin") ?: "admin",
            password = localServerPassword(),
            statusProvider = { remoteStatus() },
            peopleProvider = { robot.getBodyTargets() },
            pointsProvider = { lastMapPoints },
            detectionProvider = { remoteDetectionStatus() },
            cameraFrameProvider = { cameraFeed.latestFrame() },
            commandHandler = { handleRemoteCommand(it) },
            careProvider = { careRepo.toJson().toString() }
        )
        cloudRelay = CloudRelayClient(
            cloudUrlProvider = { prefs.getString("cloud_url", DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL },
            tokenProvider = { prefs.getString("cloud_token", DEFAULT_ROBOT_TOKEN) ?: DEFAULT_ROBOT_TOKEN },
            stateProvider = { cloudState() },
            commandHandler = { handleCloudCommand(it) }
        )
        remoteServer.start()
        cloudRelay.start()
        setContentView(buildUi())
        Handler(Looper.getMainLooper()).postDelayed({ scrollView.smoothScrollTo(0, 0) }, 700)
        Handler(Looper.getMainLooper()).postDelayed({ requestPermissionsIfNeeded() }, 500)
        robot.connect {
            setStatus(it)
            if (it.contains("connected", ignoreCase = true)) {
                robot.stopFollowTarget()
                robot.stopMove()
                lastBattery = robot.batteryInfo()
                loadMapPoints()
            }
        }
        mapHandler.postDelayed(mapRefreshTick, 10_000)
        batteryHandler.postDelayed(batteryTick, 60_000)
        setStatus(if (robot.isRobotSdkAvailable) "RobotAPI is connecting. Controls will become live on Nova." else "Preview mode. RobotAPI was not found on this device.")
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) guestAssist.startGuestAssist(auto = true)
        }, 2_500)
        messageDelivery.refreshMessages()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        if (isRecording) messageDelivery.stopRecordingAndSave()
        super.onPause()
    }

    override fun onDestroy() {
        stopAll()
        assistHandler.removeCallbacksAndMessages(null)
        securityHandler.removeCallbacksAndMessages(null)
        mapHandler.removeCallbacksAndMessages(null)
        batteryHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        cameraFeed.stop()
        remoteServer.stop()
        cloudRelay.stop()
        voice.shutdown()
        super.onDestroy()
    }

    internal fun buildUi(): View {
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            setImageResource(com.codex.novamessenger.R.drawable.nova_concierge_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.95f
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(248, 235, 242, 243), 0)
            setPadding(dp(6), dp(4), dp(6), 0)
            addView(header())
            if (safetyStopStatus.contains("Stopped")) addView(emergencyStopBar())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        scrollView = scroll
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            background = rounded(Color.argb(215, 235, 242, 243), 0)
        }
        if (currentTaskProgress > 0 || safetyStopStatus.contains("Stopped")) root.addView(taskProgressStrip())
        when (currentPage) {
            "message"      -> root.addView(messagePage())
            "care"         -> root.addView(carePage())
            "destinations" -> root.addView(destinationsPage())
            "robot"        -> root.addView(robotPage())
            "camera"       -> root.addView(cameraPage())
            else           -> root.addView(homePage())
        }
        root.addView(bottomNav())
        scroll.addView(root)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(outer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return frame
    }

    internal fun goToDestination() {
        saveSettings()
        follow.stop()
        val dest = destination()
        setTask("Guiding to $dest", "Navigating", "Arrive at destination", 55)
        setStatus("Navigating to $dest...")
        val result = robot.startNavigation(dest) { setStatus(it) }
        if (!result.ok) setStatus(result.message)
    }

    internal fun stopAll() {
        runCatching { follow.stop() }
        runCatching { robot.stopNavigation() }
        runCatching { robot.stopMove() }
        runCatching { robot.stopFollowTarget() }
        activeRoundIds = emptyList()
        activeRoundIndex = -1
        safetyStopStatus = "Stopped (Safety)"
        currentTaskTitle = "Stopped"
        currentTaskStage = "Safety stop"
        currentTaskProgress = 0
        setStatus("Stopped (Safety) — all movement halted.")
    }

    internal fun resumeOperations() {
        safetyStopStatus = ""
        currentTaskTitle = "Nova Care Assistant"
        currentTaskStage = "Ready"
        currentTaskProgress = 0
        setStatus("Operations resumed. Ready for commands.")
    }

    internal fun loadMapPoints(silent: Boolean = false) {
        if (mapLoadInFlight) return
        mapLoadInFlight = true
        lastMapLoadAt = System.currentTimeMillis()
        if (!silent) setStatus("Loading Nova map points...")
        Thread {
            val points = robot.getMapPoints()
            runOnUiThread {
                mapLoadInFlight = false
                renderPoints(points, updateStatus = !silent)
                if (points.isEmpty() && !silent) {
                    setStatus("Map points are not ready yet. Nova will keep retrying in the background.")
                }
            }
        }.start()
    }

    internal fun renderPoints(points: List<MapPoint>, updateStatus: Boolean) {
        lastMapPoints = points
        if (!::pointsList.isInitialized) {
            if (::pointInput.isInitialized) refreshDestinationDropdown()
            if (updateStatus) setStatus("Loaded ${points.size} map points.")
            if (updateStatus && currentPage == "home" && !isFinishing) {
                Handler(Looper.getMainLooper()).postDelayed({ setContentView(buildUi()) }, 150)
            }
            return
        }
        pointsList.removeAllViews()
        if (points.isEmpty()) {
            if (updateStatus) setStatus("Map points are still loading. Press Load Points again if Nova just localized.")
            return
        }
        points.take(16).forEach { point ->
            pointsList.addView(compactCard("${point.name}  ${if (point.status == 0) "Ready" else "Restricted"}") {
                setDestinationText(point.name)
                saveSettings()
                setStatus("Selected ${point.name}.")
            })
        }
        if (::pointInput.isInitialized) refreshDestinationDropdown()
        if (updateStatus) setStatus("Loaded ${points.size} map points.")
    }

    internal fun saveCurrentPoint() {
        saveSettings()
        val result = robot.saveCurrentLocation(destination())
        setStatus(result.message)
    }

    private fun repairCloudSettings() {
        val token = prefs.getString("cloud_token", "").orEmpty().trim()
        val looksLikeBrokenEncryptedToken = token.contains("/") || token.contains("=") || token.startsWith("+")
        if (token.isBlank() || looksLikeBrokenEncryptedToken) {
            prefs.edit()
                .putString("cloud_url", DEFAULT_CLOUD_URL)
                .putString("cloud_token", DEFAULT_ROBOT_TOKEN)
                .apply()
        }
    }

    private fun localServerPassword(): String {
        val existing = prefs.getString("remote_password", "").orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("remote_password", generated).apply()
        Log.i("NovaRemote", "Generated local server password. Check Nova Control settings to view it.")
        return generated
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_SEND_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
                val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
                messageDelivery.handleVoiceSendAction(message, destination, sender)
            }
            COMMAND_VOICE_PHRASE -> {
                val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
                if (phrase.isNotBlank()) guestAssist.handleGuestIntent(phrase)
            }
        }
    }

    internal fun destination(): String {
        if (::pointInput.isInitialized) {
            selectedDestination = pointInput.selectedItem?.toString()?.trim().orEmpty().ifBlank { selectedDestination }
        }
        return selectedDestination.ifBlank { "Reception" }
    }

    internal fun currentClient(): String {
        if (::clientInput.isInitialized) clientName = clientInput.text.toString().trim()
        return clientName
    }

    internal fun currentMessage(): String {
        if (::messageInput.isInitialized) messageDraft = messageInput.text.toString().trim()
        return messageDraft
    }

    internal fun setDestinationText(value: String) {
        selectedDestination = value.ifBlank { "Reception" }
        if (::pointInput.isInitialized) selectDestinationInDropdown(selectedDestination)
    }

    internal fun setClientText(value: String) {
        clientName = value
        if (::clientInput.isInitialized) clientInput.setText(value)
    }

    internal fun setMessageText(value: String) {
        messageDraft = value
        if (::messageInput.isInitialized) messageInput.setText(value)
    }

    internal fun saveSettings() {
        currentClient()
        destination()
        prefs.edit()
            .putString("client", clientName)
            .putString("destination", selectedDestination)
            .apply()
    }

    internal fun setStatus(text: String) {
        lastStatus = text
        runOnUiThread {
            if (::statusView.isInitialized) statusView.text = text.take(110)
            if (::sdkBadge.isInitialized) sdkBadge.text = if (robot.isRobotSdkAvailable) "Robot mode" else "Preview mode"
            if (::assistBadge.isInitialized && !guestAssistEnabled) assistBadge.text = "Assist off"
        }
    }

    internal fun setTask(title: String, stage: String, next: String, progress: Int) {
        vm.setTask(title, stage, next, progress)
    }

    internal fun speakReply(text: String) {
        val result = robot.speak(text)
        if (!result.ok) voice.speak(text)
        setStatus(text)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7)
    }

    private fun checkCameraReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setStatus("Camera permission granted. Camera feed can be opened.")
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            setStatus("Camera permission requested.")
        }
    }

    internal fun startCameraFeed() {
        val previous = visionManager.forceAcquire(VisionMode.CAMERA_PREVIEW)
        if (previous == VisionMode.DETECTION_WATCH) {
            securityEnabled = false
            securityHandler.removeCallbacks(securityTick)
            setStatus("Detection Watch stopped. Opening raw camera feed.")
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            visionManager.release(VisionMode.CAMERA_PREVIEW)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            setStatus("Camera permission requested. Accept it on Nova, then open camera again.")
            return
        }
        cameraFeed.start()
        setStatus(cameraFeed.lastError)
        if (currentPage == "camera") setContentView(buildUi())
    }

    internal fun stopCameraFeed() {
        visionManager.release(VisionMode.CAMERA_PREVIEW)
        cameraFeed.stop()
        setStatus("Camera feed closed.")
        if (currentPage == "camera") setContentView(buildUi())
    }

    private fun prepareRobotVisionForMotion(next: () -> Unit) {
        if (cameraFeed.isRunning) {
            cameraFeed.stop()
            currentPage = "robot"
            setContentView(buildUi())
            setStatus("Camera feed closed. Releasing vision for RobotAPI person detection...")
            Handler(Looper.getMainLooper()).postDelayed(next, 3_500)
        } else {
            next()
        }
    }

    internal fun startFollowMode() {
        prepareRobotVisionForMotion {
            securityEnabled = false
            securityHandler.removeCallbacks(securityTick)
            setStatus("Starting follow. Please stand 1 to 3 meters in front of Nova.")
            follow.start()
        }
    }

    internal fun startNarrowFollowMode() {
        prepareRobotVisionForMotion {
            securityEnabled = false
            securityHandler.removeCallbacks(securityTick)
            setStatus("Narrow-aisle follow active. Keep 1 m ahead and stay centered.")
            follow.startNarrowMode()
        }
    }

    internal fun startDoorFollowMode() {
        prepareRobotVisionForMotion {
            securityEnabled = false
            securityHandler.removeCallbacks(securityTick)
            setStatus("Starting door follow. Stay centered in front of Nova.")
            follow.startDoorMode()
        }
    }

    internal fun startSecurityWatch() {
        if (!hasAudioPermission()) requestPermissionsIfNeeded()
        val previous = visionManager.forceAcquire(VisionMode.DETECTION_WATCH)
        if (previous == VisionMode.CAMERA_PREVIEW) {
            cameraFeed.stop()
            if (currentPage == "camera") setContentView(buildUi())
            setStatus("Camera feed closed. Detection Watch uses RobotAPI vision.")
        }
        securityEnabled = true
        securityEvents = 0
        lastSecurityAlertAt = 0L
        setStatus("Security watch started. Detecting people locally.")
        securityHandler.removeCallbacks(securityTick)
        securityHandler.post(securityTick)
        if (currentPage == "camera") setContentView(buildUi())
    }

    internal fun stopSecurityWatch() {
        securityEnabled = false
        visionManager.release(VisionMode.DETECTION_WATCH)
        securityHandler.removeCallbacks(securityTick)
        setStatus("Security watch stopped.")
        if (currentPage == "camera") setContentView(buildUi())
    }

    internal fun observeSecurity() {
        val targets = robot.getBodyTargets()
        if (targets.isEmpty()) {
            setStatus("Security watch: no person detected.")
            return
        }
        securityEvents += 1
        val nearest = targets.minByOrNull { it.distanceMeters }
        lastDetectedPerson = nearest?.let {
            "${"%.1f".format(it.distanceMeters)}m at ${destination()} ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
        } ?: "Detected ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
        val status = "Security watch: ${targets.size} person shape${if (targets.size == 1) "" else "s"} detected${nearest?.let { ", nearest ${"%.1f".format(it.distanceMeters)}m" } ?: ""}."
        setStatus(status)
        val now = System.currentTimeMillis()
        if (now - lastSecurityAlertAt > vm.securityCooldownSeconds * 1_000L) {
            lastSecurityAlertAt = now
            val spoken = robot.speak("Person detected.")
            if (!spoken.ok) voice.speak("Person detected.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Microphone and camera permissions are needed on Nova.", Toast.LENGTH_LONG).show()
        }
    }

    internal fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    internal fun hideKeyboard() {
        if (::messageInput.isInitialized) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(messageInput.windowToken, 0)
        }
    }

    private fun remoteStatus(): RemoteControlServer.RemoteStatus =
        RemoteControlServer.RemoteStatus(
            status = lastStatus,
            battery = lastBattery,
            destination = selectedDestination,
            points = lastMapPoints.size,
            queue = repo.pendingCount(),
            security = securityEnabled,
            url = "http://${localIpAddress()}:8787",
            taskTitle = currentTaskTitle,
            taskStage = currentTaskStage,
            taskProgress = currentTaskProgress,
            safetyStop = safetyStopStatus
        )

    private fun remoteDetectionStatus(): RemoteControlServer.DetectionStatus {
        val people = runCatching { robot.getBodyTargets().size }.getOrDefault(0)
        return RemoteControlServer.DetectionStatus(
            cameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            cameraPreview = cameraFeed.isRunning,
            securityWatch = securityEnabled,
            events = securityEvents,
            people = people,
            note = if (securityEnabled) {
                "Detection watch is active. Remote console receives RobotAPI person-shape detections and camera snapshots when available."
            } else if (cameraFeed.isRunning) {
                cameraFeed.lastError
            } else {
                "Open Camera Feed to preview Nova's view, or Start Watch to scan with RobotAPI person detection."
            }
        )
    }

    private fun cloudState(): CloudRelayClient.CloudState {
        val people = runCatching { robot.getBodyTargets() }.getOrDefault(emptyList())
        val detection = JSONObject()
            .put("cameraPermission", checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            .put("cameraPreview", cameraFeed.isRunning)
            .put("securityWatch", securityEnabled)
            .put("events", securityEvents)
            .put("people", people.size)
        val status = JSONObject()
            .put("status", lastStatus)
            .put("battery", lastBattery)
            .put("destination", selectedDestination)
            .put("points", lastMapPoints.size)
            .put("queue", repo.pendingCount())
            .put("robotSdk", robot.isRobotSdkAvailable)
            .put("taskTitle", currentTaskTitle)
            .put("taskStage", currentTaskStage)
            .put("taskNext", currentTaskNext)
            .put("taskProgress", currentTaskProgress)
            .put("safetyStop", safetyStopStatus)
            .put("lastDetectedPerson", lastDetectedPerson)
            .put("homeBase", vm.homeBase)
            .put("afterMissionBehavior", vm.afterMissionBehavior)
        robot.getRobotPose()?.let {
            status.put(
                "robotPose",
                JSONObject().put("x", it.x).put("y", it.y).put("theta", it.theta)
            )
        }
        return CloudRelayClient.CloudState(
            status = status,
            detection = detection,
            people = people,
            points = lastMapPoints,
            care = careRepo.toJson(),
            cameraJpeg = cameraFeed.latestFrame()
        )
    }

    private fun handleCloudCommand(command: CloudRelayClient.CloudCommand): String {
        if (command.id.isNotBlank()) pendingCloudCommands[command.id] = command
        if (command.action == "start_rounds") {
            val cloudResidents = command.rawParams.optJSONArray("residents")?.toCareResidents().orEmpty()
            cloudResidents.forEach { careRepo.upsertResident(it) }
            runOnUiThread {
                if (cloudResidents.isNotEmpty()) careWorkflow.startCareRound(cloudResidents)
                else careWorkflow.startCareRound()
                setContentView(buildUi())
            }
            return "accepted: start_rounds"
        }
        handleRemoteCommand(RemoteControlServer.RemoteCommand(command.action, command.params))
        return "accepted: ${command.action}"
    }

    private fun JSONArray.toCareResidents(): List<CareResident> =
        List(length()) { index -> optJSONObject(index) }
            .filterNotNull()
            .mapNotNull { item ->
                val id = item.optString("id").ifBlank { "cloud_res_${System.currentTimeMillis()}" }
                val name = item.optString("name").ifBlank { item.optString("residentName") }
                val room = item.optString("room")
                if (name.isBlank() && room.isBlank()) null else CareResident(
                    id = id,
                    name = name.ifBlank { "Resident $room" },
                    room = room,
                    mapPoint = item.optString("mapPoint").ifBlank { room.ifBlank { destination() } },
                    notes = item.optString("notes"),
                    checkInPrompt = item.optString("checkInPrompt").ifBlank {
                        "Hello ${name.ifBlank { "there" }}. I am checking in. Do you need anything?"
                    }
                )
            }

    internal fun completeCloudWorkflow(action: String, result: String, residentId: String? = null, ok: Boolean = true) {
        val match = pendingCloudCommands.entries.firstOrNull { entry ->
            val command = entry.value
            command.action == action && (residentId.isNullOrBlank() || command.params["residentId"].orEmpty() == residentId)
        } ?: return
        pendingCloudCommands.remove(match.key)
        cloudRelay.reportCommandResult(match.value, result, ok)
    }

    private fun handleRemoteCommand(command: RemoteControlServer.RemoteCommand): String {
        runOnUiThread {
            when (command.action) {
                "follow" -> startFollowMode()
                "door_follow" -> startDoorFollowMode()
                "stop" -> stopAll()
                "camera_check" -> {
                    currentPage = "camera"
                    checkCameraReady()
                    setContentView(buildUi())
                }
                "camera_start" -> {
                    currentPage = "camera"
                    startCameraFeed()
                }
                "camera_stop" -> stopCameraFeed()
                "guide" -> {
                    val dest = command.params["destination"]?.takeIf { it.isNotBlank() }
                        ?.let { careWorkflow.resolveMapPoint(it) }
                    if (dest != null) setDestinationText(dest)
                    goToDestination()
                }
                "charge" -> setStatus(robot.goCharge().message)
                "return_home" -> returnToHomeBase()
                "security_start" -> startSecurityWatch()
                "security_stop" -> stopSecurityWatch()
                "message" -> messageDelivery.handleVoiceSendAction(
                    command.params["message"].orEmpty(),
                    command.params["destination"].orEmpty(),
                    command.params["sender"].orEmpty().ifBlank { "phone" }
                )
                "visitor_guide" -> careWorkflow.runVisitorGuide(command.params["destination"].orEmpty())
                "start_rounds" -> careWorkflow.startCareRound()
                "resident_checkin" -> {
                    val residentId = command.params["residentId"].orEmpty()
                    val residentName = command.params["residentName"].orEmpty()
                    if (residentName.isNotBlank()) {
                        careWorkflow.runExternalResidentCheckIn(
                            residentId,
                            residentName,
                            command.params["room"].orEmpty(),
                            command.params["mapPoint"].orEmpty(),
                            command.params["notes"].orEmpty()
                        )
                    } else {
                        careWorkflow.runResidentCheckIn(residentId)
                    }
                }
                "med_reminder" -> {
                    val reminderId = command.params["reminderId"].orEmpty()
                    val residentName = command.params["residentName"].orEmpty()
                    if (reminderId.isNotBlank()) careWorkflow.runReminder(reminderId)
                    else if (residentName.isNotBlank()) {
                        careWorkflow.runExternalResidentCheckIn(
                            command.params["residentId"].orEmpty(),
                            residentName,
                            command.params["room"].orEmpty(),
                            command.params["mapPoint"].orEmpty(),
                            command.params["notes"].orEmpty().ifBlank { "Medication reminder requested from the care cloud." },
                            cloudAction = "med_reminder"
                        )
                    } else careWorkflow.runReminderForResident(command.params["residentId"].orEmpty())
                }
                "staff_alert" -> careWorkflow.createStaffAlert(
                    command.params["priority"].orEmpty(),
                    command.params["room"].orEmpty(),
                    command.params["message"].orEmpty()
                )
                "update_settings" -> {
                    command.params["round_wait_seconds"]?.toIntOrNull()?.coerceIn(5, 120)?.let { vm.roundWaitSeconds = it }
                    command.params["round_prompt_seconds"]?.toIntOrNull()?.coerceIn(3, 60)?.let { vm.roundPromptSeconds = it }
                    command.params["security_cooldown_seconds"]?.toIntOrNull()?.coerceIn(5, 300)?.let { vm.securityCooldownSeconds = it }
                    command.params["guest_cooldown_seconds"]?.toIntOrNull()?.coerceIn(10, 300)?.let { vm.guestCooldownSeconds = it }
                    command.params["return_to_charge_after_round"]?.let { vm.returnToChargeAfterRound = it == "true" }
                    command.params["home_base"]?.takeIf { it.isNotBlank() }?.let { vm.homeBase = careWorkflow.resolveMapPoint(it) }
                    command.params["after_mission_behavior"]?.takeIf { it in listOf("home_base", "stay", "charge", "ask") }?.let { vm.afterMissionBehavior = it }
                    saveTimingSettings()
                    saveHomeBaseSettings()
                    setStatus("Settings updated from cloud: wait=${vm.roundWaitSeconds}s prompt=${vm.roundPromptSeconds}s sec=${vm.securityCooldownSeconds}s guest=${vm.guestCooldownSeconds}s charge=${vm.returnToChargeAfterRound}")
                }
                "upsert_resident" -> {
                    val id = command.params["id"]?.ifBlank { null } ?: "res_${System.currentTimeMillis()}"
                    val name = command.params["name"].orEmpty()
                    if (name.isNotBlank()) {
                        careRepo.upsertResident(CareResident(
                            id = id,
                            name = name,
                            room = command.params["room"].orEmpty(),
                            mapPoint = command.params["mapPoint"].orEmpty().ifBlank { "Reception" },
                            notes = command.params["notes"].orEmpty(),
                            checkInPrompt = command.params["checkInPrompt"].orEmpty()
                                .ifBlank { "Hello. I am checking in. Do you need anything?" }
                        ))
                        setStatus("Resident $name saved.")
                    }
                }
                "delete_resident" -> {
                    val id = command.params["id"].orEmpty()
                    if (id.isNotBlank()) {
                        careRepo.deleteResident(id)
                        setStatus("Resident deleted.")
                    }
                }
                else -> setStatus("Unknown phone command: ${command.action}")
            }
            if (command.action in setOf("stop", "return_home", "start_rounds", "resident_checkin",
                    "med_reminder", "staff_alert", "visitor_guide", "update_settings",
                    "upsert_resident", "delete_resident")) {
                setContentView(buildUi())
            }
        }
        return "Command sent: ${command.action}"
    }

    internal fun cloudUrl(): String =
        prefs.getString("cloud_url", DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL

    internal fun cloudToken(): String =
        prefs.getString("cloud_token", DEFAULT_ROBOT_TOKEN) ?: DEFAULT_ROBOT_TOKEN

    internal fun saveTimingSettings() {
        prefs.edit()
            .putInt("round_wait_seconds", vm.roundWaitSeconds)
            .putInt("round_prompt_seconds", vm.roundPromptSeconds)
            .putInt("security_cooldown_seconds", vm.securityCooldownSeconds)
            .putInt("guest_cooldown_seconds", vm.guestCooldownSeconds)
            .putBoolean("return_to_charge_after_round", vm.returnToChargeAfterRound)
            .putInt("battery_low_percent", vm.batteryLowPercent)
            .apply()
    }

    internal fun returnToHomeBase() {
        val base = careWorkflow.resolveMapPoint(vm.homeBase)
        setDestinationText(base)
        setTask("Returning to base", "Navigating", vm.homeBase, 45)
        speakReply("Task complete. Returning to ${vm.homeBase}.")
        follow.stop()
        val result = robot.startNavigation(base) { status ->
            setStatus(status)
            if (careWorkflow.isArrivalStatus(status)) {
                setTask("At home base", "Ready", vm.homeBase, 0)
                setStatus("Arrived at home base: ${vm.homeBase}.")
            }
        }
        if (!result.ok) setStatus("Return to home base: ${result.message}")
    }

    internal fun handleAfterMission(taskName: String) {
        val waitMs = (vm.roundWaitSeconds * 1_000L).coerceAtLeast(3_000L)
        when (vm.afterMissionBehavior) {
            "home_base" -> {
                setStatus("$taskName complete. Waiting ${vm.roundWaitSeconds}s before returning to ${vm.homeBase}.")
                Handler(Looper.getMainLooper()).postDelayed({ returnToHomeBase() }, waitMs)
            }
            "charge" -> {
                speakReply("$taskName complete. I will wait ${vm.roundWaitSeconds} seconds, then go to charge.")
                Handler(Looper.getMainLooper()).postDelayed({ setStatus(robot.goCharge().message) }, waitMs)
            }
            "ask" -> speakReply("$taskName complete. I am standing by. Say return to base, go charge, or I will stay here.")
            else -> {} // "stay" — no movement
        }
    }

    internal fun saveHomeBaseSettings() {
        prefs.edit()
            .putString("home_base", vm.homeBase)
            .putString("after_mission_behavior", vm.afterMissionBehavior)
            .apply()
    }

    internal fun saveCloudSettings(url: String, token: String) {
        prefs.edit()
            .putString("cloud_url", url.trim().trimEnd('/').ifBlank { DEFAULT_CLOUD_URL })
            .putString("cloud_token", token.trim().ifBlank { DEFAULT_ROBOT_TOKEN })
            .apply()
        cloudRelay.stop()
        cloudRelay.start()
        setStatus("Cloud settings saved. Reconnecting...")
    }

    internal fun localIpAddress(): String =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "nova-ip"

    internal fun robotReadySoon(): Boolean = runCatching { robot.isRobotSdkAvailable }.getOrDefault(false)

    internal fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(PrimaryDark)
        setPadding(0, dp(18), 0, dp(8))
    }

    internal inner class FacilityMapView(
        private val pose: RobotPose?,
        private val points: List<MapPoint>,
        private val selectedPointName: String,
        private val alertRoom: String?
    ) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val area = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dp(12).toFloat()
            area.set(pad, pad, width - pad, height - pad)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(246, 249, 252)
            canvas.drawRoundRect(area, dp(12).toFloat(), dp(12).toFloat(), paint)

            val clip = Path().also { it.addRoundRect(area, dp(12).toFloat(), dp(12).toFloat(), Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)

            drawGrid(canvas)

            val mappable = points.filter { it.x != null && it.y != null }
            if (mappable.isEmpty()) {
                canvas.restore()
                drawCentered(canvas, "Map not loaded", area.centerX(), area.centerY() - dp(10), Muted, 14f)
                drawCentered(canvas, "Tap Refresh Points", area.centerX(), area.centerY() + dp(12), Primary, 13f)
                return
            }

            val xs = mappable.mapNotNull { it.x } + listOfNotNull(pose?.x)
            val ys = mappable.mapNotNull { it.y } + listOfNotNull(pose?.y)
            val rawMinX = xs.minOrNull()!!; val rawMaxX = xs.maxOrNull()!!
            val rawMinY = ys.minOrNull()!!; val rawMaxY = ys.maxOrNull()!!
            val mgnX = max(0.5, (rawMaxX - rawMinX) * 0.22)
            val mgnY = max(0.5, (rawMaxY - rawMinY) * 0.22)
            val originX = rawMinX - mgnX; val originY = rawMinY - mgnY
            val spanX = max(1.0, rawMaxX - rawMinX + mgnX * 2)
            val spanY = max(1.0, rawMaxY - rawMinY + mgnY * 2)

            fun toScreen(px: Double?, py: Double?): Pair<Float, Float>? {
                if (px == null || py == null) return null
                val sx = area.left + ((px - originX) / spanX).toFloat() * area.width()
                val sy = area.bottom - ((py - originY) / spanY).toFloat() * area.height()
                return sx.coerceIn(area.left + dp(6), area.right - dp(6)) to
                    sy.coerceIn(area.top + dp(6), area.bottom - dp(6))
            }

            val selectedPoint = mappable.firstOrNull { it.name.equals(selectedPointName, ignoreCase = true) }
            val selectedScreen = selectedPoint?.let { toScreen(it.x, it.y) }
            val robotScreen = pose?.let { toScreen(it.x, it.y) }

            // Dashed route line
            if (robotScreen != null && selectedScreen != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.argb(110, 0, 160, 210)
                paint.pathEffect = DashPathEffect(floatArrayOf(dp(9).toFloat(), dp(5).toFloat()), 0f)
                canvas.drawLine(robotScreen.first, robotScreen.second, selectedScreen.first, selectedScreen.second, paint)
                paint.pathEffect = null
            }

            // Points
            mappable.forEach { point ->
                val screen = toScreen(point.x, point.y) ?: return@forEach
                val isSelected = point.name.equals(selectedPointName, ignoreCase = true)
                val isAlert = alertRoom?.isNotBlank() == true &&
                    (point.name.contains(alertRoom, ignoreCase = true) || alertRoom.contains(point.name, ignoreCase = true))
                val color = when {
                    isAlert -> Danger
                    isSelected -> Primary
                    else -> Color.rgb(125, 152, 165)
                }
                val r = when { isSelected -> dp(9).toFloat(); isAlert -> dp(8).toFloat(); else -> dp(5).toFloat() }
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(30, 0, 0, 0)
                canvas.drawCircle(screen.first + dp(1), screen.second + dp(2), r, paint)
                paint.color = color
                canvas.drawCircle(screen.first, screen.second, r, paint)
                if (isSelected) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2).toFloat()
                    paint.color = color
                    canvas.drawCircle(screen.first, screen.second, r + dp(4), paint)
                }
                drawPointLabel(canvas, point.name, screen.first, screen.second, color, isSelected || isAlert)
            }

            // Robot
            if (robotScreen != null) {
                val rr = dp(11).toFloat()
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(30, 0, 0, 0)
                canvas.drawCircle(robotScreen.first + dp(2), robotScreen.second + dp(2), rr, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(robotScreen.first, robotScreen.second, rr, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3).toFloat()
                paint.color = Color.rgb(0, 178, 202)
                canvas.drawCircle(robotScreen.first, robotScreen.second, rr, paint)
                val hx = (robotScreen.first + Math.cos(pose.theta) * rr * 1.8).toFloat()
                val hy = (robotScreen.second - Math.sin(pose.theta) * rr * 1.8).toFloat()
                canvas.drawLine(robotScreen.first, robotScreen.second, hx, hy, paint)
                drawPointLabel(canvas, "Nova", robotScreen.first, robotScreen.second, PrimaryDark, true)
            }

            canvas.restore()
        }

        private fun drawGrid(canvas: Canvas) {
            val step = dp(28).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(40, 150, 180, 200)
            var gx = area.left + step
            while (gx < area.right) {
                var gy = area.top + step
                while (gy < area.bottom) {
                    canvas.drawCircle(gx, gy, dp(2).toFloat(), paint)
                    gy += step
                }
                gx += step
            }
        }

        private fun drawPointLabel(canvas: Canvas, text: String, cx: Float, cy: Float, color: Int, bold: Boolean) {
            val label = if (text.length > 14) text.take(13) + "…" else text
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.textSize = dp(if (bold) 12 else 11).toFloat()
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val tw = paint.measureText(label)
            val th = dp(14).toFloat()
            var lx = cx + dp(12)
            var ly = cy - dp(3)
            if (lx + tw + dp(8) > area.right - dp(4)) lx = cx - tw - dp(14)
            if (ly - th < area.top + dp(4)) ly = cy + dp(17)
            lx = lx.coerceIn(area.left + dp(4), area.right - tw - dp(4))
            ly = ly.coerceIn(area.top + th, area.bottom - dp(4))
            val bg = RectF(lx - dp(4), ly - th, lx + tw + dp(5), ly + dp(4))
            paint.color = Color.argb(220, 255, 255, 255)
            canvas.drawRoundRect(bg, dp(5).toFloat(), dp(5).toFloat(), paint)
            paint.color = color
            canvas.drawText(label, lx, ly, paint)
        }

        private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float, color: Int, sp: Float) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.textSize = sp * resources.displayMetrics.scaledDensity
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = color
            canvas.drawText(text, x - paint.measureText(text) / 2f, y, paint)
        }
    }

    internal fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Muted)
        setPadding(0, dp(10), 0, dp(6))
    }

    internal fun input(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine(true)
        textSize = 12f
        setTextColor(Text)
        setHintTextColor(Muted)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(Color.WHITE, dp(8), Stroke)
    }

    internal fun destinationDropdown(): Spinner = Spinner(this).apply {
        background = rounded(Color.WHITE, dp(8), Stroke)
        minimumHeight = dp(42)
        setPadding(dp(10), 0, dp(10), 0)
        adapter = destinationAdapter()
        val wanted = selectedDestination.lowercase()
        val match = (0 until adapter.count).firstOrNull { adapter.getItem(it)?.toString()?.lowercase() == wanted } ?: 0
        setSelection(match)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDestination = parent?.getItemAtPosition(position)?.toString().orEmpty().ifBlank { selectedDestination }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun destinationAdapter(): ArrayAdapter<String> {
        val names = destinationOptions()
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun destinationOptions(): List<String> {
        val points = lastMapPoints.map { it.name.trim() }.filter { it.isNotBlank() }
        return (points + selectedDestination.ifBlank { "Reception Point" } + listOf("Reception Point", "Gate Entrance", "Charging Point"))
            .distinctBy { it.lowercase() }
    }

    private fun refreshDestinationDropdown() {
        val current = selectedDestination
        pointInput.adapter = destinationAdapter()
        selectDestinationInDropdown(current)
    }

    private fun selectDestinationInDropdown(value: String) {
        if (!::pointInput.isInitialized) return
        val wanted = value.ifBlank { selectedDestination }.lowercase()
        val adapter = pointInput.adapter ?: return
        val index = (0 until adapter.count).firstOrNull {
            adapter.getItem(it)?.toString()?.lowercase() == wanted
        } ?: (0 until adapter.count).firstOrNull {
            val item = adapter.getItem(it)?.toString()?.lowercase().orEmpty()
            item.contains(wanted) || wanted.contains(item)
        } ?: 0
        pointInput.setSelection(index)
    }

    internal fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Card, dp(14), Color.rgb(210, 222, 224))
        setPadding(dp(10), dp(8), dp(10), dp(10))
        layoutParams = full().apply { bottomMargin = dp(8) }
    }

    internal fun compactCard(text: String, onClick: () -> Unit): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Text)
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
        layoutParams = full().apply { bottomMargin = dp(8) }
    }

    internal fun emptyState(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Muted)
        gravity = Gravity.CENTER
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    internal fun buttonRow(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, 0)
        children.forEachIndexed { index, child ->
            addView(child, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index != children.lastIndex) marginEnd = dp(8)
            })
        }
    }

    internal fun buttonGrid(columns: Int, vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val safeColumns = max(1, columns)
        children.toList().chunked(safeColumns).forEach { chunk ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, 0)
                chunk.forEachIndexed { index, child ->
                    addView(child, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index != chunk.lastIndex) marginEnd = dp(8)
                    })
                }
                repeat(safeColumns - chunk.size) {
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                }
            })
        }
    }

    internal fun actionButton(text: String, color: Int = Primary, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 10f
        minHeight = dp(34)
        minWidth = 0
        maxLines = 2
        includeFontPadding = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setAllCaps(false)
        background = rounded(color, dp(10))
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnClickListener { onClick() }
    }

    internal fun badge(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 8f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(color, dp(99))
        setPadding(dp(7), dp(5), dp(7), dp(5))
    }

    internal fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    internal fun full(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    internal fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal val messageListReady get() = ::messageList.isInitialized

    companion object {
        const val EXTRA_COMMAND = "com.codex.novamessenger.COMMAND"
        const val EXTRA_MESSAGE = "com.codex.novamessenger.MESSAGE"
        const val EXTRA_DESTINATION = "com.codex.novamessenger.DESTINATION"
        const val EXTRA_SENDER = "com.codex.novamessenger.SENDER"
        const val EXTRA_PHRASE = "com.codex.novamessenger.PHRASE"
        const val COMMAND_SEND_MESSAGE = "send_message"
        const val COMMAND_VOICE_PHRASE = "voice_phrase"
        private const val DEFAULT_CLOUD_URL = "https://nova-cloud-relay.onrender.com"
        private const val DEFAULT_ROBOT_TOKEN = "nova-demo-robot"
    }
}
