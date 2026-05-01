package com.codex.novamessenger

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// ── Navigation helpers ────────────────────────────────────────────────────────

internal fun MainActivity.navRail(): View = buttonRow(
    navButton("Concierge", "home"),
    navButton("Care", "care"),
    navButton("Message", "message"),
    navButton("Map", "destinations"),
    navButton("Robot", "robot"),
    navButton("Camera", "camera")
).apply { setPadding(0, dp(10), 0, dp(4)) }

internal fun MainActivity.navButton(text: String, page: String) =
    actionButton(text, if (currentPage == page) Accent else PrimaryDark) {
        currentPage = page
        setContentView(buildUi())
    }

internal fun MainActivity.bottomNav(): View = buttonRow(
    navButton("Home", "home"),
    navButton("Message", "message"),
    navButton("Care", "care"),
    navButton("Map", "destinations"),
    navButton("Robot", "robot"),
    navButton("Camera", "camera")
)

// ── Two-pane layout ───────────────────────────────────────────────────────────

internal fun MainActivity.twoPane(left: View, right: View, leftWeight: Float = 1f, rightWeight: Float = 1f): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, 0)
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, leftWeight).apply {
            marginEnd = dp(6)
        })
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, rightWeight))
    }

// ── Compact status tile ───────────────────────────────────────────────────────

internal fun MainActivity.compactStatus(title: String, value: String): View {
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

// ── Pages ─────────────────────────────────────────────────────────────────────

internal fun MainActivity.homePage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(homeCommandGrid())
    root.addView(buttonRow(
        actionButton("Stop Task", Danger) { stopAll() },
        actionButton("Guest Assist", Accent) { guestAssist.startGuestAssist(auto = false) }
    ))
    return root
}

internal fun MainActivity.homeHero(): View {
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

internal fun MainActivity.homeCommandGrid(): View {
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
            careWorkflow.createStaffAlert("urgent", destination(), "Assistance requested from Nova.")
        }
    ))
    return grid
}

internal fun MainActivity.homeCommandTile(title: String, subtitle: String, code: String, color: Int, onClick: () -> Unit): View {
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

internal fun MainActivity.messagePage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Message Delivery", "Record, save, and send a visitor message to a saved map point."))
    root.addView(twoPane(workflowCard(), messageQueuePanel(), 1.05f, 1f))
    messageDelivery.refreshMessages()
    return root
}

internal fun MainActivity.carePage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Care Rounds", "Check-ins, medication reminders, and resident visits."))
    root.addView(careActionsPanel())
    root.addView(careResidentsPanel())
    return root
}

internal fun MainActivity.destinationsPage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Map Destinations", "Pick a named point. Nova uses RobotAPI navigation to guide or deliver."))
    root.addView(twoPane(facilityMapCard(), pointsPanel(), 1.35f, 0.65f))
    renderPoints(lastMapPoints, updateStatus = false)
    return root
}

internal fun MainActivity.robotPage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Follow Mode", "Nova follows a person — choose the right profile for your space."))
    root.addView(followProfilePanel())
    root.addView(followActionsCard())
    return root
}

internal fun MainActivity.cameraPage(): View {
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

// ── Panels ────────────────────────────────────────────────────────────────────

internal fun MainActivity.cameraActionsCard(): View {
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

internal fun MainActivity.cameraPreviewView(): TextureView = TextureView(this).apply {
    layoutParams = full().apply {
        height = dp(355)
        bottomMargin = dp(8)
    }
    cameraFeed.bindPreview(this)
}

internal fun MainActivity.messageQueuePanel(): View {
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

internal fun MainActivity.residentsPanel(): View {
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

internal fun MainActivity.pointsPanel(): View {
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

internal fun MainActivity.robotHealthCard(): View {
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

internal fun MainActivity.robotActionsCard(): View {
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
        actionButton("Robot Check", Primary) { careWorkflow.runRobotCheck() },
        actionButton("Stop", Danger) { stopAll() }
    ))
    box.addView(buttonRow(
        actionButton("Charge", PrimaryDark) { setStatus(robot.goCharge().message) },
        actionButton("Battery", Neutral) {
            lastBattery = robot.batteryInfo()
            setStatus(lastBattery)
        }
    ))
    box.addView(actionButton("Start Guest Assist", Accent) { guestAssist.startGuestAssist(auto = false) }.apply {
        layoutParams = full().apply { topMargin = dp(6) }
    })
    return box
}

internal fun MainActivity.detectionOverlayCard(): View {
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

internal fun MainActivity.robotHealthPanel(): View {
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

internal fun MainActivity.legacyRobotHealthPanel(): View {
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

internal fun MainActivity.facilityMapCard(): View {
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

internal fun MainActivity.activePathText(): String =
    if (currentTaskProgress > 0) "Nova -> ${destination()} -> ${currentTaskNext}" else "No active route"

internal fun MainActivity.movementVisualPanel(): TextView {
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

internal fun MainActivity.pageHero(title: String, subtitle: String): View {
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

internal fun MainActivity.systemOverview(): View {
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

internal fun MainActivity.metricGrid(): View {
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

internal fun MainActivity.metricCard(code: String, value: String, caption: String): View {
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

internal fun MainActivity.quickMessageBox(): View {
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
        actionButton("Listen Now", Accent) { guestAssist.listenToGuest() },
        actionButton("Record Message", Primary) {
            currentPage = "message"
            setContentView(buildUi())
            messageDelivery.askAndRecord()
        },
        actionButton("Use Help Message", Accent) {
            currentPage = "message"
            setContentView(buildUi())
            setMessageText("I need help. Please send someone.")
            messageDelivery.sendCurrentMessageToPoint()
        }
    ))
    return card
}

internal fun MainActivity.careDashboard(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    main.addView(buttonRow(
        commandTile("Start Rounds", "", "ROUTE", Good) { careWorkflow.startCareRound() },
        commandTile("Check-In", "", "CARE", CareBlue) { careWorkflow.runResidentCheckIn(careRepo.residents().firstOrNull()?.id) }
    ))
    main.addView(buttonRow(
        commandTile("Meds", "", "MED", CareYellow) { careWorkflow.runNextReminder() },
        commandTile("Alert", "", "ALERT", Danger) { careWorkflow.createStaffAlert("urgent", "", "Resident or visitor requested assistance.") }
    ))
    main.addView(wideCommandTile("Visitor Guide", "", "MAP", CarePurple) { careWorkflow.runVisitorGuide(destination()) })

    side.addView(currentTaskCard())
    side.addView(recentActivityPanel())

    root.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply { marginEnd = dp(12) })
    root.addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    return root
}

internal fun MainActivity.commandTile(title: String, subtitle: String, iconText: String, color: Int, onClick: () -> Unit): View {
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

internal fun MainActivity.wideCommandTile(title: String, subtitle: String, iconText: String, color: Int, onClick: () -> Unit): View {
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

internal fun MainActivity.circleIcon(text: String, color: Int, size: Int): View = TextView(this).apply {
    this.text = text
    textSize = if (size <= dp(40)) 13f else 18f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
    background = rounded(color, size / 2)
    layoutParams = LinearLayout.LayoutParams(size, size)
}

internal fun MainActivity.currentTaskCard(): View {
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

internal fun MainActivity.recentActivityPanel(): View {
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

internal fun MainActivity.quickActions(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(buttonRow(
        quickAction("CHARGE", "Charge", Good) { setStatus(robot.goCharge().message) },
        quickAction("HOME", "Reception", Primary) {
            setDestinationText(careWorkflow.resolveMapPoint("Reception"))
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

internal fun MainActivity.quickAction(code: String, title: String, color: Int, onClick: () -> Unit): View {
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

internal fun MainActivity.currentTaskText(): Triple<String, String, Int> {
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

internal fun MainActivity.taskJourneyView(): View {
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

internal fun MainActivity.progressBar(percent: Int): View {
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

internal fun MainActivity.taskDetailRow(label: String, value: String): View {
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

internal fun MainActivity.activityRow(title: String, detail: String, state: String, color: Int): View {
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

internal fun MainActivity.tint(color: Int): Int =
    Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))

internal fun MainActivity.cleanConciergePanel(): View {
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
            messageDelivery.askAndRecord()
        },
        actionButton("Guide", Primary) { goToDestination() }
    ))
    box.addView(buttonRow(
        actionButton("Follow", PrimaryDark) { startFollowMode() },
        actionButton("Stop", Danger) { stopAll() }
    ))
    return box
}

internal fun MainActivity.careCommandPanel(): View {
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
        actionButton("Start Round", Accent) { careWorkflow.startCareRound() },
        actionButton("Staff Alert", Danger) { careWorkflow.createStaffAlert("urgent", "", "A resident or visitor requested assistance.") }
    ))
    box.addView(buttonRow(
        actionButton("Medication", Primary) { careWorkflow.runNextReminder() },
        actionButton("Safety Watch", PrimaryDark) { startSecurityWatch() }
    ))
    return box
}

internal fun MainActivity.residentCard(resident: CareResident): View {
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
        actionButton("Check In", Accent) { careWorkflow.runResidentCheckIn(resident.id) },
        actionButton("Reminder", Primary) { careWorkflow.runReminderForResident(resident.id) }
    ))
    val alertButton = actionButton("Alert Staff", Danger) {
        careWorkflow.createStaffAlert("normal", resident.room, "${resident.name} requested staff assistance.")
    }
    alertButton.layoutParams = full().apply { topMargin = dp(6) }
    box.addView(alertButton)
    return box
}

internal fun MainActivity.careTodayPanel(): View {
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
                careWorkflow.runReminder(reminder.id)
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

internal fun MainActivity.careLogCard(log: CareLog): View =
    compactCard("${log.title}\n${log.detail}") {
        setStatus("${log.title}: ${log.detail}")
    }

internal fun MainActivity.statusStrip(): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(buttonRow(
        compactStatus("Map", "${lastMapPoints.size} points"),
        compactStatus("Power", lastBattery.replace("Battery ", "")),
        compactStatus("Queue", "${repo.pendingCount()}")
    ))
    row.addView(compactCard("Phone control: http://${localIpAddress()}:8787") {
        setStatus("Open http://${localIpAddress()}:8787 on your phone browser to access Nova Control.")
    })
    return row
}

internal fun MainActivity.featureHub(): View {
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

internal fun MainActivity.featureTile(title: String, subtitle: String, onClick: () -> Unit): View {
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

internal fun MainActivity.conciergePanel(): View {
    val card = card()
    card.addView(buttonRow(
        actionButton("Send to Point", Accent) { messageDelivery.sendCurrentMessageToPoint() },
        actionButton("Guide", Primary) { goToDestination() },
        actionButton("Follow", PrimaryDark) { startFollowMode() }
    ))
    card.addView(buttonRow(
        actionButton("Load Points", Neutral) { loadMapPoints() },
        actionButton("Robot Check", Neutral) { careWorkflow.runRobotCheck() },
        actionButton("Stop", Danger) { stopAll() }
    ))
    return card
}

internal fun MainActivity.header(): View {
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

internal fun MainActivity.zoxLogoMark(): View = TextView(this).apply {
    text = "ZOX"
    textSize = 11f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(Color.rgb(18, 211, 234))
    background = rounded(Color.rgb(4, 20, 45), dp(16), Color.rgb(18, 198, 231))
    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
}

internal fun MainActivity.workflowCard(): View {
    val card = card()
    clientInput = input("Client or host name", clientName)
    pointInput = destinationDropdown()
    messageInput = android.widget.EditText(this).apply {
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
        actionButton("Record", Primary) { messageDelivery.askAndRecord() },
        actionButton("Stop + Save", Warning) { messageDelivery.stopRecordingAndSave() }
    ))
    card.addView(buttonRow(
        actionButton("Save Text", Neutral) { messageDelivery.saveTextOnlyMessage() },
        actionButton("Speak", PrimaryDark) { messageDelivery.speakCurrentMessage() }
    ))
    return card
}

internal fun MainActivity.guestAssistCard(): View {
    val card = card()
    card.addView(buttonRow(
        actionButton("Start Assist", Accent) { guestAssist.startGuestAssist(auto = false) },
        actionButton("Listen Now", PrimaryDark) { guestAssist.listenToGuest() },
        actionButton("Robot Check", Primary) { careWorkflow.runRobotCheck() },
        actionButton("Stop Assist", Danger) { guestAssist.stopGuestAssist() }
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

internal fun MainActivity.robotControlsCard(): View {
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

// ── Follow page ────────────────────────────────────────────────────────────────

internal fun MainActivity.followProfilePanel(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Follow Profile"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    val isNarrow = follow.isNarrowMode()
    box.addView(buttonRow(
        followProfileTile(
            "Open Hall",
            "Wide corridors & lobbies",
            Good,
            !isNarrow && follow.isRunning()
        ) { startFollowMode() },
        followProfileTile(
            "Narrow Aisle",
            "~1 m corridor, twice Nova width",
            Accent,
            isNarrow
        ) { startNarrowFollowMode() }
    ))
    val targets = runCatching { robot.getBodyTargets() }.getOrDefault(emptyList())
    val nearest = targets.minByOrNull { it.distanceMeters }
    val statusText = when {
        follow.isRunning() && nearest != null -> "Following at ${"%.1f".format(nearest.distanceMeters)} m"
        follow.isRunning() -> "Searching for person..."
        nearest != null -> "Person detected at ${"%.1f".format(nearest.distanceMeters)} m — press a profile to start"
        else -> "No person in view — stand 1–2 m in front of Nova, then press a profile"
    }
    val statusColor = if (follow.isRunning()) Good else Muted
    val statusBg = if (follow.isRunning()) Color.rgb(232, 248, 235) else Color.rgb(245, 248, 250)
    box.addView(TextView(this).apply {
        text = statusText
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(statusColor)
        background = rounded(statusBg, dp(8), Stroke)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = full().apply { topMargin = dp(8) }
    })
    return box
}

internal fun MainActivity.followProfileTile(title: String, subtitle: String, color: Int, active: Boolean, onClick: () -> Unit): View {
    val bg = if (active) Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)) else Color.WHITE
    val border = if (active) color else Stroke
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(bg, dp(12), border)
        setPadding(dp(10), dp(16), dp(10), dp(16))
        minimumHeight = dp(88)
        setOnClickListener { onClick() }
    }
    box.addView(TextView(this).apply {
        text = title
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (active) color else Text)
    })
    box.addView(TextView(this).apply {
        text = subtitle
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(Muted)
        setPadding(0, dp(4), 0, 0)
    })
    return box
}

internal fun MainActivity.followActionsCard(): View {
    val box = card()
    box.addView(buttonRow(
        actionButton("Start Following", Good) { startFollowMode() },
        actionButton("Stop", Danger) { stopAll() }
    ))
    box.addView(buttonRow(
        actionButton("Guide to ${destination()}", Primary) { goToDestination() },
        actionButton("Go Charge", Neutral) { setStatus(robot.goCharge().message) }
    ))
    return box
}

// ── Care page (client-ready) ───────────────────────────────────────────────────

internal fun MainActivity.careActionsPanel(): View {
    val box = card()
    box.addView(buttonRow(
        careActionTile("Begin\nRounds", "Visit all residents", CareBlue) { careWorkflow.startCareRound() },
        careActionTile("Medication\nTime", "Deliver a reminder", CareYellow) { careWorkflow.runNextReminder() }
    ))
    box.addView(buttonRow(
        careActionTile("Call for\nHelp", "Alert staff now", Danger) {
            careWorkflow.createStaffAlert("urgent", "", "Resident or visitor requested assistance.")
        },
        careActionTile("Safety\nWatch", "Monitor the area", PrimaryDark) { startSecurityWatch() }
    ))
    val pending = careRepo.reminders().filter { it.doneAt == null }
    if (pending.isNotEmpty()) {
        box.addView(sectionTitle("Due Today"))
        pending.take(2).forEach { reminder ->
            val resident = careRepo.resident(reminder.residentId)
            box.addView(compactCard("${reminder.timeLabel}  •  ${reminder.title}  •  ${resident?.name ?: ""}") {
                careWorkflow.runReminder(reminder.id)
            })
        }
    }
    return box
}

internal fun MainActivity.careActionTile(title: String, subtitle: String, color: Int, onClick: () -> Unit): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(Color.WHITE, dp(12), Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        setPadding(dp(10), dp(16), dp(10), dp(16))
        minimumHeight = dp(88)
        setOnClickListener { onClick() }
    }
    box.addView(TextView(this).apply {
        text = title
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(color)
    })
    box.addView(TextView(this).apply {
        text = subtitle
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(Muted)
        setPadding(0, dp(4), 0, 0)
    })
    return box
}

internal fun MainActivity.careResidentsPanel(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Residents"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
        setPadding(0, 0, 0, dp(4))
    })
    val residents = careRepo.residents()
    if (residents.isEmpty()) {
        box.addView(emptyState("No residents configured yet. Add residents in the cloud dashboard to start care rounds."))
    } else {
        residents.take(5).forEach { resident -> box.addView(residentRowCard(resident)) }
    }
    val logs = careRepo.logs().take(3)
    if (logs.isNotEmpty()) {
        box.addView(sectionTitle("Recent Activity"))
        logs.forEach { box.addView(careLogCard(it)) }
    }
    return box
}

internal fun MainActivity.residentRowCard(resident: CareResident): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.WHITE, dp(10), Stroke)
        setPadding(dp(12), dp(10), dp(10), dp(10))
        layoutParams = full().apply { bottomMargin = dp(6) }
    }
    val nameBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    nameBox.addView(TextView(this).apply {
        text = resident.name
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    nameBox.addView(TextView(this).apply {
        text = resident.room
        textSize = 12f
        setTextColor(Muted)
        setPadding(0, dp(2), 0, 0)
    })
    row.addView(nameBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(actionButton("Visit", CareBlue) { careWorkflow.runResidentCheckIn(resident.id) }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(38))
    })
    return row
}
