package cse281.automobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.support.v7.app.AppCompatActivity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.*

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
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C
import org.opencv.imgproc.Imgproc.THRESH_BINARY

import java.lang.String.format

import cse281.env.ImageUtils


class AdasActivity : CameraActivity(), ImageReader.OnImageAvailableListener {
    private var lastProcessingTimeMs: Long = 0

    private val INPUT_SIZE = 224
    private val IMAGE_MEAN = 117
    private val IMAGE_STD = 1f
    private val INPUT_NAME = "input"
    private val OUTPUT_NAME = "output"

    private val MAINTAIN_ASPECT = true


    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var processedRgbBytes: IntArray? = null

    private var textureView: AutoFitTextureView? = null

    protected override val desiredPreviewFrameSize: Size = Size(640, 480)

    private var sensorOrientation: Int? = null
    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    companion object {
        private val TAG = "cse281.automobile.AdasActivity"

        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "Failed to load OpenCV library")
            }
        }
    }


    protected override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        previewWidth = size.width
        previewHeight = size.height

        Log.i(TAG, "Initializing at size $previewWidth x $previewHeight")

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val orientation = rotation - screenOrientation
        Log.i(TAG, "Camera orientation relative to screen canvas: $orientation")

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                INPUT_SIZE, INPUT_SIZE,
                orientation, MAINTAIN_ASPECT)

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)
    }

    protected override fun processImage() {
        if (processedRgbBytes == null) {
            processedRgbBytes = IntArray(previewWidth * previewHeight)
        }

        rgbFrameBitmap!!.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)

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
                    rgbFrameBitmap!!.getPixels(processedRgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

                    displayFrame(rgbFrameBitmap!!)

                    readyForNextImage()
                }
            )
        }

    protected fun displayFrame(frame: Bitmap) {
        if (textureView == null) {
            textureView = findViewById(R.id.texture) as AutoFitTextureView
        }

        val canvas = textureView!!.lockCanvas()

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

        canvas.drawBitmap(frame, matrix, null)

        textureView!!.unlockCanvasAndPost(canvas)
     }
}


