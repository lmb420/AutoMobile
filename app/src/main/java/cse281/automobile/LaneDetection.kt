package cse281.automobile

import android.os.AsyncTask
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.core.Scalar
import org.opencv.core.CvType
import org.opencv.core.Mat


class LaneDetection : AsyncTask<Mat, Void, ArrayList<MatOfPoint>>() {
    private val TAG = "cse281.automobile.LaneDetection"

    private val BRIGHTNESS : Double =   90.0

    private val TRI_LEFT_TOP = 0.4
    private val TRI_LEFT_SIDE = 0.45
    private val TRI_RIGHT_TOP = 0.4
    private val TRI_RIGHT_SIDE = 0.45

    private var LOWER_WHITE = Scalar(0.toDouble(), 0.toDouble(), 170.toDouble())
    private var UPPER_WHITE = Scalar(180.toDouble(), 80.toDouble(), 260.toDouble())

    private var LOWER_YELLOW = Scalar(16.0, 60.toDouble(), 80.toDouble())
    private var UPPER_YELLOW = Scalar(42.0, 255.toDouble(), 255.toDouble())

    private val CROP_WIDTH_LEFT = 0
    private val CROP_WIDTH_RIGHT = 1
    private val CROP_HEIGHT_TOP = .5
    private val CROP_HEIGHT_BOT = .95

    private var cropOffset: Point? = null

    private var trapazoidMask: Mat? = null

    private var meanBrightness: Double = 0.0

    private val kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_RECT, Size(3.toDouble(), 3.toDouble()))

    private var previewHeight : Int? = null
    private var previewWidth : Int? = null

    private var postExecutionCallback : Runnable? = null

    private var grayFrame : Mat? = null
    private var binarizedFrame : Mat? = null
    private var processedFrame : Mat? = null

    private var contours: ArrayList<MatOfPoint> = ArrayList()

    var parentActivity: AdasActivity? = null

    override fun doInBackground(vararg frames: Mat) : ArrayList<MatOfPoint> {
        Log.v(TAG, "Beginning Lane Detection")
        var frame = frames[0]
        val height = frame.rows().toDouble()
        val width = frame.cols().toDouble()

        if(grayFrame == null) {
            grayFrame = Mat()
        }
        if(binarizedFrame == null) {
            binarizedFrame = Mat()
        }
        if(processedFrame == null) {
            processedFrame = Mat()
        }

        contours.clear()

        /*
        val fullGrayFrame = Mat()
        Imgproc.cvtColor(frame, fullGrayFrame, Imgproc.COLOR_RGB2GRAY)

        val meanScalar : Scalar = Core.mean(fullGrayFrame)

        meanBrightness = (meanScalar.getValue(0))
        */

        cropOffset = Point(width * CROP_WIDTH_LEFT, height * CROP_HEIGHT_TOP)

        var croppedFrame = frame.submat(Range((height * CROP_HEIGHT_TOP).toInt(), (height * CROP_HEIGHT_BOT).toInt()),
                Range((width * CROP_WIDTH_LEFT).toInt(), (width * CROP_WIDTH_RIGHT).toInt()))

        val croppedHeight = croppedFrame.rows()
        val croppedWidth = croppedFrame.cols()
        var points : MatOfPoint
        var topPoint : Point
        var sidePoint : Point

        trapazoidMask = Mat(croppedHeight, croppedWidth, CvType.CV_8U, Scalar(255.0))

        topPoint = Point(croppedWidth * TRI_LEFT_TOP, 0.0)
        sidePoint = Point(0.0, croppedHeight * TRI_LEFT_SIDE)

        points = MatOfPoint(topPoint, sidePoint, Point(0.0,0.0))

        Imgproc.fillConvexPoly(trapazoidMask, points, Scalar(0.0))

        topPoint = Point(croppedWidth - (croppedWidth * TRI_RIGHT_TOP), 0.0)
        sidePoint = Point(croppedWidth.toDouble(), croppedHeight * TRI_RIGHT_SIDE)

        points = MatOfPoint(topPoint, sidePoint, Point(croppedWidth.toDouble(), 0.0))

        Imgproc.fillConvexPoly(trapazoidMask, points, Scalar(0.0))

        preprocessFrame(croppedFrame)

        mahalColorFilter(croppedFrame)
        imageOps()

        getContours()

        return ArrayList(contours)
    }

    private fun preprocessFrame(frame : Mat) {

        Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_RGB2GRAY)
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGB2HSV)

        //Imgproc.morphologyEx(grayFrame, processedFrame, Imgproc.MORPH_OPEN, kernel)
        //Imgproc.morphologyEx(processedFrame, processedFrame, Imgproc.MORPH_CLOSE, kernel)
        //Imgproc.morphologyEx(processedFrame, processedFrame, Imgproc.MORPH_OPEN, kernel)
    }

    private fun mahalColorFilter(frame: Mat) {
        val height = frame.rows()
        val width = frame.cols()

        val meanScalar : MatOfDouble = MatOfDouble()
        val stdDevScalar : MatOfDouble = MatOfDouble()

        Core.meanStdDev(frame, meanScalar, stdDevScalar, trapazoidMask)

        meanBrightness = meanScalar.toArray()[2]
        val stdDevBrightness = stdDevScalar.toArray()[2]
        val brightnessChange = meanBrightness - BRIGHTNESS

        Log.v(TAG, "Mean Brightness is $meanBrightness, Std Dev is $stdDevBrightness")

        //LOWER_WHITE.setValue(2, LOWER_WHITE.getValue(2) + brightnessChange)

        LOWER_WHITE.setValue(2, Math.min(meanBrightness * 1.1 + 1 * stdDevBrightness, 230.0))

        LOWER_YELLOW.setValue(2, Math.min(meanBrightness * .8, 230.0))
        //UPPER_YELLOW.setValue(2, UPPER_YELLOW.getValue(2) + brightnessChange)

        val whiteTemp = Mat(height, width, CvType.CV_8U, Scalar(0.0))
        val yellowTemp = Mat(height, width, CvType.CV_8U, Scalar(0.0))

        val mask = Mat(height, width, CvType.CV_8U, Scalar(0.0))

        Imgproc.rectangle(mask,
                Point((width / 2).toDouble(), 0.0),
                Point(width.toDouble(), height.toDouble()),
                Scalar(255.0))

        Core.inRange(frame, LOWER_WHITE, UPPER_WHITE, whiteTemp)
        Core.inRange(frame, LOWER_YELLOW, UPPER_YELLOW, yellowTemp)

        yellowTemp.setTo(Scalar(0.0), mask)

        Core.bitwise_or(whiteTemp, yellowTemp, binarizedFrame)
    }

    private fun imageOps() {
        val height = binarizedFrame!!.rows().toDouble()
        val width = binarizedFrame!!.cols().toDouble()
        var points : MatOfPoint
        var topPoint : Point
        var sidePoint : Point

        Imgproc.blur(binarizedFrame, processedFrame, kernel.size())

        Imgproc.dilate(processedFrame, processedFrame, kernel)

        Imgproc.morphologyEx(processedFrame, processedFrame, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(processedFrame, processedFrame, Imgproc.MORPH_CLOSE, kernel)

        topPoint = Point(width * TRI_LEFT_TOP, 0.0)
        sidePoint = Point(0.0, height * TRI_LEFT_SIDE)

        points = MatOfPoint(topPoint, sidePoint, Point(0.0,0.0))

        Imgproc.fillConvexPoly(processedFrame, points, Scalar(0.0))

        topPoint = Point(width - (width * TRI_RIGHT_TOP), 0.0)
        sidePoint = Point(width, height * TRI_RIGHT_SIDE)

        points = MatOfPoint(topPoint, sidePoint, Point(width, 0.0))

        Imgproc.fillConvexPoly(processedFrame, points, Scalar(0.0))

    }

    private fun getContours() {
        val hierarchy = Mat()

        Imgproc.findContours(processedFrame, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE, cropOffset)

        val numContours = contours.size
        Log.d(TAG, "Found $numContours contours")
    }

    fun setActivity(act : AdasActivity) {
        parentActivity = act
    }


    fun setPreviewSize(size: android.util.Size) {
        previewWidth = size.width
        previewHeight = size.height
    }

    fun setCallback(callback : Runnable) {
        postExecutionCallback = callback
    }

    override fun onPostExecute(result: ArrayList<MatOfPoint> ) {
        super.onPostExecute(result)

        Log.i(TAG, "Finished Lane Detection Task")

        parentActivity!!.setContours(result)

        postExecutionCallback!!.run()
        parentActivity!!.readyForNextImage()

        parentActivity = null
    }
}