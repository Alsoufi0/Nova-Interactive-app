package com.codex.novamessenger

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.ainirobot.agent.AgentCore
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.person.PersonApi
import com.ainirobot.coreservice.client.robotsetting.RobotSettingApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DirectNovaRobotAdapter(private val context: Context) : RobotAdapter {
    private val reqIds = AtomicInteger(7000)
    private val connected = AtomicBoolean(false)
    @Volatile private var cachedPoints: List<MapPoint> = emptyList()

    override val isRobotSdkAvailable: Boolean
        get() = connected.get()

    override fun connect(onStatus: (String) -> Unit) {
        Log.i(TAG, "connect: calling RobotApi.connectServer")
        RobotApi.getInstance().connectServer(context, object : ApiListener {
            override fun handleApiDisabled() {
                connected.set(false)
                Log.w(TAG, "RobotAPI disabled")
                onStatus("RobotAPI disabled. Check Nova app permissions/default app setting.")
            }

            override fun handleApiConnected() {
                connected.set(true)
                Log.i(TAG, "RobotAPI connected")
                onStatus("RobotAPI connected. Nova controls are live.")
                refreshMapPoints()
            }

            override fun handleApiDisconnected() {
                connected.set(false)
                Log.w(TAG, "RobotAPI disconnected")
                onStatus("RobotAPI disconnected. Reopen app or restart RobotAPI service.")
            }
        })
    }

    override fun getBodyTargets(): List<BodyTarget> {
        return runCatching {
            val bodyList = PersonApi.getInstance().getAllBodyList()
            bodyList?.mapNotNull { person ->
                val distance = person.distance.toDouble()
                if (distance <= 0.0) return@mapNotNull null
                BodyTarget(
                    id = person.id,
                    distanceMeters = distance,
                    angleDegrees = person.angleInView.toDouble(),
                    centerX = person.bodyX + person.bodyWidth / 2.0
                )
            }?.sortedBy { it.distanceMeters }.orEmpty()
        }.onFailure {
            Log.e(TAG, "getBodyTargets failed", it)
        }.getOrDefault(emptyList())
    }

    override fun moveWithObstacles(linearSpeed: Double, angularSpeed: Double): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        val linear = linearSpeed.coerceIn(0.0, 0.46)
        val angular = angularSpeed.coerceIn(-0.78, 0.78)
        Log.d(TAG, "motionArcWithObstacles linear=$linear angular=$angular")
        RobotApi.getInstance().motionArcWithObstacles(nextReq(), linear.toFloat(), angular.toFloat(), commandLogger("motionArc"))
        return RobotResult(true, "Follow movement command sent")
    }

    override fun stopMove(): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        Log.i(TAG, "stopMove")
        RobotApi.getInstance().stopMove(nextReq(), commandLogger("stopMove"))
        return RobotResult(true, "Stop movement command sent")
    }

    override fun startFollowTarget(targetId: Int, onStatus: (String) -> Unit): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        if (targetId < 0) return RobotResult(false, "No follow target id")
        Log.i(TAG, "startFocusFollow targetId=$targetId")
        RobotApi.getInstance().startFocusFollow(
            nextReq(),
            targetId,
            10_000,
            4.0f,
            object : ActionListener() {
                override fun onResult(status: Int, responseString: String?) {
                    val msg = "Follow result $status ${responseString.orEmpty()}"
                    Log.i(TAG, msg)
                    onStatus(msg)
                }

                override fun onError(errorCode: Int, errorString: String?) {
                    val msg = "Follow error $errorCode ${errorString.orEmpty()}"
                    Log.e(TAG, msg)
                    onStatus(msg)
                }

                override fun onStatusUpdate(status: Int, data: String?, extraData: String?) {
                    val msg = "Follow status $status ${data.orEmpty()}"
                    Log.d(TAG, "$msg extra=${extraData.orEmpty()}")
                    onStatus(msg)
                }
            }
        )
        return RobotResult(true, "Nova focus-follow started")
    }

    override fun stopFollowTarget(): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        Log.i(TAG, "stopFocusFollow")
        RobotApi.getInstance().stopFocusFollow(nextReq())
        return RobotResult(true, "Focus-follow stop command sent")
    }

    override fun startNavigation(destinationName: String, onStatus: (String) -> Unit): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        Log.i(TAG, "startNavigation destination=$destinationName")
        RobotApi.getInstance().startNavigation(
            nextReq(),
            destinationName,
            0.5,
            300_000L,
            object : ActionListener() {
                override fun onResult(status: Int, responseString: String?) {
                    val msg = "Navigation result $status ${responseString.orEmpty()}"
                    Log.i(TAG, msg)
                    onStatus(msg)
                }

                override fun onError(errorCode: Int, errorString: String?) {
                    val msg = "Navigation error $errorCode ${errorString.orEmpty()}"
                    Log.e(TAG, msg)
                    onStatus(msg)
                }

                override fun onStatusUpdate(status: Int, data: String?, extraData: String?) {
                    val msg = "Navigation status $status ${data.orEmpty()}"
                    Log.d(TAG, "$msg extra=${extraData.orEmpty()}")
                    onStatus(msg)
                }
            }
        )
        return RobotResult(true, "Navigation command sent to $destinationName")
    }

    override fun stopNavigation(): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        Log.i(TAG, "stopNavigation")
        RobotApi.getInstance().stopNavigation(nextReq())
        return RobotResult(true, "Stop navigation command sent")
    }

    override fun getMapPoints(): List<MapPoint> {
        refreshMapPoints()
        if (cachedPoints.isEmpty()) {
            Thread.sleep(900)
            refreshMapPoints()
        }
        return cachedPoints
    }

    override fun getRobotPose(): RobotPose? {
        if (!connected.get()) return null
        val latch = CountDownLatch(1)
        var pose: RobotPose? = null
        RobotApi.getInstance().getPosition(nextReq(), object : CommandListener() {
            override fun onResult(result: Int, message: String?) {
                Log.i(TAG, "getPosition result=$result message=${message.orEmpty()}")
                pose = parsePose(message)
                latch.countDown()
            }

            override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
                Log.e(TAG, "getPosition error=$errorCode ${errorString.orEmpty()} ${extraData.orEmpty()}")
                latch.countDown()
            }
        })
        latch.await(1_500, TimeUnit.MILLISECONDS)
        return pose
    }

    override fun saveCurrentLocation(name: String): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        Log.i(TAG, "setLocation name=$name")
        RobotApi.getInstance().setLocation(nextReq(), name, commandLogger("setLocation"))
        return RobotResult(true, "Save point command sent for $name")
    }

    override fun speak(text: String): RobotResult {
        Log.i(TAG, "AgentCore.tts text=$text")
        return runCatching {
            AgentCore.tts(text, timeoutMillis = 60_000)
            RobotResult(true, "TTS command sent")
        }.onFailure {
            Log.e(TAG, "AgentCore.tts failed", it)
        }.getOrElse { RobotResult(false, "AgentOS TTS failed: ${it.message}") }
    }

    override fun batteryInfo(): String {
        val robotSetting = runCatching {
            RobotSettingApi.getInstance().getRobotString("robot_setting_battery_info")
                .takeIf { it.isNotBlank() }
                ?: RobotSettingApi.getInstance().getRobotString("robot_settings_battery_info")
        }.onFailure {
            Log.e(TAG, "batteryInfo failed", it)
        }.getOrNull()
        if (!robotSetting.isNullOrBlank()) return robotSetting

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (percent >= 0) "Battery $percent%${if (charging) " charging" else ""}" else "Battery unavailable"
    }

    override fun goCharge(): RobotResult {
        if (!connected.get()) return RobotResult(false, "RobotAPI not connected")
        Log.i(TAG, "startNaviToAutoChargeAction")
        RobotApi.getInstance().startNaviToAutoChargeAction(nextReq(), 300_000L, actionLogger("autoCharge"))
        return RobotResult(true, "Auto-charge command sent")
    }

    private fun refreshMapPoints() {
        if (!connected.get()) return
        val latch = CountDownLatch(1)
        RobotApi.getInstance().getPlaceList(nextReq(), object : CommandListener() {
            override fun onResult(result: Int, message: String?) {
                Log.i(TAG, "getPlaceList result=$result message=${message.orEmpty()}")
                val parsed = parsePoints(message)
                if (parsed.isNotEmpty()) cachedPoints = parsed
                latch.countDown()
            }

            override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
                Log.e(TAG, "getPlaceList error=$errorCode ${errorString.orEmpty()} ${extraData.orEmpty()}")
                latch.countDown()
            }
        })
        latch.await(4_000, TimeUnit.MILLISECONDS)
    }

    private fun parsePoints(raw: String?): List<MapPoint> = runCatching {
        val trimmed = raw?.trim().orEmpty()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("data")
                    ?: obj.optJSONArray("places")
                    ?: obj.optJSONArray("placeList")
                    ?: obj.optJSONArray("list")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            MapPoint(
                name = item.optString("name"),
                status = item.optInt("status", 0),
                x = item.optDouble("x"),
                y = item.optDouble("y")
            )
        }.filter { it.name.isNotBlank() }
    }.onFailure {
        Log.e(TAG, "parsePoints failed raw=$raw", it)
    }.getOrDefault(emptyList())

    private fun parsePose(raw: String?): RobotPose? = runCatching {
        val obj = JSONObject(raw?.trim().orEmpty())
        RobotPose(
            x = obj.optDouble("x", obj.optDouble("px", obj.optDouble("position_x", obj.optDouble("pos_x")))),
            y = obj.optDouble("y", obj.optDouble("py", obj.optDouble("position_y", obj.optDouble("pos_y")))),
            theta = obj.optDouble("theta", obj.optDouble("z", obj.optDouble("angle", 0.0)))
        )
    }.onFailure {
        Log.e(TAG, "parsePose failed raw=$raw", it)
    }.getOrNull()

    private fun commandLogger(name: String) = object : CommandListener() {
        override fun onResult(result: Int, message: String?) {
            Log.i(TAG, "$name result=$result message=${message.orEmpty()}")
        }

        override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
            Log.e(TAG, "$name error=$errorCode ${errorString.orEmpty()} ${extraData.orEmpty()}")
        }
    }

    private fun actionLogger(name: String) = object : ActionListener() {
        override fun onResult(status: Int, responseString: String?) {
            Log.i(TAG, "$name result=$status ${responseString.orEmpty()}")
        }

        override fun onError(errorCode: Int, errorString: String?) {
            Log.e(TAG, "$name error=$errorCode ${errorString.orEmpty()}")
        }

        override fun onStatusUpdate(status: Int, data: String?, extraData: String?) {
            Log.d(TAG, "$name status=$status ${data.orEmpty()} ${extraData.orEmpty()}")
        }
    }

    private fun nextReq(): Int = reqIds.incrementAndGet()

    companion object {
        private const val TAG = "NovaRobot"
    }
}
