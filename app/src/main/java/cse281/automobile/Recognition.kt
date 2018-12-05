package cse281.automobile

import android.graphics.RectF

/**
 * An immutable result returned by a recognizer describing what was recognized.
 * Created by Zoltan Szabo on 12/17/17.
 * URL: https://github.com/szaza/android-yolo-v2
 */
class Recognition(val id: Int?, val title: String, val confidence: Float?, var bBox: RectF?) {

    override fun toString(): String {
        return "Recognition{" +
                "id=" + id +
                ", title='" + title + '\''.toString() +
                ", confidence=" + confidence +
                ", bBox=" + bBox +
                '}'.toString()
    }
}