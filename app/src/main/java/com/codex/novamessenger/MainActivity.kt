package com.codex.novamessenger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            commandHandler = { handleRemoteCommand(it) }
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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        scrollView = scroll
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
            background = rounded(Color.argb(218, 239, 244, 244), 0)
        }
        scroll.addView(root)
        root.addView(header())
        when (currentPage) {
            "message" -> root.addView(messagePage())
            "care" -> root.addView(carePage())
            "destinations" -> root.addView(destinationsPage())
            "robot" -> root.addView(robotPage())
            "camera" -> root.addView(cameraPage())
            else -> root.addView(homePage())
        }
        root.addView(bottomNav())
        frame.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
        safetyStopStatus = "Stopped"
        setTask("Stopped", "Safety stop", "Awaiting operator", 0)
        setStatus("Stopped movement and navigation.")
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
            if (::statusView.isInitialized) statusView.text = text.take(72)
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
        if (now - lastSecurityAlertAt > 30_000L) {
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
            url = "http://${localIpAddress()}:8787"
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
        handleRemoteCommand(RemoteControlServer.RemoteCommand(command.action, command.params))
        return "Cloud command sent: ${command.action}"
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
                    command.params["destination"]?.takeIf { it.isNotBlank() }?.let { setDestinationText(it) }
                    goToDestination()
                }
                "charge" -> setStatus(robot.goCharge().message)
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
                            command.params["notes"].orEmpty().ifBlank { "Medication reminder requested from the care cloud." }
                        )
                    } else careWorkflow.runReminderForResident(command.params["residentId"].orEmpty())
                }
                "staff_alert" -> careWorkflow.createStaffAlert(
                    command.params["priority"].orEmpty(),
                    command.params["room"].orEmpty(),
                    command.params["message"].orEmpty()
                )
                else -> setStatus("Unknown phone command: ${command.action}")
            }
        }
        return "Command sent: ${command.action}"
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
            val pad = dp(14).toFloat()
            area.set(pad, pad, width - pad, height - pad)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(245, 250, 251)
            canvas.drawRoundRect(area, dp(14).toFloat(), dp(14).toFloat(), paint)
            drawFacilityBackdrop(canvas)
            val mappable = points.filter { it.x != null && it.y != null }
            if (mappable.isEmpty()) {
                drawCentered(canvas, "Map points not loaded", area.centerX(), area.centerY(), Muted, 18f)
                drawCentered(canvas, "Tap Refresh Points", area.centerX(), area.centerY() + dp(26), Primary, 14f)
                return
            }
            val xs = mappable.mapNotNull { it.x } + listOfNotNull(pose?.x)
            val ys = mappable.mapNotNull { it.y } + listOfNotNull(pose?.y)
            val minX = xs.minOrNull() ?: 0.0
            val maxX = xs.maxOrNull() ?: 1.0
            val minY = ys.minOrNull() ?: 0.0
            val maxY = ys.maxOrNull() ?: 1.0
            val spanX = max(0.5, maxX - minX)
            val spanY = max(0.5, maxY - minY)
            fun toScreen(px: Double?, py: Double?): Pair<Float, Float>? {
                if (px == null || py == null) return null
                val screenX = area.left + (((px - minX) / spanX).toFloat() * area.width()).coerceIn(0f, area.width())
                val screenY = area.bottom - (((py - minY) / spanY).toFloat() * area.height()).coerceIn(0f, area.height())
                return screenX to screenY
            }
            val selected = mappable.firstOrNull { it.name.equals(selectedPointName, ignoreCase = true) }
            val selectedScreen = selected?.let { toScreen(it.x, it.y) }
            val robotScreen = pose?.let { toScreen(it.x, it.y) }
            if (robotScreen != null && selectedScreen != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(4).toFloat()
                paint.color = Accent
                canvas.drawLine(robotScreen.first, robotScreen.second, selectedScreen.first, selectedScreen.second, paint)
            }
            mappable.forEach { point ->
                val screen = toScreen(point.x, point.y) ?: return@forEach
                val isSelected = point.name.equals(selectedPointName, ignoreCase = true)
                val isAlert = alertRoom?.isNotBlank() == true &&
                    (point.name.contains(alertRoom, ignoreCase = true) || alertRoom.contains(point.name, ignoreCase = true))
                paint.style = Paint.Style.FILL
                paint.color = when {
                    isAlert -> Danger
                    isSelected -> Primary
                    else -> Color.rgb(109, 128, 137)
                }
                canvas.drawCircle(screen.first, screen.second, if (isSelected) dp(8).toFloat() else dp(5).toFloat(), paint)
                if (isSelected || isAlert) {
                    drawLabel(canvas, if (isSelected) point.name else "Alert", screen.first + dp(10), screen.second - dp(8), paint.color)
                }
            }
            if (robotScreen != null) {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawCircle(robotScreen.first, robotScreen.second, dp(15).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(4).toFloat()
                paint.color = Color.rgb(33, 183, 199)
                canvas.drawCircle(robotScreen.first, robotScreen.second, dp(15).toFloat(), paint)
                drawLabel(canvas, "Nova", robotScreen.first + dp(18), robotScreen.second + dp(4), PrimaryDark)
            }
        }

        private fun drawFacilityBackdrop(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(230, 240, 243)
            val mainHall = RectF(area.left + area.width() * 0.08f, area.centerY() - dp(18), area.right - area.width() * 0.08f, area.centerY() + dp(18))
            canvas.drawRoundRect(mainHall, dp(18).toFloat(), dp(18).toFloat(), paint)
            val crossHall = RectF(area.centerX() - dp(20), area.top + dp(22), area.centerX() + dp(20), area.bottom - dp(22))
            canvas.drawRoundRect(crossHall, dp(18).toFloat(), dp(18).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = Color.rgb(203, 218, 223)
            val rooms = listOf(
                RectF(area.left + dp(18), area.top + dp(18), area.left + area.width() * 0.28f, area.top + area.height() * 0.33f),
                RectF(area.right - area.width() * 0.28f, area.top + dp(18), area.right - dp(18), area.top + area.height() * 0.33f),
                RectF(area.left + dp(18), area.bottom - area.height() * 0.33f, area.left + area.width() * 0.28f, area.bottom - dp(18)),
                RectF(area.right - area.width() * 0.28f, area.bottom - area.height() * 0.33f, area.right - dp(18), area.bottom - dp(18))
            )
            rooms.forEach { canvas.drawRoundRect(it, dp(10).toFloat(), dp(10).toFloat(), paint) }
            paint.style = Paint.Style.FILL
            paint.textSize = dp(11).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.rgb(112, 132, 139)
            canvas.drawText("Care wing", area.left + dp(28), area.top + dp(38), paint)
            canvas.drawText("Clinic", area.right - area.width() * 0.25f, area.top + dp(38), paint)
            canvas.drawText("Lobby", area.left + dp(28), area.bottom - dp(36), paint)
            canvas.drawText("Rooms", area.right - area.width() * 0.25f, area.bottom - dp(36), paint)
        }

        private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
            val label = if (text.length > 16) text.take(15) + "." else text
            paint.style = Paint.Style.FILL
            paint.textSize = dp(12).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            val labelWidth = paint.measureText(label)
            val rect = RectF(x - dp(5), y - dp(18), x + labelWidth + dp(7), y + dp(5))
            paint.color = Color.argb(235, 255, 255, 255)
            canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
            paint.color = color
            canvas.drawText(label, x, y, paint)
        }

        private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float, color: Int, sp: Float) {
            paint.style = Paint.Style.FILL
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
        textSize = 17f
        setTextColor(Text)
        setHintTextColor(Muted)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(Color.WHITE, dp(8), Stroke)
    }

    internal fun destinationDropdown(): Spinner = Spinner(this).apply {
        background = rounded(Color.WHITE, dp(8), Stroke)
        minimumHeight = dp(56)
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
        background = rounded(Card, dp(8), Color.rgb(220, 229, 230))
        setPadding(dp(8), dp(6), dp(8), dp(8))
        layoutParams = full().apply { bottomMargin = dp(6) }
    }

    internal fun compactCard(text: String, onClick: () -> Unit): View = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Text)
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
        layoutParams = full().apply { bottomMargin = dp(8) }
    }

    internal fun emptyState(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Muted)
        gravity = Gravity.CENTER
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(18), dp(20), dp(18), dp(20))
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
        textSize = 11f
        minHeight = dp(42)
        minWidth = 0
        maxLines = 2
        includeFontPadding = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setAllCaps(false)
        background = rounded(color, dp(8))
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { onClick() }
    }

    internal fun badge(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(color, dp(99))
        setPadding(dp(8), dp(6), dp(8), dp(6))
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
