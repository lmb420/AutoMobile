package cse281.automobile

import android.os.AsyncTask
import android.util.Log
import org.opencv.core.Core.split
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc;

class LaneDetection : AsyncTask<Mat, Void, ArrayList<MatOfPoint>>() {
    private val TAG = "cse281.automobile.LaneDetection"

    val ADAPTIVE_THRESHOLD: Double = 55.toDouble()
    val MAHAL_VAL = .05

    val MH = 60
    val MS = 100
    val MV = 100

    val kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_RECT, Size(3.toDouble(), 3.toDouble()))

    var previewHeight : Int? = null
    var previewWidth : Int? = null

    var postExecutionCallback : Runnable? = null

    var processedFrame : Mat? = null
    var grayFrame : Mat? = null

    var contours: ArrayList<MatOfPoint> = ArrayList()
    var goodContours: ArrayList<MatOfPoint> = ArrayList()

    var frameChannels: ArrayList<Mat> = ArrayList(3)

    var parentActivity: AdasActivity? = null

    protected override fun doInBackground(vararg frames: Mat) : ArrayList<MatOfPoint> {
        Log.v(TAG, "Beginning Lane Detection")
        var frame = frames[0]

        contours.clear()
        goodContours.clear()

        preprocessFrame(frame)
        getContours()

        // TODO: Implement Contour filtering (Fk numpy)
        /*
        split(frame, frameChannels)
        for(i in 0.. contours.size) {
            var cimg = Mat.zeros(frameChannels[0].size(), frameChannels[0].type())
            Imgproc.drawContours(cimg, contours, i, Scalar(255.toDouble()), -1)


        }*/

        return ArrayList(contours)
    }

    protected fun preprocessFrame(frame : Mat) {
        if(grayFrame == null) {
            grayFrame = Mat()
        }
        if(processedFrame == null) {
            processedFrame = Mat()
        }

        Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_RGB2GRAY)
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGB2HSV)

        Imgproc.morphologyEx(grayFrame, processedFrame, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(processedFrame, processedFrame, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(processedFrame, processedFrame, Imgproc.MORPH_OPEN, kernel)
    }

    protected fun getContours() {
        var hierarchy = Mat()

        Imgproc.adaptiveThreshold(processedFrame, processedFrame, 255.toDouble(),
                Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 55, ADAPTIVE_THRESHOLD)
        Imgproc.findContours(processedFrame, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    }


    public fun setActivity(act : AdasActivity) {
        parentActivity = act
    }


    public fun setPreviewSize(size: android.util.Size) {
        previewWidth = size.width
        previewHeight = size.height
    }

    public fun setCallback(callback : Runnable) {
        postExecutionCallback = callback
    }

    protected override fun onPostExecute(result: ArrayList<MatOfPoint> ) {
        super.onPostExecute(result)

        Log.i(TAG, "Finished Lane Detection Task")

        parentActivity!!.setContours(result)

        postExecutionCallback!!.run()

        parentActivity!!.readyForNextImage()
    }
}