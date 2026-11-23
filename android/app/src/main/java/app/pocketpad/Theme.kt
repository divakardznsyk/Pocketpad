package app.pocketpad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** AMOLED black, muted yellow / cyan / red. Colour is reserved for things that mean something. */
object Theme {
    val bg = Color.BLACK
    val surface = Color.rgb(10, 10, 9)
    val surface2 = Color.rgb(22, 22, 18)
    val line = Color.rgb(44, 43, 34)
    val outline = Color.rgb(88, 86, 66)
    val ink = Color.rgb(232, 228, 208)
    val sub = Color.rgb(132, 128, 104)
    val accent = Color.rgb(222, 208, 64)
    val cyan = Color.rgb(62, 182, 188)
    val red = Color.rgb(206, 66, 76)
    val green = Color.rgb(96, 184, 138)

    fun cond(bold: Boolean = true): Typeface =
        Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)

    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Rectangle with the top-left and bottom-right corners cut off. */
    fun chamfer(r: RectF, cut: Float, out: Path = Path()): Path {
        out.reset()
        out.moveTo(r.left + cut, r.top)
        out.lineTo(r.right, r.top)
        out.lineTo(r.right, r.bottom - cut)
        out.lineTo(r.right - cut, r.bottom)
        out.lineTo(r.left, r.bottom)
        out.lineTo(r.left, r.top + cut)
        out.close()
        return out
    }
}

/** Panel background: chamfered corners, hairline border, the two cuts lit in [tick] colour. */
class ChamferDrawable(
    private val fill: Int,
    private val stroke: Int,
    private val strokeW: Float,
    private val cut: Float,
    private val tick: Int = 0,
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    override fun draw(c: Canvas) {
        val h = strokeW / 2
        rf.set(bounds.left + h, bounds.top + h, bounds.right - h, bounds.bottom - h)
        Theme.chamfer(rf, cut, path)
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawPath(path, p)
        if (strokeW > 0 && stroke != 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeW
            p.color = stroke
            c.drawPath(path, p)
        }
        if (tick != 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeW * 2.5f
            p.color = tick
            c.drawLine(rf.left + cut, rf.top, rf.left, rf.top + cut, p)
            c.drawLine(rf.right, rf.bottom - cut, rf.right - cut, rf.bottom, p)
        }
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
