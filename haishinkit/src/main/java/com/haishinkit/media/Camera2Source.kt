package com.haishinkit.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.net.NetStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


/**
 * A video source that captures a camera by the Camera2 API.
 */
class Camera2Source(
    private val context: Context,
    override var utilizable: Boolean = false
) : VideoSource, CameraDevice.StateCallback() {
    /**
     * The Listener interface is the primary method for handling events.
     */
    interface Listener {
        /**
         * Tells the receiver to error.
         */
        fun onError(camera: CameraDevice, error: Int)

        /**
         * Tells the receiver to create a capture request.
         */
        fun onCreateCaptureRequest(builder: CaptureRequest.Builder)
    }

    /**
     * Specifies the listener indicates the [Camera2Source.Listener] are currently being evaluated.
     */
    var listener: Listener? = null

    var device: CameraDevice? = null
        private set(value) {
            session = null
            field?.close()
            field = value
        }
    var characteristics: CameraCharacteristics? = null
        private set
    override var stream: NetStream? = null
    override val isRunning = AtomicBoolean(false)
    override var resolution = Size(0, 0)
    private var cameraId: String = DEFAULT_CAMERA_ID
    private var manager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var handler: Handler? = null
        get() {
            if (field == null) {
                val thread = HandlerThread(TAG)
                thread.start()
                field = Handler(thread.looper)
            }
            return field
        }
        set(value) {
            field?.looper?.quitSafely()
            field = value
        }
    private var session: CameraCaptureSession? = null
        private set(value) {
            field?.close()
            field = value
            field?.let {
                for (request in requests) {
                    try {
                        it.setRepeatingRequest(request.build(), null, handler)
                    } catch (e: IllegalStateException) {
                    }
                }
            }
        }
    private var requests = mutableListOf<CaptureRequest.Builder>()
    private var surfaces = mutableListOf<Surface>()
    private val imageOrientation: ImageOrientation
        get() {
            return when (characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)) {
                0 -> ImageOrientation.UP
                90 -> ImageOrientation.LEFT
                180 -> ImageOrientation.DOWN
                270 -> ImageOrientation.RIGHT
                else -> ImageOrientation.UP
            }
        }

    @SuppressLint("MissingPermission")
    fun open(position: Int? = null) {
        val cameraNames = manager.cameraIdList

        cameraNames.forEach {
            println("CAMERA $it")
            val path = context.getExternalFilesDir(null)
            val letDirectory = File(path, "LET")
            letDirectory.mkdirs()
            val file = File(letDirectory, "CamerasTotal.txt")
            file.appendText("CAMERA $it\n")
        }

        val cameras: MutableList<Map<String, Any>> = ArrayList()
        for (cameraName in cameraNames) {
            val cameraId: Int = try {
                cameraName.toInt(10)
            } catch (e: NumberFormatException) {
                -1
            }
            if (cameraId < 0) {
                continue
            }
            val details: HashMap<String, Any> = HashMap()
            val characteristics = manager.getCameraCharacteristics(cameraName)
            this.characteristics = characteristics
            details["name"] = cameraName
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
            details["sensorOrientation"] = sensorOrientation
            when (characteristics.get(CameraCharacteristics.LENS_FACING)!!) {
                CameraMetadata.LENS_FACING_FRONT -> {
                    details["lensFacing"] = "front"
                    this.cameraId = cameraName
                }
                CameraMetadata.LENS_FACING_BACK -> details["lensFacing"] = "back"
                CameraMetadata.LENS_FACING_EXTERNAL -> details["lensFacing"] = "external"
            }
            cameras.add(details)
        }

        val path = context.getExternalFilesDir(null)
        val letDirectory = File(path, "LET")
        letDirectory.mkdirs()
        val file = File(letDirectory, "RecordsCams.txt")
        file.appendText("---------- LOGS ----------\n\n\n")
        var indx = 0
        cameras.forEach {
            println("-----> CAMERA $indx <-----\n")
            file.appendText("-----> CAMERA $indx <-----\n")
            it.forEach {
                println("-----> CAM$indx: ${it.key}: ${it.value};")
                file.appendText("-----> CAM$indx: ${it.key}: ${it.value};\n")
            }
            println("////////////////////")
            file.appendText("////////////////////")
            indx++
        }


//        if (position == null) {
//            this.cameraId = DEFAULT_CAMERA_ID
//        } else {
//            this.cameraId = getCameraId(position) ?: DEFAULT_CAMERA_ID
//        }
//        characteristics = manager.getCameraCharacteristics(cameraId)

        device = null
        manager.openCamera("1", this, handler)
    }

    fun close() {
        device = null
    }

    /**
     * Switches an using camera front or back.
     */
    fun switchCamera() {
        val facing = getFacing()
        val expect = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        open(expect)
    }

    override fun setUp() {
        if (utilizable) return
        stream?.videoCodec?.setAssetManager(context.assets)
        super.setUp()
    }

    override fun tearDown() {
        if (!utilizable) return
        device = null
        super.tearDown()
    }

    override fun startRunning() {
        Log.d(TAG, "${this::startRunning.name}: $device")
        if (isRunning.get()) {
            return
        }
        isRunning.set(true)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, this::startRunning.name)
        }
    }

    override fun stopRunning() {
        if (!isRunning.get()) {
            return
        }
        session?.let {
            try {
                it.stopRepeating()
            } catch (exception: CameraAccessException) {
                Log.e(TAG, "", exception)
            }
            session = null
        }
        device = null
        isRunning.set(false)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, this::startRunning.name)
        }
    }

    override fun onOpened(camera: CameraDevice) {
        device = camera
        surfaces.clear()
        resolution = getCameraSize()
        stream?.drawable?.apply {
            imageOrientation = this@Camera2Source.imageOrientation
            createInputSurface(resolution.width, resolution.height, IMAGE_FORMAT) {
                createCaptureSession(it)
            }
        }
        stream?.videoCodec?.pixelTransform?.apply {
            imageOrientation = this@Camera2Source.imageOrientation
            createInputSurface(resolution.width, resolution.height, IMAGE_FORMAT) {
                createCaptureSession(it)
            }
        }
        setUp()
    }

    override fun onDisconnected(camera: CameraDevice) {
        device = null
    }

    override fun onError(camera: CameraDevice, error: Int) {
        listener?.onError(camera, error)
        device = null
    }

    private fun createCaptureSession(surface: Surface) {
        if (Thread.currentThread() != handler?.looper?.thread) {
            handler?.post {
                createCaptureSession(surface)
            }
            return
        }
        if (!surfaces.contains(surface)) {
            surfaces.add(surface)
        }
        val device = device ?: return
        if (surfaces.size < 2) {
            return
        }
        for (request in requests) {
            for (surface in surfaces) {
                request.removeTarget(surface)
            }
        }
        requests.clear()
        requests.add(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                listener?.onCreateCaptureRequest(this)
                surfaces.forEach {
                    addTarget(it)
                }
            }
        )
        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    this@Camera2Source.session = session
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    this@Camera2Source.session = null
                }
            },
            handler
        )
    }

    private fun getCameraId(facing: Int): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return null
    }

    private fun getFacing(): Int? {
        return characteristics?.get(CameraCharacteristics.LENS_FACING)
    }

    private fun getCameraSize(): Size {
        val scm = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val cameraSizes = scm?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(0, 0)
        return cameraSizes[0]
    }

    companion object {
        private const val IMAGE_FORMAT = ImageFormat.YUV_420_888

        private const val DEFAULT_CAMERA_ID = "0"
        private val TAG = Camera2Source::class.java.simpleName
    }
}
