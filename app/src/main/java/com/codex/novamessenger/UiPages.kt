package com.codex.novamessenger

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Navigation ────────────────────────────────────────────────────────────────

internal fun MainActivity.bottomNav(): View {
    data class Tab(val label: String, val page: String)
    val tabs = listOf(
        Tab("Home", "home"), Tab("Message", "message"), Tab("Care", "care"),
        Tab("Map", "destinations"), Tab("Robot", "robot"), Tab("Camera", "camera")
    )
    return LinearLayout(this@bottomNav).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(Color.WHITE, dp(16), Color.rgb(210, 222, 224))
        setPadding(dp(4), 0, dp(4), 0)
        layoutParams = full().apply { topMargin = dp(10) }
        tabs.forEach { tab ->
            val active = currentPage == tab.page
            val item = LinearLayout(this@bottomNav).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { currentPage = tab.page; setContentView(buildUi()) }
            }
            if (active) {
                item.addView(View(this@bottomNav).apply {
                    background = rounded(Primary, dp(99))
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(3)).also { it.bottomMargin = dp(4) }
                })
            } else {
                item.addView(View(this@bottomNav).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(3)).also { it.bottomMargin = dp(4) }
                })
            }
            item.addView(android.widget.TextView(this@bottomNav).apply {
                text = tab.label
                textSize = 11f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = android.view.Gravity.CENTER
                setTextColor(if (active) Primary else Muted)
            })
            addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}

// ── Layout helpers ────────────────────────────────────────────────────────────

internal fun MainActivity.twoPane(left: View, right: View, leftWeight: Float = 1f, rightWeight: Float = 1f): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, 0)
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, leftWeight).apply {
            marginEnd = dp(6)
        })
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, rightWeight))
    }

internal fun MainActivity.compactStatus(title: String, value: String): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Card, dp(8), Stroke)
        setPadding(dp(7), dp(4), dp(7), dp(5))
    }
    box.addView(TextView(this).apply {
        text = title
        textSize = 8f
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

// ── Global status strips ──────────────────────────────────────────────────────

internal fun MainActivity.taskProgressStrip(): View {
    val stopped = safetyStopStatus.contains("Stopped")
    val hasTask = currentTaskProgress > 0 && !stopped

    val bg = when {
        stopped -> Color.rgb(255, 232, 232)
        hasTask -> Color.rgb(240, 248, 255)
        else    -> Color.rgb(246, 248, 250)
    }
    val border = when { stopped -> Danger; hasTask -> Primary; else -> Stroke }

    val box = LinearLayout(this@taskProgressStrip).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(bg, dp(10), border)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = full().apply { bottomMargin = dp(3) }
    }

    if (stopped) {
        box.addView(TextView(this@taskProgressStrip).apply {
            text = "Stopped (Safety) — All movement halted"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Danger)
            layoutParams = full()
        })
        return box
    }

    val titleRow = LinearLayout(this@taskProgressStrip).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    titleRow.addView(TextView(this@taskProgressStrip).apply {
        text = if (hasTask) currentTaskTitle.ifBlank { "Task in progress" } else "Ready"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (hasTask) Text else Muted)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    titleRow.addView(TextView(this@taskProgressStrip).apply {
        text = if (hasTask) "${currentTaskProgress}%" else "Standby"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (hasTask) Primary else Muted)
    })
    box.addView(titleRow, full())

    if (hasTask && currentTaskStage.isNotBlank()) {
        box.addView(TextView(this@taskProgressStrip).apply {
            text = currentTaskStage
            textSize = 8f
            setTextColor(Muted)
            setPadding(0, dp(2), 0, dp(4))
        })
    }

    val prog = currentTaskProgress.coerceIn(0, 100)
    val barRow = LinearLayout(this@taskProgressStrip).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(Color.rgb(218, 232, 242), dp(4), 0)
        layoutParams = full().apply { height = dp(7); topMargin = dp(3) }
    }
    if (prog > 0) barRow.addView(View(this@taskProgressStrip).apply {
        background = rounded(Primary, dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, prog.toFloat())
    })
    if (prog < 100) barRow.addView(View(this@taskProgressStrip).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - prog).toFloat())
    })
    box.addView(barRow)
    return box
}

internal fun MainActivity.emergencyStopBar(): View {
    val stopped = safetyStopStatus.contains("Stopped")
    return if (stopped) {
        LinearLayout(this@emergencyStopBar).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(255, 246, 246), dp(8), Danger)
            minimumHeight = dp(34)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = full().apply { topMargin = dp(4); bottomMargin = dp(4) }
            isClickable = true; isFocusable = true
            setOnClickListener { resumeOperations(); setContentView(buildUi()) }
            addView(android.widget.TextView(this@emergencyStopBar).apply {
                text = "STOPPED  —  Tap to Resume Operations"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Danger)
                layoutParams = full()
            })
        }
    } else {
        LinearLayout(this@emergencyStopBar).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = full().apply { topMargin = dp(4); bottomMargin = dp(6) }
            addView(View(this@emergencyStopBar).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(android.widget.TextView(this@emergencyStopBar).apply {
                text = "Emergency Stop"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Danger)
                background = rounded(Color.rgb(255, 246, 246), dp(8), Danger)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isClickable = true; isFocusable = true
                setOnClickListener { stopAll(); setContentView(buildUi()) }
            })
        }
    }
}

// ── Pages ─────────────────────────────────────────────────────────────────────

internal fun MainActivity.homePage(): View {
    val root = LinearLayout(this@homePage).apply { orientation = LinearLayout.VERTICAL }

    root.addView(LinearLayout(this@homePage).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(1), dp(4), dp(5))
        addView(android.widget.TextView(this@homePage).apply {
            text = "Today"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PrimaryDark)
        })
        addView(android.widget.TextView(this@homePage).apply {
            text = "Choose a care workflow or let visitors speak naturally to Nova."
            textSize = 7f
            setTextColor(Muted)
            setPadding(0, dp(2), 0, 0)
        })
    })

    root.addView(buttonRow(
        compactStatus("Residents", careRepo.residents().size.toString()),
        compactStatus("Due", careRepo.reminders().count { it.doneAt == null }.toString()),
        compactStatus("Alerts", careRepo.alerts().size.toString())
    ))

    root.addView(twoPane(
        homeFeatureCard("Visitor Message", "Capture and deliver a message",
            repo.pendingCount().let { if (it > 0) "$it pending" else null }, Accent) {
            currentPage = "message"; setContentView(buildUi())
        },
        homeFeatureCard("Care Rounds", "Check-ins and reminders",
            careRepo.reminders().count { it.doneAt == null }.let { if (it > 0) "$it due" else null }, CareBlue) {
            currentPage = "care"; setContentView(buildUi())
        }
    ))

    root.addView(twoPane(
        homeFeatureCard("Resident Check-In", "Visit one resident room", null, Good) {
            currentPage = "care"; setContentView(buildUi())
        },
        homeFeatureCard("Staff Alert", "Notify care staff now", careRepo.alerts().size.takeIf { it > 0 }?.let { "$it open" }, Danger) {
            careWorkflow.createStaffAlert("urgent", destination(), "Assistance requested from Nova.")
        }
    ))

    root.addView(card().also { c ->
        c.addView(buttonRow(
            destinationDropdown().also { pointInput = it },
            actionButton("Guide Visitor", Primary) { saveSettings(); goToDestination() },
            actionButton("Safety View", PrimaryDark) { currentPage = "camera"; setContentView(buildUi()) }
        ))
    })

    return root
}
internal fun MainActivity.homeFeatureCard(title: String, subtitle: String, badge: String?, color: Int, onClick: () -> Unit): View {
    val box = LinearLayout(this@homeFeatureCard).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, dp(10), Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)))
        setPadding(dp(8), dp(7), dp(8), dp(7))
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }
    box.addView(View(this@homeFeatureCard).apply {
        background = rounded(color, dp(6))
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(3)).also { it.bottomMargin = dp(5) }
    })
    box.addView(android.widget.TextView(this@homeFeatureCard).apply {
        text = title
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(PrimaryDark)
    })
    box.addView(android.widget.TextView(this@homeFeatureCard).apply {
        text = subtitle
        textSize = 7f
        setTextColor(Muted)
        setPadding(0, dp(4), 0, 0)
    })
    if (badge != null) {
        box.addView(android.widget.TextView(this@homeFeatureCard).apply {
            text = badge
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            background = rounded(Color.argb(22, Color.red(color), Color.green(color), Color.blue(color)), dp(6), color)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = full().apply { topMargin = dp(6) }
        })
    }
    box.addView(android.widget.TextView(this@homeFeatureCard).apply {
        text = "Open"
        textSize = 8f
        setTextColor(Color.argb(130, Color.red(color), Color.green(color), Color.blue(color)))
        gravity = android.view.Gravity.END
        layoutParams = full().apply { topMargin = dp(4) }
    })
    return box
}

internal fun MainActivity.messagePage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(backHeader("Visitor Message"))
    root.addView(workflowCard())
    return root
}

internal fun MainActivity.carePage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Care Rounds", "Check-ins, medication reminders, and resident visits."))
    root.addView(careActionsPanel())
    if (careRepo.alerts().isNotEmpty()) root.addView(activeAlertsPanel())
    root.addView(careResidentAccessPanel())
    root.addView(careToolsCard())
    return root
}

internal fun MainActivity.residentsPage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Residents & Rooms", "Private resident records and navigation pins. Manage them from cloud when possible."))
    root.addView(careResidentsPanel())
    return root
}

internal fun MainActivity.destinationsPage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Facility Map", "Select a verified destination before Nova moves."))
    root.addView(mapSelectPanel())
    root.addView(facilityMapCard())
    renderPoints(lastMapPoints, updateStatus = false)
    return root
}

internal fun MainActivity.robotPage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Operations Settings", "Care timing, base location, follow assistance, and cloud connection."))
    root.addView(followProfilePanel())
    root.addView(followActionsCard())
    root.addView(settingsPanel())
    return root
}

internal fun MainActivity.cameraPage(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(pageHero("Safety View", "Live camera, person detection, and after-hours monitoring."))
    val panel = card()
    panel.addView(TextView(this).apply {
        text = "Observation"
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    panel.addView(cameraPreviewView())
    panel.addView(detectionOverlayCard())
    root.addView(twoPane(panel, cameraActionsCard(), 1.45f, 0.55f))
    return root
}

// ── Camera panels ─────────────────────────────────────────────────────────────

internal fun MainActivity.cameraActionsCard(): View {
    val panel = card()
    panel.addView(TextView(this).apply {
        text = "Detection Mode"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    val openCameraButton = actionButton("Start View", Primary) { startCameraFeed() }
    val closeCameraButton = actionButton("Close View", Neutral) { stopCameraFeed() }
    val startWatchButton = actionButton("Begin Watch", Accent) { startSecurityWatch() }
    val stopWatchButton = actionButton("End Watch", Danger) { stopSecurityWatch() }
    val scanButton = actionButton("Check Area", PrimaryDark) { observeSecurity() }
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
        height = dp(285)
        bottomMargin = dp(8)
    }
    cameraFeed.bindPreview(this)
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

// ── Message panels ────────────────────────────────────────────────────────────

internal fun MainActivity.messageQueuePanel(): View {
    val box = card()
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@messageQueuePanel).apply {
            text = "Message History"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(compactStatus("Pending", repo.pendingCount().toString()), LinearLayout.LayoutParams(dp(115), LinearLayout.LayoutParams.WRAP_CONTENT))
    })
    messageList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(messageList, full().apply { topMargin = dp(6) })
    return box
}

internal fun MainActivity.workflowCard(): View {
    val card = card()
    card.addView(TextView(this).apply {
        text = "Message Intake"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    card.addView(TextView(this).apply {
        text = "For visitors, family members, delivery staff, and care handoffs."
        textSize = 7f
        setTextColor(Muted)
        setPadding(0, dp(1), 0, dp(3))
    })
    clientInput = input("Client or host name", clientName)
    pointInput = destinationDropdown()
    messageInput = android.widget.EditText(this).apply {
        hint = "Message for the destination"
        setText(messageDraft)
        minLines = 1
        gravity = Gravity.TOP
        textSize = 9f
        setTextColor(Text)
        setHintTextColor(Muted)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = rounded(Color.WHITE, dp(8), Stroke)
    }
    val intakeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(LinearLayout(this@workflowCard).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("From / For"))
            addView(clientInput, full())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        addView(LinearLayout(this@workflowCard).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Destination"))
            addView(pointInput, full())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    card.addView(intakeRow)
    card.addView(label("Message"))
    card.addView(messageInput, full())
    card.addView(buttonRow(
        actionButton("Record Message", Primary) { messageDelivery.askAndRecord() },
        actionButton("Save Recording", Warning) { messageDelivery.stopRecordingAndSave() }
    ))
    card.addView(buttonRow(
        actionButton("Save Note", Neutral) { messageDelivery.saveTextOnlyMessage() },
        actionButton("Preview Voice", PrimaryDark) { messageDelivery.speakCurrentMessage() }
    ))
    card.addView(actionButton("Deliver to ${destination()}", Accent) {
        messageDelivery.sendCurrentMessageToPoint()
    }.apply {
        textSize = 9f
        layoutParams = full().apply { topMargin = dp(5) }
    })
    return card
}

// ── Map panels ────────────────────────────────────────────────────────────────

internal fun MainActivity.facilityMapCard(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Facility View"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    val pose = runCatching { robot.getRobotPose() }.getOrNull()
    box.addView(FacilityMapView(pose, lastMapPoints, destination(), careRepo.alerts().firstOrNull()?.room).apply {
        layoutParams = full().apply {
            height = dp(235)
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
    })
    box.addView(buttonRow(
        compactStatus("Selected", destination()),
        compactStatus("Saved Points", if (lastMapPoints.isEmpty()) "Refresh needed" else "${lastMapPoints.size}")
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
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (moving) Primary else Text)
        background = rounded(Color.rgb(246, 250, 250), dp(8), Stroke)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
}

internal fun MainActivity.pointsPanel(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Rooms & Care Points"
        textSize = 13f
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

// ── Care panels ───────────────────────────────────────────────────────────────

internal fun MainActivity.mapSelectPanel(): View {
    val box = card()
    box.addView(buttonRow(
        destinationDropdown().also { pointInput = it },
        actionButton("Refresh", Primary) { loadMapPoints() },
        actionButton("Guide", Accent) { goToDestination() }
    ))
    pointsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    return box
}

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
        minimumHeight = dp(70)
        setOnClickListener { onClick() }
    }
    box.addView(TextView(this).apply {
        text = title
        textSize = 8f
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

internal fun MainActivity.careResidentAccessPanel(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Residents & Room Pins"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    box.addView(TextView(this).apply {
        text = "Resident details stay private on Nova. Open only when needed for check-ins or to confirm a saved room point."
        textSize = 7f
        setTextColor(Muted)
        setPadding(0, dp(2), 0, dp(6))
    })
    box.addView(buttonRow(
        actionButton("View Residents", CareBlue) {
            AlertDialog.Builder(this@careResidentAccessPanel)
                .setTitle("Open resident list?")
                .setMessage("Resident names and room pins are private. Use the cloud dashboard for full editing when possible.")
                .setPositiveButton("Open") { _, _ -> currentPage = "residents"; setContentView(buildUi()) }
                .setNegativeButton("Cancel", null)
                .show()
        },
        actionButton("Cloud Manages Pins", Neutral) {
            speakReply("Please update resident rooms and saved map points from the care cloud dashboard.")
            setStatus("Resident room pins should be managed from cloud or confirmed before local edits.")
        }
    ))
    return box
}

internal fun MainActivity.careResidentsPanel(): View {
    val box = card()
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = full().apply { bottomMargin = dp(8) }
    }
    header.addView(TextView(this).apply {
        text = "Residents"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    header.addView(actionButton("+ Add", CareBlue) { confirmResidentPinEdit(null) }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(80), dp(34))
    })
    box.addView(header)
    val residents = careRepo.residents()
    if (residents.isEmpty()) {
        box.addView(emptyState("No residents yet. Tap + Add to create the first one."))
    } else {
        residents.forEach { resident -> box.addView(residentRowCard(resident)) }
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
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
    })
    nameBox.addView(TextView(this).apply {
        text = "${resident.room}  •  ${resident.mapPoint}"
        textSize = 8f
        setTextColor(Muted)
        setPadding(0, dp(2), 0, 0)
    })
    row.addView(nameBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(actionButton("Edit", PrimaryDark) { confirmResidentPinEdit(resident) }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(34)).also { it.rightMargin = dp(6) }
    })
    row.addView(actionButton("Visit", CareBlue) { careWorkflow.runResidentCheckIn(resident.id) }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(34)).also { it.rightMargin = dp(6) }
    })
    row.addView(actionButton("X", Color.parseColor("#E53935")) {
        AlertDialog.Builder(this@residentRowCard)
            .setTitle("Remove Resident")
            .setMessage("Remove ${resident.name} from care rounds?")
            .setPositiveButton("Remove") { _, _ -> careRepo.deleteResident(resident.id); buildUi().also { setContentView(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(34))
    })
    return row
}

internal fun MainActivity.confirmResidentPinEdit(resident: CareResident?) {
    AlertDialog.Builder(this)
        .setTitle(if (resident == null) "Add resident locally?" else "Edit resident room pin?")
        .setMessage("Preferred workflow: update residents and map pins in the cloud dashboard so Nova, phone, and dashboard stay consistent. Continue local edit only if you are confirming a real Nova map point now.")
        .setPositiveButton("Continue") { _, _ -> showResidentEditor(resident) }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun MainActivity.showResidentEditor(resident: CareResident?) {
    val form = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(8))
    }

    fun editField(hint: String, value: String, multiline: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setTextColor(Text)
            if (multiline) { minLines = 2; isSingleLine = false }
            layoutParams = full().apply { bottomMargin = dp(10) }
        }

    val nameField = editField("Full Name *", resident?.name ?: "")
    val roomField = editField("Room / Location", resident?.room ?: "")
    val notesField = editField("Notes (optional)", resident?.notes ?: "", multiline = true)
    val promptField = editField("Check-in Message", resident?.checkInPrompt
        ?: "Hello. I am checking in. Do you need anything?", multiline = true)

    val mapLabel = TextView(this).apply {
        text = "Navigation Destination"
        textSize = 12f
        setTextColor(Muted)
        setPadding(0, 0, 0, dp(4))
    }
    val mapOptions = (lastMapPoints.map { it.name } + listOf("Reception", "Lobby", "Nurse Station"))
        .distinct().filter { it.isNotBlank() }.sorted()
    val mapSpinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@showResidentEditor, android.R.layout.simple_spinner_item, mapOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        layoutParams = full().apply { bottomMargin = dp(12) }
        val sel = mapOptions.indexOfFirst { it.equals(resident?.mapPoint, ignoreCase = true) }.coerceAtLeast(0)
        if (mapOptions.isNotEmpty()) setSelection(sel)
    }

    form.addView(nameField)
    form.addView(roomField)
    form.addView(mapLabel)
    form.addView(mapSpinner)
    form.addView(notesField)
    form.addView(promptField)

    AlertDialog.Builder(this)
        .setTitle(if (resident == null) "Add Resident" else "Edit Resident")
        .setView(ScrollView(this).also { it.addView(form) })
        .setPositiveButton("Save") { _, _ ->
            val name = nameField.text.toString().trim()
            if (name.isBlank()) return@setPositiveButton
            careRepo.upsertResident(CareResident(
                id = resident?.id ?: "res_${System.currentTimeMillis()}",
                name = name,
                room = roomField.text.toString().trim(),
                mapPoint = if (mapOptions.isEmpty()) "Reception" else mapOptions[mapSpinner.selectedItemPosition],
                notes = notesField.text.toString().trim(),
                checkInPrompt = promptField.text.toString().trim()
                    .ifBlank { "Hello. I am checking in. Do you need anything?" }
            ))
            setContentView(buildUi())
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun MainActivity.careLogCard(log: CareLog): View =
    compactCard("${log.title}\n${log.detail}") {
        setStatus("${log.title}: ${log.detail}")
    }

internal fun MainActivity.activeAlertsPanel(): View {
    val alerts = careRepo.alerts()
    val box = LinearLayout(this@activeAlertsPanel).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.rgb(255, 237, 237), dp(10), Danger)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = full().apply { bottomMargin = dp(6) }
    }
    box.addView(TextView(this@activeAlertsPanel).apply {
        text = "Active Staff Alerts  •  ${alerts.size}"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Danger)
        setPadding(0, 0, 0, dp(8))
    })
    alerts.take(4).forEach { alert ->
        val row = LinearLayout(this@activeAlertsPanel).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, dp(8), Stroke)
            setPadding(dp(10), dp(8), dp(8), dp(8))
            layoutParams = full().apply { bottomMargin = dp(6) }
        }
        row.addView(TextView(this@activeAlertsPanel).apply {
            text = "${alert.priority.replaceFirstChar { it.uppercaseChar() }}  •  ${alert.room.ifBlank { "On site" }}  —  ${alert.message}"
            textSize = 13f
            setTextColor(Text)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(actionButton("Dismiss", Neutral) {
            careRepo.dismissAlert(alert.id)
            setContentView(buildUi())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(36))
        })
        box.addView(row)
    }
    if (alerts.size > 1) {
        box.addView(actionButton("Dismiss All", Warning) {
            careRepo.clearAlerts()
            setContentView(buildUi())
        }.apply { layoutParams = full().apply { topMargin = dp(4) } })
    }
    return box
}

internal fun MainActivity.careToolsCard(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Demo / Compliance"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Muted)
        setPadding(0, 0, 0, dp(8))
    })
    box.addView(buttonRow(
        actionButton("Clear Activity Log", Neutral) {
            AlertDialog.Builder(this@careToolsCard)
                .setTitle("Clear Activity Log")
                .setMessage("Remove all logged care activities and alerts? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    careRepo.clearLogs()
                    careRepo.clearAlerts()
                    setContentView(buildUi())
                }
                .setNegativeButton("Cancel", null)
                .show()
        },
        actionButton("Reset Reminders", Warning) {
            AlertDialog.Builder(this@careToolsCard)
                .setTitle("Reset Reminders")
                .setMessage("Mark all medication reminders as pending again?")
                .setPositiveButton("Reset") { _, _ ->
                    careRepo.resetReminders()
                    setContentView(buildUi())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    ))
    return box
}

// ── Follow panels ─────────────────────────────────────────────────────────────

internal fun MainActivity.followProfilePanel(): View {
    val box = card()
    box.addView(TextView(this).apply {
        text = "Follow Assist"
        textSize = 13f
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
        textSize = 11f
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
        minimumHeight = dp(70)
        setOnClickListener { onClick() }
    }
    box.addView(TextView(this).apply {
        text = title
        textSize = 12f
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

// ── Settings panel ────────────────────────────────────────────────────────────

internal fun MainActivity.settingsPanel(): View {
    val box = card()

    // ── Mission Behavior ──────────────────────────────────────────────────────
    val afterMissionLabels = listOf("Return to Home Base", "Stay at Location", "Go Charge", "Ask Operator")
    val afterMissionKeys  = listOf("home_base",           "stay",             "charge",    "ask")
    val homeBaseOptions = (lastMapPoints.map { it.name } +
        listOf("Reception", "Reception Point", "Lobby", "Entrance", "Charging Point"))
        .distinctBy { it.lowercase() }

    val missionBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.rgb(236, 248, 253), dp(12), Primary)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = full().apply { bottomMargin = dp(4) }
    }
    missionBox.addView(TextView(this).apply {
        text = "After Visit"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(PrimaryDark)
        setPadding(0, 0, 0, dp(10))
    })

    val afterMissionDisplay = afterMissionLabels.getOrElse(
        afterMissionKeys.indexOf(vm.afterMissionBehavior).coerceAtLeast(0)) { "Return to Home Base" }
    missionBox.addView(buttonRow(
        compactStatus("Base", vm.homeBase),
        compactStatus("After Visit", afterMissionDisplay),
        compactStatus("Wait", "${vm.roundWaitSeconds}s")
    ))

    val homeBaseSpinner = Spinner(this).apply {
        background = rounded(Color.WHITE, dp(8), Stroke)
        minimumHeight = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        adapter = ArrayAdapter(this@settingsPanel, android.R.layout.simple_spinner_item, homeBaseOptions).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(homeBaseOptions.indexOfFirst { it.equals(vm.homeBase, ignoreCase = true) }.coerceAtLeast(0))
    }
    val afterMissionSpinner = Spinner(this).apply {
        background = rounded(Color.WHITE, dp(8), Stroke)
        minimumHeight = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        adapter = ArrayAdapter(this@settingsPanel, android.R.layout.simple_spinner_item, afterMissionLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(afterMissionKeys.indexOf(vm.afterMissionBehavior).coerceAtLeast(0))
    }
    missionBox.addView(twoPane(
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Home Base Location"))
            addView(homeBaseSpinner, full())
        },
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("After Mission Behavior"))
            addView(afterMissionSpinner, full())
        }
    ))
    missionBox.addView(actionButton("Return to Home Base Now", Primary) {
        returnToHomeBase()
    }.apply { layoutParams = full().apply { topMargin = dp(10) } })
    box.addView(missionBox)

    // ── Timing ────────────────────────────────────────────────────────────────
    box.addView(TextView(this).apply {
        text = "Care Timing"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
        setPadding(0, dp(14), 0, dp(4))
    })
    val roundWaitInput = input("Seconds (5–120)", vm.roundWaitSeconds.toString()).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    val roundPromptInput = input("Seconds (< wait)", vm.roundPromptSeconds.toString()).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    val secCooldownInput = input("Seconds (5–300)", vm.securityCooldownSeconds.toString()).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    val guestCooldownInput = input("Seconds (10–300)", vm.guestCooldownSeconds.toString()).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    val batteryLowInput = input("% (5–50)", vm.batteryLowPercent.toString()).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    box.addView(twoPane(
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Wait at each stop (s)"))
            addView(roundWaitInput, full())
            addView(label("Follow-up prompt at (s)"))
            addView(roundPromptInput, full())
            addView(label("Battery low → auto-charge (%)"))
            addView(batteryLowInput, full())
        },
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Security re-announce (s)"))
            addView(secCooldownInput, full())
            addView(label("Guest re-greet (s)"))
            addView(guestCooldownInput, full())
        }
    ))

    // ── Cloud ─────────────────────────────────────────────────────────────────
    box.addView(TextView(this).apply {
        text = "Care Cloud"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Text)
        setPadding(0, dp(14), 0, dp(4))
    })
    box.addView(buttonRow(
        compactStatus("Local Console", "http://${localIpAddress()}:8787"),
        compactStatus("Cloud Sync", "2 sec polling")
    ))
    val cloudUrlInput = input("https://...", cloudUrl())
    val cloudTokenInput = input("Robot token", cloudToken())
    box.addView(label("Cloud relay URL"))
    box.addView(cloudUrlInput, full())
    box.addView(label("Robot token"))
    box.addView(cloudTokenInput, full())

    box.addView(actionButton("Save All Settings", Primary) {
        hideKeyboard()
        vm.homeBase = homeBaseOptions.getOrElse(homeBaseSpinner.selectedItemPosition) { vm.homeBase }
        vm.afterMissionBehavior = afterMissionKeys.getOrElse(afterMissionSpinner.selectedItemPosition) { vm.afterMissionBehavior }
        saveHomeBaseSettings()
        val waitS = roundWaitInput.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: 22
        val promptS = roundPromptInput.text.toString().toIntOrNull()?.coerceIn(3, (waitS - 2).coerceAtLeast(3)) ?: 12
        vm.roundWaitSeconds = waitS
        vm.roundPromptSeconds = promptS
        vm.securityCooldownSeconds = secCooldownInput.text.toString().toIntOrNull()?.coerceIn(5, 300) ?: 30
        vm.guestCooldownSeconds = guestCooldownInput.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 45
        vm.batteryLowPercent = batteryLowInput.text.toString().toIntOrNull()?.coerceIn(5, 50) ?: 20
        saveTimingSettings()
        saveCloudSettings(cloudUrlInput.text.toString(), cloudTokenInput.text.toString())
    }.apply { layoutParams = full().apply { topMargin = dp(10) } })

    return box
}

// ── Common components ─────────────────────────────────────────────────────────

internal fun MainActivity.pageHero(title: String, subtitle: String): View =
    LinearLayout(this@pageHero).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(1), dp(4), dp(5))
        layoutParams = full()
        addView(backHeader(title))
        addView(android.widget.TextView(this@pageHero).apply {
            text = subtitle
            textSize = 8f
            setTextColor(Muted)
            setPadding(0, dp(3), 0, 0)
        })
    }

internal fun MainActivity.backHeader(title: String): View =
    LinearLayout(this@backHeader).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = full()
        if (currentPage != "home") {
            addView(TextView(this@backHeader).apply {
                text = "<"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Primary)
                background = rounded(Color.WHITE, dp(8), Stroke)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { currentPage = "home"; setContentView(buildUi()) }
            }, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(8) })
        }
        addView(TextView(this@backHeader).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PrimaryDark)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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

// ── Header ────────────────────────────────────────────────────────────────────

internal fun MainActivity.header(): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, dp(10), Color.rgb(225, 232, 236))
        setPadding(dp(8), dp(5), dp(8), dp(5))
        layoutParams = full().apply { bottomMargin = dp(4) }
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
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(12, 24, 56))
    })
    titleBox.addView(TextView(this).apply {
        text = "Healthcare - Elder Care"
        textSize = 8f
        setTextColor(Color.rgb(57, 70, 104))
    })
    top.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    sdkBadge = badge(if (robotReadySoon()) "Online" else "Preview", Good)
    queueBadge = badge("${lastBattery.replace("Battery ", "").ifBlank { "--" }}%", Color.rgb(55, 118, 168))
    recordingBadge = badge(if (isRecording) "Recording" else "Ready", if (isRecording) Danger else Good)
    assistBadge = badge(if (guestAssistEnabled) "Assist on" else "Assist off", if (guestAssistEnabled) Accent else Neutral)
    top.addView(sdkBadge)
    top.addView(queueBadge, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.marginStart = dp(4) })
    top.addView(TextView(this).apply {
        text = "STOP"
            textSize = 8f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(Danger, dp(8), 0)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isClickable = true
        isFocusable = true
        setOnClickListener { stopAll(); setContentView(buildUi()) }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
        it.marginStart = dp(8)
    })
    box.addView(top)
    statusView = TextView(this).apply {
        text = "Care Unit 01  |  ${vm.homeBase}  |  ${SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())}"
        textSize = 8f
        gravity = Gravity.START
        setTextColor(Color.rgb(57, 70, 104))
        setPadding(0, dp(2), 0, 0)
    }
    box.addView(statusView, full())
    return box
}

internal fun MainActivity.zoxLogoMark(): View = TextView(this).apply {
    text = "ZOX"
    textSize = 9f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(Color.rgb(18, 211, 234))
    background = rounded(Color.rgb(4, 20, 45), dp(16), Color.rgb(18, 198, 231))
    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
}
