package cse281.automobile

import android.app.AlertDialog
import android.graphics.*
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.location.Location
import android.os.*

import android.media.ImageReader

import android.util.Log
import android.util.Size
import android.widget.ImageView
import android.widget.TextView

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc
import org.opencv.core.*
import org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C
import org.opencv.imgproc.Imgproc.THRESH_BINARY

import cse281.automobile.OverlayView.DrawCallback

import cse281.env.ImageUtils
import org.opencv.core.*
import android.app.ProgressDialog
import android.content.*
import android.location.LocationManager
import android.os.IBinder


class AdasActivity : CameraActivity(), ImageReader.OnImageAvailableListener {
    private var lastProcessingTimeMs: Long = 0

    private val INPUT_SIZE = 224
    private val IMAGE_MEAN = 117
    private val IMAGE_STD = 1f
    private val INPUT_NAME = "input"
    private val OUTPUT_NAME = "output"

    private val MAINTAIN_ASPECT = true

    private var displayOrientation: Int? = null

    private var rgbFrameBitmap: Bitmap? = null
    private var renderFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var textureView: AutoFitTextureView? = null

    protected override val desiredPreviewFrameSize: Size = Size(640, 480)

    private var timestamp: Long = 0

    private var sensorOrientation: Int? = null
    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null
    private var frameToScreenTransform: Matrix? = null

    private var isRendering: Boolean = false
    private var processingLaneDetection: Boolean = false
    private var processingFCW: Boolean = false
    private var processingSignDetection: Boolean = false

    private var laneDetectionTask: LaneDetection? = null
    private var laneContours: ArrayList<MatOfPoint>? = null
    private val boxPaint = Paint()

    private var trackingOverlay: OverlayView? = null
    private var signOverlay: OverlayView? = null
    private var infoPanel: OverlayView? = null
    private var signView: ImageView? = null

    private var signDetectionTask: SignDetection? = null
    private var signRecogs: ArrayList<Recognition>? = null
    private val signPaint = Paint()
    private val stopSignPaint = Paint()
    private var stopSignWarn = false
    private var speedLimit:Int = -1
    private var speedLimitBmp: Bitmap? = null
    private var density: Float? = null

    public var myService: LocationService? = null
    var locationManager: LocationManager? = null
    var image: ImageView? = null

    private val sc = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as LocationService.LocalBinder
            myService = binder.service
            status = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            status = false
        }
    }

    fun bindService() {
        if (status === true)
            return
        val i = Intent(applicationContext, LocationService::class.java)
        bindService(i, sc, Context.BIND_AUTO_CREATE)
        status = true
        startTime = System.currentTimeMillis()
    }

    fun unbindService() {
        if (status === false)
            return
        val i = Intent(applicationContext, LocationService::class.java)
        unbindService(sc)
        status = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if(status == true) {
            unbindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //time = findViewById(R.id.timetext)
        speed = findViewById(R.id.currentSpeed)
        time = findViewById(R.id.timestamp)

        checkGps()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (!(locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            return
        }

        if (status == false) {
            Log.e(TAG, "Binding Service")
            //Here, the Location Service gets bound and the GPS Speedometer gets Active.
            bindService()
        }
        locate = ProgressDialog(this)
        locate!!.setIndeterminate(true)
        locate!!.setCancelable(false)
        locate!!.setMessage("Getting Location...")
        locate!!.show()

        //start.setVisibility(View.GONE)
        //pause.setVisibility(View.VISIBLE)
        //pause.setText("Pause")
        //stop.setVisibility(View.VISIBLE)
    }

    fun checkGps() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGPSDisabledAlertToUser()
        }
    }

    private fun showGPSDisabledAlertToUser() {
        val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
        alertDialogBuilder.setMessage("Enable GPS to use application")
                .setCancelable(false)
                .setPositiveButton("Enable GPS",
                        object : DialogInterface.OnClickListener {
                            override fun onClick(dialog : DialogInterface, id : Int) {
                                val callGPSSettingIntent = Intent(
                                        android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                startActivity(callGPSSettingIntent)
                            }
                        })
        alertDialogBuilder.setNegativeButton("Cancel",
                object : DialogInterface.OnClickListener {
                    override fun onClick(dialog : DialogInterface, id : Int) {
                        dialog.cancel()
                    }
                })
        val alert = alertDialogBuilder.create();
        alert.show()
    }

    companion object {
        private val TAG = "cse281.automobile.AdasActivity"
        @JvmField var locate: ProgressDialog? = null
        @JvmField var dist: TextView? = null
        @JvmField var time: TextView? = null
        @JvmField var speed: TextView? = null
        @JvmField var status: Boolean = false
        @JvmField var startTime: Long = 0
        @JvmField var endTime: Long = 0

        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "Failed to load OpenCV library")
            }
        }
    }


    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        previewWidth = size.width
        previewHeight = size.height

        Log.i(TAG, "Initializing at size $previewWidth x $previewHeight")

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        renderFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)

        croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)

        Log.e(TAG, "Screen Orientation is $screenOrientation")

        Log.e(TAG, "Rotation is $rotation")

        val orientation = rotation - screenOrientation
        displayOrientation = orientation

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                INPUT_SIZE, INPUT_SIZE,
                orientation!!, MAINTAIN_ASPECT)

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        if (textureView != null) {
            Log.i(TAG, "Initializing transformation with orientation $orientation")
            frameToScreenTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    textureView!!.width, textureView!!.height,
                    displayOrientation!!, MAINTAIN_ASPECT)
        } else {
            frameToScreenTransform = null
        }

        trackingOverlay = findViewById(R.id.lane_tracking_overlay)

        trackingOverlay!!.addCallback(
                object : DrawCallback {
                    override fun drawCallback(canvas: Canvas) {
                        drawLaneContours(canvas)
                    }
                })

        signOverlay = findViewById(R.id.sign_detection_overlay)

        signOverlay!!.addCallback(
                object : DrawCallback {
                    override fun drawCallback(canvas: Canvas) {
                        drawDetections(canvas)
                    }
                })
        density = resources.displayMetrics.density
        //infoPanel = findViewById(R.id.info_panel)

        signView = findViewById(R.id.signView)

        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 12.0f
        boxPaint.strokeCap = Paint.Cap.ROUND
        boxPaint.strokeJoin = Paint.Join.ROUND
        boxPaint.strokeMiter = 100f
        boxPaint.color = 0xffff0000.toInt()


        signPaint.style = Paint.Style.STROKE
        signPaint.strokeWidth = 15.0f
        signPaint.strokeCap = Paint.Cap.ROUND
        signPaint.strokeJoin = Paint.Join.ROUND
        signPaint.strokeMiter = 100f
        signPaint.color = 0xff0000ff.toInt()

        stopSignPaint.style = Paint.Style.STROKE
        stopSignPaint.strokeWidth = 15.0f
        stopSignPaint.strokeCap = Paint.Cap.ROUND
        stopSignPaint.strokeJoin = Paint.Join.ROUND
        stopSignPaint.strokeMiter = 100f
        stopSignPaint.color = Color.YELLOW
        SignDetection.initModel(assets, previewHeight, previewWidth, orientation, this)
    }

    override fun processImage() {
        ++timestamp
        val currTimestamp = timestamp

        Log.i(TAG, "Preparing image $currTimestamp for detection in bg thread.")

        rgbFrameBitmap!!.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)

        if(speed == null) {
            speed = findViewById(R.id.currentSpeed)
        }
        if(time == null) {
            time = findViewById(R.id.timestamp)
        }

        if (processingLaneDetection == false) {
            processingLaneDetection = true

            laneDetectionTask = LaneDetection()
            laneDetectionTask!!.setPreviewSize(Size(previewWidth, previewHeight))
            laneDetectionTask!!.setActivity(this)
            laneDetectionTask!!.setCallback(
                    Runnable {
                        trackingOverlay!!.postInvalidate()
                        processingLaneDetection = false
                    }
            )

            val frame = Mat(previewWidth, previewHeight, CvType.CV_8UC1)
            Utils.bitmapToMat(rgbFrameBitmap, frame)

            laneDetectionTask!!.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, frame)
        }

        if (processingSignDetection == false) {
            signDetectionTask = SignDetection()
            processingSignDetection = true

            signDetectionTask!!.setCallback(
                    Runnable {
                        processingSignDetection = false
                    }
            )
            signDetectionTask!!.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, rgbFrameBitmap)
        }

        //displayFrame(rgbFrameBitmap!!)

        /*
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
                    rgbFrameBitmap!!.getPixels(processedRgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

                    displayFrame(rgbFrameBitmap!!)

                    readyForNextImage()
                }
            )
        */
    }
    public fun invalidateSigns(){
        signOverlay!!.postInvalidate()
    }

    // TODO: REMOVE THIS OLD ASS FUNCTION
    override fun displayFrame(frame: Bitmap) {
        if (isRendering) {
            Log.d(TAG, "Display Frame called while still running!")
            return
        }
        isRendering = true;

        if (textureView == null) {
            textureView = findViewById(R.id.texture) as AutoFitTextureView
        }

        // Draw on all the shit

        if (frameToScreenTransform == null) {
            Log.i(TAG, "Initializing transformation with orientation $displayOrientation")
            frameToScreenTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    textureView!!.width, textureView!!.height,
                    displayOrientation!!, MAINTAIN_ASPECT)
        }

        val canvas = textureView!!.lockCanvas()
        if (canvas == null) {
            isRendering = false
            Log.d(TAG, "Tried to lock textureView while already locked")
            return
        }

        /*
        val rotation = this.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, textureView!!.width.toFloat(), textureView!!.height.toFloat())
        val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val scale = Math.min(
                textureView!!.height.toFloat() / previewWidth.toFloat(),
                textureView!!.width.toFloat() / previewHeight.toFloat())

        matrix.postRotate(90 * (rotation + 1).toFloat(), 0f, 0f)

        matrix.postTranslate(previewHeight.toFloat(), 0f)

        matrix.postScale(scale, scale)

        Log.i(TAG, "Rotation is $rotation")
        */

        var renderMat = Mat()
        Utils.bitmapToMat(frame, renderMat)

        var landCoutoursLocal = laneContours
        if (landCoutoursLocal != null) {
            val numContours = landCoutoursLocal.size
            Log.d(TAG, "Drawing on $numContours contours")
            for (i in 0 until numContours - 1) {
                Imgproc.drawContours(renderMat, landCoutoursLocal, i, Scalar(255.toDouble(), 35.toDouble(), 240.toDouble()), 5)
            }
        }

        Utils.matToBitmap(renderMat, renderFrameBitmap)

        canvas.drawBitmap(renderFrameBitmap, frameToScreenTransform, null)

        textureView!!.unlockCanvasAndPost(canvas)
        Log.d(TAG, "Frame Rendered")

        isRendering = false
    }


    fun drawDetections(canvas: Canvas) {
        if (signRecogs == null) {
            return
        }
        if (frameToScreenTransform == null) {
            if (textureView == null) {
                textureView = findViewById(R.id.texture) as AutoFitTextureView
            }
            frameToScreenTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    textureView!!.width, textureView!!.height,
                    displayOrientation!!, MAINTAIN_ASPECT)
        }

        stopSignWarn = false
        signRecogs!!.map { recog ->
            frameToScreenTransform!!.mapRect(recog.bBox)
            if(recog.title == "stopSign"){
                canvas.drawRect(recog.bBox, stopSignPaint)
                stopSignWarn = true
            }else{
                canvas.drawRect(recog.bBox, signPaint)
            }
        }

        if(speedLimit != -1){
            findViewById<TextView>(R.id.speedLimit).text = "$speedLimit mph"
        }

        val matrix = Matrix()
        // RESIZE THE BIT MAP
        if(speedLimitBmp != null){
            val xFactor = 60*density!! /speedLimitBmp!!.width
            val yFactor = 80*density!! / speedLimitBmp!!.height
            matrix.postScale(xFactor, yFactor)
            val resizedBitmap = Bitmap.createBitmap(speedLimitBmp!!, 0, 0, speedLimitBmp!!.width, speedLimitBmp!!.height, matrix, false)
            signView!!.setImageBitmap(resizedBitmap)
        }


        if(stopSignWarn){
            findViewById<ImageView>(R.id.stopSign).setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark))
        }else{
            findViewById<ImageView>(R.id.stopSign).setBackgroundColor(resources.getColor(android.R.color.holo_green_dark))
        }
    }

    protected fun drawLaneContours(canvas: Canvas) {
        if (laneContours == null) {
            return
        }

        val debug = true

        if (frameToScreenTransform == null) {
            if (textureView == null) {
                textureView = findViewById(R.id.texture) as AutoFitTextureView
            }
            Log.i(TAG, "Initializing transformation with orientation $displayOrientation")
            frameToScreenTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    textureView!!.width, textureView!!.height,
                    displayOrientation!!, MAINTAIN_ASPECT)
        }

        if(debug) {
            drawDebugContours(canvas, laneContours, frameToScreenTransform)
        }
        else {
            var contourBoxes = contoursToRectangles(laneContours)
            var numContours = contourBoxes!!.size

            Log.i(TAG, "Drawing $numContours good contours")

            var outputPoints = FloatArray(16)
            var opencvPoints = Array(4, { i -> org.opencv.core.Point() })
            //var androidPoints = Array(4, { i -> android.graphics.Point() })
            for (box: RotatedRect in contourBoxes!!) {
                box.points(opencvPoints)

                outputPoints[0] = opencvPoints[0].x.toFloat()
                outputPoints[1] = opencvPoints[0].y.toFloat()
                outputPoints[2] = opencvPoints[1].x.toFloat()
                outputPoints[3] = opencvPoints[1].y.toFloat()
                outputPoints[4] = outputPoints[2]
                outputPoints[5] = outputPoints[3]
                outputPoints[6] = opencvPoints[2].x.toFloat()
                outputPoints[7] = opencvPoints[2].y.toFloat()
                outputPoints[8] = outputPoints[6]
                outputPoints[9] = outputPoints[7]
                outputPoints[10] = opencvPoints[3].x.toFloat()
                outputPoints[11] = opencvPoints[3].y.toFloat()
                outputPoints[12] = outputPoints[10]
                outputPoints[13] = outputPoints[11]
                outputPoints[14] = outputPoints[0]
                outputPoints[15] = outputPoints[1]

                frameToScreenTransform!!.mapPoints(outputPoints)

                canvas.drawLines(outputPoints, boxPaint)
            }
        }
    }

    val THRESHOLD_SIZE = .008

    protected fun contoursToRectangles(srcContours: ArrayList<MatOfPoint>?): ArrayList<RotatedRect>? {
        // TODO: REMEMBER TO MAP RECTANGLES TO THEIR PROPER LOCALTION
        // FrameToScreenTransform

        var contourBoxes = ArrayList<RotatedRect>()

        var areaThreshold = previewHeight * previewWidth * THRESHOLD_SIZE

        for (contour: MatOfPoint in srcContours!!) {
            var convertedContour = MatOfPoint2f()
            contour.convertTo(convertedContour, CvType.CV_32F)
            var rotatedRect = Imgproc.minAreaRect(convertedContour)

            var rectSize = rotatedRect.size
            var area = rectSize.height * rectSize.width

            if(area > areaThreshold) {
                contourBoxes.add(rotatedRect)
            }
        }

        return contourBoxes
    }

    protected fun drawDebugContours(canvas: Canvas, srcContours: ArrayList<MatOfPoint>?, transform: Matrix?) {

        var areaThreshold = previewHeight * previewWidth * THRESHOLD_SIZE

        var outputPoints = FloatArray(2)

        for (contour: MatOfPoint in srcContours!!) {
            var convertedContour = MatOfPoint2f()
            contour.convertTo(convertedContour, CvType.CV_32F)
            var rotatedRect = Imgproc.minAreaRect(convertedContour)

            var rectSize = rotatedRect.size
            var area = rectSize.height * rectSize.width

            if(area > areaThreshold) {
                var points = contour.toArray()
                for(point in points) {
                    outputPoints[0] = point.x.toFloat()
                    outputPoints[1] = point.y.toFloat()
                    transform!!.mapPoints(outputPoints)
                    canvas.drawCircle(outputPoints[0], outputPoints[1], 1.toFloat(), boxPaint)
                }
            }
        }
    }

    public fun setContours(contours: ArrayList<MatOfPoint>) {
        laneContours = contours
    }

    fun setSignRecogs(signRecogs: ArrayList<Recognition>) {
        this.signRecogs = signRecogs
    }

    fun setSpeedLimit(speedLimit:Int, speedLimitBmp:Bitmap){

        if(speedLimit != -1){
            this.speedLimit = speedLimit
        }
        this.speedLimitBmp = speedLimitBmp
    }

}


