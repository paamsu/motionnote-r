package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.json.JSONArray

class ImpactTimelineView(context: Context, samples: JSONArray) : View(context) {
    private val points = (0 until samples.length()).map {
        samples.getJSONObject(it).optDouble("vertical_g", 0.0).toFloat()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return
        val mid = height * .65f
        paint.color = Color.parseColor("#454B55")
        paint.strokeWidth = 1f
        canvas.drawLine(0f, mid, width.toFloat(), mid, paint)
        paint.color = Color.parseColor("#A6CDFF")
        paint.strokeWidth = 2f
        val peak = points.maxOf { kotlin.math.abs(it) }.coerceAtLeast(.1f)
        for (i in 1 until points.size) canvas.drawLine(
            (i - 1) * width.toFloat() / (points.size - 1),
            mid - points[i - 1] / peak * height * .52f,
            i * width.toFloat() / (points.size - 1),
            mid - points[i] / peak * height * .52f,
            paint
        )
        paint.color = Color.parseColor("#F7B352")
        paint.strokeWidth = 2f
        canvas.drawLine(width / 3f, 0f, width / 3f, height.toFloat(), paint)
    }
}
