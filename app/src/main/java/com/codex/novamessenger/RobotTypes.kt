package com.codex.novamessenger

import java.util.concurrent.atomic.AtomicReference

enum class VisionMode { NONE, CAMERA_PREVIEW, DETECTION_WATCH }

class VisionManager {
    private val current = AtomicReference(VisionMode.NONE)

    val activeMode: VisionMode get() = current.get()

    fun tryAcquire(mode: VisionMode): Boolean =
        current.compareAndSet(VisionMode.NONE, mode) || current.get() == mode

    fun release(mode: VisionMode) {
        current.compareAndSet(mode, VisionMode.NONE)
    }

    fun forceAcquire(mode: VisionMode): VisionMode {
        val previous = current.getAndSet(mode)
        return previous
    }
}

data class MapPoint(
    val name: String,
    val status: Int = 0,
    val x: Double? = null,
    val y: Double? = null
)

data class RobotPose(
    val x: Double,
    val y: Double,
    val theta: Double = 0.0
)

data class BodyTarget(
    val id: Int,
    val distanceMeters: Double,
    val angleDegrees: Double,
    val centerX: Double
)

data class RobotResult(
    val ok: Boolean,
    val message: String
)

data class NovaMessage(
    val id: Long,
    val destination: String,
    val prompt: String,
    val text: String,
    val audioPath: String?,
    val createdAt: Long,
    val deliveredAt: Long? = null
)

interface RobotAdapter {
    val isRobotSdkAvailable: Boolean
    fun connect(onStatus: (String) -> Unit)
    fun getBodyTargets(): List<BodyTarget>
    fun moveWithObstacles(linearSpeed: Double, angularSpeed: Double): RobotResult
    fun stopMove(): RobotResult
    fun startFollowTarget(targetId: Int, onStatus: (String) -> Unit): RobotResult
    fun stopFollowTarget(): RobotResult
    fun startNavigation(destinationName: String, onStatus: (String) -> Unit): RobotResult
    fun stopNavigation(): RobotResult
    fun getMapPoints(): List<MapPoint>
    fun getRobotPose(): RobotPose?
    fun saveCurrentLocation(name: String): RobotResult
    fun speak(text: String): RobotResult
    fun batteryInfo(): String
    fun goCharge(): RobotResult
}
