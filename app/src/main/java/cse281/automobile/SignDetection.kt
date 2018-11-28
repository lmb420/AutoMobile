package cse281.automobile

import android.app.Activity
import android.graphics.Bitmap
import android.os.AsyncTask
import android.util.Log
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer
import android.support.annotation.NonNull
import com.google.android.gms.tasks.OnFailureListener
import com.google.firebase.ml.vision.text.FirebaseVisionText
import com.google.android.gms.tasks.OnSuccessListener
import android.graphics.BitmapFactory





class SignDetection : AsyncTask<Bitmap, Void, Int>(){
    private val TAG = "cse281.automobile.SignDetection"
    var postExecutionCallback : Runnable? = null


    protected override fun doInBackground(vararg frame : Bitmap) : Int {
        Log.v(TAG, "Beginning Sign Detection")
        processText(frame[0])
        return 9
    }

    protected fun processText(frame: Bitmap) {
        val image = FirebaseVisionImage.fromBitmap(frame)
        val textRecognizer = FirebaseVision.getInstance().onDeviceTextRecognizer

        textRecognizer.processImage(image)
                .addOnSuccessListener {
                    val result = it.text
                    Log.v(TAG, "result is: $result")

                    // Task completed successfully
                    // ...
                }
                .addOnFailureListener {
                    Log.v(TAG, "Detection failed with " + it.message)
                    // Task failed with an exception
                    // ...
                }
    }

    public fun setCallback(callback : Runnable) {
        postExecutionCallback = callback
    }

    protected override fun onPostExecute(result : Int) {
        Log.v(TAG, "onPostExecute called")
        postExecutionCallback!!.run()
    }
}