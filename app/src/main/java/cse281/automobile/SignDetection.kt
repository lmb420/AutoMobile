package cse281.automobile

import android.app.Activity
import android.content.res.AssetManager
import android.os.AsyncTask
import android.util.Log
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer
import android.support.annotation.NonNull
import com.google.android.gms.tasks.OnFailureListener
import com.google.firebase.ml.vision.text.FirebaseVisionText
import com.google.android.gms.tasks.OnSuccessListener
import cse281.env.ImageUtils

import org.tensorflow.contrib.android.TensorFlowInferenceInterface
import android.R.array
import android.R.attr.bitmap
import android.graphics.*
import android.opengl.ETC1.getHeight
import android.os.SystemClock
import android.widget.Toast
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.ArrayList


class SignDetection : AsyncTask<Bitmap, Void, ArrayList<RectF>>(){
    private val TAG = "cse281.automobile.SignDetection"
    var postExecutionCallback : Runnable? = null

    companion object {
        private val cropSize = 300
        private val modelFilename = "file:///android_asset/frozen_inference_graph.pb"
        private val labelFilename = "file:///android_asset/labels.pbtxt"
        private val minimumConf = .5

        private var detector: TFDetector? = null
        private var frameToCropTransform: Matrix? = null
        private var cropToFrameTransform: Matrix? = null
        private var previewHeight:Int? = null
        private var previewWidth:Int? = null
        private var imageSize:Int? = null
        private var croppedBitmap: Bitmap? = null
        private var parentActivity: AdasActivity? = null
        private var mappedRecognitions: ArrayList<RectF>? = null
        private var lastGood: ArrayList<RectF>? = null
        private var labels: ArrayList<String>? = null


        fun initModel(assets: AssetManager, previewWidth: Int, previewHeight: Int, sensorOrientation: Int, parentActivity: AdasActivity) {
            this.previewHeight = previewHeight
            this.previewWidth = previewWidth
            detector = TFDetector.create(assets, modelFilename, labelFilename, cropSize)
            frameToCropTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    cropSize, cropSize,
                    sensorOrientation, false)
            croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
            cropToFrameTransform = Matrix()
            frameToCropTransform!!.invert(cropToFrameTransform)
            this.parentActivity = parentActivity
        }
    }

    override fun doInBackground(vararg frame : Bitmap) : ArrayList<RectF> {
        val canvas = Canvas(croppedBitmap)
        canvas.drawBitmap(frame[0], frameToCropTransform, null)

        val startTime = SystemClock.uptimeMillis()
        val results = detector!!.recognizeImage(croppedBitmap!!)
        val endTime = SystemClock.uptimeMillis()
        val elapsed = endTime - startTime
        //Log.v(TAG, "Inference took $elapsed ms")

        //val overlay = Canvas()
        val paint = Paint()
        paint.color = Color.RED
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.0f

        var minimumConfidence = minimumConf

        mappedRecognitions = ArrayList<RectF>()
        labels = ArrayList<String>()

        for (result in results) {
            val location = result.getLocation()
            if (result.confidence!! >= minimumConfidence) {
                cropToFrameTransform!!.mapRect(location)
                result.setLocation(location)
                mappedRecognitions!!.add(result.getLocation())
                labels!!.add(result.getClass())

                Log.v("$TAG.result", result.toString())
            }
        }
        if(mappedRecognitions!!.size > 0){
            processText(frame[0], mappedRecognitions!![0], labels!![0])
        }
        return mappedRecognitions!!
    }

    private fun processText(frame: Bitmap, location: RectF, objectClass: String) {
        val width = (location.width() * 1.2).toInt()
        val height = (location.height() * 1.2).toInt()

        //val croppedFrame = Bitmap.createBitmap(frame, (location.left*(5/6)).toInt(),(location.right*(5/6)).toInt(),width,height)
        val croppedFrame = Bitmap.createBitmap(frame, (location.left).toInt(),(location.top).toInt(),width,height)
        val image = FirebaseVisionImage.fromBitmap(croppedFrame)
        val textRecognizer = FirebaseVision.getInstance().onDeviceTextRecognizer

        textRecognizer.processImage(image)
                .addOnSuccessListener {
                    val result = it.text
                    Log.v("OCRRESULT", "result = " + result)
                    Toast.makeText(parentActivity!!.applicationContext, objectClass + " - " + result, Toast.LENGTH_LONG).show();

                    // Task completed successfully
                    // ...
                }
                .addOnFailureListener {
                    Log.v(TAG, "Detection failed with " + it.message)
                    // Task failed with an exception
                    // ...
                }
    }

    fun setCallback(callback : Runnable) {
        postExecutionCallback = callback
    }

    @Override
    override fun onPostExecute(result : ArrayList<RectF>) {
        super.onPostExecute(result)
        //invalidate
        if(result.size > 0) {
            Log.v(TAG, "" + result.size)
            lastGood = result
            parentActivity!!.setSignRects(result)
            parentActivity!!.invalidateSigns()
        }
        postExecutionCallback!!.run()
        parentActivity!!.readyForNextImage()
    }
}