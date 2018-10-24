package cse281.automobile

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.support.v7.app.AppCompatActivity
import android.graphics.Bitmap
import android.os.*
import android.graphics.BitmapFactory

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

import android.media.Image
import android.media.ImageReader

import android.util.Log
import android.util.Size
import android.widget.Toast

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C
import org.opencv.imgproc.Imgproc.THRESH_BINARY

import java.lang.String.format

import cse281.env.ImageUtils


class CameraActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener
{
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

    private var lastProcessingTimeMs: Long = 0

    protected val desiredPreviewFrameSize: Size = Size(640, 480)

    protected var previewWidth = 0
    protected var previewHeight = 0

    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null

    private var rgbFrameBitmap: Bitmap? = null

    private lateinit var fragment: CameraConnectionFragment


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

    companion object {
        private val TAG = "cse281.automobile.CameraActivity"
        init {
            if(!OpenCVLoader.initDebug()) {
                Log.e(TAG, "Failed to load OpenCV library")
            }
        }
    }

    override fun onStart() {
        Log.d(TAG, "Called onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.d(TAG, "Called onResume")
        super.onResume()

        handlerThread = HandlerThread("inference")
        handlerThread?.let { it.start() }
        handlerThread?.let { handler = Handler(it.getLooper()) }
    }

    override fun onPause() {
        Log.d(TAG, "Called onPause")

        if(!isFinishing())
        {
            Log.i(TAG, "Finishing")
            finish()
        }

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

    override fun onStop() {
        Log.d(TAG, "Called onStop")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "Called onDestroy")
        super.onDestroy()
    }

    @Synchronized
    protected fun runInBackground(r: Runnable) {
        handler!!.post(r)
    }

    /**
     * Callback for Camera2 API
     */
    override fun onImageAvailable(reader: ImageReader) {
        //We need wait until we have some size from onPreviewSizeChosen
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
            Toast.makeText(this@CameraActivity,
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
                object : CameraConnectionFragment.ConnectionCallback {
                    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
                        previewHeight = size.height
                        previewWidth = size.width
                        this@CameraActivity.onPreviewSizeChosen(size, rotation)
                    }
                },
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
                Log.d(TAG, format("Initializing buffer %d at size %d", i, buffer.capacity()))
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }

    protected fun onPreviewSizeChosen(size: Size, rotation: Int) {
        previewWidth = size.width
        previewHeight = size.height

        Log.i(TAG,"Initializing at size $previewWidth x $previewHeight")

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
    }

    protected fun processImage()
    {
        imageConverter!!.run()
        rgbFrameBitmap!!.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        val frame = Mat(previewWidth, previewHeight, CvType.CV_8UC1)
        val result = Mat(previewWidth, previewHeight, CvType.CV_8UC1)

        runInBackground(
                Runnable {
                    val startTime = SystemClock.uptimeMillis()

                    Utils.bitmapToMat(rgbFrameBitmap, frame)
                    Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGB2GRAY)
                    Imgproc.adaptiveThreshold(frame, result, 255.0, ADAPTIVE_THRESH_MEAN_C, THRESH_BINARY, 9, 40.0)

                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
                    //Log.v(TAG, "Processing took $lastProcessingTimeMs ms")

                    Utils.matToBitmap(result, rgbFrameBitmap)
                    rgbFrameBitmap!!.getPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)


                    fragment.displayFrame(rgbFrameBitmap!!)

                    readyForNextImage()
                }
        )
    }

    protected fun readyForNextImage() {
        postInferenceCallback!!.run()
    }
}
