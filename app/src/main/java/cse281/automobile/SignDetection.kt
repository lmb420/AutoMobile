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
import java.nio.ByteBuffer
import java.util.*


class SignDetection : AsyncTask<Bitmap, Void, Int>(){
    private val TAG = "cse281.automobile.SignDetection"
    var postExecutionCallback : Runnable? = null

    companion object {
        private val cropSize = 300
        private val modelFilename = "file:///android_asset/frozen_inference_graph.pb"
        private val labelFilename = "file:///android_asset/labels.pbtxt"
        private val minimumConf = .20

        private var detector: TFDetector? = null
        private var frameToCropTransform: Matrix? = null
        private var cropToFrameTransform: Matrix? = null
        private var previewHeight:Int? = null
        private var previewWidth:Int? = null
        private var imageSize:Int? = null
        private var croppedBitmap: Bitmap? = null


        fun initModel(assets: AssetManager, previewWidth: Int, previewHeight: Int, sensorOrientation: Int) {
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
        }
    }

    override fun doInBackground(vararg frame : Bitmap) : Int {
        Log.v(TAG, "Beginning Sign Detection")

        val canvas = Canvas(croppedBitmap)
        canvas.drawBitmap(frame[0], frameToCropTransform, null)

        val startTime = SystemClock.uptimeMillis()
        val results = detector!!.recognizeImage(croppedBitmap!!)
        val endTime = SystemClock.uptimeMillis()
        val elapsed = endTime - startTime
        Log.v(TAG, "Inference took $elapsed ms")

        //val overlay = Canvas()
        val paint = Paint()
        paint.color = Color.RED
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.0f

        var minimumConfidence = minimumConf

        val mappedRecognitions = LinkedList<Recognition>()

        for (result in results) {
            val location = result.getLocation()
            if (result.confidence!! >= minimumConfidence) {
                canvas.drawRect(location, paint)
                cropToFrameTransform!!.mapRect(location)
                result.setLocation(location)
                mappedRecognitions.add(result)
                Log.v("$TAG.result", result.toString())
            }
        }
        return 9
    }

    private fun processText(location: RectF) {
//        val image = FirebaseVisionImage.fromBitmap(frame)
//        val textRecognizer = FirebaseVision.getInstance().onDeviceTextRecognizer
//
//        textRecognizer.processImage(image)
//                .addOnSuccessListener {
//                    val result = it.text
//                    Log.v(TAG, "result is: $result")
//
//                    // Task completed successfully
//                    // ...
//                }
//                .addOnFailureListener {
//                    Log.v(TAG, "Detection failed with " + it.message)
//                    // Task failed with an exception
//                    // ...
//                }
    }

    fun setCallback(callback : Runnable) {
        postExecutionCallback = callback
    }

    override fun onPostExecute(result : Int) {
        super.onPostExecute(result)
        Log.v(TAG, "onPostExecute called")
        postExecutionCallback!!.run()
    }
}