package cse281.automobile

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Trace
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.Comparator
import java.util.PriorityQueue
import java.util.Vector
import org.tensorflow.Graph
import org.tensorflow.Operation
import org.tensorflow.contrib.android.TensorFlowInferenceInterface

/**
 * Wrapper for frozen detection models trained using the Tensorflow Object Detection API:
 * github.com/tensorflow/models/tree/master/research/object_detection
 */
class TFDetector private constructor() {

    // Config values.
    private var inputName: String? = null
    private var inputSize: Int = 0

    // Pre-allocated buffers.
    private val labels = arrayOf("???","speedLimit","stopSign")
    private var intValues: IntArray? = null
    private var byteValues: ByteArray? = null
    private var outputLocations: FloatArray? = null
    private var outputScores: FloatArray? = null
    private var outputClasses: FloatArray? = null
    private var outputNumDetections: FloatArray? = null
    private var outputNames: Array<String>? = null


    private var inferenceInterface: TensorFlowInferenceInterface? = null

    val statString: String
        get() = inferenceInterface!!.statString

    fun recognizeImage(bitmap: Bitmap): List<Recognition> {
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage")

        Trace.beginSection("preprocessBitmap")
        // Preprocess the image data to extract R, G and B bytes from int of form 0x00RRGGBB
        // on the provided parameters.
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in intValues!!.indices) {
            byteValues!![i * 3 + 2] = (intValues!![i] and 0xFF).toByte()
            byteValues!![i * 3 + 1] = (intValues!![i] shr 8 and 0xFF).toByte()
            byteValues!![i * 3 + 0] = (intValues!![i] shr 16 and 0xFF).toByte()
        }
        Trace.endSection() // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed")
        inferenceInterface!!.feed(inputName, byteValues, *longArrayOf(1.toLong(), inputSize.toLong(), inputSize.toLong(), 3.toLong()))
        Trace.endSection()

        // Run the inference call.
        Trace.beginSection("run")
        inferenceInterface!!.run(outputNames)
        Trace.endSection()

        // Copy the output Tensor back into the output array.
        Trace.beginSection("fetch")
        outputLocations = FloatArray(MAX_RESULTS * 4)
        outputScores = FloatArray(MAX_RESULTS)
        outputClasses = FloatArray(MAX_RESULTS)
        outputNumDetections = FloatArray(1)
        inferenceInterface!!.fetch(outputNames!![0], outputLocations!!)
        inferenceInterface!!.fetch(outputNames!![1], outputScores!!)
        inferenceInterface!!.fetch(outputNames!![2], outputClasses!!)
        inferenceInterface!!.fetch(outputNames!![3], outputNumDetections!!)
        Trace.endSection()

        // Find the best detections.
        val pq = PriorityQueue<Recognition>(
                1,
                Comparator<Recognition> { lhs, rhs ->
                    // Intentionally reversed to put high confidence at the head of the queue.
                    rhs.confidence!!.compareTo(lhs.confidence!!)
                })

        // Scale them back to the input size.
        for (i in outputScores!!.indices) {
            val detection = RectF(
                    outputLocations!![4 * i + 1] * inputSize,
                    outputLocations!![4 * i] * inputSize,
                    outputLocations!![4 * i + 3] * inputSize,
                    outputLocations!![4 * i + 2] * inputSize)
            pq.add(
                    Recognition(i, labels[outputClasses!![i].toInt()], outputScores!![i], detection))
        }

        val recognitions = ArrayList<Recognition>()
        for (i in 0 until Math.min(pq.size, MAX_RESULTS)) {
            recognitions.add(pq.poll())
        }
        Trace.endSection() // "recognizeImage"
        return recognitions
    }

    fun close() {
        inferenceInterface!!.close()
    }

    // Only return this many results.


    companion object {
        private val MAX_RESULTS = 100
        fun create(assetManager: AssetManager, modelFilename: String, labelFilename: String, inputSize: Int): TFDetector {
            val d = TFDetector()

//            var labelsInput: InputStream? = null
//            val actualFilename = labelFilename.split("file:///android_asset/")[1]
//            labelsInput = assetManager.open(actualFilename)
//            var br: BufferedReader? = null
//            br = BufferedReader(InputStreamReader(labelsInput!!))
//
//            val iterator = br.lineSequence()
//            iterator.map {
//                d.labels.add(it)
//            }
//            while(iterator.hasNext()) {
//                val line = iterator.next()
//                d.labels.add(line)
//                // do something with line...
//            }
//            reader.close()
//
//            br.forEachLine {
//                line -> d.labels.add(line)
//            }

//            br.close()


            d.inferenceInterface = TensorFlowInferenceInterface(assetManager, modelFilename)

            val g = d.inferenceInterface!!.graph()

            d.inputName = "image_tensor"
            // The inputName node has a shape of [N, H, W, C], where
            // N is the batch size
            // H = W are the height and width
            // C is the number of channels (3 for our purposes - RGB)
            val inputOp = g.operation(d.inputName)
                    ?: throw RuntimeException("Failed to find input Node '" + d.inputName + "'")
            d.inputSize = inputSize
            // The outputScoresName node has a shape of [N, NumLocations], where N
            // is the batch size.
            val outputOp1 = g.operation("detection_scores")
                    ?: throw RuntimeException("Failed to find output Node 'detection_scores'")
            val outputOp2 = g.operation("detection_boxes")
                    ?: throw RuntimeException("Failed to find output Node 'detection_boxes'")
            val outputOp3 = g.operation("detection_classes")
                    ?: throw RuntimeException("Failed to find output Node 'detection_classes'")

            // Pre-allocate buffers.
            d.outputNames = arrayOf("detection_boxes", "detection_scores", "detection_classes", "num_detections")
            d.intValues = IntArray(d.inputSize * d.inputSize)
            d.byteValues = ByteArray(d.inputSize * d.inputSize * 3)
            d.outputScores = FloatArray(MAX_RESULTS)
            d.outputLocations = FloatArray(MAX_RESULTS * 4)
            d.outputClasses = FloatArray(MAX_RESULTS)
            d.outputNumDetections = FloatArray(1)
            return d
        }
    }
}
