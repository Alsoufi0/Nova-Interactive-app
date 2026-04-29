package com.codex.novamessenger

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReflectionRobotAdapter(private val context: Context) : RobotAdapter {
    private val reqIds = AtomicInteger(4000)
    private val main = Handler(Looper.getMainLooper())
    private val robotApiClass = classOrNull("com.ainirobot.coreservice.client.RobotApi")
    private val personApiClass = classOrNull("com.ainirobot.coreservice.client.person.PersonApi")
    private val settingApiClass = firstClass(
        "com.ainirobot.coreservice.client.robotsetting.RobotSettingApi",
        "com.ainirobot.coreservice.client.RobotSettingApi"
    )

    override val isRobotSdkAvailable: Boolean = robotApiClass != null

    private val robotApi: Any? by lazy { robotApiClass?.callStatic("getInstance") }
    private val personApi: Any? by lazy { personApiClass?.callStatic("getInstance") }
    private val settingApi: Any? by lazy { settingApiClass?.callStatic("getInstance") }

    override fun connect(onStatus: (String) -> Unit) {
        val api = robotApi ?: return onStatus("Orion RobotAPI not found. Running in Android fallback mode.")
        val listenerClass = firstClass(
            "com.ainirobot.coreservice.client.ApiListener",
            "com.ainirobot.coreservice.client.listener.ApiListener"
        )
        if (listenerClass == null) {
            onStatus("RobotAPI found, but ApiListener class is missing.")
            return
        }
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, _ ->
            val status = when (method.name) {
                "handleApiConnected" -> "RobotAPI connected. Chassis and navigation control available."
                "handleApiDisconnected" -> "RobotAPI disconnected or robot system took over."
                "handleApiDisabled" -> "RobotAPI disabled for this app."
                else -> null
            }
            if (status != null) main.post { onStatus(status) }
            null
        }
        val result = invokeBest(api, "connectServer", context, listener)
        if (!result.ok) onStatus("RobotAPI detected, connectServer not called: ${result.message}")
    }

    override fun getBodyTargets(): List<BodyTarget> {
        val api = personApi ?: return emptyList()
        val raw = runCatching { invokeBest(api, "getAllBodyList").value as? List<*> }.getOrNull().orEmpty()
        return raw.mapNotNull { person ->
            if (person == null) return@mapNotNull null
            val distance = person.doubleValue("distance").takeIf { it > 0.0 } ?: return@mapNotNull null
            val bodyX = person.doubleValue("bodyX")
            val width = person.doubleValue("bodywidth")
            BodyTarget(
                id = person.intValue("id", -1),
                distanceMeters = distance,
                angleDegrees = person.doubleValue("angleInView"),
                centerX = bodyX + width / 2.0
            )
        }.sortedBy { it.distanceMeters }
    }

    override fun moveWithObstacles(linearSpeed: Double, angularSpeed: Double): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        val linear = linearSpeed.coerceIn(0.0, 0.45)
        val angular = angularSpeed.coerceIn(-0.8, 0.8)
        return invokeBest(api, "motionArcWithObstacles", nextReq(), linear, angular, commandListener(null)).toRobotResult()
    }

    override fun stopMove(): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(api, "stopMove", nextReq(), commandListener(null)).toRobotResult()
    }

    override fun startFollowTarget(targetId: Int, onStatus: (String) -> Unit): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(api, "startFocusFollow", nextReq(), targetId, 10_000, 4.0f, actionListener(onStatus)).toRobotResult()
    }

    override fun stopFollowTarget(): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(api, "stopFocusFollow", nextReq()).toRobotResult()
    }

    override fun startNavigation(destinationName: String, onStatus: (String) -> Unit): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(
            api,
            "startNavigation",
            nextReq(),
            destinationName,
            0.25,
            60_000,
            actionListener(onStatus)
        ).toRobotResult()
    }

    override fun stopNavigation(): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(api, "stopNavigation", nextReq()).toRobotResult()
    }

    override fun getMapPoints(): List<MapPoint> {
        val api = robotApi ?: return emptyList()
        var points = emptyList<MapPoint>()
        val listener = commandListener { event ->
            if (event.name == "onResult" && event.args.size >= 2) {
                val json = event.args[1]?.toString().orEmpty()
                points = runCatching {
                    val array = JSONArray(json)
                    List(array.length()) { i ->
                        val item = array.getJSONObject(i)
                        MapPoint(
                            name = item.optString("name"),
                            status = item.optInt("status", 0),
                            x = item.optDouble("x"),
                            y = item.optDouble("y")
                        )
                    }.filter { it.name.isNotBlank() }
                }.getOrDefault(emptyList())
            }
        }
        invokeBest(api, "getPlaceList", nextReq(), listener)
        return points
    }

    override fun getRobotPose(): RobotPose? {
        val api = robotApi ?: return null
        var pose: RobotPose? = null
        val listener = commandListener { event ->
            if (event.name == "onResult" && event.args.size >= 2) {
                pose = parsePose(event.args[1]?.toString())
            }
        }
        invokeBest(api, "getPosition", nextReq(), listener)
        return pose
    }

    override fun saveCurrentLocation(name: String): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(api, "setLocation", nextReq(), name, commandListener(null)).toRobotResult()
    }

    override fun speak(text: String): RobotResult {
        val agentCoreClass = firstClass("com.ainirobot.agent.AgentCore", "com.orion.agent.AgentCore")
        val agentCore = agentCoreClass?.kotlinObjectInstance()
            ?: agentCoreClass?.callStatic("getInstance")
        if (agentCore != null) {
            val ttsResult = invokeBest(agentCore, "tts", text, 180_000L, null)
                .takeIf { it.ok }
                ?: invokeBest(agentCore, "tts", text)
            if (ttsResult.ok) return ttsResult.toRobotResult()
        }
        if (agentCoreClass != null) {
            val staticResult = invokeStaticBest(agentCoreClass, "tts", text, 180_000L, null)
                .takeIf { it.ok }
                ?: invokeStaticBest(agentCoreClass, "tts", text)
            if (staticResult.ok) return staticResult.toRobotResult()
        }
        return RobotResult(false, "AgentOS TTS unavailable")
    }

    override fun batteryInfo(): String {
        val api = settingApi ?: return "Battery unavailable until RobotSettingApi is present."
        val value = invokeBest(api, "getRobotString", "robot_settings_battery_info")
        return value.value?.toString()?.takeIf { it.isNotBlank() } ?: value.message
    }

    override fun goCharge(): RobotResult {
        val api = robotApi ?: return RobotResult(false, "RobotAPI unavailable")
        return invokeBest(api, "startNaviToAutoChargeAction", nextReq(), 120_000, actionListener(null)).toRobotResult()
    }

    private fun nextReq(): Int = reqIds.incrementAndGet()

    private fun commandListener(onEvent: ((CallbackEvent) -> Unit)?): Any? = proxy(
        "com.ainirobot.coreservice.client.listener.CommandListener",
        onEvent
    )

    private fun actionListener(onStatus: ((String) -> Unit)?): Any? = proxy(
        "com.ainirobot.coreservice.client.listener.ActionListener"
    ) { event ->
        val message = "${event.name}: ${event.args.joinToString(" ")}"
        if (onStatus != null && (event.name.startsWith("on") || event.name.contains("Status"))) {
            main.post { onStatus(message) }
        }
    }

    private fun proxy(className: String, onEvent: ((CallbackEvent) -> Unit)?): Any? {
        val listenerClass = classOrNull(className) ?: return null
        return Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
            onEvent?.invoke(CallbackEvent(method.name, args?.toList().orEmpty()))
            null
        }
    }

    private fun classOrNull(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    private fun firstClass(vararg names: String): Class<*>? = names.firstNotNullOfOrNull { classOrNull(it) }

    private fun Class<*>.callStatic(method: String): Any? =
        runCatching { getMethod(method).invoke(null) }.getOrNull()

    private fun Class<*>.kotlinObjectInstance(): Any? =
        runCatching { getField("INSTANCE").get(null) }.getOrNull()

    private fun invokeBest(target: Any, name: String, vararg args: Any?): InvokeResult {
        val methods = target.javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
        for (method in methods) {
            val converted = convertArgs(method, args) ?: continue
            val value = runCatching { method.invoke(target, *converted) }
            if (value.isSuccess) return InvokeResult(true, "Called $name", value.getOrNull())
        }
        return InvokeResult(false, "No compatible $name(${args.size}) method found", null)
    }

    private fun invokeStaticBest(target: Class<*>, name: String, vararg args: Any?): InvokeResult {
        val methods = target.methods.filter { it.name == name && it.parameterTypes.size == args.size }
        for (method in methods) {
            val converted = convertArgs(method, args) ?: continue
            val value = runCatching { method.invoke(null, *converted) }
            if (value.isSuccess) return InvokeResult(true, "Called $name", value.getOrNull())
        }
        return InvokeResult(false, "No compatible static $name(${args.size}) method found", null)
    }

    private fun convertArgs(method: Method, args: Array<out Any?>): Array<Any?>? {
        val types = method.parameterTypes
        return Array(args.size) { index ->
            val arg = args[index]
            val type = types[index]
            when {
                arg == null -> if (type.isPrimitive) return null else null
                type.isInstance(arg) -> arg
                type == Integer.TYPE || type == Integer::class.java -> (arg as? Number)?.toInt() ?: return null
                type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> (arg as? Number)?.toLong() ?: return null
                type == java.lang.Double.TYPE || type == java.lang.Double::class.java -> (arg as? Number)?.toDouble() ?: return null
                type == java.lang.Float.TYPE || type == java.lang.Float::class.java -> (arg as? Number)?.toFloat() ?: return null
                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> arg as? Boolean ?: return null
                type == String::class.java -> arg.toString()
                else -> return null
            }
        }
    }

    private fun Any.doubleValue(name: String, default: Double = 0.0): Double {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val value = runCatching { javaClass.getMethod(getter).invoke(this) }.getOrNull()
            ?: runCatching { javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) }.getOrNull()
        return (value as? Number)?.toDouble() ?: default
    }

    private fun Any.intValue(name: String, default: Int = 0): Int {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val value = runCatching { javaClass.getMethod(getter).invoke(this) }.getOrNull()
            ?: runCatching { javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) }.getOrNull()
        return (value as? Number)?.toInt() ?: default
    }

    private fun parsePose(raw: String?): RobotPose? = runCatching {
        val obj = JSONObject(raw?.trim().orEmpty())
        RobotPose(
            x = obj.optDouble("x", obj.optDouble("px", obj.optDouble("position_x", obj.optDouble("pos_x")))),
            y = obj.optDouble("y", obj.optDouble("py", obj.optDouble("position_y", obj.optDouble("pos_y")))),
            theta = obj.optDouble("theta", obj.optDouble("z", obj.optDouble("angle", 0.0)))
        )
    }.getOrNull()

    private fun InvokeResult.toRobotResult(): RobotResult = RobotResult(ok, message)

    private data class InvokeResult(val ok: Boolean, val message: String, val value: Any?)
    private data class CallbackEvent(val name: String, val args: List<Any?>)
}

class ShapeFollowController(
    private val robot: RobotAdapter,
    private val onStatus: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var sdkFollowActive = false
    private var mode = FollowMode.OPEN
    private var filteredDistance = 0.0
    private var filteredAngle = 0.0
    private var lastLinear = 0.0
    private var lastAngular = 0.0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (sdkFollowActive) {
                handler.postDelayed(this, 1_000)
                return
            }
            val target = robot.getBodyTargets().minByOrNull { targetScore(it) }
            if (target == null) {
                robot.stopMove()
                lastLinear = 0.0
                lastAngular = 0.0
                onStatus("No body shape in view. Waiting.")
            } else {
                val rawAngle = if (target.angleDegrees != 0.0) target.angleDegrees else (target.centerX - 320.0) / 320.0 * 35.0
                filteredDistance = smooth(filteredDistance, target.distanceMeters, mode.distanceAlpha)
                filteredAngle = smooth(filteredAngle, rawAngle, mode.angleAlpha)
                val distanceError = filteredDistance - mode.targetDistance
                val centered = abs(filteredAngle) < mode.centerAngle
                val desiredLinear = when {
                    filteredDistance < mode.minDistance -> 0.0
                    !centered && mode == FollowMode.DOOR -> 0.0
                    distanceError > mode.deadband -> min(mode.maxLinear, max(mode.minLinear, distanceError * mode.linearGain))
                    else -> 0.0
                }
                val desiredAngular = (-filteredAngle / mode.turnDivisor).coerceIn(-mode.maxAngular, mode.maxAngular)
                val linear = ramp(lastLinear, desiredLinear, mode.linearStep).coerceAtLeast(0.0)
                val angular = ramp(lastAngular, desiredAngular, mode.angularStep)
                lastLinear = linear
                lastAngular = angular
                if (linear == 0.0 && abs(angular) < 0.10) robot.stopMove()
                else robot.moveWithObstacles(linear, angular)
                onStatus("${mode.label} follow ${"%.1f".format(filteredDistance)}m, speed ${"%.2f".format(linear)}, turn ${"%.2f".format(angular)}")
            }
            handler.postDelayed(this, mode.tickMs)
        }
    }

    fun start() {
        start(FollowMode.OPEN)
    }

    fun startDoorMode() {
        start(FollowMode.DOOR)
    }

    private fun start(nextMode: FollowMode) {
        mode = nextMode
        if (running) {
            onStatus("${mode.label} follow profile selected.")
            return
        }
        running = true
        val target = robot.getBodyTargets().minByOrNull { targetScore(it) }
        if (target != null) {
            sdkFollowActive = false
            filteredDistance = target.distanceMeters
            filteredAngle = target.angleDegrees
            lastLinear = 0.0
            lastAngular = 0.0
            onStatus("${mode.label} follow started at ${"%.1f".format(target.distanceMeters)}m. Stay centered in front.")
        } else {
            onStatus("Looking for a person shape before following.")
        }
        handler.post(tick)
    }

    fun isRunning(): Boolean = running

    fun stop() {
        running = false
        sdkFollowActive = false
        handler.removeCallbacks(tick)
        lastLinear = 0.0
        lastAngular = 0.0
        robot.stopFollowTarget()
        robot.stopMove()
        onStatus("Shape following stopped.")
    }

    private fun targetScore(target: BodyTarget): Double =
        abs(target.angleDegrees) * mode.angleWeight + target.distanceMeters * 0.08

    private fun smooth(previous: Double, next: Double, alpha: Double): Double =
        if (previous == 0.0) next else previous + (next - previous) * alpha

    private fun ramp(previous: Double, desired: Double, step: Double): Double =
        previous + (desired - previous).coerceIn(-step, step)

    private enum class FollowMode(
        val label: String,
        val targetDistance: Double,
        val minDistance: Double,
        val deadband: Double,
        val minLinear: Double,
        val maxLinear: Double,
        val linearGain: Double,
        val turnDivisor: Double,
        val maxAngular: Double,
        val centerAngle: Double,
        val angleWeight: Double,
        val tickMs: Long,
        val distanceAlpha: Double,
        val angleAlpha: Double,
        val linearStep: Double,
        val angularStep: Double
    ) {
        OPEN("Open", 1.35, 0.9, 0.08, 0.16, 0.48, 0.36, 36.0, 0.68, 26.0, 0.02, 280, 0.50, 0.55, 0.10, 0.18),
        DOOR("Door", 1.05, 0.82, 0.07, 0.08, 0.22, 0.20, 50.0, 0.32, 14.0, 0.06, 280, 0.40, 0.46, 0.055, 0.10)
    }
}
