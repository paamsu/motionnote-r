package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** 같은 0 기준선 위에 두 값을 그린다. 색은 안전, 수리, 고장을 뜻하지 않는다 */
open class PairedObservationGraphic(
    context: Context,
    private val before: Double,
    private val after: Double,
    private val light: Boolean,
    private val uiScale: Float,
    private val unit: String,
    private val decimals: Int,
    private val beforeLabel: String,
    private val afterLabel: String,
    private val upper: Double?,
    description: String
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density * uiScale

    init {
        require(before.isFinite() && after.isFinite() && before >= 0 && after >= 0)
        require(upper == null || upper >= maxOf(before, after) && upper > 0)
        isFocusable = true
        contentDescription = description
    }

    private fun format(value: Double) = String.format(Locale.KOREAN, "%.${decimals}f", value)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 55 * d
        val right = width - 16 * d
        val top = 30 * d
        val bottom = height - 37 * d
        if (right <= left || bottom <= top) return
        val maximum = upper ?: maxOf(before, after, 1.0) * 1.15
        paint.color = PleosPalette.muted(light)
        paint.textSize = 13 * resources.displayMetrics.scaledDensity * uiScale
        paint.strokeWidth = d
        canvas.drawLine(left, bottom, right, bottom, paint)
        canvas.drawText("0 $unit", 0f, bottom, paint)
        val span = (right - left) / 2
        val barWidth = span * .47f
        for ((i, value) in listOf(before, after).withIndex()) {
            val x = left + span * (i + .5f)
            val y = (bottom - value / maximum * (bottom - top)).toFloat()
            paint.color = if (i == 0) PleosPalette.muted(light) else PleosPalette.action(light)
            canvas.drawRoundRect(x - barWidth / 2, y, x + barWidth / 2, bottom, 4 * d, 4 * d, paint)
            paint.color = PleosPalette.ink(light)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(format(value) + " $unit", x, y - 9 * d, paint)
            canvas.drawText(if (i == 0) beforeLabel else afterLabel, x, height - 12 * d, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }
}
