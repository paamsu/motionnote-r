package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** 배열 순서별 최소·최대 범위를 그린다. 빈 구간은 비워 두고 시간축을 만들지 않는다 */
class PowertrainSignalChart(
    context: Context,
    private val signal: PowertrainBatch.Signal,
    private val light: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val ink = PleosPalette.muted(light)
    private val accent = PleosPalette.action(light)
    private val valid = signal.values.filterNotNull()

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription =
            "${signal.label}, ${signal.unit}, 표본 ${signal.values.size}개 중 유효 ${valid.size}개. 표본 순서에 따른 범위이며 시간축이 아닙니다."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (valid.isEmpty()) return
        val low = valid.min()
        val high = valid.max()
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        val left = 70 * density
        val right = width - 12 * density
        val top = 18 * density
        val bottom = height - 32 * density
        if (right <= left || bottom <= top) return
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        paint.strokeWidth = density
        paint.color = ink
        fun number(value: Double) = String.format(Locale.KOREAN, "%.1f", value)
        canvas.drawText(number(high), 0f, top + paint.textSize, paint)
        canvas.drawText(number(low), 0f, bottom, paint)
        canvas.drawLine(left, top, left, bottom, paint)
        canvas.drawLine(left, bottom, right, bottom, paint)
        canvas.drawText("표본 순서 · 시간축 아님", left, height - 7 * density, paint)
        val bins = minOf(120, signal.values.size)
        paint.color = accent
        paint.strokeWidth = 2 * density
        for (bin in 0 until bins) {
            val first = bin * signal.values.size / bins
            val end = (bin + 1) * signal.values.size / bins
            val items = signal.values.subList(first, end)
            // 빠진 값을 사이에 두고 선을 잇거나 0으로 바꾸지 않는다
            if (items.any { it == null }) continue
            val values = items.filterNotNull()
            if (values.isEmpty()) continue
            val x = left + (bin + .5f) * (right - left) / bins
            fun y(value: Double) =
                if (high == low) (top + bottom) / 2 else (bottom - (value - low) / span * (bottom - top)).toFloat()

            val upper = y(values.max())
            val lower = y(values.min())
            if (kotlin.math.abs(upper - lower) < density) canvas.drawCircle(
                x,
                upper,
                2 * density,
                paint
            )
            else canvas.drawLine(x, upper, x, lower, paint)
        }
    }
}
