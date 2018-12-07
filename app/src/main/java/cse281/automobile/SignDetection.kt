package cse281.automobile

import android.content.res.AssetManager
import android.os.AsyncTask
import android.util.Log
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.FirebaseVision
import cse281.env.ImageUtils

import android.graphics.*
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.Surface
import android.widget.Toast
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import kotlin.collections.ArrayList
import android.graphics.Bitmap




class SignDetection : AsyncTask<Bitmap, Void, ArrayList<Recognition>>(){

    var postExecutionCallback : Runnable? = null

    companion object {
        private const val TAG = "cse281.automobile.SignDetection"
        private const val BUF_LEN = 6
        private const val cropSize = 300
        private const val modelFilename = "file:///android_asset/frozen_inference_graph.pb"
        private const val labelFilename = "file:///android_asset/mappedLabels.pbtxt"
        private const val minimumConf = .6

        private var detector: TFDetector? = null
        private var frameToCropTransform: Matrix? = null
        private var cropToFrameTransform: Matrix? = null
        private var previewHeight:Int? = null
        private var previewWidth:Int? = null
        private var imageSize:Int? = null
        private var croppedBitmap: Bitmap? = null
        private var parentActivity: AdasActivity? = null

        var bestSpeedLimit:Recognition? = null
        var bestSpeedText: Int = -1

        private val paint = Paint()
        private var detectCount = BUF_LEN

        private val ORIENTATIONS = SparseIntArray()
        private var deviceRotation: Int? = null
        private var sensorOrientation: Int? = null

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        fun Bitmap.rotate(degrees: Int): Bitmap {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        }

        private fun getRotationCompensation(): Int {
            // Get the device's current rotation relative to its "native" orientation.
            // Then, from the ORIENTATIONS table, look up the angle the image must be
            // rotated to compensate for the device's rotation.
            var rotationCompensation = ORIENTATIONS.get(deviceRotation!!)

            // On most devices, the sensor orientation is 90 degrees, but for some
            // devices it is 270 degrees. For devices with a sensor orientation of
            // 270, rotate the image an additional 180 ((270 + 270) % 360) degrees.
            return (rotationCompensation + sensorOrientation!! + 270) % 360
        }

        fun initModel(assets: AssetManager, previewWidth: Int, previewHeight: Int, sensorOrientation: Int, parentActivity: AdasActivity) {
            this.sensorOrientation = sensorOrientation
            this.deviceRotation =  parentActivity.windowManager.defaultDisplay.rotation
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

            paint.color = Color.RED
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.0f

        }

        private fun processText(frame: Bitmap) {
            val location = bestSpeedLimit?.bBox!!
            var width = (location.width() * 2.5).toInt()
            var height = (location.height() * 1.5).toInt()
            val xOffset = ((width - location.width()) / 1.5).toInt()
            val yOffset = ((height - location.height()) / 2).toInt()

            if((location.top).toInt() + height > frame.height){
                height = frame.height - (location.top).toInt()
            }
            if((location.right).toInt() + width > frame.width){
                width = frame.width - (location.right).toInt()
            }

            val croppedFrame = Bitmap.createBitmap(frame, (location.left).toInt()-xOffset,(location.top).toInt() -yOffset,width,height)
            val image = FirebaseVisionImage.fromBitmap(croppedFrame)
            val textRecognizer = FirebaseVision.getInstance().onDeviceTextRecognizer

            textRecognizer.processImage(image)
                    .addOnSuccessListener {
                        val result = it.text
                        it.textBlocks.map {
                            block -> block.lines.map {
                                it.elements.map {
                                    val text=it.text
                                    if(text.toIntOrNull() != null){
                                        bestSpeedText = text.toIntOrNull()!!
                                        parentActivity!!.setSpeedLimit(bestSpeedText, croppedFrame)
                                    }else{
                                        parentActivity!!.setSpeedLimit(-1, croppedFrame)
                                    }
                                }
                            }
                        }
//                        if(result.toIntOrNull() != null){
//                            bestSpeedText = result.toIntOrNull()!!
//                            parentActivity!!.setSpeedLimit(bestSpeedText, croppedFrame)
//                        }else{
//                            parentActivity!!.setSpeedLimit(-1, croppedFrame)
//                        }
                        if(result != ""){
                            Toast.makeText(parentActivity!!.applicationContext, "Sign text: "+result, Toast.LENGTH_LONG).show();
                        }
                    }
                    .addOnFailureListener {
                        Log.v(TAG, "Detection failed with " + it.message)
                    }
        }

    }

    override fun doInBackground(vararg frame : Bitmap) : ArrayList<Recognition> {
        val canvas = Canvas(croppedBitmap)
        val frameBmp = frame[0].copy(frame[0].config, true)

        canvas.drawBitmap(frameBmp, frameToCropTransform, null)

        val startTime = SystemClock.uptimeMillis()
        var results = detector!!.recognizeImage(croppedBitmap!!)
        val endTime = SystemClock.uptimeMillis()
        val elapsed = endTime - startTime
        //Log.v(TAG, "Inference took $elapsed ms")

        bestSpeedLimit = null

        results = results.filter{it.confidence!! >= minimumConf}.sortedWith(compareByDescending{it.confidence})
        results = results.map{result ->
            cropToFrameTransform!!.mapRect(result.bBox)
//            Log.v("$TAG.debug", ""+result.confidence)
            if(bestSpeedLimit == null && result.title == ("speedLimit")){
                bestSpeedLimit = result
            }
            Log.v("$TAG.result", result.toString())
            result
        }

        if(bestSpeedLimit != null){
            processText(frameBmp)
            //val croppedFrame = Bitmap.createBitmap(frameBmp, bestSpeedLimit!!.bBox!!.left.toInt(), bestSpeedLimit!!.bBox!!.top.toInt() ,bestSpeedLimit!!.bBox!!.width().toInt(),bestSpeedLimit!!.bBox!!.height().toInt())
            //parentActivity!!.setSpeedLimit(-1, croppedFrame)

        }
        return ArrayList(results)
    }

    fun setCallback(callback : Runnable) {
        postExecutionCallback = callback
    }



    @Override
    override fun onPostExecute(result : ArrayList<Recognition>) {
        super.onPostExecute(result)
        //invalidate
        if(result.size > 0 || detectCount == 0) {
            Log.v(TAG, "" + result.size)
            parentActivity!!.setSignRecogs(result)
            parentActivity!!.invalidateSigns()
            detectCount = BUF_LEN
        }else{
            detectCount--
        }
        postExecutionCallback!!.run()
        parentActivity!!.readyForNextImage()
    }
}