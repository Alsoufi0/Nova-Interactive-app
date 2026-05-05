package com.codex.novamessenger

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.ainirobot.agent.AppAgent
import com.ainirobot.agent.action.Action
import com.ainirobot.agent.action.ActionExecutor
import com.ainirobot.agent.action.Actions
import com.ainirobot.agent.base.Parameter
import com.ainirobot.agent.base.ParameterType

class NovaConciergeApplication : Application() {
    private var appAgent: AppAgent? = null

    override fun onCreate() {
        super.onCreate()
        appAgent = object : AppAgent(this) {
            override fun onCreate() {
                Log.i(TAG, "AgentOS AppAgent created")
                setPersona(
                    "You are Nova Concierge, a calm professional healthcare and elder-care robot assistant. Your role is to greet visitors, help residents, notify staff, guide people to saved places, and keep the care team informed through the cloud dashboard."
                )
                setStyle(
                    "Brief, warm, polite, clear, and safety-aware. Confirm what you are doing, avoid long explanations, and never start robot movement unless the person clearly asks for guide or follow."
                )
                setObjective(
                    "Understand natural requests in many wordings. If someone asks what you do, explain your care concierge abilities. If someone asks to send, record, leave, tell, notify, or deliver a message, use the message action. If someone asks for help, nurse, urgent, emergency, fall, pain, or staff, use staff alert. If someone asks to take, guide, show, navigate, or help them get to a public saved place, use visitor guide. If someone asks to visit, find, see, or be guided to a patient or resident by name, pass the phrase to the app and do not reveal room information. If someone asks for staff care check-in, rounds, resident check-in, medication, medicine, or reminder, use care workflow. If someone asks to follow or come with them, use follow. If someone asks about camera, scan, detection, watch, security, or surveillance, use camera detection."
                )
                registerAction(Actions.SAY)
                registerAction(sendMessageAction())
                registerAction(capabilitiesAction())
                registerAction(staffAlertAction())
                registerAction(visitorGuideAction())
                registerAction(careWorkflowAction())
                registerAction(stopAction())
                registerAction(followAction())
                registerAction(cameraDetectionAction())
            }

            override fun onExecuteAction(action: Action, params: Bundle?): Boolean {
                Log.i(TAG, "Unhandled AgentOS action=${action.name}")
                return false
            }
        }
    }

    private fun sendMessageAction(): Action =
        Action(
            name = SEND_MESSAGE_ACTION,
            displayName = "Send concierge message",
            desc = "Use for every natural request to send, deliver, record, leave, pass along, or take a message. Trigger on phrases such as: can you send a message, I want to send a message, please tell reception, send help, record a message for my host, deliver this to the office, or let the front desk know. If the guest did not provide the content yet, call this action with an empty message so Nova can ask and record it.",
            parameters = listOf(
                Parameter(
                    "message",
                    ParameterType.STRING,
                    "The exact message content the guest wants delivered. If the user only asked to send/record a message but has not said the content yet, leave this empty.",
                    false
                ),
                Parameter(
                    "destination",
                    ParameterType.STRING,
                    "The named map destination point or recipient area. Extract from phrases like to reception, for my host, to the front desk, to the lobby, or leave empty if unknown.",
                    false
                ),
                Parameter(
                    "sender",
                    ParameterType.STRING,
                    "The guest name if known; otherwise leave empty.",
                    false
                )
            ),
            executor = object : ActionExecutor {
                override fun onExecute(action: Action, params: Bundle?): Boolean {
                    Log.i(TAG, "AgentOS send message action params=$params")
                    val launch = Intent(this@NovaConciergeApplication, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(MainActivity.EXTRA_COMMAND, MainActivity.COMMAND_SEND_MESSAGE)
                        putExtra(MainActivity.EXTRA_MESSAGE, params?.getString("message").orEmpty())
                        putExtra(MainActivity.EXTRA_DESTINATION, params?.getString("destination").orEmpty())
                        putExtra(MainActivity.EXTRA_SENDER, params?.getString("sender").orEmpty())
                    }
                    startActivity(launch)
                    action.notify(isTriggerFollowUp = false)
                    return true
                }
            }
        )

    private fun capabilitiesAction(): Action =
        phraseAction(
            name = CAPABILITIES_ACTION,
            displayName = "Explain Nova abilities",
            desc = "Use when the user asks what Nova can do, who Nova is, how Nova can help, what functions are available, or asks for options.",
            parameters = emptyList()
        ) { "what can you do" }

    private fun staffAlertAction(): Action =
        phraseAction(
            name = STAFF_ALERT_ACTION,
            displayName = "Create staff alert",
            desc = "Use when a visitor, patient, or resident asks for help, nurse, caregiver, staff, urgent support, emergency assistance, water, medication help, medicine help, pain, fall, or says alert. This must notify the care dashboard.",
            parameters = listOf(
                Parameter("message", ParameterType.STRING, "The spoken reason for the alert.", false),
                Parameter("room", ParameterType.STRING, "Room or location mentioned by the user, if any.", false),
                Parameter("priority", ParameterType.STRING, "normal or urgent.", false)
            )
        ) { params ->
            val message = params?.getString("message").orEmpty().ifBlank { "staff assistance requested" }
            val room = params?.getString("room").orEmpty()
            val priority = params?.getString("priority").orEmpty()
            // Only include priority keyword if it signals urgency — "normal" adds nothing and confuses intent detection
            val priorityWord = if (priority == "urgent") "urgent" else ""
            listOf("alert staff", priorityWord, room, message).filter { it.isNotBlank() }.joinToString(" ")
        }

    private fun visitorGuideAction(): Action =
        phraseAction(
            name = VISITOR_GUIDE_ACTION,
            displayName = "Guide visitor",
            desc = "Use when someone asks Nova to guide, take, show, navigate, escort, or help them get to a public destination, saved map point, reception, office, lobby, entrance, or conference room. Do not use this to reveal or navigate to a patient/resident room for a visitor.",
            parameters = listOf(
                Parameter("destination", ParameterType.STRING, "Destination, room, or saved map point requested by the user.", false)
            )
        ) { params -> "guide me to ${params?.getString("destination").orEmpty()}" }

    private fun careWorkflowAction(): Action =
        phraseAction(
            name = CARE_WORKFLOW_ACTION,
            displayName = "Start care workflow",
            desc = "Use for resident check-ins, care rounds, medication reminders, medicine reminders, appointment reminders, or checking on a named resident or room.",
            parameters = listOf(
                Parameter("request", ParameterType.STRING, "The full care request, including resident name, room, reminder, or round.", false)
            )
        ) { params -> params?.getString("request").orEmpty().ifBlank { "start resident check in" } }

    private fun followAction(): Action =
        phraseAction(
            name = FOLLOW_ACTION,
            displayName = "Follow person",
            desc = "Use only when the user explicitly asks Nova to follow them, come with them, walk with them, or stay with them.",
            parameters = emptyList()
        ) { "follow me" }

    private fun stopAction(): Action =
        phraseAction(
            name = STOP_ACTION,
            displayName = "Stop robot movement",
            desc = "Use immediately when the user says stop, stop following me, stop moving, cancel, pause, wait, emergency stop, or do not follow. This must stop follow, navigation, and base movement.",
            parameters = emptyList()
        ) { "stop following me" }

    private fun cameraDetectionAction(): Action =
        phraseAction(
            name = CAMERA_ACTION,
            displayName = "Open camera detection",
            desc = "Use when the user asks for camera, detection, scan, security watch, surveillance, watch hallway, or open camera.",
            parameters = listOf(
                Parameter("mode", ParameterType.STRING, "camera, detection, scan, or security.", false)
            )
        ) { params -> params?.getString("mode").orEmpty().ifBlank { "open camera detection" } }

    private fun phraseAction(
        name: String,
        displayName: String,
        desc: String,
        parameters: List<Parameter>,
        phraseBuilder: (Bundle?) -> String
    ): Action =
        Action(
            name = name,
            displayName = displayName,
            desc = desc,
            parameters = parameters,
            executor = object : ActionExecutor {
                override fun onExecute(action: Action, params: Bundle?): Boolean {
                    val phrase = phraseBuilder(params)
                    Log.i(TAG, "AgentOS phrase action=${action.name} phrase=$phrase params=$params")
                    val launch = Intent(this@NovaConciergeApplication, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(MainActivity.EXTRA_COMMAND, MainActivity.COMMAND_VOICE_PHRASE)
                        putExtra(MainActivity.EXTRA_PHRASE, phrase)
                    }
                    startActivity(launch)
                    action.notify(isTriggerFollowUp = false)
                    return true
                }
            }
        )

    companion object {
        private const val TAG = "NovaAgent"
        private const val SEND_MESSAGE_ACTION = "com.codex.novamessenger.SEND_MESSAGE"
        private const val CAPABILITIES_ACTION = "com.codex.novamessenger.CAPABILITIES"
        private const val STAFF_ALERT_ACTION = "com.codex.novamessenger.STAFF_ALERT"
        private const val VISITOR_GUIDE_ACTION = "com.codex.novamessenger.VISITOR_GUIDE"
        private const val CARE_WORKFLOW_ACTION = "com.codex.novamessenger.CARE_WORKFLOW"
        private const val STOP_ACTION = "com.codex.novamessenger.STOP"
        private const val FOLLOW_ACTION = "com.codex.novamessenger.FOLLOW"
        private const val CAMERA_ACTION = "com.codex.novamessenger.CAMERA"
    }
}
