package cse281.automobile

import android.os.AsyncTask
import org.opencv.core.Core.split
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc;

class LaneDetection : AsyncTask<Mat, Void, Void>() {

    val ADAPTIVE_THRESHOLD: Double = 55.toDouble()
    val MAHAL_VAL = .05

    val kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_RECT, Size(3.toDouble(), 3.toDouble()))

    var previewHeight : Int? = null
    var previewWidth : Int? = null

    var postExecutionCallback : Runnable? = null

    var processedFrame : Mat? = null
    var grayFrame : Mat? = null

    var contours: ArrayList<MatOfPoint> = ArrayList()
    var goodContours: ArrayList<MatOfPoint> = ArrayList()

    protected override fun doInBackground(vararg frames: Mat) : Void {
        var frame = frames[0]

        preprocessFrame(frame)
        getContours()

        for(contour in contours) {
            split(grayFrame, )
        }

        return;
    }

    protected fun preprocessFrame(frame : Mat) {
        if(grayFrame == null) {
            grayFrame = Mat()
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

    public fun setPreviewSize(size: android.util.Size) {
        previewWidth = size.width
        previewHeight = size.height
    }

    protected override fun onPostExecute(result: Void?) {
        super.onPostExecute(result)

        postExecutionCallback.run();
    }
}