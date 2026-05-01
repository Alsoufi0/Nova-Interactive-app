package com.codex.novamessenger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.pm.ActivityInfo
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.TextureView
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var robot: RobotAdapter
    private lateinit var follow: ShapeFollowController
    private lateinit var voice: VoiceMessageManager
    private lateinit var repo: MessageRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var remoteServer: RemoteControlServer
    private lateinit var cameraFeed: CameraFeedManager
    private lateinit var cloudRelay: CloudRelayClient
    private lateinit var careRepo: CareRepository

    private val visionManager = VisionManager()

    private lateinit var statusView: TextView
    private lateinit var sdkBadge: TextView
    private lateinit var queueBadge: TextView
    private lateinit var clientInput: EditText
    private lateinit var pointInput: Spinner
    private lateinit var messageInput: EditText
    private lateinit var messageList: LinearLayout
    private lateinit var pointsList: LinearLayout
    private lateinit var recordingBadge: TextView
    private lateinit var assistBadge: TextView
    private lateinit var scrollView: ScrollView

    private var currentPage = "home"
    @Volatile private var lastStatus = "Starting Nova Concierge."
    @Volatile private var lastBattery = "Battery --"
    private var clientName = ""
    @Volatile private var selectedDestination = "Reception"
    private var messageDraft = ""
    private var recordingPath: String? = null
    private var isRecording = false
    private var autoDeliverAfterRecording = false
    @Volatile private var guestAssistEnabled = false
    @Volatile private var securityEnabled = false
    @Volatile private var securityEvents = 0
    private var lastSecurityAlertAt = 0L
    private var lastGuestGreetingAt = 0L
    @Volatile private var lastMapPoints: List<MapPoint> = emptyList()
    @Volatile private var currentTaskTitle = "Nova Care Assistant"
    @Volatile private var currentTaskStage = "Ready"
    @Volatile private var currentTaskNext = "Awaiting request"
    @Volatile private var currentTaskProgress = 0
    @Volatile private var lastDetectedPerson = "None"
    @Volatile private var safetyStopStatus = "Armed"
    private var activeRoundIds: List<String> = emptyList()
    private var activeRoundIndex = -1
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceListening = false
    private val assistHandler = Handler(Looper.getMainLooper())
    private val securityHandler = Handler(Looper.getMainLooper())
    private val mapHandler = Handler(Looper.getMainLooper())
    private val guestAssistTick = object : Runnable {
        override fun run() {
            if (!guestAssistEnabled) return
            observeGuestPresence()
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
            if (!isFinishing) startGuestAssist(auto = true)
        }, 2_500)
        refreshMessages()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        if (isRecording) stopRecordingAndSave()
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

    private fun buildUi(): View {
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

    private fun navRail(): View = buttonRow(
        navButton("Concierge", "home"),
        navButton("Care", "care"),
        navButton("Message", "message"),
        navButton("Map", "destinations"),
        navButton("Robot", "robot"),
        navButton("Camera", "camera")
    ).apply { setPadding(0, dp(10), 0, dp(4)) }

    private fun navButton(text: String, page: String): Button =
        actionButton(text, if (currentPage == page) Accent else PrimaryDark) {
            currentPage = page
            setContentView(buildUi())
            refreshMessages()
            if (currentPage == "destinations") renderPoints(lastMapPoints, updateStatus = false)
        }

    private fun bottomNav(): View = buttonRow(
        navButton("Home", "home"),
        navButton("Care", "care"),
        navButton("Map", "destinations"),
        navButton("Robot", "robot")
    ).apply { setPadding(0, dp(12), 0, 0) }

    private fun homePage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(homeCommandGrid())
        root.addView(buttonRow(
            actionButton("Stop Task", Danger) { stopAll() },
            actionButton("Guest Assist", Accent) { startGuestAssist(auto = false) }
        ))
        return root
    }

    private fun homeHero(): View {
        val box = card().apply {
            background = rounded(Color.rgb(7, 17, 26), dp(14), Color.rgb(33, 183, 199))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "Nova Care Assistant"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Visitor messages, care check-ins, guidance, follow, and safety watch."
            textSize = 12f
            setTextColor(Color.rgb(205, 228, 231))
            setPadding(0, dp(3), 0, dp(4))
        })
        box.addView(buttonRow(
            compactStatus("RobotAPI", if (robot.isRobotSdkAvailable) "Online" else "Preview"),
            compactStatus("Map", if (lastMapPoints.isEmpty()) "Load" else "${lastMapPoints.size} points")
        ))
        box.addView(buttonRow(
            compactStatus("Task", currentTaskStage),
            compactStatus("Safety", safetyStopStatus)
        ))
        return box
    }

    private fun homeCommandGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(buttonRow(
            homeCommandTile("Message", "Record and deliver", "M", Accent) {
                currentPage = "message"
                setContentView(buildUi())
            },
            homeCommandTile("Care", "Rounds and check-ins", "C", CareBlue) {
                currentPage = "care"
                setContentView(buildUi())
            },
            homeCommandTile("Map", "Destinations and guide", "P", Primary) {
                currentPage = "destinations"
                setContentView(buildUi())
            }
        ))
        grid.addView(buttonRow(
            homeCommandTile("Follow", "Person tracking", "F", Good) {
                currentPage = "robot"
                setContentView(buildUi())
                startFollowMode()
            },
            homeCommandTile("Camera", "Detection view", "V", PrimaryDark) {
                currentPage = "camera"
                setContentView(buildUi())
            },
            homeCommandTile("Alert", "Notify staff", "!", Danger) {
                createStaffAlert("urgent", destination(), "Assistance requested from Nova.")
            }
        ))
        return grid
    }

    private fun homeCommandTile(title: String, subtitle: String, code: String, color: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, dp(14), Color.rgb(218, 228, 232))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minimumHeight = dp(82)
            setOnClickListener { onClick() }
        }
        box.addView(circleIcon(code, color, dp(36)))
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(11, 25, 52))
            setPadding(0, dp(4), 0, 0)
        }, full())
        box.addView(TextView(this).apply {
            text = subtitle
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Muted)
        }, full())
        return box
    }

    private fun messagePage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHero("Message Delivery", "Record, save, and send a visitor message to a saved map point."))
        root.addView(twoPane(workflowCard(), messageQueuePanel(), 1.05f, 1f))
        refreshMessages()
        return root
    }

    private fun carePage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHero("Care Command", "Rounds, reminders, staff alerts, family delivery, and care logs."))
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(careCommandPanel())
            addView(careTodayPanel())
        }
        val right = residentsPanel()
        root.addView(twoPane(left, right, 0.82f, 1.18f))
        return root
    }

    private fun destinationsPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHero("Map Destinations", "Pick a named point. Nova uses RobotAPI navigation to guide or deliver."))
        root.addView(twoPane(facilityMapCard(), pointsPanel(), 1.35f, 0.65f))
        renderPoints(lastMapPoints, updateStatus = false)
        return root
    }

    private fun robotPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHero("Robot Systems", "Movement, follow, charging, voice, battery, and safety controls."))
        root.addView(twoPane(robotHealthCard(), robotActionsCard(), 1f, 1f))
        return root
    }

    private fun cameraPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHero("Camera", "Live camera feed, person detection, and security scanning."))
        val panel = card()
        panel.addView(TextView(this).apply {
            text = "Detection Camera"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        panel.addView(cameraPreviewView())
        panel.addView(detectionOverlayCard())
        root.addView(twoPane(panel, cameraActionsCard(), 1.25f, 0.75f))
        return root
    }

    private fun cameraActionsCard(): View {
        val panel = card()
        panel.addView(TextView(this).apply {
            text = "Detection Controls"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        val openCameraButton = actionButton("Open Camera", Primary) { startCameraFeed() }
        val closeCameraButton = actionButton("Close Camera", Neutral) { stopCameraFeed() }
        val startWatchButton = actionButton("Start Watch", Accent) { startSecurityWatch() }
        val stopWatchButton = actionButton("Stop Watch", Danger) { stopSecurityWatch() }
        val scanButton = actionButton("Scan Now", PrimaryDark) { observeSecurity() }
        scanButton.layoutParams = full().apply { topMargin = dp(8) }
        panel.addView(buttonRow(openCameraButton, closeCameraButton))
        panel.addView(buttonRow(startWatchButton, stopWatchButton))
        panel.addView(scanButton)
        panel.addView(buttonRow(
            compactStatus("Mode", if (securityEnabled) "Watching" else "Standby"),
            compactStatus("Camera", if (cameraFeed.isRunning) "Live" else "Closed")
        ))
        panel.addView(buttonRow(
            compactStatus("Detect", "${robot.getBodyTargets().size} people"),
            compactStatus("Events", "$securityEvents")
        ))
        return panel
    }

    private fun cameraPreviewView(): TextureView = TextureView(this).apply {
        layoutParams = full().apply {
            height = dp(355)
            bottomMargin = dp(8)
        }
        cameraFeed.bindPreview(this)
    }

    private fun messageQueuePanel(): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "Delivery Queue"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        messageList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(messageList, full().apply { topMargin = dp(6) })
        return box
    }

    private fun residentsPanel(): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "Residents"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        careRepo.residents().take(3).forEach { resident -> box.addView(residentCard(resident)) }
        box.addView(sectionTitle("Recent Activity"))
        val logs = careRepo.logs().take(3)
        if (logs.isEmpty()) box.addView(emptyState("No care activity yet.")) else logs.forEach { box.addView(careLogCard(it)) }
        return box
    }

    private fun pointsPanel(): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "Destinations"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        box.addView(buttonRow(
            actionButton("Refresh", Primary) { loadMapPoints() },
            actionButton("Save Here", Neutral) { saveCurrentPoint() }
        ))
        box.addView(actionButton("Guide Selected", Accent) { goToDestination() }.apply {
            layoutParams = full().apply { topMargin = dp(6) }
        })
        pointsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(pointsList, full().apply { topMargin = dp(8) })
        return box
    }

    private fun robotHealthCard(): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "Robot Health"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        box.addView(robotHealthPanel())
        box.addView(metricGrid())
        return box
    }

    private fun robotActionsCard(): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "Movement"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        box.addView(buttonRow(
            actionButton("Follow", Accent) { startFollowMode() },
            actionButton("Door Follow", PrimaryDark) { startDoorFollowMode() }
        ))
        box.addView(buttonRow(
            actionButton("Robot Check", Primary) { runRobotCheck() },
            actionButton("Stop", Danger) { stopAll() }
        ))
        box.addView(buttonRow(
            actionButton("Charge", PrimaryDark) { setStatus(robot.goCharge().message) },
            actionButton("Battery", Neutral) {
                lastBattery = robot.batteryInfo()
                setStatus(lastBattery)
            }
        ))
        box.addView(actionButton("Start Guest Assist", Accent) { startGuestAssist(auto = false) }.apply {
            layoutParams = full().apply { topMargin = dp(6) }
        })
        return box
    }

    private fun detectionOverlayCard(): View {
        val targets = runCatching { robot.getBodyTargets() }.getOrDefault(emptyList())
        val nearest = targets.minByOrNull { it.distanceMeters }
        val mode = when {
            securityEnabled -> "Security"
            follow.isRunning() -> "Follow"
            cameraFeed.isRunning -> "Camera"
            else -> "Normal"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow(
                compactStatus("Overlay", if (targets.isEmpty()) "No person" else "Person detected"),
                compactStatus("Distance", nearest?.let { "${"%.1f".format(it.distanceMeters)} m" } ?: "--")
            ))
            addView(buttonRow(
                compactStatus("Mode", mode),
                compactStatus("Phone", if (cameraFeed.hasFrame) "Feed ready" else "Waiting")
            ))
        }
    }

    private fun robotHealthPanel(): View {
        lastBattery = runCatching { robot.batteryInfo() }.getOrDefault(lastBattery)
        val mapStatus = if (lastMapPoints.isEmpty()) "Load points" else "${lastMapPoints.size} points"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow(
                compactStatus("Battery", lastBattery.replace("Battery ", "")),
                compactStatus("Charging", if (lastBattery.contains("charging", true)) "Charging" else "Standby")
            ))
            addView(buttonRow(
                compactStatus("Wi-Fi", localIpAddress()),
                compactStatus("RobotAPI", if (robot.isRobotSdkAvailable) "Connected" else "Offline")
            ))
            addView(buttonRow(
                compactStatus("Camera", if (cameraFeed.isRunning) "Live" else "Closed"),
                compactStatus("Map", mapStatus)
            ))
            addView(buttonRow(
                compactStatus("Task", currentTaskStage),
                compactStatus("Safety", safetyStopStatus)
            ))
        }
    }

    private fun legacyRobotHealthPanel(): View {
        lastBattery = runCatching { robot.batteryInfo() }.getOrDefault(lastBattery)
        val mapStatus = if (lastMapPoints.isEmpty()) "Load points" else "${lastMapPoints.size} points"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow(
                compactStatus("Battery", lastBattery.replace("Battery ", "")),
                compactStatus("Charging", if (lastBattery.contains("charging", true)) "Charging" else "Standby"),
                compactStatus("Wi-Fi", localIpAddress())
            ))
            addView(buttonRow(
                compactStatus("RobotAPI", if (robot.isRobotSdkAvailable) "Connected" else "Offline"),
                compactStatus("Camera", if (cameraFeed.isRunning) "Live" else "Closed"),
                compactStatus("Map", mapStatus)
            ))
            addView(buttonRow(
                compactStatus("Cloud Sync", "2 sec"),
                compactStatus("Current Task", currentTaskStage),
                compactStatus("Safety", safetyStopStatus)
            ))
        }
    }

    private fun twoPane(left: View, right: View, leftWeight: Float = 1f, rightWeight: Float = 1f): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, leftWeight).apply {
                marginEnd = dp(6)
            })
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, rightWeight))
        }

    private fun facilityMapCard(): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "Live Facility Map"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        val pose = runCatching { robot.getRobotPose() }.getOrNull()
        box.addView(FacilityMapView(pose, lastMapPoints, destination(), careRepo.alerts().firstOrNull()?.room).apply {
            layoutParams = full().apply {
                height = dp(285)
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        })
        box.addView(buttonRow(
            compactStatus("Points", if (lastMapPoints.isEmpty()) "Load map" else "${lastMapPoints.size} saved"),
            compactStatus("Nova", pose?.let { "${"%.1f".format(it.x)}, ${"%.1f".format(it.y)}" } ?: "Waiting")
        ))
        box.addView(buttonRow(
            compactStatus("Route", activePathText()),
            compactStatus("Person", lastDetectedPerson)
        ))
        box.addView(movementVisualPanel(), full().apply { topMargin = dp(8) })
        return box
    }

    private fun activePathText(): String =
        if (currentTaskProgress > 0) "Nova -> ${destination()} -> ${currentTaskNext}" else "No active route"

    private fun movementVisualPanel(): TextView {
        val moving = currentTaskStage.contains("navigat", true) || lastStatus.contains("navigat", true)
        val text = if (moving) {
            "Moving: Nova -> ${destination()}\nNext: ${currentTaskNext.ifBlank { "Arrive safely" }}"
        } else if (currentTaskProgress > 0) {
            "Task: $currentTaskStage\nNext: ${currentTaskNext.ifBlank { "Complete task" }}"
        } else {
            "Ready: choose a destination or start a care workflow."
        }
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (moving) Primary else Text)
            background = rounded(Color.rgb(246, 250, 250), dp(8), Stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun pageHero(title: String, subtitle: String): View {
        val box = card()
        box.background = rounded(Color.rgb(11, 17, 20), dp(8), Color.rgb(57, 78, 82))
        box.setPadding(dp(8), dp(5), dp(8), dp(6))
        box.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = subtitle
            textSize = 8f
            setTextColor(Color.rgb(198, 214, 214))
            setPadding(0, dp(1), 0, 0)
        })
        return box
    }

    private fun systemOverview(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(buttonRow(
            metricCard("VOICE", "AgentOS", if (robot.isRobotSdkAvailable) "Ready" else "Preview"),
            metricCard("MAP", "${lastMapPoints.size} points", destination()),
            metricCard("POWER", lastBattery, "Charging available")
        ))
        grid.addView(buttonRow(
            metricCard("FOLLOW", "Focus + shape", "Human aware"),
            metricCard("QUEUE", "${repo.pendingCount()} pending", "Delivery messages"),
            metricCard("SAFETY", "Stop armed", "Chassis guarded")
        ))
        return grid
    }

    private fun metricGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(buttonRow(
            metricCard("SDK", if (robot.isRobotSdkAvailable) "Connected" else "Offline", "RobotAPI"),
            metricCard("VOICE", "AgentOS TTS", "Action ready"),
            metricCard("BATTERY", lastBattery, "System")
        ))
        grid.addView(buttonRow(
            metricCard("POINTS", "${lastMapPoints.size}", "Named map stops"),
            metricCard("QUEUE", "${repo.pendingCount()}", "Pending delivery"),
            metricCard("FOLLOW", "Focus API", "Fallback motion")
        ))
        return grid
    }

    private fun metricCard(code: String, value: String, caption: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(18, 29, 32), dp(8), Color.rgb(62, 82, 86))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(TextView(this).apply {
            text = code
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(107, 207, 190))
        })
        box.addView(TextView(this).apply {
            text = value
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = caption
            textSize = 12f
            setTextColor(Color.rgb(177, 193, 195))
        })
        return box
    }

    private fun quickMessageBox(): View {
        val card = card()
        card.addView(TextView(this).apply {
            text = "Voice Command"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        card.addView(TextView(this).apply {
            text = "Say: send a message to Reception that I need help."
            textSize = 14f
            setTextColor(Muted)
            setPadding(0, dp(6), 0, dp(10))
        })
        card.addView(buttonRow(
            actionButton("Listen Now", Accent) { listenToGuest() },
            actionButton("Record Message", Primary) {
                currentPage = "message"
                setContentView(buildUi())
                askAndRecord()
            },
            actionButton("Use Help Message", Accent) {
                currentPage = "message"
                setContentView(buildUi())
                setMessageText("I need help. Please send someone.")
                sendCurrentMessageToPoint()
            }
        ))
        return card
    }

    private fun careDashboard(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        main.addView(buttonRow(
            commandTile("Start Rounds", "", "ROUTE", Good) { startCareRound() },
            commandTile("Check-In", "", "CARE", CareBlue) { runResidentCheckIn(careRepo.residents().firstOrNull()?.id) }
        ))
        main.addView(buttonRow(
            commandTile("Meds", "", "MED", CareYellow) { runNextReminder() },
            commandTile("Alert", "", "ALERT", Danger) { createStaffAlert("urgent", "", "Resident or visitor requested assistance.") }
        ))
        main.addView(wideCommandTile("Visitor Guide", "", "MAP", CarePurple) { runVisitorGuide(destination()) })

        side.addView(currentTaskCard())
        side.addView(recentActivityPanel())

        root.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply { marginEnd = dp(12) })
        root.addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return root
    }

    private fun commandTile(title: String, subtitle: String, iconText: String, color: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(tint(color), dp(18), Color.argb(35, Color.red(color), Color.green(color), Color.blue(color)))
            setPadding(dp(18), dp(20), dp(18), dp(18))
            setOnClickListener { onClick() }
            minimumHeight = dp(230)
        }
        box.addView(circleIcon(iconText, color, dp(96)))
        box.addView(TextView(this).apply {
            text = title
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(10, 18, 45))
            setPadding(0, dp(18), 0, dp(10))
        })
        box.addView(actionButton("START", color, onClick).apply {
            textSize = 20f
            minHeight = dp(54)
            layoutParams = full().apply { topMargin = dp(8) }
        })
        return box
    }

    private fun wideCommandTile(title: String, subtitle: String, iconText: String, color: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(tint(color), dp(18), Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
            setPadding(dp(28), dp(18), dp(28), dp(18))
            setOnClickListener { onClick() }
            layoutParams = full().apply { topMargin = dp(12) }
        }
        box.addView(circleIcon(iconText, color, dp(90)))
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        textBox.addView(TextView(this).apply {
            text = title
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(10, 18, 45))
        })
        textBox.addView(actionButton("START", color, onClick).apply {
            textSize = 20f
            minHeight = dp(54)
            layoutParams = full().apply { topMargin = dp(12) }
        })
        box.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return box
    }

    private fun circleIcon(text: String, color: Int, size: Int): View = TextView(this).apply {
        this.text = text
        textSize = if (size <= dp(40)) 13f else 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(color, size / 2)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun currentTaskCard(): View {
        val box = card()
        box.background = rounded(Color.WHITE, dp(14), Color.rgb(216, 226, 234))
        box.addView(TextView(this).apply {
            text = "TASK"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 27, 67))
        })
        val task = currentTaskText()
        box.addView(TextView(this).apply {
            text = task.first
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 27, 67))
            setPadding(0, dp(18), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = task.second
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Primary)
        })
        box.addView(taskJourneyView(), full().apply { topMargin = dp(14) })
        box.addView(progressBar(task.third), full().apply { topMargin = dp(20); bottomMargin = dp(14) })
        box.addView(taskDetailRow("ETA", if (task.third > 0) "3 min" else "Ready"))
        box.addView(taskDetailRow("NEXT", currentTaskNext.ifBlank { destination() }))
        box.addView(actionButton("DETAILS", Color.WHITE) {
            currentPage = "care"
            setContentView(buildUi())
        }.apply {
            setTextColor(Primary)
            background = rounded(Color.WHITE, dp(8), Primary)
            layoutParams = full().apply { topMargin = dp(16) }
        })
        return box
    }

    private fun recentActivityPanel(): View {
        val box = card()
        box.background = rounded(Color.WHITE, dp(14), Color.rgb(216, 226, 234))
        box.addView(TextView(this).apply {
            text = "ACTIVITY"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 27, 67))
        })
        val logs = careRepo.logs().take(4)
        if (logs.isEmpty()) {
            box.addView(emptyState("No care activity yet. Start a round, check-in, reminder, guide, or staff alert."))
        } else {
            logs.forEach { box.addView(activityRow(it.title, it.mapPoint ?: it.type, "Logged", Good)) }
        }
        box.addView(actionButton("OPEN LOG", Color.WHITE) {
            currentPage = "care"
            setContentView(buildUi())
        }.apply {
            setTextColor(Primary)
            background = rounded(Color.WHITE, dp(8), Primary)
            layoutParams = full().apply { topMargin = dp(10) }
        })
        return box
    }

    private fun quickActions(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttonRow(
            quickAction("CHARGE", "Charge", Good) { setStatus(robot.goCharge().message) },
            quickAction("HOME", "Reception", Primary) {
                setDestinationText(resolveMapPoint("Reception"))
                goToDestination()
            },
            quickAction("STOP", "Stop", Danger) { stopAll() },
            quickAction("MANUAL", "Manual", Neutral) {
                currentPage = "robot"
                setContentView(buildUi())
            }
        ))
        return root
    }

    private fun quickAction(code: String, title: String, color: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, dp(12), Color.rgb(224, 232, 236))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
            minimumHeight = dp(100)
        }
        box.addView(circleIcon(code, color, dp(62)))
        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 27, 67))
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return box
    }

    private fun currentTaskText(): Triple<String, String, Int> {
        val lower = lastStatus.lowercase()
        return when {
            currentTaskProgress > 0 -> Triple(currentTaskTitle, currentTaskStage, currentTaskProgress)
            lower.contains("medication") || lower.contains("reminder") -> Triple("Delivering Reminder", "In Progress", 60)
            lower.contains("check") || lower.contains("round") -> Triple("Resident Check-In", "In Progress", 45)
            lower.contains("navigat") || lower.contains("guid") -> Triple("Guiding to ${destination()}", "In Progress", 55)
            lower.contains("alert") -> Triple("Staff Alert Active", "Waiting for staff", 80)
            else -> Triple("Nova Care Assistant", "Ready", 0)
        }
    }

    private fun taskJourneyView(): View {
        val stages = listOf("Starting", "Navigating", "Arrived", "Speaking", "Completed")
        val stage = currentTaskStage.lowercase()
        val active = when {
            stage.contains("complete") || currentTaskProgress >= 100 -> 4
            stage.contains("speak") || stage.contains("listen") -> 3
            stage.contains("arriv") -> 2
            stage.contains("navigat") || currentTaskProgress >= 25 -> 1
            currentTaskProgress > 0 -> 0
            else -> -1
        }
        val current = stages.getOrElse(active.coerceAtLeast(0)) { "Ready" }
        return TextView(this).apply {
            text = if (active < 0) {
                "Ready for next task"
            } else {
                "${stages.joinToString("  >  ")}\nNow: $current"
            }
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
            background = rounded(Color.rgb(244, 249, 250), dp(8), Stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun progressBar(percent: Int): View {
        val frame = FrameLayout(this).apply {
            background = rounded(Color.rgb(222, 229, 238), dp(99))
            minimumHeight = dp(12)
        }
        frame.addView(View(this).apply {
            background = rounded(Primary, dp(99))
        }, FrameLayout.LayoutParams(0, dp(12)).apply {
            width = dp((percent.coerceIn(0, 100) * 2).coerceAtLeast(14))
        })
        return frame
    }

    private fun taskDetailRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Muted)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(Text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun activityRow(title: String, detail: String, state: String, color: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(circleIcon("OK", color, dp(44)))
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        textBox.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        textBox.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(Muted)
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = state
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Good)
            background = rounded(Color.rgb(226, 246, 230), dp(99))
            setPadding(dp(8), dp(5), dp(8), dp(5))
        })
        return row
    }

    private fun tint(color: Int): Int =
        Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))

    private fun cleanConciergePanel(): View {
        val box = card()
        box.background = rounded(Color.rgb(10, 16, 18), dp(8), Color.rgb(62, 82, 86))
        box.addView(TextView(this).apply {
            text = "Concierge"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Message delivery, guest guidance, and safe following."
            textSize = 12f
            setTextColor(Color.rgb(199, 216, 216))
            setPadding(0, dp(4), 0, dp(8))
        })
        box.addView(buttonRow(
            actionButton("Take Message", Accent) {
                currentPage = "message"
                setContentView(buildUi())
                askAndRecord()
            },
            actionButton("Guide", Primary) { goToDestination() }
        ))
        box.addView(buttonRow(
            actionButton("Follow", PrimaryDark) { startFollowMode() },
            actionButton("Stop", Danger) { stopAll() }
        ))
        return box
    }

    private fun careCommandPanel(): View {
        val box = card()
        box.background = rounded(Color.rgb(10, 16, 18), dp(8), Color.rgb(62, 82, 86))
        box.addView(TextView(this).apply {
            text = "Elder Care Mode"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Built for reception, family messages, rounds, reminders, safety watch, and staff handoff."
            textSize = 12f
            setTextColor(Color.rgb(199, 216, 216))
            setPadding(0, dp(4), 0, dp(8))
        })
        box.addView(buttonRow(
            actionButton("Start Round", Accent) { startCareRound() },
            actionButton("Staff Alert", Danger) { createStaffAlert("urgent", "", "A resident or visitor requested assistance.") }
        ))
        box.addView(buttonRow(
            actionButton("Medication", Primary) { runNextReminder() },
            actionButton("Safety Watch", PrimaryDark) { startSecurityWatch() }
        ))
        return box
    }

    private fun residentCard(resident: CareResident): View {
        val box = card()
        box.addView(TextView(this).apply {
            text = "${resident.name}  ${resident.room}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        })
        box.addView(TextView(this).apply {
            text = "${resident.mapPoint}  ${resident.notes}"
            textSize = 13f
            setTextColor(Muted)
            setPadding(0, dp(4), 0, dp(8))
        })
        val residentLogs = careRepo.logs().filter { it.residentId == resident.id }.take(3)
        box.addView(TextView(this).apply {
            text = if (residentLogs.isEmpty()) {
                "Timeline: no check-ins yet | Notes: ${resident.notes.ifBlank { "none" }}"
            } else {
                "Timeline: " + residentLogs.joinToString(" | ") { it.title }
            }
            textSize = 13f
            setTextColor(Muted)
            background = rounded(Color.rgb(244, 249, 250), dp(8), Stroke)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        box.addView(buttonRow(
            actionButton("Check In", Accent) { runResidentCheckIn(resident.id) },
            actionButton("Reminder", Primary) { runReminderForResident(resident.id) }
        ))
        val alertButton = actionButton("Alert Staff", Danger) {
            createStaffAlert("normal", resident.room, "${resident.name} requested staff assistance.")
        }
        alertButton.layoutParams = full().apply { topMargin = dp(6) }
        box.addView(alertButton)
        return box
    }

    private fun careTodayPanel(): View {
        val box = card()
        val pending = careRepo.reminders().filter { it.doneAt == null }
        if (pending.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "All reminders completed."
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Good)
            })
        } else {
            pending.take(3).forEach { reminder ->
                val resident = careRepo.resident(reminder.residentId)
                box.addView(compactCard("${reminder.timeLabel}  ${reminder.title}  ${resident?.name ?: "Resident"}") {
                    runReminder(reminder.id)
                })
            }
        }
        val openAlerts = careRepo.alerts().count { it.status == "open" }
        box.addView(buttonRow(
            compactStatus("Alerts", "$openAlerts open"),
            compactStatus("Logs", "${careRepo.logs().size}")
        ))
        box.addView(compactStatus("Rounds", "${careRepo.residents().size} residents"), full().apply { topMargin = dp(5) })
        return box
    }

    private fun careLogCard(log: CareLog): View =
        compactCard("${log.title}\n${log.detail}") {
            setStatus("${log.title}: ${log.detail}")
        }

    private fun statusStrip(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(buttonRow(
            compactStatus("Map", "${lastMapPoints.size} points"),
            compactStatus("Power", lastBattery.replace("Battery ", "")),
            compactStatus("Queue", "${repo.pendingCount()}")
        ))
        row.addView(compactCard("Phone control: http://${localIpAddress()}:8787  Login: admin / nova2026") {
            setStatus("Open http://${localIpAddress()}:8787 on your phone. Login admin / nova2026.")
        })
        return row
    }

    private fun compactStatus(title: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Card, dp(8), Stroke)
            setPadding(dp(10), dp(7), dp(10), dp(8))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Muted)
        })
        box.addView(TextView(this).apply {
            text = value.ifBlank { "--" }
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
            maxLines = 2
        })
        return box
    }

    private fun featureHub(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttonRow(
            featureTile("Deliver", "Record or speak a message, then Nova drives to a named point.") {
                currentPage = "message"
                setContentView(buildUi())
            },
            featureTile("Destinations", "Live map points from RobotAPI, ready for guide or delivery.") {
                currentPage = "destinations"
                setContentView(buildUi())
                renderPoints(lastMapPoints, updateStatus = false)
            }
        ))
        root.addView(buttonRow(
            featureTile("People", "Shape detection, greeting, and follow controls for staff-led escort.") {
                currentPage = "robot"
                setContentView(buildUi())
            },
            featureTile("Power", "Battery status and auto-charge command for daily operations.") {
                currentPage = "robot"
                setContentView(buildUi())
            }
        ))
        return root
    }

    private fun featureTile(title: String, subtitle: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(12, 22, 25), dp(8), Color.rgb(70, 92, 96))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.rgb(187, 207, 208))
            setPadding(0, dp(6), 0, 0)
        })
        return box
    }

    private fun conciergePanel(): View {
        val card = card()
        card.addView(buttonRow(
            actionButton("Send to Point", Accent) { sendCurrentMessageToPoint() },
            actionButton("Guide", Primary) { goToDestination() },
            actionButton("Follow", PrimaryDark) { startFollowMode() }
        ))
        card.addView(buttonRow(
            actionButton("Load Points", Neutral) { loadMapPoints() },
            actionButton("Robot Check", Neutral) { runRobotCheck() },
            actionButton("Stop", Danger) { stopAll() }
        ))
        return card
    }

    private fun header(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(12), Color.rgb(225, 232, 236))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = full().apply { bottomMargin = dp(6) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(zoxLogoMark())
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "Nova Care Assistant"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(12, 24, 56))
        })
        titleBox.addView(TextView(this).apply {
            text = "Healthcare - Elder Care"
            textSize = 9f
            setTextColor(Color.rgb(57, 70, 104))
        })
        top.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sdkBadge = badge(if (robotReadySoon()) "Robot mode" else "Preview mode", Accent)
        queueBadge = badge("Queue 0", Color.rgb(245, 184, 81))
        recordingBadge = badge("Ready", Good)
        assistBadge = badge("Assist off", Neutral)
        top.addView(sdkBadge)
        box.addView(top)
        statusView = TextView(this).apply {
            text = "Unit 01  |  ${if (robot.isRobotSdkAvailable) "Online" else "Preview"}  |  ${lastBattery.replace("Battery ", "")}  |  ${SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())}"
            textSize = 10f
            gravity = Gravity.START
            setTextColor(Color.rgb(57, 70, 104))
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(statusView, full())
        return box
    }

    private fun zoxLogoMark(): View = TextView(this).apply {
        text = "ZOX"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(18, 211, 234))
        background = rounded(Color.rgb(4, 20, 45), dp(16), Color.rgb(18, 198, 231))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun workflowCard(): View {
        val card = card()
        clientInput = input("Client or host name", clientName)
        pointInput = destinationDropdown()
        messageInput = EditText(this).apply {
            hint = "Message for the destination"
            setText(messageDraft)
            minLines = 3
            gravity = Gravity.TOP
            textSize = 14f
            setTextColor(Text)
            setHintTextColor(Muted)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.WHITE, dp(8), Stroke)
        }
        card.addView(label("Client"))
        card.addView(clientInput, full())
        card.addView(label("Destination"))
        card.addView(pointInput, full())
        card.addView(label("Message"))
        card.addView(messageInput, full())
        card.addView(buttonRow(
            actionButton("Record", Primary) { askAndRecord() },
            actionButton("Stop + Save", Warning) { stopRecordingAndSave() }
        ))
        card.addView(buttonRow(
            actionButton("Save Text", Neutral) { saveTextOnlyMessage() },
            actionButton("Speak", PrimaryDark) { speakCurrentMessage() }
        ))
        return card
    }

    private fun guestAssistCard(): View {
        val card = card()
        card.addView(buttonRow(
            actionButton("Start Assist", Accent) { startGuestAssist(auto = false) },
            actionButton("Listen Now", PrimaryDark) { listenToGuest() },
            actionButton("Robot Check", Primary) { runRobotCheck() },
            actionButton("Stop Assist", Danger) { stopGuestAssist() }
        ))
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        strip.addView(assistBadge, weight())
        strip.addView(badge("Voice intent", PrimaryDark), weight())
        strip.addView(badge("People aware", Accent), weight())
        card.addView(strip)
        return card
    }

    private fun robotControlsCard(): View {
        val card = card()
        card.addView(buttonRow(
            actionButton("Guide", Primary) { goToDestination() },
            actionButton("Follow", Accent) { startFollowMode() },
            actionButton("Stop", Danger) { stopAll() }
        ))
        card.addView(buttonRow(
            actionButton("Battery", Neutral) { setStatus(robot.batteryInfo()) },
            actionButton("Save Point", Neutral) { saveCurrentPoint() },
            actionButton("Charge", PrimaryDark) { setStatus(robot.goCharge().message) }
        ))
        return card
    }

    private fun startGuestAssist(auto: Boolean) {
        guestAssistEnabled = true
        assistBadge.text = "Assist on"
        assistBadge.background = rounded(Good, dp(99))
        setStatus("Guest Assist is watching for nearby body shapes.")
        val greeting = if (auto) {
            "Hello, I am Nova. I can take a message, guide you, or follow with staff approval."
        } else {
            "Guest Assist is on. I can take a message, guide you, or follow with staff approval."
        }
        speakReply(greeting)
        assistHandler.removeCallbacks(guestAssistTick)
        assistHandler.post(guestAssistTick)
    }

    private fun stopGuestAssist() {
        guestAssistEnabled = false
        assistHandler.removeCallbacks(guestAssistTick)
        assistBadge.text = "Assist off"
        assistBadge.background = rounded(Neutral, dp(99))
        setStatus("Guest Assist stopped.")
    }

    private fun observeGuestPresence() {
        val target = robot.getBodyTargets().firstOrNull()
        if (target == null) {
            assistBadge.text = "Watching"
            return
        }
        assistBadge.text = "${"%.1f".format(target.distanceMeters)}m guest"
        val now = System.currentTimeMillis()
        if (now - lastGuestGreetingAt > 45_000L && target.distanceMeters in 0.8..3.0) {
            lastGuestGreetingAt = now
            val greeting = "Hello. I can take a message, guide you to ${destination()}, or follow you. What would you like?"
            robot.speak(greeting).takeIf { it.ok } ?: voice.speak(greeting)
            setStatus("Guest detected. Offered help.")
        }
    }

    private fun listenToGuest() {
        startConversationalListen("I am listening. How can I help?")
    }

    private fun startConversationalListen(prompt: String?) {
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            setStatus("Microphone permission is required for voice commands.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakReply("AgentOS voice is active. Ask me to send a message, guide you, alert staff, check on a resident, follow, or open camera detection.")
            return
        }
        if (voiceListening) return
        prompt?.let { speakReply(it) }
        voiceListening = true
        assistBadge.text = "Listening"
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    setStatus("Listening for a care concierge command...")
                }

                override fun onBeginningOfSpeech() {
                    setStatus("I hear you.")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    setStatus("Understanding request...")
                }

                override fun onError(error: Int) {
                    voiceListening = false
                    val retryable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    setStatus(if (retryable) "No clear voice command heard." else "Voice command error $error.")
                    if (guestAssistEnabled && retryable) {
                        assistHandler.postDelayed({ if (guestAssistEnabled) startConversationalListen(null) }, 4_000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    voiceListening = false
                    val phrase = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (phrase.isBlank()) {
                        setStatus("No speech result.")
                        return
                    }
                    handleGuestIntent(phrase)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
        }
        runCatching { speechRecognizer?.startListening(intent) }
            .onFailure {
                voiceListening = false
                setStatus("Could not start voice command: ${it.message ?: "unknown error"}")
            }
    }

    private fun runRobotCheck() {
        Log.i("NovaConcierge", "runRobotCheck")
        setStatus("Running Nova check...")
        Thread {
            val targets = robot.getBodyTargets()
            val points = robot.getMapPoints()
            val battery = robot.batteryInfo()
            val tts = robot.speak("Nova Concierge check. Voice is working.")
            runOnUiThread {
                renderPoints(points, updateStatus = false)
                setStatus(
                    "Robot check: sdk=${robot.isRobotSdkAvailable}, voice=${tts.ok}, points=${points.size}, people=${targets.size}, battery=$battery"
                )
            }
        }
            .start()
    }

    private fun startCareRound() {
        val residents = careRepo.residents()
        val first = residents.firstOrNull() ?: return setStatus("No residents are configured.")
        activeRoundIds = residents.map { it.id }
        activeRoundIndex = 0
        setTask("Starting care round", "Starting", "Navigate to ${first.room}", 18)
        careRepo.log("round", "Check-in round started", "Starting with ${first.name}. ${residents.size} residents queued.", first.id, first.mapPoint)
        speakReply("Starting care round. I have ${residents.size} check-ins queued. I will check on ${first.name} first.")
        runResidentCheckIn(first.id, continueRound = true)
    }

    private fun runResidentCheckIn(residentId: String?, continueRound: Boolean = false) {
        val resident = careRepo.resident(residentId) ?: return setStatus("Resident not found.")
        setDestinationText(resolveMapPoint(resident.mapPoint))
        setTask("Checking ${resident.name}", "Navigating", "Speak check-in prompt", if (continueRound) 38 else 45)
        careRepo.log("check_in", "Check-in started", "Going to ${resident.name} at ${resident.room}.", resident.id, resident.mapPoint)
        follow.stop()
        setStatus("Going to ${resident.name} for check-in...")
        var spoken = false
        val result = robot.startNavigation(destination()) { status ->
            setStatus(status)
            if (!spoken && isArrivalStatus(status)) {
                spoken = true
                setTask("Checking ${resident.name}", "Speaking", "Complete care log", 85)
                speakReply(resident.checkInPrompt)
                careRepo.log("check_in", "Check-in delivered", resident.checkInPrompt, resident.id, resident.mapPoint)
                if (continueRound) {
                    setTask("Round response window", "Listening", "Continue to next resident", 88)
                    scheduleNextRoundStop(resident)
                } else {
                    setTask("Check-in completed", "Completed", resident.room, 100)
                }
                if (currentPage == "care") setContentView(buildUi())
            }
        }
        if (!result.ok) {
            setTask("Check-in fallback", "Speaking", "Spoken in place", 85)
            speakReply(resident.checkInPrompt)
            careRepo.log("check_in", "Check-in spoken in place", result.message, resident.id, resident.mapPoint)
            if (continueRound) scheduleNextRoundStop(resident) else setTask("Check-in completed", "Completed", resident.room, 100)
        }
        if (currentPage == "care") setContentView(buildUi())
    }

    private fun scheduleNextRoundStop(resident: CareResident) {
        val current = activeRoundIds.indexOf(resident.id).takeIf { it >= 0 } ?: activeRoundIndex
        activeRoundIndex = current
        setStatus("Waiting briefly for ${resident.name}'s response before continuing the round.")
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeRoundIds.isEmpty() || activeRoundIndex != current) return@postDelayed
            careRepo.log("check_in", "Response window completed", "Nova waited for ${resident.name}'s response before continuing.", resident.id, resident.mapPoint)
            val nextIndex = current + 1
            if (nextIndex >= activeRoundIds.size) {
                activeRoundIds = emptyList()
                activeRoundIndex = -1
                setTask("Care round completed", "Completed", "All residents checked", 100)
                careRepo.log("round", "Care round completed", "All queued residents were checked.", resident.id, resident.mapPoint)
                speakReply("Care round completed. All queued residents have been checked.")
                if (currentPage == "care") setContentView(buildUi())
            } else {
                activeRoundIndex = nextIndex
                val next = careRepo.resident(activeRoundIds[nextIndex])
                if (next != null) {
                    setTask("Care round", "Navigating", "Next: ${next.room}", 55)
                    careRepo.log("round", "Continuing care round", "Next check-in: ${next.name}.", next.id, next.mapPoint)
                    speakReply("Thank you. I will continue to ${next.name}.")
                    runResidentCheckIn(next.id, continueRound = true)
                }
            }
        }, 8_000)
    }

    private fun runExternalResidentCheckIn(residentId: String, name: String, room: String, mapPoint: String, notes: String) {
        val destinationPoint = resolveMapPoint(mapPoint.ifBlank { room.ifBlank { destination() } })
        val displayName = name.ifBlank { "the resident" }
        val displayRoom = room.ifBlank { destinationPoint }
        val prompt = buildString {
            append("Hello $displayName. This is Nova checking in at $displayRoom. ")
            append("Do you need water, medication help, or staff assistance?")
            if (notes.isNotBlank()) append(" Care note: $notes")
        }
        setDestinationText(destinationPoint)
        setTask("Checking $displayName", "Navigating", "Speak check-in prompt", 45)
        careRepo.log("check_in", "Cloud resident check-in", "Going to $displayName at $displayRoom.", residentId.ifBlank { null }, destinationPoint)
        follow.stop()
        setStatus("Going to $displayName for cloud check-in...")
        var spoken = false
        val result = robot.startNavigation(destination()) { status ->
            setStatus(status)
            if (!spoken && isArrivalStatus(status)) {
                spoken = true
                setTask("Checking $displayName", "Speaking", "Complete care log", 85)
                speakReply(prompt)
                careRepo.log("check_in", "Cloud check-in delivered", prompt, residentId.ifBlank { null }, destinationPoint)
                setTask("Check-in completed", "Completed", displayRoom, 100)
                if (currentPage == "care") setContentView(buildUi())
            }
        }
        if (!result.ok) {
            setTask("Check-in fallback", "Speaking", "Spoken in place", 85)
            speakReply(prompt)
            careRepo.log("check_in", "Cloud check-in spoken in place", result.message, residentId.ifBlank { null }, destinationPoint)
            setTask("Check-in completed", "Completed", displayRoom, 100)
        }
        if (currentPage == "care") setContentView(buildUi())
    }

    private fun runNextReminder() {
        val reminder = careRepo.reminders().firstOrNull { it.doneAt == null }
        if (reminder == null) return setStatus("No pending reminders.")
        runReminder(reminder.id)
    }

    private fun runReminderForResident(residentId: String) {
        val reminder = careRepo.reminders().firstOrNull { it.residentId == residentId && it.doneAt == null }
            ?: careRepo.reminders().firstOrNull { it.residentId == residentId }
        if (reminder == null) return runResidentCheckIn(residentId)
        runReminder(reminder.id)
    }

    private fun runReminder(reminderId: String) {
        val reminder = careRepo.reminders().firstOrNull { it.id == reminderId } ?: return setStatus("Reminder not found.")
        val resident = careRepo.resident(reminder.residentId)
        setDestinationText(resolveMapPoint(resident?.mapPoint ?: destination()))
        setTask("Medication reminder", "Navigating", "Speak reminder", 45)
        careRepo.log("reminder", reminder.title, "Going to ${resident?.name ?: "resident"} for ${reminder.timeLabel}.", reminder.residentId, destination())
        follow.stop()
        setStatus("Taking reminder to ${resident?.name ?: "resident"}...")
        var spoken = false
        val result = robot.startNavigation(destination()) { status ->
            setStatus(status)
            if (!spoken && isArrivalStatus(status)) {
                spoken = true
                setTask("Medication reminder", "Speaking", "Mark complete", 85)
                speakReply(reminder.message)
                careRepo.completeReminder(reminder.id)
                careRepo.log("reminder", "Reminder delivered", reminder.message, reminder.residentId, destination())
                setTask("Reminder completed", "Completed", resident?.room ?: destination(), 100)
                if (currentPage == "care") setContentView(buildUi())
            }
        }
        if (!result.ok) {
            setTask("Medication fallback", "Speaking", "Mark complete", 85)
            speakReply(reminder.message)
            careRepo.completeReminder(reminder.id)
            careRepo.log("reminder", "Reminder spoken in place", result.message, reminder.residentId, destination())
            setTask("Reminder completed", "Completed", resident?.room ?: destination(), 100)
        }
        if (currentPage == "care") setContentView(buildUi())
    }

    private fun createStaffAlert(priority: String, room: String, message: String) {
        val alert = careRepo.createAlert(priority, room.ifBlank { destination() }, message)
        setTask("Staff alert", "Waiting for staff", alert.room, 80)
        speakReply("I alerted staff. Please wait here while help is requested.")
        setStatus("Staff alert ${alert.priority}: ${alert.message}")
        if (currentPage == "care") setContentView(buildUi())
    }

    private fun runVisitorGuide(destinationName: String) {
        val target = destinationName.ifBlank { destination() }
        setDestinationText(resolveMapPoint(target))
        setTask("Visitor guide", "Navigating", "Arrive at ${destination()}", 45)
        careRepo.log("visitor", "Visitor guide", "Guiding visitor to ${destination()}.", null, destination())
        speakReply("I can guide you to ${destination()}. Please follow me.")
        goToDestination()
    }

    private fun resolveMapPoint(preferred: String): String {
        if (lastMapPoints.isEmpty()) return preferred.ifBlank { "Reception" }
        return lastMapPoints.firstOrNull { it.name.equals(preferred, ignoreCase = true) }?.name
            ?: lastMapPoints.firstOrNull { preferred.contains(it.name, ignoreCase = true) || it.name.contains(preferred, ignoreCase = true) }?.name
            ?: lastMapPoints.firstOrNull { it.name.equals(selectedDestination, ignoreCase = true) }?.name
            ?: lastMapPoints.first().name
    }

    private fun isArrivalStatus(status: String): Boolean {
        val lower = status.lowercase()
        return listOf("arrive", "complete", "success", "finish", "in range", "in_destination", "到达", "完成").any { lower.contains(it) } ||
            Regex("""navigation result\s+(102|104)\b""").containsMatchIn(lower) ||
            Regex("""navigation result\s+1\s+true""").containsMatchIn(lower)
    }

    private fun handleGuestIntent(phrase: String) {
        val lower = phrase.lowercase()
        setStatus("Heard: $phrase")
        when {
            isStopIntent(lower) -> {
                stopAll()
                stopGuestAssist()
                speakReply("Okay, I stopped.")
            }
            isCapabilitiesIntent(lower) -> {
                speakReply(novaCapabilitiesText())
            }
            isMessageIntent(lower) -> {
                val destination = inferDestination(lower) ?: inferDestinationFromWords(lower) ?: destination()
                val body = extractMessageBody(phrase, destination)
                handleVoiceSendAction(body, destination, "visitor")
            }
            isCareIntent(lower) -> {
                val resident = findResidentFromSpeech(lower)
                val fallbackResident = careRepo.residents().firstOrNull()
                when {
                    lower.contains("medication") || lower.contains("medicine") || lower.contains("appointment") -> {
                        val targetId = resident?.id ?: fallbackResident?.id
                        if (targetId == null) speakReply("I do not have a resident registered yet. Please add residents in the care screen or cloud dashboard.")
                        else runReminderForResident(targetId)
                    }
                    lower.contains("round") -> startCareRound()
                    else -> {
                        val targetId = resident?.id ?: fallbackResident?.id
                        if (targetId == null) speakReply("I do not have a resident registered yet. Please add residents before starting check-ins.")
                        else runResidentCheckIn(targetId)
                    }
                }
            }
            isAlertIntent(lower) -> {
                val priority = if (listOf("urgent", "emergency", "fall", "fell", "pain", "can't breathe", "cannot breathe").any { lower.contains(it) }) "urgent" else "normal"
                createStaffAlert(priority, inferDestination(lower) ?: inferRoom(lower).orEmpty(), phrase)
            }
            isFollowIntent(lower) -> {
                speakReply("I will follow slowly. Please stay in front of me.")
                startFollowMode()
            }
            isGuideIntent(lower) -> {
                val point = inferDestination(lower) ?: inferDestinationFromWords(lower)
                if (point != null) setDestinationText(point) else inferRoom(lower)?.let { setDestinationText(it) }
                speakReply("I will guide you to ${destination()}.")
                goToDestination()
            }
            isCameraIntent(lower) -> {
                currentPage = "camera"
                setContentView(buildUi())
                if (lower.contains("security") || lower.contains("watch") || lower.contains("scan")) startSecurityWatch() else startCameraFeed()
            }
            else -> {
                speakReply("I can help with messages, visitor guide, resident check-ins, medication reminders, staff alerts, following, and camera detection. Please tell me which one you need.")
                if (guestAssistEnabled) assistHandler.postDelayed({ startConversationalListen(null) }, 1_800)
            }
        }
    }

    private fun inferDestination(lowerPhrase: String): String? {
        val known = lastMapPoints.map { it.name }.ifEmpty {
            listOf("Reception", "Lobby", "Meeting Room", "Office", "Entrance")
        }
        return known.firstOrNull { lowerPhrase.contains(it.lowercase()) }
    }

    private fun inferDestinationFromWords(lowerPhrase: String): String? {
        val aliases = mapOf(
            "front desk" to "Reception",
            "reception" to "Reception",
            "nurse station" to "Reception",
            "nurses station" to "Reception",
            "lobby" to "Lobby",
            "entrance" to "Gate Entrance",
            "gate" to "Gate Entrance",
            "charging" to "Charging Point",
            "charger" to "Charging Point",
            "office" to "Zox Robotics office",
            "conference" to "conference room C",
            "meeting" to "conference room C",
            "printer" to "Printer"
        )
        val target = aliases.entries.firstOrNull { lowerPhrase.contains(it.key) }?.value ?: return null
        return resolveMapPoint(target)
    }

    private fun inferRoom(lowerPhrase: String): String? =
        Regex("""\b(room|suite|bed)\s*([a-z]?\d{1,4}[a-z]?)\b""").find(lowerPhrase)
            ?.let { "Room ${it.groupValues[2].uppercase()}" }

    private fun findResidentFromSpeech(lowerPhrase: String): CareResident? =
        careRepo.residents().firstOrNull {
            lowerPhrase.contains(it.name.lowercase()) ||
                lowerPhrase.contains(it.name.lowercase().substringBefore(" ")) ||
                lowerPhrase.contains(it.room.lowercase())
        }

    private fun isStopIntent(lower: String): Boolean =
        listOf("stop", "cancel", "wait", "pause", "never mind", "nevermind", "that's enough").any { lower.contains(it) }

    private fun isCapabilitiesIntent(lower: String): Boolean =
        listOf(
            "what can you do",
            "what do you do",
            "what are you",
            "who are you",
            "help me",
            "options",
            "functions",
            "capabilities",
            "what do you offer",
            "how can you help"
        ).any { lower.contains(it) } && !isGuideIntent(lower) && !isMessageIntent(lower) && !isAlertIntent(lower)

    private fun isMessageIntent(lower: String): Boolean =
        listOf(
            "send a message",
            "send message",
            "take a message",
            "record a message",
            "leave a message",
            "deliver a message",
            "can you send",
            "could you send",
            "i want to send",
            "message to",
            "message for",
            "please tell",
            "can you tell",
            "could you tell",
            "i need you to tell",
            "tell reception",
            "tell the front desk",
            "tell nurse",
            "tell the nurse",
            "let reception know",
            "let the nurse know",
            "inform reception",
            "inform the nurse",
            "notify reception"
        ).any { lower.contains(it) }

    private fun isCareIntent(lower: String): Boolean =
        listOf("check on", "check in", "check-in", "care round", "rounds", "resident", "medication", "medicine", "appointment", "reminder").any { lower.contains(it) }

    private fun isAlertIntent(lower: String): Boolean {
        if (isGuideIntent(lower) || isMessageIntent(lower)) return false
        return listOf(
            "alert",
            "call nurse",
            "call a nurse",
            "need a nurse",
            "staff",
            "caregiver",
            "water",
            "thirsty",
            "medication help",
            "medicine help",
            "urgent",
            "emergency",
            "i need help",
            "help now",
            "fell",
            "fall detected",
            "pain"
        ).any { lower.contains(it) }
    }

    private fun isFollowIntent(lower: String): Boolean =
        listOf("follow me", "follow", "come with me", "walk with me", "stay with me").any { lower.contains(it) }

    private fun isGuideIntent(lower: String): Boolean =
        listOf("guide", "take me", "go to", "show me", "navigate", "where is", "how do i get", "help me go", "help me get").any { lower.contains(it) }

    private fun isCameraIntent(lower: String): Boolean =
        listOf("camera", "security", "detect", "detection", "scan", "watch hallway", "watch area", "surveillance").any { lower.contains(it) }

    private fun extractMessageBody(phrase: String, destination: String): String {
        val cleanup = phrase
            .replace(Regex("(?i)^(nova|hey nova|please|can you|could you|i want to|i need to|would you)\\s+"), "")
            .replace(Regex("(?i)\\b(send|take|record|leave|deliver)\\s+(a\\s+)?message\\b"), "")
            .replace(Regex("(?i)\\b(to|for)\\s+${Regex.escape(destination)}\\b"), "")
            .replace(Regex("(?i)\\b(to|for)\\s+(reception|front desk|nurse station|nurses station|lobby|office|conference room|meeting room)\\b"), "")
            .replace(Regex("(?i)^\\s*(that|saying|says|tell|let .* know)\\s+"), "")
            .trim(' ', ',', '.', ':', ';')
        val afterThat = Regex("(?i)\\b(that|saying|says)\\b\\s+(.+)").find(phrase)?.groupValues?.getOrNull(2)
        return (afterThat ?: cleanup).trim().takeIf { it.length >= 4 }.orEmpty()
    }

    private fun novaCapabilitiesText(): String =
        "I am Nova, the care concierge. I can take and deliver messages, guide visitors to saved map points, alert staff, check on residents, deliver medication reminders, follow with staff approval, open camera detection, and report activity to the cloud dashboard."

    private fun speakReply(text: String) {
        val result = robot.speak(text)
        if (!result.ok) voice.speak(text)
        setStatus(text)
    }

    private fun templateCard(): View {
        val card = card()
        card.addView(buttonRow(
            actionButton("Visitor", Neutral) { template("Your visitor has arrived and is waiting at ${destination()}.") },
            actionButton("Delivery", Neutral) { template("A delivery has arrived. Please come to ${destination()} when available.") },
            actionButton("Meeting", Neutral) { template("Your meeting guest is here. Nova can guide them when you are ready.") }
        ))
        card.addView(buttonRow(
            actionButton("VIP", Neutral) { template("A VIP guest has arrived. Please send someone to ${destination()} for a personal welcome.") },
            actionButton("Support", Neutral) { template("A guest needs assistance at ${destination()}. Please send a team member.") },
            actionButton("Pickup", Neutral) { template("Someone is waiting at ${destination()} for pickup or escort.") }
        ))
        return card
    }

    private fun askAndRecord() {
        if (!hasAudioPermission()) return setStatus("Microphone permission is required to record a message.")
        if (isRecording) return setStatus("Already recording. Press Stop + Save when finished.")
        saveSettings()
        hideKeyboard()
        val destination = destination()
        val prompt = "What do you want $destination to know?"
        setTask("Recording message", "Starting", "Record visitor message", 20)
        voice.speak(prompt)
        val result = runCatching { voice.startRecording(destination) }
        if (result.isSuccess) {
            recordingPath = result.getOrThrow().absolutePath
            isRecording = true
            recordingBadge.text = "Recording"
            recordingBadge.background = rounded(Danger, dp(99))
            setStatus(prompt)
        } else {
            setStatus("Could not start recording: ${result.exceptionOrNull()?.message ?: "unknown error"}")
        }
    }

    private fun dictateText() {
        if (!hasAudioPermission()) return setStatus("Microphone permission is required for dictation.")
        saveSettings()
        hideKeyboard()
        val destination = destination()
        val prompt = "What do you want $destination to know?"
        voice.speak(prompt)
        setStatus(prompt)
        voice.transcribeOnce(prompt) { text ->
            if (text.isBlank()) setStatus("No speech text captured. You can type the message instead.")
            else {
                setMessageText(text)
                setStatus("Captured text for $destination.")
            }
        }
    }

    private fun stopRecordingAndSave() {
        if (!isRecording) return setStatus("No recording is active.")
        val file = voice.stopRecording()
        isRecording = false
        recordingBadge.text = "Ready"
        recordingBadge.background = rounded(Good, dp(99))
        val sender = currentClient().ifBlank { "a guest" }
        val saved = saveMessage(file?.absolutePath ?: recordingPath, allowEmptyText = true, prompt = "Voice message from $sender")
        if (saved != null) setTask("Message saved", "Ready to deliver", saved.destination, 35)
        if (autoDeliverAfterRecording && saved != null) {
            autoDeliverAfterRecording = false
            speakReply("I will deliver this message to ${saved.destination}.")
            deliverMessage(saved)
        }
    }

    private fun saveTextOnlyMessage(): NovaMessage? {
        val sender = currentClient().ifBlank { "a guest" }
        return saveMessage(null, allowEmptyText = false, prompt = "Text message from $sender")
    }

    private fun saveMessage(audioPath: String?, allowEmptyText: Boolean, prompt: String): NovaMessage? {
        saveSettings()
        val text = currentMessage()
        if (!allowEmptyText && text.isBlank()) {
            setStatus("Type or dictate a message first.")
            return null
        }
        val message = NovaMessage(
            id = System.currentTimeMillis(),
            destination = destination(),
            prompt = prompt,
            text = text,
            audioPath = audioPath,
            createdAt = System.currentTimeMillis()
        )
        repo.save(message)
        setMessageText("")
        refreshMessages()
        setStatus("Saved message for ${message.destination}.")
        return message
    }

    private fun speakCurrentMessage() {
        val text = currentMessage()
        if (text.isBlank()) return setStatus("Type, dictate, or select a message first.")
        val robotTts = robot.speak(text)
        if (!robotTts.ok) voice.speak(text)
        setStatus("Speaking message for ${destination()}.")
    }

    private fun goToDestination() {
        saveSettings()
        follow.stop()
        val dest = destination()
        setTask("Guiding to $dest", "Navigating", "Arrive at destination", 55)
        setStatus("Navigating to $dest...")
        val result = robot.startNavigation(dest) { setStatus(it) }
        if (!result.ok) setStatus(result.message)
    }

    private fun sendCurrentMessageToPoint() {
        saveSettings()
        val text = currentMessage()
        val created = if (text.isNotBlank()) saveTextOnlyMessage() else null
        val message = created
            ?: repo.all().firstOrNull { it.deliveredAt == null && it.destination.equals(destination(), ignoreCase = true) }
            ?: repo.all().firstOrNull { it.deliveredAt == null }
        if (message == null) return setStatus("Create or select a message first.")
        deliverMessage(message)
    }

    private fun deliverMessage(message: NovaMessage) {
        setDestinationText(message.destination)
        follow.stop()
        setTask("Delivering message", "Navigating", "Play message from visitor", 55)
        setStatus("Taking message to ${message.destination}...")
        var played = false
        val result = robot.startNavigation(message.destination) { status ->
            setStatus(status)
            val lower = status.lowercase()
            if (listOf("arrive", "complete", "success", "finish", "到达", "完成").any { lower.contains(it) }) {
                if (!played) {
                    played = true
                    playDeliveredMessage(message)
                }
            }
            if (!played && (Regex("""navigation result\s+(102|104)\b""").containsMatchIn(lower) ||
                    Regex("""navigation result\s+1\s+true""").containsMatchIn(lower) ||
                    lower.contains("in range") || lower.contains("in_destination"))) {
                played = true
                playDeliveredMessage(message)
            }
        }
        if (!result.ok) setStatus(result.message)
    }

    private fun stopAll() {
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

    private fun loadMapPoints(silent: Boolean = false) {
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

    private fun renderPoints(points: List<MapPoint>, updateStatus: Boolean) {
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

    private fun saveCurrentPoint() {
        saveSettings()
        val result = robot.saveCurrentLocation(destination())
        setStatus(result.message)
    }

    private fun refreshMessages() {
        if (!::messageList.isInitialized) return
        queueBadge.text = "Queue ${repo.pendingCount()}"
        messageList.removeAllViews()
        val messages = repo.all()
        if (messages.isEmpty()) {
            messageList.addView(emptyState("No saved messages yet. Record or save a text message to start a delivery queue."))
            return
        }
        messages.forEach { message -> messageList.addView(messageCard(message)) }
    }

    private fun messageCard(message: NovaMessage): View {
        val card = card()
        val delivered = message.deliveredAt != null
        card.addView(TextView(this).apply {
            text = "${message.destination}${if (delivered) " - delivered" else " - pending"}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (delivered) Muted else Text)
        })
        card.addView(TextView(this).apply {
            text = message.text.ifBlank { message.prompt.ifBlank { "Voice recording saved" } }
            textSize = 15f
            setTextColor(Text)
            setPadding(0, dp(6), 0, dp(8))
        })
        card.addView(buttonGrid(
            2,
            actionButton("Navigate") {
                setDestinationText(message.destination)
                goToDestination()
            },
            actionButton("Play") {
                playDeliveredMessage(message)
            },
            actionButton("Load") {
                setDestinationText(message.destination)
                setMessageText(message.text)
                setStatus("Loaded message for editing.")
            },
            actionButton("Delete", Danger) {
                repo.delete(message.id)
                refreshMessages()
                setStatus("Deleted saved message.")
            }
        ))
        return card
    }

    private fun playDeliveredMessage(message: NovaMessage) {
        setTask("Delivering message", "Speaking", "Mark delivered", 85)
        val played = runCatching {
            if (message.audioPath != null) {
                val sender = senderFromPrompt(message.prompt)
                val spoken = robot.speak("Voice message from $sender.")
                if (!spoken.ok) voice.speak("Voice message from $sender.")
                voice.playAudio(message.audioPath)
            } else {
                val sender = senderFromPrompt(message.prompt)
                val spoken = robot.speak("Message from $sender: ${message.text}")
                if (!spoken.ok) voice.speak(message.text)
            }
        }.isSuccess
        if (played) {
            repo.markDelivered(message.id)
            refreshMessages()
            setTask("Message delivered", "Completed", message.destination, 100)
            setStatus("Delivered message at ${message.destination}.")
        } else setStatus("Could not play the saved message.")
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

    private fun template(text: String) {
        setMessageText(text)
        setStatus("Template loaded.")
    }

    private fun handleVoiceSendAction(message: String, destinationName: String, senderName: String) {
        currentPage = "message"
        setContentView(buildUi())
        val destination = destinationName.ifBlank { inferDestination(message.lowercase()) ?: destination() }
        val sender = senderName.ifBlank { currentClient().ifBlank { "a guest" } }
        setClientText(sender)
        setDestinationText(destination)
        if (message.isBlank()) {
            speakReply("I can send that. Tell me the message for $destination after the tone.")
            autoDeliverAfterRecording = true
            askAndRecord()
            return
        }
        val cleanMessage = message.trim()
        setMessageText(cleanMessage)
        val novaMessage = NovaMessage(
            id = System.currentTimeMillis(),
            destination = destination,
            prompt = "Text message from $sender",
            text = cleanMessage,
            audioPath = null,
            createdAt = System.currentTimeMillis()
        )
        repo.save(novaMessage)
        refreshMessages()
        setMessageText("")
        speakReply("I will deliver your message to $destination.")
        deliverMessage(novaMessage)
    }

    private fun senderFromPrompt(prompt: String): String =
        prompt.substringAfter("from ", "a guest").ifBlank { "a guest" }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_SEND_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
                val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
                handleVoiceSendAction(message, destination, sender)
            }
            COMMAND_VOICE_PHRASE -> {
                val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
                if (phrase.isNotBlank()) handleGuestIntent(phrase)
            }
        }
    }

    private fun destination(): String {
        if (::pointInput.isInitialized) {
            selectedDestination = pointInput.selectedItem?.toString()?.trim().orEmpty().ifBlank { selectedDestination }
        }
        return selectedDestination.ifBlank { "Reception" }
    }

    private fun currentClient(): String {
        if (::clientInput.isInitialized) clientName = clientInput.text.toString().trim()
        return clientName
    }

    private fun currentMessage(): String {
        if (::messageInput.isInitialized) messageDraft = messageInput.text.toString().trim()
        return messageDraft
    }

    private fun setDestinationText(value: String) {
        selectedDestination = value.ifBlank { "Reception" }
        if (::pointInput.isInitialized) selectDestinationInDropdown(selectedDestination)
    }

    private fun setClientText(value: String) {
        clientName = value
        if (::clientInput.isInitialized) clientInput.setText(value)
    }

    private fun setMessageText(value: String) {
        messageDraft = value
        if (::messageInput.isInitialized) messageInput.setText(value)
    }

    private fun saveSettings() {
        currentClient()
        destination()
        prefs.edit()
            .putString("client", clientName)
            .putString("destination", selectedDestination)
            .apply()
    }

    private fun setStatus(text: String) {
        lastStatus = text
        runOnUiThread {
            statusView.text = text.take(72)
            sdkBadge.text = if (robot.isRobotSdkAvailable) "Robot mode" else "Preview mode"
            if (::assistBadge.isInitialized && !guestAssistEnabled) assistBadge.text = "Assist off"
        }
    }

    private fun setTask(title: String, stage: String, next: String, progress: Int) {
        currentTaskTitle = title
        currentTaskStage = stage
        currentTaskNext = next
        currentTaskProgress = progress.coerceIn(0, 100)
        if (progress > 0) safetyStopStatus = "Armed"
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

    private fun startCameraFeed() {
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

    private fun stopCameraFeed() {
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

    private fun startFollowMode() {
        prepareRobotVisionForMotion {
            securityEnabled = false
            securityHandler.removeCallbacks(securityTick)
            setStatus("Starting follow. Please stand 1 to 3 meters in front of Nova.")
            follow.start()
        }
    }

    private fun startDoorFollowMode() {
        prepareRobotVisionForMotion {
            securityEnabled = false
            securityHandler.removeCallbacks(securityTick)
            setStatus("Starting door follow. Stay centered in front of Nova.")
            follow.startDoorMode()
        }
    }

    private fun startSecurityWatch() {
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

    private fun stopSecurityWatch() {
        securityEnabled = false
        visionManager.release(VisionMode.DETECTION_WATCH)
        securityHandler.removeCallbacks(securityTick)
        setStatus("Security watch stopped.")
        if (currentPage == "camera") setContentView(buildUi())
    }

    private fun observeSecurity() {
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

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hideKeyboard() {
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
                JSONObject()
                    .put("x", it.x)
                    .put("y", it.y)
                    .put("theta", it.theta)
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
                "message" -> {
                    handleVoiceSendAction(
                        command.params["message"].orEmpty(),
                        command.params["destination"].orEmpty(),
                        command.params["sender"].orEmpty().ifBlank { "phone" }
                    )
                }
                "visitor_guide" -> runVisitorGuide(command.params["destination"].orEmpty())
                "start_rounds" -> startCareRound()
                "resident_checkin" -> {
                    val residentId = command.params["residentId"].orEmpty()
                    val residentName = command.params["residentName"].orEmpty()
                    if (residentName.isNotBlank()) {
                        runExternalResidentCheckIn(
                            residentId,
                            residentName,
                            command.params["room"].orEmpty(),
                            command.params["mapPoint"].orEmpty(),
                            command.params["notes"].orEmpty()
                        )
                    } else {
                        runResidentCheckIn(residentId)
                    }
                }
                "med_reminder" -> {
                    val reminderId = command.params["reminderId"].orEmpty()
                    val residentName = command.params["residentName"].orEmpty()
                    if (reminderId.isNotBlank()) runReminder(reminderId)
                    else if (residentName.isNotBlank()) {
                        runExternalResidentCheckIn(
                            command.params["residentId"].orEmpty(),
                            residentName,
                            command.params["room"].orEmpty(),
                            command.params["mapPoint"].orEmpty(),
                            command.params["notes"].orEmpty().ifBlank { "Medication reminder requested from the care cloud." }
                        )
                    } else runReminderForResident(command.params["residentId"].orEmpty())
                }
                "staff_alert" -> createStaffAlert(
                    command.params["priority"].orEmpty(),
                    command.params["room"].orEmpty(),
                    command.params["message"].orEmpty()
                )
                else -> setStatus("Unknown phone command: ${command.action}")
            }
        }
        return "Command sent: ${command.action}"
    }

    private fun localIpAddress(): String =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "nova-ip"

    private fun robotReadySoon(): Boolean = runCatching { robot.isRobotSdkAvailable }.getOrDefault(false)

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(PrimaryDark)
        setPadding(0, dp(18), 0, dp(8))
    }

    private inner class FacilityMapView(
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

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Muted)
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun input(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine(true)
        textSize = 17f
        setTextColor(Text)
        setHintTextColor(Muted)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(Color.WHITE, dp(8), Stroke)
    }

    private fun destinationDropdown(): Spinner = Spinner(this).apply {
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

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Card, dp(8), Color.rgb(220, 229, 230))
        setPadding(dp(8), dp(6), dp(8), dp(8))
        layoutParams = full().apply { bottomMargin = dp(6) }
    }

    private fun compactCard(text: String, onClick: () -> Unit): View = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Text)
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
        layoutParams = full().apply { bottomMargin = dp(8) }
    }

    private fun emptyState(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Muted)
        gravity = Gravity.CENTER
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(18), dp(20), dp(18), dp(20))
    }

    private fun buttonRow(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, 0)
        children.forEachIndexed { index, child ->
            addView(child, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index != children.lastIndex) marginEnd = dp(8)
            })
        }
    }

    private fun buttonGrid(columns: Int, vararg children: View): LinearLayout = LinearLayout(this).apply {
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

    private fun actionButton(text: String, color: Int = Primary, onClick: () -> Unit): Button = Button(this).apply {
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

    private fun badge(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(color, dp(99))
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun full(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val Primary = Color.rgb(18, 110, 130)
        private val PrimaryDark = Color.rgb(20, 48, 57)
        private val Accent = Color.rgb(68, 142, 127)
        private val Neutral = Color.rgb(86, 102, 108)
        private val Warning = Color.rgb(184, 118, 43)
        private val Danger = Color.rgb(160, 55, 55)
        private val Good = Color.rgb(62, 142, 84)
        private val CareBlue = Color.rgb(37, 125, 223)
        private val CareYellow = Color.rgb(241, 178, 20)
        private val CarePurple = Color.rgb(132, 78, 210)
        private val Surface = Color.rgb(239, 244, 244)
        private val Card = Color.rgb(253, 254, 254)
        private val Stroke = Color.rgb(201, 214, 217)
        private val Text = Color.rgb(25, 38, 43)
        private val Muted = Color.rgb(92, 107, 112)
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
