package cse281.automobile


import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import cse281.env.ImageUtils

abstract class CameraActivity : Activity(), OnImageAvailableListener, CameraConnectionFragment.ConnectionCallback, FragmentArgumentProvider {
    private val TAG = "cse281.automobile.CameraActivity"

    private val PERMISSIONS_REQUEST = 1

    private val PERMISSION_CAMERA = Manifest.permission.CAMERA
    private val PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE

    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private var useCamera2API: Boolean = false
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride: Int = 0


    protected var previewWidth = 0
    protected var previewHeight = 0

    protected var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null


    private lateinit var fragment: CameraConnectionFragment

    protected val luminance: ByteArray
        get() = yuvBytes[0]!!

    protected val screenOrientation: Int
        get() {
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_270 -> return 270
                Surface.ROTATION_180 -> return 180
                Surface.ROTATION_90 -> return 90
                else -> return 0
            }
        }

    protected abstract val desiredPreviewFrameSize: Size

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Called onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (hasPermission()) {
            setFragment()
        } else {
            requestPermission()
        }

        // Example of a call to a native method
    }


    protected fun getRgbBytes(): IntArray? {
        imageConverter!!.run()
        return rgbBytes
    }

    override fun previewSizeChosen(size: Size, rotation: Int) {
        previewHeight = size.height
        previewWidth = size.width
        this.onPreviewSizeChosen(size, rotation)
    }

    override fun getCameraConnectionCallback() : CameraConnectionFragment.ConnectionCallback {
        return this
    }

    override fun getOnImageAvailableListener() : ImageReader.OnImageAvailableListener {
        return this
    }

    /**
     * Callback for Camera2 API
     */
    override fun onImageAvailable(reader: ImageReader) {
        //We need wait until we have some size from previewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }

        try {
            val image = reader.acquireLatestImage() ?: return

            if (isProcessingFrame) {
                image.close()
                Log.w(TAG, "Dropping Frame!")
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                        yuvBytes[0],
                        yuvBytes[1],
                        yuvBytes[2],
                        previewWidth,
                        previewHeight,
                        yRowStride,
                        uvRowStride,
                        uvPixelStride,
                        rgbBytes)
            }

            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }

            processImage()
        } catch (e: Exception) {
            Log.e(TAG, "Exception!")
            e.printStackTrace();
            Trace.endSection()
            return
        }

        Trace.endSection()
    }

    @Synchronized
    override fun onStart() {
        Log.d(TAG, "Called onStart")
        super.onStart()
    }

    @Synchronized
    override fun onResume() {
        Log.d(TAG, "Called onResume")
        super.onResume()

        handlerThread = HandlerThread("inference")
        handlerThread?.let { it.start() }
        handlerThread?.let { handler = Handler(it.getLooper()) }
    }

    @Synchronized
    override fun onPause() {
        Log.d(TAG, "Called onPause")

        /*
        if(!isFinishing())
        {
            Log.i(TAG, "Finishing")
            finish()
        }
        */

        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch(e : InterruptedException) {
            Log.e(TAG, "Exception")
        }

        super.onPause()
    }

    @Synchronized
    override fun onStop() {
        Log.d(TAG, "Called onStop")
        super.onStop()
    }

    @Synchronized
    override fun onDestroy() {
        Log.d(TAG, "Called onDestroy")
        super.onDestroy()
    }

    @Synchronized
    protected fun runInBackground(r: Runnable) {
        handler?.post(r)
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission() : Boolean {
        return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private fun requestPermission() {
        if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) || shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
            Toast.makeText(this,
                    "Camera AND storage permission are required for this app", Toast.LENGTH_LONG).show()
        }
        requestPermissions(arrayOf(PERMISSION_CAMERA, PERMISSION_STORAGE), PERMISSIONS_REQUEST)
    }

    // Returns true if the device supports the required hardware level, or better.
    private fun isHardwareLevelSupported(
            characteristics: CameraCharacteristics, requiredLevel: Int): Boolean {
        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!
        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            requiredLevel == deviceLevel
        } else requiredLevel <= deviceLevel
        // deviceLevel is not LEGACY, can use numerical sort
    }

    private fun chooseCamera(): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

// Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = facing == CameraCharacteristics.LENS_FACING_EXTERNAL || isHardwareLevelSupported(characteristics,
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL)
                Log.i(TAG, "Camera API 1vs2?: $useCamera2API")
                return cameraId
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera")
        }

        return null
    }

    protected fun setFragment() {
        val cameraId = chooseCamera()
        if (cameraId == null) {
            Toast.makeText(this, "No Camera Detected", Toast.LENGTH_SHORT).show()
            finish()
        }
        if (!useCamera2API)
        {
            Toast.makeText(this, "Camera2 support is required for this app", Toast.LENGTH_SHORT).show()
            finish()
        }

        val camera2Fragment = CameraConnectionFragment.newInstance(
                this,
                this,
                R.layout.fragment_camera2_basic,
                desiredPreviewFrameSize)

        camera2Fragment.setCamera(cameraId!!)
        fragment = camera2Fragment


        fragmentManager
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit()

    }

    protected fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                Log.d(TAG, java.lang.String.format("Initializing buffer %d at size %d", i, buffer.capacity()))
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }
/*
    fun requestRender() {
        val overlay = findViewById<View>(R.id.debug_overlay) as OverlayView
        if (overlay != null) {
            overlay!!.postInvalidate()
        }
    }

    fun addCallback(callback: OverlayView.DrawCallback) {
        val overlay = findViewById<View>(R.id.debug_overlay) as OverlayView
        if (overlay != null) {
            overlay!!.addCallback(callback)
        }
    }

    fun onSetDebug(debug: Boolean) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            isDebug = !isDebug
            requestRender()
            onSetDebug(isDebug)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
*/
    public fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback!!.run()
        }
    }


    protected abstract fun processImage()

    protected abstract fun onPreviewSizeChosen(size: Size, rotation: Int)

    protected abstract fun displayFrame(frame: Bitmap)

}
