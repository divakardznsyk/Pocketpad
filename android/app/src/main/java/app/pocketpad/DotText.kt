package app.pocketpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Text drawn as a dot-matrix display. Shrinks its dots so the text always fits the width it is given. */
class DotText(context: Context) : View(context) {
    var text: String = ""
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Largest distance between dot centres, in px. */
    var maxPitch = 8f
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    var color: Int = Theme.ink
        set(v) {
            field = v
            invalidate()
        }

    /** Colour of switched-off dots (0 = don't draw them). */
    var unlit: Int = Color.argb(46, 255, 255, 255)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun cols() = max(1, text.length * 6 - 1)

    private fun pitch(widthPx: Int): Float =
        if (widthPx <= 0) maxPitch else min(maxPitch, widthPx / cols().toFloat())

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val mode = MeasureSpec.getMode(widthSpec)
        val size = MeasureSpec.getSize(widthSpec)
        val p = if (mode == MeasureSpec.UNSPECIFIED) maxPitch else pitch(size)
        val wanted = (cols() * p).toInt() + 1
        val w = if (mode == MeasureSpec.EXACTLY) size else wanted
        setMeasuredDimension(w, (7 * p).toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val p = pitch(width)
        val r = p * 0.34f
        for ((i, ch) in text.withIndex()) {
            val rows = DotFont.rows(ch)
            for (row in 0 until 7) {
                for (col in 0 until 5) {
                    val lit = ((rows[row] shr (4 - col)) and 1) == 1
                    if (!lit && unlit == 0) continue
                    paint.color = if (lit) color else unlit
                    canvas.drawCircle((i * 6 + col) * p + p / 2, row * p + p / 2, r, paint)
                }
            }
        }
    }
}
