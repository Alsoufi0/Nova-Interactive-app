package com.codex.novamessenger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean

class CameraFeedManager(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile var lastError: String = "Camera is idle."
        private set

    val isRunning: Boolean get() = running.get()
    val hasFrame: Boolean get() = latestJpeg != null

    fun bindPreview(view: TextureView) {
        textureView = view
        if (view.isAvailable && running.get()) restart()
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (running.get()) restart()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun start() {
        if (!hasCameraPermission()) {
            lastError = "Camera permission is not granted."
            return
        }
        if (running.compareAndSet(false, true)) {
            cameraThread = HandlerThread("NovaCameraFeed").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        openCamera()
    }

    fun stop() {
        running.set(false)
        runCatching { textureView?.surfaceTextureListener = null }
        textureView = null
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { previewSurface?.release() }
        captureSession = null
        cameraDevice = null
        imageReader = null
        previewSurface = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        lastError = "Camera stopped."
    }

    fun latestFrame(): ByteArray? = latestJpeg

    private fun restart() {
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        captureSession = null
        cameraDevice = null
        openCamera()
    }

    private fun openCamera() {
        if (!running.get() || !hasCameraPermission()) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = runCatching { manager.cameraIdList.firstOrNull() }.getOrNull()
        if (id == null) {
            lastError = "No camera was found on this Nova."
            return
        }
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    lastError = "Camera disconnected."
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    lastError = "Camera error $error."
                    camera.close()
                }
            }, cameraHandler)
        }.onFailure {
            lastError = "Could not open camera: ${it.message.orEmpty()}"
            Log.e(TAG, lastError, it)
        }
    }

    private fun createSession(camera: CameraDevice) {
        val handler = cameraHandler ?: return
        val reader = ImageReader.newInstance(FRAME.width, FRAME.height, ImageFormat.JPEG, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val buffer = it.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                latestJpeg = bytes
            }
        }, handler)

        val surfaces = mutableListOf(reader.surface)
        previewSurface = textureView?.takeIf { it.isAvailable }?.surfaceTexture?.let { texture ->
            texture.setDefaultBufferSize(FRAME.width, FRAME.height)
            Surface(texture)
        }
        val activePreviewSurface = previewSurface
        if (activePreviewSurface != null) surfaces += activePreviewSurface

        runCatching {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    runCatching {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            if (activePreviewSurface != null) addTarget(activePreviewSurface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        session.setRepeatingRequest(builder.build(), null, handler)
                        lastError = "Camera feed is running."
                    }.onFailure {
                        lastError = "Camera disconnected while starting feed."
                        Log.e(TAG, lastError, it)
                        runCatching { session.close() }
                        runCatching { camera.close() }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    lastError = "Camera session failed."
                }
            }, handler)
        }.onFailure {
            lastError = "Could not start camera feed: ${it.message.orEmpty()}"
            Log.e(TAG, lastError, it)
        }
    }

    private fun hasCameraPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "NovaCameraFeed"
        private val FRAME = Size(640, 480)
    }
}
